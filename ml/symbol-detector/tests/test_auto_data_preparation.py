"""Source-format and leakage tests; absolutely no training."""
from pathlib import Path
import json
import cv2
import numpy as np
import importlib.util
import pytest
from blueprint_symbols.primitive_labels import convert_primitive_json
from blueprint_symbols.svg_labels import svg_to_image,matrix_for
from blueprint_symbols.dataset_manifest import build_manifest
from blueprint_symbols.cad_labels import labels_from_dxf,PixelTransform
from blueprint_symbols.crops import CropTransform


needs_torch = pytest.mark.skipif(importlib.util.find_spec('torch') is None,
                                 reason='training code; PyTorch is only on the training machine')


def _img(folder,name='page.png',shape=(400,500)):
    path=folder/name
    cv2.imwrite(str(path),np.full((*shape,3),255,np.uint8))
    return path


def test_archcad_primitive_groups_circle_and_explicit_ignored_stuff(tmp_path):
    img=_img(tmp_path)
    annotated=[
        {'type':'LINE','start':[50,50],'end':[90,50], 'semantic':'receptacle','instance':'rcp-1'},
        {'type':'CIRCLE','center':[70,70],'radius':10, 'semantic':'receptacle','instance':'rcp-1'},
        {'type':'LINE','start':[200,20],'end':[200,380],'semantic':'wall','instance':-1},
        {'type':'CIRCLE','center':[140,140],'radius':5, 'semantic':'valve','instance':'v1'},
        {'type':'LINE','start':[230,220],'end':[240,230],'semantic':'mystery','instance':'u1'},
        {'type':'LINE','start':[245,230],'end':[265,245],'semantic':'mystery','instance':'u1'},
    ]
    src=tmp_path/'arch.json'; src.write_text(json.dumps(annotated))
    out,report=convert_primitive_json(src,img,{'receptacle':'receptacle','valve':'valve'},project_group='building-A')
    assert report['countable_instance_keys']==3
    assert report['mapped']==2 and report['unmapped']==1
    r=next(x for x in out['instances'] if x['symbol_family']=='receptacle')
    assert r['bbox_xyxy']==[50,50,90,80]
    assert r['anchors']['circle_center']['xy']==[70,70]
    assert 'wall_attachment' not in r['anchors']
    u=next(x for x in out['instances'] if x['symbol_family']=='__unmapped__')
    assert u['uncertain']
    assert report['warnings']['no_instance_id']==1
    assert out['context_primitives'][0]['semantic']=='wall'


def test_archcad_missing_labels_fails_not_empty_negative_sheet(tmp_path):
    img=_img(tmp_path)
    src=tmp_path/'empty.json'
    src.write_text(json.dumps([{'type':'CIRCLE','center':[20,20],'radius':2,
                               'semantic':None,'instance':None}]))
    with pytest.raises(ValueError,match='NO_ANNOTATIONS'):
        convert_primitive_json(src,img,{},project_group='x')


def test_svg_nested_transform_viewbox_and_circle(tmp_path):
    image=_img(tmp_path,shape=(200,400))
    svg='''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 50">
      <g transform="translate(10 5)">
        <circle cx="10" cy="10" r="3" semanticId="7" instanceId="rcp1" stroke-width="1"/>
        <line x1="0" y1="10" x2="20" y2="10" semanticId="7" instanceId="rcp1"/>
        <rect x="50" y="20" width="10" height="10" semanticId="42" instanceId="thing1"/>
      </g>
    </svg>'''
    src=tmp_path/'floorplancad.svg';src.write_text(svg)
    labels,stats=svg_to_image(src,image,{'7':'receptacle'},project_group='whole-building-x')
    r=next(v for v in labels['instances'] if v['symbol_family']=='receptacle')
    assert r['anchors']['circle_center']['xy']==pytest.approx([80,60])
    assert r['bbox_xyxy'][0] == pytest.approx(38)
    assert r['bbox_xyxy'][2] == pytest.approx(122)
    assert stats['mapped']==1 and stats['exported_objects']==2
    assert sum(1 for x in labels['instances'] if x['uncertain'])==1


def test_svg_group_class_cubicasa_requires_explicit_opt_in(tmp_path):
    im=_img(tmp_path)
    src=tmp_path/'casa.svg';src.write_text('''<svg viewBox="0 0 500 400"><g id="plumbing-1" class="FixedFurniture Toilet">
      <rect x="55" y="65" width="22" height="27" /></g></svg>''')
    with pytest.raises(ValueError,match='NO_INSTANCES'):
        svg_to_image(src,im,{'FixedFurniture Toilet':'plumbing_fixture'},project_group='casa1')
    result,_=svg_to_image(src,im,{'FixedFurniture Toilet':'plumbing_fixture'},
                          project_group='casa1',include_group_classes=True)
    assert result['instances'][0]['symbol_family']=='plumbing_fixture'
    assert result['instances'][0]['anchors']=={}


def test_manifest_requires_three_real_groups_and_duplicate_hash_rejection(tmp_path):
    files=[]
    for i in range(3):
        # Different pixels across groups.
        img=np.full((20,30,3),i*10,np.uint8)
        image=tmp_path/f'im{i}.png';cv2.imwrite(str(image),img)
        doc={'image_path':image.name,'image_width':30,'image_height':20,
             'project_family_id':f'full_plan_{i}','source_group_verified':True,
             'instances':[{'symbol_family':'receptacle.duplex','bbox_xyxy':[2,2,8,8]}]}
        f=tmp_path/f'anno{i}.json';f.write_text(json.dumps(doc));files.append(f)
    manifest=build_manifest(files)
    assert set(manifest['splits'])=={'train','val','test'}
    assert all(manifest['splits'].values())
    assert sorted(manifest['class_ids'])==['receptacle.duplex']
    assert {g for groups in manifest['split_groups'].values() for g in groups}=={'full_plan_0','full_plan_1','full_plan_2'}
    a=json.loads(files[1].read_text());a['image_path']='im0.png';files[1].write_text(json.dumps(a))
    with pytest.raises(ValueError,match='leakage'):
        build_manifest(files)


def test_manifest_disallows_unverified_group_and_unknown_classes(tmp_path):
    files=[]
    for i in range(3):
        f=tmp_path/f'{i}.png';cv2.imwrite(str(f),np.full((32,32,3),i,np.uint8))
        j=tmp_path/f'{i}.json'
        j.write_text(json.dumps({'image_path':f.name,'image_width':32,'image_height':32,
                                  'project_family_id':f'grp{i}','source_group_verified':i!=1,
                                  'instances':[{'symbol_family':'__unmapped__','uncertain':True,
                                                'bbox_xyxy':[5,5,10,10]}]}))
        files.append(j)
    with pytest.raises(ValueError,match='unverified project grouping'):
        build_manifest(files)
    payload=json.loads(files[1].read_text());payload['source_group_verified']=True
    files[1].write_text(json.dumps(payload))
    with pytest.raises(ValueError,match='no mapped'):
        build_manifest(files)


def test_dxf_unique_circle_produces_anchor_not_insertion_origin(tmp_path):
    ezdxf = pytest.importorskip("ezdxf")
    doc=ezdxf.new();block=doc.blocks.new('VALVE_SYMBOL')
    block.add_circle((2,1),1)
    doc.modelspace().add_blockref('VALVE_SYMBOL',(10,10))
    path=tmp_path/'valve.dxf';doc.saveas(path)
    tx=PixelTransform(np.eye(3))
    labels=labels_from_dxf(path,{'VALVE_SYMBOL':{'symbol_family':'valve'}},tx)
    a=labels.instances[0]['anchors']
    assert a['circle_center']['xy']==[12,11]
    assert 'insert_origin' not in a


def test_raw_inspector_distinguishes_empty_release_and_actual_labels(tmp_path):
    from scripts_inspect_raw_datasets import inspect_file,inspect
    empty=tmp_path/'preview.json'
    empty.write_text(json.dumps({'semantic_labels':[], 'instance_labels':[]}))
    x=inspect_file(empty)
    assert x['format']=='archcad_array_labels'
    assert x['warning']
    good=tmp_path/'labeled.svg'
    good.write_text('<svg viewBox="0 0 10 10"><circle cx="2" cy="2" r="1" semanticId="3" instanceId="2" /></svg>')
    y=inspect_file(good)
    assert y['counts']['instance_primitives']==1
    report=inspect([good,empty]);assert report['files_inspected']==2
    assert report['semantic_counts']['3']==1


def test_photo_degradation_preserves_coordinates_and_warp_moves_anchors():
    from blueprint_symbols.augmentation import photometric,perspective_warp
    im=np.full((120,230,3),220,np.uint8)
    item={'bbox_xyxy':[40,30,70,60],'anchors':{'circle_center':{'annotated':True,'visible':True,'xy':[55,45]}}}
    out=photometric(im,np.random.default_rng(1))
    assert out.shape==im.shape
    H=np.array([[1,0,15],[0,1,8],[0,0,1.]],float)
    warped,labels=perspective_warp(im,[item],H)
    assert warped.shape==im.shape
    assert labels[0]['bbox_xyxy']==[55,38,85,68]
    assert labels[0]['anchors']['circle_center']['xy']==[70,53]
    assert item['anchors']['circle_center']['xy']==[55,45]

@needs_torch
def test_jittered_keypoint_targets_follow_crop_not_bbox_center():
    from blueprint_symbols.precision_training import jittered_precision_example
    img=np.full((210,210,3),255,np.uint8)
    inst={'bbox_xyxy':[70,80,110,120], 'anchors':{'circle_center':{'xy':[90,100], 'annotated':True,'visible':True}}}
    patch,heat,valid,crop=jittered_precision_example(img,inst,('circle_center',),seed=2)
    assert valid.tolist()==[1]
    from blueprint_symbols.geometry import heatmap_peak
    xy=crop.to_page(*heatmap_peak(heat[0].numpy(),160,160))
    assert np.linalg.norm(np.array(xy)-[90,100])<.6


def test_svg_wall_semantics_without_instance_preserved_as_context(tmp_path):
    im=_img(tmp_path)
    svg=tmp_path/'walls.svg'
    svg.write_text('<svg viewBox="0 0 500 400"><line x1="10" y1="40" x2="300" y2="40" semanticId="wall" instanceId="-1"/><circle cx="20" cy="20" r="2" semanticId="sym" instanceId="9"/></svg>')
    record,report=svg_to_image(svg,im,{'sym':'receptacle'},project_group='p')
    assert len(record['instances'])==1
    assert report['context_primitives']==1
    assert record['context_primitives'][0]['points_xy']==[[10,40],[300,40]]


@needs_torch
def test_precision_dataset_only_uses_supported_anchor_types(tmp_path):
    from blueprint_symbols.precision_dataset import PrecisionDataset,collate_precision
    from blueprint_symbols.raster_training import read_records
    img=_img(tmp_path)
    rec={'image_path':img.name,'image_width':500,'image_height':400,
         'project_family_id':'p1','instances':[
             {'instance_id':'one','bbox_xyxy':[10,10,40,40],
              'anchors':{'circle_center':{'xy':[25,25],'annotated':True,'visible':True}}},
             {'instance_id':'two','bbox_xyxy':[60,60,90,90],
              'anchors':{'wall_attachment':{'xy':[70,60],'annotated':False,'visible':True}}},
             {'instance_id':'three','bbox_xyxy':[100,100,140,140],
              'uncertain':True,'anchors':{'circle_center':{'xy':[120,120],'annotated':True,'visible':True}}}]}
    meta=tmp_path/'page.json';meta.write_text(json.dumps(rec))
    ds=PrecisionDataset(read_records([meta]),('circle_center','wall_attachment'),seed=4)
    assert len(ds)==1
    x,y,mask,info=ds[0]
    assert tuple(x.shape)==(3,160,160)
    assert mask.tolist()==[1,0]
    assert info['instance_id']=='one'
