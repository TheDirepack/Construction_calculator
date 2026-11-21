"""SVG symbol instance importer for FloorPlanCAD-like primitive annotations.

Reads semanticId/instanceId (also semantic-id/instance-id), or explicit
CubiCasa-style group classes. SVG viewBox and nested transform are respected.
Full SVG path support requires `svgpathtools` (no network access at runtime).
Does not silently turn unsupported/unannotated geometry into negatives.
"""
from __future__ import annotations
from collections import defaultdict,Counter
from pathlib import Path
import math,re,xml.etree.ElementTree as ET
import numpy as np
from .cad_labels import PixelTransform

_num=r'[-+]?(?:\d*\.\d+|\d+\.?\d*)(?:[Ee][-+]?\d+)?'
_transform_re=re.compile(r'([A-Za-z]+)\s*\(([^)]*)\)')

def matrix_for(value: str) -> np.ndarray:
    total=np.eye(3)
    for match in _transform_re.finditer(value or ''):
        kind=match.group(1).lower(); vals=[float(n) for n in re.findall(_num,match.group(2))]
        if kind=='matrix' and len(vals)==6:
            a,b,c,d,e,f=vals; m=np.array([[a,c,e],[b,d,f],[0,0,1.]])
        elif kind=='translate' and len(vals) in (1,2):
            m=np.array([[1,0,vals[0]],[0,1,vals[1] if len(vals)==2 else 0],[0,0,1.]])
        elif kind=='scale' and len(vals) in (1,2):
            m=np.diag([vals[0],vals[-1],1.])
        elif kind=='rotate' and len(vals) in (1,3):
            angle=math.radians(vals[0]);c,s=math.cos(angle),math.sin(angle)
            r=np.array([[c,-s,0],[s,c,0],[0,0,1.]])
            if len(vals)==3:
                x,y=vals[1:];m=np.array([[1,0,x],[0,1,y],[0,0,1]])@r@np.array([[1,0,-x],[0,1,-y],[0,0,1]])
            else:m=r
        elif kind in ('skewx','skewy') and len(vals)==1:
            t=math.tan(math.radians(vals[0])); m=np.array([[1,t,0],[0,1,0],[0,0,1.]]) if kind=='skewx' else np.array([[1,0,0],[t,1,0],[0,0,1.]])
        else: raise ValueError(f'unsupported SVG transform {kind} {vals}')
        total=total@m
    return total

def _point(T,xy):
    p=T@np.array([float(xy[0]),float(xy[1]),1.])
    return (float(p[0]/p[2]),float(p[1]/p[2]))

def _bounds_from_sampled_path(value):
    try:
        from svgpathtools import parse_path
    except ImportError as e:
        raise RuntimeError('SVG paths require pip install svgpathtools (listed in requirements.txt)') from e
    p=parse_path(value)
    if not p: raise ValueError('empty path')
    # Sampling curved paths preserves extrema approximately; trained bboxes
    # are padded below for stroke width. Exact circle centers remain separate.
    pts=[]
    for seg in p:
        for t in np.linspace(0,1,33):
            v=seg.point(float(t));pts.append((v.real,v.imag))
    return pts

def _coords(node):
    tag=node.tag.rsplit('}',1)[-1].lower();a=node.attrib
    if tag=='line':return [(float(a['x1']),float(a['y1'])),(float(a['x2']),float(a['y2']))],None
    if tag=='circle':
        cx,cy,r=map(float,(a['cx'],a['cy'],a['r']))
        if r<=0:raise ValueError('zero circle')
        return [(cx+r*math.cos(z),cy+r*math.sin(z)) for z in np.linspace(0,2*math.pi,65)],(cx,cy)
    if tag=='ellipse':
        cx,cy,rx,ry=map(float,(a['cx'],a['cy'],a['rx'],a['ry']))
        return [(cx+rx*math.cos(z),cy+ry*math.sin(z)) for z in np.linspace(0,2*math.pi,65)],None
    if tag=='rect':
        x,y=float(a.get('x',0)),float(a.get('y',0));w,h=float(a['width']),float(a['height'])
        return [(x,y),(x+w,y),(x,y+h),(x+w,y+h)],None
    if tag in ('polyline','polygon'):
        n=list(map(float,re.findall(_num,a['points'])));
        if len(n)%2 or len(n)<4: raise ValueError('invalid polyline')
        return list(zip(n[::2],n[1::2])),None
    if tag=='path':return _bounds_from_sampled_path(a['d']),None
    return None,None

def svg_to_image(svg_path: str | Path, image_path: str | Path,
                 class_map: dict[str,str], *, project_group: str,
                 include_group_classes: bool = False,
                 transform: PixelTransform | None = None,
                 aligned_verified:bool = False) -> tuple[dict,dict]:
    import cv2
    src=Path(svg_path); image=Path(image_path).resolve()
    raster=cv2.imread(str(image),cv2.IMREAD_UNCHANGED)
    if raster is None:raise FileNotFoundError(image)
    H,W=raster.shape[:2]
    root=ET.parse(src).getroot()
    vb=root.get('viewBox')
    if transform is None:
        if not vb:
            raise ValueError('SVG viewBox missing: supply explicit verified SVG->raster homography')
        v=list(map(float,re.findall(_num,vb)))
        if len(v)!=4 or min(v[2:])<=0:raise ValueError('invalid SVG viewBox')
        sx,sy=W/v[2],H/v[3]
        par=root.get('preserveAspectRatio','xMidYMid meet').strip()
        if par == 'none':
            tx,ty=-sx*v[0],-sy*v[1]
        else:
            if par.endswith('slice'):raise ValueError('SVG slice preserveAspectRatio needs explicit mapping')
            s=min(sx,sy);sx=sy=s
            align=par.split()[0]
            ox=(W-s*v[2])*(0 if 'xMin' in align else 1 if 'xMax' in align else .5)
            oy=(H-s*v[3])*(0 if 'YMin' in align else 1 if 'YMax' in align else .5)
            tx,ty=ox-s*v[0],oy-s*v[1]
        transform=PixelTransform(np.array([[sx,0,tx],[0,sy,ty],[0,0,1.]]))
    objects=defaultdict(list); stats=Counter(); semantics=Counter(); context=[]
    def visit(node, parent_T, inherited=None):
        tag=node.tag.rsplit('}',1)[-1].lower()
        T=parent_T@matrix_for(node.get('transform',''))
        semantic=node.get('semanticId',node.get('semantic-id',node.get('semantic',None)))
        instance=node.get('instanceId',node.get('instance-id',node.get('instance',None)))
        # CubiCasa group labels: e.g. class="FixedFurniture Toilet"; explicitly
        # enabled and mapped. Never generalize every arbitrary SVG class to a symbol.
        if include_group_classes and tag=='g' and node.get('class') and node.get('id'):
            semantic=semantic or node.get('class').strip()
            instance=instance or node.get('id')
        effective=(str(semantic),str(instance)) if (semantic is not None and instance is not None
                   and str(semantic).strip() not in ('','-1','None','null')
                   and str(instance).strip() not in ('','-1','None','null')) else inherited
        if tag in ('line','circle','rect','path','polyline','polygon','ellipse'):
            if effective is None:
                # Preserve labeled-but-uncountable semantic primitives, e.g.
                # wall boundaries, for future geometry supervision.
                if semantic is not None and str(semantic).strip() not in ('','-1','None','null'):
                    try:
                        coords,_=_coords(node)
                        if coords:
                            context.append({'semantic':str(semantic),'primitive_type':tag,
                                            'points_xy':[list(transform.point(_point(T,p))) for p in coords],
                                            'kind':'semantic_context_not_snap_ground_truth'})
                    except (KeyError,TypeError,ValueError):stats['unsupported_context_geometry']+=1
                else:
                    stats['unlabeled_geometry']+=1
            else:
                try:
                    coords,circle=_coords(node)
                    if not coords:raise ValueError('empty svg geometry')
                    points=[transform.point(_point(T,p)) for p in coords]
                    center=transform.point(_point(T,circle)) if circle else None
                    stroke=float(node.get('stroke-width','0') or 0)
                    # Handle conservative stroke bbox, inherited CSS and nonuniform
                    # stroke scaling require inspection; do not claim exact boundary.
                    sx=np.linalg.norm((transform.H@T)[:2,0]);sy=np.linalg.norm((transform.H@T)[:2,1])
                    pad=stroke*max(sx,sy)/2
                    objects[effective].append((points,center,pad))
                    semantics[effective[0]]+=1
                except (ValueError,KeyError,TypeError):
                    stats['unsupported_geometry']+=1
        for child in node:visit(child,T,effective)
    visit(root,np.eye(3))
    if not objects:raise ValueError('NO_INSTANCES: no supported labeled SVG graphics; check attribute schema, group settings and path dependency')
    instances=[]
    for (semantic,iid),parts in sorted(objects.items()):
        points=[p for pts,_,_ in parts for p in pts]
        xs,ys=zip(*points);pad=max(pad for _,_,pad in parts)
        bbox=[max(0.,min(xs)-pad),max(0.,min(ys)-pad),min(float(W),max(xs)+pad),min(float(H),max(ys)+pad)]
        if bbox[2]-bbox[0] < 1e-6 or bbox[3]-bbox[1] < 1e-6:
            stats['invalid_bbox']+=1;continue
        circles=[center for _,center,_ in parts if center]
        anchors={}
        if len(circles)==1:
            anchors['circle_center']={'xy':list(circles[0]),'visible':True,'annotated':True,
                                      'method':'unique_svg_circle_center'}
        if len(circles)>1:stats['ambiguous_multiple_circles']+=1
        family=class_map.get(semantic)
        instances.append({'instance_id':f'{src.stem}_{iid}',
                          'symbol_family':family or '__unmapped__',
                          'symbol_variant':semantic,'bbox_xyxy':bbox,'anchors':anchors,
                          'uncertain':not bool(family),
                          'source':{'kind':'svg_primitive_instance','source_annotation':str(src.resolve()),
                                    'source_instance_id':iid,'label_method':'supplied_svg_instance'}})
    result={'format_version':1,'image_path':str(image),'image_width':W,'image_height':H,
            'project_family_id':project_group,'source_group_verified':True,'calibration_verified':bool(aligned_verified),
            'source_type':'svg_primitive','instances':instances,'context_primitives':context}
    report={'labeled_primitive_semantics':dict(semantics),'exported_objects':len(instances),
            'mapped':sum(v['symbol_family']!='__unmapped__' for v in instances),
            'circle_centers':sum('circle_center' in v['anchors'] for v in instances),
            'context_primitives':len(context),
            'warnings':dict(stats)}
    return result,report
