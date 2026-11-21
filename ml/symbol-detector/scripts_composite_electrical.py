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


def _overlaps(box, boxes, margin=3.0) -> bool:
    return any(not (box[2] + margin < b[0] or b[2] + margin < box[0] or
                    box[3] + margin < b[1] or b[3] + margin < box[1]) for b in boxes)


def composite_page(doc: dict, image: np.ndarray, rng: np.random.Generator, *, per_page: int,
                   scale_range: tuple[float, float], max_ink: float, tag: str) -> tuple[np.ndarray, list[dict]]:
    page = image.copy()
    h, w = page.shape[:2]
    dark = (cv2.cvtColor(page, cv2.COLOR_BGR2GRAY) < 160).astype(np.uint8)
    integral = cv2.integral(dark)
    occupied = [list(map(float, i['bbox_xyxy'])) for i in doc['instances']]
    placed = []
    attempts = 0
    while len(placed) < per_page and attempts < per_page * 40:
        attempts += 1
        family, variant, rgba, anchors = sample_glyph(rng)
        angle = float(rng.choice([0, 90, 180, 270])) if rng.random() < .8 else float(rng.uniform(-180, 180))
        scale = float(np.exp(rng.uniform(*np.log(scale_range))))
        glyph, pts = rotate_glyph(rgba, anchors, angle, scale)
        gh, gw = glyph.shape[:2]
        if gw + 4 >= w or gh + 4 >= h:
            continue
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
        ink = (integral[by1, bx1] - integral[by0, bx1] - integral[by1, bx0] + integral[by0, bx0]) / max(1, (bx1 - bx0) * (by1 - by0))
        if ink > max_ink:
            continue
        roi = page[y:y + gh, x:x + gw].astype(np.float32)
        a = alpha[:, :, None]
        page[y:y + gh, x:x + gw] = np.clip(roi * (1 - a) + glyph[:, :, :3].astype(np.float32) * a, 0, 255).astype(np.uint8)
        occupied.append(box)
        placed.append({
            'instance_id': f'elec_{tag}_{len(placed):03d}',
            'symbol_family': family,
            'symbol_variant': variant,
            'bbox_xyxy': box,
            'anchors': {k: {'xy': [x + px, y + py], 'visible': True, 'annotated': True}
                        for k, (px, py) in pts.items()},
            'rotation_degrees_image': angle,
            'source': {'kind': 'synthetic_composite', 'label_method': 'known_placement_transform',
                       'glyph_scale': scale},
        })
    return page, placed


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('annotations', nargs='+', help='normalized page JSON paths or globs')
    p.add_argument('output', type=Path)
    p.add_argument('--per-page', type=int, default=18)
    p.add_argument('--copies', type=int, default=1, help='independent composites per page (same group)')
    p.add_argument('--scale-min', type=float, default=.5)
    p.add_argument('--scale-max', type=float, default=1.3)
    p.add_argument('--max-ink', type=float, default=.12,
                   help='skip spots whose box already has more than this fraction of dark pixels')
    p.add_argument('--seed', type=int, default=7)
    a = p.parse_args()
    paths = sorted({Path(f) for pat in a.annotations for f in glob.glob(pat, recursive=True)})
    if not paths:
        raise SystemExit('no annotation JSON matched')
    a.output.mkdir(parents=True, exist_ok=True)
    total = 0
    for n, path in enumerate(paths):
        doc = json.loads(path.read_text())
        if set(doc.get('exhaustive_families', [])) & set(ELECTRICAL):
            raise SystemExit(f'{path}: already claims electrical families; refusing to composite')
        image = cv2.imread(str((path.parent / doc['image_path']).resolve()), cv2.IMREAD_COLOR)
        if image is None:
            raise SystemExit(f'unreadable image for {path}')
        for copy in range(a.copies):
            seed = int.from_bytes(hashlib.sha256(f'{a.seed}/{path.name}/{copy}'.encode()).digest()[:8], 'big')
            stem = f'{path.stem}_el{copy}'
            page, placed = composite_page(doc, image, np.random.default_rng(seed), per_page=a.per_page,
                                          scale_range=(a.scale_min, a.scale_max), max_ink=a.max_ink, tag=stem)
            png = a.output / f'{stem}.png'
            if not cv2.imwrite(str(png), page):
                raise OSError(png)
            new = dict(doc)
            new.update({
                'document_id': f"{doc.get('document_id', path.stem)}_el{copy}",
                'source_type': 'cad_raster_with_synthetic_electrical',
                'image_path': png.name,
                'image_sha256': hashlib.sha256(png.read_bytes()).hexdigest(),
                'exhaustive_families': sorted(set(doc.get('exhaustive_families', [])) | set(ELECTRICAL)),
                'instances': list(doc['instances']) + placed,
                'composite': {'background_annotation': str(path.resolve()), 'seed': seed,
                              'electrical_families': list(ELECTRICAL)},
            })
            png.with_suffix('.json').write_text(json.dumps(new, indent=1))
            total += len(placed)
        if (n + 1) % 200 == 0:
            print(f'{n + 1}/{len(paths)} pages, {total} symbols placed', flush=True)
    print(json.dumps({'pages': len(paths) * a.copies, 'electrical_symbols': total}))


if __name__ == '__main__':
    main()
