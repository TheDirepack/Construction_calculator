"""Usage: python scripts_import_coco.py instances.json ./images ./out class_map.json

class_map: {"single_door":"door", "window":"window"}. This intentionally
requires explicit semantic mapping (architectural labels != receptacles).
Optional --groups groups.json maps image file_name -> drawing/project family ID.
"""
import argparse
from pathlib import Path
import json
from blueprint_symbols.coco_import import convert_coco
p=argparse.ArgumentParser()
p.add_argument('coco_json')
p.add_argument('images_root')
p.add_argument('out_dir')
p.add_argument('class_map_json')
p.add_argument('--groups')
a=p.parse_args()
class_map=json.loads(Path(a.class_map_json).read_text())
groups=json.loads(Path(a.groups).read_text()) if a.groups else None
print(convert_coco(a.coco_json,a.images_root,a.out_dir,class_map,project_groups=groups))
if not groups:
    print('WARNING: source project-family group split unverified. Do not trust held-out scores until grouped.')
