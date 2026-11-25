#!/usr/bin/env python3
"""Download modern construction blueprint PDFs via WordPress REST Media API."""
from __future__ import annotations

import argparse
import json
import sys
import threading
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

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
    safe_filename,
)

DEFAULT_WP_SITES = [
    ('pbc_chicago', 'https://pbcchicago.com', 'Public Building Commission of Chicago'),
    ('port_wentworth_ga', 'https://www.portwentworthga.gov', 'City of Port Wentworth GA'),
    ('south_bend_in', 'https://southbendin.gov', 'City of South Bend IN'),
]

SEARCH_TERMS = ['drawings', 'dwg', 'plans', 'bid', 'architectural', 'electrical', 'ifc', 'cd', 'adu']


def collect_wp_media(base_url: str, max_pages: int = 8) -> list[dict]:
    found: dict[str, dict] = {}
    for term in SEARCH_TERMS:
        for page in range(1, max_pages + 1):
            api_url = (
                f'{base_url.rstrip("/")}/wp-json/wp/v2/media'
                f'?mime_type=application/pdf&search={urllib.parse.quote(term)}&per_page=100&page={page}'
            )
            try:
                req = urllib.request.Request(api_url, headers=DEFAULT_HEADERS)
                with urllib.request.urlopen(req, timeout=20, context=CTX) as r:
                    total_pages = int(r.headers.get('X-WP-TotalPages') or 1)
                    items = json.loads(r.read())
            except Exception:
                break
            if not isinstance(items, list) or not items:
                break
            for it in items:
                url = str(it.get('source_url') or '')
                date_str = str(it.get('date') or '')[:10]
                if not url or not url.lower().endswith('.pdf'):
                    continue
                if date_str and date_str < '2006-01-01':
                    continue
                title = str((it.get('title') or {}).get('rendered') or '')
                orig = urllib.parse.unquote(url.rsplit('/', 1)[-1].split('?')[0])
                combined = f'{orig} {title}'
                if NON_DRAWING_EXCLUDE_RE.search(combined):
                    continue
                if not DRAWING_INCLUDE_RE.search(combined):
                    continue
                found[url] = {'url': url, 'orig_name': orig, 'title': title, 'date': date_str}
            if page >= total_pages:
                break
            time.sleep(0.1)
    return list(found.values())


def scrape_site(slug_name: str, base_url: str, label: str, max_downloads: int, workers: int) -> int:
    dest_dir = BLUEPRINTS_DIR
    dest_dir.mkdir(parents=True, exist_ok=True)
    known_shas, _holdout_shas, holdout_tokens = load_known_hashes_and_holdout()
    manifest = SourceManifest(f'wp_{slug_name}', {'source': base_url, 'organization': label})

    candidates = [c for c in collect_wp_media(base_url) if not manifest.has_url(c['url'])]
    print(f'[{label}] {len(candidates)} candidate modern PDFs from WP REST API', flush=True)

    sha_lock = threading.Lock()
    downloaded = 0
    stop_flag = threading.Event()

    def worker(cand: dict) -> dict:
        if stop_flag.is_set():
            return {'status': 'skipped_limit'}
        fn = safe_filename(slug_name, cand['orig_name'], max_len=95)
        return download_and_validate_pdf(
            cand['url'],
            dest_dir,
            fn,
            known_sha256s=known_shas,
            holdout_tokens=holdout_tokens,
            sha_lock=sha_lock,
            extra_meta={'title': cand['title'][:160], 'date': cand['date'], 'orig_name': cand['orig_name']},
            min_bytes=200_000,
            min_pages=3,
            min_large_ratio=0.25,
        )

    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(worker, c) for c in candidates]
        for i, fut in enumerate(as_completed(futures), 1):
            res = fut.result()
            if res.get('status') == 'skipped_limit':
                continue
            manifest.record(res, flush=(i % 10 == 0))
            if res.get('status') == 'ok':
                downloaded += 1
                print(
                    f"  [{slug_name} OK {downloaded}] {res['file']} ({res['bytes']/1024/1024:.1f} MB, {res.get('pages')} pp)",
                    flush=True,
                )
                if downloaded >= max_downloads:
                    stop_flag.set()

    manifest.save()
    print(f'[{label}] done: {downloaded} new blueprint PDFs saved.', flush=True)
    return downloaded


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--max-per-site', type=int, default=80)
    parser.add_argument('--workers', type=int, default=3)
    parser.add_argument('--only', action='append', help='Restrict to specific site slug(s)')
    args = parser.parse_args()

    sites = DEFAULT_WP_SITES
    if args.only:
        only_set = set(args.only)
        sites = [s for s in sites if s[0] in only_set]

    total = 0
    for slug_name, base_url, label in sites:
        total += scrape_site(slug_name, base_url, label, args.max_per_site, args.workers)
    print(f'WP Municipal total new PDFs: {total}', flush=True)


if __name__ == '__main__':
    main()
