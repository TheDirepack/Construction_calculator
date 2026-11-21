"""Score exported held-out detection tiles (no training, no inference).

Predicted labels must use YOLO `class cx cy w h confidence` normalized format,
with files named <sample>.txt under <predictions_root>/<split>/.
Missing predicted files count as no detections (full recall accounting).

python scripts_score_yolo_tiles.py exported_yolo predictions/ --split val_stress_lowres_25
"""
import argparse,json
from pathlib import Path
from blueprint_symbols.detection_eval import score

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('prepared_yolo',type=Path)
    p.add_argument('prediction_root',type=Path)
    p.add_argument('--split',default='val')
    p.add_argument('--iou',type=float,default=.5)
    p.add_argument('--confidence',type=float,default=.05)
    p.add_argument('--output',type=Path)
    a=p.parse_args()
    res=score(a.prepared_yolo,a.prediction_root,split=a.split,
              iou_threshold=a.iou,confidence_threshold=a.confidence)
    rendered=json.dumps(res,indent=2)
    if a.output:
        a.output.parent.mkdir(parents=True,exist_ok=True)
        a.output.write_text(rendered)
    print(rendered)
if __name__=='__main__':main()
