#!/usr/bin/env python3
"""Download modern (2020-2026) non-TxDOT State DOT compiled standard plan books & electrical/signal sets."""
from __future__ import annotations

import argparse
import re
import sys
import urllib.parse
import urllib.request
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

DOT_ROOT = BLUEPRINTS_DIR


def fetch_html(url: str) -> tuple[str, str]:
    req = urllib.request.Request(url, headers=DEFAULT_HEADERS)
    with urllib.request.urlopen(req, timeout=25, context=CTX) as r:
        return r.geturl(), r.read().decode('utf-8', 'ignore')


def collect_fdot() -> list[tuple[str, str, str]]:
    """Collect FDOT compiled standard plans & lighting/signal standard packages."""
    picks: dict[str, tuple[str, str, str]] = {}
    for seed in [
        'https://www.fdot.gov/design/standardplans/current/default.shtm',
        'https://www.fdot.gov/design/standardplans/2025/25.shtm',
        'https://www.fdot.gov/design/standardplans/2026/26.shtm',
    ]:
        try:
            final_url, html = fetch_html(seed)
        except Exception:
            continue
        soup = BeautifulSoup(html, 'html.parser')
        for a in soup.find_all('a', href=True):
            href = a['href'].strip()
            if '.pdf' not in href.lower():
                continue
            url = urllib.parse.urljoin(final_url, href)
            orig = urllib.parse.unquote(url.rsplit('/', 1)[-1].split('?')[0])
            text = a.get_text(' ', strip=True)
            if re.search(r'(bstandardplans|complete|entire|all.?plans|lighting|signal|electrical|630|715)', f'{orig} {text}', re.I):
                picks.setdefault(url, (url, orig, text))
    return list(picks.values())


def collect_odot() -> list[tuple[str, str, str]]:
    """Collect Oregon DOT compiled Standard Drawings & electrical/signal update PDFs."""
    picks: dict[str, tuple[str, str, str]] = {}
    url0 = 'https://www.oregon.gov/odot/Engineering/Pages/Standards.aspx'
    try:
        final_url, html = fetch_html(url0)
    except Exception:
        return []
    soup = BeautifulSoup(html, 'html.parser')
    for a in soup.find_all('a', href=True):
        href = a['href'].strip()
        if '.pdf' not in href.lower():
            continue
        url = urllib.parse.urljoin(final_url, href)
        orig = urllib.parse.unquote(url.rsplit('/', 1)[-1].split('?')[0])
        text = a.get_text(' ', strip=True)
        if re.search(r'(202[0-6]|std|standard)', f'{orig} {text}', re.I) and not re.search(r'index', orig, re.I):
            picks.setdefault(url, (url, orig, text))
    return list(picks.values())


def collect_nysdot() -> list[tuple[str, str, str]]:
    """Collect NYSDOT 2020-2026 Series 670 (Lighting) & Series 680 (Traffic Signals/Electrical) standard sheets."""
    picks: dict[str, tuple[str, str, str]] = {}
    url0 = 'https://www.dot.ny.gov/main/business-center/engineering/cadd-info/drawings/standard-sheets-us-repository'
    try:
        final_url, html = fetch_html(url0)
    except Exception:
        return []
    soup = BeautifulSoup(html, 'html.parser')
    for a in soup.find_all('a', href=True):
        href = a['href'].strip()
        if '.pdf' not in href.lower():
            continue
        url = urllib.parse.urljoin(final_url, href)
        orig = urllib.parse.unquote(url.rsplit('/', 1)[-1].split('?')[0])
        text = a.get_text(' ', strip=True)
        if re.search(r'202[0-6]', orig) and re.search(r'(670|680|660|light|signal|elect)', orig, re.I):
            picks.setdefault(url, (url, orig, text))
    return list(picks.values())


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--max-per-state', type=int, default=15)
    args = parser.parse_args()

    known_shas, _holdout_shas, holdout_tokens = load_known_hashes_and_holdout()

    collectors = [
        ('fdot', 'https://www.fdot.gov/design/standardplans/', collect_fdot),
        ('odot', 'https://www.oregon.gov/odot/Engineering/Pages/Standards.aspx', collect_odot),
        ('nysdot', 'https://www.dot.ny.gov/main/business-center/engineering/cadd-info/drawings/standard-sheets-us-repository', collect_nysdot),
    ]

    total_ok = 0
    for state, src_url, fn_col in collectors:
        dest_dir = DOT_ROOT
        dest_dir.mkdir(parents=True, exist_ok=True)
        manifest = SourceManifest(f'dot_{state}', {'source': src_url, 'state_dot': state})
        items = fn_col()[: args.max_per_state]
        print(f'[{state}] {len(items)} modern DOT candidate PDFs', flush=True)
        ok_cnt = 0
        for url, orig, text in items:
            if manifest.has_url(url):
                continue
            fn = safe_filename(state, orig, max_len=95)
            res = download_and_validate_pdf(
                url,
                dest_dir,
                fn,
                known_sha256s=known_shas,
                holdout_tokens=holdout_tokens,
                extra_meta={'orig_name': orig, 'link_text': text[:150]},
                min_bytes=150_000,
                min_pages=1,
                min_large_pages=1,
                min_large_ratio=0.25,
                allow_letter_plans=(state == 'nysdot'),
            )
            manifest.record(res, flush=True)
            if res.get('status') == 'ok':
                ok_cnt += 1
                total_ok += 1
                print(
                    f"  [{state} OK] {res['file']} ({res['bytes']/1024/1024:.1f} MB, {res.get('pages')} pp)",
                    flush=True,
                )
        manifest.save()
        print(f'[{state}] done: {ok_cnt} new PDFs saved.', flush=True)
    print(f'State DOT modern total new PDFs: {total_ok}', flush=True)


if __name__ == '__main__':
    main()
