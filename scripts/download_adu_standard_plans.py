#!/usr/bin/env python3
"""Download modern municipal pre-approved ADU / permit-ready residential plan sets."""
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

ADU_PAGES = [
    ('sacramento_city', 'https://adu.cityofsacramento.org/Shelf-ready-plans.html'),
    ('sacramento_city', 'https://adu.cityofsacramento.org/Shelf-ready-plans'),
    ('sacramento_county', 'https://development.saccounty.gov/us/en/county-government/departments/community-development/building/accessory-dwelling-units-(adu)/shelf-ready-adu-plans'),
    ('san_diego_county', 'https://www.sandiegocounty.gov/content/sdc/pds/bldg/ADU_plans.html'),
    ('san_diego_county', 'https://www.sandiegocounty.gov/content/sdc/pds/bldg/mdup.html'),
    ('san_diego_county', 'https://www.sandiegocounty.gov/content/sdc/pds/bldg/adu_info.html'),
    ('oakland_ca', 'https://www.oaklandca.gov/services/apply-for-adu-with-pre-approved-plans'),
    ('oakland_ca', 'https://www.oaklandca.gov/services/apply-for-adu-s-with-pre-approved-plans'),
    ('pasadena_ca', 'https://www.cityofpasadena.net/planning/accessory-dwelling-unit-standard-plans-program/'),
    ('santa_cruz_county', 'https://cdi.santacruzcountyca.gov/Home/Building/AccessoryDwellingUnits(ADUs)/Pre-DesignedADUPlans.aspx'),
    ('south_gate_ca', 'https://www.cityofsouthgate.org/Government/Departments/Community-Development/Welcome-to-Building-Safety/ADU-Program'),
    ('la_county', 'https://planning.lacounty.gov/planning-permits/accessory-dwelling-units/county-catalog/'),
    ('bend_or', 'https://www.bendoregon.gov/government/departments/housing/pre-approved-plans-program'),
]

DIRECT_ADU_PDFS = [
    ('sacramento_city', 'ADU_STUDIO.pdf', 'https://adu.cityofsacramento.org/content/dam/cityofsacramento/adu/plan/ADU_STUDIO.pdf'),
    ('sacramento_city', 'ADU_1_BEDROOM.pdf', 'https://adu.cityofsacramento.org/content/dam/cityofsacramento/adu/plan/ADU_1_BEDROOM.pdf'),
    ('sacramento_city', 'ADU_2_BEDROOM.pdf', 'https://adu.cityofsacramento.org/content/dam/cityofsacramento/adu/plan/ADU_2_BEDROOM.pdf'),
    ('san_diego_county', 'plan_a_36x24.pdf', 'https://www.sandiegocounty.gov/content/dam/sdc/pds/bldg/adu_info/plan_a_36x24.pdf'),
    ('san_diego_county', 'plan_b_36x24.pdf', 'https://www.sandiegocounty.gov/content/dam/sdc/pds/bldg/adu_info/plan_b_36x24.pdf'),
    ('san_diego_county', 'plan_c_36x24.pdf', 'https://www.sandiegocounty.gov/content/dam/sdc/pds/bldg/adu_info/plan_c_36x24.pdf'),
    ('san_diego_county', 'plan_d_36x24.pdf', 'https://www.sandiegocounty.gov/content/dam/sdc/pds/bldg/adu_info/plan_d_36x24.pdf'),
    ('san_diego_county', 'plan_e_36x24.pdf', 'https://www.sandiegocounty.gov/content/dam/sdc/pds/bldg/adu_info/plan_e_36x24.pdf'),
    ('san_diego_county', 'plan_f_36x24.pdf', 'https://www.sandiegocounty.gov/content/dam/sdc/pds/bldg/adu_info/plan_f_36x24.pdf'),
    ('la_city_ladbs', 'YOU-ADU-Standard-Plan.pdf', 'https://dbs.lacity.gov/sites/default/files/efs/pdf/publications/adu/you-adu/YOU-ADU-Standard-Plan.pdf'),
    ('pasadena_ca', 'Prototype-A-Studio-Standard-Plan-ADU.pdf', 'https://www.cityofpasadena.net/planning/wp-content/uploads/sites/30/Prototype-A-Studio-Standard-Plan-ADU.pdf'),
    ('pasadena_ca', 'Prototype-D-2-Bed-2-Bath-Standard-Plan-ADU.pdf', 'https://www.cityofpasadena.net/planning/wp-content/uploads/sites/30/Prototype-D-2-Bed-2-Bath-Standard-Plan-ADU.pdf'),
    ('santa_cruz_county', 'Plan_1.pdf', 'https://cdi.santacruzcountyca.gov/Portals/35/CDI/UnifiedPermitCenter/Building/Building%20Permits%20Index/Pre-designed%20ADUs/Plan%201.pdf'),
    ('santa_cruz_county', 'Plan_2.pdf', 'https://cdi.santacruzcountyca.gov/Portals/35/CDI/UnifiedPermitCenter/Building/Building%20Permits%20Index/Pre-designed%20ADUs/Plan%202.pdf'),
    ('santa_cruz_county', 'Plan_3.pdf', 'https://cdi.santacruzcountyca.gov/Portals/35/CDI/UnifiedPermitCenter/Building/Building%20Permits%20Index/Pre-designed%20ADUs/Plan%203.pdf'),
    ('south_gate_ca', 'permit-ready-adu.pdf', 'https://www.cityofsouthgate.org/files/assets/public/v/1/permit-ready-adu.pdf'),
    ('south_gate_ca', 'south-gate-garage-conversion-standard-plan-single-car-addition_1.pdf', 'https://www.cityofsouthgate.org/files/assets/public/v/1/south-gate-garage-conversion-standard-plan-single-car-addition_1.pdf'),
    ('bend_or', 'A1.0-DESCHUTES-BUNGALOW.pdf', 'https://bendoregon.gov/wp-content/uploads/2025/08/A1.0-DESCHUTES-BUNGALOW.pdf'),
]

SKIP_HANDOUT_RE = re.compile(r'(guide|handout|comparison|checklist|application|faq|brochure|presentation|ordinance|fee)', re.I)


def discover_from_page(city_slug: str, page_url: str) -> list[tuple[str, str, str]]:
    found = []
    try:
        html = urllib.request.urlopen(
            urllib.request.Request(page_url, headers=DEFAULT_HEADERS), timeout=20, context=CTX
        ).read().decode('utf-8', 'ignore')
    except Exception as e:  # noqa: BLE001
        print(f'[{city_slug}] {page_url} -> {type(e).__name__}: {str(e)[:80]}', flush=True)
        return found
    soup = BeautifulSoup(html, 'html.parser')
    for a in soup.find_all('a', href=True):
        href = a['href'].strip()
        if '.pdf' not in href.lower():
            continue
        url = urllib.parse.urljoin(page_url, href)
        # Skip 17x11 duplicates when 36x24 already present for San Diego County
        if '17x11' in url.lower():
            continue
        orig = urllib.parse.unquote(url.rsplit('/', 1)[-1].split('?')[0])
        text = a.get_text(' ', strip=True)
        if SKIP_HANDOUT_RE.search(f'{orig} {text}') and not re.search(r'(plan[_ -]?set|full|adu_|bedroom|studio|prototype|plan[_ ]?\d|laurel|willow|redwood|elderberry|magnolia)', f'{orig} {text}', re.I):
            continue
        found.append((city_slug, orig, url))
    return found


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--workers', type=int, default=4)
    args = parser.parse_args()

    DEST_DIR.mkdir(parents=True, exist_ok=True)
    known_shas, _holdout_shas, holdout_tokens = load_known_hashes_and_holdout()
    manifest = SourceManifest(
        'adu_residential_sets',
        {'description': 'Municipal Pre-Approved ADU & Permit-Ready Residential Plan Sets'},
    )

    items_by_url: dict[str, tuple[str, str, str]] = {u: (c, n, u) for c, n, u in DIRECT_ADU_PDFS}
    for city_slug, page_url in ADU_PAGES:
        for c, n, u in discover_from_page(city_slug, page_url):
            items_by_url.setdefault(u, (c, n, u))

    todo = [v for v in items_by_url.values() if not manifest.has_url(v[2])]
    print(f'ADU plans: {len(items_by_url)} total discovered, {len(todo)} pending.', flush=True)

    sha_lock = threading.Lock()
    downloaded = 0

    def worker(item: tuple[str, str, str]) -> dict:
        city_slug, orig, url = item
        fn = safe_filename(city_slug, orig, max_len=95)
        return download_and_validate_pdf(
            url,
            DEST_DIR,
            fn,
            known_sha256s=known_shas,
            holdout_tokens=holdout_tokens,
            sha_lock=sha_lock,
            extra_meta={'city': city_slug, 'orig_name': orig},
            min_bytes=150_000,
            min_pages=3,
            min_large_ratio=0.25,
            allow_letter_plans=False,
        )

    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = [pool.submit(worker, it) for it in todo]
        for i, fut in enumerate(as_completed(futures), 1):
            res = fut.result()
            manifest.record(res, flush=(i % 5 == 0))
            if res.get('status') == 'ok':
                downloaded += 1
                print(f"  [OK {downloaded}] {res['file']} ({res['bytes']/1024/1024:.1f} MB, {res.get('pages')} pp)", flush=True)
            else:
                print(f"  [{res.get('status')}] {res.get('file')}: {res.get('reason') or res.get('detail')}", flush=True)

    manifest.save()
    print(f'ADU plans done: {downloaded} new PDFs saved.', flush=True)


if __name__ == '__main__':
    main()
