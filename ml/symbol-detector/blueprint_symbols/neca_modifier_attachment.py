"""Attach separately detected symbol-mark candidates without extra device boxes.

This handles overlapping/nearby modifiers from a local template detector,
SAM component prompts, or OCR. A three-way switch can simultaneously be a
dimmer and a low-voltage switch: independent attributes are compositional.
Ambiguous attribution never becomes a trusted device attribute.
"""
from __future__ import annotations
from math import hypot

_LIST_FIELDS = {'control_features', 'protective_features', 'sensor_types',
                'notification_features', 'outlet_services'}


def _position_cost(base: list[float], mark: list[float]) -> float | None:
    bx0, by0, bx1, by1 = map(float, base)
    mx0, my0, mx1, my1 = map(float, mark)
    width, height = bx1-bx0, by1-by0
    if min(width, height, mx1-mx0, my1-my0) <= 0:
        return None
    cx, cy = (mx0+mx1)/2, (my0+my1)/2
    # NECA small switch suffixes appear right/below the S or dollar base.
    # Avoid arbitrary distant letter labels and adjacent device marks.
    if not (bx0-.35*width <= cx <= bx1+2*width
            and by0-.35*height <= cy <= by1+1.2*height):
        return None
    bcx, bcy = (bx0+bx1)/2, (by0+by1)/2
    return hypot((cx-bcx)/max(width, 4), (cy-bcy)/max(height, 4))


def attach_device_modifiers(
    base_devices: list[dict], modifiers: list[dict],
    *, ambiguity_margin: float = .20,
) -> tuple[list[dict], list[dict]]:
    """Return new device dicts + unmatched marks, never create new device boxes.

    Modifier fields: box_px, target_family, attribute, value, score, source.
    Evidence remains *provisional*, never ground truth.
    """
    if not 0 <= ambiguity_margin <= 1:
        raise ValueError('invalid attachment ambiguity margin')
    result = [
        {**d, 'analytic_attributes': dict(d.get('analytic_attributes') or {}),
         'modifier_evidence': list(d.get('modifier_evidence') or [])}
        for d in base_devices
    ]
    unmatched = []
    for mark in sorted(modifiers, key=lambda x: -float(x.get('score', 0))):
        fam, attr, val = (mark.get('target_family'), mark.get('attribute'),
                          mark.get('value'))
        if not all(isinstance(x, str) and x for x in (fam, attr, val)):
            unmatched.append({**mark, 'reason': 'invalid_modifier'})
            continue
        candidates = []
        for idx, base in enumerate(result):
            if base.get('device_type') != fam:
                continue
            cost = _position_cost(base['box_px'], mark['box_px'])
            if cost is not None:
                candidates.append((cost, idx))
        candidates.sort()
        if not candidates:
            unmatched.append({**mark, 'reason': 'no_compatible_base'})
            continue
        if len(candidates) > 1 and candidates[1][0] - candidates[0][0] < ambiguity_margin:
            unmatched.append({**mark, 'reason': 'ambiguous_base'})
            continue
        target = result[candidates[0][1]]
        attrs = target['analytic_attributes']
        current = attrs.get(attr)
        if attr in _LIST_FIELDS:
            items = list(current) if isinstance(current, list) else ([] if current is None else [current])
            if val not in items:
                items.append(val)
            attrs[attr] = items
        elif current is None or current == val:
            attrs[attr] = val
        else:
            target.setdefault('modifier_conflicts', []).append({
                'attribute': attr, 'existing': current, 'proposed': val,
            })
            unmatched.append({**mark, 'reason': 'conflicting_exclusive_modifier'})
            continue
        target['modifier_evidence'].append({
            'attribute': attr, 'value': val,
            'score': float(mark.get('score', 0)),
            'box_px': mark['box_px'],
            'source': mark.get('source', 'visual_modifier'),
        })
    return result, unmatched
