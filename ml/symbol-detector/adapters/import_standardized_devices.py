"""Import standardized blueprint *DEVICES.json files into bounding-box-only normalized pages.

    python adapters/import_standardized_devices.py \
        ../data/blueprints_and_libraries ../work/phase2_v1/normalized --dpi 200

Reads the training drawings (``1_blueprints/*.pdf``) and their DEVICES files
(``1_blueprints/devices``) from the folders at runtime; evaluation drawings
(``1_blueprints/evaluation``) are never imported for training.

Converts bottom-left PDF point bounding boxes into top-left raster pixel coordinates
for each annotated PDF page. Pages use the shared label format
(``blueprint_symbols.label_schema``): exact catalog devices, unverified proposal
boxes, the same project groups and completeness fields as the annotator, so
training reads them through ``load_training_page`` like any labeled page.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
from collections import Counter, defaultdict
from pathlib import Path

import cv2
import fitz
import numpy as np


from blueprint_symbols.label_schema import (  # noqa: E402
    completeness,
    device_for_raw_type,
    devices_bbox_points as extract_bbox_pts,
    devices_rotated_coordinates,
    devices_skip_reason,
    group_fields,
    pending_instance,
    points_to_pixels,
    device_class,
)


def canonical_family(raw_type: str) -> str:
    """Catalog family of a DEVICES.json device type ('other_device' if none)."""
    device = device_for_raw_type(raw_type)
    return device[0] if device else 'other_device'


def render_page_bgr(doc: fitz.Document, page_index_0: int, dpi: float, max_pixels: int) -> tuple[np.ndarray, fitz.Page]:
    page = doc.load_page(page_index_0)
    pw, ph = float(page.rect.width), float(page.rect.height)
    scale = dpi / 72.0
    if pw * scale * ph * scale > max_pixels:
        scale = math.sqrt(max_pixels / max(1.0, pw * ph))
    pix = page.get_pixmap(matrix=fitz.Matrix(scale, scale), alpha=False)
    arr = np.frombuffer(pix.samples, dtype=np.uint8).reshape(pix.height, pix.width, pix.n)
    if pix.n == 4:
        bgr = cv2.cvtColor(arr, cv2.COLOR_RGBA2BGR)
    elif pix.n == 3:
        bgr = cv2.cvtColor(arr, cv2.COLOR_RGB2BGR)
    else:
        bgr = cv2.cvtColor(arr, cv2.COLOR_GRAY2BGR)
    return bgr, page


def pdf_box_to_pixels(box_pts, page, w: int, h: int, *, already_rotated: bool):
    return points_to_pixels(box_pts, page, w, h, already_rotated=already_rotated)


def import_all(data_root: Path, out_dir: Path, *, dpi: float = 200.0, max_pixels: int = 36_000_000) -> dict:
    out_dir.mkdir(parents=True, exist_ok=True)
    counts: Counter = Counter()
    class_counts: Counter = Counter()
    project_pages: Counter = Counter()

    # Training drawings only: 1_blueprints/*.pdf with their 1_blueprints/devices
    # files, read from the folders now. Evaluation drawings never train.
    from blueprint_symbols.blueprint_sets import blueprint_sets, devices_for
    sets = blueprint_sets(data_root)
    counts['evaluation_drawings_left_out'] = len(sets.evaluation)
    for pdf in sets.training_pdfs():
        jpath = devices_for(pdf)
        if jpath is None:
            continue
        data = json.loads(jpath.read_text())
        devs = data.get('devices', [])
        if not devs:
            counts['empty_json_skipped'] += 1
            continue
        project_id = re.sub(r'[^a-zA-Z0-9_-]+', '_', jpath.stem.replace('_DEVICES', '')).strip('_').lower()
        by_page: dict[tuple[Path, int], list[dict]] = defaultdict(list)
        for dev in devs:
            pg = dev.get('pdf_page') or dev.get('page')
            if not isinstance(pg, int) or pg < 1:
                counts['bad_page_num'] += 1
                continue
            by_page[(pdf, pg - 1)].append(dev)

        # Render each unique annotated page and write bbox-only normalized JSON
        doc_cache: dict[Path, fitz.Document] = {}
        try:
            for (pdf_path, page_idx), page_devs in sorted(by_page.items()):
                if pdf_path not in doc_cache:
                    doc_cache[pdf_path] = fitz.open(pdf_path)
                doc = doc_cache[pdf_path]
                if page_idx >= len(doc):
                    counts['page_out_of_range'] += 1
                    continue

                bgr, page = render_page_bgr(doc, page_idx, dpi=dpi, max_pixels=max_pixels)
                h, w = bgr.shape[:2]
                already_rotated = devices_rotated_coordinates(data)

                instances = []
                for k, dev in enumerate(page_devs):
                    skip = devices_skip_reason(dev)
                    if skip:
                        counts[f'skipped_{skip}'] += 1
                        continue
                    box_pts = extract_bbox_pts(dev)
                    if box_pts is None:
                        counts['missing_bbox'] += 1
                        continue
                    px0, py0, px1, py1 = pdf_box_to_pixels(
                        box_pts, page, w, h, already_rotated=already_rotated
                    )
                    if px1 - px0 < 2.0 or py1 - py0 < 2.0:
                        counts['degenerate_box'] += 1
                        continue
                    # Reject huge bogus boxes (>25% of page width/height)
                    if (px1 - px0) > 0.25 * w or (py1 - py0) > 0.25 * h:
                        counts['oversized_box'] += 1
                        continue
                    raw_type = str(dev.get('device_type') or dev.get('symbol_name') or 'unknown')
                    device = device_for_raw_type(raw_type)
                    if device is None:
                        counts['skipped_not_electrical'] += 1
                        continue
                    family, variant = device
                    # Extracted boxes are proposals until a human verifies them
                    # in the annotator; training ignores them until then.
                    instances.append(pending_instance(
                        f'{project_id}_p{page_idx+1:03d}_{k:04d}', family, variant,
                        [round(px0, 2), round(py0, 2), round(px1, 2), round(py1, 2)],
                        review_source='devices_json', anchors={}, raw_device_type=raw_type,
                        source={'kind': 'standardized_devices_json', 'json_file': jpath.name,
                                'pdf_page': page_idx + 1,
                                'label_method': 'bbox_only_device_extraction'}))
                    class_counts[device_class(family, variant) or f'{family} (no exact device)'] += 1
                    counts['instances'] += 1
                    counts['unverified_provisional'] += 1

                if not instances:
                    counts['empty_page_after_filter'] += 1
                    continue

                stem = f'{project_id}_{pdf_path.stem[:32]}_p{page_idx+1:03d}'
                stem = re.sub(r'[^a-zA-Z0-9_-]+', '_', stem)
                png = out_dir / f'{stem}.png'
                if not cv2.imwrite(str(png), bgr):
                    raise OSError(f'Failed to write {png}')

                norm_doc = {
                    'format_version': 1,
                    'source_id': f'real_devices_{project_id}',
                    **group_fields(pdf_path),
                    'document_id': stem,
                    'page_index': page_idx,
                    'source_type': 'real_construction_pdf_devices',
                    'source_pdf_path': pdf_path.relative_to(data_root).as_posix(),
                    'image_path': png.name,
                    'image_sha256': hashlib.sha256(png.read_bytes()).hexdigest(),
                    'image_width': w,
                    'image_height': h,
                    'coordinate_system': 'original_page_pixels_top_left_y_down',
                    # The presence of some correct boxes does NOT imply that
                    # the remaining devices on a construction page were labeled.
                    **completeness(),
                    'instances': instances,
                }
                png.with_suffix('.json').write_text(json.dumps(norm_doc, indent=1))
                counts['pages'] += 1
                project_pages[project_id] += 1
        finally:
            for d in doc_cache.values():
                d.close()

    return {
        'counts': dict(counts),
        'class_counts': dict(class_counts),
        'projects': dict(project_pages),
    }


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('data_root', type=Path)
    p.add_argument('output', type=Path)
    p.add_argument('--dpi', type=float, default=200.0)
    p.add_argument('--max-pixels', type=int, default=36_000_000)
    a = p.parse_args()
    report = import_all(a.data_root.resolve(), a.output.resolve(), dpi=a.dpi, max_pixels=a.max_pixels)
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
