"""Whole-page geometric distortion with synchronized source boxes."""
from __future__ import annotations

import cv2
import numpy as np
import pytest

from blueprint_symbols.page_chaos import warp_whole_page


def drawing():
    page = np.full((192, 240, 3), 250, np.uint8)
    cv2.rectangle(page, (12, 12), (228, 180), (25, 25, 25), 2)
    cv2.line(page, (88, 12), (88, 180), (30, 30, 30), 2)
    cv2.circle(page, (64, 66), 9, (2, 2, 2), 2)
    cv2.line(page, (54, 66), (74, 66), (2, 2, 2), 2)
    cv2.circle(page, (156, 128), 12, (2, 2, 2), 2)
    return page


BOXES = [[54., 56., 75., 77.], [143., 115., 170., 142.]]


def test_page_warp_identity_and_invalid_parameters():
    page = drawing()
    for kwargs in ({"probability": 0}, {"strength": 0}):
        output, boxes, meta = warp_whole_page(page, np.random.default_rng(4),
                                              boxes=BOXES, **kwargs)
        assert np.array_equal(output, page)
        assert boxes == BOXES
        assert not meta["operations"]
    for kwargs in ({"probability": -0.1}, {"probability": 1.1},
                   {"strength": -1}, {"strength": 2.1}):
        with pytest.raises(ValueError):
            warp_whole_page(page, np.random.default_rng(4), **kwargs)
    with pytest.raises(ValueError):
        warp_whole_page(page, np.random.default_rng(4), boxes=[[0, 0, 999, 10]])


def test_page_warp_deterministic_with_matching_boxes_and_no_mutation():
    page = drawing()
    ref = page.copy()
    a, boxes_a, meta_a = warp_whole_page(
        page, np.random.default_rng(73), boxes=BOXES,
        probability=1, strength=1.4,
    )
    b, boxes_b, meta_b = warp_whole_page(
        page, np.random.default_rng(73), boxes=BOXES,
        probability=1, strength=1.4,
    )
    assert np.array_equal(page, ref)
    assert np.array_equal(a, b)
    assert boxes_a == boxes_b and meta_a == meta_b
    assert a.shape == page.shape and a.dtype == np.uint8


def test_page_warp_all_modes_and_bbox_integrity():
    page = drawing()
    all_ops = set()
    changed = 0
    for seed in range(140):
        result, warped, report = warp_whole_page(
            page, np.random.default_rng(seed), boxes=BOXES,
            probability=1, strength=1.6,
        )
        all_ops.update(report["operations"])
        changed += not np.array_equal(result, page)
        assert result.shape == page.shape
        assert len(warped) == len(BOXES)
        assert report["box_count"] == len(BOXES)
        for x0, y0, x1, y1 in warped:
            assert 0 <= x0 < x1 <= page.shape[1]
            assert 0 <= y0 < y1 <= page.shape[0]
            assert (x1 - x0) * (y1 - y0) >= 20
            # The original symbol remains in each warped bbox: page geometry
            # and the box field underwent exactly the same pixel mapping.
            patch = result[int(y0):int(y1), int(x0):int(x1)]
            assert patch.size > 0 and np.min(patch) < 195
    assert changed >= 100
    assert {"stretch_shear", "projective", "elastic_mesh", "cross_waves",
            "page_curl", "fold_bend", "radial_lens", "local_crumple",
            "feed_slip"} <= all_ops


def test_unlabeled_plan_is_warped_without_fabricating_boxes():
    image = drawing()
    seen = set()
    for seed in range(12):
        result, boxes, meta = warp_whole_page(
            image, np.random.default_rng(seed), probability=1,
            strength=1.0,
        )
        assert result.shape == image.shape
        assert boxes == [] and meta["box_count"] == 0
        seen.update(meta["operations"])
    assert len(seen) >= 4


def test_full_dataset_keeps_page_input_target_alignment(tmp_path):
    import json
    import torch
    from blueprint_symbols.ssl_pretrain import BlueprintSSLDataset

    source = tmp_path / "drawing.png"
    assert cv2.imwrite(str(source), drawing())
    manifest = {"buckets": {"drawings": {
        "weight": 1., "groups": {"project1": [str(source)]},
    }}}
    ds = BlueprintSSLDataset(
        manifest, size=256, items_per_epoch=4, seed=72,
        max_symbols=0, clean_fraction=1.,
        page_chaos_probability=1., page_chaos_strength=1.6,
        return_metadata=True,
    )
    for i in range(4):
        damaged, clean, mask, meta = ds[i]
        assert torch.equal(clean, damaged)  # fully clean SSL view
        assert not mask.any()
        assert meta["full_clean_view"]
        assert meta["page_chaos"]["box_count"] == 0
        # End-to-end clean selection intentionally bypasses page chaos.
        assert not meta["page_chaos"]["operations"]

    # When geometry is enabled, it becomes the clean reconstruction target.
    # An otherwise uncorrupted input is byte-identical to that warped target.
    from blueprint_symbols.ssl_pretrain import apply_runtime_corruptions
    warped, _, report = warp_whole_page(
        drawing(), np.random.default_rng(72), probability=1.,
        strength=1.6,
    )
    assert report["operations"]
    degraded, mask = apply_runtime_corruptions(
        warped, np.random.default_rng(73), clean_fraction=1.,
    )
    assert np.array_equal(degraded, warped)
    assert not mask.any()


def test_warp_reject_reasons_and_symbol_ink_retained_at_high_strength():
    """Guard against symbol-sized blank regions passing area-only validation."""
    page = drawing()
    for seed in range(90):
        distorted, boxes, report = warp_whole_page(
            page, np.random.default_rng(seed), boxes=BOXES,
            probability=1.0, strength=2.0,
        )
        assert report["rejected"] == sum(report["rejection_reasons"].values())
        assert all(isinstance(name, str) and count >= 1
                   for name, count in report["rejection_reasons"].items())
        assert len(boxes) == len(BOXES)
        for original_box, box in zip(BOXES, boxes):
            ox0, oy0, ox1, oy1 = map(int, original_box)
            baseline = int((cv2.cvtColor(
                page[oy0:oy1, ox0:ox1], cv2.COLOR_BGR2GRAY) < 190).sum())
            x0, y0, x1, y1 = map(int, box)
            warped_ink = int((cv2.cvtColor(
                distorted[y0:y1, x0:x1], cv2.COLOR_BGR2GRAY) < 190).sum())
            if baseline >= 6:
                assert warped_ink >= max(1, int(baseline * .28))


def test_reject_invalid_nonfinite_page_controls():
    source = drawing()
    for field in ("probability", "strength"):
        for value in (float("nan"), float("inf"), float("-inf")):
            with pytest.raises(ValueError):
                warp_whole_page(source, np.random.default_rng(7), **{field: value})


def test_inverse_warp_jacobian_rejects_foldovers_not_just_ink_loss():
    from blueprint_symbols.page_chaos import _locally_invertible_remap

    y, x = np.mgrid[:128, :160].astype(np.float32)
    assert _locally_invertible_remap(x, y)
    assert not _locally_invertible_remap(160 - x, y)  # mirror reversal
    # Piecewise compression on half a page can keep the same total ink count.
    xs = x.copy()
    xs[:, 52:106] = 52.0 + (xs[:, 52:106] - 52.0) * 0.03
    xs[:, 106:] = xs[:, 106:] - 52.0
    assert not _locally_invertible_remap(xs, y)
    assert not _locally_invertible_remap(np.full_like(x, np.nan), y)
