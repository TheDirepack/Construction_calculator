"""Bounded symbol-level chaos and independent placement regression suite."""
from __future__ import annotations

import cv2
import numpy as np
import pytest

from blueprint_symbols.symbol_chaos import distort_symbol_alpha, sample_free_origin
from blueprint_symbols.scan_chaos import add_scan_chaos
from blueprint_symbols.neca_svg_compositor import composite_neca_page
from scripts_composite_electrical import composite_page


def _device_mask() -> np.ndarray:
    alpha = np.zeros((64, 64), dtype=np.uint8)
    cv2.circle(alpha, (32, 32), 23, 255, 4)
    cv2.line(alpha, (15, 32), (49, 32), 255, 3)
    cv2.line(alpha, (32, 15), (32, 49), 255, 2)
    return alpha


def _drawing(size: int = 512) -> np.ndarray:
    image = np.full((size, size, 3), 250, dtype=np.uint8)
    cv2.rectangle(image, (40, 40), (size-40, size-40), (20, 20, 20), 3)
    cv2.line(image, (size//2, 40), (size//2, size-40), (20, 20, 20), 3)
    return image


def test_symbol_chaos_has_independent_axis_and_nonlinear_warp_modes():
    source = _device_mask()
    original = source.copy()
    shapes = set()
    operations = set()
    changed = 0
    for seed in range(180):
        output, report = distort_symbol_alpha(
            source, np.random.default_rng(seed), probability=1., strength=1.5,
        )
        assert output.ndim == 2 and output.dtype == np.uint8
        assert 3 <= min(output.shape) and max(output.shape) <= 145
        assert np.count_nonzero(output > 32) >= max(5, round((source > 32).sum() * .32))
        shapes.add(output.shape)
        operations.update(report["operations"])
        changed += output.shape != source.shape or not np.array_equal(output, source)
        assert report["severity"] in ("unchanged", "subtle", "moderate", "heavy", "extreme")
        assert report["rejected"] >= 0
    assert np.array_equal(source, original)
    assert changed >= 120
    assert len(shapes) >= 20
    assert {"axis_stretch_shear", "perspective", "elastic_mesh", "cross_ripple",
            "fold", "pinch_twist", "stroke_gain", "dry_pen",
            "misregistration", "low_dpi", "ink_fade", "blur"} <= operations


def test_symbol_chaos_is_deterministic_and_zero_is_identity():
    source = _device_mask()
    a, ma = distort_symbol_alpha(source, np.random.default_rng(42), strength=1.7)
    b, mb = distort_symbol_alpha(source, np.random.default_rng(42), strength=1.7)
    assert ma == mb and np.array_equal(a, b)
    for options in ({"probability": 0}, {"strength": 0}):
        original, report = distort_symbol_alpha(
            source, np.random.default_rng(42), **options,
        )
        assert np.array_equal(original, source)
        assert report["operations"] == []


def test_symbol_chaos_invalid_parameters_fail_closed():
    source = _device_mask()
    for params in ({"probability": -0.1}, {"probability": 1.2},
                   {"strength": -1}, {"strength": 2.5}):
        with pytest.raises(ValueError):
            distort_symbol_alpha(source, np.random.default_rng(1), **params)
    with pytest.raises(ValueError):
        distort_symbol_alpha(source.astype(np.float32), np.random.default_rng(1))


def test_free_origin_strategies_bounded_and_diverse():
    rng = np.random.default_rng(1234)
    occupied = [[100, 100, 125, 125], [300, 300, 350, 340]]
    kinds = set()
    for _ in range(300):
        x, y, mode = sample_free_origin(
            rng, (512, 640), (24, 44), occupied, chaos=1.,
        )
        assert 4 <= x <= 640-44-5
        assert 4 <= y <= 512-24-5
        kinds.add(mode)
    assert len(kinds) >= 6


def test_scan_chaos_extra_categories_preserve_size_and_clean_source():
    original = _drawing(160)
    baseline = original.copy()
    categories = set()
    changes = 0
    for seed in range(120):
        output, operations = add_scan_chaos(
            original, np.random.default_rng(seed), strength=1.3,
        )
        assert output.dtype == original.dtype and output.shape == original.shape
        assert isinstance(operations, list)
        categories.update(operations)
        changes += not np.array_equal(original, output)
    assert np.array_equal(original, baseline)
    assert changes >= 90
    assert len(categories) >= 9
    plain, ops = add_scan_chaos(original, np.random.default_rng(1), strength=0.)
    assert ops == [] and np.array_equal(plain, original)


class _SymbolBank:
    def __init__(self):
        self.alpha = _device_mask()

    def sample(self, rng):
        from blueprint_symbols.neca_svg_compositor import NecaGlyph
        return NecaGlyph(
            symbol_id="test_duplex", code="1", name="Duplex Receptacle",
            section="Outlets and Receptacles", canonical_class="receptacle",
            device_type="receptacle", subtype="duplex", alpha=self.alpha,
        )


def test_chaotic_neca_compositing_preserves_valid_boxes_and_wall_clearance():
    original = _drawing()
    page, placed = composite_neca_page(
        {"instances": []}, original, _SymbolBank(), np.random.default_rng(25),
        per_page=12, wall_fraction=.55, size_px_range=(20, 65),
        symbol_chaos_probability=1., symbol_chaos_strength=1.3,
        placement_chaos=1.,
    )
    assert np.array_equal(original, _drawing())  # input never changed
    assert page.shape == original.shape
    assert len(placed) >= 3
    for item in placed:
        box = item["bbox_xyxy"]
        assert 0 <= box[0] < box[2] <= original.shape[1]
        assert 0 <= box[1] < box[3] <= original.shape[0]
        assert item["symbol_chaos"]["severity"]
        assert item["source"]["placement_mode"]
        if item["symbol_chaos"]["operations"]:
            assert item["source"]["label_method"] == "warped_bbox_only"
            assert item["anchors"]["wall_connection_point"]["annotated"] is False


def test_chaotic_procedural_compositing_preserves_boxes():
    image = _drawing()
    page, placed = composite_page(
        {"instances": []}, image, np.random.default_rng(76),
        per_page=10, scale_range=(.4, 1.0), max_ink=.16, tag="test",
        stroke_match=False, wall_fraction=.30, canonical_classes=True,
        symbol_chaos_probability=1., symbol_chaos_strength=1.4,
        placement_chaos=1.,
    )
    assert page.shape == image.shape
    assert len(placed) >= 3
    for item in placed:
        assert item["symbol_chaos"]["severity"]
        if item["symbol_chaos"]["operations"]:
            assert item["source"]["label_method"] == "warped_bbox_only"
            assert not any(anchor["annotated"] for anchor in item["anchors"].values())
        x0, y0, x1, y1 = item["bbox_xyxy"]
        assert x1 > x0 >= 0 and y1 > y0 >= 0
        assert x1 <= 512 and y1 <= 512


def test_symbol_rejects_fragmentation_and_missing_loop_despite_ink_survival():
    from blueprint_symbols.symbol_chaos import _valid

    line = np.zeros((64, 64), dtype=np.uint8)
    cv2.line(line, (10, 32), (54, 32), 255, 6)
    dots = np.zeros_like(line)
    dots[3:61:4, 3:61:4] = 255
    assert _valid(line, line)
    # Hundreds of isolated dots have comparable total ink and box dimensions,
    # yet no longer represent the original stroke.
    assert not _valid(dots, line)

    ring = np.zeros_like(line)
    cv2.circle(ring, (32, 32), 21, 255, 3)
    broken = ring.copy()
    cv2.rectangle(broken, (28, 8), (36, 17), 0, -1)
    assert _valid(ring, ring)
    assert not _valid(broken, ring)
