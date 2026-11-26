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
from contextlib import contextmanager
from pathlib import Path

FORMAT_VERSION = 1
KIND = "document_labels"
SUFFIX = "_labels.json"
CONVERTED_DIR = "page_files_converted"
LOCK_FILE = ".labels.lock"

_cache: dict[str, tuple[int, int, dict]] = {}
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


def _read_file(path: Path, prefix: str) -> dict:
    """The parsed labels file (shared, cached by size and mtime: do not mutate)."""
    try:
        stat = path.stat()
    except FileNotFoundError:
        return _empty(prefix)
    with _cache_lock:
        known = _cache.get(str(path))
        if known and known[:2] == (stat.st_size, stat.st_mtime_ns):
            return known[2]
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict) or data.get("kind") != KIND or not isinstance(data.get("pages"), dict):
        raise ValueError(f"{path} is not a document labels file")
    with _cache_lock:
        _cache[str(path)] = (stat.st_size, stat.st_mtime_ns, data)
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
    with _cache_lock:
        _cache[str(path)] = (stat.st_size, stat.st_mtime_ns, data)


def _convert_locked(folder: Path, prefix: str) -> None:
    legacy = _legacy_pages(folder, prefix)
    if not legacy:
        return
    path = labels_path(folder, prefix)
    data = copy.deepcopy(_read_file(path, prefix))
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


def convert(folder: Path, prefix: str) -> int:
    """Move old per-page files into the labels file; returns how many were converted."""
    folder = Path(folder)
    count = len(_legacy_pages(folder, prefix))
    if count:
        with _locked(folder):
            _convert_locked(folder, prefix)
    return count


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
        data = copy.deepcopy(_read_file(path, prefix))
        current = data["pages"].get(str(page))
        if expected_revision is not ... and page_revision(current) != expected_revision:
            raise StaleRevision(f"page {page} changed since it was read")
        data["pages"][str(page)] = entry
        if proposals is not None:
            data["proposals"] = proposals
        _write_file(path, data)
    return page_revision(entry)


def set_proposals_record(folder: Path, prefix: str, record: dict) -> None:
    """Record the proposal source the saved pages were compared with."""
    folder = Path(folder)
    with _locked(folder):
        _convert_locked(folder, prefix)
        path = labels_path(folder, prefix)
        data = copy.deepcopy(_read_file(path, prefix))
        if data.get("proposals") == record:
            return
        data["proposals"] = record
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


def has_labels(folder: Path) -> bool:
    """Whether a document folder has saved pages (labels file or old page files)."""
    folder = Path(folder)
    for path in folder.glob(f"*{SUFFIX}"):
        try:
            if _read_file(path, path.name[:-len(SUFFIX)])["pages"]:
                return True
        except (OSError, ValueError):
            continue
    return next(folder.glob("*_p[0-9][0-9][0-9][0-9]*.json"), None) is not None
