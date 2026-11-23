"""Composite NECA SVG symbols without inventing negative labels on unreviewed PDF pages.

    python scripts_composite_neca_svg.py \
        --neca-dir ../data/blueprints_and_libraries/2_component_libraries/neca_100_2013_svgs/NECA_100_Vector_Library \
        --backgrounds '../work/pool/background/*new_downloads_202611*.json' \
        --output ../work/neca_composited \
        --per-page 24 --wall-fraction 0.60 --limit 400 --preview 3
"""
from __future__ import annotations

import argparse
import glob
import hashlib
import json
from pathlib import Path

import cv2
import numpy as np

from blueprint_symbols.neca_svg_compositor import CANONICAL_CLASSES, NecaSymbolBank, composite_neca_page
from blueprint_symbols.composite_supervision import composite_supervision


def draw_preview(image: np.ndarray, placed: list[dict], out_path: Path) -> None:
    vis = image.copy()
    for inst in placed:
        x0, y0, x1, y1 = (int(round(v)) for v in inst['bbox_xyxy'])
        cx, cy = int(round(0.5 * (x0 + x1))), int(round(0.5 * (y0 + y1)))
        tx, ty = (int(round(v)) for v in inst['wall_connection_point'])
        color = (0, 180, 0) if inst['on_wall'] else (200, 100, 0)
        cv2.rectangle(vis, (x0, y0), (x1, y1), color, 2)
        if inst['on_wall']:
            cv2.arrowedLine(vis, (cx, cy), (tx, ty), (0, 0, 255), 2, tipLength=0.35)
            cv2.circle(vis, (tx, ty), 4, (0, 0, 255), -1)
        else:
            cv2.circle(vis, (cx, cy), 4, (255, 0, 180), -1)
    # Downscale huge preview sheets so they're easy to inspect
    h, w = vis.shape[:2]
    if max(h, w) > 2400:
        s = 2400.0 / max(h, w)
        vis = cv2.resize(vis, None, fx=s, fy=s, interpolation=cv2.INTER_AREA)
    cv2.imwrite(str(out_path), vis)


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--neca-dir', type=Path, required=True, help='Path to NECA_100_Vector_Library folder')
    p.add_argument('--backgrounds', nargs='+', required=True, help='Globs for source page JSONs; unverified backgrounds remain positive-only')
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--per-page', type=int, default=24)
    p.add_argument('--copies', type=int, default=1)
    p.add_argument('--wall-fraction', type=float, default=0.60)
    p.add_argument('--min-px', type=float, default=20.0)
    p.add_argument('--max-px', type=float, default=64.0)
    p.add_argument('--limit', type=int, default=0)
    p.add_argument('--preview', type=int, default=3, help='Write N annotated preview overlays into output/_previews')
    p.add_argument('--seed', type=int, default=2026)
    a = p.parse_args()

    bank = NecaSymbolBank(a.neca_dir.resolve())
    print('Loaded NECA 100-2013 SVG bank:', json.dumps(bank.summary()), flush=True)

    paths = sorted({
        Path(f) for pat in a.backgrounds for f in glob.glob(pat, recursive=True)
        if 'spec' not in Path(f).stem.lower()
    })
    if not paths:
        paths = sorted({Path(f) for pat in a.backgrounds for f in glob.glob(pat, recursive=True)})
    if not paths:
        raise SystemExit('no background page JSON matched')
    if a.limit > 0 and len(paths) > a.limit:
        sel_rng = np.random.default_rng(a.seed)
        idx = sorted(sel_rng.choice(len(paths), size=a.limit, replace=False))
        paths = [paths[i] for i in idx]

    a.output.mkdir(parents=True, exist_ok=True)
    prev_dir = a.output / '_previews'
    if a.preview > 0:
        prev_dir.mkdir(parents=True, exist_ok=True)

    total = wall_total = free_total = pages_written = 0
    for n, path in enumerate(paths):
        doc = json.loads(path.read_text())
        # Validate source attestations before creating derivative files. A
        # partial/unknown background may still produce synthetic positives,
        # but can NEVER produce class-wide background-negative labels.
        composite_supervision(doc, [], generated_families=CANONICAL_CLASSES)
        src_img = (path.parent / doc['image_path']).resolve()
        image = cv2.imread(str(src_img), cv2.IMREAD_COLOR)
        if image is None:
            continue

        for copy in range(a.copies):
            seed = int.from_bytes(hashlib.sha256(f'neca/{a.seed}/{path.name}/{copy}'.encode()).digest()[:8], 'big')
            stem = f'{path.stem}_neca{copy}'
            page, placed = composite_neca_page(
                doc, image, bank, np.random.default_rng(seed),
                per_page=a.per_page,
                wall_fraction=a.wall_fraction,
                size_px_range=(a.min_px, a.max_px),
                tag=stem,
            )
            if not placed:
                continue
            ext = '.png'
            out_img = a.output / f'{stem}{ext}'
            if not cv2.imwrite(str(out_img), page):
                raise OSError(out_img)
            new_doc = dict(doc)
            new_doc.update({
                'document_id': stem,
                'source_type': 'blueprint_with_neca_svg_devices',
                'image_path': out_img.name,
                'image_sha256': hashlib.sha256(out_img.read_bytes()).hexdigest(),
                **composite_supervision(doc, placed, generated_families=CANONICAL_CLASSES),
                'composite': {
                    'background_annotation': str(path.resolve()),
                    'supervision': 'complete' if doc.get('verified_no_symbols') is True else 'positive_only',
                    'symbol_library': 'NECA_100_2013_Vector_SVG',
                    'seed': seed,
                },
            })
            out_img.with_suffix('.json').write_text(json.dumps(new_doc, indent=1))
            if pages_written < a.preview:
                draw_preview(page, placed, prev_dir / f'{stem}_preview.png')
            pages_written += 1
            total += len(placed)
            w_here = sum(1 for i in placed if i['on_wall'])
            wall_total += w_here
            free_total += len(placed) - w_here

        if (n + 1) % 50 == 0:
            print(f'{n + 1}/{len(paths)} pages -> {total} NECA symbols ({wall_total} wall, {free_total} center)', flush=True)

    print(json.dumps({
        'pages': pages_written,
        'neca_symbols': total,
        'wall_touching': wall_total,
        'free_space_center': free_total,
    }, indent=2), flush=True)


if __name__ == '__main__':
    main()
