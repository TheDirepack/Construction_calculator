"""Symbol crops and hard-negative mining never draw from held-out (real-page
test) or evaluation pages."""
from __future__ import annotations

import json
from pathlib import Path

import cv2
import numpy as np
import pytest

LEGEND_ROWS = ["DUPLEX RECEPTACLE", "3-WAY TOGGLE SWITCH", "EXIT SIGN (SINGLE FACE)",
               "FIRE ALARM HORN", "20A QUAD GFCI RECEPTACLE", "ACCESS CONTROL PANEL"]


def _store(tmp_path: Path):
    """1_blueprints/train_doc.pdf: 5 verified-blank pages (one held out) and a
    complete one, each with a different legend row; evaluation/eval_doc.pdf."""
    fitz = pytest.importorskip("fitz")
    from annotator import label_store

    data_root = tmp_path / "data"
    bp = data_root / "1_blueprints"
    (bp / "evaluation" / "devices").mkdir(parents=True)
    (bp / "devices").mkdir()
    train_pdf = bp / "train_doc.pdf"
    doc = fitz.open()
    for i, text in enumerate(LEGEND_ROWS):
        p = doc.new_page(width=400, height=200)
        p.draw_circle((60, 50), 6 + i, color=(0, 0, 0), width=1.2)
        p.draw_line((50, 50), (70, 50), color=(0, 0, 0), width=1.2)
        p.insert_text((100, 54), text, fontsize=10)
        p.insert_text((40, 180), f"SHEET E{i + 1}", fontsize=9)
    doc.save(str(train_pdf))
    doc.close()
    eval_pdf = bp / "evaluation" / "eval_doc.pdf"
    edoc = fitz.open()
    ep = edoc.new_page(width=400, height=200)
    ep.draw_circle((60, 50), 8, color=(0, 0, 0), width=1.2)
    ep.insert_text((100, 54), "DUPLEX RECEPTACLE", fontsize=10)
    edoc.save(str(eval_pdf))
    edoc.close()

    tdoc = label_store.create_document(bp, train_pdf)
    for page_no in range(1, 6):
        sess, idx, _ = label_store.open_page(tdoc.page_json(page_no))
        sess.save(idx, revision=None, instances=[], rejected=[], metadata={"verified_no_symbols": True})
    sess, idx, _ = label_store.open_page(tdoc.page_json(6))
    sess.save(idx, revision=None, rejected=[], metadata={"page_complete": True}, instances=[{
        "instance_id": "m1", "symbol_family": "receptacle", "symbol_variant": "duplex",
        "bbox_xyxy": [100.0, 100.0, 130.0, 130.0], "uncertain": False, "review_status": "accepted",
        "review_source": "human", "verified_ground_truth": True}])
    edoc_entry = label_store.create_document(bp, eval_pdf)
    sess, idx, _ = label_store.open_page(edoc_entry.page_json(1))
    sess.save(idx, revision=None, instances=[], rejected=[], metadata={"verified_no_symbols": True})
    held = label_store.holdout_pdf_pages(bp, data_root=data_root)
    assert len(held) == 1
    return data_root, bp, train_pdf, eval_pdf, held[0][1]


def test_exclusions_name_held_out_and_evaluation_pages(tmp_path):
    from scripts_extract_legend_symbols import crop_page, source_exclusions

    data_root, bp, train_pdf, eval_pdf, held_page = _store(tmp_path)
    ex = source_exclusions([bp], data_root)
    assert ex.excludes("train_doc.pdf", held_page)
    assert ex.excludes("some/folder/TRAIN_DOC.pdf", held_page)        # by file name, any folder
    assert not ex.excludes("train_doc.pdf", (held_page + 1) % 6)
    assert not ex.excludes("train_doc.pdf")                            # unlabelled pages may be cut
    assert ex.excludes("eval_doc.pdf") and ex.excludes("eval_doc.pdf", 0)
    assert ex.excludes("1_blueprints/evaluation/other.pdf", 3)
    assert crop_page({"page_index": 4}) == 4
    assert crop_page({"container": "p7@120,40"}) == 7
    assert crop_page({"container": "BLOCK_A"}) is None


def test_legend_extraction_skips_held_out_and_evaluation_pages(tmp_path):
    import scripts_extract_legend_symbols as X

    data_root, bp, train_pdf, eval_pdf, held_page = _store(tmp_path)
    ex = X.source_exclusions([bp], data_root)
    crops = tmp_path / "crops"
    crops.mkdir()
    everything = X.extract_pdf(train_pdf, "train_doc.pdf", {}, crops)
    assert held_page in {c["page_index"] for c in everything}           # it WOULD be cut
    found = X.extract_pdf(train_pdf, "train_doc.pdf", {}, crops, exclusions=ex)
    assert found and held_page not in {c["page_index"] for c in found}
    assert all(Path(c["source_root"]) / c["source_file"] == train_pdf.resolve() for c in found)
    assert X.extract_pdf(eval_pdf, "evaluation/eval_doc.pdf", {}, crops, exclusions=ex) == []


def test_composite_drops_library_crops_from_held_out_and_evaluation_pages(tmp_path):
    from blueprint_symbols.neca_svg_compositor import NecaGlyph
    from scripts_composite_neca_svg import drop_excluded_library_glyphs
    from scripts_extract_legend_symbols import source_exclusions

    data_root, bp, _, _, held_page = _store(tmp_path)
    lib = tmp_path / "blueprint_legend_crops"
    lib.mkdir()
    other = (held_page + 1) % 6
    (lib / "crops.json").write_text(json.dumps({"crops": [
        {"id": "held", "source_file": "train_doc.pdf", "page_index": held_page},
        {"id": "ok", "source_file": "train_doc.pdf", "page_index": other},
        {"id": "eval", "source_file": "eval_doc.pdf", "page_index": 0},
        {"id": "blob", "source_file": "sets/train_doc.pdf", "container": f"p{held_page}@5,5"},
        {"id": "cad", "source_file": "cad/blocks.dxf", "container": "DUPLEX"},
    ]}))
    alpha = np.zeros((20, 20), np.uint8)
    cv2.circle(alpha, (10, 10), 6, 255, 1)
    glyphs = [NecaGlyph(f"{lib.name}:{i}", "", "x", f"{lib.name}:auto", lib.name, "receptacle", "duplex",
                        alpha, "receptacle", "duplex") for i in ("held", "ok", "eval", "blob", "cad")]
    kept, dropped = drop_excluded_library_glyphs(glyphs, lib, source_exclusions([bp], data_root))
    assert dropped == 3
    assert sorted(g.symbol_id.split(":")[1] for g in kept) == ["cad", "ok"]
    assert drop_excluded_library_glyphs(glyphs, lib, source_exclusions())[1] == 0


def test_harvest_never_reads_blueprint_drawings_or_evaluation_copies(tmp_path):
    from scripts_extract_legend_symbols import SourceExclusions
    from scripts_harvest_symbols import source_files

    root = tmp_path / "libs"
    (root / "1_blueprints").mkdir(parents=True)
    (root / "cad").mkdir()
    (root / "1_blueprints" / "set.pdf").write_bytes(b"%PDF-1.4")
    (root / "cad" / "eval_doc.pdf").write_bytes(b"%PDF-1.4")
    img = root / "cad" / "duplex.png"
    cv2.imwrite(str(img), np.full((20, 20), 255, np.uint8))
    files = source_files(root, set(), SourceExclusions(evaluation_names=["eval_doc.pdf"]))
    assert [p.name for p, _ in files] == ["duplex.png"]


def test_mining_reads_only_train_verified_blank_pages(tmp_path):
    from annotator import label_store
    from blueprint_symbols.label_schema import read_page
    from scripts_mine_hard_negatives import mining_pages

    data_root, bp, _, _, held_page = _store(tmp_path)
    pages = label_store.labeled_pages(bp, data_root=data_root)
    protected = {read_page(r)["image_sha256"] for role in ("holdout", "evaluation") for r in pages[role]}
    rows = mining_pages([bp], data_root=data_root)
    assert len(rows) == 4                                   # 5 blank, 1 held out; complete page excluded
    assert all(Path(r["image_path"]).is_file() for r in rows)
    assert not {r["image_sha256"] for r in rows} & protected
    assert held_page not in {r["page_index"] for r in rows}
