"""CPU-only preflight: verify SSL backbone on a real YOLO26n-P2 detector.

Use before an expensive supervised run. No training, pretrained downloads,
or CUDA required.

  python scripts_verify_ssl_transfer.py work/pretrain_v3/best_yolo26n_p2_backbone.pt --classes 6
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from blueprint_symbols.ssl_transfer import read_ssl_backbone, install_ssl_backbone


def verify_transfer(weights: Path, *, classes: int = 6) -> dict:
    if classes < 1:
        raise ValueError("classes must be positive")
    from ultralytics import YOLO
    from ultralytics.nn.tasks import DetectionModel

    # YAML creates random weights, not a COCO/Imagenet checkpoint.
    source = YOLO("yolo26n-p2.yaml")
    detector = DetectionModel(source.model.yaml, nc=classes, verbose=False)
    if 4 not in [int(v) for v in detector.stride]:
        raise RuntimeError("YOLO26n-P2 stride-4 head missing")

    payload = read_ssl_backbone(weights)
    report = install_ssl_backbone(detector, payload)
    report.update({"checkpoint": str(weights), "classes": classes,
                   "stride": [int(v) for v in detector.stride]})
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("weights", type=Path)
    parser.add_argument("--classes", type=int, default=6)
    args = parser.parse_args()
    print(json.dumps(verify_transfer(args.weights, classes=args.classes), indent=2))


if __name__ == "__main__":
    main()
