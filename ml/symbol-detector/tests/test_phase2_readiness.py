"""Phase-two cannot use provisional device detections or fake completeness for evaluation."""
from __future__ import annotations

import json
from pathlib import Path

from blueprint_symbols.label_schema import page_ref
from blueprint_symbols.neca_svg_compositor import SAMPLING_GROUPS as CANONICAL_CLASSES
from scripts_phase2_readiness import audit_prepared_evaluation, audit_verified_eval


def _page(path: Path, group: str, *, verified: bool = True,
          positive: bool = True, attestation: bool = False) -> Path:
    box = {"symbol_family": "receptacle", "instance_id": group,
           "bbox_xyxy": [4, 5, 20, 24], "uncertain": not verified,
           "review_status": "accepted" if verified else "unreviewed",
           "review_source": "human" if verified else "sam3",
           "verified_ground_truth": verified}
    page = {
        "project_family_id": group,
        "source_group_verified": True,
        "exhaustive_families": list(CANONICAL_CLASSES) if verified else [],
        "annotation_completeness_verified": verified and not attestation,
        "verified_no_symbols": not positive,
        "instances": [box] if positive else [],
    }
    if attestation:
        page["annotation_completeness_attestation"] = {
            "method": "dataset_or_source_coverage_explicitly_verified_by_user",
            "scope": list(CANONICAL_CLASSES),
        }
    path.write_text(json.dumps(page))
    return path


def test_needs_two_independent_real_positive_groups(tmp_path):
    one = _page(tmp_path / "one.json", "group-a")
    two = _page(tmp_path / "two.json", "group-b")
    report = audit_verified_eval([one, two])
    assert report["ready"]
    assert report["real_positive_groups"] == 2
    assert report["counts"]["real_positives"] == 2
    assert report["verified_groups"] == 2


def test_negative_only_groups_do_not_qualify_for_detection_eval(tmp_path):
    a = _page(tmp_path / "a.json", "group-a", positive=False)
    b = _page(tmp_path / "b.json", "group-b", positive=False)
    result = audit_verified_eval([a, b])
    assert not result["ready"]
    assert result["real_positive_groups"] == 0


def test_provisional_positive_is_not_verified_real_ground_truth(tmp_path):
    good = _page(tmp_path / "a.json", "group-a")
    pending = _page(tmp_path / "b.json", "group-b", verified=False)
    result = audit_verified_eval([good, pending])
    assert not result["ready"]
    assert result["real_positive_groups"] == 1
    assert any("human-attested exhaustive" in p for p in result["problems"])


def test_prior_exhaustiveness_attestation_format_is_supported(tmp_path):
    a = _page(tmp_path / "a.json", "group-a", attestation=True)
    b = _page(tmp_path / "b.json", "group-b", attestation=True)
    assert audit_verified_eval([a, b])["ready"]


def test_verified_negative_with_boxes_rejected(tmp_path):
    a = _page(tmp_path / "a.json", "group-a")
    b = _page(tmp_path / "b.json", "group-b")
    data = json.loads(b.read_text())
    data["verified_no_symbols"] = True
    b.write_text(json.dumps(data))
    assert not audit_verified_eval([a, b])["ready"]


def test_prepared_eval_must_have_real_positives_in_both_splits(tmp_path):
    val = _page(tmp_path / "val.json", "group-a")
    test = _page(tmp_path / "test.json", "group-b")
    mf = tmp_path / "source_manifest.json"
    mf.write_text(json.dumps({
        "class_ids": {k: i for i, k in enumerate(CANONICAL_CLASSES)},
        "splits": {"train": [], "val": [str(val)], "test": [str(test)]},
    }))
    assert audit_prepared_evaluation(mf)["ready"]
    _page(test, "group-b", positive=False)
    result = audit_prepared_evaluation(mf)
    assert not result["ready"]
    assert any("test: zero reviewed real positive" in p for p in result["problems"])


def test_prepared_eval_supports_format2_labels_ref_and_catalog_device_classes(tmp_path):
    def _labels_file(name: str, group: str) -> str:
        lp = tmp_path / f"{name}_labels.json"
        lp.write_text(json.dumps({
            "format_version": 2,
            "pages": {
                "1": {
                    "project_family_id": group,
                    "source_group_verified": True,
                    "exhaustive_families": ["receptacle"],
                    "annotation_completeness_verified": True,
                    "verified_no_symbols": False,
                    "instances": [{
                        "symbol_family": "receptacle",
                        "symbol_variant": "duplex",
                        "instance_id": f"{group}-1",
                        "bbox_xyxy": [10, 12, 30, 34],
                        "uncertain": False,
                        "review_status": "accepted",
                        "review_source": "human",
                        "verified_ground_truth": True,
                    }],
                }
            },
        }))
        return str(page_ref(lp, 1))

    mf = tmp_path / "source_manifest.json"
    mf.write_text(json.dumps({
        "class_ids": {"receptacle.duplex": 0},
        "splits": {
            "train": [],
            "val": [_labels_file("doc_val", "proj-val")],
            "test": [_labels_file("doc_test", "proj-test")],
        },
    }))
    result = audit_prepared_evaluation(mf)
    assert result["ready"], result["problems"]
    assert result["splits"]["val"]["real_verified_positives"] == 1
    assert result["splits"]["test"]["real_verified_positives"] == 1


def test_launch_script_has_no_unsafe_fallback_or_destructive_cleanup():
    root = Path(__file__).parents[1]
    src = (root / "remote" / "run_phase2_train.sh").read_text()
    assert "--allow-partial-training" in src
    assert "--allow-unverified-completeness" not in src
    assert "rm -rf" not in src
    assert "PHASE2_DRY_RUN" in src
    assert "scripts_phase2_readiness.py --prepared-manifest" in src


def test_prepare_training_data_synthetic_eval_default_and_require_real_eval(tmp_path, monkeypatch):
    import sys
    import cv2
    import numpy as np
    import pytest
    import scripts_prepare_training_data as prep
    from annotator import label_store
    from annotator.web import LabelingSession

    monkeypatch.delenv("BPS_LABELS_DIR", raising=False)
    data_root = tmp_path / "blueprints_and_libraries"
    store = data_root / "1_blueprints"
    store.mkdir(parents=True)
    (store / "evaluation").mkdir()

    fitz = pytest.importorskip("fitz")
    train_pdf = store / "real_train.pdf"
    pdf_doc = fitz.open()
    page = pdf_doc.new_page(width=400, height=300)
    page.draw_rect(fitz.Rect(20, 20, 60, 60), color=(0, 0, 0))
    pdf_doc.save(train_pdf)
    pdf_doc.close()
    session = LabelingSession(pdf=train_pdf, blueprints_dir=store, auto_proposals=False)
    session.save(0, revision=None, instances=[{
        "instance_id": "real-1",
        "symbol_family": "receptacle",
        "symbol_variant": "duplex",
        "bbox_xyxy": [20, 20, 60, 60],
        "uncertain": False,
        "review_status": "accepted",
        "review_source": "human",
        "verified_ground_truth": True,
    }], rejected=[], metadata={"page_complete": True})

    def _make_page(name: str, group: str, *, complete: bool) -> Path:
        img = np.full((512, 512, 3), 245, np.uint8)
        cv2.line(img, (40, 40), (470, 40), (20, 20, 20), 3)
        cv2.putText(img, name, (30, 80), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 0, 0), 2)
        img_path = tmp_path / f"{name}.png"
        assert cv2.imwrite(str(img_path), img)
        page_json = {
            "image_path": img_path.name,
            "image_width": 512,
            "image_height": 512,
            "project_family_id": group,
            "source_group_verified": True,
            "exhaustive_families": ["receptacle"] if complete else [],
            "annotation_completeness_verified": complete,
            "verified_no_symbols": False,
            "instances": [{
                "instance_id": f"{name}-1",
                "symbol_family": "receptacle",
                "symbol_variant": "duplex",
                "bbox_xyxy": [120, 120, 160, 160],
                "uncertain": False,
                "review_status": "accepted",
                "review_source": "human",
                "verified_ground_truth": True,
            }],
        }
        ann = tmp_path / f"{name}.json"
        ann.write_text(json.dumps(page_json))
        return ann

    anns = [
        _make_page("comp0", "wall-0", complete=True),
        _make_page("comp1", "wall-1", complete=True),
        _make_page("comp2", "wall-2", complete=True),
        _make_page("part0", "synth-partial-0", complete=False),
    ]
    base_argv = [
        "scripts_prepare_training_data.py",
        "--label-store", str(store),
        "--data-root", str(data_root),
        "--annotations", *(str(p) for p in anns),
        "--train-tiles-per-page", "2",
        "--train-variants", "1",
        "--val-fraction", "0.2",
        "--test-fraction", "0.2",
        "--allow-partial-training",
    ]
    # Strict opt-in fails when 1_blueprints/evaluation has 0 complete pages.
    monkeypatch.setattr(sys, "argv", base_argv + ["--output", str(tmp_path / "require_real"), "--require-real-eval"])
    with pytest.raises(SystemExit) as exc:
        prep.main()
    assert exc.value.code != 0
    assert not (tmp_path / "require_real").exists()

    # Label-store-only without --annotations also fails when 1_blueprints/evaluation has 0 complete pages.
    no_synth_argv = [
        "scripts_prepare_training_data.py",
        "--label-store", str(store),
        "--data-root", str(data_root),
        "--output", str(tmp_path / "no_synth"),
        "--allow-partial-training",
    ]
    monkeypatch.setattr(sys, "argv", no_synth_argv)
    with pytest.raises(SystemExit) as exc2:
        prep.main()
    assert exc2.value.code != 0
    assert not (tmp_path / "no_synth").exists()

    # Default with synthetic annotations succeeds without any special flag, and
    # keeps 1_blueprints/*.pdf training drawings in train.
    out_dir = tmp_path / "default_synth_ok"
    monkeypatch.setattr(sys, "argv", base_argv + ["--output", str(out_dir)])
    prep.main()
    assert (out_dir / "yolo" / "data.yaml").is_file()
    manifest = json.loads((out_dir / "source_manifest.json").read_text())
    assert manifest["splits"]["train"]
    assert manifest["splits"]["val"]
    assert manifest["splits"]["test"]
    assert "file_1_blueprints_real_train" in manifest["split_groups"]["train"]
    assert "file_1_blueprints_real_train" not in manifest["split_groups"]["val"]
    assert "file_1_blueprints_real_train" not in manifest["split_groups"]["test"]
