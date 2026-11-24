"""Expandable multi-discipline symbol vocabulary, NOT a claim of trained classes.

Labels from a dataset are mapped by explicit category/block metadata into
these *families*. There is no fixed maximum class count. Multiple designs can
share a family and retain distinct `symbol_variant` labels. Unmapped classes
remain ignored/unknown, never silently treated as confirmed negatives.
"""
from __future__ import annotations

FAMILIES: dict[str, tuple[str, ...]] = {
    'electrical': (
        'receptacle', 'switch', 'luminaire', 'junction_box', 'panelboard',
        'transformer', 'disconnect', 'circuit_breaker', 'motor', 'generator',
        'fire_alarm_device', 'data_outlet', 'telecom_device', 'conduit_fitting',
        'grounding_symbol', 'electrical_meter', 'control_relay', 'sensor',
        'security_device', 'cctv_device', 'audio_visual_device', 'motor_control',
    ),
    'mechanical': (
        'valve', 'pump', 'pipe_fitting', 'pipe_connection', 'strainer',
        'pressure_gauge', 'flow_meter', 'actuator', 'heat_exchanger',
        'mechanical_equipment', 'equipment_connection', 'instrument',
    ),
    'hvac': (
        'air_terminal', 'diffuser', 'grille', 'fan', 'damper', 'fire_damper',
        'thermostat', 'temperature_sensor', 'vav_terminal', 'air_handler',
        'fan_coil', 'coil', 'duct_fitting', 'duct_connection', 'exhaust_hood',
    ),
    'plumbing': ('plumbing_fixture','drain','pipe_fitting','water_heater','plumbing_valve'),
    'architectural': ('door','window','staircase','elevator','structural_column'),
    'fire_protection': ('fire_protection_device','sprinkler_head','alarm_device'),
    'other': ('unknown_symbol',),
}

FAMILY_TO_DISCIPLINE = {family:discipline for discipline, families in FAMILIES.items() for family in families}


def discipline_for(family: str) -> str:
    return FAMILY_TO_DISCIPLINE.get(family, 'unmapped')


def validated_classes(category_names: list[str], *, extra_families: tuple[str, ...] = ()) -> dict[str, int]:
    """Create a compact class index for the *actual* annotated class subset.

    Never allocate output head channels for classes lacking training examples.
    """
    if not category_names or len(category_names)!=len(set(category_names)):
        raise ValueError('need unique nonempty target labels')
    unknown=set(category_names)-(set(FAMILY_TO_DISCIPLINE)|set(extra_families))
    if unknown:
        raise ValueError(f'unknown families: {sorted(unknown)}; map explicitly through extra_families or catalog')
    return {family: i for i,family in enumerate(sorted(category_names))}
