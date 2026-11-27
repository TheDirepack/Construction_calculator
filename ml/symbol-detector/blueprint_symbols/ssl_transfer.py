"""Verified transfer of *our own* blueprint-only SSL/Phase-2 weights into YOLO detection.

Ultralytics rebuilds its detection training model. Loading weights into a
temporary YOLO(yaml).model before model.train() is NOT sufficient. Call this
function from DetectionTrainer.get_model() on the model that will train.
"""
from __future__ import annotations

import pickle
import re
from pathlib import Path
from typing import Mapping

import torch


KIND = "blueprint_only_ssl_yolo26n_p2_backbone_neck"
DETECTOR_KIND = "blueprint_only_detector_yolo26n_p2"
ARCHITECTURE = "yolo26n-p2.yaml"


def _valid_catalog_device_names() -> frozenset[str]:
    from .label_schema import CATALOG_DEVICES

    return frozenset(f"{family}.{variant}" for family, variant in CATALOG_DEVICES)


def _normalize_names(raw_names, expected_nc: int) -> dict[int, str] | None:
    if expected_nc < 1:
        return None
    if isinstance(raw_names, (list, tuple)):
        if len(raw_names) != expected_nc:
            return None
        out: dict[int, str] = {}
        for idx, value in enumerate(raw_names):
            if not isinstance(value, str) or not value.strip():
                return None
            out[idx] = value
    elif isinstance(raw_names, dict):
        out = {}
        for raw_key, value in raw_names.items():
            if isinstance(raw_key, bool):
                return None
            try:
                idx = raw_key if isinstance(raw_key, int) else int(raw_key)
            except (TypeError, ValueError):
                return None
            if not isinstance(value, str) or not value.strip():
                return None
            out[idx] = value
        if set(out.keys()) != set(range(expected_nc)):
            return None
    else:
        return None
    if len(set(out.values())) != expected_nc:
        return None
    return {i: out[i] for i in range(expected_nc)}


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


def _read_phase2_detector_checkpoint(source: Path) -> dict:
    """Load a verified blueprint-only Phase-2 detector checkpoint (best.pt)."""
    ckpt = torch.load(source, map_location="cpu", weights_only=False)
    if not isinstance(ckpt, dict):
        raise ValueError("Detector checkpoint must be a mapping")
    train_args = ckpt.get("train_args")
    if (not isinstance(train_args, dict)
            or train_args.get("model") != ARCHITECTURE
            or train_args.get("pretrained") is not False):
        raise ValueError(
            "Checkpoint lacks blueprint-only YOLO26n-P2 provenance "
            "(expected train_args.model == 'yolo26n-p2.yaml' and pretrained is False)"
        )
    source_model = ckpt.get("ema") if ckpt.get("ema") is not None else ckpt.get("model")
    if source_model is None:
        raise ValueError("Detector checkpoint contains neither ema nor model weights")
    layers = getattr(source_model, "model", None)
    if layers is None or len(layers) < 2 or layers[-1].__class__.__name__ != "Detect":
        raise ValueError("Detector checkpoint does not contain a YOLO Detect model")
    stride = [int(v) for v in getattr(source_model, "stride", ())]
    if 4 not in stride:
        raise ValueError(f"Detector checkpoint is missing stride-4 P2 head (stride={stride})")
    nc = int(layers[-1].nc)
    names = _normalize_names(getattr(source_model, "names", None), nc)
    if names is None:
        raise ValueError("Detector checkpoint has invalid or missing class names mapping")
    valid_names = _valid_catalog_device_names()
    invalid = sorted(name for name in names.values() if name not in valid_names)
    if invalid:
        raise ValueError(f"Detector checkpoint contains non-catalog classes: {invalid[:8]}")
    head_idx = len(layers) - 1
    state_dict = {
        k: v.detach().cpu().clone()
        for k, v in source_model.state_dict().items()
    }
    if not state_dict:
        raise ValueError("Detector checkpoint state_dict is empty")
    for key, tensor in state_dict.items():
        if not isinstance(tensor, torch.Tensor):
            raise ValueError(f"Detector checkpoint entry is not a tensor: {key}")
        if tensor.is_floating_point() and not torch.isfinite(tensor).all():
            raise ValueError(f"Non-finite detector checkpoint tensor: {key}")
    head_prefix = f"model.{head_idx}."
    return {
        "kind": DETECTOR_KIND,
        "architecture": ARCHITECTURE,
        "detect_layer_index": head_idx,
        "names": names,
        "epoch": ckpt.get("epoch"),
        "best_fitness": ckpt.get("best_fitness"),
        "state_dict": state_dict,
        "backbone_neck_state_dict": {
            k: v for k, v in state_dict.items() if not k.startswith(head_prefix)
        },
    }


def read_blueprint_weights(path: str | Path) -> dict:
    """Load either a blueprint SSL backbone checkpoint or a Phase-2 detector checkpoint."""
    source = Path(path)
    if not source.is_file():
        raise FileNotFoundError(source)
    try:
        return read_ssl_backbone(source)
    except (ValueError, RuntimeError, pickle.UnpicklingError, EOFError) as ssl_exc:
        try:
            return _read_phase2_detector_checkpoint(source)
        except (ValueError, RuntimeError, pickle.UnpicklingError, EOFError, AttributeError, TypeError) as det_exc:
            raise ValueError(
                f"Not a verified blueprint SSL or Phase-2 checkpoint ({source}): "
                f"ssl={ssl_exc}; detector={det_exc}"
            ) from det_exc


def _cast_tensor(key: str, src: torch.Tensor, dst: torch.Tensor) -> torch.Tensor:
    if dst.is_floating_point():
        if not src.is_floating_point():
            raise ValueError(f"Blueprint tensor dtype mismatch (non-float source): {key}")
        if not torch.isfinite(src).all():
            raise ValueError(f"Non-finite blueprint tensor: {key}")
        return src.detach().cpu().to(dtype=dst.dtype)
    if src.dtype != dst.dtype:
        raise ValueError(f"Blueprint tensor dtype mismatch: {key} ({src.dtype} != {dst.dtype})")
    return src.detach().cpu().clone()


def _validate_detect_target(model: torch.nn.Module, expected_head_index: object,
                            *, require_stride4: bool = False) -> tuple[object, int]:
    layers = getattr(model, "model", None)
    if layers is None or len(layers) < 2:
        raise ValueError("Expected a YOLO detection model with ordered layers")
    head_index = len(layers) - 1
    if layers[-1].__class__.__name__ != "Detect":
        raise ValueError("Expected YOLO Detect head as the final layer")
    if expected_head_index != head_index:
        raise ValueError("SSL and detector architecture have different head indices")
    if require_stride4 and hasattr(model, "stride"):
        stride = [int(v) for v in getattr(model, "stride", ())]
        if 4 not in stride:
            raise ValueError(f"Target YOLO detector is missing stride-4 P2 head (stride={stride})")
    return layers, head_index


def _verify_and_load_state(model: torch.nn.Module, to_load: dict[str, torch.Tensor],
                           current: Mapping[str, torch.Tensor], *, label: str) -> set[str]:
    incompatible = model.load_state_dict(to_load, strict=False)
    intentional_missing = set(current) - set(to_load)
    # PyTorch may omit backward-compatible head buffers (e.g. BN tracking)
    # from missing_keys. Only *unexpected* missing keys are defects.
    unexplained_missing = set(incompatible.missing_keys) - intentional_missing
    if unexplained_missing or incompatible.unexpected_keys:
        raise RuntimeError(
            f"Incomplete or unexpected {label} transfer: "
            f"missing_backbone={sorted(unexplained_missing)[:8]}, "
            f"unexpected={incompatible.unexpected_keys[:8]}"
        )
    after = model.state_dict()
    for key, expected_tensor in to_load.items():
        if not torch.equal(after[key].detach().cpu(), expected_tensor):
            raise RuntimeError(f"{label} weight changed during transfer: {key}")
    return intentional_missing


def install_ssl_backbone(model: torch.nn.Module, checkpoint: Mapping) -> dict:
    """Copy and independently verify all backbone/neck tensors in the *training* model.

    The only permitted missing tensors are in the new, randomly initialized
    detector head. Fail closed on architecture changes and partial loads.
    """
    _, head_index = _validate_detect_target(model, checkpoint.get("detect_layer_index"))
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

    to_load = {k: source[k] for k in expected}
    expected_missing = _verify_and_load_state(model, to_load, current, label="SSL backbone")

    return {"transferred_tensors": len(expected), "untrained_head_tensors": len(expected_missing),
            "architecture": ARCHITECTURE, "detect_layer_index": head_index}


def install_blueprint_weights(model: torch.nn.Module, checkpoint: Mapping, *,
                              target_names=None) -> dict:
    """Install either an SSL backbone checkpoint or a Phase-2 detector checkpoint."""
    kind = checkpoint.get("kind")
    if kind == KIND:
        report = install_ssl_backbone(model, checkpoint)
        report.setdefault("checkpoint_kind", KIND)
        return report
    if kind != DETECTOR_KIND:
        raise ValueError(f"Unsupported blueprint checkpoint kind: {kind!r}")

    layers, head_index = _validate_detect_target(
        model, checkpoint.get("detect_layer_index"), require_stride4=True,
    )
    current = model.state_dict()
    head_prefix = f"model.{head_index}."
    expected_bb = {k for k in current if not k.startswith(head_prefix)}
    source_bb = checkpoint["backbone_neck_state_dict"]
    if set(source_bb) != expected_bb:
        missing = sorted(expected_bb - set(source_bb))
        extra = sorted(set(source_bb) - expected_bb)
        raise ValueError(f"Blueprint backbone key mismatch (missing={missing[:8]}, extra={extra[:8]})")

    to_load: dict[str, torch.Tensor] = {}
    for key in sorted(expected_bb):
        src = source_bb[key]
        dst = current[key]
        if src.shape != dst.shape:
            raise ValueError(f"Blueprint backbone tensor shape mismatch: {key}")
        to_load[key] = _cast_tensor(key, src, dst)

    head_keys = set(current) - expected_bb
    cls_re = re.compile(rf"^model\.{head_index}\.(?:one2one_)?cv3\.\d+\.2\.(?:weight|bias)$")
    cls_output_keys = {k for k in head_keys if cls_re.match(k)}
    src_state = checkpoint["state_dict"]
    src_names = checkpoint["names"]
    raw_nc = getattr(layers[-1], "nc", 0) or (
        len(target_names) if isinstance(target_names, (dict, list, tuple)) else 0
    )
    target_nc = int(raw_nc)
    norm_target = _normalize_names(target_names, target_nc) if target_names is not None else None
    if target_names is not None and norm_target is None:
        raise ValueError(f"Target class names are invalid or do not match detector nc={target_nc}")
    src_by_name = {name: idx for idx, name in src_names.items()}
    matched_classes = (
        target_nc if norm_target == src_names
        else (sum(1 for name in norm_target.values() if name in src_by_name)
              if norm_target is not None else 0)
    )

    transferred_head = 0
    remapped_head = 0
    for key in sorted(head_keys - cls_output_keys):
        if key in src_state and src_state[key].shape == current[key].shape:
            to_load[key] = _cast_tensor(key, src_state[key], current[key])
            transferred_head += 1

    for key in sorted(cls_output_keys):
        if norm_target == src_names:
            if key not in src_state or src_state[key].shape != current[key].shape:
                raise ValueError(f"Blueprint class-head shape mismatch for exact class map: {key}")
            to_load[key] = _cast_tensor(key, src_state[key], current[key])
            transferred_head += 1
        elif (norm_target is not None and matched_classes > 0
              and key in src_state and src_state[key].shape[1:] == current[key].shape[1:]):
            merged = current[key].detach().cpu().clone()
            for dst_idx, name in norm_target.items():
                src_idx = src_by_name.get(name)
                if src_idx is not None:
                    merged[dst_idx] = _cast_tensor(key, src_state[key][src_idx], current[key][dst_idx])
            to_load[key] = merged
            remapped_head += 1

    if norm_target == src_names and len(to_load) != len(current):
        raise ValueError(
            f"Expected full checkpoint transfer when classes match ({len(to_load)} != {len(current)})"
        )

    intentional_missing = _verify_and_load_state(model, to_load, current, label="Blueprint detector")
    return {
        "checkpoint_kind": DETECTOR_KIND,
        "transferred_tensors": len(to_load),
        "backbone_neck_tensors": len(expected_bb),
        "transferred_head_tensors": transferred_head,
        "remapped_class_head_tensors": remapped_head,
        "matched_classes": matched_classes,
        "untrained_head_tensors": len(intentional_missing),
        "architecture": ARCHITECTURE,
        "detect_layer_index": head_index,
        "source_epoch": checkpoint.get("epoch"),
        "source_best_fitness": checkpoint.get("best_fitness"),
    }


def build_ssl_detection_trainer(weights: str | Path):
    """Return Ultralytics' actual trainer class with verified blueprint weights.

    Accepts either a blueprint-only SSL backbone checkpoint or a verified
    Phase-2 detector checkpoint (``best.pt``). Tested via the real
    ``DetectionTrainer.get_model`` hook.
    """
    from ultralytics.models.yolo.detect import DetectionTrainer

    checkpoint = read_blueprint_weights(weights)

    class SSLDetectionTrainer(DetectionTrainer):
        def get_model(self, cfg=None, weights=None, verbose=True):
            # Ultralytics constructs a new class-count-specific detector here.
            # Do not pass generic weights or rely on the temporary YOLO object.
            model = super().get_model(cfg=cfg, weights=None, verbose=verbose)
            target_names = self.data.get("names") if isinstance(getattr(self, "data", None), dict) else None
            report = install_blueprint_weights(model, checkpoint, target_names=target_names)
            print(f"Verified blueprint weight transfer into actual YOLO trainer: {report}", flush=True)
            return model

    return SSLDetectionTrainer
