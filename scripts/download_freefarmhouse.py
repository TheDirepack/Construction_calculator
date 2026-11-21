import os
import urllib.request
import urllib.parse
import ssl

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE
headers = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36'}

dest_dir = '/home/max/.t3/projects/construction-calculator/blueprints_and_libraries/1_blueprints/freefarmhouse'
os.makedirs(dest_dir, exist_ok=True)

base_url = 'https://www.freefarmhouse.com/uploads/b/244cd9c0-4894-11ea-8aad-9b21e8eb84db/'
files = [
    'American Extended 2022-02-02.zip',
    'American Extended 3D Views - Low Res.pdf',
    'American Farmhouse - Rendering Pages 2021-04-10_compressed.pdf',
    'American Farmhouse 201225 full.pdf',
    'American Farmhouse CAD Files.zip',
    'American Farmhouse Digital Model.zip',
    'American Farmhouse Extended 2021-12-25.pdf',
    'American Farmhouse Extended CAD Files.zip',
    'Farmhouse.zip',
    'Southern Farmhouse - Drawing Set - Jay Osborne 2023-02-01.pdf'
]

print(f'Starting download of {len(files)} FreeFarmhouse files...')
for fn in files:
    outpath = os.path.join(dest_dir, fn)
    if os.path.exists(outpath) and os.path.getsize(outpath) > 0:
        print(f'  Already downloaded: {fn} ({os.path.getsize(outpath)/1024/1024:.2f} MB)')
        continue
    url = base_url + urllib.parse.quote(fn)
    print(f'  Downloading: {fn}...')
    try:
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, timeout=60, context=ctx) as resp:
            data = resp.read()
            with open(outpath, 'wb') as f:
                f.write(data)
        print(f'  [DONE] {fn} ({len(data)/1024/1024:.2f} MB)')
    except Exception as e:
        print(f'  [ERROR] {fn}: {e}')

print('FreeFarmhouse download completed.')
