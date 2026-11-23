"""Real device annotations are positive-only, never proof of completeness."""
from __future__ import annotations

import json

import pytest

fitz = pytest.importorskip("fitz")
from adapters.import_standardized_devices import import_all


def test_real_devices_import_does_not_claim_exhaustive_symbols(tmp_path):
    data = tmp_path / "data"
    data.mkdir()
    pdf = fitz.open()
    pdf.new_page(width=240, height=200)
    pdf.save(data / "site_plan.pdf")
    pdf.close()

    (data / "site_plan_DEVICES.json").write_text(json.dumps({
        "source_file": "site_plan.pdf",
        "devices": [{
            "pdf_page": 1,
            "device_type": "duplex_receptacle",
            "symbol_bbox_pts": [50, 50, 65, 67],
        }],
    }))
    out = tmp_path / "normalized"
    result = import_all(data, out, dpi=72, max_pixels=1_000_000)
    assert result["counts"]["pages"] == 1
    pages = list(out.glob("*.json"))
    assert len(pages) == 1
    doc = json.loads(pages[0].read_text())
    assert doc["annotation_scope"] == "positive_only"
    assert doc["annotation_completeness_verified"] is False
    assert doc["exhaustive_families"] == []
    assert doc["instances"] and doc["instances"][0]["symbol_family"] == "receptacle"
