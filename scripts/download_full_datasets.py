"""Download the HouseExpo HF mirror and a CubiCasa5K mirror.

Zenodo blocks the official CubiCasa5K zip from this network (HTTP 403), so a
public Hugging Face mirror with rendered images and annotations is used instead.
"""
import os
import json
import ssl
import urllib.request

ROOT = '/home/max/.t3/projects/construction-calculator/blueprints_and_libraries/5_datasets'
ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE
H = {'User-Agent': 'Mozilla/5.0'}

os.makedirs(os.path.join(ROOT, 'houseexpo_hf', 'data'), exist_ok=True)
os.makedirs(os.path.join(ROOT, 'cubicasa5k_hf'), exist_ok=True)


def fetch(url, dest):
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return os.path.getsize(dest)
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    with urllib.request.urlopen(urllib.request.Request(url, headers=H), timeout=1800, context=ctx) as r, \
            open(dest + '.part', 'wb') as f:
        while True:
            chunk = r.read(1024 * 512)
            if not chunk:
                break
            f.write(chunk)
    os.replace(dest + '.part', dest)
    return os.path.getsize(dest)


HOUSE = 'https://huggingface.co/datasets/cwyark/HouseExpo/resolve/main/'
for rel in ['data/train-00000-of-00001.parquet', 'data/test-00000-of-00001.parquet',
            'data/validation-00000-of-00001.parquet']:
    n = fetch(HOUSE + rel, os.path.join(ROOT, 'houseexpo_hf', rel))
    print('houseexpo', rel, n, flush=True)

CUBI = 'https://huggingface.co/datasets/Tristan12138/cubicasa5k-skv4/resolve/main/'
for rel in ['images.tar', 'train.jsonl', 'val.jsonl', 'test.jsonl', 'stats.json', 'README.md']:
    n = fetch(CUBI + rel, os.path.join(ROOT, 'cubicasa5k_hf', rel))
    print('cubicasa mirror', rel, n, flush=True)

json.dump({
    'houseexpo': 'https://huggingface.co/datasets/cwyark/HouseExpo',
    'cubicasa5k_mirror': 'https://huggingface.co/datasets/Tristan12138/cubicasa5k-skv4',
    'cubicasa5k_official': 'https://zenodo.org/records/2613548 (HTTP 403 from this network)',
}, open(os.path.join(ROOT, 'DATASET_SOURCES.json'), 'w'), indent=1)
print('DONE', flush=True)
