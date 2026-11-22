"""Slow Wikimedia Commons downloader - one item at a time with delays to avoid HTTP 429."""
import concurrent.futures, hashlib, json, os, pathlib, re, time, urllib.parse, urllib.request

ROOT=pathlib.Path(__file__).resolve().parent.parent / 'blueprints_and_libraries/1_blueprints/wikimedia_us_category_full'
HEADERS={'User-Agent':'ConstructionReferenceResearch/1.0 (local educational reference collection)'}

def read_json():
    return json.load(open(ROOT/'SOURCE.json'))

def write_json(d):
    (ROOT/'SOURCE.json').write_text(json.dumps(d,indent=2))

def fetch_one(item, max_attempts=3):
    path=ROOT/item['file']; temp=path.with_name(path.name+'.part')
    def valid():
        if not path.is_file() or path.stat().st_size!=item['bytes']:
            return False
        h=hashlib.sha1()
        with path.open('rb') as f:
            for chunk in iter(lambda:f.read(1024*1024),b''): h.update(chunk)
        return h.hexdigest()==item['sha1']
    try:
        if not valid():
            for attempt in range(max_attempts):
                try:
                    req=urllib.request.Request(item['url'],headers=HEADERS)
                    with urllib.request.urlopen(req,timeout=120) as src, temp.open('wb') as out:
                        while True:
                            chunk=src.read(1024*1024)
                            if not chunk: break
                            out.write(chunk)
                    os.replace(temp,path)
                    if not valid():
                        raise ValueError('size or SHA-1 mismatch')
                    break
                except urllib.error.HTTPError as e:
                    if e.code==429:
                        wait=60*(attempt+1)
                        print(f'  429 rate-limited, waiting {wait}s...', flush=True)
                        time.sleep(wait)
                        if attempt==max_attempts-1:
                            item['status']='failed'; item['error']=f'HTTP 429 after {max_attempts} attempts'
                            return item
                        continue
                    raise
                except Exception:
                    temp.unlink(missing_ok=True)
                    if attempt==max_attempts-1:
                        item['status']='failed'; item['error']=str(e)
                        return item
                    time.sleep(5*(attempt+1))
        item['status']='verified'
    except Exception as e:
        item['status']='failed'; item['error']=str(e)
    return item

if __name__=='__main__':
    d=read_json()
    items=d['items']
    failed=[x for x in items if x.get('status')!='verified']
    print(f'Retrying {len(failed)} failed items, one at a time with delays', flush=True)
    newly_verified=0
    newly_failed=0
    for i,item in enumerate(failed):
        print(f'[{i+1}/{len(failed)}] {item["title"][:70]}', flush=True)
        result=fetch_one(item)
        # Update the item in the main list
        for j,orig in enumerate(items):
            if orig['pageid']==item['pageid']:
                items[j]=result
                break
        if result['status']=='verified':
            newly_verified+=1
            print(f'  VERIFIED ({result.get("bytes",0)} bytes)', flush=True)
        else:
            newly_failed+=1
            print(f'  FAILED: {result.get("error","unknown")[:80]}', flush=True)
        write_json(d)
        # Delay between requests to avoid rate limiting
        if i < len(failed)-1:
            delay=8
            print(f'  Waiting {delay}s before next...', flush=True)
            time.sleep(delay)
    total_verified=sum(1 for x in items if x.get('status')=='verified')
    total_failed=sum(1 for x in items if x.get('status')!='verified')
    print(f'DONE: {total_verified}/{len(items)} verified, {total_failed} failed', flush=True)
    print(f'This run: {newly_verified} newly verified, {newly_failed} newly failed', flush=True)
