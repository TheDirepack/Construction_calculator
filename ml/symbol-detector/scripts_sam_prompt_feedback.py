"""Summarize which SAM NECA exemplars produce noisy reviewed proposals.

Works entirely locally; consumes existing VLM/human review-batch JSON files.
Model review decisions are NOT ground truth. Suggested denylist entries use
human-only decisions and are never applied automatically.
"""
from __future__ import annotations

import argparse
import json
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any


def summarize_prompt_feedback(batches: list[dict[str, Any]], *,
                              min_reviews: int = 10,
                              min_documents: int = 2,
                              reject_fraction: float = 0.95) -> dict[str, Any]:
    if min_reviews < 1 or min_documents < 1 or not 0 < reject_fraction <= 1:
        raise ValueError('invalid review thresholds')
    stats: dict[str, Counter] = defaultdict(Counter)
    documents: dict[str, set[str]] = defaultdict(set)
    human_documents: dict[str, set[str]] = defaultdict(set)
    for batch in batches:
        doc = str(batch.get('source_pdf_relative') or batch.get('source_file') or 'unknown')
        for item in batch.get('items', []):
            sid = item.get('neca_symbol_id')
            decision = item.get('review_status')
            if not isinstance(sid, str) or not sid or decision not in ('accepted', 'rejected'):
                continue
            source = item.get('review_source')
            source = str(source).lower() if source is not None else 'unattributed'
            stats[sid][decision] += 1
            stats[sid]['reviewed'] += 1
            documents[sid].add(doc)
            if source == 'human':
                stats[sid]['human_' + decision] += 1
                stats[sid]['human_reviewed'] += 1
                human_documents[sid].add(doc)
    ranked = []
    suggestions = []
    for sid, s in stats.items():
        count = s['reviewed']
        h = s['human_reviewed']
        rejected = s['rejected']
        hr = s['human_rejected']
        entry = {
            'neca_symbol_id': sid,
            'reviewed': count,
            'rejected': rejected,
            'accepted': s['accepted'],
            'rejected_fraction': round(rejected / count, 4),
            'documents': len(documents[sid]),
            'human_reviewed': h,
            'human_rejected': hr,
            'human_accepted': s['human_accepted'],
            'human_rejected_fraction': round(hr / h, 4) if h else None,
            'human_documents': len(human_documents[sid]),
        }
        ranked.append(entry)
        if (h >= min_reviews and len(human_documents[sid]) >= min_documents
                and hr / h >= reject_fraction
                and s['human_accepted'] == 0):
            suggestions.append(sid)
    ranked.sort(key=lambda x: (-x['rejected'], -x['reviewed'], x['neca_symbol_id']))
    return {
        'reviewed_prompts': len(ranked),
        'reviewed_proposals': sum(x['reviewed'] for x in ranked),
        'ranked': ranked,
        'manual_denylist_suggestions': sorted(suggestions),
        'warning': 'VLM and unknown-reviewer decisions are advisory. Denylist suggestions require human reviews and are not applied automatically.',
    }


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--review-dir', type=Path, required=True)
    ap.add_argument('--out', type=Path, required=True)
    ap.add_argument('--min-human-reviews', type=int, default=10)
    ap.add_argument('--min-documents', type=int, default=2)
    ap.add_argument('--min-human-reject-fraction', type=float, default=0.95)
    ap.add_argument('--suggested-denylist', type=Path,
                    help='Optional separate JSON output; inspect manually before using as --prompt-denylist')
    args = ap.parse_args()
    files = sorted(args.review_dir.rglob('*_batch_*.json'))
    if not files:
        ap.error('no existing review batch JSON files found')
    payloads = [json.loads(p.read_text()) for p in files]
    report = summarize_prompt_feedback(
        payloads, min_reviews=args.min_human_reviews,
        min_documents=args.min_documents,
        reject_fraction=args.min_human_reject_fraction,
    )
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, indent=2))
    if args.suggested_denylist:
        args.suggested_denylist.parent.mkdir(parents=True, exist_ok=True)
        args.suggested_denylist.write_text(
            json.dumps(report['manual_denylist_suggestions'], indent=2)
        )
    print(json.dumps({
        'review_batches': len(files),
        'reviewed_prompts': report['reviewed_prompts'],
        'reviewed_proposals': report['reviewed_proposals'],
        'manual_suggestions': len(report['manual_denylist_suggestions']),
        'report_file': str(args.out),
    }, indent=2))


if __name__ == '__main__':
    main()
