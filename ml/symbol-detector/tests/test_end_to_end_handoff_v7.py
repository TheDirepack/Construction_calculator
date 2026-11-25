"""No model downloads or training; only our blueprint source annotations."""
import json
from pathlib import Path
import cv2
import numpy as np
import pytest
from scripts_prepare_training_data import prepare, discover
from scripts_train_ultralytics import plan
from scripts_verify_yolo_export import verify_export


def _source_data(base: Path):
    paths=[]
    for i in range(5):
        im=np.full((216,268,3),255,np.uint8)
        cv2.circle(im,(70,65),10,(10,10,10),2)
        cv2.putText(im, f'P{i}',(12,203),cv2.FONT_HERSHEY_SIMPLEX,.6,(i*30,0,0),2)
        ip=base/f'page{i}.png';assert cv2.imwrite(str(ip),im)
        ann=base/f'page{i}.json'
        ann.write_text(json.dumps({
            'image_path':ip.name,'image_width':268,'image_height':216,
            'source_group_verified':True,'project_family_id':f'plan_family_{i}',
            'source_type':'CAD-derived','exhaustive_families':['receptacle'],
            'instances':[{'symbol_family':'receptacle','symbol_variant':'duplex','bbox_xyxy':[58,53,82,77],
                          'source':{'kind':'cad_svg'}}],
        }))
        paths.append(ann)
    return paths


def test_convert_blueprint_source_annotations_to_yolo_format_without_yolo_sources(tmp_path):
    paths=_source_data(tmp_path)
    assert len(discover([str(tmp_path/'page*.json')])) == 5
    out=tmp_path/'exported'
    result=prepare([str(tmp_path/'page*.json')], out, train_tiles_per_page=2,
                   stress_val=True, stress_test=True)
    assert result['training_performed'] is False
    assert result['pretrained_general_models_used'] is False
    assert result['verification']['ok']
    assert len(result['source_groups'])==3
    assert all(result['source_groups'][k] >= 1 for k in ('train','val','test'))
    assert (out/'yolo'/'data.yaml').exists()
    assert (out/'yolo'/'evaluation_configs'/'test_stress_lowres_25.yaml').exists()
    assert verify_export(out/'yolo')['ok']
    conf=plan(out/'yolo'/'data.yaml')
    assert conf['pretrained'] is False and conf['initialization']=='random'
    with pytest.raises(FileExistsError):
        prepare([str(tmp_path/'page*.json')],out,train_tiles_per_page=2)


def test_verify_export_rejects_invalid_label_and_missing_images(tmp_path):
    _source_data(tmp_path)
    out=tmp_path/'exported'
    prepare([str(tmp_path/'page*.json')],out,train_tiles_per_page=2)
    ds=out/'yolo'
    label=next((ds/'labels'/'val').glob('*.txt'))
    label.write_text('999 0.4 0.5 0.1 0.1\n')
    with pytest.raises(ValueError,match='range'):
        verify_export(ds)
