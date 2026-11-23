"""Measure device over-selection on exact, reviewed no-device crops.

Input:
  QA manifest from scripts_audit_negative_controls.py, augmented by reviewers
  with detector_negative_verified=true ONLY after entire viewport review.
  Predictions JSON:
   {"predictions":[{"id":"notes_01","detections":[
      {"score":0.72,"symbol_family":"receptacle","bbox_xyxy":[...]}]}]}

All controls require a corresponding prediction entry. Default evaluation
fails closed if a viewport is still unreviewed. --diagnostic-only allows
unreviewed candidates but does NOT report their outputs as false positives.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
import json
from pathlib import Path


def score_controls(
    manifest: dict, predictions: dict, *,
    confidence: float = .25, diagnostic_only: bool = False,
) -> dict:
    if not 0 <= confidence <= 1:
        raise ValueError("confidence must be in [0,1]")
    controls = manifest.get("controls", [])
    preds = predictions.get("predictions", [])
    if not isinstance(controls, list) or not isinstance(preds, list):
        raise ValueError("controls and predictions must be lists")
    control_by_id = {item["id"]: item for item in controls}
    if len(control_by_id) != len(controls):
        raise ValueError("duplicate control IDs")
    pred_by_id = {item["id"]: item for item in preds}
    if len(pred_by_id) != len(preds) or set(pred_by_id) != set(control_by_id):
        raise ValueError("predictions must cover each control exactly once")
    if not diagnostic_only and any(
        not item.get("detector_negative_verified", False)
        for item in controls
    ):
        raise ValueError("unreviewed negatives cannot be scored as false positives")
    by_kind = defaultdict(lambda: {"images": 0, "overselected": 0,
                                  "detections": 0, "max_score": 0.0})
    for item in controls:
        result = pred_by_id[item["id"]]
        hits = [d for d in result["detections"] if float(d["score"]) >= confidence]
        for hit in result["detections"]:
            if not 0 <= float(hit["score"]) <= 1:
                raise ValueError("invalid detection score")
        group = by_kind[item["kind"]]
        group["images"] += 1
        group["overselected"] += bool(hits)
        group["detections"] += len(hits)
        group["max_score"] = max(group["max_score"],
                                 max((float(h["score"]) for h in hits), default=0))
    groups = {}
    for key, data in sorted(by_kind.items()):
        groups[key] = {
            **data,
            "detections_per_image": round(data["detections"] / data["images"], 4),
            "fraction_with_any_detection": round(data["overselected"] / data["images"], 4),
        }
    return {
        "assessment": "diagnostic_unreviewed_not_ground_truth" if diagnostic_only
                      else "verified_no_device_false_positives",
        "confidence_threshold": confidence,
        "total_controls": len(controls),
        "total_detections": sum(v["detections"] for v in groups.values()),
        "by_kind": groups,
    }


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("control_manifest", type=Path)
    p.add_argument("predictions", type=Path)
    p.add_argument("--confidence", type=float, default=.25)
    p.add_argument("--diagnostic-only", action="store_true")
    p.add_argument("--out", type=Path)
    args = p.parse_args()
    report = score_controls(
        json.loads(args.control_manifest.read_text()),
        json.loads(args.predictions.read_text()),
        confidence=args.confidence, diagnostic_only=args.diagnostic_only,
    )
    value = json.dumps(report, indent=2)
    if args.out:
        args.out.write_text(value, encoding="utf8")
    print(value)


if __name__ == "__main__":
    main()
