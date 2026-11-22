#!/usr/bin/env python3
"""Download direct files in Commons' US architectural drawings category with metadata."""
import concurrent.futures, hashlib, json, os, pathlib, re, time, urllib.parse, urllib.request
ROOT=pathlib.Path(__file__).resolve().parent.parent / 'blueprints_and_libraries/1_blueprints/wikimedia_us_category_full'
ROOT.mkdir(parents=True, exist_ok=True)
HEADERS={'User-Agent':'ConstructionReferenceResearch/1.0 (local educational reference collection)'}
params={'action':'query','generator':'categorymembers','gcmtitle':'Category:Architectural_drawings_from_the_United_States','gcmtype':'file','gcmlimit':'500','prop':'imageinfo','iiprop':'url|size|mime|sha1|extmetadata','format':'json'}
req=urllib.request.Request('https://commons.wikimedia.org/w/api.php?'+urllib.parse.urlencode(params),headers=HEADERS)
with urllib.request.urlopen(req,timeout=60) as r: data=json.load(r)
if 'continue' in data: raise RuntimeError('Category pagination needed; aborting to avoid incomplete manifest')
items=[]
for page in data['query']['pages'].values():
 info=page['imageinfo'][0]; title=page['title']; ext=pathlib.Path(title).suffix[:12]
 filename=f"{page['pageid']}_{re.sub(r'[^A-Za-z0-9._-]+','_',title[5:])[:100]}"
 if ext and not filename.lower().endswith(ext.lower()): filename+=ext
 meta=info.get('extmetadata',{})
 items.append({'pageid':page['pageid'],'title':title,'url':info['url'],'description_url':info.get('descriptionurl'),'bytes':info['size'],'sha1':info['sha1'],'mime':info.get('mime'),'license':meta.get('LicenseShortName',{}).get('value'),'credit':meta.get('Credit',{}).get('value'),'artist':meta.get('Artist',{}).get('value'),'file':filename})
def fetch(item):
 path=ROOT/item['file']; temp=path.with_name(path.name+'.part')
 def valid():
  if not path.is_file() or path.stat().st_size!=item['bytes']: return False
  h=hashlib.sha1()
  with path.open('rb') as f:
   for chunk in iter(lambda:f.read(1024*1024),b''): h.update(chunk)
  return h.hexdigest()==item['sha1']
 try:
  if not valid():
   for attempt in range(3):
    try:
     with urllib.request.urlopen(urllib.request.Request(item['url'],headers=HEADERS),timeout=120) as src, temp.open('wb') as out:
      while chunk:=src.read(1024*1024): out.write(chunk)
     os.replace(temp,path)
     if not valid(): raise ValueError('size or SHA-1 mismatch')
     break
    except Exception:
     temp.unlink(missing_ok=True)
     if attempt==2: raise
     time.sleep(2*(attempt+1))
  item['status']='verified'
 except Exception as e: item['status']='failed'; item['error']=str(e)
 return item
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as ex: results=list(ex.map(fetch,items))
(ROOT/'SOURCE.json').write_text(json.dumps({'source':'https://commons.wikimedia.org/wiki/Category:Architectural_drawings_from_the_United_States','scope':'direct category files only, no subcategories','items':results},indent=2))
print('Verified',sum(x['status']=='verified' for x in results),'/',len(results),'failed',[(x['title'],x.get('error')) for x in results if x['status']!='verified'])
