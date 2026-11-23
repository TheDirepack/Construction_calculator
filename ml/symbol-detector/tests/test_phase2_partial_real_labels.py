"""Phase-two real positives/negatives and incomplete real labels stay safe."""
from __future__ import annotations

import json
from pathlib import Path

import cv2
import numpy as np
import pytest

from blueprint_symbols.dataset_manifest import build_manifest
from blueprint_symbols.partial_supervision import read_supervision
from scripts_export_yolo_tiles import run


def mixed_source_manifest(tmp_path: Path):
    paths=[]
    for i in range(5):
        image=np.full((512,512,3),243,np.uint8)
        for x in range(65,500,82):
            cv2.line(image,(x,35),(x,475),(25,25,25),3)
        cv2.circle(image,(240,240),14,(0,0,0),2)
        cv2.circle(image,(370,370),14,(0,0,0),2)
        cv2.putText(image,str(i),(25,40),cv2.FONT_HERSHEY_SIMPLEX,.7,(0,0,0),2)
        image_path=tmp_path/f'drawing_{i}.png'
        assert cv2.imwrite(str(image_path),image)
        instances=[] if i==3 else [
            {'instance_id':f'{i}:0','symbol_family':'receptacle',
             'bbox_xyxy':[225,225,255,255],'uncertain':False}
        ]
        # i=0 has a genuine, UNANNOTATED second symbol. It must produce
        # positive-only supervision, not an all-background YOLO target.
        annotations={
            'image_path':image_path.name,'image_width':512,'image_height':512,
            'project_family_id':f'building-{i}','source_group_verified':True,
            'source_type':('real_partially_labeled' if i==0 else
                           'verified_real_negative' if i==3 else
                           'real_verified_positive' if i==1 else
                           'synthetic_verified'),
            'exhaustive_families':([] if i==0 else ['receptacle']),
            'instances':instances,
        }
        label_path=tmp_path/f'drawing_{i}.json'
        label_path.write_text(json.dumps(annotations))
        paths.append(label_path)
    mf=build_manifest(paths,val_fraction=.20,test_fraction=.20,
                      allow_partial_training=True)
    out=tmp_path/'manifest.json'
    out.write_text(json.dumps(mf))
    return out,mf


def test_partial_real_group_always_training_and_heldout_complete(tmp_path):
    manifest, data=mixed_source_manifest(tmp_path)
    assert 'building-0' in data['split_groups']['train']
    all_groups={
        split:set(data['split_groups'][split])
        for split in ('train','val','test')
    }
    assert not (all_groups['train'] & all_groups['val'])
    assert not (all_groups['train'] & all_groups['test'])
    assert not (all_groups['val'] & all_groups['test'])
    assert all(set(r['exhaustive_families'])=={'receptacle'}
               for r in data['source_images']
               if r['project_group'] in all_groups['val']|all_groups['test'])


def test_export_retains_partial_positive_without_false_negative_mask(tmp_path):
    source,mf=mixed_source_manifest(tmp_path)
    report=run(source,tmp_path/'out',samples=6,train_variants=1,
               allow_partial_training=True,
               train_negative_fraction=1.,blank_negative_fraction=1.,
               eval_empty_keep_fraction=1.,eval_blank_keep_fraction=1.)
    assert report['partial_training_enabled']
    assert report['counts']['train_partial_images']>0
    out=tmp_path/'out'
    supervision=read_supervision(out/'data.yaml')
    assert supervision is not None and supervision['has_partial']
    rows=[json.loads(line) for line in
          (out/'sample_provenance.jsonl').read_text().splitlines()]
    partial=[x for x in rows if x['split']=='train' and x.get('partial_positive_only')]
    assert partial
    assert all(x['verified_negative_class_ids']==[] for x in partial)
    assert all(supervision['samples'][x['sample']]==[] for x in partial)
    # Every partial tile exported into training has a known positive: no
    # unlabeled-only crop is assigned an empty-label negative.
    assert all((out/'labels'/'train'/f"{x['sample']}.txt").read_text().strip()
               for x in partial)
    assert report['counts'].get('train_skipped_uninformative_partial', 0)>=0


def test_partial_without_masked_mode_fails_closed(tmp_path):
    source,_=mixed_source_manifest(tmp_path)
    with pytest.raises(ValueError,match='exhaustive_families'):
        run(source,tmp_path/'reject',samples=2)
    with pytest.raises(ValueError,match='cannot combine'):
        run(source,tmp_path/'reject2',samples=2,
            allow_partial_training=True,allow_unverified_completeness=True)


def test_no_partial_group_in_locked_evaluation(tmp_path):
    paths=[]
    for file in sorted(tmp_path.glob('drawing_*.json')):
        paths.append(file)
    if not paths:
        mixed_source_manifest(tmp_path)
        paths=list(tmp_path.glob('drawing_*.json'))
    manifest=build_manifest(paths)
    # An old unmasked manifest can have a partially labeled page in eval.
    if 'building-0' in (manifest['split_groups']['val']+manifest['split_groups']['test']):
        with pytest.raises(ValueError, match='locked evaluation includes'):
            build_manifest(paths,locked_manifest=manifest,allow_partial_training=True)


def test_supervision_metadata_missing_image_fails_closed(tmp_path):
    root=tmp_path/'prepared'
    (root/'images'/'train').mkdir(parents=True)
    (root/'images'/'train'/'a.png').write_bytes(b'PNG')
    (root/'data.yaml').write_text('names:\n  0: receptacle\n')
    (root/'phase2_supervision.json').write_text(json.dumps({
        'format_version':1,'class_ids':{'receptacle':0},
        'samples':{'another_sample':[]},
    }))
    with pytest.raises(ValueError,match='differ'):
        read_supervision(root/'data.yaml')


def test_actual_ultralytics_criterion_masks_unknown_background(tmp_path):
    torch=pytest.importorskip('torch')
    pytest.importorskip('ultralytics')
    from ultralytics import __version__ as version
    if version != '8.4.216':
        pytest.skip('actual criterion pinned to 8.4.216')
    from types import SimpleNamespace
    from ultralytics.cfg import get_cfg
    from blueprint_symbols.partial_supervision import build_masked_detection_trainer

    source,manifest=mixed_source_manifest(tmp_path)
    run(source,tmp_path/'out',samples=4,train_variants=1,
        allow_partial_training=True,train_negative_fraction=1.,
        eval_empty_keep_fraction=1.,eval_blank_keep_fraction=1.)
    dataset_yaml=tmp_path/'out'/'data.yaml'
    trainer_type=build_masked_detection_trainer(dataset_yaml)
    trainer=trainer_type.__new__(trainer_type)
    trainer.args=SimpleNamespace(cls_remap=False)
    trainer.data={'nc':1,'channels':3}
    model=trainer.get_model(cfg='yolo26n-p2.yaml',weights=None,verbose=False)
    model.args=get_cfg()
    criterion=model.init_criterion()
    # Actual E2E dual branches must both use the masked criterion.
    assert type(criterion.one2many.bce).__name__ == 'PositiveAwareBCE'
    assert type(criterion.one2one.bce).__name__ == 'PositiveAwareBCE'
    bce=criterion.one2many.bce
    # Batch item 0: partial, unknown background; known positive still trains.
    # Batch item 1: fully annotated negative: background trains normally.
    logits=torch.tensor([[[2.0],[2.0]],[[2.0],[2.0]]],requires_grad=True)
    scores=torch.tensor([[[0.],[1.]],[[0.],[0.]]])
    bce.verified_class_mask=torch.tensor([[False],[True]])
    loss=bce(logits,scores)
    assert loss[0,0,0].item()==0
    assert loss[0,1,0].item()>0
    assert loss[1,0,0].item()>0
    loss.sum().backward()
    assert logits.grad[0,0,0].item()==0
    assert logits.grad[0,1,0].abs().item()>0
    assert logits.grad[1,0,0].abs().item()>0
