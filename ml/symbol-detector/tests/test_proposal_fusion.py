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



def test_nested_different_size_boxes_are_one_device():
    # A small tight symbol box and a 26x26 box have IoU < 0.50
    # despite enclosing the same symbol center.
    first = det(100, 'switch', .91, 's1')
    first['box_px'] = [100, 100, 118, 118]
    second = det(96, 'switch', .87, 's2')
    second['box_px'] = [96, 96, 122, 122]
    assert len(merge_sam_proposals([first, second])) == 1


def test_adjacent_real_symbols_are_not_merged_by_containment_gate():
    left = det(100, 'receptacle', .90, 'r1')
    left['box_px'] = [100, 100, 118, 118]
    right = det(120, 'receptacle', .89, 'r2')
    right['box_px'] = [120, 100, 138, 118]
    assert len(merge_sam_proposals([left, right])) == 2
