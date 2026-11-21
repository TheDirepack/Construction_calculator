"""Import verified ArchCAD-like labeled primitive JSON, never train.

python scripts_import_primitives.py input.json matching.png class_map.json output.json \
  --group ORIGINAL_DRAWING_ID [--homography H.json]
"""
from pathlib import Path
import argparse,json,numpy as np
from blueprint_symbols.primitive_labels import convert_primitive_json
from blueprint_symbols.cad_labels import PixelTransform


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('primitive_json',type=Path);p.add_argument('image',type=Path)
    p.add_argument('map',type=Path);p.add_argument('out',type=Path)
    p.add_argument('--group',required=True,help='ORIGINAL CAD PROJECT ID; not tile/slice ID')
    p.add_argument('--homography',type=Path,help='Optional 3x3 CAD to pixel H as JSON')
    p.add_argument('--aligned-verified',action='store_true',help='Assert paired raster alignment verified')
    a=p.parse_args()
    transform=PixelTransform(np.array(json.loads(a.homography.read_text()),dtype=float)) if a.homography else None
    labels,report=convert_primitive_json(a.primitive_json,a.image,json.loads(a.map.read_text()),
                                         project_group=a.group,transform=transform,aligned_verified=a.aligned_verified)
    a.out.parent.mkdir(parents=True,exist_ok=True)
    a.out.write_text(json.dumps(labels,indent=2))
    a.out.with_name(a.out.stem+'.report.json').write_text(json.dumps(report,indent=2))
    print(json.dumps(report,indent=2))
if __name__=='__main__': main()
