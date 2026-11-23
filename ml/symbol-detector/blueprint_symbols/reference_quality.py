"""Quality checks for NECA/real-blueprint SAM visual reference glyphs.

A reference containing a legend paragraph, solid table cell, or only a
few accidental pixels can make SAM return many irrelevant bounding boxes.
Conservative preflight rejects such assets before GPU prompt encoding.
"""
from __future__ import annotations

import numpy as np


def reference_quality_reason(
    alpha: np.ndarray,
    *,
    min_ink_fraction: float = 0.012,
    max_ink_fraction: float = 0.84,
    max_components: int = 42,
    threshold: int = 32,
) -> str | None:
    """None = plausible glyph; text-fragment and ink tests are heuristic."""
    if alpha is None or alpha.ndim != 2 or alpha.size == 0:
        return 'invalid_alpha'
    if not (0 <= min_ink_fraction < max_ink_fraction <= 1):
        raise ValueError('invalid reference density thresholds')
    if max_components < 0:
        raise ValueError('max_components must be nonnegative')
    fg = np.ascontiguousarray((alpha >= threshold).astype(np.uint8))
    density = float(np.count_nonzero(fg)) / fg.size
    if density < min_ink_fraction:
        return 'too_sparse'
    if density > max_ink_fraction:
        return 'mostly_solid'
    if max_components:
        import cv2
        components, _, stats, _ = cv2.connectedComponentsWithStats(
            fg, connectivity=8,
        )
        # Ignore single-pixel noise in 96px anti-aliased reference art.
        significant = int(np.count_nonzero(stats[1:, cv2.CC_STAT_AREA] >= 2))
        if significant > max_components:
            return 'fragmented_legend'
    return None


# Only these visually inspected icon families have an enclosing core glyph
# with optional outside notes. Multi-device strips/tracks and assemblies
# should NEVER be cropped to a single disconnected component.
_CORE_CROP_PREFIXES = (
    'receptacle.wall_', 'receptacle.floor_duplex',
    'receptacle.ceiling_', 'receptacle.special_',
    'junction_box.', 'telecom.', 'fire_detector.',
    'security.camera_',
)


def trim_external_reference_annotations(
    alpha: np.ndarray, visual_group: str, *,
    threshold: int = 64,
    minimum_ink_area: int = 10,
    margin_px: int = 2,
) -> tuple[np.ndarray, bool]:
    """Crop exterior schedule/circuit labels, preserving internal glyph letters.

    Uses the largest coherent core's *bounding area*, not just ink area.
    Never use on long/multi-unit assembly silhouettes. Internal letters
    (e.g. S in a fire-detector hexagon) remain inside the enclosing shape.
    """
    if not visual_group.startswith(_CORE_CROP_PREFIXES):
        return alpha, False
    if alpha is None or alpha.ndim != 2 or alpha.size == 0:
        return alpha, False
    import cv2
    fg = np.ascontiguousarray((alpha >= threshold).astype(np.uint8))
    n, _, stats, _ = cv2.connectedComponentsWithStats(fg, 8)
    candidates = [
        stats[i] for i in range(1, n)
        if stats[i, cv2.CC_STAT_AREA] >= minimum_ink_area
        and stats[i, cv2.CC_STAT_WIDTH] >= 5
        and stats[i, cv2.CC_STAT_HEIGHT] >= 5
    ]
    if not candidates:
        return alpha, False
    core = max(candidates, key=lambda st: (
        st[cv2.CC_STAT_WIDTH] * st[cv2.CC_STAT_HEIGHT],
        st[cv2.CC_STAT_AREA],
    ))
    x0 = max(0, int(core[cv2.CC_STAT_LEFT]) - margin_px)
    y0 = max(0, int(core[cv2.CC_STAT_TOP]) - margin_px)
    x1 = min(alpha.shape[1], int(core[cv2.CC_STAT_LEFT] +
                                  core[cv2.CC_STAT_WIDTH]) + margin_px)
    y1 = min(alpha.shape[0], int(core[cv2.CC_STAT_TOP] +
                                  core[cv2.CC_STAT_HEIGHT]) + margin_px)
    proposed = alpha[y0:y1, x0:x1]
    # Avoid accidental crop to a single letter in a disconnected device.
    if (proposed.size < .18 * alpha.size
            or np.count_nonzero(proposed > threshold) < .45 *
               np.count_nonzero(fg)):
        return alpha, False
    if proposed.shape == alpha.shape:
        return alpha, False
    return np.ascontiguousarray(proposed), True
