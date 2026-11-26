"""Offline smoke/regression tests. No downloads or neural-network training."""
from pathlib import Path
import json
import numpy as np
import cv2
import pytest
from blueprint_symbols.degradation import degrade,choose_profile,PROFILES
from blueprint_symbols.raster_training import read_records,RasterTileDataset
from blueprint_symbols.dataset_manifest import build_manifest
from blueprint_symbols.pseudolabel import verify_unlabeled_sources,consolidate_predictions
from scripts_export_yolo_tiles import run
from scripts_train_ultralytics import plan


def _three_page_manifest(tmp_path):
    ann=[]
    for i in range(3):
        img=np.full((245,315,3),255,np.uint8)
        # Unique photo-like background signatures so images don't hash equally.
        cv2.putText(img, f'BUILDING {i}', (3,235),cv2.FONT_HERSHEY_SIMPLEX,.4,(i*20,0,0),1)
        cv2.circle(img,(86,65),10,(0,0,0),1)
        cv2.line(img,(86,55),(86,75),(0,0,0),1)
        p=tmp_path/f'{i}.png';cv2.imwrite(str(p),img)
        data={'image_path':p.name,'image_width':315,'image_height':245,
              'project_family_id':f'bldg-{i}','source_group_verified':True,
              'source_type':'synthetic_verified','exhaustive_families':['receptacle'],
              'instances':[{'instance_id':f'i{i}','symbol_family':'receptacle','symbol_variant':'duplex',
                            'bbox_xyxy':[76,55,96,75]}]}
        j=tmp_path/f'{i}.json';j.write_text(json.dumps(data));ann.append(j)
    manifest=build_manifest(ann)
    path=tmp_path/'manifest.json';path.write_text(json.dumps(manifest))
    return path,manifest,ann


def test_raster_damage_shape_reproducible_and_effectful():
    img=np.full((123,197,3),246,np.uint8)
    cv2.circle(img,(80,40),11,(20,20,20),2)
    clean,r=degrade(img,np.random.default_rng(1),profile='clean')
    assert np.array_equal(clean,img) and r.downsample_fraction==1.
    for name in PROFILES[1:]:
        a,report=degrade(img,np.random.default_rng(55),profile=name)
        b,_=degrade(img,np.random.default_rng(55),profile=name)
        assert a.shape==img.shape and a.dtype==np.uint8
        assert np.array_equal(a,b)
        assert (a != img).any()
        assert report.downsample_fraction<=1.0
    with pytest.raises(ValueError):degrade(img,np.random.default_rng(1),profile='unlisted')


def test_symbol_target_bands_reach_twenty_pixels(tmp_path):
    path,manifest,_=_three_page_manifest(tmp_path)
    records=read_records(manifest['splits']['train'])
    ds=RasterTileDataset(records,{'receptacle.duplex':0},size=512,
        virtual_tiles_per_image=10,target_symbol_pixels=((20,20,1.),),
        degradation_profile='clean')
    got=[]
    for i in range(len(ds)):
        im,gt,info=ds.sample(i)
        if info['target_symbol_pixels'] is not None:
            got.append(info['target_symbol_pixels'])
            assert info['damage']['profile']=='clean'
            for _,box in gt:
                assert max(box[2]-box[0],box[3]-box[1])==pytest.approx(20,abs=.01)
    assert len(got)>0 and set(got)=={20.0}


def test_export_heldout_clean_stress_and_completeness_guard(tmp_path):
    path,manifest,ann=_three_page_manifest(tmp_path)
    record=json.loads(ann[0].read_text());record.pop('exhaustive_families')
    ann[0].write_text(json.dumps(record))
    path.write_text(json.dumps(build_manifest(ann)))  # Rebuild after changing the source.
    with pytest.raises(ValueError,match='exhaustive_families'):
        run(path,tmp_path/'rejected',samples=4)
    # No label completeness exceptions for the actual audited source.
    record['exhaustive_families']=['receptacle'];ann[0].write_text(json.dumps(record))
    path.write_text(json.dumps(build_manifest(ann)))  # Restore the verified source snapshot.
    report=run(path,tmp_path/'yolo',samples=8,stress_val=True,stress_test=True)
    assert report['counts']['train_images']>0
    assert report['counts']['val_images']>0 and report['counts']['test_images']>0
    assert report['counts']['val_stress_extreme_images']>0
    assert report['unverified_annotation_completeness']==[]
    train_meta=[json.loads(x) for x in (tmp_path/'yolo'/'sample_provenance.jsonl').read_text().splitlines()]
    train_meta=[x for x in train_meta if x['split']=='train']
    assert any(x['damage'] is not None for x in train_meta)
    assert any(16<=x['target_symbol_pixels']<=40 for x in train_meta if x['target_symbol_pixels'])
    assert len(set(x['group'] for x in train_meta))==1
    with pytest.raises(FileExistsError):run(path,tmp_path/'yolo',samples=8)


def test_teacher_candidates_never_become_ground_truth_and_holdouts_blocked(tmp_path):
    path,manifest,ann=_three_page_manifest(tmp_path)
    eval_group=manifest['split_groups']['test'][0]
    eval_file=next(x['image'] for x in manifest['source_images'] if x['project_group']==eval_group)
    with pytest.raises(ValueError,match='evaluation project group'):
        verify_unlabeled_sources([{'image_path':eval_file,'project_family_id':eval_group,
                                   'source_group_verified':True}],manifest)
    with pytest.raises(ValueError,match='duplicates held-out'):
        verify_unlabeled_sources([{'image_path':eval_file,'project_family_id':'unrelated',
                                   'source_group_verified':True}],manifest)
    items=[{'symbol_family':'receptacle','bbox_xyxy':[10,10,30,30],
            'confidence':.8,'source_scale':1.0},
           {'symbol_family':'receptacle','bbox_xyxy':[11,11,30,30],
            'confidence':.72,'source_scale':1.5},
           {'symbol_family':'valve','bbox_xyxy':[11,11,30,30],
            'confidence':.9,'source_scale':1.0}]
    # Same spot, two classes: the stronger class is kept, the other is an alternative.
    [top]=consolidate_predictions(items)
    assert top['symbol_family']=='valve'
    assert top['alternative_labels']==[{'symbol_family':'receptacle','confidence':.8}]
    merged=consolidate_predictions(items,cross_class_iou=None)
    assert len(merged)==2
    r=next(x for x in merged if x['symbol_family']=='receptacle')
    assert r['multiscale_agreement'] and r['uncertain'] and r['review_required']
    assert r['source']['not_ground_truth']


def test_six_gb_launcher_never_trains_without_explicit_run(tmp_path):
    data=tmp_path/'data.yaml';data.write_text('names:\n  0: receptacle\n')
    conf=plan(data)
    assert conf['batch']==2 and conf['imgsz']==512 and conf['amp']
    assert conf['architecture']=='yolo26n-p2.yaml' and conf['cache'] is False
    assert conf['pretrained'] is False and conf['initialization']=='random'
    assert 'pretrained_weights' not in conf
