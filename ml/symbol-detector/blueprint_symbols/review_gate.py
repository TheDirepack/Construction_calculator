"""Fail-closed validation of VLM-reviewed blueprint symbol proposals.

Proposals are never ground truth. This gate only permits explicit, complete
reviews to enter the *provisional* refined-device export.
"""
from __future__ import annotations

import math
from collections.abc import Mapping
from typing import Any
from blueprint_symbols.electrical_device_attributes import validate_device_attributes


def _numbers(value: Any, size: int, label: str) -> list[float]:
    if not isinstance(value, (list, tuple)) or len(value) != size:
        raise ValueError(f'{label} must be a {size}-element list')
    try:
        result = [float(n) for n in value]
    except (ValueError, TypeError) as exc:
        raise ValueError(f'{label} must be numeric') from exc
    if not all(math.isfinite(n) for n in result):
        raise ValueError(f'{label} must contain only finite numbers')
    return result


def review_decision(item: dict, taxonomy: Mapping[str, list[str]], *, coord_max: float = 1000.) -> str:
    """Return accepted/rejected/pending; reject malformed explicit acceptance.

    Missing status, legacy keep=True, or uncertain decisions never pass.
    """
    status = item.get('review_status', 'unreviewed')
    if status not in {'accepted', 'rejected', 'uncertain', 'unreviewed'}:
        raise ValueError(f'Unknown review_status: {status!r}')
    keep = item.get('keep')
    if status == 'accepted' and keep is not True:
        raise ValueError('Accepted device must explicitly set keep=true')
    if status == 'rejected':
        if keep is True:
            raise ValueError('Rejected device cannot set keep=true')
        return 'rejected'
    if status != 'accepted':
        return 'pending'

    dtype = item.get('refined_device_type')
    subtype = item.get('refined_subtype')
    if dtype not in taxonomy or subtype not in taxonomy[dtype]:
        raise ValueError(f'Unsupported accepted device type/subtype: {dtype!r}/{subtype!r}')
    box = _numbers(item.get('refined_bbox_cell'), 4, 'refined_bbox_cell')
    if (not all(0 <= n <= coord_max for n in box) or box[0] >= box[2] or box[1] >= box[3]):
        raise ValueError('refined_bbox_cell is out of bounds or inverted')
    crop = _numbers(item.get('crop_pdf_rect_bl'), 4, 'crop_pdf_rect_bl')
    if crop[0] >= crop[2] or crop[1] >= crop[3]:
        raise ValueError('crop_pdf_rect_bl is inverted')
    if type(item.get('on_wall')) is not bool:
        raise ValueError('Accepted device must explicitly set on_wall true/false')
    if item['on_wall']:
        point = _numbers(item.get('wall_contact_cell'), 2, 'wall_contact_cell')
        if not all(0 <= n <= coord_max for n in point):
            raise ValueError('wall_contact_cell is out of bounds')
    validate_device_attributes(dtype, item.get('attributes'))
    if not isinstance(item.get('pdf_page'), int) or item['pdf_page'] < 1:
        raise ValueError('pdf_page must be positive 1-based integer')
    if not isinstance(item.get('device_index'), int) or item['device_index'] < 0:
        raise ValueError('device_index must be nonnegative integer')
    return 'accepted'
