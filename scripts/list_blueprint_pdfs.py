#!/usr/bin/env python3
"""Inventory every source PDF under BLUEPRINTS_ROOT with page/sheet/source metadata.

Outputs `<BLUEPRINTS_ROOT>/<output_prefix>.json` and `<BLUEPRINTS_ROOT>/<output_prefix>.md`.
Default prefix is `BLUEPRINT_PDF_INVENTORY`.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import statistics
import sys
import time
from collections import Counter, defaultdict
from pathlib import Path

import pymupdf
pymupdf.TOOLS.mupdf_display_errors(False)

DEFAULT_ROOT = Path(
    os.environ.get(
        'BLUEPRINTS_ROOT',
        '/home/max/.t3/projects/construction-calculator/blueprints_and_libraries',
    )
)

TXDOT_RE = re.compile(r'^(?:\d{2}_)?(?:\d{2}(?:\d{4})?-\d{2,9}|(?:VCSLC|RFC)[_ -]\d{2,6}-\d{2,4}).*plan', re.I)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open('rb') as f:
        while chunk := f.read(1024 * 1024):
            h.update(chunk)
    return h.hexdigest()


def load_manifests(root: Path) -> tuple[dict[str, dict], dict[str, dict], dict[str, str]]:
    """Return (by_path, by_sha, eval_sha_to_canonical) from existing manifests and SOURCE.json files."""
    by_path: dict[str, dict] = {}
    by_sha: dict[str, dict] = {}
    eval_shas: dict[str, str] = {}

    for rel, split_name in [
        ('1_blueprints/manifest.json', '1_blueprints/pool'),
        ('1_blueprints/evaluation/manifest.json', '1_blueprints/evaluation_holdout'),
    ]:
        p = root / rel
        if not p.is_file():
            continue
        try:
            data = json.loads(p.read_text())
        except Exception:
            continue
        for entry in data.get('files', []):
            sha = entry.get('sha256', '')
            cp = entry.get('canonical_path', '')
            info = {
                'sha256': sha,
                'canonical_path': cp,
                'manifest_split': split_name,
                'primary_source': entry.get('primary_source', ''),
                'source_url': entry.get('source_url', ''),
                'size_bytes': entry.get('size_bytes'),
            }
            if sha and sha not in by_sha:
                by_sha[sha] = info
            if split_name == '1_blueprints/evaluation_holdout' and sha:
                eval_shas[sha] = cp
            for candidate in [cp, *(entry.get('duplicate_paths') or [])]:
                if candidate:
                    by_path[os.path.normpath(candidate)] = info

    # Also read scraper manifests in _download_logs/manifests/ and legacy scraped_202611/**/SOURCE.json
    manifests_dir = root / '_download_logs' / 'manifests'
    if manifests_dir.is_dir():
        for sj in sorted(manifests_dir.glob('*.json')):
            try:
                data = json.loads(sj.read_text())
            except Exception:
                continue
            for item in data.get('items', data.get('files', [])):
                if not isinstance(item, dict):
                    continue
                fn = item.get('file')
                sha = item.get('sha256')
                if fn and sha:
                    fp = os.path.normpath(str(root / '1_blueprints' / fn))
                    by_path.setdefault(fp, {'sha256': sha, 'canonical_path': fp, 'source_url': item.get('url')})
                    if sha not in by_sha:
                        by_sha[sha] = {'sha256': sha, 'canonical_path': fp, 'source_url': item.get('url')}

    scraped_root = root / '1_blueprints' / 'scraped_202611'
    if scraped_root.is_dir():
        for sj in scraped_root.rglob('SOURCE.json'):
            try:
                data = json.loads(sj.read_text())
            except Exception:
                continue
            for item in data.get('items', data.get('files', [])):
                if not isinstance(item, dict):
                    continue
                fn = item.get('file')
                sha = item.get('sha256')
                if fn and sha:
                    fp = os.path.normpath(str(sj.parent / fn))
                    by_path.setdefault(fp, {'sha256': sha, 'canonical_path': fp, 'source_url': item.get('url')})
                    if sha not in by_sha:
                        by_sha[sha] = {'sha256': sha, 'canonical_path': fp, 'source_url': item.get('url')}
    return by_path, by_sha, eval_shas


def classify_split(rel_posix: str, sha: str, eval_shas: dict[str, str]) -> str:
    if rel_posix.startswith('1_blueprints/evaluation/'):
        return '1_blueprints/evaluation_holdout'
    if rel_posix.startswith('1_blueprints/'):
        if sha in eval_shas:
            return '1_blueprints/evaluation_holdout_linked'
        return '1_blueprints/pool'
    if rel_posix.startswith('2_component_libraries/'):
        return '2_component_libraries'
    if rel_posix.startswith('3_open_source_architecture/'):
        return '3_open_source_architecture'
    if rel_posix.startswith('4_archives/internet_archive'):
        return '4_archives/vintage_pattern_books'
    if rel_posix.startswith('4_archives/'):
        return '4_archives/modern_dot_municipal'
    if rel_posix.startswith('5_datasets/'):
        return '5_datasets'
    return 'other'


def classify_origin(rel_posix: str, canonical_path: str, primary_source: str = '') -> str:
    if primary_source and primary_source.startswith(('scraped/', 'state_dot/')):
        return primary_source
    cp = canonical_path or rel_posix
    cp = canonical_path or rel_posix
    low_cp = cp.lower()
    name = rel_posix.rsplit('/', 1)[-1]

    if rel_posix.startswith('1_blueprints/scraped_202611/'):
        parts = rel_posix.split('/')
        sub = parts[2] if len(parts) > 3 else 'misc'
        if len(parts) > 4 and sub in ('civicplus_municipal', 'wp_municipal', 'edu_and_schools'):
            return f'scraped/{sub}/{parts[3]}'
        return f'scraped/{sub}'

    if 'txdot-facilities-plans' in low_cp or TXDOT_RE.match(name):
        return 'TxDOT Facilities Plans'
    if '1_blueprints/rinker_hall/' in rel_posix:
        return 'UF Rinker Hall'
    if '1_blueprints/freefarmhouse/' in rel_posix:
        return 'FreeFarmhouse'
    if 'public-construction-plans/purchase' in low_cp or re.match(r'^su[-_]', name, re.I):
        return 'SUNY Purchase'
    if 'public-construction-plans/klamath' in low_cp or name.upper().startswith('KCC'):
        return 'Klamath Community College'
    if 'public-construction-plans/midlands' in low_cp or re.search(r'(?:^H59[-_]|_MTC_|MTC[_ ])', name, re.I):
        return 'Midlands Technical College'
    if 'public-construction-plans/newberry' in low_cp or 'newberry' in name.lower() or 'gallman' in name.lower():
        return 'Newberry County SC'
    if 'public-construction-plans/portwentworth' in low_cp or 'anchor-park' in name.lower() or 'port-wentworth' in name.lower():
        return 'Port Wentworth GA'
    if re.match(r'^(?:2995|3100|3143|3560|3670|3809|3818|3827|3863|3873|3920|3964)_', name):
        return 'Maine BGS (prior download)'
    if '4_archives/internet_archive' in rel_posix or any(
        tok in low_cp for tok in ['americanbuilder', 'usmodernist', 'radford', 'sears', 'bungalow', 'internet_archive']
    ):
        return 'Internet Archive (vintage 1907-1955)'
    if '4_archives/state_dot_samples/caltrans' in rel_posix or name.startswith('Caltrans_'):
        return 'Caltrans'
    if '4_archives/state_dot_samples/washington' in rel_posix or name.startswith('WSDOT_'):
        return 'WSDOT'
    if '4_archives/municipal_samples/seattle' in rel_posix or name.startswith('Seattle_'):
        return 'Seattle Standard Plans'
    if '4_archives/state_dot_samples/' in rel_posix:
        parts = rel_posix.split('/')
        return f'State DOT ({parts[2]})' if len(parts) > 2 else 'State DOT'
    if rel_posix.startswith('2_component_libraries/'):
        parts = rel_posix.split('/')
        return f'Component Library ({parts[1]})' if len(parts) > 1 else 'Component Library'
    if rel_posix.startswith('3_open_source_architecture/'):
        parts = rel_posix.split('/')
        return f'Open Source Arch ({parts[1]})' if len(parts) > 1 else 'Open Source Arch'
    if 'user_scans' in rel_posix or 'Scanned_' in name:
        return 'User Scans (NJATC)'
    if any(tok in name.lower() for tok in ['nmnh', 'baird', '675-901', '657-26-602', '36c776', 'ang_m', 'trinka-davis']):
        return 'Federal / VA / Smithsonian (prior)'
    if '1_blueprints/new_downloads_202611' in rel_posix or rel_posix.startswith('1_blueprints/'):
        return 'Municipal / Institutional (prior batches)'
    return 'Other'


def inspect_pdf(path: Path) -> dict:
    try:
        with pymupdf.open(path) as doc:
            pages = len(doc)
            if pages == 0:
                return {'pages': 0, 'median_size_in': [0.0, 0.0], 'max_dim_in': 0.0, 'is_large_format': False, 'has_text_layer': False, 'error': 'empty'}
            # Sample up to 15 evenly spaced pages for dimensions
            if pages <= 15:
                idxs = list(range(pages))
            else:
                idxs = sorted({int(i * (pages - 1) / 14) for i in range(15)})
            widths, heights, max_dims = [], [], []
            for idx in idxs:
                rect = doc[idx].rect
                w_in = round(float(rect.width) / 72.0, 1)
                h_in = round(float(rect.height) / 72.0, 1)
                widths.append(w_in)
                heights.append(h_in)
                max_dims.append(max(w_in, h_in))
            # Check text layer across first 3 pages
            chars = 0
            for idx in range(min(3, pages)):
                chars += len((doc[idx].get_text('text') or '').strip())
                if chars >= 50:
                    break
            med_w = round(float(statistics.median(widths)), 1)
            med_h = round(float(statistics.median(heights)), 1)
            max_dim = round(max(max_dims), 1)
            return {
                'pages': pages,
                'median_size_in': [med_w, med_h],
                'max_dim_in': max_dim,
                'is_large_format': max_dim >= 16.5,
                'has_text_layer': chars >= 50,
            }
    except Exception as e:  # noqa: BLE001
        return {
            'pages': 0,
            'median_size_in': [0.0, 0.0],
            'max_dim_in': 0.0,
            'is_large_format': False,
            'has_text_layer': False,
            'error': str(e)[:120],
        }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=DEFAULT_ROOT)
    parser.add_argument('--output-prefix', default='BLUEPRINT_PDF_INVENTORY')
    args = parser.parse_args()

    root = args.root.resolve()
    t0 = time.time()
    by_path, by_sha, eval_shas = load_manifests(root)

    pdf_paths = sorted(
        p for p in root.rglob('*') if p.is_file() and p.suffix.lower() == '.pdf'
    )
    print(f'Found {len(pdf_paths)} PDF files under {root}', flush=True)

    inode_sha_cache: dict[tuple[int, int], str] = {}
    records: list[dict] = []
    seen_shas: dict[str, str] = {}

    for i, path in enumerate(pdf_paths, 1):
        rel = path.relative_to(root).as_posix()
        norm_abs = os.path.normpath(str(path))
        st = path.stat()
        size_bytes = st.st_size
        inode_key = (st.st_dev, st.st_ino)

        m_info = by_path.get(norm_abs)
        sha = ''
        if m_info and m_info.get('sha256') and (m_info.get('size_bytes') in (None, size_bytes)):
            sha = m_info['sha256']
        elif inode_key in inode_sha_cache:
            sha = inode_sha_cache[inode_key]
        else:
            sha = sha256_file(path)
            inode_sha_cache[inode_key] = sha

        canonical = (m_info or {}).get('canonical_path') or by_sha.get(sha, {}).get('canonical_path') or str(path)
        p_src = (m_info or {}).get('primary_source') or by_sha.get(sha, {}).get('primary_source') or ''
        split = classify_split(rel, sha, eval_shas)
        origin = classify_origin(rel, canonical, p_src)
        meta = inspect_pdf(path)

        is_dup_path = sha in seen_shas
        first_rel = seen_shas.get(sha)
        if not is_dup_path:
            seen_shas[sha] = rel

        records.append({
            'filename': path.name,
            'rel_path': rel,
            'size_bytes': size_bytes,
            'size_mb': round(size_bytes / (1024 * 1024), 2),
            'sha256': sha,
            'canonical_path': canonical,
            'split': split,
            'source_origin': origin,
            'is_duplicate_in_tree': is_dup_path,
            'duplicate_of_rel_path': first_rel,
            **meta,
        })
        if i % 200 == 0:
            print(f'  inspected {i}/{len(pdf_paths)}...', flush=True)

    # Aggregate statistics
    unique_records = [r for r in records if not r['is_duplicate_in_tree']]
    by_split: dict[str, dict] = defaultdict(lambda: {'files': 0, 'unique_files': 0, 'bytes': 0, 'unique_bytes': 0, 'pages': 0, 'large_format_files': 0})
    by_origin: dict[str, dict] = defaultdict(lambda: {'files': 0, 'unique_files': 0, 'bytes': 0, 'unique_bytes': 0, 'pages': 0, 'large_format_files': 0})

    for r in records:
        for grp, key in [(by_split, r['split']), (by_origin, r['source_origin'])]:
            g = grp[key]
            g['files'] += 1
            g['bytes'] += r['size_bytes']
            if not r['is_duplicate_in_tree']:
                g['unique_files'] += 1
                g['unique_bytes'] += r['size_bytes']
                g['pages'] += r.get('pages', 0)
                if r.get('is_large_format'):
                    g['large_format_files'] += 1

    def finalize_table(d: dict[str, dict]) -> list[dict]:
        out = []
        for k, v in sorted(d.items(), key=lambda kv: (-kv[1]['unique_files'], kv[0])):
            out.append({
                'name': k,
                'files': v['files'],
                'unique_files': v['unique_files'],
                'total_mb': round(v['bytes'] / (1024 * 1024), 1),
                'unique_mb': round(v['unique_bytes'] / (1024 * 1024), 1),
                'unique_pages': v['pages'],
                'large_format_files': v['large_format_files'],
            })
        return out

    summary = {
        'generated_at': time.strftime('%Y-%m-%d %H:%M:%S %Z'),
        'elapsed_sec': round(time.time() - t0, 1),
        'root': str(root),
        'total_pdf_paths': len(records),
        'unique_pdf_sha256': len(unique_records),
        'total_bytes_all_paths': sum(r['size_bytes'] for r in records),
        'unique_bytes': sum(r['size_bytes'] for r in unique_records),
        'unique_pages': sum(r.get('pages', 0) for r in unique_records),
        'unique_large_format': sum(1 for r in unique_records if r.get('is_large_format')),
        'unique_vector_text': sum(1 for r in unique_records if r.get('has_text_layer')),
        'error_pdfs': sum(1 for r in records if 'error' in r),
        'by_split': finalize_table(by_split),
        'by_source_origin': finalize_table(by_origin),
    }

    json_path = root / f'{args.output_prefix}.json'
    md_path = root / f'{args.output_prefix}.md'

    json_path.write_text(json.dumps({'summary': summary, 'files': records}, indent=2))

    # Build Markdown report
    lines = [
        f'# Blueprint Source PDF Inventory (`{args.output_prefix}`)',
        '',
        f'Generated: {summary["generated_at"]} ({summary["elapsed_sec"]}s)  ',
        f'Root: `{root}`',
        '',
        '## Executive Summary',
        '',
        '| Metric | Value |',
        '|---|---:|',
        f'| Total PDF files on disk (incl. cross-folder copies/hardlinks) | {summary["total_pdf_paths"]:,} |',
        f'| Unique PDFs (by SHA-256) | {summary["unique_pdf_sha256"]:,} |',
        f'| Unique total size | {summary["unique_bytes"] / 1024 / 1024 / 1024:.2f} GB ({summary["unique_bytes"] / 1024 / 1024:,.1f} MB) |',
        f'| Unique total pages | {summary["unique_pages"]:,} |',
        f'| Large-format blueprint PDFs (>= 16.5 in) | {summary["unique_large_format"]:,} |',
        f'| Vector / text-layer PDFs | {summary["unique_vector_text"]:,} |',
        f'| Corrupted / unreadable PDFs | {summary["error_pdfs"]:,} |',
        '',
        '## Breakdown by Split / Directory Category',
        '',
        '| Split / Category | PDF Paths | Unique PDFs | Unique Size (MB) | Unique Pages | Large Format |',
        '|---|---:|---:|---:|---:|---:|',
    ]
    for row in summary['by_split']:
        lines.append(
            f"| `{row['name']}` | {row['files']:,} | {row['unique_files']:,} | {row['unique_mb']:,.1f} | {row['unique_pages']:,} | {row['large_format_files']:,} |"
        )

    lines.extend([
        '',
        '## Breakdown by Source Origin',
        '',
        '| Source Origin | PDF Paths | Unique PDFs | Unique Size (MB) | Unique Pages | Large Format |',
        '|---|---:|---:|---:|---:|---:|',
    ])
    for row in summary['by_source_origin']:
        lines.append(
            f"| {row['name']} | {row['files']:,} | {row['unique_files']:,} | {row['unique_mb']:,.1f} | {row['unique_pages']:,} | {row['large_format_files']:,} |"
        )

    lines.extend(['', '## Complete Catalog of Every Source PDF (Grouped by Source Origin)', ''])

    grouped: dict[str, list[dict]] = defaultdict(list)
    for r in records:
        grouped[r['source_origin']].append(r)

    for origin in [row['name'] for row in summary['by_source_origin']]:
        items = sorted(grouped[origin], key=lambda x: (x['rel_path'].lower()))
        uniq_cnt = sum(1 for x in items if not x['is_duplicate_in_tree'])
        lines.extend([
            f'### {origin} ({len(items)} paths, {uniq_cnt} unique)',
            '',
            '| Filename | Relative Path | MB | Pages | Sheet Size (in) | Type | Split | Note |',
            '|---|---|---:|---:|---|---|---|',
        ])
        for x in items:
            sz_in = f"{x['median_size_in'][0]}x{x['median_size_in'][1]}"
            kind = ('Vector' if x['has_text_layer'] else 'Raster') + (' / Blueprint' if x['is_large_format'] else ' / Letter')
            if 'error' in x:
                kind = f"ERROR: {x['error']}"
            note = f"dup of `{x['duplicate_of_rel_path']}`" if x['is_duplicate_in_tree'] else ''
            fn_esc = x['filename'].replace('|', '\\|')
            rel_esc = x['rel_path'].replace('|', '\\|')
            lines.append(
                f"| `{fn_esc}` | `{rel_esc}` | {x['size_mb']:.2f} | {x['pages']} | {sz_in} | {kind} | `{x['split']}` | {note} |"
            )
        lines.append('')

    md_path.write_text('\n'.join(lines))
    print(f'Wrote {json_path} and {md_path} in {summary["elapsed_sec"]}s', flush=True)
    print(
        f"Summary: {summary['total_pdf_paths']} PDF paths, {summary['unique_pdf_sha256']} unique, "
        f"{summary['unique_bytes']/1024/1024/1024:.2f} GB, {summary['unique_pages']:,} pages",
        flush=True,
    )


if __name__ == '__main__':
    main()
