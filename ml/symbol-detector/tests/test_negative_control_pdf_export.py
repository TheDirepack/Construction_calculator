"""Exact real-PDF no-device review proposals remain QA-only."""
from __future__ import annotations

import json

import cv2
import pytest

fitz = pytest.importorskip("fitz")

from scripts_audit_negative_controls import render_controls


def _pdf(path):
    doc = fitz.open()
    page = doc.new_page(width=1100, height=800)
    page.insert_text((100, 110), "NOTES AND SCHEDULE", fontsize=20)
    page.draw_line((100, 350), (700, 350), width=3)
    doc.save(path)
    doc.close()


def test_exact_negative_pdf_viewport_export_is_not_detector_ground_truth(tmp_path):
    source = tmp_path / "construction.pdf"
    _pdf(source)
    manifest = tmp_path / "controls.json"
    manifest.write_text(json.dumps({"controls": [
        {"id": "notes", "pdf": str(source), "page": 1,
         "rect_pt": [70, 70, 600, 300], "kind": "text_heavy"},
        {"id": "lines", "pdf": str(source), "page": 1,
         "rect_pt": [90, 320, 760, 500], "kind": "walls_only"},
        {"id": "blank", "pdf": str(source), "page": 1,
         "rect_pt": [800, 510, 1000, 720], "kind": "blank"},
    ]}))
    report = render_controls(manifest, tmp_path / "out", size=128, dpi=200)
    assert report["count"] == 3
    assert set(report["kind_counts"]) == {"text_heavy", "walls_only", "blank"}
    exported = json.loads((tmp_path / "out" / "manifest.json").read_text())
    assert exported == report
    for row in report["controls"]:
        assert row["synthetic_suppressed"] is True
        assert row["detector_negative_verified"] is False
        assert row["use_for_detector_evaluation"] is False
        assert row["review_status"] == "pending_exact_crop_exhaustive_review"
        im = cv2.imread(str(tmp_path / "out" / row["output"]))
        assert im is not None and im.shape == (128, 128, 3)
        assert row["aspect_ratio_preserved"] is True
        left, top, right, bottom = row["content_bbox_xyxy"]
        assert 0 <= left < right <= 128 and 0 <= top < bottom <= 128
        source_x0, source_y0, source_x1, source_y1 = row["rect_pt"]
        source_ratio = ((source_x1 - source_x0) /
                        (source_y1 - source_y0))
        output_ratio = (right - left) / (bottom - top)
        assert output_ratio == pytest.approx(source_ratio, rel=.04)
        if top:
            assert (im[:top] == 255).all()
        if left:
            assert (im[:, :left] == 255).all()
    assert report["controls"][2]["ink_fraction_lt_210"] < .001


def test_reject_duplicate_ids_and_outside_pdf_page(tmp_path):
    source = tmp_path / "a.pdf"
    _pdf(source)
    for entries, expected in (
        ([{"id": "a", "pdf": str(source), "page": 1,
           "rect_pt": [30, 30, 150, 160], "kind": "blank"}]*2,
         "nonunique"),
        ([{"id": "a", "pdf": str(source), "page": 1,
           "rect_pt": [900, 30, 1200, 160], "kind": "blank"}],
         "invalid source crop"),
        ([{"id": "a", "pdf": str(source), "page": 1,
           "rect_pt": [0, 0, 150, 160], "kind": "made_up"}],
         "unknown kind"),
    ):
        spec = tmp_path / "manifest.json"
        spec.write_text(json.dumps({"controls": entries}))
        with pytest.raises(ValueError, match=expected):
            render_controls(spec, tmp_path / "output", size=128)
