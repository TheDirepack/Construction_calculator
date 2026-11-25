#!/usr/bin/env python3
"""Download modern USACE / Federal standard building design PDFs (2006-2026)."""
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
    SourceManifest,
    download_and_validate_pdf,
    load_known_hashes_and_holdout,
    safe_filename,
)

DEST_DIR = BLUEPRINTS_DIR
MRSI_ROOT = 'https://mrsi.erdc.dren.mil/cos/'
SEED_PAGES = [
    'https://mrsi.erdc.dren.mil/cos/army-standards/',
    'https://mrsi.erdc.dren.mil/cos/',
]
OLD_YEAR_RE = re.compile(r'\b(19\d{2}|200[0-5])\b')


def fetch_html(url: str) -> str:
    req = urllib.request.Request(url, headers=DEFAULT_HEADERS)
    with urllib.request.urlopen(req, timeout=25, context=CTX) as r:
        return r.read().decode('utf-8', 'ignore')


def encode_url(url: str) -> str:
    parts = urllib.parse.urlsplit(url)
    path = urllib.parse.quote(urllib.parse.unquote(parts.path), safe='/')
    query = urllib.parse.quote(urllib.parse.unquote(parts.query), safe='=&')
    return urllib.parse.urlunsplit((parts.scheme, parts.netloc, path, query, ''))


def crawl_mrsi(max_pages: int = 40) -> list[tuple[str, str]]:
    visited: set[str] = set()
    queue = list(SEED_PAGES)
    pdfs: dict[str, str] = {}

    while queue and len(visited) < max_pages:
        url = queue.pop(0)
        if url in visited:
            continue
        visited.add(url)
        try:
            html = fetch_html(url)
        except Exception:
            continue
        soup = BeautifulSoup(html, 'html.parser')
        for a in soup.find_all('a', href=True):
            full = encode_url(urllib.parse.urljoin(url, a['href'].strip()).split('#')[0])
            low = full.lower()
            if '.pdf' in low:
                pdfs.setdefault(full, a.get_text(' ', strip=True))
            elif full.startswith(MRSI_ROOT) and full not in visited and '?' not in full and not re.search(r'\.(dwg|dgn|zip|rvt|xlsx?|docx?)$', low):
                queue.append(full)
    return sorted(pdfs.items())


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--max-downloads', type=int, default=120)
    parser.add_argument('--workers', type=int, default=3)
    args = parser.parse_args()

    DEST_DIR.mkdir(parents=True, exist_ok=True)
    known_shas, _holdout_shas, holdout_tokens = load_known_hashes_and_holdout()
    manifest = SourceManifest(
        'federal_standards',
        {'source': 'https://mrsi.erdc.dren.mil/cos/army-standards/', 'description': 'USACE / Army Centers of Standardization building standard designs (2006+)'},
    )

    found = crawl_mrsi()
    candidates = []
    for url, text in found:
        if manifest.has_url(url):
            continue
        orig = urllib.parse.unquote(url.rsplit('/', 1)[-1].split('?')[0])
        if OLD_YEAR_RE.search(orig) and not re.search(r'20(?:0[6-9]|1\d|2\d)', orig):
            continue
        if re.search(r'(waiver|memo|policy|checklist|charter|agenda)', orig, re.I):
            continue
        candidates.append((url, orig, text))

    print(f'USACE MRSI: {len(found)} PDFs discovered, {len(candidates)} modern standard-design candidates.', flush=True)
    sha_lock = threading.Lock()
    downloaded = 0

    def worker(item: tuple[str, str, str]) -> dict:
        url, orig, text = item
        fn = safe_filename('usace', orig, max_len=95)
        return download_and_validate_pdf(
            url,
            DEST_DIR,
            fn,
            known_sha256s=known_shas,
            holdout_tokens=holdout_tokens,
            sha_lock=sha_lock,
            extra_meta={'orig_name': orig, 'link_text': text[:150]},
            min_bytes=200_000,
            min_pages=4,
            min_large_pages=2,
            min_large_ratio=0.35,
            allow_letter_plans=False,
            reject_vintage=True,
        )

    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = [pool.submit(worker, c) for c in candidates[: args.max_downloads]]
        for i, fut in enumerate(as_completed(futures), 1):
            res = fut.result()
            manifest.record(res, flush=(i % 10 == 0))
            if res.get('status') == 'ok':
                downloaded += 1
                print(
                    f"  [OK {downloaded}] {res['file']} ({res['bytes']/1024/1024:.1f} MB, {res.get('pages')} pp, {res.get('median_size_in')})",
                    flush=True,
                )

    manifest.save()
    print(f'Federal standards done: {downloaded} new PDFs saved.', flush=True)


if __name__ == '__main__':
    main()
