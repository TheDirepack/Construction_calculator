"""Tests for precision-first role-aware Phase-2 penalties, real negative oversampling, and periodic real-page eval."""
from __future__ import annotations

import json
import sys
from pathlib import Path
from types import SimpleNamespace

import cv2
import numpy as np
import pytest

from blueprint_symbols.dataset_manifest import build_manifest
from blueprint_symbols.partial_supervision import DEFAULT_PHASE2_LOSS_CFG, read_supervision
from blueprint_symbols.real_page_eval import score_complete_page, select_real_eval_pages
from scripts_export_yolo_tiles import run as export_yolo_tiles


def _draw_blueprint_sheet(path: Path, *, with_symbol: bool, seed: int) -> None:
    rng = np.random.default_rng(seed)
    img = np.full((640, 640, 3), 246, np.uint8)
    for _ in range(8):
        x1, y1 = int(rng.integers(30, 580)), int(rng.integers(30, 580))
        x2, y2 = int(rng.integers(30, 580)), int(rng.integers(30, 580))
        cv2.line(img, (x1, y1), (x2, y2), (25, 25, 25), int(rng.integers(2, 5)))
    rx, ry = int(rng.integers(40, 360)), int(rng.integers(40, 360))
    cv2.rectangle(img, (rx, ry), (rx + int(rng.integers(80, 220)), ry + int(rng.integers(80, 220))), (40, 40, 40), 2)
    cv2.putText(img, f"DETAIL {seed:04d}", (40, 50), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 0, 0), 2)
    if with_symbol:
        cv2.circle(img, (260, 260), 16, (0, 0, 0), 2)
    assert cv2.imwrite(str(path), img)


def _write_manifest(tmp_path: Path, *, real_blank_docs: int = 1, real_complete_docs: int = 1) -> tuple[Path, dict]:
    tmp_path.mkdir(parents=True, exist_ok=True)
    paths: list[Path] = []

    def _add_page(name: str, group: str, doc_id: str, page_idx: int, *,
                  source_type: str, verified_no_symbols: bool, complete: bool, with_symbol: bool):
        img_path = tmp_path / f"{name}.png"
        _draw_blueprint_sheet(img_path, with_symbol=with_symbol, seed=len(paths) + 1)
        instances = []
        if with_symbol:
            instances.append({
                "instance_id": f"{name}_0",
                "symbol_family": "receptacle",
                "symbol_variant": "duplex",
                "bbox_xyxy": [242.0, 242.0, 278.0, 278.0],
                "uncertain": False,
                "review_status": "accepted",
                "review_source": "human",
                "verified_ground_truth": True,
            })
        payload = {
            "image_path": img_path.name,
            "image_width": 640,
            "image_height": 640,
            "project_family_id": group,
            "document_id": doc_id,
            "page_index": page_idx,
            "source_group_verified": True,
            "source_type": source_type,
            "exhaustive_families": (["receptacle"] if (complete or verified_no_symbols) else []),
            "annotation_completeness_verified": bool(complete and not verified_no_symbols),
            "verified_no_symbols": verified_no_symbols,
            "instances": instances,
        }
        if "synthetic" in source_type:
            payload["wall_only_background_verified"] = True
            payload["synthetic_targets_only"] = True
        ann = tmp_path / f"{name}.json"
        ann.write_text(json.dumps(payload), encoding="utf-8")
        paths.append(ann)

    _add_page("real_partial", "g-partial", "doc_part.pdf", 0,
              source_type="real_partially_labeled", verified_no_symbols=False, complete=False, with_symbol=True)
    for b in range(real_blank_docs):
        _add_page(f"real_blank_{b}", f"g-blank-{b}", f"doc_blank_{b}.pdf", b,
                  source_type="real_construction_pdf_manual", verified_no_symbols=True, complete=False, with_symbol=False)
    for c in range(real_complete_docs):
        _add_page(f"real_comp_{c}", f"g-comp-{c}", f"doc_comp_{c}.pdf", c,
                  source_type="real_construction_pdf_manual", verified_no_symbols=False, complete=True, with_symbol=True)
    # 6 synthetic complete pages: 4 held out for val/test, 2 kept in train.
    for s in range(6):
        _add_page(f"synth_{s}", f"g-synth-{s}", f"synth_{s}", 0,
                  source_type="blueprint_with_neca_svg_devices", verified_no_symbols=False, complete=True, with_symbol=True)

    eval_files = [p for p in paths if p.stem in ("synth_0", "synth_1", "synth_2", "synth_3")]
    mf = build_manifest(
        paths,
        val_fraction=0.25,
        test_fraction=0.25,
        allow_partial_training=True,
        evaluation_files=eval_files,
    )
    mf_path = tmp_path / "source_manifest.json"
    mf_path.write_text(json.dumps(mf), encoding="utf-8")
    return mf_path, mf


def test_tile_export_assigns_sample_roles_and_oversamples_real_pages(tmp_path):
    mf_path, _ = _write_manifest(tmp_path)
    out = tmp_path / "yolo"
    report = export_yolo_tiles(
        mf_path,
        out,
        samples=2,
        train_variants=1,
        allow_partial_training=True,
        train_negative_fraction=1.0,
        blank_negative_fraction=1.0,
        verified_wall_negative_fraction=1.0,
        verified_wall_blank_fraction=1.0,
        real_blank_tiles_per_page=5,
        real_complete_tiles_per_page=6,
        real_blank_negative_fraction=1.0,
        real_complete_negative_fraction=1.0,
        eval_empty_keep_fraction=1.0,
        eval_blank_keep_fraction=1.0,
    )
    assert report["counts"]["train_role_real_blank_images"] == 5
    assert report["counts"]["train_role_real_complete_images"] == 6
    assert report["counts"]["train_role_partial_images"] >= 1
    assert report["counts"]["train_role_synthetic_complete_images"] >= 1

    sup = read_supervision(out / "data.yaml")
    assert sup is not None and sup["has_partial"]
    roles = sup["sample_roles"]
    assert set(roles) == set(sup["samples"])
    assert set(roles.values()) == {"partial", "real_blank", "real_complete", "synthetic_complete"}

    prov = [json.loads(line) for line in (out / "sample_provenance.jsonl").read_text().splitlines() if line.strip()]
    train_prov = {row["sample"]: row for row in prov if row["split"] == "train"}
    assert all(train_prov[s]["sample_role"] == roles[s] for s in roles)

    # Corrupt sample_roles must fail closed.
    sidecar = json.loads((out / "phase2_supervision.json").read_text())
    first_key = next(iter(sidecar["sample_roles"]))
    sidecar["sample_roles"][first_key] = "bogus_role"
    (out / "phase2_supervision.json").write_text(json.dumps(sidecar))
    with pytest.raises(ValueError, match="sample_roles"):
        read_supervision(out / "data.yaml")


def test_score_complete_page_and_deterministic_real_eval_selection(tmp_path):
    class_names = {"receptacle.duplex", "switch.single_pole"}
    gts = [
        {"symbol_family": "receptacle.duplex", "bbox_xyxy": [100.0, 100.0, 140.0, 140.0], "verified_ground_truth": True},
        {"symbol_family": "switch.single_pole", "bbox_xyxy": [300.0, 100.0, 340.0, 140.0], "verified_ground_truth": True},
        {"symbol_family": "receptacle.duplex", "bbox_xyxy": [500.0, 100.0, 540.0, 140.0], "verified_ground_truth": True},
    ]
    dets = [
        {"symbol_family": "receptacle.duplex", "bbox_xyxy": [100.0, 100.0, 140.0, 140.0], "confidence": 0.91},
        {"symbol_family": "receptacle.duplex", "bbox_xyxy": [300.0, 100.0, 340.0, 140.0], "confidence": 0.85},  # wrong class
        {"symbol_family": "receptacle.duplex", "bbox_xyxy": [125.0, 100.0, 165.0, 140.0], "confidence": 0.60},  # near GT FP (IoU > 0.1, < 0.5)
        {"symbol_family": "switch.single_pole", "bbox_xyxy": [20.0, 20.0, 60.0, 60.0], "confidence": 0.72},     # off-label FP
        {"symbol_family": "switch.single_pole", "bbox_xyxy": [40.0, 400.0, 80.0, 440.0], "confidence": 0.30},   # filtered at 0.50
    ]
    s25 = score_complete_page(dets, gts, class_names=class_names, conf_threshold=0.25)
    assert s25 == {
        "gt_total": 3,
        "detections": 5,
        "tp_exact_class": 1,
        "tp_wrong_class": 1,
        "fp_near_gt": 1,
        "fp_off_label": 2,
        "fn_missed": 1,
    }
    s50 = score_complete_page(dets, gts, class_names=class_names, conf_threshold=0.50)
    assert s50["fp_off_label"] == 1

    mf_path, _ = _write_manifest(tmp_path / "sel", real_blank_docs=8, real_complete_docs=6)
    sel_a = select_real_eval_pages(mf_path, max_blank_pages=4, max_complete_pages=3)
    sel_b = select_real_eval_pages(mf_path, max_blank_pages=4, max_complete_pages=3)
    assert sel_a == sel_b
    assert len(sel_a["blank_pages"]) == 4
    assert len(sel_a["complete_pages"]) == 3
    assert sel_a["available_blank_pages"] == 8
    assert sel_a["available_complete_pages"] == 6
    assert len({r["document_id"] for r in sel_a["blank_pages"]}) == 4


def test_scripts_train_ultralytics_plan_only_without_torch(tmp_path):
    import os
    import subprocess

    mf_path, _ = _write_manifest(tmp_path)
    out = tmp_path / "yolo"
    export_yolo_tiles(mf_path, out, samples=2, train_variants=1, allow_partial_training=True)

    repo_root = str(Path(__file__).resolve().parents[1])
    env = dict(os.environ)
    env["PYTHONPATH"] = f"{repo_root}{os.pathsep}{env['PYTHONPATH']}" if env.get("PYTHONPATH") else repo_root
    code = (
        "import sys, scripts_train_ultralytics as train_cli; "
        "sys.argv = ['scripts_train_ultralytics.py', sys.argv[1], "
        "'--real-eval-every', '5', '--real-blank-neg-weight', '7.5']; "
        "train_cli.main(); "
        "assert 'torch' not in sys.modules, 'torch must not be imported in plan-only mode'"
    )
    proc = subprocess.run(
        [sys.executable, "-c", code, str(out / "data.yaml")],
        env=env,
        capture_output=True,
        text=True,
        check=True,
    )
    stdout = proc.stdout
    assert '"real_blank_neg_weight": 7.5' in stdout
    assert '"every_epochs": 5' in stdout
    assert "PLAN ONLY" in stdout


def test_role_aware_bce_and_partial_detection_loss(tmp_path):
    torch = pytest.importorskip("torch")
    ultralytics = pytest.importorskip("ultralytics")
    if ultralytics.__version__ != "8.4.216":
        pytest.skip("actual criterion pinned to ultralytics==8.4.216")
    from ultralytics.cfg import get_cfg
    from blueprint_symbols.partial_supervision import build_masked_detection_trainer
    from blueprint_symbols.partial_yolo import (
        ROLE_PARTIAL,
        ROLE_REAL_BLANK,
        ROLE_REAL_COMPLETE,
        ROLE_SYNTHETIC_COMPLETE,
    )

    mf_path, _ = _write_manifest(tmp_path)
    out = tmp_path / "yolo"
    export_yolo_tiles(
        mf_path, out, samples=2, train_variants=1, allow_partial_training=True,
        real_blank_tiles_per_page=3, real_complete_tiles_per_page=3,
        real_blank_negative_fraction=1.0, real_complete_negative_fraction=1.0,
        eval_empty_keep_fraction=1.0, eval_blank_keep_fraction=1.0,
    )
    trainer_type = build_masked_detection_trainer(out / "data.yaml", loss_cfg=DEFAULT_PHASE2_LOSS_CFG)
    trainer = trainer_type.__new__(trainer_type)
    trainer.args = SimpleNamespace(cls_remap=False)
    trainer.data = {"nc": 1, "channels": 3}
    model = trainer.get_model(cfg="yolo26n-p2.yaml", weights=None, verbose=False)
    model.args = get_cfg()
    criterion = model.init_criterion()
    bce = criterion.one2many.bce

    # Test 4 batch items (partial, synthetic_complete, real_complete, real_blank), 3 anchors, 2 classes.
    logits = torch.tensor([
        [[2.0, -2.0], [2.0, -2.0], [-2.0, 2.0]],   # partial
        [[2.0, -2.0], [2.0, -2.0], [-2.0, 2.0]],   # synthetic_complete
        [[2.0, -2.0], [2.0, -2.0], [2.0, 2.0]],    # real_complete (anchor 0: far bg, anchor 1: near bg, anchor 2: fg cls 0 + wrong cls 1)
        [[2.0, -2.0], [-4.0, -4.0], [-2.0, -2.0]], # real_blank (anchor 0: confident FP logit=2 vs anchor 1: quiet -4)
    ], requires_grad=True)
    scores = torch.zeros_like(logits)
    scores[0, 2, 1] = 1.0  # partial known positive
    scores[2, 2, 0] = 1.0  # real_complete GT positive on anchor 2, class 0 (class 1 is wrong class)

    bce.verified_class_mask = torch.tensor([
        [False, False],
        [True, True],
        [True, True],
        [True, True],
    ])
    bce.sample_role = torch.tensor([
        ROLE_PARTIAL,
        ROLE_SYNTHETIC_COMPLETE,
        ROLE_REAL_COMPLETE,
        ROLE_REAL_BLANK,
    ])
    bce.near_gt_mask = torch.tensor([
        [False, False, False],
        [False, False, False],
        [False, True, True],
        [False, False, False],
    ])
    loss = bce(logits, scores)
    assert loss[0, 0, 0].item() == 0.0  # partial unverified bg stays zero
    assert loss[0, 2, 0].item() > 0.0   # partial foreground anchor wrong class IS penalized
    assert loss[0, 2, 1].item() > 0.0   # partial positive active
    # Blank confident FP penalty >> synthetic bg penalty at identical logit=2.0:
    assert loss[3, 0, 0].item() > loss[1, 0, 0].item() * 4.0
    # Real complete far bg >> near bg at identical logit=2.0:
    assert loss[2, 0, 0].item() > loss[2, 1, 0].item() * 2.0
    # Real complete wrong class at GT anchor >> standard unweighted BCE:
    raw_wrong = torch.nn.functional.binary_cross_entropy_with_logits(logits[2, 2, 1].detach(), scores[2, 2, 1])
    assert loss[2, 2, 1].item() > raw_wrong.item() * 10.0
    loss.sum().backward()
    assert logits.grad[0, 0, 0].item() == 0.0

    # Test PartialYOLODataset + forward/backward + transient tensor cleanup.
    cfg = get_cfg(overrides={"imgsz": 512, "mosaic": 0.0, "mixup": 0.0, "cutmix": 0.0, "copy_paste": 0.0, "fraction": 1.0})
    trainer.args = cfg
    trainer.model = model
    trainer.data = {"nc": 1, "channels": 3, "names": ["receptacle.duplex"]}
    dataset = trainer.build_dataset(str(out / "images" / "train"), mode="train", batch=2)
    items = [dataset[i] for i in range(len(dataset))]
    roles_seen = {int(it["sample_role"].item()) for it in items}
    assert {ROLE_PARTIAL, ROLE_REAL_BLANK, ROLE_REAL_COMPLETE}.issubset(roles_seen)

    blank_item = next(it for it in items if int(it["sample_role"].item()) == ROLE_REAL_BLANK)
    comp_item = next(it for it in items if int(it["sample_role"].item()) == ROLE_REAL_COMPLETE)
    collated = dataset.collate_fn([blank_item, comp_item])
    assert collated["sample_role"].tolist() == [ROLE_REAL_BLANK, ROLE_REAL_COMPLETE]
    collated["img"] = torch.nn.functional.interpolate(
        collated["img"].float(), size=(128, 128), mode="bilinear", align_corners=False
    ) / 255.0
    model.criterion = criterion
    model.train()
    total_loss, _ = model.loss(collated)
    assert torch.isfinite(total_loss).all()
    total_loss.sum().backward()
    assert criterion.one2many.bce.sample_role is None
    assert criterion.one2one.bce.near_gt_mask is None


def test_real_page_eval_torch_and_callback(tmp_path):
    torch = pytest.importorskip("torch")
    ultralytics = pytest.importorskip("ultralytics")
    if ultralytics.__version__ != "8.4.216":
        pytest.skip("real_page_eval test pinned to ultralytics==8.4.216")
    from types import SimpleNamespace as NS
    from ultralytics import YOLO
    from blueprint_symbols.real_page_eval import (
        build_real_eval_callback,
        evaluate_real_pages,
        predict_full_page_torch,
    )
    from blueprint_symbols.ssl_transfer import read_blueprint_weights

    mf_path, mf = _write_manifest(tmp_path, real_blank_docs=2, real_complete_docs=2)
    names = {v: k for k, v in mf["class_ids"].items()}
    yolo = YOLO("yolo26n-p2.yaml")
    # Rebuild model with nc=1 cleanly.
    from ultralytics.nn.tasks import DetectionModel
    model = DetectionModel(cfg="yolo26n-p2.yaml", nc=1, verbose=False).float().cpu()
    model.names = names
    model.train()

    img = np.full((640, 640, 3), 245, np.uint8)
    dets, n_tiles = predict_full_page_torch(model, img, names=names, imgsz=512, overlap=192, confidence=0.25)
    assert isinstance(dets, list) and n_tiles == 4
    assert model.training is True

    metrics = evaluate_real_pages(model, mf_path, names=names, imgsz=512, overlap=192, max_blank_pages=2, max_complete_pages=2)
    assert metrics["blank_pages"] == 2
    assert metrics["complete_pages"] == 2
    assert "f05_exact" in metrics and "real_score" in metrics

    save_dir = tmp_path / "run_eval"
    cb = build_real_eval_callback(
        manifest_path=mf_path, every_epochs=2, max_blank_pages=2, max_complete_pages=2, imgsz=512
    )
    trainer = NS(
        epoch=0,
        epochs=3,
        save_dir=save_dir,
        model=model,
        ema=None,
        data={"names": names},
        args=NS(model="yolo26n-p2.yaml", pretrained=False),
    )
    cb(trainer)  # epoch 1 -> evaluated
    trainer.epoch = 1
    cb(trainer)  # epoch 2 -> evaluated
    trainer.epoch = 2
    cb(trainer)  # final epoch 3 -> evaluated

    lines = (save_dir / "real_eval_metrics.jsonl").read_text(encoding="utf-8").strip().splitlines()
    assert [json.loads(line)["epoch"] for line in lines] == [1, 2, 3]
    assert (save_dir / "real_eval_latest.json").is_file()
    best_real = save_dir / "weights" / "best_real.pt"
    assert best_real.is_file()
    meta = read_blueprint_weights(best_real)
    assert meta["names"] == names
