"""Keypoint training data from externally supervised exact source anchors.

No assumed wall snap labels. Every target requires visible=True and
annotated=True. Supports varying input page sizes via fixed-size local crops.
"""
from __future__ import annotations
from pathlib import Path
import cv2,numpy as np,torch
from torch.utils.data import Dataset
from .raster_training import RasterRecord
from .precision_training import jittered_precision_example
from .augmentation import photometric

class PrecisionDataset(Dataset):
    def __init__(self,records:list[RasterRecord],names:tuple[str,...],*,seed:int=19,
                 patch_size:int=160,augment:bool=False):
        if not names or len(set(names))!=len(names):raise ValueError('anchor names required, no duplicates')
        self.records=records;self.names=names;self.seed=seed
        self.patch_size=patch_size;self.augment=augment;self.epoch=0
        self.eligible=[]
        for ri,rec in enumerate(records):
            for ii,inst in enumerate(rec.instances):
                if inst.get('uncertain',False):continue
                if any((inst.get('anchors',{}).get(k) or {}).get('annotated',False)
                       and (inst.get('anchors',{}).get(k) or {}).get('visible',False)
                       for k in names):self.eligible.append((ri,ii))
    def __len__(self):return len(self.eligible)
    def set_epoch(self,epoch:int):self.epoch=int(epoch)
    def __getitem__(self,i):
        ri,ii=self.eligible[i]; rec=self.records[ri]; inst=rec.instances[ii]
        image=cv2.imread(str(rec.image),cv2.IMREAD_COLOR)
        if image is None or image.shape[:2]!=(rec.height,rec.width):
            raise ValueError(f'bad source raster: {rec.image}')
        seed=int(np.random.SeedSequence([self.seed,self.epoch,i]).generate_state(1)[0])
        patch,targets,valid,transform=jittered_precision_example(image,inst,self.names,seed=seed,
                                                                     patch_size=self.patch_size)
        if self.augment:
            rgb=(patch.numpy().transpose(1,2,0)*255).astype(np.uint8)
            rgb=photometric(rgb,np.random.default_rng(seed+1))
            patch=torch.from_numpy(np.ascontiguousarray(rgb.transpose(2,0,1))).float()/255
        return patch,targets,valid, {'project_group':rec.group,'instance_id':inst.get('instance_id'),
                                     'crop_origin':[transform.origin_x,transform.origin_y]}

def collate_precision(items):
    x,y,v,m=zip(*items)
    return torch.stack(x),torch.stack(y),torch.stack(v),list(m)
