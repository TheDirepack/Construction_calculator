"""Legend-sheet symbol cutting: text -> device matching, cutting, the crop library."""
from __future__ import annotations

import json
import sys
from pathlib import Path

import cv2
import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from blueprint_symbols.label_schema import device_for_legend_text  # noqa: E402
from blueprint_symbols.symbol_library import legend_symbol_glyphs, library_glyphs  # noqa: E402


@pytest.mark.parametrize("text,device", [
    ("120V DUPLEX RECEPTACLE", ("receptacle", "duplex")),
    ("GFI DUPLEX RECEPTACLE, NEMA 5-20R", ("receptacle", "duplex_gfci")),     # most specific wins
    ("DOUBLE DUPLEX RECEPTACLE", ("receptacle", "quad")),
    ("3-WAY TOGGLE SWITCH", ("switch", "three_way")),
    ("EXIT SIGN, CEILING MOUNTED", ("luminaire", "exit_sign")),          # no ceiling version
    ("NON-FUSIBLE DISCONNECT SWITCH", ("disconnect", "non_fused")),
    ("COMBINATION VOICE/DATA OUTLET", ("data_outlet", "voice_data_combo")),
    ("HEAT TRACE", None),                         # 'heat' alone is not a heat detector
    ("DUCT SMOKE DETECTOR REMOTE TEST SWITCH", None),   # excluded word
    ("JUNCTION OR PULL BOX", None),               # two devices
    ("CURRENT TRANSFORMER", None),
])
def test_legend_text_names_catalog_device(text, device):
    assert device_for_legend_text(text) == device


def _legend_pdf(path: Path) -> None:
    pymupdf = pytest.importorskip("pymupdf")
    doc = pymupdf.open()
    page = doc.new_page(width=400, height=200)
    page.draw_circle((60, 50), 8, color=(0, 0, 0), width=1.2)
    page.draw_line((52, 50), (68, 50), color=(0, 0, 0), width=1.2)
    page.insert_text((100, 54), "DUPLEX RECEPTACLE", fontsize=10)
    page.draw_rect(pymupdf.Rect(50, 110, 70, 125), color=(0, 0, 0), width=1.2)
    page.insert_text((100, 122), "GENERAL NOTES APPLY", fontsize=10)     # names no device
    doc.save(path)


def test_cuts_symbol_left_of_matching_legend_text(tmp_path):
    import scripts_extract_legend_symbols as X

    pdf = tmp_path / "legend.pdf"
    _legend_pdf(pdf)
    crops_dir = tmp_path / "crops"
    crops_dir.mkdir()
    found = X.extract_pdf(pdf, "legend.pdf", {"pages_or_sheets_with_symbols": "PDF pages 1 of 1"}, crops_dir)
    assert [c["device"] for c in found] == ["receptacle.duplex"]
    x0, y0, x1, y1 = found[0]["bbox_pt"]
    assert 50 <= x0 < 53 and 66 < x1 <= 70 and 40 < y0 < 43 and 57 < y1 < 60
    assert (crops_dir / f"{found[0]['id']}.png").exists()


def test_rough_box_snaps_to_the_symbol_ink(tmp_path):
    pymupdf = pytest.importorskip("pymupdf")
    import scripts_extract_legend_symbols as X

    pdf = tmp_path / "legend.pdf"
    _legend_pdf(pdf)
    page = pymupdf.open(pdf)[0]
    crop, box = X.snap_to_ink(page, [44, 104, 76, 131])
    assert box[0] == pytest.approx(50, abs=1.5) and box[2] == pytest.approx(70, abs=1.5)
    assert crop.min() < 100


def test_library_composites_only_accepted_catalog_devices(tmp_path):
    (tmp_path / "crops").mkdir()
    ids = ["a1", "b2", "c3", "d4"]
    for crop_id in ids:
        img = np.full((20, 20), 255, np.uint8)
        cv2.circle(img, (10, 10), 6, 0, 1)
        cv2.imwrite(str(tmp_path / "crops" / f"{crop_id}.png"), img)
    (tmp_path / "crops.json").write_text(json.dumps({"crops": [
        {"id": "a1", "device": "receptacle.duplex", "source_file": "org/a.pdf"},
        {"id": "b2", "device": "receptacle.duplex", "source_file": "org/a.pdf"},
        {"id": "c3", "device": "luminaire.exit_sign", "source_file": "org/a.pdf"},
        {"id": "d4", "device": "receptacle.duplex", "source_file": "org/a.pdf"},
    ]}))
    (tmp_path / "review.json").write_text(json.dumps({
        "a1": {"status": "accepted"},
        "b2": {"status": "rejected"},
        "c3": {"status": "accepted", "device": "luminaire.exit_sign_ceiling"},
        # d4 unreviewed
    }))
    glyphs, report = legend_symbol_glyphs(tmp_path)
    assert sorted(f"{g.family}.{g.variant}" for g in glyphs) == ["luminaire.exit_sign", "receptacle.duplex"]   # old name, merged class
    assert report["counts"]["not_accepted_device"] == 2

    # pretraining takes every drawing review did not reject, labelled or not
    ssl, _ = library_glyphs(tmp_path, labeled_only=False)
    assert sorted(g.symbol_id.split(":")[1] for g in ssl) == ["a1", "c3", "d4"]
    assert {g.family for g in ssl if g.symbol_id.endswith("d4")} == {""}


def test_bank_mixes_library_drawings_into_pretraining_samples():
    from blueprint_symbols.neca_svg_compositor import NecaGlyph, NecaSymbolBank

    bank = NecaSymbolBank.__new__(NecaSymbolBank)       # skip SVG rasterising
    neca = NecaGlyph("n1", "", "duplex", "s", "receptacle", "receptacle", "duplex",
                     np.ones((4, 4), np.uint8), "receptacle", "duplex")
    bank.by_class = {"receptacle": [neca]}
    bank.active_classes = ["receptacle"]
    bank.class_probs = np.array([1.0])
    bank.devices_only = False
    bank.extra_by_source = {}
    bank.library_share = 0.5
    bank.by_device = {"receptacle.duplex": [neca]}
    extra = [NecaGlyph(f"harvested_symbols:{i}", "", "x", "harvested_symbols:cad_block", "harvested_symbols",
                       "", "", np.ones((4, 4), np.uint8)) for i in range(3)]
    bank.add_glyphs(extra, library_share=0.75)
    rng = np.random.default_rng(0)
    drawn = [bank.sample(rng).symbol_id for _ in range(2000)]
    share = sum(d.startswith("harvested") for d in drawn) / len(drawn)
    assert 0.7 < share < 0.8
    assert "receptacle.duplex" in bank.by_device and len(bank.by_device) == 1   # unlabelled stay out

    bank.devices_only = True
    with pytest.raises(ValueError):
        bank.add_glyphs(extra)          # detector training needs a device on every drawing
