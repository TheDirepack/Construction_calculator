"""Independent electrical symbol attributes for VLM refinement.

The first-stage detector should predict *device family*. Fine shape variants
are hypotheses, not complete electrical specifications. Do not multiply
classification labels across every voltage/phase/control combination.
"""
from __future__ import annotations

from math import isfinite

# Extensible fine-grained schema: multiple attributes may coexist.
# Optional fields are omitted when not actually shown by a symbol,
# project legend, nearby label, or schedule.
ATTRIBUTE_SCHEMA = {
    'switch': {
        'pole_configuration': ['single_pole', 'double_pole', 'three_way',
                               'four_way', 'double_throw', 'other'],
        'control_features': ['dimmer', 'low_voltage', 'occupancy', 'vacancy',
                             'timer', 'key_operated', 'pilot_light', 'momentary',
                             'scene_control', 'weatherproof', 'motor_control'],
        'dimming_protocol': ['line_voltage', 'zero_to_ten_volt', 'dali',
                             'phase_cut', 'other'],
        'mounting': ['wall', 'ceiling', 'floor', 'panel', 'other'],
    },
    'receptacle': {
        'receptacle_configuration': ['single', 'duplex', 'quad', 'multioutlet',
                                     'special_purpose', 'other'],
        'protective_features': ['gfci', 'afci', 'tamper_resistant',
                                'weather_resistant', 'surge_protection'],
        'switching': ['unswitched', 'partially_switched', 'fully_switched'],
        'circuit_role': ['normal', 'emergency', 'standby', 'dedicated', 'other'],
        'mounting': ['wall', 'ceiling', 'floor', 'counter', 'pole', 'other'],
    },
    'light_fixture': {
        'form_factor': ['downlight', 'linear', 'strip', 'troffer_2x2', 'troffer_2x4',
                        'wall_sconce', 'pendant', 'track', 'spotlight', 'pole', 'other'],
        'exit_faces': ['single', 'double', 'other'],
        'exit_arrow_directions': ['none', 'left', 'right', 'both', 'up', 'down', 'other'],
        'mounting': ['ceiling', 'wall', 'floor', 'pole', 'other'],
    },
    'fire_alarm_detector': {
        'sensor_types': ['smoke', 'heat', 'carbon_monoxide', 'flame', 'beam',
                         'duct', 'gas', 'waterflow', 'tamper', 'other'],
        'mounting': ['ceiling', 'wall', 'duct', 'other'],
    },
    'fire_alarm_notification': {
        'notification_features': ['horn', 'speaker', 'strobe', 'bell', 'beacon',
                                  'vibration', 'other'],
        'mounting': ['ceiling', 'wall', 'other'],
    },
    'data_outlet': {
        'outlet_services': ['data', 'telephone', 'fiber', 'coax', 'audio_visual',
                            'other'],
        'mounting': ['wall', 'floor', 'ceiling', 'other'],
    },
}

# Open-ended numeric/electrical fields are not symbol classes.
# They require evidence from the current project, not the NECA template.
NUMERIC_ATTRIBUTES = {
    'voltage_v', 'amperage_a', 'wattage_w', 'power_va',
    'pole_count', 'wire_count', 'phase_count',
}
STRING_ATTRIBUTES = {'nema_configuration', 'circuit_label', 'panel_label',
                     'fixture_type_mark', 'schedule_reference'}
FLAG_ATTRIBUTES = {'emergency_power', 'weatherproof', 'gfci', 'afci',
                   'dimming', 'switched', 'dedicated', 'floor_mount'}
LIST_FIELDS = {'control_features', 'protective_features', 'sensor_types',
               'notification_features', 'outlet_services'}


def validate_device_attributes(device_type: str, attributes: dict) -> None:
    """Validate recognized fields; preserve custom extensions for rare symbols.

    Does not claim evidence was verified. A separately reviewed source must
    establish numeric properties, phase, NEMA pattern and circuit properties.
    """
    if not isinstance(attributes, dict):
        raise ValueError('attributes must be an object')
    schema = ATTRIBUTE_SCHEMA.get(device_type, {})
    for key, value in attributes.items():
        if key in NUMERIC_ATTRIBUTES:
            if type(value) not in (int, float) or not isfinite(value) or value <= 0:
                raise ValueError(f'{key} must be a finite positive number')
            if key == 'phase_count' and value not in (1, 3):
                raise ValueError('phase_count must be 1 or 3')
            if key in ('pole_count', 'wire_count') and not float(value).is_integer():
                raise ValueError(f'{key} must be integer-valued')
        elif key in STRING_ATTRIBUTES:
            if not isinstance(value, str) or not value.strip():
                raise ValueError(f'{key} must be a nonempty string')
        elif key in FLAG_ATTRIBUTES:
            if type(value) is not bool:
                raise ValueError(f'{key} must be boolean')
        elif key in schema:
            valid = set(schema[key])
            if key in LIST_FIELDS:
                if not isinstance(value, list) or len(value) != len(set(map(str, value))) or \
                   not all(v in valid for v in value):
                    raise ValueError(f'{key} must be a list of distinct allowed values')
            elif value not in valid:
                raise ValueError(f'{key} has unknown choice: {value!r}')
        elif key == 'evidence':
            if not isinstance(value, dict):
                raise ValueError('evidence must be an object')
        else:
            # New drawing-specific fields are allowed as extensions.
            if value is None:
                raise ValueError('omit unknown attributes instead of writing null')
