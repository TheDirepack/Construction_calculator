"""Composite NECA SVG symbols without inventing negative labels on unreviewed PDF pages.

    python scripts_composite_neca_svg.py \
        --neca-dir ../data/blueprints_and_libraries/2_component_libraries/neca_100_2013_svgs/NECA_100_Vector_Library \
        --backgrounds '../work/pool/background/*new_downloads_202611*.json' \
        --output ../work/neca_composited \
        --per-page 24 --wall-fraction 0.60 --limit 400 --preview 3
"""
from __future__ import annotations

from blueprint_symbols.label_schema import read_page
import argparse
import glob
import hashlib
import json
from pathlib import Path

import cv2
import numpy as np

from blueprint_symbols.neca_svg_compositor import NecaSymbolBank, composite_neca_page
from blueprint_symbols.composite_supervision import composite_supervision
from blueprint_symbols.composite_output import source_stem, source_seed, create_output_dir


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


def render_training_backgrounds(data_root: Path, out_dir: Path, count: int, *, seed: int,
                                dpi: int = 150, max_pixels: int = 24_000_000,
                                page_hash_cache: Path | None = None,
                                exclude_pages: list[tuple[Path, int]] = ()) -> list[Path]:
    """Render ``count`` random pages of the training drawings (``1_blueprints/*.pdf``,
    found at runtime) to page JSONs; positive-only, since their own devices
    are unlabelled. ``exclude_pages`` (PDF, 0-based page): pages held out for
    the real-page test; with ``page_hash_cache`` their copies in other PDFs
    are skipped too."""
    import pymupdf
    from blueprint_symbols.blueprint_sets import blueprint_sets, pdf_page_entries
    from blueprint_symbols.label_schema import page_header

    pdfs = blueprint_sets(data_root).training_pdfs()
    if not pdfs:
        raise SystemExit(f'no training PDFs in {data_root}/1_blueprints')
    rng = np.random.default_rng(seed)
    out_dir.mkdir(parents=True, exist_ok=True)
    hashes = None
    if page_hash_cache is not None:      # exact duplicate pages (and evaluation copies) once
        from blueprint_symbols.page_hash_cache import PageHashCache
        hashes = PageHashCache(page_hash_cache)
    excluded = {(Path(pdf).resolve(), int(page)) for pdf, page in exclude_pages}
    excluded_hashes = set()
    if hashes is not None:
        for pdf, page in sorted(excluded):
            try:
                excluded_hashes.add(hashes.page_hash(pdf, page))
            except Exception:
                pass
    written: list[Path] = []
    tries = 0
    seen = set()
    while len(written) < count and tries < count * 10:
        tries += 1
        pdf = pdfs[int(rng.integers(len(pdfs)))]
        try:
            pages = pdf_page_entries(pdf)
        except Exception:
            continue
        if not pages:
            continue
        entry = pages[int(rng.integers(len(pages)))]
        key = (str(pdf), entry['pdf_page_index'])
        if key in seen:
            continue
        seen.add(key)
        if (Path(pdf).resolve(), entry['pdf_page_index']) in excluded:
            continue
        if hashes is not None:
            try:
                if hashes.page_hash(pdf, entry['pdf_page_index']) in excluded_hashes:
                    continue
            except Exception:
                pass
            try:
                if hashes.is_duplicate(pdf, entry['pdf_page_index']):
                    continue
            except Exception:
                pass
        with pymupdf.open(pdf) as doc:
            page = doc[entry['pdf_page_index']]
            zoom = dpi / 72
            if page.rect.width * zoom * page.rect.height * zoom > max_pixels:
                zoom = (max_pixels / (page.rect.width * page.rect.height)) ** .5
            pix = page.get_pixmap(matrix=pymupdf.Matrix(zoom, zoom), alpha=False)
        stem = f'{pdf.stem[:60]}_p{entry["pdf_page_index"] + 1:04d}'
        img_path = out_dir / f'{stem}.png'
        pix.save(img_path)
        doc_json = page_header(source=pdf, document_id=pdf.name, page_index=entry['pdf_page_index'],
                               source_type='real_construction_pdf_background', image_path=img_path.name,
                               image_sha256=hashlib.sha256(img_path.read_bytes()).hexdigest(),
                               width=pix.width, height=pix.height)
        json_path = out_dir / f'{stem}.json'
        json_path.write_text(json.dumps(doc_json, indent=1))
        written.append(json_path)
    print(f'Rendered {len(written)} background pages from {len(pdfs)} training drawings', flush=True)
    return written


def verified_blank_training_backgrounds(
    label_stores: list[Path],
    out_dir: Path,
    count: int,
    *,
    data_root: Path | None = None,
    seed: int = 2026,
) -> list[Path]:
    """Build ``count`` verified-blank training background JSONs from ``label_store``
    ``train`` pages ONLY (never ``holdout`` or ``evaluation``).

    Each returned page carries ``verified_no_symbols=True`` and retains its
    training drawing's ``project_family_id`` so composites placed onto it stay
    in the ``train`` split while receiving complete 180-class supervision.
    """
    from annotator import label_store
    from blueprint_symbols.label_schema import page_header, parse_page_ref

    if count <= 0:
        return []

    candidates: list[tuple[str, Path, Path, dict]] = []
    seen_sha: set[str] = set()
    for store in label_stores:
        train_refs = label_store.labeled_pages(store, data_root=data_root)["train"]
        for ref in train_refs:
            try:
                doc = read_page(ref)
            except (OSError, ValueError):
                continue
            if doc.get("verified_no_symbols") is not True or (doc.get("instances") or []):
                continue
            if doc.get("source_group_verified") is not True or not doc.get("project_family_id"):
                continue
            parsed = parse_page_ref(ref)
            base_dir = parsed[0].parent if parsed is not None else Path(ref).resolve().parent
            src_img = (base_dir / str(doc.get("image_path") or "")).resolve()
            if not src_img.is_file():
                continue
            digest = str(doc.get("image_sha256") or hashlib.sha256(src_img.read_bytes()).hexdigest())
            if digest in seen_sha:
                continue
            seen_sha.add(digest)
            candidates.append((digest, Path(ref), src_img, doc))

    if not candidates:
        print("Verified-blank training backgrounds: 0 usable pages found", flush=True)
        return []

    candidates.sort(key=lambda item: (item[0], str(item[2])))
    rng = np.random.default_rng(seed)
    order = rng.permutation(len(candidates))
    shuffled = [candidates[int(idx)] for idx in order]

    out_dir.mkdir(parents=True, exist_ok=True)
    written: list[Path] = []
    for i in range(count):
        digest, _ref, src_img, doc = shuffled[i % len(shuffled)]
        stem = f"vblank_{i:04d}_{src_img.stem[:40]}"
        payload = page_header(
            source=str(doc.get("source_id") or src_img.stem),
            document_id=str(doc.get("document_id") or src_img.stem),
            page_index=int(doc.get("page_index") or 0),
            source_type="real_verified_blank_background",
            image_path=str(src_img),
            image_sha256=digest,
            width=int(doc["image_width"]),
            height=int(doc["image_height"]),
        )
        payload.update({
            "source_id": str(doc.get("source_id") or src_img.stem),
            "source_license": str(doc.get("source_license") or "user-owned"),
            "project_family_id": str(doc["project_family_id"]),
            "source_group_verified": True,
            "verified_no_symbols": True,
            "exhaustive_families": [],
            "annotation_completeness": "complete",
            "annotation_completeness_verified": True,
            "annotation_scope": "verified_negative",
            "wall_only_background_verified": True,
            "synthetic_targets_only": True,
        })
        json_path = out_dir / f"{stem}.json"
        json_path.write_text(json.dumps(payload, indent=1), encoding="utf-8")
        written.append(json_path)

    print(
        f"Verified-blank training backgrounds: {len(written)} pages "
        f"from {len(candidates)} unique verified-blank training sheets",
        flush=True,
    )
    return written


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--neca-dir', type=Path, required=True, help='Path to NECA_100_Vector_Library folder')
    p.add_argument('--backgrounds', nargs='+', default=[],
                   help='Globs for source page JSONs; unverified backgrounds remain positive-only')
    p.add_argument('--page-hash-cache', type=Path,
                   help='shared PDF page fingerprint cache (work/pdf_page_hashes.sqlite): '
                        'background pages skip exact duplicates and copies of evaluation pages')
    p.add_argument('--background-pages', type=int, default=0,
                   help='also render this many random pages of the TRAINING drawings (1_blueprints/*.pdf '
                        'under --data-root, read at runtime) as backgrounds')
    p.add_argument('--verified-blank-pages', type=int, default=0,
                   help='also composite onto this many verified-blank TRAINING pages from --label-store '
                        '(train split only, never holdout or evaluation) for complete real-sheet supervision')
    p.add_argument('--walls-only-pages', type=int, default=0,
                   help='also make this many proven symbol-free backgrounds (walls drawn from ArchCAD / '
                        'FloorPlanCAD line labels under --data-root, plus --layouts-dir); their '
                        'composites are completely labelled')
    p.add_argument('--layouts-dir', type=Path,
                   help='rendered wall layouts (work/pool/background_layouts) to include as walls-only')
    p.add_argument('--dataset-pages', type=int, default=0,
                   help='also use this many ArchCAD / FloorPlanCAD / CubiCasa images as backgrounds '
                        '(positive-only unless listed in <data root>/symbol_free_images.txt)')
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--per-page', type=int, default=24)
    p.add_argument('--copies', type=int, default=1)
    p.add_argument('--wall-fraction', type=float, default=0.60)
    p.add_argument('--min-px', type=float, default=20.0)
    p.add_argument('--max-px', type=float, default=64.0)
    p.add_argument('--limit', type=int, default=0)
    p.add_argument('--preview', type=int, default=3, help='Write N annotated preview overlays into output/_previews')
    p.add_argument('--seed', type=int, default=2026)
    p.add_argument('--label-store', type=Path, action='append', default=[],
                   help='also composite crops of human-verified devices from this label store')
    p.add_argument('--data-root', type=Path,
                   help='blueprints_and_libraries root: training drawings are 1_blueprints/*.pdf; '
                        'evaluation drawings (1_blueprints/evaluation) never lend crops or backgrounds')
    p.add_argument('--symbol-library', type=Path, action='append', default=[],
                   help='also composite accepted crops of this legend crop library '
                        '(scripts_extract_legend_symbols.py output, e.g. 2_component_libraries/legend_symbol_crops)')
    a = p.parse_args()

    bank = NecaSymbolBank(a.neca_dir.resolve(), devices_only=True)
    print('Loaded NECA 100-2013 SVG bank:', json.dumps(bank.summary()), flush=True)
    if a.label_store:
        from annotator import label_store
        from blueprint_symbols.verified_symbols import verified_symbol_glyphs
        # Evaluation drawings never lend symbols to training data.
        pages = [p for store in a.label_store for p in label_store.training_pages(store, data_root=a.data_root)]
        glyphs, report = verified_symbol_glyphs(pages, allow_complete=True)
        bank.add_glyphs(glyphs)
        print('Verified-label crops:', json.dumps(report), flush=True)
    for library in a.symbol_library:
        from blueprint_symbols.symbol_library import legend_symbol_glyphs
        glyphs, report = legend_symbol_glyphs(library)
        bank.add_glyphs(glyphs)
        print(f'Legend crops from {library}:', json.dumps(report['counts']), f"{report['devices']} devices", flush=True)
    print(f'Devices to place: {len(bank.devices)}', flush=True)

    paths = sorted({
        Path(f).resolve() for pat in a.backgrounds for f in glob.glob(pat, recursive=True)
        if 'spec' not in Path(f).stem.lower()
    })
    if not paths:
        paths = sorted({Path(f).resolve() for pat in a.backgrounds for f in glob.glob(pat, recursive=True)})
    if a.limit > 0 and len(paths) > a.limit:
        sel_rng = np.random.default_rng(a.seed)
        idx = sorted(sel_rng.choice(len(paths), size=a.limit, replace=False))
        paths = [paths[i] for i in idx]

    create_output_dir(a.output)
    if a.background_pages:
        if a.data_root is None:
            raise SystemExit('--background-pages needs --data-root')
        held_out = []
        if a.label_store:
            from annotator import label_store
            held_out = [p for store in a.label_store
                        for p in label_store.holdout_pdf_pages(store, data_root=a.data_root)]
            print(f'{len(held_out)} pages held out for the real-page test are not backgrounds', flush=True)
        paths += render_training_backgrounds(a.data_root, a.output / '_backgrounds',
                                             a.background_pages, seed=a.seed,
                                             page_hash_cache=a.page_hash_cache,
                                             exclude_pages=held_out)
    if a.verified_blank_pages:
        if not a.label_store:
            raise SystemExit('--verified-blank-pages needs --label-store')
        paths += verified_blank_training_backgrounds(
            a.label_store,
            a.output / '_backgrounds_verified_blank',
            a.verified_blank_pages,
            data_root=a.data_root,
            seed=a.seed,
        )
    if a.walls_only_pages or a.dataset_pages:
        if a.data_root is None:
            raise SystemExit('--walls-only-pages / --dataset-pages need --data-root')
        from blueprint_symbols.dataset_backgrounds import (
            other_dataset_backgrounds, read_symbol_free_list, walls_only_backgrounds)
        if a.walls_only_pages:
            paths += walls_only_backgrounds(a.data_root, a.output / '_backgrounds_walls_only',
                                            a.walls_only_pages, seed=a.seed, layouts_dir=a.layouts_dir)
        if a.dataset_pages:
            paths += other_dataset_backgrounds(a.data_root, a.output / '_backgrounds_datasets',
                                               a.dataset_pages, seed=a.seed,
                                               symbol_free=read_symbol_free_list(a.data_root))
    if not paths:
        raise SystemExit('no background pages: give --backgrounds and/or --background-pages')
    prev_dir = a.output / '_previews'
    if a.preview > 0:
        prev_dir.mkdir(parents=True, exist_ok=True)

    total = wall_total = free_total = pages_written = 0
    for n, path in enumerate(paths):
        doc = read_page(path)
        # Validate source attestations before creating derivative files. A
        # partial/unknown background may still produce synthetic positives,
        # but can NEVER produce class-wide background-negative labels.
        composite_supervision(doc, [], generated_families=bank.families)
        src_img = (path.parent / doc['image_path']).resolve()
        image = cv2.imread(str(src_img), cv2.IMREAD_COLOR)
        if image is None:
            continue

        for copy in range(a.copies):
            seed = source_seed(path, namespace='neca', seed=a.seed, copy=copy)
            stem = source_stem(path, 'neca', copy)
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
                **composite_supervision(doc, placed, generated_families=bank.families),
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
