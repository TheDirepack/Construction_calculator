"""Context-aware synthetic placement preferences, not source-device labels."""
from __future__ import annotations

import numpy as np
import pytest

from blueprint_symbols.placement_policy import wall_placement_probability


def test_context_changes_wall_odds_without_eliminating_randomness():
    p = .5
    assert wall_placement_probability("switch", "three_way", p) > .75
    assert wall_placement_probability("receptacle", "duplex", p) > .75
    assert wall_placement_probability("data_outlet", "data", p) > .75
    assert wall_placement_probability("light_fixture", "wall_light", p) > .85
    assert wall_placement_probability("luminaire", "wall_sconce", p) > .85
    assert wall_placement_probability("light_fixture", "downlight", p) < .10
    assert wall_placement_probability("luminaire", "troffer_2x4", p) < .10
    assert wall_placement_probability("receptacle", "floor", p) < .10
    assert wall_placement_probability("fire_alarm", "smoke_detector", p) < .12
    assert wall_placement_probability("fire_alarm_device", "pull_station", p) > .80
    assert wall_placement_probability("junction_box", "junction_box", p) == p
    assert wall_placement_probability("light_fixture", "exit_sign", p) > .1


@pytest.mark.parametrize("base", [0, .05, .15, .5, .85, .95, 1])
def test_class_placement_bounds_and_monotonicity(base):
    variants = [
        ("switch", "single_pole"), ("light_fixture", "2x4"),
        ("receptacle", "floor"), ("fire_alarm", "pull_station"),
        ("other_device", "unspecified"),
    ]
    probabilities = [
        wall_placement_probability(t, sub, base) for t, sub in variants
    ]
    assert all(0 <= p <= 1 for p in probabilities)
    if base in (0, 1):
        assert all(p == base for p in probabilities)


def test_placement_is_statistically_randomized_by_drawing_prevalence():
    bases = np.linspace(.1, .9, 17)
    for type_name, subtype in (
        ("switch", "single_pole"),
        ("light_fixture", "downlight"),
        ("light_fixture", "exit_sign"),
    ):
        probs = [wall_placement_probability(type_name, subtype, float(x))
                 for x in bases]
        assert all(a < b for a, b in zip(probs, probs[1:]))


def test_invalid_probability_rejected():
    for value in (-1., 1.01, float("nan"), float("inf")):
        with pytest.raises(ValueError):
            wall_placement_probability("switch", "single_pole", value)
