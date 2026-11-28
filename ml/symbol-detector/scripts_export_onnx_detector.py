"""Export a verified blueprint-only Phase-2 detector checkpoint (.pt) to ONNX.

Runs strictly on CPU so training jobs on the GPU are undisturbed, checks
blueprint-only provenance via ``ssl_transfer.read_blueprint_weights``, exports
an ONNX graph alongside a metadata sidecar (``<output>.json``), and verifies
numerical parity against PyTorch using ``onnxruntime`` on CPU.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
from pathlib import Path

import numpy as np


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _deterministic_test_tensor(imgsz: int, verify_image: Path | None = None) -> np.ndarray:
    if verify_image is not None:
        import cv2

        img = cv2.imread(str(verify_image), cv2.IMREAD_COLOR)
        if img is None:
            raise FileNotFoundError(f"Could not read --verify-image: {verify_image}")
        h, w = img.shape[:2]
        y0 = max(0, (h - imgsz) // 2)
        x0 = max(0, (w - imgsz) // 2)
        crop = img[y0:y0 + imgsz, x0:x0 + imgsz]
        if crop.shape[0] != imgsz or crop.shape[1] != imgsz:
            pad = np.full((imgsz, imgsz, 3), 255, dtype=np.uint8)
            pad[:crop.shape[0], :crop.shape[1]] = crop
            crop = pad
        return crop[:, :, ::-1].astype(np.float32).transpose(2, 0, 1)[None] / 255.0

    yy, xx = np.meshgrid(
        np.linspace(0.0, 1.0, imgsz, dtype=np.float32),
        np.linspace(0.0, 1.0, imgsz, dtype=np.float32),
        indexing="ij",
    )
    arr = np.stack([xx, yy, 0.5 * (xx + yy)], axis=0)[None]
    return np.ascontiguousarray(arr, dtype=np.float32)


def _cutoff(raw) -> float | None:
    if isinstance(raw, (int, float)) and not isinstance(raw, bool) and 0.05 < float(raw) < 1.0:
        return round(float(raw), 4)
    return None


def _compact_set(rep) -> dict | None:
    """A page set's report without its full sweep (``real_page_eval._set_report``)."""
    if not isinstance(rep, dict):
        return None
    keep = ("role", "used_for_selection", "evaluated", "reason", "documents", "blank_pages",
            "complete_pages", "available_blank_pages", "available_complete_pages", "truth_symbols",
            "blank_max_conf", "at_chosen", "at_class_cutoffs", "blank_fp_classes_by_cutoff", "per_document")
    return {k: rep[k] for k in keep if k in rep}


def real_eval_metadata(real_eval: dict | None) -> dict:
    """ONNX sidecar fields from a checkpoint's ``real_eval`` (``real_page_eval``).

    ``recommended_confidence`` is the cutoff the SELECTION pages chose, which
    clears the highest blank-page score by the configured margin
    (``recommended_confidence_source`` says how it was obtained; checkpoints
    from before the selection/test split fall back to their margin-free
    ``best_threshold``). ``recommended_class_confidence`` holds per-class
    cutoffs (zero blank-page false detections on the selection pages), to be
    applied as ``recommended_class_confidence.get(cls, recommended_class_base_confidence)``;
    a value above 1 disables the class. In ``real_eval_summary`` only
    ``selection`` (and the legacy top-level numbers) chose anything; ``test``,
    ``cross_document`` and ``stress`` are reported only."""
    out = {
        "recommended_confidence": None,
        "recommended_confidence_source": None,
        "recommended_class_base_confidence": None,
        "recommended_class_confidence": None,
        "real_eval_summary": None,
    }
    if real_eval is None:
        return out
    summary = {
        "epoch": real_eval.get("epoch"),
        "real_score": real_eval.get("real_score"),
        "blank_pages": real_eval.get("blank_pages"),
        "complete_pages": real_eval.get("complete_pages"),
        "best_threshold": real_eval.get("best_threshold"),
        "at_blank_fp_budget": real_eval.get("at_blank_fp_budget"),
        "at_zero_blank_fp": real_eval.get("at_zero_blank_fp"),
    }
    if "recommended_confidence" in real_eval:           # selection/test-split evaluation
        summary.update({
            "scores_used_for_selection": "selection",
            "metrics_scope": real_eval.get("metrics_scope"),
            "page_pool": real_eval.get("page_pool"),
            "val_test_independent": real_eval.get("val_test_independent"),
            "eligible": real_eval.get("eligible"),
            "selection_tier": real_eval.get("selection_tier"),
            "selection_note": real_eval.get("selection_note"),
            "chosen_threshold": real_eval.get("chosen_threshold"),
            "margin": real_eval.get("margin"),
            "min_recall": real_eval.get("min_recall"),
            "blank_max_conf": real_eval.get("blank_max_conf"),
            "blank_margin_gap": real_eval.get("blank_margin_gap"),
            "margin_ok": real_eval.get("margin_ok"),
            "per_class_point": real_eval.get("per_class_point"),
            "blank_max_by_class": real_eval.get("blank_max_by_class"),
            "selection": _compact_set(real_eval.get("selection")),
            "test": _compact_set(real_eval.get("test")),
            "cross_document": _compact_set(real_eval.get("cross_document")),
            "stress": real_eval.get("stress"),
        })
        rec = _cutoff(real_eval.get("recommended_confidence")) if real_eval.get("eligible") else None
        out["recommended_confidence"] = rec
        if rec is not None:
            out["recommended_confidence_source"] = (
                "selection_pages_with_blank_margin" if real_eval.get("margin_ok")
                else "selection_pages_margin_not_reached")
        base = _cutoff(real_eval.get("recommended_class_base_confidence"))
        classes = real_eval.get("recommended_class_confidence")
        if rec is not None and base is not None and isinstance(classes, dict):
            out["recommended_class_base_confidence"] = base
            out["recommended_class_confidence"] = {
                str(k): round(float(v), 4) for k, v in classes.items()
                if isinstance(v, (int, float)) and not isinstance(v, bool)}
    else:                                               # older checkpoint: no margin, no split
        best_th = real_eval.get("best_threshold") if isinstance(real_eval.get("best_threshold"), dict) else {}
        rec = _cutoff(best_th.get("threshold"))
        if rec is not None and rec < 0.999:
            out["recommended_confidence"] = rec
            out["recommended_confidence_source"] = "legacy_best_threshold_no_margin_no_test_split"
    out["real_eval_summary"] = summary
    return out


def export_onnx_detector(
    weights: Path,
    *,
    output: Path | None = None,
    imgsz: int = 512,
    opset: int = 17,
    verify_image: Path | None = None,
    max_parity_diff: float = 1e-3,
) -> dict:
    import onnxruntime as ort
    import torch
    from ultralytics import YOLO

    from blueprint_symbols.ssl_transfer import ARCHITECTURE, DETECTOR_KIND, read_blueprint_weights

    weights = Path(weights).resolve()
    if imgsz < 128 or imgsz % 32:
        raise ValueError(f"invalid imgsz={imgsz}: expected positive multiple of 32")

    payload = read_blueprint_weights(weights)
    if payload.get("kind") != DETECTOR_KIND:
        raise ValueError(
            f"Expected a Phase-2 detector checkpoint ({DETECTOR_KIND}), got {payload.get('kind')!r}"
        )

    out_onnx = Path(output).resolve() if output is not None else weights.with_suffix(".onnx")
    out_onnx.parent.mkdir(parents=True, exist_ok=True)
    out_meta = Path(f"{out_onnx}.json")
    pt_sha = _sha256(weights)

    yolo = YOLO(str(weights))
    model = yolo.model.float().eval().cpu()
    model.fuse()
    detect = model.model[-1]
    end2end = bool(getattr(detect, "end2end", False) or getattr(model, "end2end", False))
    detect.export = True

    class _SingleOutputWrapper(torch.nn.Module):
        def __init__(self, inner: torch.nn.Module) -> None:
            super().__init__()
            self.inner = inner

        def forward(self, x: torch.Tensor) -> torch.Tensor:
            out = self.inner(x)
            return out[0] if isinstance(out, (tuple, list)) else out

    wrapper = _SingleOutputWrapper(model).eval().cpu()
    dummy = torch.zeros(1, 3, imgsz, imgsz, dtype=torch.float32)

    tmp_onnx = out_onnx.with_name(f".{out_onnx.name}.{os.getpid()}.tmp")
    try:
        with torch.no_grad():
            torch.onnx.export(
                wrapper,
                dummy,
                str(tmp_onnx),
                input_names=["images"],
                output_names=["output0"],
                dynamic_axes={"images": {0: "batch"}, "output0": {0: "batch"}},
                opset_version=opset,
                dynamo=False,
            )

        sess = ort.InferenceSession(str(tmp_onnx), providers=["CPUExecutionProvider"])
        test_np = _deterministic_test_tensor(imgsz, verify_image=verify_image)
        with torch.no_grad():
            pt_out = wrapper(torch.from_numpy(test_np)).cpu().numpy()
        ort_out = sess.run(["output0"], {"images": test_np})[0]
        if pt_out.shape != ort_out.shape:
            raise RuntimeError(f"ONNX output shape {ort_out.shape} != PyTorch {pt_out.shape}")
        max_diff = float(np.abs(pt_out - ort_out).max())
        if not np.isfinite(max_diff) or max_diff >= max_parity_diff:
            raise RuntimeError(
                f"ONNX parity check failed: max_abs_diff={max_diff:.6g} >= {max_parity_diff}"
            )

        os.replace(tmp_onnx, out_onnx)
    except Exception:
        if tmp_onnx.exists():
            tmp_onnx.unlink()
        raise

    epoch_0idx = payload.get("epoch")
    real_eval = payload.get("real_eval") if isinstance(payload.get("real_eval"), dict) else None
    eval_meta = real_eval_metadata(real_eval)
    meta = {
        "format_version": 1,
        "kind": "blueprint_only_detector_onnx_yolo26n_p2",
        "architecture": ARCHITECTURE,
        "imgsz": imgsz,
        "stride": [int(v) for v in model.stride],
        "end2end": end2end,
        "output_layout": "end2end_xyxy_score_cls" if end2end else "xywh_cls_probs",
        "output_shape": list(ort_out.shape),
        "names": {str(k): str(v) for k, v in payload["names"].items()},
        "source_checkpoint": str(weights),
        "source_pt_sha256": pt_sha,
        "onnx_sha256": _sha256(out_onnx),
        "epoch_0idx": epoch_0idx,
        "epoch": (int(epoch_0idx) + 1) if isinstance(epoch_0idx, int) else None,
        "best_fitness": payload.get("best_fitness"),
        **eval_meta,
        "parity_max_abs_diff": max_diff,
    }
    tmp_meta = out_meta.with_name(f".{out_meta.name}.{os.getpid()}.tmp")
    tmp_meta.write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")
    os.replace(tmp_meta, out_meta)
    return {
        "onnx": str(out_onnx),
        "metadata": str(out_meta),
        "classes": len(payload["names"]),
        "epoch": meta["epoch"],
        "best_fitness": meta["best_fitness"],
        "output_layout": meta["output_layout"],
        "output_shape": meta["output_shape"],
        "recommended_confidence": meta["recommended_confidence"],
        "recommended_confidence_source": meta["recommended_confidence_source"],
        "parity_max_abs_diff": max_diff,
    }


def main(argv: list[str] | None = None) -> int:
    os.environ["CUDA_VISIBLE_DEVICES"] = ""
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--weights", type=Path, required=True, help="Blueprint-only Phase-2 best.pt checkpoint")
    p.add_argument("--output", type=Path, default=None, help="Output .onnx path (default: <weights>.onnx)")
    p.add_argument("--imgsz", type=int, default=512)
    p.add_argument("--opset", type=int, default=17)
    p.add_argument("--verify-image", type=Path, default=None, help="Optional real image crop for parity check")
    args = p.parse_args(argv)
    report = export_onnx_detector(
        args.weights,
        output=args.output,
        imgsz=args.imgsz,
        opset=args.opset,
        verify_image=args.verify_image,
    )
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
