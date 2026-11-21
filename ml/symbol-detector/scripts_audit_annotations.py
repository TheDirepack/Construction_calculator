"""Audit per-sheet labels without loading huge rasters or assuming image sizes.

python scripts_audit_annotations.py 'data/**/*.json' --out inventory.json --classes classes.json
`classes.json` is derived from ACTUALLY labeled families, not a closed list.
UNKNOWN/IGNORE are excluded from the learned class head. Group count is
reported for leakage review; this script does not certify grouped splitting.
"""
from __future__ import annotations
from pathlib import Path
from collections import Counter,defaultdict
import argparse
import glob
import json


def audit(paths: list[Path]) -> tuple[dict,dict]:
    counts=Counter(); variants=Counter(); sources=Counter(); anchor_names=Counter()
    groups=defaultdict(set); sizes=Counter(); problems=[]
    usable=set(); unique_groups=set()
    for file in paths:
        try:
            obj=json.loads(file.read_text())
            if not isinstance(obj,dict) or not isinstance(obj.get('instances'),list):
                continue
            w,h=int(obj['image_width']),int(obj['image_height'])
            if w<=0 or h<=0:
                raise ValueError('invalid image dimensions')
            group=obj.get('project_family_id')
            if not group:
                problems.append({'file':str(file),'issue':'missing_project_family_id'})
                group='MISSING:'+str(file)
            unique_groups.add(group)
            sizes[(w,h)]+=1
            sources[obj.get('source_type','unspecified')]+=1
            for instance in obj['instances']:
                family=str(instance.get('symbol_family','unknown_symbol'))
                variant=str(instance.get('symbol_variant','unspecified'))
                counts[family]+=1
                variants[(family,variant)]+=1
                groups[group].add(family)
                if family not in ('unknown_symbol','ignore','unmapped','__unmapped__') and not instance.get('uncertain',False):
                    usable.add(family)
                for name,a in instance.get('anchors',{}).items():
                    if isinstance(a,dict) and a.get('annotated') and a.get('visible'):
                        anchor_names[name]+=1
                box=instance.get('bbox_xyxy')
                if not box or len(box)!=4 or not (0<=box[0]<box[2]<=w and 0<=box[1]<box[3]<=h):
                    problems.append({'file':str(file),'issue':'invalid_or_outside_bbox',
                                     'instance_id':instance.get('instance_id')})
        except (OSError,ValueError,KeyError,TypeError) as ex:
            problems.append({'file':str(file),'issue':type(ex).__name__+': '+str(ex)})
    labels={name:i for i,name in enumerate(sorted(usable))}
    report={
        'annotated_pages':sum(sizes.values()),'instances':sum(counts.values()),
        'project_groups':len(unique_groups),'source_types':dict(sorted(sources.items())),
        'family_counts':dict(counts.most_common()),
        'variant_counts':[{'family':f,'variant':v,'count':n} for (f,v),n in variants.most_common()],
        'anchor_counts':dict(anchor_names.most_common()),
        'image_dimensions':[{'width':w,'height':h,'count':n} for (w,h),n in sizes.most_common()],
        'group_family_coverage':{k:sorted(v) for k,v in groups.items()},
        'problems':problems,
        'warning':'No model or precision accuracy is implied by annotation counts. Check provenance and group overlap.',
    }
    return report,labels


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('annotations_glob')
    p.add_argument('--out',type=Path,required=True)
    p.add_argument('--classes',type=Path,required=True)
    args=p.parse_args()
    files=[Path(p) for p in glob.glob(args.annotations_glob,recursive=True)]
    if not files:
        p.error('no matching annotations')
    report,classes=audit(files)
    if not report['annotated_pages']:
        p.error('no valid per-sheet annotations found')
    args.out.write_text(json.dumps(report,indent=2))
    args.classes.write_text(json.dumps(classes,indent=2))
    print(f"Audited {report['annotated_pages']} pages, {report['instances']} objects, "
          f"{len(classes)} trainable symbol families, {report['project_groups']} source groups")
    if report['problems']:
        print(f"WARNING: {len(report['problems'])} metadata problems. Read {args.out}")


if __name__=='__main__':
    main()
