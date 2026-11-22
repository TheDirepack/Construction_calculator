#!/usr/bin/env python3
"""Download UW Cities and Buildings public display images; resumable by ID."""
import concurrent.futures as cf, json, os, pathlib, time
import requests
ROOT=pathlib.Path(__file__).resolve().parents[1]/'blueprints_and_libraries/4_archives'
DEST=ROOT/'uw_cities_and_buildings_full'; DEST.mkdir(exist_ok=True)
API='https://digitalcollections.lib.washington.edu/digital/api'
SESSION=requests.Session(); SESSION.headers.update({'User-Agent':'construction-reference-collection/1.0 (educational archive)'})

def get(url):
 for n in range(4):
  try:
   r=requests.get(url,timeout=(25,60),headers={'User-Agent':'construction-reference-collection/1.0'}); r.raise_for_status(); return r
  except Exception:
   if n==3: raise
   time.sleep(2**n)

def page(number):
 d=get(f'{API}/search/collection/buildings/maxRecords/1000/page/{number}').json()
 return number,d.get('totalResults'),d.get('items',[])

def dl(rec):
 rid=str(rec['itemId']); name=f'{rid}.jpg'; target=DEST/name
 if target.exists() and target.stat().st_size > 0: return {'id':rid,'file':name,'bytes':target.stat().st_size,'status':'existing'}
 url=f'{API}/singleitem/image/buildings/{rid}/default.jpg'; tmp=target.with_suffix('.jpg.part')
 try:
  r=get(url); data=r.content
  if not data.startswith(b'\xff\xd8\xff') or not data.endswith(b'\xff\xd9'): raise ValueError('response is not a complete JPEG')
  tmp.write_bytes(data); os.replace(tmp,target)
  return {'id':rid,'file':name,'bytes':len(data),'status':'downloaded'}
 except Exception as e:
  tmp.unlink(missing_ok=True); return {'id':rid,'error':str(e),'status':'failed'}

if __name__=='__main__':
 records_path=DEST/'records.json'
 if records_path.exists():
  saved=json.loads(records_path.read_text()); cached=saved.get('records',[])
 else: cached=[]
 if len(cached)>1000:
  total=int(saved['total_reported']); records={str(r['itemId']):r for r in cached}; print('using cached metadata',len(records),'of',total,flush=True)
 else:
  first=page(1); total=int(first[1]); pages=[first]; print('records advertised',total,flush=True)
  with cf.ThreadPoolExecutor(max_workers=4) as ex:
   for count,result in enumerate(ex.map(page,range(2,(total+999)//1000+1)),2):
    pages.append(result); print('metadata pages',count,flush=True)
  records={str(item['itemId']):item for _,_,items in pages for item in items if item.get('itemId')}
  records_path.write_text(json.dumps({'source':f'{API}/search/collection/buildings','total_reported':total,'records':list(records.values())},indent=1))
 try: existing={str(x['id']) for x in json.loads((ROOT/'uw_cities_and_buildings/SOURCE.json').read_text())['items']}
 except Exception: existing=set()
 print('unique records',len(records),'already in sample',len(existing),flush=True)
 results=[]; records_to_fetch=[v for k,v in records.items() if k not in existing]
 with cf.ThreadPoolExecutor(max_workers=4) as ex:
  for n,res in enumerate(ex.map(dl,records_to_fetch),1):
   results.append(res)
   if n%100==0:
    (DEST/'SOURCE.json').write_text(json.dumps({'collection':'https://digitalcollections.lib.washington.edu/digital/collection/buildings','note':'Public default display JPEGs; first 300 records in sibling sample directory. Rights are per item; consult collection metadata.','total_reported':total,'unique_records':len(records),'enumeration_note':'Fewer unique IDs than reported total; enumeration incomplete until reconciled.','sample_ids_skipped':len(existing),'results':results},indent=1))
    print('downloads processed',n,'failures',sum(x['status']=='failed' for x in results),flush=True)
 (DEST/'SOURCE.json').write_text(json.dumps({'collection':'https://digitalcollections.lib.washington.edu/digital/collection/buildings','note':'Public default display JPEGs; first 300 records in sibling sample directory. Rights are per item; consult collection metadata.','total_reported':total,'unique_records':len(records),'enumeration_note':'Fewer unique IDs than reported total; enumeration incomplete until reconciled.','sample_ids_skipped':len(existing),'results':results},indent=1))
 print('done',len(results),'failures',sum(x['status']=='failed' for x in results),flush=True)
