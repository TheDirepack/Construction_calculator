#!/usr/bin/env python3
"""Verify jackluoluo/ArchCAD archive integrity, extract zips, and update SOURCE.json."""
from __future__ import annotations

import json
import os
import time
import zipfile
from pathlib import Path

BLUEPRINTS_ROOT = Path(
    os.environ.get(
        'BLUEPRINTS_ROOT',
        '/home/max/.t3/projects/construction-calculator/blueprints_and_libraries',
    )
)
ARCHCAD_DIR = BLUEPRINTS_ROOT / '5_datasets' / 'archcad_400k'


def main() -> None:
    source_path = ARCHCAD_DIR / 'SOURCE.json'
    if not source_path.is_file():
        raise SystemExit(f'Missing {source_path}')
    meta = json.loads(source_path.read_text())

    # 1. Verify downloaded file sizes
    expected = meta.get('file_manifest', [])
    size_checks = []
    for entry in expected:
        rel = entry['path']
        exp_sz = int(entry.get('size') or 0)
        fp = ARCHCAD_DIR / rel
        actual_sz = fp.stat().st_size if fp.is_file() else -1
        ok = fp.is_file() and (exp_sz == 0 or actual_sz == exp_sz)
        size_checks.append({'path': rel, 'expected_bytes': exp_sz, 'actual_bytes': actual_sz, 'ok': ok})
        if not ok:
            raise SystemExit(f'Size mismatch for {rel}: expected {exp_sz}, got {actual_sz}')
    print(f'Verified {len(size_checks)} ArchCAD files match upstream sizes.', flush=True)

    # 2. Verify ZIP integrity & extract into extracted/<modality>/
    extracted_root = ARCHCAD_DIR / 'extracted'
    extracted_root.mkdir(parents=True, exist_ok=True)
    zip_stats = {}
    for zp in sorted((ARCHCAD_DIR / 'data').glob('*.zip')):
        modality = zp.stem  # png, json, svg, point, caption
        target_dir = extracted_root / modality
        target_dir.mkdir(parents=True, exist_ok=True)
        print(f'Testing {zp.name} ({zp.stat().st_size/1024/1024:.1f} MB)...', flush=True)
        with zipfile.ZipFile(zp, 'r') as zf:
            bad = zf.testzip()
            if bad is not None:
                raise SystemExit(f'Corrupted entry in {zp.name}: {bad}')
            members = [m for m in zf.infolist() if not m.is_dir()]
            # Extract cleanly, preserving internal layout (or stripping redundant top dir if needed)
            extracted_now = 0
            skipped_existing = 0
            for m in members:
                out_path = (target_dir / m.filename).resolve()
                if not str(out_path).startswith(str(target_dir.resolve())):
                    raise SystemExit(f'Unsafe zip path: {m.filename}')
                if out_path.is_file() and out_path.stat().st_size == m.file_size:
                    skipped_existing += 1
                    continue
                out_path.parent.mkdir(parents=True, exist_ok=True)
                with zf.open(m, 'r') as src, out_path.open('wb') as dst:
                    while buf := src.read(1024 * 512):
                        dst.write(buf)
                extracted_now += 1
            zip_stats[modality] = {
                'zip_file': f'data/{zp.name}',
                'zip_bytes': zp.stat().st_size,
                'entries': len(members),
                'extracted_now': extracted_now,
                'skipped_existing': skipped_existing,
                'crc_verified': True,
            }
            print(
                f'  {modality}: {len(members)} entries (extracted={extracted_now}, existing={skipped_existing})',
                flush=True,
            )

    meta['verified_at'] = time.strftime('%Y-%m-%d %H:%M:%S %Z')
    meta['size_checks'] = size_checks
    meta['extracted'] = zip_stats
    tmp = source_path.with_suffix('.json.tmp')
    tmp.write_text(json.dumps(meta, indent=2))
    os.replace(tmp, source_path)
    print(f'Updated {source_path}', flush=True)


if __name__ == '__main__':
    main()
