"""Group NECA 100 SVGs by visible core silhouette, not by full symbol label.

Every source entry receives a classification. Composite switch suffix variants
are metadata, never another device proposal. Unreviewed shapes keep unique
groups rather than disappearing from the coarse search.
"""
from __future__ import annotations
from collections import Counter, defaultdict

MODIFIER_SECTIONS = {
    'Luminaires - Mounting Modifiers', 'Luminaires - Orientation Modifiers',
    'Luminaires - Emergency Modifiers', 'Luminaires - Extended Modifiers',
}
NON_DEVICE_SECTIONS = {
    'Raceways', 'Raceways - Indicators', 'Site Work',
    'Schematic and One-Line Diagram Symbols', 'Switchboard Meters',
    'One-Line Diagram Switches', 'Miscellaneous',
}
SWITCH_SUFFIX_ATTRIBUTES = {
    '002': ('pole_configuration', 'double_pole'),
    '003': ('pole_configuration', 'three_way'),
    '004': ('pole_configuration', 'four_way'),
    '005': ('control_group', 'letter_designation'),
    '006': ('control_features', 'circuit_breaker'),
    '007': ('pole_configuration', 'double_throw'),
    '008': ('control_features', 'glow_toggle'),
    '009': ('control_features', 'left_on_orientation'),
    '010': ('control_features', 'key_operated'),
    '011': ('control_features', 'key_operated_with_pilot_light'),
    '012': ('control_features', 'low_voltage'),
    '013': ('control_features', 'low_voltage_master'),
    '014': ('control_features', 'momentary'),
    '015': ('control_features', 'pilot_light'),
    '016': ('control_features', 'timer'),
    '017': ('control_features', 'weatherproof'),
    '021': ('control_features', 'projection_screen_control'),
}


def classify_neca_artwork(entry: dict, alternative_parents: set[str] | None = None) -> dict:
    sid = str(entry['id'])
    parent, _, suffix = sid.partition('__')
    section = str(entry.get('section', ''))
    pieces = parent.split('_')
    no, number = (pieces[1], pieces[2]) if len(pieces) >= 3 else ('other', parent)
    root = section.lower().replace(' - ', '.').replace(' ', '_')
    group, role, reason, attrs = f'{root}.{parent}', 'base', 'distinct_or_unreviewed_shape', {}
    if section in NON_DEVICE_SECTIONS:
        role, group, reason = 'excluded', f'non_device.{root}', 'not_placed_device'
    elif section in MODIFIER_SECTIONS:
        role, group, reason = 'modifier', f'lighting_modifier.{parent}', 'attached_non_device_mark'
    elif section == 'Raceways - Boxes and Busways' and not any(
            x in entry.get('name', '').lower() for x in ('box', 'pole', 'handhole')):
        role, reason = 'excluded', 'routing_assembly'
    elif section == 'Annex A - Alternate Fire Safety' and (
            '__basic' in sid or 'basic shape' in entry.get('name','').lower()):
        role, reason = 'excluded', 'ambiguous_basic_shape'
    elif entry.get('kind') == 'numbered_row' and parent in (alternative_parents or set()):
        role, reason = 'excluded', 'parent_contains_separate_artwork'
    elif no == '4' and section == 'Switches and Sensors':
        if number == '001' or number in SWITCH_SUFFIX_ATTRIBUTES:
            notation = 'dollar' if suffix == 'dollar' else 'letter_s' if suffix == 'letter_s' else 'mixed'
            group = f'switch.base_{notation}'
            if number in SWITCH_SUFFIX_ATTRIBUTES:
                role, reason = 'composite', 'base_switch_plus_suffix'
                key, val = SWITCH_SUFFIX_ATTRIBUTES[number]
                attrs = {key: val}
            else:
                reason = 'bare_switch'
        elif number == '018':
            group, attrs = 'switch.boxed_dimmer', {'control_features': 'dimmer'}
        elif number in ('019','020'):
            group = 'occupancy_sensor.diamond_m'
            attrs = {'mounting':'wall' if number == '019' else 'ceiling'}
        else:
            group = 'switch.other_' + number
    elif no == '3' and section == 'Outlets and Receptacles':
        mapped = {
            '001':'floor_duplex_box','002':'wall_duplex_open',
            '003':'wall_duplex_emergency_filled','004':'wall_single_open',
            '005':'wall_single_emergency_filled','006':'wall_quad_open',
            '007':'wall_quad_emergency_filled','008':'multioutlet_strip',
            '009':'multioutlet_assembly','011':'clock_hanger',
            '012':'power_data_floor_box','014':'ceiling_duplex','015':'ceiling_quad',
        }
        if number == '010':
            group = 'receptacle.special_' + ('triangle' if suffix.endswith('1')
                         else 'square' if suffix.endswith('2') else 'combined')
        elif number == '013':
            group = 'junction_box.' + ('circle' if suffix.endswith('1')
                         else 'square' if suffix.endswith('2') else 'combined')
        else:
            group = 'receptacle.' + mapped.get(number, 'other_'+number)
        if number in ('003','005','007'):
            attrs = {'circuit_role':'emergency'}
    elif no == '2' and section == 'Luminaires':
        if number == '001':
            shape = {'alternative_1':'round','alternative_2':'rectangular',
                     'alternative_3':'triangular'}.get(suffix,'combined')
            group = 'luminaire.fixture_'+shape
        elif number in ('004','005'):
            group = 'luminaire.'+('spotlight' if number=='004' else 'exit')+'_'+(suffix or 'combined')
        else:
            kinds = {'002':'single_strip','003':'fluorescent_strip','006':'track',
                     '007':'remote_emergency_heads','008':'emergency_battery_unit',
                     '009':'single_pole_site','010':'twin_pole_site','011':'cobra_head',
                     '012':'bollard','013':'wallpack'}
            group = 'luminaire.'+kinds.get(number, 'other_'+number)
    elif section == 'Fire Alarm Sensors':
        if number in {f'{n:03d}' for n in range(202,215)} - {'204'}:
            group = 'fire_detector.hexagon_letter'
        elif number == '204':
            group = 'fire_detector.square_rts'
        elif number == '201':
            group = 'fire_detector.square_pull'
        elif number == '215':
            group = 'fire_detector.damper_circle'
        else:
            group = 'fire_detector.square_letter'
    elif section == 'Security':
        if number == '001':
            group = 'security.camera_cone'
        elif number in ('004','005','006'):
            group = 'security.door_signal_'+number
        elif number == '016':
            group = 'security.push_button'
        else:
            group = 'security.labeled_box'
    elif section == 'Motor and HVAC Controls':
        if number.isdigit() and 18 <= int(number) <= 24:
            group = 'hvac_control.circle_letter'
        elif number.isdigit() and 25 <= int(number) <= 34:
            group = 'hvac_control.square_letter'
        elif number in ('004','005','009','010'):
            group = 'hvac_control.rectangle_letter'
        elif number in ('006','012','013'):
            group = 'hvac_control.switch_glyph'
        else:
            group = 'hvac_control.'+number
    elif section == 'Communications - Teledata':
        names = {'001':'data_open_triangle','002':'data_floor_square',
                 '003':'split_triangle','004':'split_floor_square',
                 '005':'phone_filled_triangle','006':'phone_wall_triangle',
                 '007':'phone_floor_square'}
        group = 'telecom.'+names.get(number,'other_'+number)
    elif section == 'Power Distribution Equipment':
        if number in ('001','002'): group = 'distribution.hatched_panel'
        elif number in ('003','005'): group = 'distribution.reverse_hatched_panel'
        elif number in ('007','008'): group = 'distribution.square_transformer'
        elif number in ('011','012'): group = 'distribution.circle_meter'
        else: group = 'distribution.'+number
    elif section == 'Fire Alarm Communications and Panels':
        if number in ('006','007','008','009','011','012','013','014','015','016','017'):
            group = 'fire_control.labeled_panel_box'
    elif section == 'Fire Alarm Indicators':
        if number in ('103','105','106','107','108','109','110'):
            group = 'fire_notification.box_accessory_'+number
        elif number in ('111','112'):
            group = 'fire_notification.circular_light_'+number
        else:
            group = 'fire_notification.'+number
    return {'symbol_id': sid, 'code': entry.get('code', ''),
            'name': entry.get('name', ''), 'file': entry.get('file', ''),
            'native_vectors': bool(entry.get('native_vectors', False)),
            'kind': entry.get('kind', ''), 'section': section,
            'role': role, 'visual_group': group, 'attributes': attrs,
            'reason': reason, 'parent': parent}


def audit_neca_visual_groups(entries: list[dict]) -> dict:
    """Account for every source SVG, including ignored routing/modifier art."""
    alt_parents = {x['id'].split('__')[0] for x in entries
                   if x.get('kind') == 'printed_alternative'}
    classified = [classify_neca_artwork(x, alt_parents) for x in entries]
    groups = defaultdict(list)
    for x in classified:
        if x['role'] in ('base','composite'):
            groups[x['visual_group']].append(x)
    return {
        'source_entries':len(entries),
        'roles':dict(Counter(x['role'] for x in classified)),
        'visual_group_count':len(groups),
        'groups': {g: {'base_ids':[x['symbol_id'] for x in xs if x['role']=='base'],
                       'composite_ids':[x['symbol_id'] for x in xs if x['role']=='composite']}
                   for g,xs in sorted(groups.items())},
        'entries':classified,
    }


def choose_visual_base_prototypes(prompts: list, *, per_group: int = 1) -> list:
    """Preserve every visibly distinct base group; omit suffix composites.

    Metadata-only selection before SVG rendering/model loading. Selection is
    stable under input ordering and prefers native vector art.
    """
    if per_group < 1:
        raise ValueError('per_group must be positive')
    groups = defaultdict(list)
    for p in prompts:
        if getattr(p, 'prompt_role', 'base') == 'base':
            groups[getattr(p, 'visual_group', p.symbol_id)].append(p)
    selected = []
    for name in sorted(groups):
        choices = sorted(groups[name], key=lambda p: (
            not p.native_vectors, p.kind != 'printed_alternative', p.symbol_id))
        selected.extend(choices[:per_group])
    return selected
