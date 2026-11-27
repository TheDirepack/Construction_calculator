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
from ultralytics.utils.tal import make_anchors

from .partial_supervision import DEFAULT_PHASE2_LOSS_CFG

ROLE_PARTIAL = 0
ROLE_SYNTHETIC_COMPLETE = 1
ROLE_REAL_COMPLETE = 2
ROLE_REAL_BLANK = 3

ROLE_NAME_TO_ID = {
    'partial': ROLE_PARTIAL,
    'synthetic_complete': ROLE_SYNTHETIC_COMPLETE,
    'real_complete': ROLE_REAL_COMPLETE,
    'real_blank': ROLE_REAL_BLANK,
}


class PositiveAwareBCE(nn.Module):
    """Suppress false negative gradients for unverified classes/background,
    and apply precision-first penalties on verified blank/complete real pages."""

    def __init__(
        self,
        *,
        real_blank_neg_weight: float = DEFAULT_PHASE2_LOSS_CFG['real_blank_neg_weight'],
        real_complete_far_neg_weight: float = DEFAULT_PHASE2_LOSS_CFG['real_complete_far_neg_weight'],
        real_complete_near_neg_weight: float = DEFAULT_PHASE2_LOSS_CFG['real_complete_near_neg_weight'],
        real_complete_wrong_cls_weight: float = DEFAULT_PHASE2_LOSS_CFG['real_complete_wrong_cls_weight'],
        real_complete_pos_weight: float = DEFAULT_PHASE2_LOSS_CFG['real_complete_pos_weight'],
        synthetic_complete_neg_weight: float = DEFAULT_PHASE2_LOSS_CFG['synthetic_complete_neg_weight'],
        synthetic_complete_wrong_cls_weight: float = DEFAULT_PHASE2_LOSS_CFG['synthetic_complete_wrong_cls_weight'],
        partial_wrong_cls_weight: float = DEFAULT_PHASE2_LOSS_CFG['partial_wrong_cls_weight'],
        fp_boost: float = DEFAULT_PHASE2_LOSS_CFG['fp_boost'],
    ):
        super().__init__()
        self.real_blank_neg_weight = float(real_blank_neg_weight)
        self.real_complete_far_neg_weight = float(real_complete_far_neg_weight)
        self.real_complete_near_neg_weight = float(real_complete_near_neg_weight)
        self.real_complete_wrong_cls_weight = float(real_complete_wrong_cls_weight)
        self.real_complete_pos_weight = float(real_complete_pos_weight)
        self.synthetic_complete_neg_weight = float(synthetic_complete_neg_weight)
        self.synthetic_complete_wrong_cls_weight = float(synthetic_complete_wrong_cls_weight)
        self.partial_wrong_cls_weight = float(partial_wrong_cls_weight)
        self.fp_boost = float(fp_boost)
        self.verified_class_mask = None
        self.sample_role = None
        self.near_gt_mask = None

    def forward(self, logits: torch.Tensor, scores: torch.Tensor) -> torch.Tensor:
        coverage = self.verified_class_mask
        if coverage is None:
            raise RuntimeError('actual YOLO loss received no verified-class mask')
        if (coverage.ndim != 2 or coverage.shape[0] != logits.shape[0] or
                coverage.shape[1] != logits.shape[-1]):
            raise ValueError('phase-two class mask mismatches YOLO output')
        # Assigned known positives are always trainable; at a verified
        # foreground anchor every other class is an explicit wrong-class
        # negative even on positive-only partial tiles, while unverified
        # background anchors receive zero negative gradient.
        pos_mask = scores > 0
        fg_anchor = pos_mask.any(dim=-1, keepdim=True)  # (B, A, 1)
        allowed = coverage[:, None, :] | pos_mask | fg_anchor
        base_loss = F.binary_cross_entropy_with_logits(logits, scores, reduction='none')
        weights = allowed.to(dtype=logits.dtype)

        role = self.sample_role
        if role is None:
            return base_loss * weights
        if role.ndim != 1 or role.shape[0] != logits.shape[0]:
            raise ValueError('phase-two sample_role mismatches batch size')

        neg_allowed = allowed & (~pos_mask)
        p_det = torch.sigmoid(logits.detach()).to(dtype=logits.dtype)
        fp_factor = 1.0 + self.fp_boost * p_det
        half_fp_factor = 1.0 + 0.5 * self.fp_boost * p_det

        is_partial = (role == ROLE_PARTIAL)[:, None, None]
        if is_partial.any():
            partial_wrong = is_partial & fg_anchor & (~pos_mask)
            weights = torch.where(partial_wrong, self.partial_wrong_cls_weight * fp_factor, weights)

        is_syn_complete = (role == ROLE_SYNTHETIC_COMPLETE)[:, None, None]
        if is_syn_complete.any():
            syn_bg = is_syn_complete & neg_allowed & (~fg_anchor)
            syn_wrong = is_syn_complete & neg_allowed & fg_anchor
            weights = torch.where(syn_bg, self.synthetic_complete_neg_weight * half_fp_factor, weights)
            weights = torch.where(syn_wrong, self.synthetic_complete_wrong_cls_weight * fp_factor, weights)

        is_real_blank = (role == ROLE_REAL_BLANK)[:, None, None]
        if is_real_blank.any():
            blank_neg = is_real_blank & neg_allowed
            weights = torch.where(blank_neg, self.real_blank_neg_weight * fp_factor, weights)

        is_real_complete = (role == ROLE_REAL_COMPLETE)[:, None, None]
        if is_real_complete.any():
            near_mask = self.near_gt_mask
            if near_mask is not None:
                if near_mask.shape != (logits.shape[0], logits.shape[1]):
                    raise ValueError('phase-two near_gt_mask mismatches batch/anchor shape')
                near_anchor = near_mask[:, :, None] | fg_anchor
            else:
                near_anchor = fg_anchor
            far_bg = is_real_complete & neg_allowed & (~near_anchor)
            near_bg = is_real_complete & neg_allowed & near_anchor & (~fg_anchor)
            wrong_cls = is_real_complete & neg_allowed & fg_anchor
            right_cls = is_real_complete & pos_mask
            weights = torch.where(far_bg, self.real_complete_far_neg_weight * fp_factor, weights)
            weights = torch.where(near_bg, self.real_complete_near_neg_weight * half_fp_factor, weights)
            weights = torch.where(wrong_cls, self.real_complete_wrong_cls_weight * fp_factor, weights)
            pos_w = weights.new_tensor(self.real_complete_pos_weight)
            weights = torch.where(right_cls, pos_w, weights)

        return base_loss * weights


class PartialDetectionLoss(v8DetectionLoss):
    """One branch of YOLO26's detection loss with verified negative masking."""

    def __init__(self, model, tal_topk=10, tal_topk2=None):
        super().__init__(model, tal_topk=tal_topk, tal_topk2=tal_topk2)
        raw_cfg = getattr(model, 'phase2_loss_cfg', None) or {}
        loss_kwargs = {k: float(raw_cfg[k]) for k in DEFAULT_PHASE2_LOSS_CFG if k in raw_cfg}
        self.bce = PositiveAwareBCE(**loss_kwargs)

    def _compute_near_gt_mask(self, preds: dict[str, torch.Tensor], batch: dict[str, torch.Tensor]) -> torch.Tensor:
        pred_scores = preds['scores']
        batch_size = pred_scores.shape[0]
        anchor_points, stride_tensor = make_anchors(preds['feats'], self.stride, 0.5)
        anchor_xy = anchor_points * stride_tensor  # (A, 2)
        num_anchors = anchor_xy.shape[0]
        if batch['bboxes'].numel() == 0:
            return torch.zeros((batch_size, num_anchors), dtype=torch.bool, device=self.device)
        dtype = pred_scores.dtype
        imgsz = torch.tensor(preds['feats'][0].shape[2:], device=self.device, dtype=dtype) * self.stride[0]
        targets = torch.cat((batch['batch_idx'].view(-1, 1), batch['cls'].view(-1, 1), batch['bboxes']), 1)
        targets = self.preprocess(targets.to(self.device), batch_size, scale_tensor=imgsz[[1, 0, 1, 0]])
        _, gt_bboxes = targets.split((1, 4), 2)  # (B, M, 4) xyxy pixels
        if gt_bboxes.shape[1] == 0:
            return torch.zeros((batch_size, num_anchors), dtype=torch.bool, device=self.device)
        bw = (gt_bboxes[..., 2] - gt_bboxes[..., 0]).clamp(min=0.0)
        bh = (gt_bboxes[..., 3] - gt_bboxes[..., 1]).clamp(min=0.0)
        valid = (bw > 0) & (bh > 0)
        margin = torch.maximum(0.25 * torch.maximum(bw, bh), torch.full_like(bw, 12.0))
        gx0 = (gt_bboxes[..., 0] - margin).unsqueeze(-1)
        gy0 = (gt_bboxes[..., 1] - margin).unsqueeze(-1)
        gx1 = (gt_bboxes[..., 2] + margin).unsqueeze(-1)
        gy1 = (gt_bboxes[..., 3] + margin).unsqueeze(-1)
        ax = anchor_xy[:, 0].view(1, 1, -1)
        ay = anchor_xy[:, 1].view(1, 1, -1)
        inside = valid.unsqueeze(-1) & (ax >= gx0) & (ax <= gx1) & (ay >= gy0) & (ay <= gy1)
        return inside.any(dim=1)

    def loss(self, preds, batch):
        coverage = batch.get('verified_class_mask')
        if not isinstance(coverage, torch.Tensor):
            raise RuntimeError('partial real-image training batch lacks class mask')
        self.bce.verified_class_mask = coverage.to(device=self.device, dtype=torch.bool)
        role = batch.get('sample_role')
        if isinstance(role, torch.Tensor):
            role_dev = role.to(device=self.device, dtype=torch.long)
            self.bce.sample_role = role_dev
            self.bce.near_gt_mask = (
                self._compute_near_gt_mask(preds, batch)
                if bool((role_dev == ROLE_REAL_COMPLETE).any())
                else None
            )
        else:
            self.bce.sample_role = None
            self.bce.near_gt_mask = None
        try:
            return super().loss(preds, batch)
        finally:
            self.bce.verified_class_mask = None
            self.bce.sample_role = None
            self.bce.near_gt_mask = None


class PartialDetectionModel(DetectionModel):
    """Global class so the trained weights/checkpoint remain serializable."""

    def init_criterion(self):
        if getattr(self.model[-1], 'one2one_cv2', None) is not None:
            return E2ELoss(self, PartialDetectionLoss)
        return PartialDetectionLoss(self)

    def loss(self, batch, preds=None):
        if (not self.training and not torch.is_grad_enabled()
                and 'verified_class_mask' not in batch):
            img = batch['img']
            nc = int(self.model[-1].nc)
            batch = dict(
                batch,
                verified_class_mask=torch.ones(
                    (img.shape[0], nc), dtype=torch.bool, device=img.device
                ),
            )
        return super().loss(batch, preds=preds)


class PartialYOLODataset(YOLODataset):
    """Attach per-image verified class masks and supervision roles to train batches."""

    def __init__(
        self,
        *args,
        coverage: dict[str, list[int]],
        num_classes: int,
        sample_roles: dict[str, str] | None = None,
        **kwargs,
    ):
        self.verified_coverage = coverage
        self.supervised_classes = num_classes
        self.sample_roles = sample_roles
        super().__init__(*args, **kwargs)
        missing = [file for file in self.im_files if Path(file).stem not in coverage]
        if missing:
            raise ValueError(f'missing phase-two class mask for train images: {missing[:4]}')
        if sample_roles is not None:
            missing_roles = [file for file in self.im_files if Path(file).stem not in sample_roles]
            if missing_roles:
                raise ValueError(f'missing phase-two sample_roles for train images: {missing_roles[:4]}')

    def __getitem__(self, index):
        sample = super().__getitem__(index)
        image_name = Path(self.im_files[index]).stem
        verified_ids = self.verified_coverage[image_name]
        coverage = torch.zeros(self.supervised_classes, dtype=torch.bool)
        coverage[verified_ids] = True
        sample['verified_class_mask'] = coverage
        if self.sample_roles is not None:
            role_id = ROLE_NAME_TO_ID[self.sample_roles[image_name]]
        else:
            role_id = ROLE_PARTIAL if len(verified_ids) < self.supervised_classes else ROLE_SYNTHETIC_COMPLETE
        sample['sample_role'] = torch.tensor(role_id, dtype=torch.long)
        return sample

    @staticmethod
    def collate_fn(batch):
        masks = torch.stack([sample.pop('verified_class_mask') for sample in batch])
        roles = [sample.pop('sample_role') for sample in batch] if all('sample_role' in s for s in batch) else None
        data = YOLODataset.collate_fn(batch)
        data['verified_class_mask'] = masks
        if roles is not None:
            data['sample_role'] = torch.stack(roles)
        return data
