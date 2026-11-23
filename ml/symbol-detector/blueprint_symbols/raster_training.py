"""Training tiles from arbitrary-size raster drawings and existing annotations.

Never resize entire source sheets to the model size. Randomly sample coordinate-
tracked crops with scale jitter. Dataset images may have any aspect ratio and
resolution. Existing categories/anchors travel through affine transforms.
"""
from __future__ import annotations
from dataclasses import dataclass
from collections import Counter
from pathlib import Path
import hashlib
import json
import math
import cv2
import numpy as np
try:  # Only the training dataset needs PyTorch; reading and splitting records does not.
    import torch
    from torch.utils.data import Dataset
except ImportError:
    torch = None
    Dataset = object
from .multiscale import ScaleTile, sample_tile
from .augmentation import photometric
from .degradation import degrade, choose_profile


@dataclass(frozen=True)
class RasterRecord:
    image: Path
    group: str
    width: int
    height: int
    instances: list[dict]
    wall_only_background_verified: bool = False
    exhaustive_families: frozenset[str] = frozenset()
    source_type: str = 'unknown'
    verified_no_symbols: bool = False


def read_records(annotation_paths: list[str | Path], *, classes: dict[str, int] | None = None) -> list[RasterRecord]:
    records=[]
    for annotation in annotation_paths:
        path = Path(annotation)
        data = json.loads(path.read_text())
        image = (path.parent / data['image_path']).resolve()
        if not image.is_file():
            raise FileNotFoundError(image)
        w, h = int(data['image_width']), int(data['image_height'])
        if w <= 0 or h <= 0:
            raise ValueError(f'invalid dimensions: {path}')
        negative=data.get('verified_no_symbols',False)
        if type(negative) is not bool:
            raise ValueError(f'verified_no_symbols must be boolean: {path}')
        if negative and data['instances']:
            raise ValueError(f'verified_no_symbols page contains active annotations: {path}')
        exhaustive=set(data.get('exhaustive_families', []))
        if negative and classes is not None:
            exhaustive.update(classes)
        record=RasterRecord(image, str(data.get('project_family_id',data.get('source_id',path.stem))),
                            w,h,data['instances'],
                            bool(data.get('wall_only_background_verified', False) and
                                 data.get('synthetic_targets_only', False)),
                            frozenset(exhaustive),
                            str(data.get('source_type', 'unknown')),
                            negative)
        records.append(record)
    return records


def group_split(records: list[RasterRecord], *, val_fraction: float = .15) -> tuple[list[RasterRecord],list[RasterRecord]]:
    """Stable group-based split; no random tile leakage from one source plan."""
    if not 0 < val_fraction < 1:
        raise ValueError('val_fraction must be between 0 and 1')
    train,val=[],[]
    for rec in records:
        unit=int.from_bytes(hashlib.sha256(rec.group.encode()).digest()[:8],'big')/(1<<64)
        (val if unit < val_fraction else train).append(rec)
    return train,val


class RasterTileDataset(Dataset):
    def __init__(self, records: list[RasterRecord], classes: dict[str,int], *,
                 size: int = 512, virtual_tiles_per_image: int = 32,
                 source_scales: tuple[float,float] = (.5,3.), seed: int = 1,
                 augment_photometric: bool = False,
                 target_symbol_pixels: tuple[tuple[int,int,float],...] | None = None,
                 degradation_profile: str | None = None,
                 class_balance_exponent: float = .5,
                 cache_max_mb: int = 128):
        if not records or not classes or virtual_tiles_per_image <= 0 or size < 32:
            raise ValueError('invalid dataset parameters')
        if min(source_scales) <= 0 or source_scales[1] < source_scales[0]:
            raise ValueError('invalid scale range')
        self.records=records
        self.cache_max_bytes=max(0,int(cache_max_mb))*(1<<20)
        self._cached_image_key=None
        self._cached_image=None
        self.classes=classes
        self.size=size
        self.samples=virtual_tiles_per_image
        self.scales=source_scales
        self.seed=seed
        self.epoch=0
        self.augment_photometric=bool(augment_photometric)
        if target_symbol_pixels is not None:
            if not target_symbol_pixels or any(lo <= 0 or hi < lo or weight <= 0
                for lo,hi,weight in target_symbol_pixels):
                raise ValueError('bad target symbol pixel bands')
        self.target_symbol_pixels = target_symbol_pixels
        self.degradation_profile = degradation_profile
        if not 0 <= class_balance_exponent <= 1:
            raise ValueError('class_balance_exponent must be in [0,1]')
        counts=Counter(inst.get('symbol_family') for rec in records for inst in rec.instances
                       if inst.get('symbol_family') in classes and not inst.get('uncertain',False))
        self.class_sampling_weights={cls: max(1,n)**(-class_balance_exponent)
                                     for cls,n in counts.items()}

    def __len__(self) -> int:
        return len(self.records)*self.samples

    def set_epoch(self, epoch: int) -> None:
        self.epoch=int(epoch)

    def __getitem__(self, index: int) -> tuple[torch.Tensor, list[tuple[int,tuple[float,float,float,float]]], dict]:
        if torch is None:
            raise ImportError('training tensors need PyTorch: pip install -r requirements-train.txt')
        rgb,targets,info=self.sample(index)
        return torch.from_numpy(np.ascontiguousarray(rgb.transpose(2,0,1))).float().div_(255),targets,info

    def sample(self, index: int) -> tuple[np.ndarray, list[tuple[int,tuple[float,float,float,float]]], dict]:
        """One tile as uint8 RGB (height, width, 3); data export uses this without PyTorch."""
        # Export consecutive tiles from the same source. Reading an 8K raster
        # from disk for every tile is unnecessarily expensive.
        rec=self.records[index // self.samples]
        # Stable single-worker reproducibility. Multiprocessing workers can
        # create distinct index subsets without sharing a mutable RNG state.
        rng=np.random.default_rng(np.random.SeedSequence([self.seed,self.epoch,index]))
        if self._cached_image_key==rec.image:
            img=self._cached_image
        else:
            img=cv2.imread(str(rec.image),cv2.IMREAD_COLOR)
            if img is None or img.shape[:2] != (rec.height,rec.width):
                raise ValueError(f'image missing or different size: {rec.image}')
            self._cached_image_key=rec.image if img.nbytes<=self.cache_max_bytes else None
            self._cached_image=img if self._cached_image_key is not None else None
        eligible=[v for v in rec.instances if v.get('symbol_family') in self.classes
                  and not v.get('uncertain',False)]
        picked=None
        if eligible and rng.random() < .85:
            weight=np.asarray([self.class_sampling_weights.get(v.get('symbol_family'),1.)
                               for v in eligible],dtype=np.float64)
            weight/=weight.sum()
            picked=eligible[int(rng.choice(len(eligible),p=weight))]
        target_px=None
        if picked is not None and self.target_symbol_pixels:
            weight=np.array([v[2] for v in self.target_symbol_pixels],float)
            band=int(rng.choice(len(weight),p=weight/weight.sum()))
            lo,hi,_=self.target_symbol_pixels[band]
            target_px=float(rng.uniform(lo,hi))
            a,b,c,d=map(float,picked['bbox_xyxy'])
            extent=max(c-a,d-b)
            s=float(extent/target_px)
            s=max(.15,min(32.,s))
        else:
            s=float(np.exp(rng.uniform(np.log(self.scales[0]),np.log(self.scales[1]))))
        span=self.size*s
        if picked is not None:
            a,b,c,d=map(float,picked['bbox_xyxy'])
            cx,cy=(a+c)/2,(b+d)/2
            x0=cx-rng.uniform(.2,.8)*span
            y0=cy-rng.uniform(.2,.8)*span
        else:
            x0=float(rng.uniform(0,max(1,rec.width-span)))
            y0=float(rng.uniform(0,max(1,rec.height-span)))
        x0=float(np.clip(x0,0,max(0,rec.width-span)))
        y0=float(np.clip(y0,0,max(0,rec.height-span)))
        tile=ScaleTile(x0,y0,s,self.size,rec.width,rec.height)
        patch=sample_tile(img,tile)
        damage_info=None
        if self.degradation_profile:
            profile = (choose_profile(rng) if self.degradation_profile == 'mixed'
                       else self.degradation_profile)
            patch,damage=degrade(patch,rng,profile=profile)
            damage_info=damage.to_dict()
        elif self.augment_photometric:
            patch=photometric(patch,rng)
        # BGR -> RGB normalized float for PyTorch.
        rgb=cv2.cvtColor(patch,cv2.COLOR_BGR2RGB)
        targets=[]
        ignore=[]
        for inst in rec.instances:
            a,b,c,d=map(float,inst['bbox_xyxy'])
            u0,v0=(a-x0)/s,(b-y0)/s
            u1,v1=(c-x0)/s,(d-y0)/s
            cx0,cy0=max(0,u0),max(0,v0)
            cx1,cy1=min(self.size,u1),min(self.size,v1)
            if cx1<=cx0 or cy1<=cy0:
                continue
            coverage=(cx1-cx0)*(cy1-cy0)/((u1-u0)*(v1-v0))
            box=(cx0,cy0,cx1,cy1)
            if coverage >= .98 and inst.get('symbol_family') in self.classes and not inst.get('uncertain',False):
                targets.append((self.classes[inst['symbol_family']],box))
            else:
                # Never mislabel a clipped/uncertain component as background.
                ignore.append(box)
        info={'tile':tile, 'ignore_boxes':ignore, 'group':rec.group,
              'verified_wall_background':rec.wall_only_background_verified,
              'verified_page_negative':rec.verified_no_symbols,
              'exhaustive_families':sorted(rec.exhaustive_families),
              'source_type':rec.source_type,
              'image_path':str(rec.image), 'target_symbol_pixels':target_px,
              'damage':damage_info}
        return np.ascontiguousarray(rgb),targets,info


def collate_tiles(batch):
    images, labels, infos = zip(*batch)
    return torch.stack(images),list(labels),list(infos)
