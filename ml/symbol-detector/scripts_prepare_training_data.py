"""One-command blueprint annotation -> source-isolated YOLO training dataset.

Inputs are our normalized blueprint annotations (created from CAD/SVG/COCO,
not any generic YOLO dataset). Does not train or download model weights.

python scripts_prepare_training_data.py \
  --annotations 'converted/**/*.json' --output prepared/blueprints \
  --train-tiles-per-page 32 --stress-val --stress-test

All group splits happen BEFORE producing clean or damaged tile variants.
The data output uses YOLO's *file format*, not YOLO's training datasets.
"""
from __future__ import annotations
import argparse
from collections import Counter
import glob
import json
from pathlib import Path
from blueprint_symbols.dataset_manifest import build_manifest
from scripts_export_yolo_tiles import run
from scripts_verify_yolo_export import verify_export


def discover(patterns: list[str]) -> list[Path]:
    results = []
    for pattern in patterns:
        path = Path(pattern)
        found = glob.glob(pattern, recursive=True) if any(k in pattern for k in '*?[') else [str(path)]
        for name in found:
            f = Path(name)
            if not f.is_file() or f.suffix.lower() != '.json':
                continue
            try:
                doc = json.loads(f.read_text())
            except (OSError, ValueError):
                continue
            if isinstance(doc, dict) and isinstance(doc.get('instances'), list) and 'image_path' in doc:
                results.append(f.resolve())
    return sorted(set(results))


def prepare(patterns: list[str], destination: Path, *, train_tiles_per_page: int = 32,
            train_variants: int = 3, seed: int = 2026, val_fraction: float = .15,
            test_fraction: float = .15, stress_val: bool = True,
            stress_test: bool = True, allow_unverified_completeness: bool = False,
            locked_manifest: Path | None = None) -> dict:
    if destination.exists() and any(destination.iterdir()):
        raise FileExistsError(f'refusing to overwrite existing directory: {destination}')
    paths = discover(patterns)
    if not paths:
        raise ValueError('no valid normalized blueprint annotation JSON files found')
    lock=json.loads(locked_manifest.read_text()) if locked_manifest is not None else None
    manifest = build_manifest(paths, val_fraction=val_fraction, test_fraction=test_fraction,
                              locked_manifest=lock)
    destination.mkdir(parents=True, exist_ok=True)
    mpath = destination / 'source_manifest.json'
    mpath.write_text(json.dumps(manifest, indent=2))
    dataset = destination / 'yolo'
    export = run(mpath, dataset, samples=train_tiles_per_page, train_variants=train_variants,
                 stress_val=stress_val, stress_test=stress_test,
                 allow_unverified_completeness=allow_unverified_completeness, seed=seed)
    audit = verify_export(dataset)
    result = {
        'kind': 'blueprint_training_data_preparation',
        'training_performed': False,
        'pretrained_general_models_used': False,
        'source_annotated_pages': len(paths),
        'source_groups': {k: len(v) for k, v in manifest['split_groups'].items()},
        'classes': manifest['class_ids'],
        'split_counts': export['counts'],
        'verification': audit,
        'warnings': manifest['warnings'],
        'data_yaml': str((dataset / 'data.yaml').resolve()),
    }
    (destination / 'preparation_report.json').write_text(json.dumps(result, indent=2))
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--annotations', nargs='+', required=True,
                   help='Glob(s) for normalized CAD/SVG/COCO-derived page annotations')
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--train-tiles-per-page', type=int, default=32)
    p.add_argument('--train-variants', type=int, default=3)
    p.add_argument('--val-fraction', type=float, default=.15)
    p.add_argument('--test-fraction', type=float, default=.15)
    p.add_argument('--seed', type=int, default=2026)
    p.add_argument('--stress-val', action='store_true', default=False)
    p.add_argument('--stress-test', action='store_true', default=False)
    p.add_argument('--locked-manifest',type=Path,
                   help='Freeze the original val/test groups and class IDs when expanding training data')
    p.add_argument('--allow-unverified-completeness', action='store_true',
                   help='EXPLORATORY ONLY: unannotated symbols may become false negatives')
    a = p.parse_args()
    result = prepare(a.annotations, a.output, train_tiles_per_page=a.train_tiles_per_page,
                     train_variants=a.train_variants, val_fraction=a.val_fraction,
                     test_fraction=a.test_fraction, seed=a.seed,
                     stress_val=a.stress_val, stress_test=a.stress_test,
                     allow_unverified_completeness=a.allow_unverified_completeness,
                     locked_manifest=a.locked_manifest)
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
