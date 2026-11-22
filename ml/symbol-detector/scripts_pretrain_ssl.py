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
import json
import math
import time
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


def build_pretrain_manifests(
    data_root: Path,
    work_root: Path,
    *,
    fpcad_train_dir: Path | None = None,
    pool_val_fraction: float = 0.10,
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

    train_buckets: dict[str, dict[str, Any]] = {}
    val_buckets: dict[str, dict[str, Any]] = {}
    audit: dict[str, Any] = {
        'excluded_counts': defaultdict(int),
        'included_counts': defaultdict(int),
        'val_monitor_counts': defaultdict(int),
        'locked_eval_groups_count': len(locked_eval_groups),
    }

    # 1. Real blueprints from curated page pool (background + reserve + label_queue)
    for role, bucket_name, weight in (
        ('background', 'pool_background', 0.28),
        ('reserve', 'pool_reserve', 0.10),
        ('label_queue', 'pool_electrical_unlabeled', 0.12),
    ):
        rdir = pool_dir / role
        if not rdir.is_dir():
            continue
        tr_groups: dict[str, list[str]] = defaultdict(list)
        va_groups: dict[str, list[str]] = defaultdict(list)
        for jpath in sorted(rdir.glob('*.json')):
            rel = f'{role}/{jpath.name}'
            if rel in excluded_rel:
                audit['excluded_counts'][f'{role}_junk'] += 1
                continue
            try:
                doc = json.loads(jpath.read_text())
            except Exception:
                continue
            img_path = (jpath.parent / doc['image_path']).resolve()
            if not img_path.is_file():
                continue
            gid = str(doc.get('project_family_id') or jpath.stem)
            if gid in locked_eval_groups or group_is_holdout(gid, val_fraction=pool_val_fraction):
                va_groups[gid].append(str(img_path))
                audit['val_monitor_counts'][bucket_name] += 1
                audit['excluded_counts'][f'{role}_holdout'] += 1
            else:
                tr_groups[gid].append(str(img_path))
                audit['included_counts'][bucket_name] += 1
        if tr_groups:
            train_buckets[bucket_name] = {'weight': weight, 'groups': dict(tr_groups)}
        if va_groups:
            val_buckets[bucket_name] = {'weight': weight, 'groups': dict(va_groups)}

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
            train_buckets['cubicasa5k_train'] = {'weight': 0.22, 'groups': cc_tr_groups}
        if cc_va_groups:
            val_buckets['cubicasa5k_val_readonly'] = {'weight': 0.25, 'groups': cc_va_groups}

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
            train_buckets['floorplancad_train'] = {'weight': 0.20, 'groups': dict(fp_groups)}

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
            train_buckets['rendered_layouts_train'] = {'weight': 0.08, 'groups': bl_groups}

    audit['excluded_counts'] = dict(audit['excluded_counts'])
    audit['included_counts'] = dict(audit['included_counts'])
    audit['val_monitor_counts'] = dict(audit['val_monitor_counts'])
    audit['total_train_images'] = sum(audit['included_counts'].values())
    audit['total_train_groups'] = sum(len(b['groups']) for b in train_buckets.values())
    audit['total_val_monitor_images'] = sum(audit['val_monitor_counts'].values())

    train_manifest = {'kind': 'blueprint_ssl_train', 'buckets': train_buckets}
    val_manifest = {'kind': 'blueprint_ssl_val_readonly', 'buckets': val_buckets}
    return train_manifest, val_manifest, audit


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
) -> None:
    # Full resumable pretraining state
    torch.save({
        'epoch': epoch,
        'best_val': best_val,
        'model': model.state_dict(),
        'optimizer': optimizer.state_dict(),
        'scheduler': scheduler.state_dict(),
        'scaler': scaler.state_dict(),
        'history': history,
    }, out_dir / 'last_pretrain.pt')

    # Clean YOLO26n-P2 backbone+neck checkpoint (no Detect head, no decoder)
    bb_payload = {
        'kind': 'blueprint_only_ssl_yolo26n_p2_backbone_neck',
        'architecture': 'yolo26n-p2.yaml',
        'epoch': epoch,
        'best_val': best_val,
        'detect_layer_index': model.detect_idx,
        'backbone_neck_state_dict': model.backbone_neck_state_dict(),
    }
    torch.save(bb_payload, out_dir / 'last_yolo26n_p2_backbone.pt')
    if is_best:
        torch.save(bb_payload, out_dir / 'best_yolo26n_p2_backbone.pt')


def train(args: argparse.Namespace) -> None:
    out_dir: Path = args.out.resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    train_manifest, val_manifest, audit = build_pretrain_manifests(
        args.data_root.resolve(),
        args.work_root.resolve(),
        fpcad_train_dir=args.fpcad_train_dir.resolve() if args.fpcad_train_dir else None,
        pool_val_fraction=args.pool_val_fraction,
    )
    (out_dir / 'pretrain_audit.json').write_text(json.dumps(audit, indent=2))
    (out_dir / 'train_manifest.json').write_text(json.dumps(train_manifest))
    (out_dir / 'val_manifest.json').write_text(json.dumps(val_manifest))
    print('=== PRETRAIN DATA AUDIT ===', flush=True)
    print(json.dumps(audit, indent=2), flush=True)

    if not args.run:
        print('PLAN ONLY. Pass --run to start GPU pretraining.', flush=True)
        return

    if not torch.cuda.is_available():
        raise SystemExit('CUDA GPU unavailable; refusing CPU pretraining')

    device = torch.device('cuda:0')

    train_ds = BlueprintSSLDataset(
        train_manifest, size=args.imgsz, items_per_epoch=args.items_per_epoch, seed=args.seed
    )
    val_ds = BlueprintSSLDataset(
        val_manifest, size=args.imgsz, items_per_epoch=args.val_items,
        seed=args.seed + 9999, deterministic_eval=True,
    ) if val_manifest['buckets'] else None

    train_loader = DataLoader(
        train_ds, batch_size=args.batch, shuffle=True, num_workers=args.workers,
        pin_memory=True, drop_last=True, worker_init_fn=_worker_init,
    )
    val_loader = DataLoader(
        val_ds, batch_size=args.batch, shuffle=False, num_workers=min(2, args.workers),
        pin_memory=True, drop_last=False, worker_init_fn=_worker_init,
    ) if val_ds is not None else None

    model = Yolo26P2SSLModel('yolo26n-p2.yaml', imgsz=args.imgsz).to(device)
    criterion = BlueprintSSLLoss().to(device)
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-2)

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
        model.load_state_dict(ckpt['model'])
        optimizer.load_state_dict(ckpt['optimizer'])
        scheduler.load_state_dict(ckpt['scheduler'])
        scaler.load_state_dict(ckpt['scaler'])
        start_epoch = int(ckpt['epoch']) + 1
        best_val = float(ckpt.get('best_val', float('inf')))
        history = list(ckpt.get('history', []))
        print(f'Resumed from epoch {start_epoch} (best_val={best_val:.5f})', flush=True)

    total_params = sum(p.numel() for p in model.parameters())
    bb_params = sum(p.numel() for k, p in model.det_model.named_parameters() if not k.startswith(f'model.{model.detect_idx}.'))
    print(f'Model: YOLO26n-P2 SSL | backbone+neck={bb_params:,} params | total_with_decoder={total_params:,} params', flush=True)
    print(f'Training: epochs={args.epochs}, items/epoch={args.items_per_epoch}, batch={args.batch}, lr={args.lr}, workers={args.workers}', flush=True)

    metrics_log = out_dir / 'metrics.jsonl'

    for epoch in range(start_epoch, args.epochs):
        t0 = time.time()
        train_ds.set_epoch(epoch)
        model.train()
        tr_loss = tr_l1 = tr_edge = 0.0
        steps = 0

        for step, (deg, clean, mask) in enumerate(train_loader, start=1):
            deg = deg.to(device, non_blocking=True)
            clean = clean.to(device, non_blocking=True)
            mask = mask.to(device, non_blocking=True)

            optimizer.zero_grad(set_to_none=True)
            with torch.amp.autocast('cuda', dtype=torch.float16):
                pred = model(deg)
                loss, stats = criterion(pred, clean, mask)

            scaler.scale(loss).backward()
            scaler.unscale_(optimizer)
            torch.nn.utils.clip_grad_norm_(model.parameters(), 2.0)
            scaler.step(optimizer)
            scaler.update()

            tr_loss += stats['loss']
            tr_l1 += stats['l1']
            tr_edge += stats['edge']
            steps += 1

            if step == 1 or step % 50 == 0 or step == len(train_loader):
                vram_mb = torch.cuda.max_memory_allocated(device) / (1024 * 1024)
                print(
                    f'  ep {epoch+1:03d}/{args.epochs} step {step:04d}/{len(train_loader)} '
                    f'loss={tr_loss/steps:.4f} l1={tr_l1/steps:.4f} edge={tr_edge/steps:.4f} '
                    f'lr={optimizer.param_groups[0]["lr"]:.2e} vram={vram_mb:.0f}MB',
                    flush=True,
                )

        scheduler.step()

        # Read-only validation monitor (no gradients, no weight updates)
        val_loss = val_l1 = val_edge = float('nan')
        if val_loader is not None:
            model.eval()
            vl = vl1 = ve = 0.0
            vsteps = 0
            with torch.no_grad():
                for deg, clean, mask in val_loader:
                    deg = deg.to(device, non_blocking=True)
                    clean = clean.to(device, non_blocking=True)
                    mask = mask.to(device, non_blocking=True)
                    with torch.amp.autocast('cuda', dtype=torch.float16):
                        pred = model(deg)
                        _, vstats = criterion(pred, clean, mask)
                    vl += vstats['loss']
                    vl1 += vstats['l1']
                    ve += vstats['edge']
                    vsteps += 1
            if vsteps:
                val_loss, val_l1, val_edge = vl / vsteps, vl1 / vsteps, ve / vsteps

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

        save_checkpoints(out_dir, model, optimizer, scheduler, scaler, epoch, best_val, history, is_best)
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
    p.add_argument('--batch', type=int, default=16)
    p.add_argument('--epochs', type=int, default=60)
    p.add_argument('--items-per-epoch', type=int, default=8192)
    p.add_argument('--val-items', type=int, default=512)
    p.add_argument('--lr', type=float, default=1e-3)
    p.add_argument('--workers', type=int, default=2)
    p.add_argument('--pool-val-fraction', type=float, default=0.10)
    p.add_argument('--seed', type=int, default=2026)
    p.add_argument('--resume', action='store_true')
    p.add_argument('--run', action='store_true', help='Explicitly start GPU pretraining')
    train(p.parse_args())


if __name__ == '__main__':
    main()
