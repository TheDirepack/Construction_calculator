"""Explicit non-device negatives must not be contaminated by synthetic positives."""
from __future__ import annotations

import cv2
import numpy as np
import pytest

fitz = pytest.importorskip("fitz")

from blueprint_symbols.ssl_pretrain import BlueprintSSLDataset


@pytest.mark.parametrize("kind", [
    "text_heavy", "walls_only", "sparse_lines", "blank",
    "non_device_geometry",
])
def test_negative_raster_controls_suppress_new_symbols(kind, tmp_path):
    src = tmp_path / "drawing.png"
    drawing = np.full((192, 192, 3), 255, np.uint8)
    for offset in range(35, 120, 12):
        cv2.line(drawing, (12, offset), (160, offset),
                 (35, 45, 56), 2)
    cv2.putText(drawing, "SCHEDULE", (20, 165),
                cv2.FONT_HERSHEY_SIMPLEX, .5, (0, 0, 0), 1)
    assert cv2.imwrite(str(src), drawing)
    entry = {"image_path": str(src), "negative_control_kind": kind,
             "boxes": []}
    manifest = {"buckets": {"hard_negative": {
        "weight": 1., "groups": {"test_project": [entry]}
    }}}
    ds = BlueprintSSLDataset(
        manifest, size=128, items_per_epoch=12, seed=1337,
        clean_fraction=1., max_symbols=16,
        symbol_augment_probability=1., return_metadata=True,
    )
    for i in range(12):
        degraded, clean, mask, meta = ds[i]
        assert degraded.shape == clean.shape == (3, 128, 128)
        assert not mask.any()
        assert meta["negative_control_kind"] == kind
        assert meta["synthetic_suppressed"] is True
        assert meta["detector_negative_verified"] is False
        assert meta["symbol_stats"]["requested"] == 0
        assert meta["symbol_stats"]["placed"] == 0
        assert not meta["symbol_stats"].get("placements")
    assert cv2.imread(str(src)) is not None


def test_negative_pdf_crops_keep_variable_dpi_without_adding_devices(tmp_path):
    source = tmp_path / "non_device.pdf"
    pdf = fitz.open()
    page = pdf.new_page(width=1200, height=900)
    for y in range(100, 600, 30):
        page.insert_text((100, y), "GENERAL NOTES AND SPECIFICATIONS")
    page.draw_line((400, 600), (900, 600), color=(0, 0, 0), width=2)
    pdf.save(source)
    pdf.close()
    entry = {"image_path": str(source), "source_pdf": str(source),
             "pdf_page_index": 0, "negative_control_kind": "text_heavy"}
    manifest = {"buckets": {"hard_negative_pdf": {
        "weight": 1., "groups": {"project": [entry]}
    }}}
    ds = BlueprintSSLDataset(
        manifest, size=128, items_per_epoch=8, seed=223,
        crop_reuse_views=4, crop_bank_size=1,
        fresh_crop_probability=0., max_symbols=16,
        clean_fraction=0., return_metadata=True,
    )
    try:
        dpi = []
        ops = []
        for i in range(8):
            degraded, clean, mask, meta = ds[i]
            assert meta["synthetic_suppressed"] is True
            assert meta["symbol_stats"]["requested"] == 0
            assert meta["symbol_stats"]["placed"] == 0
            assert meta["detector_negative_verified"] is False
            assert degraded.shape == clean.shape == (3, 128, 128)
            dpi.append(meta["pdf_crop"]["effective_dpi"])
            ops.extend(meta["degradation"]["operations"])
        assert len(set(dpi[:4])) == 4
        assert len(ops) > 0  # negative crops still get scanning degradation.
    finally:
        if ds._pdf_sampler is not None:
            ds._pdf_sampler.close()


def test_negative_control_tag_is_explicit_and_fail_closed(tmp_path):
    src = tmp_path / "blank.png"
    assert cv2.imwrite(str(src), np.full((128, 128, 3), 255, np.uint8))
    manifest = {"buckets": {"hard_negative": {
        "groups": {"project": [{
            "image_path": str(src),
            "negative_control_kind": "made_up_negative",
        }]}
    }}}
    ds = BlueprintSSLDataset(
        manifest, size=128, items_per_epoch=2,
        max_symbols=16, return_metadata=True,
    )
    with pytest.raises(ValueError, match="unknown negative control kind"):
        _ = ds[0]
