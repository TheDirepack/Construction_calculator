"""Inspect downloaded raw dataset label schemas and counts. NO TRAINING.

python scripts_inspect_raw_datasets.py 'downloads/**/*' --out raw_inventory.json
Scans COCO, ArchCAD primitive JSON, FloorPlanCAD SVG, CubiCasa SVG and DXF.
It reports ACTUALLY present semantic/instance IDs, not claimed dataset sizes.
"""
from __future__ import annotations
from collections import Counter
from pathlib import Path
import argparse,glob,json,xml.etree.ElementTree as ET


def inspect_file(path:Path) -> dict:
    counts=Counter(); categories=Counter();types=Counter()
    if path.suffix.lower()=='.svg':
        for _,e in ET.iterparse(path,events=('end',)):
            tag=e.tag.rsplit('}',1)[-1]
            if tag in ('g','path','circle','ellipse','line','polyline','rect','polygon'):
                sem=e.get('semanticId',e.get('semantic-id',e.get('semantic')))
                iid=e.get('instanceId',e.get('instance-id',e.get('instance')))
                if tag=='g' and e.get('class'):
                    categories['svg_group:'+e.get('class')]+=1
                if sem is not None:
                    categories[str(sem)]+=1;counts['semantic_primitives']+=1
                    if iid is not None and str(iid).strip() not in ('','-1','None'):
                        counts['instance_primitives']+=1
                elif tag!='g': counts['unlabeled_graphics']+=1
                types[tag]+=1
            e.clear()
        kind='svg'
    elif path.suffix.lower()=='.dxf':
        import ezdxf
        doc=ezdxf.readfile(str(path));kind='dxf'
        for e in doc.modelspace():
            types[e.dxftype()]+=1
            if e.dxftype()=='INSERT':
                categories[e.dxf.name]+=int(getattr(e,'mcount',1))
                counts['block_insertions']+=int(getattr(e,'mcount',1))
    elif path.suffix.lower()=='.json':
        obj=json.loads(path.read_text())
        if isinstance(obj,dict) and all(k in obj for k in ('images','annotations','categories')):
            kind='coco'
            categories.update({str(c['name']):0 for c in obj['categories']})
            catmap={int(x['id']):str(x['name']) for x in obj['categories']}
            counts['images']=len(obj['images']);counts['annotations']=len(obj['annotations'])
            for a in obj['annotations']:
                categories[catmap.get(int(a['category_id']),'UNKNOWN_CATEGORY')]+=1
        else:
            if isinstance(obj,list):data=obj
            elif isinstance(obj,dict):
                data=obj.get('primitives',obj.get('entities',None))
                if data is None and 'semantic_labels' in obj:
                    labels=obj['semantic_labels'];instances=obj.get('instance_labels',[])
                    counts['semantic_labels']=len(labels)
                    counts['instance_labels']=len(instances)
                    categories.update(str(k) for k in labels if k is not None)
                    return {'file':str(path),'format':'archcad_array_labels','counts':dict(counts),
                            'categories':dict(categories),'primitive_types':{},
                            'warning':'Empty semantic_labels/instance_labels cannot supervise training' if not labels or not instances else None}
                if not isinstance(data,list):return {'file':str(path),'format':'other_json','counts':{},'categories':{},'primitive_types':{}}
            else:return {'file':str(path),'format':'other_json','counts':{},'categories':{},'primitive_types':{}}
            kind='primitive_json'
            counts['primitives']=len(data)
            for primitive in data:
                if not isinstance(primitive,dict):continue
                types[str(primitive.get('type','missing'))]+=1
                sem=primitive.get('semantic',primitive.get('semantic_label'))
                inst=primitive.get('instance',primitive.get('instance_id'))
                if sem is None or str(sem).strip() in ('','None','-1'):
                    counts['unlabeled']+=1;continue
                categories[str(sem)]+=1;counts['semantic_primitives']+=1
                if inst is not None and str(inst).strip() not in ('','-1','None'):
                    counts['instance_primitives']+=1
    else: return {'file':str(path),'format':'ignored','counts':{},'categories':{},'primitive_types':{}}
    warning=None
    if kind in ('primitive_json','svg') and not counts.get('semantic_primitives'):
        warning='No semantic-labeled primitives detected; DO NOT treat as a negative image'
    if kind in ('primitive_json','svg') and not counts.get('instance_primitives'):
        warning='No instance IDs detected; cannot supervise detector instances without conversion/verification'
    return {'file':str(path),'format':kind,'counts':dict(counts),'categories':dict(categories),
            'primitive_types':dict(types),'warning':warning}


def inspect(paths:list[Path],*,max_files:int=1000) -> dict:
    supported={'.svg','.json','.dxf'}
    selected=sorted(p for p in paths if p.is_file() and p.suffix.lower() in supported)[:max_files]
    results=[];summaries=Counter();classes=Counter();warnings=[]
    for path in selected:
        try:
            result=inspect_file(path)
            results.append(result);summaries[result['format']]+=1
            classes.update(result['categories'])
            if result.get('warning'):warnings.append({'file':str(path),'issue':result['warning']})
        except (OSError,ValueError,KeyError,TypeError,ImportError,ET.ParseError) as e:
            warnings.append({'file':str(path),'issue':type(e).__name__+': '+str(e)})
    return {'files_inspected':len(selected),'max_files':max_files,'formats':dict(summaries),
            'semantic_counts':dict(classes.most_common()),'files':results,'warnings':warnings,
            'note':'Counts are observed labels or CAD block occurrences, not physical anchor labels or unique independent projects.'}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('glob');p.add_argument('--out',type=Path,required=True)
    p.add_argument('--max-files',type=int,default=1000)
    a=p.parse_args()
    files=[Path(x) for x in glob.glob(a.glob,recursive=True)]
    result=inspect(files,max_files=a.max_files)
    a.out.parent.mkdir(parents=True,exist_ok=True)
    a.out.write_text(json.dumps(result,indent=2))
    print(f"Inspected {result['files_inspected']} files; formats={result['formats']}; "
          f"classes={len(result['semantic_counts'])}; warnings={len(result['warnings'])}")
if __name__=='__main__':main()
