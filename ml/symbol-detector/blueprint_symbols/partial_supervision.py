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
        return None
    raw = json.loads(path.read_text())
    if not isinstance(raw, dict) or raw.get('format_version') != 1:
        raise ValueError('unsupported phase-two supervision metadata version')
    classes = raw.get('class_ids')
    if (not isinstance(classes, dict) or not classes or
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
    n = len(classes)
    for name, class_ids in samples.items():
        if (not isinstance(class_ids, list) or
                any(type(i) is not int or i < 0 or i >= n for i in class_ids) or
                class_ids != sorted(set(class_ids))):
            raise ValueError(f'invalid verified-negative class mask for sample {name}')
    raw['has_partial'] = any(len(ids) != n for ids in samples.values())
    return raw


def build_masked_detection_trainer(data_yaml: Path, weights: Path | None = None):
    """Build a real Ultralytics trainer, preserving verified SSL weight transfer.

    Requires the v8.4.216 YOLO26 API used in this project's regression CI.
    No custom loss is installed for ordinary fully-annotated datasets.
    """
    import torch
    import torch.nn as nn
    import torch.nn.functional as F
    from types import MethodType
    from ultralytics import __version__ as ultralytics_version
    from ultralytics.data.dataset import YOLODataset
    from ultralytics.models.yolo.detect import DetectionTrainer
    from ultralytics.utils import colorstr
    from ultralytics.utils.loss import E2ELoss, v8DetectionLoss
    from ultralytics.utils.torch_utils import unwrap_model

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

    class PositiveAwareBCE(nn.Module):
        def __init__(self):
            super().__init__()
            self.verified_class_mask = None

        def forward(self, logits, assigned_scores):
            if self.verified_class_mask is None:
                raise RuntimeError('missing phase-two classification mask in actual loss')
            mask = self.verified_class_mask
            if (mask.ndim != 2 or mask.shape[0] != logits.shape[0] or
                    mask.shape[1] != logits.shape[-1]):
                raise ValueError('phase-two class mask does not match prediction heads')
            # Known assigned positive scores ALWAYS train. Unknown-background
            # labels NEVER contribute negative BCE gradients.
            allowed = mask[:, None, :] | (assigned_scores > 0)
            return F.binary_cross_entropy_with_logits(
                logits, assigned_scores, reduction='none'
            ) * allowed.to(dtype=logits.dtype)

    class PartialDetectionLoss(v8DetectionLoss):
        def __init__(self, model, tal_topk=10, tal_topk2=None):
            super().__init__(model, tal_topk=tal_topk, tal_topk2=tal_topk2)
            self.bce = PositiveAwareBCE()

        def loss(self, preds, batch):
            mask = batch.get('verified_class_mask')
            if not isinstance(mask, torch.Tensor):
                raise RuntimeError('partial detection batch has no verified class mask')
            self.bce.verified_class_mask = mask.to(device=self.device, dtype=torch.bool)
            return super().loss(preds, batch)

    class PartialYOLODataset(YOLODataset):
        def __init__(self, *args, **kwargs):
            self.coverage = kwargs.pop('coverage')
            super().__init__(*args, **kwargs)
            missing = [p for p in self.im_files if Path(p).stem not in self.coverage]
            if missing:
                raise ValueError(f'missing class masks for training images: {missing[:4]}')

        def __getitem__(self, index):
            item = super().__getitem__(index)
            name = Path(self.im_files[index]).stem
            mask = torch.zeros(nc, dtype=torch.bool)
            mask[self.coverage[name]] = True
            item['verified_class_mask'] = mask
            return item

        @staticmethod
        def collate_fn(batch):
            masks = torch.stack([item.pop('verified_class_mask') for item in batch])
            output = YOLODataset.collate_fn(batch)
            output['verified_class_mask'] = masks
            return output

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
            )

        def get_model(self, cfg=None, weights=None, verbose=True):
            model = super().get_model(cfg=cfg, weights=weights, verbose=verbose)
            def init_partial_criterion(self_model):
                if getattr(self_model.model[-1], 'one2one_cv2', None) is not None:
                    return E2ELoss(self_model, PartialDetectionLoss)
                return PartialDetectionLoss(self_model)
            model.init_criterion = MethodType(init_partial_criterion, model)
            return model

    return PartialTrainer
