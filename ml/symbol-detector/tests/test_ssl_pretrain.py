"""Regression tests for blueprint-only runtime SSL pretraining pipeline."""
from __future__ import annotations

import numpy as np
import pytest

pytest.importorskip("torch")

from blueprint_symbols.ssl_pretrain import (
    apply_geometric_variability,
    apply_runtime_corruptions,
    group_is_holdout,
    sample_random_crop,
    sample_label_guided_crop,
)


def _fake_blueprint(h: int = 800, w: int = 1000) -> np.ndarray:
    img = np.full((h, w, 3), 248, dtype=np.uint8)
    img[100:700, 120:124] = 20
    img[100:104, 120:880] = 20
    img[350:353, 120:880] = 30
    img[220:260, 400:440] = 15
    return img


def test_runtime_corruptions_preserve_shape_and_variability():
    img = _fake_blueprint()
    rng1 = np.random.default_rng(101)
    rng2 = np.random.default_rng(202)

    c1 = apply_geometric_variability(sample_random_crop(img, 512, rng1), rng1)
    d1, m1 = apply_runtime_corruptions(c1, rng1, mask_prob=1.0)
    d2, m2 = apply_runtime_corruptions(c1, rng2, mask_prob=1.0)

    assert c1.shape == (512, 512, 3) and c1.dtype == np.uint8
    assert d1.shape == (512, 512, 3) and d1.dtype == np.uint8
    assert m1.shape == (512, 512) and m1.dtype == np.float32
    assert 0.05 < float(m1.mean()) < 0.90
    assert not np.array_equal(d1, d2)
    assert not np.array_equal(d1, c1)


def test_group_holdout_is_deterministic_and_bounded():
    flags = [group_is_holdout(f'project_{i}', val_fraction=0.10) for i in range(1000)]
    assert flags == [group_is_holdout(f'project_{i}', val_fraction=0.10) for i in range(1000)]
    frac = sum(flags) / len(flags)
    assert 0.06 < frac < 0.14


def test_clean_views_are_truly_unmodified():
    image = _fake_blueprint(320, 480)
    for seed in range(10):
        degraded, mask = apply_runtime_corruptions(
            image, np.random.default_rng(seed), clean_fraction=1.0
        )
        assert np.array_equal(degraded, image)
        assert not mask.any()


def test_degradation_strength_is_random_and_keeps_shapes():
    image = _fake_blueprint(320, 480)
    different = 0
    for seed in range(20):
        degraded, mask = apply_runtime_corruptions(
            image, np.random.default_rng(seed), clean_fraction=0.0
        )
        assert degraded.shape == image.shape and degraded.dtype == np.uint8
        assert mask.shape == image.shape[:2]
        different += not np.array_equal(degraded, image)
    assert different >= 15


def test_device_centered_crop_preserves_target_and_random_fallback():
    image = np.full((900, 1400, 3), 255, np.uint8)
    # A verified small device at a fixed location.
    cv = (900, 400, 930, 430)
    image[cv[1]:cv[3], cv[0]:cv[2]] = 0
    rng = np.random.default_rng(32)
    for _ in range(12):
        crop = sample_label_guided_crop(
            image, 512, rng, [list(cv)], focus_probability=1.0
        )
        assert crop.shape == (512, 512, 3)
        assert (crop < 30).any()


def test_runtime_symbol_count_zero_through_sixteen_and_nonoverwriting():
    from blueprint_symbols.ssl_augmentation import compose_ssl_symbols

    image = np.full((512, 512, 3), 255, dtype=np.uint8)
    counts = set()
    for seed in range(40):
        result, stats = compose_ssl_symbols(
            image, np.random.default_rng(seed),
            max_symbols=16, min_size_px=12, max_size_px=48,
        )
        assert result.shape == image.shape and result.dtype == image.dtype
        assert 0 <= stats['placed'] <= stats['requested'] <= 16
        assert stats['placed'] == stats['neca'] + stats['procedural']
        assert np.all(image == 255)  # composition never mutates source
        counts.add(stats['requested'])
    assert 0 in counts
    assert 16 in counts
    assert len(counts) >= 10


def test_labeled_pretraining_manifest_provides_boxes(tmp_path):
    import json
    import cv2
    from scripts_pretrain_ssl import build_pretrain_manifests

    annotations = []
    for i in range(16):
        im = _fake_blueprint(320, 480)
        png = tmp_path / f'annotated_{i}.png'
        assert cv2.imwrite(str(png), im)
        ann = tmp_path / f'annotated_{i}.json'
        ann.write_text(json.dumps({
            'image_path': png.name, 'source_group_verified': True,
            'project_family_id': f'unique_building_{i}',
            'image_width': 480, 'image_height': 320,
            'instances': [
                {'bbox_xyxy': [110, 100, 140, 130], 'symbol_family': 'receptacle'},
                {'bbox_xyxy': [200, 200, 220, 220], 'uncertain': True},
            ],
        }))
        annotations.append(ann)
    train, val, audit = build_pretrain_manifests(
        tmp_path, tmp_path / 'work', labeled_annotations=annotations,
    )
    assert audit['included_counts'].get('labeled_devices_train', 0) > 0
    assert 'labeled_devices' in train['buckets']
    first = next(iter(train['buckets']['labeled_devices']['groups'].values()))[0]
    assert first['boxes'] == [[110., 100., 140., 130.]]
    assert len(first['image_path']) > 0


def test_run_fingerprint_stable_and_detects_changed_configuration():
    import argparse
    from scripts_pretrain_ssl import run_fingerprint

    args = argparse.Namespace(
        imgsz=512, batch=4, epochs=60, items_per_epoch=8192,
        val_items=512, optimizer="MuSGD", lr=0.005, workers=2,
        pool_val_fraction=0.1, seed=2026, max_symbols=16,
        symbol_min_px=12.0, symbol_max_px=80.0,
        symbol_augment_probability=1.0, procedural_fraction=0.25,
        clean_fraction=0.2, labeled_focus_probability=0.65,
        symbol_chaos_probability=0.88, symbol_chaos_strength=1.0,
        placement_chaos=0.85,
        neca_dir=None,
    )
    train = {"buckets": {"a": {"groups": {"project_a": ["a.png"]}}}}
    val = {"buckets": {"b": {"groups": {"project_b": ["b.png"]}}}}
    original = run_fingerprint(train, val, args)
    assert original == run_fingerprint(train, val, args)
    args.clean_fraction = 0.5
    assert original != run_fingerprint(train, val, args)
    args.clean_fraction = 0.2
    assert original != run_fingerprint(
        {"buckets": {"a": {"groups": {"project_a": ["changed.png"]}}}},
        val, args)


def test_ssl_train_validation_isolation_guard_rejects_overlaps(tmp_path):
    from scripts_pretrain_ssl import assert_ssl_partition_isolation

    image_a = str(tmp_path / "a.png")
    image_b = str(tmp_path / "b.png")
    train = {"buckets": {"real": {"groups": {"same_project": [image_a]}}}}
    val = {"buckets": {"other": {"groups": {"same_project": [image_b]}}}}
    with pytest.raises(ValueError, match="source isolation"):
        assert_ssl_partition_isolation(train, val)

    val = {"buckets": {"other": {"groups": {"different_project": [image_a]}}}}
    with pytest.raises(ValueError, match="source isolation"):
        assert_ssl_partition_isolation(train, val)

    val = {"buckets": {"other": {"groups": {"different_project": [image_b]}}}}
    assert_ssl_partition_isolation(train, val) is None


def test_pretrain_rejects_no_steps_and_invalid_degradation_settings():
    import argparse
    from scripts_pretrain_ssl import validate_pretrain_settings

    args = argparse.Namespace(
        batch=4, epochs=60, items_per_epoch=8192, val_items=512,
        workers=2, imgsz=512, pool_val_fraction=.10, lr=0.005,
        max_symbols=16, symbol_min_px=12.0, symbol_max_px=80.0,
        symbol_augment_probability=1.0, procedural_fraction=0.25,
        clean_fraction=0.2, labeled_focus_probability=0.65,
    )
    validate_pretrain_settings(args)
    args.items_per_epoch = 2
    with pytest.raises(ValueError, match="items-per-epoch"):
        validate_pretrain_settings(args)
    args.items_per_epoch = 8192
    args.clean_fraction = float("nan")
    with pytest.raises(ValueError, match="clean_fraction"):
        validate_pretrain_settings(args)
    args.clean_fraction = 0.2
    args.symbol_min_px = 90
    with pytest.raises(ValueError, match="symbol size"):
        validate_pretrain_settings(args)


def test_sparse_ink_gets_meaningful_gradient_in_reconstruction_loss():
    import torch
    from blueprint_symbols.ssl_pretrain import BlueprintSSLLoss

    target = torch.ones((1, 3, 64, 64), dtype=torch.float32)
    target[:, :, 31:33, 31:33] = 0.0  # four dark pixels in ~4,096
    predicted = torch.ones_like(target, requires_grad=True)
    mask = torch.zeros((1, 1, 64, 64), dtype=torch.float32)
    loss, stats = BlueprintSSLLoss(mask_weight=0, edge_weight=0)(predicted, target, mask)
    assert loss.item() > 1.0  # ink must not be washed out by white background
    loss.backward()
    assert predicted.grad is not None
    assert predicted.grad[:, :, 31:33, 31:33].abs().sum().item() > 0

    # A blank page still yields finite, nonzero loss if incorrectly predicted.
    blank_target = torch.ones((1, 3, 64, 64), dtype=torch.float32)
    blank_pred = torch.zeros_like(blank_target)
    blank_loss, _ = BlueprintSSLLoss()(blank_pred, blank_target, mask)
    assert torch.isfinite(blank_loss)
    assert blank_loss.item() >= 2.0


def test_ssl_loss_rejects_invalid_region_weights():
    from blueprint_symbols.ssl_pretrain import BlueprintSSLLoss
    with pytest.raises(ValueError):
        BlueprintSSLLoss(ink_weight=0)
    with pytest.raises(ValueError):
        BlueprintSSLLoss(mask_weight=-1)


def test_corruption_metadata_tracks_clean_and_independent_categories():
    image = _fake_blueprint(128, 128)
    categories = set()
    severities = set()
    for seed in range(64):
        degraded, mask, metadata = apply_runtime_corruptions(
            image, np.random.default_rng(seed), clean_fraction=0.20,
            return_metadata=True,
        )
        assert degraded.shape == image.shape
        assert mask.shape == image.shape[:2]
        assert metadata["severity"] in ("clean", "light", "moderate", "heavy", "extreme")
        assert isinstance(metadata["operations"], list)
        if metadata["severity"] == "clean":
            assert not metadata["operations"] and np.array_equal(degraded, image)
        else:
            categories.update(metadata["operations"])
        severities.add(metadata["severity"])
    assert "clean" in severities
    assert len(severities) >= 3
    assert len(categories) >= 8


def test_neca_source_fingerprint_tracks_actual_svg_strokes(tmp_path):
    import json
    from scripts_pretrain_ssl import neca_source_fingerprint

    root = tmp_path / "bank"
    root.mkdir()
    (root / "one.svg").write_text('<svg><circle r="4"/></svg>')
    (root / "symbols.json").write_text(json.dumps({
        "symbols": [{"id": "symbol_1", "file": "one.svg"}],
    }))
    first = neca_source_fingerprint(root)
    assert first == neca_source_fingerprint(root)
    (root / "one.svg").write_text('<svg><circle r="6"/></svg>')
    assert neca_source_fingerprint(root) != first

    (root / "symbols.json").write_text(json.dumps({
        "symbols": [{"id": "symbol_1", "file": "../outside.svg"}],
    }))
    with pytest.raises(ValueError, match="escapes"):
        neca_source_fingerprint(root)



def test_new_warp_controls_change_resume_fingerprint():
    import argparse
    from scripts_pretrain_ssl import run_fingerprint

    args = argparse.Namespace(
        imgsz=512, batch=4, epochs=2, items_per_epoch=128, val_items=32,
        optimizer="MuSGD", lr=.005, workers=0, pool_val_fraction=.1, seed=99,
        max_symbols=16, symbol_min_px=12., symbol_max_px=80.,
        symbol_augment_probability=1., procedural_fraction=.25,
        clean_fraction=.2, labeled_focus_probability=.65,
        symbol_chaos_probability=.88, symbol_chaos_strength=1.,
        placement_chaos=.85, neca_dir=None,
    )
    manifest = {"buckets": {"real": {"groups": {"building": ["a.png"]}}}}
    reference = run_fingerprint(manifest, {"buckets": {}}, args)
    args.symbol_chaos_probability = .40
    assert run_fingerprint(manifest, {"buckets": {}}, args) != reference
    args.symbol_chaos_probability = .88
    args.symbol_chaos_strength = 1.8
    assert run_fingerprint(manifest, {"buckets": {}}, args) != reference
    args.symbol_chaos_strength = 1.
    args.placement_chaos = .20
    assert run_fingerprint(manifest, {"buckets": {}}, args) != reference


def test_clean_fraction_bypasses_symbol_page_and_scan_damage(tmp_path):
    import cv2
    import torch
    from blueprint_symbols.ssl_pretrain import BlueprintSSLDataset

    png = tmp_path / "sheet.png"
    assert cv2.imwrite(str(png), _fake_blueprint(320, 320))
    manifest = {"buckets": {"drawings": {"groups": {"project": [str(png)]}}}}
    dataset = BlueprintSSLDataset(
        manifest, size=256, items_per_epoch=12, seed=123,
        clean_fraction=1.0, max_symbols=16, symbol_augment_probability=1.0,
        symbol_chaos_probability=1.0, page_chaos_probability=1.0,
        return_metadata=True,
    )
    placed_total = 0
    for index in range(len(dataset)):
        damaged, clean, mask, info = dataset[index]
        assert info["full_clean_view"] is True
        assert info["geometric_variability_enabled"] is False
        assert torch.equal(clean, damaged)
        assert not bool(mask.any())
        assert info["page_chaos"]["operations"] == []
        assert info["degradation"]["operations"] == []
        placements = info["symbol_stats"].get("placements", [])
        placed_total += len(placements)
        assert all(not p["symbol_chaos"].get("operations") for p in placements)
    assert placed_total > 0


def test_no_clean_views_means_scans_and_page_warps_are_eligible(tmp_path):
    import cv2
    from blueprint_symbols.ssl_pretrain import BlueprintSSLDataset

    png = tmp_path / "sheet.png"
    assert cv2.imwrite(str(png), _fake_blueprint(320, 320))
    manifest = {"buckets": {"drawings": {"groups": {"project": [str(png)]}}}}
    dataset = BlueprintSSLDataset(
        manifest, size=256, items_per_epoch=8, seed=492,
        clean_fraction=0.0, max_symbols=0,
        page_chaos_probability=1.0, page_chaos_strength=1.5,
        return_metadata=True,
    )
    modes = set()
    for index in range(len(dataset)):
        _, _, _, info = dataset[index]
        assert info["full_clean_view"] is False
        assert info["degradation"]["severity"] != "clean"
        assert info["degradation"]["operations"]
        modes.update(info["page_chaos"]["operations"])
    assert modes


def test_masked_reconstruction_does_not_erase_known_inserted_symbols():
    """Masking half a label can teach reconstruction; erasing all cannot."""
    sheet = _fake_blueprint(256, 256)
    boxes = [[18., 20., 52., 56.], [100., 92., 132., 124.],
             [175., 173., 202., 197.]]
    rescued = 0
    for seed in range(32):
        degraded, mask, meta = apply_runtime_corruptions(
            sheet, np.random.default_rng(seed), mask_prob=1.0,
            clean_fraction=0.0, return_metadata=True,
            protected_boxes=boxes,
        )
        assert degraded.shape == sheet.shape
        assert 0 <= float(mask.mean()) <= .65
        for x0, y0, x1, y1 in boxes:
            fraction = float(mask[int(y0):int(y1), int(x0):int(x1)].mean())
            assert fraction <= .6001
        rescued += "masked_symbol_visibility_guard" in meta["operations"]
    assert rescued > 0


def test_mask_visibility_guard_rejects_nonfinite_symbol_boxes():
    sheet = _fake_blueprint(128, 128)
    with pytest.raises(ValueError, match="protected symbol bbox"):
        apply_runtime_corruptions(
            sheet, np.random.default_rng(42), mask_prob=1.0,
            clean_fraction=0.0,
            protected_boxes=[[float("nan"), 1., 8., 8.]],
        )
