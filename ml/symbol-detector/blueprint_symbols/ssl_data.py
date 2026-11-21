"""Source-isolated paired views for optional blueprint-only self-supervision.

This is a *data* component, not a pretraining model or training runner. The
clean/source crop and a degraded crop share exactly the same coordinates.
Only train-partition images and separately verified non-held-out unlabeled
sources are eligible. Crop and damage are reproducible from seed/index.
"""
from __future__ import annotations

from pathlib import Path
import hashlib
import json
import math
import cv2
import numpy as np
from .multiscale import ScaleTile, sample_tile
from .variants import make_variant
from .pseudolabel import verify_unlabeled_sources


def build_ssl_sources(manifest: dict, unlabeled: list[dict] | None = None) -> dict:
    if not all(manifest.get('splits', {}).get(k) for k in ('train', 'val', 'test')):
        raise ValueError('train/val/test source manifest is required')
    approved_train=set(manifest['split_groups']['train'])
    source_rows=[]
    seen_hashes=set()
    for row in manifest['source_images']:
        if row['project_group'] not in approved_train:
            continue
        h=row['image_sha256']
        if h in seen_hashes:
            continue
        seen_hashes.add(h)
        source_rows.append({'image_path':row['image'],'group':row['project_group'],
                            'image_sha256':h,'annotation':row['annotation'],
                            'kind':'labeled_training'})
    for row in verify_unlabeled_sources(unlabeled or [],manifest):
        if row['image_sha256'] in seen_hashes:
            continue
        seen_hashes.add(row['image_sha256'])
        source_rows.append({'image_path':row['image_path'],'group':row['project_family_id'],
                            'image_sha256':row['image_sha256'], 'annotation':None,
                            'kind':'unlabeled_training'})
    if not source_rows:
        raise ValueError('no eligible pretraining images')
    source_rows.sort(key=lambda x:(x['kind'],x['group'],x['image_path']))
    return {'format_version':1,'purpose':'blueprint_only_self_supervised_data',
            'training_performed':False,'heldout_sources_included':False,
            'source_manifest_hash':hashlib.sha256(json.dumps(manifest,sort_keys=True).encode()).hexdigest(),
            'sources':source_rows}


class SSLViewDataset:
    """Lazy, deterministic clean/degraded crop pairs, arbitrary source sizes.

    Randomized per index and epoch; no pre-generated image copies required.
    Call set_epoch() between training epochs when an actual trainer is built.
    Returns (BGR clean uint8, BGR degraded uint8, metadata), not tensors.
    """
    def __init__(self, ssl_manifest: dict, *, size: int = 512, items_per_epoch: int = 8192,
                 seed: int = 2026, labeled_fraction: float = .4):
        if size < 64 or size % 32 or items_per_epoch < 1 or not 0<=labeled_fraction<=1:
            raise ValueError('invalid SSL data sampling options')
        self.sources=ssl_manifest['sources']
        self.labeled=[x for x in self.sources if x['kind']=='labeled_training']
        self.unlabeled=[x for x in self.sources if x['kind']=='unlabeled_training']
        if not self.sources:
            raise ValueError('empty SSL manifest')
        self.size=size
        self.items_per_epoch=items_per_epoch
        self.seed=seed
        self.epoch=0
        self.labeled_fraction=labeled_fraction
        self._cache_path=None
        self._cache_image=None
        self._cached_annotation={}

    def __len__(self):
        return self.items_per_epoch

    def set_epoch(self, epoch: int):
        self.epoch=int(epoch)

    def __getitem__(self, i: int):
        if not 0<=i<len(self):
            raise IndexError(i)
        rng=np.random.default_rng(np.random.SeedSequence([self.seed,self.epoch,i]))
        if self.labeled and self.unlabeled:
            pool=self.labeled if rng.random()<self.labeled_fraction else self.unlabeled
        else:
            pool=self.labeled or self.unlabeled
        source=pool[int(rng.integers(len(pool)))]
        path=source['image_path']
        if self._cache_path==path:
            image=self._cache_image
        else:
            image=cv2.imread(str(path),cv2.IMREAD_COLOR)
            if image is None:
                raise ValueError(f'cannot decode SSL training source: {path}')
            # Keep a single bounded image, not an entire full-resolution corpus.
            self._cache_path=path if image.nbytes<=128*(1<<20) else None
            self._cache_image=image if self._cache_path else None
        H,W=image.shape[:2]
        scale=float(np.exp(rng.uniform(math.log(.5),math.log(3.))))
        target=None
        if source['annotation'] and rng.random()<.65:
            if source['annotation'] not in self._cached_annotation:
                self._cached_annotation[source['annotation']]=json.loads(Path(source['annotation']).read_text())
            possible=[v for v in self._cached_annotation[source['annotation']]['instances']
                      if not v.get('uncertain',False) and 'bbox_xyxy' in v]
            if possible:
                item=possible[int(rng.integers(len(possible)))]
                a,b,c,d=map(float,item['bbox_xyxy'])
                size_px=float(rng.uniform(16,80))
                scale=float(np.clip(max(c-a,d-b)/size_px,.15,32.))
                target=((a+c)/2,(b+d)/2)
        span=self.size*scale
        if target is None:
            x=float(rng.uniform(0,max(0,W-span))) if W>span else 0.
            y=float(rng.uniform(0,max(0,H-span))) if H>span else 0.
        else:
            x=float(np.clip(target[0]-rng.uniform(.25,.75)*span,0,max(0,W-span)))
            y=float(np.clip(target[1]-rng.uniform(.25,.75)*span,0,max(0,H-span)))
        tile=ScaleTile(x,y,scale,self.size,W,H)
        clean=sample_tile(image,tile)
        profile=('lowres_random' if i%3==1 else 'mixed_random' if i%3==2 else 'clean')
        damaged,details=make_variant(clean,rng,profile)
        return clean,damaged,{'source':path,'group':source['group'],'source_type':source['kind'],
                              'source_box':list(tile.source_box),'source_scale':scale,
                              'variant':details,'epoch':self.epoch,'index':i}
