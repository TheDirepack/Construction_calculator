#!/usr/bin/env python3
"""Download modern municipal building & facility blueprint PDFs from CivicPlus Bids.aspx portals.

Crawls `Bids.aspx?CatID=showStatus&txtSort=Category&showAllBids=on&Status=open|closed`,
filters bid titles for facility/building/MEP projects, inspects `Bids.aspx?bidID=<id>`,
and validates `/DocumentCenter/View/<id>` PDF attachments.
"""
from __future__ import annotations

import argparse
import re
import sys
import threading
import time
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
    matches_holdout,
    safe_filename,
)

DEFAULT_SITES = [
    ('urbandale_ia', 'https://www.urbandale.org', 'City of Urbandale IA'),
    ('missoula_mt', 'https://www.ci.missoula.mt.us', 'City of Missoula MT'),
    ('coralville_ia', 'https://www.coralville.org', 'City of Coralville IA'),
    ('fitchburg_wi', 'https://www.fitchburgwi.gov', 'City of Fitchburg WI'),
]

FACILITY_BID_RE = re.compile(
    r'(building|facilit|fire\s*station|police|library|community\s*center|rec(?:reation)?|'
    r'city\s*hall|public\s*works|maintenance|garage|shelter|restroom|pool|aquatic|'
    r'pavilion|park\s*improv|water\s*treatment|wastewater|pump\s*station|lift\s*station|'
    r'hvac|boiler|chiller|electrical|lighting|generator|solar|roof|remodel|renovat|'
    r'expansion|addition|interior|hangar|terminal|civic|senior\s*center|museum|depot)',
    re.I,
)
SKIP_BID_RE = re.compile(
    r'(software|erp|website|audit|insurance|janitorial|mowing|lawn|snow\s*removal|'
    r'trash|refuse|vehicle|truck|police\s*car|uniform|fuel\s*card|consulting\s*services|'
    r'banking|catering|printing|crack\s*seal|microsurfacing|slurry\s*seal|tree\s*trim)',
    re.I,
)


def fetch_html(url: str, timeout: int = 20) -> str:
    req = urllib.request.Request(url, headers=DEFAULT_HEADERS)
    with urllib.request.urlopen(req, timeout=timeout, context=CTX) as r:
        return r.read().decode('utf-8', 'ignore')


def collect_bid_ids(base_url: str) -> dict[str, str]:
    bids: dict[str, str] = {}
    for qs in [
        'Bids.aspx?CatID=showStatus&txtSort=Category&showAllBids=on&Status=open',
        'Bids.aspx?CatID=showStatus&txtSort=Category&showAllBids=on&Status=closed',
        'Bids.aspx?showAllBids=on',
    ]:
        try:
            html = fetch_html(f'{base_url.rstrip("/")}/{qs}')
        except Exception:
            continue
        soup = BeautifulSoup(html, 'html.parser')
        for a in soup.find_all('a', href=re.compile(r'bidID=\d+', re.I)):
            m = re.search(r'bidID=(\d+)', a['href'], re.I)
            if not m:
                continue
            bid_id = m.group(1)
            title = a.get_text(' ', strip=True)
            if bid_id not in bids or len(title) > len(bids[bid_id]):
                bids[bid_id] = title
    return bids


def scrape_city(slug_name: str, base_url: str, label: str, max_downloads: int, workers: int, delay: float) -> int:
    dest_dir = BLUEPRINTS_DIR
    dest_dir.mkdir(parents=True, exist_ok=True)
    known_shas, _holdout_shas, holdout_tokens = load_known_hashes_and_holdout()
    manifest = SourceManifest(f'civicplus_{slug_name}', {'source': base_url, 'municipality': label})

    bids = collect_bid_ids(base_url)
    facility_bids = [
        (bid_id, title)
        for bid_id, title in sorted(bids.items(), key=lambda kv: int(kv[0]), reverse=True)
        if FACILITY_BID_RE.search(title) and not SKIP_BID_RE.search(title) and not matches_holdout(title, holdout_tokens)
    ]
    print(f'[{label}] total bids={len(bids)}, facility-filtered={len(facility_bids)}', flush=True)

    doc_candidates = []
    seen_urls: set[str] = set()
    for bid_id, bid_title in facility_bids:
        detail_url = f'{base_url.rstrip("/")}/Bids.aspx?bidID={bid_id}'
        try:
            html = fetch_html(detail_url, timeout=15)
        except Exception:
            continue
        soup = BeautifulSoup(html, 'html.parser')
        for a in soup.find_all('a', href=re.compile(r'(/DocumentCenter/View/\d+|\.pdf)', re.I)):
            href = a['href'].strip()
            url = urllib.parse.urljoin(base_url, href)
            if url in seen_urls or manifest.has_url(url):
                continue
            link_text = a.get_text(' ', strip=True)
            if NON_DRAWING_EXCLUDE_RE.search(link_text):
                continue
            if not (DRAWING_INCLUDE_RE.search(link_text) or re.search(r'(bid\s*doc|construction|plan|draw|addend)', link_text, re.I)):
                continue
            seen_urls.add(url)
            doc_candidates.append((bid_id, bid_title, link_text, url))
        if delay > 0:
            time.sleep(delay)
        if len(doc_candidates) >= max_downloads * 4:
            break

    print(f'[{label}] {len(doc_candidates)} document links queued for PDF/blueprint check', flush=True)
    sha_lock = threading.Lock()
    downloaded = 0
    stop_flag = threading.Event()

    def worker(item: tuple[str, str, str, str]) -> dict:
        if stop_flag.is_set():
            return {'status': 'skipped_limit'}
        bid_id, bid_title, link_text, url = item
        doc_id = url.rsplit('/', 1)[-1].split('?')[0]
        fn = safe_filename(f'{slug_name}_bid{bid_id}', f'{bid_title}_{link_text}', doc_id, max_len=80)
        return download_and_validate_pdf(
            url,
            dest_dir,
            fn,
            known_sha256s=known_shas,
            holdout_tokens=holdout_tokens,
            sha_lock=sha_lock,
            extra_meta={'bid_id': bid_id, 'bid_title': bid_title[:160], 'link_text': link_text[:160]},
            min_bytes=250_000,
            min_pages=3,
            min_large_ratio=0.25,
        )

    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(worker, c) for c in doc_candidates]
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
    parser.add_argument('--max-per-city', type=int, default=40)
    parser.add_argument('--workers', type=int, default=3)
    parser.add_argument('--delay', type=float, default=0.15)
    parser.add_argument('--only', action='append', help='Filter by city slug (e.g. urbandale_ia)')
    args = parser.parse_args()

    sites = DEFAULT_SITES
    if args.only:
        only_set = set(args.only)
        sites = [s for s in sites if s[0] in only_set]

    total = 0
    for slug_name, base_url, label in sites:
        total += scrape_city(slug_name, base_url, label, args.max_per_city, args.workers, args.delay)
    print(f'CivicPlus total new PDFs: {total}', flush=True)


if __name__ == '__main__':
    main()
