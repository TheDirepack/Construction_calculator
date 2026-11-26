"""Usage: python scripts_autolabel_dxf.py drawing.dxf paired.png mapping.json out_labels.json

Writes one Format-2 labels file (``<stem>_labels.json``, page 1; see
``label_schema.write_labels_file``): ``out.json`` becomes ``out_labels.json``.

Do not use a transform inferred from unrelated raster/CAD extents: it will
produce incorrect pixel labels. Confirm the image and DXF are precisely paired.
"""
import argparse
import json
from pathlib import Path
import cv2
from blueprint_symbols.cad_labels import load_cad_mapping, labels_from_dxf
from blueprint_symbols.label_schema import COORDINATE_SYSTEM, labels_file_for, write_labels_file

p=argparse.ArgumentParser()
p.add_argument('cad_dxf')
p.add_argument('paired_raster')
p.add_argument('mapping_json')
p.add_argument('output_json')
p.add_argument('--group',required=True,help='ORIGINAL drawing project ID; prevents duplicate CAD variants across splits')
p.add_argument('--aligned-verified',action='store_true',help='Assert CAD-to-raster homography checked by overlay')
args=p.parse_args()
image=cv2.imread(args.paired_raster)
if image is None:
    p.error(f'cannot read raster: {args.paired_raster}')
block_map,transform=load_cad_mapping(args.mapping_json)
res=labels_from_dxf(args.cad_dxf,block_map,transform,document_id=Path(args.cad_dxf).stem)
output={'format_version':1,'source_type':'paired_dxf_raster',
        'document_id':Path(args.cad_dxf).stem,'page_index':0,
        'coordinate_system':COORDINATE_SYSTEM,
        'image_path':str(Path(args.paired_raster).resolve()),
        'image_width':image.shape[1],'image_height':image.shape[0],
        'project_family_id':args.group,'source_group_verified':True,
        'cad_source_path':str(Path(args.cad_dxf).resolve()),
        'instances':res.instances,'unknown_blocks':res.unknown_blocks,
        'skipped_entities':res.skipped,
        'calibration_method':'explicit_cad_to_pixel_homography',
        'calibration_verified':args.aligned_verified}
out=write_labels_file(labels_file_for(args.output_json),{1:output},imported_by='dxf',
                      info={'imported':{'cad_source_path':output['cad_source_path']}})
print(f"Wrote {len(res.instances)} metadata-derived instances to {out}. Unknown: {res.unknown_blocks}. Verify visual overlay before training.")
