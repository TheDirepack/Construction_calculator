#!/usr/bin/env python3
"""Download modern (2006-2026) construction & architectural blueprint PDFs from Internet Archive."""
from __future__ import annotations

import argparse
import json
import re
import sys
import threading
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from blueprint_dl_utils import (
    BLUEPRINTS_DIR,
    CTX,
    DEFAULT_HEADERS,
    NON_DRAWING_EXCLUDE_RE,
    SourceManifest,
    download_and_validate_pdf,
    load_known_hashes_and_holdout,
    safe_filename,
)

DEST_DIR = BLUEPRINTS_DIR

IA_QUERIES = [
    ('identifier:Arlington_MA_ShowDocument* AND title:("Drawings" OR "Plan Set" OR "Architectural" OR "Floor Plan") AND date:[2010-01-01 TO 2026-12-31]', 150),
    ('("bid set" OR "construction drawings" OR "architectural drawings" OR "as-built") AND mediatype:texts AND date:[2006-01-01 TO 2026-12-31]', 80),
]


def ia_search(query: str, rows: int) -> list[dict]:
    url = (
        'https://archive.org/advancedsearch.php?q=' + urllib.parse.quote(query) +
        f'&fl[]=identifier&fl[]=title&fl[]=year&rows={rows}&output=json'
    )
    req = urllib.request.Request(url, headers=DEFAULT_HEADERS)
    with urllib.request.urlopen(req, timeout=30, context=CTX) as r:
        return json.loads(r.read()).get('response', {}).get('docs', [])


def resolve_pdf(doc: dict) -> dict | None:
    ident = doc['identifier']
    title = str(doc.get('title') or '')
    year = str(doc.get('year') or '')
    if NON_DRAWING_EXCLUDE_RE.search(title):
        return None
    if re.search(r'(minutes|agenda|housing trust|action plan|complete streets|reading room|arxiv|forensic)', title, re.I):
        return None
    try:
        req = urllib.request.Request(f'https://archive.org/metadata/{ident}', headers=DEFAULT_HEADERS)
        with urllib.request.urlopen(req, timeout=20, context=CTX) as r:
            meta = json.loads(r.read())
    except Exception:
        return None
    files = meta.get('files', [])
    pdfs = [
        f for f in files
        if str(f.get('name', '')).lower().endswith('.pdf')
        and 300_000 <= int(f.get('size') or 0) <= 200_000_000
    ]
    if not pdfs:
        return None
    # Prefer original PDF over derived text PDF if multiple exist
    pdfs.sort(key=lambda f: (0 if f.get('source') == 'original' else 1, -int(f.get('size') or 0)))
    chosen = pdfs[0]
    url = f"https://archive.org/download/{ident}/{urllib.parse.quote(chosen['name'])}"
    return {'url': url, 'ident': ident, 'title': title, 'year': year, 'orig_name': chosen['name']}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--max-downloads', type=int, default=120)
    parser.add_argument('--workers', type=int, default=4)
    args = parser.parse_args()

    DEST_DIR.mkdir(parents=True, exist_ok=True)
    known_shas, _, holdout_tokens = load_known_hashes_and_holdout()
    manifest = SourceManifest(
        'ia_modern_plans',
        {'source': 'https://archive.org/', 'description': 'Modern (2006-2026) architectural and construction plan sets from Internet Archive'},
    )

    seen_ids: set[str] = set()
    docs: list[dict] = []
    for q, rows in IA_QUERIES:
        try:
            for d in ia_search(q, rows):
                ident = d.get('identifier')
                if ident and ident not in seen_ids:
                    seen_ids.add(ident)
                    docs.append(d)
        except Exception as e:  # noqa: BLE001
            print(f'IA search err: {e}', flush=True)

    print(f'IA modern search: {len(docs)} candidate items.', flush=True)
    candidates = []
    with ThreadPoolExecutor(max_workers=6) as pool:
        for res in pool.map(resolve_pdf, docs):
            if res and not manifest.has_url(res['url']):
                candidates.append(res)

    print(f'IA modern PDF candidates resolved: {len(candidates)}', flush=True)
    sha_lock = threading.Lock()
    downloaded = 0
    stop = threading.Event()

    def worker(c: dict) -> dict:
        if stop.is_set():
            return {'status': 'skipped_limit'}
        fn = safe_filename(c['ident'], c['title'] or c['orig_name'], max_len=85)
        return download_and_validate_pdf(
            c['url'],
            DEST_DIR,
            fn,
            known_sha256s=known_shas,
            holdout_tokens=holdout_tokens,
            sha_lock=sha_lock,
            extra_meta={'identifier': c['ident'], 'title': c['title'][:160], 'year': c['year']},
            min_bytes=300_000,
            min_pages=3,
            min_large_ratio=0.25,
            reject_vintage=True,
        )

    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        for i, fut in enumerate(as_completed([pool.submit(worker, c) for c in candidates]), 1):
            res = fut.result()
            if res.get('status') == 'skipped_limit':
                continue
            manifest.record(res, flush=(i % 10 == 0))
            if res.get('status') == 'ok':
                downloaded += 1
                print(
                    f"  [IA OK {downloaded}] {res['file']} ({res['bytes']/1024/1024:.1f} MB, {res.get('pages')} pp)",
                    flush=True,
                )
                if downloaded >= args.max_downloads:
                    stop.set()

    manifest.save()
    print(f'IA modern done: {downloaded} new PDFs saved.', flush=True)


if __name__ == '__main__':
    main()
