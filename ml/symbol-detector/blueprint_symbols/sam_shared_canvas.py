"""Same-image SAM3 exemplar prompting on a single shared reference + plan tile.

SAM3 geometry prompts are image-specific, not a supported cross-image
semantic feature cache. Keep all distinct local NECA base glyphs on a narrow
reference strip inside the SAME vision frame as the blueprint crop.
Each proposal concept uses one independently labeled reference box; geometry
and DETR batches reuse the ONE vision encoding for that tile.
"""
from __future__ import annotations

from dataclasses import dataclass
from math import ceil
from typing import Any

import cv2
import numpy as np


@dataclass(frozen=True)
class SharedCanvas:
    panel_rgb: np.ndarray
    target_width: int
    reference_boxes: tuple[tuple[float, float, float, float], ...]
    slot_pitch: int
    reference_px: int

    @property
    def tile_size(self) -> int:
        return int(self.panel_rgb.shape[0])

    def with_target(self, crop: np.ndarray) -> np.ndarray:
        h, w = crop.shape[:2]
        if crop.ndim != 3 or crop.shape[2] != 3:
            raise ValueError('expected RGB blueprint target')
        if h > self.tile_size or w > self.target_width:
            raise ValueError('target crop overflows reference canvas')
        result = self.panel_rgb.copy()
        result[:h, :w] = crop
        return result


def build_shared_canvas(
    glyphs: list[Any], *, tile_size: int = 1008,
    slot_pitch: int = 36, reference_px: int = 24,
    min_target_width: int = 480,
) -> SharedCanvas:
    """Preserve every reference: fail instead of silently truncating categories.

    Reference boxes are normalized CXCYWH in full tile coordinates. Model
    predictions are retained only on the LEFT target region; detections
    touching the reference strip are not blueprint objects.
    """
    if (not glyphs or tile_size < 64 or slot_pitch < 8
            or reference_px < 4 or reference_px >= slot_pitch
            or min_target_width < 1):
        raise ValueError('invalid shared-canvas layout parameters')
    rows = tile_size // slot_pitch
    columns = ceil(len(glyphs) / rows)
    reference_width = columns * slot_pitch
    target_width = tile_size - reference_width
    if target_width < min_target_width or target_width <= 0:
        raise ValueError(
            f'{len(glyphs)} visual groups do not fit in the reference strip: '
            f'target_width={target_width}, minimum={min_target_width}. '
            'Reduce reference prototypes per group or choose smaller glyph slots.'
        )
    panel = np.full((tile_size, tile_size, 3), 255, dtype=np.uint8)
    bounds = []
    for idx, glyph in enumerate(glyphs):
        r, col = divmod(idx, columns)
        cx = target_width + col * slot_pitch + slot_pitch // 2
        cy = r * slot_pitch + slot_pitch // 2
        alpha = np.asarray(glyph.alpha)
        if alpha.ndim != 2 or alpha.size == 0:
            raise ValueError(f'empty reference alpha for {glyph.symbol_id}')
        ah, aw = alpha.shape
        factor = reference_px / max(ah, aw)
        width = max(2, int(round(aw * factor)))
        height = max(2, int(round(ah * factor)))
        ink = cv2.resize(
            alpha, (width, height),
            interpolation=cv2.INTER_AREA if factor < 1 else cv2.INTER_LINEAR,
        )
        x0, y0 = cx-width//2, cy-height//2
        x1, y1 = x0+width, y0+height
        if x0 < target_width or x1 > tile_size or y0 < 0 or y1 > tile_size:
            raise ValueError('reference glyph overflows assigned slot')
        roi = panel[y0:y1, x0:x1].astype(np.float32)
        opacity = (ink.astype(np.float32)/255.0)[:, :, None]
        panel[y0:y1, x0:x1] = np.clip(
            roi*(1.0-opacity), 0, 255,
        ).astype(np.uint8)
        bounds.append((
            ((x0+x1)/2)/tile_size, ((y0+y1)/2)/tile_size,
            (x1-x0)/tile_size, (y1-y0)/tile_size,
        ))
    return SharedCanvas(
        panel_rgb=panel, target_width=target_width,
        reference_boxes=tuple(bounds),
        slot_pitch=slot_pitch, reference_px=reference_px,
    )
