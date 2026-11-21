"""Minimal differentiable training objective for the detector's raw heads.

This is a *starter loss*, not a validated blueprint training recipe. It assigns
one positive cell per GT box at one scale and reports cell collisions. Dense
symbols and small-object recall will likely require Gaussian targets,
assignment improvements, hard-negative mining and augmentations.
"""
from __future__ import annotations
from collections.abc import Sequence
import math
import torch
import torch.nn.functional as F

# Each batch item is a list of (class_id, (x0, y0, x1, y1)) tuples.
Annotation = tuple[int, tuple[float, float, float, float]]


def detector_loss(heads: Sequence[torch.Tensor],
                  annotations: Sequence[Sequence[Annotation]],
                  image_size: tuple[int, int],
                  ignore_boxes: Sequence[Sequence[tuple[float, float, float, float]]] | None = None) -> tuple[torch.Tensor, dict[str, int]]:
    """Returns scalar supervised loss and assignment statistics.

    This reference implementation assumes each source image has been resized or
    tiled to match the model input. Box coordinates must be pixel coordinates
    in that input, *not* full-page coordinates.
    """
    if len(heads) != 3:
        raise ValueError('Expected exactly P2/P3/P4 heads')
    width, height = image_size
    if width <= 0 or height <= 0 or len(annotations) != heads[0].shape[0]:
        raise ValueError('Image size / batch mismatch')
    if ignore_boxes is not None and len(ignore_boxes) != len(annotations):
        raise ValueError('ignore_boxes must match batch length')
    total = heads[0].new_zeros(())
    assigned = collisions = 0
    for head_index, (pred, stride) in enumerate(zip(heads, (4, 8, 16))):
        batch, channels, h, w = pred.shape
        if channels < 6 or (h, w) != (height // stride, width // stride):
            raise ValueError('Head shape mismatches image dimensions')
        classes = channels - 5
        gt_obj = torch.zeros((batch, h, w), device=pred.device, dtype=pred.dtype)
        targets: list[tuple[int,int,int,int,float,float,float,float]] = []
        for b, annos in enumerate(annotations):
            for category, (x0,y0,x1,y1) in annos:
                if not 0 <= category < classes or not 0 <= x0 < x1 <= width or not 0 <= y0 < y1 <= height:
                    raise ValueError('Invalid class or bbox')
                maxside = max(x1-x0, y1-y0)
                destination = 0 if maxside <= 40 else 1 if maxside <= 110 else 2
                if destination != head_index:
                    continue
                cx, cy = (x0+x1)*.5/stride, (y0+y1)*.5/stride
                ix, iy = min(int(cx), w-1), min(int(cy), h-1)
                if gt_obj[b,iy,ix]:
                    collisions += 1
                    continue
                gt_obj[b,iy,ix] = 1.0
                targets.append((b,category,iy,ix,cx-ix,cy-iy,math.log(x1-x0),math.log(y1-y0)))
                assigned += 1
        logits = pred[:,0]
        ce = F.binary_cross_entropy_with_logits(logits,gt_obj,reduction='none')
        positive = gt_obj.bool()
        ignored = torch.zeros_like(positive)
        if ignore_boxes is not None:
            xx=(torch.arange(w,device=pred.device,dtype=pred.dtype)+.5)*stride
            yy=(torch.arange(h,device=pred.device,dtype=pred.dtype)+.5)*stride
            for b,boxes in enumerate(ignore_boxes):
                for x0,y0,x1,y1 in boxes:
                    ignored[b] |= ((xx[None,:]>=x0)&(xx[None,:]<x1)&
                                   (yy[:,None]>=y0)&(yy[:,None]<y1))
        negative = ~positive & ~ignored
        num_pos = max(1, int(positive.sum().item()))
        object_loss = ce[positive].sum()/num_pos
        if bool(negative.any()):
            object_loss = object_loss + .20*ce[negative].mean()
        total = total + object_loss
        if targets:
            bs = torch.tensor([v[0] for v in targets],device=pred.device,dtype=torch.long)
            cls = torch.tensor([v[1] for v in targets],device=pred.device,dtype=torch.long)
            ys = torch.tensor([v[2] for v in targets],device=pred.device,dtype=torch.long)
            xs = torch.tensor([v[3] for v in targets],device=pred.device,dtype=torch.long)
            label = torch.zeros((len(targets),classes),device=pred.device,dtype=pred.dtype)
            label.scatter_(1,cls[:,None],1.)
            class_logits = pred[bs,1:1+classes,ys,xs]
            offset_logits = pred[bs,1+classes:3+classes,ys,xs]
            log_size = pred[bs,3+classes:5+classes,ys,xs]
            xy_targets = torch.tensor([[v[4],v[5]] for v in targets],device=pred.device,dtype=pred.dtype)
            wh_targets = torch.tensor([[v[6],v[7]] for v in targets],device=pred.device,dtype=pred.dtype)
            total = total + F.binary_cross_entropy_with_logits(class_logits,label)
            total = total + F.binary_cross_entropy_with_logits(offset_logits,xy_targets)
            total = total + .25*F.smooth_l1_loss(log_size,wh_targets)
    return total, {'assigned': assigned, 'collisions': collisions}
