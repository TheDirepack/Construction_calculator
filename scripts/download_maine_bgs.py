#!/usr/bin/env python3
"""Download modern state facility construction blueprint sets from Maine BGS.

Source: https://www.maine.gov/dafs/bgs/business-opportunities
Skips legal ads, bid tabulations, RFQs, non-blueprint docs, duplicates, and holdouts.
"""
from __future__ import annotations

import argparse
import re
import sys
import threading
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

from bs4 import BeautifulSoup

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

INDEX_URL = 'https://www.maine.gov/dafs/bgs/business-opportunities'
DEST_DIR = BLUEPRINTS_DIR


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--max-downloads', type=int, default=200)
    parser.add_argument('--workers', type=int, default=4)
    args = parser.parse_args()

    DEST_DIR.mkdir(parents=True, exist_ok=True)
    known_shas, _holdout_shas, holdout_tokens = load_known_hashes_and_holdout()
    manifest = SourceManifest(
        'maine_bgs',
        {'source': INDEX_URL, 'description': 'Maine Bureau of General Services construction bid drawings'},
    )

    html = urllib.request.urlopen(
        urllib.request.Request(INDEX_URL, headers=DEFAULT_HEADERS), timeout=30, context=CTX
    ).read().decode('utf-8', 'ignore')
    soup = BeautifulSoup(html, 'html.parser')

    candidates = []
    seen_urls: set[str] = set()
    for a in soup.find_all('a', href=True):
        href = a['href'].strip()
        if '.pdf' not in href.lower():
            continue
        url = urllib.parse.urljoin(INDEX_URL, href)
        if url in seen_urls or manifest.has_url(url):
            continue
        seen_urls.add(url)
        link_text = a.get_text(' ', strip=True)
        orig_name = urllib.parse.unquote(url.rsplit('/', 1)[-1].split('?')[0])
        combined = f'{orig_name} {link_text}'
        if NON_DRAWING_EXCLUDE_RE.search(combined):
            continue
        if re.search(r'\b(19\d{2}|200[0-5])\b', combined):
            continue
        if not DRAWING_INCLUDE_RE.search(combined):
            continue
        candidates.append((url, orig_name, link_text))

    print(f'Maine BGS: {len(candidates)} candidate drawing PDFs found.', flush=True)
    sha_lock = threading.Lock()
    downloaded = 0

    def worker(item: tuple[str, str, str]) -> dict:
        url, orig_name, link_text = item
        fn = safe_filename('maine_bgs', orig_name, max_len=95)
        return download_and_validate_pdf(
            url,
            DEST_DIR,
            fn,
            known_sha256s=known_shas,
            holdout_tokens=holdout_tokens,
            sha_lock=sha_lock,
            extra_meta={'orig_name': orig_name, 'link_text': link_text[:150]},
            min_bytes=200_000,
            min_pages=2,
            min_large_ratio=0.25,
        )

    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = [pool.submit(worker, c) for c in candidates[: args.max_downloads]]
        for i, fut in enumerate(as_completed(futures), 1):
            res = fut.result()
            manifest.record(res, flush=(i % 10 == 0))
            if res.get('status') == 'ok':
                downloaded += 1
                print(
                    f"  [OK {downloaded}] {res['file']} ({res['bytes']/1024/1024:.1f} MB, {res.get('pages')} pp)",
                    flush=True,
                )
            elif res.get('status') in ('rejected_not_blueprint', 'error'):
                print(f"  [{res.get('status')}] {res.get('file')}: {res.get('reason') or res.get('detail')}", flush=True)

    manifest.save()
    print(f'Maine BGS done: {downloaded} new PDFs saved.', flush=True)


if __name__ == '__main__':
    main()
