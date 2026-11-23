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
