"""Local feedback reports never auto-trust VLM-only rejection counts."""
from scripts_sam_prompt_feedback import summarize_prompt_feedback


def _batch(name, items):
    return {'source_pdf_relative': name, 'items': items}


def _entry(symbol_id, *, source='human', decision='rejected'):
    return {'neca_symbol_id': symbol_id, 'review_status': decision,
            'review_source': source}


def test_noisy_reference_suggestions_need_cross_document_human_review():
    batches = [
        _batch('a.pdf', [_entry('bad') for _ in range(6)] +
               [_entry('vlm_only', source='vlm') for _ in range(20)]),
        _batch('b.pdf', [_entry('bad') for _ in range(5)] +
               [_entry('vlm_only', source='vlm') for _ in range(20)]),
    ]
    result = summarize_prompt_feedback(batches, min_reviews=10,
                                       min_documents=2, reject_fraction=.95)
    assert result['manual_denylist_suggestions'] == ['bad']
    ranks = {x['neca_symbol_id']: x for x in result['ranked']}
    assert ranks['bad']['human_reviewed'] == 11
    assert ranks['vlm_only']['human_reviewed'] == 0
    assert ranks['vlm_only']['reviewed'] == 40


def test_one_confirmed_positive_blocks_automatic_prompt_deny_suggestion():
    batches = [
        _batch('a.pdf', [_entry('good') for _ in range(11)]),
        _batch('b.pdf', [_entry('good'), _entry('good', decision='accepted')]),
    ]
    result = summarize_prompt_feedback(batches, min_reviews=10,
                                       min_documents=2, reject_fraction=.90)
    assert result['manual_denylist_suggestions'] == []


def test_unreviewed_proposals_are_not_counted_as_false_positives():
    result = summarize_prompt_feedback([
        _batch('doc.pdf', [{'neca_symbol_id': 'x', 'review_status': 'unreviewed'},
                           {'neca_symbol_id': 'x', 'review_status': 'uncertain'}])
    ])
    assert result['reviewed_prompts'] == 0
    assert result['reviewed_proposals'] == 0
