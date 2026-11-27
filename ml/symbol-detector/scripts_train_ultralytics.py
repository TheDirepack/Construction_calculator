"""Explicit, blueprint-only Ultralytics YOLO26n-P2 training launcher.

No general-purpose pretrained weights or dataset are used or downloaded.
With --weights, initialize from our own SSL backbone and verify every
transferred tensor in the actual DetectionTrainer model.
Default is PLAN ONLY. Training ONLY starts with --run, on user's own machine.

  python scripts_train_ultralytics.py prepared_yolo/data.yaml
  python scripts_train_ultralytics.py prepared_yolo/data.yaml --run
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path


def plan(data: Path, *, batch: int = 2, epochs: int = 300,
         optimizer: str = 'MuSGD', imgsz: int = 512, workers: int = 2,
         lr0: float | None = None, fliplr: float = 0.0, flipud: float = 0.0,
         scale: float = 0.0, translate: float = 0.0,
         loss_cfg: dict[str, float] | None = None,
         real_eval_every: int = 0,
         real_eval_manifest: Path | None = None,
         real_eval_blank_pages: int = 24,
         real_eval_complete_pages: int = 24) -> dict:
    if not data.is_file():
        raise FileNotFoundError(f'Prepared blueprint dataset YAML not found: {data}')
    if batch < 1 or epochs < 1 or workers < 0 or imgsz < 128 or imgsz % 32:
        raise ValueError('invalid training dimensions/settings')
    if optimizer not in ('auto', 'AdamW', 'MuSGD', 'SGD'):
        raise ValueError('invalid optimizer')
    from blueprint_symbols.label_schema import MIRROR_ALLOWED
    if (fliplr or flipud) and not MIRROR_ALLOWED:
        raise ValueError('flips mirror text and arrows (S3, GFI, WP, exit arrows) that never occur '
                         'in drawings; the catalog augmentation policy forbids them (mirror: false). '
                         'Rotations are applied per device class by the tile export.')
    cfg = {
        'architecture': 'yolo26n-p2.yaml',
        'initialization': 'random',
        'pretrained': False,
        'data': str(data.resolve()),
        'epochs': epochs,
        'imgsz': imgsz,
        'batch': batch,
        'optimizer': optimizer,
        'amp': True,
        'device': 0,
        'workers': workers,
        'cache': False,
        'mosaic': 0.0, 'mixup': 0.0, 'close_mosaic': 0,
        'hsv_h': 0.0, 'hsv_s': 0.0, 'hsv_v': 0.0,
        'fliplr': fliplr, 'flipud': flipud,
        'degrees': 0.0, 'translate': translate,
        'scale': scale, 'shear': 0.0, 'perspective': 0.0,
        'patience': 40, 'cos_lr': True, 'seed': 2026,
        'notes': [
            'Only annotations converted from blueprint/CAD sources are accepted as input.',
            'Random-initialized detector; no COCO, Objects365, or general YOLO weights.',
            '6 GiB GPU is a target: profile VRAM and reduce batch if needed.',
            'Prepared tiles already contain explicit symbol size and damage variations.',
            'Final held-out test is excluded from checkpoint selection.',
        ],
    }
    if lr0 is not None:
        cfg['lr0'] = lr0
    if loss_cfg is not None:
        cfg['phase2_loss_cfg'] = {k: float(v) for k, v in loss_cfg.items()}
    if real_eval_every > 0:
        cfg['real_eval'] = {
            'every_epochs': int(real_eval_every),
            'manifest': str(real_eval_manifest.resolve()) if real_eval_manifest else None,
            'blank_pages': int(real_eval_blank_pages),
            'complete_pages': int(real_eval_complete_pages),
        }
    return cfg


def main():
    from blueprint_symbols.partial_supervision import DEFAULT_PHASE2_LOSS_CFG, read_supervision

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('data_yaml', type=Path)
    parser.add_argument('--batch', type=int, default=2)
    parser.add_argument('--epochs', type=int, default=300)
    parser.add_argument('--imgsz', type=int, default=512)
    parser.add_argument('--workers', type=int, default=2)
    parser.add_argument('--optimizer', choices=('auto', 'AdamW', 'MuSGD', 'SGD'), default='MuSGD')
    parser.add_argument('--lr0', type=float, default=None, help='Initial learning rate (important for AdamW, e.g. 0.0008)')
    parser.add_argument('--fliplr', type=float, default=0.0)
    parser.add_argument('--flipud', type=float, default=0.0)
    parser.add_argument('--scale', type=float, default=0.0)
    parser.add_argument('--translate', type=float, default=0.0)
    parser.add_argument('--project', type=Path, help='Ultralytics output folder (default: ./runs/detect)')
    parser.add_argument('--name', help='run name inside --project')
    parser.add_argument('--exist-ok', action='store_true', help='reuse an existing run folder')
    parser.add_argument('--weights', type=Path,
                        help='Optional blueprint-only SSL backbone+neck or Phase-2 detector checkpoint (best.pt)')
    parser.add_argument('--real-eval-every', type=int, default=0,
                        help='Run deterministic full-page real blank/complete eval every N epochs (0 disables)')
    parser.add_argument('--real-eval-manifest', type=Path, default=None,
                        help='Optional source_manifest.json path for periodic real-page eval')
    parser.add_argument('--real-eval-blank-pages', type=int, default=24)
    parser.add_argument('--real-eval-complete-pages', type=int, default=24)
    parser.add_argument('--real-blank-neg-weight', type=float, default=DEFAULT_PHASE2_LOSS_CFG['real_blank_neg_weight'])
    parser.add_argument('--real-complete-far-neg-weight', type=float, default=DEFAULT_PHASE2_LOSS_CFG['real_complete_far_neg_weight'])
    parser.add_argument('--real-complete-near-neg-weight', type=float, default=DEFAULT_PHASE2_LOSS_CFG['real_complete_near_neg_weight'])
    parser.add_argument('--real-complete-wrong-cls-weight', type=float, default=DEFAULT_PHASE2_LOSS_CFG['real_complete_wrong_cls_weight'])
    parser.add_argument('--real-complete-pos-weight', type=float, default=DEFAULT_PHASE2_LOSS_CFG['real_complete_pos_weight'])
    parser.add_argument('--synthetic-complete-neg-weight', type=float, default=DEFAULT_PHASE2_LOSS_CFG['synthetic_complete_neg_weight'])
    parser.add_argument('--synthetic-complete-wrong-cls-weight', type=float, default=DEFAULT_PHASE2_LOSS_CFG['synthetic_complete_wrong_cls_weight'])
    parser.add_argument('--fp-boost', type=float, default=DEFAULT_PHASE2_LOSS_CFG['fp_boost'])
    parser.add_argument('--run', action='store_true', help='Explicitly start training on your machine')
    args = parser.parse_args()
    loss_cfg = {
        'real_blank_neg_weight': args.real_blank_neg_weight,
        'real_complete_far_neg_weight': args.real_complete_far_neg_weight,
        'real_complete_near_neg_weight': args.real_complete_near_neg_weight,
        'real_complete_wrong_cls_weight': args.real_complete_wrong_cls_weight,
        'real_complete_pos_weight': args.real_complete_pos_weight,
        'synthetic_complete_neg_weight': args.synthetic_complete_neg_weight,
        'synthetic_complete_wrong_cls_weight': args.synthetic_complete_wrong_cls_weight,
        'fp_boost': args.fp_boost,
    }
    eval_manifest = args.real_eval_manifest
    if eval_manifest is None and args.real_eval_every > 0:
        candidate = args.data_yaml.resolve().parent.parent / 'source_manifest.json'
        if candidate.is_file():
            eval_manifest = candidate
    config = plan(args.data_yaml, batch=args.batch, epochs=args.epochs,
                  optimizer=args.optimizer, imgsz=args.imgsz, workers=args.workers,
                  lr0=args.lr0, fliplr=args.fliplr, flipud=args.flipud,
                  scale=args.scale, translate=args.translate,
                  loss_cfg=loss_cfg,
                  real_eval_every=args.real_eval_every,
                  real_eval_manifest=eval_manifest,
                  real_eval_blank_pages=args.real_eval_blank_pages,
                  real_eval_complete_pages=args.real_eval_complete_pages)
    if args.weights:
        config["initialization"] = "blueprint_checkpoint_verified_transfer"
        config["ssl_checkpoint"] = str(args.weights.expanduser().resolve())
    # Standard YOLO loss incorrectly calls missing real-device labels
    # background. Activate the actual per-class loss when sidecar says partial.
    supervision = read_supervision(args.data_yaml)
    if supervision is not None:
        config['supervision'] = ('masked_partial_real_labels'
                                 if supervision['has_partial'] else 'complete_labels')
        config['partial_training_images'] = sum(
            len(v) < len(supervision['class_ids'])
            for v in supervision['samples'].values())
    print(json.dumps(config, indent=2))
    if not args.run:
        print('PLAN ONLY. No training or model downloads performed.')
        return
    try:
        import torch
    except ImportError:
        parser.error('PyTorch with CUDA is required to train')
    if not torch.cuda.is_available():
        parser.error('NVIDIA CUDA GPU unavailable; refusing CPU training')
    try:
        from ultralytics import YOLO
    except ImportError:
        parser.error('Install Ultralytics on your own training machine')
    model = YOLO(config['architecture'])
    ssl_trainer = None
    if supervision is not None and supervision['has_partial']:
        from blueprint_symbols.partial_supervision import build_masked_detection_trainer
        ssl_trainer = build_masked_detection_trainer(args.data_yaml, args.weights, loss_cfg=loss_cfg)
    elif args.weights:
        # Model.train() constructs a NEW DetectionModel. Loading a state dict
        # into the YOLO(yaml) object here would silently discard those weights.
        # Instead inject the verified SSL tensors into the actual training model
        # during DetectionTrainer.get_model().
        from blueprint_symbols.ssl_transfer import build_ssl_detection_trainer
        ssl_trainer = build_ssl_detection_trainer(args.weights)
    if args.real_eval_every > 0 and eval_manifest is not None and eval_manifest.is_file():
        from blueprint_symbols.real_page_eval import build_real_eval_callback
        model.add_callback(
            'on_fit_epoch_end',
            build_real_eval_callback(
                manifest_path=eval_manifest,
                every_epochs=args.real_eval_every,
                max_blank_pages=args.real_eval_blank_pages,
                max_complete_pages=args.real_eval_complete_pages,
                imgsz=args.imgsz,
            ),
        )
    strides = [int(v) for v in model.model.stride]
    if 4 not in strides:
        parser.error(f'P2 stride-4 head is missing; found strides: {strides}')
    keywords = {k: v for k, v in config.items() if k in (
        'epochs', 'imgsz', 'batch', 'optimizer', 'lr0', 'amp', 'device', 'workers',
        'cache', 'mosaic', 'mixup', 'close_mosaic', 'hsv_h', 'hsv_s', 'hsv_v',
        'fliplr', 'flipud', 'degrees', 'translate', 'scale', 'shear',
        'perspective', 'patience', 'cos_lr', 'seed', 'pretrained')}
    if args.project:
        keywords['project'] = str(args.project.resolve())
    if args.name:
        keywords['name'] = args.name
    if args.exist_ok:
        keywords['exist_ok'] = True
    model.train(data=config['data'], trainer=ssl_trainer, **keywords)


if __name__ == '__main__':
    main()
