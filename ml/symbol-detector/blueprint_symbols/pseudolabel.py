"""Candidate labels from a fine-tuned teacher; never auto-promote to ground truth.

Keep teachers fully separate from final held-out evaluation groups. Repeated
predictions across scales are *supporting evidence*, not independent truth.
"""
from __future__ import annotations
import hashlib
from pathlib import Path


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


def _containment(small,big):
    """Fraction of ``small``'s area inside ``big``."""
    x0=max(small[0],big[0]);y0=max(small[1],big[1]);x1=min(small[2],big[2]);y1=min(small[3],big[3])
    inter=max(0.,x1-x0)*max(0.,y1-y0)
    return inter/max(1e-8,(small[2]-small[0])*(small[3]-small[1]))


def _suppress_across(output:list[dict], *, cross_class_iou:float, containment:float) -> list[dict]:
    """Greedy class-agnostic pass over merged candidates (highest confidence first).

    * Two device classes on the same spot (IoU >= ``cross_class_iou``): one
      symbol read as ``switch.single_pole`` and ``switch.three_way``. The
      stronger is kept, the other is recorded in ``alternative_labels``.
    * A box mostly inside a stronger box of the same class (``containment``
      of its area): a partial detection of a symbol cut by a tile edge.
    """
    kept:list[dict]=[]
    for cand in sorted(output,key=lambda x:float(x['confidence']),reverse=True):
        absorbed=False
        for k in kept:
            same=k['symbol_family']==cand['symbol_family']
            overlap=_iou(cand['bbox_xyxy'],k['bbox_xyxy'])
            if (not same and overlap>=cross_class_iou) or (
                    same and _containment(cand['bbox_xyxy'],k['bbox_xyxy'])>=containment):
                if not same:
                    alts=k.setdefault('alternative_labels',[])
                    if all(a['symbol_family']!=cand['symbol_family'] for a in alts):
                        alts.append({'symbol_family':cand['symbol_family'],
                                     'confidence':float(cand['confidence'])})
                k['supporting_detections']+=0 if not same else cand['supporting_detections']
                absorbed=True
                break
        if not absorbed:
            kept.append(cand)
    return kept


def consolidate_predictions(candidates:list[dict], *, min_confidence:float = .2,
                            match_iou:float=.35, cross_class_iou:float | None=.6,
                            containment:float | None=.8) -> list[dict]:
    """Merge per-tile and per-scale detections by class and geometry, then
    suppress across classes and partial (tile-edge) boxes (``_suppress_across``;
    pass None to disable either).

    Confidence is the *maximum teacher prediction*; not calibrated likelihood.
    Results are review candidates, even with multi-scale agreement.

    For tiled page inference (deployment and real-page evaluation) use
    ``page_detector.merge_tile_detections`` instead: it counts distinct
    agreeing tiles, reports real multi-scale agreement and sets the review
    flags from disagreement. This function stays for teacher pseudo-labels,
    which are always review candidates.
    """
    if not 0 <= min_confidence <= 1 or not 0 < match_iou <= 1:
        raise ValueError('bad confidence or IOU threshold')
    if cross_class_iou is not None and not 0 < cross_class_iou <= 1:
        raise ValueError('bad cross-class IOU threshold')
    if containment is not None and not 0 < containment <= 1:
        raise ValueError('bad containment threshold')
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
    if cross_class_iou is None and containment is None:
        return output
    return _suppress_across(output,cross_class_iou=cross_class_iou if cross_class_iou is not None else 2.,
                            containment=containment if containment is not None else 2.)
