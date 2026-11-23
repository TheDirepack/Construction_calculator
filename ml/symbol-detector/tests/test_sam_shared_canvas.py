"""Same-image visual exemplar layout: no hidden loss of distinct base shapes."""
from types import SimpleNamespace

import cv2
import numpy as np
import pytest

from blueprint_symbols.sam_shared_canvas import build_shared_canvas
from blueprint_symbols.sam_precision import owns_tile_center, tile_origins


def _glyph(i):
    ink = np.zeros((30, 30), dtype=np.uint8)
    cv2.circle(ink, (15, 15), 9, 255, 2)
    return SimpleNamespace(symbol_id=f'NECA_{i}', alpha=ink)


@pytest.mark.parametrize('n,target_width', [
    (1, 972),
    (28, 972),
    (29, 936),
    (166, 792),
    (187, 756),
])
def test_shared_layout_retains_all_reference_boxes(n, target_width):
    layout = build_shared_canvas([_glyph(i) for i in range(n)])
    assert layout.panel_rgb.shape == (1008,1008,3)
    assert layout.target_width == target_width
    assert len(layout.reference_boxes) == n
    assert all(
        (0 < cx < 1 and 0 < cy < 1 and w > 0 and h > 0 and
         (cx - w / 2) * 1008 >= target_width and
         (cx + w / 2) * 1008 <= 1008)
        for cx,cy,w,h in layout.reference_boxes
    )


def test_target_pixels_unchanged_and_reference_ink_retained():
    layout = build_shared_canvas([_glyph(i) for i in range(166)])
    target = np.full((850, 790, 3), 255, dtype=np.uint8)
    target[100:130, 200:230] = 10
    target[400:410, 700:710] = 40
    frame = layout.with_target(target)
    np.testing.assert_array_equal(frame[:850,:790], target)
    assert (frame[850:, :layout.target_width] == 255).all()
    assert (frame[:, layout.target_width:] < 200).any()
    assert not np.array_equal(frame[:,layout.target_width:], target[:,:1])


def test_shared_canvas_tiling_owns_each_target_center_once():
    layout = build_shared_canvas([_glyph(i) for i in range(166)])
    w,h = 1937,1330
    xs = tile_origins(w, layout.target_width, 128)
    ys = tile_origins(h, layout.tile_size, 128)
    for xx, yy in [(5, 5), (720, 42), (1280, 1000), (w-2,h-2)]:
        box = [xx-.5, yy-.5, xx+.5, yy+.5]
        count = sum(owns_tile_center(
            box, tile_x=x, tile_y=y, tile_size=layout.tile_size,
            tile_width=layout.target_width, overlap=128,
            image_width=w, image_height=h,
        ) for x in xs for y in ys)
        assert count == 1


def test_shared_canvas_rejects_oversized_bank_instead_of_dropping_categories():
    with pytest.raises(ValueError, match='do not fit'):
        build_shared_canvas([_glyph(i) for i in range(450)],
                            min_target_width=600)
    with pytest.raises(ValueError):
        build_shared_canvas([])
    layout=build_shared_canvas([_glyph(0)])
    with pytest.raises(ValueError):
        layout.with_target(np.full((1008, 1008, 3),255,np.uint8))
