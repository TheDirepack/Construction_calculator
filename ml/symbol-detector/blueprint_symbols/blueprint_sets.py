"""Which drawings train and which evaluate, read from the folders at runtime.

    blueprints_and_libraries/1_blueprints/*.pdf              training drawings
    blueprints_and_libraries/1_blueprints/devices/*.json     their extracted DEVICES files
    blueprints_and_libraries/1_blueprints/evaluation/*.pdf   evaluation drawings (never trained on)
    blueprints_and_libraries/1_blueprints/evaluation/devices/*.json

Nothing is indexed or cached on disk: moving a PDF between the folders moves
it between the sets the next time anything runs. PDFs in other subfolders of
``1_blueprints`` belong to neither set. A file name in both folders counts as
evaluation.

Labeling covers both sets; only training keeps evaluation drawings out.
"""
from __future__ import annotations

import json
import time
from dataclasses import dataclass
from pathlib import Path

BLUEPRINTS_DIR = "1_blueprints"
EVALUATION_DIR = "evaluation"
DEVICES_DIR = "devices"
TRAIN, EVALUATION = "train", "evaluation"


def blueprints_dir(path: str | Path) -> Path:
    """The ``1_blueprints`` folder of a data root, of itself, or above a path inside it."""
    path = Path(path).expanduser().resolve()
    if path.name == BLUEPRINTS_DIR:
        return path
    if (path / BLUEPRINTS_DIR).is_dir():
        return path / BLUEPRINTS_DIR
    for parent in path.parents:
        if parent.name == BLUEPRINTS_DIR:
            return parent
    raise FileNotFoundError(f"no {BLUEPRINTS_DIR} folder at or above {path}")


def _pdfs(folder: Path) -> dict[str, Path]:
    if not folder.is_dir():
        return {}
    return {p.name.casefold(): p for p in sorted(folder.iterdir())
            if p.is_file() and p.suffix.lower() == ".pdf"}


@dataclass(frozen=True)
class BlueprintSets:
    root: Path                       # the 1_blueprints folder
    train: dict[str, Path]           # casefolded file name -> PDF
    evaluation: dict[str, Path]

    def role(self, pdf: str | Path) -> str | None:
        """``train``, ``evaluation`` or None (in neither folder), by file name."""
        name = Path(str(pdf)).name.casefold()
        if name in self.evaluation:
            return EVALUATION
        if name in self.train:
            return TRAIN
        return None

    def pdfs(self, role: str) -> list[Path]:
        return sorted((self.train if role == TRAIN else self.evaluation).values())

    def training_pdfs(self) -> list[Path]:
        """Training drawings, minus any name that is also an evaluation drawing."""
        return [p for k, p in sorted(self.train.items()) if k not in self.evaluation]


def blueprint_sets(path: str | Path) -> BlueprintSets:
    """Scan the folders now (a data root, ``1_blueprints`` or a path inside it)."""
    root = blueprints_dir(path)
    return BlueprintSets(root=root, train=_pdfs(root), evaluation=_pdfs(root / EVALUATION_DIR))


# --------------------------------------------------------------------------- DEVICES files

DEVICES_PATTERNS = ("*_DEVICES.json", "*_devices.json")
_DEVICES_CACHE: dict[str, tuple[float, dict[str, Path]]] = {}
_DEVICES_TTL_S = 60.0         # a long labeling session sees new files within a minute


def devices_files(folder: str | Path) -> dict[str, Path]:
    """PDF file name (casefolded) -> DEVICES file, for the files directly in
    ``folder`` (a ``devices`` folder). Matched by the ``source_file`` recorded
    inside, since DEVICES files are named after the drawing set."""
    folder = Path(folder).resolve()
    cached = _DEVICES_CACHE.get(str(folder))
    if cached is not None and time.monotonic() - cached[0] < _DEVICES_TTL_S:
        return cached[1]
    found: dict[str, Path] = {}
    files = sorted({p for pattern in DEVICES_PATTERNS for p in folder.glob(pattern)}) if folder.is_dir() else []
    for path in files:
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if not isinstance(data, dict) or not data.get("devices"):
            continue
        source = str(data.get("source_file") or data.get("source_pdf") or "")
        if source:
            found.setdefault(Path(source).name.casefold(), path)
    _DEVICES_CACHE[str(folder)] = (time.monotonic(), found)
    return found


def devices_for(pdf: str | Path) -> Path | None:
    """The DEVICES file of a drawing: from the ``devices`` folder beside it."""
    pdf = Path(pdf)
    return devices_files(pdf.parent / DEVICES_DIR).get(pdf.name.casefold())


# --------------------------------------------------------------------------- PDF pages

def page_hash(doc, page, stream_digests: dict[int, bytes] | None = None) -> str:
    """Fingerprint of a PDF page's own content: page size and rotation, its
    drawing instructions, and the bytes of every image and form XObject it
    uses. The same sheet bound into several PDFs (re-issued sets, the same
    download twice, standard detail sheets) hashes the same; any change in
    content does not. Nothing is rendered."""
    import hashlib

    stream_digests = {} if stream_digests is None else stream_digests

    def stream(xref: int) -> bytes:
        if xref not in stream_digests:
            try:
                raw = doc.xref_stream_raw(xref) or b""
            except Exception:
                raw = b""
            stream_digests[xref] = hashlib.sha256(raw).digest()
        return stream_digests[xref]

    h = hashlib.sha256()
    box = page.rect
    h.update(f"{box.width:.1f}x{box.height:.1f}r{page.rotation}|".encode())
    h.update(page.read_contents() or b"")
    for item in sorted(page.get_xobjects(), key=lambda x: x[0]):
        h.update(b"X" + stream(item[0]))
    for item in sorted(page.get_images(full=True), key=lambda x: x[0]):
        h.update(b"I" + stream(item[0]))
    return h.hexdigest()[:32]


def pdf_page_entries(pdf: Path, *, group: str | None = None) -> list[dict]:
    """Drawable pages of a PDF, for rendering crops on demand (no rasters kept).
    Duplicate pages are skipped lazily when sampled (page_hash_cache)."""
    import pymupdf

    from .label_schema import source_group

    pdf = Path(pdf).resolve()
    group = group or source_group(pdf)[0]
    st = pdf.stat()
    signature = [st.st_size, st.st_mtime_ns]
    pages = []
    with pymupdf.open(pdf) as doc:
        for index, page in enumerate(doc):
            box = page.rect
            if box.is_empty or box.width < 72 or box.height < 72 or not page.get_contents():
                continue
            pages.append({
                "image_path": str(pdf), "source_pdf": str(pdf), "pdf_page_index": index,
                "pdf_width_pt": float(box.width), "pdf_height_pt": float(box.height),
                "project_family_id": group, "source_group_verified": True,
                "pdf_source_signature": signature,
            })
    return pages
