"""Download representative state DOT plan sets (Caltrans).

State DOT archives publish plan sets per project rather than as one bulk
endpoint. These two Caltrans documents are the concrete samples kept locally:
the full 2022 Standard Plans book and a complete sample plan set.
"""
import os
import json
import ssl
import urllib.request

DEST = ('/home/max/.t3/projects/construction-calculator/blueprints_and_libraries/'
        '4_archives/state_dot_samples/caltrans')
ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE
H = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
                   '(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'}

FILES = [
    ('https://dot.ca.gov/-/media/dot-media/programs/design/documents/'
     'locked_2022__entire-revised-std-plans-97sheets_11-18-22-a11y.pdf',
     'Caltrans_2022_Standard_Plans_97_sheets.pdf'),
    ('https://dot.ca.gov/-/media/dot-media/programs/engineering/documents/des-bim41/'
     '202609ctplans101-a11y.pdf',
     'Caltrans_Sample_Plan_Set_Retaining_Walls.pdf'),
]

os.makedirs(DEST, exist_ok=True)
manifest = []
for url, name in FILES:
    path = os.path.join(DEST, name)
    if not (os.path.exists(path) and os.path.getsize(path) > 0):
        with urllib.request.urlopen(urllib.request.Request(url, headers=H), timeout=300, context=ctx) as r, \
                open(path + '.part', 'wb') as f:
            while True:
                chunk = r.read(1024 * 256)
                if not chunk:
                    break
                f.write(chunk)
        os.replace(path + '.part', path)
    n = os.path.getsize(path)
    manifest.append({'file': name, 'bytes': n, 'url': url})
    print(f'{name}: {n/1024/1024:.1f} MB')

with open(os.path.join(DEST, 'SOURCE.json'), 'w') as f:
    json.dump({'source': 'https://dot.ca.gov/programs/design', 'files': manifest}, f, indent=1)
