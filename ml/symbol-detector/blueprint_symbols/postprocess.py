"""CPU decoding and tile merging for untrained anchor-free detector outputs.

Model output per scale: (N, 1 + classes + 4, h, w), ordered as
[objectness, class logits..., dx, dy, log_w, log_h].
Offsets are sigmoid units inside the feature cell; sizes are in input pixels.
No postprocessing operations need to be placed on mobile NPUs.
"""
from __future__ import annotations
from dataclasses import dataclass, replace
import numpy as np
from .tiles import Tile


@dataclass(frozen=True)
class Detection:
    class_id: int
    confidence: float
    box: tuple[float, float, float, float]  # x0,y0,x1,y1, exclusive upper edges
    tile_id: int = -1

    @property
    def center(self) -> tuple[float, float]:
        x0, y0, x1, y1 = self.box
        return ((x0+x1)/2.0, (y0+y1)/2.0)


def _sigmoid(a: np.ndarray) -> np.ndarray:
    return 1.0 / (1.0 + np.exp(-np.clip(a, -50, 50)))


def decode_heads(heads: tuple[np.ndarray, ...], input_size: tuple[int, int],
                 min_score: float = 0.25, topk: int = 250) -> list[Detection]:
    """Decode a single tile. `heads` are NCHW arrays for batch N=1.

    Box sizes use exp(log_size), clamped for numerical safety.  Decoding is
    explicitly distinct from learned inference and should be tested against
    known manually constructed logits before real use.
    """
    width, height = input_size
    if width <= 0 or height <= 0 or topk <= 0 or not 0 <= min_score <= 1:
        raise ValueError('invalid input_size, threshold, or topk')
    result: list[Detection] = []
    for head, stride in zip(heads, (4, 8, 16)):
        raw = np.asarray(head)
        if raw.ndim != 4 or raw.shape[0] != 1 or raw.shape[1] < 6:
            raise ValueError('expected (1, 1+K+4, H, W), with K>=1')
        classes = raw.shape[1] - 5
        h, w = raw.shape[2:]
        objectness = _sigmoid(raw[0, 0])
        scores = objectness[None, ...] * _sigmoid(raw[0, 1:1+classes])
        flat = scores.reshape(-1)
        n = min(flat.size, topk)
        candidate_indices = np.argpartition(flat, -n)[-n:]
        for index in candidate_indices:
            score = float(flat[index])
            if score < min_score or not np.isfinite(score):
                continue
            class_id, iy, ix = np.unravel_index(int(index), scores.shape)
            dx = float(_sigmoid(raw[0, 1+classes, iy, ix]))
            dy = float(_sigmoid(raw[0, 2+classes, iy, ix]))
            bw = float(np.exp(np.clip(raw[0, 3+classes, iy, ix], -3, np.log(max(width, height)*2))))
            bh = float(np.exp(np.clip(raw[0, 4+classes, iy, ix], -3, np.log(max(width, height)*2))))
            cx, cy = (ix+dx)*stride, (iy+dy)*stride
            box = (max(0.0, cx-bw/2), max(0.0, cy-bh/2),
                   min(float(width), cx+bw/2), min(float(height), cy+bh/2))
            if box[2] > box[0] and box[3] > box[1]:
                result.append(Detection(int(class_id), score, box))
    result.sort(key=lambda d: d.confidence, reverse=True)
    return result[:topk]


def iou(a: tuple[float, float, float, float],
        b: tuple[float, float, float, float]) -> float:
    x0, y0 = max(a[0], b[0]), max(a[1], b[1])
    x1, y1 = min(a[2], b[2]), min(a[3], b[3])
    intersection = max(0., x1-x0) * max(0., y1-y0)
    area_a = max(0., a[2]-a[0]) * max(0., a[3]-a[1])
    area_b = max(0., b[2]-b[0]) * max(0., b[3]-b[1])
    union = area_a + area_b - intersection
    return intersection / union if union else 0.0


def to_page(detections: list[Detection], tile: Tile, tile_id: int = -1) -> list[Detection]:
    return [replace(d, box=tile.to_page_box(d.box), tile_id=tile_id) for d in detections]


def merge_tiles(detections: list[Detection], iou_threshold: float = 0.45) -> list[Detection]:
    """Class-specific greedy NMS; avoids most duplicate overlapping-tile hits.

    This is NOT sufficient to merge truncated detections on tile edges; use
    ample overlap + a border-rejection policy in the full inference system.
    """
    if not 0 <= iou_threshold <= 1:
        raise ValueError('iou_threshold must be within [0,1]')
    kept: list[Detection] = []
    for d in sorted(detections, key=lambda v: v.confidence, reverse=True):
        if all(d.class_id != k.class_id or iou(d.box, k.box) <= iou_threshold for k in kept):
            kept.append(d)
    return kept
