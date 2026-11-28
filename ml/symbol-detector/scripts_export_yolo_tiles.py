"""Export audited raster labels into YOLO detection tiles. NO TRAINING.

Generated training tiles cover ~10-128px symbols, clean and independently
degraded variants including low-res-only. Validation/test images are clean
deterministic full-coverage tiles; optional paired degradation sets test the
same original drawing without moving any source group into training.
True evaluation must also run on original whole pages after cross-tile fusion.

python scripts_export_yolo_tiles.py manifest.json out/yolo --train-tiles-per-page 32 \
    --stress-val --stress-test --allow-unverified-completeness

--seed picks every training tile and variant: the same seed re-exports the
same dataset, a new one re-samples it. Verified-blank real pages are balanced
per document (--real-blank-tiles-per-document). --hard-negatives (or the
PHASE2_HARD_NEGATIVES environment variable, for callers such as
scripts_prepare_training_data.py) reads scripts_mine_hard_negatives.py's
windows: each becomes extra verified-empty training tiles.
"""
from __future__ import annotations
import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import cv2
import numpy as np
from blueprint_symbols.raster_training import read_records, RasterTileDataset
from blueprint_symbols.dataset_manifest import validate_manifest_sources, trusted_training_instance
from blueprint_symbols.variants import (make_variant, rotate90, skew, symbols_survive,
                                        EVAL_VARIANTS, TRAIN_VARIANTS)
from blueprint_symbols.multiscale import ScaleTile, plan_pyramid_tiles, sample_tile
from blueprint_symbols.label_schema import allowed_rotations, load_training_page

# Target refers to MAX(width, height) of a symbol as seen by the detector.
# Full-page inference tiles the page at its native 150 DPI render (scale 1),
# where verified symbols measure ~24-115 px (median ~59). Training draws a
# size around that, and TRAIN_SCALE_LIMITS keeps each tile within 0.6-2.2
# source px per model px: a 32 px symbol is never shrunk to 2-5 px (the old
# bands drew 10-40 px for most tiles: s up to ~6) nor upsampled into blur.
# Large symbols carry real weight: a detector that saw 112-160 px in 5% of
# tiles lost a troffer above ~115 px (0.99 at 85 px, ~0 at 130 px).
PIXEL_BANDS = ((14,20,.04),(20,32,.13),(32,48,.21),(48,72,.23),(72,112,.23),(112,160,.16))
TRAIN_SCALE_LIMITS = (.6, 2.2)        # source px per model px for symbol-centred tiles
TRAIN_RANDOM_SCALES = (.75, 1.6)      # tiles not centred on a symbol (background, negatives)
MIN_SYMBOL_PIXELS = 14.               # never shrink the picked symbol below this
BLANK_VARIANTS = 2                    # variants of a nearly blank kept negative tile
# A document's verified-blank pages together give at most this many pages'
# worth of base tiles (each page still gets REAL_BLANK_MIN_TILES), so one big
# PDF of blank sheets does not dominate the real negatives.
REAL_BLANK_DOCUMENT_PAGES = 6
REAL_BLANK_MIN_TILES = 8
HARD_NEGATIVE_VARIANTS = 4
HARD_NEGATIVES_ENV = 'PHASE2_HARD_NEGATIVES'
HARD_NEGATIVE_VARIANTS_ENV = 'PHASE2_HARD_NEGATIVE_VARIANTS'


def real_blank_tiles_per_record(groups: list[str], per_page: int, per_document: int) -> list[int]:
    """Base tiles for each verified-blank real page (``groups``: its document),
    capping a document at ``per_document`` tiles (0 = no cap), never below
    ``min(per_page, REAL_BLANK_MIN_TILES)`` per page."""
    counts = Counter(groups)
    floor = min(per_page, REAL_BLANK_MIN_TILES)
    out = []
    for g in groups:
        n = counts[g]
        if per_document <= 0 or n * per_page <= per_document:
            out.append(per_page)
        else:
            out.append(max(floor, min(per_page, per_document // n)))
    return out


def load_hard_negative_windows(path: str | Path) -> list[dict]:
    """Windows of scripts_mine_hard_negatives.py (validated)."""
    data = json.loads(Path(path).read_text())
    if data.get('format') != 'phase2_hard_negative_windows' or int(data.get('format_version', 0)) != 1:
        raise ValueError(f'not a hard-negative windows file: {path}')
    out = []
    for row in data.get('windows', []):
        x0, y0, x1, y1 = (float(v) for v in row['window_xyxy'])
        if not (x1 > x0 and y1 > y0):
            raise ValueError(f'bad hard-negative window: {row}')
        if row.get('split', 'train') != 'train':
            raise ValueError('hard-negative windows must come from TRAIN pages only')
        out.append(row)
    return out


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, 'rb') as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b''):
            digest.update(chunk)
    return digest.hexdigest()


def hard_negative_tile(window, rec, rng: np.random.Generator, *, jitter: bool, size: int = 512) -> ScaleTile:
    """The tile showing ``window`` (page px) of ``rec``; with ``jitter`` the
    view shifts by up to a quarter tile and rescales 0.8-1.3x, keeping the
    window's centre (where the false detection was) in view."""
    x0, y0, x1, y1 = (float(v) for v in window)
    s = max(x1 - x0, y1 - y0) / size
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    if jitter:
        s *= float(np.exp(rng.uniform(np.log(.8), np.log(1.3))))
        span = size * s
        cx += float(rng.uniform(-.25, .25)) * span
        cy += float(rng.uniform(-.25, .25)) * span
    s = max(TRAIN_SCALE_LIMITS[0], min(TRAIN_SCALE_LIMITS[1], s))
    span = size * s
    tx = float(np.clip(cx - span / 2, 0, max(0., rec.width - span)))
    ty = float(np.clip(cy - span / 2, 0, max(0., rec.height - span)))
    return ScaleTile(tx, ty, s, size, rec.width, rec.height)

def _export_annotations(instances: list[dict], tile, classes: dict[str,int],
                        represented_indices: set[int] | None = None):
    targets=[];ignored=[]
    for idx,inst in enumerate(instances):
        a,b,c,d=map(float,inst['bbox_xyxy'])
        u0,v0=(a-tile.x)/tile.source_scale,(b-tile.y)/tile.source_scale
        u1,v1=(c-tile.x)/tile.source_scale,(d-tile.y)/tile.source_scale
        x0,y0,x1,y1=max(0,u0),max(0,v0),min(tile.size,u1),min(tile.size,v1)
        if x1<=x0 or y1<=y0:continue
        area=max(1e-9,(u1-u0)*(v1-v0))
        coverage=(x1-x0)*(y1-y0)/area
        cls=inst.get('symbol_family')
        if (cls not in classes or not trusted_training_instance(inst) or coverage < .98):
            ignored.append([x0,y0,x1,y1])
        else:
            targets.append((classes[cls],(x0,y0,x1,y1)))
            if represented_indices is not None:
                represented_indices.add(idx)
    return targets,ignored


def mask_ignored(image: np.ndarray, ignored, keep=()) -> tuple[np.ndarray, int]:
    """Paint ignore regions out with the tile's paper colour instead of
    discarding the whole tile.

    An ignore region is an edge-clipped symbol (<98% inside the tile), a
    pending/unverified box, or an ``__unmapped__`` / non-class box. Plain YOLO
    training has no ignore boxes, so leaving the ink there would teach it as
    background; dropping the tile threw away every verified symbol beside it.
    Pixels inside a kept target box are never painted (an overlapping pending
    duplicate must not erase a verified symbol). Returns (image, regions painted).
    """
    if not ignored:
        return image, 0
    h, w = image.shape[:2]
    mask = np.zeros((h, w), np.uint8)
    for x0, y0, x1, y1 in ignored:
        # pad 2 px: anti-aliased stroke edges outside the labelled box
        mask[max(0, int(np.floor(y0)) - 2):min(h, int(np.ceil(y1)) + 2),
             max(0, int(np.floor(x0)) - 2):min(w, int(np.ceil(x1)) + 2)] = 1
    for _, (x0, y0, x1, y1) in keep:
        mask[max(0, int(np.floor(y0))):min(h, int(np.ceil(y1))),
             max(0, int(np.floor(x0))):min(w, int(np.ceil(x1)))] = 0
    if not mask.any():
        return image, 0
    flat = image.reshape(-1, image.shape[2]) if image.ndim == 3 else image.reshape(-1, 1)
    paper = np.percentile(flat, 90, axis=0).astype(image.dtype)   # paper, not ink
    out = image.copy()
    out[mask.astype(bool)] = paper if image.ndim == 3 else paper[0]
    return out, len(ignored)


def train_variant(image: np.ndarray, targets, classes: dict[str,int], *, base_index: int,
                  variant_index: int, seed: int):
    """One exported training variant of a base tile: (image, targets, damage).

    The single place the export augments a training tile (the tile preview,
    ``scripts_preview_training_tiles.py``, runs exactly this).

    Variant 0 is the clean upright tile. Every other variant is turned by a
    multiple of 90 degrees that every labelled device in the tile allows
    (catalog ``augmentation``; ``label_schema.allowed_rotations``; never
    mirrored), then damaged (``TRAIN_VARIANTS``: vector re-render, scan,
    ...); scan variants are also skewed up to 1.5 degrees.
    """
    variant_name=TRAIN_VARIANTS[min(variant_index,len(TRAIN_VARIANTS)-1)]
    variant_seed=int.from_bytes(hashlib.sha256(f'{seed}/train/{base_index}/{variant_index}'.encode()).digest()[:8],'big')
    if variant_index >= len(TRAIN_VARIANTS):
        variant_name='mixed_random' if variant_index % 2 else 'lowres_random'
    rng=np.random.default_rng(variant_seed)
    names={v:k for k,v in classes.items()}
    allowed=set(allowed_rotations(None))          # an empty tile: background text rules
    for cls,_ in targets:
        allowed&=set(allowed_rotations(names.get(cls)))
    degrees=0 if variant_index==0 else int(rng.choice(sorted(allowed | {0})))
    targets=list(targets)
    if degrees:
        image,targets=rotate90(image,targets,degrees//90)
    tilt=0.
    if variant_name in ('scan_random','mixed_random') and rng.random()<.7:
        tilt=float(rng.uniform(-1.5,1.5))
        image,targets=skew(image,targets,tilt)
    variant,damage=make_variant(image,rng,variant_name)
    if targets and not symbols_survive(image,variant,targets):
        # The damage wiped a labelled symbol out (a hard threshold or heavy
        # blur on faint lines): draw once more, else keep the tile undamaged.
        variant,damage=make_variant(image,rng,variant_name)
        if not symbols_survive(image,variant,targets):
            variant,damage=image.copy(),{'variant':variant_name,'reverted':'symbol_ink_lost',
                                         'operators':[]}
    damage.update(rotation_degrees=degrees,skew_degrees=round(tilt,3))
    return variant,targets,damage


def _save_sample(out:Path, split:str, name:str, img:np.ndarray, labels, *, counters:Counter, fmt='.png'):
    image_dir=out/'images'/split
    label_dir=out/'labels'/split
    image_dir.mkdir(parents=True,exist_ok=True)
    label_dir.mkdir(parents=True,exist_ok=True)
    path=image_dir/f'{name}{fmt}'
    if not cv2.imwrite(str(path),img):
        raise OSError(f'could not write tile image: {path}')
    h,w=img.shape[:2]
    rows=[]
    for cls,(x0,y0,x1,y1) in labels:
        if not (x1>x0 and y1>y0):continue
        x,y=(x0+x1)/(2*w),(y0+y1)/(2*h)
        bw,bh=(x1-x0)/w,(y1-y0)/h
        if not all(0 <= q <= 1 for q in (x,y,bw,bh)):
            raise ValueError('invalid YOLO coordinates')
        rows.append(f'{cls} {x:.8f} {y:.8f} {bw:.8f} {bh:.8f}')
    (label_dir/f'{name}.txt').write_text('\n'.join(rows)+('\n' if rows else ''))
    counters[f'{split}_images']+=1
    counters[f'{split}_symbols']+=len(rows)


def _img(rec):
    img=cv2.imread(str(rec.image),cv2.IMREAD_COLOR)
    if img is None or img.shape[:2]!=(rec.height,rec.width):
        raise ValueError(f'bad source raster: {rec.image}')
    return img


def _ink_fraction(bgr: np.ndarray, threshold: int = 190) -> float:
    gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY) if bgr.ndim == 3 else bgr
    return float((gray < threshold).mean())


def _is_real_source_type(source_type: str | None) -> bool:
    """True only for genuine non-composited construction sheets."""
    st = str(source_type or '').lower()
    if not st or any(tok in st for tok in ('synthetic', 'neca_svg', 'composite', 'wall_only')):
        return False
    return 'real' in st


def run(manifest:Path,out:Path,*,samples=32,seed=2026,scales=(1.,),overlap=128,
        stress_val=False,stress_test=False,allow_unverified_completeness=False,
        allow_partial_training=False, train_variants=3, eval_variants=None, train_negative_fraction=.12,
        blank_ink_threshold=.015, blank_negative_fraction=.02,
        verified_wall_negative_fraction=.65, verified_wall_blank_fraction=.12,
        real_blank_tiles_per_page: int | None = None,
        real_complete_tiles_per_page: int | None = None,
        real_blank_negative_fraction: float | None = None,
        real_complete_negative_fraction: float | None = None,
        eval_empty_keep_fraction=.25, eval_blank_keep_fraction=.02,
        blank_variants: int = BLANK_VARIANTS,
        real_blank_tiles_per_document: int | None = None,
        hard_negatives: str | Path | None = None,
        hard_negative_variants: int | None = None) -> dict:
    """Export the manifest's splits as YOLO tiles (see the module docstring).

    Training variants: positive tiles get ``train_variants``; empty tiles of
    attested symbol-free sources (verified-blank pages, walls-only) get the
    same; other empty tiles 2; nearly blank tiles ``blank_variants`` (never
    more than ``train_variants``).
    ``real_blank_tiles_per_document``: cap on one document's verified-blank
    base tiles (default ``REAL_BLANK_DOCUMENT_PAGES`` pages' worth; 0 = off).
    ``hard_negatives``: windows JSON (default: ``$PHASE2_HARD_NEGATIVES``),
    each exported as ``hard_negative_variants`` (default 4, or
    ``$PHASE2_HARD_NEGATIVE_VARIANTS``) verified-empty ``real_blank`` tiles
    of its TRAIN page; windows of any other page are skipped and counted.
    """
    if hard_negatives is None:
        hard_negatives = os.environ.get(HARD_NEGATIVES_ENV) or None
    if hard_negative_variants is None:
        hard_negative_variants = int(os.environ.get(HARD_NEGATIVE_VARIANTS_ENV) or HARD_NEGATIVE_VARIANTS)
    if not 1 <= hard_negative_variants <= 12 or not 1 <= blank_variants <= 12:
        raise ValueError('bad tiling options')
    mf=json.loads(manifest.read_text())
    validate_manifest_sources(mf)
    splits=mf.get('splits',{})
    if not all(splits.get(s) for s in ('train','val','test')):
        raise ValueError('expected non-empty train/val/test split manifest')
    if out.exists() and any(out.iterdir()):
        raise FileExistsError(f'refusing to overwrite nonempty output directory: {out}')
    classes=mf['class_ids']
    if sorted(classes.values()) != list(range(len(classes))):
        raise ValueError('class ids must be consecutive')
    if (samples < 1 or not 1 <= train_variants <= 12 or not scales or
            overlap < 0 or overlap >= 512 or not 0 <= train_negative_fraction <= 1 or
            not 0 <= verified_wall_negative_fraction <= 1 or
            not 0 <= verified_wall_blank_fraction <= 1 or
            (real_blank_tiles_per_page is not None and real_blank_tiles_per_page < 1) or
            (real_complete_tiles_per_page is not None and real_complete_tiles_per_page < 1) or
            (real_blank_negative_fraction is not None and not 0 <= real_blank_negative_fraction <= 1) or
            (real_complete_negative_fraction is not None and not 0 <= real_complete_negative_fraction <= 1)):
        raise ValueError('bad tiling options')
    # Positive-only partial pages need a class-aware masked loss in phase two.
    # The legacy unverified exploratory override must never bypass that requirement.
    if allow_partial_training and allow_unverified_completeness:
        raise ValueError('partial training cannot combine with unsafe completeness override')
    per_split={key:read_records(value,classes=classes) for key,value in splits.items()}
    unchecked=[]
    incomplete_eval=[]
    for s in per_split:
        for path in splits[s]:
            data=load_training_page(path)
            negative=data.get('verified_no_symbols',False)
            if type(negative) is not bool or (negative and data['instances']):
                raise ValueError(f'invalid verified_no_symbols negative attestation: {path}')
            exhaustive=set(classes) if negative else set(data.get('exhaustive_families', []))
            if not set(classes).issubset(exhaustive):
                unchecked.append(path)
                if s in ('val', 'test'):
                    incomplete_eval.append(path)
    if allow_partial_training:
        if incomplete_eval:
            raise ValueError(f'{len(incomplete_eval)} incomplete validation/test pages; '
                             'partial pages may only contribute training positives')
    elif unchecked and not allow_unverified_completeness:
        raise ValueError(f'{len(unchecked)} pages lack exhaustive_families for all classes; '
                         'use --allow-partial-training for a masked-loss export '
                         'or --allow-unverified-completeness for UNSAFE exploratory export')
    count=Counter();groups={}; meta=[]
    supervision={}
    sample_roles={}
    evaluation_coverage={}
    for split,recs in per_split.items():
        for rec in recs:
            if rec.group in groups and groups[rec.group]!=split:
                raise ValueError(f'cross-split group leakage: {rec.group}')
            groups[rec.group]=split
    class_set = set(classes)
    samples_per_record = None
    blank_per_page = real_blank_tiles_per_page if real_blank_tiles_per_page is not None else samples
    if real_blank_tiles_per_document is None:
        real_blank_tiles_per_document = REAL_BLANK_DOCUMENT_PAGES * blank_per_page
    real_blank_recs = [rec for rec in per_split['train']
                       if _is_real_source_type(rec.source_type) and rec.verified_no_symbols]
    blank_counts = dict(zip(map(id, real_blank_recs), real_blank_tiles_per_record(
        [rec.group for rec in real_blank_recs], blank_per_page, real_blank_tiles_per_document)))
    if (real_blank_tiles_per_page is not None or real_complete_tiles_per_page is not None
            or any(n != samples for n in blank_counts.values())):
        samples_per_record = []
        for rec in per_split['train']:
            is_real_rec = _is_real_source_type(rec.source_type)
            if is_real_rec and rec.verified_no_symbols:
                samples_per_record.append(blank_counts[id(rec)])
                count['train_real_blank_base_tiles'] += blank_counts[id(rec)]
            elif is_real_rec and class_set.issubset(rec.exhaustive_families):
                samples_per_record.append(
                    real_complete_tiles_per_page if real_complete_tiles_per_page is not None else samples
                )
            else:
                samples_per_record.append(samples)
    train=RasterTileDataset(per_split['train'], classes, size=512,
        virtual_tiles_per_image=samples, seed=seed, source_scales=TRAIN_RANDOM_SCALES,
        target_symbol_pixels=PIXEL_BANDS, degradation_profile=None,
        scale_limits=TRAIN_SCALE_LIMITS, min_symbol_pixels=MIN_SYMBOL_PIXELS,
        samples_per_record=samples_per_record)
    for i in range(len(train)):
        rgb,targets,info=train.sample(i)
        image=cv2.cvtColor(rgb,cv2.COLOR_RGB2BGR)
        # Clipped/pending/unmapped boxes are painted out, the tile is kept.
        image,masked=mask_ignored(image,info['ignore_boxes'],targets)
        if masked:
            count['train_tiles_with_masked_regions']+=1
            count['train_masked_regions']+=masked
        complete_names=set(info['exhaustive_families'])
        verified_class_ids=sorted(classes[k] for k in classes if k in complete_names)
        partial_positive_only=len(verified_class_ids)<len(classes)
        is_real = _is_real_source_type(info.get('source_type'))
        if partial_positive_only:
            sample_role = 'partial'
        elif is_real and info.get('verified_page_negative'):
            sample_role = 'real_blank'
        elif is_real:
            sample_role = 'real_complete'
        else:
            sample_role = 'synthetic_complete'
        is_empty=not targets
        if allow_partial_training and is_empty and not verified_class_ids:
            # This tile has neither a labeled positive nor any verified
            # background class. Every classification gradient would be masked.
            count['train_skipped_uninformative_partial']+=1
            continue
        is_blank=is_empty and _ink_fraction(image) < blank_ink_threshold
        if is_empty:
            # Fully annotated symbol-free tiles are necessary hard negatives, but
            # mostly-white tiles with no/few lines are strongly devalued and get fewer variants.
            neg_seed=int.from_bytes(hashlib.sha256(f'{seed}/negative/{i}'.encode()).digest()[:8],'big')
            if info.get('verified_wall_background') or info.get('verified_page_negative'):
                # Empty crops from attested symbol-free sources are meaningful
                # hard negatives; retain more without flooding on blank paper.
                nonblank_p = (
                    real_blank_negative_fraction
                    if (is_real and info.get('verified_page_negative') and real_blank_negative_fraction is not None)
                    else verified_wall_negative_fraction
                )
                keep_p = verified_wall_blank_fraction if is_blank else nonblank_p
            elif is_real and not partial_positive_only and real_complete_negative_fraction is not None:
                keep_p = blank_negative_fraction if is_blank else real_complete_negative_fraction
            else:
                keep_p=blank_negative_fraction if is_blank else train_negative_fraction
            if np.random.default_rng(neg_seed).random() >= keep_p:
                count['train_skipped_blank' if is_blank else 'train_skipped_empty']+=1
                continue
            count['train_accepted_blank_negatives' if is_blank else 'train_accepted_negative_bases']+=1
        # Multiple *independent* variants of the SAME crop. Source group stays
        # unchanged. Positive tiles and empty tiles of attested symbol-free
        # sources get all variants (these teach "no symbol here"); other
        # empty tiles 2, nearly blank ones ``blank_variants``.
        verified_source = bool(info.get('verified_wall_background') or info.get('verified_page_negative'))
        if is_blank:
            n_variants = min(blank_variants, train_variants)
        elif is_empty and not verified_source:
            n_variants = min(2, train_variants)
        else:
            n_variants = train_variants
        for variant_index in range(n_variants):
            variant,variant_targets,damage=train_variant(image,targets,classes,base_index=i,
                                                         variant_index=variant_index,seed=seed)
            name=f'train_{i:08d}_v{variant_index:02d}'
            _save_sample(out,'train',name,variant,variant_targets,counters=count)
            supervision[name]=verified_class_ids
            sample_roles[name]=sample_role
            count[f'train_role_{sample_role}_images']+=1
            count['train_partial_images' if partial_positive_only else 'train_complete_images']+=1
            meta.append({'verified_negative_class_ids':verified_class_ids,
                         'partial_positive_only':partial_positive_only,
                         'sample_role':sample_role,
                         'source_type':info['source_type'],
                         'verified_page_negative':info.get('verified_page_negative', False),
                         'sample':name,'source':info['image_path'],'group':info['group'],
                         'source_box':list(info['tile'].source_box),
                         'source_scale':info['tile'].source_scale,
                         'target_symbol_pixels':info['target_symbol_pixels'],
                         'damage':damage,'split':'train', 'variant_index':variant_index,
                         'base_sample':f'train_{i:08d}',
                         'masked_ignore_regions':masked})
    if hard_negatives:
        _export_hard_negatives(load_hard_negative_windows(hard_negatives), per_split, classes,
                               out=out, seed=seed, variants=hard_negative_variants, count=count,
                               supervision=supervision, sample_roles=sample_roles, meta=meta)
    # Full-coverage tiles for VAL and TEST. No random sampling or augmentation.
    for split in ('val','test'):
        for ri,rec in enumerate(per_split[split]):
            image=_img(rec)
            expected={idx for idx,inst in enumerate(rec.instances)
                      if inst.get('symbol_family') in classes and trusted_training_instance(inst)}
            observed=set()
            tiles=plan_pyramid_tiles(rec.width,rec.height,tile_size=512,overlap=overlap,source_scales=scales)
            for ti,tile in enumerate(tiles):
                represented=set()
                targets,ignored=_export_annotations(rec.instances,tile,classes,represented)
                # Keep explicit source-instance coverage separate from mAP.
                # Overlapping validation tiles can duplicate detections; they
                # cannot substitute for fused full-page evaluation.
                observed.update(represented & expected)
                patch,masked=mask_ignored(sample_tile(image,tile),ignored,targets)
                if masked:
                    count[f'{split}_tiles_with_masked_regions']+=1
                if not targets:
                    is_blank=_ink_fraction(patch) < blank_ink_threshold
                    ev_seed=int.from_bytes(hashlib.sha256(f'{seed}/{split}_empty/{ri}/{ti}'.encode()).digest()[:8],'big')
                    keep_p=eval_blank_keep_fraction if is_blank else eval_empty_keep_fraction
                    if np.random.default_rng(ev_seed).random() >= keep_p:
                        count[f'{split}_skipped_blank_empty' if is_blank else f'{split}_skipped_empty']+=1
                        continue
                    count[f'{split}_empty_complete_tiles']+=1
                name=f'{split}_{ri:05d}_{ti:06d}'
                _save_sample(out,split,name,patch,targets,counters=count)
                meta.append({'sample':name,'source':str(rec.image),'group':rec.group,
                             'source_box':list(tile.source_box),'source_scale':tile.source_scale,
                             'damage':None,'split':split})
                if (split=='val' and stress_val) or (split=='test' and stress_test):
                    # Diagnostic-only stress partition; does NOT replace clean metric.
                    for profile in (eval_variants if eval_variants is not None else EVAL_VARIANTS[1:]):
                        profile_seed=int.from_bytes(hashlib.sha256(f'{seed}/{name}/{profile}'.encode()).digest()[:8],'big')
                        variant,report=make_variant(patch,np.random.default_rng(profile_seed),profile)
                        stress_split=f'{split}_stress_{profile}'
                        _save_sample(out,stress_split,name,variant,targets,counters=count)
                        meta.append({'sample':name,'source':str(rec.image),'group':rec.group,
                                     'source_box':list(tile.source_box),'source_scale':tile.source_scale,
                                     'damage':report,'split':stress_split,
                                     'base_sample':name})
            missing=expected-observed
            evaluation_coverage[str(rec.image)]={'split':split,'annotated_instances':len(expected),
                                                 'represented_in_tiles':len(observed),
                                                 'missing':len(missing)}
            if missing:
                count[f'{split}_unrepresented_symbols']+=len(missing)
    if count['val_unrepresented_symbols'] or count['test_unrepresented_symbols']:
        raise ValueError('held-out tile set does not cover every labeled source symbol; '
                         f'val missing={count["val_unrepresented_symbols"]}, '
                         f'test missing={count["test_unrepresented_symbols"]}. '
                         'Adjust overlap/scales or resolve ignored/clipped annotations; '
                         'do not report incomplete tile metrics as complete.')
    out.mkdir(parents=True,exist_ok=True)
    names='\n'.join(f'  {v}: {json.dumps(k)}' for k,v in sorted(classes.items(),key=lambda x:x[1]))
    # Signal that this export requires the supervision sidecar even if it is
    # accidentally removed. The training launcher must fail rather than
    # silently falling back to all-background YOLO labels.
    (out/'data.yaml').write_text(
        f'path: {out.resolve()}\ntrain: images/train\nval: images/val\n'
        f'test: images/test\nphase2_supervision: phase2_supervision.json\nnames:\n{names}\n'
    )
    # Each diagnostic set has a separate YAML. No stress images become training
    # examples; test is NEVER used by the training launcher or early stopping.
    eval_dir=out/'evaluation_configs'
    eval_dir.mkdir(parents=True, exist_ok=True)
    for split in ('val','test'):
        used=[split] + sorted(k for k in count if k.startswith(split+'_stress_') and k.endswith('_images'))
        for dataset in used:
            key=dataset[:-7] if dataset.endswith('_images') else dataset
            lines=(f'path: {out.resolve()}\ntrain: images/train\n'
                   f'val: images/{key}\n' + f'names:\n{names}\n')
            (eval_dir/f'{key}.yaml').write_text(lines)
    (out/'sample_provenance.jsonl').write_text(''.join(json.dumps(row)+'\n' for row in meta))
    # Separate file required by the actual training loader. Standard YOLO
    # labels alone cannot encode positive-only supervision.
    (out/'phase2_supervision.json').write_text(json.dumps({
        'format_version':1,
        'class_ids':classes,
        'partial_training_enabled':allow_partial_training,
        'samples':supervision,
        'sample_roles':sample_roles,
    },indent=2))
    report={'source_manifest':str(manifest.resolve()),'classes':classes,
            'counts':dict(count),'unverified_annotation_completeness':sorted(unchecked),
            'notes':['No training performed.','Only trusted class-labeled instances exported.',
                     'Ignored regions (edge-clipped, pending/unverified, unmapped boxes) are painted '
                     'out with paper colour; the tile and its verified symbols are kept.',
                     'Held-out whole-page evaluation must fuse tiled predictions and count missed instances.',
                     'Stress tiles are diagnostics; realistic held-out real photos/scans remain necessary.',
                     'Paired variants are correlated; report source-group-level metrics, not independent-sample confidence.'],
            'evaluation_source_instance_coverage':evaluation_coverage,
            'train_variants_per_base':train_variants,
            'partial_training_enabled':allow_partial_training,
            'train_incomplete_source_pages':[str(p) for p in unchecked if p not in incomplete_eval],
            'train_negative_fraction':train_negative_fraction,
            'verified_wall_negative_fraction':verified_wall_negative_fraction,
            'verified_wall_blank_fraction':verified_wall_blank_fraction,
            'seed':seed,
            'blank_variants':blank_variants,
            'real_blank_tiles_per_document':real_blank_tiles_per_document,
            'hard_negatives':str(hard_negatives) if hard_negatives else None,
            'hard_negative_variants':hard_negative_variants,
            'evaluation_variants':list(eval_variants) if eval_variants is not None else list(EVAL_VARIANTS[1:])}
    (out/'export_report.json').write_text(json.dumps(report,indent=2))
    if count['train_images']==0 or count['val_images']==0 or count['test_images']==0:
        raise ValueError('one of train/val/test generated zero usable tiles; inspect export_report.json')
    return report


def _export_hard_negatives(windows, per_split, classes, *, out, seed, variants, count,
                           supervision, sample_roles, meta) -> None:
    """Extra verified-empty training tiles at mined false-detection windows.

    A window is used only on a TRAIN record that is a real verified-blank
    page with the same image (path, or SHA-256 when the path differs and the
    window records it). Every other window (holdout, evaluation, val/test,
    unknown or changed image) is skipped and counted, never exported."""
    train = [rec for rec in per_split['train']
             if _is_real_source_type(rec.source_type) and rec.verified_no_symbols and not rec.instances]
    by_path = {str(Path(rec.image).resolve()): rec for rec in train}
    held = {str(Path(rec.image).resolve()) for split in ('val', 'test') for rec in per_split.get(split, [])}
    sha_cache: dict[str, str] = {}

    def sha(rec):
        key = str(rec.image)
        if key not in sha_cache:
            sha_cache[key] = _sha256_file(Path(rec.image))
        return sha_cache[key]

    by_sha = None
    all_ids = sorted(classes.values())
    images: dict[str, np.ndarray] = {}
    for wi, row in enumerate(windows):
        path = str(Path(str(row.get('image_path') or '')).resolve()) if row.get('image_path') else ''
        rec = by_path.get(path)
        want = row.get('image_sha256')
        if rec is None and want:
            if by_sha is None:
                by_sha = {sha(r): r for r in train}
            rec = by_sha.get(want)
        if rec is None or path in held:
            count['train_hard_negative_windows_skipped'] += 1
            continue
        if want and sha(rec) != want:
            count['train_hard_negative_windows_changed_image'] += 1
            continue
        key = str(rec.image)
        if key not in images:
            if len(images) > 4:
                images.clear()
            images[key] = _img(rec)
        image = images[key]
        base = f'train_hn_{wi:06d}'
        for k in range(variants):
            rng = np.random.default_rng(np.random.SeedSequence(
                [int(seed) & 0xFFFFFFFF, 0x484E, wi, k]))
            tile = hard_negative_tile(row['window_xyxy'], rec, rng, jitter=k > 0)
            patch = sample_tile(image, tile)
            variant, _, damage = train_variant(patch, [], classes, base_index=10**9 + wi,
                                               variant_index=k, seed=seed)
            name = f'{base}_v{k:02d}'
            _save_sample(out, 'train', name, variant, [], counters=count)
            supervision[name] = all_ids
            sample_roles[name] = 'real_blank'
            count['train_role_real_blank_images'] += 1
            count['train_complete_images'] += 1
            count['train_hard_negative_images'] += 1
            meta.append({'verified_negative_class_ids': all_ids, 'partial_positive_only': False,
                         'sample_role': 'real_blank', 'source_type': rec.source_type,
                         'verified_page_negative': True, 'hard_negative': True,
                         'hard_negative_max_confidence': row.get('max_confidence'),
                         'sample': name, 'source': str(rec.image), 'group': rec.group,
                         'source_box': list(tile.source_box), 'source_scale': tile.source_scale,
                         'target_symbol_pixels': None, 'damage': damage, 'split': 'train',
                         'variant_index': k, 'base_sample': base, 'masked_ignore_regions': 0})
        count['train_hard_negative_windows'] += 1


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('manifest',type=Path);p.add_argument('output_dir',type=Path)
    p.add_argument('--train-tiles-per-page',type=int,default=32)
    p.add_argument('--train-variants',type=int,default=3,help='clean + independently downsampled/damaged variants per crop')
    p.add_argument('--train-negative-fraction',type=float,default=.20,help='retain completely annotated symbol-free tiles')
    p.add_argument('--verified-wall-negative-fraction',type=float,default=.65,
                   help='retain nonblank empty tiles from verified wall-only backgrounds')
    p.add_argument('--verified-wall-blank-fraction',type=float,default=.12,
                   help='retain nearly blank empty tiles from verified wall-only backgrounds')
    p.add_argument('--real-blank-tiles-per-page',type=int,default=None,
                   help='override base tile sample count for verified-blank real pages')
    p.add_argument('--real-complete-tiles-per-page',type=int,default=None,
                   help='override base tile sample count for fully-labeled real pages')
    p.add_argument('--real-blank-negative-fraction',type=float,default=None,
                   help='retain nonblank negative tiles from verified-blank real pages')
    p.add_argument('--real-complete-negative-fraction',type=float,default=None,
                   help='retain nonblank empty tiles from fully-labeled real pages')
    p.add_argument('--blank-variants',type=int,default=BLANK_VARIANTS,
                   help='variants of a kept nearly blank negative tile (at most --train-variants)')
    p.add_argument('--real-blank-tiles-per-document',type=int,default=None,
                   help=f'cap one document\'s verified-blank base tiles (default {REAL_BLANK_DOCUMENT_PAGES} '
                        'pages\' worth; 0 = no cap)')
    p.add_argument('--hard-negatives',type=Path,default=None,
                   help=f'scripts_mine_hard_negatives.py windows JSON (default ${HARD_NEGATIVES_ENV})')
    p.add_argument('--hard-negative-variants',type=int,default=None,
                   help=f'tiles per hard-negative window (default {HARD_NEGATIVE_VARIANTS})')
    p.add_argument('--eval-scales',default='1.0',help='comma-separated source pixels per model pixel')
    p.add_argument('--overlap',type=int,default=128)
    p.add_argument('--seed',type=int,default=2026)
    p.add_argument('--stress-val',action='store_true')
    p.add_argument('--stress-test',action='store_true')
    p.add_argument('--allow-unverified-completeness',action='store_true')
    p.add_argument('--allow-partial-training',action='store_true',
                   help='use class-masked loss for partial real positives; evaluation must be exhaustive')
    args=p.parse_args()
    report=run(args.manifest,args.output_dir,samples=args.train_tiles_per_page,
               seed=args.seed,scales=tuple(float(s) for s in args.eval_scales.split(',')),
               overlap=args.overlap,stress_val=args.stress_val,stress_test=args.stress_test,
               allow_unverified_completeness=args.allow_unverified_completeness,
               allow_partial_training=args.allow_partial_training,
               train_variants=args.train_variants,train_negative_fraction=args.train_negative_fraction,
               verified_wall_negative_fraction=args.verified_wall_negative_fraction,
               verified_wall_blank_fraction=args.verified_wall_blank_fraction,
               real_blank_tiles_per_page=args.real_blank_tiles_per_page,
               real_complete_tiles_per_page=args.real_complete_tiles_per_page,
               real_blank_negative_fraction=args.real_blank_negative_fraction,
               real_complete_negative_fraction=args.real_complete_negative_fraction,
               blank_variants=args.blank_variants,
               real_blank_tiles_per_document=args.real_blank_tiles_per_document,
               hard_negatives=args.hard_negatives,
               hard_negative_variants=args.hard_negative_variants)
    print(json.dumps(report['counts'],indent=2))
if __name__=='__main__':main()
