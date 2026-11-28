"""Real-page eval: selection vs locked test vs cross-document sets, blank-page
margin, minimum recall, per-class cutoffs, uncertainty, stress pass, manifest
independence flag and the ONNX metadata (no torch needed: inference is faked)."""
from __future__ import annotations

import hashlib
import json
import pickle
import sys
import types
from pathlib import Path
from types import SimpleNamespace

import cv2
import numpy as np
import pytest

from blueprint_symbols import real_page_eval as rpe
from blueprint_symbols.dataset_manifest import assign_holdout_eval_sets, build_manifest

REC = "receptacle.duplex"
PANEL = "panelboard.main"
NAMES = {0: REC, 1: PANEL}
GT_BOX = [282.0, 282.0, 318.0, 318.0]


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
        "bbox_xyxy": GT_BOX, "uncertain": False, "review_status": "accepted",
        "review_source": "human", "verified_ground_truth": True}]
    page = {"image_path": f"{name}.png", "image_width": 640, "image_height": 640,
            "project_family_id": group, "document_id": f"{group}.pdf", "page_index": idx,
            "source_group_verified": True, "source_type": "real_construction_pdf_manual",
            "exhaustive_families": ["receptacle"], "annotation_completeness_verified": not blank,
            "verified_no_symbols": blank, "instances": inst}
    path = tmp / f"{name}.json"
    path.write_text(json.dumps(page), encoding="utf-8")
    return path


def _manifest(tmp: Path, *, eval_projects: int = 2) -> tuple[Path, dict]:
    tmp.mkdir(parents=True, exist_ok=True)
    seed = iter(range(100, 1000))
    files, held, evals = [], [], []
    for g in range(3):
        files.append(_page(tmp, f"t_blank_{g}", f"g{g}", 0, blank=True, seed=next(seed)))
        files.append(_page(tmp, f"t_comp_{g}", f"g{g}", 1, blank=False, seed=next(seed)))
    for g in range(eval_projects):
        evals.append(_page(tmp, f"e_comp_{g}", f"e{g}", 0, blank=False, seed=next(seed)))
        evals.append(_page(tmp, f"e_blank_{g}", f"e{g}", 1, blank=True, seed=next(seed)))
    for g in range(2):                       # two training drawings, 3 blank + 2 labelled held-out pages each
        for i in range(3):
            held.append(_page(tmp, f"h_blank_{g}_{i}", f"g{g}", 10 + i, blank=True, seed=next(seed)))
        for i in range(2):
            held.append(_page(tmp, f"h_comp_{g}_{i}", f"g{g}", 20 + i, blank=False, seed=next(seed)))
    mf = build_manifest(files + evals, val_fraction=0.25, test_fraction=0.25,
                        evaluation_files=evals, holdout_files=held)
    path = tmp / "source_manifest.json"
    path.write_text(json.dumps(mf), encoding="utf-8")
    return path, mf


# ---------------------------------------------------------------- page sets

def test_assign_holdout_eval_sets_is_deterministic_and_covers_both_sets() -> None:
    rows = ([{"document_id": "a.pdf", "page_index": i, "verified_no_symbols": True, "annotation": f"a#b{i}"}
             for i in range(9)]
            + [{"document_id": "a.pdf", "page_index": 100 + i, "verified_no_symbols": False,
                "annotation": f"a#c{i}"} for i in range(3)]
            + [{"document_id": "b.pdf", "page_index": 1, "verified_no_symbols": True, "annotation": "b#b1"},
               {"document_id": "b.pdf", "page_index": 2, "verified_no_symbols": False, "annotation": "b#c2"},
               {"document_id": "c.pdf", "page_index": 1, "verified_no_symbols": True, "annotation": "c#b1"}]
            + [{"document_id": "d.pdf", "page_index": i, "verified_no_symbols": False, "annotation": f"d#c{i}"}
               for i in range(2)])
    first = {r["annotation"]: r["eval_set"] for r in assign_holdout_eval_sets([dict(r) for r in rows])}
    again = {r["annotation"]: r["eval_set"]
             for r in assign_holdout_eval_sets([dict(r) for r in reversed(rows)])}
    assert first == again                                   # independent of input order
    assert set(first.values()) == {"selection", "test"}
    for doc, kind in (("a#b", True), ("a#c", False), ("d#c", False)):
        assert {v for k, v in first.items() if k.startswith(doc)} == {"selection", "test"}
    for blank in (True, False):
        kinds = [first[r["annotation"]] for r in rows if r["verified_no_symbols"] is blank]
        assert abs(kinds.count("selection") - kinds.count("test")) <= 1


def test_manifest_splits_holdout_and_eval_drawings_are_cross_document(tmp_path: Path) -> None:
    mf_path, mf = _manifest(tmp_path)
    assert mf["val_test_independent"] is True
    assert {r["eval_set"] for r in mf["real_holdout"]} == {"selection", "test"}
    assert sum(mf["real_holdout_sets"].values()) == len(mf["real_holdout"]) == 10
    assert set(mf["real_holdout_sets"]) == {"selection_blank", "test_blank", "selection_complete", "test_complete"}

    sel = rpe.select_real_eval_pages(mf_path, max_blank_pages=100, max_complete_pages=100)
    assert sel["page_pool"] == "real_holdout"
    sets = sel["sets"]
    refs = lambda name: {r["ref"] for r in sets[name]["blank_pages"] + sets[name]["complete_pages"]}
    holdout = {r["annotation"] for r in mf["real_holdout"]}
    assert refs("selection") | refs("test") == holdout and not refs("selection") & refs("test")
    by_ref = {r["annotation"]: r["eval_set"] for r in mf["real_holdout"]}
    assert all(by_ref[r] == "selection" for r in refs("selection"))
    for name in ("selection", "test"):                      # both sets get both page kinds
        assert sets[name]["blank_pages"] and sets[name]["complete_pages"]
    evaluation = {str(p.resolve()) for p in tmp_path.glob("e_*.json")}
    assert refs("cross_document") == evaluation
    assert not refs("cross_document") & (refs("selection") | refs("test"))
    assert [r["ref"] for r in sel["blank_pages"]] == [r["ref"] for r in sets["selection"]["blank_pages"]]


def test_single_evaluation_project_is_flagged_and_can_be_required(tmp_path: Path, capsys) -> None:
    _, mf = _manifest(tmp_path / "one", eval_projects=1)
    assert mf["val_test_independent"] is False
    assert any("val_test_independent: false" in w for w in mf["warnings"])
    assert "val_test_independent = false" in capsys.readouterr().err
    files = sorted((tmp_path / "one").glob("[te]_*.json"))
    evals = [p for p in files if p.name.startswith("e_")]
    with pytest.raises(ValueError, match="same drawings"):
        build_manifest(files, evaluation_files=evals, require_independent_test=True)


# ---------------------------------------------------------- operating point

def _det(cls: str, conf: float, box=None) -> dict:
    return {"symbol_family": cls, "confidence": conf, "bbox_xyxy": box or [500.0, 500.0, 530.0, 530.0]}


def _records(found_confs, blank_dets, *, extra=()):
    gts = [{"symbol_family": REC, "bbox_xyxy": [10.0 + 50 * i, 10.0, 40.0 + 50 * i, 40.0],
            "verified_ground_truth": True} for i in range(len(found_confs))]
    dets = [_det(REC, c, g["bbox_xyxy"]) for c, g in zip(found_confs, gts) if c is not None]
    recs = [rpe._blank_record({"document_id": f"blank{i}"}, d) for i, d in enumerate(blank_dets)]
    recs.append(rpe._complete_record({"document_id": "comp"}, dets + list(extra), gts, {REC, PANEL}))
    return recs


def test_cutoff_clears_highest_blank_score_by_margin() -> None:
    recs = _records([0.9] * 6 + [0.6] * 4, [[_det(PANEL, 0.62), _det(REC, 0.30)], []])
    ch = rpe.choose_operating_point(recs, margin=0.05, min_recall=0.05)
    assert ch["tier"] == 2 and ch["eligible"] and ch["margin_ok"]
    assert ch["blank_max_conf"] == 0.62
    assert ch["chosen"]["threshold"] >= 0.67 and ch["chosen"]["blank_fp"] == 0
    assert ch["margin_gap"] >= 0.05
    assert ch["real_score"] == ch["chosen"]["fp_score"]
    # Without the margin the 0.65 grid cutoff would do; 0.62 + 0.05 is a candidate too.
    assert 0.67 in {r["threshold"] for r in ch["sweep_with_margin_point"]}
    # Margin 0 still clears the blank maximum strictly.
    ch0 = rpe.choose_operating_point(recs, margin=0.0, min_recall=0.05)
    assert ch0["chosen"]["threshold"] > 0.62 and ch0["chosen"]["blank_fp"] == 0


def test_per_class_cutoffs_zero_blank_fp_and_more_recall() -> None:
    recs = _records([0.9] * 6 + [0.6] * 4, [[_det(PANEL, 0.62), _det(REC, 0.30)], [_det(PANEL, 0.55)]])
    pc = rpe.per_class_cutoffs(recs, margin=0.05, min_recall=0.05)
    assert pc["class_cutoffs"][PANEL] == 0.67
    assert pc["point"]["blank_fp"] == 0
    assert pc["point"]["found"] == 10                    # receptacle can stay at 0.35
    assert pc["class_cutoffs"].get(REC, pc["base"]) <= 0.6
    assert pc["blank_max_by_class"] == {PANEL: 0.62, REC: 0.3}
    glob = rpe.choose_operating_point(recs, margin=0.05, min_recall=0.05)
    assert glob["chosen"]["found"] == 6


def test_model_that_finds_nothing_is_never_eligible() -> None:
    ch = rpe.choose_operating_point(_records([None] * 5, [[], []]))
    assert ch["tier"] == 0 and not ch["eligible"] and ch["real_score"] < -1
    blank_only = [rpe._blank_record({"document_id": "b"}, [])]
    ch = rpe.choose_operating_point(blank_only)
    assert not ch["eligible"] and "no fully labelled" in ch["reason"]
    # Below the minimum recall: 1 of 30 found is 0.033 < 0.05.
    ch = rpe.choose_operating_point(_records([0.9] + [None] * 29, [[]]), min_recall=0.05)
    assert not ch["eligible"]
    assert rpe.choose_operating_point(_records([0.9] + [None] * 29, [[]]), min_recall=0.0)["eligible"]


def test_margin_unreachable_is_tier_one_and_scores_below_any_margin_checkpoint() -> None:
    recs = _records([0.9] * 4, [[_det(REC, 0.97)]])
    ch = rpe.choose_operating_point(recs, margin=0.05)
    assert ch["tier"] == 1 and ch["eligible"] and not ch["margin_ok"]
    assert -1.0 <= ch["real_score"] < 0.0
    good = rpe.choose_operating_point(_records([0.9] + [None] * 9, [[]]), margin=0.05)
    assert good["tier"] == 2 and good["real_score"] > ch["real_score"]


def test_sweep_reports_blank_false_detections_per_class_at_every_cutoff() -> None:
    sweep = rpe.threshold_sweep([[_det(PANEL, 0.12), _det(PANEL, 0.3), _det(REC, 0.55)]], [], {REC, PANEL})
    at = {r["threshold"]: r for r in sweep}
    assert at[0.1]["blank_fp_by_class"] == {PANEL: 2, REC: 1}
    assert at[0.25]["blank_fp_by_class"] == {PANEL: 1, REC: 1}
    assert at[0.5]["blank_fp_by_class"] == {REC: 1}
    assert at[0.6]["blank_fp_by_class"] == {}
    assert at[0.1]["blank_pages_with_fp"] == 1


def test_uncertainty_bounds() -> None:
    assert rpe.poisson_upper_bound(0) / 37 == pytest.approx(0.081, abs=5e-4)
    assert rpe.poisson_upper_bound(3) == pytest.approx(7.754, abs=1e-3)
    assert rpe.binomial_upper_bound(0, 37) == pytest.approx(0.0778, abs=5e-4)
    assert rpe.binomial_interval(5, 10) == pytest.approx([0.18709, 0.81291], abs=1e-4)
    assert rpe.binomial_interval(0, 10)[0] == 0.0 and rpe.binomial_interval(10, 10)[1] == 1.0


def test_page_matcher_matches_reference_scoring() -> None:
    gts = [{"symbol_family": REC, "bbox_xyxy": [100.0, 100.0, 140.0, 140.0], "verified_ground_truth": True},
           {"symbol_family": PANEL, "bbox_xyxy": [300.0, 100.0, 340.0, 140.0], "verified_ground_truth": True}]
    dets = [_det(REC, 0.9, [100.0, 100.0, 140.0, 140.0]), _det(REC, 0.8, [301.0, 100.0, 340.0, 140.0]),
            _det(REC, 0.7, [120.0, 100.0, 160.0, 140.0]), _det(PANEL, 0.6)]
    s = rpe.score_complete_page(dets, gts, class_names={REC, PANEL}, conf_threshold=0.5)
    assert (s["tp_exact_class"], s["tp_wrong_class"], s["fp_near_gt"], s["fp_off_label"], s["fn_missed"]) == (1, 1, 1, 1, 0)


# ------------------------------------------------------- end to end (faked)

class _FakeModel:
    names = NAMES

    def half(self):
        return self

    def cpu(self):
        return self

    def eval(self):
        return self


def _fake_detector(monkeypatch, table: dict, default: list, calls: list | None = None):
    """predict_full_page_torch replaced by a lookup on the image's bytes."""
    def fake(model, image, *, names, imgsz=512, overlap=192, confidence=0.25, batch_size=8,
             device=None, max_raw=None):
        key = hashlib.sha256(np.ascontiguousarray(image).tobytes()).hexdigest()
        if calls is not None:
            calls.append(key)
        dets = table.get(key, default)
        dets = dets(model) if callable(dets) else dets
        return [dict(d) for d in dets if d["confidence"] >= confidence], 4
    monkeypatch.setattr(rpe, "predict_full_page_torch", fake)


def _image_key(json_path: Path) -> str:
    page = json.loads(json_path.read_text())
    img = cv2.imread(str(json_path.parent / page["image_path"]), cv2.IMREAD_COLOR)
    return hashlib.sha256(np.ascontiguousarray(img).tobytes()).hexdigest()


def _table(tmp: Path, mf: dict, *, test_blank_fp: float | None = None) -> dict:
    """Every labelled page found at 0.9; selection blank pages carry a panelboard
    at 0.62 and a receptacle at 0.3; test blank pages optionally a high FP."""
    table = {}
    sets = {r["annotation"]: r["eval_set"] for r in mf["real_holdout"]}
    for path in tmp.glob("[he]_*.json"):
        key = _image_key(path)
        ref = str(path.resolve())
        if "_comp_" in path.name:
            table[key] = [_det(REC, 0.9, GT_BOX)]
        elif sets.get(ref) == "selection":
            table[key] = [_det(PANEL, 0.62), _det(REC, 0.30)]
        elif sets.get(ref) == "test" and test_blank_fp is not None:
            table[key] = [_det(PANEL, test_blank_fp)]
        else:
            table[key] = []
    return table


BACK_COMPAT_KEYS = (
    "blank_pages", "available_blank_pages", "blank_fp_025_total", "blank_fp_025_mean", "blank_fp_025_max",
    "blank_fp_050_total", "blank_fp_050_mean", "blank_fp_075_total", "blank_zero_fp_pages_025",
    "blank_zero_fp_pages_050", "blank_max_conf", "worst_blank_pages", "complete_pages",
    "available_complete_pages", "gt_total", "tp_exact_class", "f05_exact", "complete_050", "best_threshold",
    "at_blank_fp_budget", "at_zero_blank_fp", "blank_fp_budget_per_page", "blank_fp_weight", "fp_score_beta",
    "blank_fp_classes_050", "sweep", "real_score")


def test_evaluate_real_pages_selection_test_cross_document_and_stress(tmp_path: Path, monkeypatch) -> None:
    mf_path, mf = _manifest(tmp_path)
    stress_default = [_det(PANEL, 0.95)]                   # re-rendered / shifted pages look different
    _fake_detector(monkeypatch, _table(tmp_path, mf), stress_default)
    m = rpe.evaluate_real_pages(_FakeModel(), mf_path, names=NAMES, stress=True)
    for key in BACK_COMPAT_KEYS:
        assert key in m, key
    assert m["page_pool"] == "real_holdout" and m["val_test_independent"] is True
    assert m["selection"]["used_for_selection"] is True
    assert m["test"]["used_for_selection"] is False and m["test"]["evaluated"]
    assert m["cross_document"]["evaluated"] and m["cross_document"]["documents"] == 2
    assert m["eligible"] and m["selection_tier"] == 2 and m["margin_ok"]
    assert m["blank_max_conf"] == 0.62
    assert m["recommended_confidence"] >= 0.67 and m["blank_margin_gap"] >= 0.05
    cut = m["recommended_class_confidence"]
    assert cut.get(PANEL, m["recommended_class_base_confidence"]) >= 0.67      # clears 0.62 + margin
    assert m["blank_max_by_class"] == {PANEL: 0.62, REC: 0.3}
    assert m["per_class_point"]["blank_fp"] == 0
    assert m["blank_fp_classes_050"] == {PANEL: m["blank_pages"]}     # the 0.50-only view, as before
    assert m["selection"]["blank_fp_classes_by_cutoff"]["0.10"] == {PANEL: m["blank_pages"], REC: m["blank_pages"]}
    chosen = m["chosen_threshold"]["threshold"]
    for name in ("selection", "test", "cross_document"):
        rep = m[name]
        assert rep["at_chosen"]["threshold"] == chosen
        unc = rep["at_chosen"]["uncertainty"]
        assert unc["blank_pages"] == rep["blank_pages"] and unc["truth_symbols"] == rep["truth_symbols"]
        assert unc["blank_fp_per_page_upper95"] == pytest.approx(
            rpe.poisson_upper_bound(rep["at_chosen"]["blank_fp"]) / rep["blank_pages"], abs=1e-5)
        assert sum(d["blank_pages"] for d in rep["per_document"].values()) == rep["blank_pages"]
    assert set(m["selection"]["per_document"]) == {"g0.pdf", "g1.pdf"}
    assert set(m["cross_document"]["per_document"]) == {"e0.pdf", "e1.pdf"}
    stress = m["stress"]
    assert set(stress["variants"]) == {"shift_half_stride", "scale_0.75", "scale_1.5"}
    assert stress["used_for_selection"] is False
    assert stress["worst_blank_fp_at_chosen"] == m["blank_pages"]   # 0.95 >= chosen cutoff on every page
    assert stress["pages"] == m["blank_pages"]

    # The locked test set never moves the choice, only its own reported numbers.
    _fake_detector(monkeypatch, _table(tmp_path, mf, test_blank_fp=0.95), stress_default)
    m2 = rpe.evaluate_real_pages(_FakeModel(), mf_path, names=NAMES)
    for key in ("real_score", "recommended_confidence", "recommended_class_confidence", "chosen_threshold"):
        assert m2[key] == m[key], key
    assert m2["test"]["at_chosen"]["blank_fp"] > m["test"]["at_chosen"]["blank_fp"] == 0
    assert m2["stress"] is None


def test_callback_saves_only_eligible_checkpoints_and_exposes_metrics(tmp_path: Path, monkeypatch) -> None:
    mf_path, mf = _manifest(tmp_path / "data")
    finds = _table(tmp_path / "data", mf)
    mode = {"find": False}
    table = {k: (lambda model, v=v: v if mode["find"] or not any(d["symbol_family"] == REC and d["confidence"] > 0.5
                                                                 for d in v) else [])
             for k, v in finds.items()}
    calls: list = []
    _fake_detector(monkeypatch, table, [], calls)
    saved = {}
    fake_torch = types.ModuleType("torch")
    fake_torch.save = lambda obj, path: (saved.__setitem__("ckpt", obj), Path(path).write_bytes(pickle.dumps(obj["real_eval"])))
    fake_torch.cuda = SimpleNamespace(is_available=lambda: False)
    monkeypatch.setitem(sys.modules, "torch", fake_torch)

    cb = rpe.build_real_eval_callback(manifest_path=mf_path, every_epochs=1, margin=0.05, min_recall=0.05)
    trainer = SimpleNamespace(epoch=0, epochs=3, save_dir=tmp_path / "run", model=_FakeModel(), ema=None,
                              data={"names": NAMES}, args=SimpleNamespace(model="yolo26n-p2.yaml"))
    cb(trainer)                                              # finds nothing: not saved
    best = tmp_path / "run" / "weights" / "best_real.pt"
    assert not best.exists()
    first = trainer.real_eval_metrics
    assert not first["eligible"] and not first["best_real_saved"] and first["real_score"] < -1
    assert first["test"]["evaluated"] is False             # locked sets only for a new best / last epoch
    n_first = len(calls)

    mode["find"] = True
    trainer.epoch = 1
    cb(trainer)                                              # finds: saved, test + cross-document scored
    second = trainer.real_eval_metrics
    assert second["eligible"] and second["best_real_saved"] and best.is_file()
    assert second["test"]["evaluated"] and second["cross_document"]["evaluated"]
    assert len(calls) > 2 * n_first
    ckpt = saved["ckpt"]
    assert ckpt["best_fitness"] == second["real_score"]
    assert ckpt["real_eval"]["selection"]["used_for_selection"] is True
    assert ckpt["real_eval"]["test"]["used_for_selection"] is False

    trainer.epoch = 2
    cb(trainer)                                              # same score: not saved, but last epoch reports
    third = trainer.real_eval_metrics
    assert not third["best_real_saved"] and third["test"]["evaluated"]
    rows = [json.loads(line) for line in (tmp_path / "run" / "real_eval_metrics.jsonl").read_text().splitlines()]
    assert [r["epoch"] for r in rows] == [1, 2, 3]
    assert [r["real_score"] for r in rows] == [first["real_score"], second["real_score"], third["real_score"]]


def test_onnx_metadata_records_selection_vs_test(tmp_path: Path, monkeypatch) -> None:
    from scripts_export_onnx_detector import real_eval_metadata

    mf_path, mf = _manifest(tmp_path)
    _fake_detector(monkeypatch, _table(tmp_path, mf), [])
    m = rpe.evaluate_real_pages(_FakeModel(), mf_path, names=NAMES)
    meta = real_eval_metadata(m)
    assert meta["recommended_confidence"] == m["recommended_confidence"]
    assert meta["recommended_confidence_source"] == "selection_pages_with_blank_margin"
    assert meta["recommended_class_confidence"] == m["recommended_class_confidence"]
    assert meta["recommended_class_base_confidence"] == m["recommended_class_base_confidence"]
    summary = meta["real_eval_summary"]
    assert summary["scores_used_for_selection"] == "selection"
    assert summary["selection"]["used_for_selection"] is True
    assert summary["test"]["used_for_selection"] is False and summary["cross_document"]["evaluated"]
    assert "sweep" not in summary["selection"]
    assert summary["best_threshold"] == m["best_threshold"]          # legacy key still present
    json.dumps(meta)

    assert real_eval_metadata({**m, "eligible": False})["recommended_confidence"] is None
    legacy = real_eval_metadata({"best_threshold": {"threshold": 0.85}, "real_score": 0.5})
    assert legacy["recommended_confidence"] == 0.85
    assert legacy["recommended_confidence_source"].startswith("legacy")
    assert real_eval_metadata(None)["real_eval_summary"] is None
