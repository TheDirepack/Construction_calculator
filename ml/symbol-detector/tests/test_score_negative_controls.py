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



def test_symbol_family_false_positive_rates_use_all_negative_images():
    """Two same-class boxes in one image count as one hit image."""
    predictions = _predictions()
    predictions["predictions"][0]["detections"].append(
        {"score": .95, "symbol_family": "receptacle"}
    )
    report = score_controls(_manifest(True), predictions, confidence=.25)
    assert report["total_detections"] == 3
    assert report["verified_controls"] == 3
    receptacles = report["by_family"]["receptacle"]
    assert receptacles["images"] == 3
    assert receptacles["detections"] == 2
    assert receptacles["overselected"] == 1
    assert receptacles["detections_per_image"] == pytest.approx(2 / 3, abs=1e-4)
    assert receptacles["fraction_with_any_detection"] == pytest.approx(
        1 / 3, abs=1e-4
    )
    assert report["by_kind_family"]["text_heavy"]["receptacle"]["images"] == 1
    assert report["by_kind_family"]["text_heavy"]["receptacle"]["detections"] == 2
    assert report["by_kind_family"]["walls_only"]["downlight"]["images"] == 1
    assert report["by_kind_family"]["blank"] == {}
    assert "switch" not in report["by_family"]  # below confidence


@pytest.mark.parametrize("bad_value", ["true", "yes", 1, None, False])
def test_only_literal_true_is_verified_negative(bad_value):
    manifest = _manifest(True)
    manifest["controls"][0]["detector_negative_verified"] = bad_value
    with pytest.raises(ValueError, match="unreviewed negatives"):
        score_controls(manifest, _predictions())
    diagnostic = score_controls(
        manifest, _predictions(), diagnostic_only=True
    )
    assert diagnostic["verified_controls"] == 2
    assert diagnostic["assessment"] == "diagnostic_unreviewed_not_ground_truth"


@pytest.mark.parametrize("bad_score", ["0.9", True, float("nan"),
                                        float("inf"), -0.01, 1.01, None])
def test_reject_nonfinite_or_invalid_detection_confidence(bad_score):
    predictions = _predictions()
    predictions["predictions"][0]["detections"][0]["score"] = bad_score
    with pytest.raises(ValueError, match="invalid detection score"):
        score_controls(_manifest(True), predictions)


def test_reject_unclassified_detections_and_missing_detection_list():
    predictions = _predictions()
    predictions["predictions"][0]["detections"][0].pop("symbol_family")
    with pytest.raises(ValueError, match="symbol_family"):
        score_controls(_manifest(True), predictions)
    predictions = _predictions()
    predictions["predictions"][0].pop("detections")
    with pytest.raises(ValueError, match="detections list"):
        score_controls(_manifest(True), predictions)


def test_reject_empty_control_manifest_and_invalid_control_ids():
    with pytest.raises(ValueError, match="nonempty"):
        score_controls({"controls": []}, {"predictions": []})
    controls = _manifest(True)
    controls["controls"][0]["id"] = ""
    with pytest.raises(ValueError, match="control id"):
        score_controls(controls, _predictions())
