"""Convert the Voxel51 FiftyOne export of FloorPlanCAD into normalized page JSON.

    python -I adapters/import_floorplancad_fiftyone.py \
        DATA/5_datasets/floorplancad_sample  OUT/normalized/floorplancad \
        --class-map class_maps/floorplancad.json

Input layout (as downloaded from huggingface.co/datasets/Voxel51/FloorPlanCAD):
  samples.json            FiftyOne samples, `ground_truth.detections[]` with
                          `label` and relative `bounding_box` [x, y, w, h]
  data/NNNN-MMMM.png      1000x1000 RGBA rasters (transparent background)

What this does:
- Flattens each RGBA raster onto white and writes an RGB PNG next to the JSON.
  (OpenCV's IMREAD_COLOR drops alpha, which turns the transparent background
  black; the exporter reads pages that way.)
- Maps labels through an explicit class map. Map values:
    "<family>"  -> a catalog family (labeled target)
    "drop"      -> a known non-target (furniture, walls): treated as background
    "ignore"    -> kept as an `__unmapped__`, uncertain region; tiles touching
                   it are skipped instead of being taught as background
  A label missing from the map is an error, so new releases cannot silently
  add classes.
- Groups pages by the drawing prefix of the file name (`NNNN` in
  `NNNN-MMMM.png`): every crop of one drawing stays in one split.
- FloorPlanCAD is panoptically labeled (every primitive belongs to a class),
  so each target family in the map is recorded in `exhaustive_families`.
  Only samples whose raster exists are converted; the rest are counted.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path

import cv2
import numpy as np


def flatten_rgba(path: Path) -> np.ndarray:
    raw = cv2.imread(str(path), cv2.IMREAD_UNCHANGED)
    if raw is None:
        raise ValueError(f'unreadable raster: {path}')
    if raw.ndim == 2:
        return cv2.cvtColor(raw, cv2.COLOR_GRAY2BGR)
    if raw.shape[2] == 3:
        return raw
    bgr = raw[:, :, :3].astype(np.float32)
    alpha = raw[:, :, 3:4].astype(np.float32) / 255.0
    return np.clip(bgr * alpha + 255.0 * (1 - alpha), 0, 255).round().astype(np.uint8)


def convert(source: Path, out: Path, class_map: dict[str, str]) -> dict:
    samples = json.loads((source / 'samples.json').read_text())['samples']
    targets = sorted({v for v in class_map.values() if v not in ('drop', 'ignore')})
    out.mkdir(parents=True, exist_ok=True)
    counts: Counter = Counter()
    unknown: Counter = Counter()
    for sample in samples:
        raster = source / sample['filepath']
        if not raster.is_file():
            counts['missing_raster'] += 1
            continue
        stem = raster.stem                       # e.g. 0134-0007
        drawing = stem.split('-')[0]
        image = flatten_rgba(raster)
        h, w = image.shape[:2]
        png = out / f'fpcad_{stem}.png'
        if not cv2.imwrite(str(png), image):
            raise OSError(f'could not write {png}')
        instances = []
        for k, det in enumerate((sample.get('ground_truth') or {}).get('detections', [])):
            label = det['label']
            mapped = class_map.get(label)
            if mapped is None:
                unknown[label] += 1
                continue
            if mapped == 'drop':
                counts['dropped_non_target'] += 1
                continue
            bx, by, bw, bh = (float(v) for v in det['bounding_box'])
            x0, y0 = max(0.0, bx * w), max(0.0, by * h)
            x1, y1 = min(float(w), (bx + bw) * w), min(float(h), (by + bh) * h)
            if x1 - x0 < 1 or y1 - y0 < 1:
                counts['skipped_degenerate_box'] += 1
                continue
            ignored = mapped == 'ignore'
            instances.append({
                'instance_id': f'fpcad_{stem}_{k:04d}',
                'symbol_family': '__unmapped__' if ignored else mapped,
                'symbol_variant': label,
                'bbox_xyxy': [x0, y0, x1, y1],
                'anchors': {},
                'uncertain': ignored,
                'source': {'kind': 'fiftyone_detection', 'label': label,
                           'label_method': 'supplied_dataset_annotation'},
            })
            counts['ignored_regions' if ignored else 'labeled_instances'] += 1
        doc = {
            'format_version': 1,
            'source_id': 'floorplancad_voxel51',
            'source_license': 'see dataset card (non-commercial research)',
            'project_family_id': f'floorplancad_{drawing}',
            'source_group_verified': True,
            'document_id': stem,
            'page_index': 0,
            'source_type': 'cad_raster',
            'image_path': png.name,
            'image_sha256': hashlib.sha256(png.read_bytes()).hexdigest(),
            'image_width': w,
            'image_height': h,
            'coordinate_system': 'original_page_pixels_top_left_y_down',
            'exhaustive_families': targets,
            'instances': instances,
        }
        png.with_suffix('.json').write_text(json.dumps(doc, indent=1))
        counts['pages'] += 1
    if unknown:
        raise ValueError(f'labels missing from the class map (add them as a family, '
                         f'"drop" or "ignore"): {dict(unknown)}')
    return dict(counts)


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('source', type=Path)
    p.add_argument('output', type=Path)
    p.add_argument('--class-map', type=Path, required=True)
    a = p.parse_args()
    cmap = {k: v for k, v in json.loads(a.class_map.read_text()).items() if not k.startswith('_')}
    print(json.dumps(convert(a.source, a.output, cmap), indent=2))


if __name__ == '__main__':
    main()
