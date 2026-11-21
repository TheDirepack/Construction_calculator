"""Optional local training handoff for the person running the training.

python scripts_train_refiner.py --manifest prepared/manifest.json \
 --anchors circle_center,wall_attachment --out refiner.pt --epochs 20
Only labeled/visible source anchors are supervised; mask head remains unused
until exact foreground masks are provided. This file is NOT run by prep scripts.
"""
import argparse,json,torch
from pathlib import Path
from torch.utils.data import DataLoader
from blueprint_symbols.models import AnchorRefiner
from blueprint_symbols.raster_training import read_records
from blueprint_symbols.precision_dataset import PrecisionDataset,collate_precision
from blueprint_symbols.precision_training import precision_loss

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--manifest',type=Path,required=True)
    p.add_argument('--anchors',default='circle_center')
    p.add_argument('--epochs',type=int,default=20);p.add_argument('--batch-size',type=int,default=16)
    p.add_argument('--lr',type=float,default=.001);p.add_argument('--out',type=Path,default=Path('refiner.pt'))
    a=p.parse_args()
    if a.epochs<1 or a.batch_size<1:p.error('epochs and batch-size must be positive')
    mf=json.loads(a.manifest.read_text())
    names=tuple(k.strip() for k in a.anchors.split(',') if k.strip())
    if not names or len(set(names))!=len(names):p.error('unique anchor names required')
    for k in ('train','val','test'):
        if not mf.get('splits',{}).get(k):p.error('manifest missing '+k)
    train=PrecisionDataset(read_records(mf['splits']['train']),names,augment=True)
    val=PrecisionDataset(read_records(mf['splits']['val']),names,augment=False)
    if not train:p.error('no trusted anchors in training groups')
    if not val:p.error('no trusted anchors in validation groups; need different groups')
    loader=DataLoader(train,batch_size=a.batch_size,shuffle=True,num_workers=0,collate_fn=collate_precision)
    valid_loader=DataLoader(val,batch_size=a.batch_size,num_workers=0,collate_fn=collate_precision)
    device='cuda' if torch.cuda.is_available() else 'cpu'
    model=AnchorRefiner(len(names)).to(device)
    optimizer=torch.optim.AdamW(model.parameters(),lr=a.lr)
    best=float('inf')
    for epoch in range(a.epochs):
        train.set_epoch(epoch);model.train();cost=0.
        for x,target,mask,_ in loader:
            pred=model(x.to(device));loss=precision_loss(pred,target.to(device),mask.to(device))
            optimizer.zero_grad(set_to_none=True);loss.backward();optimizer.step()
            cost+=float(loss.detach().cpu())
        model.eval();vsum=0.
        with torch.no_grad():
            for x,target,mask,_ in valid_loader:
                loss=precision_loss(model(x.to(device)),target.to(device),mask.to(device))
                vsum+=float(loss.detach().cpu())
        vloss=vsum/len(valid_loader)
        print(f'epoch {epoch+1}: train_heatmap_loss={cost/len(loader):.5f} val_heatmap_loss={vloss:.5f}')
        if vloss<best:
            best=vloss
            a.out.parent.mkdir(parents=True,exist_ok=True)
            torch.save({'state_dict':model.cpu().state_dict(),'anchor_names':names,
                        'model':'AnchorRefiner','mask_head_supervised':False,
                        'note':'Only held-out anchor accuracy validates snapping precision'},a.out)
            model.to(device)
    print('Saved best validation checkpoint to',a.out)
if __name__=='__main__':main()
