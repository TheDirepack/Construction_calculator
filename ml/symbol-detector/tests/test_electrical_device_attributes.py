"""Attribute combinations are independent of fine shape classifiers."""
import pytest
from blueprint_symbols.electrical_device_attributes import (
    ATTRIBUTE_SCHEMA, validate_device_attributes,
)


def test_switch_three_way_dimmer_with_low_voltage_and_specs():
    attrs = {
        'pole_configuration': 'three_way',
        'control_features': ['dimmer', 'low_voltage'],
        'voltage_v': 120, 'phase_count': 1, 'amperage_a': 15,
        'evidence': {
            'voltage_v': {'source': 'legend', 'text': '120V'},
            'phase_count': {'source': 'panel_schedule', 'text': '1 PH'},
            'amperage_a': {'source': 'panel_schedule', 'text': '15A'},
        },
    }
    validate_device_attributes('switch', attrs)
    assert len(ATTRIBUTE_SCHEMA['switch']['control_features']) > 5


def test_receptacle_gfci_three_phase_and_emergency_as_separate_fields():
    validate_device_attributes('receptacle', {
        'receptacle_configuration': 'quad',
        'protective_features': ['gfci', 'weather_resistant'],
        'circuit_role': 'emergency',
        'phase_count': 3, 'voltage_v': 208,
    })


@pytest.mark.parametrize('invalid', [
    {'voltage_v': -120},
    {'phase_count': 2},
    {'pole_count': 1.5},
    {'gfci': 'maybe'},
    {'control_features': ['dimmer', 'dimmer']},
    {'pole_configuration': 'three_and_a_half_way'},
])
def test_reject_malformed_electrical_attributes(invalid):
    with pytest.raises(ValueError):
        validate_device_attributes('switch', invalid)


def test_unknown_attributes_remain_extensible_but_not_null():
    validate_device_attributes('switch', {'manufacturer_specific_mod': 'x'})
    with pytest.raises(ValueError):
        validate_device_attributes('switch', {'unknown_mod': None})
