"""Deterministic CPU-only SAM precision rejection tests."""
from collections import Counter

import numpy as np
import pytest

from blueprint_symbols.sam_precision import (
    evaluate_symbol_box, filter_sam_detections, owns_tile_center, tile_origins,
)


def _dark_symbol_image(h=1100, w=1800):
    img = np.full((h, w, 3), 255, dtype=np.uint8)
    img[200:220, 210:230] = 45
    img[200:220, 500:520] = 45
    img[200:220, 950:970] = 45
    img[100:105, 30:300] = 0
    return img


def test_ink_filter_rejects_white_elongated_and_filled_regions():
    img = _dark_symbol_image()
    assert evaluate_symbol_box(img, [205, 195, 235, 225]) is None
    assert evaluate_symbol_box(img, [700, 700, 740, 740]) == 'blank_region'
    assert evaluate_symbol_box(img, [25, 90, 310, 113]) == 'elongated_line'
    assert evaluate_symbol_box(img, [210, 200, 230, 220]) == 'filled_region'


def test_tile_ownership_covers_overlap_once_even_when_last_tile_is_partial():
    tile_size, overlap, W, H = 1008, 128, 1800, 1100
    tiles = [(x, y) for y in (0, 880) for x in (0, 880)]
    for center in [(10, 10), (900, 200), (944, 500), (1500, 900), (1799, 1099)]:
        box = [center[0] - 1, center[1] - 1, center[0] + 1, center[1] + 1]
        owners = [xy for xy in tiles if owns_tile_center(
            box, tile_x=xy[0], tile_y=xy[1], tile_size=tile_size,
            overlap=overlap, image_width=W, image_height=H,
        )]
        assert len(owners) == 1


def test_filter_drops_overlap_duplicates_blank_and_excess_family_hits():
    img = _dark_symbol_image()
    dets = [
        {'device_type': 'switch', 'score': .9, 'box_px': [205, 195, 235, 225]},
        {'device_type': 'switch', 'score': .85, 'box_px': [495, 195, 525, 225]},
        {'device_type': 'switch', 'score': .84, 'box_px': [945, 195, 975, 225]},
        {'device_type': 'switch', 'score': .8, 'box_px': [700, 700, 740, 740]},
    ]
    stats = Counter()
    out = filter_sam_detections(
        dets, img, tile_x=0, tile_y=0, tile_size=1008, overlap=128,
        max_per_family=1, stats=stats,
    )
    assert len(out) == 1
    assert out[0]['score'] == .9
    assert stats['filtered_family_hit_cap'] == 1
    assert stats['filtered_tile_overlap_duplicate'] == 1
    assert stats['filtered_blank_region'] == 1


def test_bad_precision_settings_fail_without_gpu():
    img = _dark_symbol_image()
    with pytest.raises(ValueError):
        owns_tile_center([100, 100, 120, 120], tile_x=0, tile_y=0,
                         tile_size=100, overlap=100,
                         image_width=1000, image_height=1000)
    assert evaluate_symbol_box(img, [float('nan'), 0, 10, 10]) == 'invalid_box'



def test_global_tile_box_cap_is_independent_of_family_counts():
    img = _dark_symbol_image()
    proposals = [
        {'device_type': 'switch', 'score': .95, 'box_px': [205, 195, 235, 225]},
        {'device_type': 'receptacle', 'score': .94, 'box_px': [495, 195, 525, 225]},
    ]
    stats = Counter()
    out = filter_sam_detections(
        proposals, img, tile_x=0, tile_y=0,
        tile_size=1008, overlap=128, max_total=1,
        stats=stats,
    )
    assert len(out) == 1
    assert stats['filtered_total_hit_cap'] == 1


def test_minimal_tile_grid_covers_pages_without_redundant_tail():
    assert tile_origins(100, 1008, 128) == [0]
    assert tile_origins(1008, 1008, 128) == [0]
    assert tile_origins(1800, 1008, 128) == [0, 880]
    assert tile_origins(2000, 1008, 128) == [0, 880, 1760]
    assert tile_origins(5000, 1008, 128)[-1] + 1008 >= 5000
    for length in range(1, 3400, 139):
        xs = tile_origins(length, 1008, 128)
        assert xs[0] == 0
        assert xs[-1] + 1008 >= length
        assert all(a + 1008 >= b for a, b in zip(xs, xs[1:]))
        assert len(xs) == len(set(xs))
    with pytest.raises(ValueError):
        tile_origins(0, 1008, 128)
