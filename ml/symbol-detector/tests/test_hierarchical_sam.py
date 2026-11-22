"""CPU-only tests for the SAM coarse-to-fine prompt planner."""
from dataclasses import dataclass

import pytest

from blueprint_symbols.hierarchical_sam import (
    active_fine_indices,
    accepted_fine_match,
    build_hierarchical_prompt_plan,
    explain_query_savings,
)


@dataclass(frozen=True)
class Prompt:
    device_type: str
    subtype: str


def test_coarse_sees_every_family_but_fine_only_queries_active_family():
    glyphs = [
        Prompt('switch', 'single_pole'),
        Prompt('switch', 'three_way'),
        Prompt('switch', 'four_way'),
        Prompt('switch', 'dimmer'),
        Prompt('receptacle', 'duplex'),
        Prompt('receptacle', 'quad'),
        Prompt('receptacle', 'gfci'),
        Prompt('fire_alarm_detector', 'smoke'),
    ]
    plan = build_hierarchical_prompt_plan(glyphs, coarse_per_family=1)
    assert len(plan['families']) == 3
    assert len(plan['coarse_indices']) == 3
    assert len(active_fine_indices(plan, {'switch'})) == 4
    assert len(active_fine_indices(plan, {'not-a-family'})) == 0
    assert len(active_fine_indices(plan, set(), fallback_full=True)) == 8
    savings = explain_query_savings(plan, {'receptacle'})
    assert savings['coarse_queries'] == 3
    assert savings['fine_queries'] == 3
    assert savings['total_queries'] == 6
    assert savings['flat_queries'] == 8


def test_representatives_cover_distinct_subtypes_when_possible():
    glyphs = [
        Prompt('switch', 'single_pole'),
        Prompt('switch', 'single_pole'),
        Prompt('switch', 'three_way'),
        Prompt('switch', 'four_way'),
        Prompt('receptacle', 'duplex'),
    ]
    plan = build_hierarchical_prompt_plan(glyphs, coarse_per_family=2)
    switch_coarse = [i for i in plan['coarse_indices'] if glyphs[i].device_type == 'switch']
    assert [glyphs[i].subtype for i in switch_coarse] == ['single_pole', 'three_way']
    assert len(active_fine_indices(plan, {'switch'})) == 4


def test_refinement_must_intersect_coarse_family_proposal():
    coarse = [
        {'device_type': 'switch', 'box_px': [100, 100, 120, 120]},
        {'device_type': 'receptacle', 'box_px': [200, 200, 220, 220]},
    ]
    assert accepted_fine_match(
        {'device_type': 'switch', 'box_px': [102, 101, 120, 121]}, coarse)
    assert not accepted_fine_match(
        {'device_type': 'switch', 'box_px': [200, 200, 220, 220]}, coarse)
    assert not accepted_fine_match(
        {'device_type': 'switch', 'box_px': [500, 500, 520, 520]}, coarse)


def test_planner_validates_inputs_and_fine_budget():
    glyphs = [Prompt('switch', 'single_pole'), Prompt('switch', 'three_way'),
              Prompt('switch', 'four_way')]
    plan = build_hierarchical_prompt_plan(glyphs, coarse_per_family=1,
                                          fine_per_family=2)
    assert len(active_fine_indices(plan, {'switch'})) == 2
    for kwargs in [{'coarse_per_family': 0}, {'fine_per_family': -1}]:
        with pytest.raises(ValueError):
            build_hierarchical_prompt_plan(glyphs, **kwargs)
    with pytest.raises(ValueError):
        build_hierarchical_prompt_plan([])


def test_coarse_not_used_as_verified_fine_subtype():
    from blueprint_symbols.proposal_fusion import merge_sam_proposals
    detected = [
        {'box_px': [100, 100, 120, 120], 'device_type': 'switch',
         'subtype': 'unspecified', 'stage': 'coarse', 'score': .92},
        {'box_px': [101, 100, 121, 120], 'device_type': 'switch',
         'subtype': 'three_way', 'stage': 'fine', 'score': .61},
    ]
    merged = merge_sam_proposals(detected)
    assert len(merged) == 1
    assert merged[0]['stage'] == 'fine'
    assert merged[0]['subtype'] == 'three_way'
    assert merged[0]['family_only'] is False
    assert merged[0]['alternative_labels'] == []
    generic = merge_sam_proposals(detected[:1])
    assert generic[0]['subtype'] == 'unspecified'
    assert generic[0]['needs_class_review'] is True
