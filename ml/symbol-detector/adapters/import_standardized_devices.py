"""Import standardized blueprint *DEVICES.json files into bounding-box-only normalized pages.

    python adapters/import_standardized_devices.py \
        ../data/blueprints_and_libraries ../work/phase2_v1/normalized --dpi 200

Converts bottom-left PDF point bounding boxes into top-left raster pixel coordinates
for each annotated PDF page. Strips all keypoints/anchors/wall-snaps so Phase 2 trains
purely on device bounding boxes.
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


CANONICAL_CLASSES = (
    'receptacle',
    'luminaire',
    'fire_alarm',
    'junction_box',
    'switch_sensor',
    'other_device',
)


def canonical_family(raw_type: str) -> str:
    t = str(raw_type or '').strip().lower().replace(' ', '_')
    if 'receptacle' in t or 'outlet' in t:
        return 'receptacle'
    if any(k in t for k in ('light', 'luminaire', 'exit_sign')):
        return 'luminaire'
    if any(k in t for k in ('fire_alarm', 'fire_extinguisher', 'speaker', 'smoke', 'horn', 'strobe', 'pull_station')):
        return 'fire_alarm'
    if any(k in t for k in ('junction_box', 'pull_box', 'handhole')):
        return 'junction_box'
    if any(k in t for k in ('switch', 'occupancy_sensor', 'temperature_sensor', 'thermostat', 'motion_detector', 'key_pad')):
        return 'switch_sensor'
    return 'other_device'


def extract_bbox_pts(dev: dict) -> list[float] | None:
    """Extract [x0, y0, x1, y1] in bottom-left PDF points from any supported DEVICES.json schema."""
    for key in ('symbol_bbox', 'symbol_bbox_pts', 'symbol_bbox_bl', 'symbol_graphic_bbox', 'bounding_box', 'bbox_pts', 'bbox'):
        val = dev.get(key)
        if isinstance(val, list) and len(val) == 4:
            try:
                x0, y0, x1, y1 = (float(v) for v in val)
                if math.isfinite(x0) and math.isfinite(y0) and math.isfinite(x1) and math.isfinite(y1):
                    return [min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1)]
            except (TypeError, ValueError):
                continue
        elif isinstance(val, dict) and all(k in val for k in ('x1', 'y1', 'x2', 'y2')):
            try:
                x0, y0, x1, y1 = float(val['x1']), float(val['y1']), float(val['x2']), float(val['y2'])
                return [min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1)]
            except (TypeError, ValueError):
                continue
    return None


def index_pdfs(data_root: Path) -> dict[str, Path]:
    pdfs: dict[str, Path] = {}
    for p in sorted(data_root.rglob('*.pdf')):
        pdfs.setdefault(p.name, p)
        pdfs.setdefault(p.name.lower(), p)
    return pdfs


def resolve_pdf(src_str: str, json_path: Path, pdf_index: dict[str, Path]) -> Path | None:
    if not src_str:
        return None
    name = Path(src_str).name
    for cand in (
        json_path.parent / name,
        pdf_index.get(name),
        pdf_index.get(name.lower()),
    ):
        if cand and cand.is_file():
            return cand
    # Fallback: strip " (1)" / " (2)" duplicate suffix
    cleaned = re.sub(r'\s*\(\d+\)(?=\.pdf$)', '', name, flags=re.IGNORECASE)
    for cand in (json_path.parent / cleaned, pdf_index.get(cleaned), pdf_index.get(cleaned.lower())):
        if cand and cand.is_file():
            return cand
    return None


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


def pdf_box_to_pixels(
    box_pts: list[float],
    page: fitz.Page,
    w: int,
    h: int,
    *,
    already_rotated: bool,
) -> tuple[float, float, float, float]:
    """Convert bottom-left PDF point box [x0, y0, x1, y1] to top-left raster pixels."""
    x0_pt, y0_pt, x1_pt, y1_pt = box_pts
    pw, ph = float(page.rect.width), float(page.rect.height)
    sx, sy = w / pw, h / ph
    if page.rotation and not already_rotated:
        # Pre-v3 extractors on rotated PDFs output unrotated mediabox coords (e.g. rotation=270)
        r = fitz.Rect(x0_pt, y0_pt, x1_pt, y1_pt) * page.rotation_matrix
        px0, px1 = sorted((r.x0 * sx, r.x1 * sx))
        py0, py1 = sorted((r.y0 * sy, r.y1 * sy))
    else:
        px0, px1 = x0_pt * sx, x1_pt * sx
        py0, py1 = (ph - y1_pt) * sy, (ph - y0_pt) * sy
    return (
        float(np.clip(px0, 0.0, w)),
        float(np.clip(py0, 0.0, h)),
        float(np.clip(px1, 0.0, w)),
        float(np.clip(py1, 0.0, h)),
    )


def import_all(data_root: Path, out_dir: Path, *, dpi: float = 200.0, max_pixels: int = 36_000_000) -> dict:
    out_dir.mkdir(parents=True, exist_ok=True)
    pdf_index = index_pdfs(data_root)
    counts: Counter = Counter()
    class_counts: Counter = Counter()
    project_pages: Counter = Counter()

    rinker_dir = data_root / '1_blueprints' / 'rinker_hall'
    rinker_sheets: dict[str, Path] = {}
    if rinker_dir.is_dir():
        for pdf in sorted(rinker_dir.rglob('*.pdf')):
            code = pdf.stem.split('_')[0].split()[0].upper()
            rinker_sheets[code] = pdf

    for jpath in sorted(data_root.rglob('*DEVICES.json')):
        data = json.loads(jpath.read_text())
        devs = data.get('devices', [])
        if not devs:
            counts['empty_json_skipped'] += 1
            continue

        src_str = str(data.get('source_file') or data.get('source_pdf') or '')
        project_id = re.sub(r'[^a-zA-Z0-9_-]+', '_', jpath.stem.replace('_DEVICES', '')).strip('_').lower()
        if src_str.lower() == 'rinker_hall':
            project_id = 'rinker_hall'

        # Group devices by (pdf_path, 0-based page_index)
        by_page: dict[tuple[Path, int], list[dict]] = defaultdict(list)
        if src_str.lower() == 'rinker_hall':
            for dev in devs:
                sheet = str(dev.get('sheet') or '').strip().upper()
                pdf = rinker_sheets.get(sheet)
                if pdf is None:
                    counts['missing_rinker_sheet'] += 1
                    continue
                by_page[(pdf, 0)].append(dev)
        else:
            pdf = resolve_pdf(src_str, jpath, pdf_index)
            if pdf is None:
                counts['missing_pdf'] += 1
                print(f'WARN: missing PDF for {jpath.name}: {src_str}', flush=True)
                continue
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
                coord_sys = data.get('coordinate_system')
                already_rotated = isinstance(coord_sys, dict) and 'rotation' in str(coord_sys.get('reference', '')).lower()

                instances = []
                for k, dev in enumerate(page_devs):
                    if dev.get('likely_title_block_artifact'):
                        counts['skipped_title_block'] += 1
                        continue
                    if dev.get('placement') == 'text_label_proxy' or (
                        dev.get('confidence') == 'low' and dev.get('symbol_bbox_pts') is None and dev.get('symbol_bbox') is None
                    ):
                        counts['skipped_text_proxy'] += 1
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
                    fam = canonical_family(raw_type)
                    instances.append({
                        'instance_id': f'{project_id}_p{page_idx+1:03d}_{k:04d}',
                        'symbol_family': fam,
                        'symbol_variant': raw_type,
                        'bbox_xyxy': [round(px0, 2), round(py0, 2), round(px1, 2), round(py1, 2)],
                        'anchors': {},
                        'source': {
                            'kind': 'standardized_devices_json',
                            'json_file': jpath.name,
                            'pdf_page': page_idx + 1,
                            'label_method': 'bbox_only_device_extraction',
                        },
                    })
                    class_counts[fam] += 1
                    counts['instances'] += 1

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
                    'project_family_id': project_id,
                    'source_group_verified': True,
                    'document_id': stem,
                    'page_index': page_idx,
                    'source_type': 'real_construction_pdf_devices',
                    'image_path': png.name,
                    'image_sha256': hashlib.sha256(png.read_bytes()).hexdigest(),
                    'image_width': w,
                    'image_height': h,
                    'coordinate_system': 'original_page_pixels_top_left_y_down',
                    'exhaustive_families': list(CANONICAL_CLASSES),
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
