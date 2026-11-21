"""Render an inspectable overlay from scripts_resolve_snaps output.

python scripts_visualize_snaps.py examples/demo_snaps.json examples/demo_overlay.png
This is human QA, not another model and not a trained accuracy metric.
"""
import argparse
from pathlib import Path
import json
import cv2


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('snaps',type=Path)
    p.add_argument('output',type=Path)
    args=p.parse_args()
    doc=json.loads(args.snaps.read_text())
    image=cv2.imread(doc['image_path'])
    if image is None:
        p.error('cannot load raster from snaps JSON: '+str(doc['image_path']))
    for inst in doc['instances']:
        box=inst['bbox_xyxy']
        snap=inst['snap']
        pt=tuple(round(float(v)) for v in snap['point'])
        center=tuple(round(float(v)) for v in snap['center'])
        confirmed=snap['kind']=='wall_centerline_intersection'
        color=(0,130,0) if confirmed else (0,120,255)  # BGR; green vs orange
        cv2.rectangle(image,tuple(map(round,box[:2])),tuple(map(round,box[2:])),color,1)
        if center!=pt:
            cv2.line(image,center,pt,color,1,cv2.LINE_AA)
        cv2.drawMarker(image,pt,color,cv2.MARKER_CROSS,12,1,cv2.LINE_AA)
        label=f"{inst.get('instance_id') or inst['symbol_family']}: {snap['kind']}"
        cv2.putText(image,label,(max(2,pt[0]-20),max(15,pt[1]-18)),
                    cv2.FONT_HERSHEY_SIMPLEX,.40,color,1,cv2.LINE_AA)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    if not cv2.imwrite(str(args.output),image):
        raise IOError('cannot write overlay image')
    print('Overlay:',args.output)

if __name__=='__main__':main()
