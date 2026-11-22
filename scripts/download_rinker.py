import os
import urllib.request
import urllib.parse
import ssl
import re
from bs4 import BeautifulSoup
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ctx = ssl.create_default_context()
headers = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36'}

BLUEPRINTS_ROOT = Path(
    os.environ.get('BLUEPRINTS_ROOT', Path(__file__).resolve().parent.parent / 'blueprints_and_libraries')
)
dest_dir = str(BLUEPRINTS_ROOT / '1_blueprints/rinker_hall')
os.makedirs(dest_dir, exist_ok=True)

drawings_url = 'http://coremng.dcp.ufl.edu/rinkerhall/drawings.htm'
print('Fetching Rinker Hall drawing catalog...')
req = urllib.request.Request(drawings_url, headers=headers)
html = urllib.request.urlopen(req, timeout=15, context=ctx).read().decode('utf-8', errors='ignore')

soup = BeautifulSoup(html, 'html.parser')

drawings = []
for tr in soup.find_all('tr'):
    tds = tr.find_all(['td', 'th'])
    if len(tds) >= 2:
        title = tds[0].get_text(strip=True)
        sheet_no = tds[1].get_text(strip=True)
        pdf_a = tr.find('a', href=lambda h: h and h.lower().endswith('.pdf'))
        if pdf_a:
            full_url = urllib.parse.urljoin(drawings_url, pdf_a['href'])
            drawings.append({
                'sheet_no': sheet_no,
                'title': title,
                'url': full_url,
                'orig_fn': os.path.basename(pdf_a['href'])
            })

print(f'Found {len(drawings)} Rinker Hall drawings.')

def get_discipline(sheet_no):
    sheet = sheet_no.upper()
    if sheet.startswith('A'):
        return 'architectural'
    elif sheet.startswith('S'):
        return 'structural'
    elif sheet.startswith('M'):
        return 'mechanical'
    elif sheet.startswith('E'):
        return 'electrical'
    elif sheet.startswith('P'):
        return 'plumbing'
    elif sheet.startswith('FP') or sheet.startswith('F'):
        return 'fire_protection'
    elif sheet.startswith('L') or sheet.startswith('I'):
        return 'landscape_and_irrigation'
    elif sheet.startswith('C'):
        return 'civil'
    else:
        return 'general'

for d in drawings:
    disc = get_discipline(d['sheet_no'])
    d['discipline'] = disc
    d['subfolder'] = os.path.join(dest_dir, disc)
    os.makedirs(d['subfolder'], exist_ok=True)

    clean_title = re.sub(r'[^\w\s\-]', '', d['title']).strip().replace(' ', '_').lower()
    clean_title = re.sub(r'_+', '_', clean_title)
    d['filename'] = f"{d['sheet_no']}_{clean_title}.pdf"
    d['dest_path'] = os.path.join(d['subfolder'], d['filename'])

# Write an index README.md
readme_path = os.path.join(dest_dir, 'README.md')
with open(readme_path, 'w') as f:
    f.write('# University of Florida – Rinker Hall Construction Drawings\n\n')
    f.write('Complete set of construction drawings for the M.E. Rinker, Sr. Hall building (School of Building Construction at the University of Florida).\n\n')
    f.write('| Sheet No | Title | Discipline | File Path |\n')
    f.write('| --- | --- | --- | --- |\n')
    for d in drawings:
        rel_path = f"{d['discipline']}/{d['filename']}"
        f.write(f"| {d['sheet_no']} | {d['title']} | {d['discipline'].replace('_', ' ').title()} | [{d['filename']}]({rel_path}) |\n")

def download_drawing(d):
    dest = d['dest_path']
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return f"Already exists: {d['filename']}"
    try:
        r = urllib.request.Request(d['url'], headers=headers)
        with urllib.request.urlopen(r, timeout=30, context=ctx) as resp:
            data = resp.read()
            with open(dest, 'wb') as f:
                f.write(data)
        return f"Downloaded: {d['filename']} ({len(data)/1024/1024:.2f} MB)"
    except Exception as e:
        return f"Error {d['filename']}: {e}"

print('Starting concurrent download of Rinker Hall drawings...')
with ThreadPoolExecutor(max_workers=8) as ex:
    results = list(ex.map(download_drawing, drawings))

for res in results[:10]:
    print(' ', res)
print(f'Done downloading {len(drawings)} Rinker Hall drawings.')
