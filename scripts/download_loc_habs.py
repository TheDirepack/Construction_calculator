"""Download the HABS/HAER/HALS measured-drawing sample listed in manifest.json.

loc.gov rate-limits bulk API access from this network (HTTP 429), but the
manifest captured earlier holds each item's sheet count, and the tile server
path is derivable from the item id:

    item il0549 -> .../habshaer/il/il0500/il0549/sheet/00001a.tif

So the sheets are fetched directly from tile.loc.gov without further API calls.
"""
import os
import json
from concurrent.futures import ThreadPoolExecutor, as_completed

from dl_common import BLUEPRINTS_ROOT, save, slug

DEST = str(BLUEPRINTS_ROOT / '4_archives/loc_habs_haer_hals')
MANIFEST = os.path.join(DEST, 'manifest.json')


def base_url(item_id):
    st = item_id[:2]
    num = int(item_id[2:])
    roll = (num // 100) * 100
    return f'https://tile.loc.gov/storage-services/master/pnp/habshaer/{st}/{st}{roll:04d}/{item_id}/sheet/'


def download(url, path, jpeg):
    """The master TIFF, or the 1024px JPEG beside it when the master is unavailable."""
    n = save(url, path, timeout=180, attempts=4, backoff=4, stop_on_404=True)
    if n or not jpeg:
        return n
    return save(jpeg, path.rsplit('.', 1)[0] + '.jpg', timeout=180, attempts=1)


def main():
    with open(MANIFEST) as f:
        manifest = json.load(f)
    jobs = []
    for item in manifest['items']:
        prefix = base_url(item['item_id'])
        for sp in range(1, item['sheets'] + 1):
            name = f"{item['item_id']}_sheet{sp:02d}_{slug(item['title'], limit=70, fallback='drawing')}.tif"
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
