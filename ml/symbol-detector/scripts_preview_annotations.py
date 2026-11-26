"""Render quick overlay for checking import alignment before trusting CAD anchors.

python scripts_preview_annotations.py converted/page.json preview.png --max-side 1800
Drawing dimensions are arbitrary; overlay is DISPLAY-ONLY and never changes labels.
"""
import argparse,json
from pathlib import Path
import cv2

def render_preview(annotation:Path,out:Path,max_side:int=1800):
    if max_side<128:raise ValueError('max_side must be >=128')
    from blueprint_symbols.label_schema import read_page
    data=read_page(annotation)   # page JSON or a page of a labels file (<stem>_labels.json#p12)
    image=(annotation.parent/data['image_path']).resolve()
    img=cv2.imread(str(image),cv2.IMREAD_COLOR)
    if img is None:raise FileNotFoundError(image)
    h,w=img.shape[:2]
    if (w,h)!=(data['image_width'],data['image_height']):
        raise ValueError('raster dimensions differ from label metadata')
    scale=min(1.,max_side/max(w,h))
    if scale<1:
        img=cv2.resize(img,(round(w*scale),round(h*scale)),interpolation=cv2.INTER_AREA)
    for instance in data['instances']:
        x0,y0,x1,y1=[round(float(v)*scale) for v in instance['bbox_xyxy']]
        uncertain=instance.get('uncertain',False)
        color=(160,80,200) if uncertain else (40,170,20)
        cv2.rectangle(img,(x0,y0),(x1,y1),color,1)
        name=str(instance.get('symbol_family','?'))
        cv2.putText(img,name,(x0,max(9,y0-3)),cv2.FONT_HERSHEY_SIMPLEX,.35,color,1,cv2.LINE_AA)
        for anchor_name,anchor in instance.get('anchors',{}).items():
            if anchor.get('visible',False) and anchor.get('annotated',False):
                x,y=[round(float(q)*scale) for q in anchor['xy']]
                cv2.circle(img,(x,y),3,(0,0,240),-1)
    out.parent.mkdir(parents=True,exist_ok=True)
    if not cv2.imwrite(str(out),img):raise IOError('could not write preview')
    return {'output':str(out),'symbol_instances':len(data['instances']),
            'anchors':sum(len(v.get('anchors',{})) for v in data['instances']),
            'display_scale':scale,'image_original_size':[w,h]}

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('annotations',type=Path);p.add_argument('output',type=Path)
    p.add_argument('--max-side',type=int,default=1800)
    a=p.parse_args()
    print(json.dumps(render_preview(a.annotations,a.output,a.max_side),indent=2))
if __name__=='__main__':main()
