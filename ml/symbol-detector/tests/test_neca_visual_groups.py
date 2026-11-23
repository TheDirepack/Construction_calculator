"""Visual reference grouping is structural; 3-way/etc are not base classes."""
from dataclasses import dataclass

from blueprint_symbols.neca_visual_groups import (
    SWITCH_SUFFIX_ATTRIBUTES,
    audit_neca_visual_groups,
    choose_visual_base_prototypes,
    classify_neca_artwork,
)


def _entry(sid, section, *, kind='printed_alternative', name='symbol'):
    return {'id': sid, 'section': section, 'kind': kind, 'name': name}


def test_switch_base_notation_vs_suffix_compositions():
    base = classify_neca_artwork(_entry(
        'NECA_4_001__letter_s', 'Switches and Sensors'))
    three = classify_neca_artwork(_entry(
        'NECA_4_003__letter_s', 'Switches and Sensors'))
    four = classify_neca_artwork(_entry(
        'NECA_4_004__letter_s', 'Switches and Sensors'))
    dollar = classify_neca_artwork(_entry(
        'NECA_4_001__dollar', 'Switches and Sensors'))
    dimmer = classify_neca_artwork(_entry(
        'NECA_4_018', 'Switches and Sensors', kind='numbered_row'))
    assert base['role'] == 'base'
    assert three['role'] == four['role'] == 'composite'
    assert base['visual_group'] == three['visual_group'] == four['visual_group']
    assert dollar['visual_group'] != base['visual_group']
    assert dimmer['visual_group'] not in (base['visual_group'], dollar['visual_group'])
    assert three['attributes'] == {'pole_configuration': 'three_way'}
    assert four['attributes'] == {'pole_configuration': 'four_way'}


def test_receptacle_core_shapes_all_distinct():
    groups = {
        classify_neca_artwork(_entry(
            f'NECA_3_{code}', 'Outlets and Receptacles',
            kind='numbered_row',
        ))['visual_group']
        for code in ['001','002','003','004','005','006','007','014','015']
    }
    assert len(groups) == 9


def test_modifier_and_routing_artwork_are_not_independent_devices():
    lum = classify_neca_artwork(_entry(
        'NECA_2_100__surface_mounted__round', 'Luminaires - Mounting Modifiers'))
    wire = classify_neca_artwork(_entry(
        'NECA_1_106', 'Raceways - Indicators', kind='numbered_row'))
    assert lum['role'] == 'modifier'
    assert wire['role'] == 'excluded'


@dataclass(frozen=True)
class Prompt:
    symbol_id: str
    visual_group: str
    prompt_role: str
    native_vectors: bool = True
    kind: str = 'printed_alternative'


def test_visual_group_selection_keeps_every_distinct_core_shape():
    refs = [
        Prompt('S3', 'switch.base_letter_s', 'composite'),
        Prompt('S', 'switch.base_letter_s', 'base'),
        Prompt('dollar', 'switch.base_dollar', 'base'),
        Prompt('single', 'receptacle.wall_single_open', 'base'),
        Prompt('duplex', 'receptacle.wall_duplex_open', 'base'),
        Prompt('quad', 'receptacle.wall_quad_open', 'base'),
        Prompt('panel_b', 'distribution.hatched_panel', 'base', False),
        Prompt('panel_a', 'distribution.hatched_panel', 'base', True),
    ]
    chosen = choose_visual_base_prototypes(refs, per_group=1)
    assert len(chosen) == 6
    assert 'S3' not in {x.symbol_id for x in chosen}
    assert 'panel_a' in {x.symbol_id for x in chosen}


def test_every_source_entry_receives_exactly_one_role_and_group():
    entries = [
        _entry('NECA_4_001__letter_s', 'Switches and Sensors'),
        _entry('NECA_4_003__letter_s', 'Switches and Sensors'),
        _entry('NECA_3_002', 'Outlets and Receptacles', kind='numbered_row'),
        _entry('NECA_2_100__surface_mounted', 'Luminaires - Mounting Modifiers'),
        _entry('NECA_1_106', 'Raceways - Indicators', kind='numbered_row'),
    ]
    audit = audit_neca_visual_groups(entries)
    assert audit['source_entries'] == len(entries)
    assert sum(audit['roles'].values()) == len(entries)
    assert audit['visual_group_count'] == 2
    assert len(SWITCH_SUFFIX_ATTRIBUTES) >= 15
