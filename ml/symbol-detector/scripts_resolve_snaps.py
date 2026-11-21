"""Working v1 *geometry* CLI; consumes annotations, not untrained model output.

python scripts_resolve_snaps.py --annotations sheet.json --walls walls.json --output snaps.json
Wall JSON may contain [{"start":[x,y], "end":[x,y]}] or {"walls":[...]}.
All points are ORIGINAL raster pixel coordinates (continuous pixel edges).
"""
from pathlib import Path
import argparse
import json
import cv2
from blueprint_symbols.snapping import WallSegment,resolve_annotation
from blueprint_symbols.catalog import discipline_for


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--annotations',type=Path,required=True)
    parser.add_argument('--image',type=Path,help='Overrides image_path in annotations')
    parser.add_argument('--walls',type=Path,help='Verified wall centerlines in page pixel coordinates')
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    doc=json.loads(args.annotations.read_text())
    path=args.image or args.annotations.parent/doc['image_path']
    image=cv2.imread(str(path),cv2.IMREAD_COLOR)
    if image is None:
        raise FileNotFoundError(path)
    if 'image_width' in doc and (doc['image_width']!=image.shape[1] or doc['image_height']!=image.shape[0]):
        raise ValueError('annotation image size differs from raster; mapping would be wrong')
    walls=[]
    if args.walls:
        data=json.loads(args.walls.read_text())
        if isinstance(data,dict): data=data['walls']
        for item in data:
            if item.get('source','verified_wall_centerline')!='verified_wall_centerline':
                raise ValueError('Only verified wall centerlines accepted by CLI')
            walls.append(WallSegment(tuple(item['start']),tuple(item['end'])))
    instances=[]
    for obj in doc['instances']:
        snap=resolve_annotation(image,obj,walls)
        instances.append({'instance_id':obj.get('instance_id'),
                          'discipline':obj.get('discipline',discipline_for(obj.get('symbol_family',''))),
                          'symbol_family':obj.get('symbol_family'),
                          'symbol_variant':obj.get('symbol_variant','unspecified'),
                          'bbox_xyxy':obj['bbox_xyxy'],
                          'snap':snap.as_dict()})
    output={'format_version':1,'coordinate_system':'continuous_image_pixel_edges',
            'image_path':str(path),'image_width':image.shape[1],
            'image_height':image.shape[0], 'instances':instances}
    args.output.parent.mkdir(exist_ok=True,parents=True)
    args.output.write_text(json.dumps(output,indent=2))
    print(f"Resolved {len(instances)} instances; verified walls: {len(walls)}; output: {args.output}")


if __name__=='__main__':
    main()
