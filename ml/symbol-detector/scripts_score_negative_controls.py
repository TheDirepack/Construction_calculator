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
Outputs include by_kind, by_family and sparse by_kind_family metrics.
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
    """Score exact negative views; preserve separate content and class axes.

    In diagnostic mode, the same counts describe *detections*, not verified
    false positives. Class-by-kind statistics use every image of that kind
    as the denominator, including images with no predictions for that class.
    """
    import math

    if not isinstance(confidence, (int, float)) or isinstance(confidence, bool):
        raise ValueError("confidence must be a finite number in [0,1]")
    if not math.isfinite(confidence) or not 0 <= confidence <= 1:
        raise ValueError("confidence must be a finite number in [0,1]")
    controls = manifest.get("controls", [])
    preds = predictions.get("predictions", [])
    if not isinstance(controls, list) or not isinstance(preds, list) or not controls:
        raise ValueError("controls must be a nonempty list and predictions a list")
    if any(not isinstance(item, dict) or
           not isinstance(item.get("id"), str) or not item["id"] or
           not isinstance(item.get("kind"), str) or not item["kind"]
           for item in controls):
        raise ValueError("control id/kind must be nonempty strings")
    if any(not isinstance(item, dict) or
           not isinstance(item.get("id"), str) or not item["id"]
           for item in preds):
        raise ValueError("prediction id must be a nonempty string")
    control_by_id = {item["id"]: item for item in controls}
    if len(control_by_id) != len(controls):
        raise ValueError("duplicate control IDs")
    pred_by_id = {item["id"]: item for item in preds}
    if len(pred_by_id) != len(preds) or set(pred_by_id) != set(control_by_id):
        raise ValueError("predictions must cover each control exactly once")

    # Do not treat a JSON string ("true"), integer (1), or an absent flag
    # as an exhaustive human review of the exact exported viewport.
    verified_count = sum(
        item.get("detector_negative_verified") is True for item in controls
    )
    if not diagnostic_only and verified_count != len(controls):
        raise ValueError("unreviewed negatives cannot be scored as false positives")

    def empty_counts() -> dict:
        return {"overselected": 0, "detections": 0, "max_score": 0.0}

    def add_counts(stats: dict, hits: list[tuple[str, float]]) -> None:
        stats["overselected"] += bool(hits)
        stats["detections"] += len(hits)
        if hits:
            stats["max_score"] = max(stats["max_score"],
                                     max(score for _, score in hits))

    def summarize(stats: dict, total_images: int) -> dict:
        return {
            "images": total_images,
            **stats,
            "detections_per_image": round(stats["detections"] / total_images, 4),
            "fraction_with_any_detection": round(
                stats["overselected"] / total_images, 4
            ),
        }

    kind_counts: dict[str, int] = defaultdict(int)
    by_kind_counts: dict[str, dict] = defaultdict(empty_counts)
    by_family_counts: dict[str, dict] = defaultdict(empty_counts)
    by_kind_family_counts: dict[str, dict[str, dict]] = defaultdict(
        lambda: defaultdict(empty_counts)
    )
    for item in controls:
        kind = item["kind"]
        kind_counts[kind] += 1
        detections = pred_by_id[item["id"]].get("detections")
        if not isinstance(detections, list):
            raise ValueError("each prediction must have a detections list")
        hits: list[tuple[str, float]] = []
        for detection in detections:
            if not isinstance(detection, dict):
                raise ValueError("detections must be objects")
            raw_score = detection.get("score")
            if (not isinstance(raw_score, (int, float)) or
                    isinstance(raw_score, bool) or
                    not math.isfinite(raw_score) or
                    not 0 <= raw_score <= 1):
                raise ValueError("invalid detection score")
            family = detection.get("symbol_family")
            if not isinstance(family, str) or not family.strip():
                raise ValueError("detections require a nonempty symbol_family")
            if raw_score >= confidence:
                hits.append((family, float(raw_score)))
        add_counts(by_kind_counts[kind], hits)
        # One image with several detections of a family counts once in the
        # image-level false-positive rate, but all boxes count toward volume.
        hits_by_family: dict[str, list[tuple[str, float]]] = defaultdict(list)
        for family, score in hits:
            hits_by_family[family].append((family, score))
        for family, family_hits in hits_by_family.items():
            add_counts(by_family_counts[family], family_hits)
            add_counts(by_kind_family_counts[kind][family], family_hits)

    groups = {
        kind: summarize(by_kind_counts[kind], n)
        for kind, n in sorted(kind_counts.items())
    }
    # Sparse family mappings omit zero-hit families. Denominators still
    # include every eligible crop, not only crops containing predictions.
    families = {
        family: summarize(stats, len(controls))
        for family, stats in sorted(by_family_counts.items())
    }
    kind_families = {
        kind: {
            family: summarize(stats, kind_counts[kind])
            for family, stats in sorted(by_kind_family_counts[kind].items())
        }
        for kind in sorted(kind_counts)
    }
    return {
        "assessment": ("diagnostic_unreviewed_not_ground_truth"
                       if diagnostic_only else "verified_no_device_false_positives"),
        "confidence_threshold": confidence,
        "total_controls": len(controls),
        "verified_controls": verified_count,
        "total_detections": sum(v["detections"] for v in groups.values()),
        "by_kind": groups,
        "by_family": families,
        "by_kind_family": kind_families,
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
