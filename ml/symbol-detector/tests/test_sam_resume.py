"""Self-hosted scanner must not treat crash-truncated output as completed."""
import json

from blueprint_symbols.sam_resume import atomic_write_json, completed_sam_output


def _payload():
    return {
        'run_config_hash': 'runtime-model-123',
        'source_pdf_relative': '1_blueprints/a.pdf',
        'status': 'provisional_detections_for_refinement',
        'total_pdf_pages': 2,
        'pages_scanned': 1,
        'total_devices': 1,
        'pages': [{'pdf_page': 2}],
        'devices': [{'pdf_page': 2}],
    }


def _done(path):
    return completed_sam_output(
        path, run_config_hash='runtime-model-123',
        source_pdf_relative='1_blueprints/a.pdf',
    )


def test_atomic_output_survives_resume_and_rejects_wrong_run(tmp_path):
    path = tmp_path / 'result.json'
    assert not _done(path)
    atomic_write_json(path, _payload())
    assert _done(path)
    assert not completed_sam_output(
        path, run_config_hash='different',
        source_pdf_relative='1_blueprints/a.pdf',
    )
    assert not completed_sam_output(
        path, run_config_hash='runtime-model-123',
        source_pdf_relative='other.pdf',
    )
    assert list(tmp_path.glob('*.tmp')) == []


def test_truncated_or_inconsistent_output_is_recomputed(tmp_path):
    path = tmp_path / 'result.json'
    path.write_text('{"incomplete":')
    assert not _done(path)
    payload = _payload()
    payload['total_devices'] = 100
    path.write_text(json.dumps(payload))
    assert not _done(path)
    payload['total_devices'] = 1
    payload['pages_scanned'] = 4
    path.write_text(json.dumps(payload))
    assert not _done(path)
    payload['pages_scanned'] = 1
    atomic_write_json(path, payload)
    assert _done(path)


def test_empty_but_valid_page_scan_can_resume(tmp_path):
    path = tmp_path / 'empty.json'
    payload = _payload()
    payload['devices'] = []
    payload['pages'] = []
    payload['total_devices'] = 0
    payload['pages_scanned'] = 0
    atomic_write_json(path, payload)
    assert _done(path)
