"""Explicit, scratch-only Ultralytics YOLO26n-P2 training launcher.

No general-purpose pretrained weights or dataset are used or downloaded.
Default is PLAN ONLY. Training ONLY starts with --run, on user's own machine.

  python scripts_train_ultralytics.py prepared_yolo/data.yaml
  python scripts_train_ultralytics.py prepared_yolo/data.yaml --run
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path


def plan(data: Path, *, batch: int = 2, epochs: int = 300,
         optimizer: str = 'MuSGD', imgsz: int = 512, workers: int = 2) -> dict:
    if not data.is_file():
        raise FileNotFoundError(f'Prepared blueprint dataset YAML not found: {data}')
    if batch < 1 or epochs < 1 or workers < 0 or imgsz < 128 or imgsz % 32:
        raise ValueError('invalid training dimensions/settings')
    if optimizer not in ('auto', 'AdamW', 'MuSGD', 'SGD'):
        raise ValueError('invalid optimizer')
    return {
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
        'fliplr': 0.0, 'flipud': 0.0,
        'degrees': 0.0, 'translate': 0.0,
        'scale': 0.0, 'shear': 0.0, 'perspective': 0.0,
        'patience': 40, 'cos_lr': True, 'seed': 2026,
        'notes': [
            'Only annotations converted from blueprint/CAD sources are accepted as input.',
            'Random-initialized detector; no COCO, Objects365, or general YOLO weights.',
            '6 GiB GPU is a target: profile VRAM and reduce batch if needed.',
            'Prepared tiles already contain explicit symbol size and damage variations.',
            'Final held-out test is excluded from checkpoint selection.',
        ],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('data_yaml', type=Path)
    parser.add_argument('--batch', type=int, default=2)
    parser.add_argument('--epochs', type=int, default=300)
    parser.add_argument('--imgsz', type=int, default=512)
    parser.add_argument('--workers', type=int, default=2)
    parser.add_argument('--optimizer', choices=('auto', 'AdamW', 'MuSGD', 'SGD'), default='MuSGD')
    parser.add_argument('--project', type=Path, help='Ultralytics output folder (default: ./runs/detect)')
    parser.add_argument('--name', help='run name inside --project')
    parser.add_argument('--exist-ok', action='store_true', help='reuse an existing run folder')
    parser.add_argument('--run', action='store_true', help='Explicitly start training on your machine')
    args = parser.parse_args()
    config = plan(args.data_yaml, batch=args.batch, epochs=args.epochs,
                  optimizer=args.optimizer, imgsz=args.imgsz, workers=args.workers)
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
    strides = [int(v) for v in model.model.stride]
    if 4 not in strides:
        parser.error(f'P2 stride-4 head is missing; found strides: {strides}')
    keywords = {k: v for k, v in config.items() if k in (
        'epochs', 'imgsz', 'batch', 'optimizer', 'amp', 'device', 'workers',
        'cache', 'mosaic', 'mixup', 'close_mosaic', 'hsv_h', 'hsv_s', 'hsv_v',
        'fliplr', 'flipud', 'degrees', 'translate', 'scale', 'shear',
        'perspective', 'patience', 'cos_lr', 'seed', 'pretrained')}
    if args.project:
        keywords['project'] = str(args.project.resolve())
    if args.name:
        keywords['name'] = args.name
    if args.exist_ok:
        keywords['exist_ok'] = True
    model.train(data=config['data'], **keywords)


if __name__ == '__main__':
    main()
