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
