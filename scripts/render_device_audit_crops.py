#!/usr/bin/env python3
"""Render review crops for device JSON records with known local PDF mappings.
Usage: python3 scripts/render_device_audit_crops.py [--limit N]
Outputs under blueprints_and_libraries/_device_audit_crops; no source JSON is changed.
"""
import json, pathlib, argparse
import pymupdf
ROOT=pathlib.Path(__file__).resolve().parents[1]
B=ROOT/'blueprints_and_libraries'
MAP={
 '1_blueprints/rinker_hall/EXTRACTED_DEVICES.json':('1_blueprints/rinker_hall/electrical',{'E102P':'E102P_electrical_first_floor_power_plan.pdf','E103L':'E103L_electrical_second_and_third_floor_lighting.pdf','E103P':'E103P_electrical_second_and_third_floor_power_plan.pdf'}),
 '1_blueprints/new_downloads_202611b/C2_DEVICES.json':('1_blueprints/new_downloads_202611b',None),
 '1_blueprints/new_downloads_202611b/Washington_DEVICES.json':('1_blueprints/new_downloads_202611b',None),
 '1_blueprints/new_downloads_202611b/UNT_Willis_DEVICES.json':('1_blueprints/new_downloads_202611b',None),
 '1_blueprints/new_downloads_202611d/Fire_Admin_DEVICES.json':('1_blueprints/new_downloads_202611d',None),
 '4_archives/state_dot_samples/caltrans/Caltrans_DEVICES.json':('4_archives/state_dot_samples/caltrans',None),
}
PDF={'1_blueprints/new_downloads_202611b/C2_DEVICES.json':'C2_Drawings.pdf','1_blueprints/new_downloads_202611b/Washington_DEVICES.json':'WashingtonDrawings (1).pdf','1_blueprints/new_downloads_202611d/Fire_Admin_DEVICES.json':'Biid-2024-00316-CC-Fire-Admin-Bldg-Drawings-002.pdf','4_archives/state_dot_samples/caltrans/Caltrans_DEVICES.json':'Caltrans_2022_Standard_Plans_97_sheets.pdf'}
def box(d,x):
 for k in ('symbol_graphic_bbox','symbol_bbox','symbol_bbox_bl','bounding_box'):
  v=x.get(k)
  if isinstance(v,dict):return [v['x1'],v['y1'],v['x2'],v['y2']],True
  if isinstance(v,list) and len(v)==4:return v, k.endswith('_bl') or d.get('coordinate_origin')=='bottom-left' or 'bottom-left' in str(d.get('units',''))
 if x.get('bbox_pts'):return x['bbox_pts'],True
 if x.get('symbol_bbox_pts'):return x['symbol_bbox_pts'],True
 if x.get('text_bbox'):return x['text_bbox'],True
 return None,True
ap=argparse.ArgumentParser(); ap.add_argument('--limit',type=int); args=ap.parse_args(); out=B/'_device_audit_crops'; out.mkdir(exist_ok=True); report=[]; n=0
for rel,(subdir,sheetmap) in MAP.items():
 d=json.loads((B/rel).read_text()); docpdf=PDF.get(rel)
 for i,x in enumerate(d.get('devices',[])):
  if args.limit and n>=args.limit:break
  bx,bl=box(d,x); rid=x.get('instance_id',x.get('device_id',i+1)); page=int(x.get('page',1))
  if rel.endswith('EXTRACTED_DEVICES.json'):
   sheet=x.get('sheet'); docpath=B/'1_blueprints/rinker_hall/electrical'/sheetmap.get(sheet,'')
  else: docpath=B/subdir/docpdf if docpdf else None
  rec={'source_json':rel,'record_id':rid,'index':i,'page':page,'bbox':bx,'crop':None,'status':'unresolved'}
  try:
   if not docpath or not docpath.exists() or not bx: raise ValueError('source PDF or symbol bbox unavailable')
   pdf=pymupdf.open(docpath); pg=pdf[page-1]; a,b,c,e=bx
   clip=pymupdf.Rect(a-35,pg.rect.height-e-35,c+35,pg.rect.height-b+35) if bl else pymupdf.Rect(a-35,b-35,c+35,e+35)
   clip &= pg.rect
   dest=out/(pathlib.Path(rel).stem+f'_p{page}_id{rid}.png'); pg.get_pixmap(matrix=pymupdf.Matrix(4,4),clip=clip,alpha=False).save(dest)
   rec['crop']=str(dest.relative_to(B)); rec['status']='crop_rendered'
  except Exception as e: rec['reason']=str(e)
  report.append(rec); n+=1
 if args.limit and n>=args.limit:break
(out/'CROP_INDEX.json').write_text(json.dumps(report,indent=2)); print('rendered',sum(r['status']=='crop_rendered' for r in report),'of',len(report),'index',out/'CROP_INDEX.json')
