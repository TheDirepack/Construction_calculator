"""Cheap, deterministic quality gates for SAM blueprint-symbol proposals.

Self-hosted pipeline: reject blank text-box predictions, long routing lines,
tile-edge duplicates and generic family-only predictions before VLM review.
Thresholds are deliberately conservative and require calibration on real
confirmed *positive and negative* crops; this is not a learned classifier.
"""
from __future__ import annotations

from collections import Counter
from math import isfinite
from typing import Any

import numpy as np


def owns_tile_center(box: list[float], *, tile_x: int, tile_y: int,
                     tile_size: int, overlap: int,
                     image_width: int, image_height: int) -> bool:
    """Assign each detection center to a unique tile in the overlap grid.

    Adjacent tile cores meet in the middle of their overlap. Border tiles
    retain the actual image boundaries, including partial padded tiles.
    This suppresses duplicated inference proposals before global NMS.
    """
    if tile_size <= overlap or overlap < 0:
        raise ValueError('invalid tile overlap')
    cx = .5 * (box[0] + box[2])
    cy = .5 * (box[1] + box[3])
    half = overlap / 2.0
    min_x = tile_x + (half if tile_x else 0.0)
    min_y = tile_y + (half if tile_y else 0.0)
    max_x = min(image_width, tile_x + tile_size -
                (half if tile_x + tile_size < image_width else 0.0))
    max_y = min(image_height, tile_y + tile_size -
                (half if tile_y + tile_size < image_height else 0.0))
    return min_x <= cx < max_x and min_y <= cy < max_y


def evaluate_symbol_box(
    image_rgb: np.ndarray,
    box: list[float],
    *,
    max_aspect_ratio: float = 6.0,
    min_ink_fraction: float = 0.018,
    max_ink_fraction: float = 0.85,
    ink_threshold: int = 195,
    minimum_box_size: float = 10.0,
) -> str | None:
    """Reason for rejection, or None for plausible local drawing ink.

    The ink check cannot recognize semantics; a high-contrast word might
    survive. It is intended to eliminate cheap false positives before a VLM.
    """
    if not isinstance(box, (list, tuple)) or len(box) != 4:
        return 'invalid_box'
    try:
        coords = [float(x) for x in box]
    except (ValueError, TypeError):
        return 'invalid_box'
    if not all(isfinite(x) for x in coords):
        return 'invalid_box'
    x0, y0, x1, y1 = coords
    width, height = x1 - x0, y1 - y0
    if width < minimum_box_size or height < minimum_box_size:
        return 'too_small'
    if max(width, height) / min(width, height) > max_aspect_ratio:
        return 'elongated_line'
    h, w = image_rgb.shape[:2]
    ix0, iy0 = max(0, int(x0)), max(0, int(y0))
    ix1, iy1 = min(w, int(np.ceil(x1))), min(h, int(np.ceil(y1)))
    if ix1 <= ix0 or iy1 <= iy0:
        return 'out_of_page'
    crop = image_rgb[iy0:iy1, ix0:ix1]
    if crop.ndim == 3:
        gray = crop[..., :3].mean(axis=2)
    else:
        gray = crop
    ink = float(np.count_nonzero(gray < ink_threshold)) / gray.size
    if ink < min_ink_fraction:
        return 'blank_region'
    if ink > max_ink_fraction:
        return 'filled_region'
    return None


def filter_sam_detections(
    detections: list[dict[str, Any]],
    image_rgb: np.ndarray,
    *,
    tile_x: int,
    tile_y: int,
    tile_size: int,
    overlap: int,
    use_owner: bool = True,
    max_aspect_ratio: float = 6.0,
    min_ink_fraction: float = 0.018,
    max_ink_fraction: float = 0.85,
    minimum_box_size: float = 10.0,
    max_per_family: int = 0,
    max_total: int = 0,
    stats: Counter | None = None,
) -> list[dict[str, Any]]:
    """Pure postprocessing; no GPU/network dependence."""
    h, w = image_rgb.shape[:2]
    accepted = []
    by_family = Counter()
    for d in sorted(detections, key=lambda x: -float(x['score'])):
        box = d['box_px']
        if use_owner and not owns_tile_center(
            box, tile_x=tile_x, tile_y=tile_y, tile_size=tile_size,
            overlap=overlap, image_width=w, image_height=h,
        ):
            reason = 'tile_overlap_duplicate'
        else:
            reason = evaluate_symbol_box(
                image_rgb, box, max_aspect_ratio=max_aspect_ratio,
                min_ink_fraction=min_ink_fraction,
                max_ink_fraction=max_ink_fraction,
                minimum_box_size=minimum_box_size,
            )
        family = str(d.get('device_type', 'unknown'))
        if reason is None and max_per_family and by_family[family] >= max_per_family:
            reason = 'family_hit_cap'
        if reason is None and max_total and len(accepted) >= max_total:
            reason = 'total_hit_cap'
        if reason:
            if stats is not None:
                stats['filtered_' + reason] += 1
            continue
        by_family[family] += 1
        accepted.append(d)
    return accepted
