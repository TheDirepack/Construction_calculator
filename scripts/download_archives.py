"""Download public archival blueprint/plan collections.

Sources handled here:
  * NPS Olmsted Archives via NPGallery album (original JPEGs)
  * Internet Archive vintage home-builder catalogs and pattern books (PDF)
  * University of Washington Cities and Buildings Database (CONTENTdm API)

Each source gets a SOURCE.json manifest; downloads are resumable.
"""
import os
import re
import json
import ssl
import time
import urllib.parse
import urllib.request
import urllib.error
from concurrent.futures import ThreadPoolExecutor, as_completed

ROOT = '/home/max/.t3/projects/construction-calculator/blueprints_and_libraries/4_archives'
ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE
H = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
                   '(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'}


def read(url, timeout=120):
    last = None
    for attempt in range(5):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=H), timeout=timeout, context=ctx) as r:
                return r.read()
        except Exception as e:  # noqa: BLE001
            last = e
            time.sleep(4 * (attempt + 1))
    raise last


def save(url, path, timeout=300):
    if os.path.exists(path) and os.path.getsize(path) > 0:
        return os.path.getsize(path)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    for attempt in range(6):
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
            time.sleep(5 * (attempt + 1))
    return 0


def slug(text, limit=60):
    text = re.sub(r'[^A-Za-z0-9]+', '_', text or '').strip('_').lower()
    return text[:limit] or 'item'


# ---------------------------------------------------------------- Olmsted NPS
def olmsted():
    dest = os.path.join(ROOT, 'olmsted_archives')
    os.makedirs(dest, exist_ok=True)
    album = 'https://npgallery.nps.gov/SearchResults/albumid/4892a7df-dab4-46c7-bbe3-0ee857047bec'
    html = read(album).decode('utf-8', 'ignore')
    m = re.search(r'var search = (\{.*?\});\s*\n', html, re.S)
    if not m:
        print('olmsted: search payload not found', flush=True)
        return
    data = json.loads(m.group(1))
    assets = []
    for result in data.get('Results', []):
        a = result.get('Asset') or {}
        if a.get('PrimaryType') != 'image':
            continue
        assets.append({'id': a.get('AssetID'), 'title': a.get('Title') or a.get('AltText') or 'plan',
                       'constraint': (a.get('ConstraintsInformation') or {}).get('Constraint')})
    print(f'olmsted: {len(assets)} image assets', flush=True)
    manifest = []
    for i, a in enumerate(assets, 1):
        name = f"{i:02d}_{slug(a['title'])}.jpg"
        n = save(f"https://npgallery.nps.gov/GetAsset/{a['id']}/original", os.path.join(dest, name))
        manifest.append({**a, 'file': name, 'bytes': n})
        print(f"  {i}/{len(assets)} {name} {n/1024/1024:.1f} MB", flush=True)
    with open(os.path.join(dest, 'SOURCE.json'), 'w') as f:
        json.dump({'source': album, 'collection': 'Olmsted Archives, Frederick Law Olmsted NHS',
                   'assets': manifest}, f, indent=1)


# ------------------------------------------------------------ Internet Archive
IA_QUERIES = [
    ('title:("american builder") AND mediatype:texts AND date:[1900-01-01 TO 1959-12-31]', 8),
    ('title:("home plans") AND mediatype:texts AND date:[1900-01-01 TO 1959-12-31]', 6),
    ('title:("house plans") AND mediatype:texts AND date:[1900-01-01 TO 1959-12-31]', 6),
    ('title:("bungalow book") AND mediatype:texts', 3),
    ('title:("modern homes") AND mediatype:texts AND date:[1900-01-01 TO 1959-12-31]', 5),
    ('title:("plan book") AND mediatype:texts AND date:[1900-01-01 TO 1959-12-31]', 4),
    ('title:("catalog homes") AND mediatype:texts', 3),
    ('title:("sears") AND title:("homes") AND mediatype:texts', 4),
]


def ia_search(query, rows):
    url = ('https://archive.org/advancedsearch.php?q=' + urllib.parse.quote(query) +
           '&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=year&rows=' + str(rows) + '&output=json')
    d = json.loads(read(url))
    return d['response']['docs']


def internet_archive():
    dest = os.path.join(ROOT, 'internet_archive_pattern_books')
    os.makedirs(dest, exist_ok=True)
    seen, picks = set(), []
    for query, rows in IA_QUERIES:
        try:
            for doc in ia_search(query, rows):
                ident = doc['identifier']
                if ident in seen:
                    continue
                seen.add(ident)
                picks.append({'identifier': ident, 'title': doc.get('title'), 'year': doc.get('year')})
        except Exception as e:  # noqa: BLE001
            print('ia query fail', query, e, flush=True)
    print(f'internet archive: {len(picks)} candidate items', flush=True)
    manifest = []
    for i, p in enumerate(picks, 1):
        try:
            meta = json.loads(read(f"https://archive.org/metadata/{p['identifier']}"))
        except Exception as e:  # noqa: BLE001
            print('  meta fail', p['identifier'], e, flush=True)
            continue
        files = meta.get('files', [])
        pdfs = [f for f in files if f.get('name', '').lower().endswith('.pdf')]
        if not pdfs:
            continue
        pdfs.sort(key=lambda f: int(f.get('size') or 0))
        chosen = pdfs[0]
        size = int(chosen.get('size') or 0)
        if size > 250 * 1024 * 1024:
            continue
        name = f"{slug(p['identifier'])}.pdf"
        n = save(f"https://archive.org/download/{p['identifier']}/{urllib.parse.quote(chosen['name'])}",
                 os.path.join(dest, name))
        manifest.append({**p, 'file': name, 'bytes': n,
                         'license': meta.get('metadata', {}).get('licenseurl') or
                                    meta.get('metadata', {}).get('rights') or 'public domain / see item'})
        print(f"  {i}/{len(picks)} {name} {n/1024/1024:.1f} MB", flush=True)
    with open(os.path.join(dest, 'SOURCE.json'), 'w') as f:
        json.dump({'source': 'https://archive.org/advancedsearch.php',
                   'description': 'Vintage US home-builder catalogs and pattern books',
                   'items': manifest}, f, indent=1)


# ------------------------------------------------------- UW Cities & Buildings
def uw():
    dest = os.path.join(ROOT, 'uw_cities_and_buildings')
    os.makedirs(dest, exist_ok=True)
    api = 'https://digitalcollections.lib.washington.edu/digital/api'
    records = []
    start = 1
    failures = 0
    while len(records) < 300:
        url = f'{api}/search/collection/buildings/maxRecords/100/start/{start}'
        try:
            d = json.loads(read(url))
        except Exception as e:  # noqa: BLE001
            failures += 1
            print('uw search fail', e, flush=True)
            if failures >= 3:
                break
            time.sleep(10)
            continue
        failures = 0
        items = d.get('items') or d.get('results') or []
        if not items:
            break
        records.extend(items)
        start += 100
    print(f'uw: {len(records)} records', flush=True)
    manifest = []
    for i, rec in enumerate(records[:300], 1):
        rid = rec.get('itemId') or rec.get('id')
        if rid is None:
            continue
        title = rec.get('title') or rec.get('Title') or f'item_{rid}'
        name = f"{i:03d}_{slug(title)}.jpg"
        n = save(f'{api}/singleitem/image/buildings/{rid}/default.jpg', os.path.join(dest, name))
        if n:
            manifest.append({'id': rid, 'title': title, 'file': name, 'bytes': n})
        if i % 50 == 0:
            print(f'  {i}/300', flush=True)
    with open(os.path.join(dest, 'SOURCE.json'), 'w') as f:
        json.dump({'source': 'https://digitalcollections.lib.washington.edu/digital/collection/buildings',
                   'description': 'University of Washington Cities and Buildings Database',
                   'items': manifest}, f, indent=1)


if __name__ == '__main__':
    olmsted()
    internet_archive()
    uw()
