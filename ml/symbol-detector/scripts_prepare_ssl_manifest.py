"""Create a split-safe blueprint-only SSL image manifest. DOES NOT TRAIN.

python scripts_prepare_ssl_manifest.py prepared/source_manifest.json pretrain_sources.json \\
    --unlabeled additional_unlabeled.json --preview-dir ssl_preview

Unlabeled JSON is an array of {image_path, project_family_id,
source_group_verified:true}. For every source, IDs identify ORIGINAL projects;
held-out projects cannot enter pretraining, even via damaged copies.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import cv2
from blueprint_symbols.ssl_data import build_ssl_sources, SSLViewDataset


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('source_manifest',type=Path)
    p.add_argument('output',type=Path)
    p.add_argument('--unlabeled',type=Path,action='append',
                   help='repeat for multiple original-project image lists')
    p.add_argument('--preview-dir',type=Path)
    p.add_argument('--preview-count',type=int,default=3)
    args=p.parse_args()
    source=json.loads(args.source_manifest.read_text())
    unlab=[]
    for fn in args.unlabeled or []:
        records=json.loads(fn.read_text())
        if not isinstance(records,list):
            p.error(f'unlabeled JSON must be an array: {fn}')
        for item in records:
            row=dict(item)
            if not Path(row['image_path']).is_absolute():
                row['image_path']=str((fn.parent/row['image_path']).resolve())
            unlab.append(row)
    result=build_ssl_sources(source,unlab)
    if args.output.exists():
        p.error('refusing to overwrite existing SSL manifest')
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result,indent=2))
    if args.preview_dir:
        if args.preview_dir.exists() and any(args.preview_dir.iterdir()):
            p.error('refusing to overwrite preview directory')
        args.preview_dir.mkdir(parents=True,exist_ok=True)
        ds=SSLViewDataset(result,items_per_epoch=max(3,args.preview_count))
        for index in range(args.preview_count):
            clean,damaged,meta=ds[index]
            if not cv2.imwrite(str(args.preview_dir/f'{index:03d}_clean.png'),clean):
                raise OSError('failed to write SSL preview')
            if not cv2.imwrite(str(args.preview_dir/f'{index:03d}_input.png'),damaged):
                raise OSError('failed to write SSL preview')
            (args.preview_dir/f'{index:03d}_meta.json').write_text(json.dumps(meta,indent=2))
    print(json.dumps({'eligible_sources':len(result['sources']),
                      'unlabeled_sources':sum(x['kind']=='unlabeled_training' for x in result['sources']),
                      'model_training_performed':False},indent=2))

if __name__=='__main__':main()
