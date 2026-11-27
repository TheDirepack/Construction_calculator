"""CPU-only preflight: verify SSL or Phase-2 weights on a real YOLO26n-P2 detector.

Use before an expensive supervised run. No training, pretrained downloads,
or CUDA required.

  python scripts_verify_ssl_transfer.py work/pretrain_v3/best_yolo26n_p2_backbone.pt --classes 6
  python scripts_verify_ssl_transfer.py work/phase2_v8_init/runs/phase2_v8_init/weights/best.pt --data-yaml work/prepared/yolo/data.yaml
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from blueprint_symbols.ssl_transfer import read_blueprint_weights, install_blueprint_weights


def verify_transfer(weights: Path, *, classes: int = 6, data_yaml: Path | None = None) -> dict:
    target_names = None
    if data_yaml is not None:
        import yaml

        data = yaml.safe_load(Path(data_yaml).read_text(encoding="utf-8"))
        if not isinstance(data, dict) or not data.get("names"):
            raise ValueError(f"Prepared dataset YAML is missing class names: {data_yaml}")
        target_names = data["names"]
        classes = len(target_names)
    if classes < 1:
        raise ValueError("classes must be positive")
    from ultralytics import YOLO
    from ultralytics.nn.tasks import DetectionModel

    # YAML creates random weights, not a COCO/Imagenet checkpoint.
    source = YOLO("yolo26n-p2.yaml")
    detector = DetectionModel(source.model.yaml, nc=classes, verbose=False)
    if 4 not in [int(v) for v in detector.stride]:
        raise RuntimeError("YOLO26n-P2 stride-4 head missing")

    payload = read_blueprint_weights(weights)
    report = install_blueprint_weights(detector, payload, target_names=target_names)
    report.update({"checkpoint": str(weights), "classes": classes,
                   "stride": [int(v) for v in detector.stride]})
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("weights", type=Path)
    parser.add_argument("--classes", type=int, default=6)
    parser.add_argument("--data-yaml", type=Path, default=None,
                        help="Verify full class mapping against a prepared YOLO data.yaml")
    args = parser.parse_args()
    print(json.dumps(verify_transfer(args.weights, classes=args.classes, data_yaml=args.data_yaml), indent=2))


if __name__ == "__main__":
    main()
