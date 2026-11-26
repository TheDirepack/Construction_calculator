"""The label store: one folder per source document, labeled straight from the PDF.

A drawing's labels live in the ``devices`` folder beside it, next to its
extracted ``*_DEVICES.json``. The store is the ``1_blueprints`` folder::

    1_blueprints/X.pdf                                   training drawing
    1_blueprints/devices/X_DEVICES.json                  its extracted device boxes
    1_blueprints/devices/X_<sha12>/                      its labels (below)
    1_blueprints/evaluation/Y.pdf                        evaluation drawing
    1_blueprints/evaluation/devices/Y_<sha12>/           its labels
    1_blueprints/devices/batches/batch_NN.txt            agent batches (document folders)
    1_blueprints/devices/scanned_pdfs.json               PDFs triaged without electrical pages

Each document folder holds::

    document.json          source, hash, page count, pages to label
    <prefix>_p0012.json    annotation page (web annotator --pdf format)
    images/                pages rendered on demand from the PDF
    legend/                per-document legend crops (agents)
    marks/                 numbered candidate cache (agents)
    survey.json            what an agent learned about the set

Sources outside the two drawing folders (pool photos) go to
``1_blueprints/devices/``. ``$BPS_LABELS_DIR`` (or any ``--store`` that is not
a ``1_blueprints`` folder) is a flat store instead: ``<store>/<stem>_<sha12>/``
and ``<store>/batches``. The annotator, agents, model pre-labeling and
training all resolve folders through this module, so they read and write the
same page files. Page JSONs are named by 1-based PDF page number.

Photos (no PDF) are ``kind: "image"`` documents: one page whose JSON points at
the photo kept in the document's ``images/`` folder.
"""
from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass, field
from pathlib import Path

from annotator.web import LabelingSession, sha256_file
from blueprint_symbols.label_schema import COORDINATE_SYSTEM, page_header

DOCUMENT_FILE = "document.json"
BATCH_DIR = "batches"
LABELS_DIR = "devices"                # beside each drawing, with its DEVICES.json
EVALUATION_DIR = "evaluation"
PAGE_NAME = re.compile(r"_p(\d{4,5})\.json$")
SAFE = re.compile(r"[^a-zA-Z0-9_-]+")


def default_store(project: Path | None = None) -> Path:
    """``$BPS_LABELS_DIR`` if set, else the ``1_blueprints`` folder: the
    training machine's ``data/blueprints_and_libraries/1_blueprints`` under the
    working directory, or this checkout's ``blueprints_and_libraries/1_blueprints``."""
    env = os.environ.get("BPS_LABELS_DIR")
    if env:
        return Path(env).expanduser().resolve()
    from annotator.web import DEFAULT_BLUEPRINTS_DIR
    base = Path(project) if project else Path.cwd()
    for candidate in (base / "data" / "blueprints_and_libraries" / "1_blueprints",
                      DEFAULT_BLUEPRINTS_DIR):
        if candidate.is_dir():
            return candidate.resolve()
    return DEFAULT_BLUEPRINTS_DIR.resolve()


def is_blueprints_root(store: Path) -> bool:
    """A ``1_blueprints`` folder (labels in its ``devices`` folders), as
    opposed to a flat store of document folders."""
    store = Path(store)
    return (store.name == "1_blueprints" or (store / LABELS_DIR).is_dir()
            or (store / EVALUATION_DIR).is_dir())


def store_roots(store: Path) -> list[Path]:
    """Folders whose subfolders are documents: both ``devices`` folders of a
    ``1_blueprints`` store, or the flat store itself."""
    store = Path(store).resolve()
    if is_blueprints_root(store):
        return [store / LABELS_DIR, store / EVALUATION_DIR / LABELS_DIR]
    return [store]


def state_dir(store: Path) -> Path:
    """Where store-wide files (batches, scan memory) go."""
    store = Path(store).resolve()
    return store / LABELS_DIR if is_blueprints_root(store) else store


def _drawing_label_dirs(source: Path) -> list[Path]:
    """The ``devices`` folder beside a drawing first, then the other drawing
    folder's (a PDF moved between training and evaluation keeps its labels)."""
    parent = Path(source).resolve().parent
    root = parent.parent if parent.name == EVALUATION_DIR else parent
    dirs = [parent / LABELS_DIR, root / LABELS_DIR, root / EVALUATION_DIR / LABELS_DIR]
    return list(dict.fromkeys(dirs))


def labels_folder_beside(source: Path, sha256: str) -> Path:
    """A drawing's label folder in the ``devices`` folder beside it, or its
    existing folder in the other drawing folder's ``devices`` after a move."""
    name = doc_folder_name(source, sha256)
    dirs = _drawing_label_dirs(source)
    for folder in (d / name for d in dirs):
        if folder.is_dir():
            return folder
    return dirs[0] / name


def document_folder(store: Path, source: Path, sha256: str) -> Path:
    """Where a source's labels live in ``store`` (existing folder first)."""
    store, source = Path(store).resolve(), Path(source).resolve()
    if not is_blueprints_root(store):
        return store / doc_folder_name(source, sha256)
    if source.parent in (store, store / EVALUATION_DIR):
        return labels_folder_beside(source, sha256)
    return store / LABELS_DIR / doc_folder_name(source, sha256)


def doc_folder_name(source: Path, sha256: str) -> str:
    """Same as ``web.default_out_dir``: ``<sanitized stem[:48]>_<sha12>``."""
    return f"{SAFE.sub('_', Path(source).stem)[:48]}_{sha256[:12]}"


def json_prefix(source: Path) -> str:
    return SAFE.sub("_", Path(source).stem)[:48].strip("_") or "page"


def parse_pages(raw: str | None, page_count: int) -> list[int]:
    """``"3, 7-9"`` -> [3, 7, 8, 9] (1-based PDF pages, checked against the document)."""
    pages: set[int] = set()
    for part in (raw or "").replace(";", ",").split(","):
        part = part.strip()
        if not part:
            continue
        lo, sep, hi = part.partition("-")
        if sep and lo.strip().isdigit() and hi.strip().isdigit():
            pages.update(range(int(lo), int(hi) + 1))
        elif part.isdigit():
            pages.add(int(part))
        else:
            raise ValueError(f"bad page {part!r}: use PDF page numbers (1-based) like 7 or 7-9, "
                             "not sheet ids")
    bad = [p for p in sorted(pages) if not 1 <= p <= page_count]
    if bad:
        raise ValueError(f"pages {bad[:5]} are outside this document (1-{page_count})")
    return sorted(pages)


# --------------------------------------------------------------------------
# training vs evaluation and DEVICES files: read from the blueprint folders at
# runtime (blueprint_symbols.blueprint_sets)
# --------------------------------------------------------------------------

def devices_json_for(pdf: Path) -> Path | None:
    """The extracted *_DEVICES.json of a drawing, from the ``devices`` folder beside it."""
    from blueprint_symbols.blueprint_sets import devices_for
    return devices_for(pdf)


def document_role(doc: "Document", data_root: Path | None = None) -> str | None:
    """``train``, ``evaluation`` or None for a stored document, decided by
    where its PDF is now: ``1_blueprints/*.pdf`` trains,
    ``1_blueprints/evaluation/*.pdf`` evaluates, anything else neither."""
    from blueprint_symbols.blueprint_sets import blueprint_sets
    try:
        sets = blueprint_sets(data_root if data_root is not None else doc.source)
    except FileNotFoundError:
        return None
    return sets.role(doc.source)


def is_evaluation(source: Path, data_root: Path | None = None) -> bool:
    """Whether a drawing is in the evaluation folder (never trained on)."""
    from blueprint_symbols.blueprint_sets import EVALUATION, blueprint_sets
    try:
        return blueprint_sets(data_root if data_root is not None else source).role(source) == EVALUATION
    except FileNotFoundError:
        return False


@dataclass
class Document:
    folder: Path
    kind: str                       # "pdf" | "image"
    source: Path                    # the PDF, or the photo in images/
    sha256: str
    page_count: int
    json_prefix: str
    targets: dict[int, str] = field(default_factory=dict)   # page -> why it should be labeled
    meta: dict = field(default_factory=dict)

    @property
    def name(self) -> str:
        return str(self.meta.get("source_rel") or self.source.name)

    def page_json(self, page: int) -> Path:
        return self.folder / f"{self.json_prefix}_p{page:04d}.json"

    @property
    def survey_path(self) -> Path:
        return self.folder / "survey.json"

    def survey(self) -> dict | None:
        path = self.survey_path
        return json.loads(path.read_text(encoding="utf-8")) if path.is_file() else None

    def pages_to_label(self) -> list[int]:
        """Triage pages plus pages added in the survey, minus pages the survey skipped."""
        pages = set(self.targets)
        survey = self.survey() or {}
        pages.update(int(p) for p in survey.get("label_pages", []) if 1 <= int(p) <= self.page_count)
        pages.difference_update(int(p) for p in survey.get("skip_pages", []))
        return sorted(pages)

    def save_meta(self) -> None:
        data = dict(self.meta)
        stat = self.source.stat()
        data.update({"format_version": 1, "kind": self.kind, "source": str(self.source),
                     "sha256": self.sha256, "page_count": self.page_count,
                     "json_prefix": self.json_prefix, "size": stat.st_size,
                     "mtime_ns": stat.st_mtime_ns,
                     "targets": {str(k): v for k, v in sorted(self.targets.items())}})
        self.folder.mkdir(parents=True, exist_ok=True)
        tmp = self.folder / (DOCUMENT_FILE + ".tmp")
        tmp.write_text(json.dumps(data, indent=1), encoding="utf-8")
        tmp.replace(self.folder / DOCUMENT_FILE)
        self.meta = data


def load_document(folder: Path) -> Document:
    folder = Path(folder).resolve()
    path = folder / DOCUMENT_FILE
    if not path.is_file():
        raise FileNotFoundError(f"{folder} is not a label-store document (no {DOCUMENT_FILE})")
    data = json.loads(path.read_text(encoding="utf-8"))
    source = Path(data["source"])
    if not source.is_absolute():
        source = (folder / source).resolve()
    moved = not source.is_file()
    if moved:
        source = _moved_source(source, data.get("sha256"))
        if source is None:
            raise FileNotFoundError(f"source of {folder.name} is missing: {data['source']}")
    doc = Document(folder=folder, kind=data["kind"], source=source, sha256=data["sha256"],
                   page_count=int(data["page_count"]), json_prefix=data["json_prefix"],
                   targets={int(k): v for k, v in (data.get("targets") or {}).items()}, meta=data)
    stat = source.stat()
    if (stat.st_size, stat.st_mtime_ns) != (data.get("size"), data.get("mtime_ns")):
        current = sha256_file(source)
        if current != doc.sha256:
            raise ValueError(f"{source.name} changed since its labels were started "
                             f"(sha {current[:12]} != {doc.sha256[:12]})")
        doc.save_meta()                     # same bytes, new mtime: refresh the recorded stat
    elif moved:
        if "evaluation" in doc.meta:        # shown by the annotator
            doc.meta["evaluation"] = is_evaluation(source)
        doc.save_meta()                     # remember where the PDF is now
    return doc


def _moved_source(old: Path, sha: str | None) -> Path | None:
    """A drawing moved between the blueprint folders (e.g. into evaluation/):
    the PDF of the same name in 1_blueprints or 1_blueprints/evaluation,
    if its bytes are the same."""
    from blueprint_symbols.blueprint_sets import blueprint_sets
    try:
        sets = blueprint_sets(old.parent)
    except FileNotFoundError:
        return None
    name = old.name.casefold()
    for candidate in (sets.evaluation.get(name), sets.train.get(name)):
        if candidate is not None and (sha is None or sha256_file(candidate) == sha):
            return candidate.resolve()
    return None


def document_for(page_json: Path) -> Document:
    return load_document(Path(page_json).resolve().parent)


def page_number(page_json: Path) -> int:
    match = PAGE_NAME.search(Path(page_json).name)
    if not match:
        raise ValueError(f"{Path(page_json).name} is not a label-store page "
                         "(expected <name>_pNNNN.json as printed by next/doc)")
    return int(match.group(1))


def open_page(page_json: Path) -> tuple[LabelingSession, int, Document]:
    """A session on one page of a store document, and that page's session index.

    A PDF page's JSON need not exist yet: it is created on the first save.
    """
    page_json = Path(page_json).resolve()
    doc = document_for(page_json)
    page = page_number(page_json)
    if not 1 <= page <= doc.page_count:
        raise ValueError(f"page {page} is outside {doc.name} (1-{doc.page_count})")
    if page_json != doc.page_json(page):
        raise ValueError(f"this document's page {page} is {doc.page_json(page).name}")
    if doc.kind == "image":
        return LabelingSession(edit=page_json), 0, doc
    # Same session as the browser annotator: a page opened for the first time
    # starts with the same machine proposals (e.g. extracted DEVICES.json boxes).
    session = LabelingSession(pdf=doc.source, out_dir=doc.folder, page=page - 1,
                              source_sha256=doc.sha256)
    return session, page - 1, doc


def page_status(doc: Document, page: int) -> dict:
    path = doc.page_json(page)
    row = {"pdf_page": page, "page": str(path), "assessed": False, "boxes": 0, "agent_boxes": 0,
           "model_boxes": 0, "human_verified": 0, "symbols_present": None, "page_kind": None}
    if path.is_file():
        data = json.loads(path.read_text(encoding="utf-8"))
        assessment = data.get("vlm_assessment") or {}
        instances = data.get("instances") or []
        row.update({
            "assessed": bool(assessment),
            "boxes": len(instances),
            "agent_boxes": sum(1 for i in instances if i.get("review_source") == "vlm_agent"),
            "model_boxes": sum(1 for i in instances if i.get("review_source") == "model"),
            "human_verified": sum(1 for i in instances if i.get("verified_ground_truth")),
            "symbols_present": assessment.get("symbols_present"),
            "page_kind": assessment.get("page_kind"),
        })
    return row


def iter_documents(store: Path, *, skip_missing: bool = False):
    """Documents of a store. ``skip_missing`` leaves out documents whose PDF
    is in neither blueprint folder any more (deleted or moved elsewhere)."""
    metas = [m for root in store_roots(store) for m in root.glob(f"*/{DOCUMENT_FILE}")]
    for meta in sorted(metas, key=lambda m: (m.parent.name, str(m))):
        try:
            yield load_document(meta.parent)
        except FileNotFoundError:
            if not skip_missing:
                raise


def batch_documents(batch: Path) -> list[Path]:
    """Document folders listed in a batch file. A relative line (a folder
    name) is looked up in the store's document folders."""
    batch = Path(batch).resolve()
    base = batch.parent.parent if batch.parent.name == BATCH_DIR else batch.parent
    roots = store_roots(base.parent) if base.name == LABELS_DIR else [base]
    out = []
    for line in batch.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            path = Path(line)
            if not path.is_absolute():
                path = next((r / path for r in roots if (r / path).is_dir()), roots[0] / path)
            out.append(path.resolve())
    return out


def labeled_pages(store: Path, *, data_root: Path | None = None) -> dict[str, list[Path]]:
    """Usable saved pages split by where their PDF is now:
    ``{"train": [...], "evaluation": [...]}`` (``document_role``).

    Labeling covers every drawing; this is where evaluation drawings are kept
    out of training. Pages of PDFs in neither folder are left out of both.
    """
    out: dict[str, list[Path]] = {"train": [], "evaluation": []}
    roles: dict[Path, str | None] = {}
    for page in _usable_pages(store):
        doc = document_for(page)
        if doc.folder not in roles:
            roles[doc.folder] = document_role(doc, data_root)
        if roles[doc.folder]:
            out[roles[doc.folder]].append(page)
    return out


def training_pages(store: Path, *, data_root: Path | None = None) -> list[Path]:
    """Usable pages of training drawings (``labeled_pages``)."""
    return labeled_pages(store, data_root=data_root)["train"]


def _usable_pages(store: Path) -> list[Path]:
    """Saved pages of every document that training or evaluation can use (see
    ``label_schema.training_usable``), with their page image on disk.

    A page image missing on this machine (labeled elsewhere, cache cleared) is
    rendered again from the PDF; the session checks it against the saved hash.
    """
    from blueprint_symbols.label_schema import training_usable
    out = []
    for doc in iter_documents(store, skip_missing=True):
        for path in sorted(doc.folder.glob(f"{doc.json_prefix}_p[0-9]*.json")):
            data = json.loads(path.read_text(encoding="utf-8"))
            if not training_usable(data):
                continue
            if not (path.parent / str(data.get("image_path", ""))).is_file():
                session, index, _ = open_page(path)
                session.page_document(index)
            out.append(path)
    return out


IMAGE_PAGE_FIELDS = ("format_version", "source_id", "source_license", "project_family_id",
                     "source_group_verified", "document_id", "page_index", "source_type",
                     "exhaustive_families", "verified_no_symbols", "annotation_completeness",
                     "annotation_completeness_verified", "annotation_scope", "instances",
                     "rejected_proposals", "vlm_assessment")


def create_document(store: Path, source: Path, *, targets: dict[int, str] | None = None,
                    meta: dict | None = None, image_page: dict | None = None,
                    sha256: str | None = None) -> Document:
    """Add a PDF or a photo to the store (or merge triage targets into an existing entry).

    A PDF is referenced in place. A photo is copied into the document's
    ``images/`` folder (photos have no other durable home) and gets a single
    edit-mode page JSON; ``image_page`` seeds that JSON's metadata and labels.
    """
    from PIL import Image

    store = Path(store).resolve()
    source = Path(source).resolve()
    sha = sha256 or sha256_file(source)
    folder = document_folder(store, source, sha)
    if (folder / DOCUMENT_FILE).is_file():
        doc = load_document(folder)
        for page, reason in (targets or {}).items():
            doc.targets.setdefault(int(page), reason)
        doc.meta.update({k: v for k, v in (meta or {}).items() if k not in doc.meta})
        doc.save_meta()
        return doc
    folder.mkdir(parents=True, exist_ok=True)
    if source.suffix.lower() == ".pdf":
        import fitz  # type: ignore
        with fitz.open(source) as pdf:
            page_count = len(pdf)
        doc = Document(folder=folder, kind="pdf", source=source, sha256=sha, page_count=page_count,
                       json_prefix=json_prefix(source),
                       targets={int(k): v for k, v in (targets or {}).items()}, meta=dict(meta or {}))
        doc.save_meta()
        return doc
    images = folder / "images"
    images.mkdir(exist_ok=True)
    copy = images / f"{json_prefix(source)}{source.suffix.lower()}"
    copy.write_bytes(source.read_bytes())
    with Image.open(copy) as image:
        width, height = image.size
    doc = Document(folder=folder, kind="image", source=copy, sha256=sha, page_count=1,
                   json_prefix=json_prefix(source), targets={1: (targets or {}).get(1, "photo")},
                   meta=dict(meta or {}))
    page = page_header(source=source, document_id=source.stem, page_index=0,
                       source_type="real_construction_photo", image_path=f"images/{copy.name}",
                       image_sha256=sha, width=width, height=height)
    page.update({k: v for k, v in (image_page or {}).items() if k in IMAGE_PAGE_FIELDS})
    page.update({"image_path": f"images/{copy.name}", "image_sha256": sha,
                 "image_width": width, "image_height": height,
                 "coordinate_system": COORDINATE_SYSTEM})
    doc.page_json(1).write_text(json.dumps(page, indent=1), encoding="utf-8")
    doc.save_meta()
    return doc
