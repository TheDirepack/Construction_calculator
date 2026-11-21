"""Training starter for annotated raster sheets (no OCR, no dataset download).

Usage:
  python scripts_train_detector.py --annotations 'dataset/**/*.json' \\
    --classes classes.json --out detector.pt --epochs 3

Classes JSON e.g. {"receptacle":0,"switch":1}. Requires real annotations;
no claim of accuracy or NPU speed is made by a successful training run.
"""
from __future__ import annotations
import argparse
from pathlib import Path
import json
import torch
from torch.utils.data import DataLoader
from blueprint_symbols.models import SymbolDetector
from blueprint_symbols.raster_training import read_records,group_split,RasterTileDataset,collate_tiles
from blueprint_symbols.training import detector_loss


def main() -> None:
    p=argparse.ArgumentParser()
    p.add_argument('--annotations',help='legacy glob; prefer --manifest for leakage-safe split')
    p.add_argument('--manifest',help='prepared manifest with train/val/test and class_ids')
    p.add_argument('--classes',help='JSON {name: consecutive_zero_based_class_id}, unnecessary with --manifest')
    p.add_argument('--out',default='detector.pt')
    p.add_argument('--epochs',type=int,default=3)
    p.add_argument('--tile-size',type=int,default=512)
    p.add_argument('--batch-size',type=int,default=4)
    p.add_argument('--samples-per-image',type=int,default=16)
    p.add_argument('--lr',type=float,default=.001)
    args=p.parse_args()
    import glob
    if args.manifest:
        manifest=json.loads(Path(args.manifest).read_text())
        if not all(manifest.get('splits',{}).get(x) for x in ('train','val','test')):
            p.error('Manifest missing train/val/test. Run scripts_prepare_manifest.py first')
        if manifest.get('verification',{}).get('training_performed') is not False:
            p.error('Expected a preparation-only manifest')
        train=read_records([Path(x) for x in manifest['splits']['train']])
        val=read_records([Path(x) for x in manifest['splits']['val']])
        classes=manifest['class_ids']
        if args.classes and json.loads(Path(args.classes).read_text()) != classes:
            p.error('--classes differs from manifest class IDs')
    else:
        if not args.annotations or not args.classes:
            p.error('--manifest recommended, or provide --annotations AND --classes')
        ann=[Path(x) for x in sorted(glob.glob(args.annotations,recursive=True))]
        if not ann:
            p.error('No annotations matched; dataset is not present')
        print('WARNING: legacy auto split has not verified original-project grouping/alignment')
        train,val=group_split(read_records(ann))
        classes=json.loads(Path(args.classes).read_text())
    if sorted(classes.values()) != list(range(len(classes))):
        p.error('classes must be distinct consecutive IDs beginning with 0')
    if not train:
        p.error('Group split yielded no train records')
    device='cuda' if torch.cuda.is_available() else 'cpu'
    loader=DataLoader(RasterTileDataset(train,classes,size=args.tile_size,
                                       virtual_tiles_per_image=args.samples_per_image),
                      batch_size=args.batch_size,shuffle=True,num_workers=0,
                      collate_fn=collate_tiles)
    model=SymbolDetector(len(classes)).to(device)
    opt=torch.optim.AdamW(model.parameters(),lr=args.lr)
    for epoch in range(args.epochs):
        model.train()
        total=0.
        collisions=0
        assignments=0
        for images,gt,metas in loader:
            pred=model(images.to(device))
            loss,stats=detector_loss(pred,gt,(args.tile_size,args.tile_size),
                                     ignore_boxes=[m['ignore_boxes'] for m in metas])
            opt.zero_grad(set_to_none=True)
            loss.backward()
            opt.step()
            total+=float(loss.detach().cpu())
            collisions+=stats['collisions']
            assignments+=stats['assigned']
        print(f'epoch {epoch+1}: mean_train_loss={total/len(loader):.5f} '
              f'assigned={assignments} collisions={collisions}; holdout_groups={len(val)}')
    torch.save(model.cpu().state_dict(),args.out)
    print('Checkpoint saved:',args.out,'(unvalidated accuracy; evaluate on held-out complete drawings)')

if __name__=='__main__':
    main()
