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
    """Annotation pages matching the patterns: page JSONs, labels files (one
    per PDF, every saved page; see annotator.document_labels) and references
    to a page of one (``X_labels.json#p12``)."""
    from blueprint_symbols.label_schema import expand_page_paths, parse_page_ref, read_page
    results = []
    for pattern in patterns:
        path = Path(pattern)
        found = glob.glob(pattern, recursive=True) if any(k in pattern for k in '*?[') else [str(path)]
        for f in expand_page_paths(found):
            if parse_page_ref(f) is None and (not f.is_file() or f.suffix.lower() != '.json'):
                continue
            try:
                doc = read_page(f)
            except (OSError, ValueError):
                continue
            if isinstance(doc, dict) and isinstance(doc.get('instances'), list) and 'image_path' in doc:
                results.append(f.resolve())
    return sorted(set(results))


def label_store_pages(stores: list[Path], *, data_root: Path | None = None) -> dict[str, list[str]]:
    """Usable pages of label stores (annotator, agents, model review), split by
    where each drawing's PDF is now: ``1_blueprints/*.pdf`` -> ``train``,
    ``1_blueprints/evaluation/*.pdf`` -> ``evaluation`` (blueprint_sets).
    Evaluation pages only ever become validation/test."""
    from annotator import label_store
    out: dict[str, list[str]] = {"train": [], "evaluation": []}
    for store in stores:
        for role, pages in label_store.labeled_pages(store, data_root=data_root).items():
            out[role] += [str(p) for p in pages]
    return out


def complete_pages(paths: list[str]) -> list[str]:
    from blueprint_symbols.label_schema import read_page
    """Pages fully labelled (every symbol, or verified empty): only these can evaluate."""
    out = []
    for path in paths:
        data = read_page(path)
        if data.get('annotation_completeness_verified') is True or data.get('verified_no_symbols') is True:
            out.append(path)
    return out


def prepare(patterns: list[str], destination: Path, *, train_tiles_per_page: int = 32,
            train_variants: int = 3, seed: int = 2026, val_fraction: float = .15,
            test_fraction: float = .15, stress_val: bool = True,
            stress_test: bool = True, allow_unverified_completeness: bool = False,
            allow_partial_training: bool = False,
            locked_manifest: Path | None = None,
            verified_wall_negative_fraction: float = .65,
            verified_wall_blank_fraction: float = .12,
            real_blank_tiles_per_page: int | None = None,
            real_complete_tiles_per_page: int | None = None,
            real_blank_negative_fraction: float | None = None,
            real_complete_negative_fraction: float | None = None,
            eval_scales: tuple[float, ...] = (1.0,), eval_overlap: int = 128,
            evaluation_files: list[str] | None = None,
            train_only_files: list[str] | None = None) -> dict:
    if destination.exists() and any(destination.iterdir()):
        raise FileExistsError(f'refusing to overwrite existing directory: {destination}')
    paths = discover(list(patterns) + list(evaluation_files or []))
    if not paths:
        raise ValueError('no valid normalized blueprint annotation JSON files found')
    lock=json.loads(locked_manifest.read_text()) if locked_manifest is not None else None
    manifest = build_manifest(paths, val_fraction=val_fraction, test_fraction=test_fraction,
                              locked_manifest=lock, allow_partial_training=allow_partial_training,
                              evaluation_files=evaluation_files,
                              train_only_files=train_only_files)
    destination.mkdir(parents=True, exist_ok=True)
    mpath = destination / 'source_manifest.json'
    mpath.write_text(json.dumps(manifest, indent=2))
    dataset = destination / 'yolo'
    export = run(mpath, dataset, samples=train_tiles_per_page, train_variants=train_variants,
                 stress_val=stress_val, stress_test=stress_test,
                 allow_unverified_completeness=allow_unverified_completeness,
                 allow_partial_training=allow_partial_training, seed=seed,
                 verified_wall_negative_fraction=verified_wall_negative_fraction,
                 verified_wall_blank_fraction=verified_wall_blank_fraction,
                 real_blank_tiles_per_page=real_blank_tiles_per_page,
                 real_complete_tiles_per_page=real_complete_tiles_per_page,
                 real_blank_negative_fraction=real_blank_negative_fraction,
                 real_complete_negative_fraction=real_complete_negative_fraction,
                 scales=tuple(eval_scales), overlap=eval_overlap)
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
    p.add_argument('--annotations', nargs='+', default=[],
                   help='Glob(s) for normalized CAD/SVG/COCO-derived page annotations')
    p.add_argument('--label-store', type=Path, action='append', default=[],
                   help='label store (work/labels): adds its pages that have verified boxes '
                        'or a verified completeness attestation, minus the evaluation set')
    p.add_argument('--data-root', type=Path,
                   help='blueprints_and_libraries root: label-store drawings in 1_blueprints/ train, '
                        'those in 1_blueprints/evaluation/ validate and test (default: found from each PDF)')
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--train-tiles-per-page', type=int, default=32)
    p.add_argument('--train-variants', type=int, default=3)
    p.add_argument('--verified-wall-negative-fraction', type=float, default=.65)
    p.add_argument('--verified-wall-blank-fraction', type=float, default=.12)
    p.add_argument('--real-blank-tiles-per-page', type=int, default=None)
    p.add_argument('--real-complete-tiles-per-page', type=int, default=None)
    p.add_argument('--real-blank-negative-fraction', type=float, default=None)
    p.add_argument('--real-complete-negative-fraction', type=float, default=None)
    p.add_argument('--val-fraction', type=float, default=.15)
    p.add_argument('--test-fraction', type=float, default=.15)
    p.add_argument('--seed', type=int, default=2026)
    p.add_argument('--stress-val', action='store_true', default=False)
    p.add_argument('--stress-test', action='store_true', default=False)
    p.add_argument('--locked-manifest',type=Path,
                   help='Freeze the original val/test groups and class IDs when expanding training data')
    p.add_argument('--allow-partial-training', action='store_true',
                   help='train from labeled real positives without treating missing labels as negatives')
    p.add_argument('--allow-unverified-completeness', action='store_true',
                   help='EXPLORATORY ONLY: unannotated symbols may become false negatives')
    p.add_argument('--eval-scales', default='1',
                   help='comma-separated source scales for val/test tiling, e.g. 1,2 '
                        '(2 = each 512px tile covers 1024 source px; use it when dense pages '
                        'leave symbols clipped in every native-scale tile)')
    p.add_argument('--eval-overlap', type=int, default=128)
    p.add_argument('--allow-synthetic-eval', action='store_true',
                   help='deprecated no-op: synthetic eval fallback is always enabled when '
                        '--annotations provides complete synthetic groups')
    p.add_argument('--require-real-eval', action='store_true',
                   help='fail if 1_blueprints/evaluation has no complete real evaluation pages')
    a = p.parse_args()
    store_pages = label_store_pages(a.label_store, data_root=a.data_root)
    evaluation = complete_pages(store_pages['evaluation'])
    if store_pages['evaluation']:
        print(json.dumps({'evaluation_pages': len(store_pages['evaluation']),
                          'fully_labelled_evaluation_pages': len(evaluation)}), flush=True)
    if a.label_store and not evaluation:
        if a.require_real_eval or not a.annotations:
            p.error('no fully labelled evaluation pages: validation and test come from the drawings in '
                    '1_blueprints/evaluation; label some of their pages completely ("every symbol on this '
                    'page is labeled", or "verified no target symbols")')
        print(json.dumps({
            'note': 'no fully labelled evaluation pages in 1_blueprints/evaluation; '
                    'val/test will use held-out complete synthetic/walls-only groups',
        }), flush=True)
    patterns = list(a.annotations) + store_pages['train']
    if not patterns:
        p.error('give --annotations and/or --label-store')
    result = prepare(patterns, a.output, train_tiles_per_page=a.train_tiles_per_page,
                     train_variants=a.train_variants, val_fraction=a.val_fraction,
                     test_fraction=a.test_fraction, seed=a.seed,
                     stress_val=a.stress_val, stress_test=a.stress_test,
                     allow_unverified_completeness=a.allow_unverified_completeness,
                     allow_partial_training=a.allow_partial_training,
                     verified_wall_negative_fraction=a.verified_wall_negative_fraction,
                     verified_wall_blank_fraction=a.verified_wall_blank_fraction,
                     real_blank_tiles_per_page=a.real_blank_tiles_per_page,
                     real_complete_tiles_per_page=a.real_complete_tiles_per_page,
                     real_blank_negative_fraction=a.real_blank_negative_fraction,
                     real_complete_negative_fraction=a.real_complete_negative_fraction,
                     locked_manifest=a.locked_manifest,
                     eval_scales=tuple(float(v) for v in a.eval_scales.split(',')),
                     eval_overlap=a.eval_overlap,
                     evaluation_files=evaluation,
                     train_only_files=store_pages['train'] if not evaluation else None)
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
