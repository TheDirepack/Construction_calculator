"""One labels JSON per document: ``<prefix>_labels.json`` in its label folder.

Every saved page of a PDF lives in this one file::

    {"format_version": 1, "kind": "document_labels", "json_prefix": "X",
     "proposals": {"path": ".../X_DEVICES.json", "sha256": "..."},
     "pages": {"4": {...page...}, "12": {...page...}}}

Each page entry has the annotator page format (``label_schema``); page keys
are 1-based PDF page numbers. Pages never saved are absent. ``proposals``
records which machine-proposal file (an extracted ``*_DEVICES.json``, SAM run)
the saved pages were last compared with, so an edited DEVICES file shows up in
the annotator (``web.LabelingSession._refresh_proposals``).

Writers (the browser annotator, labeling agents, model pre-labeling) change
one page at a time under an exclusive file lock, re-reading the file first,
so two processes never drop each other's pages. A page's revision is the hash
of its own entry, so editing one page never conflicts with another.

Folders from before this format held one ``<prefix>_pNNNN.json`` per page.
They are converted the first time the document is read; the old files are
moved into ``page_files_converted/``, not deleted.
"""
from __future__ import annotations

import copy
import fcntl
import hashlib
import json
import os
import re
import threading
from collections import OrderedDict
from contextlib import contextmanager
from pathlib import Path

FORMAT_VERSION = 2
KIND = "document_labels"
SUFFIX = "_labels.json"
CONVERTED_DIR = "page_files_converted"
LOCK_FILE = ".labels.lock"
_MAX_PARSED_CACHE = 8

_cache: "OrderedDict[str, tuple[int, int, dict]]" = OrderedDict()
_meta_cache: dict[str, tuple[int, int, bool, bool]] = {}
_no_legacy_cache: dict[tuple[str, str], int] = {}
_cache_lock = threading.Lock()


class StaleRevision(Exception):
    """The page changed since the caller read it."""


def labels_path(folder: Path, prefix: str) -> Path:
    return Path(folder) / f"{prefix}{SUFFIX}"


def page_revision(entry: dict | None) -> str | None:
    """Hash of one page entry (key order and whitespace do not matter)."""
    if entry is None:
        return None
    canonical = json.dumps(entry, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _empty(prefix: str) -> dict:
    return {"format_version": FORMAT_VERSION, "kind": KIND, "json_prefix": prefix, "pages": {}}


def _legacy_pages(folder: Path, prefix: str) -> dict[int, Path]:
    pattern = re.compile(rf"^{re.escape(prefix)}_p(\d{{4,5}})\.json$")
    out = {}
    for path in Path(folder).glob(f"{prefix}_p*.json"):
        match = pattern.match(path.name)
        if match and path.is_file():
            out[int(match.group(1))] = path
    return out


@contextmanager
def _locked(folder: Path):
    folder.mkdir(parents=True, exist_ok=True)
    with open(folder / LOCK_FILE, "a+b") as handle:
        fcntl.flock(handle, fcntl.LOCK_EX)
        try:
            yield
        finally:
            fcntl.flock(handle, fcntl.LOCK_UN)


def _meta_from_data(data: dict) -> tuple[bool, bool]:
    return bool(data["pages"]), bool((data.get("training_ready") or {}).get("ready"))


def _store_parsed_locked(key: str, size: int, mtime_ns: int, data: dict) -> None:
    has_pages, ready = _meta_from_data(data)
    _meta_cache[key] = (size, mtime_ns, has_pages, ready)
    _cache[key] = (size, mtime_ns, data)
    _cache.move_to_end(key)
    while len(_cache) > _MAX_PARSED_CACHE:
        _cache.popitem(last=False)


def _read_file(path: Path, prefix: str) -> dict:
    """The parsed labels file (shared, cached by size and mtime: do not mutate)."""
    try:
        stat = path.stat()
    except FileNotFoundError:
        return _empty(prefix)
    key = str(path)
    with _cache_lock:
        known = _cache.get(key)
        if known and known[:2] == (stat.st_size, stat.st_mtime_ns):
            _cache.move_to_end(key)
            return known[2]
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict) or data.get("kind") != KIND or not isinstance(data.get("pages"), dict):
        raise ValueError(f"{path} is not a document labels file")
    with _cache_lock:
        _store_parsed_locked(key, stat.st_size, stat.st_mtime_ns, data)
    return data


def _write_file(path: Path, data: dict) -> None:
    data["pages"] = {str(k): data["pages"][k] for k in sorted(data["pages"], key=int)}
    encoded = (json.dumps(data, ensure_ascii=False, indent=1) + "\n").encode("utf-8")
    tmp = path.with_name(f".{path.name}.{os.getpid()}.{threading.get_ident()}.tmp")
    with open(tmp, "wb") as handle:
        handle.write(encoded)
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(tmp, path)
    stat = path.stat()
    key = str(path)
    with _cache_lock:
        _store_parsed_locked(key, stat.st_size, stat.st_mtime_ns, data)


_FAST_SUMMARY_BYTES = 64 * 1024


def _fast_summary_bytes(raw: bytes) -> tuple[bool, bool] | None:
    """Extract ``(has_pages, is_training_ready)`` without decoding multi-MB page payloads."""
    head = raw[:512]
    if not head.lstrip().startswith(b"{") or not raw.rstrip().endswith(b"}"):
        return None
    if not re.search(rb'"kind"\s*:\s*"document_labels"', head):
        return None
    if re.search(rb'"pages"\s*:\s*\{\s*"\d+"\s*:', head):
        has_pages = True
    elif re.search(rb'"pages"\s*:\s*\{\s*\}', head):
        has_pages = False
    else:
        return None
    marker = b'\n "training_ready": '
    idx = raw.rfind(marker)
    if idx != -1:
        try:
            tail_text = raw[idx + len(marker):].decode("utf-8")
            val, _ = json.JSONDecoder().raw_decode(tail_text)
        except (UnicodeDecodeError, ValueError):
            return None
        if not isinstance(val, dict) or "ready" not in val:
            return None
        return has_pages, bool(val.get("ready"))
    if b'"training_ready"' in raw:
        return None
    return has_pages, False


def _file_summary(path: Path) -> tuple[bool, bool]:
    """Return ``(has_pages, is_training_ready)`` for ``path`` without pinning large JSONs in ``_cache``."""
    try:
        stat = path.stat()
    except OSError:
        return False, False
    key = str(path)
    with _cache_lock:
        cached_meta = _meta_cache.get(key)
        if cached_meta and cached_meta[:2] == (stat.st_size, stat.st_mtime_ns):
            return cached_meta[2], cached_meta[3]
        known = _cache.get(key)
        if known and known[:2] == (stat.st_size, stat.st_mtime_ns):
            has_pages, ready = _meta_from_data(known[2])
            _meta_cache[key] = (stat.st_size, stat.st_mtime_ns, has_pages, ready)
            return has_pages, ready
    summary = None
    if stat.st_size >= _FAST_SUMMARY_BYTES:
        raw = path.read_bytes()
        summary = _fast_summary_bytes(raw)
        if summary is None:
            data = json.loads(raw.decode("utf-8"))
            if not isinstance(data, dict) or data.get("kind") != KIND or not isinstance(data.get("pages"), dict):
                raise ValueError(f"{path} is not a document labels file")
            summary = _meta_from_data(data)
    else:
        data = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(data, dict) or data.get("kind") != KIND or not isinstance(data.get("pages"), dict):
            raise ValueError(f"{path} is not a document labels file")
        summary = _meta_from_data(data)
    has_pages, ready = summary
    with _cache_lock:
        _meta_cache[key] = (stat.st_size, stat.st_mtime_ns, has_pages, ready)
    return has_pages, ready


def _convert_locked(folder: Path, prefix: str) -> None:
    legacy = _legacy_pages(folder, prefix)
    if not legacy:
        return
    path = labels_path(folder, prefix)
    existing = _read_file(path, prefix)
    data = dict(existing)
    data["pages"] = dict(existing["pages"])
    for page, file in sorted(legacy.items()):
        # A page already in the labels file is newer than its old page file.
        data["pages"].setdefault(str(page), json.loads(file.read_text(encoding="utf-8")))
    _write_file(path, data)
    backup = folder / CONVERTED_DIR
    backup.mkdir(exist_ok=True)
    for file in legacy.values():
        target = backup / file.name
        if target.exists():
            target = backup / f"{file.stem}.{file.stat().st_mtime_ns}{file.suffix}"
        os.replace(file, target)
    with _cache_lock:
        _no_legacy_cache.pop((str(folder.resolve()), prefix), None)


def convert(folder: Path, prefix: str) -> int:
    """Move old per-page files into the labels file; returns how many were converted."""
    folder = Path(folder)
    try:
        folder_mtime = folder.stat().st_mtime_ns
    except OSError:
        return 0
    key = (str(folder.resolve()), prefix)
    with _cache_lock:
        if _no_legacy_cache.get(key) == folder_mtime:
            return 0
    count = len(_legacy_pages(folder, prefix))
    if count:
        with _locked(folder):
            _convert_locked(folder, prefix)
        return count
    with _cache_lock:
        _no_legacy_cache[key] = folder_mtime
    return 0


def read(folder: Path, prefix: str) -> dict:
    """The whole labels file (converted first if needed). Shared: do not mutate."""
    folder = Path(folder)
    convert(folder, prefix)
    return _read_file(labels_path(folder, prefix), prefix)


def pages(folder: Path, prefix: str) -> dict[int, dict]:
    """Saved pages by 1-based page number. Shared: do not mutate."""
    return {int(k): v for k, v in read(folder, prefix)["pages"].items()}


def get_page(folder: Path, prefix: str, page: int) -> tuple[dict | None, str | None]:
    """A copy of one saved page and its revision, or (None, None)."""
    entry = read(folder, prefix)["pages"].get(str(page))
    if entry is None:
        return None, None
    return copy.deepcopy(entry), page_revision(entry)


def proposals_record(folder: Path, prefix: str) -> dict | None:
    return read(folder, prefix).get("proposals")


def write_page(folder: Path, prefix: str, page: int, entry: dict, *,
               expected_revision: str | None | object = ...,
               proposals: dict | None = None) -> str:
    """Store one page; returns its new revision.

    With ``expected_revision`` the write is refused (``StaleRevision``) when
    the stored page is not that revision (None: not saved yet).
    ``proposals`` updates the document's proposal-source record.
    """
    folder = Path(folder)
    with _locked(folder):
        _convert_locked(folder, prefix)
        path = labels_path(folder, prefix)
        existing = _read_file(path, prefix)
        data = dict(existing)
        data["pages"] = dict(existing["pages"])
        current = data["pages"].get(str(page))
        if expected_revision is not ... and page_revision(current) != expected_revision:
            raise StaleRevision(f"page {page} changed since it was read")
        data["pages"][str(page)] = copy.deepcopy(entry)
        data["format_version"] = FORMAT_VERSION
        if proposals is not None:
            data["proposals"] = copy.deepcopy(proposals)
        _write_file(path, data)
    return page_revision(entry)


def set_proposals_record(folder: Path, prefix: str, record: dict) -> None:
    """Record the proposal source the saved pages were compared with."""
    folder = Path(folder)
    with _locked(folder):
        _convert_locked(folder, prefix)
        path = labels_path(folder, prefix)
        existing = _read_file(path, prefix)
        if existing.get("proposals") == record:
            return
        data = dict(existing)
        data["pages"] = dict(existing["pages"])
        data["proposals"] = copy.deepcopy(record)
        _write_file(path, data)


def write_page_view(folder: Path, prefix: str, page: int, entry: dict, dest_dir: Path,
                    **extra) -> Path:
    """A standalone per-page JSON of a saved page (for training and other
    page-file readers): ``dest_dir/<prefix>_pNNNN.json`` with an absolute
    image path. Generated, never edited; rewritten only when it changed."""
    path = Path(dest_dir) / f"{prefix}_p{page:04d}.json"
    view = dict(entry, image_path=str((Path(folder) / str(entry.get("image_path", ""))).resolve()),
                **extra)
    encoded = (json.dumps(view, ensure_ascii=False, indent=1) + "\n").encode("utf-8")
    if not path.is_file() or path.read_bytes() != encoded:
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_name(f".{path.name}.tmp")
        tmp.write_bytes(encoded)
        os.replace(tmp, path)
    return path


def set_document_info(folder: Path, prefix: str, info: dict) -> None:
    """Set document-level sections (``document``, ``sources``, ``extracted``,
    ...) of the labels file; pages are untouched."""
    folder = Path(folder)
    with _locked(folder):
        _convert_locked(folder, prefix)
        path = labels_path(folder, prefix)
        existing = _read_file(path, prefix)
        data = dict(existing)
        for k, v in info.items():
            if k != "pages":
                data[k] = copy.deepcopy(v)
        data["format_version"] = FORMAT_VERSION
        if data != existing:
            data["pages"] = dict(existing["pages"])
            _write_file(path, data)


def training_ready(folder: Path, prefix: str) -> bool:
    """Whether a person marked this PDF done for now: training uses only
    such PDFs (``label_store.labeled_pages``, ``label_schema.expand_page_paths``)."""
    folder = Path(folder)
    try:
        convert(folder, prefix)
        return _file_summary(labels_path(folder, prefix))[1]
    except (OSError, ValueError):
        return False


def set_training_ready(folder: Path, prefix: str, ready: bool, *, by: str = "annotator") -> dict:
    import time
    record = {"ready": bool(ready), "set_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
              "set_by": by}
    set_document_info(folder, prefix, {"training_ready": record})
    return record


def has_labels(folder: Path) -> bool:
    """Whether a document folder has saved pages (labels file or old page files)."""
    folder = Path(folder)
    for path in folder.glob(f"*{SUFFIX}"):
        try:
            if _file_summary(path)[0]:
                return True
        except (OSError, ValueError):
            continue
    return next(folder.glob("*_p[0-9][0-9][0-9][0-9]*.json"), None) is not None
