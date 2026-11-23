"""Integration: transfer checkpoint onto real YOLO26n-P2 with a different class head."""
from __future__ import annotations

import pytest

torch = pytest.importorskip("torch")
pytest.importorskip("ultralytics")

from ultralytics import YOLO

from blueprint_symbols.ssl_transfer import KIND, ARCHITECTURE, build_ssl_detection_trainer
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



def test_real_ultralytics_trainer_hook_loads_our_backbone(tmp_path):
    """Call DetectionTrainer.get_model itself, not a standalone helper."""
    source = YOLO(ARCHITECTURE).model
    head = len(source.model) - 1
    state = {
        k: v.detach().cpu().clone() for k, v in source.state_dict().items()
        if not k.startswith(f"model.{head}.")
    }
    with torch.no_grad():
        # Distinctive values make accidental random initialization observable.
        source.model[0].conv.weight.fill_(0.03125)
        state["model.0.conv.weight"] = source.model[0].conv.weight.detach().cpu().clone()
    weight_path = tmp_path / "blueprint_ssl.pt"
    torch.save({
        "kind": KIND, "architecture": ARCHITECTURE,
        "detect_layer_index": head,
        "backbone_neck_state_dict": state,
    }, weight_path)

    trainer_type = build_ssl_detection_trainer(weight_path)
    trainer = trainer_type.__new__(trainer_type)
    # A normal Ultralytics trainer initializes args during __init__;
    # this isolated get_model test deliberately skips that heavy setup.
    from types import SimpleNamespace
    trainer.args = SimpleNamespace(cls_remap=False)
    trainer.data = {"nc": 6, "channels": 3}
    trained = trainer.get_model(cfg=source.yaml, weights=None, verbose=False)
    assert trained.model[-1].nc == 6
    assert torch.equal(trained.model[0].conv.weight.detach().cpu(), state["model.0.conv.weight"])
    for key, original in state.items():
        assert torch.equal(trained.state_dict()[key].detach().cpu(), original)
