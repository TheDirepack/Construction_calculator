"""Train anchor heatmaps from CAD-derived page coordinates without hand labels.

The network's input size is fixed for NPU execution. Annotation positions are
always original page pixels and mapped through the exact crop transform.
Missing/undefined semantic anchors are ignored, never guessed from bbox.
"""
from __future__ import annotations
import numpy as np
import torch
import torch.nn.functional as F
from .crops import CropTransform, crop_for_refiner


def anchor_targets(anchors: dict, names: tuple[str, ...], crop: CropTransform,
                   *, patch_size: int = 160, heatmap_size: int = 40,
                   sigma: float = 1.3) -> tuple[torch.Tensor,torch.Tensor]:
    """Return [A,H,W] Gaussian heatmaps and [A] supervision mask.

    Sources of anchor truth should be CAD block-local, explicit vector geometry,
    or *reviewed* pseudo-labels. The box center is not a substitute for a
    physical wall-attachment point.
    """
    if patch_size <= 0 or heatmap_size <= 0 or sigma <= 0:
        raise ValueError('invalid heatmap dimensions or sigma')
    result=np.zeros((len(names),heatmap_size,heatmap_size),np.float32)
    valid=np.zeros(len(names),np.float32)
    ys,xs=np.mgrid[:heatmap_size,:heatmap_size].astype(np.float32)
    for idx,name in enumerate(names):
        anchor=anchors.get(name)
        if not anchor or not anchor.get('annotated',False) or not anchor.get('visible',False):
            continue
        px,py=anchor['xy']
        x=(float(px)-crop.origin_x)/crop.px_per_patch_x
        y=(float(py)-crop.origin_y)/crop.px_per_patch_y
        if not (0<=x<patch_size and 0<=y<patch_size):
            continue
        # Center of each heatmap cell corresponds to (i+.5)*stride in patch.
        hx=x*heatmap_size/patch_size-.5
        hy=y*heatmap_size/patch_size-.5
        result[idx]=np.exp(-((xs-hx)**2+(ys-hy)**2)/(2*sigma**2))
        valid[idx]=1
    return torch.from_numpy(result),torch.from_numpy(valid)


def precision_example(page: np.ndarray, instance: dict, names: tuple[str, ...],
                      *, patch_size: int = 160, heatmap_size: int = 40):
    patch,transform=crop_for_refiner(page,tuple(instance['bbox_xyxy']),size=patch_size)
    target,valid=anchor_targets(instance.get('anchors',{}),names,transform,
                                patch_size=patch_size,heatmap_size=heatmap_size)
    if patch.ndim == 2:
        rgb=np.repeat(patch[:,:,None],3,axis=2)
    else:
        # Image is supplied in OpenCV BGR order.
        rgb=patch[:,:,::-1]
    tensor=torch.from_numpy(np.ascontiguousarray(rgb.transpose(2,0,1))).float()/255
    return tensor,target,valid,transform


def precision_loss(logits: torch.Tensor, target: torch.Tensor,
                   valid: torch.Tensor, *, foreground_mask: torch.Tensor | None = None) -> torch.Tensor:
    """Per-anchor heatmap BCE, masked for undefined anchors.

    The optional last output channel is a symbol foreground mask. Do not train
    it against made-up all-zero backgrounds when masks are unavailable.
    """
    if logits.ndim != 4 or target.shape != logits.shape[:1]+(logits.shape[1]-1,)+logits.shape[2:]:
        raise ValueError('logits/target shape mismatch')
    if valid.shape != logits.shape[:1]+(logits.shape[1]-1,):
        raise ValueError('valid shape mismatch')
    if not (valid>=0).all() or not (valid<=1).all():
        raise ValueError('valid must be 0..1')
    keypoint_loss=F.binary_cross_entropy_with_logits(logits[:,:-1],target,reduction='none')
    # Gaussian foreground cells have much stronger weight than blank cells.
    weights=1+15*target
    weighted=keypoint_loss*weights*valid[:,:,None,None]
    denom=valid.sum().clamp(min=1)*logits.shape[-1]*logits.shape[-2]
    loss=weighted.sum()/denom
    if foreground_mask is not None:
        if foreground_mask.shape != logits[:, -1].shape:
            raise ValueError('foreground_mask shape mismatch')
        loss=loss+F.binary_cross_entropy_with_logits(logits[:,-1],foreground_mask)
    return loss


def jittered_precision_example(page: np.ndarray, instance: dict, names: tuple[str, ...],
                               *, seed: int, translation_fraction: float=.15,
                               log_scale_std: float=.12,
                               patch_size: int=160, heatmap_size: int=40):
    """Train refiner on imperfect detector boxes, not exclusively oracle boxes.

    Perturb the conditioning crop; recompute targets from the SAME exact
    source-coordinate anchors. The source anchor coordinates never drift.
    """
    if translation_fraction < 0 or log_scale_std < 0:
        raise ValueError('jitter parameters must be nonnegative')
    rng=np.random.default_rng(seed)
    x0,y0,x1,y1=map(float,instance['bbox_xyxy'])
    w,h=x1-x0,y1-y0
    scale=float(np.exp(rng.normal(0,log_scale_std)))
    dx=float(rng.uniform(-translation_fraction,translation_fraction))*w
    dy=float(rng.uniform(-translation_fraction,translation_fraction))*h
    cx=(x0+x1)/2+dx;cy=(y0+y1)/2+dy
    noisy=instance.copy()
    noisy['bbox_xyxy']=[cx-w*scale/2,cy-h*scale/2,cx+w*scale/2,cy+h*scale/2]
    return precision_example(page,noisy,names,patch_size=patch_size,heatmap_size=heatmap_size)
