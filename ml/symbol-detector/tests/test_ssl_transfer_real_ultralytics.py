"""Integration: transfer checkpoint onto real YOLO26n-P2 with a different class head."""
from __future__ import annotations

import copy
import json
from types import SimpleNamespace

import pytest

torch = pytest.importorskip("torch")
pytest.importorskip("ultralytics")

from ultralytics import YOLO

from blueprint_symbols.label_schema import CATALOG_DEVICES
from blueprint_symbols.partial_supervision import build_masked_detection_trainer
from blueprint_symbols.partial_yolo import PartialDetectionModel
from blueprint_symbols.ssl_transfer import (
    KIND,
    DETECTOR_KIND,
    ARCHITECTURE,
    build_ssl_detection_trainer,
)
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


def _save_fake_phase2_checkpoint(tmp_path, names: dict[int, str], *, filename: str = "best.pt",
                                 train_args: dict | None = None, drop_stride4: bool = False):
    from ultralytics.nn.tasks import DetectionModel

    cfg = YOLO(ARCHITECTURE).model.yaml
    source = DetectionModel(cfg, nc=len(names), verbose=False)
    source.names = dict(names)
    with torch.no_grad():
        source.model[0].conv.weight.fill_(0.125)
        source.model[-1].cv2[0][2].weight.fill_(0.25)
        for c in range(len(names)):
            val = 0.0625 * ((c % 7) + 1)
            source.model[-1].cv3[0][2].weight[c].fill_(val)
            source.model[-1].one2one_cv3[0][2].bias[c].fill_(-val)
    ema = copy.deepcopy(source).half()
    ema.__class__ = PartialDetectionModel
    if drop_stride4:
        ema.stride = torch.tensor([8.0, 16.0, 32.0])
    ckpt_path = tmp_path / filename
    torch.save({
        "epoch": 21,
        "best_fitness": 0.58673,
        "model": None,
        "ema": ema,
        "train_args": {"model": ARCHITECTURE, "pretrained": False} if train_args is None else train_args,
    }, ckpt_path)
    return ckpt_path, ema


def test_phase2_detector_checkpoint_transfers_all_tensors_fp16_to_fp32(tmp_path):
    names = {i: f"{f}.{v}" for i, (f, v) in enumerate(CATALOG_DEVICES[:6])}
    ckpt_path, ema = _save_fake_phase2_checkpoint(tmp_path, names)
    ema_sd = {k: v.float().cpu() if v.is_floating_point() else v.cpu() for k, v in ema.state_dict().items()}

    prep = tmp_path / "prepared"
    (prep / "images" / "train").mkdir(parents=True)
    (prep / "labels" / "train").mkdir(parents=True)
    (prep / "images" / "train" / "s0.png").write_bytes(b"PNG")
    (prep / "labels" / "train" / "s0.txt").write_text("", encoding="utf-8")
    names_yaml = "\n".join(f"  {i}: {n}" for i, n in names.items())
    data_yaml = prep / "data.yaml"
    data_yaml.write_text(f"path: .\ntrain: images/train\nval: images/train\nnames:\n{names_yaml}\n", encoding="utf-8")
    (prep / "phase2_supervision.json").write_text(json.dumps({
        "format_version": 1,
        "partial_training_enabled": True,
        "class_ids": {n: i for i, n in names.items()},
        "samples": {"s0": []},
    }), encoding="utf-8")

    report = verify_transfer(ckpt_path, data_yaml=data_yaml)
    assert report["checkpoint_kind"] == DETECTOR_KIND
    assert report["transferred_tensors"] == len(ema_sd)
    assert report["untrained_head_tensors"] == 0
    assert report["matched_classes"] == 6

    for trainer_type in (
        build_ssl_detection_trainer(ckpt_path),
        build_masked_detection_trainer(data_yaml, ckpt_path),
    ):
        trainer = trainer_type.__new__(trainer_type)
        trainer.args = SimpleNamespace(cls_remap=False)
        trainer.data = {"nc": 6, "channels": 3, "names": dict(names)}
        trained = trainer.get_model(cfg=ARCHITECTURE, weights=None, verbose=False)
        trained_sd = trained.state_dict()
        for key, expected in ema_sd.items():
            assert torch.equal(trained_sd[key].detach().cpu(), expected), key
    assert type(trained) is PartialDetectionModel


def test_phase2_detector_checkpoint_remaps_reordered_and_new_classes(tmp_path):
    src_devices = [f"{f}.{v}" for f, v in CATALOG_DEVICES[:103]]
    new_device = f"{CATALOG_DEVICES[103][0]}.{CATALOG_DEVICES[103][1]}"
    src_names = {i: name for i, name in enumerate(src_devices)}
    ckpt_path, ema = _save_fake_phase2_checkpoint(tmp_path, src_names, filename="best_103.pt")

    dst_names = [new_device] + list(reversed(src_devices))
    data_yaml = tmp_path / "remap_data.yaml"
    names_yaml = "\n".join(f"  {i}: {n}" for i, n in enumerate(dst_names))
    data_yaml.write_text(f"names:\n{names_yaml}\n", encoding="utf-8")

    report = verify_transfer(ckpt_path, data_yaml=data_yaml)
    assert report["checkpoint_kind"] == DETECTOR_KIND
    assert report["matched_classes"] == 103
    assert report["remapped_class_head_tensors"] == 16
    assert report["untrained_head_tensors"] == 0

    trainer_type = build_ssl_detection_trainer(ckpt_path)
    trainer = trainer_type.__new__(trainer_type)
    trainer.args = SimpleNamespace(cls_remap=False)
    trainer.data = {"nc": 104, "channels": 3, "names": dst_names}
    trained = trainer.get_model(cfg=ARCHITECTURE, weights=None, verbose=False)
    # Row 103 in dst was row 0 in src; row 1 in dst was row 102 in src.
    assert torch.equal(
        trained.model[-1].cv3[0][2].weight[103].detach().cpu(),
        ema.model[-1].cv3[0][2].weight[0].float().cpu(),
    )
    assert torch.equal(
        trained.model[-1].one2one_cv3[0][2].bias[1].detach().cpu(),
        ema.model[-1].one2one_cv3[0][2].bias[102].float().cpu(),
    )

    # Also check --classes 6 smoke verification succeeds when class count mismatches.
    smoke = verify_transfer(ckpt_path, classes=6)
    assert smoke["checkpoint_kind"] == DETECTOR_KIND
    assert smoke["matched_classes"] == 0
    assert smoke["untrained_head_tensors"] > 0


def test_phase2_detector_checkpoint_rejects_unverified_and_coco_models(tmp_path):
    valid_names = {i: f"{f}.{v}" for i, (f, v) in enumerate(CATALOG_DEVICES[:6])}

    pretrained_ckpt, _ = _save_fake_phase2_checkpoint(
        tmp_path, valid_names, filename="pretrained_true.pt",
        train_args={"model": ARCHITECTURE, "pretrained": True},
    )
    with pytest.raises(ValueError, match="provenance|Not a verified"):
        verify_transfer(pretrained_ckpt, classes=6)

    wrong_arch_ckpt, _ = _save_fake_phase2_checkpoint(
        tmp_path, valid_names, filename="wrong_arch.pt",
        train_args={"model": "yolo11n.yaml", "pretrained": False},
    )
    with pytest.raises(ValueError, match="provenance|Not a verified"):
        verify_transfer(wrong_arch_ckpt, classes=6)

    coco_names = {0: "person", **{i: valid_names[i] for i in range(1, 6)}}
    coco_ckpt, _ = _save_fake_phase2_checkpoint(tmp_path, coco_names, filename="coco_names.pt")
    with pytest.raises(ValueError, match="non-catalog|Not a verified"):
        verify_transfer(coco_ckpt, classes=6)

    no_p2_ckpt, _ = _save_fake_phase2_checkpoint(
        tmp_path, valid_names, filename="no_p2.pt", drop_stride4=True,
    )
    with pytest.raises(ValueError, match="stride-4|Not a verified"):
        verify_transfer(no_p2_ckpt, classes=6)
