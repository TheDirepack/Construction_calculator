"""CPU-only tests: trailing switch marks are components, not device boxes."""
import numpy as np

from blueprint_symbols.switch_suffix_templates import (
    SuffixTemplate, search_switch_suffixes, split_switch_suffix,
    select_unambiguous_suffix_hits,
)


def _template(letter, attr, value, pattern):
    return SuffixTemplate(
        letter, 'letter_s', attr, value,
        np.asarray(pattern, dtype=np.uint8)*255, 26, 15,
    )


def test_split_ink_after_large_left_hand_base():
    ink = np.zeros((35, 38), dtype=np.uint8)
    ink[0:27, 0:15] = 200
    ink[18:32, 18:26] = 240
    found = split_switch_suffix(ink)
    assert found is not None
    suffix, bh, bw = found
    assert suffix.shape == (14, 8)
    assert bh == 27
    assert bw == 15


def test_two_independent_switch_mark_templates_can_match_one_base():
    page = np.full((250, 250, 3), 255, dtype=np.uint8)
    first = np.array([
        [0,1,1,1,0], [0,0,0,1,0], [0,1,1,1,0],
        [0,0,0,1,0], [0,1,1,1,0],
    ], dtype=np.uint8)*255
    second = np.array([
        [1,0,0,1,0], [1,0,0,1,0], [1,1,1,1,0],
        [1,0,0,1,0], [1,0,0,1,0],
    ], dtype=np.uint8)*255
    page[118:123,117:122] = 255-first[:, :, None]
    page[118:123,131:136] = 255-second[:, :, None]
    refs = [
        SuffixTemplate('S3', 'letter_s', 'pole_configuration',
                       'three_way', first, 26, 15),
        SuffixTemplate('SD', 'letter_s', 'control_features',
                       'dimmer', second, 26, 15),
    ]
    box = [{'device_type':'switch', 'box_px':[100,100,115,126],
            'visual_group':'switch.base_letter_s'}]
    matches = search_switch_suffixes(page, box, refs, min_score=.95)
    assert {(x['attribute'], x['value']) for x in matches} == {
        ('pole_configuration','three_way'), ('control_features','dimmer'),
    }
    # Neither mark was manufactured into a separate device.
    assert all(x['target_family']=='switch' for x in matches)


def test_matching_ignores_unrelated_device_family():
    ref = _template('S3', 'pole_configuration','three_way', [[1,0],[0,1]])
    page = np.full((100,100,3),255,dtype=np.uint8)
    assert search_switch_suffixes(
        page, [{'device_type':'receptacle','box_px':[10,10,20,20],
                'visual_group':'receptacle.wall_duplex_open'}], [ref]) == []



def test_ambiguous_suffix_ink_cannot_reappear_as_third_lower_ranked_label():
    pattern = np.ones((5, 5), dtype=np.uint8) * 255
    refs = [
        SuffixTemplate('S3', 'letter_s', 'pole_configuration', 'three_way', pattern, 26, 15),
        SuffixTemplate('S4', 'letter_s', 'pole_configuration', 'four_way', pattern, 26, 15),
        SuffixTemplate('ST', 'letter_s', 'control_features', 'timer', pattern, 26, 15),
    ]
    # These three meanings all hit the identical pixels. Removing the first
    # two must also block the third, rather than incorrectly attaching it.
    hits = [
        (0.99, refs[0], (30, 20), 5, 5),
        (0.98, refs[1], (30, 20), 5, 5),
        (0.96, refs[2], (30, 20), 5, 5),
        (0.95, refs[2], (60, 20), 5, 5),
    ]
    kept = select_unambiguous_suffix_hits(hits, min_margin=.06)
    assert len(kept) == 1
    assert kept[0][2] == (60, 20)
    assert kept[0][1].value == 'timer'
