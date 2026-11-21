"""Audit an exported YOLO-format blueprint dataset without loading any model.

Checks sample/label matching, normalized boxes, valid classes, provenance,
source isolation across partitions, and clean/stress split consistency.
"""
from __future__ import annotations
import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path
import cv2


def verify_export(folder: Path) -> dict:
    folder = Path(folder)
    report = json.loads((folder / 'export_report.json').read_text())
    classes = report['classes']
    expected = set(classes.values())
    if expected != set(range(len(classes))):
        raise ValueError('class ids are not zero-based consecutive')
    provenance = [json.loads(line) for line in (folder / 'sample_provenance.jsonl').read_text().splitlines() if line.strip()]
    if not provenance:
        raise ValueError('missing export samples')
    split_groups = defaultdict(set)
    count = Counter()
    names = set()
    tracked_images=set()
    tracked_labels=set()
    for row in provenance:
        split = row['split']
        original_split = split.split('_stress_', 1)[0]
        if original_split not in ('train', 'val', 'test'):
            raise ValueError(f'unexpected split {split}')
        split_groups[original_split].add(row['group'])
        k = (split, row['sample'])
        if k in names:
            raise ValueError(f'duplicate sample name within partition: {k}')
        names.add(k)
        ipath = folder / 'images' / split / f"{row['sample']}.png"
        lpath = folder / 'labels' / split / f"{row['sample']}.txt"
        if not ipath.is_file() or not lpath.is_file():
            raise ValueError(f'missing image or label: {ipath}, {lpath}')
        tracked_images.add(ipath.resolve())
        tracked_labels.add(lpath.resolve())
        img = cv2.imread(str(ipath), cv2.IMREAD_UNCHANGED)
        if img is None:
            raise ValueError(f'invalid image {ipath}')
        if img.shape[:2]!=(512,512):
            raise ValueError(f'unexpected tile dimensions: {ipath}: {img.shape[:2]}')
        for line_no, line in enumerate(lpath.read_text().splitlines(), 1):
            parts = line.split()
            if len(parts) != 5:
                raise ValueError(f'invalid label column count: {lpath}:{line_no}')
            try:
                class_id = int(parts[0])
                x, y, w, h = map(float, parts[1:])
            except ValueError as e:
                raise ValueError(f'invalid label value: {lpath}:{line_no}') from e
            if class_id not in expected or not (0 <= x <= 1 and 0 <= y <= 1 and 0 < w <= 1 and 0 < h <= 1):
                raise ValueError(f'out of range YOLO label: {lpath}:{line_no}')
            if x-w/2 < -1e-6 or x+w/2 > 1+1e-6 or y-h/2 < -1e-6 or y+h/2 > 1+1e-6:
                raise ValueError(f'box outside image: {lpath}:{line_no}')
            count[f'{split}_labels'] += 1
        count[f'{split}_images'] += 1
    actual_images=set(x.resolve() for x in (folder/'images').glob('**/*') if x.is_file())
    actual_labels=set(x.resolve() for x in (folder/'labels').glob('**/*') if x.is_file())
    if actual_images!=tracked_images or actual_labels!=tracked_labels:
        raise ValueError('untracked or missing image/label files in exported dataset; '
                         f'extra images={len(actual_images-tracked_images)}, '
                         f'extra labels={len(actual_labels-tracked_labels)}')
    for first in ('train', 'val', 'test'):
        for second in ('train', 'val', 'test'):
            if first != second and split_groups[first] & split_groups[second]:
                raise ValueError(f'original drawing group leaks between {first} and {second}')
    for part in ('train', 'val', 'test'):
        if count[f'{part}_images'] == 0:
            raise ValueError(f'empty {part} partition')
    expected_counts = report['counts']
    for part in sorted({row['split'] for row in provenance}):
        if expected_counts.get(f'{part}_images') != count[f'{part}_images']:
            raise ValueError(f'export count mismatch in {part}')
        if expected_counts.get(f'{part}_symbols') != count[f'{part}_labels']:
            raise ValueError(f'export label count mismatch in {part}')
    return {'ok': True, 'groups': {k: len(v) for k,v in split_groups.items()},
            'counts': dict(count), 'classes': classes,
            'no_model_training': True}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('export_dir', type=Path)
    args = p.parse_args()
    print(json.dumps(verify_export(args.export_dir), indent=2))

if __name__ == '__main__':
    main()
