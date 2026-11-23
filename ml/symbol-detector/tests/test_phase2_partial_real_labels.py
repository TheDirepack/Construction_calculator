"""Phase-two real positives/negatives and incomplete real labels stay safe."""
from __future__ import annotations

import json
from pathlib import Path

import cv2
import numpy as np
import pytest

from blueprint_symbols.dataset_manifest import build_manifest
from blueprint_symbols.raster_training import read_records
from blueprint_symbols.ssl_data import build_ssl_sources
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
            'exhaustive_families':([] if i in (0,3) else ['receptacle']),
            'verified_no_symbols':i==3,
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


def test_verified_negative_attestation_is_class_complete(tmp_path):
    _, manifest = mixed_source_manifest(tmp_path)
    negative = next(row for row in manifest["source_images"] if row["verified_no_symbols"])
    assert negative["exhaustive_families"] == ["receptacle"]
    record = read_records([negative["annotation"]], classes=manifest["class_ids"])[0]
    assert record.verified_no_symbols is True
    assert record.exhaustive_families == frozenset({"receptacle"})
    # Partial real symbols remain unknown background, despite being present in the JSON.
    partial = next(row for row in manifest["source_images"] if row["project_group"] == "building-0")
    assert partial["verified_no_symbols"] is False
    assert not read_records([partial["annotation"]], classes=manifest["class_ids"])[0].exhaustive_families


def test_verified_negative_samples_keep_training_and_pretraining_coverage(tmp_path):
    source, manifest = mixed_source_manifest(tmp_path)
    negative = next(row for row in manifest["source_images"] if row["verified_no_symbols"])
    assert negative["project_group"] in manifest["split_groups"]["train"]
    ssl = build_ssl_sources(manifest)
    assert any(row["image_path"] == negative["image"] for row in ssl["sources"])
    dest = tmp_path / "export"
    run(source, dest, samples=5, train_variants=1, allow_partial_training=True,
        verified_wall_negative_fraction=1., verified_wall_blank_fraction=1.,
        train_negative_fraction=1., blank_negative_fraction=1.)
    provenance = [json.loads(line) for line in (dest / "sample_provenance.jsonl").read_text().splitlines()]
    negatives = [row for row in provenance if row.get("verified_page_negative")]
    assert negatives, "verified symbol-free pages must contribute negative training tiles"
    coverage = json.loads((dest / "phase2_supervision.json").read_text())["samples"]
    assert all(row["verified_negative_class_ids"] == [0] and coverage[row["sample"]] == [0]
               for row in negatives)


def test_forged_page_negative_with_active_symbols_fails(tmp_path):
    mixed_source_manifest(tmp_path)
    annotation = tmp_path / "drawing_1.json"
    raw = json.loads(annotation.read_text())
    raw["verified_no_symbols"] = True
    annotation.write_text(json.dumps(raw))
    with pytest.raises(ValueError, match="verified_no_symbols page contains active annotations"):
        build_manifest([tmp_path / f"drawing_{i}.json" for i in range(5)],
                       val_fraction=.2, test_fraction=.2, allow_partial_training=True)


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

    # Regression: YOLO saves the *model object*, not only state_dict.
    # The custom loss/model must be importable global classes, not closures.
    import io
    model.criterion = criterion
    stream=io.BytesIO()
    torch.save(model,stream)
    stream.seek(0)
    restored=torch.load(stream,map_location='cpu',weights_only=False)
    assert type(restored).__name__=='PartialDetectionModel'
    assert type(restored.criterion.one2many.bce).__name__=='PositiveAwareBCE'

    # Read actual YOLO image/label pairs and prove masks follow samples through
    # the real detector DataLoader's expected collate contract.
    cfg=get_cfg(overrides={'imgsz':512,'mosaic':0.0,'mixup':0.0,
                           'cutmix':0.0,'copy_paste':0.0,'fraction':1.0})
    trainer.args=cfg
    trainer.model=model
    trainer.data={'nc':1,'channels':3,'names':['receptacle']}
    dataset=trainer.build_dataset(str(tmp_path/'out'/'images'/'train'),mode='train',batch=2)
    assert len(dataset)>0
    items=[dataset[i] for i in range(len(dataset))]
    mask_counts={int(item['verified_class_mask'][0]) for item in items}
    assert mask_counts=={0,1}
    partial_item=next(item for item in items if not item['verified_class_mask'][0])
    complete_item=next(item for item in items if item['verified_class_mask'][0])
    collated=dataset.collate_fn([partial_item,complete_item])
    assert collated['verified_class_mask'].shape==(2,1)
    assert collated['img'].shape[0]==2

    # Actual YOLO26 forward + assigned-object loss + backward on a mixed
    # partial/complete minibatch, not just a synthetic BCE tensor.
    import torch.nn.functional as F
    collated['img']=F.interpolate(
        collated['img'].float(),size=(128,128),mode='bilinear',
        align_corners=False,
    )/255.0
    model.train()
    model.zero_grad(set_to_none=True)
    loss,metrics=model.loss(collated)
    assert torch.isfinite(loss).all()
    loss.sum().backward()
    assert model.model[0].conv.weight.grad is not None
    assert torch.isfinite(model.model[0].conv.weight.grad).all()


def test_partial_export_requires_sidecar_even_after_accidental_deletion(tmp_path):
    source, _ = mixed_source_manifest(tmp_path)
    output = tmp_path / 'prepared'
    run(source, output, samples=4, train_variants=1,
        allow_partial_training=True, train_negative_fraction=1.,
        eval_empty_keep_fraction=1., eval_blank_keep_fraction=1.)
    yaml_path = output / 'data.yaml'
    assert 'phase2_supervision: phase2_supervision.json' in yaml_path.read_text()
    assert read_supervision(yaml_path)['has_partial']
    (output / 'phase2_supervision.json').unlink()
    with pytest.raises(ValueError, match='missing phase-two supervision sidecar'):
        read_supervision(yaml_path)
    # The provenance file also protects older exporter output whose YAML
    # predates the explicit marker.
    yaml_path.write_text(yaml_path.read_text().replace(
        'phase2_supervision: phase2_supervision.json\n', ''
    ))
    with pytest.raises(ValueError, match='missing phase-two supervision sidecar'):
        read_supervision(yaml_path)


def test_partial_sidecar_requires_matching_label_files_and_explicit_mode(tmp_path):
    source, _ = mixed_source_manifest(tmp_path)
    output = tmp_path / 'prepared'
    run(source, output, samples=4, train_variants=1,
        allow_partial_training=True, train_negative_fraction=1.,
        eval_empty_keep_fraction=1., eval_blank_keep_fraction=1.)
    data_yaml = output / 'data.yaml'
    sidecar = output / 'phase2_supervision.json'
    assert read_supervision(data_yaml)['has_partial']
    mask = json.loads(sidecar.read_text())
    mask['partial_training_enabled'] = 'true'
    sidecar.write_text(json.dumps(mask))
    with pytest.raises(ValueError, match='partial_training_enabled'):
        read_supervision(data_yaml)
    mask['partial_training_enabled'] = True
    sidecar.write_text(json.dumps(mask))
    labels = sorted((output / 'labels' / 'train').glob('*.txt'))
    assert labels
    labels[0].unlink()
    with pytest.raises(ValueError, match='label files differ'):
        read_supervision(data_yaml)


def test_manifest_rejects_string_verification_or_malformed_exhaustiveness(tmp_path):
    _, _ = mixed_source_manifest(tmp_path)
    sources = sorted(tmp_path.glob('drawing_*.json'))
    first = sources[0]
    original = first.read_text()
    doc = json.loads(original)
    for key, value, pattern in [
        ('source_group_verified', 'true', 'unverified project grouping'),
        ('source_group_verified', 1, 'unverified project grouping'),
        ('calibration_verified', 'false', 'unverified CAD-to-raster alignment'),
        ('exhaustive_families', 'receptacle', 'invalid exhaustive_families'),
        ('exhaustive_families', ['receptacle', 'receptacle'], 'invalid exhaustive_families'),
    ]:
        modified = dict(doc, **{key: value})
        first.write_text(json.dumps(modified))
        with pytest.raises(ValueError, match=pattern):
            build_manifest(sources, val_fraction=.20, test_fraction=.20,
                           allow_partial_training=True)
    first.write_text(original)


def test_explicitly_pending_boxes_never_become_training_positives():
    from types import SimpleNamespace
    from blueprint_symbols.dataset_manifest import trusted_training_instance
    from scripts_export_yolo_tiles import _export_annotations

    base = {'symbol_family': 'receptacle', 'bbox_xyxy': [90, 90, 130, 130],
            'uncertain': False}
    legacy = dict(base)
    accepted = dict(base, review_status='accepted', review_source='human',
                    verified_ground_truth=True)
    pending = dict(base, review_status='unreviewed', review_source='sam3',
                   verified_ground_truth=False)
    fake_accept = dict(base, review_status='accepted', review_source='sam3',
                       verified_ground_truth=True)
    absent_status = dict(base, verified_ground_truth=False)
    assert trusted_training_instance(legacy)
    assert trusted_training_instance(accepted)
    assert not trusted_training_instance(pending)
    assert not trusted_training_instance(fake_accept)
    assert not trusted_training_instance(absent_status)
    tile = SimpleNamespace(x=0, y=0, size=512, source_scale=1)
    for source in (pending, fake_accept, absent_status):
        targets, ignored = _export_annotations([source], tile, {'receptacle': 0})
        assert targets == []
        assert ignored == [[90.0, 90.0, 130.0, 130.0]]
    for source in (legacy, accepted):
        targets, ignored = _export_annotations([source], tile, {'receptacle': 0})
        assert len(targets) == 1 and ignored == []


def test_manifest_class_count_ignores_malformed_explicit_review(tmp_path):
    _manifest, _ = mixed_source_manifest(tmp_path)
    source = sorted(tmp_path.glob('drawing_*.json'))[0]
    document = json.loads(source.read_text())
    document['instances'][0].update({
        'review_status': 'unreviewed', 'review_source': 'sam3',
        'verified_ground_truth': False, 'uncertain': False,
    })
    source.write_text(json.dumps(document))
    refreshed = build_manifest(sorted(tmp_path.glob('drawing_*.json')),
                               val_fraction=.2, test_fraction=.2,
                               allow_partial_training=True)
    row = next(x for x in refreshed['source_images'] if x['annotation'] == str(source))
    assert row['usable_families'] == []


def test_sampler_enforces_review_status_for_actual_training_targets(tmp_path):
    """Training sample() must enforce the same trust policy as the manifest."""
    from blueprint_symbols.raster_training import RasterRecord, RasterTileDataset

    image = tmp_path / 'page.png'
    assert cv2.imwrite(str(image), np.full((512, 512, 3), 245, np.uint8))
    instances = [
        {'instance_id': 'accepted', 'symbol_family': 'receptacle',
         'bbox_xyxy': [40, 40, 80, 80], 'review_status': 'accepted',
         'review_source': 'human', 'verified_ground_truth': True, 'uncertain': False},
        {'instance_id': 'legacy', 'symbol_family': 'receptacle',
         'bbox_xyxy': [110, 40, 150, 80], 'uncertain': False},
        # Invalid-but-possible imported records must not become positives.
        {'instance_id': 'pending', 'symbol_family': 'switch_sensor',
         'bbox_xyxy': [185, 40, 225, 80], 'review_status': 'unreviewed',
         'review_source': 'sam3', 'verified_ground_truth': False, 'uncertain': False},
        {'instance_id': 'model_accepted', 'symbol_family': 'switch_sensor',
         'bbox_xyxy': [250, 40, 290, 80], 'review_status': 'accepted',
         'review_source': 'sam3', 'verified_ground_truth': True, 'uncertain': False},
        {'instance_id': 'uncertain', 'symbol_family': 'switch_sensor',
         'bbox_xyxy': [320, 40, 360, 80], 'uncertain': True},
    ]
    rec = RasterRecord(image, 'source-A', 512, 512, instances)
    dataset = RasterTileDataset(
        [rec], {'receptacle': 0, 'switch_sensor': 1},
        size=512, virtual_tiles_per_image=4, source_scales=(1., 1.),
        target_symbol_pixels=None,
    )
    assert set(dataset.class_sampling_weights) == {'receptacle'}
    for index in range(len(dataset)):
        _, targets, meta = dataset.sample(index)
        assert len(targets) == 2
        assert all(target[0] == 0 for target in targets)
        assert len(meta['ignore_boxes']) == 3
        assert {(round(box[0]), round(box[1])) for box in meta['ignore_boxes']} == {
            (185, 40), (250, 40), (320, 40),
        }


def test_pending_only_sampler_cannot_invent_positive_or_verified_negative(tmp_path):
    from blueprint_symbols.raster_training import RasterRecord, RasterTileDataset

    image = tmp_path / 'pending.png'
    assert cv2.imwrite(str(image), np.full((512, 512, 3), 255, np.uint8))
    pending = {'symbol_family': 'switch_sensor', 'bbox_xyxy': [40, 40, 80, 80],
               'review_status': 'unreviewed', 'review_source': 'sam3',
               'verified_ground_truth': False, 'uncertain': False}
    dataset = RasterTileDataset(
        [RasterRecord(image, 'pending-only', 512, 512, [pending])],
        {'switch_sensor': 0},
        size=512, virtual_tiles_per_image=2, source_scales=(1., 1.),
    )
    assert dataset.class_sampling_weights == {}
    for i in range(len(dataset)):
        _, positives, meta = dataset.sample(i)
        assert positives == []
        assert len(meta['ignore_boxes']) == 1
