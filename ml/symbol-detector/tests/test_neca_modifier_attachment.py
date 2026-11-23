"""Geometry associates feature marks with one base, not new boxes."""
from blueprint_symbols.neca_modifier_attachment import attach_device_modifiers


def _mod(box, attribute, value, *, family='switch', score=.98):
    return {'box_px':box, 'target_family':family,
            'attribute':attribute, 'value':value, 'score':score,
            'source':'ocr_or_sam_component'}


def test_three_way_dimmer_sensor_low_voltage_compose_on_one_switch():
    bases = [
        {'device_type':'switch', 'box_px':[100,100,115,126]},
        {'device_type':'switch', 'box_px':[210,100,225,126]},
    ]
    marks = [
        _mod([117,118,125,132], 'pole_configuration','three_way'),
        _mod([129,118,148,131], 'control_features','low_voltage'),
        _mod([130,135,143,150], 'control_features','dimmer'),
        _mod([120,136,131,148], 'control_features','occupancy'),
    ]
    out, rejected = attach_device_modifiers(bases, marks)
    assert len(out) == len(bases) == 2
    assert not rejected
    assert out[0]['analytic_attributes']['pole_configuration'] == 'three_way'
    assert set(out[0]['analytic_attributes']['control_features']) == {
        'dimmer', 'low_voltage', 'occupancy',
    }
    assert len(out[0]['modifier_evidence']) == 4
    assert out[1]['analytic_attributes'] == {}


def test_ambiguous_or_distant_mark_never_assigns_a_tag():
    bases = [
        {'device_type':'switch', 'box_px':[100,100,118,120]},
        {'device_type':'switch', 'box_px':[116,100,134,120]},
    ]
    # Both bases are similarly plausible; preserve uncertainty.
    out, missed = attach_device_modifiers(bases, [
        _mod([115,117,119,125], 'control_features','dimmer'),
        _mod([500,500,510,510], 'control_features','timer'),
    ], ambiguity_margin=.4)
    assert not any(x['analytic_attributes'] for x in out)
    assert {x['reason'] for x in missed} == {'ambiguous_base','no_compatible_base'}


def test_incompatible_exclusive_modifiers_not_silently_merged():
    bases = [{'device_type':'switch', 'box_px':[100,100,115,126]}]
    out, missed = attach_device_modifiers(bases, [
        _mod([118,119,126,130], 'pole_configuration','three_way',score=.99),
        _mod([130,119,138,130], 'pole_configuration','four_way',score=.98),
    ])
    assert out[0]['analytic_attributes']['pole_configuration'] == 'three_way'
    assert len(out[0]['modifier_conflicts']) == 1
    assert missed[0]['reason'] == 'conflicting_exclusive_modifier'
