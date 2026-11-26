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
