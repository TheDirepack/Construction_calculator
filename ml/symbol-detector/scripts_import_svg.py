"""Convert vector SVG symbol instances to original-raster labels, no training.

python scripts_import_svg.py drawing.svg drawing.png map.json drawing_labels.json --group ORIGINAL_PROJECT
Writes one Format-2 labels file (page 1; ``label_schema.write_labels_file``):
an output not named ``*_labels.json`` gets that suffix (``x.json`` -> ``x_labels.json``).
Optional --group-classes for CubiCasa-style annotated groups (explicit mapping).
"""
import argparse,json
from pathlib import Path
from blueprint_symbols.svg_labels import svg_to_image
from blueprint_symbols.label_schema import labels_file_for, write_labels_file

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('svg',type=Path);p.add_argument('image',type=Path)
    p.add_argument('map',type=Path);p.add_argument('output',type=Path)
    p.add_argument('--group',required=True);p.add_argument('--group-classes',action='store_true')
    p.add_argument('--aligned-verified',action='store_true',help='Assert SVG and raster paired accurately')
    a=p.parse_args()
    result,report=svg_to_image(a.svg,a.image,json.loads(a.map.read_text()),project_group=a.group,
                               include_group_classes=a.group_classes,aligned_verified=a.aligned_verified)
    out=write_labels_file(labels_file_for(a.output),{1:result},imported_by='svg',
                          info={'imported':{'svg':str(a.svg.resolve())}})
    out.with_name(out.name[:-len('_labels.json')]+'.report.json').write_text(json.dumps(report,indent=2))
    report['labels_file']=str(out)
    print(json.dumps(report,indent=2))
if __name__=='__main__':main()
