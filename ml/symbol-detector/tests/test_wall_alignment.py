"""Angled wall attachment geometry shared by procedural and NECA devices."""
from __future__ import annotations

import math

import cv2
import numpy as np
import pytest

from blueprint_symbols.neca_svg_compositor import (
    NecaGlyph, _resize_and_rotate_alpha, composite_neca_page,
)
from blueprint_symbols.wall_alignment import (
    WallSite, detect_wall_sites, snap_symbol_to_wall, wall_rotation_degrees,
)
from scripts_composite_electrical import composite_page


def diagonal_plan():
    page = np.full((512, 512, 3), 255, np.uint8)
    a, b = (65, 120), (440, 332)
    cv2.line(page, a, b, (30, 30, 30), 3, cv2.LINE_AA)
    return page, a, b


def angle_error(rotation: float, a, b) -> float:
    # OpenCV angle is counterclockwise, image wall slope clockwise.
    wall_angle = math.degrees(math.atan2(b[1] - a[1], b[0] - a[0]))
    return abs(((-rotation - wall_angle + 90.0) % 180.0) - 90.0)


def point_line_distance(point, a, b):
    dx, dy = b[0] - a[0], b[1] - a[1]
    return abs(dx * (a[1] - point[1]) - dy * (a[0] - point[0])) / math.hypot(dx, dy)


@pytest.mark.parametrize("a,b", [
    ((65, 120), (440, 332)),   # diagonal
    ((70, 300), (440, 300)),   # horizontal
    ((280, 65), (280, 440)),   # vertical
    ((70, 400), (440, 110)),   # descending
])
def test_detected_wall_rotation_is_parallel_and_snap_avoids_wall(a, b):
    drawing = np.full((512, 512), 255, np.uint8)
    cv2.line(drawing, a, b, 30, 3, cv2.LINE_AA)
    sites, wall_mask = detect_wall_sites(
        drawing, np.random.default_rng(24)
    )
    assert sites, "long wall should produce candidate attachment sites"
    assert wall_mask.any()
    for site in sites[:20]:
        angle = wall_rotation_degrees(site.nx, site.ny)
        assert angle_error(angle, a, b) < 5.0
        glyph = np.zeros((32, 60), np.uint8)
        cv2.rectangle(glyph, (8, 7), (52, 25), 255, 2)
        rotated = _resize_and_rotate_alpha(glyph, 38.0, angle)
        xy = snap_symbol_to_wall(rotated, site, wall_mask)
        if xy is None:
            continue
        x, y = xy
        iy, ix = np.nonzero(rotated > 32)
        assert not np.any(wall_mask[y + iy, x + ix])
        assert point_line_distance((site.x, site.y), a, b) < 5.0
        break
    else:
        pytest.fail("no geometrically valid wall-adjacent placement found")


def test_no_wall_candidate_for_blank_plan():
    blank = np.full((512, 512), 255, np.uint8)
    sites, wall_mask = detect_wall_sites(
        blank, np.random.default_rng(5)
    )
    assert not sites
    assert not wall_mask.any()


class _WallGlyphBank:
    def sample(self, rng):
        glyph = np.zeros((48, 64), np.uint8)
        cv2.rectangle(glyph, (12, 10), (52, 38), 255, 3)
        return NecaGlyph(
            symbol_id="test_rect", code="test", name="wall fixture",
            section="Luminaires", canonical_class="luminaire",
            device_type="light_fixture", subtype="wall_light",
            alpha=glyph,
        )


def test_neca_compositor_rotates_diagonal_wall_symbols():
    page, a, b = diagonal_plan()
    composited, items = composite_neca_page(
        {"instances": []}, page, _WallGlyphBank(),
        np.random.default_rng(72),
        per_page=6, wall_fraction=1.0, drawing_dpi=200.,
        size_px_range=(20., 30.), max_clutter_ink=.2,
        symbol_chaos_probability=0., placement_chaos=0.,
    )
    assert composited.shape == page.shape
    assert items
    for item in items:
        assert item["on_wall"]
        assert angle_error(item["rotation_degrees_image"], a, b) < 5.
        assert item["source"]["wall_contact_method"] == "raster_line_fit"
        assert point_line_distance(item["wall_connection_point"], a, b) < 5.
        assert np.linalg.norm(item["target_vector"]) > 1.
        assert all(0 <= value <= 512 for value in item["bbox_xyxy"])


def test_procedural_compositor_rotates_diagonal_wall_symbols():
    page, a, b = diagonal_plan()
    composited, items = composite_page(
        {"instances": []}, page, np.random.default_rng(81),
        per_page=8, scale_range=(.65, .85), max_ink=.2,
        tag="angle_test", wall_fraction=1.0,
        drawing_dpi=200., match_local_ink=True,
        symbol_chaos_probability=0., placement_chaos=0.,
    )
    assert composited.shape == page.shape
    assert items
    for item in items:
        assert item["on_wall"]
        assert angle_error(item["rotation_degrees_image"], a, b) < 5.
        assert item["source"]["wall_contact_method"] == "raster_line_fit"
        assert point_line_distance(item["wall_connection_point"], a, b) < 5.
        assert np.linalg.norm(item["target_vector"]) > 1.


def test_wall_normal_rotation_matches_original_cardinal_semantics():
    assert wall_rotation_degrees(0, -1) == pytest.approx(0)
    assert wall_rotation_degrees(-1, 0) == pytest.approx(90)
    assert wall_rotation_degrees(0, 1) == pytest.approx(180)
    assert wall_rotation_degrees(1, 0) == pytest.approx(270)
