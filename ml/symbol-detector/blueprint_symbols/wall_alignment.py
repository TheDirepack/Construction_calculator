"""Shared raster-wall alignment for NECA and procedural device compositors.

Hough segments provide local wall tangents, including diagonal/rotated plans.
Positions and contacts are estimated from pixels, NOT original CAD vectors.
Keep the true glyph ink clear of the wall rather than requiring its rotated
axis-aligned bounding rectangle to be wall-free.
"""
from __future__ import annotations

from dataclasses import dataclass
import math

import cv2
import numpy as np


@dataclass(frozen=True)
class WallSite:
    x: float
    y: float
    nx: float
    ny: float
    """(nx,ny) points from the wall into the chosen open side."""


def wall_rotation_degrees(nx: float, ny: float) -> float:
    """Rotate a glyph whose attachment edge is originally at its bottom.

    OpenCV positive angles rotate counterclockwise in the displayed image.
    The default glyph body points up (0,-1), so (1,0) needs 270 degrees.
    """
    return math.degrees(math.atan2(-nx, -ny)) % 360.0


def detect_wall_sites(
    gray: np.ndarray,
    rng: np.random.Generator,
    max_sites: int = 800,
) -> tuple[list[WallSite], np.ndarray]:
    """Find long local line segments and sample open-side wall sites.

    Hough is not a semantic wall detector: circuit lines and drawing borders
    can also qualify. Subsequent clipping, clutter and proximity gates still
    apply. Returns mask at the input image resolution.
    """
    if gray.ndim != 2 or gray.dtype != np.uint8:
        raise ValueError("expected 8-bit grayscale drawing")
    h, w = gray.shape
    wall_mask = np.zeros((h, w), np.uint8)
    if max_sites <= 0 or min(h, w) < 48:
        return [], wall_mask

    scale = min(1.0, 1600.0 / max(h, w))
    small = (cv2.resize(gray, None, fx=scale, fy=scale,
                        interpolation=cv2.INTER_AREA)
             if scale < 1.0 else gray)
    sh, sw = small.shape
    paper = float(np.percentile(small, 90))
    contrast = paper - float(np.percentile(small, 2))
    threshold = paper - max(7.0, min(36.0, contrast * 0.24))
    ink = np.asarray(small < threshold, np.uint8)
    length_floor = max(24, min(80, int(round(min(sw, sh) * 0.10))))
    raw = cv2.HoughLinesP(
        ink, rho=1, theta=np.pi / 360,
        threshold=max(16, length_floor // 2),
        minLineLength=length_floor,
        maxLineGap=5,
    )
    if raw is None:
        return [], wall_mask

    # OpenCV releases differ: HoughLinesP may return (N,1,4) or (N,4).
    # Normalize both before iterating so a line is always (x1,y1,x2,y2).
    segments = [tuple(map(float, line)) for line in np.asarray(raw).reshape(-1, 4)]
    rng.shuffle(segments)
    # Budget for busy sheets and avoid thousands of near-identical line sites.
    segments = segments[:max(1, min(len(segments), max_sites // 2))]
    sites: list[WallSite] = []

    def density(px: float, py: float, tx: float, ty: float,
                nx: float, ny: float) -> float:
        votes = []
        for step in (7.0, 13.0, 21.0):
            for along in (-9.0, 0.0, 9.0):
                x = int(round(px + nx * step + tx * along))
                y = int(round(py + ny * step + ty * along))
                if 0 <= x < sw and 0 <= y < sh:
                    votes.append(int(ink[y, x]))
        return float(np.mean(votes)) if votes else 1.0

    for x1, y1, x2, y2 in segments:
        dx, dy = x2 - x1, y2 - y1
        length = math.hypot(dx, dy)
        if length < length_floor:
            continue
        tx, ty = dx / length, dy / length
        nx, ny = -ty, tx
        # Draw at original scale to keep all collision tests in one system.
        p1 = (int(round(x1 / scale)), int(round(y1 / scale)))
        p2 = (int(round(x2 / scale)), int(round(y2 / scale)))
        cv2.line(wall_mask, p1, p2, 1,
                 max(2, int(round(2.0 / scale))), cv2.LINE_8)
        count = min(8, max(1, int(round(length / 56.0))))
        for _ in range(count):
            fraction = float(rng.uniform(0.15, 0.85))
            px = x1 + fraction * dx
            py = y1 + fraction * dy
            if not (12 < px < sw - 12 and 12 < py < sh - 12):
                continue
            a = density(px, py, tx, ty, nx, ny)
            b = density(px, py, tx, ty, -nx, -ny)
            # Both sides strongly cluttered: do not invent an open wall side.
            if min(a, b) >= 0.7:
                continue
            if b < a or (abs(a - b) < 0.08 and rng.random() < 0.5):
                nx_side, ny_side = -nx, -ny
            else:
                nx_side, ny_side = nx, ny
            sites.append(WallSite(px / scale, py / scale,
                                  nx_side, ny_side))
    rng.shuffle(sites)
    return sites[:max_sites], wall_mask


def snap_symbol_to_wall(
    alpha: np.ndarray,
    site: WallSite,
    wall_mask: np.ndarray,
    *,
    attachment: tuple[float, float] | None = None,
    margin: int = 3,
    max_shift: int = 7,
) -> tuple[int, int] | None:
    """Find a translated rotated glyph that touches, but does not cross, wall.

    The most wallward *ink* points define the baseline. The existing
    attachment anchor, when available, determines position along that baseline.
    Check actual alpha pixels: diagonal glyphs inevitably have a bounding
    rectangle that intersects the diagonal wall.
    """
    if alpha.ndim != 2 or wall_mask.ndim != 2:
        raise ValueError("expected 2D glyph alpha and wall mask")
    h, w = wall_mask.shape
    gh, gw = alpha.shape
    yy, xx = np.nonzero(alpha > 32)
    if len(xx) < 3 or min(gh, gw) < 2:
        return None
    nx, ny = site.nx, site.ny
    tx, ty = -ny, nx
    projections = xx * nx + yy * ny
    back = float(projections.min())
    edge = projections < back + 2.0
    if attachment is None:
        along = float(np.median(xx[edge] * tx + yy[edge] * ty))
    else:
        along = float(attachment[0] * tx + attachment[1] * ty)
    anchor_x = nx * back + tx * along
    anchor_y = ny * back + ty * along
    for shift in range(max_shift + 1):
        # Keep 1–2 pixels between independently rasterized wall and device
        # strokes. This is still a wall-associated, not free-space, placement.
        x = int(round(site.x - anchor_x + nx * (2.0 + shift)))
        y = int(round(site.y - anchor_y + ny * (2.0 + shift)))
        if (x < margin or y < margin or x + gw >= w - margin
                or y + gh >= h - margin):
            continue
        if np.any(wall_mask[y + yy, x + xx]):
            continue
        return x, y
    return None
