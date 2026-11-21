"""Download the HABS/HAER/HALS measured-drawing sample listed in manifest.json.

loc.gov rate-limits bulk API access from this network (HTTP 429), but the
manifest captured earlier holds each item's sheet count, and the tile server
path is derivable from the item id:

    item il0549 -> .../habshaer/il/il0500/il0549/sheet/00001a.tif

So the sheets are fetched directly from tile.loc.gov without further API calls.
"""
import os
import re
import json
import ssl
import time
import urllib.request
import urllib.error
from concurrent.futures import ThreadPoolExecutor, as_completed

DEST = ('/home/max/.t3/projects/construction-calculator/blueprints_and_libraries/'
        '4_archives/loc_habs_haer_hals')
MANIFEST = os.path.join(DEST, 'manifest.json')

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE
H = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
                   '(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'}


def slug(text, limit=70):
    text = re.sub(r'<[^>]+>', '', text or '')
    text = re.sub(r'[^A-Za-z0-9]+', '_', text).strip('_').lower()
    return text[:limit] or 'drawing'


def base_url(item_id):
    st = item_id[:2]
    num = int(item_id[2:])
    roll = (num // 100) * 100
    return f'https://tile.loc.gov/storage-services/master/pnp/habshaer/{st}/{st}{roll:04d}/{item_id}/sheet/'


def download(url, path, jpeg):
    if os.path.exists(path) and os.path.getsize(path) > 0:
        return os.path.getsize(path)
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=H), timeout=180, context=ctx) as r, \
                    open(path + '.part', 'wb') as f:
                while True:
                    chunk = r.read(1024 * 256)
                    if not chunk:
                        break
                    f.write(chunk)
            os.replace(path + '.part', path)
            return os.path.getsize(path)
        except urllib.error.HTTPError as e:
            if e.code == 404:
                break
            time.sleep(4 * (attempt + 1))
        except Exception:  # noqa: BLE001
            time.sleep(4 * (attempt + 1))
    # Fall back to the 1024px JPEG when the master TIFF is unavailable.
    if jpeg:
        jpath = path.rsplit('.', 1)[0] + '.jpg'
        try:
            with urllib.request.urlopen(urllib.request.Request(jpeg, headers=H), timeout=180, context=ctx) as r, \
                    open(jpath + '.part', 'wb') as f:
                while True:
                    chunk = r.read(1024 * 256)
                    if not chunk:
                        break
                    f.write(chunk)
            os.replace(jpath + '.part', jpath)
            return os.path.getsize(jpath)
        except Exception:  # noqa: BLE001
            pass
    return 0


def main():
    with open(MANIFEST) as f:
        manifest = json.load(f)
    jobs = []
    for item in manifest['items']:
        prefix = base_url(item['item_id'])
        for sp in range(1, item['sheets'] + 1):
            name = f"{item['item_id']}_sheet{sp:02d}_{slug(item['title'])}.tif"
            jobs.append((prefix + f'{sp:05d}a.tif', os.path.join(DEST, name),
                         prefix + f'{sp:05d}v.jpg'))
    print(f'{len(jobs)} sheets to fetch for {len(manifest["items"])} items', flush=True)
    ok = 0
    total = 0
    files = []
    with ThreadPoolExecutor(max_workers=3) as pool:
        futures = {pool.submit(download, url, path, jpeg): path for url, path, jpeg in jobs}
        for i, fut in enumerate(as_completed(futures), 1):
            n = fut.result()
            if n:
                ok += 1
                total += n
                files.append({'file': os.path.basename(futures[fut]), 'bytes': n})
            if i % 25 == 0:
                print(f'  {i}/{len(jobs)} {total/1024/1024:.0f} MB', flush=True)
    manifest['sheets_downloaded'] = ok
    manifest['files'] = files
    with open(MANIFEST, 'w') as f:
        json.dump(manifest, f, indent=1)
    print(f'DONE {ok}/{len(jobs)} sheets, {total/1024/1024:.1f} MB')


if __name__ == '__main__':
    main()
