"""Run a user-trained local detector on every held-out source drawing.

Generates predictions for scripts_score_full_pages.py, mapping all tiled
boxes back to original raster pixels and consolidating overlapping results.
No training, weight downloads, or general-purpose models are involved.

python scripts_predict_full_pages.py prepared/source_manifest.json \\
  local_trained_best.pt heldout_predictions.json --split val --device 0
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import cv2
from scripts_propose_labels import propose


def predict_split(model, manifest: dict, *, split: str, scales: tuple[float,...],
                  confidence: float, device: str, max_tiles: int = 30000) -> dict:
    from blueprint_symbols.multiscale import plan_pyramid_tiles
    from blueprint_symbols.dataset_manifest import validate_manifest_sources
    from blueprint_symbols.label_schema import load_training_page
    validate_manifest_sources(manifest)
    if split not in ('val','test') or not 0<confidence<1 or not scales:
        raise ValueError('invalid held-out inference configuration')
    images=[]
    total_tiles=0
    for annotation in manifest['splits'][split]:
        source=Path(annotation).resolve()
        # a page JSON or a page of a PDF's labels file (<stem>_labels.json#p12),
        # as training sees it (device classes, image_path made absolute)
        record=load_training_page(source)
        if record.get('project_family_id') not in manifest['split_groups'][split]:
            raise ValueError(f'group does not match held-out partition: {source}')
        if not set(manifest['class_ids']).issubset(set(record.get('exhaustive_families',[]))):
            raise ValueError(f'held-out source missing complete target class annotations: {source}')
        image_path=(source.parent/record['image_path']).resolve()
        image=cv2.imread(str(image_path),cv2.IMREAD_COLOR)
        if image is None or image.shape[:2]!=(record['image_height'],record['image_width']):
            raise ValueError(f'cannot decode/mismatched held-out image: {image_path}')
        h,w=image.shape[:2]
        nt=len(plan_pyramid_tiles(w,h,tile_size=512,overlap=160,source_scales=scales))
        total_tiles+=nt
        if total_tiles>max_tiles:
            raise ValueError(f'max_tiles exceeded before inference: {max_tiles}; choose fewer scales')
        candidates,info=propose(model,image,manifest['class_ids'],scales=scales,
                                confidence=confidence,tile_size=512,overlap=160,device=device)
        images.append({'annotation_path':str(source),'instances':[
            {'symbol_family':item['symbol_family'],'bbox_xyxy':item['bbox_xyxy'],
             'confidence':item['confidence']} for item in candidates],
            'tiles':info['tile_count']})
    return {'format_version':1,'split':split,'source_manifest_groups':manifest['split_groups'][split],
            'scales':list(scales),'confidence':confidence,'tiles_total':total_tiles,
            'predictions':images,'training_performed':False}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('source_manifest',type=Path)
    p.add_argument('local_weights',type=Path)
    p.add_argument('output_predictions',type=Path)
    p.add_argument('--split',choices=('val','test'),default='val')
    p.add_argument('--scales',default='.75,1.,1.5',
                   help='source pixels per detector pixel (training covers 0.6-2.2; 1 = native 150 DPI)')
    p.add_argument('--confidence',type=float,default=.05)
    p.add_argument('--device',default='cpu')
    p.add_argument('--max-tiles',type=int,default=30000)
    a=p.parse_args()
    if not a.local_weights.is_file():p.error('weights must exist locally; never auto-download')
    if a.output_predictions.exists():p.error('refusing to overwrite previous evaluation')
    manifest=json.loads(a.source_manifest.read_text())
    try:from ultralytics import YOLO
    except ImportError:p.error('install ultralytics on the evaluation machine')
    model=YOLO(str(a.local_weights))
    result=predict_split(model,manifest,split=a.split,
                         scales=tuple(float(s) for s in a.scales.split(',')),
                         confidence=a.confidence,device=a.device,max_tiles=a.max_tiles)
    result['weights']=str(a.local_weights.resolve())
    a.output_predictions.parent.mkdir(parents=True,exist_ok=True)
    a.output_predictions.write_text(json.dumps(result,indent=2))
    print(json.dumps({'pages':len(result['predictions']),'tiles':result['tiles_total'],
                      'predictions':sum(len(v['instances']) for v in result['predictions']),
                      'split':a.split},indent=2))

if __name__=='__main__':main()
