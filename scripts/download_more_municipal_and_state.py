#!/usr/bin/env python3
"""Download modern blueprints from newly discovered CivicPlus, WordPress, State Facility & Federal portals."""
from __future__ import annotations

import argparse
import json
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
from download_civicplus_bids import FACILITY_BID_RE, SKIP_BID_RE, collect_bid_ids

EXTRA_CIVICPLUS = [
    ('oak_ridge_tn', 'https://www.oakridgetn.gov', 'City of Oak Ridge TN'),
    ('new_castle_county_de', 'https://www.nccde.org', 'New Castle County DE'),
    ('port_arthur_tx', 'https://www.portarthurtx.gov', 'City of Port Arthur TX'),
    ('rockford_il', 'https://www.rockfordil.gov', 'City of Rockford IL'),
    ('boxborough_ma', 'https://www.boxborough-ma.gov', 'Town of Boxborough MA'),
    ('groveland_fl', 'https://www.groveland-fl.gov', 'City of Groveland FL'),
    ('aiken_county_sc', 'https://sc-aikencounty.civicplus.com', 'Aiken County SC'),
    ('muscatine_ia', 'https://www.muscatineiowa.gov', 'City of Muscatine IA'),
    ('grants_pass_or', 'https://www.grantspassoregon.gov', 'City of Grants Pass OR'),
    ('st_charles_county_mo', 'https://www.sccmo.org', 'St. Charles County MO'),
]

EXTRA_WP_SITES = [
    ('providence_ri', 'https://www.providenceri.gov', 'City of Providence RI'),
    ('mentor_oh', 'https://www.cityofmentor.com', 'City of Mentor OH'),
    ('yarrow_point_wa', 'https://yarrowpointwa.gov', 'Town of Yarrow Point WA'),
    ('poulsbo_wa', 'https://cityofpoulsbo.com', 'City of Poulsbo WA'),
    ('los_banos_ca', 'https://losbanos.org', 'City of Los Banos CA'),
    ('macon_bibb_ga', 'https://www.maconbibb.us', 'Macon-Bibb County GA'),
]

STATE_FACILITIES = [
    ('montana_fwp', 'Montana FWP Design & Construction', ['https://fwp.mt.gov/aboutfwp/design-construction'], [
        'https://fwp.mt.gov/binaries/docs/aboutFWP/design-and-construction/elkhornFoundationRepairsDrawings.pdf',
        'https://fwp.mt.gov/binaries/docs/aboutFWP/design-and-construction/freezoutLakeWaterControlPlans.pdf',
        'https://fwp.mt.gov/binaries/docs/aboutFWP/design-and-construction/eurekaReservoirFASDrawings.pdf',
    ]),
    ('missouri_oa_fmdc', 'Missouri OA FMDC Electronic Plans', ['https://oa.mo.gov/facilities/bid-opportunities/bid-listing-electronic-plans'], []),
    ('hawaii_dags_tg', 'Hawaii DAGS Technical Guides', ['https://ags.hawaii.gov/publicworks/'], [
        'https://ags.hawaii.gov/publicworks/wp-content/uploads/sites/9/2016/09/TG-08580.pdf',
    ]),
    ('alaska_dot_std', 'Alaska DOT&PF Standard Drawings', [
        'https://dot.alaska.gov/stwddes/dcsprecon/stdplans.shtml',
        'https://dot.alaska.gov/stwddes/dcsprecon/stddrawings.shtml',
    ], []),
]

FEDERAL_GUIDES = [
    ('fema_p320_safe_room_2025.pdf', 'https://www.fema.gov/sites/default/files/documents/fema_rsl_fema-p-320-taking-shelter-from-the-storm_042025.pdf', True),
    ('fema_p361_safe_rooms.pdf', 'https://www.fema.gov/sites/default/files/documents/fema_safe-rooms-for-tornadoes-and-hurricanes_p-361.pdf', True),
    ('usfs_trail_plans_2025.pdf', 'https://www.fs.usda.gov/sites/default/files/2025-07/Trail-Plans.pdf', True),
    ('usfs_trail_bridge_plans_2025.pdf', 'https://www.fs.usda.gov/sites/default/files/2025-07/Trail-Bridge-Plans.pdf', True),
    ('usace_cdc_0_5_med_drawings_2024.pdf', 'https://mrsi.erdc.dren.mil/content/cos/swf/hnc/cdc/CDC-0-5_STANDARD%20DESIGN%20_MED_DRAWINGS_SEP%202024.pdf', True),
    ('usace_fire_station_drawings_2021.pdf', 'https://mrsi.erdc.dren.mil/content/cos/swf/hnc/fs/Standard_Drawings_Set_One_Company_HQ_Fire_Station_Sep_2021.pdf', True),
    ('usace_fire_station_design_2021.pdf', 'https://mrsi.erdc.dren.mil/content/cos/swf/hnc/fs/Standard_Design_Fire_Station__Mar2021_.pdf', True),
    ('usace_temf_standard_design_2026.pdf', 'https://mrsi.erdc.dren.mil/content/cos/sas/temf/TEMF%20Standard%20Design%20Rev%206.0%20dated%2020260522.pdf', True),
    ('usace_bde_bnhq_standard_2025.pdf', 'https://mrsi.erdc.dren.mil/content/cos/sas/bn-bde-hq/BDE-BNHQ%20Army%20Standard%2020250321.pdf', True),
]


def fetch_html(url: str, timeout: int = 20) -> tuple[str, str]:
    req = urllib.request.Request(url, headers=DEFAULT_HEADERS)
    with urllib.request.urlopen(req, timeout=timeout, context=CTX) as r:
        return r.geturl(), r.read().decode('utf-8', 'ignore')


def scrape_civicplus_site(slug_name: str, base_url: str, label: str, max_dl: int, workers: int) -> int:
    dest_dir = BLUEPRINTS_DIR
    dest_dir.mkdir(parents=True, exist_ok=True)
    known_shas, _, holdout_tokens = load_known_hashes_and_holdout()
    manifest = SourceManifest(f'civicplus_{slug_name}', {'source': base_url, 'municipality': label})

    bids = collect_bid_ids(base_url)
    facility_bids = [
        (bid_id, title)
        for bid_id, title in sorted(bids.items(), key=lambda kv: int(kv[0]), reverse=True)
        if FACILITY_BID_RE.search(title) and not SKIP_BID_RE.search(title) and not matches_holdout(title, holdout_tokens)
    ]
    print(f'[CivicPlus {label}] total bids={len(bids)}, facility-filtered={len(facility_bids)}', flush=True)

    docs = []
    seen_urls: set[str] = set()
    for bid_id, bid_title in facility_bids[:80]:
        try:
            _, html = fetch_html(f'{base_url.rstrip("/")}/Bids.aspx?bidID={bid_id}', timeout=15)
        except Exception:
            continue
        soup = BeautifulSoup(html, 'html.parser')
        for a in soup.find_all('a', href=re.compile(r'(/DocumentCenter/View/\d+|/ImageRepository/Document\?documentID=\d+|\.pdf)', re.I)):
            url = urllib.parse.urljoin(base_url, a['href'].strip())
            if url in seen_urls or manifest.has_url(url):
                continue
            text = a.get_text(' ', strip=True)
            if NON_DRAWING_EXCLUDE_RE.search(text):
                continue
            if not (DRAWING_INCLUDE_RE.search(text) or re.search(r'(bid\s*doc|construction|plan|draw|addend|exhib|attach)', text, re.I)):
                continue
            seen_urls.add(url)
            docs.append((bid_id, bid_title, text, url))
        time.sleep(0.1)

    sha_lock = threading.Lock()
    ok = 0
    stop = threading.Event()

    def worker(item: tuple[str, str, str, str]) -> dict:
        if stop.is_set():
            return {'status': 'skipped_limit'}
        bid_id, bid_title, text, url = item
        doc_id = re.sub(r'[^0-9A-Za-z]+', '', url.split('/')[-1].split('=')[-1])
        fn = safe_filename(f'{slug_name}_bid{bid_id}', f'{bid_title}_{text}', doc_id, max_len=80)
        return download_and_validate_pdf(
            url, dest_dir, fn, known_sha256s=known_shas, holdout_tokens=holdout_tokens, sha_lock=sha_lock,
            extra_meta={'bid_id': bid_id, 'bid_title': bid_title[:160], 'link_text': text[:160]},
            min_bytes=200_000, min_pages=2, min_large_ratio=0.25,
        )

    with ThreadPoolExecutor(max_workers=workers) as pool:
        for i, fut in enumerate(as_completed([pool.submit(worker, d) for d in docs]), 1):
            res = fut.result()
            if res.get('status') == 'skipped_limit':
                continue
            manifest.record(res, flush=(i % 10 == 0))
            if res.get('status') == 'ok':
                ok += 1
                print(f"  [{slug_name} OK {ok}] {res['file']} ({res['bytes']/1024/1024:.1f} MB, {res.get('pages')} pp)", flush=True)
                if ok >= max_dl:
                    stop.set()
    manifest.save()
    return ok


def scrape_wp_site(slug_name: str, base_url: str, label: str, max_dl: int, workers: int) -> int:
    dest_dir = BLUEPRINTS_DIR
    dest_dir.mkdir(parents=True, exist_ok=True)
    known_shas, _, holdout_tokens = load_known_hashes_and_holdout()
    manifest = SourceManifest(f'wp_{slug_name}', {'source': base_url, 'organization': label})

    found: dict[str, dict] = {}
    for term in ['drawings', 'plans', 'dwg', 'bid', 'fire station', 'electrical', 'adu']:
        for page in range(1, 5):
            api = f'{base_url.rstrip("/")}/wp-json/wp/v2/media?mime_type=application/pdf&search={urllib.parse.quote(term)}&per_page=100&page={page}'
            try:
                req = urllib.request.Request(api, headers=DEFAULT_HEADERS)
                with urllib.request.urlopen(req, timeout=15, context=CTX) as r:
                    total_pages = int(r.headers.get('X-WP-TotalPages') or 1)
                    items = json.loads(r.read())
            except Exception:
                break
            if not isinstance(items, list) or not items:
                break
            for it in items:
                url = str(it.get('source_url') or '')
                date_str = str(it.get('date') or '')[:10]
                if not url or not url.lower().endswith('.pdf') or (date_str and date_str < '2006-01-01'):
                    continue
                orig = urllib.parse.unquote(url.rsplit('/', 1)[-1].split('?')[0])
                title = str((it.get('title') or {}).get('rendered') or '')
                comb = f'{orig} {title}'
                if NON_DRAWING_EXCLUDE_RE.search(comb) and not DRAWING_INCLUDE_RE.search(orig):
                    continue
                if DRAWING_INCLUDE_RE.search(comb):
                    found[url] = {'url': url, 'orig_name': orig, 'title': title, 'date': date_str}
            if page >= total_pages:
                break

    todo = [c for c in found.values() if not manifest.has_url(c['url'])]
    print(f'[WP {label}] {len(found)} candidates, {len(todo)} pending', flush=True)
    sha_lock = threading.Lock()
    ok = 0
    stop = threading.Event()

    def worker(c: dict) -> dict:
        if stop.is_set():
            return {'status': 'skipped_limit'}
        fn = safe_filename(slug_name, c['orig_name'], max_len=95)
        return download_and_validate_pdf(
            c['url'], dest_dir, fn, known_sha256s=known_shas, holdout_tokens=holdout_tokens, sha_lock=sha_lock,
            extra_meta={'title': c['title'][:160], 'date': c['date'], 'orig_name': c['orig_name']},
            min_bytes=200_000, min_pages=3, min_large_ratio=0.25,
        )

    with ThreadPoolExecutor(max_workers=workers) as pool:
        for i, fut in enumerate(as_completed([pool.submit(worker, c) for c in todo]), 1):
            res = fut.result()
            if res.get('status') == 'skipped_limit':
                continue
            manifest.record(res, flush=(i % 10 == 0))
            if res.get('status') == 'ok':
                ok += 1
                print(f"  [{slug_name} OK {ok}] {res['file']} ({res['bytes']/1024/1024:.1f} MB, {res.get('pages')} pp)", flush=True)
                if ok >= max_dl:
                    stop.set()
    manifest.save()
    return ok


def scrape_state_and_federal(workers: int) -> int:
    total = 0
    known_shas, _, holdout_tokens = load_known_hashes_and_holdout()
    sha_lock = threading.Lock()

    for slug_name, label, seeds, direct in STATE_FACILITIES:
        dest_dir = BLUEPRINTS_DIR
        dest_dir.mkdir(parents=True, exist_ok=True)
        manifest = SourceManifest(f'state_{slug_name}', {'organization': label, 'seeds': seeds})
        urls: dict[str, str] = {u: urllib.parse.unquote(u.rsplit('/', 1)[-1].split('?')[0]) for u in direct}
        for s in seeds:
            try:
                final_url, html = fetch_html(s)
                soup = BeautifulSoup(html, 'html.parser')
                for a in soup.find_all('a', href=True):
                    if '.pdf' not in a['href'].lower():
                        continue
                    full = urllib.parse.urljoin(final_url, a['href'].strip())
                    orig = urllib.parse.unquote(full.rsplit('/', 1)[-1].split('?')[0])
                    comb = f"{orig} {a.get_text(' ', strip=True)}"
                    if NON_DRAWING_EXCLUDE_RE.search(comb) and not DRAWING_INCLUDE_RE.search(orig):
                        continue
                    if DRAWING_INCLUDE_RE.search(comb) or 'standard' in comb.lower() or 'tg-' in orig.lower():
                        urls.setdefault(full, orig)
            except Exception as e:  # noqa: BLE001
                print(f'[{slug_name}] fetch err: {e}', flush=True)

        todo = [(u, o) for u, o in urls.items() if not manifest.has_url(u)]
        print(f'[State {label}] {len(urls)} PDFs, {len(todo)} pending', flush=True)
        for u, o in todo[:120]:
            fn = safe_filename(slug_name, o, max_len=95)
            res = download_and_validate_pdf(
                u, dest_dir, fn, known_sha256s=known_shas, holdout_tokens=holdout_tokens, sha_lock=sha_lock,
                extra_meta={'orig_name': o}, min_bytes=150_000, min_pages=2, min_large_ratio=0.25,
                allow_letter_plans=('hawaii' in slug_name),
            )
            manifest.record(res, flush=True)
            if res.get('status') == 'ok':
                total += 1
                print(f"  [{slug_name} OK] {res['file']} ({res['bytes']/1024/1024:.1f} MB, {res.get('pages')} pp)", flush=True)
        manifest.save()

    # Federal standard designs are handled by download_federal_standards.py (with proper URL encoding)
    return total


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--max-per-site', type=int, default=35)
    parser.add_argument('--workers', type=int, default=3)
    args = parser.parse_args()

    total = 0
    for s in EXTRA_CIVICPLUS:
        total += scrape_civicplus_site(*s, args.max_per_site, args.workers)
    for s in EXTRA_WP_SITES:
        total += scrape_wp_site(*s, args.max_per_site, args.workers)
    total += scrape_state_and_federal(args.workers)
    print(f'More municipal/state total new PDFs: {total}', flush=True)


if __name__ == '__main__':
    main()
