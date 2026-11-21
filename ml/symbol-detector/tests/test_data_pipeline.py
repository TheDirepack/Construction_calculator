import json
import math
from pathlib import Path
import cv2
import numpy as np
import pytest
import torch

from blueprint_symbols.multiscale import plan_pyramid_tiles, sample_tile, ScaleTile
from blueprint_symbols.cad_labels import PixelTransform, labels_from_dxf
from blueprint_symbols.synthetic import SymbolAsset, generate_sheet, write_sheet
from blueprint_symbols.raster_training import read_records, RasterTileDataset, group_split
from blueprint_symbols.models import SymbolDetector
from blueprint_symbols.training import detector_loss


@pytest.mark.parametrize('width,height', [(1920,1080),(3840,2160),(5137,1179),(701,3173),(211,409)])
def test_any_resolution_and_aspect_ratio_full_coverage(width,height):
    tiles=plan_pyramid_tiles(width,height,tile_size=128,overlap=32,source_scales=(.5,1.,2.))
    assert len(tiles)>0
    for s in (.5,1.,2.):
        layer=[t for t in tiles if t.source_scale==s]
        assert layer[0].x == 0 and layer[0].y == 0
        assert max(t.source_box[2] for t in layer)==width
        assert max(t.source_box[3] for t in layer)==height
        for _ in range(100):
            rng=np.random.default_rng(int(width+height+s*100))
            x,y=rng.uniform(0,width),rng.uniform(0,height)
            assert any(t.x<=x<=t.x+t.size*s and t.y<=y<=t.y+t.size*s for t in layer)


def test_sampling_scale_and_subpixel_coordinate_mapping():
    img=np.full((53,119),255,np.uint8)
    img[21,81]=0
    tile=ScaleTile(50.,0.,1.,80,119,53)
    patch=sample_tile(img,tile)
    assert patch[21,31]==0
    assert tile.to_page_point(31.5,21.5)==(81.5,21.5)
    big=ScaleTile(40,0,2.,80,119,53)
    assert big.source_box[2] == 119
    # Not a page-wide resize; model input shape is constant.
    assert sample_tile(img,big).shape==(80,80)


def test_math_maps_y_up_cad_to_y_down_pixels_and_inverse():
    tx=PixelTransform.from_extents((10,20,110,70),400,400)
    assert np.allclose(tx.point((10,70)),(0,100))
    assert np.allclose(tx.point((110,20)),(400,300))
    assert np.allclose(tx.inverse().point(tx.point((37,46))),(37,46))
    assert np.allclose(tx.bbox((10,20,20,30)),(0,260,40,300))
    affine=PixelTransform(np.array([[2,0,10],[0,3,-8],[0,0,1]]))
    assert affine.point((4,5)) == (18,7)
    H=PixelTransform(np.array([[1,0,0],[0,1,0],[.001,0,1]]))
    assert H.point((100,0))[0] == pytest.approx(100/1.1)
    with pytest.raises(ValueError):
        PixelTransform(np.zeros((3,3)))


def test_dxf_insert_auto_labels_include_rotation_and_anchors(tmp_path):
    import ezdxf
    doc=ezdxf.new()
    b=doc.blocks.new('RCP_DUPLEX',base_point=(0,0))
    b.add_circle((0,0),1.)
    # Raster transform gives known image coords, no guessed registration.
    doc.modelspace().add_blockref('RCP_DUPLEX',(10,20),dxfattribs={'rotation':90})
    doc.modelspace().add_blockref('UNKNOWN',(40,50))
    fn=tmp_path/'test.dxf'
    doc.saveas(fn)
    tx=PixelTransform(np.array([[10,0,0],[0,-10,500],[0,0,1]]))
    labels=labels_from_dxf(fn,{'RCP_DUPLEX':{
        'symbol_family':'receptacle','symbol_variant':'duplex',
        'anchors_local':{'insert_origin':[0,0],'outward':[2,0]}}},tx)
    assert len(labels.instances)==1
    a=labels.instances[0]
    assert a['bbox_xyxy']==pytest.approx([90,290,110,310])
    assert a['anchors']['insert_origin']['xy']==pytest.approx([100,300])
    assert a['anchors']['outward']['xy']==pytest.approx([100,280])
    assert a['rotation_degrees_image']==pytest.approx(-90)
    assert labels.unknown_blocks=={'UNKNOWN':1}


def test_synthetic_and_arbitrary_raster_crop_with_source_group(tmp_path):
    symbol=np.zeros((21,21,4),np.uint8)
    cv2.circle(symbol,(10,10),7,(0,0,0,255),2)
    asset=SymbolAsset('receptacle','duplex',symbol,{'center':(.5,.5),'offset':(.75,.5)})
    img,labels=generate_sheet([asset],width=707,height=399,count=9,seed=22)
    assert len(labels)==9
    for l in labels:
        a,b,c,d=l['bbox_xyxy']
        assert 0<=a<c<=707 and 0<=b<d<=399
        assert l['anchors']['center']['annotated']
    annotation=write_sheet(tmp_path/'one.png',img,labels,group='plan-family-1',seed=22)
    recs=read_records([annotation])
    assert len(recs)==1
    data=RasterTileDataset(recs,{'receptacle':0},size=128,source_scales=(.5,2.),seed=3)
    for idx in range(3):
        patch,tgts,meta=data[idx]
        assert tuple(patch.shape)==(3,128,128)
        assert (patch>=0).all() and (patch<=1).all()
        for _,b in tgts:
            assert 0<=b[0]<b[2]<=128 and 0<=b[1]<b[3]<=128
    a,b=group_split(recs,val_fraction=.5)
    assert len(a)+len(b)==1


def test_partial_objects_are_ignored_in_training_loss():
    heads=[]
    for div in (4,8,16):
        pred=torch.zeros((1,7,64//div,64//div),requires_grad=True)
        heads.append(pred)
    gt=[[(0,(17,17,27,27))]]
    ignore=[[(50,5,64,22)]]
    loss,_=detector_loss(heads,gt,(64,64),ignore_boxes=ignore)
    assert torch.isfinite(loss)
    loss.backward()
    assert heads[0].grad is not None
    # An ignored cell is *not* trained as negative objectness.
    assert heads[0].grad[0,0,2,14].item()==0
    with pytest.raises(ValueError):
        detector_loss(heads,gt,(64,64),ignore_boxes=[])

from blueprint_symbols.precision_training import anchor_targets,precision_example,precision_loss
from blueprint_symbols.crops import CropTransform
from blueprint_symbols.models import AnchorRefiner


def test_precision_ground_truth_from_cad_pixel_anchors_and_missing_mask():
    image=np.full((180,331,3),255,np.uint8)
    annotation={'bbox_xyxy':[100,60,130,90], 'anchors':{
        'insert_origin':{'xy':[115,75],'visible':True,'annotated':True},
        'wall_attachment':{'xy':[109,63],'visible':True,'annotated':True}}}
    crop,t,valid,xform=precision_example(image,annotation,
                        ('insert_origin','wall_attachment','missing'),patch_size=160)
    assert tuple(crop.shape)==(3,160,160)
    assert tuple(t.shape)==(3,40,40)
    assert valid.tolist()==[1,1,0]
    assert t[2].sum()==0
    # map heatmap peak back into original page coordinates
    from blueprint_symbols.geometry import heatmap_peak
    approx=heatmap_peak(t[0].numpy(),160,160)
    mapped=xform.to_page(*approx)
    assert np.linalg.norm(np.array(mapped)-[115,75])<.6
    model=AnchorRefiner(3)
    pred=model(crop[None,...])
    loss=precision_loss(pred,t[None,...],valid[None,...])
    assert torch.isfinite(loss)
    loss.backward()
    assert model.heatmaps.weight.grad is not None
    # No fake training for segmentation (last channel) without vector masks.
    assert model.heatmaps.weight.grad[-1].abs().sum().item()==0


def test_excluded_unknown_instance_not_treated_as_negative(tmp_path):
    img=np.full((300,300,3),255,np.uint8)
    cv2.imwrite(str(tmp_path/'mix.png'),img)
    meta={'image_path':'mix.png','image_width':300,'image_height':300,
          'project_family_id':'family', 'instances':[
          {'symbol_family':'unknown_variant','bbox_xyxy':[120,120,180,180]}]}
    (tmp_path/'mix.json').write_text(json.dumps(meta))
    dataset=RasterTileDataset(read_records([tmp_path/'mix.json']),
                              {'receptacle':0},size=256,source_scales=(1.,1.))
    patch,gt,info=dataset[0]
    assert gt == []
    assert info['ignore_boxes']
from blueprint_symbols.coco_import import convert_coco


def test_coco_import_labels_preserves_unmapped_as_ignore_and_group(tmp_path):
    im=np.full((41,73,3),255,np.uint8)
    cv2.imwrite(str(tmp_path/'page.png'),im)
    coco={'images':[{'id':1,'file_name':'page.png','width':73,'height':41}],
          'categories':[{'id':4,'name':'switch'},{'id':5,'name':'window'}],
          'annotations':[{'id':11,'image_id':1,'category_id':4,'bbox':[3,4,7,8]},
                         {'id':12,'image_id':1,'category_id':5,'bbox':[50,15,5,7]}]}
    ann=tmp_path/'coco.json'
    ann.write_text(json.dumps(coco))
    counts=convert_coco(ann,tmp_path,tmp_path/'converted',{'switch':'switch'},
                        project_groups={'page.png':'family1'})
    assert counts['mapped_instances']==1 and counts['unmapped_instances']==1
    recs=read_records([tmp_path/'converted/image_00000001.json'])
    assert recs[0].group=='family1'
    assert len(recs[0].instances)==2
    assert recs[0].instances[0]['bbox_xyxy']==[3,4,10,12]
    assert not recs[0].instances[0]['anchors']
    data=RasterTileDataset(recs,{'switch':0},size=128,source_scales=(1.,1.),seed=2)
    _,target,info=data[0]
    assert target and info['ignore_boxes']
