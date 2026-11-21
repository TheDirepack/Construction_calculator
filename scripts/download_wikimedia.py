import os
import urllib.request
import urllib.parse
import ssl
import json
import re
from concurrent.futures import ThreadPoolExecutor

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE
headers = {'User-Agent': 'ConstructionCalcBot/1.0 (https://github.com/thedirepack/construction-calculator; info@constructioncalc.org)'}

dest_dir = '/home/max/.t3/projects/construction-calculator/blueprints_and_libraries/1_blueprints/wikimedia_us_drawings'
os.makedirs(dest_dir, exist_ok=True)

api_url = 'https://commons.wikimedia.org/w/api.php?' + urllib.parse.urlencode({
    'action': 'query',
    'generator': 'categorymembers',
    'gcmtitle': 'Category:Architectural_drawings_from_the_United_States',
    'gcmtype': 'file',
    'gcmlimit': '50',
    'prop': 'imageinfo',
    'iiprop': 'url|size|mime',
    'format': 'json'
})

print('Fetching Wikimedia architectural drawings...')
req = urllib.request.Request(api_url, headers=headers)
with urllib.request.urlopen(req, timeout=15, context=ctx) as r:
    data = json.loads(r.read())

pages = data.get('query', {}).get('pages', {})
print(f'Found {len(pages)} drawings in Wikimedia category.')

items = []
for pid, page in pages.items():
    title = page.get('title', '').replace('File:', '').strip()
    imageinfo = page.get('imageinfo', [{}])[0]
    url = imageinfo.get('url')
    size = imageinfo.get('size', 0)
    mime = imageinfo.get('mime', '')
    ext = os.path.splitext(title)[1].lower()

    items.append({
        'title': title,
        'url': url,
        'size': size,
        'mime': mime,
        'ext': ext
    })

# Write index README
readme_path = os.path.join(dest_dir, 'README.md')
with open(readme_path, 'w') as f:
    f.write('# Wikimedia Commons – Architectural Drawings from the United States\n\n')
    f.write('Publicly available collection of US architectural and engineering blueprints, HABS/HAER survey drawings, elevations, and construction plans.\n\n')
    f.write('| Title | Format | Size | Source |\n')
    f.write('| --- | --- | --- | --- |\n')
    for it in items:
        f.write(f"| {it['title']} | {it['mime']} | {it['size']/1024/1024:.2f} MB | [Commons Link]({it['url']}) |\n")

def download_item(it):
    clean_title = re.sub(r'[^\w\s\-\.]', '_', it['title']).strip()
    clean_title = re.sub(r'_+', '_', clean_title)
    if len(clean_title) > 80:
        clean_title = clean_title[:76] + it['ext']

    outpath = os.path.join(dest_dir, clean_title)
    if os.path.exists(outpath) and os.path.getsize(outpath) > 0:
        return f"Already exists: {clean_title}"

    # Skip huge files over 35MB
    if it['size'] > 35 * 1024 * 1024:
        return f"Skipped (too large: {it['size']/1024/1024:.1f} MB): {clean_title}"

    try:
        r = urllib.request.Request(it['url'], headers=headers)
        with urllib.request.urlopen(r, timeout=60, context=ctx) as resp:
            data = resp.read()
            with open(outpath, 'wb') as f:
                f.write(data)
        return f"Downloaded: {clean_title} ({len(data)/1024/1024:.2f} MB)"
    except Exception as e:
        return f"Error {clean_title}: {e}"

print('Starting concurrent download of Wikimedia drawings...')
with ThreadPoolExecutor(max_workers=6) as ex:
    results = list(ex.map(download_item, items))

success_count = sum(1 for r in results if 'Downloaded' in r or 'Already' in r)
print(f'Done downloading Wikimedia drawings: {success_count}/{len(items)} downloaded.')
