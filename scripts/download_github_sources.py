"""Download open-source architecture projects and code-hosted datasets from GitHub.

Each repo is fetched as a branch zip from codeload.github.com, extracted into its
target directory, and the zip is kept alongside for provenance. Resumable: an
existing extracted directory with content is left alone unless FORCE is set.
"""
import os
import sys
import json
import shutil
import zipfile
import urllib.request
import urllib.error

ROOT = '/home/max/.t3/projects/construction-calculator/blueprints_and_libraries'
H = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
                   '(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'}
FORCE = '--force' in sys.argv

SOURCES = [
    # (repo, branch, target dir, category, description)
    ('wikihouseproject/Microhouse', 'master', '3_open_source_architecture/wikihouse_microhouse',
     'Wikihouse', 'Open-source CNC-cut Microhouse design files'),
    ('wikihouseproject/Skylark', 'main', '3_open_source_architecture/wikihouse_skylark',
     'Wikihouse', 'Open-source CNC-cut Skylark house design files (large)'),
    ('timrolls/Opendesk', 'master', '3_open_source_architecture/opendesk',
     'Opendesk', 'Open-source CNC furniture cutting files and assembly instructions'),
    ('uncreatednet/Elemental_Incremental_Housing', 'master', '3_open_source_architecture/elemental_incremental_housing',
     'Elemental', 'Alejandro Aravena / Elemental incremental housing DWG drawings'),
    ('opensourceecology/Civilization-Starter-Kit', 'master', '3_open_source_architecture/open_source_ecology_civilization_starter_kit',
     'Open Source Ecology', 'Global Village Construction Set documentation kit'),
    ('opensourceecology/vcs-library', 'main', '3_open_source_architecture/open_source_ecology_vcs_library',
     'Open Source Ecology', 'Village Construction Set part library: schemas, compilers, views'),
    ('CubiCasa/CubiCasa5k', 'master', '5_datasets/cubicasa5k',
     'CubiCasa5K', '5,000 annotated residential floor plans (code, samples, annotations)'),
    ('TeaganLi/HouseExpo', 'master', '5_datasets/houseexpo',
     'HouseExpo', '35,126 2D indoor layouts with room categories'),
    ('caspervanengelenburg/msd', 'main', '5_datasets/msd',
     'MSD', 'Benchmark dataset for floor plans of building complexes'),
]


def fetch(url, dest, timeout=300):
    req = urllib.request.Request(url, headers=H)
    with urllib.request.urlopen(req, timeout=timeout) as r, open(dest, 'wb') as f:
        shutil.copyfileobj(r, f, length=1024 * 256)


def main():
    for repo, branch, rel, category, desc in SOURCES:
        target = os.path.join(ROOT, rel)
        if os.path.isdir(target) and os.listdir(target) and not FORCE:
            print(f'SKIP {repo}: already extracted', flush=True)
            continue
        os.makedirs(target, exist_ok=True)
        zip_path = target + '.zip'
        url = f'https://codeload.github.com/{repo}/zip/refs/heads/{branch}'
        print(f'GET  {repo} ({branch}) -> {zip_path}', flush=True)
        try:
            fetch(url, zip_path)
        except Exception as e:  # noqa: BLE001
            print(f'FAIL {repo}: {e}', flush=True)
            continue
        size = os.path.getsize(zip_path)
        print(f'  got {size/1024/1024:.1f} MB, extracting', flush=True)
        try:
            with zipfile.ZipFile(zip_path) as z:
                z.extractall(target)
        except Exception as e:  # noqa: BLE001
            print(f'FAIL extract {repo}: {e}', flush=True)
            continue
        # Flatten the single top-level folder GitHub adds.
        entries = [e for e in os.listdir(target) if not e.startswith('__MACOSX')]
        if len(entries) == 1 and os.path.isdir(os.path.join(target, entries[0])):
            inner = os.path.join(target, entries[0])
            for name in os.listdir(inner):
                shutil.move(os.path.join(inner, name), os.path.join(target, name))
            os.rmdir(inner)
        meta = {'repo': repo, 'branch': branch, 'category': category,
                'description': desc, 'zip_bytes': size,
                'source_url': f'https://github.com/{repo}'}
        with open(os.path.join(target, 'SOURCE.json'), 'w') as f:
            json.dump(meta, f, indent=2)
        print(f'  done {repo}', flush=True)


if __name__ == '__main__':
    main()
