"""Coordinate-preserving crop of an original raster for local anchor inference."""
from __future__ import annotations
from dataclasses import dataclass
import cv2
import numpy as np


@dataclass(frozen=True)
class CropTransform:
    """Affine transform from model patch coords to full-page pixel coordinates."""
    origin_x: float
    origin_y: float
    px_per_patch_x: float
    px_per_patch_y: float

    def to_page(self, x: float, y: float) -> tuple[float, float]:
        return (self.origin_x + x*self.px_per_patch_x,
                self.origin_y + y*self.px_per_patch_y)


def crop_for_refiner(image: np.ndarray, box: tuple[float, float, float, float],
                     size: int = 160, margin: float = 0.35) -> tuple[np.ndarray, CropTransform]:
    """Create square padded symbol crop; keep exact mapping to source pixels.

    Cropping/resizing is for the neural input, not for final geometric fitting:
    that later stage must inspect original source pixels.
    """
    if image.ndim not in (2,3) or size < 8 or margin < 0:
        raise ValueError('invalid source, target size or margin')
    x0,y0,x1,y1 = map(float,box)
    if x1 <= x0 or y1 <= y0:
        raise ValueError('box must have positive width and height')
    cx, cy = (x0+x1)*.5, (y0+y1)*.5
    side = max(x1-x0, y1-y0) * (1+2*margin)
    left, top = cx-side/2, cy-side/2
    # Bilinear interpolation centers align with continuous source coordinates.
    step = side / size
    affine = np.asarray([[step, 0., left + step*.5 - .5],
                         [0., step, top + step*.5 - .5]], dtype=np.float32)
    patch = cv2.warpAffine(image, affine, (size,size),
                           flags=cv2.INTER_LINEAR | cv2.WARP_INVERSE_MAP,
                           borderMode=cv2.BORDER_CONSTANT, borderValue=(255,255,255))
    return patch, CropTransform(left, top, step, step)
