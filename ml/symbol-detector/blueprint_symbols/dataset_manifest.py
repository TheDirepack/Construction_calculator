"""Pretraining checks: provenance, exact group splits, duplicate images, labels.

This module builds manifests only; it never trains or moves source data.
Empty validation/test sets and unverified source grouping are rejected.
"""
from __future__ import annotations
from pathlib import Path
from collections import Counter,defaultdict
import hashlib, json, math
import cv2
from .label_schema import PageFingerprints, annotation_sha256, load_training_page, page_fingerprint, read_page

EXCLUDED={'__unmapped__','unmapped','ignore','unknown_symbol','__ignore__'}


def trusted_training_instance(instance: dict) -> bool:
    """Accept legacy confirmed labels, but never promote explicit pending reviews.

    Legacy CAD/synthetic sources have no review_status. In modern manual
    annotations, a verification field is authoritative: absence of uncertainty
    alone cannot turn a model proposal into a trusted detection target.
    """
    if instance.get('uncertain', False) is not False:
        return False
    if 'review_status' in instance:
        return (instance['review_status'] == 'accepted' and
                instance.get('review_source') == 'human' and
                instance.get('verified_ground_truth') is True)
    # Older auto-label proposals may lack modern review_status, but still
    # carry review_required or model provenance. Do not let a malformed
    # uncertain=False field promote these into ground truth.
    if 'review_required' in instance and instance['review_required'] is not False:
        return False
    provenance = instance.get('source')
    if isinstance(provenance, dict) and (
            provenance.get('not_ground_truth') is True or
            provenance.get('kind') in ('teacher_pseudo_label', 'model_prediction') or
            provenance.get('label_method') == 'model_prediction'):
        return False
    if 'review_source' in instance and (
            instance['review_source'] != 'human' or
            instance.get('verified_ground_truth') is not True):
        return False
    if 'verified_ground_truth' in instance and instance['verified_ground_truth'] is not True:
        return False
    return True


def _sha(path:Path) -> str:
    digest=hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda:f.read(1<<20),b''):
            digest.update(block)
    return digest.hexdigest()

def build_manifest(files:list[Path], *, val_fraction:float=.15, test_fraction:float=.15,
                   require_verified_groups:bool=True,
                   allow_partial_training:bool=False,
                   locked_manifest: dict | None = None,
                   evaluation_files: list[Path] | None = None) -> dict:
    """Stable group-aware partitions; same image in different groups is an error.

    ``evaluation_files``: pages of the evaluation drawings
    (``1_blueprints/evaluation``). When given, they alone form validation and
    test (their projects split between the two) and every other group trains;
    no training project is held out. Evaluation pages must be fully labelled.

    Stratification cannot guarantee held-out coverage of all rare classes;
    distribution and coverage warnings are explicitly reported.
    """
    if val_fraction<=0 or test_fraction<=0 or val_fraction+test_fraction>=1:
        raise ValueError('fractions must be positive, with train portion remaining')
    docs=[]; by_hash={}; by_group=defaultdict(list)
    negative_pages=PageFingerprints(); negative_digests={}; duplicate_negatives=[]
    warnings=[]; counts=Counter(); group_counts=defaultdict(Counter)
    for path in sorted(set(Path(f).resolve() for f in files)):
        raw=read_page(path)             # a page JSON, or a page of a PDF's labels file
        if not isinstance(raw,dict) or 'instances' not in raw:
            continue
        data=load_training_page(path)   # saved devices -> detector classes
        g=data.get('project_family_id')
        if not isinstance(g,str) or not g.strip():
            raise ValueError(f'missing project group {path}')
        if require_verified_groups and data.get('source_group_verified') is not True:
            raise ValueError(f'unverified project grouping {path}; supply a mapping to original drawing/project')
        if 'calibration_verified' in data and data['calibration_verified'] is not True:
            raise ValueError(f'unverified CAD-to-raster alignment {path}; inspect overlay and re-export with --aligned-verified')
        exhaustive = raw.get('exhaustive_families', [])   # validate what was saved
        if (not isinstance(exhaustive, list) or
                any(not isinstance(name, str) or not name.strip() for name in exhaustive) or
                len(set(exhaustive)) != len(exhaustive)):
            raise ValueError(f'invalid exhaustive_families attestation: {path}')
        img=(path.parent/data['image_path']).resolve()
        if not img.is_file():raise FileNotFoundError(img)
        image=cv2.imread(str(img),cv2.IMREAD_UNCHANGED)
        if image is None:
            raise ValueError(f'cannot decode source raster: {img}')
        width,height=int(data['image_width']),int(data['image_height'])
        if (width,height)!=(image.shape[1],image.shape[0]):
            raise ValueError(f'annotation dimensions disagree with image: {path} / {img}')
        if not isinstance(data['instances'],list):
            raise ValueError(f'instances must be a list: {path}')
        verified_no_symbols=data.get('verified_no_symbols',False)
        if type(verified_no_symbols) is not bool:
            raise ValueError(f'verified_no_symbols must be boolean: {path}')
        if verified_no_symbols and data['instances']:
            raise ValueError(f'verified_no_symbols page contains active annotations: {path}')
        instance_ids=set()
        for index,inst in enumerate(data['instances']):
            if not isinstance(inst,dict) or not isinstance(inst.get('bbox_xyxy'),(list,tuple)) or len(inst['bbox_xyxy'])!=4:
                raise ValueError(f'invalid bbox schema: {path}, instance {index}')
            a,b,c,d=map(float,inst['bbox_xyxy'])
            if not all(map(math.isfinite,(a,b,c,d))) or not (0<=a<c<=width and 0<=b<d<=height):
                raise ValueError(f'invalid/out-of-image bbox: {path}, instance {index}')
            iid=inst.get('instance_id')
            if iid is not None:
                if iid in instance_ids:
                    raise ValueError(f'duplicate instance id {iid!r}: {path}')
                instance_ids.add(iid)
        digest=_sha(img)
        if verified_no_symbols:
            # Symbol-free standard sheets recur across drawing sets (DOT details,
            # general notes). Keep one copy: repeats add no information, and
            # copies in different projects would leak across splits.
            first=negative_digests.get(digest) or negative_pages.match(page_fingerprint(image))
            if first is not None:
                duplicate_negatives.append({'annotation':str(path),'duplicate_of':first})
                continue
            negative_pages.name_last(str(path))
            negative_digests[digest]=str(path)
        if digest in by_hash:
            if by_hash[digest]!=g:
                raise ValueError(f'leakage: same raster sha256 occurs across groups {by_hash[digest]} and {g}: {img}')
            raise ValueError(f'duplicate raster annotations inside group {g}: {img}; '
                             'merge annotations instead of training twice from conflicting labels')
        by_hash[digest]=g
        seen=set()
        for inst in data['instances']:
            cls=inst.get('symbol_family')
            if cls not in EXCLUDED and cls and trusted_training_instance(inst):
                counts[cls]+=1;group_counts[g][cls]+=1;seen.add(cls)
        docs.append({'annotation':str(path),'annotation_sha256':annotation_sha256(path),
                     'image':str(img),'image_sha256':digest,
                     'project_group':g,'source_type':data.get('source_type','unknown'),
                     'image_width':data['image_width'],'image_height':data['image_height'],
                     'usable_families':sorted(seen),
                     'verified_no_symbols':verified_no_symbols,
                     'exhaustive_families':sorted(set(data.get('exhaustive_families', [])))})
        by_group[g].append(docs[-1])
    # Only explicit reviewed negatives imply exhaustive class coverage.
    # A zero-box unreviewed source remains unknown.
    for row in docs:
        if row['verified_no_symbols']:
            row['exhaustive_families']=sorted(counts)
    if duplicate_negatives:
        warnings.append(f'{len(duplicate_negatives)} verified symbol-free pages repeat an earlier '
                        'page (same fingerprint) and were left out')
    if not docs: raise ValueError('no training annotation files found')
    groups=sorted(by_group,key=lambda g:hashlib.sha256(g.encode()).hexdigest())
    if evaluation_files:
        return _evaluation_folder_split(docs, by_group, groups, counts, group_counts, warnings,
                                        duplicate_negatives, evaluation_files,
                                        allow_partial_training=allow_partial_training,
                                        require_verified_groups=require_verified_groups)
    if len(groups)<3:
        raise ValueError('at least 3 verified independent source groups are needed for train/val/test')
    if locked_manifest is not None:
        validate_manifest_sources(locked_manifest)
        old_docs={str(Path(r['annotation']).resolve()):r for r in locked_manifest['source_images']}
        current_docs={str(Path(r['annotation']).resolve()):r for r in docs}
        if not set(old_docs).issubset(current_docs):
            raise ValueError('locked manifest source annotations must remain in the new input list')
        for path,old in old_docs.items():
            if (current_docs[path]['annotation_sha256']!=old['annotation_sha256'] or
                    current_docs[path]['image_sha256']!=old['image_sha256']):
                raise ValueError(f'locked manifest source changed: {path}')
        old_heldout=set(locked_manifest['split_groups']['val']+locked_manifest['split_groups']['test'])
        new_ann=set(current_docs)-set(old_docs)
        for path in new_ann:
            if current_docs[path]['project_group'] in old_heldout:
                raise ValueError(f'new annotation belongs to a locked evaluation group: {path}')
        split_groups={'val':list(locked_manifest['split_groups']['val']),
                      'test':list(locked_manifest['split_groups']['test']),
                      'train':[g for g in groups if g not in old_heldout]}
    else:
        split_groups=None
    n=len(groups)
    nval=max(1,round(n*val_fraction));ntest=max(1,round(n*test_fraction))
    if nval+ntest>=n: nval,ntest=1,1
    # Reserve enough source groups for training to actually contain every
    # mapped class. A purely random split can put all examples of a rare
    # electrical/HVAC symbol into validation, making that class unlearnable.
    remaining_classes=set(counts) if split_groups is None else set()
    # A partially annotated project cannot be used for standard validation or
    # test: missing truth would be counted as false positives.
    partial_groups=set()
    if allow_partial_training:
        active=set(counts)
        partial_groups={g for g in groups if any(
            not active.issubset(set(row['exhaustive_families']))
            for row in by_group[g]
        )}
        if len(groups)-len(partial_groups)<2 and locked_manifest is None:
            raise ValueError('partial-label training requires at least two fully '
                             'annotated independent source groups for val/test')
        if locked_manifest is not None and any(g in partial_groups for g in (
                locked_manifest['split_groups']['val']+
                locked_manifest['split_groups']['test'])):
            raise ValueError('locked evaluation includes a partially annotated group')
    train_protected=sorted(partial_groups, key=groups.index)
    remaining_classes.difference_update({c for g in train_protected for c in group_counts[g]})
    class_group_frequency={c:sum(bool(group_counts[g][c]) for g in groups) for c in counts}
    while remaining_classes:
        candidates=[g for g in groups if g not in train_protected]
        choice=max(candidates,key=lambda g:(sum(1/class_group_frequency[c] for c in remaining_classes
                                                if group_counts[g][c]),
                                            sum(bool(group_counts[g][c]) for c in remaining_classes),
                                            -groups.index(g)))
        train_protected.append(choice)
        remaining_classes.difference_update({c for c in remaining_classes if group_counts[choice][c]})
    if split_groups is None and len(train_protected)+nval+ntest>n:
        raise ValueError('cannot make nonempty held-out partitions while retaining every mapped '
                         'class in training; provide more independent source groups or fewer '
                         'target classes')
    # Hold out other whole groups. Prefer class-diverse groups so validation
    # and test are informative; deterministic tie-breaks preserve repeatability.
    if split_groups is None:
        remaining=[g for g in groups if g not in train_protected]
        split_groups={'train':list(train_protected),'val':[],'test':[]}
    else:
        remaining=[]
    for split,needed in ([('val',nval),('test',ntest)] if remaining else []):
        covered=set()
        for _ in range(needed):
            eligible=[g for g in remaining if g not in partial_groups]
            if not eligible:
                raise ValueError('no fully annotated source group available for evaluation')
            selected=max(eligible,key=lambda g:(sum(1/class_group_frequency[c]
                                                    for c in counts if group_counts[g][c] and c not in covered),
                                                -groups.index(g)))
            split_groups[split].append(selected)
            covered.update(c for c in counts if group_counts[selected][c])
            remaining.remove(selected)
    split_groups['train'].extend(remaining)
    splits={name:[d['annotation'] for g in gs for d in by_group[g]] for name,gs in split_groups.items()}
    coverage={name:dict(sum((group_counts[g] for g in gs),Counter())) for name,gs in split_groups.items()}
    for name in sorted(counts):
        groups_for=[g for g in groups if group_counts[g][name]]
        if len(groups_for)<3:
            warnings.append(f'Rare class {name!r} occurs in {len(groups_for)} independent groups; robust train/val/test metrics unavailable')
        if not coverage['train'].get(name):
            warnings.append(f'Class {name!r} absent from train partition')
        if not coverage['val'].get(name) or not coverage['test'].get(name):
            warnings.append(f'Class {name!r} absent from one or more evaluation partitions')
    if locked_manifest is None:
        classes={name:i for i,name in enumerate(sorted(k for k,v in coverage['train'].items() if v>0))}
    else:
        classes=dict(locked_manifest['class_ids'])
        for name in sorted(k for k,v in coverage['train'].items() if v>0 and k not in classes):
            classes[name]=len(classes)
        if any(coverage['train'].get(c,0)==0 for c in classes):
            raise ValueError('locked training class disappeared; supply its original training sources')
    if not classes:raise ValueError('train partition contains no mapped, trusted symbol classes')
    return {'format_version':1,'splits':splits, 'split_groups':split_groups,
            'class_ids':classes,'per_split_class_instances':coverage,
            'source_images':docs,'warnings':warnings,
            'deduplicated_negative_pages':duplicate_negatives,
            'partial_training_enabled':allow_partial_training,
            'verification':{'source_group_verified':require_verified_groups,
                            'all_images_hash_checked':True,
                            'training_performed':False}}


def validate_manifest_sources(manifest: dict) -> None:
    """Recheck immutable inputs after split creation, before exporting labels.

    A manifest with changed source annotations/images is not a reproducible
    training dataset; silently proceeding can create misaligned gold boxes.
    """
    sources={str(Path(x['annotation']).resolve()):x for x in manifest.get('source_images',[])}
    if not sources:
        raise ValueError('manifest has no source image hashes')
    listed=[]
    for split in ('train','val','test'):
        expected_groups=set(manifest['split_groups'][split])
        for item in manifest['splits'][split]:
            a=str(Path(item).resolve())
            if a not in sources:
                raise ValueError(f'annotation missing from source inventory: {a}')
            row=sources[a]
            if row['project_group'] not in expected_groups:
                raise ValueError(f'group inventory disagrees with split assignment: {a}')
            listed.append(a)
    if len(listed)!=len(set(listed)) or set(listed)!=set(sources):
        raise ValueError('duplicate or missing annotations in split manifest')
    for annotation,row in sources.items():
        if 'annotation_sha256' not in row:
            raise ValueError('manifest predates annotation hash validation; rebuild source manifest')
        if annotation_sha256(annotation) != row['annotation_sha256']:
            raise ValueError(f'source annotation changed after manifest creation: {annotation}')
        image=Path(row['image'])
        if _sha(image) != row['image_sha256']:
            raise ValueError(f'source image changed after manifest creation: {image}')


def _evaluation_folder_split(docs, by_group, groups, counts, group_counts, warnings,
                             duplicate_negatives, evaluation_files, *,
                             allow_partial_training, require_verified_groups) -> dict:
    """Train on every non-evaluation group; validate and test on the
    evaluation drawings' groups (alternating, in a stable hash order)."""
    eval_set={str(Path(f).resolve()) for f in evaluation_files}
    eval_groups=sorted({d['project_group'] for d in docs if d['annotation'] in eval_set}, key=groups.index)
    mixed=[g for g in eval_groups if any(d['annotation'] not in eval_set for d in by_group[g])]
    if mixed:
        raise ValueError(f'project groups mix training and evaluation drawings: {mixed[:5]}; '
                         'a project must sit in one folder')
    if not eval_groups:
        raise ValueError('no usable evaluation pages: label pages of drawings in 1_blueprints/evaluation '
                         'completely ("every symbol on this page is labeled")')
    active=set(counts)
    incomplete=[d['annotation'] for g in eval_groups for d in by_group[g]
                if not active.issubset(set(d['exhaustive_families']))]
    if incomplete:
        raise ValueError(f'{len(incomplete)} evaluation pages are not fully labelled '
                         f'(e.g. {incomplete[0]}); evaluation only uses pages marked '
                         '"every symbol on this page is labeled" or verified empty')
    train=[g for g in groups if g not in eval_groups]
    if not train:
        raise ValueError('no training pages outside the evaluation folder')
    if len(eval_groups)==1:
        split_groups={'train':train,'val':list(eval_groups),'test':list(eval_groups)}
        warnings.append('only one evaluation project: validation and test are the same drawings')
    else:
        split_groups={'train':train,'val':eval_groups[0::2],'test':eval_groups[1::2]}
    splits={name:[d['annotation'] for g in gs for d in by_group[g]] for name,gs in split_groups.items()}
    coverage={name:dict(sum((group_counts[g] for g in gs),Counter())) for name,gs in split_groups.items()}
    for name in sorted(counts):
        if not coverage['train'].get(name):
            warnings.append(f'Class {name!r} occurs only in evaluation drawings, never trained')
        elif not coverage['val'].get(name) and not coverage['test'].get(name):
            warnings.append(f'Class {name!r} absent from the evaluation drawings')
    classes={name:i for i,name in enumerate(sorted(k for k,v in coverage['train'].items() if v>0))}
    if not classes:raise ValueError('train partition contains no mapped, trusted symbol classes')
    return {'format_version':1,'splits':splits,'split_groups':split_groups,
            'class_ids':classes,'per_split_class_instances':coverage,
            'source_images':docs,'warnings':warnings,
            'deduplicated_negative_pages':duplicate_negatives,
            'partial_training_enabled':allow_partial_training,
            'holdout':'evaluation_folder',
            'verification':{'source_group_verified':require_verified_groups,
                            'all_images_hash_checked':True,
                            'training_performed':False}}
