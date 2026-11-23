"""Catalog and prompt-budget regression tests; no GPU/model download."""
from __future__ import annotations

import json

import pytest

from blueprint_symbols.neca_prompt_catalog import (
    choose_neca_prompts, list_neca_prompt_specs, SamNecaPrompt,
)


def _item(id, name, section, kind='numbered_row'):
    return {'id': id, 'code': id, 'name': name, 'section': section,
            'kind': kind, 'file': id + '.svg', 'native_vectors': True}


def test_parent_alternatives_modifiers_and_routing_are_not_device_prompts(tmp_path):
    root = tmp_path / 'neca'
    root.mkdir()
    entries = [
        _item('NECA_2_001', 'Luminaire: (drawn to scale)', 'Luminaires'),
        _item('NECA_2_001__alternative_1', 'Luminaire: (drawn to scale)', 'Luminaires', 'printed_alternative'),
        _item('NECA_2_002', 'Accent directional arrow', 'Luminaires - Orientation Modifiers'),
        _item('NECA_1_206', 'Cabletray size as indicated', 'Raceways - Boxes and Busways'),
        _item('NECA_3_002', 'Duplex convenience receptacle', 'Outlets and Receptacles'),
    ]
    for e in entries: (root / e['file']).write_text('<svg/>')
    (root / 'symbols.json').write_text(json.dumps({'symbols': entries}))
    specs, report = list_neca_prompt_specs(root)
    assert {p.symbol_id for p in specs} == {'NECA_2_001__alternative_1', 'NECA_3_002'}
    assert report['excluded']['parent_has_individual_alternatives'] == 1
    assert report['excluded']['non_device_or_modifier'] == 1
    assert report['excluded']['routing_or_long_assembly'] == 1
    assert specs[1].category == 'power_outlet'
    assert specs[1].subcategory == 'duplex_receptacle'


def test_balanced_budget_prioritizes_common_device_types():
    def p(category, name, sub):
        return SamNecaPrompt(sub, '', name, '', category, category, sub,
                             category, sub, 'numbered_row', True, sub + '.svg')
    specs = [
        p('power_outlet', 'Special receptacle', 'special'),
        p('power_outlet', 'Duplex receptacle', 'duplex'),
        p('lighting_control', 'Motorized projection screen', 'screen'),
        p('lighting_control', 'Single pole switch', 'single_pole'),
        p('fire_alarm_detection', 'Hydrogen detector', 'hydrogen'),
        p('fire_alarm_detection', 'Smoke detector.', 'smoke'),
    ]
    selected = choose_neca_prompts(specs, per_category=1, total=3)
    assert {p.symbol_id for p in selected} == {'duplex', 'single_pole', 'smoke'}
    with pytest.raises(ValueError, match='exclude'):
        choose_neca_prompts(specs, total=2)


def test_allowlist_bypasses_budget_and_rejects_unknown():
    p = SamNecaPrompt('a', '', 'Duplex', '', 'power_outlet', 'receptacle',
                      'duplex', 'power_outlet', 'duplex', 'numbered_row', True, 'a.svg')
    assert choose_neca_prompts([p], total=1, allowlist={'a'}) == [p]
    with pytest.raises(ValueError, match='unavailable'):
        choose_neca_prompts([p], allowlist={'not-found'})
