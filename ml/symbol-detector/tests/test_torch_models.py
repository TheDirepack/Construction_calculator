"""Model and training tests. They need PyTorch, which only the training machine installs
(requirements-train.txt); without it they are skipped."""
import json

import cv2
import numpy as np
import pytest

torch = pytest.importorskip("torch")

from blueprint_symbols.models import AnchorRefiner, SymbolDetector, parameter_counts
from blueprint_symbols.precision_training import precision_example, precision_loss
from blueprint_symbols.raster_training import RasterTileDataset, group_split, read_records
from blueprint_symbols.synthetic import SymbolAsset, generate_sheet, write_sheet
from blueprint_symbols.coco_import import convert_coco
from blueprint_symbols.training import detector_loss


def test_detector_shapes_and_param_budget():
    with torch.no_grad():
        d = SymbolDetector(14).eval()
        outs = d(torch.zeros(1, 3, 128, 128))
    assert [tuple(x.shape) for x in outs] == [(1, 19, 32, 32), (1, 19, 16, 16), (1, 19, 8, 8)]
    assert parameter_counts()['symbol_detector'] < 1_000_000


def test_refiner_shapes_and_param_budget():
    with torch.no_grad():
        model = AnchorRefiner(4).eval()
        out = model(torch.zeros(1, 3, 160, 160))
    assert tuple(out.shape) == (1, 5, 40, 40)
    assert parameter_counts()['anchor_refiner'] < 100_000


def test_training_loss_backward_and_density_warning():
    torch.manual_seed(1)
    model = SymbolDetector(3)
    outputs = model(torch.zeros((1,3,128,128)))
    gt = [[(1,(20.,18.,36.,32.)), (0,(72.,68.,90.,84.))]]
    loss, stats = detector_loss(outputs, gt, (128,128))
    assert stats == {'assigned':2, 'collisions':0}
    assert torch.isfinite(loss)
    loss.backward()
    assert model.head2[-1].weight.grad is not None
    assert torch.isfinite(model.head2[-1].weight.grad).all()
    # Same center feature-grid cell cannot represent two separate objects.
    _, stats2 = detector_loss(outputs, [[(0,(20.,18.,36.,32.)),(1,(21.,18.,35.,32.))]], (128,128))
    assert stats2['collisions'] == 1


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


def test_coco_import_ignores_unmapped_categories_in_training_tiles(tmp_path):
    im=np.full((41,73,3),255,np.uint8)
    cv2.imwrite(str(tmp_path/'page.png'),im)
    coco={'images':[{'id':1,'file_name':'page.png','width':73,'height':41}],
          'categories':[{'id':4,'name':'switch'},{'id':5,'name':'window'}],
          'annotations':[{'id':11,'image_id':1,'category_id':4,'bbox':[3,4,7,8]},
                         {'id':12,'image_id':1,'category_id':5,'bbox':[50,15,5,7]}]}
    ann=tmp_path/'coco.json'
    ann.write_text(json.dumps(coco))
    convert_coco(ann,tmp_path,tmp_path/'converted',{'switch':'switch'},project_groups={'page.png':'family1'})
    recs=read_records([tmp_path/'converted/image_00000001.json'])
    data=RasterTileDataset(recs,{'switch':0},size=128,source_scales=(1.,1.),seed=2)
    _,target,info=data[0]
    assert target and info['ignore_boxes']
