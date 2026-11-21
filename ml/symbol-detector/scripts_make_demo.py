"""Generate a small, labeled geometry demo (NOT a trained detector)."""
from pathlib import Path
import json
import cv2
import numpy as np


def main():
    root=Path(__file__).resolve().parent/'examples'
    root.mkdir(exist_ok=True)
    image=np.full((517,901,3),245,np.uint8)
    # Verified wall centerline (thick raster stroke). Circle is offset from wall
    # and joined by short horizontal wall-attachment stem.
    cv2.line(image,(190,35),(190,468),(35,35,35),4,cv2.LINE_AA)
    cv2.line(image,(190,150),(204,150),(35,35,35),2,cv2.LINE_AA)
    cv2.circle(image,(220,150),16,(5,5,5),2,cv2.LINE_AA)
    cv2.line(image,(214,140),(214,160),(5,5,5),2,cv2.LINE_AA)
    cv2.line(image,(226,140),(226,160),(5,5,5),2,cv2.LINE_AA)
    # Independent receptacle circle; correct snap must remain circle center.
    cv2.circle(image,(490,149),17,(5,5,5),2,cv2.LINE_AA)
    cv2.line(image,(483,137),(483,161),(5,5,5),2,cv2.LINE_AA)
    cv2.line(image,(497,137),(497,161),(5,5,5),2,cv2.LINE_AA)
    # HVAC air-terminal + a mechanical valve as other families.
    cv2.rectangle(image,(595,305),(651,361),(30,30,30),2)
    for i in range(3):
        cv2.line(image,(605+i*18,315),(641-i*18,351),(30,30,30),1)
    cv2.line(image,(365,307),(365,382),(50,50,50),2)
    cv2.line(image,(331,340),(399,340),(50,50,50),2)
    cv2.polylines(image,[np.array([[340,323],[365,340],[340,357]],np.int32)],False,(10,10,10),2)
    cv2.polylines(image,[np.array([[390,323],[365,340],[390,357]],np.int32)],False,(10,10,10),2)
    cv2.imwrite(str(root/'demo_raster.png'),image)
    anns={'image_path':'demo_raster.png','image_width':901,'image_height':517,
          'project_family_id':'synthetic_demo_1',
          'source_type':'synthetic_demonstration',
          'instances':[
              {'instance_id':'R-wall','symbol_family':'receptacle',
               'symbol_variant':'duplex','bbox_xyxy':[199,129,241,171]},
              {'instance_id':'R-free','symbol_family':'receptacle',
               'symbol_variant':'duplex','bbox_xyxy':[469,127,511,171]},
              {'instance_id':'HVAC-1','symbol_family':'diffuser',
               'symbol_variant':'ceiling','bbox_xyxy':[595,305,652,362]},
              {'instance_id':'MECH-1','symbol_family':'valve',
               'symbol_variant':'generic','bbox_xyxy':[330,315,400,365]},
          ]}
    (root/'demo_annotations.json').write_text(json.dumps(anns,indent=2))
    (root/'demo_verified_walls.json').write_text(json.dumps({'walls':[
        {'start':[190,35],'end':[190,468],
         'source':'verified_wall_centerline'}]},indent=2))
    print('Wrote',root)


if __name__=='__main__': main()
