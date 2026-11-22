#!/usr/bin/env python3
"""Repair misleading extensions from the historical Innerscene downloader.

Uses libmagic's file identification; ambiguous types become .bin and are recorded
for investigation. Does not transform any file contents.
"""
from collections import Counter
import json, pathlib, subprocess
root=pathlib.Path(__file__).resolve().parent.parent/'blueprints_and_libraries/2_component_libraries/innerscene_cad_library'
manifest=root/'manifest.json'; entries=json.loads(manifest.read_text())
paths=[root/(x['slug']+'.dxf') for x in entries if (root/(x['slug']+'.dxf')).is_file()]
descriptions=[]
for pos in range(0,len(paths),100):
 out=subprocess.run(['file','-b','--',*map(str,paths[pos:pos+100])],capture_output=True,text=True,check=True).stdout.splitlines()
 if len(out)!=len(paths[pos:pos+100]): raise RuntimeError('Unexpected file output')
 descriptions.extend(out)
index={p.stem:(p,desc) for p,desc in zip(paths,descriptions)}
def extension(desc):
 checks=[('AutoCAD Drawing Exchange Format','dxf'),('DWG AutoDesk','dwg'),('PDF document','pdf'),('SVG Scalable Vector Graphics','svg'),('Microsoft Visio 2013+','vsdx'),('Microsoft Word 2007+','docx'),('Microsoft PowerPoint 2007+','pptx'),('Microsoft Excel 2007+','xlsx'),('OpenDocument Spreadsheet','ods'),('OpenEXR image data','exr'),('Radiance HDR image data','hdr'),('Windows Enhanced Metafile','emf'),('OpenType font data','otf'),('Adobe Photoshop Image','psd'),('Targa image data','tga'),('PostScript document','eps'),('Zip archive data','zip')]
 return next((ext for label,ext in checks if desc.startswith(label)),'bin')
counts=Counter()
for entry in entries:
 slug=entry['slug']
 if slug not in index: continue
 old,desc=index[slug]; ext=extension(desc); new=old.with_suffix('.'+ext)
 if new!=old:
  if new.exists(): raise RuntimeError(f'Refusing to overwrite {new}')
  old.rename(new)
 entry.update(file=new.name,detected_type=desc,extension_method='libmagic' if ext!='bin' else 'unresolved; libmagic description only')
 counts[ext]+=1
manifest.write_text(json.dumps(entries,indent=1)+'\n')
print('Classified',sum(counts.values()),'files:',dict(counts))
print('Unresolved .bin:',counts['bin'])
