"""Download the full Innerscene public CAD drawing library (DXF).

The library sitemap lists ~1,863 drawings; each has a public download endpoint:
    https://www.innerscene.com/api/library/<slug>/download   -> DXF

Resumable: existing non-empty .dxf files are skipped.
"""
import os
import re
import json
import ssl
import time
import urllib.request
import urllib.error
from concurrent.futures import ThreadPoolExecutor, as_completed

BASE = 'https://www.innerscene.com'
DEST = '/home/max/.t3/projects/construction-calculator/blueprints_and_libraries/2_component_libraries/innerscene_cad_library'
MANIFEST = os.path.join(DEST, 'manifest.json')

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE
HEADERS = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
                         '(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'}

os.makedirs(DEST, exist_ok=True)


def fetch(url, timeout=60):
    req = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(req, timeout=timeout, context=ctx) as r:
        return r.read(), dict(r.headers)


def drawing_slugs():
    body, _ = fetch(BASE + '/sitemap/library.xml', timeout=90)
    locs = re.findall(r'<loc>(.*?)</loc>', body.decode('utf-8', 'ignore'))
    slugs = []
    for loc in locs:
        if '/tools/library/drawings/' not in loc:
            continue
        if '/tag/' in loc or '/format/' in loc:
            continue
        slug = loc.rstrip('/').rsplit('/', 1)[-1]
        if slug:
            slugs.append(slug)
    return sorted(set(slugs))


def download(slug):
    path = os.path.join(DEST, slug + '.dxf')
    if os.path.exists(path) and os.path.getsize(path) > 1024:
        return slug, 'skip', os.path.getsize(path)
    url = f'{BASE}/api/library/{slug}/download'
    last = None
    for attempt in range(4):
        try:
            body, headers = fetch(url, timeout=120)
            # Some Cloudflare responses return the signed file URL as plain text.
            if len(body) < 1024 and body.lstrip().startswith(b'http'):
                body, headers = fetch(body.decode('utf-8').strip(), timeout=120)
            if len(body) < 1024:
                raise RuntimeError(f'short response {len(body)}b')
            with open(path + '.part', 'wb') as f:
                f.write(body)
            os.replace(path + '.part', path)
            return slug, 'ok', len(body)
        except Exception as e:  # noqa: BLE001
            last = e
            time.sleep(1.5 * (attempt + 1))
    return slug, f'fail: {last}', 0


def main():
    raise RuntimeError("Disabled: upstream returns multiple formats and the legacy downloader mislabels them as DXF. Use manifest.json until this downloader is updated to preserve verified formats.")
    slugs = drawing_slugs()
    print(f'{len(slugs)} drawings in library sitemap', flush=True)
    results = []
    with ThreadPoolExecutor(max_workers=4) as pool:
        futures = {pool.submit(download, s): s for s in slugs}
        for i, fut in enumerate(as_completed(futures), 1):
            slug, status, size = fut.result()
            results.append({'slug': slug, 'status': status, 'bytes': size})
            if i % 50 == 0 or i == len(slugs):
                ok = sum(1 for r in results if r['status'] in ('ok', 'skip'))
                print(f'{i}/{len(slugs)} processed, {ok} present', flush=True)
    with open(MANIFEST, 'w') as f:
        json.dump(results, f, indent=1)
    ok = sum(1 for r in results if r['status'] in ('ok', 'skip'))
    failed = [r for r in results if r['status'] not in ('ok', 'skip')]
    total = sum(r['bytes'] for r in results)
    print(f'DONE {ok}/{len(results)} files, {total/1024/1024:.1f} MB, {len(failed)} failed')
    for r in failed[:20]:
        print('  FAIL', r['slug'], r['status'])


if __name__ == '__main__':
    main()
