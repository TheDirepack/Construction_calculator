"""1 in 4 verified pages of training drawings are held out for the real-page test."""
from __future__ import annotations

import json
import shutil
from pathlib import Path

import cv2
import numpy as np

from annotator.label_store import split_holdout
from blueprint_symbols.dataset_manifest import build_manifest
from blueprint_symbols.real_page_eval import select_real_eval_pages


def test_split_holdout_quarter_per_drawing_and_kind() -> None:
    pages = ([("a.pdf", n, "blank", f"a#b{n}") for n in range(1, 136)]
             + [("a.pdf", n, "complete", f"a#c{n}") for n in range(200, 213)]
             + [("b.pdf", n, "blank", f"b#b{n}") for n in range(1, 6)]
             + [("b.pdf", n, "complete", f"b#c{n}") for n in range(10, 13)])
    train, held = split_holdout(pages, 4)
    assert sorted(train + held) == sorted(p[3] for p in pages)
    assert not set(train) & set(held)
    count = lambda prefix: sum(h.startswith(prefix) for h in held)
    assert (count("a#b"), count("a#c"), count("b#b"), count("b#c")) == (34, 3, 1, 0)   # <4 pages: none held
    assert set(split_holdout(list(reversed(pages)), 4)[1]) == set(held)       # order-independent
    assert split_holdout(pages, 0) == ([p[3] for p in pages], [])


def _sheet(path: Path, seed: int, *, symbol: bool) -> None:
    rng = np.random.default_rng(seed)
    img = np.full((640, 640, 3), 246, np.uint8)
    for _ in range(10):
        p1 = tuple(int(v) for v in rng.integers(20, 620, 2))
        p2 = tuple(int(v) for v in rng.integers(20, 620, 2))
        cv2.line(img, p1, p2, (20, 20, 20), int(rng.integers(2, 5)))
    cv2.putText(img, f"SHEET {seed}", (40, 60), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (0, 0, 0), 2)
    if symbol:
        cv2.circle(img, (300, 300), 16, (0, 0, 0), 2)
    assert cv2.imwrite(str(path), img)


def _page(tmp: Path, name: str, group: str, idx: int, *, blank: bool, seed: int) -> Path:
    _sheet(tmp / f"{name}.png", seed, symbol=not blank)
    inst = [] if blank else [{
        "instance_id": f"{name}_0", "symbol_family": "receptacle", "symbol_variant": "duplex",
        "bbox_xyxy": [282.0, 282.0, 318.0, 318.0], "uncertain": False, "review_status": "accepted",
        "review_source": "human", "verified_ground_truth": True}]
    page = {"image_path": f"{name}.png", "image_width": 640, "image_height": 640,
            "project_family_id": group, "document_id": f"{group}.pdf", "page_index": idx,
            "source_group_verified": True, "source_type": "real_construction_pdf_manual",
            "exhaustive_families": ["receptacle"], "annotation_completeness_verified": not blank,
            "verified_no_symbols": blank, "instances": inst}
    path = tmp / f"{name}.json"
    path.write_text(json.dumps(page), encoding="utf-8")
    return path


def test_manifest_keeps_holdout_out_of_training_and_eval_uses_only_holdout(tmp_path: Path) -> None:
    files, held = [], []
    for g in range(3):
        files.append(_page(tmp_path, f"t_blank_{g}", f"g{g}", 0, blank=True, seed=10 + g))
        files.append(_page(tmp_path, f"t_comp_{g}", f"g{g}", 1, blank=False, seed=20 + g))
    held.append(_page(tmp_path, "h_blank", "g0", 5, blank=True, seed=30))
    held.append(_page(tmp_path, "h_comp", "g1", 6, blank=False, seed=31))
    # A held-out page that repeats a training sheet (same raster) never counts.
    shutil.copy(tmp_path / "t_blank_1.png", tmp_path / "h_copy.png")
    copy = json.loads((tmp_path / "t_blank_1.json").read_text())
    copy.update(image_path="h_copy.png", page_index=9)
    (tmp_path / "h_copy.json").write_text(json.dumps(copy))
    held.append(tmp_path / "h_copy.json")

    mf = build_manifest(files, val_fraction=0.25, test_fraction=0.25, holdout_files=held)
    trained = {str(Path(a).resolve()) for refs in mf["splits"].values() for a in refs}
    holdout = {r["annotation"] for r in mf["real_holdout"]}
    assert holdout == {str((tmp_path / "h_blank.json").resolve()), str((tmp_path / "h_comp.json").resolve())}
    assert not trained & holdout
    assert any("repeat a training page" in w for w in mf["warnings"])

    mf_path = tmp_path / "source_manifest.json"
    mf_path.write_text(json.dumps(mf), encoding="utf-8")
    sel = select_real_eval_pages(mf_path, max_blank_pages=1000, max_complete_pages=1000)
    assert [r["ref"] for r in sel["blank_pages"]] == [str((tmp_path / "h_blank.json").resolve())]
    assert [r["ref"] for r in sel["complete_pages"]] == [str((tmp_path / "h_comp.json").resolve())]
    assert {r["split"] for r in sel["blank_pages"] + sel["complete_pages"]} == {"holdout"}


def _det(cls: str, box: list[float], conf: float) -> dict:
    return {"symbol_family": cls, "bbox_xyxy": box, "confidence": conf}


def test_sweep_scores_each_checkpoint_at_its_own_best_cutoff() -> None:
    from blueprint_symbols.real_page_eval import threshold_sweep
    classes = {"receptacle.duplex"}
    gts = [{"symbol_family": "receptacle.duplex", "bbox_xyxy": [10.0 + 60 * i, 10.0, 40.0 + 60 * i, 40.0],
            "verified_ground_truth": True} for i in range(4)]

    def checkpoint(shift: float):
        """Same ranking; ``shift`` only makes every score more confident."""
        found = [_det("receptacle.duplex", g["bbox_xyxy"], 0.40 + shift) for g in gts]
        off = [_det("receptacle.duplex", [500.0, 500.0, 530.0, 530.0], 0.20 + shift)]
        blank = [[0.15 + shift, 0.22 + shift]]
        return threshold_sweep(blank, [(found + off, gts)], classes)

    unsure, confident = checkpoint(0.0), checkpoint(0.45)
    best = lambda sweep: max(sweep, key=lambda r: r["f05"])
    best_fp = lambda sweep: max(sweep, key=lambda r: r["fp_score"])
    assert best(unsure)["f05"] == best(confident)["f05"] == 1.0       # all 4 found, nothing false
    assert best_fp(unsure)["fp_score"] == best_fp(confident)["fp_score"] == 1.0
    # A fixed 0.5 cutoff would call these two checkpoints very different:
    at = lambda sweep, t: next(r for r in sweep if r["threshold"] == t)
    assert at(unsure, 0.5)["found"] == 0 and at(confident, 0.5)["found"] == 4
    # Blank-page detections count against precision (2x in precision_weighted).
    low = at(confident, 0.6)
    assert low["blank_fp"] == 2 and low["labelled_fp"] == 1 and low["precision"] == round(4 / 7, 5)
    assert low["precision_weighted"] == round(4 / 9, 5)


def test_fp_first_score_prefers_fewer_false_detections_and_penalizes_blank_fps() -> None:
    from blueprint_symbols.real_page_eval import threshold_sweep

    classes = {"receptacle.duplex"}
    gts = [
        {
            "symbol_family": "receptacle.duplex",
            "bbox_xyxy": [10.0 + 50 * i, 10.0, 40.0 + 50 * i, 40.0],
            "verified_ground_truth": True,
        }
        for i in range(52)
    ]

    # r4b-epoch-5-like tradeoff:
    # at 0.80 -> 15 found, 5 blank FP, 1 labelled FP
    # at 0.90 -> 11 found, 1 blank FP, 0 labelled FP
    dets_r4b = (
        [_det("receptacle.duplex", g["bbox_xyxy"], 0.92) for g in gts[:11]]
        + [_det("receptacle.duplex", g["bbox_xyxy"], 0.82) for g in gts[11:15]]
        + [_det("receptacle.duplex", [400.0, 400.0, 430.0, 430.0], 0.82)]
    )
    blank_r4b = [[0.92], [0.82, 0.82, 0.82, 0.82]]
    sw_r4b = threshold_sweep(blank_r4b, [(dets_r4b, gts)], classes)
    at = lambda sw, t: next(r for r in sw if r["threshold"] == t)
    assert at(sw_r4b, 0.9)["fp_score"] > at(sw_r4b, 0.8)["fp_score"]

    # Clean 0-FP checkpoint (18/52 found, 0 blank FP, 0 labelled FP) beats noisy high-recall (40/52, 15 blank FP).
    clean_dets = [_det("receptacle.duplex", g["bbox_xyxy"], 0.90) for g in gts[:18]]
    noisy_dets = [_det("receptacle.duplex", g["bbox_xyxy"], 0.90) for g in gts[:40]]
    sw_clean = threshold_sweep([[0.05]], [(clean_dets, gts)], classes)
    sw_noisy = threshold_sweep([[0.90] * 15], [(noisy_dets, gts)], classes)
    assert at(sw_clean, 0.9)["fp_score"] > at(sw_noisy, 0.9)["fp_score"]

    # For equal found and equal total FPs (1), blank-page FP has strictly lower fp_score than labelled-page FP.
    one_blank_fp = threshold_sweep([[0.90]], [(clean_dets, gts)], classes)
    one_label_fp = threshold_sweep(
        [[0.05]],
        [(clean_dets + [_det("receptacle.duplex", [400.0, 400.0, 430.0, 430.0], 0.90)], gts)],
        classes,
    )
    assert at(one_blank_fp, 0.9)["fp_score"] < at(one_label_fp, 0.9)["fp_score"]


def test_verified_blank_backgrounds_exclude_holdout_and_eval_and_produce_complete_train(tmp_path: Path) -> None:
    from annotator import label_store
    from blueprint_symbols.composite_supervision import composite_supervision
    from scripts_composite_neca_svg import verified_blank_training_backgrounds

    data_root = tmp_path / "data"
    bp = data_root / "1_blueprints"
    ev = bp / "evaluation"
    bp.mkdir(parents=True)
    ev.mkdir(parents=True)
    (bp / "devices").mkdir()
    (ev / "devices").mkdir()

    # Create a real multi-page dummy PDF (6 pages) in 1_blueprints -> 1 blank held out, 4 train blanks, 1 complete.
    import fitz

    train_pdf = bp / "train_doc.pdf"
    doc = fitz.open()
    for i in range(6):
        p = doc.new_page(width=400, height=400)
        p.insert_text((40, 40 + 20 * i), f"SHEET {i + 1}")
    doc.save(str(train_pdf))
    doc.close()

    # Evaluation PDF (1 page) -> must never be selected.
    eval_pdf = ev / "eval_doc.pdf"
    edoc = fitz.open()
    ep = edoc.new_page(width=400, height=400)
    ep.insert_text((40, 40), "EVAL SHEET")
    edoc.save(str(eval_pdf))
    edoc.close()

    tdoc = label_store.create_document(bp, train_pdf)
    for page_no in range(1, 6):
        sess, idx, _ = label_store.open_page(tdoc.page_json(page_no))
        sess.save(idx, revision=None, instances=[], rejected=[], metadata={"verified_no_symbols": True})
    # Page 6: verified complete with 1 box
    sess, idx, _ = label_store.open_page(tdoc.page_json(6))
    sess.save(
        idx,
        revision=None,
        instances=[{
            "instance_id": "m1",
            "symbol_family": "receptacle",
            "symbol_variant": "duplex",
            "bbox_xyxy": [100.0, 100.0, 130.0, 130.0],
            "uncertain": False,
            "review_status": "accepted",
            "review_source": "human",
            "verified_ground_truth": True,
        }],
        rejected=[],
        metadata={"page_complete": True},
    )

    edoc_entry = label_store.create_document(bp, eval_pdf)
    sess, idx, _ = label_store.open_page(edoc_entry.page_json(1))
    sess.save(idx, revision=None, instances=[], rejected=[], metadata={"verified_no_symbols": True})

    pages = label_store.labeled_pages(bp, data_root=data_root)
    assert len(pages["holdout"]) == 1
    assert len(pages["train"]) == 5
    from blueprint_symbols.label_schema import read_page
    held_shas = {read_page(r)["image_sha256"] for r in pages["holdout"]}
    eval_shas = {read_page(r)["image_sha256"] for r in pages["evaluation"]}

    bg_dir = tmp_path / "vb_bgs"
    written = verified_blank_training_backgrounds([bp], bg_dir, 6, data_root=data_root, seed=2026)
    assert len(written) == 6
    for p in written:
        bg_doc = json.loads(p.read_text(encoding="utf-8"))
        assert bg_doc["verified_no_symbols"] is True
        assert bg_doc["source_type"] == "real_verified_blank_background"
        assert bg_doc["wall_only_background_verified"] is True
        assert bg_doc["synthetic_targets_only"] is True
        assert bg_doc["image_sha256"] not in held_shas
        assert bg_doc["image_sha256"] not in eval_shas
        sup = composite_supervision(
            bg_doc,
            [{
                "instance_id": "syn_0",
                "symbol_family": "receptacle",
                "symbol_variant": "duplex",
                "bbox_xyxy": [20.0, 20.0, 50.0, 50.0],
                "uncertain": False,
                "review_status": "accepted",
                "review_source": "human",
                "verified_ground_truth": True,
            }],
            generated_families=["receptacle", "switch"],
        )
        assert sup["annotation_completeness_verified"] is True
        assert sup["verified_no_symbols"] is False
        assert set(sup["exhaustive_families"]) == {"receptacle", "switch"}
