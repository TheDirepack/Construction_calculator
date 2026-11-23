#!/usr/bin/env python3
"""Discover/download public construction-plan PDFs from selected government/college archives.

Python >=3.9; standard library only. Public HTML pages and linked PDFs only.
No login, captcha solving, hidden API probing, or anti-bot bypass.
The public sites may rate-limit or block scripts; this is not a bypass tool.

Examples:
  python public_blueprint_plans.py --list-sources
  python public_blueprint_plans.py --sources midlands purchase --dry-run
  python public_blueprint_plans.py --sources midlands purchase newberry --download
  python public_blueprint_plans.py --download --include-revisions --max-files 100
  python public_blueprint_plans.py --download --ca-bundle /path/to/ca.pem

Files are placed under OUTPUT/SOURCE/; discovered metadata is stored in catalog.csv.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import html
from html.parser import HTMLParser
import json
import os
from pathlib import Path
import re
import shutil
import ssl
import sys
import time
from dataclasses import dataclass
from http.client import HTTPException
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, quote, unquote, urljoin, urlsplit, urlunsplit
from urllib.request import HTTPSHandler, Request, build_opener

USER_AGENT = 'PublicBlueprintPlanCollector/1.0 (public-file discovery; polite 1 req/second)'
PDF_PREFIX = b'%PDF-'
POSITIVE = re.compile(r'(?i)\b(drawings?|plans?|blueprints?|sheets?)\b|\bbid[ -]?set\b|\bpermit[ -]?set\b')
NEGATIVE = re.compile(
    r'(?i)\b(project manuals?|specifications?|technical specs?|bid forms?|bid results?|bid tabs?|'
    r'invitations?|solicitations?|requests? for|proposal|rfp\b|rfq\b|rfis?\b|'
    r'addenda?|pre[- ]?bid|meeting|sign[- ]?in|notices?|'
    r'geotech|soil|submittals?|substitutions?|plan holders?|site visit|'
    r'payroll|insurance|hazmat|environmental report|evaluation|photographs?|'
    r'price|pricing|boilerplate|minutes|inspection report|appendix|'
    r'cut[ -]?sheets?|product data|product literature|manufacturer(?:s)?|catalog(?:ue)?s?|'
    r'submittal packages?|shop drawings?|material samples?|renderings?|'
    r'certif|attendance|bidder list|scope of work|schedule of values)\b'
)
REVISION = re.compile(r'(?i)\b(addend(?:um|a)|revisions?|revised|rev[-_. ]?[a-z0-9]+)\b')
# Try to avoid collecting standalone manuals etc whose titles include incidental references to drawings.


def normalize_url(url: str) -> str:
    """Percent-encode unsafe characters while preserving existing URL escapes.

    HTML links commonly contain literal spaces (e.g. 'Permit Set - Plan.pdf').
    urllib.request rejects such URLs as InvalidURL unless the path is encoded.
    Preserve reserved URL delimiters, especially query parameters and %XX escapes.
    """
    parts = urlsplit(url)
    path = quote(parts.path, safe="/:%@!$&'()*+,;=-._~")
    query = quote(parts.query, safe="/?:%@!$&'()*+,;=-._~")
    # Fragments are browser-local and should not be sent to the PDF endpoint.
    return urlunsplit((parts.scheme, parts.netloc, path, query, ''))


@dataclass(frozen=True)
class Source:
    label: str
    seeds: tuple[str, ...]
    allowed_hosts: tuple[str, ...]
    follow: str = ''
    max_pages: int = 1
    note: str = ''

SOURCES = {
    'midlands': Source(
        'Midlands Technical College',
        ('https://www.midlandstech.edu/procurement/construction-projects',),
        ('midlandstech.edu',), note='Archive with project drawings links'),
    'purchase': Source(
        'SUNY Purchase',
        ('https://www.purchase.edu/offices/purchasing/procurement-opportunities/',),
        ('purchase.edu',),
        r'^/offices/purchasing/procurement-opportunities/20\d{2}/?$', 12,
        'Current year plus annual archives (2018–2025 shown)'),
    'klamath': Source(
        'Klamath Community College',
        ('https://www.klamathcc.edu/en-US/about/request-for-proposals/',),
        ('klamathcc.edu',), note='Discipline-separated project drawing PDFs'),
    'newberry': Source(
        'Newberry County',
        ('https://www.newberrycounty.gov/purchasing/solicitations',),
        ('newberrycounty.gov',), r'^/solicitations/[^/]+/?$', 100,
        'Each solicitation page has a documents section'),
    'portwentworth': Source(
        'Port Wentworth',
        ('https://portwentworthga.gov/dept-admin/',),
        ('portwentworthga.gov',), note='Municipal construction bid archive'),
    'maine': Source(
        'Maine Bureau of General Services',
        ('https://www.maine.gov/dafs/bgs/business-opportunities',),
        ('maine.gov',), note='Centralized state procurement table; may return HTTP 403'),
    'albany': Source(
        'City of Albany, Oregon',
        ('https://albanyoregon.gov/finance/purchasing/bids',
         'https://albanyoregon.gov/finance/purchasing/bids?view_bid=767',
         'https://albanyoregon.gov/finance/purchasing/bids?view_bid=731',
         'https://albanyoregon.gov/finance/purchasing/bids?view_bid=749',
         'https://albanyoregon.gov/finance/purchasing/bids?view_bid=741'),
        ('albanyoregon.gov',), r'^/finance/purchasing/bids/?$', 80,
        'Municipal utility infrastructure plans; anti-bot verification possible'),
    'austin': Source(
        'City of Austin, Texas',
        ('https://financeonline.austintexas.gov/afo/account_services/solicitation/solicitation_details.cfm?sid=143005',
         'https://financeonline.austintexas.gov/afo/account_services/solicitation/solicitation_details.cfm?sid=141943',
         'https://financeonline.austintexas.gov/afo/account_services/solicitation/solicitation_details.cfm?sid=143702'),
        ('austintexas.gov',), note='Public solicitation attachment pages; starter IDs only'),
    'durham': Source(
        'City of Durham, North Carolina',
        ('https://www.durhamnc.gov/bids.aspx?bidID=2296',),
        ('durhamnc.gov',), note='Public construction bid posting; starter project only'),
}

class LinkExtractor(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.links: list[tuple[str, str]] = []
        self.current_href: str | None = None
        self.current_words: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]):
        if tag == 'a':
            self.current_href = dict(attrs).get('href')
            self.current_words = []

    def handle_data(self, data: str):
        if self.current_href is not None:
            self.current_words.append(data)

    def handle_endtag(self, tag: str):
        if tag == 'a' and self.current_href:
            title = ' '.join(' '.join(self.current_words).split())
            self.links.append((self.current_href, html.unescape(title)))
            self.current_href = None
            self.current_words = []


def in_hosts(url: str, source: Source) -> bool:
    host = (urlsplit(url).hostname or '').lower()
    return any(host == h or host.endswith('.' + h) for h in source.allowed_hosts)


def pdf_link(url: str, title: str) -> bool:
    path = unquote(urlsplit(url).path).lower()
    if path.endswith('.pdf'):
        return True
    # University CMS endpoint: /live/files/4775-su-040623-2-bid-set-drawingspart1pdf
    if re.search(r'/live/files/\d+', path) and title.lower().endswith('.pdf'):
        return True
    # Municipal DocumentCenter / dynamic download endpoints, if explicitly labeled PDF.
    if re.search(r'/documentcenter/view/\d+|/download', path) and ('pdf' in title.lower() or 'drawing' in title.lower()):
        return True
    return False


def is_plan(url: str, title: str, include_revisions: bool = False) -> bool:
    if not pdf_link(url, title):
        return False
    filename = unquote(urlsplit(url).path.rsplit('/', 1)[-1]).replace('_', ' ').replace('-', ' ')
    combined = f'{title} {filename}'
    if NEGATIVE.search(title) or NEGATIVE.search(filename):
        return False
    if not include_revisions and REVISION.search(combined):
        return False
    if not POSITIVE.search(combined):
        return False
    # 'Plan holders', 'site plan approval' and other obvious false positives.
    return True


def maybe_follow(url: str, source: Source) -> bool:
    if not source.follow or not in_hosts(url, source):
        return False
    p = urlsplit(url)
    if not re.match(source.follow, p.path, re.I):
        return False
    if source.label.startswith('City of Albany'):
        return bool(parse_qs(p.query).get('view_bid'))
    return True


def safe_filename(url: str, title: str) -> str:
    raw = unquote(urlsplit(url).path.rstrip('/').rsplit('/', 1)[-1])
    if not raw or (not raw.lower().endswith('.pdf') and '/live/files/' in url):
        raw = title or raw
    raw = raw.replace('\\', '-').replace('/', '-')
    raw = re.sub(r'[^\w.() +\-]+', '_', raw, flags=re.UNICODE).strip(' ._')
    if not raw.lower().endswith('.pdf'):
        raw += '.pdf'
    raw = raw[:145].rstrip(' .')
    if not raw.lower().endswith('.pdf'):
        raw += '.pdf'
    url_id = hashlib.sha256(url.encode()).hexdigest()[:10]
    return f'{Path(raw).stem[:130]}_{url_id}.pdf'


class Client:
    def __init__(self, ca_bundle: str | None, insecure: bool, timeout: int):
        if insecure:
            ctx = ssl._create_unverified_context()
        else:
            ctx = ssl.create_default_context(cafile=ca_bundle)
        self.opener = build_opener(HTTPSHandler(context=ctx))
        self.timeout = timeout

    def open(self, url: str, headers: dict | None = None):
        h = {'User-Agent': USER_AGENT, 'Accept-Encoding': 'identity'}
        if headers:
            h.update(headers)
        return self.opener.open(Request(normalize_url(url), headers=h), timeout=self.timeout)


def discover(source_key: str, client: Client, delay: float = 1.0, revisions: bool = False) -> tuple[list[dict], list[str]]:
    source = SOURCES[source_key]
    pending = list(source.seeds)
    visited: set[str] = set()
    documents: dict[str, dict] = {}
    errors = []
    while pending and len(visited) < source.max_pages:
        url = pending.pop(0)
        if url in visited:
            continue
        visited.add(url)
        try:
            with client.open(url) as resp:
                data = resp.read(8 * 1024 * 1024)  # HTML listing size bound
                content_type = resp.headers.get('Content-Type', '')
                if 'pdf' in content_type.lower() or data.startswith(PDF_PREFIX):
                    errors.append(f'{url}: expected HTML listing, found PDF')
                    continue
                if 'text/html' not in content_type.lower() and not data.lstrip().startswith(b'<'):
                    errors.append(f'{url}: not an HTML listing ({content_type})')
                    continue
                actual_url = resp.url
            parser = LinkExtractor()
            parser.feed(data.decode('utf-8', errors='replace'))
            if len(parser.links) < 2:
                errors.append(f'{url}: unusually few links (site challenge or changed layout?)')
            for href, title in parser.links:
                target = normalize_url(urljoin(actual_url, href))
                if urlsplit(target).scheme not in ('https', 'http'):
                    continue
                if not in_hosts(target, source):
                    continue
                if is_plan(target, title, include_revisions=revisions):
                    documents[target] = {
                        'source': source_key,
                        'name': title,
                        'url': target,
                        'listing': actual_url,
                    }
                elif maybe_follow(target, source) and target not in visited and target not in pending:
                    pending.append(target)
        except (HTTPError, URLError, HTTPException, OSError, TimeoutError, ValueError) as e:
            errors.append(f'{url}: {e}')
        if delay:
            time.sleep(delay)
    if pending:
        errors.append(f'{source_key}: stopped at page cap {source.max_pages}; {len(pending)} pages remain')
    return sorted(documents.values(), key=lambda d: d['url']), errors


def looks_pdf(path: Path) -> bool:
    """Quick integrity check; protects against HTML errors and truncated transfers."""
    try:
        size = path.stat().st_size
        if size < 256:
            return False
        with path.open('rb') as f:
            if not f.read(1024).lstrip(b'\xef\xbb\xbf').startswith(PDF_PREFIX):
                return False
            f.seek(max(0, size - 65536))
            return b'%%EOF' in f.read()
    except OSError:
        return False


def download(item: dict, output: Path, client: Client, delay: float, retries: int = 3) -> str:
    source = SOURCES[item['source']]
    url = normalize_url(item['url'])
    if not in_hosts(url, source):
        return 'BLOCKED: URL left allowed source host'
    directory = output / item['source']
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / safe_filename(url, item['name'])
    if looks_pdf(path):
        return 'EXISTS'
    partial = path.with_suffix('.pdf.part')
    for attempt in range(retries):
        existing_size = partial.stat().st_size if partial.exists() else 0
        headers = {'Range': f'bytes={existing_size}-'} if existing_size else {}
        try:
            with client.open(url, headers) as response:
                status = getattr(response, 'status', 200)
                if existing_size and status == 206:
                    content_range = response.headers.get('Content-Range', '')
                    if not content_range.startswith(f'bytes {existing_size}-'):
                        raise IOError('server returned unexpected Content-Range')
                    mode = 'ab'
                else:
                    # 200 means no range support; safely start fresh.
                    mode = 'wb'
                before = partial.stat().st_size if mode == 'ab' and partial.exists() else 0
                expected = response.headers.get('Content-Length')
                with partial.open(mode) as f:
                    shutil.copyfileobj(response, f, length=1024 * 1024)
                received = partial.stat().st_size - before
                if expected and received != int(expected):
                    raise IOError(f'incomplete download ({received} of {expected} bytes)')
            if not looks_pdf(partial):
                try: partial.unlink()
                except FileNotFoundError: pass
                return 'INVALID: response is not a PDF (possibly HTML/login page)'
            os.replace(partial, path)
            return f'DOWNLOADED {path.stat().st_size} bytes'
        except HTTPError as e:
            if e.code == 416 and existing_size and looks_pdf(partial):
                os.replace(partial, path)
                return f'DOWNLOADED {path.stat().st_size} bytes'
            if e.code not in (429, 500, 502, 503, 504):
                return f'HTTP {e.code}'
            msg = f'HTTP {e.code}'
        except (URLError, HTTPException, TimeoutError, OSError, ValueError) as e:
            msg = str(e)
        if attempt < retries - 1:
            time.sleep(min(16, 2 ** attempt + delay))
    return 'FAILED: ' + msg


def write_catalog(rows: list[dict], output: Path):
    output.mkdir(parents=True, exist_ok=True)
    with (output / 'catalog.csv').open('w', newline='', encoding='utf-8') as f:
        w = csv.DictWriter(f, fieldnames=['source', 'name', 'url', 'listing'])
        w.writeheader(); w.writerows(rows)


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--list-sources', action='store_true', help='Show available archive sources')
    p.add_argument('--sources', nargs='+', choices=list(SOURCES), help='Selected sources (default: all)')
    action = p.add_mutually_exclusive_group()
    action.add_argument('--download', action='store_true', help='Scan then download matching PDFs')
    action.add_argument('--dry-run', action='store_true', help='Scan without downloading (default)')
    p.add_argument('--include-revisions', action='store_true', help='Include individually revised drawing PDFs')
    p.add_argument('--output', type=Path, default=Path('Public-Construction-Plans'))
    p.add_argument('--max-files', type=int, default=0, help='Limit number of PDFs to download (0 = unlimited)')
    p.add_argument('--delay', type=float, default=1.0, help='Seconds between requests (default 1)')
    p.add_argument('--timeout', type=int, default=45)
    p.add_argument('--ca-bundle', default=None, help='Trusted PEM certificate bundle')
    p.add_argument('--insecure', action='store_true', help='Disable TLS verification (not recommended)')
    args = p.parse_args(argv)
    if args.list_sources:
        for key, s in SOURCES.items():
            print(f'{key:15s} {s.label:36s} {s.note}')
        return 0
    if args.delay < 0 or args.max_files < 0 or args.timeout <= 0:
        p.error('delay and max-files must be nonnegative; timeout must be positive')
    if args.insecure and args.ca_bundle:
        p.error('choose --ca-bundle or --insecure, not both')
    if args.insecure:
        print('WARNING: TLS certificate verification is DISABLED.', file=sys.stderr)
    client = Client(args.ca_bundle, args.insecure, args.timeout)
    all_rows = []
    for key in args.sources or SOURCES:
        print(f'\n[SCAN] {key}: {SOURCES[key].label}', flush=True)
        rows, errors = discover(key, client, delay=args.delay, revisions=args.include_revisions)
        all_rows.extend(rows)
        print(f'  {len(rows)} candidate plan PDFs, {len(errors)} scan warnings')
        for err in errors[:15]:
            print('  [WARN]', err)
    # URL exact dedup, even if encountered via two listing pages.
    all_rows = list({r['url']: r for r in all_rows}.values())
    all_rows.sort(key=lambda r: (r['source'], r['url']))
    write_catalog(all_rows, args.output)
    print(f'\n[CATALOG] {len(all_rows)} plan PDFs -> {args.output / "catalog.csv"}')
    if not args.download:
        for r in all_rows:
            print(f'  {r["source"]:15} {r["name"][:65]:65} {r["url"]}')
        return 0
    if args.max_files:
        all_rows = all_rows[:args.max_files]
    if not all_rows:
        print('[NOTE] No plan links discovered; check scan warnings / source access.')
        return 1
    status_path = args.output / 'download_results.jsonl'
    with status_path.open('a', encoding='utf-8') as logfile:
        for i, row in enumerate(all_rows, start=1):
            result = download(row, args.output, client, args.delay)
            print(f'[{i}/{len(all_rows)}] {row["source"]} {row["name"][:50]}: {result}', flush=True)
            logfile.write(json.dumps({**row, 'result': result}, ensure_ascii=False) + '\n')
            logfile.flush()
            if args.delay:
                time.sleep(args.delay)
    print('[DONE]', status_path)
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
