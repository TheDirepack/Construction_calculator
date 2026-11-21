"""Preflight prepared training data: no model, no optimization, no checkpoint.

python scripts_preflight_training.py manifest.json --samples 3
"""
import argparse,json
from pathlib import Path
from collections import Counter
from blueprint_symbols.raster_training import read_records,RasterTileDataset

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('manifest',type=Path);p.add_argument('--samples',type=int,default=4)
    a=p.parse_args(); manifest=json.loads(a.manifest.read_text())
    if not all(manifest.get('splits',{}).get(k) for k in ('train','val','test')):
        p.error('manifest requires nonempty train/val/test')
    if not manifest.get('class_ids'):p.error('no classes')
    report={'records':{},'classes':manifest['class_ids'],'sampled_tiles':0,
            'sample_targets':0,'sample_ignored':0,'warnings':manifest.get('warnings',[])}
    groups={}
    for split in ('train','val','test'):
        records=read_records(manifest['splits'][split]);report['records'][split]=len(records)
        for record in records:
            if record.group in groups and groups[record.group]!=split:
                p.error(f'project group {record.group} leaks between {split} and {groups[record.group]}')
            groups[record.group]=split
        if split=='train':
            ds=RasterTileDataset(records,manifest['class_ids'],virtual_tiles_per_image=2,seed=42)
            for i in range(min(a.samples,len(ds))):
                image,gt,meta=ds[i]
                if tuple(image.shape)!=(3,512,512):p.error('bad model input shape')
                report['sampled_tiles']+=1;report['sample_targets']+=len(gt)
                report['sample_ignored']+=len(meta['ignore_boxes'])
    print(json.dumps(report,indent=2))
if __name__=='__main__':main()
