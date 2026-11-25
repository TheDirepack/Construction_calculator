#!/usr/bin/env python3
"""Download modern public building & MEP blueprint PDF sets from SAM.gov.

Uses SAM.gov's public opportunity search API and attachment resource API (no API key
required). Strictly skips private/CUI attachments, non-PDFs, small forms/specs,
duplicates (SHA-256), and locked evaluation holdout projects.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
import threading

sys.path.insert(0, str(Path(__file__).resolve().parent))
from blueprint_dl_utils import (
    BLUEPRINTS_DIR,
    CTX,
    DEFAULT_HEADERS,
    DRAWING_INCLUDE_RE,
    NON_DRAWING_EXCLUDE_RE,
    SourceManifest,
    download_and_validate_pdf,
    load_known_hashes_and_holdout,
    matches_holdout,
    safe_filename,
)

DEST_DIR = BLUEPRINTS_DIR

# Construction & MEP NAICS codes (commercial, institutional, industrial, electrical, HVAC/plumbing, multifamily)
SEARCH_SPECS = [
    ('236220', 'drawings'),
    ('238210', 'drawings'),
    ('238220', 'drawings'),
    ('236220', 'bid set'),
    ('236220', 'electrical drawings'),
    ('236220', 'floor plan'),
    ('236210', 'drawings'),
    ('236116', 'drawings'),
]


def fetch_json(url: str, timeout: int = 30) -> dict:
    req = urllib.request.Request(url, headers=DEFAULT_HEADERS)
    with urllib.request.urlopen(req, timeout=timeout, context=CTX) as r:
        return json.loads(r.read())


def collect_candidates(
    max_pages_per_query: int,
    include_archived: bool,
    holdout_tokens: set[str],
    manifest: SourceManifest,
    delay: float,
) -> list[dict]:
    seen_rids: set[str] = set()
    candidates: list[dict] = []
    states = ['true', 'false'] if include_archived else ['true']

    for is_active in states:
        for naics, q in SEARCH_SPECS:
            for page in range(max_pages_per_query):
                s_url = (
                    'https://sam.gov/api/prod/sgs/v1/search/?random=1&index=opp'
                    f'&page={page}&size=25&sort=-modifiedDate&mode=search'
                    f'&is_active={is_active}&naics={naics}&q={urllib.parse.quote(q)}'
                )
                try:
                    data = fetch_json(s_url)
                except Exception as e:  # noqa: BLE001
                    print(f'[sam search err] naics={naics} q={q} p={page}: {e}', flush=True)
                    break
                results = data.get('_embedded', {}).get('results', [])
                if not results:
                    break

                for item in results:
                    oid = item.get('_id')
                    sol = str(item.get('solicitationNumber') or oid or '')
                    title = str(item.get('title') or '')
                    if not oid or matches_holdout(sol + ' ' + title, holdout_tokens):
                        continue

                    r_url = f'https://sam.gov/api/prod/opps/v3/opportunities/{oid}/resources?api_key=null'
                    try:
                        rd = fetch_json(r_url, timeout=20)
                    except Exception:
                        continue
                    att_lists = rd.get('_embedded', {}).get('opportunityAttachmentList', [])
                    atts = att_lists[0].get('attachments', []) if att_lists else []

                    for a in atts:
                        if a.get('accessLevel') != 'public':
                            continue
                        nm = str(a.get('name') or '')
                        rid = str(a.get('resourceId') or '')
                        sz = int(a.get('size') or 0)
                        if not rid or rid in seen_rids:
                            continue
                        if not nm.lower().endswith('.pdf'):
                            continue
                        if sz and (sz < 500_000 or sz > 250_000_000):
                            continue
                        if not DRAWING_INCLUDE_RE.search(nm) or NON_DRAWING_EXCLUDE_RE.search(nm):
                            continue
                        dl_url = f'https://sam.gov/api/prod/opps/v3/opportunities/resources/files/{rid}/download'
                        if manifest.has_url(dl_url):
                            continue
                        seen_rids.add(rid)
                        candidates.append({
                            'url': dl_url,
                            'rid': rid,
                            'name': nm,
                            'sol': sol,
                            'opp_id': oid,
                            'title': title[:160],
                            'naics': naics,
                            'posted_date': str(item.get('publishDate') or item.get('modifiedDate') or '')[:10],
                            'expected_bytes': sz,
                        })
                if delay > 0:
                    time.sleep(delay)
                total_pages = int(data.get('page', {}).get('totalPages') or 0)
                if page + 1 >= total_pages:
                    break
            print(
                f'  query active={is_active} naics={naics} q={q!r}: total candidates so far={len(candidates)}',
                flush=True,
            )
    return candidates


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--max-downloads', type=int, default=200)
    parser.add_argument('--max-pages-per-query', type=int, default=12)
    parser.add_argument('--active-only', action='store_true')
    parser.add_argument('--workers', type=int, default=3)
    parser.add_argument('--delay', type=float, default=0.15)
    args = parser.parse_args()

    DEST_DIR.mkdir(parents=True, exist_ok=True)
    known_shas, _holdout_shas, holdout_tokens = load_known_hashes_and_holdout()
    manifest = SourceManifest(
        'sam_gov',
        {
            'source': 'SAM.gov Contract Opportunities (public attachments)',
            'api_base': 'https://sam.gov/api/prod/',
            'naics_codes': sorted({n for n, _ in SEARCH_SPECS}),
        },
    )

    print(f'Collecting SAM.gov blueprint candidates (max_downloads={args.max_downloads})...', flush=True)
    candidates = collect_candidates(
        max_pages_per_query=args.max_pages_per_query,
        include_archived=not args.active_only,
        holdout_tokens=holdout_tokens,
        manifest=manifest,
        delay=args.delay,
    )
    # Prioritize larger blueprint sets first (higher chance of full multi-discipline sets)
    candidates.sort(key=lambda c: c['expected_bytes'], reverse=True)
    print(f'Collected {len(candidates)} new public PDF drawing candidates.', flush=True)

    sha_lock = threading.Lock()
    downloaded_ok = 0
    processed = 0
    stop_flag = threading.Event()

    def worker(cand: dict) -> dict:
        if stop_flag.is_set():
            return {'status': 'skipped_limit'}
        fn = safe_filename(cand['sol'], cand['name'], cand['rid'])
        res = download_and_validate_pdf(
            cand['url'],
            DEST_DIR,
            fn,
            known_sha256s=known_shas,
            holdout_tokens=holdout_tokens,
            sha_lock=sha_lock,
            extra_meta={
                'solicitation': cand['sol'],
                'opp_id': cand['opp_id'],
                'title': cand['title'],
                'naics': cand['naics'],
                'posted_date': cand['posted_date'],
                'orig_name': cand['name'],
            },
            min_bytes=500_000,
            min_pages=4,
            min_large_ratio=0.30,
        )
        return res

    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        batch_size = min(len(candidates), args.max_downloads * 3)
        futures = [pool.submit(worker, c) for c in candidates[:batch_size]]
        for fut in as_completed(futures):
            res = fut.result()
            if res.get('status') == 'skipped_limit':
                continue
            processed += 1
            manifest.record(res, flush=(processed % 10 == 0))
            st = res.get('status')
            if st == 'ok':
                downloaded_ok += 1
                print(
                    f"  [OK {downloaded_ok}/{args.max_downloads}] {res['file']} "
                    f"({res['bytes']/1024/1024:.1f} MB, {res.get('pages')} pp, {res.get('median_size_in')})",
                    flush=True,
                )
                if downloaded_ok >= args.max_downloads:
                    stop_flag.set()
            elif st in ('rejected_not_blueprint', 'error'):
                print(f"  [{st}] {res.get('file')}: {res.get('reason') or res.get('detail')}", flush=True)

    manifest.save()
    print(f'SAM.gov done: {downloaded_ok} new PDFs downloaded, {processed} processed.', flush=True)


if __name__ == '__main__':
    main()
