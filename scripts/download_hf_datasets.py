"""Download Hugging Face dataset snapshots used as public floor-plan corpora.

Uses the public dataset tree API so no huggingface_hub install is required.
Supports optional HF_TOKEN env var for gated datasets and `--only <repo>`.
Resumable: files already on disk with the expected size are skipped.
"""
import argparse
import os
import json
import ssl
import time
import urllib.request
import urllib.error
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

BLUEPRINTS_ROOT = Path(
    os.environ.get('BLUEPRINTS_ROOT', Path(__file__).resolve().parent.parent / 'blueprints_and_libraries')
)
ROOT = str(BLUEPRINTS_ROOT)
H = {'User-Agent': 'Mozilla/5.0'}
if os.environ.get('HF_TOKEN'):
    H['Authorization'] = f"Bearer {os.environ['HF_TOKEN'].strip()}"

DATASETS = [
    ('jackluoluo/ArchCAD', '5_datasets/archcad_400k'),
    ('Voxel51/FloorPlanCAD', '5_datasets/floorplancad_sample'),
    ('Tristan12138/resplan-fp', '5_datasets/resplan'),
    ('Cybernaut101/rPlan_subset_100', '5_datasets/rplan_subset_100'),
]

ctx = ssl.create_default_context()


def api(url):
    req = urllib.request.Request(url, headers=H)
    with urllib.request.urlopen(req, timeout=60, context=ctx) as r:
        return json.loads(r.read())


def list_files(repo, path=''):
    files = []
    for entry in api(f'https://huggingface.co/api/datasets/{repo}/tree/main/{path}'):
        if entry['type'] == 'file':
            files.append((entry['path'], entry.get('size', 0)))
        elif entry['type'] == 'directory':
            files.extend(list_files(repo, entry['path']))
    return files


def download(repo, rel, size, dest):
    path = os.path.join(dest, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    if os.path.exists(path) and (size == 0 or os.path.getsize(path) == size):
        return rel, 'skip', os.path.getsize(path)
    url = f'https://huggingface.co/datasets/{repo}/resolve/main/{rel}'
    for attempt in range(4):
        try:
            req = urllib.request.Request(url, headers=H)
            with urllib.request.urlopen(req, timeout=600, context=ctx) as r, open(path + '.part', 'wb') as f:
                while True:
                    chunk = r.read(1024 * 1024)
                    if not chunk:
                        break
                    f.write(chunk)
            os.replace(path + '.part', path)
            return rel, 'ok', os.path.getsize(path)
        except Exception as e:  # noqa: BLE001
            time.sleep(2 * (attempt + 1))
            last = e
    return rel, f'fail: {last}', 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--only', action='append', help='Restrict download to specific repo(s), e.g. jackluoluo/ArchCAD')
    args = parser.parse_args()
    targets = DATASETS
    if args.only:
        only_set = set(args.only)
        targets = [d for d in DATASETS if d[0] in only_set]
        if not targets:
            raise SystemExit(f'Unknown repo(s): {args.only}')

    for repo, rel_dir in targets:
        dest = os.path.join(ROOT, rel_dir)
        os.makedirs(dest, exist_ok=True)
        files = list_files(repo)
        total = sum(s for _, s in files)
        print(f'{repo}: {len(files)} files, {total/1024/1024:.1f} MB -> {dest}', flush=True)
        done = 0
        failures = []
        with ThreadPoolExecutor(max_workers=6) as pool:
            futures = [pool.submit(download, repo, p, s, dest) for p, s in files]
            for fut in as_completed(futures):
                rel, status, n = fut.result()
                done += 1
                if status.startswith('fail'):
                    failures.append((rel, status))
                    print(f'  FAIL {rel}: {status}', flush=True)
                elif done % 100 == 0:
                    print(f'  {done}/{len(files)}', flush=True)
        with open(os.path.join(dest, 'SOURCE.json'), 'w') as f:
            json.dump({
                'dataset': repo,
                'files': len(files),
                'bytes': total,
                'source_url': f'https://huggingface.co/datasets/{repo}',
                'file_manifest': [{'path': p, 'size': s} for p, s in files],
                'failures': failures,
            }, f, indent=2)
        print(f'DONE {repo} (failures={len(failures)})', flush=True)


if __name__ == '__main__':
    main()
