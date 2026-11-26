"""Generate UNTRUSTED multi-scale YOLO teacher candidates from unlabeled pages.

Requires an existing fine-tuned local Ultralytics weight file and the user's
verified project group IDs; NEVER runs training or downloads model weights.

python scripts_propose_labels.py --manifest prepared/manifest.json \
  --unlabeled unlabeled.json --weights local_teacher.pt --output-dir suggestions

Output can be browsed/reviewed. It must not be merged into training truth as-is.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import cv2
import numpy as np
from blueprint_symbols.pseudolabel import verify_unlabeled_sources, consolidate_predictions
from blueprint_symbols.multiscale import plan_pyramid_tiles,sample_tile,touches_inner_edge


def propose(model, image:np.ndarray, class_ids:dict[str,int], *, scales=(.75,1.,1.5),
            confidence=.2, tile_size=512, overlap=160, device='cpu',
            teacher_class_map: dict[str,str] | None = None) -> tuple[list[dict],dict]:
    h,w=image.shape[:2]
    model_names={int(k):str(v) for k,v in model.names.items()}
    expected={id:name for name,id in class_ids.items()}
    if teacher_class_map is None:
        if model_names!=expected:
            raise ValueError(f'teacher class mapping differs from labeled manifest! weights={model_names}, manifest={expected}; '
                             'provide an explicit --teacher-class-map for a blueprint specialist checkpoint')
        recognized=expected
    else:
        if not isinstance(teacher_class_map,dict):
            raise ValueError('teacher class map must be a JSON object')
        unknown=set(teacher_class_map.values())-set(class_ids)
        if unknown:
            raise ValueError(f'teacher map refers to classes not in training taxonomy: {sorted(unknown)}')
        recognized={idx:teacher_class_map[name] for idx,name in model_names.items()
                    if name in teacher_class_map}
        if not recognized:
            raise ValueError('no model classes explicitly mapped to the blueprint taxonomy')
    raw=[]
    tiles=plan_pyramid_tiles(w,h,tile_size=tile_size,overlap=overlap,source_scales=scales)
    for tile in tiles:
        patch=sample_tile(image,tile)
        prediction=model.predict(source=patch,conf=confidence,device=device,imgsz=tile_size,verbose=False)[0]
        if prediction.boxes is None:continue
        xyxy=prediction.boxes.xyxy.cpu().numpy()
        classes=prediction.boxes.cls.cpu().numpy().astype(int)
        scores=prediction.boxes.conf.cpu().numpy()
        for b,c,s in zip(xyxy,classes,scores):
            if int(c) not in recognized:
                continue  # Never convert an unrelated specialist category to background.
            if touches_inner_edge(tile,tuple(float(v) for v in b)):
                continue  # cut by this tile's edge; an overlapping tile sees it whole
            bb=list(tile.to_page_box(tuple(float(v) for v in b)))
            bb=[max(0.,min(w,bb[0])),max(0.,min(h,bb[1])),max(0.,min(w,bb[2])),max(0.,min(h,bb[3]))]
            if bb[2]<=bb[0] or bb[3]<=bb[1]:continue
            raw.append({'symbol_family':recognized[int(c)],'bbox_xyxy':bb,'confidence':float(s),
                        'source_scale':float(tile.source_scale)})
    return consolidate_predictions(raw,min_confidence=confidence),{'tile_count':len(tiles),'raw_predictions':len(raw)}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--manifest',type=Path,required=True)
    p.add_argument('--unlabeled',type=Path,required=True,help='JSON array: {image_path,project_family_id,source_group_verified:true}')
    p.add_argument('--weights',type=Path,required=True,help='already fine-tuned teacher weights on local disk')
    p.add_argument('--output-dir',type=Path,required=True)
    p.add_argument('--device',default='cpu')
    p.add_argument('--teacher-class-map',type=Path,
                   help='JSON map from teacher checkpoint class names to canonical blueprint class names; '
                        'required when using a specialist model with a different class vocabulary')
    p.add_argument('--scales',default='.75,1.0,1.5')
    p.add_argument('--conf',type=float,default=.2)
    args=p.parse_args()
    mf=json.loads(args.manifest.read_text())
    items=json.loads(args.unlabeled.read_text())
    if not isinstance(items,list):p.error('--unlabeled should be a JSON array')
    sources=verify_unlabeled_sources(items,mf)
    if not args.weights.is_file():p.error('teacher checkpoint missing; never auto-download unknown weights')
    if args.output_dir.exists() and any(args.output_dir.iterdir()):p.error('refusing to overwrite nonempty output dir')
    try:from ultralytics import YOLO
    except ImportError:p.error('pip install ultralytics is needed for this OPTIONAL teacher stage')
    model=YOLO(str(args.weights))
    teacher_map=json.loads(args.teacher_class_map.read_text()) if args.teacher_class_map else None
    args.output_dir.mkdir(parents=True,exist_ok=True)
    summary=[]
    for index,source in enumerate(sources):
        img=cv2.imread(source['image_path'],cv2.IMREAD_COLOR)
        if img is None:raise ValueError(f'cannot decode {source["image_path"]}')
        suggestions,stats=propose(model,img,mf['class_ids'],device=args.device,confidence=args.conf,
                                  scales=tuple(float(v) for v in args.scales.split(',')),
                                  teacher_class_map=teacher_map)
        record={'format_version':1,**source,'image_width':img.shape[1],'image_height':img.shape[0],
                'instances':suggestions,'pseudo_labels_are_ground_truth':False,
                'requires_review':True,'teacher_checkpoint':str(args.weights.resolve()),
                'teacher_class_map':teacher_map,'stats':stats}
        (args.output_dir/f'candidate_{index:06d}.json').write_text(json.dumps(record,indent=2))
        summary.append({'source':source['image_path'],'candidates':len(suggestions),**stats})
    (args.output_dir/'summary.json').write_text(json.dumps(summary,indent=2))
    print(json.dumps({'pages':len(sources),'candidates':sum(x['candidates'] for x in summary)},indent=2))
if __name__=='__main__':main()
