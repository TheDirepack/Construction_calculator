from __future__ import annotations

import numpy as np
import pytest

from scripts_circuitnet_review_sheets import (
    apply_batch_updates_to_devices,
    cell_box_to_pdf_bl,
    cell_point_to_pdf_bl,
    compose_contact_sheet,
    compute_crop_rect_bl,
    pdf_bl_box_to_cell,
)


def test_pdf_cell_coordinate_roundtrip() -> None:
    bbox_bl = [420.5, 610.25, 438.5, 628.25]
    crop_bl = compute_crop_rect_bl(bbox_bl, context_mult=2.5, min_context_pts=36.0)
    box_cell = pdf_bl_box_to_cell(bbox_bl, crop_bl)
    # Centered box should land symmetrically around (500, 500)
    assert abs(0.5 * (box_cell[0] + box_cell[2]) - 500.0) < 0.2
    assert abs(0.5 * (box_cell[1] + box_cell[3]) - 500.0) < 0.2

    recovered = cell_box_to_pdf_bl(box_cell, crop_bl)
    for a, b in zip(bbox_bl, recovered):
        assert abs(a - b) < 0.05

    # Top of cell (v=0) maps to top of crop (cy1); bottom (v=1000) maps to bottom (cy0)
    assert abs(cell_point_to_pdf_bl(0.0, 0.0, crop_bl)[0] - crop_bl[0]) < 0.05
    assert abs(cell_point_to_pdf_bl(0.0, 0.0, crop_bl)[1] - crop_bl[3]) < 0.05
    assert abs(cell_point_to_pdf_bl(1000.0, 1000.0, crop_bl)[0] - crop_bl[2]) < 0.05
    assert abs(cell_point_to_pdf_bl(1000.0, 1000.0, crop_bl)[1] - crop_bl[1]) < 0.05


def test_partial_row_fills_remaining_slots_with_white() -> None:
    # 13 tiles with cols=10 -> 2 rows x 10 cols, last 7 slots of row 2 must be pure white
    tiles = [np.full((60, 50, 3), 80, dtype=np.uint8) for _ in range(13)]
    sheet = compose_contact_sheet(tiles, cols=10, pad=4)
    assert sheet.shape == (2 * 60 + 3 * 4, 10 * 50 + 11 * 4, 3)

    # Slot index 12 (row 1, col 2) is dark gray (80); slot index 13 (row 1, col 3) is white (255)
    y0 = 4 + 1 * (60 + 4)
    x_filled = 4 + 2 * (50 + 4)
    x_white = 4 + 3 * (50 + 4)
    assert int(sheet[y0 + 10, x_filled + 10, 0]) == 80
    assert int(sheet[y0 + 10, x_white + 10, 0]) == 255
    assert (sheet[y0:y0 + 60, x_white:, :] == 255).all()


def test_apply_batch_updates_converts_refinements_and_drops_false_positives() -> None:
    crop_bl = [100.0, 200.0, 200.0, 300.0]  # 100x100 pt square -> 1 cell unit = 0.1 pt
    batch = {
        'source_file': 'plan.pdf',
        'source_pdf_relative': '1_blueprints/plan.pdf',
        'source_pdf_absolute': '/tmp/plan.pdf',
        'source_circuitnet_json': '/tmp/plan_CIRCUITNET.json',
        'batch_index': 1,
        'items': [
            {
                'slot': 1,
                'device_index': 0,
                'pdf_page': 2,
                'provisional_device_type': 'receptacle',
                'score': 0.91,
                'crop_pdf_rect_bl': crop_bl,
                'provisional_bbox_cell': [400.0, 400.0, 600.0, 600.0],
                'review_status': 'accepted',
                'keep': True,
                'refined_device_type': 'receptacle',
                'refined_subtype': 'duplex',
                'attributes': {},
                'refined_bbox_cell': [420.0, 400.0, 580.0, 600.0],
                'on_wall': True,
                'wall_contact_cell': [500.0, 600.0],  # bottom edge of box -> y_bl = 240.0
            },
            {
                'slot': 2,
                'device_index': 1,
                'pdf_page': 2,
                'provisional_device_type': 'switch',
                'score': 0.36,
                'crop_pdf_rect_bl': crop_bl,
                'provisional_bbox_cell': [400.0, 400.0, 600.0, 600.0],
                'review_status': 'rejected',
                'keep': False,
            },
            {
                'slot': 3,
                'device_index': 2,
                'pdf_page': 2,
                'provisional_device_type': 'light',
                'score': 0.88,
                'crop_pdf_rect_bl': crop_bl,
                'provisional_bbox_cell': [400.0, 400.0, 600.0, 600.0],
                'review_status': 'accepted',
                'keep': True,
                'refined_device_type': 'light_fixture',
                'refined_subtype': 'other',
                'attributes': {},
                'refined_bbox_cell': [400.0, 400.0, 600.0, 600.0],
                'on_wall': False,
                'wall_contact_cell': [500.0, 500.0],
            },
        ],
    }

    refined = apply_batch_updates_to_devices([batch])
    assert refined['summary'] == {
        'kept_devices': 2,
        'removed_false_positives': 1,
        'skipped_unreviewed': 0,
        'wall_mounted': 1,
        'free_space_center': 1,
    }
    d0, d1 = refined['devices']
    assert d0['device_type'] == 'receptacle'
    assert d0['symbol_bbox'] == [142.0, 240.0, 158.0, 260.0]
    assert d0['symbol_center'] == [150.0, 250.0]
    assert d0['wall_connection_point'] == [150.0, 240.0]
    assert d0['target_vector_pts'] == [0.0, -10.0]

    assert d1['device_type'] == 'light_fixture'
    assert d1['on_wall'] is False
    assert d1['wall_connection_point'] == [150.0, 250.0]
    assert d1['target_vector_pts'] == [0.0, 0.0]

def test_review_gate_rejects_legacy_default_true_and_uncertain_items() -> None:
    batch = {
        'batch_index': 1,
        'items': [
            {'device_index': 0, 'pdf_page': 1, 'keep': True},  # legacy unreviewed default
            {'device_index': 1, 'pdf_page': 1, 'review_status': 'uncertain', 'keep': None},
            {'device_index': 2, 'pdf_page': 1, 'review_status': 'rejected', 'keep': False},
        ],
    }
    result = apply_batch_updates_to_devices([batch])
    assert result['devices'] == []
    assert result['summary']['skipped_unreviewed'] == 2
    assert result['summary']['removed_false_positives'] == 1
    assert result['verified_ground_truth'] is False
    assert result['exhaustive_families'] == []


def test_review_gate_refuses_invalid_accepted_geometry_and_labels() -> None:
    from blueprint_symbols.review_gate import review_decision
    item = {
        'review_status': 'accepted', 'keep': True, 'device_index': 1,
        'pdf_page': 2, 'refined_device_type': 'receptacle',
        'refined_subtype': 'duplex', 'refined_bbox_cell': [400, 400, 600, 600],
        'crop_pdf_rect_bl': [100, 100, 200, 200], 'on_wall': False,
        'attributes': {},
    }
    assert review_decision(item, {'receptacle': ['duplex']}) == 'accepted'
    for changes in [
        {'review_status': 'accepted', 'keep': None},
        {'refined_subtype': 'invented'},
        {'refined_bbox_cell': [800, 400, 200, 600]},
        {'refined_bbox_cell': [float('nan'), 400, 600, 600]},
        {'on_wall': None},
        {'on_wall': True, 'wall_contact_cell': [1001, 50]},
    ]:
        with pytest.raises(ValueError):
            review_decision({**item, **changes}, {'receptacle': ['duplex']})


def test_neca_fine_subtypes_extend_review_taxonomy_and_roundtrip():
    from scripts_circuitnet_review_sheets import merge_review_taxonomy
    extended = merge_review_taxonomy({
        'prompt_taxonomy': {'fire_alarm_detector': ['photoelectric_smoke_detector']},
    })
    assert 'duplex' in extended['receptacle']
    assert extended['fire_alarm_detector'] == ['photoelectric_smoke_detector', 'unspecified']
    batch = {
        'batch_index': 1, 'taxonomy': extended, 'items': [{
            'device_index': 3, 'pdf_page': 1,
            'review_status': 'accepted', 'keep': True,
            'provisional_category': 'fire_alarm_detection',
            'provisional_subcategory': 'photoelectric_smoke_detector',
            'refined_device_type': 'fire_alarm_detector',
            'refined_subtype': 'photoelectric_smoke_detector',
            'refined_bbox_cell': [200, 300, 400, 500],
            'crop_pdf_rect_bl': [10, 20, 30, 40],
            'on_wall': False, 'attributes': {},
        }],
    }
    output = apply_batch_updates_to_devices([batch])
    assert output['summary']['kept_devices'] == 1
    device = output['devices'][0]
    assert device['device_type'] == 'fire_alarm_detector'
    assert device['subtype'] == 'photoelectric_smoke_detector'
    assert device['category'] == 'fire_alarm_detection'
    assert device['verified_ground_truth'] is False


def test_neca_taxonomy_rejects_arbitrary_class_names():
    from scripts_circuitnet_review_sheets import merge_review_taxonomy
    for value in ({'Unknown name': ['foo']}, {'known': ['not valid!']}, {'bad': 'string'}):
        with pytest.raises(ValueError):
            merge_review_taxonomy({'prompt_taxonomy': value})


def test_neca_review_category_hierarchy_cannot_be_hallucinated():
    from blueprint_symbols.review_gate import review_decision
    item = {
        'review_status': 'accepted', 'keep': True, 'device_index': 1,
        'pdf_page': 2, 'refined_device_type': 'receptacle',
        'refined_subtype': 'duplex', 'refined_bbox_cell': [400, 400, 600, 600],
        'crop_pdf_rect_bl': [100, 100, 200, 200], 'on_wall': False,
        'provisional_category': 'power_outlet', 'provisional_subcategory': 'duplex_receptacle',
        'attributes': {},
    }
    category_taxonomy = {'power_outlet': ['duplex_receptacle', 'quad_receptacle']}
    assert review_decision(
        item, {'receptacle': ['duplex']}, category_taxonomy=category_taxonomy
    ) == 'accepted'
    with pytest.raises(ValueError, match='Unknown accepted category'):
        review_decision(
            {**item, 'refined_category': 'imaginary'}, {'receptacle': ['duplex']},
            category_taxonomy=category_taxonomy,
        )
    with pytest.raises(ValueError, match='Unknown subcategory'):
        review_decision(
            {**item, 'refined_subcategory': 'imaginary'}, {'receptacle': ['duplex']},
            category_taxonomy=category_taxonomy,
        )


def test_sam3_visual_group_to_review_sheet_and_approved_pdf_roundtrip(tmp_path):
    """The actual SAM3 output filename must reach human/VLM review intact."""
    import argparse
    import json
    import pymupdf

    from scripts_circuitnet_review_sheets import cmd_render, cmd_apply

    doc = pymupdf.open()
    page = doc.new_page(width=600, height=400)
    page.draw_rect(pymupdf.Rect(230, 150, 258, 180), color=(0, 0, 0))
    pdf_path = tmp_path / 'drawing.pdf'
    doc.save(pdf_path)
    doc.close()

    source = tmp_path / 'proposals'
    source.mkdir()
    payload = {
        'source_file': 'drawing.pdf',
        'source_pdf_relative': 'drawing.pdf',
        'source_pdf_absolute': str(pdf_path),
        'prompt_taxonomy': {'switch': ['unspecified', 'three_way']},
        'prompt_category_taxonomy': {'lighting_control': ['unspecified']},
        'devices': [{
            'pdf_page': 1,
            'device_type': 'switch',
            'subtype': 'unspecified',
            'category': 'lighting_control',
            'subcategory': 'unspecified',
            'score': .94,
            'symbol_bbox': [230, 220, 258, 250],
            'visual_group': 'switch.base_letter_s',
            'analytic_attributes': {'pole_configuration': 'three_way'},
            'modifier_evidence': [{
                'source': 'neca_suffix_template',
                'attribute': 'pole_configuration',
                'value': 'three_way',
                'score': .98,
                'box_px': [240, 155, 256, 170],
            }],
        }],
    }
    (source / 'drawing_12345678_abcdef123456_SAM3.json').write_text(json.dumps(payload))
    review_dir = tmp_path / 'review'
    cmd_render(argparse.Namespace(
        proposals_dir=source, data_root=tmp_path, out=review_dir,
        per_sheet=50, cols=10, cell_size=128, dpi=96,
        context_mult=2.5, min_context_pts=36.0,
        min_score=0.0, pdf_filter='', limit_pdfs=0,
        no_grid=True, include_pdf=False, zip=False,
    ))
    reviewed = list(review_dir.rglob('*_batch_001.json'))
    assert len(reviewed) == 1
    batch = json.loads(reviewed[0].read_text())
    assert batch['schema'] == 'blueprint_device_refinement_batch_v2'
    assert len(batch['items']) == 1
    item = batch['items'][0]
    assert item['review_status'] == 'unreviewed'
    assert item['keep'] is None
    assert item['visual_group'] == 'switch.base_letter_s'
    assert item['analytic_attributes'] == {'pole_configuration': 'three_way'}
    assert item['modifier_evidence'][0]['source'] == 'neca_suffix_template'
    assert reviewed[0].with_suffix('.png').exists()

    item.update({
        'review_status': 'accepted', 'review_source': 'human', 'keep': True,
        'refined_device_type': 'switch', 'refined_subtype': 'unspecified',
        'refined_bbox_cell': item['provisional_bbox_cell'],
        'on_wall': False,
        'attributes': {'pole_configuration': 'three_way'},
    })
    reviewed[0].write_text(json.dumps(batch))
    output = tmp_path / 'approved'
    cmd_apply(argparse.Namespace(review_dir=review_dir, out=output))
    results = list(output.glob('*_REFINED_DEVICES.json'))
    assert len(results) == 1
    result = json.loads(results[0].read_text())
    assert result['summary']['kept_devices'] == 1
    device = result['devices'][0]
    assert device['visual_group'] == 'switch.base_letter_s'
    assert device['analytic_attributes_proposed'] == {
        'pole_configuration': 'three_way'
    }
    assert device['attributes'] == {'pole_configuration': 'three_way'}
    assert device['review_source'] == 'human'
    assert not device['verified_ground_truth']
    assert device['symbol_bbox'] == [230, 220, 258, 250]
