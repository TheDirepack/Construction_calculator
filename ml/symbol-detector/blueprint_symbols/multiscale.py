"""Resolution-independent source-space tile planner for fixed-shape NPU models.

Source images can have *any* dimensions/aspect ratio. 'source_scale' is the
number of original image pixels per model pixel, NOT a DPI or page-size preset.
The model always receives the same input shape; page coordinates retain floats.

This module only plans and optionally samples tiles. For very large pages, a
PDF/region decoder should render/read each source rectangle instead of loading
all pages into memory.
"""
from __future__ import annotations
from dataclasses import dataclass
from typing import Iterable
import math
import cv2
import numpy as np


@dataclass(frozen=True)
class ScaleTile:
    x: float
    y: float
    source_scale: float
    size: int
    image_width: int
    image_height: int

    @property
    def source_box(self) -> tuple[float, float, float, float]:
        span = self.size * self.source_scale
        return (self.x, self.y, min(self.x + span, self.image_width),
                min(self.y + span, self.image_height))

    def to_page_point(self, model_x: float, model_y: float) -> tuple[float, float]:
        # Coordinates are continuous pixel *edges*; centers are at x + 0.5.
        return (self.x + model_x * self.source_scale,
                self.y + model_y * self.source_scale)

    def to_page_box(self, box: tuple[float, float, float, float]) -> tuple[float, float, float, float]:
        a = self.to_page_point(box[0], box[1])
        b = self.to_page_point(box[2], box[3])
        return (a[0], a[1], b[0], b[1])


def _origins(length: int, span: float, step: float) -> list[float]:
    if span >= length:
        return [0.0]
    n = int(math.ceil((length - span) / step))
    # Last tile flush with far edge; previous tiles evenly spaced and overlap.
    return [float(min(i * step, length - span)) for i in range(n)] + [float(length - span)]


def plan_pyramid_tiles(width: int, height: int, *, tile_size: int = 512,
                       overlap: int = 96, source_scales: Iterable[float] = (1.0,)) -> list[ScaleTile]:
    """Enumerate complete source coverage at each requested physical glyph scale.

    0.5 => upscale source: useful for tiny glyphs (adds no lost detail).
    1.0 => source-native tiles; 2.0 => cover twice the original span per tile.
    No source-wide resize or resolution-dependent class labels.
    """
    if width <= 0 or height <= 0 or tile_size <= 0 or not 0 <= overlap < tile_size:
        raise ValueError('invalid page/tile sizes or overlap')
    scales = tuple(float(s) for s in source_scales)
    if not scales or any(not math.isfinite(s) or s <= 0 for s in scales):
        raise ValueError('source_scales must be positive finite numbers')
    if len(set(scales)) != len(scales):
        raise ValueError('duplicate source_scales waste inference')
    tiles: list[ScaleTile] = []
    for scale in scales:
        span = tile_size * scale
        step = (tile_size - overlap) * scale
        for y in _origins(height, span, step):
            for x in _origins(width, span, step):
                tiles.append(ScaleTile(x, y, scale, tile_size, width, height))
    return tiles


def sample_tile(image: np.ndarray, tile: ScaleTile, *, padding: int = 255) -> np.ndarray:
    """Sampling helper for in-memory arrays; production should use ROI readers.

    Uses pixel-edge coordinates: input pixel center (j+.5) corresponds to
    source edge x + (j+.5)*source_scale. Missing data pads white.
    """
    if image.ndim not in (2, 3) or image.shape[:2] != (tile.image_height, tile.image_width):
        raise ValueError('source image dimensions do not match tile plan')
    s = tile.source_scale
    affine = np.array([[s, 0, tile.x + .5 * s - .5],
                       [0, s, tile.y + .5 * s - .5]], dtype=np.float64)
    return cv2.warpAffine(image, affine, (tile.size, tile.size),
                          flags=cv2.INTER_LINEAR | cv2.WARP_INVERSE_MAP,
                          borderMode=cv2.BORDER_CONSTANT, borderValue=(padding,)*3)
