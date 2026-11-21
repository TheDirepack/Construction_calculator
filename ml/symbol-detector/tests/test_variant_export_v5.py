"""Ensure low-DPI raster/vector samples and evaluation split semantics."""
import json
from pathlib import Path
import cv2
import numpy as np
import pytest
from blueprint_symbols.variants import make_variant, low_resolution, EVAL_VARIANTS
from blueprint_symbols.dataset_manifest import build_manifest
from scripts_render_vector_variants import render_variants
from scripts_export_yolo_tiles import run


def test_independent_corruptions_and_downsample_equivalence():
    img=np.full((128,192,3),255,np.uint8)
    cv2.circle(img,(60,50),10,(0,0,0),2)
    a,r=make_variant(img,np.random.default_rng(4),'clean')
    assert np.array_equal(a,img) and r['operators']==[]
    b,rb=make_variant(img,np.random.default_rng(4),'lowres_25')
    assert np.array_equal(b,low_resolution(img,.25))
    assert rb['operators']==['area_downscale','linear_reconstruct']
    assert not np.array_equal(b,img)
    for name in EVAL_VARIANTS:
        v,m=make_variant(img,np.random.default_rng(124),name)
        assert v.shape==img.shape and v.dtype==np.uint8 and m['variant']==name
    with pytest.raises(ValueError):make_variant(img,np.random.default_rng(0),'other')


def test_svg_true_low_dpi_and_anchor_scaling(tmp_path):
    from cairosvg import svg2png
    svg=tmp_path/'source.svg'
    svg.write_text('<svg xmlns="http://www.w3.org/2000/svg" width="200" height="100" viewBox="0 0 200 100"><rect x="10" y="15" width="30" height="25" fill="black"/></svg>')
    raster=tmp_path/'source.png';raster.write_bytes(svg2png(url=str(svg)))
    record=tmp_path/'source.json';record.write_text(json.dumps({
        'image_path':'source.png','image_width':200,'image_height':100,
        'project_family_id':'building-A','source_group_verified':True,
        'exhaustive_families':['valve'],
        'instances':[{'symbol_family':'valve','bbox_xyxy':[10,15,40,40],
                      'anchors':{'center':{'xy':[25,27.5],'annotated':True}}}]}))
    with pytest.raises(ValueError,match='aligned-verified'):
        render_variants(record,svg,tmp_path/'unverified')
    out=render_variants(record,svg,tmp_path/'variants',fractions=(.5,.25),aligned_verified=True)
    for j, factor in zip(out,(.5,.25)):
        d=json.loads(j.read_text())
        assert d['project_family_id']=='building-A'
        assert d['instances'][0]['bbox_xyxy']==pytest.approx([10*factor,15*factor,40*factor,40*factor])
        assert d['instances'][0]['anchors']['center']['xy']==pytest.approx([25*factor,27.5*factor])
        rendered=cv2.imread(str(j.parent/d['image_path']))
        assert rendered.shape[:2]==(int(100*factor),int(200*factor))
    with pytest.raises(FileExistsError):
        render_variants(record,svg,tmp_path/'variants',fractions=(.5,.25),aligned_verified=True)


def test_paired_training_heldout_degradation_never_in_train(tmp_path):
    annotations=[]
    for n in range(3):
        img=np.full((200,220,3),255,np.uint8)
        cv2.circle(img,(50+n,50),10,(0,0,0),1)
        p=tmp_path/f'img{n}.png';assert cv2.imwrite(str(p),img)
        f=tmp_path/f'img{n}.json';f.write_text(json.dumps({
            'image_path':p.name,'image_width':220,'image_height':200,
            'source_group_verified':True,'project_family_id':f'group-{n}',
            'exhaustive_families':['valve'],
            'instances':[{'symbol_family':'valve','bbox_xyxy':[40+n,40,60+n,60]}]}));annotations.append(f)
    mf=build_manifest(annotations)
    m=tmp_path/'manifest.json';m.write_text(json.dumps(mf))
    out=tmp_path/'dataset'
    report=run(m,out,samples=3,stress_val=True,stress_test=True,train_variants=3)
    assert report['train_variants_per_base']==3
    assert (out/'evaluation_configs'/'test_stress_lowres_25.yaml').exists()
    assert (out/'evaluation_configs'/'val_stress_thin_strokes.yaml').exists()
    samples=[json.loads(v) for v in (out/'sample_provenance.jsonl').read_text().splitlines()]
    train=[v for v in samples if v['split']=='train']
    assert {v['damage']['variant'] for v in train}=={'clean','lowres_random','mixed_random'}
    assert {v['group'] for v in train}.isdisjoint({v['group'] for v in samples if v['split'].startswith(('test','val'))})
    base={}
    for v in train:
        base.setdefault(v['base_sample'],[]).append(v)
    assert all(len(g)==3 for g in base.values())
    for group in base.values():
        labels=[(out/'labels'/'train'/f"{v['sample']}.txt").read_text() for v in group]
        assert len(set(labels))==1


def test_full_page_missing_predictions_count_as_misses(tmp_path):
    from scripts_score_full_pages import score_full_pages
    ann=[]
    for n in range(3):
        image=np.full((80,80,3),255,np.uint8)
        cv2.circle(image,(20+n*4,20),4,(n*30,0,0),1)
        ip=tmp_path/f'page{n}.png';cv2.imwrite(str(ip),image)
        a=tmp_path/f'page{n}.json'
        a.write_text(json.dumps({'image_path':ip.name,'image_width':80,'image_height':80,
            'project_family_id':f'p{n}','source_group_verified':True,
            'exhaustive_families':['valve'],'instances':[
                {'symbol_family':'valve','bbox_xyxy':[10,10,25,25]}]}))
        ann.append(a)
    manifest=build_manifest(ann)
    mf=tmp_path/'manifest.json';mf.write_text(json.dumps(manifest))
    pred=tmp_path/'predictions.json';pred.write_text(json.dumps({'predictions':[]}))
    result=score_full_pages(mf,pred,split='test')
    assert result['overall']['tp']==0 and result['overall']['fn']==1
    assert result['overall']['recall']==0
    held=manifest['splits']['test'][0]
    pred.write_text(json.dumps({'predictions':[
        {'annotation_path':held,'instances':[
            {'symbol_family':'valve','bbox_xyxy':[10,10,25,25],'confidence':.95}]}]}))
    result=score_full_pages(mf,pred,split='test')
    assert result['overall']['tp']==1 and result['overall']['fn']==0
    assert result['center_error_px_median']==0
    # Must not accidentally score training pages as held-out pages.
    pred.write_text(json.dumps({'predictions':[
        {'annotation_path':manifest['splits']['train'][0],'instances':[]}]}))
    with pytest.raises(ValueError,match='unapproved'):
        score_full_pages(mf,pred,split='test')


def test_tile_eval_missing_prediction_is_false_negative(tmp_path):
    from blueprint_symbols.detection_eval import score
    out=tmp_path/'prepared'
    (out/'labels'/'val').mkdir(parents=True)
    (out/'labels'/'val'/'val_one.txt').write_text('0 0.25 0.25 0.05 0.05\n')
    (out/'export_report.json').write_text(json.dumps({'classes':{'valve':0}}))
    (out/'sample_provenance.jsonl').write_text(json.dumps({'sample':'val_one','split':'val','group':'p0'})+'\n')
    result=score(out,tmp_path/'absent_predictions',split='val')
    assert result['metrics']['all']['fn']==1
    pred=tmp_path/'predictions'/'val';pred.mkdir(parents=True)
    (pred/'val_one.txt').write_text('0 0.25 0.25 0.05 0.05 0.99\n')
    result=score(out,tmp_path/'predictions',split='val')
    assert result['metrics']['all']['tp']==1
    assert result['metrics']['all']['median_center_error_px']==0
