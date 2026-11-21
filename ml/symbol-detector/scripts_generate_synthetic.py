"""Usage: python scripts_generate_synthetic.py assets.json output_folder --sheets 10

Asset JSON = [{"png":"...", "family":"receptacle", "variant":"duplex",
                "anchors_normalized":{"visual_center":[.5,.5]}}]
If a high-fidelity symbol anchor cannot be established from CAD, leave it out.
"""
import argparse
import json
from pathlib import Path
from blueprint_symbols.synthetic import load_asset_png, generate_sheet, write_sheet

p=argparse.ArgumentParser()
p.add_argument('assets_json')
p.add_argument('output_folder')
p.add_argument('--sheets',type=int,default=10)
p.add_argument('--seed',type=int,default=123)
a=p.parse_args()
source=Path(a.assets_json)
assets=[]
for item in json.loads(source.read_text()):
    target=Path(item['png'])
    if not target.is_absolute():
        target=source.parent/target
    assets.append(load_asset_png(target,item['family'],item.get('variant','unspecified'),item.get('anchors_normalized')))
folder=Path(a.output_folder)
for i in range(a.sheets):
    # Deliberately mixed dimensions/aspect ratios. Not fixed camera tiers.
    size=[(1536,1024),(2111,1280),(900,1800),(3072,900)][i%4]
    page, labels=generate_sheet(assets,width=size[0],height=size[1],count=35,seed=a.seed+i)
    out=write_sheet(folder/f'sheet_{i:04d}.png',page,labels,group=f'synthetic_assets_{source.stem}',seed=a.seed+i)
    print(out, len(labels))
