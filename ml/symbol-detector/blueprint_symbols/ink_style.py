"""Sample nearby drafting-line colours instead of painting synthetic ink black.

BGR values are taken from the original drawing (not progressively composited
symbols), and from a ring around the chosen placement. Distant title blocks,
different-colour annotations and isolated local paper should not dominate.
"""
from __future__ import annotations

import cv2
import numpy as np


def local_drawing_ink_bgr(
    image: np.ndarray,
    bbox: list[float] | tuple[float, float, float, float],
    *,
    min_samples: int = 6,
) -> tuple[int, int, int]:
    """Robust BGR line colour near a placement, with full-page fallback.

    Select pixels substantially darker than the neighbourhood's paper, then
    take the median colour of selected line pixels (robust to antialiasing and
    isolated non-drafting marks). Does not assume black-only PDF printing.
    """
    if image.ndim != 3 or image.shape[2] != 3 or image.dtype != np.uint8:
        raise ValueError("expected uint8 BGR drawing")
    if len(bbox) != 4:
        raise ValueError("expected xyxy bounding box")
    h, w = image.shape[:2]
    bx0, by0, bx1, by1 = [int(round(v)) for v in bbox]
    if bx1 <= bx0 or by1 <= by0:
        raise ValueError("empty drawing box")
    padding = max(24, min(160, int(max(bx1 - bx0, by1 - by0) * 2.3)))
    x0, y0 = max(0, bx0 - padding), max(0, by0 - padding)
    x1, y1 = min(w, bx1 + padding), min(h, by1 + padding)

    def read_ink(pixels: np.ndarray) -> tuple[int, int, int] | None:
        if len(pixels) < min_samples:
            return None
        pixels2 = np.ascontiguousarray(pixels.reshape(-1, 1, 3))
        gray = cv2.cvtColor(pixels2, cv2.COLOR_BGR2GRAY).reshape(-1)
        # Background often has a tinted paper colour and scanner noise. An
        # adaptive contrast threshold detects pale grey/blue ink as well.
        paper = float(np.percentile(gray, 85))
        threshold = paper - max(11., min(32., (paper - float(np.percentile(gray, 10))) * .18))
        candidates = pixels[gray < threshold]
        if len(candidates) < min_samples:
            return None
        col = np.median(candidates, axis=0)
        return tuple(int(round(c)) for c in np.clip(col, 0, 255))

    if x1 > x0 and y1 > y0:
        roi = image[y0:y1, x0:x1]
        ring = np.ones(roi.shape[:2], np.bool_)
        ring[max(0, by0-y0):min(y1-y0, by1-y0),
             max(0, bx0-x0):min(x1-x0, bx1-x0)] = False
        choice = read_ink(roi[ring])
        if choice is not None:
            return choice

    global_choice = read_ink(image.reshape(-1, 3))
    return global_choice if global_choice is not None else (35, 35, 35)


def sized_symbol_range(
    size_px_range: tuple[float, float],
    drawing_dpi: float | None,
    image_shape: tuple[int, int],
    *,
    reference_dpi: float = 200.0,
) -> tuple[float, float]:
    """Convert reference pixel-symbol sizes to one common drawing DPI.

    Glyph catalogs generally do not encode reliable real-world dimensions.
    `reference_dpi` is the pixel-size reference; it is **not** a claim that
    the SVGs represent physically dimensioned equipment.
    """
    import math
    lower, upper = size_px_range
    if not all(map(math.isfinite, (lower, upper, reference_dpi))) or not (
        0 < lower <= upper and reference_dpi > 0
    ):
        raise ValueError("invalid symbol size range")
    if drawing_dpi is not None and not (
        math.isfinite(drawing_dpi) and 100 <= drawing_dpi <= 400
    ):
        raise ValueError("drawing_dpi must be in [100,400]")
    factor = (drawing_dpi / reference_dpi) if drawing_dpi is not None else 1.
    # Keep a sizeable gap around devices even at 400dpi; important with up to
    # sixteen independently distorted symbols in a 512x512 crop.
    cap = max(8., min(image_shape) * .38)
    small = max(4., min(lower * factor, cap))
    large = max(small, min(upper * factor, cap))
    return (small, large)
