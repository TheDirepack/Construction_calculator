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
