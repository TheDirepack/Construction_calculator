"""Regression tests for semantic alternatives in SAM exemplar NMS."""
import pytest
from blueprint_symbols.proposal_fusion import merge_sam_proposals


def det(x, cls, score, symbol_id, subtype='unspecified'):
    return {'box_px': [x, 0, x + 20, 20], 'score': score,
            'device_type': cls, 'subtype': subtype, 'symbol_id': symbol_id}


def test_overlapping_prompts_preserve_competing_types():
    merged = merge_sam_proposals([
        det(0, 'switch', .92, 's1'),
        det(1, 'switch', .89, 's2'),  # duplicate semantic label
        det(2, 'receptacle', .81, 'r1'),
        det(100, 'light_fixture', .75, 'l1'),
    ])
    assert len(merged) == 2
    assert merged[0]['same_region_prompt_hits'] == 3
    assert merged[0]['needs_class_review'] is True
    assert [a['device_type'] for a in merged[0]['alternative_labels']] == ['receptacle']
    assert merged[0]['verified_ground_truth'] is False
    assert merged[1]['needs_class_review'] is False


def test_different_subtypes_are_alternatives_not_confident_truth():
    merged = merge_sam_proposals([
        det(0, 'receptacle', .92, 'duplex', subtype='duplex'),
        det(0, 'receptacle', .91, 'gfci', subtype='gfci_duplex'),
    ])
    assert len(merged) == 1
    assert merged[0]['alternative_labels'][0]['subtype'] == 'gfci_duplex'


def test_reject_nonfinite_or_inverted_boxes():
    for box in ([float('nan'), 0, 5, 5], [0, 0, -1, 10]):
        d = det(0, 'switch', .9, 'x')
        d['box_px'] = box
        with pytest.raises(ValueError):
            merge_sam_proposals([d])
    with pytest.raises(ValueError):
        merge_sam_proposals([], iou_threshold=0)
