"""Primitive-level CAD annotations -> conservative raster-aligned symbol records.

Supports ArchCAD JSON list of LINE/CIRCLE/ARC/...(semantic, instance),
including an explicit check for released samples lacking labels. Does not
invent wall snaps from bounding boxes. Data remains in original source pixels.
"""
from __future__ import annotations
from collections import Counter, defaultdict
from pathlib import Path
import json
import math
import numpy as np
from .cad_labels import PixelTransform


def _pt(v):
    if not isinstance(v, (list, tuple)) or len(v) < 2:
        raise ValueError('expected 2D point')
    p = (float(v[0]), float(v[1]))
    if not all(map(math.isfinite, p)):
        raise ValueError('nonfinite point')
    return p


def _arc_points(p: dict) -> list[tuple[float,float]]:
    """Return arc extrema and endpoints; angle convention must be supplied.

    Unlike lines/circles, arc schema varies by export. Without validated
    'start_angle'/'end_angle' in degrees, fail closed.
    """
    c = _pt(p['center']); radius = float(p['radius'])
    start, end = float(p['start_angle']), float(p['end_angle'])
    if radius <= 0 or not all(map(math.isfinite,(radius,start,end))):
        raise ValueError('invalid arc')
    delta = (end-start) % 360
    if math.isclose(delta,0) and not math.isclose(end,start):
        delta=360
    angles=[start,start+delta]
    angles.extend(a for a in range(math.floor(start/90)*90,math.ceil((start+delta)/90)*90+1,90)
                  if start < a < start+delta)
    return [(c[0]+radius*math.cos(math.radians(a)),c[1]+radius*math.sin(math.radians(a))) for a in angles]


def primitive_geometry(primitive: dict) -> dict:
    """Extract source-space extrema and exact circle metadata when possible."""
    t=str(primitive.get('type','')).upper()
    if t in ('LINE','SEGMENT'):
        pts=[_pt(primitive['start']), _pt(primitive['end'])]
    elif t in ('CIRCLE',):
        c=_pt(primitive['center']); r=float(primitive['radius'])
        if not 0 < r < 1e12: raise ValueError('bad circle radius')
        pts=[(c[0]-r,c[1]-r),(c[0]+r,c[1]+r)]
        return {'extrema':pts,'circle':(c,r)}
    elif t == 'ARC':
        pts=_arc_points(primitive)
    elif t in ('LWPOLYLINE','POLYLINE','POLYGON'):
        pts=[_pt(x) for x in primitive.get('points',primitive.get('vertices',[]))]
        if not pts: raise ValueError('polyline has no vertices')
    elif t in ('ELLIPSE',):
        c=_pt(primitive['center']); rx=float(primitive['rx']); ry=float(primitive['ry'])
        if rx<=0 or ry<=0: raise ValueError('invalid ellipse')
        pts=[(c[0]-rx,c[1]-ry),(c[0]+rx,c[1]+ry)]
    else:
        raise ValueError(f'unsupported primitive: {t}')
    return {'extrema':pts}


def _is_usable_instance(instance):
    return instance is not None and str(instance).strip() not in ('','-1','-1.0','None','null','0','unlabeled')


def convert_primitive_json(source_path: str | Path, image_path: str | Path,
                           class_map: dict[str,str], *, transform: PixelTransform | None = None,
                           project_group: str, strict: bool = True, aligned_verified: bool = False) -> tuple[dict,dict]:
    """Group *actually annotated* primitives by semantic+instance.

    Source JSON must be list or {'primitives': list}. `transform` maps vector
    geometry to image pixels. No implicit scaling: paired data must be either
    already raster-aligned or carry verified H. Mapping semantic name to family
    must be explicit. Empty/missing labels are errors in strict mode.
    """
    import cv2
    src=Path(source_path)
    data=json.loads(src.read_text())
    primitives=data if isinstance(data,list) else data.get('primitives',data.get('entities',[]))
    if not isinstance(primitives,list): raise ValueError('expected list of primitives')
    image=Path(image_path).resolve()
    info=cv2.imread(str(image),cv2.IMREAD_UNCHANGED)
    if info is None: raise FileNotFoundError(image)
    h,w=info.shape[:2]
    if not project_group: raise ValueError('project_group required (prevents cross-tile split leakage)')
    counter=Counter(); objects=defaultdict(list)
    semantic_seen=Counter(); instances_seen=set(); context=[]
    for idx, p in enumerate(primitives):
        if not isinstance(p,dict): counter['invalid_record']+=1; continue
        semantic=p.get('semantic',p.get('semantic_label',None))
        instance=p.get('instance',p.get('instance_id',None))
        if semantic is None or str(semantic).strip() in ('','-1','None','null'):
            counter['unlabeled_semantic']+=1; continue
        semantic=str(semantic)
        semantic_seen[semantic]+=1
        if not _is_usable_instance(instance):
            counter['no_instance_id']+=1
            try:
                geom=primitive_geometry(p)
                # 'wall' strokes are contextual geometry, not object instances.
                # The line could be a wall boundary, NOT a verified wall midline.
                context.append({'semantic':semantic,'primitive_type':str(p.get('type')),
                                'points_xy':[list(transform.point(x) if transform else x)
                                             for x in geom['extrema']],
                                'kind':'semantic_context_not_snap_ground_truth'})
            except (KeyError,TypeError,ValueError,OverflowError):
                counter['unsupported_context_geometry']+=1
            continue
        instances_seen.add((semantic,str(instance)))
        try:
            geom=primitive_geometry(p)
        except (KeyError,TypeError,ValueError,OverflowError):
            counter['unsupported_or_invalid_geometry']+=1; continue
        # Preserve even unmapped classes as ignore regions, not false background.
        objects[(semantic,str(instance))].append(geom)
    if strict and not semantic_seen:
        raise ValueError(f'NO_ANNOTATIONS: {src} has no populated semantic labels')
    if strict and not objects:
        raise ValueError(f'NO_INSTANCES: {src} has no valid labeled countable primitives')
    out=[]
    for (semantic, iid), group in sorted(objects.items()):
        pts=[(transform.point(p) if transform is not None else p)
             for entity in group for p in entity['extrema']]
        xs,ys=zip(*pts)
        bbox=[max(0.,min(xs)),max(0.,min(ys)),min(float(w),max(xs)),min(float(h),max(ys))]
        # Thin line-only symbols still occupy pixels even when their ideal
        # geometry has zero height/width. Expand only degenerate axes.
        if bbox[2]-bbox[0] < 1e-6:
            bbox[0]=max(0.,bbox[0]-.5);bbox[2]=min(float(w),bbox[2]+.5)
        if bbox[3]-bbox[1] < 1e-6:
            bbox[1]=max(0.,bbox[1]-.5);bbox[3]=min(float(h),bbox[3]+.5)
        if bbox[2]-bbox[0] < 1e-6 or bbox[3]-bbox[1] < 1e-6:
            counter['empty_or_outside_bbox']+=1; continue
        circles=[x['circle'] for x in group if 'circle' in x]
        anchors={}
        # A unique vector circle is evidence of its geometric center, never
        # evidence of the wall attachment point or physical device location.
        if len(circles)==1:
            xy=transform.point(circles[0][0]) if transform else circles[0][0]
            anchors['circle_center']={'xy':list(xy),'annotated':True,'visible':True,
                                      'method':'unique_exact_vector_circle'}
        elif len(circles)>1:
            counter['ambiguous_multiple_circles']+=1
        family=class_map.get(semantic)
        out.append({'instance_id':f'{src.stem}_{iid}',
                    'symbol_family':family if family else '__unmapped__',
                    'symbol_variant':semantic,'bbox_xyxy':bbox,'anchors':anchors,
                    'uncertain':not bool(family),
                    'source':{'kind':'primitive_cad', 'source_annotation':str(src.resolve()),
                              'source_instance_id':iid,'semantic':semantic,
                              'primitive_count':len(group),'label_method':'supplied_primitive_instance'}})
    result={'format_version':1,'image_path':str(image),'image_width':w,'image_height':h,
            'project_family_id':project_group,'source_group_verified':True,'calibration_verified':bool(aligned_verified),
            'source_type':'primitive_cad','instances':out,'context_primitives':context}
    report={'input_primitives':len(primitives),'annotated_semantic_counts':dict(semantic_seen),
            'countable_instance_keys':len(instances_seen),'exported_objects':len(out),
            'mapped':sum(x['symbol_family']!='__unmapped__' for x in out),
            'unmapped':sum(x['symbol_family']=='__unmapped__' for x in out),
            'circle_center_labels':sum('circle_center' in x['anchors'] for x in out),
            'context_primitives':len(context),
            'warnings':dict(counter)}
    return result,report
