#!/usr/bin/env python3
"""Audit flattened blueprints folder, duplicate isolation, and ArchCAD dataset."""
from __future__ import annotations

import hashlib
import json
import os
from collections import defaultdict
from pathlib import Path

import pymupdf

pymupdf.TOOLS.mupdf_display_errors(False)

ROOT = Path(
    os.environ.get(
        'BLUEPRINTS_ROOT',
        '/home/max/.t3/projects/construction-calculator/blueprints_and_libraries',
    )
)

# Pre-existing baseline corrupted/unreadable PDFs already present before 2026-11 scraping
KNOWN_BASELINE_CORRUPT = {
    '1_blueprints/japaneseflowerar0000marg.pdf',
    '1_blueprints/unset0000unse_c6w5.pdf',
}


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open('rb') as f:
        while chunk := f.read(1024 * 1024):
            h.update(chunk)
    return h.hexdigest()


def main() -> None:
    bdir = ROOT / '1_blueprints'
    eval_dir = bdir / 'evaluation'
    eval_manifest = json.loads((eval_dir / 'manifest.json').read_text())
    main_manifest = json.loads((bdir / 'manifest.json').read_text())

    eval_pdfs = sorted(p for p in eval_dir.iterdir() if p.is_file() and p.suffix.lower() == '.pdf')
    print(f'1. Locked evaluation holdout count: {len(eval_pdfs)} (expected 115)')
    if len(eval_pdfs) != 115:
        raise SystemExit(f'FAIL: expected 115 eval PDFs, found {len(eval_pdfs)}')

    # 2. Subfolder isolation: no PDFs anywhere inside 1_blueprints/ subdirs other than evaluation/
    stray_sub_pdfs = [
        p for p in bdir.rglob('*')
        if p.is_file() and p.suffix.lower() == '.pdf' and p.parent not in (bdir, eval_dir)
    ]
    print(f'2. PDFs in 1_blueprints subdirs other than evaluation/: {len(stray_sub_pdfs)}')
    if stray_sub_pdfs:
        raise SystemExit(f'FAIL: stray subfolder PDFs: {[str(p.relative_to(ROOT)) for p in stray_sub_pdfs[:10]]}')

    # 3. 4_archives/ PDFs: must be 0
    arch_dir = ROOT / '4_archives'
    arch_pdfs = [p for p in arch_dir.rglob('*') if p.is_file() and p.suffix.lower() == '.pdf'] if arch_dir.is_dir() else []
    print(f'3. PDFs in 4_archives/: {len(arch_pdfs)}')
    if arch_pdfs:
        raise SystemExit(f'FAIL: PDFs still in 4_archives/: {[str(p.relative_to(ROOT)) for p in arch_pdfs[:10]]}')

    # 4. Leftover .part files
    part_files = list(bdir.rglob('*.part')) + list((ROOT / '5_datasets' / 'archcad_400k').rglob('*.part'))
    print(f'4. Leftover .part files: {len(part_files)}')
    if part_files:
        raise SystemExit(f'FAIL: leftover .part files: {part_files[:5]}')

    # 5. Global SHA-256 uniqueness across ALL PDFs under BLUEPRINTS_ROOT
    all_pdfs = sorted(p for p in ROOT.rglob('*') if p.is_file() and p.suffix.lower() == '.pdf')
    by_sha: dict[str, list[Path]] = defaultdict(list)
    zero_byte: list[Path] = []
    unreadable: list[tuple[str, str]] = []
    pool_shas: set[str] = set()
    eval_shas = {it['sha256'] for it in eval_manifest.get('files', []) if it.get('sha256')}

    flat_pool_pdfs = sorted(p for p in bdir.iterdir() if p.is_file() and p.suffix.lower() == '.pdf')
    print(f'5. Auditing {len(all_pdfs)} total PDFs under {ROOT.name} ({len(flat_pool_pdfs)} flat in 1_blueprints/, {len(eval_pdfs)} in evaluation/)...')

    manifest_files = {it['filename']: it for it in main_manifest.get('files', []) if it.get('filename')}
    manifest_missing_disk: list[str] = []
    manifest_missing_entry: list[str] = []

    for p in flat_pool_pdfs:
        if p.name not in manifest_files:
            manifest_missing_entry.append(p.name)
    disk_names = {p.name for p in flat_pool_pdfs}
    for fn in manifest_files:
        if fn not in disk_names:
            manifest_missing_disk.append(fn)

    for i, p in enumerate(all_pdfs, 1):
        if i % 500 == 0:
            print(f'   audited {i}/{len(all_pdfs)}...', flush=True)
        rel = p.relative_to(ROOT).as_posix()
        if p.stat().st_size == 0:
            zero_byte.append(p)
            continue
        # Use manifest sha cache when size matches to keep audit fast, else hash file
        m_entry = manifest_files.get(p.name) if p.parent == bdir else None
        if m_entry and m_entry.get('sha256') and m_entry.get('size_bytes') == p.stat().st_size:
            sha = m_entry['sha256']
        else:
            sha = sha256_file(p)
        by_sha[sha].append(p)
        if p.parent == bdir:
            pool_shas.add(sha)
        try:
            with pymupdf.open(p) as doc:
                if len(doc) == 0:
                    unreadable.append((rel, '0 pages'))
        except Exception as e:  # noqa: BLE001
            unreadable.append((rel, str(e)[:100]))

    dup_groups = {sha: paths for sha, paths in by_sha.items() if len(paths) > 1}
    eval_overlaps = sorted(pool_shas & eval_shas)
    new_unreadable = [(r, err) for r, err in unreadable if r not in KNOWN_BASELINE_CORRUPT]

    print(f'   Zero-byte PDFs: {len(zero_byte)}')
    print(f'   Duplicate SHA-256 groups across entire tree: {len(dup_groups)}')
    print(f'   SHA-256 overlap (flat 1_blueprints/*.pdf vs evaluation/): {len(eval_overlaps)}')
    print(f'   1_blueprints/manifest.json entries={len(manifest_files)}, missing_entry={len(manifest_missing_entry)}, stale_entry={len(manifest_missing_disk)}')
    print(f'   Unreadable PDFs: {len(unreadable)} total (new_unreadable={len(new_unreadable)})')

    if dup_groups:
        for sha, paths in list(dup_groups.items())[:10]:
            print(f'   DUP {sha[:10]}: {[str(x.relative_to(ROOT)) for x in paths]}')
    if eval_overlaps:
        print(f'   EVAL OVERLAPS: {eval_overlaps[:10]}')
    if unreadable:
        print(f'   UNREADABLE: {unreadable[:10]}')

    # 6. ArchCAD check
    archcad = ROOT / '5_datasets' / 'archcad_400k'
    arch_source = json.loads((archcad / 'SOURCE.json').read_text())
    ext = arch_source.get('extracted', {})
    print(f'6. ArchCAD verified files={len(arch_source.get("size_checks", []))}, extracted modalities={list(ext.keys())}')
    for mod, info in ext.items():
        print(f'   {mod:10s}: {info.get("entries")} entries, crc_verified={info.get("crc_verified")}')

    if (
        zero_byte
        or dup_groups
        or eval_overlaps
        or manifest_missing_entry
        or manifest_missing_disk
        or new_unreadable
        or len(unreadable) > 4
    ):
        raise SystemExit('AUDIT FAILED')
    print('AUDIT PASSED!')


if __name__ == '__main__':
    main()
