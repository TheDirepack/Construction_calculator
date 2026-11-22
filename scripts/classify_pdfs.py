#!/usr/bin/env python3
"""Fast PDF classifier: vector (has text layer) vs image (scanned raster).

Usage:
    python3 scripts/classify_pdfs.py <dir-or-pdf> [<output.json>]

Classification is a rough first-pass filter, not proof: it reads only page 1
with pdftotext and falls back to a size-per-page heuristic. A "VECTOR" verdict
means "worth running the device extractor on", not "contains vector symbols".

Outputs JSON with summary + per-file entries, and prints a VECTOR/IMAGE split.
"""
import json
import re
import subprocess
import sys
from pathlib import Path

CONSTRUCTION_KEYWORDS = [
    r'drawing', r'plan', r'blueprint', r'bid', r'dwg', r'architectural',
    r'construction', r'mep', r'hvac', r'electrical', r'plumbing', r'structural',
    r'civil', r'site', r'floor', r'elevation', r'section', r'detail',
    r'sheet', r'permit', r'ifb', r'rfp', r'addendum', r'set',
    r'mechanical', r'fire', r'alarm', r'reflected', r'ceiling', r'riser',
    r'panel', r'schedule', r'standard.plan', r'washingtondrawing', r'rinker',
    r'farmhouse', r'cornish', r'kingsessing', r'unt_willis', r'upper.campus',
    r'upper.dublin', r'umd_d6', r'south.creek', r'dch.taggart', r'mtc.saluda',
    r'nmnh', r'baird', r'scanned', r'\b[A-Z]\d+\.\d+\b',
    r'\b[A-Z]\d+\b', r'\bFA-\d+\b', r'\b[CSPEGMFAHV]\d',
]
NON_CONSTRUCTION_KEYWORDS = [
    r'battletech', r'eclipse.phase', r'foundation', r'space.1889',
    r'aerotech', r'baofeng', r'user.manual', r'installation.manual',
    r'course.completion', r'cml_', r'motor.control', r'ms-smb',
    r'annas.archive', r'catalyst', r'fanpro', r'posthuman', r'isaac.asimov',
    r'sg2_user', r'ultimatesguide', r'pdfcoffee',
]


def is_construction(filename: str):
    name = filename.lower()
    for pat in NON_CONSTRUCTION_KEYWORDS:
        if re.search(pat, name):
            return False, f'excluded: {pat}'
    for pat in CONSTRUCTION_KEYWORDS:
        if re.search(pat, name):
            return True, f'construction: {pat}'
    return False, 'no construction keyword'


def classify(path: Path):
    try:
        size_mb = path.stat().st_size / (1024 * 1024)
        try:
            result = subprocess.run(
                ['pdftotext', '-f', '1', '-l', '1', str(path), '-'],
                capture_output=True, timeout=20,
            )
            text = result.stdout.decode('utf-8', errors='ignore')
            has_text = len(text.strip()) > 50
        except Exception:
            has_text = False

        try:
            result = subprocess.run(['pdfinfo', str(path)],
                                    capture_output=True, timeout=15)
            info = result.stdout.decode('utf-8', errors='ignore')
            pages_match = re.search(r'Pages:\s*(\d+)', info)
            pages = int(pages_match.group(1)) if pages_match else 0
        except Exception:
            pages = 0

        if has_text:
            return 'VECTOR', {'pages': pages, 'size_mb': round(size_mb, 1)}
        if pages > 0 and size_mb / pages > 1.0:
            return 'IMAGE', {'pages': pages, 'size_mb': round(size_mb, 1)}
        if size_mb > 10 and pages == 0:
            return 'IMAGE', {'pages': pages, 'size_mb': round(size_mb, 1)}
        return 'VECTOR', {'pages': pages, 'size_mb': round(size_mb, 1)}
    except Exception as e:
        return 'ERROR', {'error': str(e)[:100]}


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    target = Path(sys.argv[1])
    if target.is_dir():
        pdfs = sorted(target.rglob('*.pdf'))
        out = Path(sys.argv[2]) if len(sys.argv) > 2 else target / 'CLASSIFICATION.json'
    else:
        pdfs = [target]
        out = Path(sys.argv[2]) if len(sys.argv) > 2 else target.with_suffix('.classification.json')

    print(f'PDFs found: {len(pdfs)}')
    results = {'summary': {'total_pdfs': len(pdfs)}, 'vector_files': [],
               'image_files': [], 'error_files': [], 'excluded_files': []}

    for i, path in enumerate(pdfs, 1):
        is_const, reason = is_construction(path.name)
        if not is_const:
            results['excluded_files'].append(
                {'name': path.name, 'path': str(path), 'reason': reason})
            continue
        cls, details = classify(path)
        entry = {
            'name': path.name, 'path': str(path),
            'size_mb': round(path.stat().st_size / (1024 * 1024), 1),
            'classification': cls, 'details': details,
        }
        key = {'VECTOR': 'vector_files', 'IMAGE': 'image_files'}.get(cls, 'error_files')
        results[key].append(entry)
        print(f'  [{i}/{len(pdfs)}] {cls:7s} {path.name} '
              f'({entry["size_mb"]} MB, {details.get("pages", "?")} pp)')

    for k in ('vector_files', 'image_files', 'error_files', 'excluded_files'):
        results['summary'][k.replace('_files', '')] = len(results[k])

    out.write_text(json.dumps(results, indent=2))
    s = results['summary']
    print(f"\nVECTOR {s.get('vector', 0)} | IMAGE {s.get('image', 0)} | "
          f"ERROR {s.get('error', 0)} | EXCLUDED {s.get('excluded', 0)}")
    print(f'Wrote {out}')


if __name__ == '__main__':
    main()
