"""Composite procedurally drawn electrical symbols onto real, labeled floor plans.

    python -I scripts_composite_electrical.py 'normalized/floorplancad/*.json' \
        composited/floorplancad --per-page 18 --seed 7

Every input page must be a normalized annotation JSON (for example from
adapters/import_floorplancad_fiftyone.py). For each page this writes a new
PNG + JSON that keeps all original instances and adds the placed electrical
symbols with exact boxes and anchors.

Why composite onto real plans rather than blank sheets: the detector has to
learn receptacles *against* walls, doors, text and furniture, which blank
sheets do not have. The page keeps its original project group, so every
composite of a drawing stays in that drawing's split.

`exhaustive_families` becomes the page's original list plus every electrical
family this script can place. That claim assumes the background plans have
no electrical symbols of their own. That holds for architectural sets like
FloorPlanCAD and CubiCasa, but NOT for electrical, RCP or MEP sheets, so
never run this on those. Pages with an electrical family already in
`exhaustive_families` are refused for the same reason.
"""
from __future__ import annotations

import argparse
import glob
import hashlib
import json
import math
from pathlib import Path

import cv2
import numpy as np

from blueprint_symbols.electrical_glyphs import DEFAULT_WEIGHTS, rotate_glyph, sample_glyph

ELECTRICAL = tuple(DEFAULT_WEIGHTS)

CANONICAL_ELECTRICAL_MAP = {
    'receptacle': 'receptacle',
    'switch': 'switch_sensor',
    'luminaire': 'luminaire',
    'junction_box': 'junction_box',
    'fire_alarm_device': 'fire_alarm',
    'data_outlet': 'other_device',
    'panelboard': 'other_device',
}


def _find_wall_sites(gray: np.ndarray, rng: np.random.Generator, max_sites: int = 800) -> list[tuple[int, int, int, int]]:
    """Detect straight wall pixels and return (wx, wy, nx, ny) sites where (nx, ny) points into open space."""
    h, w = gray.shape
    scale = min(1.0, 1600.0 / max(h, w))
    small = cv2.resize(gray, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA) if scale < 1.0 else gray
    sh, sw = small.shape
    ink = (small < 165).astype(np.uint8)
    k = max(20, min(80, max(sh, sw) // 45))
    long_h = cv2.morphologyEx(ink, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (k, 1)))
    long_v = cv2.morphologyEx(ink, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (1, k)))

    sites: list[tuple[int, int, int, int]] = []
    hy, hx = np.nonzero(long_h > 0)
    if len(hx):
        idx = rng.choice(len(hx), size=min(max_sites // 2, len(hx)), replace=False)
        for i in idx:
            sx, sy = int(hx[i]), int(hy[i])
            if sy < 12 or sy >= sh - 12 or sx < 12 or sx >= sw - 12:
                continue
            above = float(ink[max(0, sy - 14):max(1, sy - 2), max(0, sx - 6):min(sw, sx + 7)].mean())
            below = float(ink[min(sh - 1, sy + 2):min(sh, sy + 14), max(0, sx - 6):min(sw, sx + 7)].mean())
            ny = -1 if above <= below else 1
            sites.append((int(round(sx / scale)), int(round(sy / scale)), 0, ny))

    vy, vx = np.nonzero(long_v > 0)
    if len(vx):
        idx = rng.choice(len(vx), size=min(max_sites // 2, len(vx)), replace=False)
        for i in idx:
            sx, sy = int(vx[i]), int(vy[i])
            if sy < 12 or sy >= sh - 12 or sx < 12 or sx >= sw - 12:
                continue
            left = float(ink[max(0, sy - 6):min(sh, sy + 7), max(0, sx - 14):max(1, sx - 2)].mean())
            right = float(ink[max(0, sy - 6):min(sh, sy + 7), min(sw - 1, sx + 2):min(sw, sx + 14)].mean())
            nx = -1 if left <= right else 1
            sites.append((int(round(sx / scale)), int(round(sy / scale)), nx, 0))

    rng.shuffle(sites)
    return sites


def _overlaps(box, boxes, margin=3.0) -> bool:
    return any(not (box[2] + margin < b[0] or b[2] + margin < box[0] or
                    box[3] + margin < b[1] or b[3] + margin < box[1]) for b in boxes)


GLYPH_STROKE_PX = 1.8   # mean stroke width of the procedural glyphs at scale 1


def estimate_stroke(gray: np.ndarray) -> float:
    """Typical drafting line width of a page, in pixels (median dark run length)."""
    dark = gray < 160
    runs = []
    for axis_img in (dark, dark.T):
        sample = axis_img[::max(1, axis_img.shape[0] // 400)]
        d = np.diff(np.pad(sample.astype(np.int8), ((0, 0), (1, 1))), axis=1)
        starts, ends = np.nonzero(d == 1), np.nonzero(d == -1)
        lengths = ends[1] - starts[1]
        runs.append(lengths[(lengths > 0) & (lengths <= 12)])
    allr = np.concatenate(runs) if runs else np.array([])
    return float(np.median(allr)) if len(allr) > 200 else 2.0


def composite_page(doc: dict, image: np.ndarray, rng: np.random.Generator, *, per_page: int,
                   scale_range: tuple[float, float], max_ink: float, tag: str,
                   stroke_match: bool = True, wall_fraction: float = 0.6,
                   canonical_classes: bool = False,
                   symbol_chaos_probability: float = 0.0,
                   symbol_chaos_strength: float = 1.0,
                   placement_chaos: float = 0.0,
                   drawing_dpi: float | None = None,
                   match_local_ink: bool = False,
                   ink_reference: np.ndarray | None = None) -> tuple[np.ndarray, list[dict]]:
    from blueprint_symbols.symbol_chaos import distort_symbol_alpha, sample_free_origin
    from blueprint_symbols.ink_style import local_drawing_ink_bgr

    if drawing_dpi is not None and not (math.isfinite(drawing_dpi) and
                                        100 <= drawing_dpi <= 400):
        raise ValueError("drawing_dpi must be in [100,400]")
    reference = image if ink_reference is None else ink_reference
    if reference.shape != image.shape or reference.dtype != image.dtype:
        raise ValueError("ink_reference must match input drawing shape/dtype")
    if not 0 <= placement_chaos <= 1:
        raise ValueError("placement_chaos must be in [0,1]")
    page = image.copy()
    h, w = page.shape[:2]
    gray = cv2.cvtColor(page, cv2.COLOR_BGR2GRAY)
    dark = (gray < 160).astype(np.uint8)
    integral = cv2.integral(dark)
    wall_sites = _find_wall_sites(gray, rng) if wall_fraction > 0 and per_page > 0 else []
    wall_cursor = 0
    # Match glyph line weight to the page so symbols sit at a plausible drawing scale.
    reference_scale = (drawing_dpi / 200.0) if drawing_dpi is not None else 1.0
    base = (estimate_stroke(gray) / (GLYPH_STROKE_PX * reference_scale)
            if stroke_match else 1.0)
    base = float(np.clip(base, .65, 1.55)) if drawing_dpi is not None else float(
        np.clip(base, .4, 6.0)
    )
    ink_bgr = np.percentile(page[dark.astype(bool)], 10, axis=0) if dark.any() else np.zeros(3)
    occupied = [list(map(float, i['bbox_xyxy'])) for i in doc['instances']]
    placed = []
    attempts = 0
    wall_probability = (
        float(rng.uniform(.10, .90))
        if placement_chaos and rng.random() < placement_chaos else wall_fraction
    )
    while len(placed) < per_page and attempts < per_page * 50:
        attempts += 1
        family, variant, rgba, anchors = sample_glyph(rng)
        scale = base * float(np.exp(rng.uniform(*np.log(scale_range))))
        if drawing_dpi is not None:
            # Scale the complete procedural glyph at the crop's actual PDF
            # rasterization DPI, using the same 200dpi size convention as
            # the NECA SVG compositor. Preserve the glyph's physical scale
            # across 100–400dpi drawings while bounding its on-canvas size.
            scale *= drawing_dpi / 200.0
            max_dim = max(rgba.shape[:2])
            scale = min(scale, max(8.0, min(h, w) * .38) / max_dim)

        want_wall = bool(wall_sites) and (rng.random() < wall_probability)
        chaos_details = {"severity": "unchanged", "operations": [], "rejected": 0}
        placement_mode = "wall" if want_wall else "uniform"
        if want_wall:
            wx, wy, nx, ny = wall_sites[wall_cursor % len(wall_sites)]
            wall_cursor += 1
            # Orient symbol so its bottom/attachment edge touches the wall and body extends along (nx, ny)
            if ny == -1:
                angle = 0.0
            elif ny == 1:
                angle = 180.0
            elif nx == -1:
                angle = 90.0
            else:
                angle = 270.0
            anchors_with_contact = dict(anchors)
            if 'wall_attachment' not in anchors_with_contact:
                anchors_with_contact['wall_attachment'] = (0.5, 1.0)
            glyph, pts = rotate_glyph(rgba, anchors_with_contact, angle, scale)
            if symbol_chaos_probability:
                old_h, old_w = glyph.shape[:2]
                warped, chaos_details = distort_symbol_alpha(
                    glyph[:, :, 3], rng, probability=symbol_chaos_probability,
                    strength=symbol_chaos_strength,
                )
                gh, gw = warped.shape
                glyph = np.zeros((gh, gw, 4), dtype=np.uint8)
                glyph[:, :, 3] = warped
                pts = {key: (px * gw / old_w, py * gh / old_h)
                       for key, (px, py) in pts.items()}
                # For warped wall-mounted symbols, use the new outer edge as
                # the approximate physical contact point, never the distorted
                # interior of the mask.
                anchor = ((gw / 2, gh) if ny == -1 else
                          (gw / 2, 0) if ny == 1 else
                          (gw, gh / 2) if nx == -1 else (0, gh / 2))
                pts["wall_attachment"] = anchor
            gh, gw = glyph.shape[:2]
            if gw + 4 >= w or gh + 4 >= h:
                continue
            ax, ay = pts['wall_attachment']
            x = int(round(wx - ax))
            y = int(round(wy - ay))
            if x < 2 or y < 2 or x + gw >= w - 2 or y + gh >= h - 2:
                continue
        else:
            angle = float(rng.choice([0, 90, 180, 270])) if rng.random() < .8 else float(rng.uniform(-180, 180))
            glyph, pts = rotate_glyph(rgba, anchors, angle, scale)
            if symbol_chaos_probability:
                old_h, old_w = glyph.shape[:2]
                warped, chaos_details = distort_symbol_alpha(
                    glyph[:, :, 3], rng, probability=symbol_chaos_probability,
                    strength=symbol_chaos_strength,
                )
                gh, gw = warped.shape
                glyph = np.zeros((gh, gw, 4), dtype=np.uint8)
                glyph[:, :, 3] = warped
                pts = {key: (px * gw / old_w, py * gh / old_h)
                       for key, (px, py) in pts.items()}
            gh, gw = glyph.shape[:2]
            if gw + 4 >= w or gh + 4 >= h:
                continue
            if placement_chaos and gw + 10 < w and gh + 10 < h:
                x, y, placement_mode = sample_free_origin(
                    rng, (h, w), (gh, gw), occupied, chaos=placement_chaos,
                )
            else:
                x = int(rng.integers(2, w - gw - 2))
                y = int(rng.integers(2, h - gh - 2))

        alpha = glyph[:, :, 3].astype(np.float32) / 255
        ys, xs = np.nonzero(alpha > .1)
        if len(xs) == 0:
            continue
        box = [x + float(xs.min()), y + float(ys.min()), x + float(xs.max()) + 1, y + float(ys.max()) + 1]
        if _overlaps(box, occupied):
            continue
        bx0, by0, bx1, by1 = (int(v) for v in box)
        # Allow slightly higher ink at the edge for wall-touching symbols (since one edge touches the wall!)
        ink_cap = max_ink + (0.14 if want_wall else 0.0)
        ink = (integral[by1, bx1] - integral[by0, bx1] - integral[by1, bx0] + integral[by0, bx0]) / max(1, (bx1 - bx0) * (by1 - by0))
        if ink > ink_cap:
            continue
        roi = page[y:y + gh, x:x + gw].astype(np.float32)
        a = alpha[:, :, None]
        # Ink darkens paper (multiply), in the page's own ink colour, so photos stay tinted.
        ink_col = (
            np.asarray(local_drawing_ink_bgr(reference, box), dtype=np.float32)
            if match_local_ink else
            np.clip(ink_bgr + rng.normal(0, 8, 3), 0, 200).astype(np.float32)
        )
        inked = roi * (1 - a) + np.minimum(roi, ink_col) * a
        page[y:y + gh, x:x + gw] = np.clip(inked, 0, 255).astype(np.uint8)
        occupied.append(box)

        cx = 0.5 * (box[0] + box[2])
        cy = 0.5 * (box[1] + box[3])
        if want_wall:
            target_xy = [float(wx), float(wy)]
            target_vec = [float(wx - cx), float(wy - cy)]
        else:
            target_xy = [float(cx), float(cy)]
            target_vec = [0.0, 0.0]

        out_family = CANONICAL_ELECTRICAL_MAP[family] if canonical_classes else family
        # A nonlinear glyph warp does not preserve the old analytic anchor
        # coordinates. Keep approximate points for illustration but mark
        # them unannotated to avoid supervised keypoint label pollution.
        precise_anchors = not bool(chaos_details["operations"])
        out_anchors = {k: {'xy': [x + px, y + py], 'visible': True,
                           'annotated': precise_anchors}
                       for k, (px, py) in pts.items()}
        out_anchors['wall_connection_point'] = {
            'xy': target_xy, 'visible': True, 'annotated': precise_anchors,
        }

        placed.append({
            'instance_id': f'elec_{tag}_{len(placed):03d}',
            'symbol_family': out_family,
            'symbol_variant': variant,
            'bbox_xyxy': box,
            'on_wall': bool(want_wall),
            'wall_connection_point': target_xy,
            'target_vector': target_vec,
            'anchors': out_anchors,
            'rotation_degrees_image': angle,
            'symbol_chaos': chaos_details,
            'source': {'kind': 'synthetic_composite', 'label_method': ('warped_bbox_only' if chaos_details["operations"]
                                        else 'known_placement_transform'),
                       'glyph_scale': scale, 'drawing_dpi': drawing_dpi,
                       'ink_color_bgr': [int(round(v)) for v in ink_col],
                       'placement_mode': 'wall_touching' if want_wall else placement_mode},
        })
    return page, placed


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('annotations', nargs='+', help='normalized page JSON paths or globs')
    p.add_argument('output', type=Path)
    p.add_argument('--per-page', type=int, default=0,
                   help='fixed symbols per page; 0 = scale with area via --per-mp')
    p.add_argument('--per-mp', type=float, default=14.0, help='symbols per megapixel (min 6, max 300)')
    p.add_argument('--no-stroke-match', action='store_true',
                   help='use raw --scale-min/max instead of matching the page line weight')
    p.add_argument('--keep-families', default='',
                   help='comma list of other labeled families to keep as classes; verified labels of '
                        'any other family are dropped (they become background), uncertain ones are kept')
    p.add_argument('--copies', type=int, default=1, help='independent composites per page (same group)')
    p.add_argument('--scale-min', type=float, default=.6, help='random factor on top of the stroke match')
    p.add_argument('--scale-max', type=float, default=1.6)
    p.add_argument('--max-ink', type=float, default=.12,
                   help='skip spots whose box already has more than this fraction of dark pixels')
    p.add_argument('--negative', action='store_true',
                   help='place nothing: pages pass through as verified electrical-free negatives')
    p.add_argument('--wall-fraction', type=float, default=0.6,
                   help='fraction of placed symbols snapped touching a detected wall (with wall_connection_point & target_vector)')
    p.add_argument('--canonical-classes', action='store_true',
                   help='map glyph families to the 6 canonical Phase 2 device classes (matching *DEVICES.json)')
    p.add_argument('--limit', type=int, default=0,
                   help='randomly subsample at most N input pages (0 = all)')
    p.add_argument('--seed', type=int, default=7)
    a = p.parse_args()
    paths = sorted({Path(f) for pat in a.annotations for f in glob.glob(pat, recursive=True)})
    if not paths:
        raise SystemExit('no annotation JSON matched')
    if a.limit > 0 and len(paths) > a.limit:
        sel_rng = np.random.default_rng(a.seed)
        idx = sorted(sel_rng.choice(len(paths), size=a.limit, replace=False))
        paths = [paths[i] for i in idx]
    a.output.mkdir(parents=True, exist_ok=True)
    out_electrical = tuple(sorted(set(CANONICAL_ELECTRICAL_MAP.values()))) if a.canonical_classes else ELECTRICAL
    total = 0
    wall_total = 0
    for n, path in enumerate(paths):
        doc = json.loads(path.read_text())
        if set(doc.get('exhaustive_families', [])) & (set(ELECTRICAL) | set(out_electrical)):
            raise SystemExit(f'{path}: already claims electrical families; refusing to composite')
        src_img = (path.parent / doc['image_path']).resolve()
        image = cv2.imread(str(src_img), cv2.IMREAD_COLOR)
        if image is None:
            raise SystemExit(f'unreadable image for {path}')
        keep = set(out_electrical) | {f for f in a.keep_families.split(',') if f}
        base_instances = [i for i in doc['instances']
                          if i.get('uncertain') or i.get('symbol_family') in keep
                          or i.get('symbol_family') in ('__unmapped__', None)]
        doc = dict(doc, instances=base_instances,
                   exhaustive_families=[f for f in doc.get('exhaustive_families', []) if f in keep])
        mp = image.shape[0] * image.shape[1] / 1e6
        count = 0 if a.negative else (a.per_page or int(np.clip(round(a.per_mp * mp), 6, 300)))
        for copy in range(a.copies):
            seed = int.from_bytes(hashlib.sha256(f'{a.seed}/{path.name}/{copy}'.encode()).digest()[:8], 'big')
            stem = f'{path.stem}_el{copy}'
            page, placed = composite_page(doc, image, np.random.default_rng(seed), per_page=count,
                                          scale_range=(a.scale_min, a.scale_max), max_ink=a.max_ink, tag=stem,
                                          stroke_match=not a.no_stroke_match,
                                          wall_fraction=a.wall_fraction,
                                          canonical_classes=a.canonical_classes)
            ext = '.jpg' if src_img.suffix.lower() in ('.jpg', '.jpeg') else '.png'
            png = a.output / f'{stem}{ext}'
            if not cv2.imwrite(str(png), page, [cv2.IMWRITE_JPEG_QUALITY, 93] if ext == '.jpg' else []):
                raise OSError(png)
            new = dict(doc)
            new.update({
                'document_id': f"{doc.get('document_id', path.stem)}_el{copy}",
                'source_type': 'cad_raster_with_synthetic_electrical',
                'image_path': png.name,
                'image_sha256': hashlib.sha256(png.read_bytes()).hexdigest(),
                'exhaustive_families': sorted(set(doc.get('exhaustive_families', [])) | set(out_electrical)),
                'instances': list(doc['instances']) + placed,
                'composite': {'background_annotation': str(path.resolve()), 'seed': seed,
                              'electrical_families': list(out_electrical)},
            })
            png.with_suffix('.json').write_text(json.dumps(new, indent=1))
            total += len(placed)
            wall_total += sum(1 for item in placed if item.get('on_wall'))
        if (n + 1) % 100 == 0:
            print(f'{n + 1}/{len(paths)} pages, {total} symbols placed ({wall_total} wall-touching)', flush=True)
    print(json.dumps({'pages': len(paths) * a.copies, 'electrical_symbols': total,
                      'wall_touching': wall_total, 'free_space': total - wall_total}))


if __name__ == '__main__':
    main()
