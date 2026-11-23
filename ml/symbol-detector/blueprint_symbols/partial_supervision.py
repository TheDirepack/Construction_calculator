"""Real/synthetic mixed phase-two supervision for the pinned Ultralytics YOLO26.

The regular YOLO BCE target is zero for every unlabeled class/anchor. On a
partially annotated real drawing that trains missed true objects as negatives.
A source can verify background *per target class* with exhaustive_families;
only those classes receive negative classification gradients. All assigned,
known positive target scores remain trainable, even for otherwise incomplete
classes.

This module is used by the actual Ultralytics E2E one-to-many + one-to-one
criterion. It never invents bounding boxes, hard negatives or pseudo labels.
"""
from __future__ import annotations

import json
from pathlib import Path


def read_supervision(data_yaml: Path) -> dict | None:
    """Read versioned sample masks and enforce exact on-disk coverage."""
    root = data_yaml.resolve().parent
    path = root / 'phase2_supervision.json'
    if not path.is_file():
        # Every dataset from our exporter carries provenance and a sidecar.
        # Without the sidecar, ordinary YOLO would train unknown devices as
        # background. External fully labeled YOLO datasets remain supported.
        if (root / 'sample_provenance.jsonl').is_file() or (
                data_yaml.is_file() and
                'phase2_supervision:' in data_yaml.read_text(encoding='utf-8')):
            raise ValueError('missing phase-two supervision sidecar; cannot use ordinary YOLO loss')
        return None
    raw = json.loads(path.read_text())
    if not isinstance(raw, dict) or raw.get('format_version') != 1:
        raise ValueError('unsupported phase-two supervision metadata version')
    classes = raw.get('class_ids')
    if (not isinstance(classes, dict) or not classes or
            any(not isinstance(name, str) or not name.strip() or
                type(index) is not int for name, index in classes.items()) or
            sorted(classes.values()) != list(range(len(classes)))):
        raise ValueError('invalid phase-two class mapping')
    samples = raw.get('samples')
    if not isinstance(samples, dict) or not samples:
        raise ValueError('missing phase-two sample-level supervision masks')
    files = list((root / 'images' / 'train').glob('*'))
    seen = {p.stem for p in files if p.is_file() and p.suffix.lower() in (
        '.jpg', '.jpeg', '.png', '.webp', '.bmp', '.tif', '.tiff')}
    if not seen or set(samples) != seen:
        raise ValueError('phase-two supervision masks differ from training images')
    image_stems = [p.stem for p in files if p.is_file() and p.suffix.lower() in (
        '.jpg', '.jpeg', '.png', '.webp', '.bmp', '.tif', '.tiff')]
    if len(image_stems) != len(seen):
        raise ValueError('duplicate training-image stems in phase-two dataset')
    labels = root / 'labels' / 'train'
    if ({p.stem for p in labels.glob('*.txt')} if labels.is_dir() else set()) != seen:
        raise ValueError('phase-two label files differ from training images')
    n = len(classes)
    for name, class_ids in samples.items():
        if (not isinstance(class_ids, list) or
                any(type(i) is not int or i < 0 or i >= n for i in class_ids) or
                class_ids != sorted(set(class_ids))):
            raise ValueError(f'invalid verified-negative class mask for sample {name}')
    raw['has_partial'] = any(len(ids) != n for ids in samples.values())
    if raw['has_partial'] and raw.get('partial_training_enabled') is not True:
        raise ValueError('partial training masks require explicit partial_training_enabled=true')
    return raw



def build_masked_detection_trainer(data_yaml: Path, weights: Path | None = None):
    """Real YOLO26 trainer using global (checkpoint-picklable) masked model/loss.

    Preserves the verified backbone transfer hook when an SSL checkpoint is
    supplied. A custom trainer is necessary because the standard YOLO
    criterion cannot represent unknown-background labels.
    """
    from ultralytics import __version__ as ultralytics_version
    from ultralytics.models.yolo.detect import DetectionTrainer
    from ultralytics.utils import colorstr
    from ultralytics.utils.torch_utils import unwrap_model
    from .partial_yolo import PartialYOLODataset, PartialDetectionModel

    if ultralytics_version != '8.4.216':
        raise RuntimeError('partial-label YOLO loss requires tested ultralytics==8.4.216; '
                           f'installed {ultralytics_version}')
    supervision = read_supervision(data_yaml)
    if supervision is None or not supervision['has_partial']:
        raise ValueError('masked trainer requires partially annotated training samples')
    nc = len(supervision['class_ids'])
    if weights is not None:
        from .ssl_transfer import build_ssl_detection_trainer
        base = build_ssl_detection_trainer(weights)
    else:
        base = DetectionTrainer

    class PartialTrainer(base):
        def build_dataset(self, img_path, mode='train', batch=None):
            if mode != 'train':
                return super().build_dataset(img_path, mode, batch)
            cfg = self.args
            if cfg.mosaic or cfg.mixup or cfg.cutmix or cfg.copy_paste:
                raise ValueError('mosaic/mixup/cutmix/copy-paste unsupported with partial masks')
            if cfg.multi_scale:
                raise ValueError('multi_scale augmentation unsupported with partial masks')
            if cfg.single_cls or cfg.classes is not None or cfg.fraction != 1.0:
                raise ValueError('partial supervision requires unfiltered original classes/images')
            if int(self.data['nc']) != nc:
                raise ValueError('phase-two class count does not match model dataset')
            names = self.data['names']
            if isinstance(names, dict):
                names = [names[i] for i in range(nc)]
            if {name: i for i, name in enumerate(names)} != supervision['class_ids']:
                raise ValueError('phase-two class identities do not match YOLO data.yaml')
            stride = max(int(unwrap_model(self.model).stride.max()), 32)
            return PartialYOLODataset(
                img_path=img_path, imgsz=cfg.imgsz, batch_size=batch,
                augment=True, hyp=cfg, rect=cfg.rect, cache=cfg.cache or None,
                single_cls=False, stride=stride, pad=0.0,
                prefix=colorstr('train: '), task=cfg.task,
                classes=None, data=self.data, fraction=1.0,
                tile=cfg.tile, coverage=supervision['samples'],
                num_classes=nc,
            )

        def get_model(self, cfg=None, weights=None, verbose=True):
            model = super().get_model(cfg=cfg, weights=weights, verbose=verbose)
            if type(model).__name__ != 'DetectionModel':
                raise TypeError('unrecognized model type for phase-two partial supervision')
            # Replace the method through a globally importable subclass.
            # Avoid setting a locally defined bound init_criterion function:
            # torch.save()/Ultralytics checkpoints must pickle this model.
            model.__class__ = PartialDetectionModel
            return model

    return PartialTrainer
