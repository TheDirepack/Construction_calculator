"""Class-aware synthetic-device placement without claiming real-plan labels.

This controls only whether a *newly generated* symbol prefers an available
wall line or free space. It never classifies or edits pre-existing devices.
Per-crop random wall prevalence remains the input; common classes modify its
odds, with guaranteed 0 and 1 preserved for explicit test/CLI overrides.
"""
from __future__ import annotations

import math


def wall_placement_probability(
    device_type: str, subtype: str, base_probability: float
) -> float:
    """Modulate random wall/free placement using drawing-device conventions."""
    p = float(base_probability)
    if not math.isfinite(p) or not 0 <= p <= 1:
        raise ValueError("wall probability must lie in [0,1]")
    if p in (0.0, 1.0):
        return p
    name = (device_type or "").lower()
    variant = (subtype or "").lower()

    # Relative odds, not hard placement decisions: ceiling/floor symbols
    # still occasionally appear in unusual wall-mounted contexts.
    odds = 1.0
    if name in ("receptacle", "data_outlet", "switch", "switch_sensor",
                "panelboard", "thermostat", "temperature_sensor"):
        odds = 5.0
        if name == "receptacle" and "floor" in variant:
            odds = .05
    elif name in ("luminaire", "light_fixture"):
        if variant in ("wall_light", "wall_sconce"):
            odds = 8.0
        elif variant in ("downlight", "2x2", "2x4", "linear", "strip",
                         "troffer_2x4", "troffer_2x2", "recessed_can",
                         "ceiling_outlet", "ceiling_fan"):
            odds = .05
        elif variant == "exit_sign":
            odds = .8
    elif name in ("fire_alarm", "fire_alarm_device"):
        if variant in ("smoke_detector", "heat_detector",
                       "duct_smoke_detector", "beam_smoke_detector"):
            odds = .07
        elif variant in ("pull_station", "horn_strobe", "strobe", "horn",
                         "speaker_strobe", "annunciator", "control_panel"):
            odds = 6.0
    elif name in ("occupancy_sensor", "ceiling_fan"):
        odds = .07
    elif name in ("security_device", "speaker"):
        odds = 2.0
    # Smooth odds transformation avoids arbitrary probability floors and
    # retains drawing-to-drawing variability for every class.
    return float(p * odds / ((1.0 - p) + p * odds))
