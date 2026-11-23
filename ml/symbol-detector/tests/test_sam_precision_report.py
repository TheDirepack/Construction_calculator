"""Human proposal-precision reporting: never infer recall from candidates."""
import pytest

from scripts_sam_precision_report import summarize_sam_reviews, scanned_page_rates


def _item(index, status, keep, *, source='human',
          typ='switch', visual='switch.base_letter_s', score=.94, page=1):
    return {
        'device_index': index, 'pdf_page': page, 'score': score,
        'provisional_device_type': typ, 'visual_group': visual,
        'review_status': status, 'keep': keep,
        'review_source': source,
    }


def _batch(items, *, doc='project/plan.pdf', proposal='scan_1_SAM3.json'):
    return {
        'source_pdf_relative': doc,
        'source_circuitnet_json': proposal,
        'items': items,
    }


def test_human_and_vlm_separation_partial_review():
    report = summarize_sam_reviews([_batch([
        _item(0,'accepted',True,score=.99),
        _item(1,'rejected',False,score=.97),
        _item(2,'rejected',False,score=.71),
        _item(3,'accepted',True,source='vlm'),
        _item(4,'rejected',False,source='vlm'),
        _item(5,'accepted',True,source=None),
        _item(6,'unreviewed',None),
        _item(7,'uncertain',None),
        _item(8,'accepted',False),
    ])])
    assert report['total_candidates'] == 9
    assert report['human_review_coverage'] == round(3/9, 4)
    assert report['human_candidate_precision'] == {
        'accepted': 1, 'rejected': 2, 'decided': 3,
        'candidate_precision_proxy': round(1/3,4),
        'rejection_fraction': round(2/3,4),
    }
    assert report['vlm_advisory']['decided'] == 2
    assert report['unattributed_advisory']['decided'] == 1
    assert report['high_score_human_rejections_ge_0_90'] == 1
    assert report['pending_or_uncertain'] == 2
    assert report['inconsistent_decisions'] == 1
    assert report['recall'] is None
    assert report['false_boxes_per_full_pdf_page'] is None
    assert report['human_reviewed_pages'] == 1
    assert report['by_visual_group']['switch.base_letter_s']['decided'] == 3


def test_no_human_decisions_cannot_claim_precision():
    report = summarize_sam_reviews([_batch([
        _item(0,'accepted',True,source='vlm'),
        _item(1,'rejected',False,source=None),
        _item(2,'unreviewed',None),
    ])])
    assert report['human_candidate_precision']['candidate_precision_proxy'] is None
    assert report['human_review_coverage'] == 0
    assert report['vlm_advisory']['accepted'] == 1
    assert report['unattributed_advisory']['rejected'] == 1


def test_reports_separate_pdf_families_and_score_ranges():
    report = summarize_sam_reviews([
        _batch([
            _item(0,'rejected',False,score=.98),
            _item(1,'accepted',True,score=.83,typ='receptacle',
                  visual='receptacle.wall_duplex_open',page=2),
        ], doc='a/plan.pdf', proposal='scan_a.json'),
        _batch([
            _item(0,'accepted',True,score=.55),
        ], doc='b/plan.pdf', proposal='scan_b.json'),
    ])
    assert report['human_reviewed_documents'] == 2
    assert report['human_reviewed_pages'] == 3
    assert report['by_device_family']['switch']['decided'] == 2
    assert report['by_device_family']['receptacle']['accepted'] == 1
    assert report['by_score_bucket']['0.95-1.00']['rejected'] == 1
    assert report['by_score_bucket']['0.70-0.84']['accepted'] == 1
    assert report['by_score_bucket']['0.50-0.69']['accepted'] == 1


def test_duplicate_review_does_not_inflate_precision():
    batch = _batch([
        _item(0,'accepted',True),
        _item(0,'accepted',True),
    ])
    with pytest.raises(ValueError, match='duplicate candidate'):
        summarize_sam_reviews([batch])


def test_invalid_or_empty_input_is_rejected():
    with pytest.raises(ValueError, match='no review'):
        summarize_sam_reviews([])
    with pytest.raises(ValueError, match='items array'):
        summarize_sam_reviews([{}])
    with pytest.raises(ValueError, match='JSON objects'):
        summarize_sam_reviews([_batch([None])])



def test_scanned_page_false_boxes_only_when_every_proposal_human_reviewed(tmp_path):
    import json
    proposal = tmp_path / 'scan_1_SAM3.json'
    proposal.write_text(json.dumps({
        'source_pdf_relative': 'project/plan.pdf',
        'total_devices': 3,
        'pages_scanned': 5,
        'devices': [{}, {}, {}],
    }))
    batch = _batch([
        _item(0, 'accepted', True),
        _item(1, 'rejected', False),
        _item(2, 'accepted', True),
    ])
    report = summarize_sam_reviews([batch])
    rates = scanned_page_rates([batch], [proposal], report)
    assert rates == {
        'source_proposals_reconciled': 1,
        'scanned_pages': 5,
        'source_candidate_boxes': 3,
        'candidate_boxes_per_scanned_page': .6,
        'known_human_false_boxes_per_scanned_page_lower_bound': .2,
        'human_review_complete': True,
        'false_boxes_per_scanned_page': .2,
    }
    incomplete = _batch([
        _item(0, 'accepted', True),
        _item(1, 'rejected', False),
        _item(2, 'accepted', True, source='vlm'),
    ])
    rpt = summarize_sam_reviews([incomplete])
    partial = scanned_page_rates([incomplete], [proposal], rpt)
    assert partial['human_review_complete'] is False
    assert partial['false_boxes_per_scanned_page'] is None
    assert partial['known_human_false_boxes_per_scanned_page_lower_bound'] == .2


def test_page_rate_requires_all_source_proposals_and_consistent_pdf(tmp_path):
    import json
    proposal = tmp_path / 'scan_1_SAM3.json'
    proposal.write_text(json.dumps({
        'source_pdf_relative': 'project/plan.pdf',
        'total_devices': 2,
        'pages_scanned': 3,
        'devices': [{}, {}],
    }))
    only_one_reviewed = _batch([_item(0, 'rejected', False)])
    report = summarize_sam_reviews([only_one_reviewed])
    with pytest.raises(ValueError, match='cover 1 proposals'):
        scanned_page_rates([only_one_reviewed], [proposal], report)
    fully_reviewed = _batch([
        _item(0, 'accepted', True), _item(1, 'rejected', False),
    ])
    report = summarize_sam_reviews([fully_reviewed])
    wrong_pdf = dict(fully_reviewed, source_pdf_relative='another/plan.pdf')
    with pytest.raises(ValueError, match='source PDF mismatch'):
        scanned_page_rates([wrong_pdf], [proposal], report)
    with pytest.raises(ValueError, match='no matching source proposal'):
        scanned_page_rates([fully_reviewed], [], report)



def test_precision_report_cli_reconciles_review_and_sam_json(tmp_path, monkeypatch):
    import json
    import sys
    from scripts_sam_precision_report import main

    proposals = tmp_path / 'proposals'
    reviews = tmp_path / 'reviews' / 'my_pdf'
    proposals.mkdir()
    reviews.mkdir(parents=True)
    (proposals / 'scan_1_SAM3.json').write_text(json.dumps({
        'source_pdf_relative': 'project/plan.pdf',
        'total_devices': 2,
        'pages_scanned': 4,
        'devices': [{}, {}],
    }))
    (reviews / 'my_pdf_batch_001.json').write_text(json.dumps(_batch([
        _item(0, 'accepted', True),
        _item(1, 'rejected', False),
    ])))
    output = tmp_path / 'report.json'
    monkeypatch.setattr(sys, 'argv', [
        'scripts_sam_precision_report.py',
        '--review-dir', str(reviews.parent),
        '--proposals-dir', str(proposals),
        '--out', str(output),
    ])
    main()
    result = json.loads(output.read_text())
    assert result['review_batch_files'] == 1
    assert result['scanned_page_rates']['scanned_pages'] == 4
    assert result['scanned_page_rates']['candidate_boxes_per_scanned_page'] == .5
    assert result['scanned_page_rates']['false_boxes_per_scanned_page'] == .25
    assert result['false_boxes_per_full_pdf_page'] is None
    assert result['recall'] is None
