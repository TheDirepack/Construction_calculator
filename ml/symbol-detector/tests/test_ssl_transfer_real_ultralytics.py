"""Integration: transfer checkpoint onto real YOLO26n-P2 with a different class head."""
from __future__ import annotations

import pytest

torch = pytest.importorskip("torch")
pytest.importorskip("ultralytics")

from ultralytics import YOLO

from blueprint_symbols.ssl_transfer import KIND, ARCHITECTURE
from scripts_verify_ssl_transfer import verify_transfer


def test_real_yolo26p2_ssl_checkpoint_to_six_class_detection_model(tmp_path):
    random_source = YOLO(ARCHITECTURE)
    model = random_source.model
    head_idx = len(model.model) - 1
    state = {
        k: v.cpu().clone() for k, v in model.state_dict().items()
        if not k.startswith(f"model.{head_idx}.")
    }
    path = tmp_path / "self_trained_style_checkpoint.pt"
    torch.save({
        "kind": KIND,
        "architecture": ARCHITECTURE,
        "detect_layer_index": head_idx,
        "backbone_neck_state_dict": state,
    }, path)
    report = verify_transfer(path, classes=6)
    assert report["transferred_tensors"] == len(state)
    assert report["untrained_head_tensors"] > 0
    assert 4 in report["stride"]
    assert report["classes"] == 6
