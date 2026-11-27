"""Deterministic full-page symbol detector inference for the local annotator.

Runs locally on CPU with ``onnxruntime`` (requiring neither PyTorch nor
Ultralytics on the workstation) or falls back to Ultralytics ``.pt`` weights when
installed without an ONNX export. Inference is strictly deterministic: fixed
scale ``1.0`` (native rendered page pixels), fixed tile size and overlap, no
randomization, and deterministic tie-breaking across NMS and consolidation.
"""
from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import re
import threading
import time
from pathlib import Path

import cv2
import numpy as np

from blueprint_symbols.label_schema import device_for_class, iou as _iou, pending_instance
from blueprint_symbols.multiscale import plan_pyramid_tiles, sample_tile, touches_inner_edge
from blueprint_symbols.pseudolabel import consolidate_predictions

SAFE_TAG = re.compile(r"[^A-Za-z0-9_.-]+")

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

    def predict_page(
        self,
        image: np.ndarray,
        *,
        confidence: float = 0.25,
        overlap: int = 192,
        batch: int | None = None,
    ) -> tuple[list[dict], dict]:
        """Run deterministic scale-1.0 tiled detection across an entire page image."""
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

        t0 = time.perf_counter()
        h, w = image.shape[:2]
        tiles = plan_pyramid_tiles(
            w, h, tile_size=self.imgsz, overlap=overlap, source_scales=(1.0,),
        )
        active: list[tuple] = []
        for tile in tiles:
            patch = self._extract_tile_native(image, tile)
            if int(patch.min()) >= 250:
                continue
            active.append((tile, patch))
        skipped_blank = len(tiles) - len(active)

        raw: list[dict] = []
        for start in range(0, len(active), chunk_size):
            chunk = active[start:start + chunk_size]
            chunk_tiles = [item[0] for item in chunk]
            patches = [item[1] for item in chunk]
            if self._ort_session is not None:
                batch_dets = self._run_tile_batch_onnx(patches, confidence=confidence)
            else:
                batch_dets = self._run_tile_batch_pt(patches, confidence=confidence)
            for tile, tile_dets in zip(chunk_tiles, batch_dets):
                for box, cls_id, score in tile_dets:
                    if touches_inner_edge(tile, box):
                        continue
                    px0, py0, px1, py1 = tile.to_page_box(box)
                    bb = [
                        max(0.0, min(float(w), px0)),
                        max(0.0, min(float(h), py0)),
                        max(0.0, min(float(w), px1)),
                        max(0.0, min(float(h), py1)),
                    ]
                    if bb[2] - bb[0] < 2.0 or bb[3] - bb[1] < 2.0:
                        continue
                    raw.append({
                        "symbol_family": self.names.get(int(cls_id), str(int(cls_id))),
                        "bbox_xyxy": bb,
                        "confidence": float(score),
                        "source_scale": 1.0,
                    })

        merged = (
            consolidate_predictions(
                raw, min_confidence=confidence, cross_class_iou=0.60, containment=0.80,
            )
            if raw
            else []
        )
        merged.sort(
            key=lambda d: (
                -float(d["confidence"]),
                tuple(round(float(v), 4) for v in d["bbox_xyxy"]),
                str(d["symbol_family"]),
            )
        )

        safe_tag = SAFE_TAG.sub("_", self.model_tag)[:40]
        model_info = {
            "weights": str(self.weights),
            "weights_sha256": self.weights_sha256,
            "model_name": self.model_name,
            "epoch": self.epoch,
            "best_fitness": self.best_fitness,
            "confidence_threshold": float(confidence),
            "scales": [1.0],
            "tile_size": self.imgsz,
            "overlap": overlap,
        }
        try:
            from annotator.model import discipline_for
        except Exception:
            discipline_for = lambda _family: "unmapped"  # noqa: E731

        instances: list[dict] = []
        unmapped_class = 0
        for det in merged:
            mapped = device_for_class(det["symbol_family"])
            if mapped is None or mapped[0] in ("unknown_symbol", "unmapped"):
                unmapped_class += 1
                continue
            family, variant = mapped
            box = [
                round(max(0.0, min(float(w), float(det["bbox_xyxy"][0]))), 3),
                round(max(0.0, min(float(h), float(det["bbox_xyxy"][1]))), 3),
                round(max(0.0, min(float(w), float(det["bbox_xyxy"][2]))), 3),
                round(max(0.0, min(float(h), float(det["bbox_xyxy"][3]))), 3),
            ]
            if box[2] - box[0] < 2.0 or box[3] - box[1] < 2.0:
                continue
            if any(_iou(box, prev["bbox_xyxy"]) >= 0.45 for prev in instances):
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
                    model_class=str(det["symbol_family"]),
                    model_supporting_detections=int(det.get("supporting_detections", 1)),
                    model_scales=[float(s) for s in det.get("independent_scales", [1.0])],
                    model_info=model_info,
                )
            )

        elapsed_ms = round((time.perf_counter() - t0) * 1000.0, 1)
        stats = {
            "tiles": len(tiles),
            "evaluated_tiles": len(active),
            "skipped_blank_tiles": skipped_blank,
            "raw_detections": len(raw),
            "detections": len(instances),
            "unmapped_class": unmapped_class,
            "elapsed_ms": elapsed_ms,
            "model_tag": self.model_tag,
            "model_name": self.model_name,
            "model_path": str(self.weights),
            "epoch": self.epoch,
            "best_fitness": self.best_fitness,
            "confidence": float(confidence),
            "tile_size": self.imgsz,
            "overlap": overlap,
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
