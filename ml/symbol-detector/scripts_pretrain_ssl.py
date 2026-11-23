"""Build a split-safe blueprint-only SSL pretraining manifest and train YOLO26n-P2.

Strictly excludes all validation and test plans:
  - CubiCasa5K official: train.txt ONLY (val.txt and test.txt excluded)
  - FloorPlanCAD full: train-00 / train-01 clean PNGs ONLY (test-00 and coco_vis excluded)
  - Rendered vector layouts: excludes any layout ID in cubicasa5k_hf/{val,test}.jsonl
    or resplan/{val,test}.jsonl
  - Curated page pool (background, reserve, label_queue): excludes quarantined/junk
    pages and holds out a deterministic 10% group-level validation split

All randomization, geometric variability, resolution filtering, common filtering,
and JPEG compression noise are applied on-the-fly at runtime inside the DataLoader.

Usage:
  python scripts_pretrain_ssl.py --work-root ../work --data-root ../data/blueprints_and_libraries \
      --out ../work/pretrain_v1 --epochs 60 --batch 16 --run
"""
from __future__ import annotations

import argparse
import glob
import hashlib
import json
import math
import time
from contextlib import nullcontext
from collections import defaultdict
from pathlib import Path
from typing import Any

import cv2
import torch
from torch.utils.data import DataLoader


def _worker_init(_worker_id: int) -> None:
    cv2.setNumThreads(1)
    torch.set_num_threads(1)

from blueprint_symbols.ssl_pretrain import (
    BlueprintSSLDataset,
    BlueprintSSLLoss,
    Yolo26P2SSLModel,
    group_is_holdout,
)


def _load_jsonl_ids(paths: list[Path]) -> set[str]:
    ids: set[str] = set()
    for f in paths:
        if not f.is_file():
            continue
        for line in f.open():
            try:
                rec = json.loads(line)
                ids.add(str(rec['id']).replace('/', '_'))
            except Exception:
                continue
    return ids


def _load_cubicasa_split_ids(txt_path: Path) -> set[str]:
    if not txt_path.is_file():
        return set()
    return {line.strip().strip('/') for line in txt_path.read_text().splitlines() if line.strip()}


REAL_CONSTRUCTION_HINTS = (
    'new_downloads_202611',
    'rinker_hall',
    'njatc_plant',
    'freefarmhouse',
    'field_album_1',
    'state_dot_samples',
    'user_scans',
)


def _is_real_construction(gid: str, source_path: str) -> bool:
    hay = f'{gid} {source_path}'.lower()
    return any(h in hay for h in REAL_CONSTRUCTION_HINTS)


def assert_ssl_partition_isolation(train_manifest: dict, val_manifest: dict) -> None:
    """Reject overlapping source project IDs or image paths across SSL splits.

    A stable original-project mapping is still required when one drawing has
    different renders, filenames, or resolutions. This gate detects exact
    path/group conflicts, not perceptual near-duplicates.
    """
    def collect(manifest: dict) -> tuple[set[str], set[str]]:
        groups: set[str] = set()
        paths: set[str] = set()
        for bucket in manifest.get("buckets", {}).values():
            for gid, entries in bucket.get("groups", {}).items():
                groups.add(str(gid))
                for entry in entries:
                    path = entry["image_path"] if isinstance(entry, dict) else entry
                    paths.add(str(Path(path).expanduser().resolve()))
        return groups, paths

    train_groups, train_paths = collect(train_manifest)
    val_groups, val_paths = collect(val_manifest)
    group_overlap = train_groups & val_groups
    path_overlap = train_paths & val_paths
    if group_overlap or path_overlap:
        raise ValueError(
            "SSL train/validation source isolation violation: "
            f"groups={sorted(group_overlap)[:8]}, paths={sorted(path_overlap)[:8]}"
        )


def build_pretrain_manifests(
    data_root: Path,
    work_root: Path,
    *,
    fpcad_train_dir: Path | None = None,
    pool_val_fraction: float = 0.10,
    include_reserve: bool = False,
    labeled_annotations: list[Path] | None = None,
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    """Discover all eligible blueprint training images and strictly exclude val/test plans."""
    pool_dir = work_root / 'pool'
    junk_manifest = work_root / 'junk-review' / 'manifest.json'
    excluded_rel: set[str] = set()
    if junk_manifest.is_file():
        excluded_rel = set(json.loads(junk_manifest.read_text()).get('excluded_paths', []))

    # Exclude any groups already placed in val/test by existing manifests (e.g. smoke)
    locked_eval_groups: set[str] = set()
    for sm in work_root.glob('*/prepared/source_manifest.json'):
        try:
            d = json.loads(sm.read_text())
            for sp in ('val', 'test'):
                locked_eval_groups.update(d.get('split_groups', {}).get(sp, []))
        except Exception:
            pass

    from scripts_index_real_pdfs import reserved_sources
    reserved_file = work_root / "heldout_real_pdfs.json"
    reserved_paths, reserved_groups = reserved_sources(
        reserved_file if reserved_file.is_file() else None, data_root.resolve()
    )

    # Direct PDF page catalog: all eligible pages, no 24 MP intermediates.
    # Exclude reserved/locked groups *before* either SSL training or validation.
    pdf_index_file = work_root / "pdf_page_index" / "index.json"
    indexed_pdf_paths: set[str] = set()
    pdf_train: dict[str, list[dict[str, Any]]] = defaultdict(list)
    pdf_val: dict[str, list[dict[str, Any]]] = defaultdict(list)
    if pdf_index_file.is_file():
        index = json.loads(pdf_index_file.read_text())
        if index.get("format_version") != 1:
            raise ValueError("Unsupported direct PDF page index version")
        if Path(index.get("data_root", "")).resolve() != data_root.resolve():
            raise ValueError("PDF index was built against a different data_root")
        for rel, info in sorted(index.get("documents", {}).items()):
            gid = str(info.get("project_family_id", "")).strip()
            source = str((data_root / rel).resolve())
            if not gid or gid in reserved_groups or source in reserved_paths:
                continue
            if gid in locked_eval_groups:
                continue
            pages = info.get("pages", [])
            if not pages:
                continue
            # Include the actual source size/mtime in the training fingerprint
            # so a replaced PDF with unchanged page count cannot silently
            # change the image distribution during checkpoint resume.
            pages = [
                {**item, "pdf_source_signature": info.get("signature")}
                for item in pages
            ]
            # Source PDF is the unit of train/validation isolation. Never split
            # different pages from the same original PDF across partitions.
            target = pdf_val if group_is_holdout(gid, val_fraction=pool_val_fraction) else pdf_train
            target[gid].extend(pages)
            indexed_pdf_paths.add(source)

    train_buckets: dict[str, dict[str, Any]] = {}
    val_buckets: dict[str, dict[str, Any]] = {}
    audit: dict[str, Any] = {
        'excluded_counts': defaultdict(int),
        'included_counts': defaultdict(int),
        'val_monitor_counts': defaultdict(int),
        'locked_eval_groups_count': len(locked_eval_groups),
        'explicit_reserved_pdf_groups_count': len(reserved_groups),
        'explicit_reserved_pdf_paths_count': len(reserved_paths),
        'direct_pdf_sources': len(indexed_pdf_paths),
        'direct_pdf_pages_train': sum(len(p) for p in pdf_train.values()),
        'direct_pdf_pages_val_monitor': sum(len(p) for p in pdf_val.values()),
    }

    # 1. Real construction drawings & curated pool (deduplicated by image_sha256)
    seen_sha: set[str] = set()
    real_tr: dict[str, list[str]] = defaultdict(list)
    real_va: dict[str, list[str]] = defaultdict(list)
    other_tr: dict[str, list[str]] = defaultdict(list)
    other_va: dict[str, list[str]] = defaultdict(list)

    roles_to_scan = ['label_queue', 'background']
    if include_reserve:
        roles_to_scan.append('reserve')

    for role in roles_to_scan:
        rdir = pool_dir / role
        if not rdir.is_dir():
            continue
        for jpath in sorted(rdir.glob('*.json')):
            rel = f'{role}/{jpath.name}'
            if rel in excluded_rel:
                audit['excluded_counts'][f'{role}_junk'] += 1
                continue
            try:
                doc = json.loads(jpath.read_text())
            except Exception:
                continue
            sha = doc.get('image_sha256')
            if sha:
                if sha in seen_sha:
                    audit['excluded_counts']['duplicate_sha256'] += 1
                    continue
                seen_sha.add(sha)
            img_path = (jpath.parent / doc['image_path']).resolve()
            if not img_path.is_file():
                continue
            gid = str(doc.get('project_family_id') or jpath.stem)
            src_path = str((doc.get('pool') or {}).get('source_path') or jpath.name)
            source_file = str((data_root / src_path).resolve())
            if gid in reserved_groups or source_file in reserved_paths:
                audit['excluded_counts']['explicit_reserved_raster_source'] += 1
                continue
            if source_file in indexed_pdf_paths:
                # Prefer fresh PDF-native crops, avoid doubling the same page
                # via its stale/capped raster representation.
                audit['excluded_counts']['replaced_by_direct_pdf'] += 1
                continue
            is_real = _is_real_construction(gid, src_path) or role == 'label_queue'
            # Locked detector val/test projects must never influence even SSL
            # checkpoint selection. They are excluded rather than moved to SSL val.
            if gid in locked_eval_groups:
                audit['excluded_counts']['locked_detector_eval'] += 1
                continue
            is_val = group_is_holdout(gid, val_fraction=pool_val_fraction)
            if is_real:
                if is_val:
                    real_va[gid].append(str(img_path))
                    audit['val_monitor_counts']['real_construction_drawings'] += 1
                    audit['excluded_counts']['real_construction_holdout'] += 1
                else:
                    real_tr[gid].append(str(img_path))
                    audit['included_counts']['real_construction_drawings'] += 1
            else:
                if is_val:
                    other_va[gid].append(str(img_path))
                    audit['val_monitor_counts']['pool_other_background'] += 1
                    audit['excluded_counts']['pool_other_holdout'] += 1
                else:
                    other_tr[gid].append(str(img_path))
                    audit['included_counts']['pool_other_background'] += 1

    if pdf_train:
        train_buckets['real_pdf_pages'] = {'weight': 0.62, 'groups': dict(pdf_train)}
        audit['included_counts']['direct_pdf_pages'] = sum(
            len(v) for v in pdf_train.values()
        )
    if pdf_val:
        val_buckets['real_pdf_pages'] = {'weight': 0.62, 'groups': dict(pdf_val)}
        audit['val_monitor_counts']['direct_pdf_pages'] = sum(
            len(v) for v in pdf_val.values()
        )
    if real_tr:
        train_buckets['real_construction_drawings'] = {
            'weight': 0.30 if pdf_train else 0.55, 'groups': dict(real_tr)
        }
    if real_va:
        val_buckets['real_construction_drawings'] = {
            'weight': 0.30 if pdf_val else 0.55, 'groups': dict(real_va)
        }
    if other_tr:
        train_buckets['pool_other_background'] = {'weight': 0.08, 'groups': dict(other_tr)}
    if other_va:
        val_buckets['pool_other_background'] = {'weight': 0.15, 'groups': dict(other_va)}

    # 2. Official CubiCasa5K: train.txt ONLY (strictly exclude val.txt and test.txt)
    cc_root = data_root / '5_datasets' / 'cubicasa5k_official' / 'cubicasa5k'
    if cc_root.is_dir():
        cc_train_ids = _load_cubicasa_split_ids(cc_root / 'train.txt')
        cc_val_ids = _load_cubicasa_split_ids(cc_root / 'val.txt')
        cc_test_ids = _load_cubicasa_split_ids(cc_root / 'test.txt')
        cc_tr_groups: dict[str, list[str]] = {}
        cc_va_groups: dict[str, list[str]] = {}
        for rel_id in sorted(cc_train_ids):
            if rel_id in cc_val_ids or rel_id in cc_test_ids:
                audit['excluded_counts']['cubicasa_overlap'] += 1
                continue
            img = (cc_root / rel_id / 'F1_scaled.png').resolve()
            if img.is_file():
                cc_tr_groups[f'cubicasa_{rel_id.replace("/", "_")}'] = [str(img)]
                audit['included_counts']['cubicasa5k_train'] += 1
        for rel_id in sorted(cc_val_ids):
            img = (cc_root / rel_id / 'F1_scaled.png').resolve()
            if img.is_file():
                cc_va_groups[f'cubicasa_{rel_id.replace("/", "_")}'] = [str(img)]
                audit['val_monitor_counts']['cubicasa5k_val_readonly'] += 1
        audit['excluded_counts']['cubicasa5k_val'] = len(cc_val_ids)
        audit['excluded_counts']['cubicasa5k_test'] = len(cc_test_ids)
        if cc_tr_groups:
            train_buckets['cubicasa5k_train'] = {'weight': 0.15, 'groups': cc_tr_groups}
        if cc_va_groups:
            val_buckets['cubicasa5k_val_readonly'] = {'weight': 0.30, 'groups': cc_va_groups}

    # 3. FloorPlanCAD full train PNGs ONLY (never test-00.tar.xz, never coco_vis)
    if fpcad_train_dir and fpcad_train_dir.is_dir():
        fp_groups: dict[str, list[str]] = defaultdict(list)
        for png in sorted(fpcad_train_dir.glob('*.png')):
            drawing = png.stem.split('-')[0]
            gid = f'floorplancad_{drawing}'
            if gid in locked_eval_groups:
                audit['excluded_counts']['floorplancad_locked_eval'] += 1
                continue
            fp_groups[gid].append(str(png.resolve()))
            audit['included_counts']['floorplancad_train'] += 1
        if fp_groups:
            train_buckets['floorplancad_train'] = {'weight': 0.18, 'groups': dict(fp_groups)}

    # 4. Rendered vector layouts: exclude ALL cubicasa5k_hf / resplan val & test IDs
    bl_dir = pool_dir / 'background_layouts'
    if bl_dir.is_dir():
        ds_root = data_root / '5_datasets'
        blocked_layout_ids = _load_jsonl_ids([
            ds_root / 'cubicasa5k_hf' / 'val.jsonl',
            ds_root / 'cubicasa5k_hf' / 'test.jsonl',
            ds_root / 'resplan' / 'val.jsonl',
            ds_root / 'resplan' / 'test.jsonl',
        ])
        bl_groups: dict[str, list[str]] = {}
        for jpath in sorted(bl_dir.glob('*.json')):
            if jpath.stem in blocked_layout_ids:
                audit['excluded_counts']['layouts_val_test'] += 1
                continue
            try:
                doc = json.loads(jpath.read_text())
            except Exception:
                continue
            img_path = (jpath.parent / doc['image_path']).resolve()
            if not img_path.is_file():
                continue
            gid = str(doc.get('project_family_id') or jpath.stem)
            bl_groups[gid] = [str(img_path)]
            audit['included_counts']['rendered_layouts_train'] += 1
        if bl_groups:
            train_buckets['rendered_layouts_train'] = {'weight': 0.04, 'groups': bl_groups}

    # 5. Real, verified positive device boxes guide SSL crop selection. This
    # does NOT assert that all symbols have been labeled, and it never turns
    # unannotated regions into supervised detection negatives.
    labeled_tr: dict[str, list[dict[str, Any]]] = defaultdict(list)
    labeled_va: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for jpath in sorted(set(labeled_annotations or [])):
        try:
            doc = json.loads(jpath.read_text())
        except (OSError, ValueError):
            audit['excluded_counts']['invalid_labeled_json'] += 1
            continue
        if not doc.get('source_group_verified'):
            audit['excluded_counts']['unverified_labeled_group'] += 1
            continue
        gid = str(doc.get('project_family_id') or '').strip()
        if not gid or gid in locked_eval_groups or gid in reserved_groups:
            audit['excluded_counts']['labeled_locked_or_missing_group'] += 1
            continue
        imgpath = (jpath.parent / str(doc.get('image_path', ''))).resolve()
        if not imgpath.is_file():
            audit['excluded_counts']['labeled_image_missing'] += 1
            continue
        boxes = []
        w, h = doc.get('image_width', 0), doc.get('image_height', 0)
        for inst in doc.get('instances', []):
            if inst.get('uncertain') or inst.get('review_required'):
                continue
            box = inst.get('bbox_xyxy')
            if not isinstance(box, list) or len(box) != 4:
                continue
            try:
                x0, y0, x1, y1 = map(float, box)
            except (ValueError, TypeError):
                continue
            if all(math.isfinite(v) for v in (x0, y0, x1, y1)) and 0 <= x0 < x1 <= w and 0 <= y0 < y1 <= h:
                boxes.append([x0, y0, x1, y1])
        if not boxes:
            audit['excluded_counts']['labeled_no_usable_boxes'] += 1
            continue
        record = {'image_path': str(imgpath), 'boxes': boxes}
        target = labeled_va if group_is_holdout(gid, val_fraction=pool_val_fraction) else labeled_tr
        target[gid].append(record)
        key = 'labeled_devices_train' if target is labeled_tr else 'labeled_devices_val'
        audit['included_counts' if target is labeled_tr else 'val_monitor_counts'][key] += 1

    if labeled_tr:
        train_buckets['labeled_devices'] = {'weight': 0.24, 'groups': dict(labeled_tr)}
    if labeled_va:
        val_buckets['labeled_devices'] = {'weight': 0.24, 'groups': dict(labeled_va)}

    audit['excluded_counts'] = dict(audit['excluded_counts'])
    audit['included_counts'] = dict(audit['included_counts'])
    audit['val_monitor_counts'] = dict(audit['val_monitor_counts'])
    audit['total_train_images'] = sum(audit['included_counts'].values())
    audit['total_train_groups'] = sum(len(b['groups']) for b in train_buckets.values())
    audit['total_val_monitor_images'] = sum(audit['val_monitor_counts'].values())

    train_manifest = {'kind': 'blueprint_ssl_train', 'buckets': train_buckets}
    val_manifest = {'kind': 'blueprint_ssl_val_readonly', 'buckets': val_buckets}
    assert_ssl_partition_isolation(train_manifest, val_manifest)
    return train_manifest, val_manifest, audit


def neca_source_fingerprint(symbol_dir: Path | None) -> str | None:
    """Fingerprint SVG geometry as well as symbols.json metadata.

    A symbol's vector strokes may change without changing its metadata ID;
    resuming with the same metadata hash alone would silently change the
    denoising objective mid-run.
    """
    if symbol_dir is None:
        return None
    root = symbol_dir.resolve()
    meta_path = root / "symbols.json"
    catalog = json.loads(meta_path.read_text())
    digest = hashlib.sha256()
    digest.update(meta_path.read_bytes())
    refs = sorted({str(item["file"]) for item in catalog.get("symbols", [])})
    for relative in refs:
        svg = (root / relative).resolve()
        if not svg.is_relative_to(root):
            raise ValueError(f"NECA symbol path escapes source bank: {relative}")
        if not svg.is_file():
            # NecaSymbolBank skips missing files, but a change in availability
            # must still invalidate resume, not silently alter data sampling.
            digest.update(f"MISSING:{relative}".encode())
            continue
        digest.update(relative.encode())
        with svg.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1 << 20), b""):
                digest.update(chunk)
    return digest.hexdigest()


def run_fingerprint(train_manifest: dict, val_manifest: dict, args: argparse.Namespace) -> str:
    """Fingerprint the source manifest and training settings for safe resume.

    This is a manifest/configuration signature, not a checksum of every image
    byte. Dataset source-file provenance must still be independently managed.
    """
    fields = (
        "imgsz", "batch", "microbatch", "epochs", "items_per_epoch", "val_items",
        "optimizer", "lr", "workers", "pool_val_fraction", "seed",
        "max_symbols", "symbol_min_px", "symbol_max_px",
        "symbol_augment_probability", "procedural_fraction",
        "clean_fraction", "labeled_focus_probability",
        "symbol_chaos_probability", "symbol_chaos_strength", "placement_chaos",
        "page_chaos_probability", "page_chaos_strength",
    )
    # The defaults preserve callers constructing Namespace in older tests.
    chaos_defaults = {"microbatch": min(4, args.batch),
                      "symbol_chaos_probability": .88,
                      "symbol_chaos_strength": 1.0,
                      "placement_chaos": .85,
                      "page_chaos_probability": .70,
                      "page_chaos_strength": 1.0}
    conf = {key: getattr(args, key, chaos_defaults.get(key)) for key in fields}
    conf["microbatch"] = configured_microbatch(args)
    # Changing loss weighting changes the trained representation; old
    # optimizer state must not be resumed across objective revisions.
    conf["ssl_objective"] = "ink-paper-balanced-v2"
    conf["training_step_policy"] = "effective_batch_gradient_accumulation_v1"
    conf["source_sampling_policy"] = "paired_source_locality_permuted_pages_v1"
    conf["native_pdf_rendering"] = "uniform_physical_dpi_100_to_400_ink_anchored_v2"
    conf["neca_composition_policy"] = "standalone_figure_variants_v2"
    conf["symbol_rendering_policy"] = "shared_200dpi_reference_and_local_ink_v1"
    conf["symbol_geometry_augmentation"] = "bounded_chaos_v1"
    conf["page_geometry_augmentation"] = "aligned_multiwarp_v2_ink_retention"
    conf["clean_sampling_policy"] = "end_to_end_undistorted_crop_v1"
    conf["mask_visibility_policy"] = "known_inserted_symbols_max_60_percent_v1"
    conf["neca_svg_bank_sha256"] = neca_source_fingerprint(args.neca_dir)
    manifest = {"format": 1, "settings": conf,
                "train": train_manifest, "validation": val_manifest}
    return hashlib.sha256(json.dumps(manifest, sort_keys=True).encode()).hexdigest()


def save_checkpoints(
    out_dir: Path,
    model: Yolo26P2SSLModel,
    optimizer: torch.optim.Optimizer,
    scheduler: torch.optim.lr_scheduler.LRScheduler,
    scaler: torch.amp.GradScaler,
    epoch: int,
    best_val: float,
    history: list[dict[str, Any]],
    is_best: bool,
    fingerprint: str,
) -> None:
    # Atomic same-directory replacements retain the previous complete
    # checkpoint if the process dies during serialization.
    def atomic_save(payload: dict, destination: Path) -> None:
        temporary = destination.with_name(destination.name + '.tmp')
        torch.save(payload, temporary)
        temporary.replace(destination)

    # Full resumable pretraining state
    atomic_save({
        'epoch': epoch,
        'best_val': best_val,
        'model': model.state_dict(),
        'optimizer': optimizer.state_dict(),
        'scheduler': scheduler.state_dict(),
        'scaler': scaler.state_dict(),
        'history': history,
        'run_fingerprint': fingerprint,
        'torch_rng_state': torch.get_rng_state(),
        'cuda_rng_state_all': torch.cuda.get_rng_state_all(),
    }, out_dir / 'last_pretrain.pt')

    # Clean YOLO26n-P2 backbone+neck checkpoint (no Detect head, no decoder)
    bb_payload = {
        'kind': 'blueprint_only_ssl_yolo26n_p2_backbone_neck',
        'architecture': 'yolo26n-p2.yaml',
        'epoch': epoch,
        'best_val': best_val,
        'run_fingerprint': fingerprint,
        'detect_layer_index': model.detect_idx,
        'backbone_neck_state_dict': model.backbone_neck_state_dict(),
    }
    atomic_save(bb_payload, out_dir / 'last_yolo26n_p2_backbone.pt')
    if is_best:
        atomic_save(bb_payload, out_dir / 'best_yolo26n_p2_backbone.pt')


def build_musgd_optimizer(model: torch.nn.Module, *, lr: float, momentum: float = 0.937, weight_decay: float = 5e-4) -> torch.optim.Optimizer:
    """Build Ultralytics YOLO26 native MuSGD optimizer with proper Muon/SGD param groups."""
    from ultralytics.optim import MuSGD

    bn_types = tuple(v for k, v in torch.nn.__dict__.items() if 'Norm' in k and isinstance(v, type))
    g_bn, g_weight, g_bias = [], [], []
    seen: set[int] = set()
    for module in model.modules():
        for name, param in module.named_parameters(recurse=False):
            if not param.requires_grad or id(param) in seen:
                continue
            seen.add(id(param))
            if name == 'bias':
                g_bias.append(param)
            elif isinstance(module, bn_types) or param.ndim < 2:
                g_bn.append(param)
            else:
                g_weight.append(param)
    param_groups = [
        {'params': g_bn, 'weight_decay': 0.0, 'use_muon': False},
        {'params': g_weight, 'weight_decay': weight_decay, 'use_muon': True},
        {'params': g_bias, 'weight_decay': 0.0, 'use_muon': False},
    ]
    return MuSGD(param_groups, lr=lr, momentum=momentum, nesterov=True)


def configured_microbatch(args: argparse.Namespace) -> int:
    """Default to a small GPU microbatch when only --batch was provided."""
    size = getattr(args, "microbatch", None)
    return min(2, args.batch) if size is None else size


def resolve_batch_plan(effective_batch: int, microbatch: int) -> tuple[int, int]:
    """Return the actual GPU batch and accumulation count for an optimizer step.

    The 512px full-resolution SSL decoder is substantially heavier than the
    ordinary YOLO detector head. A logical batch of 14 can be built from seven GPU microbatches of two
    on a memory-limited card.
    """
    if (effective_batch < 1 or microbatch < 1
            or microbatch > effective_batch or effective_batch % microbatch):
        raise ValueError("microbatch must divide effective batch exactly")
    return microbatch, effective_batch // microbatch


def accumulated_training_update(
    model: torch.nn.Module,
    criterion: torch.nn.Module,
    optimizer: torch.optim.Optimizer,
    scaler: torch.amp.GradScaler,
    microbatches,
    *,
    device: torch.device,
    effective_batch: int,
    grad_clip: float = 2.0,
) -> dict[str, float]:
    """One real optimizer step over an effective batch of independent views.

    Keep FP16 gradients scaled across *all* microbatches, unscale and clip
    once, then update once; this follows PyTorch's AMP accumulation rules.
    The loss is weighted by microbatch sample count to preserve a proper
    effective-batch average even with unequal chunk sizes.
    """
    optimizer.zero_grad(set_to_none=True)
    total = 0
    running = {"loss": 0.0, "l1": 0.0, "edge": 0.0}
    for deg, clean, mask in microbatches:
        n = int(deg.shape[0])
        if n < 1 or total + n > effective_batch:
            raise ValueError("microbatches exceed configured effective batch")
        deg = deg.to(device, non_blocking=device.type == "cuda")
        clean = clean.to(device, non_blocking=device.type == "cuda")
        mask = mask.to(device, non_blocking=device.type == "cuda")
        context = (torch.amp.autocast("cuda", dtype=torch.float16)
                   if device.type == "cuda" else nullcontext())
        with context:
            pred = model(deg)
            loss, stats = criterion(pred, clean, mask)
        if not bool(torch.isfinite(loss.detach()).all()):
            raise FloatingPointError("nonfinite SSL pretraining loss")
        scaler.scale(loss * (n / effective_batch)).backward()
        for key in running:
            running[key] += float(stats[key]) * n
        total += n
    if total != effective_batch:
        raise ValueError(
            f"incomplete SSL effective batch: got {total}, expected {effective_batch}"
        )
    scaler.unscale_(optimizer)
    grad_norm = torch.nn.utils.clip_grad_norm_(model.parameters(), grad_clip)
    scaler.step(optimizer)
    scaler.update()
    result = {key: value / effective_batch for key, value in running.items()}
    result["grad_norm"] = float(grad_norm.detach().item())
    result["samples"] = float(total)
    return result


def validate_pretrain_settings(args: argparse.Namespace) -> None:
    """Fail before building manifests or allocating a model for invalid runs."""
    if args.batch < 1 or args.items_per_epoch < args.batch:
        raise ValueError("items-per-epoch must be at least batch size")
    microbatch = configured_microbatch(args)
    resolve_batch_plan(args.batch, microbatch)
    if args.items_per_epoch % args.batch:
        raise ValueError("items-per-epoch must be divisible by effective batch size")
    if args.epochs < 1 or args.val_items < 1 or args.workers < 0:
        raise ValueError("invalid epochs/validation items/workers")
    if args.imgsz < 128 or args.imgsz % 32:
        raise ValueError("imgsz must be a multiple of 32 and at least 128")
    if not 0 <= args.pool_val_fraction < 1:
        raise ValueError("pool-val-fraction must be in [0,1)")
    if not math.isfinite(args.lr) or args.lr <= 0:
        raise ValueError("learning rate must be finite and positive")
    if not 0 <= args.max_symbols <= 128:
        raise ValueError("max-symbols must be in [0,128]")
    if not 0 < args.symbol_min_px <= args.symbol_max_px:
        raise ValueError("invalid symbol size range")
    for key in ("symbol_augment_probability", "procedural_fraction",
                "clean_fraction", "labeled_focus_probability",
                "symbol_chaos_probability", "placement_chaos",
                "page_chaos_probability"):
        defaults = {"symbol_chaos_probability": .88,
                    "placement_chaos": .85, "page_chaos_probability": .70}
        prob = getattr(args, key, defaults.get(key))
        if not math.isfinite(prob) or not 0 <= prob <= 1:
            raise ValueError(f"{key} must be a probability in [0,1]")
    symbol_strength = getattr(args, "symbol_chaos_strength", 1.0)
    page_strength = getattr(args, "page_chaos_strength", 1.0)
    if not math.isfinite(symbol_strength) or not 0 <= symbol_strength <= 2:
        raise ValueError("symbol_chaos_strength must be in [0,2]")
    if not math.isfinite(page_strength) or not 0 <= page_strength <= 2:
        raise ValueError("page_chaos_strength must be in [0,2]")


def train(args: argparse.Namespace) -> None:
    validate_pretrain_settings(args)
    out_dir: Path = args.out.resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    train_manifest, val_manifest, audit = build_pretrain_manifests(
        args.data_root.resolve(),
        args.work_root.resolve(),
        fpcad_train_dir=args.fpcad_train_dir.resolve() if args.fpcad_train_dir else None,
        pool_val_fraction=args.pool_val_fraction,
        labeled_annotations=[
            Path(f) for pattern in args.labeled_annotations for f in glob.glob(pattern, recursive=True)
        ],
    )
    identity = run_fingerprint(train_manifest, val_manifest, args)
    prior_fingerprint_file = out_dir / "run_fingerprint.txt"
    existing_checkpoint = out_dir / "last_pretrain.pt"
    # Fail BEFORE replacing existing audit/manifests. A wrong --out path or
    # stale resume settings must not overwrite the last reproducible run's
    # dataset records even if the optimizer itself is protected.
    if args.run:
        if args.resume:
            if not existing_checkpoint.is_file():
                raise FileNotFoundError("Resume requested but last_pretrain.pt is missing")
            if not prior_fingerprint_file.is_file():
                raise ValueError("SSL resume fingerprint file is missing; existing run files untouched")
            if prior_fingerprint_file.read_text().strip() != identity:
                raise ValueError("SSL resume changes data or configuration; existing run files untouched")
        elif existing_checkpoint.exists() or prior_fingerprint_file.exists():
            raise SystemExit("Existing SSL run found; use --resume or a fresh --out directory")
        if args.init_weights and not args.init_weights.is_file():
            raise FileNotFoundError(f"Initial weights do not exist: {args.init_weights}")
        if args.neca_dir and not (args.neca_dir / "symbols.json").is_file():
            raise FileNotFoundError(f"NECA SVG bank missing symbols.json: {args.neca_dir}")
        if not torch.cuda.is_available():
            raise SystemExit("CUDA GPU unavailable; refusing CPU pretraining")

    (out_dir / 'pretrain_audit.json').write_text(json.dumps(audit, indent=2))
    (out_dir / 'train_manifest.json').write_text(json.dumps(train_manifest))
    (out_dir / 'val_manifest.json').write_text(json.dumps(val_manifest))
    print('=== PRETRAIN DATA AUDIT ===', flush=True)
    print(json.dumps(audit, indent=2), flush=True)

    if not args.run:
        print('PLAN ONLY. Pass --run to start GPU pretraining.', flush=True)
        return

    (out_dir / "run_fingerprint.txt").write_text(identity + "\n")
    print(f'Pretrain run fingerprint: {identity}', flush=True)

    torch.manual_seed(args.seed)
    torch.cuda.manual_seed_all(args.seed)
    device = torch.device('cuda:0')

    data_opts = {
        'neca_dir': args.neca_dir,
        'max_symbols': args.max_symbols,
        'symbol_min_px': args.symbol_min_px,
        'symbol_max_px': args.symbol_max_px,
        'symbol_augment_probability': args.symbol_augment_probability,
        'procedural_fraction': args.procedural_fraction,
        'clean_fraction': args.clean_fraction,
        'labeled_focus_probability': args.labeled_focus_probability,
        'symbol_chaos_probability': args.symbol_chaos_probability,
        'symbol_chaos_strength': args.symbol_chaos_strength,
        'placement_chaos': args.placement_chaos,
        'page_chaos_probability': args.page_chaos_probability,
        'page_chaos_strength': args.page_chaos_strength,
    }
    train_ds = BlueprintSSLDataset(
        train_manifest, size=args.imgsz, items_per_epoch=args.items_per_epoch,
        seed=args.seed, **data_opts,
    )
    val_ds = BlueprintSSLDataset(
        val_manifest, size=args.imgsz, items_per_epoch=args.val_items,
        seed=args.seed + 9999, deterministic_eval=True, **data_opts,
    ) if val_manifest['buckets'] else None

    microbatch, accumulate = resolve_batch_plan(
        args.batch, configured_microbatch(args)
    )
    train_loader = DataLoader(
        # Epoch-specific randomized source pairs are preplanned inside the
        # dataset. Shuffling here would destroy consecutive PDF cache hits.
        train_ds, batch_size=microbatch, shuffle=False, num_workers=args.workers,
        pin_memory=True, drop_last=True, worker_init_fn=_worker_init,
    )
    val_loader = DataLoader(
        val_ds, batch_size=microbatch, shuffle=False, num_workers=min(2, args.workers),
        pin_memory=True, drop_last=False, worker_init_fn=_worker_init,
    ) if val_ds is not None else None

    model = Yolo26P2SSLModel('yolo26n-p2.yaml', imgsz=args.imgsz).to(device)
    # Detect is never called during SSL reconstruction. Do not optimize its
    # random head weights; only backbone, neck and SSL decoder are trained.
    model.det_model.model[model.detect_idx].requires_grad_(False)
    if args.init_weights and args.init_weights.is_file() and not (args.resume and (out_dir / 'last_pretrain.pt').is_file()):
        init_ckpt = torch.load(args.init_weights, map_location=device, weights_only=False)
        if 'model' in init_ckpt:
            missing, unexpected = model.load_state_dict(init_ckpt['model'], strict=False)
            print(f'Warm-started full SSL model from {args.init_weights} (missing={len(missing)}, unexpected={len(unexpected)})', flush=True)
        else:
            sd = init_ckpt.get('backbone_neck_state_dict', init_ckpt)
            missing, unexpected = model.det_model.load_state_dict(sd, strict=False)
            print(f'Warm-started backbone+neck from {args.init_weights} (loaded={len(sd)}, missing={len(missing)}, unexpected={len(unexpected)})', flush=True)
    criterion = BlueprintSSLLoss().to(device)
    if args.optimizer == 'MuSGD':
        optimizer = build_musgd_optimizer(model, lr=args.lr)
    else:
        optimizer = torch.optim.SGD(model.parameters(), lr=args.lr, momentum=0.937, nesterov=True, weight_decay=5e-4)

    warmup_epochs = min(3, max(1, args.epochs // 15))

    def lr_lambda(ep: int) -> float:
        if ep < warmup_epochs:
            return float(ep + 1) / float(warmup_epochs)
        prog = (ep - warmup_epochs) / max(1, args.epochs - warmup_epochs)
        return 0.01 + 0.99 * 0.5 * (1.0 + math.cos(math.pi * prog))

    scheduler = torch.optim.lr_scheduler.LambdaLR(optimizer, lr_lambda)
    scaler = torch.amp.GradScaler('cuda')

    start_epoch = 0
    best_val = float('inf')
    history: list[dict[str, Any]] = []

    resume_ckpt = out_dir / 'last_pretrain.pt'
    if args.resume and resume_ckpt.is_file():
        ckpt = torch.load(resume_ckpt, map_location=device, weights_only=False)
        if ckpt.get('run_fingerprint') != identity:
            raise ValueError('SSL resume dataset/configuration fingerprint differs from checkpoint')
        model.load_state_dict(ckpt['model'])
        optimizer.load_state_dict(ckpt['optimizer'])
        scheduler.load_state_dict(ckpt['scheduler'])
        scaler.load_state_dict(ckpt['scaler'])
        start_epoch = int(ckpt['epoch']) + 1
        best_val = float(ckpt.get('best_val', float('inf')))
        history = list(ckpt.get('history', []))
        if 'torch_rng_state' in ckpt:
            torch.set_rng_state(ckpt['torch_rng_state'].cpu())
        if 'cuda_rng_state_all' in ckpt:
            torch.cuda.set_rng_state_all(ckpt['cuda_rng_state_all'])
        print(f'Resumed from epoch {start_epoch} (best_val={best_val:.5f})', flush=True)

    trainable_params = sum(p.numel() for p in model.parameters() if p.requires_grad)
    bb_params = sum(p.numel() for k, p in model.det_model.named_parameters()
                    if not k.startswith(f'model.{model.detect_idx}.'))
    frozen_detect_params = sum(
        p.numel() for p in model.det_model.model[model.detect_idx].parameters()
    )
    optimizer_param_ids = [id(p) for group in optimizer.param_groups for p in group["params"]]
    expected_param_ids = {id(p) for p in model.parameters() if p.requires_grad}
    if len(optimizer_param_ids) != len(expected_param_ids) or set(optimizer_param_ids) != expected_param_ids:
        raise ValueError("SSL optimizer must include every trainable model parameter exactly once")
    print(
        f'Model: YOLO26n-P2 SSL | backbone+neck={bb_params:,} params | '
        f'trainable_with_decoder={trainable_params:,} | '
        f'frozen_random_detect_head={frozen_detect_params:,} params',
        flush=True,
    )
    print(
        f'Training: epochs={args.epochs}, items/epoch={args.items_per_epoch}, '
        f'effective_batch={args.batch}, gpu_microbatch={microbatch}, '
        f'accumulation_steps={accumulate}, updates/epoch={len(train_loader) // accumulate}, '
        f'opt={args.optimizer}, lr={args.lr}, workers={args.workers}', flush=True
    )

    metrics_log = out_dir / 'metrics.jsonl'

    for epoch in range(start_epoch, args.epochs):
        t0 = time.time()
        train_ds.set_epoch(epoch)
        model.train()
        tr_loss = tr_l1 = tr_edge = 0.0
        steps = 0

        loader_iter = iter(train_loader)
        updates_per_epoch = len(train_loader) // accumulate
        for step in range(1, updates_per_epoch + 1):
            # The DataLoader shuffles microbatches; each optimizer update sees
            # exactly the requested logical batch, without needing 14 images
            # and their full-resolution feature maps on the GPU at once.
            stats = accumulated_training_update(
                model, criterion, optimizer, scaler,
                (next(loader_iter) for _ in range(accumulate)),
                device=device, effective_batch=args.batch,
            )
            tr_loss += stats["loss"]
            tr_l1 += stats["l1"]
            tr_edge += stats["edge"]
            steps += 1

            if step == 1 or step % 50 == 0 or step == updates_per_epoch:
                vram_mb = torch.cuda.max_memory_allocated(device) / (1024 * 1024)
                reserved_mb = torch.cuda.max_memory_reserved(device) / (1024 * 1024)
                print(
                    f'  ep {epoch+1:03d}/{args.epochs} update {step:04d}/{updates_per_epoch} '
                    f'loss={tr_loss/steps:.4f} l1={tr_l1/steps:.4f} edge={tr_edge/steps:.4f} '
                    f'grad_norm={stats["grad_norm"]:.3f} '
                    f'lr={optimizer.param_groups[0]["lr"]:.2e} '
                    f'vram_allocated={vram_mb:.0f}MB vram_reserved={reserved_mb:.0f}MB',
                    flush=True,
                )

        scheduler.step()

        # Read-only validation monitor (no gradients, no weight updates)
        val_loss = val_l1 = val_edge = float('nan')
        if val_loader is not None:
            model.eval()
            vl = vl1 = ve = 0.0
            vitems = 0
            with torch.inference_mode():
                for deg, clean, mask in val_loader:
                    deg = deg.to(device, non_blocking=True)
                    clean = clean.to(device, non_blocking=True)
                    mask = mask.to(device, non_blocking=True)
                    with torch.amp.autocast('cuda', dtype=torch.float16):
                        pred = model(deg)
                        _, vstats = criterion(pred, clean, mask)
                    count = int(deg.shape[0])
                    vl += vstats['loss'] * count
                    vl1 += vstats['l1'] * count
                    ve += vstats['edge'] * count
                    vitems += count
            if vitems:
                val_loss, val_l1, val_edge = vl / vitems, vl1 / vitems, ve / vitems

        dt = time.time() - t0
        monitor = val_loss if math.isfinite(val_loss) else (tr_loss / max(1, steps))
        is_best = monitor < best_val
        if is_best:
            best_val = monitor

        row = {
            'epoch': epoch + 1,
            'train_loss': round(tr_loss / max(1, steps), 5),
            'train_l1': round(tr_l1 / max(1, steps), 5),
            'train_edge': round(tr_edge / max(1, steps), 5),
            'val_loss': round(val_loss, 5) if math.isfinite(val_loss) else None,
            'val_l1': round(val_l1, 5) if math.isfinite(val_l1) else None,
            'val_edge': round(val_edge, 5) if math.isfinite(val_edge) else None,
            'lr': optimizer.param_groups[0]['lr'],
            'seconds': round(dt, 1),
            'is_best': is_best,
        }
        history.append(row)
        with metrics_log.open('a') as f:
            f.write(json.dumps(row) + '\n')

        print(
            f'EPOCH {epoch+1:03d}/{args.epochs} ({dt:.1f}s) | '
            f'train={row["train_loss"]:.4f} (l1={row["train_l1"]:.4f}, edge={row["train_edge"]:.4f}) | '
            f'val={row["val_loss"] if row["val_loss"] is not None else "n/a"} '
            f'{"*BEST*" if is_best else ""}',
            flush=True,
        )

        save_checkpoints(out_dir, model, optimizer, scheduler, scaler, epoch, best_val, history, is_best, identity)
        if (epoch + 1) % 10 == 0:
            torch.save(
                {'epoch': epoch + 1, 'backbone_neck_state_dict': model.backbone_neck_state_dict()},
                out_dir / f'epoch_{epoch+1:03d}_backbone.pt',
            )


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--data-root', type=Path, required=True)
    p.add_argument('--work-root', type=Path, required=True)
    p.add_argument('--fpcad-train-dir', type=Path, default=None,
                   help='Optional directory of extracted FloorPlanCAD train-only clean PNGs')
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--imgsz', type=int, default=512)
    p.add_argument('--batch', type=int, default=14,
                   help='Effective examples per optimizer step (default 16)')
    p.add_argument('--microbatch', type=int, default=None,
                   help='Physical GPU images per forward/backward; defaults to min(2, batch)')
    p.add_argument('--epochs', type=int, default=60)
    p.add_argument('--items-per-epoch', type=int, default=8190)
    p.add_argument('--val-items', type=int, default=512)
    p.add_argument('--optimizer', choices=('MuSGD', 'SGD'), default='MuSGD')
    p.add_argument('--lr', type=float, default=5e-3)
    p.add_argument('--workers', type=int, default=2)
    p.add_argument('--pool-val-fraction', type=float, default=0.10)
    p.add_argument('--seed', type=int, default=2026)
    p.add_argument('--labeled-annotations', action='append', default=[], metavar='GLOB',
                   help='Verified positive device JSONs used for device-centered SSL crops')
    p.add_argument('--neca-dir', type=Path, default=None,
                   help='NECA_100_Vector_Library containing symbols.json and all vector SVGs')
    p.add_argument('--max-symbols', type=int, default=16,
                   help='Random number of synthetic symbols per crop: 0..N (inclusive)')
    p.add_argument('--symbol-min-px', type=float, default=12.0)
    p.add_argument('--symbol-max-px', type=float, default=80.0)
    p.add_argument('--symbol-augment-probability', type=float, default=1.00)
    p.add_argument('--procedural-fraction', type=float, default=0.25)
    p.add_argument('--clean-fraction', type=float, default=0.20)
    p.add_argument('--labeled-focus-probability', type=float, default=0.65)
    p.add_argument('--symbol-chaos-probability', type=float, default=0.88,
                   help='Probability each inserted symbol receives independent geometry/print corruption')
    p.add_argument('--symbol-chaos-strength', type=float, default=1.0,
                   help='Nonlinear warp/stroke severity multiplier 0..2')
    p.add_argument('--placement-chaos', type=float, default=0.85,
                   help='Mix uniform, edge, corner, cluster, and band placements 0..1')
    p.add_argument('--page-chaos-probability', type=float, default=0.70,
                   help='Probability of aligned whole-drawing geometry deformation 0..1')
    p.add_argument('--page-chaos-strength', type=float, default=1.0,
                   help='Amplitude of perspective, nonuniform scaling, folds, page curl and waves 0..2')
    p.add_argument('--init-weights', type=Path, default=None,
                   help='Optional prior SSL checkpoint (last_pretrain.pt or best_yolo26n_p2_backbone.pt) to warm-start from')
    p.add_argument('--resume', action='store_true')
    p.add_argument('--run', action='store_true', help='Explicitly start GPU pretraining')
    train(p.parse_args())


if __name__ == '__main__':
    main()
