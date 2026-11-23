"""Verified transfer of *our own* blueprint-only SSL backbone into YOLO detection.

Ultralytics rebuilds its detection training model. Loading weights into a
temporary YOLO(yaml).model before model.train() is NOT sufficient. Call this
function from DetectionTrainer.get_model() on the model that will train.
"""
from __future__ import annotations

from pathlib import Path
from typing import Mapping

import torch


KIND = "blueprint_only_ssl_yolo26n_p2_backbone_neck"
ARCHITECTURE = "yolo26n-p2.yaml"


def read_ssl_backbone(path: str | Path) -> dict:
    """Load our simple tensor-only backbone checkpoint, rejecting other weights."""
    source = Path(path)
    if not source.is_file():
        raise FileNotFoundError(source)
    payload = torch.load(source, map_location="cpu", weights_only=True)
    if not isinstance(payload, dict) or payload.get("kind") != KIND:
        raise ValueError("Not a blueprint-only YOLO26n-P2 SSL backbone checkpoint")
    if payload.get("architecture") != ARCHITECTURE:
        raise ValueError("SSL checkpoint architecture does not match YOLO26n-P2")
    state = payload.get("backbone_neck_state_dict")
    if not isinstance(state, dict) or not state:
        raise ValueError("SSL checkpoint is missing backbone-neck tensors")
    if not all(isinstance(k, str) and isinstance(v, torch.Tensor) for k, v in state.items()):
        raise ValueError("SSL backbone weights must contain only named torch tensors")
    return payload


def install_ssl_backbone(model: torch.nn.Module, checkpoint: Mapping) -> dict:
    """Copy and independently verify all backbone/neck tensors in the *training* model.

    The only permitted missing tensors are in the new, randomly initialized
    detector head. Fail closed on architecture changes and partial loads.
    """
    layers = getattr(model, "model", None)
    if layers is None or len(layers) < 2:
        raise ValueError("Expected a YOLO detection model with ordered layers")
    head_index = len(layers) - 1
    if layers[-1].__class__.__name__ != "Detect":
        raise ValueError("Expected YOLO Detect head as the final layer")
    if checkpoint.get("detect_layer_index") != head_index:
        raise ValueError("SSL and detector architecture have different head indices")
    source = checkpoint["backbone_neck_state_dict"]
    current = model.state_dict()
    head_prefix = f"model.{head_index}."
    expected = {k for k in current if not k.startswith(head_prefix)}
    if set(source) != expected:
        missing = sorted(expected - set(source))
        extra = sorted(set(source) - expected)
        raise ValueError(f"SSL backbone key mismatch (missing={missing[:8]}, extra={extra[:8]})")
    for key in expected:
        weight = source[key]
        if weight.shape != current[key].shape or weight.dtype != current[key].dtype:
            raise ValueError(f"SSL backbone tensor shape/dtype mismatch: {key}")
        if not torch.isfinite(weight).all():
            raise ValueError(f"Non-finite SSL backbone tensor: {key}")

    incompatible = model.load_state_dict(source, strict=False)
    expected_missing = set(current) - expected
    if set(incompatible.missing_keys) != expected_missing or incompatible.unexpected_keys:
        raise RuntimeError("Incomplete or unexpected SSL backbone transfer")
    after = model.state_dict()
    for key in expected:
        if not torch.equal(after[key].detach().cpu(), source[key].detach().cpu()):
            raise RuntimeError(f"SSL backbone changed during transfer: {key}")

    return {"transferred_tensors": len(expected), "untrained_head_tensors": len(expected_missing),
            "architecture": ARCHITECTURE, "detect_layer_index": head_index}
