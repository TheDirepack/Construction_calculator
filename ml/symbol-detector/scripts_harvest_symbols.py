"""Harvest every symbol drawing in the component libraries.

    python scripts_harvest_symbols.py harvest --workers 8
    python scripts_harvest_symbols.py sheets            # labeled, unreviewed crops
    python scripts_harvest_symbols.py --agent NAME review --batch decisions.json

Sources (``2_component_libraries`` by default, legend_symbol_crops excluded):
* CAD files (DXF; DWG converted with libredwg's ``dwg2dxf``): every named
  block definition is one symbol (``cad_block``); a file whose model space is
  one small drawing is one symbol (``cad_file``); symbol sheets are split into
  separate drawings (``cad_sheet``), each labelled by the nearest text below
  or right of it.
* single-symbol images (PNG/JPG/SVG): ``image``.
* with ``--pdf-blobs``, legend pages listed in web_symbol_sources/manifest.json:
  every drawing on the page that is not text (``pdf_blob``). Off by default:
  it also yields line fragments and letters drawn as strokes.

Writes ``<out>/crops/<id>.png``, ``<out>/crops.json`` and ``<out>/review.json``,
the same format as legend_symbol_crops. Self-supervised pretraining uses every
crop that is not rejected. Detector training uses a crop only once review
accepts it as a catalog device; a crop gets a proposed ``device`` when its
block name, file name or caption names one (``device_for_legend_text``).
Identical drawings (same shape hash) are kept once.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
from concurrent.futures import ProcessPoolExecutor, as_completed
from pathlib import Path

import cv2
import numpy as np

from blueprint_symbols.label_schema import device_class, device_for_legend_text

SYMBOL_PX = 192             # long side of a stored crop
MAX_BLOCK_ENTITIES = 400
MAX_PIECES = 30             # separate ink pieces in one symbol (letters count)
SHEET_WORDS = re.compile(r"legend|symbol|block|device|collection|sheet|library|schedule|key", re.I)
# block names of whole drawings, sheet furniture and dimension arrowheads
NOT_A_SYMBOL = re.compile(r"title|border|frame|sheet|drawing|plan|elevation|section|detail|layout|viewport|"
                          r"arrow|tick|dim", re.I)
CAD_EXT = {".dxf", ".dwg"}
IMAGE_EXT = {".png", ".jpg", ".jpeg", ".svg"}
ABOUT = ("Symbol drawings harvested from the component libraries (scripts_harvest_symbols.py). "
         "Pretraining uses every crop not rejected; detector training only crops whose device "
         "review.json accepts.")


def _device_for(text: str) -> str | None:
    words = re.sub(r"[_\-.]+", " ", text or "")
    words = re.sub(r"([a-z])([A-Z])", r"\1 \2", words)
    dev = device_for_legend_text(words)
    return device_class(*dev) if dev else None


def _shape_hash(img: np.ndarray) -> str:
    small = cv2.resize(img, (17, 16), interpolation=cv2.INTER_AREA).astype(np.int16)
    bits = np.packbits(small[:, 1:] > small[:, :-1]).tobytes()
    aspect = min(255, img.shape[1] * 16 // max(1, img.shape[0]))
    return hashlib.sha1(bits + bytes([aspect])).hexdigest()[:16]


def _trim(gray: np.ndarray, pad: int = 2) -> np.ndarray | None:
    ys, xs = np.nonzero(gray < 200)
    if len(xs) < 8:
        return None
    crop = gray[max(0, ys.min() - pad):ys.max() + 1 + pad, max(0, xs.min() - pad):xs.max() + 1 + pad]
    return crop if min(crop.shape) >= 4 else None


def _normalise(gray: np.ndarray) -> np.ndarray | None:
    """Trimmed, long side SYMBOL_PX (never upscaled more than 4x)."""
    crop = _trim(gray)
    if crop is None:
        return None
    s = min(SYMBOL_PX / max(crop.shape), 4.0)
    if s != 1:
        crop = cv2.resize(crop, None, fx=s, fy=s, interpolation=cv2.INTER_AREA if s < 1 else cv2.INTER_CUBIC)
    return crop


def _plausible(crop: np.ndarray) -> bool:
    h, w = crop.shape
    if max(h, w) / max(1, min(h, w)) > 8:
        return False                    # a line, a border strip or a text row
    ink = (crop < 128).mean()
    if not 0.004 < ink < 0.75 or _hatch_tile(crop):
        return False
    if ((crop > 60) & (crop < 215)).mean() > 0.3:
        return False                    # shaded or photographic, not line work
    n, _, stats, _ = cv2.connectedComponentsWithStats((crop < 128).astype(np.uint8), connectivity=8)
    if n - 1 > MAX_PIECES:
        return False                    # a detail, table or text block
    return not _letter_row(stats[1:], crop.shape[0])


def _letter_row(stats: np.ndarray, height: int) -> bool:
    """Pieces of one height side by side with aligned tops: a word, not a symbol."""
    parts = stats[stats[:, 3] >= 0.25 * height]
    if len(parts) < 2:
        return False
    hs = parts[:, 3].astype(float)
    tops = parts[:, 1].astype(float)
    return bool(hs.max() / hs.min() <= 1.35 and np.ptp(tops) <= 0.2 * np.median(hs)
                and (parts[:, 2] <= 1.4 * hs).all())


def _hatch_tile(crop: np.ndarray) -> bool:
    """A patch of parallel diagonal hatch lines (a fill pattern, not a symbol)."""
    g = crop.astype(np.float32)
    gx, gy = cv2.Sobel(g, cv2.CV_32F, 1, 0), cv2.Sobel(g, cv2.CV_32F, 0, 1)
    mag = np.hypot(gx, gy)
    strong = mag > 0.3 * mag.max() if mag.max() > 0 else mag > 1
    if strong.sum() < 50:
        return False
    ang = (np.degrees(np.arctan2(gy[strong], gx[strong])) % 180)
    hist, _ = np.histogram(ang, bins=18, range=(0, 180))
    top = int(hist.argmax())
    share = (hist[top] + hist[(top + 1) % 18] + hist[top - 1]) / hist.sum()
    diagonal = 2 <= top <= 7 or 11 <= top <= 16
    return bool(diagonal and share > 0.6)


# --------------------------------------------------------------------------- CAD

def _read_cad(path: Path, cache: Path):
    import ezdxf
    from ezdxf import recover

    if path.suffix.lower() == ".dwg":
        key = hashlib.sha1(str(path).encode()).hexdigest()[:16]
        dxf = cache / f"{key}.dxf"
        if not dxf.exists():
            cache.mkdir(parents=True, exist_ok=True)
            with tempfile.TemporaryDirectory(dir=cache) as tmp:     # same filesystem: atomic move
                out = Path(tmp) / "x.dxf"
                subprocess.run(["dwg2dxf", "-y", "-o", str(out), str(path)], capture_output=True, timeout=120)
                if not out.exists():
                    return None
                out.replace(dxf)
        path = dxf
    try:
        doc, _auditor = recover.readfile(str(path))
    except Exception:
        try:
            doc = ezdxf.readfile(str(path))
        except Exception:
            return None
    return doc


def _texts(entities):
    """(text, x, y) of TEXT/MTEXT in drawing units, INSERTs expanded."""
    from blueprint_symbols.cad_render import _flat_entities
    out = []
    for e in _flat_entities(entities, text=True):
        try:
            if e.dxftype() == "TEXT":
                out.append((e.dxf.text, e.dxf.insert.x, e.dxf.insert.y))
            elif e.dxftype() == "MTEXT":
                out.append((e.plain_text(), e.dxf.insert.x, e.dxf.insert.y))
        except Exception:
            continue
    return out


def blobs(gray_notext: np.ndarray, *, min_px: float, max_px: float, join_px: int):
    """Boxes of separate drawings: long rules removed, nearby strokes joined."""
    ink = (gray_notext < 170).astype(np.uint8)
    k = max(5, int(1.5 * max_px))
    rules = cv2.morphologyEx(ink, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (k, 1)))
    rules |= cv2.morphologyEx(ink, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (1, k)))
    ink &= 1 - rules
    grown = cv2.dilate(ink, cv2.getStructuringElement(cv2.MORPH_RECT, (join_px, join_px)))
    n, _labels, stats, _ = cv2.connectedComponentsWithStats(grown, 8)
    out = []
    for i in range(1, n):
        x, y, w, h = (int(v) for v in stats[i, :4])
        if min_px <= max(w, h) <= max_px:
            out.append((x, y, x + w, y + h))
    return out


def harvest_cad(path: Path, rel: str, cache: Path) -> list[dict]:
    from blueprint_symbols.cad_render import TEXT_TYPES, entity_polylines, extents, rasterize

    doc = _read_cad(path, cache)
    if doc is None:
        return []
    found: list[dict] = []

    def render(entities, size, text=True):
        pl = entity_polylines(entities, text=text)
        if not pl:
            return None, None
        img, scale, origin = rasterize(pl, size)
        return img, (scale, origin)

    def main_drawing(entities):
        """The largest separate drawing (dimension lines, captions and scale
        notes around a library symbol dropped); letters inside it kept."""
        pl_text = entity_polylines(entities, text=True)
        pl_plain = entity_polylines(entities, text=False)
        if not pl_plain:
            return None
        box = extents(pl_text)
        size = 600
        full, _, _ = rasterize(pl_text, size, box=box)
        plain, _, _ = rasterize(pl_plain, size, box=box)
        found_blobs = blobs(plain, min_px=12, max_px=10 * size, join_px=7)
        if not found_blobs:
            return None
        x0, y0, x1, y1 = max(found_blobs, key=lambda b: (b[2] - b[0]) * (b[3] - b[1]))
        return _normalise(full[y0:y1, x0:x1])

    # 1. named block definitions
    for block in doc.blocks:
        name = block.name
        if name.startswith(("*", "_")) or block.is_any_layout or NOT_A_SYMBOL.search(name):
            continue
        ents = list(block)
        drawn = [e for e in ents if e.dxftype() not in TEXT_TYPES]
        if not drawn or len(ents) > MAX_BLOCK_ENTITIES:
            continue
        try:
            img, _ = render(ents, SYMBOL_PX)
            plain, _ = render(ents, SYMBOL_PX, text=False)
            if img is None or plain is None or (plain < 128).sum() < 0.3 * (img < 128).sum():
                continue        # mostly text: a tag or title, not a symbol
            crop = main_drawing(ents)
        except Exception:
            continue
        if crop is not None and _plausible(crop):
            found.append({"method": "cad_block", "container": name, "text": name,
                          "device": _device_for(name), "img": crop})

    # 2. model space: one symbol, or a sheet of them
    msp = list(doc.modelspace())
    if not msp:
        return found
    try:
        pl_all = entity_polylines(msp)
        pl_drawn = entity_polylines(msp, text=False)
    except Exception:
        return found
    if not pl_drawn:
        return found
    box = extents(pl_all)
    probe, _, _ = rasterize(pl_drawn, 1200, box=box)
    parts = blobs(probe, min_px=30, max_px=1200, join_px=9)
    small = len(pl_drawn) <= 120 and len(parts) < 3
    if small:
        crop = main_drawing(msp)
        stem = re.sub(r"-[0-9a-f]{8}$", "", path.stem)
        if crop is not None and _plausible(crop):
            found.append({"method": "cad_file", "container": "modelspace", "text": stem,
                          "device": _device_for(stem), "img": crop})
        return found
    if not SHEET_WORDS.search(path.stem) and "innerscene" in rel:
        return found            # a whole plan: its blocks were harvested above
    size = 3000
    full, scale, (ox, oy) = rasterize(pl_all, size, box=box)
    plain, _, _ = rasterize(pl_drawn, size, box=box)
    texts = _texts(msp)
    for x0, y0, x1, y1 in blobs(plain, min_px=size / 120, max_px=size / 6, join_px=max(3, size // 300)):
        crop = _normalise(full[y0:y1, x0:x1])
        if crop is None or not _plausible(crop):
            continue
        caption = _caption(texts, (x0, y0, x1, y1), scale, ox, oy, margin=4)
        found.append({"method": "cad_sheet", "container": f"modelspace@{x0},{y0}", "text": caption,
                      "device": _device_for(caption) if caption else None, "img": crop})
    return found


def _caption(texts, box, scale, ox, oy, margin) -> str:
    """Nearest text below or right of a drawing (legend-sheet caption)."""
    x0, y0, x1, y1 = box
    w, h = x1 - x0, y1 - y0
    best, best_d = "", float("inf")
    for text, tx, ty in texts:
        px = (tx - ox) * scale + margin
        py = (oy - ty) * scale + margin
        below = x0 - w <= px <= x1 + w and y1 <= py <= y1 + 2.5 * h + 40
        right = x1 <= px <= x1 + 6 * max(w, h) + 40 and y0 - h <= py <= y1 + h
        if below or right:
            d = (py - y1) if below else (px - x1)
            if d < best_d and len(text.strip()) > 2:
                best, best_d = text.strip(), d
    return best


# --------------------------------------------------------------------------- images, PDFs

def harvest_image(path: Path) -> list[dict]:
    if path.suffix.lower() == ".svg":
        try:
            import cairosvg
            png = cairosvg.svg2png(url=str(path), output_height=SYMBOL_PX * 2, background_color="white")
        except Exception:
            return []
        arr = cv2.imdecode(np.frombuffer(png, np.uint8), cv2.IMREAD_UNCHANGED)
    else:
        arr = cv2.imread(str(path), cv2.IMREAD_UNCHANGED)
    if arr is None:
        return []
    if arr.ndim == 3 and arr.shape[2] == 4:
        a = arr[..., 3:4].astype(np.float32) / 255
        arr = (arr[..., :3] * a + 255 * (1 - a)).astype(np.uint8)
    gray = cv2.cvtColor(arr, cv2.COLOR_BGR2GRAY) if arr.ndim == 3 else arr
    crop = _normalise(gray)
    if crop is None or not _plausible(crop):
        return []
    return [{"method": "image", "container": "", "text": path.stem, "device": _device_for(path.stem), "img": crop}]


def harvest_pdf(path: Path, pages: list[int] | None) -> list[dict]:
    """Every non-text drawing on legend pages (unlabelled)."""
    import pymupdf
    from scripts_extract_legend_symbols import render_ink, text_lines

    found = []
    doc = pymupdf.open(path)
    for pno in (pages if pages is not None else range(min(len(doc), 40))):
        page = doc[pno]
        if page.rotation:
            page.remove_rotation()
        if len(page.get_text("words")) < 20:
            continue            # text drawn as strokes (or a scan): it can't be told from symbols
        lines = text_lines(page)
        heights = sorted(l["bbox"][3] - l["bbox"][1] for l in lines)
        h = heights[len(heights) // 2] if heights else 8.0
        zoom = float(np.clip(20.0 / h, 1.0, 6.0))
        if page.rect.width * zoom * page.rect.height * zoom > 120e6:
            zoom = (120e6 / (page.rect.width * page.rect.height)) ** 0.5
        gray = render_ink(page, page.rect, zoom)
        plain = gray.copy()
        for w in page.get_text("words"):           # every word, any direction
            x0, y0, x1, y1 = (int(v * zoom) for v in w[:4])
            plain[max(0, y0 - 1):y1 + 2, max(0, x0 - 1):x1 + 2] = 255
        hp = h * zoom
        for x0, y0, x1, y1 in blobs(plain, min_px=1.0 * hp, max_px=9 * hp, join_px=max(2, int(0.3 * hp))):
            crop = _normalise(np.where(plain[y0:y1, x0:x1] < 255, gray[y0:y1, x0:x1], 255).astype(np.uint8))
            if crop is not None and _plausible(crop):
                found.append({"method": "pdf_blob", "container": f"p{pno}@{x0},{y0}", "text": "",
                              "device": None, "img": crop})
    return found


def _one(args) -> tuple[str, list[dict], str | None]:
    path, rel, cache, pages, pdf_blobs = args
    try:
        ext = path.suffix.lower()
        if ext in CAD_EXT:
            return rel, harvest_cad(path, rel, cache), None
        if ext in IMAGE_EXT:
            return rel, harvest_image(path), None
        if ext == ".pdf":
            # only known legend pages: other PDFs are manuals, photos and plans
            return rel, (harvest_pdf(path, pages) if pdf_blobs and pages is not None else []), None
    except Exception as exc:        # one broken download must not stop the harvest
        return rel, [], f"{type(exc).__name__}: {exc}"[:200]
    return rel, [], None


def source_files(root: Path, skip: set[str]) -> list[tuple[Path, list[int] | None]]:
    manifest_pages: dict[Path, list[int]] = {}
    from scripts_extract_legend_symbols import page_list
    for mf in root.glob("web_symbol_sources/manifest.json"):     # the legend-sheet downloads
        try:
            entries = json.loads(mf.read_text())
        except Exception:
            continue
        for e in entries if isinstance(entries, list) else []:
            p = mf.parent / e.get("file", "")
            if p.suffix.lower() == ".pdf" and p.is_file():
                import pymupdf
                try:
                    manifest_pages[p.resolve()] = page_list(e, len(pymupdf.open(p)))
                except Exception:
                    pass
    out = []
    for p in sorted(root.rglob("*")):
        if not p.is_file() or any(part in skip for part in p.relative_to(root).parts):
            continue
        ext = p.suffix.lower()
        if ext in CAD_EXT or ext in IMAGE_EXT or ext == ".pdf":
            out.append((p, manifest_pages.get(p.resolve())))
    return out


def cmd_harvest(a) -> None:
    from scripts_extract_legend_symbols import Store

    files = source_files(a.sources, set(a.skip))
    if a.limit:
        files = files[:a.limit]
    cache = a.out / "_dxf_cache"
    jobs = [(p, str(p.relative_to(a.sources)), cache, pages, a.pdf_blobs) for p, pages in files]
    print(f"{len(jobs)} source files", flush=True)
    with Store(a.out, ABOUT) as store:
        seen = {c["shape"] for c in store.crops if c.get("shape")}
        done_sources = {c["source_file"] for c in store.crops}
        jobs = [j for j in jobs if j[1] not in done_sources] if not a.redo else jobs
        added = errors = 0
        error_log: dict[str, str] = {}
        with ProcessPoolExecutor(max_workers=a.workers) as pool:
            futures = [pool.submit(_one, j) for j in jobs]
            for n, fut in enumerate(as_completed(futures), 1):
                rel, found, err = fut.result()
                if err is not None:
                    errors += 1
                    error_log[rel] = err
                for item in found:
                    shape = _shape_hash(item["img"])
                    if shape in seen:
                        continue
                    seen.add(shape)
                    crop_id = hashlib.sha1(f"{rel}|{item['container']}|{shape}".encode()).hexdigest()[:12]
                    cv2.imwrite(str(store.crops_dir / f"{crop_id}.png"), item.pop("img"))
                    store.crops.append({"id": crop_id, "source_file": rel, "shape": shape, **item})
                    added += 1
                if n % 100 == 0:
                    print(f"{n}/{len(jobs)} files, {added} new symbols, {errors} unreadable", flush=True)
        (a.out / "_unreadable.json").write_text(json.dumps(error_log, indent=1))
        by_method: dict[str, int] = {}
        for c in store.crops:
            by_method[c["method"]] = by_method.get(c["method"], 0) + 1
        print(json.dumps({"crops": len(store.crops), "added": added, "unreadable_files": errors,
                          "by_method": by_method,
                          "with_proposed_device": sum(1 for c in store.crops if c.get("device"))}), flush=True)


def cmd_sheets(a) -> None:
    """Contact sheets of labelled, unreviewed crops: the crop, its proposed
    device (blue) and the name or caption it came from (green)."""
    from scripts_extract_legend_symbols import Store

    with Store(a.out, ABOUT) as store:
        todo = sorted((c for c in store.crops if c.get("device") and c["id"] not in store.review),
                      key=lambda c: (c["device"], c["source_file"]))
    sheet_dir = a.out / "_review_sheets"
    sheet_dir.mkdir(exist_ok=True)
    for old in sheet_dir.glob("*"):
        old.unlink()
    cell_w, cell_h, cols, per = 300, 190, 4, 40
    index: dict[str, dict[str, str]] = {}
    for s in range(0, len(todo), per):
        chunk = todo[s:s + per]
        name = f"sheet_{s // per:03d}"
        rows = (len(chunk) + cols - 1) // cols
        sheet = np.full((rows * cell_h, cols * cell_w, 3), 255, np.uint8)
        for k, c in enumerate(chunk):
            r, q = divmod(k, cols)
            ox, oy = q * cell_w, r * cell_h
            g = cv2.imread(str(a.out / "crops" / f"{c['id']}.png"), cv2.IMREAD_GRAYSCALE)
            sc = min((cell_w - 20) / g.shape[1], 130 / g.shape[0], 2.0)
            g = cv2.resize(g, None, fx=sc, fy=sc, interpolation=cv2.INTER_AREA)
            sheet[oy + 22:oy + 22 + g.shape[0], ox + 10:ox + 10 + g.shape[1]] = g[..., None]
            cv2.putText(sheet, f"#{k} {c['device']}", (ox + 4, oy + 15), cv2.FONT_HERSHEY_SIMPLEX, 0.4, (200, 0, 0), 1)
            label = f"{c['method']}: {c.get('text') or ''}"
            cv2.putText(sheet, label[:44], (ox + 4, oy + cell_h - 8), cv2.FONT_HERSHEY_SIMPLEX, 0.35, (0, 110, 0), 1)
            cv2.rectangle(sheet, (ox, oy), (ox + cell_w - 1, oy + cell_h - 1), (180, 180, 180), 1)
            index.setdefault(name, {})[str(k)] = c["id"]
        cv2.imwrite(str(sheet_dir / f"{name}.png"), sheet)
    (sheet_dir / "index.json").write_text(json.dumps(index, indent=1))
    print(f"{len(index)} sheets, {len(todo)} labelled crops to review, in {sheet_dir}")


def main() -> None:
    import scripts_extract_legend_symbols as legend

    root = Path(__file__).resolve().parents[2] / "blueprints_and_libraries" / "2_component_libraries"
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--sources", type=Path, default=root)
    p.add_argument("--out", type=Path, default=root / "harvested_symbols")
    p.add_argument("--agent", default="human")
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("harvest")
    s.add_argument("--workers", type=int, default=max(1, (os.cpu_count() or 2) - 1))
    s.add_argument("--skip", nargs="*", default=["legend_symbol_crops", "harvested_symbols", "neca_100_2013_svgs"],
                   help="folder names to leave out (NECA is loaded by its own bank)")
    s.add_argument("--limit", type=int, default=0)
    s.add_argument("--redo", action="store_true", help="also re-read sources harvested before")
    s.add_argument("--pdf-blobs", action="store_true",
                   help="also cut drawings from legend PDF pages (noisy: line fragments and stroke-drawn letters)")
    s.set_defaults(fn=cmd_harvest)
    s = sub.add_parser("sheets")
    s.set_defaults(fn=cmd_sheets)
    s = sub.add_parser("review")
    s.add_argument("--id")
    s.add_argument("--status", choices=("accepted", "rejected"))
    s.add_argument("--device")
    s.add_argument("--batch")
    s.set_defaults(fn=lambda a: _review(a, legend))
    s = sub.add_parser("devices")
    s.set_defaults(fn=legend.cmd_devices)
    a = p.parse_args()
    a.out.mkdir(parents=True, exist_ok=True)
    a.fn(a)


def _review(a, legend) -> None:
    original = legend.Store
    legend.Store = lambda out: original(out, ABOUT)     # keep this library's description
    try:
        legend.cmd_review(a)
    finally:
        legend.Store = original


if __name__ == "__main__":
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    main()
