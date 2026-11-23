"""Pickle-safe Ultralytics YOLO26 class-masked criterion and dataset.

Pinned to ultralytics==8.4.216. Classes must live at module scope:
Ultralytics saves complete detector model objects in checkpoints, while local
functions/classes inserted on model instances cannot be pickled reliably.
"""
from __future__ import annotations

from pathlib import Path

import torch
import torch.nn as nn
import torch.nn.functional as F

from ultralytics.data.dataset import YOLODataset
from ultralytics.nn.tasks import DetectionModel
from ultralytics.utils.loss import E2ELoss, v8DetectionLoss


class PositiveAwareBCE(nn.Module):
    """Suppress false negative gradients for unverified classes/background."""

    def __init__(self):
        super().__init__()
        self.verified_class_mask = None

    def forward(self, logits: torch.Tensor, scores: torch.Tensor) -> torch.Tensor:
        coverage = self.verified_class_mask
        if coverage is None:
            raise RuntimeError('actual YOLO loss received no verified-class mask')
        if (coverage.ndim != 2 or coverage.shape[0] != logits.shape[0] or
                coverage.shape[1] != logits.shape[-1]):
            raise ValueError('phase-two class mask mismatches YOLO output')
        # Assigned known positives are always trainable; unverified target
        # classes receive no background-negative gradient at other anchors.
        allowed = coverage[:, None, :] | (scores > 0)
        return F.binary_cross_entropy_with_logits(
            logits, scores, reduction='none'
        ) * allowed.to(dtype=logits.dtype)


class PartialDetectionLoss(v8DetectionLoss):
    """One branch of YOLO26's detection loss with verified negative masking."""

    def __init__(self, model, tal_topk=10, tal_topk2=None):
        super().__init__(model, tal_topk=tal_topk, tal_topk2=tal_topk2)
        self.bce = PositiveAwareBCE()

    def loss(self, preds, batch):
        coverage = batch.get('verified_class_mask')
        if not isinstance(coverage, torch.Tensor):
            raise RuntimeError('partial real-image training batch lacks class mask')
        self.bce.verified_class_mask = coverage.to(device=self.device, dtype=torch.bool)
        return super().loss(preds, batch)


class PartialDetectionModel(DetectionModel):
    """Global class so the trained weights/checkpoint remain serializable."""

    def init_criterion(self):
        if getattr(self.model[-1], 'one2one_cv2', None) is not None:
            return E2ELoss(self, PartialDetectionLoss)
        return PartialDetectionLoss(self)


class PartialYOLODataset(YOLODataset):
    """Attach per-image verified class masks to train batches without guessing."""

    def __init__(self, *args, coverage: dict[str, list[int]], num_classes: int, **kwargs):
        self.verified_coverage = coverage
        self.supervised_classes = num_classes
        super().__init__(*args, **kwargs)
        missing = [file for file in self.im_files if Path(file).stem not in coverage]
        if missing:
            raise ValueError(f'missing phase-two class mask for train images: {missing[:4]}')

    def __getitem__(self, index):
        sample = super().__getitem__(index)
        image_name = Path(self.im_files[index]).stem
        coverage = torch.zeros(self.supervised_classes, dtype=torch.bool)
        coverage[self.verified_coverage[image_name]] = True
        sample['verified_class_mask'] = coverage
        return sample

    @staticmethod
    def collate_fn(batch):
        masks = torch.stack([sample.pop('verified_class_mask') for sample in batch])
        data = YOLODataset.collate_fn(batch)
        data['verified_class_mask'] = masks
        return data
