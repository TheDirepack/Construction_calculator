"""Document-level helpers for vision-model labeling agents.

``agent_api`` works on one page. Labeling a drawing set well needs the whole
document: the sheet index tells you which sheets are electrical, the legend
sheet tells you what each glyph means in *this* set, and only then do the
plan sheets make sense. A vision model is also poor at pixel-exact boxes but
good at choosing between numbered candidates. This module supplies:

* **PDF text access** for label-store documents (``annotator.label_store``):
  sheet numbers and titles, legend/index detection, and words with
  page-pixel boxes;
* **marks**: numbered candidate boxes (ink blobs with walls and leaders
  removed; on PDF pages, blobs under PDF text are dropped exactly) cached per
  page, so an agent can write ``"mark": "m12"`` instead of estimating
  coordinates;
* **tightening**: shrink a rough box onto the ink of the glyph it covers;
* a per-document **legend library** of reference crops that later views can
  show beside the region being labeled.

Every function here is read-only on the label JSONs. Writes still go through
``agent_api.apply_actions`` -> ``LabelingSession.save``.
"""
from __future__ import annotations

import json
import math
import re
from functools import lru_cache
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

LEGEND_DIR = "legend"
MARKS_DIR = "marks"
KEYWORDS = {
    "legend": ("LEGEND", "SYMBOL LIST", "SYMBOLS", "SYMBOL SCHEDULE", "ELECTRICAL SYMBOLS"),
    "index": ("SHEET INDEX", "DRAWING INDEX", "SHEET LIST", "INDEX OF DRAWINGS", "LIST OF DRAWINGS",
              "DRAWING LIST"),
    "abbreviations": ("ABBREVIATIONS",),
    "schedule": ("PANEL SCHEDULE", "LIGHTING FIXTURE SCHEDULE", "LUMINAIRE SCHEDULE", "FIXTURE SCHEDULE"),
    "electrical": ("ELECTRICAL", "LIGHTING PLAN", "POWER PLAN", "FIRE ALARM", "RECEPTACLE", "PANELBOARD"),
    "other_discipline": ("PLUMBING", "STRUCTURAL", "MECHANICAL", "CIVIL", "LANDSCAPE", "ARCHITECTURAL",
                         "FOUNDATION", "FRAMING"),
}
SHEET_ID = re.compile(r"^[A-Z]{1,3}[-.]?\d{1,3}(?:\.\d{1,3})?[A-Z]?$")
Box = list  # [x0, y0, x1, y1] in page pixels


# --------------------------------------------------------------------------
# source PDFs: text, sheet titles, vectors
# --------------------------------------------------------------------------

def _norm_name(name: str) -> str:
    """Filename key that survives the flattening done when PDFs were filed
    (spaces/plus/punctuation -> underscores, case folded)."""
    stem = Path(name).name
    if stem.lower().endswith(".pdf"):
        stem = stem[:-4]
    return re.sub(r"[^a-z0-9]+", "_", stem.lower()).strip("_")


@lru_cache(maxsize=8)
def _pdf_lookup(roots: tuple[str, ...]) -> dict[str, str]:
    table: dict[str, str] = {}
    for root in roots:
        for path in Path(root).rglob("*"):
            if path.suffix.lower() == ".pdf" and path.is_file():
                table.setdefault(_norm_name(path.name), str(path))
    return table


def find_pdf(roots: list[Path], source_path: str) -> Path | None:
    """Locate a source PDF under ``roots`` by path, then by normalised filename."""
    if not str(source_path).lower().endswith(".pdf"):
        return None
    roots = [Path(r) for r in roots if Path(r).is_dir()]
    name = Path(source_path).name
    for root in roots:
        for candidate in (root / source_path, root / name):
            if candidate.is_file():
                return candidate
    hit = _pdf_lookup(tuple(str(r) for r in roots)).get(_norm_name(name))
    return Path(hit) if hit else None


def _fitz():
    import fitz  # type: ignore
    return fitz


def _page_matrix(page, width: int, height: int):
    """Matrix from PDF user space to the rendered page image's pixels."""
    fitz = _fitz()
    rotated = page.rect * page.rotation_matrix
    zoom_x = width / max(rotated.width, 1e-6)
    zoom_y = height / max(rotated.height, 1e-6)
    return page.rotation_matrix * fitz.Matrix(zoom_x, zoom_y)


def _keywords(text: str) -> list[str]:
    upper = text.upper()
    return [kind for kind, words in KEYWORDS.items() if any(w in upper for w in words)]


def _title_block(page) -> dict:
    """Sheet id and title from the largest text in the lower-right title block."""
    rect = page.rect * page.rotation_matrix
    info = page.get_text("dict")
    spans = []
    for block in info.get("blocks", []):
        for line in block.get("lines", []):
            for span in line.get("spans", []):
                text = span.get("text", "").strip()
                if not text:
                    continue
                x0, y0, x1, y1 = (_fitz().Rect(span["bbox"]) * page.rotation_matrix)
                spans.append((text, span.get("size", 0), x0, y0, x1, y1))
    corner = [s for s in spans if s[2] > rect.width * 0.6 and s[3] > rect.height * 0.55]
    sheet_ids = sorted((s for s in corner if SHEET_ID.match(s[0].replace(" ", ""))),
                       key=lambda s: -s[1])
    titles = sorted((s for s in corner if len(s[0]) >= 8 and s[0].upper() == s[0]
                     and not SHEET_ID.match(s[0])), key=lambda s: -s[1])
    return {
        "sheet": sheet_ids[0][0] if sheet_ids else None,
        "title_candidates": [t[0] for t in titles[:6]],
    }


def document_outline(pdf_path: Path, *, max_pages: int = 400) -> list[dict]:
    """One row per PDF page (1-based): sheet id, title and keyword flags."""
    fitz = _fitz()
    rows = []
    with fitz.open(pdf_path) as doc:
        for number, page in enumerate(doc):
            if number >= max_pages:
                break
            text = page.get_text()
            row = {"pdf_page": number + 1, "flags": _keywords(text), "chars": len(text)}
            try:
                row.update(_title_block(page))
            except Exception:                                    # odd PDFs: keep flags
                pass
            rows.append(row)
    # Project name, firm and "BID SET" repeat on every sheet; the sheet title does not.
    counts: dict[str, int] = {}
    for row in rows:
        for text in set(row.get("title_candidates", [])):
            counts[text] = counts.get(text, 0) + 1
    limit = max(2, len(rows) * 0.4)
    for row in rows:
        own = [t for t in row.pop("title_candidates", []) if counts.get(t, 0) <= limit]
        row["title"] = " / ".join(own[:2])[:140] or None
    return rows


def pdf_words(page_json: Path, size: tuple[int, int], region: Box | None = None, *,
              limit: int = 400) -> list[dict] | None:
    """Words of a label-store PDF page with boxes in rendered-page pixels, or None
    for photos. ``size`` is the rendered page's (width, height)."""
    from annotator import label_store
    document = label_store.document_for(page_json)
    if document.kind != "pdf":
        return None
    number = label_store.page_number(page_json)
    fitz = _fitz()
    out = []
    with fitz.open(document.source) as doc:
        page = doc[number - 1]
        matrix = _page_matrix(page, size[0], size[1])
        for x0, y0, x1, y1, word, *_ in page.get_text("words"):
            r = fitz.Rect(x0, y0, x1, y1) * matrix
            box = [round(r.x0, 1), round(r.y0, 1), round(r.x1, 1), round(r.y1, 1)]
            if region and not _intersects(box, region):
                continue
            out.append({"text": word, "box": box})
            if len(out) >= limit:
                break
    return out


# --------------------------------------------------------------------------
# raster helpers
# --------------------------------------------------------------------------

def _intersects(a: Box, b: Box) -> bool:
    return not (a[2] < b[0] or a[0] > b[2] or a[3] < b[1] or a[1] > b[3])


def _overlap_fraction(a: Box, b: Box) -> float:
    ix = max(0.0, min(a[2], b[2]) - max(a[0], b[0]))
    iy = max(0.0, min(a[3], b[3]) - max(a[1], b[1]))
    area = max(1e-6, (a[2] - a[0]) * (a[3] - a[1]))
    return ix * iy / area


def iou(a: Box, b: Box) -> float:
    ix = max(0.0, min(a[2], b[2]) - max(a[0], b[0]))
    iy = max(0.0, min(a[3], b[3]) - max(a[1], b[1]))
    inter = ix * iy
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / union if union > 0 else 0.0


def _cluster(boxes: list[Box], gap: float) -> list[Box]:
    """Union boxes that touch (within ``gap``) until stable."""
    merged = [list(map(float, b)) for b in boxes]
    changed = True
    while changed:
        changed = False
        out: list[Box] = []
        for box in merged:
            for other in out:
                if (box[0] - gap <= other[2] and other[0] - gap <= box[2]
                        and box[1] - gap <= other[3] and other[1] - gap <= box[3]):
                    other[0] = min(other[0], box[0]); other[1] = min(other[1], box[1])
                    other[2] = max(other[2], box[2]); other[3] = max(other[3], box[3])
                    changed = True
                    break
            else:
                out.append(box)
        merged = out
    return merged


def _ink_mask(gray: np.ndarray) -> np.ndarray:
    import cv2
    blur = cv2.GaussianBlur(gray, (3, 3), 0)
    block = 31 if min(gray.shape) > 40 else max(3, (min(gray.shape) // 2) * 2 + 1)
    adaptive = cv2.adaptiveThreshold(blur, 255, cv2.ADAPTIVE_THRESH_MEAN_C, cv2.THRESH_BINARY_INV, block, 12)
    # Dark ink only: adaptive thresholding alone also fires on paper texture.
    dark = (blur < np.percentile(blur, 60) - 25).astype(np.uint8) * 255
    return cv2.bitwise_and(adaptive, dark) if dark.any() else adaptive


def _crop_gray(image_path: Path, box: Box) -> tuple[np.ndarray, int, int]:
    with Image.open(image_path) as source:
        x0, y0 = max(0, int(math.floor(box[0]))), max(0, int(math.floor(box[1])))
        x1, y1 = min(source.width, int(math.ceil(box[2]))), min(source.height, int(math.ceil(box[3])))
        crop = source.convert("L").crop((x0, y0, x1, y1))
    return np.asarray(crop), x0, y0


def tighten_box(image_path: Path, box: Box, *, pad: float = 2.0) -> tuple[Box, str]:
    """Shrink ``box`` to the ink of the glyph at its centre.

    Ink components that touch the middle 60% of the box are kept; long lines
    that merely pass through (walls, leaders) are clipped to the box. Returns
    the new box and a short note. The box is never grown beyond the original
    plus ``pad`` and is left unchanged when too little ink is found.
    """
    import cv2
    gray, ox, oy = _crop_gray(image_path, box)
    if gray.size == 0 or min(gray.shape) < 4:
        return list(box), "unchanged: box too small"
    mask = _ink_mask(gray)
    h, w = mask.shape
    # Walls and leader lines run straight through a symbol's box; strip lines
    # longer than most of the box so they cannot drag the extent to the edges.
    lh = cv2.morphologyEx(mask, cv2.MORPH_OPEN,
                          cv2.getStructuringElement(cv2.MORPH_RECT, (max(12, int(w * 0.75)), 1)))
    lv = cv2.morphologyEx(mask, cv2.MORPH_OPEN,
                          cv2.getStructuringElement(cv2.MORPH_RECT, (1, max(12, int(h * 0.75)))))
    mask = cv2.subtract(mask, cv2.bitwise_or(lh, lv))
    count, labels, stats, _ = cv2.connectedComponentsWithStats(mask, connectivity=8)
    cx0, cy0, cx1, cy1 = w * 0.2, h * 0.2, w * 0.8, h * 0.8
    keep = []
    for label in range(1, count):
        x, y, bw, bh, area = (int(v) for v in stats[label])
        if area < 3:
            continue
        if x <= cx1 and x + bw >= cx0 and y <= cy1 and y + bh >= cy0:
            keep.append((x, y, x + bw, y + bh))
    if not keep:
        return list(box), "unchanged: no ink near the box centre"
    x0 = min(k[0] for k in keep); y0 = min(k[1] for k in keep)
    x1 = max(k[2] for k in keep); y1 = max(k[3] for k in keep)
    new = [max(box[0], ox + x0 - pad), max(box[1], oy + y0 - pad),
           min(box[2], ox + x1 + pad), min(box[3], oy + y1 + pad)]
    if (new[2] - new[0]) * (new[3] - new[1]) < 0.08 * (box[2] - box[0]) * (box[3] - box[1]):
        return list(box), "unchanged: ink found was much smaller than the box (check the box)"
    return [round(float(v), 1) for v in new], "tightened"


def _raster_candidates(image_path: Path, region: Box, min_px: float, max_px: float) -> list[Box]:
    """Symbol-sized ink blobs with long straight lines (walls, leaders) removed."""
    import cv2
    gray, ox, oy = _crop_gray(image_path, region)
    mask = _ink_mask(gray)
    line_len = int(max(max_px * 1.5, 25))
    horizontal = cv2.morphologyEx(mask, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (line_len, 1)))
    vertical = cv2.morphologyEx(mask, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (1, line_len)))
    glyphs = cv2.subtract(mask, cv2.bitwise_or(horizontal, vertical))
    glyphs = cv2.morphologyEx(glyphs, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8))
    count, _, stats, _ = cv2.connectedComponentsWithStats(glyphs, connectivity=8)
    boxes = []
    for label in range(1, count):
        x, y, bw, bh, area = (int(v) for v in stats[label])
        side = max(bw, bh)
        if not (min_px <= side <= max_px):
            continue
        if min(bw, bh) < side * 0.25 or area < side * 1.5:
            continue
        boxes.append([float(ox + x), float(oy + y), float(ox + x + bw), float(oy + y + bh)])
    return _drop_text_runs(boxes)


def _text_height(boxes: list[Box]) -> float | None:
    """Most common height among wide-ish blobs: the drawing's text height."""
    heights = [round(b[3] - b[1]) for b in boxes if (b[2] - b[0]) > 1.2 * (b[3] - b[1])]
    if len(heights) < 4:
        return None
    counts: dict[int, int] = {}
    for h in heights:
        for k in (h - 1, h, h + 1):
            counts[k] = counts.get(k, 0) + 1
    best = max(counts, key=lambda k: (counts[k], -k))
    return float(best) if counts[best] >= 4 else None


def _drop_text_runs(boxes: list[Box]) -> list[Box]:
    """Remove blobs that look like text: the region's text height plus a wide
    shape or a same-height neighbour on the same line."""
    text_h = _text_height(boxes)
    keep = []
    for i, a in enumerate(boxes):
        aw, ah = a[2] - a[0], a[3] - a[1]
        neighbours = 0
        for j, b in enumerate(boxes):
            if i == j:
                continue
            bh = b[3] - b[1]
            same_row = abs((a[1] + a[3]) - (b[1] + b[3])) / 2 < ah * 0.35
            close = min(abs(a[0] - b[2]), abs(b[0] - a[2])) < ah * 1.2
            if same_row and close and 0.75 < bh / max(ah, 1) < 1.33:
                neighbours += 1
        textlike_height = text_h is not None and 0.8 * text_h <= ah <= 1.2 * text_h
        if neighbours >= 2:
            continue
        if textlike_height and (aw > 1.4 * ah or neighbours >= 1):
            continue
        keep.append(a)
    return keep


# --------------------------------------------------------------------------
# marks (numbered candidates, cached per page)
# --------------------------------------------------------------------------

def _marks_path(page_json: Path) -> Path:
    path = Path(page_json).resolve().parent / MARKS_DIR / f"{Path(page_json).stem}.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    return path


def load_marks(page_json: Path) -> dict[str, Box]:
    path = _marks_path(page_json)
    if not path.is_file():
        return {}
    return json.loads(path.read_text(encoding="utf-8")).get("marks", {})


def find_marks(page_json: Path, image_path: Path, region: Box, *, min_px: float = 10,
               max_px: float = 90, limit: int = 80) -> tuple[list[dict], str]:
    """Detect candidates in ``region``, merge them into the page cache, return them."""
    # Raster blobs with long lines removed find symbols reliably; on vector
    # pages each glyph is many tiny segments chained into the walls, so the
    # PDF is used for what it does exactly: knowing where the text is.
    found = _raster_candidates(image_path, region, min_px, max_px)
    source = "raster"
    with Image.open(image_path) as source:
        size = source.size
    words = pdf_words(page_json, size, region, limit=5000)
    if words is not None:
        source = "raster+pdf_text"
        text_boxes = [w["box"] for w in words]
        found = [b for b in found if not any(_overlap_fraction(b, t) > 0.35 for t in text_boxes)]
    found.sort(key=lambda b: (round(b[1] / 40), b[0]))
    marks = load_marks(page_json)
    next_id = 1 + max((int(k[1:]) for k in marks if k[1:].isdigit()), default=0)
    result = []
    for box in found[:limit]:
        box = [round(float(v), 1) for v in box]
        existing = next((k for k, v in marks.items() if iou(v, box) > 0.6), None)
        if existing is None:
            existing = f"m{next_id}"
            next_id += 1
            marks[existing] = box
        result.append({"id": existing, "box": marks[existing]})
    _marks_path(page_json).write_text(json.dumps({"marks": marks}), encoding="utf-8")
    return result, source


# --------------------------------------------------------------------------
# legend library (per document)
# --------------------------------------------------------------------------

def _legend_dir(page_json: Path) -> Path:
    """A document's legend library lives in its label-store folder."""
    path = Path(page_json).resolve().parent / LEGEND_DIR
    path.mkdir(parents=True, exist_ok=True)
    return path


def load_legend(page_json: Path) -> list[dict]:
    path = _legend_dir(page_json) / "legend.json"
    if not path.is_file():
        return []
    return json.loads(path.read_text(encoding="utf-8")).get("entries", [])


def add_legend_entry(page_json: Path, image_path: Path, box: Box, *, family: str, variant: str,
                     label: str = "") -> dict:
    directory = _legend_dir(page_json)
    entries = load_legend(page_json)
    ident = f"L{1 + max((int(e['id'][1:]) for e in entries), default=0)}"
    with Image.open(image_path) as source:
        crop = source.convert("RGB").crop(tuple(int(round(v)) for v in box))
    crop.save(directory / f"{ident}.png")
    entry = {"id": ident, "family": family, "variant": variant, "label": label[:120],
             "from_page": str(page_json), "box": [round(v, 1) for v in box]}
    entries.append(entry)
    (directory / "legend.json").write_text(json.dumps({"entries": entries}, indent=1), encoding="utf-8")
    return entry


def remove_legend_entry(page_json: Path, ident: str) -> bool:
    directory = _legend_dir(page_json)
    entries = load_legend(page_json)
    kept = [e for e in entries if e["id"] != ident]
    (directory / "legend.json").write_text(json.dumps({"entries": kept}, indent=1), encoding="utf-8")
    (directory / f"{ident}.png").unlink(missing_ok=True)
    return len(kept) != len(entries)


def legend_sheet(page_json: Path, *, cell: int = 120, columns: int = 3, height: int | None = None,
                 font=None) -> Image.Image | None:
    """Contact sheet of the document's legend crops, labelled ``L3 family/variant``."""
    entries = load_legend(page_json)
    if not entries:
        return None
    directory = _legend_dir(page_json)
    label_h = 30
    rows = math.ceil(len(entries) / columns)
    sheet = Image.new("RGB", (columns * cell, rows * (cell + label_h)), (250, 250, 247))
    draw = ImageDraw.Draw(sheet)
    for i, entry in enumerate(entries):
        cx, cy = (i % columns) * cell, (i // columns) * (cell + label_h)
        try:
            with Image.open(directory / f"{entry['id']}.png") as crop:
                crop = crop.convert("RGB")
                crop.thumbnail((cell - 8, cell - 8))
                sheet.paste(crop, (cx + (cell - crop.width) // 2, cy + (cell - crop.height) // 2))
        except OSError:
            pass
        draw.rectangle([cx, cy, cx + cell - 1, cy + cell + label_h - 1], outline=(180, 180, 180))
        text = f"{entry['id']} {entry['family']}/{entry['variant']}"
        draw.text((cx + 3, cy + cell + 1), text[:22], fill=(20, 20, 20), font=font)
        if entry.get("label"):
            draw.text((cx + 3, cy + cell + 14), entry["label"][:22], fill=(90, 90, 90), font=font)
    if height and sheet.height > height:
        scale = height / sheet.height
        sheet = sheet.resize((max(1, int(sheet.width * scale)), height), Image.LANCZOS)
    return sheet
