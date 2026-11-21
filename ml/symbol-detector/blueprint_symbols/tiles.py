"""Coordinate-preserving, overlapping raster page tiling."""
from __future__ import annotations
from dataclasses import dataclass
import math


@dataclass(frozen=True)
class Tile:
    x: int
    y: int
    width: int
    height: int

    def to_page_point(self, x: float, y: float) -> tuple[float, float]:
        return (self.x + x, self.y + y)

    def to_page_box(self, box: tuple[float, float, float, float]) -> tuple[float, float, float, float]:
        return (box[0] + self.x, box[1] + self.y, box[2] + self.x, box[3] + self.y)


def _origins(length: int, tile_size: int, overlap: int) -> list[int]:
    if length <= 0 or tile_size <= 0 or not (0 <= overlap < tile_size):
        raise ValueError('Require positive dimensions and 0 <= overlap < tile_size')
    if length <= tile_size:
        return [0]
    stride = tile_size - overlap
    origins = list(range(0, length - tile_size + 1, stride))
    last = length - tile_size
    if origins[-1] != last:
        origins.append(last)
    return origins


def tile_page(width: int, height: int, tile_size: int = 512, overlap: int = 96) -> list[Tile]:
    """Return full-coverage tiles; do not create a page-sized bitmap."""
    return [Tile(x, y, min(tile_size, width - x), min(tile_size, height - y))
            for y in _origins(height, tile_size, overlap)
            for x in _origins(width, tile_size, overlap)]


def page_px_to_pdf_points(x: float, y: float, dpi: float) -> tuple[float, float]:
    if dpi <= 0:
        raise ValueError('dpi must be > 0')
    return (x * 72.0 / dpi, y * 72.0 / dpi)
