"""Class-aware consolidation of competing SAM NECA exemplar proposals.

Confidence across individual NECA prompts is not calibrated class probability;
we retain alternative interpretations for VLM/human review instead of treating
the highest-scoring prompt as verified ground truth.
"""
from __future__ import annotations

from math import isfinite


def _iou(a: list[float], b: list[float]) -> float:
    w = max(0., min(a[2], b[2]) - max(a[0], b[0]))
    h = max(0., min(a[3], b[3]) - max(a[1], b[1]))
    x = w * h
    aa = max(0., (a[2]-a[0]) * (a[3]-a[1]))
    bb = max(0., (b[2]-b[0]) * (b[3]-b[1]))
    return x / (aa + bb - x) if aa + bb > x else 0.





def _same_physical_symbol(a: list[float], b: list[float], iou_threshold: float) -> bool:
    """Recognize variable-size boxes enclosing the same device.

    A small circle-box and a larger circle+letter box can have IoU below
    conventional NMS even with identical centers. Requiring BOTH strong
    containment and near-identical centers avoids merging adjacent devices.
    """
    overlap = _iou(a, b)
    if overlap >= iou_threshold:
        return True
    area_a = (a[2] - a[0]) * (a[3] - a[1])
    area_b = (b[2] - b[0]) * (b[3] - b[1])
    inter = overlap * (area_a + area_b) / (1.0 + overlap) if overlap else 0.
    containment = inter / max(1e-6, min(area_a, area_b))
    if containment < .80:
        return False
    cx_a, cy_a = (a[0] + a[2]) * .5, (a[1] + a[3]) * .5
    cx_b, cy_b = (b[0] + b[2]) * .5, (b[1] + b[3]) * .5
    width = min(a[2] - a[0], b[2] - b[0])
    height = min(a[3] - a[1], b[3] - b[1])
    return abs(cx_a - cx_b) <= .25 * width and abs(cy_a - cy_b) <= .25 * height


def merge_sam_proposals(detections: list[dict], *, iou_threshold: float = .50,
                        max_alternatives: int = 6) -> list[dict]:
    """Merge duplicate overlapping proposals, preserving competing labels.

    No confidence boost from prompt agreement: multiple NECA variants are
    correlated queries, not independent detectors. High overlap still requires
    visual confirmation; merged outputs remain provisional.
    """
    if not 0 < iou_threshold <= 1 or max_alternatives < 1:
        raise ValueError('invalid consolidation parameters')
    # A coarse exemplar establishes only a FAMILY. If a colocated fine
    # variant passes the gating/score checks, it outranks the coarse generic
    # label irrespective of the raw cross-prompt score (not calibrated).
    sorted_dets = sorted(
        detections, key=lambda d: (d.get('stage') != 'fine', -float(d['score']))
    )
    selected: list[dict] = []
    for d in sorted_dets:
        box = d.get('box_px')
        if (not isinstance(box, (list, tuple)) or len(box) != 4 or
                not all(isfinite(float(v)) for v in box) or
                not (box[0] < box[2] and box[1] < box[3])):
            raise ValueError('invalid SAM box')
        best = None
        best_iou = iou_threshold
        for cluster in selected:
            ov = _iou(box, cluster['box_px'])
            if _same_physical_symbol(box, cluster['box_px'], iou_threshold):
                # Prefer the closest overlapping geometry; also merge a
                # contained prediction if standard IoU alone is too low.
                if best is None or ov >= best_iou:
                    best, best_iou = cluster, ov
        if best is None:
            primary = dict(d)
            primary['same_region_prompt_hits'] = 1
            primary['alternative_labels'] = []
            primary['_label_scores'] = {(d['device_type'], d.get('subtype')): float(d['score'])}
            selected.append(primary)
            continue
        best['same_region_prompt_hits'] += 1
        key = (d['device_type'], d.get('subtype'))
        # Do not report a generic family proposal as a competing subtype
        # when a fine result identifies the same family in the same region.
        coarse_same_family = (
            d.get('stage') == 'coarse'
            and d['device_type'] == best['device_type']
            and best.get('stage') == 'fine'
        )
        if key not in best['_label_scores'] and not coarse_same_family:
            best['_label_scores'][key] = float(d['score'])
            best['alternative_labels'].append({
                'device_type': d['device_type'], 'subtype': d.get('subtype'),
                'category': d.get('category'), 'subcategory': d.get('subcategory'),
                'score': float(d['score']), 'neca_symbol_id': d.get('symbol_id'),
                'neca_name': d.get('neca_name'),
            })
    for cluster in selected:
        cluster.pop('_label_scores')
        cluster['alternative_labels'] = cluster['alternative_labels'][:max_alternatives]
        cluster['needs_class_review'] = bool(cluster['alternative_labels']) or cluster.get('stage') in ('coarse', 'visual_base')
        cluster['family_only'] = cluster.get('stage') in ('coarse', 'visual_base')
        cluster['verified_ground_truth'] = False
    return selected
