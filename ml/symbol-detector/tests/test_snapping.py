"""Tests: no source-resolution assumptions; wall attachment vs isolated circle."""
import json
import subprocess
import sys
from pathlib import Path
import cv2
import numpy as np
import pytest
from blueprint_symbols.catalog import FAMILIES, discipline_for, validated_classes
from blueprint_symbols.snapping import (
    WallSegment, detect_circle_in_box, resolve_annotation, resolve_snap)


def test_multidiscipline_catalog_is_open_to_real_dataset_classes():
    assert {'electrical','mechanical','hvac'} <= set(FAMILIES)
    assert discipline_for('receptacle') == 'electrical'
    assert discipline_for('pump') == 'mechanical'
    assert discipline_for('vav_terminal') == 'hvac'
    labels=validated_classes(['receptacle','pump','vav_terminal','diffuser'])
    assert len(labels)==4 and len(set(labels.values()))==4
    with pytest.raises(ValueError):
        validated_classes(['receptacle','receptacle'])
    with pytest.raises(ValueError):
        validated_classes(['invented_without_mapping'])


def test_snap_perpendicular_wall_intersection_and_circle_fallback():
    # Imaginary visual center of receptacle is at (120,90).
    # A vertical wall centerline x=108 intersects the symbol centerline at (108,90).
    box=(108,78,132,102)
    wall=WallSegment((108,0),(108,200))
    attached=resolve_snap(box,circle_center=(120,90),walls=[wall],wall_attachable=True)
    assert attached.kind == 'wall_centerline_intersection'
    assert attached.point == pytest.approx((108,90))
    assert attached.quality == 'estimated'
    isolated=resolve_snap(box,circle_center=(120,90),walls=[],wall_attachable=True)
    assert isolated.kind == 'circle_center'
    assert isolated.point == pytest.approx((120,90))
    far=resolve_snap(box,circle_center=(120,90),walls=[WallSegment((200,0),(200,200))],wall_attachable=True)
    assert far.kind == 'circle_center'
    assert far.point == pytest.approx((120,90))


def test_wall_endpoints_ambiguous_walls_and_explicit_axis():
    box=(82,82,118,118)
    distant_short=WallSegment((80,0),(80,20))
    result=resolve_snap(box,circle_center=(100,100),walls=[distant_short],wall_attachable=True)
    assert result.kind == 'circle_center'
    walls=[WallSegment((88,0),(88,250)), WallSegment((0,88),(250,88))]
    ambiguous=resolve_snap(box,circle_center=(100,100),walls=walls,wall_attachable=True)
    assert ambiguous.kind == 'ambiguous_wall'
    assert ambiguous.quality=='uncertain'
    # A known diagonal symbol axis intersects a horizontal wall at x=112.
    slanted=resolve_snap(box,circle_center=(100,100),
                         walls=[WallSegment((10,112),(200,112))],wall_attachable=True,
                         symbol_axis=(1,1))
    assert slanted.point == pytest.approx((112,112))
    assert slanted.kind == 'wall_centerline_intersection'


def test_raster_circle_center_and_non_receptacle_do_not_gain_fake_anchors():
    for side in (500,1000):
        image=np.full((side,side*2),255,np.uint8)
        cx,cy=round(side*.42),round(side*.37)
        r=round(side*.023)
        cv2.circle(image,(cx,cy),r,0,2,cv2.LINE_AA)
        box=(cx-r-3,cy-r-3,cx+r+3,cy+r+3)
        circle=detect_circle_in_box(image,box)
        assert circle is not None
        assert np.linalg.norm(np.asarray(circle.center)-[cx,cy])<1.6
        wall=WallSegment((cx-r-6,0),(cx-r-6,side))
        hit=resolve_annotation(image,{'symbol_family':'receptacle','bbox_xyxy':box},[wall])
        assert hit.kind=='wall_centerline_intersection'
        assert abs(hit.point[0]-(cx-r-6))<.001
        assert abs(hit.point[1]-cy)<1.6
        pump=resolve_annotation(image,{'symbol_family':'pump','bbox_xyxy':box},[wall])
        assert pump.kind=='bbox_center_provisional'
        assert pump.wall_index is None
        assert pump.quality=='provisional'


def test_explicit_cad_anchor_used_before_heuristic():
    image=np.full((100,100),255,np.uint8)
    ann={'symbol_family':'receptacle','bbox_xyxy':[40,40,60,60],
         'anchors':{'circle_center':{'xy':[51.25,48.75],
                                     'annotated':True,'visible':True}}}
    r=resolve_annotation(image,ann,[WallSegment((40,10),(40,90))])
    assert r.kind=='wall_centerline_intersection'
    assert r.point==pytest.approx((40,48.75))
    assert r.quality=='source_geometry'


def test_cli_resolves_mixed_discipline_raster(tmp_path):
    img=np.full((237,513),255,np.uint8)
    cv2.circle(img,(180,85),13,0,2,cv2.LINE_AA)
    imgpath=tmp_path/'plan.png'
    cv2.imwrite(str(imgpath),img)
    ann={'image_path':'plan.png','image_width':513,'image_height':237,
         'instances':[{'instance_id':'R1','symbol_family':'receptacle',
                       'bbox_xyxy':[164,69,196,101]},
                      {'instance_id':'F1','symbol_family':'fan',
                       'bbox_xyxy':[280,130,320,170]}]}
    annotation=tmp_path/'plan.json'
    annotation.write_text(json.dumps(ann))
    (tmp_path/'walls.json').write_text(json.dumps({'walls':[
        {'start':[160,0],'end':[160,220]}]}))
    output=tmp_path/'snaps.json'
    proc=subprocess.run([sys.executable,'scripts_resolve_snaps.py',
                          '--annotations',str(annotation),'--walls',str(tmp_path/'walls.json'),
                          '--output',str(output)],cwd=Path(__file__).resolve().parent.parent,
                        capture_output=True,text=True)
    assert proc.returncode==0, proc.stderr
    data=json.loads(output.read_text())
    assert data['image_width']==513
    assert data['instances'][0]['snap']['kind']=='wall_centerline_intersection'
    assert data['instances'][0]['snap']['point'][0]==160
    assert data['instances'][1]['discipline']=='hvac'
    assert data['instances'][1]['snap']['kind']=='bbox_center_provisional'


def test_invalid_wall_and_no_circle_not_misreported_as_precise():
    with pytest.raises(ValueError):
        WallSegment((1,2),(1,2))
    img=np.full((80,80),255,np.uint8)
    res=resolve_annotation(img,{'bbox_xyxy':[8,8,25,25],'symbol_family':'receptacle'})
    assert res.kind=='bbox_center_provisional'
    assert res.quality=='provisional'


def test_visual_center_is_not_falsely_called_circle_center():
    im=np.full((80,80),255,np.uint8)
    ann={'symbol_family':'air_handler','bbox_xyxy':[10,10,50,40],
         'anchors':{'visual_center':{'xy':[25,20],'visible':True,'annotated':True}}}
    res=resolve_annotation(im,ann)
    assert res.kind=='visual_center'
    assert res.point==(25,20)
    assert res.quality=='source_geometry'


def test_catalog_accepts_explicit_new_symbol_families():
    result=validated_classes(['receptacle','hydronic_fitting'],extra_families=('hydronic_fitting',))
    assert len(result)==2


def test_dataset_auditor_counts_real_families_without_restriction(tmp_path):
    from scripts_audit_annotations import audit
    annotation={'image_width':1237,'image_height':497,'source_type':'cad_raster',
                'project_family_id':'hydronic-project-1',
                'instances':[{'symbol_family':'hydronic_fitting','symbol_variant':'tee',
                              'bbox_xyxy':[200,50,220,74]},
                             {'symbol_family':'receptacle','symbol_variant':'duplex',
                              'bbox_xyxy':[42,20,58,36],
                              'anchors':{'circle_center':{'xy':[50,28],'visible':True,'annotated':True}}}]}
    file=tmp_path/'annotations.json'
    file.write_text(json.dumps(annotation))
    result,classes=audit([file])
    assert result['instances']==2
    assert result['family_counts']['hydronic_fitting']==1
    assert result['anchor_counts']['circle_center']==1
    assert set(classes)=={'hydronic_fitting','receptacle'}
