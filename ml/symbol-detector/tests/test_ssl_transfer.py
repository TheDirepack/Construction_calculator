"""SSL backbone transfer must affect the actual model that will train."""
from __future__ import annotations

import copy

import pytest

torch = pytest.importorskip("torch")
from torch import nn

from blueprint_symbols.ssl_transfer import (
    ARCHITECTURE, KIND, read_ssl_backbone, install_ssl_backbone,
)


class Detect(nn.Module):
    def __init__(self):
        super().__init__()
        self.boxes = nn.Conv2d(4, 2, 1)


class FakeYOLO(nn.Module):
    def __init__(self):
        super().__init__()
        self.model = nn.Sequential(
            nn.Conv2d(3, 4, 3, padding=1, bias=False),
            nn.BatchNorm2d(4),
            Detect(),
        )


def checkpoint_for(source: FakeYOLO):
    return {
        "kind": KIND,
        "architecture": ARCHITECTURE,
        "detect_layer_index": 2,
        "backbone_neck_state_dict": {
            k: v.clone() for k, v in source.state_dict().items()
            if not k.startswith("model.2.")
        },
    }


def test_actual_training_backbone_copied_and_head_left_random():
    source, training = FakeYOLO(), FakeYOLO()
    with torch.no_grad():
        source.model[0].weight.fill_(0.123)
        source.model[1].running_mean.fill_(0.222)
    previous_head = copy.deepcopy(training.model[2].state_dict())
    report = install_ssl_backbone(training, checkpoint_for(source))
    assert report["transferred_tensors"] > 3
    assert report["untrained_head_tensors"] > 0
    for key, tensor in checkpoint_for(source)["backbone_neck_state_dict"].items():
        assert torch.equal(training.state_dict()[key], tensor)
    assert all(torch.equal(training.model[2].state_dict()[k], v)
               for k, v in previous_head.items())


def test_reject_wrong_architecture_and_missing_backbone_keys():
    source, training = FakeYOLO(), FakeYOLO()
    payload = checkpoint_for(source)
    del payload["backbone_neck_state_dict"]["model.0.weight"]
    with pytest.raises(ValueError, match="key mismatch"):
        install_ssl_backbone(training, payload)
    payload = checkpoint_for(source)
    payload["detect_layer_index"] = 3
    with pytest.raises(ValueError, match="head indices"):
        install_ssl_backbone(training, payload)


def test_reject_incompatible_shapes_before_loading():
    payload = checkpoint_for(FakeYOLO())
    payload["backbone_neck_state_dict"]["model.0.weight"] = torch.ones(5, 3, 3, 3)
    with pytest.raises(ValueError, match="shape/dtype"):
        install_ssl_backbone(FakeYOLO(), payload)


def test_ssl_checkpoint_metadata_is_required(tmp_path):
    path = tmp_path / "ssl.pt"
    torch.save(checkpoint_for(FakeYOLO()), path)
    loaded = read_ssl_backbone(path)
    assert loaded["kind"] == KIND
    loaded["architecture"] = "unrelated.yaml"
    torch.save(loaded, path)
    with pytest.raises(ValueError, match="architecture"):
        read_ssl_backbone(path)
    torch.save({"state_dict": FakeYOLO().state_dict()}, path)
    with pytest.raises(ValueError, match="Not a blueprint-only"):
        read_ssl_backbone(path)
