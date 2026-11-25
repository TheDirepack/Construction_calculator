"""Cut device symbols out of legend sheets, for compositing.

A legend row is a symbol with its description to the right. For every text
line whose description names a catalog device (``aliases.legend`` in the
catalog, via ``label_schema.device_for_legend_text``), the ink just left of
it is cut out as one drawing of that device.

    python scripts_extract_legend_symbols.py auto --sheets --tessdata ~/.cache/tessdata_eng

Sources default to ``2_component_libraries/web_symbol_sources`` (with its
``manifest.json``); output to ``2_component_libraries/legend_symbol_crops``:
``crops/<id>.png`` (ink on white), ``crops.json`` and ``review.json``
({id: {"status": "accepted"|"rejected", "device": optional correction}}).
Crop ids are a hash of file, page and box, so reviews survive re-extraction.
Only accepted crops are composited (``blueprint_symbols.symbol_library``).

Where text matching cannot find the symbols (charts, scans), a reviewer
boxes them: ``render`` draws a page region with a grid in PDF points, ``add``
snaps a rough box to the ink inside it and saves the crop (with a preview to
check), ``review`` accepts or rejects automatic crops (``sheets`` draws them
in context), ``devices`` lists the catalog ids. Writes are locked, so several
reviewers can work at once.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
from pathlib import Path

import cv2
import numpy as np

from blueprint_symbols.label_schema import device_class, device_for_legend_text

TARGET_TEXT_PX = 22      # render so a description line is ~this tall
MIN_ZOOM, MAX_ZOOM = 1.5, 12.0
SEARCH_W = 12.0          # search this many line heights left of the text
SEARCH_H = 3.5           # ... and this many above/below its centre
MAX_GAP = 5.0            # symbol must end within this many line heights of the text
MAX_SYMBOL_W, MAX_SYMBOL_H = 9.0, 5.0
SYMBOL_TEXT_CHARS = 5    # text this short may be part of a symbol (S3, FACP, WP)
NOTE_NUMBER = re.compile(r"^\(?\d{1,3}[.)]?$|^[A-Z][.)]$")
# A crop that is only letters is an abbreviation-table entry, except the
# lettered switch symbols (S, S3, $4, SD, SWP).
LETTER_SYMBOL = re.compile(r"^[S$][A-Z0-9]{0,2}$", re.I)
MIN_SYMBOL = 0.8         # smaller than this many line heights is a bullet or dot


def page_list(entry: dict, n_pages: int) -> list[int]:
    """0-based legend pages a manifest entry names, else every page."""
    text = str(entry.get("pages_or_sheets_with_symbols") or "")
    m = re.search(r"pp?\.?\s*([\d,\s\-]+)", text) or re.search(r"pages?\s+([\d,\s\-]+)", text)
    if not m:
        return list(range(n_pages))
    pages: set[int] = set()
    for part in re.findall(r"\d+\s*-\s*\d+|\d+", m.group(1)):
        if "-" in part:
            a, b = (int(v) for v in part.split("-"))
            pages.update(range(a, b + 1))
        else:
            pages.add(int(part))
    out = sorted(p - 1 for p in pages if 1 <= p <= n_pages)
    return out or list(range(n_pages))


def text_lines(page, tessdata: str | None = None) -> list[dict]:
    """Horizontal text lines of a page; OCR (tesseract) when it has no text
    layer (scans, CAD text drawn as strokes) and ``tessdata`` is given."""
    textpage = None
    if tessdata and not page.get_text("words"):
        textpage = page.get_textpage_ocr(language="eng", dpi=300, full=True, tessdata=tessdata)
    lines = []
    for block in page.get_text("dict", textpage=textpage)["blocks"]:
        for line in block.get("lines", ()):
            text = "".join(s["text"] for s in line["spans"]).strip()
            if not text:
                continue
            x0, y0, x1, y1 = line["bbox"]
            if line.get("dir", (1, 0))[0] < 0.99:      # rotated text is not a legend row
                continue
            lines.append({"text": text, "bbox": (x0, y0, x1, y1), "ocr": textpage is not None})
    if textpage is not None:
        lines = _join_ocr_words(lines)
    return lines


def _join_ocr_words(lines: list[dict]) -> list[dict]:
    """OCR splits a line at wide spaces ("RECEPTACLE,"  "SINGLE"); rejoin them."""
    lines = sorted(lines, key=lambda l: (round(l["bbox"][1]), l["bbox"][0]))
    out: list[dict] = []
    for line in lines:
        x0, y0, x1, y1 = line["bbox"]
        h = y1 - y0
        prev = next((o for o in reversed(out[-6:])
                     if abs(o["bbox"][1] - y0) < 0.35 * h and abs(o["bbox"][3] - y1) < 0.35 * h
                     and 0 <= x0 - o["bbox"][2] < 1.5 * h and len(o["text"]) > SYMBOL_TEXT_CHARS), None)
        if prev is None:
            out.append(dict(line))
            continue
        b = prev["bbox"]
        prev["text"] += " " + line["text"]
        prev["bbox"] = (b[0], min(b[1], y0), x1, max(b[3], y1))
    return out


def _row_text(lines: list[dict], i: int) -> str:
    """The line plus directly following lines of the same paragraph."""
    x0, y0, x1, y1 = lines[i]["bbox"]
    h = y1 - y0
    parts, bottom = [lines[i]["text"]], y1
    for other in lines[i + 1:i + 3]:
        ox0, oy0, _, oy1 = other["bbox"]
        if abs(ox0 - x0) < 0.8 * h and 0 <= oy0 - bottom < 0.5 * h:
            parts.append(other["text"])
            bottom = oy1
        else:
            break
    return " ".join(parts)


def render_ink(page, clip, zoom: float) -> np.ndarray:
    """Render a region as ink darkness (0 = full ink): the darkest colour
    channel, so pale coloured CAD linework (yellow, cyan) counts as ink."""
    import pymupdf

    pix = page.get_pixmap(matrix=pymupdf.Matrix(zoom, zoom), clip=clip, colorspace=pymupdf.csRGB, alpha=False)
    return np.frombuffer(pix.samples, np.uint8).reshape(pix.height, pix.width, 3).min(axis=2).copy()


def cut_symbol(page, line: dict, lines: list[dict]):
    """Ink of the symbol left of ``line``: (gray crop, bbox in pt) or None."""
    import pymupdf

    x0, y0, x1, y1 = line["bbox"]
    h = max(y1 - y0, 2.0)
    cy = 0.5 * (y0 + y1)
    region = pymupdf.Rect(x0 - SEARCH_W * h, cy - SEARCH_H * h, x0 - 0.15 * h, cy + SEARCH_H * h) & page.rect
    if region.is_empty or region.width < h:
        return None
    zoom = float(np.clip(TARGET_TEXT_PX / h, MIN_ZOOM, MAX_ZOOM))
    gray = render_ink(page, region, zoom)

    def to_px(b):
        return (int((b[0] - region.x0) * zoom), int((b[1] - region.y0) * zoom),
                int(np.ceil((b[2] - region.x0) * zoom)), int(np.ceil((b[3] - region.y0) * zoom)))

    # Blank out other descriptions: longer text is never part of a symbol.
    for other in lines:
        if other is line:
            continue
        ob = other["bbox"]
        if ob[2] < region.x0 or ob[0] > region.x1 or ob[3] < region.y0 or ob[1] > region.y1:
            continue
        if len(other["text"].replace(" ", "")) > SYMBOL_TEXT_CHARS:
            a, b, c, d = to_px(ob)
            gray[max(0, b - 1):d + 1, max(0, a - 1):c + 1] = 255

    ink = (gray < 170).astype(np.uint8)
    hp = h * zoom
    # Table rules and long leaders are not symbols.
    k = max(5, int(MAX_SYMBOL_W * hp))
    long_lines = cv2.morphologyEx(ink, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (k, 1)))
    long_lines |= cv2.morphologyEx(ink, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (1, int(MAX_SYMBOL_H * hp))))
    ink &= 1 - long_lines
    d = max(1, int(round(0.3 * hp)))
    grown = cv2.dilate(ink, cv2.getStructuringElement(cv2.MORPH_RECT, (d, d)))
    n, labels, stats, _ = cv2.connectedComponentsWithStats(grown, 8)
    gh, gw = gray.shape
    band = ((cy - region.y0) * zoom - 0.6 * hp, (cy - region.y0) * zoom + 0.6 * hp)
    seeds = [i for i in range(1, n)
             if stats[i, 1] < band[1] and stats[i, 1] + stats[i, 3] > band[0]]
    if not seeds:
        return None
    # Nearest real mark left of the text; leader dashes and bullets are skipped.
    seeds = [i for i in seeds if max(stats[i, 2], stats[i, 3]) - d >= MIN_SYMBOL * hp]
    if not seeds:
        return None
    nearest = max(seeds, key=lambda i: stats[i, 0] + stats[i, 2])
    sx0, sy0, sw, sh = (int(v) for v in stats[nearest, :4])
    if gw - (sx0 + sw) > MAX_GAP * hp:
        return None
    if sw > MAX_SYMBOL_W * hp or sh > MAX_SYMBOL_H * hp:
        return None
    if sx0 <= 0 or sy0 <= 0 or sy0 + sh >= gh:      # cut off by the search window
        return None
    mask = (labels == nearest) & (ink > 0)
    ys, xs = np.nonzero(mask)
    bx0, by0, bx1, by1 = xs.min(), ys.min(), xs.max() + 1, ys.max() + 1
    if min(bx1 - bx0, by1 - by0) < 4 or max(bx1 - bx0, by1 - by0) < MIN_SYMBOL * hp:
        return None
    crop = np.where(mask[by0:by1, bx0:bx1], gray[by0:by1, bx0:bx1], 255).astype(np.uint8)
    box_pt = [round(region.x0 + bx0 / zoom, 2), round(region.y0 + by0 / zoom, 2),
              round(region.x0 + bx1 / zoom, 2), round(region.y0 + by1 / zoom, 2)]
    # Text-only crops: note numbers ("10.") and abbreviation-table entries.
    inside = [o for o in lines if o is not line
              and o["bbox"][0] < box_pt[2] and o["bbox"][2] > box_pt[0]
              and o["bbox"][1] < box_pt[3] and o["bbox"][3] > box_pt[1]]
    if inside and not line.get("ocr"):          # OCR reads symbols as letters
        drawn = mask.copy()
        for o in inside:
            a, b, c, d2 = to_px(o["bbox"])
            drawn[max(0, b - 1):d2 + 1, max(0, a - 1):c + 1] = False
        if drawn.sum() < 0.1 * mask.sum():
            letters = "".join(o["text"] for o in inside).replace(" ", "")
            if not LETTER_SYMBOL.match(letters):
                return None
    return crop, box_pt


def extract_pdf(path: Path, rel: str, entry: dict, crops_dir: Path, tessdata: str | None = None) -> list[dict]:
    import pymupdf

    doc = pymupdf.open(path)
    out = []
    seen_shapes: set[tuple[str, bytes]] = set()     # same legend repeated on several sheets
    for pno in page_list(entry, len(doc)):
        page = load_page(doc, pno)
        lines = text_lines(page, tessdata)
        seen_boxes: list[list[float]] = []
        for i, line in enumerate(lines):
            text = _row_text(lines, i)
            device = device_for_legend_text(text)
            if device is None or device_class(*device) is None:
                continue
            got = cut_symbol(page, line, lines)
            if got is None:
                continue
            crop, box = got
            if any(_overlap(box, b) > 0.5 for b in seen_boxes):
                continue        # a continuation line pointing at the same symbol
            seen_boxes.append(box)
            shape = (device_class(*device), _shape_hash(crop))
            if shape in seen_shapes:
                continue
            seen_shapes.add(shape)
            crop_id = _crop_id(rel, pno, box)
            cv2.imwrite(str(crops_dir / f"{crop_id}.png"), crop)
            out.append({
                "id": crop_id,
                "method": "auto",
                "device": device_class(*device),
                "legend_text": text,
                "source_file": rel,
                "page_index": pno,
                "bbox_pt": box,
                "text_bbox_pt": [round(v, 2) for v in line["bbox"]],
                "organisation": entry.get("organisation"),
                "licence": entry.get("licence"),
            })
    return out


def load_page(doc, pno: int):
    page = doc[pno]
    if page.rotation:
        page.remove_rotation()      # work in the sheet's reading orientation
    return page


def _crop_id(rel: str, pno: int, box) -> str:
    key = f"{rel}|{pno}|" + ",".join(f"{v:.0f}" for v in box)
    return hashlib.sha1(key.encode()).hexdigest()[:12]


def _shape_hash(crop: np.ndarray) -> bytes:
    small = cv2.resize(crop, (17, 16), interpolation=cv2.INTER_AREA).astype(np.int16)
    return np.packbits(small[:, 1:] > small[:, :-1]).tobytes() + bytes([min(255, crop.shape[1] * 8 // crop.shape[0])])


def _overlap(a, b) -> float:
    ix = max(0.0, min(a[2], b[2]) - max(a[0], b[0]))
    iy = max(0.0, min(a[3], b[3]) - max(a[1], b[1]))
    small = min((a[2] - a[0]) * (a[3] - a[1]), (b[2] - b[0]) * (b[3] - b[1])) or 1.0
    return ix * iy / small


def snap_to_ink(page, box_pt) -> tuple[np.ndarray, list[float]] | None:
    """Tight crop of the drawing inside a rough box: ink whose pieces lie
    mostly inside the box, without table rules crossing it."""
    import pymupdf

    bx0, by0, bx1, by1 = (float(v) for v in box_pt)
    side = max(bx1 - bx0, by1 - by0, 1.0)
    clip = pymupdf.Rect(bx0 - 0.15 * side, by0 - 0.15 * side, bx1 + 0.15 * side, by1 + 0.15 * side) & page.rect
    zoom = float(np.clip(160.0 / side, 1.5, 16.0))
    gray = render_ink(page, clip, zoom)
    ink = (gray < 170).astype(np.uint8)
    k = int(1.25 * side * zoom)
    rules = cv2.morphologyEx(ink, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (k, 1)))
    rules |= cv2.morphologyEx(ink, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (1, k)))
    ink &= 1 - rules
    n, labels, stats, _ = cv2.connectedComponentsWithStats(cv2.dilate(ink, np.ones((3, 3), np.uint8)), 8)
    ax0, ay0 = (bx0 - clip.x0) * zoom, (by0 - clip.y0) * zoom
    ax1, ay1 = (bx1 - clip.x0) * zoom, (by1 - clip.y0) * zoom
    keep = np.zeros(n, bool)
    for i in range(1, n):
        x, y, w, h = stats[i, :4]
        ix = max(0.0, min(ax1, x + w) - max(ax0, x))
        iy = max(0.0, min(ay1, y + h) - max(ay0, y))
        keep[i] = ix * iy >= 0.6 * w * h
    mask = keep[labels] & (ink > 0)
    if mask.sum() < 6:
        return None
    ys, xs = np.nonzero(mask)
    x0, y0, x1, y1 = xs.min(), ys.min(), xs.max() + 1, ys.max() + 1
    crop = np.where(mask[y0:y1, x0:x1], gray[y0:y1, x0:x1], 255).astype(np.uint8)
    box = [round(clip.x0 + x0 / zoom, 2), round(clip.y0 + y0 / zoom, 2),
           round(clip.x0 + x1 / zoom, 2), round(clip.y0 + y1 / zoom, 2)]
    return crop, box


def context_image(page, crop: dict, width: int, height: int) -> np.ndarray:
    """The legend row around a crop (BGR), the crop's box in red."""
    import pymupdf

    b = crop["bbox_pt"]
    h = max(0.4 * (b[3] - b[1]), 2.0)
    t = crop.get("text_bbox_pt") or [b[2] + h, b[1], b[2] + 20 * h, b[3]]
    h = max(t[3] - t[1], h)
    clip = pymupdf.Rect(min(b[0], t[0]) - h, min(b[1], t[1]) - h,
                        min(max(b[2], t[2]), max(b[2], t[0]) + 25 * h) + h, max(b[3], t[3]) + h) & page.rect
    zoom = min(width / clip.width, height / clip.height)
    pix = page.get_pixmap(matrix=pymupdf.Matrix(zoom, zoom), clip=clip, alpha=False)
    img = np.frombuffer(pix.samples, np.uint8).reshape(pix.height, pix.width, pix.n)[..., :3][..., ::-1].copy()
    x0, y0 = int((b[0] - clip.x0) * zoom), int((b[1] - clip.y0) * zoom)
    x1, y1 = int((b[2] - clip.x0) * zoom) + 1, int((b[3] - clip.y0) * zoom) + 1
    cv2.rectangle(img, (x0 - 2, y0 - 2), (x1 + 2, y1 + 2), (0, 0, 255), 1)
    return img


def effective_device(crop: dict, review: dict) -> str:
    return (review.get(crop["id"]) or {}).get("device") or crop["device"]


def contact_sheets(out: Path, sources: Path, crops: list[dict], review: dict, per_sheet: int = 30,
                   audit: bool = False) -> list[Path]:
    """Numbered sheets of unreviewed crops (``audit``: accepted crops) grouped
    by device. Each cell shows the legend row with the cut-out box in red and
    the device it is labelled as."""
    import pymupdf

    prefix = "audit" if audit else "sheet"
    sheet_dir = out / ("_audit_sheets" if audit else "_review_sheets")
    sheet_dir.mkdir(exist_ok=True)
    for old in sheet_dir.glob("*"):
        old.unlink()
    if audit:
        todo = [c for c in crops if (review.get(c["id"]) or {}).get("status") == "accepted"]
    else:
        todo = [c for c in crops if c["id"] not in review]
    todo.sort(key=lambda c: (effective_device(c, review), c["source_file"]))
    cell_w, cell_h, cols = 420, 170, 3
    docs: dict[str, object] = {}
    written, index = [], {}
    for s in range(0, len(todo), per_sheet):
        chunk = todo[s:s + per_sheet]
        rows = (len(chunk) + cols - 1) // cols
        sheet = np.full((rows * cell_h, cols * cell_w, 3), 255, np.uint8)
        for k, c in enumerate(chunk):
            r, q = divmod(k, cols)
            ox, oy = q * cell_w, r * cell_h
            if c["source_file"] not in docs:
                docs[c["source_file"]] = pymupdf.open(sources / c["source_file"])
            img = context_image(load_page(docs[c["source_file"]], c["page_index"]), c, cell_w - 10, cell_h - 28)
            sheet[oy + 22:oy + 22 + img.shape[0], ox + 5:ox + 5 + img.shape[1]] = img
            cv2.putText(sheet, f"#{k} {effective_device(c, review)}", (ox + 4, oy + 15), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (200, 0, 0), 1)
            cv2.rectangle(sheet, (ox, oy), (ox + cell_w - 1, oy + cell_h - 1), (170, 170, 170), 1)
            index.setdefault(f"{prefix}_{len(written):03d}", {})[str(k)] = c["id"]
        name = sheet_dir / f"{prefix}_{len(written):03d}.png"
        cv2.imwrite(str(name), sheet)
        written.append(name)
    (sheet_dir / "index.json").write_text(json.dumps(index, indent=1))
    return written


def render_with_grid(page, clip, out_path: Path, max_px: int = 1800) -> dict:
    """Render a page region with a labelled grid in PDF points, so a reader
    of the image can give boxes in page coordinates."""
    import pymupdf

    clip = pymupdf.Rect(clip) & page.rect if clip else page.rect
    zoom = min(max_px / clip.width, max_px / clip.height, 12.0)
    pix = page.get_pixmap(matrix=pymupdf.Matrix(zoom, zoom), clip=clip, alpha=False)
    img = np.frombuffer(pix.samples, np.uint8).reshape(pix.height, pix.width, pix.n)[..., :3][..., ::-1].copy()
    margin = 46
    canvas = np.full((img.shape[0] + 2 * margin, img.shape[1] + 2 * margin, 3), 255, np.uint8)
    canvas[margin:margin + img.shape[0], margin:margin + img.shape[1]] = img
    span = max(clip.width, clip.height)
    step = next(v for v in (1, 2, 5, 10, 20, 25, 50, 100, 200, 250, 500) if span / v <= 24)
    for axis in (0, 1):
        lo, hi = (clip.x0, clip.x1) if axis == 0 else (clip.y0, clip.y1)
        v = np.ceil(lo / step) * step
        while v <= hi:
            px = margin + int((v - lo) * zoom)
            if axis == 0:
                cv2.line(canvas, (px, margin), (px, margin + img.shape[0]), (255, 190, 120), 1)
                for y in (14, canvas.shape[0] - 8):
                    cv2.putText(canvas, f"{v:g}", (px - 12, y), cv2.FONT_HERSHEY_SIMPLEX, 0.4, (200, 60, 0), 1)
            else:
                cv2.line(canvas, (margin, px), (margin + img.shape[1], px), (255, 190, 120), 1)
                for x in (2, canvas.shape[1] - margin + 2):
                    cv2.putText(canvas, f"{v:g}", (x, px + 4), cv2.FONT_HERSHEY_SIMPLEX, 0.4, (200, 60, 0), 1)
            v += step
    # the grid is drawn over the page, so redraw the page ink on top of it
    ink = img.min(axis=2) < 200
    region = canvas[margin:margin + img.shape[0], margin:margin + img.shape[1]]
    region[ink] = img[ink]
    cv2.imwrite(str(out_path), canvas)
    return {"image": str(out_path), "clip_pt": [round(v, 1) for v in clip], "grid_step_pt": step,
            "page_size_pt": [round(page.rect.width, 1), round(page.rect.height, 1)]}


# ---------------------------------------------------------------------------
# Store: crops.json + review.json, shared by the automatic pass and agents.

LEGEND_ABOUT = ("Symbols cut from legend sheets (scripts_extract_legend_symbols.py): "
                "method 'auto' = matched legend text, 'agent' = boxed by a reviewer. "
                "review.json accepts or rejects each crop; only accepted crops are composited.")


class Store:
    """crops.json + review.json of a crop library, locked for concurrent writers."""

    def __init__(self, out: Path, about: str = LEGEND_ABOUT):
        self.about = about
        self.out = out
        self.crops_dir = out / "crops"
        self.crops_dir.mkdir(parents=True, exist_ok=True)
        self.index_path = out / "crops.json"
        self.review_path = out / "review.json"
        self.claimed_path = out / "agent_sources.json"
        self._lock_file = open(out / ".lock", "w")

    def __enter__(self):
        import fcntl
        fcntl.flock(self._lock_file, fcntl.LOCK_EX)
        self.crops = json.loads(self.index_path.read_text())["crops"] if self.index_path.exists() else []
        self.review = json.loads(self.review_path.read_text()) if self.review_path.exists() else {}
        self.claimed = json.loads(self.claimed_path.read_text()) if self.claimed_path.exists() else []
        return self

    def __exit__(self, *exc):
        import fcntl
        if exc[0] is None:
            _write_json(self.index_path, {"about": self.about, "crops": self.crops})
            _write_json(self.review_path, self.review)
            _write_json(self.claimed_path, sorted(set(self.claimed)))
        fcntl.flock(self._lock_file, fcntl.LOCK_UN)


def _write_json(path: Path, data) -> None:
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, indent=1, ensure_ascii=False) + "\n")
    tmp.replace(path)


def _check_device(device: str) -> str:
    from blueprint_symbols.label_schema import device_for_class
    fam_var = device_for_class(device)
    if not fam_var or device_class(*fam_var) != device:
        raise SystemExit(f"not a catalog device: {device!r} (see the 'devices' command)")
    return device


def _manifest_entry(sources: Path, rel: str) -> dict:
    for entry in json.loads((sources / "manifest.json").read_text()):
        if entry["file"] == rel:
            return entry
    raise SystemExit(f"{rel!r} is not in {sources / 'manifest.json'}")


def cmd_auto(a) -> None:
    manifest = json.loads((a.sources / "manifest.json").read_text())
    found_all: list[dict] = []
    with Store(a.out) as store:
        for entry in manifest:
            rel = entry["file"]
            if not rel.lower().endswith(".pdf") or (a.only and not any(s in rel for s in a.only)):
                continue
            if rel in store.claimed:
                print(f"   -  {rel} (boxed by agents)", flush=True)
                continue
            found = extract_pdf(a.sources / rel, rel, entry, store.crops_dir, a.tessdata)
            print(f"{len(found):4d}  {rel}", flush=True)
            found_all.extend(found)
        redone = {c["source_file"] for c in found_all} | {
            e["file"] for e in manifest if not a.only or any(s in e["file"] for s in a.only)}
        # keep agent crops and auto crops of sources not re-run
        store.crops = [c for c in store.crops
                       if c.get("method") == "agent" or c["source_file"] not in redone] + found_all
        keep = {c["id"] for c in store.crops}
        for png in store.crops_dir.glob("*.png"):
            if png.stem not in keep:
                png.unlink()
        _summary(store)
        if a.sheets:
            sheets = contact_sheets(a.out, a.sources, store.crops, store.review)
            print(f"{len(sheets)} review sheets in {a.out / '_review_sheets'}")


def _summary(store: Store) -> None:
    status = {}
    for c in store.crops:
        st = (store.review.get(c["id"]) or {}).get("status", "unreviewed")
        status[st] = status.get(st, 0) + 1
    accepted = {(store.review[c["id"]].get("device") or c["device"])
                for c in store.crops if (store.review.get(c["id"]) or {}).get("status") == "accepted"}
    print(json.dumps({"crops": len(store.crops), **status, "accepted_devices": len(accepted)}), flush=True)


def cmd_sheets(a) -> None:
    with Store(a.out) as store:
        sheets = contact_sheets(a.out, a.sources, store.crops, store.review, audit=a.audit)
        _summary(store)
    print(f"{len(sheets)} sheets in {sheets[0].parent if sheets else a.out} (index.json maps <sheet>#n to crop id)")


def cmd_render(a) -> None:
    import pymupdf

    doc = pymupdf.open(a.sources / a.source)
    page = load_page(doc, a.page)
    out = Path(a.image) if a.image else a.out / "_agent_renders" / (
        f"{Path(a.source).stem}_p{a.page}" + ("_" + "_".join(f"{v:g}" for v in a.clip) if a.clip else "") + ".png")
    out.parent.mkdir(parents=True, exist_ok=True)
    print(json.dumps({**render_with_grid(page, a.clip, out, a.max_px), "pages": len(doc)}))


def cmd_add(a) -> None:
    """Add boxed symbols: one via --box/--device, or many via --batch (JSON list
    of {"page", "box", "device", "text"})."""
    import pymupdf

    items = json.loads(Path(a.batch).read_text()) if a.batch else [
        {"page": a.page, "box": a.box, "device": a.device, "text": a.text}]
    entry = _manifest_entry(a.sources, a.source)
    doc = pymupdf.open(a.sources / a.source)
    previews = a.out / "_agent_previews"
    previews.mkdir(exist_ok=True)
    results = []
    with Store(a.out) as store:
        for item in items:
            device = _check_device(item["device"])
            page = load_page(doc, int(item["page"]))
            got = snap_to_ink(page, item["box"])
            if got is None:
                results.append({"box": item["box"], "error": "no ink inside the box"})
                continue
            crop, box = got
            crop_id = _crop_id(a.source, int(item["page"]), box)
            cv2.imwrite(str(store.crops_dir / f"{crop_id}.png"), crop)
            record = {
                "id": crop_id, "method": "agent", "device": device,
                "legend_text": item.get("text") or "", "source_file": a.source,
                "page_index": int(item["page"]), "bbox_pt": box,
                "organisation": entry.get("organisation"), "licence": entry.get("licence"),
                "added_by": a.agent,
            }
            store.crops = [c for c in store.crops if c["id"] != crop_id] + [record]
            store.review[crop_id] = {"status": "accepted", "by": a.agent}
            preview = previews / f"{crop_id}.png"
            ctx = context_image(page, record, 600, 140)
            big = cv2.resize(crop, None, fx=min(4.0, 120 / max(crop.shape)), fy=min(4.0, 120 / max(crop.shape)),
                             interpolation=cv2.INTER_AREA)
            canvas = np.full((ctx.shape[0] + big.shape[0] + 30, max(ctx.shape[1], big.shape[1]) + 10, 3), 255, np.uint8)
            canvas[:ctx.shape[0], :ctx.shape[1]] = ctx
            canvas[ctx.shape[0] + 25:ctx.shape[0] + 25 + big.shape[0], 5:5 + big.shape[1]] = big[..., None]
            cv2.putText(canvas, f"{crop_id} {device}", (5, ctx.shape[0] + 18), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (200, 0, 0), 1)
            cv2.imwrite(str(preview), canvas)
            results.append({"id": crop_id, "device": device, "box_pt": box, "size_px": list(crop.shape[::-1]),
                            "preview": str(preview)})
    print(json.dumps(results, indent=1))


def cmd_review(a) -> None:
    """Record decisions: --id with --status (and optional --device), or
    --batch JSON {crop_id: {"status": ..., "device": optional, "note": optional}}."""
    decisions = json.loads(Path(a.batch).read_text()) if a.batch else {
        a.id: {"status": a.status, **({"device": a.device} if a.device else {})}}
    with Store(a.out) as store:
        known = {c["id"]: c for c in store.crops}
        sheet_index = {}
        for idx_path in (a.out / "_review_sheets" / "index.json", a.out / "_audit_sheets" / "index.json"):
            if idx_path.exists():
                sheet_index.update(json.loads(idx_path.read_text()))
        bad = []
        for key, d in decisions.items():
            crop_id = key
            if key not in known and "#" in key:          # "sheet_003#12" / "audit_003#12"
                sheet, _, n = key.partition("#")
                crop_id = sheet_index.get(sheet, {}).get(n, key)
            if crop_id not in known or d.get("status") not in ("accepted", "rejected"):
                bad.append(key)
                continue
            rec = {"status": d["status"], "by": a.agent}
            if d.get("device") and d["device"] != known[crop_id]["device"]:
                rec["device"] = _check_device(d["device"])
            if d.get("note"):
                rec["note"] = str(d["note"])[:200]
            store.review[crop_id] = rec
        _summary(store)
    if bad:
        print(json.dumps({"not_recorded": bad}))


def cmd_remove(a) -> None:
    with Store(a.out) as store:
        store.crops = [c for c in store.crops if c["id"] not in a.ids or c.get("method") != "agent"]
        for crop_id in a.ids:
            store.review.pop(crop_id, None) if crop_id not in {c["id"] for c in store.crops} else None
            png = store.crops_dir / f"{crop_id}.png"
            if png.exists() and crop_id not in {c["id"] for c in store.crops}:
                png.unlink()
        _summary(store)


def cmd_claim(a) -> None:
    """Hand whole source files to agents: their automatic crops are dropped and
    ``auto`` skips them from now on."""
    with Store(a.out) as store:
        known = {e["file"] for e in json.loads((a.sources / "manifest.json").read_text())}
        for rel in a.sources_:
            if rel not in known:
                raise SystemExit(f"{rel!r} is not in manifest.json")
        store.claimed = sorted(set(store.claimed) | set(a.sources_))
        dropped = [c["id"] for c in store.crops if c["source_file"] in a.sources_ and c.get("method") != "agent"]
        store.crops = [c for c in store.crops if c["id"] not in dropped]
        for crop_id in dropped:
            store.review.pop(crop_id, None)
            (store.crops_dir / f"{crop_id}.png").unlink(missing_ok=True)
        print(json.dumps({"claimed": store.claimed, "auto_crops_dropped": len(dropped)}))


def cmd_devices(a) -> None:
    from blueprint_symbols.label_schema import CATALOG_PATH
    for d in json.loads(CATALOG_PATH.read_text())["devices"]:
        print(f"{d['symbol_family']}.{d['symbol_variant']:<40} {d['label']}")


def main() -> None:
    root = Path(__file__).resolve().parents[2] / "blueprints_and_libraries" / "2_component_libraries"
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--sources", type=Path, default=root / "web_symbol_sources", help="folder with manifest.json")
    p.add_argument("--out", type=Path, default=root / "legend_symbol_crops")
    p.add_argument("--agent", default="human", help="who records adds/reviews")
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("auto", help="cut symbols next to legend text that names a device")
    s.add_argument("--only", nargs="*", default=[], help="only source files containing these strings")
    s.add_argument("--sheets", action="store_true", help="also write contact sheets of unreviewed crops")
    s.add_argument("--tessdata", default=os.environ.get("TESSDATA_PREFIX"),
                   help="folder with eng.traineddata, to OCR legend sheets without a text layer")
    s.set_defaults(fn=cmd_auto)
    s = sub.add_parser("sheets", help="contact sheets of unreviewed crops")
    s.add_argument("--audit", action="store_true", help="sheets of accepted crops instead (_audit_sheets)")
    s.set_defaults(fn=cmd_sheets)
    s = sub.add_parser("render", help="render a page (or --clip region, PDF points) with a coordinate grid")
    s.add_argument("--source", required=True, help="file path relative to --sources, as in manifest.json")
    s.add_argument("--page", type=int, required=True, help="0-based page index")
    s.add_argument("--clip", type=float, nargs=4, metavar=("X0", "Y0", "X1", "Y1"))
    s.add_argument("--max-px", type=int, default=1800)
    s.add_argument("--image", help="output png (default: <out>/_agent_renders/...)")
    s.set_defaults(fn=cmd_render)
    s = sub.add_parser("add", help="add a boxed symbol (box in PDF points; snapped to the ink inside)")
    s.add_argument("--source", required=True)
    s.add_argument("--page", type=int)
    s.add_argument("--box", type=float, nargs=4, metavar=("X0", "Y0", "X1", "Y1"))
    s.add_argument("--device")
    s.add_argument("--text", default="", help="the legend description")
    s.add_argument("--batch", help="JSON list of {page, box, device, text}")
    s.set_defaults(fn=cmd_add)
    s = sub.add_parser("review", help="accept/reject crops")
    s.add_argument("--id")
    s.add_argument("--status", choices=("accepted", "rejected"))
    s.add_argument("--device", help="corrected catalog device")
    s.add_argument("--batch", help='JSON {"<crop id or sheet_NNN#k>": {"status", "device"?, "note"?}}')
    s.set_defaults(fn=cmd_review)
    s = sub.add_parser("remove", help="delete agent-added crops")
    s.add_argument("ids", nargs="+")
    s.set_defaults(fn=cmd_remove)
    s = sub.add_parser("claim", help="hand source files to agents (auto skips them)")
    s.add_argument("sources_", nargs="+", metavar="SOURCE")
    s.set_defaults(fn=cmd_claim)
    s = sub.add_parser("devices", help="list catalog device ids")
    s.set_defaults(fn=cmd_devices)
    a = p.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
