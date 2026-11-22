"""Render 50-up CircuitNet bounding-box contact sheets + VLM-editable batch JSONs, one PDF at a time.

Two subcommands:
  1. `render`: Reads `*_CIRCUITNET.json` files one PDF at a time, crops around every
     provisional bounding box at high DPI with surrounding wall/drawing context,
     overlays a crisp bounding box + subtle 0..1000 coordinate ruler grid, and
     composites up to 50 crops (default 10 cols x 5 rows) into a single PNG per batch
     alongside a companion JSON. Partial final batches use only as many rows as needed
     and fill any remaining cells in the final row with pure white.

  2. `apply`: Reads the VLM-refined batch JSONs (where the model sets `keep: false` on
     false positives, updates `refined_bbox_cell` in [0..1000], and sets
     `wall_contact_cell` + `on_wall`), converts all coordinates back to exact
     bottom-left PDF points, and writes one consolidated `*_REFINED_DEVICES.json` per PDF.

Examples:
  # Generate 50-up contact sheets + batch JSONs (one PDF at a time)
  python scripts_circuitnet_review_sheets.py render \
      --proposals-dir ../work/circuitnet_proposals \
      --data-root ../data/blueprints_and_libraries \
      --out ../work/circuitnet_review_sheets \
      --per-sheet 50 --cols 10 --cell-size 256 --dpi 300

  # Apply model-updated batch JSONs back into exact PDF-point device JSONs
  python scripts_circuitnet_review_sheets.py apply \
      --review-dir ../work/circuitnet_review_sheets \
      --out ../work/circuitnet_refined_devices
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
from typing import Any

import cv2
import numpy as np
import pymupdf


COORD_MAX = 1000.0

REFINEMENT_INSTRUCTIONS = (
    "Each numbered cell (#01..#NN) shows a zoomed crop around one provisional CircuitNet "
    "detection (red box) on a 0..1000 coordinate grid (origin at top-left of the image area "
    "below the header bar; light grid lines at 250, 500, 750). For every item in `items`: "
    "(1) Set `keep` to false if it is a false positive / not a real electrical device; "
    "(2) Refine `refined_bbox_cell` = [x0, y0, x1, y1] tightly around the true symbol in 0..1000; "
    "(3) Set `refined_device_type` if the provisional class is wrong; "
    "(4) Set `on_wall` to true if the device mounts on/touches a wall and put `wall_contact_cell` "
    "= [wx, wy] at the exact outside wall contact point, OR set `on_wall` to false and put "
    "`wall_contact_cell` at the refined box center."
)


def pdf_bl_point_to_cell(
    x_bl: float,
    y_bl: float,
    crop_pdf_bl: tuple[float, float, float, float],
) -> tuple[float, float]:
    """Convert a bottom-left PDF point to cell-local [0..1000] top-left coordinates."""
    cx0, cy0, cx1, cy1 = crop_pdf_bl
    cw = max(1e-6, cx1 - cx0)
    ch = max(1e-6, cy1 - cy0)
    u = (x_bl - cx0) / cw * COORD_MAX
    v = (cy1 - y_bl) / ch * COORD_MAX
    return u, v


def pdf_bl_box_to_cell(
    bbox_bl: list[float] | tuple[float, float, float, float],
    crop_pdf_bl: tuple[float, float, float, float],
) -> list[float]:
    """Convert [bx0, by0, bx1, by1] (bottom-left PDF pts) -> [u0, v0, u1, v1] in [0..1000]."""
    bx0, by0, bx1, by1 = (float(v) for v in bbox_bl)
    u0, v0 = pdf_bl_point_to_cell(bx0, by1, crop_pdf_bl)
    u1, v1 = pdf_bl_point_to_cell(bx1, by0, crop_pdf_bl)
    return [round(u0, 1), round(v0, 1), round(u1, 1), round(v1, 1)]


def cell_point_to_pdf_bl(
    u: float,
    v: float,
    crop_pdf_bl: list[float] | tuple[float, float, float, float],
) -> list[float]:
    """Convert cell-local [0..1000] top-left point -> [x, y] in bottom-left PDF points."""
    cx0, cy0, cx1, cy1 = (float(val) for val in crop_pdf_bl)
    cw = cx1 - cx0
    ch = cy1 - cy0
    x_bl = cx0 + (float(u) / COORD_MAX) * cw
    y_bl = cy1 - (float(v) / COORD_MAX) * ch
    return [round(x_bl, 2), round(y_bl, 2)]


def cell_box_to_pdf_bl(
    box_cell: list[float] | tuple[float, float, float, float],
    crop_pdf_bl: list[float] | tuple[float, float, float, float],
) -> list[float]:
    """Convert [u0, v0, u1, v1] in [0..1000] -> [bx0, by0, bx1, by1] in bottom-left PDF points."""
    u0, v0, u1, v1 = (float(val) for val in box_cell)
    if u0 > u1:
        u0, u1 = u1, u0
    if v0 > v1:
        v0, v1 = v1, v0
    bx0, by1 = cell_point_to_pdf_bl(u0, v0, crop_pdf_bl)
    bx1, by0 = cell_point_to_pdf_bl(u1, v1, crop_pdf_bl)
    return [round(bx0, 2), round(by0, 2), round(bx1, 2), round(by1, 2)]


def compute_crop_rect_bl(
    bbox_bl: list[float],
    *,
    context_mult: float = 2.5,
    min_context_pts: float = 36.0,
) -> tuple[float, float, float, float]:
    """Compute a square context window in bottom-left PDF points centered on `bbox_bl`."""
    bx0, by0, bx1, by1 = (float(v) for v in bbox_bl)
    cx = 0.5 * (bx0 + bx1)
    cy = 0.5 * (by0 + by1)
    bw = max(1.0, bx1 - bx0)
    bh = max(1.0, by1 - by0)
    half = max(max(bw, bh) * context_mult, min_context_pts)
    return (round(cx - half, 2), round(cy - half, 2), round(cx + half, 2), round(cy + half, 2))


def render_crop_square(
    page: pymupdf.Page,
    crop_pdf_bl: tuple[float, float, float, float],
    *,
    cell_size: int = 256,
    dpi: float = 300.0,
) -> np.ndarray:
    """Render the exact square `crop_pdf_bl` onto a `cell_size x cell_size` BGR image.

    If the context window extends slightly beyond the PDF page border, out-of-page areas
    are padded with white so X/Y scale and [0..1000] coordinates stay undistorted.
    """
    pw = float(page.rect.width)
    ph = float(page.rect.height)
    cx0, cy0, cx1, cy1 = crop_pdf_bl
    cw = max(1e-6, cx1 - cx0)
    ch = max(1e-6, cy1 - cy0)

    # Convert bottom-left PDF rect -> PyMuPDF top-left rect
    tl_x0, tl_y0 = cx0, ph - cy1
    tl_x1, tl_y1 = cx1, ph - cy0

    # Intersect with actual page bounds
    ix0 = max(0.0, tl_x0)
    iy0 = max(0.0, tl_y0)
    ix1 = min(pw, tl_x1)
    iy1 = min(ph, tl_y1)

    canvas = np.full((cell_size, cell_size, 3), 255, dtype=np.uint8)
    if ix1 <= ix0 or iy1 <= iy0:
        return canvas

    # Choose render scale high enough to avoid upscaling blur, bounded by requested DPI
    scale = max(dpi / 72.0, cell_size / max(cw, ch))
    clip = pymupdf.Rect(ix0, iy0, ix1, iy1)
    pix = page.get_pixmap(matrix=pymupdf.Matrix(scale, scale), clip=clip, colorspace=pymupdf.csRGB, alpha=False)
    rgb = np.frombuffer(pix.samples, dtype=np.uint8).reshape(pix.height, pix.width, 3)
    bgr = cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR)

    # Destination rectangle inside the square canvas
    dx0 = int(round((ix0 - tl_x0) / cw * cell_size))
    dy0 = int(round((iy0 - tl_y0) / ch * cell_size))
    dx1 = int(round((ix1 - tl_x0) / cw * cell_size))
    dy1 = int(round((iy1 - tl_y0) / ch * cell_size))
    dx0 = max(0, min(cell_size - 1, dx0))
    dy0 = max(0, min(cell_size - 1, dy0))
    dx1 = max(dx0 + 1, min(cell_size, dx1))
    dy1 = max(dy0 + 1, min(cell_size, dy1))

    tw, th = dx1 - dx0, dy1 - dy0
    interp = cv2.INTER_AREA if (bgr.shape[1] >= tw and bgr.shape[0] >= th) else cv2.INTER_LINEAR
    canvas[dy0:dy1, dx0:dx1] = cv2.resize(bgr, (tw, th), interpolation=interp)
    return canvas


def draw_annotated_cell(
    crop_bgr: np.ndarray,
    bbox_cell: list[float],
    *,
    slot: int,
    pdf_page: int,
    device_type: str,
    score: float,
    header_h: int = 26,
    draw_grid: bool = True,
) -> np.ndarray:
    """Overlay subtle 0..1000 ruler grid + provisional red box + header bar onto a cell."""
    cell_size = crop_bgr.shape[0]
    img = crop_bgr.copy()

    # Subtle 250-unit coordinate grid (250, 500, 750 in 0..1000 space)
    if draw_grid:
        overlay = img.copy()
        for tick in (250, 500, 750):
            p = int(round((tick / COORD_MAX) * cell_size))
            cv2.line(overlay, (p, 0), (p, cell_size - 1), (185, 210, 235), 1, cv2.LINE_AA)
            cv2.line(overlay, (0, p), (cell_size - 1, p), (185, 210, 235), 1, cv2.LINE_AA)
        cv2.addWeighted(overlay, 0.45, img, 0.55, 0.0, dst=img)
        # Small readable tick labels on top and left edges
        for tick in (250, 500, 750):
            p = int(round((tick / COORD_MAX) * cell_size))
            cv2.putText(img, str(tick), (max(2, p - 10), 10), cv2.FONT_HERSHEY_SIMPLEX, 0.30, (120, 120, 120), 1, cv2.LINE_AA)
            cv2.putText(img, str(tick), (2, min(cell_size - 3, p + 3)), cv2.FONT_HERSHEY_SIMPLEX, 0.30, (120, 120, 120), 1, cv2.LINE_AA)

    # Draw provisional CircuitNet bounding box in vivid red + center dot
    u0, v0, u1, v1 = bbox_cell
    px0 = int(round((u0 / COORD_MAX) * cell_size))
    py0 = int(round((v0 / COORD_MAX) * cell_size))
    px1 = int(round((u1 / COORD_MAX) * cell_size))
    py1 = int(round((v1 / COORD_MAX) * cell_size))
    cv2.rectangle(img, (px0, py0), (px1, py1), (60, 20, 225), 2)
    cx, cy = (px0 + px1) // 2, (py0 + py1) // 2
    cv2.circle(img, (cx, cy), 3, (60, 20, 225), -1)

    # Clean header strip ABOVE the 0..1000 image area (never shifts image coordinates)
    tile = np.full((cell_size + header_h, cell_size, 3), 255, dtype=np.uint8)
    tile[:header_h, :] = (242, 244, 248)
    tile[header_h:, :] = img

    short_cls = device_type[:15]
    label = f'#{slot:02d} p{pdf_page} {short_cls} {score:.2f}'
    cv2.putText(
        tile,
        label,
        (5, header_h - 8),
        cv2.FONT_HERSHEY_SIMPLEX,
        0.42,
        (20, 20, 20),
        1,
        cv2.LINE_AA,
    )
    # Thin outer border separating cells cleanly
    cv2.rectangle(tile, (0, 0), (cell_size - 1, cell_size + header_h - 1), (180, 185, 195), 1)
    return tile


def compose_contact_sheet(tiles: list[np.ndarray], *, cols: int, pad: int = 4) -> np.ndarray:
    """Tile `len(tiles)` cells into `ceil(len(tiles)/cols)` rows x `cols` columns.

    Any unused slots on the final row are filled with pure white (255, 255, 255).
    """
    if not tiles:
        raise ValueError('compose_contact_sheet requires at least one tile')
    th, tw = tiles[0].shape[:2]
    rows = int(math.ceil(len(tiles) / cols))
    sheet_w = cols * tw + (cols + 1) * pad
    sheet_h = rows * th + (rows + 1) * pad
    sheet = np.full((sheet_h, sheet_w, 3), 255, dtype=np.uint8)

    for idx, tile in enumerate(tiles):
        r = idx // cols
        c = idx % cols
        y0 = pad + r * (th + pad)
        x0 = pad + c * (tw + pad)
        sheet[y0:y0 + th, x0:x0 + tw] = tile
    return sheet


def resolve_pdf_path(payload: dict[str, Any], data_root: Path | None) -> Path | None:
    rel = payload.get('source_pdf_relative')
    if data_root is not None and rel:
        cand = (data_root / rel).resolve()
        if cand.is_file():
            return cand
    abs_p = payload.get('source_pdf_absolute')
    if abs_p:
        cand = Path(abs_p)
        if cand.is_file():
            return cand
    return None


def cmd_render(args: argparse.Namespace) -> None:
    proposals_dir = args.proposals_dir.resolve()
    data_root = args.data_root.resolve() if args.data_root else None
    out_root = args.out.resolve()
    out_root.mkdir(parents=True, exist_ok=True)

    json_files = sorted(
        list(proposals_dir.glob('*_CIRCUITNET.json')) + list(proposals_dir.glob('*_SAM3.json'))
    )
    if args.pdf_filter:
        flt = args.pdf_filter.lower()
        json_files = [p for p in json_files if flt in p.name.lower()]
    if args.limit_pdfs > 0:
        json_files = json_files[:args.limit_pdfs]
    if not json_files:
        raise SystemExit(f'No *_CIRCUITNET.json or *_SAM3.json files found in {proposals_dir}')

    per_sheet = int(args.per_sheet)
    cols = int(args.cols)
    cell_size = int(args.cell_size)

    total_pdfs = 0
    total_sheets = 0
    total_crops = 0
    pdf_index: list[dict[str, Any]] = []

    for jpath in json_files:
        payload = json.loads(jpath.read_text())
        raw_devices = payload.get('devices', [])
        indexed_devices = [
            (dev_idx, dev)
            for dev_idx, dev in enumerate(raw_devices)
            if float(dev.get('score', 0.0)) >= args.min_score
        ]
        if not indexed_devices:
            print(f'SKIP {jpath.name}: 0 devices (min_score={args.min_score})', flush=True)
            continue

        pdf_path = resolve_pdf_path(payload, data_root)
        if pdf_path is None:
            print(f'SKIP {jpath.name}: source PDF not found ({payload.get("source_pdf_relative")})', flush=True)
            continue

        pdf_stem = jpath.name.removesuffix('_CIRCUITNET.json').removesuffix('_SAM3.json')
        pdf_out_dir = out_root / pdf_stem
        pdf_out_dir.mkdir(parents=True, exist_ok=True)

        doc = pymupdf.open(pdf_path)
        page_cache: dict[int, pymupdf.Page] = {}
        sheets_for_pdf = 0
        pdf_batches_meta: list[dict[str, Any]] = []
        try:
            n_batches = int(math.ceil(len(indexed_devices) / per_sheet))
            for b_idx in range(n_batches):
                batch_slice = indexed_devices[b_idx * per_sheet:(b_idx + 1) * per_sheet]
                tiles: list[np.ndarray] = []
                items: list[dict[str, Any]] = []

                for slot, (dev_idx, dev) in enumerate(batch_slice, start=1):
                    pnum = int(dev['pdf_page'])
                    if pnum not in page_cache:
                        page_cache[pnum] = doc.load_page(pnum - 1)
                    page = page_cache[pnum]

                    bbox_bl = [float(v) for v in dev['symbol_bbox']]
                    crop_bl = compute_crop_rect_bl(
                        bbox_bl,
                        context_mult=args.context_mult,
                        min_context_pts=args.min_context_pts,
                    )
                    bbox_cell = pdf_bl_box_to_cell(bbox_bl, crop_bl)
                    crop_bgr = render_crop_square(
                        page,
                        crop_bl,
                        cell_size=cell_size,
                        dpi=args.dpi,
                    )
                    tile = draw_annotated_cell(
                        crop_bgr,
                        bbox_cell,
                        slot=slot,
                        pdf_page=pnum,
                        device_type=str(dev.get('device_type', 'unknown')),
                        score=float(dev.get('score', 0.0)),
                        draw_grid=not args.no_grid,
                    )
                    tiles.append(tile)

                    cx_cell = round(0.5 * (bbox_cell[0] + bbox_cell[2]), 1)
                    cy_cell = round(0.5 * (bbox_cell[1] + bbox_cell[3]), 1)
                    items.append({
                        'slot': slot,
                        'grid_pos': {'row': (slot - 1) // cols + 1, 'col': (slot - 1) % cols + 1},
                        'device_index': dev_idx,
                        'pdf_page': pnum,
                        'provisional_device_type': dev.get('device_type'),
                        'score': dev.get('score'),
                        'crop_pdf_rect_bl': list(crop_bl),
                        'provisional_bbox_cell': bbox_cell,
                        'provisional_bbox_pdf_bl': bbox_bl,
                        # Fields for the refinement model to update:
                        'keep': True,
                        'refined_device_type': dev.get('device_type'),
                        'refined_bbox_cell': bbox_cell,
                        'on_wall': None,
                        'wall_contact_cell': [cx_cell, cy_cell],
                    })

                sheet = compose_contact_sheet(tiles, cols=cols)
                rows_used = int(math.ceil(len(tiles) / cols))
                batch_name = f'{pdf_stem}_batch_{b_idx + 1:03d}'
                img_path = pdf_out_dir / f'{batch_name}.png'
                json_path = pdf_out_dir / f'{batch_name}.json'
                if not cv2.imwrite(str(img_path), sheet):
                    raise OSError(f'Failed writing {img_path}')

                batch_payload = {
                    'schema': 'circuitnet_refinement_batch_v1',
                    'source_file': payload.get('source_file'),
                    'source_pdf_relative': payload.get('source_pdf_relative'),
                    'source_pdf_absolute': payload.get('source_pdf_absolute'),
                    'source_circuitnet_json': str(jpath),
                    'batch_index': b_idx + 1,
                    'total_batches_for_pdf': n_batches,
                    'image_file': img_path.name,
                    'grid': {
                        'cols': cols,
                        'rows': rows_used,
                        'slots_filled': len(tiles),
                        'slots_white_padded': rows_used * cols - len(tiles),
                        'cell_image_px': cell_size,
                        'cell_coord_range': [0, int(COORD_MAX)],
                        'cell_coord_origin': 'top_left_of_crop_below_header',
                    },
                    'instructions': REFINEMENT_INSTRUCTIONS,
                    'items': items,
                }
                json_path.write_text(json.dumps(batch_payload, indent=2))
                pdf_batches_meta.append({
                    'batch_index': b_idx + 1,
                    'image_file': img_path.name,
                    'json_file': json_path.name,
                    'slots_filled': len(tiles),
                    'rows': rows_used,
                    'cols': cols,
                })
                sheets_for_pdf += 1
                total_sheets += 1
                total_crops += len(tiles)
        finally:
            doc.close()

        manifest = {
            'pdf_folder': pdf_stem,
            'source_file': payload.get('source_file'),
            'source_pdf_relative': payload.get('source_pdf_relative'),
            'source_circuitnet_json': str(jpath),
            'total_devices': len(indexed_devices),
            'total_batches': sheets_for_pdf,
            'batches': pdf_batches_meta,
        }
        (pdf_out_dir / 'manifest.json').write_text(json.dumps(manifest, indent=2))
        pdf_index.append({
            'folder': pdf_stem,
            'source_file': payload.get('source_file'),
            'total_devices': len(indexed_devices),
            'total_batches': sheets_for_pdf,
        })

        total_pdfs += 1
        print(
            f'[{total_pdfs}] {pdf_stem}: {len(indexed_devices)} detections -> '
            f'{sheets_for_pdf} sheet(s) in {pdf_out_dir}',
            flush=True,
        )

    (out_root / 'index.json').write_text(json.dumps({
        'pdfs_processed': total_pdfs,
        'sheets_written': total_sheets,
        'crops_rendered': total_crops,
        'folders': pdf_index,
    }, indent=2))

    print(
        json.dumps({
            'pdfs_processed': total_pdfs,
            'sheets_written': total_sheets,
            'crops_rendered': total_crops,
            'out_dir': str(out_root),
        }, indent=2),
        flush=True,
    )


def apply_batch_updates_to_devices(batch_payloads: list[dict[str, Any]]) -> dict[str, Any]:
    """Convert a PDF's refined batch JSONs back into exact bottom-left PDF-point devices."""
    if not batch_payloads:
        raise ValueError('batch_payloads must be non-empty')

    first = batch_payloads[0]
    kept_devices: list[dict[str, Any]] = []
    removed_count = 0
    wall_count = 0
    center_count = 0

    for batch in sorted(batch_payloads, key=lambda b: int(b.get('batch_index', 0))):
        for item in batch.get('items', []):
            if not item.get('keep', True):
                removed_count += 1
                continue
            crop_bl = item['crop_pdf_rect_bl']
            box_cell = item.get('refined_bbox_cell') or item['provisional_bbox_cell']
            bbox_bl = cell_box_to_pdf_bl(box_cell, crop_bl)
            cx = round(0.5 * (bbox_bl[0] + bbox_bl[2]), 2)
            cy = round(0.5 * (bbox_bl[1] + bbox_bl[3]), 2)

            on_wall = item.get('on_wall')
            wc_cell = item.get('wall_contact_cell')
            if on_wall is True and wc_cell is not None:
                wall_pt = cell_point_to_pdf_bl(wc_cell[0], wc_cell[1], crop_bl)
                wall_count += 1
            else:
                wall_pt = [cx, cy]
                center_count += 1

            vec_pts = [round(wall_pt[0] - cx, 2), round(wall_pt[1] - cy, 2)]
            kept_devices.append({
                'device_index': item.get('device_index'),
                'pdf_page': int(item['pdf_page']),
                'device_type': item.get('refined_device_type') or item.get('provisional_device_type'),
                'provisional_device_type': item.get('provisional_device_type'),
                'score': item.get('score'),
                'symbol_bbox': bbox_bl,
                'symbol_center': [cx, cy],
                'on_wall': bool(on_wall) if on_wall is not None else False,
                'wall_connection_point': wall_pt,
                'target_vector_pts': vec_pts,
                'status': 'refined_from_circuitnet',
            })

    return {
        'source_file': first.get('source_file'),
        'source_pdf_relative': first.get('source_pdf_relative'),
        'source_pdf_absolute': first.get('source_pdf_absolute'),
        'source_circuitnet_json': first.get('source_circuitnet_json'),
        'coordinate_system': {
            'origin': 'bottom_left',
            'units': 'PDF_points',
            'reference': 'display_oriented_page_after_applying_pdf_rotation',
            'symbol_bbox_format': '[x_min,y_min,x_max,y_max]',
        },
        'summary': {
            'kept_devices': len(kept_devices),
            'removed_false_positives': removed_count,
            'wall_mounted': wall_count,
            'free_space_center': center_count,
        },
        'devices': kept_devices,
    }


def cmd_apply(args: argparse.Namespace) -> None:
    review_dir = args.review_dir.resolve()
    out_dir = args.out.resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    pdf_dirs = sorted(p for p in review_dir.iterdir() if p.is_dir())
    if not pdf_dirs:
        raise SystemExit(f'No per-PDF folders found under {review_dir}')

    written = 0
    for pdir in pdf_dirs:
        batch_jsons = sorted(pdir.glob('*_batch_*.json'))
        if not batch_jsons:
            continue
        batches = [json.loads(bp.read_text()) for bp in batch_jsons]
        refined = apply_batch_updates_to_devices(batches)
        out_path = out_dir / f'{pdir.name}_REFINED_DEVICES.json'
        out_path.write_text(json.dumps(refined, indent=2))
        s = refined['summary']
        print(
            f'{pdir.name}: kept={s["kept_devices"]} removed={s["removed_false_positives"]} '
            f'(wall={s["wall_mounted"]}, center={s["free_space_center"]}) -> {out_path.name}',
            flush=True,
        )
        written += 1

    print(f'DONE: wrote {written} refined PDF device JSON(s) to {out_dir}', flush=True)


def build_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest='command', required=True)

    r = sub.add_parser('render', help='Render 50-up contact sheets + editable batch JSONs per PDF')
    r.add_argument('--proposals-dir', type=Path, required=True, help='Directory containing *_CIRCUITNET.json files')
    r.add_argument('--data-root', type=Path, default=None, help='Root folder to resolve source_pdf_relative')
    r.add_argument('--out', type=Path, required=True, help='Output directory for per-PDF contact sheets & JSONs')
    r.add_argument('--per-sheet', type=int, default=50, help='Max crops composited into one sheet (default: 50)')
    r.add_argument('--cols', type=int, default=10, help='Columns per contact sheet row (default: 10)')
    r.add_argument('--cell-size', type=int, default=256, help='Square crop image size in px per cell (default: 256)')
    r.add_argument('--dpi', type=float, default=300.0, help='PDF crop render DPI (default: 300)')
    r.add_argument('--context-mult', type=float, default=2.5, help='Half-window size = max(w,h) * context_mult')
    r.add_argument('--min-context-pts', type=float, default=36.0, help='Minimum half-window size in PDF points')
    r.add_argument('--min-score', type=float, default=0.0, help='Optional minimum CircuitNet score filter')
    r.add_argument('--pdf-filter', type=str, default='', help='Optional substring filter on *_CIRCUITNET.json name')
    r.add_argument('--limit-pdfs', type=int, default=0, help='Optional cap on number of PDFs (0 = all)')
    r.add_argument('--no-grid', action='store_true', help='Disable the 0..1000 coordinate helper grid overlay')
    r.set_defaults(func=cmd_render)

    a = sub.add_parser('apply', help='Convert model-refined batch JSONs back into PDF-point device JSONs')
    a.add_argument('--review-dir', type=Path, required=True, help='Output folder from `render` with updated batch JSONs')
    a.add_argument('--out', type=Path, required=True, help='Output folder for *_REFINED_DEVICES.json files')
    a.set_defaults(func=cmd_apply)

    return ap


def main() -> None:
    parser = build_parser()
    args = parser.parse_args()
    args.func(args)


if __name__ == '__main__':
    main()
