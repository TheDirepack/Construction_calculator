"""Prepare audited train/val/test manifests from imported per-page annotations.

python scripts_prepare_manifest.py 'converted/**/*.json' output/manifest.json
Inputs must have VERIFIED original-project grouping, not filenames/tiles.
Does not train.
"""
import argparse,glob,json
from pathlib import Path
from blueprint_symbols.dataset_manifest import build_manifest

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('glob');p.add_argument('out',type=Path)
    p.add_argument('--val-fraction',type=float,default=.15)
    p.add_argument('--test-fraction',type=float,default=.15)
    args=p.parse_args()
    files=[]
    for file in glob.glob(args.glob,recursive=True):
        try:
            data=json.loads(Path(file).read_text())
        except (OSError,ValueError):continue
        if isinstance(data,dict) and isinstance(data.get('instances'),list):files.append(Path(file))
    manifest=build_manifest(files,val_fraction=args.val_fraction,test_fraction=args.test_fraction)
    args.out.parent.mkdir(parents=True,exist_ok=True)
    args.out.write_text(json.dumps(manifest,indent=2))
    print(f"{len(manifest['source_images'])} images, {len(manifest['class_ids'])} train classes, "
          f"split sizes: { {k:len(v) for k,v in manifest['splits'].items()} }")
    if manifest['warnings']:print('WARNINGS:\n'+'\n'.join(manifest['warnings']))
if __name__=='__main__':main()
