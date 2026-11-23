"""Hard-negative evaluation distinguishes unreviewed QA from verified negatives."""
import pytest

from scripts_score_negative_controls import score_controls


def _manifest(reviewed=False):
    return {"controls": [
        {"id": "notes", "kind": "text_heavy",
         "detector_negative_verified": reviewed},
        {"id": "wall", "kind": "walls_only",
         "detector_negative_verified": reviewed},
        {"id": "blank", "kind": "blank",
         "detector_negative_verified": reviewed},
    ]}


def _predictions():
    return {"predictions": [
        {"id": "notes", "detections": [
            {"score": .8, "symbol_family": "receptacle"},
            {"score": .2, "symbol_family": "switch"},
        ]},
        {"id": "wall", "detections": [
            {"score": .5, "symbol_family": "downlight"}]},
        {"id": "blank", "detections": []},
    ]}


def test_unreviewed_crops_never_claim_false_positive_ground_truth():
    with pytest.raises(ValueError, match="unreviewed negatives"):
        score_controls(_manifest(), _predictions(), confidence=.25)
    report = score_controls(
        _manifest(), _predictions(), confidence=.25, diagnostic_only=True
    )
    assert report["assessment"] == "diagnostic_unreviewed_not_ground_truth"
    assert report["total_detections"] == 2


def test_verified_hard_negative_overselection_by_content():
    report = score_controls(_manifest(True), _predictions(), confidence=.25)
    assert report["assessment"] == "verified_no_device_false_positives"
    assert report["total_controls"] == 3
    assert report["total_detections"] == 2
    assert report["by_kind"]["text_heavy"]["detections_per_image"] == 1.
    assert report["by_kind"]["walls_only"]["fraction_with_any_detection"] == 1.
    assert report["by_kind"]["blank"]["fraction_with_any_detection"] == 0.
    report = score_controls(_manifest(True), _predictions(), confidence=.9)
    assert report["total_detections"] == 0


def test_score_rejects_missing_predictions_and_invalid_confidence():
    p = _predictions()
    p["predictions"].pop()
    with pytest.raises(ValueError, match="cover each"):
        score_controls(_manifest(True), p)
    with pytest.raises(ValueError, match="confidence"):
        score_controls(_manifest(True), _predictions(), confidence=1.1)
