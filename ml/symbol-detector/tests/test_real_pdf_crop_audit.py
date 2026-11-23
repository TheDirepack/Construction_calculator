"""Local-only real-PDF QA command regression (does not add training records)."""
from __future__ import annotations

import json

import cv2
import pytest

fitz = pytest.importorskip("fitz")

from scripts_audit_real_pdf_crops import audit_pdfs


def test_audit_reports_dpi_and_writes_side_by_side_without_training_labels(tmp_path):
    pdf_path = tmp_path / "sample_electrical_plan.pdf"
    pdf = fitz.open()
    for page_number in range(2):
        page = pdf.new_page(width=900, height=650)
        page.draw_rect(fitz.Rect(90, 100, 700, 530),
                       color=(.12, .14, .20), width=1)
        page.draw_circle(fitz.Point(260, 220), 15,
                         color=(.30, .36, .42), width=1)
        page.insert_text((100, 150), f"LIGHTING PLAN {page_number}",
                         fontsize=12)
    pdf.save(pdf_path)
    pdf.close()

    output = tmp_path / "qa"
    result = audit_pdfs(
        [pdf_path], output, pages=[2], samples_per_page=3,
        size=128, seed=9, max_symbols=4,
    )
    assert result["purpose"] == "real_pdf_visual_qa_only_not_training"
    assert result["sample_count"] == 3
    assert {record["page"] for record in result["records"]} == {2}
    assert 100 <= result["dpi_min"] <= result["dpi_max"] <= 400
    assert json.loads((output / "report.json").read_text()) == result
    assert not list(output.glob("*.pdf"))
    for row in result["records"]:
        assert row["pdf_crop"]["sampling_policy"] == "adaptive_ink_guided_v2"
        image = cv2.imread(str(output / row["output"]))
        assert image is not None
        assert image.shape == (128, 256, 3)
        assert 0 <= row["symbol_placed"] <= row["symbol_requested"] <= 4


def test_audit_rejects_out_of_range_page(tmp_path):
    pdf_path = tmp_path / "one.pdf"
    pdf = fitz.open()
    pdf.new_page(width=900, height=650)
    pdf.save(pdf_path)
    pdf.close()
    with pytest.raises(IndexError, match="Page 3"):
        audit_pdfs([pdf_path], tmp_path / "qa", pages=[3],
                   samples_per_page=1, max_symbols=0)

def test_audit_identically_named_pdfs_keep_separate_output_images(tmp_path):
    inputs = []
    for subdir in ("first", "second"):
        directory = tmp_path / subdir
        directory.mkdir()
        path = directory / "plans.pdf"
        pdf = fitz.open()
        page = pdf.new_page(width=900, height=650)
        page.draw_line((30, 30), (600, 450),
                       color=(0, 0, 0), width=2)
        pdf.save(path)
        pdf.close()
        inputs.append(path)
    output = tmp_path / "qa"
    result = audit_pdfs(inputs, output, pages=[1], samples_per_page=1,
                        size=128, max_symbols=0)
    assert result["pdf_count"] == 2
    filenames = [row["output"] for row in result["records"]]
    assert len(set(filenames)) == 2
    assert all((output / name).is_file() for name in filenames)
