"""Deterministic periodic evaluation on real blank and fully-labeled blueprint pages.

Runs whole-page scale-1.0 tiled inference (no blank-tile skipping, no sampling,
no augmentation) periodically during Phase-2 post-training so checkpoints can be
audited and selected for low false positives on blank sheets, low off-label FPs
on complete sheets, and accurate device classification.
"""
from __future__ import annotations

import copy
from datetime import datetime, timezone
import json
import os
from pathlib import Path

import cv2
import numpy as np

from .dataset_manifest import trusted_training_instance
from .label_schema import iou as _iou, load_training_page, parse_page_ref
from .multiscale import plan_pyramid_tiles, touches_inner_edge
from .page_detector import LocalPageDetector, decode_onnx_tile_outputs
from .pseudolabel import consolidate_predictions


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


def select_real_eval_pages(
    manifest_path: Path,
    *,
    max_blank_pages: int = 24,
    max_complete_pages: int = 24,
) -> dict[str, list[dict]]:
    """Deterministically select real verified-blank and fully-labeled pages across splits/PDFs."""
    manifest = json.loads(Path(manifest_path).read_text(encoding="utf-8"))
    splits = manifest.get("splits") or {}
    blank_candidates: list[dict] = []
    complete_candidates: list[dict] = []
    seen_refs: set[str] = set()

    for split in ("val", "test", "train"):
        for raw_ref in splits.get(split) or []:
            ref = str(raw_ref)
            if ref in seen_refs:
                continue
            seen_refs.add(ref)
            page = load_training_page(ref)
            if not is_real_source_type(page.get("source_type")):
                continue
            is_blank = page.get("verified_no_symbols") is True and not page.get("instances")
            is_complete = (
                page.get("annotation_completeness_verified") is True
                and not page.get("verified_no_symbols")
            )
            if not is_blank and not is_complete:
                continue
            row = {
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
            if is_blank:
                blank_candidates.append(row)
            else:
                complete_candidates.append(row)

    sort_key = lambda r: (
        0 if r["split"] == "val" else (1 if r["split"] == "test" else 2),
        r["document_id"],
        r["page_index"],
        r["ref"],
    )
    blank_candidates.sort(key=sort_key)
    complete_candidates.sort(key=sort_key)
    return {
        "blank_pages": _pick_even(blank_candidates, max_blank_pages),
        "complete_pages": _pick_even(complete_candidates, max_complete_pages),
        "available_blank_pages": len(blank_candidates),
        "available_complete_pages": len(complete_candidates),
    }


def predict_full_page_torch(
    model,
    image_bgr: np.ndarray,
    *,
    names: dict[int, str],
    imgsz: int = 512,
    overlap: int = 192,
    confidence: float = 0.25,
    batch_size: int = 16,
    device=None,
) -> tuple[list[dict], int]:
    """Deterministic scale-1.0 tiled inference over EVERY tile of a page image."""
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
                out = model(tensor)
                pred_tensor = out[0] if isinstance(out, (tuple, list)) else out
                pred_np = pred_tensor.detach().float().cpu().numpy()
                for idx, tile in enumerate(chunk_tiles):
                    dets = decode_onnx_tile_outputs(
                        pred_np[idx],
                        confidence=confidence,
                        output_layout=output_layout,
                    )
                    for box, cls_id, score in dets:
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
                            "symbol_family": names.get(int(cls_id), str(int(cls_id))),
                            "bbox_xyxy": bb,
                            "confidence": float(score),
                            "source_scale": 1.0,
                        })
        consolidated = (
            consolidate_predictions(
                raw, min_confidence=confidence, cross_class_iou=0.60, containment=0.80
            )
            if raw
            else []
        )
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
    dets = sorted(
        (
            d
            for d in detections
            if float(d.get("confidence", 0.0)) >= conf_threshold
            and d.get("symbol_family") in class_names
        ),
        key=lambda d: (
            -float(d["confidence"]),
            tuple(round(float(v), 4) for v in d["bbox_xyxy"]),
            str(d["symbol_family"]),
        ),
    )
    gts = [
        (list(map(float, g["bbox_xyxy"])), str(g["symbol_family"]))
        for g in gt_instances
        if g.get("symbol_family") in class_names and trusted_training_instance(g)
    ]

    matched_det: set[int] = set()
    matched_gt: set[int] = set()
    tp_exact = 0
    tp_wrong = 0

    for di, det in enumerate(dets):
        dbox = det["bbox_xyxy"]
        dcls = det["symbol_family"]
        best_gi, best_iou = None, match_iou - 1e-12
        for gi, (gbox, gcls) in enumerate(gts):
            if gi in matched_gt or gcls != dcls:
                continue
            ov = float(_iou(dbox, gbox))
            if ov >= match_iou and ov > best_iou:
                best_iou = ov
                best_gi = gi
        if best_gi is not None:
            matched_det.add(di)
            matched_gt.add(best_gi)
            tp_exact += 1

    for di, det in enumerate(dets):
        if di in matched_det:
            continue
        dbox = det["bbox_xyxy"]
        best_gi, best_iou = None, match_iou - 1e-12
        for gi, (gbox, _) in enumerate(gts):
            if gi in matched_gt:
                continue
            ov = float(_iou(dbox, gbox))
            if ov >= match_iou and ov > best_iou:
                best_iou = ov
                best_gi = gi
        if best_gi is not None:
            matched_det.add(di)
            matched_gt.add(best_gi)
            tp_wrong += 1

    fp_near = 0
    fp_off = 0
    for di, det in enumerate(dets):
        if di in matched_det:
            continue
        dbox = det["bbox_xyxy"]
        max_ov = max((float(_iou(dbox, gbox)) for gbox, _ in gts), default=0.0)
        if max_ov >= near_iou:
            fp_near += 1
        else:
            fp_off += 1

    fn_missed = len(gts) - tp_exact - tp_wrong
    return {
        "gt_total": len(gts),
        "detections": len(dets),
        "tp_exact_class": tp_exact,
        "tp_wrong_class": tp_wrong,
        "fp_near_gt": fp_near,
        "fp_off_label": fp_off,
        "fn_missed": fn_missed,
    }


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


def evaluate_real_pages(
    model,
    manifest_path: Path,
    *,
    names: dict[int, str],
    imgsz: int = 512,
    overlap: int = 192,
    batch_size: int = 16,
    max_blank_pages: int = 24,
    max_complete_pages: int = 24,
    device=None,
) -> dict:
    """Evaluate ``model`` deterministically across real blank and fully-labeled sheets."""
    selected = select_real_eval_pages(
        manifest_path,
        max_blank_pages=max_blank_pages,
        max_complete_pages=max_complete_pages,
    )
    norm_names = {int(k): str(v) for k, v in names.items()}
    class_set = set(norm_names.values())

    blank_rows: list[dict] = []
    for row in selected["blank_pages"]:
        img = cv2.imread(row["image_path"], cv2.IMREAD_COLOR)
        if img is None:
            raise FileNotFoundError(row["image_path"])
        dets, n_tiles = predict_full_page_torch(
            model, img, names=norm_names, imgsz=imgsz, overlap=overlap,
            confidence=0.25, batch_size=batch_size, device=device,
        )
        c25 = len(dets)
        c50 = sum(1 for d in dets if float(d["confidence"]) >= 0.50)
        c75 = sum(1 for d in dets if float(d["confidence"]) >= 0.75)
        max_c = max((float(d["confidence"]) for d in dets), default=0.0)
        blank_rows.append({
            "document_id": row["document_id"],
            "page_index": row["page_index"],
            "split": row["split"],
            "tiles": n_tiles,
            "fp_025": c25,
            "fp_050": c50,
            "fp_075": c75,
            "max_conf": round(max_c, 4),
        })

    comp_25_rows: list[dict] = []
    comp_50_rows: list[dict] = []
    for row in selected["complete_pages"]:
        img = cv2.imread(row["image_path"], cv2.IMREAD_COLOR)
        if img is None:
            raise FileNotFoundError(row["image_path"])
        dets, _ = predict_full_page_torch(
            model, img, names=norm_names, imgsz=imgsz, overlap=overlap,
            confidence=0.25, batch_size=batch_size, device=device,
        )
        comp_25_rows.append(
            score_complete_page(dets, row["instances"], class_names=class_set, conf_threshold=0.25)
        )
        comp_50_rows.append(
            score_complete_page(dets, row["instances"], class_names=class_set, conf_threshold=0.50)
        )

    n_blank = len(blank_rows)
    fp_025_total = sum(r["fp_025"] for r in blank_rows)
    fp_050_total = sum(r["fp_050"] for r in blank_rows)
    fp_075_total = sum(r["fp_075"] for r in blank_rows)
    fp_025_mean = (fp_025_total / n_blank) if n_blank else 0.0
    fp_050_mean = (fp_050_total / n_blank) if n_blank else 0.0
    worst_blank = sorted(blank_rows, key=lambda r: (-r["fp_025"], -r["max_conf"], r["document_id"], r["page_index"]))[:5]

    summary_25 = _aggregate_complete_scores(comp_25_rows)
    summary_50 = _aggregate_complete_scores(comp_50_rows)

    n_comp = len(comp_25_rows)
    if summary_25["gt_total"] > 0:
        real_score = summary_25["f05_exact"] / (
            1.0
            + 0.35 * fp_025_mean
            + 0.25 * (summary_25["fp_off_label"] / max(1, n_comp))
            + 0.15 * (summary_25["tp_wrong_class"] / max(1, n_comp))
        )
    else:
        real_score = 1.0 / (1.0 + fp_025_mean)

    return {
        "blank_pages": n_blank,
        "available_blank_pages": selected["available_blank_pages"],
        "blank_fp_025_total": fp_025_total,
        "blank_fp_025_mean": round(fp_025_mean, 4),
        "blank_fp_025_max": max((r["fp_025"] for r in blank_rows), default=0),
        "blank_fp_050_total": fp_050_total,
        "blank_fp_050_mean": round(fp_050_mean, 4),
        "blank_fp_075_total": fp_075_total,
        "blank_zero_fp_pages_025": sum(1 for r in blank_rows if r["fp_025"] == 0),
        "blank_zero_fp_pages_050": sum(1 for r in blank_rows if r["fp_050"] == 0),
        "blank_max_conf": round(max((r["max_conf"] for r in blank_rows), default=0.0), 4),
        "worst_blank_pages": worst_blank,
        "complete_pages": n_comp,
        "available_complete_pages": selected["available_complete_pages"],
        **summary_25,
        "complete_050": summary_50,
        "real_score": round(float(real_score), 6),
    }


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
    batch_size: int = 16,
):
    """Create an Ultralytics ``on_fit_epoch_end`` callback for periodic real-page evaluation."""
    if every_epochs < 1:
        raise ValueError(f"every_epochs must be >= 1, got {every_epochs}")
    manifest_path = Path(manifest_path).resolve()
    state = {"best_score": -1.0}

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

        metrics = evaluate_real_pages(
            eval_model,
            manifest_path,
            names=names,
            imgsz=imgsz,
            overlap=overlap,
            batch_size=batch_size,
            max_blank_pages=max_blank_pages,
            max_complete_pages=max_complete_pages,
        )
        metrics["epoch"] = epoch_0 + 1
        metrics["timestamp_utc"] = datetime.now(timezone.utc).isoformat()

        save_dir = Path(trainer.save_dir)
        save_dir.mkdir(parents=True, exist_ok=True)
        jsonl_path = save_dir / "real_eval_metrics.jsonl"
        with jsonl_path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(metrics, ensure_ascii=False) + "\n")
        latest_path = save_dir / "real_eval_latest.json"
        tmp_latest = latest_path.with_name(f".{latest_path.name}.{os.getpid()}.tmp")
        tmp_latest.write_text(json.dumps(metrics, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        os.replace(tmp_latest, latest_path)

        improved = metrics["real_score"] > state["best_score"]
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
        print(
            f"[RealPageEval ep {metrics['epoch']}] "
            f"blank({metrics['blank_pages']}p): fp@0.25={metrics['blank_fp_025_total']} (mean {metrics['blank_fp_025_mean']:.2f}, 0fp={metrics['blank_zero_fp_pages_025']}), "
            f"fp@0.50={metrics['blank_fp_050_total']} (mean {metrics['blank_fp_050_mean']:.2f}) | "
            f"complete({metrics['complete_pages']}p, gt={metrics['gt_total']}): "
            f"@0.25[exact={metrics['tp_exact_class']} wrong_cls={metrics['tp_wrong_class']} off_fp={metrics['fp_off_label']} F0.5={metrics['f05_exact']:.3f}] "
            f"@0.50[exact={c50['tp_exact_class']} wrong_cls={c50['tp_wrong_class']} off_fp={c50['fp_off_label']} F0.5={c50['f05_exact']:.3f}] "
            f"score={metrics['real_score']:.4f}{' *BEST_REAL*' if improved else ''}",
            flush=True,
        )

    return on_fit_epoch_end
