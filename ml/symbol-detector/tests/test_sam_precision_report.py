"""Human proposal-precision reporting: never infer recall from candidates."""
import pytest

from scripts_sam_precision_report import summarize_sam_reviews


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
