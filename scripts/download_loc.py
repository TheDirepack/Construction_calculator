import os
import urllib.request
import urllib.parse
import ssl
import re
from bs4 import BeautifulSoup
from pathlib import Path

ctx = ssl.create_default_context()
headers = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36'}

BLUEPRINTS_ROOT = Path(
    os.environ.get('BLUEPRINTS_ROOT', Path(__file__).resolve().parent.parent / 'blueprints_and_libraries')
)
dest_dir = str(BLUEPRINTS_ROOT / '1_blueprints/library_of_congress')
os.makedirs(dest_dir, exist_ok=True)

loc_url = 'https://www.loc.gov/free-to-use/architecture-and-design/'
print('Fetching Library of Congress Architecture & Design items...')
req = urllib.request.Request(loc_url, headers=headers)
html = urllib.request.urlopen(req, timeout=15, context=ctx).read().decode('utf-8', errors='ignore')

soup = BeautifulSoup(html, 'html.parser')

items = []
for a in soup.find_all('a', href=re.compile(r'/resource/|/item/')):
    href = a.get('href')
    img = a.find('img')
    text = a.get_text(strip=True)
    if not text and a.parent:
        text = a.parent.get_text(strip=True)
    img_src = img.get('src') if img else None
    if img_src:
        items.append((text, href, img_src))

print(f'Found {len(items)} items on Library of Congress page.')

for title, href, img_src in items:
    # Build clean filename
    safe_title = re.sub(r'[^\w\s\-]', '', title).strip().replace(' ', '_')
    safe_title = re.sub(r'_+', '_', safe_title)
    if len(safe_title) > 60:
        safe_title = safe_title[:60]

    img_filename = f'{safe_title}.jpg'
    outpath = os.path.join(dest_dir, img_filename)

    if os.path.exists(outpath) and os.path.getsize(outpath) > 0:
        print(f'  Already downloaded: {img_filename}')
        continue

    img_url = urllib.parse.urljoin(loc_url, img_src)
    print(f'  Downloading: {title[:40]} -> {img_filename}...')
    try:
        r = urllib.request.Request(img_url, headers=headers)
        with urllib.request.urlopen(r, timeout=30, context=ctx) as resp:
            data = resp.read()
            with open(outpath, 'wb') as f:
                f.write(data)
        print(f'  [DONE] {img_filename} ({len(data)/1024:.1f} KB)')
    except Exception as e:
        print(f'  [ERROR] {title}: {e}')

print('Library of Congress download completed.')
