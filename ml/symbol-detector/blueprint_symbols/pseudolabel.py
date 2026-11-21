"""Candidate labels from a fine-tuned teacher; never auto-promote to ground truth.

Keep teachers fully separate from final held-out evaluation groups. Repeated
predictions across scales are *supporting evidence*, not independent truth.
"""
from __future__ import annotations
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import cv2


def image_sha256(path: Path) -> str:
    h=hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda:f.read(1<<20),b''):
            h.update(chunk)
    return h.hexdigest()


def verify_unlabeled_sources(items:list[dict], manifest:dict) -> list[dict]:
    """Require original-project grouping and exclude evaluation groups/images.

    Does not guarantee against resized/modified near-duplicate pages: these
    require provenance-based grouping and a separate perceptual-duplicate audit.
    """
    excluded={g for split in ('val','test') for g in manifest.get('split_groups',{}).get(split,[])}
    holdout_hashes={x['image_sha256'] for x in manifest.get('source_images',[])
                     if x['project_group'] in excluded}
    ready=[]; seen=set()
    for item in items:
        group=item.get('project_family_id')
        if not isinstance(group,str) or not group.strip():
            raise ValueError('unlabeled image missing verified project_family_id')
        if not item.get('source_group_verified',False):
            raise ValueError(f'unverified project group for {item.get("image_path")}')
        if group in excluded:
            raise ValueError(f'unlabeled image shares evaluation project group {group!r}')
        file=Path(item['image_path']).expanduser().resolve()
        if not file.is_file():
            raise FileNotFoundError(file)
        digest=image_sha256(file)
        if digest in holdout_hashes:
            raise ValueError(f'unlabeled image duplicates held-out evaluation image: {file}')
        if digest in seen:continue
        seen.add(digest)
        ready.append({'image_path':str(file),'project_family_id':group,
                      'image_sha256':digest,'source_group_verified':True})
    return ready


def _iou(a,b):
    x0=max(a[0],b[0]);y0=max(a[1],b[1]);x1=min(a[2],b[2]);y1=min(a[3],b[3])
    inter=max(0.,x1-x0)*max(0.,y1-y0)
    aa=max(1e-8,(a[2]-a[0])*(a[3]-a[1]));bb=max(1e-8,(b[2]-b[0])*(b[3]-b[1]))
    return inter/(aa+bb-inter)


def consolidate_predictions(candidates:list[dict], *, min_confidence:float = .2,
                            match_iou:float=.35) -> list[dict]:
    """Merge per-tile and per-scale detections by class and geometry.

    Confidence is the *maximum teacher prediction*; not calibrated likelihood.
    Results are review candidates, even with multi-scale agreement.
    """
    if not 0 <= min_confidence <= 1 or not 0 < match_iou <= 1:
        raise ValueError('bad confidence or IOU threshold')
    remaining=sorted((x for x in candidates if x['confidence']>=min_confidence),
                     key=lambda x:float(x['confidence']),reverse=True)
    groups=[]
    for cand in remaining:
        box=cand['bbox_xyxy'];class_name=cand['symbol_family']
        if not all(isinstance(v,(int,float)) for v in box) or box[2]<=box[0] or box[3]<=box[1]:
            raise ValueError('bad teacher bbox')
        best=None;best_overlap=0.
        for grp in groups:
            if grp[0]['symbol_family']!=class_name:continue
            overlap=_iou(box,grp[0]['bbox_xyxy'])
            if overlap >= match_iou and overlap > best_overlap:
                best=grp;best_overlap=overlap
        if best is None:groups.append([cand])
        else:best.append(cand)
    output=[]
    for grp in groups:
        scales=sorted({float(x['source_scale']) for x in grp})
        weight=sum(x['confidence'] for x in grp)
        box=[sum(x['bbox_xyxy'][j]*x['confidence'] for x in grp)/weight for j in range(4)]
        confidence=max(float(x['confidence']) for x in grp)
        output.append({'symbol_family':grp[0]['symbol_family'],'bbox_xyxy':box,
                       'confidence':confidence,'supporting_detections':len(grp),
                       'independent_scales':scales,'multiscale_agreement':len(scales)>=2,
                       'uncertain':True,'review_required':True,
                       'source':{'kind':'teacher_pseudo_label','label_method':'model_prediction',
                                 'not_ground_truth':True}})
    return output
