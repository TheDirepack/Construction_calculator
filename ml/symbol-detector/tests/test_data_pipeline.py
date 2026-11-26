import json
import math
from pathlib import Path
import cv2
import numpy as np
import pytest

from blueprint_symbols.multiscale import plan_pyramid_tiles, sample_tile, ScaleTile
from blueprint_symbols.cad_labels import PixelTransform, labels_from_dxf
from blueprint_symbols.raster_training import read_records


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
    ezdxf = pytest.importorskip("ezdxf")
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


from blueprint_symbols.crops import CropTransform


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
    recs=read_records([Path(str(tmp_path/'converted/image_00000001_labels.json')+'#p1')])
    assert recs[0].group=='family1'
    assert len(recs[0].instances)==2
    assert recs[0].instances[0]['bbox_xyxy']==[3,4,10,12]
    assert not recs[0].instances[0]['anchors']
