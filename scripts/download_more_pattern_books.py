#!/usr/bin/env python3
"""Fetch Internet Archive pre-1931 house-plan books, preserving item provenance."""
import concurrent.futures, hashlib, json, os, pathlib, urllib.parse, urllib.request
root=pathlib.Path('blueprints_and_libraries/4_archives/internet_archive_additional'); root.mkdir(parents=True,exist_ok=True)
headers={'User-Agent':'ConstructionReferenceCollector/1.0 (reference library)'}
def get(url):
 return urllib.request.urlopen(urllib.request.Request(url,headers=headers),timeout=90)
queries=['title:("house plans") AND mediatype:texts AND year:[1900 TO 1930]','title:("home plans") AND mediatype:texts AND year:[1900 TO 1930]','title:("bungalow") AND title:("plans") AND mediatype:texts AND year:[1900 TO 1930]']
seen=set(); docs=[]
for q in queries:
 params=urllib.parse.urlencode([('q',q),('fl[]','identifier'),('fl[]','title'),('fl[]','year'),('rows','100'),('output','json')])
 try:
  result=json.load(get('https://archive.org/advancedsearch.php?'+params))
  for doc in result['response']['docs']:
   if doc['identifier'] not in seen: seen.add(doc['identifier']); docs.append(doc)
 except Exception as e: print('Search failed:',q,e,flush=True)
def fetch(d):
 ident=d['identifier']; out={'identifier':ident,'title':d.get('title'),'year':d.get('year'),'item_url':'https://archive.org/details/'+ident}
 try:
  meta=json.load(get('https://archive.org/metadata/'+urllib.parse.quote(ident)))
  if meta.get('is_dark') or meta.get('metadata',{}).get('access-restricted-item')=='true': raise ValueError('restricted item')
  candidates=[f for f in meta.get('files',[]) if f.get('name','').lower().endswith('.pdf') and not f.get('private') and 0<int(f.get('size') or 0)<=100*1024*1024]
  if not candidates: raise ValueError('no eligible public PDF under 100 MiB')
  f=min(candidates,key=lambda x:int(x['size'])); name=ident+'.pdf'; path=root/name; temp=root/(name+'.part'); url='https://archive.org/download/'+urllib.parse.quote(ident)+'/'+urllib.parse.quote(f['name'])
  if not path.exists():
   with get(url) as src,open(temp,'wb') as dst:
    while True:
     b=src.read(1024*1024)
     if not b: break
     dst.write(b)
   if temp.stat().st_size != int(f['size']): raise ValueError('size mismatch')
   with open(temp,'rb') as fd:
    if fd.read(5)!=b'%PDF-': raise ValueError('not PDF')
   if f.get('md5'):
    with open(temp,'rb') as fd:
     if hashlib.file_digest(fd,'md5').hexdigest()!=f['md5']: raise ValueError('checksum mismatch')
   os.replace(temp,path)
  out.update(file=name,bytes=path.stat().st_size,download_url=url,source_md5=f.get('md5'),rights=meta.get('metadata',{}).get('rights'),license_url=meta.get('metadata',{}).get('licenseurl'),status='downloaded')
 except Exception as e:
  (root/(ident+'.pdf.part')).unlink(missing_ok=True); out.update(status='unavailable',reason=str(e))
 print(ident,out['status'],flush=True); return out
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as ex: result=list(ex.map(fetch,docs))
(root/'SOURCE.json').write_text(json.dumps({'source':'https://archive.org/advancedsearch.php','queries':queries,'note':'Pre-1931 publications; rights are recorded where supplied and should be reviewed per item. Downloaded files match upstream byte size and MD5 when provided.','items':result},indent=2))
print('DONE',sum(x['status']=='downloaded' for x in result),'/',len(result),flush=True)
