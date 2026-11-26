"""Real failure-mode tests added after v7 audit; no model is trained."""
import json
from pathlib import Path
import cv2
import numpy as np
import pytest
from blueprint_symbols.dataset_manifest import build_manifest
from blueprint_symbols.ssl_data import build_ssl_sources, SSLViewDataset
from scripts_export_yolo_tiles import run
from scripts_verify_yolo_export import verify_export


def draw_sources(root, count=6):
    paths=[]
    for i in range(count):
        image=np.full((192,242,3),245,np.uint8)
        cv2.circle(image,(70,70),15,(i*19,0,0),2)
        cv2.putText(image,str(i),(12,160),cv2.FONT_HERSHEY_SIMPLEX,1,(0,0,0),2)
        ip=root/f'{i}.png';assert cv2.imwrite(str(ip),image)
        a=root/f'{i}.json'
        instances=[{'instance_id':'base','symbol_family':'valve','bbox_xyxy':[52,52,88,88]}]
        if i==0:
            instances.append({'instance_id':'rare','symbol_family':'rare_motor','bbox_xyxy':[110,100,130,120]})
        a.write_text(json.dumps({'image_path':ip.name,'image_width':242,'image_height':192,
                                 'project_family_id':f'project-{i}','source_group_verified':True,
                                 'exhaustive_families':['valve','rare_motor'],
                                 'instances':instances}))
        paths.append(a)
    return paths


def test_rare_symbols_must_be_in_training_not_only_heldout(tmp_path):
    files=draw_sources(tmp_path)
    m=build_manifest(files)
    assert 'project-0' in m['split_groups']['train']
    assert set(m['class_ids'])=={'valve','rare_motor'}
    assert all(m['splits'][s] for s in ('train','val','test'))
    assert build_manifest(files)['split_groups']==m['split_groups']


def test_early_rejection_of_invalid_source_coordinates_and_dimensions(tmp_path):
    files=draw_sources(tmp_path,3)
    d=json.loads(files[0].read_text());d['image_width']=200
    files[0].write_text(json.dumps(d))
    with pytest.raises(ValueError,match='dimensions disagree'):
        build_manifest(files)
    d['image_width']=242;d['instances'][0]['bbox_xyxy']=[10,10,500,40]
    files[0].write_text(json.dumps(d))
    with pytest.raises(ValueError,match='invalid/out-of-image bbox'):
        build_manifest(files)


def test_heldout_clipped_or_unknown_symbols_cannot_silently_disappear(tmp_path):
    files=draw_sources(tmp_path,3)
    m=build_manifest(files)
    # Source groups are already split. Add unknown geometry to a test source.
    # The page fits one tile: the unknown box is painted out of that tile
    # (never taught as background) and the tile, with its verified symbol,
    # is kept, so no held-out symbol disappears. (Dropping the whole tile, as
    # before, silently lost the verified symbol beside it.)
    held=Path(m['splits']['test'][0]);data=json.loads(held.read_text())
    data['instances'].append({'instance_id':'unknown','symbol_family':'__unmapped__',
                              'uncertain':True,'bbox_xyxy':[90,90,130,130]})
    held.write_text(json.dumps(data))
    m=build_manifest(files) # Rebuilt because every input change invalidates an existing manifest.
    mf=tmp_path/'manifest.json';mf.write_text(json.dumps(m))
    report=run(mf,tmp_path/'prepared',samples=2)
    assert report['counts']['test_tiles_with_masked_regions']>=1
    image=str((held.parent/data['image_path']).resolve())
    coverage=report['evaluation_source_instance_coverage'][image]
    assert coverage['missing']==0 and coverage['represented_in_tiles']==coverage['annotated_instances']==1


def test_export_checker_detects_extras_and_wrong_canvas(tmp_path):
    files=draw_sources(tmp_path,3)
    m=build_manifest(files);mf=tmp_path/'manifest.json';mf.write_text(json.dumps(m))
    dest=tmp_path/'prepared'
    run(mf,dest,samples=3)
    assert verify_export(dest)['ok']
    extra=dest/'images'/'train'/'rogue.png'
    assert cv2.imwrite(str(extra),np.zeros((512,512,3),np.uint8))
    with pytest.raises(ValueError,match='untracked'):
        verify_export(dest)
    extra.unlink()
    item=next((dest/'images'/'val').glob('*.png'))
    assert cv2.imwrite(str(item),np.zeros((320,512,3),np.uint8))
    with pytest.raises(ValueError,match='unexpected tile dimensions'):
        verify_export(dest)


def test_ssl_uses_only_train_and_verified_unlabeled_sources(tmp_path):
    files=draw_sources(tmp_path,4)
    m=build_manifest(files)
    extra=tmp_path/'unlabeled.png'
    img=np.full((95,470,3),255,np.uint8)
    cv2.line(img,(20,20),(460,70),(0,0,0),2)
    assert cv2.imwrite(str(extra),img)
    ss=build_ssl_sources(m,[{'image_path':str(extra),'project_family_id':'outside-project',
                             'source_group_verified':True}])
    assert len(ss['sources'])==len(m['splits']['train'])+1
    assert {x['group'] for x in ss['sources']}.isdisjoint(set(m['split_groups']['test']+m['split_groups']['val']))
    ds=SSLViewDataset(ss,size=512,items_per_epoch=12,seed=11,labeled_fraction=0)
    clean,modified,info=ds[1]
    assert clean.shape==modified.shape==(512,512,3)
    assert info['source']==str(extra)
    assert not np.array_equal(clean,modified)
    c2,d2,inf2=ds[1]
    assert np.array_equal(clean,c2) and np.array_equal(modified,d2) and info==inf2
    with pytest.raises(ValueError,match='evaluation project group'):
        build_ssl_sources(m,[{'image_path':str(extra),
                              'project_family_id':m['split_groups']['test'][0],
                              'source_group_verified':True}])


def test_teacher_specialist_class_mapping_ignores_unrelated_classes():
    from scripts_propose_labels import propose
    class Tensor:
        def __init__(self, array):self.data=np.asarray(array)
        def cpu(self):return self
        def numpy(self):return self.data
    class Boxes:
        xyxy=Tensor([[40,40,80,80],[100,100,120,120]])
        cls=Tensor([0,1])
        conf=Tensor([.9,.95])
    class Pred:
        boxes=Boxes()
    class Teacher:
        names={0:'SERVICE_KEY_RCP',1:'UNRELATED_SYMBOL'}
        def predict(self,**kwargs):return [Pred()]
    image=np.full((512,512,3),255,np.uint8)
    with pytest.raises(ValueError,match='teacher class mapping differs'):
        propose(Teacher(),image,{'receptacle':0},scales=(1.,))
    rows,stats=propose(Teacher(),image,{'receptacle':0},scales=(1.,),
                       teacher_class_map={'SERVICE_KEY_RCP':'receptacle'})
    assert len(rows)==1 and rows[0]['symbol_family']=='receptacle'
    assert rows[0]['uncertain'] is True and rows[0]['review_required'] is True
    assert stats['raw_predictions']==1
    with pytest.raises(ValueError,match='not in training taxonomy'):
        propose(Teacher(),image,{'receptacle':0},scales=(1.,),
                teacher_class_map={'SERVICE_KEY_RCP':'other'})


def test_coverage_attestation_does_not_modify_source_jsons(tmp_path):
    from scripts_attest_dataset_coverage import attest
    paths=draw_sources(tmp_path,3)
    original=paths[0].read_bytes()
    with pytest.raises(ValueError,match='assert-exhaustive'):
        attest([str(tmp_path/'*.json')],tmp_path/'covered',['valve','rare_motor'])
    result=attest([str(tmp_path/'*.json')],tmp_path/'covered',['valve','rare_motor'],verified=True)
    assert result['records']==3
    assert paths[0].read_bytes()==original
    doc=json.loads(next((tmp_path/'covered').glob('*.json')).read_text())
    assert set(doc['exhaustive_families'])=={'valve','rare_motor'}
    assert Path(doc['image_path']).is_file()


def test_manifest_rejects_changed_source_after_split(tmp_path):
    from blueprint_symbols.dataset_manifest import validate_manifest_sources
    files=draw_sources(tmp_path,3)
    m=build_manifest(files)
    validate_manifest_sources(m)
    doc=json.loads(files[0].read_text())
    doc['instances'][0]['bbox_xyxy']=[40,40,82,82]
    files[0].write_text(json.dumps(doc))
    with pytest.raises(ValueError,match='annotation changed'):
        validate_manifest_sources(m)


def test_scanned_pdf_ingestion_keeps_project_group_and_native_detail(tmp_path):
    fitz = pytest.importorskip("fitz")
    from scripts_render_pdf_sources import render_pdf_pages
    im=np.full((280,700,3),255,np.uint8)
    cv2.circle(im,(350,120),9,(0,0,0),2)
    src=tmp_path/'image.png';assert cv2.imwrite(str(src),im)
    pdf=fitz.open();page=pdf.new_page(width=350,height=140)
    page.insert_image(fitz.Rect(0,0,350,140),filename=str(src))
    fn=tmp_path/'scan.pdf';pdf.save(fn);pdf.close()
    records=render_pdf_pages(fn,tmp_path/'pages',group='same-building',dpi=72,max_pixels=2_000_000)
    assert len(records)==1 and records[0]['project_family_id']=='same-building'
    assert records[0]['source_group_verified']
    assert records[0]['render_resolution'][0]>=700
    assert cv2.imread(records[0]['image_path']).shape[1]>=700
    with pytest.raises(FileExistsError):
        render_pdf_pages(fn,tmp_path/'pages',group='same-building')


def test_expanding_training_data_preserves_locked_evaluation_groups(tmp_path):
    files=draw_sources(tmp_path,6)
    first=build_manifest(files)
    extra_img=tmp_path/'new_source.png'
    assert cv2.imwrite(str(extra_img),np.full((90,150,3),193,np.uint8))
    extra=tmp_path/'new_source.json'
    new_doc={'image_path':extra_img.name,'image_width':150,'image_height':90,
             'project_family_id':'new_training_project','source_group_verified':True,
             'instances':[{'symbol_family':'hvac_diffuser','bbox_xyxy':[10,10,30,30]}],
             'exhaustive_families':['receptacle','valve','rare_motor','hvac_diffuser']}
    extra.write_text(json.dumps(new_doc))
    expanded=build_manifest(files+[extra],locked_manifest=first)
    assert expanded['splits']['val']==first['splits']['val']
    assert expanded['splits']['test']==first['splits']['test']
    assert expanded['split_groups']['val']==first['split_groups']['val']
    assert expanded['split_groups']['test']==first['split_groups']['test']
    assert all(expanded['class_ids'][k]==v for k,v in first['class_ids'].items())
    assert expanded['class_ids']['hvac_diffuser']==len(first['class_ids'])
    assert str(extra.resolve()) in expanded['splits']['train']
    new_doc['project_family_id']=first['split_groups']['test'][0]
    extra.write_text(json.dumps(new_doc))
    with pytest.raises(ValueError,match='locked evaluation group'):
        build_manifest(files+[extra],locked_manifest=first)
