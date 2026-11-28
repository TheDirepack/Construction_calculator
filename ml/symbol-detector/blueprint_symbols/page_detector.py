"""Deterministic full-page symbol detector inference for the local annotator.

Runs locally on CPU with ``onnxruntime`` (requiring neither PyTorch nor
Ultralytics on the workstation) or falls back to Ultralytics ``.pt`` weights when
installed without an ONNX export. Inference is strictly deterministic: fixed
scales (default ``1.0``, native rendered page pixels), fixed tile size and
overlap, no randomization, and deterministic tie-breaking across NMS and
consolidation.

Tile detections become page detections through ONE shared function,
``merge_tile_detections``; deployment (``LocalPageDetector``) and the
training-time real-page evaluation should both call it (with
``tile_raw_detections`` to convert each tile's decoded boxes) so their page
results are identical for the same raw tile outputs.
"""
from __future__ import annotations

from collections.abc import Collection, Mapping, Sequence
import hashlib
import importlib.util
import json
import math
import os
import re
import threading
import time
from pathlib import Path

import cv2
import numpy as np

from blueprint_symbols.label_schema import device_for_class, pending_instance
from blueprint_symbols.multiscale import ScaleTile, plan_pyramid_tiles, sample_tile, touches_inner_edge
from blueprint_symbols.pseudolabel import _containment, _iou as _box_iou
from blueprint_symbols.sheet_gate import GatePolicy, SheetClass, decide_gate

SAFE_TAG = re.compile(r"[^A-Za-z0-9_.-]+")

MERGE_FUNCTION = "blueprint_symbols.page_detector.merge_tile_detections"
SCORE_MODES = ("max", "mean", "wbf")
BLANK_TILE_MIN_VALUE = 250

_DETECTOR_CACHE_LOCK = threading.RLock()
_DETECTOR_CACHE: dict[tuple[str, int, int], "LocalPageDetector"] = {}


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _sidecar_meta_path(weights: Path) -> Path:
    if weights.suffix.lower() == ".onnx":
        return Path(f"{weights}.json")
    return weights.with_suffix(".onnx.json")


def _read_sidecar(weights: Path) -> dict:
    meta_path = _sidecar_meta_path(weights)
    if not meta_path.is_file():
        return {}
    try:
        data = json.loads(meta_path.read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def _prefer_matching_onnx(target: Path) -> Path:
    if target.suffix.lower() != ".pt":
        return target
    onnx_path = target.with_suffix(".onnx")
    meta_path = Path(f"{onnx_path}.json")
    if onnx_path.is_file() and meta_path.is_file():
        if importlib.util.find_spec("ultralytics") is None:
            return onnx_path
        meta = _read_sidecar(onnx_path)
        if meta.get("source_pt_sha256") == _sha256(target):
            return onnx_path
    return target


def resolve_detector_weights(
    explicit: Path | None = None,
    *,
    work_dir: Path | None = None,
) -> Path | None:
    """Locate local detector weights (preferring ONNX when up to date)."""
    env_raw = os.environ.get("BPS_DETECTOR_WEIGHTS", "").strip()
    raw = explicit if explicit is not None else (Path(env_raw) if env_raw else None)
    if raw is not None:
        target = Path(raw).expanduser().resolve()
        if not target.is_file():
            raise FileNotFoundError(target)
        return _prefer_matching_onnx(target)

    base_work = (
        Path(work_dir)
        if work_dir is not None
        else Path(__file__).resolve().parent.parent / "work"
    )
    models_root = base_work / "models"
    if not models_root.is_dir():
        return None

    latest = models_root / "latest"
    if latest.is_dir():
        for name in ("best.onnx", "best.pt"):
            candidate = latest / name
            if candidate.is_file():
                return _prefer_matching_onnx(candidate.resolve())

    subdirs = [d for d in models_root.iterdir() if d.is_dir()]
    subdirs.sort(key=lambda d: (d.stat().st_mtime_ns, d.name), reverse=True)
    for folder in subdirs:
        for name in ("best.onnx", "best.pt"):
            candidate = folder / name
            if candidate.is_file():
                return _prefer_matching_onnx(candidate.resolve())
    return None


def describe_detector_weights(
    explicit: Path | None = None,
    *,
    work_dir: Path | None = None,
) -> dict:
    """Fast metadata-only summary of available detector weights."""
    empty = {
        "available": False,
        "model_name": None,
        "model_path": None,
        "epoch": None,
        "best_fitness": None,
        "recommended_confidence": None,
        "real_eval_summary": None,
        "classes": None,
    }
    try:
        weights = resolve_detector_weights(explicit, work_dir=work_dir)
    except FileNotFoundError:
        return empty
    if weights is None or not weights.is_file():
        return empty
    resolved = weights.resolve()
    meta = _read_sidecar(resolved)
    names = meta.get("names")
    return {
        "available": True,
        "model_name": resolved.parent.name,
        "model_path": str(resolved),
        "epoch": meta.get("epoch"),
        "best_fitness": meta.get("best_fitness"),
        "recommended_confidence": meta.get("recommended_confidence"),
        "real_eval_summary": meta.get("real_eval_summary"),
        "classes": len(names) if isinstance(names, dict) else None,
    }


def nms_xyxy(boxes: np.ndarray, scores: np.ndarray, iou_threshold: float) -> list[int]:
    """Deterministic greedy non-maximum suppression for XYXY boxes."""
    if len(scores) == 0:
        return []
    boxes = np.asarray(boxes, dtype=np.float64)
    scores = np.asarray(scores, dtype=np.float64)
    order = np.array(
        sorted(range(len(scores)), key=lambda i: (-float(scores[i]), i)),
        dtype=np.int64,
    )
    areas = np.maximum(0.0, boxes[:, 2] - boxes[:, 0]) * np.maximum(0.0, boxes[:, 3] - boxes[:, 1])
    keep: list[int] = []
    while order.size > 0:
        idx = int(order[0])
        keep.append(idx)
        if order.size == 1:
            break
        rest = order[1:]
        xx0 = np.maximum(boxes[idx, 0], boxes[rest, 0])
        yy0 = np.maximum(boxes[idx, 1], boxes[rest, 1])
        xx1 = np.minimum(boxes[idx, 2], boxes[rest, 2])
        yy1 = np.minimum(boxes[idx, 3], boxes[rest, 3])
        inter = np.maximum(0.0, xx1 - xx0) * np.maximum(0.0, yy1 - yy0)
        union = np.maximum(1e-9, areas[idx] + areas[rest] - inter)
        iou = inter / union
        order = rest[iou < iou_threshold]
    return keep


def decode_onnx_tile_outputs(
    raw_output: np.ndarray,
    *,
    confidence: float,
    iou_threshold: float = 0.70,
    max_det: int = 300,
    output_layout: str | None = None,
) -> list[tuple[tuple[float, float, float, float], int, float]]:
    """Decode one tile's ONNX output tensor into ``((x0, y0, x1, y1), cls, score)``.

    Supports both Ultralytics raw Detect exports ``(4 + nc, num_anchors)`` and
    end-to-end exports ``(max_det, 6)``.
    """
    arr = np.asarray(raw_output, dtype=np.float32)
    if arr.ndim == 3:
        if arr.shape[0] != 1:
            raise ValueError(f"Expected single-tile ONNX output, got shape {arr.shape}")
        arr = arr[0]
    if arr.ndim != 2 or arr.size == 0:
        return []

    is_e2e = output_layout == "end2end_xyxy_score_cls" or (
        output_layout is None and arr.shape[1] == 6 and arr.shape[0] != 4
    )
    results: list[tuple[tuple[float, float, float, float], int, float]] = []
    if is_e2e:
        if arr.shape[1] < 6:
            return []
        for row in arr:
            x0, y0, x1, y1, score, cls_raw = (float(v) for v in row[:6])
            if score < confidence or x1 <= x0 or y1 <= y0:
                continue
            results.append(((x0, y0, x1, y1), int(round(cls_raw)), score))
        results.sort(key=lambda d: (-d[2], d[1], d[0]))
        return results[:max_det]

    if arr.shape[0] < 5:
        raise ValueError(f"Unexpected YOLO ONNX output shape: {arr.shape}")
    preds = arr.T  # (N, 4 + nc)
    cls_probs = preds[:, 4:]
    cls_ids = np.argmax(cls_probs, axis=1)
    scores = cls_probs[np.arange(len(preds)), cls_ids]
    mask = scores >= confidence
    if not np.any(mask):
        return []

    preds = preds[mask]
    cls_ids = cls_ids[mask]
    scores = scores[mask]
    cx, cy, bw, bh = preds[:, 0], preds[:, 1], preds[:, 2], preds[:, 3]
    valid = (bw > 0) & (bh > 0) & np.isfinite(cx) & np.isfinite(cy) & np.isfinite(bw) & np.isfinite(bh)
    if not np.any(valid):
        return []

    cx, cy, bw, bh = cx[valid], cy[valid], bw[valid], bh[valid]
    cls_ids = cls_ids[valid]
    scores = scores[valid]
    boxes = np.stack([cx - 0.5 * bw, cy - 0.5 * bh, cx + 0.5 * bw, cy + 0.5 * bh], axis=1)

    for cls_id in np.unique(cls_ids):
        c_idx = np.flatnonzero(cls_ids == cls_id)
        kept_local = nms_xyxy(boxes[c_idx], scores[c_idx], iou_threshold)
        for k in kept_local:
            orig = int(c_idx[k])
            b = boxes[orig]
            results.append(((float(b[0]), float(b[1]), float(b[2]), float(b[3])), int(cls_id), float(scores[orig])))

    results.sort(key=lambda d: (-d[2], d[1], d[0]))
    return results[:max_det]


# --------------------------------------------------------------------------
# shared tile -> page fusion (deployment and evaluation must both use these)
# --------------------------------------------------------------------------

def is_blank_tile(patch: np.ndarray) -> bool:
    """A tile with no ink (every pixel >= 250); deployment skips these."""
    return int(patch.min()) >= BLANK_TILE_MIN_VALUE


def tile_raw_detections(
    tile: ScaleTile,
    tile_dets: Sequence[tuple[tuple[float, float, float, float], int, float]],
    *,
    names: Mapping[int, str],
    width: int,
    height: int,
    tile_index: int | str | None = None,
) -> list[dict]:
    """One tile's decoded ``((x0, y0, x1, y1), cls, score)`` boxes as page raw detections.

    Drops boxes cut by an inner tile edge (a neighbouring tile sees them whole),
    maps to page pixels, clips to the page and drops boxes under 2 px. Each
    raw dict records ``tile_index`` (when given) and ``tile_origin`` so
    ``merge_tile_detections`` can count distinct agreeing tiles.
    """
    out: list[dict] = []
    for box, cls_id, score in tile_dets:
        if touches_inner_edge(tile, box):
            continue
        px0, py0, px1, py1 = tile.to_page_box(box)
        bb = [
            max(0.0, min(float(width), px0)),
            max(0.0, min(float(height), py0)),
            max(0.0, min(float(width), px1)),
            max(0.0, min(float(height), py1)),
        ]
        if bb[2] - bb[0] < 2.0 or bb[3] - bb[1] < 2.0:
            continue
        item = {
            "symbol_family": names.get(int(cls_id), str(int(cls_id))),
            "bbox_xyxy": bb,
            "confidence": float(score),
            "source_scale": float(tile.source_scale),
            "tile_origin": [float(tile.x), float(tile.y)],
        }
        if tile_index is not None:
            item["tile_index"] = tile_index
        out.append(item)
    return out


class _TileCover:
    """Vectorized ``_tile_sees_whole`` over many tiles."""

    def __init__(self, tile_map: Mapping) -> None:
        self.keys = list(tile_map)
        tiles = [tile_map[k] for k in self.keys]
        self.x = np.array([t.x for t in tiles], dtype=np.float64)
        self.y = np.array([t.y for t in tiles], dtype=np.float64)
        self.s = np.array([t.source_scale for t in tiles], dtype=np.float64)
        self.size = np.array([t.size for t in tiles], dtype=np.float64)
        boxes = np.array([t.source_box for t in tiles], dtype=np.float64).reshape(-1, 4)
        iw = np.array([t.image_width for t in tiles], dtype=np.float64)
        ih = np.array([t.image_height for t in tiles], dtype=np.float64)
        # Same rules as multiscale.touches_inner_edge (margin 2 model px).
        self.inner_left = boxes[:, 0] > 0.5
        self.inner_top = boxes[:, 1] > 0.5
        self.inner_right = boxes[:, 2] < iw - 0.5
        self.inner_bottom = boxes[:, 3] < ih - 0.5

    def covering(self, box: Sequence[float], margin: float = 2.0) -> set:
        if not self.keys:
            return set()
        mx0 = (box[0] - self.x) / self.s
        my0 = (box[1] - self.y) / self.s
        mx1 = (box[2] - self.x) / self.s
        my1 = (box[3] - self.y) / self.s
        inside = (mx0 >= 0) & (my0 >= 0) & (mx1 <= self.size) & (my1 <= self.size)
        touches = ((mx0 <= margin) & self.inner_left) | ((my0 <= margin) & self.inner_top) | \
            ((mx1 >= self.size - margin) & self.inner_right) | \
            ((my1 >= self.size - margin) & self.inner_bottom)
        return {self.keys[i] for i in np.flatnonzero(inside & ~touches)}


def _tile_sees_whole(tile: ScaleTile, box: Sequence[float]) -> bool:
    """Whether ``box`` (page pixels) lies fully inside ``tile`` without
    touching one of its inner edges, i.e. the tile could have detected it."""
    s = float(tile.source_scale)
    mbox = ((box[0] - tile.x) / s, (box[1] - tile.y) / s,
            (box[2] - tile.x) / s, (box[3] - tile.y) / s)
    if mbox[0] < 0 or mbox[1] < 0 or mbox[2] > tile.size or mbox[3] > tile.size:
        return False
    return not touches_inner_edge(tile, mbox)


class _BoxGrid:
    """Uniform-grid index of boxes so overlap queries stay near-linear on
    flooded pages (an untrained head can emit 300 boxes per tile)."""

    def __init__(self, cell: float = 128.0) -> None:
        self.cell = float(cell)
        self.cells: dict[tuple[int, int], list[int]] = {}

    def _keys(self, box: Sequence[float]):
        c = self.cell
        for gx in range(int(box[0] // c), int(box[2] // c) + 1):
            for gy in range(int(box[1] // c), int(box[3] // c) + 1):
                yield gx, gy

    def add(self, ident: int, box: Sequence[float]) -> None:
        for key in self._keys(box):
            bucket = self.cells.setdefault(key, [])
            if not bucket or bucket[-1] != ident:
                bucket.append(ident)

    def near(self, box: Sequence[float]) -> list[int]:
        """Ids whose (registered) boxes share a cell with ``box``, ascending."""
        found: set[int] = set()
        for key in self._keys(box):
            found.update(self.cells.get(key, ()))
        return sorted(found)


def _det_sort_key(d: dict) -> tuple:
    return (
        -float(d["confidence"]),
        tuple(round(float(v), 4) for v in d["bbox_xyxy"]),
        str(d["symbol_family"]),
    )


def merge_tile_detections(
    raw: Sequence[dict],
    *,
    confidence: float,
    tiles: Sequence[ScaleTile] | Mapping[int | str, ScaleTile] | None = None,
    scales: Sequence[float] | None = None,
    score_mode: str = "max",
    min_output_confidence: float | None = None,
    match_iou: float = 0.35,
    cross_class_iou: float = 0.60,
    containment: float = 0.80,
    dedup_iou: float | None = 0.45,
    min_agreeing_scales: int = 1,
    review_confidence: float = 0.75,
    allowed_classes: Collection[str] | None = None,
    max_raw: int | None = None,
    stats: dict | None = None,
) -> list[dict]:
    """Fuse overlapping-tile (and multi-scale) raw detections into page detections.

    ``raw``: dicts with ``symbol_family``, ``bbox_xyxy`` (page pixels),
    ``confidence``, ``source_scale`` and, ideally, ``tile_index`` (key into
    ``tiles``); ``tile_raw_detections`` builds them. Raw dicts without
    ``tile_index`` are each treated as their own tile.

    1. Drop raw boxes under ``confidence`` (must equal the decode threshold),
       outside ``allowed_classes``, and beyond the ``max_raw`` strongest.
    2. Class-aware fusion: a box joins the same-class cluster whose fused box
       overlaps it most (IoU >= ``match_iou``). The fused box is the
       confidence-weighted mean of the members (weighted box fusion).
    3. Agreement per cluster: ``supporting_detections`` = distinct tiles that
       fired this class here; ``covering_tiles`` = tiles in ``tiles`` that
       contain the fused box whole (so could have fired), plus the agreeing
       ones; ``tile_agreement`` = supporting / covering.
    4. Score (``score_mode``): ``"max"`` (default) keeps the maximum tile
       confidence, so cutoffs chosen on earlier checkpoints
       (``recommended_confidence``) keep their meaning; ``"mean"`` averages
       the per-tile maxima; ``"wbf"`` is weighted-box-fusion style:
       sum of per-tile maxima / covering tiles, which pulls down objects only
       some of the tiles that saw them whole fired on. ``max_confidence``,
       ``mean_confidence`` and ``wbf_confidence`` are always reported.
    5. With more than one scale run, ``min_agreeing_scales`` > 1 drops
       clusters not seen *with the same class* at that many scales (removes
       false positives that hop between classes across scales).
    6. Drop clusters scoring under ``min_output_confidence`` (default
       ``confidence``; a page gate passes a raised cutoff here).
    7. Class-agnostic suppression, strongest first: a different class at
       IoU >= min(``cross_class_iou``, ``dedup_iou``) becomes an
       ``alternative_labels`` entry of the stronger box; the same class at
       IoU >= ``dedup_iou`` or ``containment`` of its area inside the
       stronger box is absorbed (tiles and scales are added to its support).
    8. ``uncertain``/``review_required`` are set only with a reason in
       ``review_reasons``: ``mid_confidence`` (score < ``review_confidence``),
       ``class_disagreement`` (an alternative at >= half the score),
       ``tile_disagreement`` (>= 2 covering tiles, under half agree),
       ``scale_disagreement`` (several scales run, seen at one) or
       ``unstable_score`` (per-tile scores spread >= 0.25).

    ``multiscale_agreement`` is None when only one scale was run. Output is
    sorted by (-confidence, rounded box, class) and stays compatible with
    ``pseudolabel.consolidate_predictions`` consumers. ``stats``, if given,
    is filled with counts of what each step removed.
    """
    if not 0.0 <= float(confidence) <= 1.0:
        raise ValueError(f"confidence must be within [0, 1], got {confidence}")
    if score_mode not in SCORE_MODES:
        raise ValueError(f"score_mode must be one of {SCORE_MODES}, got {score_mode!r}")
    for name, value in (("match_iou", match_iou), ("cross_class_iou", cross_class_iou),
                        ("containment", containment)):
        if not 0.0 < float(value) <= 1.0:
            raise ValueError(f"{name} must be within (0, 1]")
    if dedup_iou is not None and not 0.0 < float(dedup_iou) <= 1.0:
        raise ValueError("dedup_iou must be within (0, 1] or None")
    if int(min_agreeing_scales) < 1:
        raise ValueError("min_agreeing_scales must be >= 1")
    cutoff = float(confidence if min_output_confidence is None else min_output_confidence)

    if tiles is None:
        tile_map: dict | None = None
    elif isinstance(tiles, Mapping):
        tile_map = dict(tiles)
    else:
        tile_map = dict(enumerate(tiles))
    if scales is not None:
        scales_run = sorted({float(s) for s in scales})
    elif tile_map:
        scales_run = sorted({float(t.source_scale) for t in tile_map.values()})
    else:
        scales_run = sorted({float(r.get("source_scale", 1.0)) for r in raw}) or [1.0]
    multi_scale = len(scales_run) >= 2

    counts = {"raw_in": len(raw), "below_confidence": 0, "not_allowed_class": 0,
              "max_raw_truncated": 0, "clusters": 0, "scale_filtered": 0,
              "below_output_confidence": 0, "suppressed": 0, "output": 0}
    cands: list[tuple[dict, object]] = []
    for i, item in enumerate(raw):
        conf = float(item["confidence"])
        if conf < confidence:
            counts["below_confidence"] += 1
            continue
        if allowed_classes is not None and item["symbol_family"] not in allowed_classes:
            counts["not_allowed_class"] += 1
            continue
        box = item["bbox_xyxy"]
        if (len(box) != 4 or not all(isinstance(v, (int, float)) and math.isfinite(v) for v in box)
                or box[2] <= box[0] or box[3] <= box[1]):
            raise ValueError(f"bad raw detection bbox: {box!r}")
        tile_id = item.get("tile_index", f"raw{i}")
        cands.append((item, tile_id))
    cands.sort(key=lambda c: (-float(c[0]["confidence"]), str(c[0]["symbol_family"]),
                              tuple(round(float(v), 4) for v in c[0]["bbox_xyxy"]), str(c[1])))
    if max_raw is not None and len(cands) > max_raw:
        counts["max_raw_truncated"] = len(cands) - int(max_raw)
        cands = cands[:int(max_raw)]

    clusters: list[dict] = []
    grids: dict[str, _BoxGrid] = {}
    for item, tile_id in cands:
        cls = item["symbol_family"]
        grid = grids.setdefault(cls, _BoxGrid())
        best = None
        best_overlap = 0.0
        for ci in grid.near(item["bbox_xyxy"]):          # creation order: ties go to the older
            cl = clusters[ci]
            overlap = _box_iou(item["bbox_xyxy"], cl["box"])
            if overlap >= match_iou and overlap > best_overlap:
                best, best_overlap = cl, overlap
        if best is None:
            best = {"cls": cls, "members": [], "id": len(clusters), "w": 0.0, "wb": [0.0] * 4}
            clusters.append(best)
        best["members"].append((item, tile_id))
        conf = float(item["confidence"])        # confidence-weighted mean box (WBF)
        best["w"] += conf
        best["wb"] = [acc + float(v) * conf for acc, v in zip(best["wb"], item["bbox_xyxy"])]
        best["box"] = [v / best["w"] for v in best["wb"]]
        grid.add(best["id"], best["box"])               # stale cells are harmless: IoU re-checked
    counts["clusters"] = len(clusters)

    cover = _TileCover(tile_map) if tile_map is not None else None
    fused: list[dict] = []
    for cl in clusters:
        per_tile: dict = {}
        tile_origin: dict = {}
        for item, tile_id in cl["members"]:
            per_tile[tile_id] = max(per_tile.get(tile_id, 0.0), float(item["confidence"]))
            tile_origin.setdefault(tile_id, item.get("tile_origin"))
        box = cl["box"]
        agreeing = set(per_tile)
        if cover is not None:
            covering = len(cover.covering(box) | agreeing)
        else:
            covering = None
        tile_scores = list(per_tile.values())
        max_conf = max(tile_scores)
        mean_conf = sum(tile_scores) / len(tile_scores)
        wbf_conf = sum(tile_scores) / max(len(tile_scores), covering or 0)
        score = {"max": max_conf, "mean": mean_conf, "wbf": wbf_conf}[score_mode]
        scales_seen = sorted({float(m.get("source_scale", 1.0)) for m, _ in cl["members"]})
        fused.append({
            "symbol_family": cl["cls"],
            "bbox_xyxy": [float(v) for v in box],
            "confidence": float(score),
            "score_mode": score_mode,
            "max_confidence": float(max_conf),
            "mean_confidence": float(mean_conf),
            "wbf_confidence": float(wbf_conf),
            "min_tile_confidence": float(min(tile_scores)),
            "_tiles": agreeing,
            "_covering": covering,
            "_origins": tile_origin,
            "raw_detections": len(cl["members"]),
            "independent_scales": scales_seen,
        })

    if multi_scale and min_agreeing_scales > 1:
        kept_scale = [d for d in fused if len(d["independent_scales"]) >= min_agreeing_scales]
        counts["scale_filtered"] = len(fused) - len(kept_scale)
        fused = kept_scale
    above = [d for d in fused if d["confidence"] >= cutoff]
    counts["below_output_confidence"] = len(fused) - len(above)

    cross_thr = min(cross_class_iou, dedup_iou) if dedup_iou is not None else cross_class_iou
    kept: list[dict] = []
    kept_grid = _BoxGrid()
    for cand in sorted(above, key=_det_sort_key):
        absorbed = False
        for ki in kept_grid.near(cand["bbox_xyxy"]):     # strongest first, as kept is ordered
            k = kept[ki]
            overlap = _box_iou(cand["bbox_xyxy"], k["bbox_xyxy"])
            if cand["symbol_family"] == k["symbol_family"]:
                if (dedup_iou is not None and overlap >= dedup_iou) or \
                        _containment(cand["bbox_xyxy"], k["bbox_xyxy"]) >= containment:
                    k["_tiles"] = k["_tiles"] | cand["_tiles"]
                    for tid, origin in cand["_origins"].items():
                        k["_origins"].setdefault(tid, origin)
                    k["raw_detections"] += cand["raw_detections"]
                    k["independent_scales"] = sorted(set(k["independent_scales"])
                                                     | set(cand["independent_scales"]))
                    absorbed = True
            elif overlap >= cross_thr:
                alts = k.setdefault("_alts", {})
                prev = alts.get(cand["symbol_family"])
                if prev is None:
                    alts[cand["symbol_family"]] = {"confidence": cand["confidence"],
                                                   "tiles": set(cand["_tiles"])}
                else:
                    prev["confidence"] = max(prev["confidence"], cand["confidence"])
                    prev["tiles"] |= cand["_tiles"]
                absorbed = True
            if absorbed:
                break
        if absorbed:
            counts["suppressed"] += 1
        else:
            kept_grid.add(len(kept), cand["bbox_xyxy"])
            kept.append(cand)

    output: list[dict] = []
    for d in kept:
        agreeing = d.pop("_tiles")
        covering = d.pop("_covering")
        origins = d.pop("_origins")
        alts_raw = d.pop("_alts", {})
        if covering is not None:
            covering = max(covering, len(agreeing))
        support = len(agreeing)
        alt_tiles: set = set()
        alternatives = []
        for fam, info in sorted(alts_raw.items(), key=lambda kv: (-kv[1]["confidence"], kv[0])):
            alt_tiles |= info["tiles"]
            alternatives.append({"symbol_family": fam, "confidence": float(info["confidence"]),
                                 "supporting_detections": len(info["tiles"])})
        voters = agreeing | alt_tiles
        tile_agreement = (support / covering) if covering else None
        multiscale_agreement = (len(d["independent_scales"]) >= 2) if multi_scale else None
        reasons = []
        if d["confidence"] < review_confidence:
            reasons.append("mid_confidence")
        if any(a["confidence"] >= 0.5 * d["confidence"] for a in alternatives):
            reasons.append("class_disagreement")
        if covering is not None and covering >= 2 and tile_agreement is not None and tile_agreement < 0.5:
            reasons.append("tile_disagreement")
        if multiscale_agreement is False:
            reasons.append("scale_disagreement")
        if support >= 2 and d["max_confidence"] - d["min_tile_confidence"] >= 0.25:
            reasons.append("unstable_score")
        ordered_tiles = sorted(agreeing, key=lambda t: (isinstance(t, str), str(t) if isinstance(t, str) else t))
        d.update({
            "supporting_detections": support,
            "supporting_tiles": ordered_tiles,
            "tile_origins": [origins[t] for t in ordered_tiles if origins.get(t) is not None],
            "covering_tiles": covering,
            "tile_agreement": None if tile_agreement is None else round(float(tile_agreement), 4),
            "class_agreement": round(support / len(voters), 4) if voters else 1.0,
            "scales_run": list(scales_run),
            "multiscale_agreement": multiscale_agreement,
            "uncertain": bool(reasons),
            "review_required": bool(reasons),
            "review_reasons": reasons,
            "source": {"kind": "teacher_pseudo_label", "label_method": "model_prediction",
                       "not_ground_truth": True, "merge": MERGE_FUNCTION},
        })
        if alternatives:
            d["alternative_labels"] = alternatives
        output.append(d)
    output.sort(key=_det_sort_key)
    counts["output"] = len(output)
    if stats is not None:
        stats.update(counts)
    return output


def merge_settings(**overrides) -> dict:
    """The merge parameters in effect (defaults of ``merge_tile_detections``)."""
    settings = {"function": MERGE_FUNCTION, "match_iou": 0.35, "cross_class_iou": 0.60,
                "containment": 0.80, "dedup_iou": 0.45, "review_confidence": 0.75,
                "score_mode": "max", "min_agreeing_scales": 1}
    settings.update({k: v for k, v in overrides.items() if v is not None})
    return settings


def describe_tile_grid(width: int, height: int, *, tile_size: int, overlap: int,
                       scales: Sequence[float]) -> list[dict]:
    """Compact, reproducible description of the tile grid per scale."""
    grid = []
    for scale in scales:
        tiles = plan_pyramid_tiles(width, height, tile_size=tile_size, overlap=overlap,
                                   source_scales=(float(scale),))
        xs = sorted({t.x for t in tiles})
        ys = sorted({t.y for t in tiles})
        grid.append({
            "scale": float(scale),
            "origin_xy": [xs[0], ys[0]],
            "step_px": float((tile_size - overlap) * float(scale)),
            "span_px": float(tile_size * float(scale)),
            "columns": len(xs),
            "rows": len(ys),
            "last_origin_xy": [xs[-1], ys[-1]],
            "last_tile_flush_with_page_edge": True,
        })
    return grid


class LocalPageDetector:
    """Deterministic full-page sliding-window symbol detector (CPU)."""

    def __init__(self, weights: Path) -> None:
        self.weights = Path(weights).resolve()
        if not self.weights.is_file():
            raise FileNotFoundError(self.weights)
        meta = _read_sidecar(self.weights)
        self.meta = meta
        self.model_name = self.weights.parent.name
        self.epoch = meta.get("epoch")
        self.best_fitness = meta.get("best_fitness")
        self.imgsz = int(meta.get("imgsz") or 512)
        self.output_layout = meta.get("output_layout")
        self.weights_sha256 = _sha256(self.weights)
        self.model_tag = f"{self.model_name}-{self.weights_sha256[:8]}"
        self._ort_session = None
        self._ort_input_name = None
        self._yolo_model = None
        self.names: dict[int, str] = {}

        if self.weights.suffix.lower() == ".onnx":
            raw_names = meta.get("names")
            if not isinstance(raw_names, dict) or not raw_names:
                raise ValueError(
                    f"Missing or invalid class mapping in ONNX sidecar: {_sidecar_meta_path(self.weights)}"
                )
            self.names = {int(k): str(v) for k, v in raw_names.items()}
            import onnxruntime as ort

            opts = ort.SessionOptions()
            cpu_count = os.cpu_count() or 4
            opts.intra_op_num_threads = max(1, min(4, cpu_count))
            opts.inter_op_num_threads = 1
            self._ort_session = ort.InferenceSession(
                str(self.weights), sess_options=opts, providers=["CPUExecutionProvider"],
            )
            self._ort_input_name = self._ort_session.get_inputs()[0].name
        elif self.weights.suffix.lower() == ".pt":
            from ultralytics import YOLO

            yolo = YOLO(str(self.weights))
            self._yolo_model = yolo
            self.names = {int(k): str(v) for k, v in yolo.names.items()}
        else:
            raise ValueError(f"Unsupported detector weights format: {self.weights.suffix}")

    def _run_tile_batch_onnx(
        self,
        patches: list[np.ndarray],
        *,
        confidence: float,
    ) -> list[list[tuple[tuple[float, float, float, float], int, float]]]:
        assert self._ort_session is not None and self._ort_input_name is not None
        rgb_tensors = [
            patch[:, :, ::-1].astype(np.float32).transpose(2, 0, 1) / 255.0
            for patch in patches
        ]
        batch_arr = np.ascontiguousarray(np.stack(rgb_tensors, axis=0), dtype=np.float32)
        outputs = self._ort_session.run(None, {self._ort_input_name: batch_arr})[0]
        return [
            decode_onnx_tile_outputs(
                outputs[i], confidence=confidence, output_layout=self.output_layout,
            )
            for i in range(len(patches))
        ]

    def _run_tile_batch_pt(
        self,
        patches: list[np.ndarray],
        *,
        confidence: float,
    ) -> list[list[tuple[tuple[float, float, float, float], int, float]]]:
        assert self._yolo_model is not None
        results = self._yolo_model.predict(
            source=patches, conf=confidence, device="cpu", imgsz=self.imgsz, verbose=False,
        )
        decoded: list[list[tuple[tuple[float, float, float, float], int, float]]] = []
        for res in results:
            boxes = getattr(res, "boxes", None)
            if boxes is None or len(boxes) == 0:
                decoded.append([])
                continue
            xyxy = boxes.xyxy.cpu().numpy()
            cls_ids = boxes.cls.cpu().numpy().astype(int)
            scores = boxes.conf.cpu().numpy()
            items = [
                ((float(b[0]), float(b[1]), float(b[2]), float(b[3])), int(c), float(s))
                for b, c, s in zip(xyxy, cls_ids, scores)
            ]
            items.sort(key=lambda d: (-d[2], d[1], d[0]))
            decoded.append(items)
        return decoded

    @staticmethod
    def _extract_tile_native(image: np.ndarray, tile) -> np.ndarray:
        if (tile.source_scale == 1.0 and float(tile.x).is_integer() and float(tile.y).is_integer()
                and tile.x >= 0 and tile.y >= 0):
            x0, y0 = int(tile.x), int(tile.y)
            x1, y1 = min(x0 + tile.size, image.shape[1]), min(y0 + tile.size, image.shape[0])
            crop = image[y0:y1, x0:x1]
            if crop.shape[0] == tile.size and crop.shape[1] == tile.size:
                return crop
            pad = np.full((tile.size, tile.size, 3), 255, dtype=np.uint8)
            pad[:crop.shape[0], :crop.shape[1]] = crop
            return pad
        return sample_tile(image, tile)

    def mapped_classes(self) -> dict[str, tuple[str, str]]:
        """Model class name -> catalog ``(family, variant)`` for mapped classes only."""
        cached = getattr(self, "_mapped_classes", None)
        if cached is None:
            cached = {}
            for name in self.names.values():
                mapped = device_for_class(name)
                if mapped is not None and mapped[0] not in ("unknown_symbol", "unmapped"):
                    cached[name] = (mapped[0], mapped[1])
            self._mapped_classes = cached
        return cached

    def collect_raw_detections(
        self,
        image: np.ndarray,
        *,
        confidence: float,
        overlap: int = 192,
        batch: int | None = None,
        scales: Sequence[float] = (1.0,),
    ) -> tuple[list[dict], dict[int, ScaleTile], dict]:
        """Run every non-blank tile; returns (raw page detections,
        {plan index: evaluated tile}, tiling info)."""
        if not 0.0 < confidence < 1.0:
            raise ValueError(f"confidence must be in (0, 1), got {confidence}")
        if image is None or image.ndim not in (2, 3):
            raise ValueError("expected a 2D or 3D image array")
        chunk_size = batch if batch is not None else (1 if self._ort_session is not None else 8)
        if chunk_size < 1:
            raise ValueError(f"batch must be positive, got {chunk_size}")
        if image.ndim == 2:
            image = cv2.cvtColor(image, cv2.COLOR_GRAY2BGR)
        elif image.shape[2] == 4:
            image = image[:, :, :3]
        h, w = image.shape[:2]
        tiles = plan_pyramid_tiles(
            w, h, tile_size=self.imgsz, overlap=overlap,
            source_scales=tuple(float(s) for s in scales),
        )
        active: list[tuple[int, ScaleTile, np.ndarray]] = []
        for index, tile in enumerate(tiles):
            patch = self._extract_tile_native(image, tile)
            if is_blank_tile(patch):
                continue
            active.append((index, tile, patch))

        raw: list[dict] = []
        for start in range(0, len(active), chunk_size):
            chunk = active[start:start + chunk_size]
            patches = [item[2] for item in chunk]
            if self._ort_session is not None:
                batch_dets = self._run_tile_batch_onnx(patches, confidence=confidence)
            else:
                batch_dets = self._run_tile_batch_pt(patches, confidence=confidence)
            for (index, tile, _), tile_dets in zip(chunk, batch_dets):
                raw.extend(tile_raw_detections(
                    tile, tile_dets, names=self.names, width=w, height=h, tile_index=index,
                ))
        evaluated = {index: tile for index, tile, _ in active}
        info = {
            "tiles": len(tiles),
            "evaluated_tiles": len(active),
            "skipped_blank_tiles": len(tiles) - len(active),
            "image_size": [int(w), int(h)],
        }
        return raw, evaluated, info

    def detect_page(
        self,
        image: np.ndarray,
        *,
        confidence: float = 0.25,
        overlap: int = 192,
        batch: int | None = None,
        scales: Sequence[float] = (1.0,),
        score_mode: str = "max",
        min_agreeing_scales: int = 1,
        min_output_confidence: float | None = None,
        mapped_only: bool = True,
    ) -> tuple[list[dict], dict]:
        """Model-class page detections from the shared ``merge_tile_detections``."""
        raw, evaluated, info = self.collect_raw_detections(
            image, confidence=confidence, overlap=overlap, batch=batch, scales=scales,
        )
        merge_stats: dict = {}
        merged = merge_tile_detections(
            raw,
            confidence=confidence,
            tiles=evaluated,
            scales=scales,
            score_mode=score_mode,
            min_output_confidence=min_output_confidence,
            min_agreeing_scales=min_agreeing_scales,
            allowed_classes=set(self.mapped_classes()) if mapped_only else None,
            stats=merge_stats,
        ) if raw else []
        info = dict(info, raw_detections=len(raw), merge=merge_stats)
        return merged, info

    def provenance(
        self,
        *,
        confidence: float,
        confidence_source: str,
        effective_confidence: float,
        overlap: int,
        scales: Sequence[float],
        image_size: Sequence[int],
        dpi: float | None,
        score_mode: str,
        min_agreeing_scales: int,
        gate: dict | None,
    ) -> dict:
        """What produced a page's detections: checkpoint, cutoff and its
        source, DPI, tile grid and merge settings (written into proposals)."""
        width, height = int(image_size[0]), int(image_size[1])
        return {
            "weights": str(self.weights),
            "weights_sha256": self.weights_sha256,
            "model_tag": self.model_tag,
            "model_name": self.model_name,
            "epoch": self.epoch,
            "best_fitness": self.best_fitness,
            "confidence_threshold": float(confidence),
            "confidence_source": str(confidence_source),
            "recommended_confidence": self.meta.get("recommended_confidence"),
            "effective_confidence": float(effective_confidence),
            "sheet_gate": gate,
            "dpi": None if dpi is None else float(dpi),
            "image_size": [width, height],
            "scales": [float(s) for s in scales],
            "tile_size": self.imgsz,
            "overlap": int(overlap),
            "tile_grid": describe_tile_grid(width, height, tile_size=self.imgsz,
                                            overlap=int(overlap), scales=scales),
            "merge": merge_settings(score_mode=score_mode, min_agreeing_scales=min_agreeing_scales),
        }

    def predict_page(
        self,
        image: np.ndarray,
        *,
        confidence: float = 0.25,
        overlap: int = 192,
        batch: int | None = None,
        scales: Sequence[float] = (1.0,),
        score_mode: str = "max",
        min_agreeing_scales: int = 1,
        sheet: SheetClass | dict | None = None,
        gate: GatePolicy | None = None,
        confidence_source: str = "caller",
        dpi: float | None = None,
    ) -> tuple[list[dict], dict]:
        """Run deterministic tiled detection across an entire page image.

        ``sheet`` (``sheet_gate.classify_pdf_page``) and ``gate`` (a
        ``GatePolicy``) apply the page gate: non-electrical sheets are skipped
        or get the raised cutoff; unknown sheets always run at ``confidence``.
        Without ``gate`` the page is never gated.
        """
        if not 0.0 < confidence < 1.0:
            raise ValueError(f"confidence must be in (0, 1), got {confidence}")
        if image is None or image.ndim not in (2, 3):
            raise ValueError("expected a 2D or 3D image array")
        t0 = time.perf_counter()
        h, w = image.shape[:2]
        decision = decide_gate(sheet, gate, confidence)
        gate_info = decision.to_dict()
        effective = float(decision.confidence)
        scales = tuple(float(s) for s in scales)
        provenance = self.provenance(
            confidence=confidence, confidence_source=confidence_source,
            effective_confidence=effective, overlap=overlap, scales=scales,
            image_size=(w, h), dpi=dpi, score_mode=score_mode,
            min_agreeing_scales=min_agreeing_scales, gate=gate_info,
        )

        if decision.action == "skip":
            n_tiles = len(plan_pyramid_tiles(w, h, tile_size=self.imgsz, overlap=overlap,
                                             source_scales=scales))
            merged: list[dict] = []
            info = {"tiles": n_tiles, "evaluated_tiles": 0, "skipped_blank_tiles": 0,
                    "raw_detections": 0, "merge": {}}
        else:
            merged, info = self.detect_page(
                image, confidence=confidence, overlap=overlap, batch=batch, scales=scales,
                score_mode=score_mode, min_agreeing_scales=min_agreeing_scales,
                min_output_confidence=effective if effective > confidence else None,
            )
        merge_stats = info.get("merge") or {}
        unmapped_class = int(merge_stats.get("not_allowed_class", 0))

        safe_tag = SAFE_TAG.sub("_", self.model_tag)[:40]
        try:
            from annotator.model import discipline_for
        except Exception:
            discipline_for = lambda _family: "unmapped"  # noqa: E731

        mapped_classes = self.mapped_classes()
        instances: list[dict] = []
        for det in merged:
            family, variant = mapped_classes[det["symbol_family"]]
            box = [
                round(max(0.0, min(float(w), float(det["bbox_xyxy"][0]))), 3),
                round(max(0.0, min(float(h), float(det["bbox_xyxy"][1]))), 3),
                round(max(0.0, min(float(w), float(det["bbox_xyxy"][2]))), 3),
                round(max(0.0, min(float(h), float(det["bbox_xyxy"][3]))), 3),
            ]
            if box[2] - box[0] < 2.0 or box[3] - box[1] < 2.0:
                continue
            ident = f"m_{safe_tag}_{len(instances) + 1}"
            instances.append(
                pending_instance(
                    ident,
                    family,
                    variant,
                    box,
                    review_source="model",
                    discipline=discipline_for(family),
                    device_type=family,
                    model_tag=self.model_tag,
                    model_confidence=round(float(det["confidence"]), 4),
                    model_max_confidence=round(float(det["max_confidence"]), 4),
                    model_class=str(det["symbol_family"]),
                    model_supporting_detections=int(det["supporting_detections"]),
                    model_covering_tiles=det["covering_tiles"],
                    model_tile_agreement=det["tile_agreement"],
                    model_tile_origins=det["tile_origins"],
                    model_scales=[float(s) for s in det["independent_scales"]],
                    model_multiscale_agreement=det["multiscale_agreement"],
                    model_alternative_labels=list(det.get("alternative_labels", [])),
                    model_review_required=bool(det["review_required"]),
                    model_review_reasons=list(det["review_reasons"]),
                    model_info=provenance,
                )
            )

        elapsed_ms = round((time.perf_counter() - t0) * 1000.0, 1)
        stats = {
            "tiles": info["tiles"],
            "evaluated_tiles": info["evaluated_tiles"],
            "skipped_blank_tiles": info["skipped_blank_tiles"],
            "raw_detections": info["raw_detections"],
            "detections": len(instances),
            "unmapped_class": unmapped_class,
            "elapsed_ms": elapsed_ms,
            "model_tag": self.model_tag,
            "model_name": self.model_name,
            "model_path": str(self.weights),
            "epoch": self.epoch,
            "best_fitness": self.best_fitness,
            "confidence": float(confidence),
            "effective_confidence": effective,
            "tile_size": self.imgsz,
            "overlap": overlap,
            "scales": list(scales),
            "sheet_gate": gate_info,
            "review_required": sum(1 for x in instances if x["model_review_required"]),
            "merge": merge_stats,
            "provenance": provenance,
        }
        return instances, stats


def get_cached_detector(
    explicit: Path | None = None,
    *,
    work_dir: Path | None = None,
) -> LocalPageDetector:
    """Return a cached thread-safe ``LocalPageDetector`` keyed by file stat."""
    weights = resolve_detector_weights(explicit, work_dir=work_dir)
    if weights is None:
        raise FileNotFoundError(
            "No trained symbol detector weights found under work/models/latest/. "
            "Run remote/pull_best_model.sh or pass --detector-weights."
        )
    resolved = weights.resolve()
    st = resolved.stat()
    key = (str(resolved), st.st_mtime_ns, st.st_size)
    with _DETECTOR_CACHE_LOCK:
        cached = _DETECTOR_CACHE.get(key)
        if cached is not None:
            return cached
        detector = LocalPageDetector(resolved)
        _DETECTOR_CACHE.clear()
        _DETECTOR_CACHE[key] = detector
        return detector
