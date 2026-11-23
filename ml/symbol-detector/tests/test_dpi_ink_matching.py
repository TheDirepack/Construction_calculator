"""Verify real-PDF DPI and synthetic symbol physical scale / ink matching."""
from __future__ import annotations

import cv2
import numpy as np
import pytest

from blueprint_symbols.ink_style import local_drawing_ink_bgr, sized_symbol_range
from blueprint_symbols.neca_svg_compositor import NecaGlyph, composite_neca_page
from blueprint_symbols.ssl_augmentation import compose_ssl_symbols
from scripts_composite_electrical import composite_page


def blue_drawing():
    image = np.full((512, 512, 3), (238, 239, 242), np.uint8)
    blue = (183, 108, 53)  # BGR, not grayscale
    for y in (50, 260, 462):
        cv2.line(image, (12, y), (500, y), blue, 2)
    for x in (62, 265, 445):
        cv2.line(image, (x, 15), (x, 498), blue, 2)
    return image, blue


class _Bank:
    def sample(self, rng):
        alpha = np.zeros((64, 64), np.uint8)
        cv2.circle(alpha, (32, 32), 22, 255, 4)
        cv2.line(alpha, (18, 32), (45, 32), 255, 2)
        return NecaGlyph(
            symbol_id="fixture_receptacle", code="1", name="Duplex",
            section="Outlets and Receptacles", canonical_class="receptacle",
            device_type="receptacle", subtype="duplex", alpha=alpha,
        )


def test_local_colour_follows_nearby_lines_not_distant_colour():
    image = np.full((256, 256, 3), (240, 240, 245), np.uint8)
    blue = (176, 94, 48)
    cv2.line(image, (90, 100), (210, 100), blue, 3)
    cv2.line(image, (112, 20), (112, 190), blue, 2)
    # Distant black title block should NOT determine local device colour.
    cv2.rectangle(image, (1, 1), (35, 36), (0, 0, 0), -1)
    matched = local_drawing_ink_bgr(image, [135, 120, 175, 165])
    assert np.allclose(matched, blue, atol=5), (matched, blue)


def test_neca_symbols_use_pdf_dpi_and_blueprint_line_colour():
    original, blue = blue_drawing()
    sizes = []
    for dpi in (100., 200., 400.):
        result, items = composite_neca_page(
            {"instances": []}, original, _Bank(), np.random.default_rng(123),
            per_page=5, wall_fraction=0.,
            size_px_range=(30., 30.),
            drawing_dpi=dpi, match_local_ink=True,
            symbol_chaos_probability=0., placement_chaos=0.,
        )
        assert items, f"no symbols fitted at {dpi}dpi"
        assert result.shape == original.shape
        assert np.array_equal(original, blue_drawing()[0])
        first_size = items[0]["source"]["target_px"]
        sizes.append(first_size)
        for item in items:
            assert item["source"]["drawing_dpi"] == dpi
            assert np.allclose(item["source"]["ink_color_bgr"], blue, atol=6)
            x0, y0, x1, y1 = item["bbox_xyxy"]
            assert 0 <= x0 < x1 <= 512 and 0 <= y0 < y1 <= 512
    assert sizes[0] == pytest.approx(15., abs=.1)
    assert sizes[1] == pytest.approx(30., abs=.1)
    assert sizes[2] == pytest.approx(60., abs=.1)


def test_procedural_symbols_match_original_line_colour_and_common_dpi():
    original, blue = blue_drawing()
    result, items = composite_page(
        {"instances": []}, original, np.random.default_rng(42),
        per_page=8, scale_range=(.45, 1.2), max_ink=.16, tag="dpi",
        wall_fraction=0., stroke_match=False, canonical_classes=True,
        drawing_dpi=325.5, match_local_ink=True,
        ink_reference=original, symbol_chaos_probability=0.,
    )
    assert items
    assert result.shape == original.shape
    for item in items:
        assert item["source"]["drawing_dpi"] == 325.5
        assert np.allclose(item["source"]["ink_color_bgr"], blue, atol=6)
        x0, y0, x1, y1 = item["bbox_xyxy"]
        assert 0 <= x0 < x1 <= 512 and 0 <= y0 < y1 <= 512


def test_ssl_symbol_range_scales_with_dpi_and_stays_inside_canvas():
    assert sized_symbol_range((12., 80.), 100., (512, 512)) == (6., 40.)
    assert sized_symbol_range((12., 80.), 200., (512, 512)) == (12., 80.)
    assert sized_symbol_range((12., 80.), 400., (512, 512)) == (24., 160.)
    lo, hi = sized_symbol_range((12., 80.), 400., (128, 128))
    assert 0 < lo <= hi <= .38 * 128
    for dpi in (99., 401., float("nan")):
        with pytest.raises(ValueError, match="drawing_dpi"):
            sized_symbol_range((12., 80.), dpi, (512, 512))


def test_ssl_metadata_preserves_dpi_ink_and_bounded_boxes():
    original, blue = blue_drawing()
    seen = 0
    for dpi in (100., 400.):
        result, report = compose_ssl_symbols(
            # Seed 23 legitimately requests zero symbols; use a seed
            # selecting a nonzero requested count to test placed metadata.
            original, np.random.default_rng(1), max_symbols=16,
            drawing_dpi=dpi, neca_bank=_Bank(), procedural_fraction=0.,
            symbol_chaos_probability=0., placement_chaos=0.,
        )
        assert result.shape == original.shape
        assert report["drawing_dpi"] == dpi
        assert report["symbol_size_range_px"] == (
            [6., 40.] if dpi == 100. else [24., 160.]
        )
        for item in report["placements"]:
            seen += 1
            assert item["drawing_dpi"] == dpi
            assert item["ink_color_bgr"] is not None
            assert np.allclose(item["ink_color_bgr"], blue, atol=6)
            x0, y0, x1, y1 = item["bbox_xyxy"]
            assert 0 <= x0 < x1 <= 512
            assert 0 <= y0 < y1 <= 512
    assert seen > 0
