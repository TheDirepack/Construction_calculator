#!/usr/bin/env python3
"""Retrieve building and engineering symbol categories, retaining provenance."""
import concurrent.futures, hashlib, html, io, json, pathlib, re, time, urllib.request, urllib.parse, zipfile
base='https://blocks.draftsperson.net'; root=pathlib.Path(__file__).resolve().parent.parent/'blueprints_and_libraries/2_component_libraries/draftsperson_additional_symbols'; root.mkdir(parents=True,exist_ok=True)
headers={'User-Agent':'ConstructionReferenceResearch/1.0'}
def read(url):
 with urllib.request.urlopen(urllib.request.Request(url,headers=headers),timeout=45) as r:return r.read()
categories=['building-services-symbols','electrical-symbols','hydraulic-symbols','pid-symbols','architectural-symbols','structural-symbols']
links={}; failures=[]
for cat in categories:
 url=f'{base}/download-category/{cat}/'
 try:
  text=read(url).decode(); pages={1}|{int(n) for n in re.findall(r'/download-category/'+cat+r'/page/(\d+)/',text)}
  for n in range(1,max(pages)+1):
   page=text if n==1 else read(url+f'page/{n}/').decode()
   for link in set(re.findall(r'href="(/download/[^"?]+/)"',page)): links.setdefault(base+link,[]).append(cat)
 except Exception as e: failures.append({'category':cat,'error':str(e)})
print('Unique listing pages:',len(links),flush=True)
def fetch(pair):
 url,cats=pair; item={'page':url,'categories':cats}
 try:
  text=read(url).decode(); scripts=re.findall(r'<script type="application/ld\+json">(.*?)</script>',text,re.S); obj=next((json.loads(x) for x in scripts if 'associatedMedia' in x),None)
  if not obj: raise ValueError('No associatedMedia metadata')
  media=obj['associatedMedia']; match=re.search(r'<a[^>]*class="download-button"[^>]*href="([^"]+)"',text); target=urllib.parse.urljoin(base,html.unescape(match.group(1))) if match else media['contentUrl']; name=re.sub(r'[^a-zA-Z0-9._-]+','_',url.rstrip('/').split('/')[-1])+'.dwg'; data=read(target)
  if data.startswith(b'PK\x03\x04'):
   with zipfile.ZipFile(io.BytesIO(data)) as archive:
    if archive.testzip(): raise ValueError('invalid ZIP archive')
   name=name[:-4]+'.zip'
  elif not data.startswith(b'AC10'): raise ValueError('response is not a recognized DWG or ZIP')
  (root/name).write_bytes(data)
  item.update(title=html.unescape(obj.get('name','')),url=target,license_url=obj.get('license'),file=name,bytes=len(data),sha256=hashlib.sha256(data).hexdigest(),status='ok')
 except Exception as e: item.update(status='failed',error=str(e))
 return item
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as p: items=list(p.map(fetch,links.items()))
(root/'SOURCE.json').write_text(json.dumps({'source':base,'categories':categories,'category_errors':failures,'items':items},indent=2))
print('Downloaded',sum(x['status']=='ok' for x in items),'/',len(items),'category errors',failures,'item errors',[(x['page'],x.get('error')) for x in items if x['status']!='ok'])
