"""Measure review burden and candidate precision for self-hosted SAM proposals.

Only explicit human review decisions count toward the human precision proxy.
VLM and unattributed decisions are reported separately, NEVER as truth.
Reviewing detections alone cannot estimate recall or exhaustive coverage.

Usage:
  python scripts_sam_precision_report.py --review-dir work/sam/review \
      --out work/sam/precision_review_report.json
"""
from __future__ import annotations

import argparse
import json
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any


def _score_bucket(score: Any) -> str:
    try:
        v = float(score)
    except (TypeError, ValueError):
        return 'unknown'
    if not 0 <= v <= 1:
        return 'unknown'
    if v < 0.5:
        return '0.00-0.49'
    if v < 0.7:
        return '0.50-0.69'
    if v < 0.85:
        return '0.70-0.84'
    if v < 0.95:
        return '0.85-0.94'
    return '0.95-1.00'


def _decisions(counter: Counter) -> dict[str, Any]:
    accepted = counter['accepted']
    rejected = counter['rejected']
    reviewed = accepted + rejected
    return {
        'accepted': accepted,
        'rejected': rejected,
        'decided': reviewed,
        'candidate_precision_proxy': round(accepted / reviewed, 4) if reviewed else None,
        'rejection_fraction': round(rejected / reviewed, 4) if reviewed else None,
    }


def summarize_sam_reviews(batches: list[dict[str, Any]]) -> dict[str, Any]:
    """Summarize only verifiable status+keep pairs.

    Any confidence interval would assume representative sampling; do not
    invent one for selective human review. Score buckets are descriptive,
    not calibrated classifier confidence.
    """
    if not batches:
        raise ValueError('no review batches supplied')
    family: dict[str, Counter] = defaultdict(Counter)
    visual_group: dict[str, Counter] = defaultdict(Counter)
    score_bucket: dict[str, Counter] = defaultdict(Counter)
    document: dict[str, Counter] = defaultdict(Counter)
    by_source: dict[str, Counter] = defaultdict(Counter)
    counts = Counter()
    all_pages: set[tuple[str, int]] = set()
    human_pages: set[tuple[str, int]] = set()
    human_documents: set[str] = set()
    seen: set[tuple[str, str]] = set()

    for batch in batches:
        if not isinstance(batch, dict) or not isinstance(batch.get('items'), list):
            raise ValueError('each review batch must contain an items array')
        doc = str(batch.get('source_pdf_relative') or batch.get('source_file') or 'unknown')
        proposal = str(batch.get('source_circuitnet_json') or doc)
        for item in batch['items']:
            if not isinstance(item, dict):
                raise ValueError('review items must be JSON objects')
            counts['all_candidates'] += 1
            index = item.get('device_index')
            if index is not None:
                key = (proposal, str(index))
                if key in seen:
                    raise ValueError(f'duplicate candidate in review input: {key}')
                seen.add(key)
            page = item.get('pdf_page')
            if type(page) is int and page >= 1:
                all_pages.add((doc, page))
            src = str(item.get('review_source') or 'unattributed').lower()
            status = item.get('review_status')
            keep = item.get('keep')
            if (status == 'accepted' and keep is True):
                decision = 'accepted'
            elif status == 'rejected' and keep is False:
                decision = 'rejected'
            elif status in ('accepted', 'rejected'):
                counts['inconsistent_decisions'] += 1
                continue
            else:
                counts['pending_or_uncertain'] += 1
                continue
            counts['decided_candidates'] += 1
            if src not in ('human', 'vlm'):
                src = 'unattributed'
            by_source[src][decision] += 1
            if src != 'human':
                continue
            counts['human_decisions'] += 1
            human_documents.add(doc)
            if type(page) is int and page >= 1:
                human_pages.add((doc, page))
            name = str(item.get('provisional_device_type') or 'unknown')
            group = str(item.get('visual_group') or 'unknown')
            family[name][decision] += 1
            visual_group[group][decision] += 1
            score = item.get('score')
            score_bucket[_score_bucket(score)][decision] += 1
            document[doc][decision] += 1
            if decision == 'rejected':
                try:
                    if float(score) >= .90:
                        counts['high_score_human_rejections'] += 1
                except (ValueError, TypeError):
                    pass

    human = _decisions(by_source['human'])
    human_coverage = (
        counts['human_decisions'] / counts['all_candidates']
        if counts['all_candidates'] else 0.0
    )
    return {
        'schema': 'sam_precision_review_report_v1',
        'total_candidates': counts['all_candidates'],
        'total_decided': counts['decided_candidates'],
        'pending_or_uncertain': counts['pending_or_uncertain'],
        'inconsistent_decisions': counts['inconsistent_decisions'],
        'reviewed_candidate_pages': len(all_pages),
        'human_reviewed_pages': len(human_pages),
        'human_reviewed_documents': len(human_documents),
        'human_review_coverage': round(human_coverage, 4),
        'human_candidate_precision': human,
        'vlm_advisory': _decisions(by_source['vlm']),
        'unattributed_advisory': _decisions(by_source['unattributed']),
        'high_score_human_rejections_ge_0_90': counts['high_score_human_rejections'],
        'by_device_family': {k: _decisions(v) for k, v in sorted(family.items())},
        'by_visual_group': {k: _decisions(v) for k, v in sorted(visual_group.items())},
        'by_score_bucket': {k: _decisions(v) for k, v in sorted(score_bucket.items())},
        'by_document': {k: _decisions(v) for k, v in sorted(document.items())},
        'recall': None,
        'false_boxes_per_full_pdf_page': None,
        'limitations': (
            'Candidate precision proxy uses explicit human accepted/rejected decisions '
            'only; partial or selective review can bias the result. VLM/unattributed '
            'decisions are advisory. Score is not calibrated probability. Recall '
            'requires an independently exhaustive device inventory; review batches '
            'cannot establish it. Full-page false-box rates require the number '
            'of scanned pages, not just pages containing candidate boxes.'
        ),
    }




def scanned_page_rates(
    batches: list[dict[str, Any]],
    proposal_files: list[Path],
    report: dict[str, Any],
) -> dict[str, Any]:
    """Require exact review/proposal reconciliation before page-rate claims.

    A human review can be selectively incomplete. In that case only the
    *known* false-box lower bound is available, not a complete false-box rate.
    """
    if not proposal_files:
        raise ValueError('no matching source proposal JSONs supplied for page-rate evaluation')
    by_name: dict[str, dict] = {}
    for path in proposal_files:
        if path.name in by_name:
            raise ValueError(f'ambiguous duplicate proposal basename: {path.name}')
        payload = json.loads(path.read_text())
        if not isinstance(payload, dict) or not isinstance(payload.get('devices'), list):
            raise ValueError(f'invalid proposal device array: {path}')
        by_name[path.name] = payload

    review_counts = Counter()
    review_documents = {}
    for batch in batches:
        name = Path(str(batch.get('source_circuitnet_json') or '')).name
        if not name or name not in by_name:
            raise ValueError(f'no matching source proposal for review batch: {name}')
        review_counts[name] += len(batch.get('items', []))
        doc = str(batch.get('source_pdf_relative') or batch.get('source_file') or '')
        if name in review_documents and review_documents[name] != doc:
            raise ValueError(f'inconsistent PDF identity in review batches for {name}')
        review_documents[name] = doc

    total_pages = 0
    total_proposals = 0
    for name, count in review_counts.items():
        payload = by_name[name]
        count_from_proposal = payload.get('total_devices')
        pages = payload.get('pages_scanned')
        if type(count_from_proposal) is not int or count_from_proposal != len(payload['devices']):
            raise ValueError(f'invalid total_devices on source proposal: {name}')
        if type(pages) is not int or pages <= 0:
            raise ValueError(f'missing positive pages_scanned on source proposal: {name}')
        if count != count_from_proposal:
            raise ValueError(
                f'review batches for {name} cover {count} proposals; '
                f'source contains {count_from_proposal}. Refusing a biased page-rate calculation.'
            )
        proposal_doc = str(payload.get('source_pdf_relative') or payload.get('source_file') or '')
        if review_documents[name] != proposal_doc:
            raise ValueError(f'review source PDF mismatch for {name}')
        total_pages += pages
        total_proposals += count

    if report['total_candidates'] != total_proposals:
        raise ValueError('summarized review count differs from reconciled proposals')
    rejected = report['human_candidate_precision']['rejected']
    human_complete = (
        report['human_candidate_precision']['decided'] == report['total_candidates']
        and report['inconsistent_decisions'] == 0
        and report['pending_or_uncertain'] == 0
    )
    return {
        'source_proposals_reconciled': len(review_counts),
        'scanned_pages': total_pages,
        'source_candidate_boxes': total_proposals,
        'candidate_boxes_per_scanned_page': round(total_proposals / total_pages, 4),
        'known_human_false_boxes_per_scanned_page_lower_bound': round(rejected / total_pages, 4),
        'human_review_complete': human_complete,
        'false_boxes_per_scanned_page': round(rejected / total_pages, 4) if human_complete else None,
    }

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--review-dir', type=Path, required=True)
    parser.add_argument('--proposals-dir', type=Path,
                        help='Optional original *_SAM3.json files for verified per-scanned-page box rates')
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    files = sorted(args.review_dir.rglob('*_batch_*.json'))
    if not files:
        parser.error('no *_batch_*.json review files found')
    batches = [json.loads(p.read_text()) for p in files]
    report = summarize_sam_reviews(batches)
    if args.proposals_dir is not None:
        originals = sorted(args.proposals_dir.glob('*_SAM3.json'))
        originals += sorted(args.proposals_dir.glob('*_CIRCUITNET.json'))
        report['scanned_page_rates'] = scanned_page_rates(batches, originals, report)
    report['review_batch_files'] = len(files)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({
        'total_candidates': report['total_candidates'],
        'human_review_coverage': report['human_review_coverage'],
        'human_candidate_precision': report['human_candidate_precision'],
        'scanned_page_rates': report.get('scanned_page_rates'),
        'high_score_human_rejections_ge_0_90': report['high_score_human_rejections_ge_0_90'],
        'output': str(args.out),
    }, indent=2))


if __name__ == '__main__':
    main()
