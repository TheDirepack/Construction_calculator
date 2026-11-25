#!/usr/bin/env python3
"""Download modern (2006-2026) building blueprint PDFs from US universities, colleges, and school districts."""
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

EDU_ROOT = BLUEPRINTS_DIR

# Each entry: (org_slug, org_label, kind ['html', 'wp', 'cwu_dir'], seed_urls, extra_direct_pdfs)
PORTALS = [
    # --- Public Universities ---
    (
        'uc_davis_health',
        'UC Davis Health (CA)',
        'html',
        [
            'https://health.ucdavis.edu/facilities/work-with-us/contractors/out-to-bid',
            'https://health.ucdavis.edu/facilities/work-with-us/contractors/projects-out-to-bid.html',
            'https://health.ucdavis.edu/facilities/work-with-us/advertisements.html',
        ],
        [
            'https://health.ucdavis.edu/media-resources/facilities/documents/bids/9557730-construction-documents.pdf',
            'https://health.ucdavis.edu/media-resources/facilities/documents/bids/9557230-construction-documents.pdf',
            'https://health.ucdavis.edu/media-resources/facilities/documents/bids/research-buildings-network-refresh-g000.pdf',
            'https://www.ucdmc.ucdavis.edu/hrd/pdfs/bids/9557580/9557580_Plans.pdf',
        ],
    ),
    (
        'tamu_corpus_christi',
        'Texas A&M University-Corpus Christi (TX)',
        'html',
        ['https://www.tamucc.edu/finance-and-administration/financial-services/purchasing/bid-opportunities.php'],
        ['https://www.tamucc.edu/finance-and-administration/financial-services/purchasing/assets/csp6-0001/csp6-0001-drawings.pdf'],
    ),
    (
        'ul_lafayette',
        'University of Louisiana at Lafayette (LA)',
        'html',
        ['https://hull.louisiana.edu/for-vendors/vendor-opportunities/public-works-construction-and-renovation-solicitations-title-38'],
        [
            'https://hull.louisiana.edu/sites/default/files/NIRC%20BLDG%2034%20MECHANICAL%20DRAWINGS.pdf',
            'https://hull.louisiana.edu/sites/default/files/UL%20NIRC%20C06%20Novel%20Macaque%20Breeding%20Runs%20Plans.pdf',
            'https://police.louisiana.edu/sites/police/files/24040%20M101.pdf',
        ],
    ),
    (
        'missouri_western',
        'Missouri Western State University (MO)',
        'wp',
        [
            'https://www.missouriwestern.edu/purchasing',
            'https://www.missouriwestern.edu',
        ],
        [
            'https://www.missouriwestern.edu/wp-content/uploads/2019/08/Scanlon-Hall-4th-Floor-and-Roof-2.pdf',
            'https://www.missouriwestern.edu/purchasing/wp-content/uploads/2026/02/Scanlon-Hall-4th-Floor-and-Roof.pdf',
            'https://www.missouriwestern.edu/purchasing/wp-content/uploads/2026/03/Baker-Roof-Drawings.pdf',
        ],
    ),
    (
        'uri_capital',
        'University of Rhode Island (RI)',
        'wp',
        [
            'https://web.uri.edu/capitalprojects',
            'https://web.uri.edu/capitalprojects/',
            'https://web.uri.edu/strategic-procurement/legacy-bid-information/',
        ],
        [
            'https://web.uri.edu/capitalprojects/wp-content/uploads/sites/765/2018/07/100672PlansC-Bliss-drawings.pdf',
            'https://web.uri.edu/capitalprojects/wp-content/uploads/sites/765/2018/07/100643PLANSC.pdf',
        ],
    ),
    (
        'unlv',
        'University of Nevada, Las Vegas (NV)',
        'html',
        [
            'https://www.unlv.edu/planningconstruction',
            'https://www.unlv.edu/purchasing',
        ],
        ['https://www.unlv.edu/sites/default/files/page_files/27/Gensler-UNLVStudentUnion3rdFloorRemodel-Drawings.pdf'],
    ),
    (
        'umbc',
        'University of Maryland, Baltimore County (MD)',
        'wp',
        ['https://procurement.umbc.edu'],
        ['https://procurement.umbc.edu/wp-content/uploads/sites/148/2024/02/VF101_23-126.DWG_.pdf'],
    ),
    (
        'cwu',
        'Central Washington University (WA)',
        'cwu_dir',
        [
            'https://www.cwu.edu/~files/docs/campus_map/03_CADWorking/building_documents/',
            'https://www.cwu.edu/about/offices/contracts/purchasing/current-bid-opportunities.php',
        ],
        [],
    ),
    # --- Community & Technical Colleges ---
    (
        'wvhepc',
        'WV Higher Education / CTC System (WV)',
        'wp',
        ['https://www.wvhepc.edu', 'https://www.wvhepc.edu/resources/purchasing-and-finance/'],
        [
            'https://www.wvhepc.edu/wp-content/uploads/2026/05/ATC-RENOVATION-ADDENDUM-NO.-6-OMNI-ASSOCIATES-ARCHITECTS-5.13.26.pdf',
            'https://www.wvhepc.edu/wp-content/uploads/2026/05/PIERPONT-CTC-CAPERTON-CENTER-ADDENDUM-NO.-2.pdf',
        ],
    ),
    (
        'garrett_college',
        'Garrett College (MD)',
        'html',
        [
            'https://www.garrettcollege.edu/purchasing.php',
            'https://www.garrettcollege.edu/cepac.php',
            'https://www.garrettcollege.edu/pac-project-info.php',
        ],
        ['https://www.garrettcollege.edu/downloads/stem-bid-drawings.pdf'],
    ),
    (
        'state_tech_mo',
        'State Technical College of Missouri (MO)',
        'wp',
        ['https://statetechmo.edu', 'https://statetechmo.edu/rfps-bidding-projects/'],
        [
            'https://statetechmo.edu/wp-content/uploads/2019/11/Mechanical-Electrical-Plumbing-Drawings-for-.pdf',
            'https://www.statetechmo.edu/wp-content/uploads/2019/11/MEP.pdf',
            'https://www.statetechmo.edu/wp-content/uploads/2019/11/MEP1.pdf',
        ],
    ),
    (
        'east_central_college',
        'East Central College (MO)',
        'wp',
        [
            'https://www.eastcentral.edu',
            'https://www.eastcentral.edu/purchasing',
            'https://www.eastcentral.edu/purchasing/bid-opportunities/',
        ],
        ['https://www.eastcentral.edu/wp-content/uploads/2025/07/Addendum-No.-01-Hansen-Hall-Theater-Repairs.pdf'],
    ),
    (
        'red_rocks_cc',
        'Red Rocks Community College (CO)',
        'html',
        ['https://www.rrcc.edu/wu/construction-projects'],
        ['https://www.rrcc.edu/sites/default/files/2026-01/M0.1.pdf'],
    ),
    (
        'joliet_jc',
        'Joliet Junior College (IL)',
        'html',
        [
            'https://www.jjc.edu/community/vendors/current-solicitations',
            'https://jjc.edu/community/vendors/awarded-bids',
        ],
        [],
    ),
    # --- K-12 School Districts ---
    (
        'monroe_ps_mi',
        'Monroe Public Schools (MI)',
        'html',
        [
            'https://bids.monroe.k12.mi.us',
            'https://monroe.k12.mi.us/Departments/Operations/Building-and-Site-Sinking-Fund-Projects/Mechanical-Updates-and-Related-Work-at-Hollywood-Elementary-School/index.html',
        ],
        [
            'https://monroe.k12.mi.us/Downloads/26110%20Base%20Bid%20A%20Drawings%20ADD1.pdf',
            'https://monroe.k12.mi.us/Downloads/26110%20Base%20Bid%20B%20Drawings%20ADD1.pdf',
        ],
    ),
    (
        'sanger_usd_ca',
        'Sanger Unified School District (CA)',
        'html',
        ['https://www.sanger.k12.ca.us/page/purchasing'],
        [],
    ),
    (
        'shawnee_mission_sd_ks',
        'Shawnee Mission School District (KS)',
        'html',
        ['https://www.smsd.org/about/departments/purchasing/bids-bid-summaries'],
        [
            'https://resources.finalsite.net/images/v1530646093/smsdorg/w5uzcgqjbc8jq7l54aeq/17-003Drawings-SMSouth.pdf',
            'https://resources.finalsite.net/images/v1530646093/smsdorg/z59fubqjbc8jq7l54aeq/17-003Drawings-SMNorthwest.pdf',
            'https://resources.finalsite.net/images/v1530647306/smsdorg/o0f7wycucgzfshk96qdv/17-001Drawings.pdf',
        ],
    ),
    (
        'idea_public_schools_tx',
        'IDEA Public Schools (TX)',
        'wp',
        ['https://ideapublicschools.org', 'https://ideapublicschools.org/our-story/finance-budget/pcs/bids-rfps/'],
        [
            'https://ideapublicschools.org/wp-content/uploads/2024/05/IDEA-Public-Schools-Middle-RGV-Mechanical-Upgrades-CSP-24-MRMU-0424-Addendum-1.pdf',
            'https://ideapublicschools.org/wp-content/uploads/2025/09/I-D-E-A-P-U-B-L-I-C-S-C-H-O-O-L-S-EXTERIOR-SIDEWALK-CANOPY-REPAIRS-DONNA-TEXAS.pdf',
        ],
    ),
    (
        'seattle_ps_wa',
        'Seattle Public Schools (WA)',
        'wp',
        ['https://www.seattleschools.org', 'https://www.seattleschools.org/departments/capital-projects-and-planning/'],
        ['https://www.seattleschools.org/wp-content/uploads/2023/09/MILITARY-ROAD-S.-E1.1.pdf'],
    ),
]

WP_TERMS = ['drawings', 'drawing', 'dwg', 'plans', 'plan', 'bid', 'mep', 'electrical', 'architectural', 'mechanical', 'addendum']


def fetch_html(url: str, timeout: int = 20) -> tuple[str, str]:
    req = urllib.request.Request(url, headers=DEFAULT_HEADERS)
    with urllib.request.urlopen(req, timeout=timeout, context=CTX) as r:
        return r.geturl(), r.read().decode('utf-8', 'ignore')


def extract_pdfs_from_html(page_url: str, max_depth: int = 1) -> dict[str, dict]:
    found: dict[str, dict] = {}
    visited: set[str] = set()
    queue: list[tuple[str, int]] = [(page_url, 0)]
    base_host = urllib.parse.urlparse(page_url).netloc

    while queue and len(visited) < 40:
        cur, depth = queue.pop(0)
        if cur in visited:
            continue
        visited.add(cur)
        try:
            final_url, html = fetch_html(cur)
        except Exception:
            continue
        soup = BeautifulSoup(html, 'html.parser')
        for a in soup.find_all('a', href=True):
            href = a['href'].strip()
            full = urllib.parse.urljoin(final_url, href).split('#')[0]
            low = full.lower()
            text = a.get_text(' ', strip=True)
            if '.pdf' in low:
                orig = urllib.parse.unquote(full.rsplit('/', 1)[-1].split('?')[0])
                combined = f'{orig} {text}'
                if NON_DRAWING_EXCLUDE_RE.search(combined) and not DRAWING_INCLUDE_RE.search(orig):
                    continue
                if DRAWING_INCLUDE_RE.search(combined) or re.search(r'\b(cd|bid|const|addend|project|mep|hvac|elec|arch|struct|floor|sheet|a[-_.]?\d|e[-_.]?\d|m[-_.]?\d)\b', combined, re.I):
                    found[full] = {'url': full, 'orig_name': orig, 'link_text': text[:150]}
            elif depth < max_depth:
                parsed = urllib.parse.urlparse(full)
                if parsed.netloc == base_host and full not in visited:
                    if re.search(r'(bid|solicitation|project|facilit|draw|purchas|capital|sinking|7facman|building_documents)', low):
                        queue.append((full, depth + 1))
    return found


def extract_cwu_dir(dir_url: str) -> dict[str, dict]:
    found: dict[str, dict] = {}
    visited: set[str] = set()
    queue = [dir_url]
    while queue and len(visited) < 120:
        cur = queue.pop(0)
        if cur in visited:
            continue
        visited.add(cur)
        try:
            final_url, html = fetch_html(cur, timeout=15)
        except Exception:
            continue
        soup = BeautifulSoup(html, 'html.parser')
        for a in soup.find_all('a', href=True):
            href = a['href'].strip()
            if href.startswith('?') or href in ('../', '/'):
                continue
            full = urllib.parse.urljoin(final_url, href)
            if not full.startswith(dir_url):
                continue
            if full.lower().endswith('.pdf'):
                orig = urllib.parse.unquote(full.rsplit('/', 1)[-1])
                bldg = urllib.parse.unquote(full.replace(dir_url, '').split('/')[0])
                found[full] = {'url': full, 'orig_name': f'{bldg}_{orig}', 'link_text': orig}
            elif full.endswith('/') and full not in visited:
                queue.append(full)
    return found


def extract_wp_pdfs(base_url: str, max_pages: int = 4) -> dict[str, dict]:
    found: dict[str, dict] = {}
    root = base_url.rstrip('/')
    if not re.match(r'^https?://[^/]+(?:/[^/]+)?$', root):
        return found
    for term in WP_TERMS:
        for page in range(1, max_pages + 1):
            api = f'{root}/wp-json/wp/v2/media?mime_type=application/pdf&search={urllib.parse.quote(term)}&per_page=100&page={page}'
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
                if not url or '.pdf' not in url.lower():
                    continue
                if date_str and date_str < '2006-01-01':
                    continue
                orig = urllib.parse.unquote(url.rsplit('/', 1)[-1].split('?')[0])
                title = str((it.get('title') or {}).get('rendered') or '')
                combined = f'{orig} {title}'
                if NON_DRAWING_EXCLUDE_RE.search(combined) and not DRAWING_INCLUDE_RE.search(orig):
                    continue
                if DRAWING_INCLUDE_RE.search(combined) or re.search(r'(addend|mep|hvac|elec|arch|roof|reno|dwg|plan|draw)', combined, re.I):
                    found[url] = {'url': url, 'orig_name': orig, 'link_text': title[:150], 'date': date_str}
            if page >= total_pages:
                break
            time.sleep(0.05)
    return found


def scrape_org(entry: tuple, max_per_org: int, workers: int) -> int:
    org_slug, label, kind, seeds, direct_urls = entry
    dest_dir = EDU_ROOT
    dest_dir.mkdir(parents=True, exist_ok=True)
    known_shas, _holdout_shas, holdout_tokens = load_known_hashes_and_holdout()
    manifest = SourceManifest(f'edu_{org_slug}', {'organization': label, 'seeds': seeds})

    candidates: dict[str, dict] = {}
    for u in direct_urls:
        orig = urllib.parse.unquote(u.rsplit('/', 1)[-1].split('?')[0])
        candidates[u] = {'url': u, 'orig_name': orig, 'link_text': 'verified_direct'}

    for s in seeds:
        if kind == 'cwu_dir' and 'building_documents' in s:
            candidates.update(extract_cwu_dir(s))
        elif kind == 'wp' and '/wp-' not in s and not s.endswith('.php') and 'bid' not in s and 'purchasing-and-finance' not in s and 'capital-projects' not in s:
            candidates.update(extract_wp_pdfs(s))
            candidates.update(extract_pdfs_from_html(s, max_depth=1))
        else:
            candidates.update(extract_pdfs_from_html(s, max_depth=1))

    todo = [
        c for u, c in candidates.items()
        if not manifest.has_url(u) and not matches_holdout(c['orig_name'] + ' ' + c.get('link_text', ''), holdout_tokens)
    ]
    print(f'[{label}] {len(candidates)} discovered, {len(todo)} pending', flush=True)

    sha_lock = threading.Lock()
    downloaded = 0
    stop_flag = threading.Event()
    allow_letter = (org_slug == 'cwu')

    def worker(c: dict) -> dict:
        if stop_flag.is_set():
            return {'status': 'skipped_limit'}
        fn = safe_filename(org_slug, c['orig_name'], max_len=95)
        return download_and_validate_pdf(
            c['url'],
            dest_dir,
            fn,
            known_sha256s=known_shas,
            holdout_tokens=holdout_tokens,
            sha_lock=sha_lock,
            extra_meta={'orig_name': c['orig_name'], 'link_text': c.get('link_text', '')},
            min_bytes=150_000,
            min_pages=1 if allow_letter else 2,
            min_large_pages=1,
            min_large_ratio=0.25,
            allow_letter_plans=allow_letter,
        )

    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(worker, c) for c in todo[: max_per_org * 3]]
        for i, fut in enumerate(as_completed(futures), 1):
            res = fut.result()
            if res.get('status') == 'skipped_limit':
                continue
            manifest.record(res, flush=(i % 10 == 0))
            if res.get('status') == 'ok':
                downloaded += 1
                print(f"  [{org_slug} OK {downloaded}] {res['file']} ({res['bytes']/1024/1024:.1f} MB, {res.get('pages')} pp)", flush=True)
                if downloaded >= max_per_org:
                    stop_flag.set()

    manifest.save()
    print(f'[{label}] done: {downloaded} new blueprint PDFs saved.', flush=True)
    return downloaded


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--max-per-org', type=int, default=30)
    parser.add_argument('--workers', type=int, default=3)
    parser.add_argument('--only', action='append', help='Restrict to specific org slug(s)')
    args = parser.parse_args()

    entries = PORTALS
    if args.only:
        only_set = set(args.only)
        entries = [e for e in entries if e[0] in only_set]

    total = 0
    for e in entries:
        total += scrape_org(e, args.max_per_org, args.workers)
    print(f'Edu & Schools total new PDFs: {total}', flush=True)


if __name__ == '__main__':
    main()
