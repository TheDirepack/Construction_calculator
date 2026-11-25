#!/usr/bin/env python3
"""Shared deduplication, holdout isolation, PDF quality validation, and manifest helpers."""
from __future__ import annotations

import hashlib
import json
import os
import re
import ssl
import statistics
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

import pymupdf
pymupdf.TOOLS.mupdf_display_errors(False)

from dl_common import slug

BLUEPRINTS_ROOT = Path(
    os.environ.get(
        'BLUEPRINTS_ROOT',
        '/home/max/.t3/projects/construction-calculator/blueprints_and_libraries',
    )
)
BLUEPRINTS_DIR = BLUEPRINTS_ROOT / '1_blueprints'
SCRAPED_ROOT = BLUEPRINTS_DIR
MANIFESTS_DIR = BLUEPRINTS_ROOT / '_download_logs' / 'manifests'

CTX = ssl.create_default_context()
DEFAULT_HEADERS = {
    'User-Agent': (
        'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 '
        '(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
    )
}

# Tuned regexes for modern architectural / MEP / building construction drawing sets
DRAWING_INCLUDE_RE = re.compile(
    r'(draw|dwg|plan(?:s|\b|[_-]?set)|blueprint|arch(?:itectural)?|mep(?:fp)?|'
    r'elec(?:trical)?|mech(?:anical)?|struct(?:ural)?|plumb(?:ing)?|'
    r'bid[_ -]?set|permit[_ -]?(?:set|plans?|drawings?)|ifc|100[_\s%-]*cd|'
    r'construction[_ -]?doc|sheet|rcp|floor[_ -]?plan|standard[_ -]?plan|'
    r'typical[_ -]?design|prototype|adu[_ -]?plan|casita)',
    re.I,
)

NON_DRAWING_EXCLUDE_RE = re.compile(
    r'(?:^|[\b_\s.-])(spec(?:ification)?s?|project[_ -]?manual|wage[_ -]?det|'
    r'davis[_ -]?bacon|sign[_ -]?in|attendee|pre[_ -]?bid|plan[_ -]?holder|'
    r'tabulation|bid[_ -]?tab|abstract|q\s*&\s*a|qa[_ -]|rfi|clarification|'
    r'sf[_ -]?1442|sf[_ -]?1449|sf[_ -]?24|sf[_ -]?30|sf[_ -]?33|solicitation|'
    r'statement[_ -]?of[_ -]?work|sow\b|past[_ -]?perf|subcontract|'
    r'buy[_ -]?american|checklist|agenda|minutes|price[_ -]?sched|'
    r'schedule[_ -]?of[_ -]?values|bid[_ -]?form|bid[_ -]?bond|insurance|'
    r'terms[_ -]?and[_ -]?conditions|legal[_ -]?ad|notice[_ -]?to[_ -]?bidders|'
    r'invitation[_ -]?for[_ -]?bid|ifb[_ -]?notice|rfq[_ -]?info|'
    r'contract[_ -]?template|submittal[_ -]?log|geotech(?:nical)?[_ -]?report|'
    r'asbestos|lead[_ -]?paint[_ -]?report|environmental[_ -]?assessment|'
    r'traffic[_ -]?control[_ -]?narrative|stormwater[_ -]?calc)(?:$|[\b_\s.-])',
    re.I,
)

# Specific solicitation / project identifiers from 1_blueprints/evaluation/manifest.json
# so we never download sister revisions or alternate attachments of locked holdout projects.
STATIC_HOLDOUT_TOKENS = {
    '657-26-602', '3873', '3920', '26050-b', 'anchor-park', 'port-wentworth-city-hall',
    'firestation9', 'kcc_clc', 'kcc-clc', 'kcc_cosmetology', 'kcc-cosmetology',
    'south_creek_pool_house', 'south-creek-pool-house', 'upper-campus-event-center',
    'city-of-springfield-city-complex', 'kirkwood-east-hall', 'chem-lab-renovation',
    'nmnh_baird', 'nmnh-baird', 'whitmire', 'newberry-airport', '2026-13-mccp',
    '2026-11-downtown-amphitheater', '2026-17-tabulation', '26-10_itb_water_treatment',
    'su-081523', 'vcslc_25-18220', '031851-19-1', '061851-19-1', '021859-19-1',
    '041859-19-1', '25-470418060', '151851-19-4', '101851-19-4', '25-18060',
    '211851-19-2', '566180-19-1', '566180-19-3', '151851-19-14', '151851-19-6',
    '241859-19-1', '211851-19-1', '211851-19-3', '19-18200', '381851-19-3',
    '09-18233', '6349-43-001', 'caltrans_2022', 'wsdot_standard_plans_2026',
    'seattle_2026_standard_plans', 'city_hall_expansion_plans', 'existing_city_hall_plans',
}


def load_known_hashes_and_holdout(
    root: Path = BLUEPRINTS_ROOT,
) -> tuple[set[str], set[str], set[str]]:
    """Return (known_sha256s, holdout_sha256s, holdout_tokens)."""
    known: set[str] = set()
    holdout: set[str] = set()
    tokens: set[str] = set(STATIC_HOLDOUT_TOKENS)

    main_m = root / '1_blueprints' / 'manifest.json'
    if main_m.is_file():
        try:
            for item in json.loads(main_m.read_text()).get('files', []):
                if sha := item.get('sha256'):
                    known.add(sha)
        except Exception:
            pass

    eval_m = root / '1_blueprints' / 'evaluation' / 'manifest.json'
    if eval_m.is_file():
        try:
            for item in json.loads(eval_m.read_text()).get('files', []):
                if sha := item.get('sha256'):
                    known.add(sha)
                    holdout.add(sha)
                fn = (item.get('filename') or '').lower()
                stem = fn[:-4] if fn.endswith('.pdf') else fn
                if len(stem) >= 8 and not stem.startswith('document'):
                    tokens.add(stem)
        except Exception:
            pass

    eval_dir = root / '1_blueprints' / 'evaluation'
    if eval_dir.is_dir():
        for p in eval_dir.glob('*.pdf'):
            stem = p.stem.lower()
            if len(stem) >= 8 and not stem.startswith('document'):
                tokens.add(stem)

    if MANIFESTS_DIR.is_dir():
        for sj in MANIFESTS_DIR.glob('*.json'):
            try:
                data = json.loads(sj.read_text())
            except Exception:
                continue
            for item in data.get('items', data.get('files', [])):
                if isinstance(item, dict) and item.get('sha256'):
                    known.add(item['sha256'])

    scraped = root / '1_blueprints' / 'scraped_202611'
    if scraped.is_dir():
        for sj in scraped.rglob('SOURCE.json'):
            try:
                data = json.loads(sj.read_text())
            except Exception:
                continue
            for item in data.get('items', data.get('files', [])):
                if isinstance(item, dict) and item.get('sha256'):
                    known.add(item['sha256'])

    return known, holdout, tokens


def matches_holdout(text: str, holdout_tokens: set[str]) -> bool:
    low = (text or '').lower()
    return any(tok in low for tok in holdout_tokens)


def inspect_pdf_quality(
    path: Path,
    *,
    min_pages: int = 2,
    min_large_pages: int = 1,
    min_large_ratio: float = 0.25,
    allow_letter_plans: bool = False,
    reject_vintage: bool = False,
) -> tuple[bool, dict[str, Any]]:
    """Verify PDF opens cleanly and looks like a real modern blueprint/plan set."""
    try:
        with path.open('rb') as f:
            magic = f.read(5)
        if magic != b'%PDF-':
            return False, {'reason': f'bad_magic:{magic!r}'}

        with pymupdf.open(path) as doc:
            if doc.needs_pass:
                return False, {'reason': 'encrypted'}
            pages = len(doc)
            if pages < min_pages:
                return False, {'reason': f'too_few_pages:{pages}<{min_pages}', 'pages': pages}

            if pages <= 25:
                idxs = list(range(pages))
            else:
                idxs = sorted({int(i * (pages - 1) / 24) for i in range(25)})

            widths_in: list[float] = []
            heights_in: list[float] = []
            large_pages = 0
            for idx in idxs:
                rect = doc[idx].rect
                w_in = float(rect.width) / 72.0
                h_in = float(rect.height) / 72.0
                widths_in.append(w_in)
                heights_in.append(h_in)
                if max(w_in, h_in) >= 16.5:
                    large_pages += 1

            chars = 0
            first_text = ''
            for idx in range(min(3, pages)):
                t = (doc[idx].get_text('text') or '').strip()
                if idx == 0:
                    first_text = t[:2000]
                chars += len(t)

            if reject_vintage:
                meta_text = ' '.join(str(v or '') for v in (doc.metadata or {}).values()) + ' ' + first_text
                yrs = [int(y) for y in re.findall(r'\b(19\d{2}|20\d{2})\b', meta_text)]
                modern_yrs = [y for y in yrs if 2006 <= y <= 2027]
                old_yrs = [y for y in yrs if 1850 <= y <= 2005]
                if old_yrs and not modern_yrs:
                    return False, {'reason': f'vintage_year:{max(old_yrs)}', 'pages': pages}

            sampled = len(idxs)
            ratio = large_pages / sampled if sampled else 0.0
            med_w = round(float(statistics.median(widths_in)), 1)
            med_h = round(float(statistics.median(heights_in)), 1)
            info = {
                'pages': pages,
                'median_size_in': [med_w, med_h],
                'large_sampled': large_pages,
                'sampled': sampled,
                'large_ratio': round(ratio, 2),
                'has_text_layer': chars >= 50,
            }

            if not allow_letter_plans and (large_pages < min_large_pages or ratio < min_large_ratio):
                return False, {**info, 'reason': f'not_large_format:large={large_pages}/{sampled}({med_w}x{med_h}in)'}

            return True, info
    except Exception as e:  # noqa: BLE001
        return False, {'reason': f'pymupdf_error:{str(e)[:100]}'}


def download_and_validate_pdf(
    url: str,
    dest_dir: Path,
    filename: str,
    *,
    known_sha256s: set[str],
    holdout_tokens: set[str],
    sha_lock: threading.Lock | None = None,
    extra_meta: dict[str, Any] | None = None,
    min_bytes: int = 150_000,
    max_bytes: int = 250_000_000,
    min_pages: int = 2,
    min_large_pages: int = 1,
    min_large_ratio: float = 0.25,
    allow_letter_plans: bool = False,
    reject_vintage: bool = False,
    headers: dict[str, str] | None = None,
    timeout: int = 180,
    attempts: int = 3,
) -> dict[str, Any]:
    """Stream-download a PDF, enforce dedup/holdout/quality checks, and return provenance dict."""
    dest_dir.mkdir(parents=True, exist_ok=True)
    meta_out: dict[str, Any] = {'url': url, 'file': filename}
    if extra_meta:
        meta_out.update(extra_meta)

    if matches_holdout(filename + ' ' + json.dumps(extra_meta or {}), holdout_tokens):
        return {**meta_out, 'status': 'skipped_holdout_project'}

    url_tag = hashlib.sha1(url.encode('utf-8', 'ignore')).hexdigest()[:8]
    if (dest_dir / filename).exists():
        stem = filename[:-4] if filename.lower().endswith('.pdf') else filename
        filename = f'{stem}_{url_tag}.pdf'
        meta_out['file'] = filename
    target = dest_dir / filename
    part = dest_dir / (filename + '.part')

    req_headers = dict(DEFAULT_HEADERS)
    if headers:
        req_headers.update(headers)

    last_err = ''
    for attempt in range(attempts):
        try:
            req = urllib.request.Request(url, headers=req_headers)
            with urllib.request.urlopen(req, timeout=timeout, context=CTX) as r:
                cl = r.headers.get('Content-Length')
                if cl and cl.isdigit() and int(cl) > max_bytes:
                    return {**meta_out, 'status': 'skipped_too_large', 'bytes': int(cl)}

                first = r.read(16)
                if not first.startswith(b'%PDF-'):
                    return {**meta_out, 'status': 'rejected_not_pdf', 'detail': repr(first[:16])}

                h = hashlib.sha256()
                total = 0
                with part.open('wb') as f:
                    f.write(first)
                    h.update(first)
                    total += len(first)
                    while chunk := r.read(1024 * 256):
                        total += len(chunk)
                        if total > max_bytes:
                            raise ValueError(f'exceeded max_bytes {max_bytes}')
                        f.write(chunk)
                        h.update(chunk)

            if total < min_bytes:
                part.unlink(missing_ok=True)
                return {**meta_out, 'status': 'rejected_too_small', 'bytes': total}

            sha = h.hexdigest()
            if sha_lock:
                with sha_lock:
                    dup = sha in known_sha256s
                    if not dup:
                        known_sha256s.add(sha)
            else:
                dup = sha in known_sha256s
                if not dup:
                    known_sha256s.add(sha)

            if dup:
                part.unlink(missing_ok=True)
                return {**meta_out, 'status': 'skipped_duplicate', 'sha256': sha, 'bytes': total}

            ok, qinfo = inspect_pdf_quality(
                part,
                min_pages=min_pages,
                min_large_pages=min_large_pages,
                min_large_ratio=min_large_ratio,
                allow_letter_plans=allow_letter_plans,
                reject_vintage=reject_vintage,
            )
            if not ok:
                part.unlink(missing_ok=True)
                return {**meta_out, 'status': 'rejected_not_blueprint', 'sha256': sha, 'bytes': total, **qinfo}

            if sha_lock:
                with sha_lock:
                    if target.exists():
                        stem = filename[:-4] if filename.lower().endswith('.pdf') else filename
                        filename = f'{stem}_{url_tag}.pdf'
                        meta_out['file'] = filename
                        target = dest_dir / filename
                    os.replace(part, target)
            else:
                if target.exists():
                    stem = filename[:-4] if filename.lower().endswith('.pdf') else filename
                    filename = f'{stem}_{url_tag}.pdf'
                    meta_out['file'] = filename
                    target = dest_dir / filename
                os.replace(part, target)
            return {
                **meta_out,
                'status': 'ok',
                'sha256': sha,
                'bytes': total,
                'pages': qinfo['pages'],
                'median_size_in': qinfo['median_size_in'],
                'has_text_layer': qinfo['has_text_layer'],
            }
        except urllib.error.HTTPError as e:
            part.unlink(missing_ok=True)
            last_err = f'HTTP {e.code}'
            if e.code in (401, 403, 404, 406, 410):
                break
            time.sleep(2 * (attempt + 1))
        except Exception as e:  # noqa: BLE001
            part.unlink(missing_ok=True)
            last_err = str(e)[:120]
            if 'exceeded max_bytes' in last_err:
                break
            time.sleep(2 * (attempt + 1))

    part.unlink(missing_ok=True)
    return {**meta_out, 'status': 'error', 'detail': last_err}


class SourceManifest:
    """Thread-safe incremental JSON manifest in `_download_logs/manifests/<id>.json`."""

    def __init__(self, manifest_id: str | Path, source_meta: dict[str, Any]):
        if isinstance(manifest_id, Path):
            self.path = manifest_id if manifest_id.suffix.lower() == '.json' else (manifest_id / 'SOURCE.json')
        else:
            self.path = MANIFESTS_DIR / f'{manifest_id}.json'
        self.dest_dir = self.path.parent
        self.lock = threading.Lock()
        self.source_meta = dict(source_meta)
        self.items: dict[str, dict[str, Any]] = {}
        if self.path.is_file():
            try:
                old = json.loads(self.path.read_text())
                for it in old.get('items', []):
                    key = it.get('url') or it.get('file')
                    if key:
                        self.items[key] = it
            except Exception:
                pass

    def has_url(self, url: str) -> bool:
        with self.lock:
            it = self.items.get(url)
            if not it:
                return False
            st = it.get('status')
            if st in (
                'ok',
                'existing',
                'skipped_duplicate',
                'skipped_holdout_project',
                'rejected_not_blueprint',
                'rejected_too_small',
                'rejected_not_pdf',
                'skipped_too_large',
                'removed_non_blueprint',
                'removed_duplicate',
            ):
                return True
            return False

    def record(self, item: dict[str, Any], flush: bool = False) -> None:
        key = item.get('url') or item.get('file')
        if not key:
            return
        with self.lock:
            self.items[key] = item
            if flush:
                self._write_unlocked()

    def save(self) -> None:
        with self.lock:
            self._write_unlocked()

    def _write_unlocked(self) -> None:
        self.dest_dir.mkdir(parents=True, exist_ok=True)
        items_list = sorted(self.items.values(), key=lambda x: (x.get('status', ''), x.get('file', '')))
        counts: dict[str, int] = {}
        ok_bytes = 0
        ok_pages = 0
        for it in items_list:
            st = it.get('status', 'unknown')
            counts[st] = counts.get(st, 0) + 1
            if st in ('ok', 'existing'):
                ok_bytes += int(it.get('bytes') or 0)
                ok_pages += int(it.get('pages') or 0)
        payload = {
            **self.source_meta,
            'updated_at': time.strftime('%Y-%m-%d %H:%M:%S %Z'),
            'status_counts': counts,
            'downloaded_bytes': ok_bytes,
            'downloaded_pages': ok_pages,
            'items': items_list,
        }
        tmp = self.path.with_suffix('.json.tmp')
        tmp.write_text(json.dumps(payload, indent=2))
        os.replace(tmp, self.path)


def safe_filename(prefix: str, name: str, suffix_id: str = '', max_len: int = 90) -> str:
    stem = re.sub(r'\.pdf$', '', name, flags=re.I)
    s_pref = slug(prefix, limit=30, fallback='') if prefix else ''
    s_stem = slug(stem, limit=max_len, fallback='plan')
    parts = [p for p in [s_pref, s_stem, suffix_id[:8] if suffix_id else ''] if p]
    return '_'.join(parts) + '.pdf'
