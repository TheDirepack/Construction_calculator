"""Evaluate rough symbol detector on complete held-out source pages.

Predictions must already be in ORIGINAL image pixel-edge coordinates, after
cross-tile fusion. Unlike tile diagnostics, each GT symbol is counted once.

Predictions example:
{
  "predictions": [
    {"annotation_path": "/absolute/or/relative/annotation.json",
     "instances": [{"symbol_family": "receptacle",
                    "bbox_xyxy": [10.3,20.1,30.6,40.5],"confidence": 0.94}]}
  ]
}

Each val/test page absent from the predictions file is evaluated as ZERO
predictions, never silently excluded. No inference or training is performed.
"""
from __future__ import annotations
import argparse
from collections import Counter,defaultdict
import json
from pathlib import Path
import math
import statistics
from blueprint_symbols.detection_eval import _iou, _center
from blueprint_symbols.dataset_manifest import trusted_training_instance, validate_manifest_sources
from blueprint_symbols.label_schema import load_training_page


def score_full_pages(manifest_path:Path, predictions_path:Path, *, split='test',
                     confidence=.05, iou_threshold=.5) -> dict:
    if split not in ('val','test') or not 0<=confidence<=1 or not 0<iou_threshold<=1:
        raise ValueError('invalid held-out evaluation options')
    manifest=json.loads(manifest_path.read_text())
    validate_manifest_sources(manifest)
    classes=set(manifest['class_ids'])
    pages=[Path(p).resolve() for p in manifest['splits'][split]]
    if not pages:raise ValueError('empty evaluation partition')
    raw=json.loads(predictions_path.read_text())
    pred_by_page={}
    for row in raw['predictions']:
        key=Path(row['annotation_path']).resolve()
        if key in pred_by_page:raise ValueError(f'duplicate prediction page: {key}')
        pred_by_page[key]=row['instances']
    extraneous=set(pred_by_page)-set(pages)
    if extraneous:raise ValueError('predictions refer to unapproved training/other split pages')
    count=Counter();per_class=defaultdict(Counter);per_group=defaultdict(Counter)
    centers=[];center_relative=[]
    for page in pages:
        data=load_training_page(page)   # page JSON or <stem>_labels.json#p12, device classes
        if not classes.issubset(set(data.get('exhaustive_families',[]))):
            raise ValueError(f'page not annotated exhaustively for all trained classes: {page}')
        group=data.get('project_family_id')
        if group not in manifest['split_groups'][split]:
            raise ValueError('project group inconsistent with manifest')
        truths=[x for x in data['instances'] if x.get('symbol_family') in classes
                and trusted_training_instance(x)]
        pitems=sorted((x for x in pred_by_page.get(page,[]) if float(x.get('confidence',0))>=confidence),
                      key=lambda v:-float(v['confidence']))
        matched=set()
        for item in pitems:
            cls=item['symbol_family'];box=list(map(float,item['bbox_xyxy']))
            if cls not in classes or len(box)!=4 or not all(map(math.isfinite,box)) or box[2]<=box[0] or box[3]<=box[1]:
                raise ValueError(f'bad prediction: {item}')
            best=None;max_iou=iou_threshold
            for j,gt in enumerate(truths):
                if j in matched or gt['symbol_family']!=cls:continue
                ov=_iou(box,gt['bbox_xyxy'])
                if ov>=max_iou:best=j;max_iou=ov
            if best is None:
                count['fp']+=1;per_class[cls]['fp']+=1;per_group[group]['fp']+=1
            else:
                matched.add(best)
                count['tp']+=1;per_class[cls]['tp']+=1;per_group[group]['tp']+=1
                err,size=_center(box,truths[best]['bbox_xyxy'])
                centers.append(err);center_relative.append(err/max(size,1e-8))
        for j,gt in enumerate(truths):
            if j not in matched:
                count['fn']+=1;per_class[gt['symbol_family']]['fn']+=1;per_group[group]['fn']+=1
    def metrics(c):
        return {'tp':c['tp'],'fp':c['fp'],'fn':c['fn'],
                'precision':c['tp']/max(1,c['tp']+c['fp']),
                'recall':c['tp']/max(1,c['tp']+c['fn'])}
    group_recall=[c['tp']/max(1,c['tp']+c['fn']) for c in per_group.values() if c['tp']+c['fn']]
    centers.sort()
    return {'split':split,'pages':len(pages),'groups':len(per_group),
            'iou_threshold':iou_threshold,'confidence_threshold':confidence,
            'overall':metrics(count),
            'per_class':{k:metrics(v) for k,v in sorted(per_class.items())},
            'group_macro_recall':statistics.mean(group_recall) if group_recall else None,
            'center_error_px_median':statistics.median(centers) if centers else None,
            'center_error_px_p90':centers[min(len(centers)-1,math.ceil(.9*len(centers))-1)] if centers else None,
            'center_error_relative_to_gt_size_median':statistics.median(center_relative) if center_relative else None,
            'cautions':['Only manually verified or CAD-exact held-out labels support numerical accuracy.',
                        'Unlabeled drawings can provide stability/consistency tests, not true recall.',
                        'BBox midpoint is a rough detector location, not a physical snapping anchor.']}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('manifest',type=Path);p.add_argument('predictions',type=Path)
    p.add_argument('--split',choices=['val','test'],default='test')
    p.add_argument('--iou',type=float,default=.5)
    p.add_argument('--confidence',type=float,default=.05)
    p.add_argument('--out',type=Path)
    a=p.parse_args()
    result=score_full_pages(a.manifest,a.predictions,split=a.split,
                            confidence=a.confidence,iou_threshold=a.iou)
    content=json.dumps(result,indent=2)
    if a.out:
        a.out.parent.mkdir(parents=True,exist_ok=True);a.out.write_text(content)
    print(content)
if __name__=='__main__':main()
