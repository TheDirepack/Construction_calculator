"""First-pass classical geometry: isolated line stroke, not a wall interpreter."""
from __future__ import annotations
from dataclasses import dataclass
from typing import Optional
import cv2
import numpy as np


@dataclass(frozen=True)
class FittedLine:
    start: tuple[float, float]
    end: tuple[float, float]
    rms_width_px: float
    anisotropy: float


def fit_isolated_stroke_centerline(gray: np.ndarray, *, min_pixels: int = 20) -> Optional[FittedLine]:
    """Fit centerline to a *single isolated* dark straight stroke in a crop.

    The pixel centroid and first principal component provide a subpixel
    total-least-squares line. Does NOT resolve crosses, text, parallel
    walls, curves or symbol-wire overlaps. Those require segmentation.
    """
    if gray.ndim == 3:
        gray = cv2.cvtColor(gray, cv2.COLOR_BGR2GRAY)
    if gray.ndim != 2 or gray.dtype != np.uint8:
        raise ValueError('expected uint8 grayscale or BGR crop')
    blurred = cv2.GaussianBlur(gray, (3, 3), 0)
    _, foreground = cv2.threshold(blurred, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    yy, xx = np.nonzero(foreground)
    if len(xx) < min_pixels:
        return None
    points = np.column_stack([xx, yy]).astype(np.float64)
    centroid = points.mean(axis=0)
    centered = points - centroid
    cov = (centered.T @ centered) / len(points)
    eigenvalues, eigenvectors = np.linalg.eigh(cov)
    if eigenvalues[-1] <= 0:
        return None
    anisotropy = float(eigenvalues[-1] / max(eigenvalues[0], 1e-9))
    if anisotropy < 5.0:
        return None
    direction = eigenvectors[:, -1]
    if direction[0] < 0 or (abs(direction[0]) < 1e-9 and direction[1] < 0):
        direction = -direction
    projections = centered @ direction
    t0, t1 = np.percentile(projections, [1.0, 99.0])
    start = centroid + t0 * direction
    end = centroid + t1 * direction
    return FittedLine(tuple(map(float, start)), tuple(map(float, end)),
                      float(np.sqrt(eigenvalues[0]) * 2), anisotropy)


def closest_point_on_segment(point: tuple[float, float],
                             a: tuple[float, float],
                             b: tuple[float, float]) -> tuple[float, float]:
    p, x, y = map(lambda v: np.asarray(v, dtype=np.float64), (point, a, b))
    segment = y - x
    d2 = float(segment @ segment)
    if d2 == 0:
        return tuple(map(float, x))
    t = max(0.0, min(1.0, float((p-x) @ segment)/d2))
    return tuple(map(float, x + t*segment))


def heatmap_peak(heatmap: np.ndarray, input_width: int, input_height: int) -> tuple[float, float]:
    """Decode at stride-4 with local quadratic subpixel peak correction.

    The heatmap must be a single keypoint map; this is NOT a confidence estimator.
    """
    if heatmap.ndim != 2 or not heatmap.size:
        raise ValueError('expected 2D heatmap')
    h, w = heatmap.shape
    iy, ix = np.unravel_index(np.argmax(heatmap), heatmap.shape)
    xf, yf = float(ix), float(iy)
    for axis in (0, 1):
        idx = ix if axis == 0 else iy
        lim = w if axis == 0 else h
        if 0 < idx < lim - 1:
            if axis == 0:
                a, b, c = float(heatmap[iy, ix-1]), float(heatmap[iy, ix]), float(heatmap[iy, ix+1])
            else:
                a, b, c = float(heatmap[iy-1, ix]), float(heatmap[iy, ix]), float(heatmap[iy+1, ix])
            denom = a - 2*b + c
            delta = max(-0.5, min(0.5, 0.5*(a-c)/denom)) if denom < -1e-12 else 0.0
            if axis == 0:
                xf += delta
            else:
                yf += delta
    return ((xf + 0.5) * input_width / w, (yf + 0.5) * input_height / h)
