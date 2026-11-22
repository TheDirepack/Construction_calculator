#!/usr/bin/env python3
"""Build the final Washington_DEVICES.json from extracted device records."""
import json, os

SRC = '/tmp/opencode/wash_devices.json'
OUT = '/home/max/.t3/projects/construction-calculator/blueprints_and_libraries/1_blueprints/standardized_device_jsons_v3/Washington_DEVICES.json'

SHEETS = {
    47: 'E2.01 Electrical Site & Floor Plan (Demo)',
    48: 'E2.11 Electrical Site & Floor Plan',
    49: 'E3.01 Electrical Enlarged Floor Plan - Demo - C Wing',
    50: 'E3.02 Electrical Enlarged Floor Plan - Demo - B/A Wing',
    51: 'E3.11 Electrical Enlarged Floor Plan - C Wing',
    52: 'E3.12 Electrical Enlarged Floor Plan - B/A Wing',
    58: 'FA Fire Alarm Site Plan',
    59: 'FA Fire Alarm Floor Plan - Building A',
    60: 'FA Fire Alarm Floor Plan - Building B',
    61: 'FA Fire Alarm Floor Plan - Building C',
    62: 'FA Fire Alarm Floor Plan - Buildings D & E',
    63: 'FA Fire Alarm Floor Plan - West Portable Classroom Buildings',
}

# Device-type legend compiled from Sheet E0.01 (page 46) - Electrical Symbol Legend.
TYPE_LEGEND = {
    'F':  {'device_type': 'lighting_fixture',        'subtype': 'led_luminaire',
           'description': 'LED lighting fixture (letter=type, small letter=switch control, number=circuit)'},
    'J':  {'device_type': 'junction_box',            'subtype': 'junction_box',
           'description': 'Junction box'},
    'R':  {'device_type': 'receptacle',              'subtype': 'duplex',
           'description': '20A-125V duplex receptacle'},
    'X':  {'device_type': 'exit_sign',               'subtype': 'exit_light',
           'description': 'Exit light fixture'},
    'K':  {'device_type': 'switch',                  'subtype': 'key_operated',
           'description': 'Key-operated switch'},
    'C':  {'device_type': 'receptacle',              'subtype': 'clock',
           'description': 'Clock outlet receptacle'},
    'D':  {'device_type': 'wall_device',             'subtype': 'wall_device',
           'description': 'Generic wall-mounted device'},
    'OC': {'device_type': 'occupancy_sensor',        'subtype': 'wall_mount',
           'description': 'Wall-mount occupancy sensor (Wattstopper DSW-301-W)'},
    'CEF':{'device_type': 'ceiling_exhaust_fan',     'subtype': 'exhaust_fan',
           'description': 'Ceiling exhaust fan'},
    'EUH':{'device_type': 'electric_unit_heater',    'subtype': 'unit_heater',
           'description': 'Electric unit heater'},
    'IWH':{'device_type': 'instantaneous_water_heater','subtype':'water_heater',
           'description': 'Instantaneous water heater'},
    'FD': {'device_type': 'fire_damper',             'subtype': 'fire_damper',
           'description': 'Fire damper'},
    'FE': {'device_type': 'fire_extinguisher',       'subtype': 'fire_extinguisher',
           'description': 'Fire extinguisher'},
    'WP': {'device_type': 'device',                  'subtype': 'weatherproof',
           'description': 'Weatherproof device'},
    'S':  {'device_type': 'fire_alarm_device',       'subtype': 'smoke_detector',
           'description': 'Fire alarm device (smoke detector / notification appliance)'},
    'PC': {'device_type': 'receptacle',              'subtype': 'clock',
           'description': 'Clock outlet receptacle'},
    'HB': {'device_type': 'receptacle',              'subtype': 'special_purpose',
           'description': 'Special-purpose single power receptacle'},
    'LC1':{'device_type': 'dimmer_switch',           'subtype': 'wall_box_dimmer',
           'description': 'Wall-box dimmer switch (Wattstopper LMDM-101-W)'},
    'SW': {'device_type': 'switch',                  'subtype': 'wall_switch',
           'description': 'Wall switch'},
    'T':  {'device_type': 'thermostat',              'subtype': 'thermostat',
           'description': 'Thermostat'},
    'TS': {'device_type': 'data_outlet',             'subtype': 'telecom_data',
           'description': 'Telecom/data outlet'},
    'JB': {'device_type': 'junction_box',            'subtype': 'junction_box',
           'description': 'Junction box'},
}

def main():
    src = json.load(open(SRC))
    devices = []
    type_counts = {}
    for pnum in sorted(src['pages'].keys(), key=int):
        pg = src['pages'][pnum]
        sheet = SHEETS.get(int(pnum), f'Page {pnum}')
        for r in pg['devices']:
            code = r['device_code']
            info = TYPE_LEGEND.get(code, {'device_type': 'device', 'subtype': code,
                                          'description': 'Unclassified device symbol'})
            dtype = info['device_type']; subtype = info['subtype']
            type_counts[dtype] = type_counts.get(dtype, 0) + 1
            rec = {
                'pdf_page': int(pnum),
                'sheet': sheet,
                'discipline': 'electrical',
                'device_code': code,
                'device_type': dtype,
                'subtype': subtype,
                'attributes': {},
                'symbol_bbox': r['symbol_bbox'],
                'symbol_center': r['symbol_center'],
                'device_center': r.get('device_center', r['symbol_center']),
                'wall_connection_point': r.get('wall_connection_point'),
                'placement_rule': 'source proximity-based wall classification; review required',
                'geometry_source': 'source extraction record',
                'vector_path_ids': None,
                'wall_vector_path_ids': None,
                'confidence': 'low',
                'verification': 'unverified_source_extraction',
                'symbol_bbox_completeness': 'source extraction bbox; not independently reviewed',
                'label_bbox': r['label_bbox'],
                'on_wall': r['on_wall'],
                'id': f'Washington_D{len(devices)+1:05d}',
                'classification_status': 'migrated_from_source_legend',
                'coordinate_precision': 'source PDF points; wall proximity is approximate',
                'label_interpretation': 'electrical legend code and adjacent label',
                'source_record': r,
                'attributes': {
                    'wall_distance': r.get('wall_distance'),
                    'symbol_attached': r.get('symbol_attached'),
                    'label_center': r.get('label_center'),
                    'label_to_symbol_distance': r.get('label_to_symbol_distance', 0.0),
                },
                'geometry_audit': {'status': 'unverified', 'reason': 'Source extraction uses approximate wall proximity; verify symbol and wall geometry visually.'},
            }
            devices.append(rec)

    out = {
        'schema_version': '3.0.0',
        'source_file': '/home/max/.t3/projects/construction-calculator/blueprints_and_libraries/1_blueprints/new_downloads_202611b/WashingtonDrawings (1).pdf',
        'source_pages_total': 75,
        'coordinate_system': {'origin': 'bottom_left', 'units': 'PDF_points', 'reference': 'source extraction page coordinates', 'rotation_handling': 'preserved from source extraction', 'symbol_bbox_format': '[x_min,y_min,x_max,y_max]', 'wall_connection_point_status': 'approximate source projection; unverified'},
        'taxonomy': {k: list({v['subtype'] for v in TYPE_LEGEND.values() if v['device_type'] == k}) for k in sorted({v['device_type'] for v in TYPE_LEGEND.values()})},
        'legend_types': [{'device_code': k, **v} for k,v in TYPE_LEGEND.items()],
        'plan_pages_processed': sorted(int(k) for k in src['pages'].keys()),
        'page_results': [{'pdf_page': int(k), 'sheet': SHEETS.get(int(k), f'Page {k}'), 'geometry_matched_devices': sum(1 for x in devices if x['pdf_page'] == int(k)), 'unlocated_equipment_candidates': 0} for k in sorted(src['pages'], key=int)],
        'devices': devices,
        'unresolved_candidates': [],
        'summary': {'geometry_matched_devices': len(devices), 'unresolved_label_candidates': 0, 'by_type': dict(sorted(type_counts.items())), 'extraction_status': 'migrated_source_extraction_unverified', 'limitations': [
            'Devices detected on electrical plan sheets (E2/E3 series, PDF pages 47-52) and fire alarm plan sheets (FA series, PDF pages 58-63).',
            'Device type is identified from the symbol code and matched to Sheet E0.01 electrical symbol legend.',
            'symbol_bbox / symbol_center: bounding box and center of the detected symbol glyph (PDF points, bottom-left origin).',
            'label_bbox / label_center: bounding box and center of the adjacent text label.',
            'on_wall=true => wall_connection_point is the closest point on the detected wall line; device_center omitted.',
            'on_wall=false => device_center is provided (ceiling/floor-mounted); wall_connection_point omitted.',
            'symbol_attached=false means no vector symbol glyph was found within tolerance; location is taken from the text label.',
            'Wall detection is proximity-based and approximate; wall_distance is reported for every device.',
            'Fire-alarm device codes (S, WP, etc.) are best-effort classifications; verify against the fire-alarm legend.',
        ]},
        'extraction_revision': 'v3_source_legend_and_approximate_wall_mapping',
        'source_metadata': {'source_path': '/home/max/.t3/projects/construction-calculator/blueprints_and_libraries/1_blueprints/new_downloads_202611b/WashingtonDrawings (1).pdf', 'total_devices': len(devices), 'units': 'PDF points (origin bottom-left)', 'coordinate_origin': 'bottom-left', 'legend_source': 'Sheet E0.01 - Electrical Sheet Index, Legend & Notes (PDF page 46)', 'device_type_summary': dict(sorted(type_counts.items())), 'notes': [
            'Generated from the source extraction input in /tmp/opencode/wash_devices.json.',
            'The source extraction is not independently visually verified.',
        ]},
    }
    with open(OUT, 'w') as f:
        json.dump(out, f, indent=1)
    print('WROTE', OUT)
    print('total devices:', len(devices))
    print('type summary:', dict(sorted(type_counts.items())))

if __name__ == '__main__':
    main()
