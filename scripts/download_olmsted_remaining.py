import os, re, json, ssl, time, urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

ROOT='/home/max/.t3/projects/construction-calculator/blueprints_and_libraries/4_archives/olmsted_acadia_flickr'
os.makedirs(ROOT, exist_ok=True)
ctx=ssl.create_default_context(); ctx.check_hostname=False; ctx.verify_mode=ssl.CERT_NONE
H={'User-Agent':'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'}

need=json.load(open(os.path.join(ROOT,'remaining_photo_ids.json')))
print(f'Downloading {len(need)} remaining Olmsted Acadia photos', flush=True)

def get_page(pid):
    """Visit photo page, extract original image URL."""
    url=f'https://www.flickr.com/photos/olmsted_archives/{pid}/'
    html=urllib.request.urlopen(urllib.request.Request(url,headers=H),timeout=30,context=ctx).read().decode('utf-8','ignore')
    # Look for original image URL
    m=re.search(r'https://live\.staticflickr\.com/\d+/\d+_[0-9a-f]+_o\.jpg',html)
    if m:
        return m.group(0)
    # Fallback: try _k or _h size, or any staticflickr URL
    m=re.search(r'https://live\.staticflickr\.com/[^\"\'<>]+',html)
    if m:
        return m.group(0)
    return None

def dl(pid):
    path=os.path.join(ROOT,pid+'_o.jpg')
    if os.path.exists(path) and os.path.getsize(path)>0:
        return {'id':pid,'status':'existing'}
    try:
        img_url=get_page(pid)
        if not img_url:
            return {'id':pid,'status':'fail','error':'no image URL'}
        # Convert to original size if needed
        if '_o.jpg' not in img_url:
            img_url=re.sub(r'_(b|h|k|z|c|q|m|n|s|t)\.jpg','_o.jpg',img_url)
        data=urllib.request.urlopen(urllib.request.Request(img_url,headers=H),timeout=60,context=ctx).read()
        if data.startswith(b'\xff\xd8\xff') or len(data)>50000:
            open(path,'wb').write(data)
            return {'id':pid,'status':'ok','bytes':len(data)}
        return {'id':pid,'status':'fail','error':'not a valid image'}
    except Exception as e:
        return {'id':pid,'status':'fail','error':str(e)[:80]}

results=[]
with ThreadPoolExecutor(max_workers=3) as ex:
    futures=[ex.submit(dl,pid) for pid in need]
    for i,fut in enumerate(as_completed(futures),1):
        r=fut.result(); results.append(r)
        if i%10==0 or i==len(need):
            ok=sum(1 for x in results if x['status'] in ('ok','existing'))
            print(f'  {i}/{len(need)} done, {ok} ok', flush=True)

ok=sum(1 for x in results if x['status'] in ('ok','existing'))
failed=[x for x in results if x['status']=='fail']
print(f'DONE: {ok}/{len(need)} downloaded, {len(failed)} failed', flush=True)
for f in failed[:15]:
    print('  FAIL',f['id'],f.get('error',''), flush=True)

# Update SOURCE.json
src=json.load(open(os.path.join(ROOT,'SOURCE.json')))
existing_ids={i['photo_id'] for i in src.get('items',[])}
for r in results:
    if r['status'] in ('ok','existing') and r['id'] not in existing_ids:
        path=os.path.join(ROOT,r['id']+'_o.jpg')
        src['items'].append({
            'photo_id':r['id'],
            'file':r['id']+'_o.jpg',
            'bytes':os.path.getsize(path),
            'url':f'https://www.flickr.com/photos/olmsted_archives/{r["id"]}',
            'photo_page':f'https://www.flickr.com/photos/olmsted_archives/{r["id"]}'
        })
src['downloaded']=len(src['items'])
json.dump(src,open(os.path.join(ROOT,'SOURCE.json'),'w'),indent=1)
print(f'SOURCE.json updated: {src["downloaded"]} items', flush=True)
