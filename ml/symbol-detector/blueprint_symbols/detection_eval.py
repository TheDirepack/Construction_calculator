"""Honest tile-level YOLO detection diagnostics, including center precision.

Tile evaluation is NOT a replacement for full-page inference + cross-tile fusion:
overlapping tiles duplicate targets and artificially inflate instance counts.
No training is performed in this module.
"""
from __future__ import annotations
from collections import Counter, defaultdict
from pathlib import Path
import json
import math
import statistics


def _boxes(path:Path, *, predictions:bool) -> list[tuple[int,tuple[float,float,float,float],float]]:
    if not path.is_file():
        if predictions:return []  # Missing prediction file => zero predictions.
        raise FileNotFoundError(path)
    out=[]
    for line in path.read_text().splitlines():
        if not line.strip():continue
        v=line.split()
        if len(v) != (6 if predictions else 5):
            raise ValueError(f'bad YOLO row {path}: {line}')
        c=int(v[0]); cx,cy,w,h=(float(x) for x in v[1:5])
        conf=float(v[5]) if predictions else 1.
        if c<0 or not 0 <= conf <= 1 or not all(math.isfinite(x) for x in (cx,cy,w,h,conf)):
            raise ValueError(f'bad YOLO row {path}: {line}')
        if not (0<=cx<=1 and 0<=cy<=1 and 0<w<=1 and 0<h<=1):
            raise ValueError(f'normalized YOLO out of range in {path}: {line}')
        out.append((c,((cx-w/2)*512,(cy-h/2)*512,(cx+w/2)*512,(cy+h/2)*512),conf))
    return out


def _iou(a,b):
    x0=max(a[0],b[0]);y0=max(a[1],b[1]);x1=min(a[2],b[2]);y1=min(a[3],b[3])
    inter=max(0.,x1-x0)*max(0.,y1-y0)
    sa=(a[2]-a[0])*(a[3]-a[1]);sb=(b[2]-b[0])*(b[3]-b[1])
    return inter/max(1e-12, sa+sb-inter)


def _center(a,b):
    return (math.hypot((a[0]+a[2]-b[0]-b[2])/2,(a[1]+a[3]-b[1]-b[3])/2),
            max(a[2]-a[0],a[3]-a[1]))


def _size_band(box):
    w=max(box[2]-box[0],box[3]-box[1])
    if w<16:return '<16px'
    if w<24:return '16-23px'
    if w<40:return '24-39px'
    if w<72:return '40-71px'
    return '72px+'


def score(prepared:Path, prediction_root:Path, *, split='val', iou_threshold=.5,
          confidence_threshold=.05) -> dict:
    if not split.startswith(('val','test')) or not 0 < iou_threshold <= 1:
        raise ValueError('must score held-out val/test and valid IoU')
    meta=[json.loads(x) for x in (prepared/'sample_provenance.jsonl').read_text().splitlines()]
    selection=[x for x in meta if x['split']==split]
    if not selection:raise ValueError(f'no tiles found for {split}')
    ids=json.loads((prepared/'export_report.json').read_text())['classes']
    classes={v:k for k,v in ids.items()}
    counts=defaultdict(Counter); errors=defaultdict(list); normalized=defaultdict(list)
    groups=defaultdict(Counter)
    for item in selection:
        sample=item['sample']; grp=item['group']
        true=_boxes(prepared/'labels'/split/f'{sample}.txt',predictions=False)
        pred=_boxes(prediction_root/split/f'{sample}.txt',predictions=True)
        pred=sorted((p for p in pred if p[2]>=confidence_threshold),key=lambda p:-p[2])
        matched=set()
        for cls,box,confidence in pred:
            best=None;biou=iou_threshold
            for idx,(tc,tbox,_) in enumerate(true):
                if idx in matched or tc!=cls:continue
                cur=_iou(box,tbox)
                if cur>=biou:
                    best=idx;biou=cur
            cname=classes.get(cls,f'unknown:{cls}')
            if best is None:
                counts['all']['fp']+=1;counts[f'class:{cname}']['fp']+=1
                groups[grp]['fp']+=1
            else:
                matched.add(best)
                d,scale=_center(box,true[best][1])
                for key in ('all', f'class:{cname}',f'size:{_size_band(true[best][1])}'):
                    counts[key]['tp']+=1;errors[key].append(d);normalized[key].append(d/scale)
                groups[grp]['tp']+=1
        for j,(cls,tbox,_) in enumerate(true):
            if j in matched:continue
            cname=classes.get(cls,f'unknown:{cls}')
            for key in ('all',f'class:{cname}',f'size:{_size_band(tbox)}'):
                counts[key]['fn']+=1
            groups[grp]['fn']+=1
    result={}
    for key,c in sorted(counts.items()):
        tp,fp,fn=c['tp'],c['fp'],c['fn']
        center=sorted(errors[key]);ncenter=sorted(normalized[key]);
        result[key]={"tp":tp,"fp":fp,"fn":fn,"precision":tp/max(1,tp+fp),
                     "recall":tp/max(1,tp+fn),
                     "median_center_error_px":statistics.median(center) if center else None,
                     "p90_center_error_px":center[min(len(center)-1,math.ceil(.9*len(center))-1)] if center else None,
                     "median_center_error_fraction_of_symbol":statistics.median(ncenter) if ncenter else None}
    # Macro across independent building/drawing groups, not correlated tiles.
    macro=[]
    for gc in groups.values():
        if gc['tp']+gc['fn']:macro.append(gc['tp']/(gc['tp']+gc['fn']))
    return {'split':split,'tile_count':len(selection),'independent_source_groups':len(groups),
            'iou_threshold':iou_threshold,'confidence_threshold':confidence_threshold,
            'metrics':result,'mean_group_recall':sum(macro)/len(macro) if macro else None,
            'warning':'Tile metrics double-count overlapping source instances. Use full-page fusion and independent real drawings for final quality claims.'}
