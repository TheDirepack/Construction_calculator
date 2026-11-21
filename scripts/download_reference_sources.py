"""Download remaining reference sources.

  * PhotoCAD architecture gallery: public high-resolution previews plus metadata.
    The DXF/DWG/SVG/PDF downloads are behind a free email sign-in, so the vector
    files themselves are not fetched here.
  * Google Open Buildings: the smallest v3 S2 tiles, as a format sample of the
    global footprint dataset (the full release is far too large to mirror).
"""
import os
import re
import json
import ssl
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

ROOT = '/home/max/.t3/projects/construction-calculator/blueprints_and_libraries'
ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE
H = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
                   '(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'}


def read(url, timeout=90):
    with urllib.request.urlopen(urllib.request.Request(url, headers=H), timeout=timeout, context=ctx) as r:
        return r.read()


def save(url, path, timeout=300):
    if os.path.exists(path) and os.path.getsize(path) > 0:
        return os.path.getsize(path)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=H), timeout=timeout, context=ctx) as r, \
                    open(path + '.part', 'wb') as f:
                while True:
                    chunk = r.read(1024 * 256)
                    if not chunk:
                        break
                    f.write(chunk)
            os.replace(path + '.part', path)
            return os.path.getsize(path)
        except Exception:  # noqa: BLE001
            time.sleep(1.5 * (attempt + 1))
    return 0


def photocad():
    dest = os.path.join(ROOT, '1_blueprints', 'photocad_architecture')
    os.makedirs(dest, exist_ok=True)
    index = read('https://photocad.ai/vectors/architecture').decode('utf-8', 'ignore')
    slugs = sorted(set(re.findall(r'/vectors/architecture/([a-z0-9-]+-[0-9a-f]{4})', index)))
    print(f'photocad: {len(slugs)} gallery items', flush=True)
    items = []
    for i, slug in enumerate(slugs, 1):
        page_url = f'https://photocad.ai/vectors/architecture/{slug}'
        try:
            html = read(page_url).decode('utf-8', 'ignore')
        except Exception as e:  # noqa: BLE001
            print('  page fail', slug, e, flush=True)
            continue
        title = re.search(r'<title>([^<]+)</title>', html)
        title = (title.group(1) if title else slug).replace(' | PhotoCAD', '').strip()
        urls = re.findall(r'https://[a-z0-9]+\.supabase\.co/storage/v1/object/public/gallery-items/'
                          + re.escape(slug) + r'/' + re.escape(slug) + r'-(original|vector)(?:-thumb)?\.webp[^"\\ ]*', html)
        # Prefer the full original; fall back to the vector preview.
        full = re.search(r'https://[a-z0-9]+\.supabase\.co/storage/v1/object/public/gallery-items/'
                         + re.escape(slug) + r'/' + re.escape(slug) + r'-original\.webp[^"\\ ]*', html)
        if not full:
            full = re.search(r'https://[a-z0-9]+\.supabase\.co/storage/v1/object/public/gallery-items/'
                             + re.escape(slug) + r'/' + re.escape(slug) + r'-vector\.webp[^"\\ ]*', html)
        if not full:
            print('  no preview', slug, flush=True)
            continue
        name = slug + '.webp'
        n = save(full.group(0), os.path.join(dest, name))
        items.append({'slug': slug, 'title': title, 'page': page_url, 'file': name, 'bytes': n,
                      'vector_files': 'DXF/DWG/SVG/PDF require free account sign-in at the page'})
        print(f'  {i}/{len(slugs)} {name} {n/1024:.0f} KB', flush=True)
    with open(os.path.join(dest, 'SOURCE.json'), 'w') as f:
        json.dump({'source': 'https://photocad.ai/vectors/architecture',
                   'note': 'Public previews downloaded; vector downloads require a free account.',
                   'items': items}, f, indent=1)


def open_buildings():
    dest = os.path.join(ROOT, '5_datasets', 'google_open_buildings_sample')
    os.makedirs(dest, exist_ok=True)
    objects = []
    token = None
    for _ in range(4):
        url = ('https://storage.googleapis.com/storage/v1/b/open-buildings-data/o?prefix=v3/points_s2_level_4_gzip/'
               '&maxResults=1000&fields=items(name,size),nextPageToken')
        if token:
            url += '&pageToken=' + urllib.parse.quote(token)
        d = json.loads(read(url))
        objects.extend(d.get('items', []))
        token = d.get('nextPageToken')
        if not token:
            break
    files = sorted(((int(o.get('size') or 0), o['name']) for o in objects if int(o.get('size') or 0) > 0))
    picks = [f for f in files if f[0] < 3 * 1024 * 1024][:20]
    print(f'open buildings: {len(objects)} tiles listed, {len(picks)} small tiles selected', flush=True)
    manifest = []
    for size, name in picks:
        base = name.rsplit('/', 1)[-1]
        n = save('https://storage.googleapis.com/open-buildings-data/' + urllib.parse.quote(name),
                 os.path.join(dest, base))
        manifest.append({'object': name, 'file': base, 'bytes': n})
        print(f'  {base} {n/1024:.0f} KB', flush=True)
    with open(os.path.join(dest, 'SOURCE.json'), 'w') as f:
        json.dump({'source': 'gs://open-buildings-data/v3/points_s2_level_4_gzip/',
                   'note': 'Smallest S2 tiles only; the full global release is multi-terabyte.',
                   'tiles': manifest}, f, indent=1)


if __name__ == '__main__':
    photocad()
    open_buildings()
