"""Deterministic periodic evaluation on real blank and fully-labeled blueprint pages.

Runs whole-page scale-1.0 tiled inference (no blank-tile skipping, no sampling,
no augmentation) periodically during Phase-2 post-training so checkpoints can be
audited and selected for low false positives on blank sheets, low off-label FPs
on complete sheets, and accurate device classification.

Page sets (``select_real_eval_pages``):

* ``selection``: half of the held-out pages of training drawings
  (``real_holdout`` rows with ``eval_set == "selection"``). The ONLY pages
  that choose ``best_real.pt``, the confidence cutoffs and ``real_score``.
* ``test``: the other half (``eval_set == "test"``). Locked: scored at the
  cutoffs chosen on ``selection`` and only reported.
* ``cross_document``: real fully-labelled / blank pages of the validation and
  test drawings (``1_blueprints/evaluation``), never trained and never used to
  choose anything. Reported separately.

``test`` and ``cross_document`` are scored when a checkpoint becomes the new
``best_real.pt``, at the last epoch, or every ``report_every`` evaluations, so
their numbers describe the chosen checkpoint and are not looked at every epoch.
"""
from __future__ import annotations

import copy
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path

import cv2
import numpy as np

from .dataset_manifest import assign_holdout_eval_sets, trusted_training_instance
from .label_schema import load_training_page, parse_page_ref
from .multiscale import plan_pyramid_tiles
from .page_detector import (LocalPageDetector, decode_onnx_tile_outputs, merge_tile_detections,
                            tile_raw_detections)


SWEEP_FLOOR = 0.10                      # lowest confidence kept for the sweep
SWEEP_THRESHOLDS = tuple(round(0.10 + 0.05 * i, 2) for i in range(18)) + (0.97, 0.98, 0.99)
BLANK_FP_BUDGET = 0.05                  # "at most 1 false detection per 20 blank pages"
BLANK_FP_WEIGHT = 2.0                   # blank-page FPs count twice as heavily as labelled-page FPs
FP_SCORE_BETA = 0.25                    # F0.25: precision weighted 4x recall
MAX_RAW_PER_PAGE = 4000
DEFAULT_BLANK_MARGIN = 0.05             # cutoff must clear the highest blank-page score by this much
DEFAULT_MIN_RECALL = 0.05               # below this a checkpoint is never saved as best_real.pt
CLASS_REPORT_CUTOFFS = (0.10, 0.25, 0.50, 0.75)
STRESS_SCALES = (0.75, 1.5)
EVAL_SETS = ("selection", "test", "cross_document")
METRICS_SCOPE = (
    "Top-level keys, 'selection', 'chosen_threshold', 'recommended_confidence', "
    "'recommended_class_confidence' and 'real_score' come from the SELECTION pages only and "
    "are what chose this checkpoint and its cutoffs (optimistic). 'test' (held-out pages of "
    "training drawings) and 'cross_document' (evaluation drawings) were scored at those "
    "cutoffs and never used to choose anything."
)


def is_real_source_type(source_type: str | None) -> bool:
    """True only for genuine non-composited construction sheets."""
    st = str(source_type or "").lower()
    if not st or any(tok in st for tok in ("synthetic", "neca_svg", "composite", "wall_only")):
        return False
    return "real" in st


def _resolve_page_image_path(ref: str, page: dict) -> Path:
    parsed = parse_page_ref(ref)
    base_path = Path(parsed[0]) if parsed is not None else Path(ref)
    return (base_path.parent / str(page["image_path"])).resolve()


def _pick_even(items: list[dict], limit: int) -> list[dict]:
    if limit <= 0:
        return []
    if len(items) <= limit:
        return list(items)
    return [items[int(i * len(items) / limit)] for i in range(limit)]


def _env_float(name: str, default: float) -> float:
    raw = os.environ.get(name)
    return float(raw) if raw not in (None, "") else float(default)


def _env_int(name: str, default: int) -> int:
    raw = os.environ.get(name)
    return int(raw) if raw not in (None, "") else int(default)


def _page_row(ref: str, split: str) -> dict | None:
    page = load_training_page(ref)
    if not is_real_source_type(page.get("source_type")):
        return None
    is_blank = page.get("verified_no_symbols") is True and not page.get("instances")
    is_complete = (
        page.get("annotation_completeness_verified") is True
        and not page.get("verified_no_symbols")
    )
    if not is_blank and not is_complete:
        return None
    return {
        "ref": ref,
        "split": split,
        "document_id": str(page.get("document_id") or page.get("source_id") or ""),
        "page_index": int(page.get("page_index") or 0),
        "image_path": str(_resolve_page_image_path(ref, page)),
        "image_width": int(page["image_width"]),
        "image_height": int(page["image_height"]),
        "instances": list(page.get("instances") or []),
        "verified_no_symbols": bool(is_blank),
    }


def _sort_key(r: dict):
    return (r["document_id"], r["page_index"], r["ref"])


def select_real_eval_pages(
    manifest_path: Path,
    *,
    max_blank_pages: int = 24,
    max_complete_pages: int = 24,
) -> dict:
    """Deterministically select real verified-blank and fully-labeled pages.

    Returns ``sets`` with ``selection``, ``test`` and ``cross_document`` page
    lists (each capped at ``max_blank_pages`` / ``max_complete_pages``, picked
    evenly across drawings). The top-level ``blank_pages`` / ``complete_pages``
    are the selection set.

    A manifest with ``real_holdout`` (verified pages of training drawings that
    never trained, ``label_store.labeled_pages``) splits those pages into
    ``selection`` and ``test`` by their ``eval_set`` (computed with
    ``dataset_manifest.assign_holdout_eval_sets`` for older manifests). An
    older manifest without held-out pages falls back to training pages
    (``page_pool == "training_pages_fallback"``: optimistic, they trained).
    Real pages of the validation and test drawings are always the
    ``cross_document`` set and never selection."""
    manifest = json.loads(Path(manifest_path).read_text(encoding="utf-8"))
    splits = manifest.get("splits") or {}
    holdout_rows = manifest.get("real_holdout") or []
    seen_refs: set[str] = set()

    def rows_for(refs: list, split: str) -> list[dict]:
        out = []
        for raw_ref in refs:
            ref = str(raw_ref)
            if ref in seen_refs:
                continue
            seen_refs.add(ref)
            row = _page_row(ref, split)
            if row is not None:
                out.append(row)
        return out

    cross = rows_for(list(splits.get("val") or []) + list(splits.get("test") or []), "val_test")
    cross_splits = {str(r): name for name in ("val", "test") for r in (splits.get(name) or [])}
    for row in cross:
        row["split"] = cross_splits.get(row["ref"], "val")
        row["eval_set"] = "cross_document"

    if holdout_rows:
        page_pool = "real_holdout"
        sets_by_ref = {str(r["annotation"]): r.get("eval_set") for r in holdout_rows}
        pool = rows_for([r["annotation"] for r in holdout_rows], "holdout")
        missing = [r for r in pool if sets_by_ref.get(r["ref"]) not in ("selection", "test")]
        assign_holdout_eval_sets(missing)       # manifests written before the selection/test split
        for row in pool:
            if sets_by_ref.get(row["ref"]) in ("selection", "test"):
                row["eval_set"] = sets_by_ref[row["ref"]]
    else:
        page_pool = "training_pages_fallback"
        pool = rows_for(list(splits.get("train") or []), "train")
        assign_holdout_eval_sets(pool)

    sets: dict[str, dict] = {}
    for name in EVAL_SETS:
        rows = cross if name == "cross_document" else [r for r in pool if r["eval_set"] == name]
        blank = sorted((r for r in rows if r["verified_no_symbols"]), key=_sort_key)
        complete = sorted((r for r in rows if not r["verified_no_symbols"]), key=_sort_key)
        sets[name] = {
            "blank_pages": _pick_even(blank, max_blank_pages),
            "complete_pages": _pick_even(complete, max_complete_pages),
            "available_blank_pages": len(blank),
            "available_complete_pages": len(complete),
        }
    sel = sets["selection"]
    return {
        "blank_pages": sel["blank_pages"],
        "complete_pages": sel["complete_pages"],
        "available_blank_pages": sel["available_blank_pages"],
        "available_complete_pages": sel["available_complete_pages"],
        "page_pool": page_pool,
        "val_test_independent": manifest.get("val_test_independent"),
        "sets": sets,
    }


def predict_full_page_torch(
    model,
    image_bgr: np.ndarray,
    *,
    names: dict[int, str],
    imgsz: int = 512,
    overlap: int = 192,
    confidence: float = 0.25,
    batch_size: int = 8,
    device=None,
    max_raw: int | None = None,
) -> tuple[list[dict], int]:
    """Deterministic scale-1.0 tiled inference over EVERY tile of a page image.
    ``max_raw`` keeps only the most confident tile detections before merging."""
    import torch

    if image_bgr is None or image_bgr.ndim not in (2, 3):
        raise ValueError("expected a 2D or 3D BGR image")
    if image_bgr.ndim == 2:
        image_bgr = cv2.cvtColor(image_bgr, cv2.COLOR_GRAY2BGR)
    elif image_bgr.shape[2] == 4:
        image_bgr = image_bgr[:, :, :3]

    h, w = image_bgr.shape[:2]
    tiles = plan_pyramid_tiles(w, h, tile_size=imgsz, overlap=overlap, source_scales=(1.0,))
    was_training = bool(getattr(model, "training", False))
    try:
        model.eval()
        param = next(model.parameters(), None)
        target_device = device if device is not None else (
            param.device if param is not None else torch.device("cpu")
        )
        target_dtype = (
            param.dtype if (param is not None and param.is_floating_point()) else torch.float32
        )
        layers = getattr(model, "model", None)
        detect = layers[-1] if layers is not None and len(layers) > 0 else None
        output_layout = (
            "end2end_xyxy_score_cls"
            if bool(getattr(detect, "end2end", False) or getattr(model, "end2end", False))
            else "xywh_cls_probs"
        )

        raw: list[dict] = []
        with torch.inference_mode():
            for start in range(0, len(tiles), max(1, int(batch_size))):
                chunk_tiles = tiles[start:start + max(1, int(batch_size))]
                batch_np = np.stack(
                    [
                        LocalPageDetector._extract_tile_native(image_bgr, t)[:, :, ::-1]
                        .transpose(2, 0, 1)
                        for t in chunk_tiles
                    ],
                    axis=0,
                )
                tensor = torch.from_numpy(np.ascontiguousarray(batch_np)).to(
                    device=target_device, dtype=target_dtype
                ) / 255.0
                with torch.amp.autocast(
                    device_type=target_device.type,
                    enabled=target_device.type == "cuda",
                ):
                    out = model(tensor)
                pred_tensor = out[0] if isinstance(out, (tuple, list)) else out
                pred_np = pred_tensor.detach().float().cpu().numpy()
                for idx, tile in enumerate(chunk_tiles):
                    dets = decode_onnx_tile_outputs(
                        pred_np[idx],
                        confidence=confidence,
                        output_layout=output_layout,
                    )
                    raw += tile_raw_detections(
                        tile, dets, names=names, width=w, height=h, tile_index=start + idx)
        # The same merge as deployment (page_detector.LocalPageDetector), so the
        # real-page numbers describe what the annotator shows.
        consolidated = merge_tile_detections(
            raw, confidence=confidence, tiles=dict(enumerate(tiles)), max_raw=max_raw)
        consolidated.sort(
            key=lambda d: (
                -float(d["confidence"]),
                tuple(round(float(v), 4) for v in d["bbox_xyxy"]),
                str(d["symbol_family"]),
            )
        )
        return consolidated, len(tiles)
    finally:
        model.train(was_training)



# --------------------------------------------------------------------------
# Matching and scoring
# --------------------------------------------------------------------------

def _det_sort_key(d: dict):
    return (
        -float(d["confidence"]),
        tuple(round(float(v), 4) for v in d["bbox_xyxy"]),
        str(d["symbol_family"]),
    )


def _iou_matrix(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    """Pairwise IoU, the same arithmetic as ``label_schema.iou``."""
    if len(a) == 0 or len(b) == 0:
        return np.zeros((len(a), len(b)), dtype=np.float64)
    x0 = np.maximum(a[:, None, 0], b[None, :, 0])
    y0 = np.maximum(a[:, None, 1], b[None, :, 1])
    x1 = np.minimum(a[:, None, 2], b[None, :, 2])
    y1 = np.minimum(a[:, None, 3], b[None, :, 3])
    inter = np.maximum(0.0, x1 - x0) * np.maximum(0.0, y1 - y0)
    area_a = (a[:, 2] - a[:, 0]) * (a[:, 3] - a[:, 1])
    area_b = (b[:, 2] - b[:, 0]) * (b[:, 3] - b[:, 1])
    union = area_a[:, None] + area_b[None, :] - inter
    with np.errstate(divide="ignore", invalid="ignore"):
        return np.where(union > 0, inter / np.where(union > 0, union, 1.0), 0.0)


class _PageMatcher:
    """One labelled page's detections against its truth, IoUs computed once,
    scored at any cutoff (``score``) with ``score_complete_page`` semantics."""

    def __init__(self, detections: list[dict], gt_instances: list[dict], *, class_names: set[str],
                 match_iou: float = 0.50, near_iou: float = 0.10) -> None:
        self.dets = sorted((d for d in detections if d.get("symbol_family") in class_names),
                           key=_det_sort_key)
        self.conf = np.array([float(d["confidence"]) for d in self.dets], dtype=np.float64)
        self.cls = [str(d["symbol_family"]) for d in self.dets]
        gts = [(list(map(float, g["bbox_xyxy"])), str(g["symbol_family"]))
               for g in gt_instances
               if g.get("symbol_family") in class_names and trusted_training_instance(g)]
        self.gt_cls = np.array([c for _, c in gts], dtype=object)
        self.n_gt = len(gts)
        self.match_iou = float(match_iou)
        self.near_iou = float(near_iou)
        self.iou = _iou_matrix(
            np.array([[float(v) for v in d["bbox_xyxy"]] for d in self.dets], dtype=np.float64).reshape(-1, 4),
            np.array([b for b, _ in gts], dtype=np.float64).reshape(-1, 4))

    def keep(self, threshold: float, class_cutoffs: dict[str, float] | None = None) -> np.ndarray:
        if class_cutoffs:
            th = np.array([float(class_cutoffs.get(c, threshold)) for c in self.cls], dtype=np.float64)
            return self.conf >= th
        return self.conf >= float(threshold)

    def score(self, keep: np.ndarray) -> dict:
        idx = [int(i) for i in np.flatnonzero(keep)]
        free = np.ones(self.n_gt, dtype=bool)
        matched: set[int] = set()
        tp_exact = tp_wrong = 0
        if self.n_gt:
            for exact in (True, False):
                for di in idx:
                    if di in matched:
                        continue
                    ok = free & (self.iou[di] >= self.match_iou)
                    if exact:
                        ok &= self.gt_cls == self.cls[di]
                    if not ok.any():
                        continue
                    gi = int(np.argmax(np.where(ok, self.iou[di], -1.0)))
                    free[gi] = False
                    matched.add(di)
                    if exact:
                        tp_exact += 1
                    else:
                        tp_wrong += 1
        fp_near = fp_off = 0
        for di in idx:
            if di in matched:
                continue
            if self.n_gt and float(self.iou[di].max()) >= self.near_iou:
                fp_near += 1
            else:
                fp_off += 1
        return {
            "gt_total": self.n_gt,
            "detections": len(idx),
            "tp_exact_class": tp_exact,
            "tp_wrong_class": tp_wrong,
            "fp_near_gt": fp_near,
            "fp_off_label": fp_off,
            "fn_missed": self.n_gt - tp_exact - tp_wrong,
        }


def score_complete_page(
    detections: list[dict],
    gt_instances: list[dict],
    *,
    class_names: set[str],
    conf_threshold: float = 0.25,
    match_iou: float = 0.50,
    near_iou: float = 0.10,
) -> dict:
    """Match page detections against ground-truth instances by location and class."""
    m = _PageMatcher(detections, gt_instances, class_names=class_names,
                     match_iou=match_iou, near_iou=near_iou)
    return m.score(m.keep(conf_threshold))


def _aggregate_complete_scores(per_page: list[dict]) -> dict:
    gt_total = sum(p["gt_total"] for p in per_page)
    dets = sum(p["detections"] for p in per_page)
    tp_exact = sum(p["tp_exact_class"] for p in per_page)
    tp_wrong = sum(p["tp_wrong_class"] for p in per_page)
    fp_near = sum(p["fp_near_gt"] for p in per_page)
    fp_off = sum(p["fp_off_label"] for p in per_page)
    fn_missed = sum(p["fn_missed"] for p in per_page)
    precision = float(tp_exact / dets) if dets > 0 else 0.0
    recall = float(tp_exact / gt_total) if gt_total > 0 else 0.0
    f1 = (2.0 * precision * recall / (precision + recall)) if (precision + recall) > 0 else 0.0
    f05 = (
        (1.25 * precision * recall / (0.25 * precision + recall))
        if (0.25 * precision + recall) > 0
        else 0.0
    )
    loc_recall = float((tp_exact + tp_wrong) / gt_total) if gt_total > 0 else 0.0
    return {
        "gt_total": gt_total,
        "detections": dets,
        "tp_exact_class": tp_exact,
        "tp_wrong_class": tp_wrong,
        "fp_near_gt": fp_near,
        "fp_off_label": fp_off,
        "fn_missed": fn_missed,
        "precision_exact": round(precision, 5),
        "recall_exact": round(recall, 5),
        "f1_exact": round(f1, 5),
        "f05_exact": round(f05, 5),
        "location_recall": round(loc_recall, 5),
    }


# --------------------------------------------------------------------------
# Uncertainty (exact Poisson / Clopper-Pearson bounds; no scipy needed)
# --------------------------------------------------------------------------

def _poisson_cdf(k: int, lam: float) -> float:
    if lam <= 0:
        return 1.0
    return min(1.0, sum(math.exp(-lam + i * math.log(lam) - math.lgamma(i + 1)) for i in range(int(k) + 1)))


def _binom_cdf(x: int, n: int, p: float) -> float:
    if x < 0:
        return 0.0
    if x >= n or p <= 0.0:
        return 1.0
    if p >= 1.0:
        return 0.0
    lp, lq = math.log(p), math.log1p(-p)
    lc = math.lgamma(n + 1)
    return min(1.0, sum(math.exp(lc - math.lgamma(i + 1) - math.lgamma(n - i + 1) + i * lp + (n - i) * lq)
                        for i in range(int(x) + 1)))


def _bisect(f, lo: float, hi: float, iters: int = 60) -> float:
    """Root of a decreasing ``f`` (f(lo) > 0 >= f(hi))."""
    for _ in range(iters):
        mid = 0.5 * (lo + hi)
        if f(mid) > 0:
            lo = mid
        else:
            hi = mid
    return hi


def poisson_upper_bound(k: int, conf: float = 0.95) -> float:
    """One-sided ``conf`` upper bound on a Poisson mean after observing ``k``
    events (k=0: -ln(0.05) = 3.0)."""
    alpha = 1.0 - conf
    hi = max(10.0, 2.0 * k + 20.0)
    while _poisson_cdf(k, hi) > alpha:
        hi *= 2.0
    return _bisect(lambda lam: _poisson_cdf(k, lam) - alpha, 0.0, hi)


def binomial_upper_bound(x: int, n: int, conf: float = 0.95) -> float | None:
    """One-sided Clopper-Pearson upper bound on a proportion (x of n)."""
    if n <= 0:
        return None
    if x >= n:
        return 1.0
    alpha = 1.0 - conf
    return _bisect(lambda p: _binom_cdf(x, n, p) - alpha, 0.0, 1.0)


def binomial_interval(x: int, n: int, conf: float = 0.95) -> list[float] | None:
    """Two-sided Clopper-Pearson interval for x of n."""
    if n <= 0:
        return None
    a = (1.0 - conf) / 2.0
    lo = 0.0 if x <= 0 else _bisect(lambda p: _binom_cdf(x - 1, n, p) - (1.0 - a), 0.0, 1.0)
    hi = 1.0 if x >= n else _bisect(lambda p: _binom_cdf(x, n, p) - a, 0.0, 1.0)
    return [round(lo, 5), round(hi, 5)]


def _uncertainty(point: dict, *, documents: int, with_intervals: bool = True) -> dict:
    n_blank = point["blank_pages"]
    out = {
        "documents": documents,
        "blank_pages": n_blank,
        "complete_pages": point["complete_pages"],
        "truth_symbols": point["gt_total"],
        "blank_fp_per_page_upper95": (round(poisson_upper_bound(point["blank_fp"]) / n_blank, 5)
                                      if n_blank else None),
        "blank_pages_with_fp_upper95": (round(binomial_upper_bound(point["blank_pages_with_fp"], n_blank), 5)
                                        if n_blank else None),
    }
    if with_intervals:
        out["recall_ci95"] = binomial_interval(point["found"], point["gt_total"])
        out["precision_ci95"] = binomial_interval(point["found"], point["found"] + point["labelled_fp"]
                                                  + point["blank_fp"])
    return out


# --------------------------------------------------------------------------
# Page records and operating points
# --------------------------------------------------------------------------

def _blank_record(row: dict, dets: list, tiles: int | None = None) -> dict:
    conf, cls = [], []
    for d in dets:
        if isinstance(d, dict):
            conf.append(float(d["confidence"]))
            cls.append(str(d.get("symbol_family")))
        else:
            conf.append(float(d))
            cls.append("?")
    return {
        "document_id": str(row.get("document_id") or ""),
        "page_index": int(row.get("page_index") or 0),
        "split": row.get("split"),
        "eval_set": row.get("eval_set"),
        "tiles": tiles,
        "blank": True,
        "conf": np.array(conf, dtype=np.float64),
        "cls": cls,
    }


def _complete_record(row: dict, dets: list[dict], instances: list[dict], class_set: set[str],
                     tiles: int | None = None) -> dict:
    return {
        "document_id": str(row.get("document_id") or ""),
        "page_index": int(row.get("page_index") or 0),
        "split": row.get("split"),
        "eval_set": row.get("eval_set"),
        "tiles": tiles,
        "blank": False,
        "matcher": _PageMatcher(dets, instances, class_names=class_set),
    }


def _blank_keep(rec: dict, threshold: float, class_cutoffs: dict[str, float] | None) -> np.ndarray:
    if class_cutoffs:
        th = np.array([float(class_cutoffs.get(c, threshold)) for c in rec["cls"]], dtype=np.float64)
        return rec["conf"] >= th
    return rec["conf"] >= float(threshold)


def _score_point(records: list[dict], threshold: float, class_cutoffs: dict[str, float] | None = None,
                 *, detail: bool = False) -> dict:
    """Every page scored at one cutoff (``class_cutoffs`` override it per
    class): blank-page false detections (per class too), labelled-page
    matches, F0.5 and the false-positive-first weighted F0.25 ``fp_score``."""
    blank = [r for r in records if r["blank"]]
    comp = [r for r in records if not r["blank"]]
    blank_fp = 0
    pages_with_fp = 0
    by_class: dict[str, int] = {}
    for rec in blank:
        keep = _blank_keep(rec, threshold, class_cutoffs)
        n = int(keep.sum())
        blank_fp += n
        pages_with_fp += int(n > 0)
        for i in np.flatnonzero(keep):
            by_class[rec["cls"][int(i)]] = by_class.get(rec["cls"][int(i)], 0) + 1
    agg = _aggregate_complete_scores([r["matcher"].score(r["matcher"].keep(threshold, class_cutoffs))
                                      for r in comp])
    n_blank = len(blank)
    b2 = FP_SCORE_BETA * FP_SCORE_BETA
    fp_labelled = agg["detections"] - agg["tp_exact_class"]
    all_dets = agg["detections"] + blank_fp
    precision = agg["tp_exact_class"] / all_dets if all_dets else 0.0
    recall = agg["recall_exact"]
    f05 = (1.25 * precision * recall / (0.25 * precision + recall)) if (precision + recall) > 0 else 0.0
    weighted_fp = BLANK_FP_WEIGHT * blank_fp + fp_labelled
    denom = agg["tp_exact_class"] + weighted_fp
    precision_weighted = (agg["tp_exact_class"] / denom) if denom > 0 else 0.0
    fp_score = (
        ((1.0 + b2) * precision_weighted * recall) / (b2 * precision_weighted + recall)
        if (precision_weighted + recall) > 0
        else 0.0
    )
    point = {
        "threshold": threshold,
        "blank_fp": blank_fp,
        "blank_fp_per_page": round(blank_fp / n_blank, 4) if n_blank else 0.0,
        "gt_total": agg["gt_total"],
        "found": agg["tp_exact_class"],
        "wrong_class": agg["tp_wrong_class"],
        "labelled_fp": fp_labelled,
        "precision": round(precision, 5),
        "precision_weighted": round(precision_weighted, 5),
        "recall": round(recall, 5),
        "f05": round(f05, 5),
        "fp_score": round(fp_score, 5),
        "blank_pages_with_fp": pages_with_fp,
        "blank_fp_by_class": dict(sorted(by_class.items(), key=lambda kv: (-kv[1], kv[0]))),
    }
    if class_cutoffs is not None:
        point["class_cutoffs"] = dict(class_cutoffs)
    if detail:
        point.update(
            blank_pages=n_blank,
            complete_pages=len(comp),
            fp_near_gt=agg["fp_near_gt"],
            fp_off_label=agg["fp_off_label"],
            fn_missed=agg["fn_missed"],
        )
        point["uncertainty"] = _uncertainty(point, documents=len({r["document_id"] for r in records}))
    return point


def _sweep(records: list[dict], thresholds=SWEEP_THRESHOLDS) -> list[dict]:
    return [_score_point(records, t) for t in thresholds]


def threshold_sweep(blank_confs: list[list],
                    complete: list[tuple[list[dict], list[dict]]],
                    class_set: set[str],
                    thresholds=SWEEP_THRESHOLDS) -> list[dict]:
    """Pages scored at every cutoff in ``thresholds``: blank-page false
    detections (``blank_fp_by_class`` when blank entries are detection dicts
    rather than bare scores), labelled-page matches (``score_complete_page``),
    unweighted F0.5, and false-positive-first weighted F0.25 (``fp_score``)."""
    records = [_blank_record({}, confs) for confs in blank_confs]
    records += [_complete_record({}, dets, gts, class_set) for dets, gts in complete]
    return _sweep(records, thresholds)


def _ceil4(x: float) -> float:
    return math.ceil(round(x * 10000.0, 6)) / 10000.0


def _clear_cut(score: float, margin: float) -> float:
    """Lowest 4-decimal cutoff at least ``margin`` above ``score`` and strictly above it."""
    return max(_ceil4(score + margin), _ceil4(score + 1e-4))


def _blank_max(records: list[dict]) -> float:
    return max((float(r["conf"].max()) for r in records if r["blank"] and len(r["conf"])), default=0.0)


def _point_key(r: dict):
    return (r["fp_score"], -r["blank_fp"], -r["labelled_fp"], r["threshold"])


def _recall_ok(r: dict, min_recall: float) -> bool:
    return r["gt_total"] > 0 and r["found"] > 0 and r["recall"] >= min_recall


def choose_operating_point(records: list[dict], *, margin: float = DEFAULT_BLANK_MARGIN,
                           min_recall: float = DEFAULT_MIN_RECALL) -> dict:
    """Cutoff chosen on the SELECTION pages.

    ``blank_max_conf`` is the highest score on any blank page (detections are
    only kept down to ``SWEEP_FLOOR``, so with none the bound is the floor).
    Candidate cutoffs are the sweep grid plus ``blank max + margin``. Tiers:

    2: the best ``fp_score`` cutoff that clears the blank maximum by ``margin``
       and reaches ``min_recall`` (with at least one symbol found);
    1: no cutoff does both: the fewest-blank-FP cutoff reaching ``min_recall``
       (``margin_ok`` false);
    0: nothing reaches ``min_recall`` (or there are no labelled pages): never
       saved as ``best_real.pt`` and no recommended cutoff.

    ``real_score`` orders tiers strictly: tier 2 = fp_score (0..1), tier 1 =
    fp_score - 1 (-1..0), tier 0 = best recall - 2 (-2..-1)."""
    blank_max = _blank_max(records)
    blank_bound = blank_max if blank_max > 0 else SWEEP_FLOOR
    need = blank_bound + margin
    margin_point = _clear_cut(blank_bound, margin)
    grid = set(SWEEP_THRESHOLDS)
    if SWEEP_FLOOR <= margin_point <= 0.999:
        grid.add(margin_point)
    sweep = _sweep(records, sorted(grid))
    clear = [r for r in sweep if r["threshold"] >= need - 1e-9 and (blank_max == 0 or r["threshold"] > blank_max)]
    ok = [r for r in clear if _recall_ok(r, min_recall)]
    reached = [r for r in sweep if _recall_ok(r, min_recall)]
    if ok:
        tier, chosen = 2, max(ok, key=_point_key)
    elif reached:
        tier, chosen = 1, max(reached, key=lambda r: (-r["blank_fp"], r["fp_score"], -r["labelled_fp"], r["threshold"]))
    else:
        tier, chosen = 0, max(clear or sweep, key=_point_key)
    if tier == 2:
        real_score = chosen["fp_score"]
    elif tier == 1:
        real_score = chosen["fp_score"] - 1.0
    else:
        real_score = max((r["recall"] for r in sweep), default=0.0) - 2.0
    labelled = any(not r["blank"] for r in records) and sweep and sweep[0]["gt_total"] > 0
    if tier == 0:
        reason = ("no fully labelled selection pages: a model that finds nothing would score best"
                  if not labelled else
                  f"no cutoff reaches the minimum recall {min_recall} on the selection pages")
    elif tier == 1:
        reason = (f"no cutoff clears the highest blank-page score {blank_max:.4f} by {margin} "
                  f"and keeps recall >= {min_recall}")
    else:
        reason = None
    return {
        "tier": tier,
        "eligible": tier > 0,
        "reason": reason,
        "chosen": chosen,
        "real_score": round(float(real_score), 6),
        "blank_max_conf": round(blank_max, 4),
        "blank_max_conf_bound": round(blank_bound, 4),
        "margin": margin,
        "min_recall": min_recall,
        "margin_cutoff": margin_point,
        "margin_gap": round(float(chosen["threshold"]) - blank_bound, 4),
        "margin_ok": tier == 2,
        "sweep_with_margin_point": sweep,
    }


def per_class_cutoffs(records: list[dict], *, margin: float = DEFAULT_BLANK_MARGIN,
                      min_recall: float = DEFAULT_MIN_RECALL) -> dict:
    """Per-class cutoffs with zero blank-page false detections on these
    (SELECTION) pages, each clearing its class's highest blank-page score by
    ``margin``: class cutoff = max(base, class blank max + margin), for the
    base (>= ``SWEEP_FLOOR + margin``, so classes never seen on a blank page
    also clear the floor) with the best ``fp_score`` reaching ``min_recall``.
    A class cutoff above 1 disables that class."""
    class_max: dict[str, float] = {}
    for rec in records:
        if not rec["blank"]:
            continue
        for c, v in zip(rec["cls"], rec["conf"]):
            class_max[c] = max(class_max.get(c, 0.0), float(v))
    raised = {c: _clear_cut(v, margin) for c, v in sorted(class_max.items())}
    bases = [t for t in SWEEP_THRESHOLDS if t >= SWEEP_FLOOR + margin - 1e-9]
    best = None
    for base in bases:
        cut = {c: v for c, v in raised.items() if v > base}
        point = _score_point(records, base, cut)
        if point["blank_fp"]:
            continue                            # cannot happen; kept as a guard
        if _recall_ok(point, min_recall) and (best is None or _point_key(point) > _point_key(best)):
            best = point
    return {
        "base": best["threshold"] if best else None,
        "class_cutoffs": best["class_cutoffs"] if best else None,
        "point": best,
        "blank_max_by_class": {c: round(v, 4) for c, v in sorted(class_max.items(), key=lambda kv: -kv[1])},
    }


# --------------------------------------------------------------------------
# Inference
# --------------------------------------------------------------------------

def _read_image(path: str) -> np.ndarray:
    img = cv2.imread(path, cv2.IMREAD_COLOR)
    if img is None:
        raise FileNotFoundError(path)
    return img


def _infer_rows(model, rows: list[dict], *, names: dict[int, str], class_set: set[str], imgsz: int,
                overlap: int, batch_size: int, device) -> list[dict]:
    """Records for ``rows`` (each page inferred once at ``SWEEP_FLOOR``)."""
    out = []
    for row in rows:
        dets, n_tiles = predict_full_page_torch(
            model, _read_image(row["image_path"]), names=names, imgsz=imgsz, overlap=overlap,
            confidence=SWEEP_FLOOR, batch_size=batch_size, device=device, max_raw=MAX_RAW_PER_PAGE,
        )
        out.append(_blank_record(row, dets, n_tiles) if row["verified_no_symbols"]
                   else _complete_record(row, dets, row["instances"], class_set, n_tiles))
    return out


def _set_report(records: list[dict], *, role: str, threshold: float | None,
                class_base: float | None, class_cutoffs: dict[str, float] | None,
                available: dict, sweep: list[dict] | None = None) -> dict:
    """One page set scored over the sweep, at the selection-chosen cutoff and
    at the selection per-class cutoffs, overall and per drawing."""
    blank = [r for r in records if r["blank"]]
    report = {
        "role": role,
        "used_for_selection": role == "selection",
        "evaluated": True,
        "documents": len({r["document_id"] for r in records}),
        "blank_pages": len(blank),
        "complete_pages": len(records) - len(blank),
        "available_blank_pages": available.get("available_blank_pages"),
        "available_complete_pages": available.get("available_complete_pages"),
        "blank_max_conf": round(_blank_max(records), 4),
        "sweep": sweep if sweep is not None else _sweep(records),
    }
    report["truth_symbols"] = report["sweep"][0]["gt_total"] if report["sweep"] else 0
    report["blank_fp_classes_by_cutoff"] = {
        f"{t:.2f}": _score_point(blank, t)["blank_fp_by_class"] for t in CLASS_REPORT_CUTOFFS}
    report["at_chosen"] = _score_point(records, threshold, detail=True) if threshold is not None else None
    report["at_class_cutoffs"] = (_score_point(records, class_base, class_cutoffs, detail=True)
                                  if class_base is not None and class_cutoffs is not None else None)
    per_doc: dict[str, dict] = {}
    for doc in sorted({r["document_id"] for r in records}):
        sub = [r for r in records if r["document_id"] == doc]
        entry = {
            "blank_pages": sum(r["blank"] for r in sub),
            "complete_pages": sum(not r["blank"] for r in sub),
            "blank_max_conf": round(_blank_max(sub), 4),
        }
        for name, base, cut in (("at_chosen", threshold, None),
                                ("at_class_cutoffs", class_base, class_cutoffs)):
            if base is None or (name == "at_class_cutoffs" and cut is None):
                continue
            p = _score_point(sub, base, cut)
            entry[name] = {k: p[k] for k in ("threshold", "blank_fp", "blank_pages_with_fp", "gt_total",
                                              "found", "wrong_class", "labelled_fp", "precision", "recall",
                                              "fp_score", "blank_fp_by_class")}
            n_b = entry["blank_pages"]
            entry[name]["blank_fp_per_page_upper95"] = (
                round(poisson_upper_bound(p["blank_fp"]) / n_b, 5) if n_b else None)
        per_doc[doc or "?"] = entry
    report["per_document"] = per_doc
    return report


def _legacy_blank_summary(records: list[dict]) -> dict:
    """The pre-split blank-page keys, on the given (selection) records."""
    rows = []
    blank_fp_classes: dict[str, int] = {}
    for rec in (r for r in records if r["blank"]):
        conf = rec["conf"]
        for c, v in zip(rec["cls"], conf):
            if v >= 0.50:
                blank_fp_classes[c] = blank_fp_classes.get(c, 0) + 1
        rows.append({
            "document_id": rec["document_id"],
            "page_index": rec["page_index"],
            "split": rec["split"],
            "tiles": rec["tiles"],
            "fp_025": int((conf >= 0.25).sum()),
            "fp_050": int((conf >= 0.50).sum()),
            "fp_075": int((conf >= 0.75).sum()),
            "max_conf": round(float(conf.max()) if len(conf) else 0.0, 4),
        })
    n = len(rows)
    t25 = sum(r["fp_025"] for r in rows)
    t50 = sum(r["fp_050"] for r in rows)
    return {
        "blank_pages": n,
        "blank_fp_025_total": t25,
        "blank_fp_025_mean": round(t25 / n, 4) if n else 0.0,
        "blank_fp_025_max": max((r["fp_025"] for r in rows), default=0),
        "blank_fp_050_total": t50,
        "blank_fp_050_mean": round(t50 / n, 4) if n else 0.0,
        "blank_fp_075_total": sum(r["fp_075"] for r in rows),
        "blank_zero_fp_pages_025": sum(1 for r in rows if r["fp_025"] == 0),
        "blank_zero_fp_pages_050": sum(1 for r in rows if r["fp_050"] == 0),
        "blank_max_conf": round(max((r["max_conf"] for r in rows), default=0.0), 4),
        "worst_blank_pages": sorted(rows, key=lambda r: (-r["fp_025"], -r["max_conf"], r["document_id"],
                                                         r["page_index"]))[:5],
        "blank_fp_classes_050": dict(sorted(blank_fp_classes.items(), key=lambda kv: -kv[1])[:15]),
    }


def _stress_variants(image: np.ndarray, *, imgsz: int, overlap: int):
    """(name, image): tile grid shifted by half a tile stride, and the page
    re-rendered (resized) at each ``STRESS_SCALES`` factor."""
    shift = max(1, (imgsz - overlap) // 2)
    yield "shift_half_stride", cv2.copyMakeBorder(image, shift, 0, shift, 0, cv2.BORDER_CONSTANT,
                                                 value=(255, 255, 255))
    for s in STRESS_SCALES:
        interp = cv2.INTER_AREA if s < 1 else cv2.INTER_LINEAR
        yield f"scale_{s:g}", cv2.resize(image, None, fx=s, fy=s, interpolation=interp)


def stress_blank_pages(model, rows: list[dict], *, names: dict[int, str], imgsz: int, overlap: int,
                       batch_size: int, device, threshold: float | None,
                       class_base: float | None = None,
                       class_cutoffs: dict[str, float] | None = None) -> dict:
    """Robustness pass on SELECTION blank pages (reported, never selects):
    blank false detections at the chosen cutoff (and per-class cutoffs) after
    shifting the tile grid and re-rendering at 0.75x / 1.5x."""
    variants: dict[str, list[dict]] = {}
    for row in rows:
        img = _read_image(row["image_path"])
        for name, var in _stress_variants(img, imgsz=imgsz, overlap=overlap):
            dets, n_tiles = predict_full_page_torch(
                model, var, names=names, imgsz=imgsz, overlap=overlap, confidence=SWEEP_FLOOR,
                batch_size=batch_size, device=device, max_raw=MAX_RAW_PER_PAGE)
            variants.setdefault(name, []).append(_blank_record(row, dets, n_tiles))
    out: dict = {"pages": len(rows), "variants": {}}
    for name, recs in variants.items():
        entry = {"blank_max_conf": round(_blank_max(recs), 4),
                 "tiles": sum(int(r["tiles"] or 0) for r in recs)}
        if threshold is not None:
            p = _score_point(recs, threshold, detail=True)
            entry["at_chosen"] = {k: p[k] for k in ("threshold", "blank_fp", "blank_pages_with_fp",
                                                     "blank_fp_by_class")}
            entry["at_chosen"]["blank_fp_per_page_upper95"] = p["uncertainty"]["blank_fp_per_page_upper95"]
        if class_base is not None and class_cutoffs is not None:
            p = _score_point(recs, class_base, class_cutoffs)
            entry["at_class_cutoffs"] = {k: p[k] for k in ("blank_fp", "blank_pages_with_fp",
                                                            "blank_fp_by_class")}
        out["variants"][name] = entry
    vs = out["variants"]
    if vs and threshold is not None:
        worst = max(vs, key=lambda n: (vs[n]["at_chosen"]["blank_fp"], vs[n]["blank_max_conf"]))
        out["worst_variant"] = worst
        out["worst_blank_fp_at_chosen"] = vs[worst]["at_chosen"]["blank_fp"]
    if vs and class_cutoffs is not None and class_base is not None:
        out["worst_blank_fp_at_class_cutoffs"] = max(v["at_class_cutoffs"]["blank_fp"] for v in vs.values())
    out["worst_blank_max_conf"] = max((v["blank_max_conf"] for v in vs.values()), default=0.0)
    return out


# --------------------------------------------------------------------------
# Evaluation
# --------------------------------------------------------------------------

def _norm_names(names) -> dict[int, str]:
    return {int(k): str(v) for k, v in names.items()}


def evaluate_selection(model, selected: dict, *, names: dict[int, str], imgsz: int = 512,
                       overlap: int = 192, batch_size: int = 8, device=None,
                       margin: float = DEFAULT_BLANK_MARGIN,
                       min_recall: float = DEFAULT_MIN_RECALL) -> tuple[dict, dict]:
    """Score the SELECTION pages and choose the cutoffs. Returns
    (metrics, state) where ``state`` keeps the inferred records for
    ``add_report_sets`` / ``add_stress``."""
    norm_names = _norm_names(names)
    class_set = set(norm_names.values())
    sel = selected["sets"]["selection"] if "sets" in selected else selected
    rows = list(sel["blank_pages"]) + list(sel["complete_pages"])
    records = _infer_rows(model, rows, names=norm_names, class_set=class_set, imgsz=imgsz,
                          overlap=overlap, batch_size=batch_size, device=device)
    choice = choose_operating_point(records, margin=margin, min_recall=min_recall)
    per_class = per_class_cutoffs(records, margin=margin, min_recall=min_recall)
    chosen = choice["chosen"]
    threshold = chosen["threshold"] if choice["eligible"] else None
    class_ok = choice["eligible"] and per_class["base"] is not None
    class_base = per_class["base"] if class_ok else None
    class_cut = per_class["class_cutoffs"] if class_ok else None
    report = _set_report(records, role="selection", threshold=chosen["threshold"],
                         class_base=per_class["base"], class_cutoffs=per_class["class_cutoffs"],
                         available=sel, sweep=[r for r in choice["sweep_with_margin_point"]
                                               if r["threshold"] in SWEEP_THRESHOLDS])
    sweep = report["sweep"]
    comp = [r for r in records if not r["blank"]]
    summary_25 = _aggregate_complete_scores([r["matcher"].score(r["matcher"].keep(0.25)) for r in comp])
    summary_50 = _aggregate_complete_scores([r["matcher"].score(r["matcher"].keep(0.50)) for r in comp])
    best = max(sweep, key=_point_key) if sweep else None
    budget = [r for r in sweep if r["blank_fp_per_page"] <= BLANK_FP_BUDGET]
    at_budget = max(budget, key=lambda r: (r["fp_score"], r["recall"], -r["blank_fp"], r["threshold"])) if budget else None
    zero = [r for r in sweep if r["blank_fp"] == 0]
    at_zero = max(zero, key=lambda r: (r["fp_score"], r["recall"], -r["labelled_fp"], r["threshold"])) if zero else None
    metrics = {
        "metrics_scope": METRICS_SCOPE,
        "page_pool": selected.get("page_pool"),
        "val_test_independent": selected.get("val_test_independent"),
        **_legacy_blank_summary(records),
        "available_blank_pages": sel.get("available_blank_pages"),
        "complete_pages": len(comp),
        "available_complete_pages": sel.get("available_complete_pages"),
        **summary_25,
        "complete_050": summary_50,
        "best_threshold": best,                 # best fp_score on the grid, no margin (as before)
        "at_blank_fp_budget": at_budget,
        "at_zero_blank_fp": at_zero,
        "blank_fp_budget_per_page": BLANK_FP_BUDGET,
        "blank_fp_weight": BLANK_FP_WEIGHT,
        "fp_score_beta": FP_SCORE_BETA,
        "sweep": sweep,
        "chosen_threshold": chosen,
        "selection_tier": choice["tier"],
        "eligible": choice["eligible"],
        "ineligible_reason": choice["reason"] if not choice["eligible"] else None,
        "selection_note": choice["reason"],
        "margin": margin,
        "min_recall": min_recall,
        "blank_max_conf_bound": choice["blank_max_conf_bound"],
        "margin_cutoff": choice["margin_cutoff"],
        "blank_margin_gap": choice["margin_gap"],
        "margin_ok": choice["margin_ok"],
        "recommended_confidence": threshold,
        "recommended_class_base_confidence": class_base,
        "recommended_class_confidence": class_cut,
        "per_class_point": per_class["point"],
        "blank_max_by_class": per_class["blank_max_by_class"],
        "selection": report,
        "test": {"role": "test", "used_for_selection": False, "evaluated": False},
        "cross_document": {"role": "cross_document", "used_for_selection": False, "evaluated": False},
        "stress": None,
        "real_score": choice["real_score"],
    }
    state = {"records": {"selection": records}, "names": norm_names, "class_set": class_set,
             "threshold": chosen["threshold"], "class_base": per_class["base"],
             "class_cutoffs": per_class["class_cutoffs"]}
    return metrics, state


def add_report_sets(model, selected: dict, metrics: dict, state: dict, *, imgsz: int = 512,
                    overlap: int = 192, batch_size: int = 8, device=None,
                    sets=("test", "cross_document")) -> dict:
    """Score the locked ``test`` and ``cross_document`` sets at the cutoffs the
    SELECTION pages chose (reported only; nothing here feeds selection)."""
    for name in sets:
        sel = selected["sets"][name]
        rows = list(sel["blank_pages"]) + list(sel["complete_pages"])
        if not rows:
            metrics[name] = {"role": name, "used_for_selection": False, "evaluated": False,
                             "reason": "no pages", **{k: sel[k] for k in ("available_blank_pages",
                                                                           "available_complete_pages")}}
            continue
        records = _infer_rows(model, rows, names=state["names"], class_set=state["class_set"], imgsz=imgsz,
                              overlap=overlap, batch_size=batch_size, device=device)
        state["records"][name] = records
        metrics[name] = _set_report(records, role=name, threshold=state["threshold"],
                                    class_base=state["class_base"], class_cutoffs=state["class_cutoffs"],
                                    available=sel)
    return metrics


def add_stress(model, selected: dict, metrics: dict, state: dict, *, imgsz: int = 512,
               overlap: int = 192, batch_size: int = 8, device=None) -> dict:
    sel = selected["sets"]["selection"] if "sets" in selected else selected
    metrics["stress"] = stress_blank_pages(
        model, list(sel["blank_pages"]), names=state["names"], imgsz=imgsz, overlap=overlap,
        batch_size=batch_size, device=device, threshold=state["threshold"],
        class_base=state["class_base"], class_cutoffs=state["class_cutoffs"])
    metrics["stress"]["used_for_selection"] = False
    return metrics


def evaluate_real_pages(
    model,
    manifest_path: Path,
    *,
    names: dict[int, str],
    imgsz: int = 512,
    overlap: int = 192,
    batch_size: int = 8,
    max_blank_pages: int = 24,
    max_complete_pages: int = 24,
    device=None,
    margin: float = DEFAULT_BLANK_MARGIN,
    min_recall: float = DEFAULT_MIN_RECALL,
    report_sets=("test", "cross_document"),
    stress: bool = False,
    selected: dict | None = None,
) -> dict:
    """Evaluate ``model`` deterministically across real blank and fully-labeled sheets.

    Every detection down to ``SWEEP_FLOOR`` is kept, and the pages are scored
    at each cutoff in ``SWEEP_THRESHOLDS``. Precision weights blank-page false
    positives by ``BLANK_FP_WEIGHT`` alongside labelled-page FPs.

    Only the SELECTION pages choose: ``chosen_threshold`` / ``recommended_confidence``
    (``choose_operating_point``: clears the highest blank-page score by
    ``margin`` and reaches ``min_recall``), ``recommended_class_confidence``
    (``per_class_cutoffs``) and ``real_score``. ``report_sets`` are scored at
    those cutoffs and reported; ``stress`` adds the robustness pass."""
    if selected is None:
        selected = select_real_eval_pages(manifest_path, max_blank_pages=max_blank_pages,
                                          max_complete_pages=max_complete_pages)
    kw = dict(imgsz=imgsz, overlap=overlap, batch_size=batch_size, device=device)
    metrics, state = evaluate_selection(model, selected, names=names, margin=margin,
                                        min_recall=min_recall, **kw)
    if report_sets:
        add_report_sets(model, selected, metrics, state, sets=tuple(report_sets), **kw)
    if stress:
        add_stress(model, selected, metrics, state, **kw)
    return metrics


def _fmt_point(p: dict | None) -> str:
    if not p:
        return "none"
    return (f"{p['threshold']:.2f}: P={p['precision']:.3f} R={p['recall']:.3f} "
            f"found={p['found']}/{p['gt_total']} blankFP={p['blank_fp']} labelledFP={p['labelled_fp']}")


def _fmt_set(rep: dict | None) -> str:
    if not rep or not rep.get("evaluated"):
        return "not scored"
    p = rep.get("at_chosen")
    unc = (p or {}).get("uncertainty") or {}
    return (f"{rep['documents']}doc {rep['blank_pages']}blank {rep['complete_pages']}labelled "
            f"gt={rep['truth_symbols']} @chosen {_fmt_point(p)} "
            f"blankFP/page<={unc.get('blank_fp_per_page_upper95')} (95%)")


def _names_dict(raw_names) -> dict[int, str]:
    if isinstance(raw_names, dict):
        return {int(k): str(v) for k, v in raw_names.items()}
    if isinstance(raw_names, (list, tuple)):
        return {i: str(v) for i, v in enumerate(raw_names)}
    raise ValueError(f"Unsupported class names structure: {type(raw_names)}")


def build_real_eval_callback(
    *,
    manifest_path: Path,
    every_epochs: int = 5,
    max_blank_pages: int = 24,
    max_complete_pages: int = 24,
    imgsz: int = 512,
    overlap: int = 192,
    batch_size: int = 8,
    margin: float | None = None,
    min_recall: float | None = None,
    stress_every: int | None = None,
    report_every: int | None = None,
):
    """Create an Ultralytics ``on_fit_epoch_end`` callback for periodic real-page evaluation.

    Each evaluation scores the SELECTION pages, sets
    ``trainer.real_eval_metrics`` (``real_score`` = the selection score) and
    appends to ``real_eval_metrics.jsonl``. ``weights/best_real.pt`` is saved
    when ``real_score`` improves and the checkpoint is eligible (reaches
    ``min_recall``; a model that finds nothing never is). The locked ``test``
    and ``cross_document`` sets are scored for each new ``best_real.pt``, at
    the last epoch and every ``report_every`` evaluations (0: only those).
    ``stress_every`` > 0 runs the robustness pass every N evaluations and at
    the last epoch (0: off; it re-infers every selection blank page 3 times,
    about 3.6x the blank-page cost).

    Defaults come from ``BPS_REAL_EVAL_MARGIN`` (0.05),
    ``BPS_REAL_EVAL_MIN_RECALL`` (0.05), ``BPS_REAL_EVAL_STRESS_EVERY`` (0)
    and ``BPS_REAL_EVAL_REPORT_EVERY`` (0)."""
    if every_epochs < 1:
        raise ValueError(f"every_epochs must be >= 1, got {every_epochs}")
    margin = _env_float("BPS_REAL_EVAL_MARGIN", DEFAULT_BLANK_MARGIN) if margin is None else float(margin)
    min_recall = (_env_float("BPS_REAL_EVAL_MIN_RECALL", DEFAULT_MIN_RECALL)
                  if min_recall is None else float(min_recall))
    stress_every = _env_int("BPS_REAL_EVAL_STRESS_EVERY", 0) if stress_every is None else int(stress_every)
    report_every = _env_int("BPS_REAL_EVAL_REPORT_EVERY", 0) if report_every is None else int(report_every)
    if margin < 0 or not 0 <= min_recall <= 1 or stress_every < 0 or report_every < 0:
        raise ValueError("margin >= 0, 0 <= min_recall <= 1, stress_every >= 0, report_every >= 0")
    manifest_path = Path(manifest_path).resolve()
    state = {"best_score": None, "evaluations": 0, "selected": None}

    def on_fit_epoch_end(trainer) -> None:
        import torch

        epoch_0 = int(getattr(trainer, "epoch", 0))
        total_epochs = int(getattr(trainer, "epochs", 0) or 0)
        is_first = epoch_0 == 0
        is_periodic = ((epoch_0 + 1) % every_epochs) == 0
        is_last = total_epochs > 0 and (epoch_0 + 1) >= total_epochs
        if not (is_first or is_periodic or is_last):
            return

        ema = getattr(trainer, "ema", None)
        eval_model = ema.ema if (ema is not None and getattr(ema, "ema", None) is not None) else trainer.model
        names = _names_dict(getattr(eval_model, "names", None) or trainer.data["names"])
        kw = dict(imgsz=imgsz, overlap=overlap, batch_size=batch_size)

        try:
            if state["selected"] is None:
                state["selected"] = select_real_eval_pages(
                    manifest_path, max_blank_pages=max_blank_pages, max_complete_pages=max_complete_pages)
            selected = state["selected"]
            state["evaluations"] += 1
            n_eval = state["evaluations"]
            metrics, ev_state = evaluate_selection(eval_model, selected, names=names, margin=margin,
                                                   min_recall=min_recall, **kw)
            improved = metrics["eligible"] and (state["best_score"] is None
                                                or metrics["real_score"] > state["best_score"])
            if improved or is_last or (report_every and n_eval % report_every == 0):
                add_report_sets(eval_model, selected, metrics, ev_state, **kw)
            if stress_every and (n_eval % stress_every == 0 or is_last):
                add_stress(eval_model, selected, metrics, ev_state, **kw)
            metrics["epoch"] = epoch_0 + 1
            metrics["evaluation"] = n_eval
            metrics["timestamp_utc"] = datetime.now(timezone.utc).isoformat()
            metrics["best_real_saved"] = bool(improved)
            trainer.real_eval_metrics = metrics

            save_dir = Path(trainer.save_dir)
            save_dir.mkdir(parents=True, exist_ok=True)
            jsonl_path = save_dir / "real_eval_metrics.jsonl"
            with jsonl_path.open("a", encoding="utf-8") as handle:
                handle.write(json.dumps(metrics, ensure_ascii=False) + "\n")
            latest_path = save_dir / "real_eval_latest.json"
            tmp_latest = latest_path.with_name(f".{latest_path.name}.{os.getpid()}.tmp")
            tmp_latest.write_text(json.dumps(metrics, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            os.replace(tmp_latest, latest_path)

            if improved:
                state["best_score"] = metrics["real_score"]
                weights_dir = save_dir / "weights"
                weights_dir.mkdir(parents=True, exist_ok=True)
                best_real_path = weights_dir / "best_real.pt"
                ckpt_model = copy.deepcopy(eval_model).half().cpu().eval()
                if hasattr(ckpt_model, "criterion"):
                    ckpt_model.criterion = None
                train_args = dict(vars(trainer.args)) if hasattr(trainer, "args") else {}
                train_args.setdefault("model", "yolo26n-p2.yaml")
                train_args.setdefault("pretrained", False)
                ckpt = {
                    "epoch": epoch_0,
                    "best_fitness": float(metrics["real_score"]),
                    "model": ckpt_model,
                    "ema": None,
                    "updates": getattr(ema, "updates", None) if ema is not None else None,
                    "optimizer": None,
                    "train_args": train_args,
                    "date": metrics["timestamp_utc"],
                    "real_eval": metrics,
                }
                tmp_pt = best_real_path.with_name(f".{best_real_path.name}.{os.getpid()}.tmp")
                torch.save(ckpt, tmp_pt)
                os.replace(tmp_pt, best_real_path)

            c50 = metrics["complete_050"]
            stress = metrics.get("stress") or {}
            print(
                f"[RealPageEval ep {metrics['epoch']}] SELECTION "
                f"blank({metrics['blank_pages']}p): fp@0.25={metrics['blank_fp_025_total']} (mean {metrics['blank_fp_025_mean']:.2f}, 0fp={metrics['blank_zero_fp_pages_025']}), "
                f"fp@0.50={metrics['blank_fp_050_total']} (mean {metrics['blank_fp_050_mean']:.2f}) max={metrics['blank_max_conf']:.3f} | "
                f"complete({metrics['complete_pages']}p, gt={metrics['gt_total']}): "
                f"@0.25[exact={metrics['tp_exact_class']} wrong_cls={metrics['tp_wrong_class']} off_fp={metrics['fp_off_label']} F0.5={metrics['f05_exact']:.3f}] "
                f"@0.50[exact={c50['tp_exact_class']} wrong_cls={c50['tp_wrong_class']} off_fp={c50['fp_off_label']} F0.5={c50['f05_exact']:.3f}] "
                f"| chosen {_fmt_point(metrics['chosen_threshold'])} tier={metrics['selection_tier']} "
                f"gap={metrics['blank_margin_gap']:+.3f} (margin {metrics['margin']})"
                f" | no-margin best {_fmt_point(metrics.get('best_threshold'))}"
                f" | 0 blank FP: {_fmt_point(metrics.get('at_zero_blank_fp'))}"
                f" | TEST {_fmt_set(metrics.get('test'))}"
                f" | CROSS-DOC {_fmt_set(metrics.get('cross_document'))}"
                + (f" | STRESS worst blankFP@chosen={stress.get('worst_blank_fp_at_chosen')} ({stress.get('worst_variant')})"
                   if stress else "")
                + f" score={metrics['real_score']:.4f}{' *BEST_REAL*' if improved else ''}"
                + (f" [not saved: {metrics['ineligible_reason']}]" if not metrics["eligible"] else ""),
                flush=True,
            )
        finally:
            if torch.cuda.is_available():
                torch.cuda.empty_cache()

    return on_fit_epoch_end
