"""Explicit, blueprint-only Ultralytics YOLO26n-P2 training launcher.

No general-purpose pretrained weights or dataset are used or downloaded.
With --weights, initialize from our own SSL backbone and verify every
transferred tensor in the actual DetectionTrainer model.
Default is PLAN ONLY. Training ONLY starts with --run, on user's own machine.

  python scripts_train_ultralytics.py prepared_yolo/data.yaml
  python scripts_train_ultralytics.py prepared_yolo/data.yaml --run
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
import shutil
from pathlib import Path


def plan(data: Path, *, batch: int = 2, epochs: int = 300,
         optimizer: str = 'MuSGD', imgsz: int = 512, workers: int = 2,
         lr0: float | None = None, fliplr: float = 0.0, flipud: float = 0.0,
         scale: float = 0.0, translate: float = 0.0,
         loss_cfg: dict[str, float] | None = None,
         real_eval_every: int = 0,
         real_eval_manifest: Path | None = None,
         real_eval_blank_pages: int = 24,
         real_eval_complete_pages: int = 24,
         real_patience: int = 8,
         init_weights: Path | None = None) -> dict:
    if not data.is_file():
        raise FileNotFoundError(f'Prepared blueprint dataset YAML not found: {data}')
    if batch < 1 or epochs < 1 or workers < 0 or imgsz < 128 or imgsz % 32:
        raise ValueError('invalid training dimensions/settings')
    if optimizer not in ('auto', 'AdamW', 'MuSGD', 'SGD'):
        raise ValueError('invalid optimizer')
    if real_patience < 0:
        raise ValueError('real_patience must be >= 0 (0 disables real-page early stopping)')
    from blueprint_symbols.label_schema import MIRROR_ALLOWED
    if (fliplr or flipud) and not MIRROR_ALLOWED:
        raise ValueError('flips mirror text and arrows (S3, GFI, WP, exit arrows) that never occur '
                         'in drawings; the catalog augmentation policy forbids them (mirror: false). '
                         'Rotations are applied per device class by the tile export.')
    cfg = {
        'architecture': 'yolo26n-p2.yaml',
        'initialization': 'random',
        'pretrained': False,
        'data': str(data.resolve()),
        'epochs': epochs,
        'imgsz': imgsz,
        'batch': batch,
        'optimizer': optimizer,
        'amp': True,
        'device': 0,
        'workers': workers,
        'cache': False,
        'mosaic': 0.0, 'mixup': 0.0, 'close_mosaic': 0,
        'hsv_h': 0.0, 'hsv_s': 0.0, 'hsv_v': 0.0,
        'fliplr': fliplr, 'flipud': flipud,
        'degrees': 0.0, 'translate': translate,
        'scale': scale, 'shear': 0.0, 'perspective': 0.0,
        'patience': 40, 'cos_lr': True, 'seed': 2026,
        'notes': [
            'Only annotations converted from blueprint/CAD sources are accepted as input.',
            ('Random-initialized detector; no COCO, Objects365, or general YOLO weights.'
             if init_weights is None else
             'Initialized from our own blueprint-only checkpoint (see init_checkpoint); '
             'no COCO, Objects365, or general YOLO weights.'),
            '6 GiB GPU is a target: profile VRAM and reduce batch if needed.',
            'Prepared tiles already contain explicit symbol size and damage variations.',
            'The selection set chooses the checkpoint; the locked test set is only reported.',
        ],
    }
    if init_weights is not None:
        cfg['initialization'] = 'blueprint_checkpoint_verified_transfer'
        cfg['init_checkpoint'] = checkpoint_identity(init_weights)
        cfg['ssl_checkpoint'] = cfg['init_checkpoint']['path']
    if lr0 is not None:
        cfg['lr0'] = lr0
    if loss_cfg is not None:
        cfg['phase2_loss_cfg'] = {k: float(v) for k, v in loss_cfg.items()}
    if real_eval_every > 0:
        active = real_eval_manifest is not None and real_eval_manifest.is_file()
        cfg['real_eval'] = {
            'every_epochs': int(real_eval_every),
            'manifest': str(real_eval_manifest.resolve()) if real_eval_manifest else None,
            'blank_pages': int(real_eval_blank_pages),
            'complete_pages': int(real_eval_complete_pages),
            'active': active,
        }
        if active:
            # The real-page score (not tile mAP on the small synthetic val)
            # drives early stopping and best.pt; Ultralytics' own patience is
            # off (0 = disabled in EarlyStopping).
            cfg['patience'] = 0
            cfg['real_eval']['patience_evals'] = int(real_patience)
            cfg['real_eval']['selection'] = (
                'weights/best.pt = epoch with the best real_score; '
                'weights/best_tile_map.pt = Ultralytics tile-mAP best (reference only)')
    return cfg


def checkpoint_identity(path: Path) -> dict:
    """Resolved path, size and sha256 of an init/backbone checkpoint (no torch needed)."""
    resolved = Path(path).expanduser().resolve()
    if not resolved.is_file():
        raise FileNotFoundError(f'init checkpoint not found: {resolved}')
    digest = hashlib.sha256()
    with resolved.open('rb') as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b''):
            digest.update(chunk)
    return {'path': str(resolved), 'bytes': resolved.stat().st_size, 'sha256': digest.hexdigest()}


def _is_main_rank() -> bool:
    return int(os.environ.get('RANK', -1)) in (-1, 0)


def build_real_selection_callbacks(*, patience: int) -> dict:
    """Ultralytics callbacks: real-page-score patience and best.pt selection.

    Reads ``trainer.real_eval_metrics`` (set by the real-page eval callback,
    which must be registered first on ``on_fit_epoch_end``). Patience counts
    real evaluations without a strictly better ``real_score``; when it runs
    out ``trainer.stop`` is set and the epoch loop ends. ``patience=0`` only
    disables the stop, best.pt still follows the real score.

    Once a real score exists, Ultralytics' tile-mAP best is written to
    weights/best_tile_map.pt instead of best.pt, and best.pt is a copy of the
    last.pt written in the epoch whose real_score improved (the same EMA
    weights the eval scored). Until the first real eval, Ultralytics writes
    best.pt as usual. Torch-free: only trainer attributes and files are used.
    """
    if patience < 0:
        raise ValueError('patience must be >= 0')
    state = {
        'best_score': None, 'best_epoch': None, 'evals': 0, 'since_best': 0,
        'last_metrics': None, 'done': False, 'history': [],
    }

    def on_train_start(trainer) -> None:
        original_save = trainer.save_model

        def save_model(*args, **kwargs):
            if state['best_score'] is None:
                return original_save(*args, **kwargs)
            real_best = trainer.best
            trainer.best = Path(real_best).with_name('best_tile_map.pt')
            try:
                return original_save(*args, **kwargs)
            finally:
                trainer.best = real_best

        trainer.save_model = save_model

    def _write_state(trainer) -> None:
        save_dir = getattr(trainer, 'save_dir', None)
        if save_dir is None:
            return
        out = Path(save_dir) / 'real_selection.json'
        payload = {k: state[k] for k in ('best_score', 'best_epoch', 'evals', 'since_best', 'history')}
        payload['patience_evals'] = patience
        payload['stopped_by_real_patience'] = state.get('stopped', False)
        tmp = out.with_name(f'.{out.name}.{os.getpid()}.tmp')
        tmp.write_text(json.dumps(payload, indent=2) + '\n', encoding='utf-8')
        os.replace(tmp, out)

    def on_fit_epoch_end(trainer) -> None:
        if state['done']:
            return  # e.g. Ultralytics final_eval re-running epoch-end callbacks
        metrics = getattr(trainer, 'real_eval_metrics', None)
        fresh = (isinstance(metrics, dict) and metrics.get('real_score') is not None
                 and metrics is not state['last_metrics'])
        if fresh:
            state['last_metrics'] = metrics
            score = float(metrics['real_score'])
            epoch = int(metrics.get('epoch', int(getattr(trainer, 'epoch', 0)) + 1))
            state['evals'] += 1
            improved = state['best_score'] is None or score > state['best_score']
            if improved:
                state['best_score'], state['best_epoch'], state['since_best'] = score, epoch, 0
                # An ineligible checkpoint (finds nothing / below min recall)
                # never becomes best.pt, like best_real.pt.
                if _is_main_rank() and metrics.get('eligible', True) is not False:
                    last, best = Path(trainer.last), Path(trainer.best)
                    if last.is_file():
                        tmp = best.with_name(f'.{best.name}.{os.getpid()}.tmp')
                        shutil.copyfile(last, tmp)
                        os.replace(tmp, best)
                    else:
                        print(f'[RealSelection] WARNING: {last} missing; best.pt not updated', flush=True)
            else:
                state['since_best'] += 1
            state['history'].append({'epoch': epoch, 'real_score': score, 'improved': improved})
            stop = patience > 0 and state['since_best'] >= patience
            if stop:
                state['stopped'] = True
                trainer.stop = True
            print(f"[RealSelection ep {epoch}] real_score={score:.4f} best={state['best_score']:.4f}"
                  f"@ep{state['best_epoch']} no-improve evals={state['since_best']}/"
                  f"{patience if patience else 'off'}{' -> STOP' if stop else ''}", flush=True)
            if _is_main_rank():
                _write_state(trainer)
        if getattr(trainer, 'stop', False):
            state['done'] = True

    return {'on_train_start': on_train_start, 'on_fit_epoch_end': on_fit_epoch_end, 'state': state}


def main():
    from blueprint_symbols.partial_supervision import DEFAULT_PHASE2_LOSS_CFG, read_supervision

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('data_yaml', type=Path)
    parser.add_argument('--batch', type=int, default=2)
    parser.add_argument('--epochs', type=int, default=300)
    parser.add_argument('--imgsz', type=int, default=512)
    parser.add_argument('--workers', type=int, default=2)
    parser.add_argument('--optimizer', choices=('auto', 'AdamW', 'MuSGD', 'SGD'), default='MuSGD')
    parser.add_argument('--lr0', type=float, default=None, help='Initial learning rate (important for AdamW, e.g. 0.0008)')
    parser.add_argument('--fliplr', type=float, default=0.0)
    parser.add_argument('--flipud', type=float, default=0.0)
    parser.add_argument('--scale', type=float, default=0.0)
    parser.add_argument('--translate', type=float, default=0.0)
    parser.add_argument('--project', type=Path, help='Ultralytics output folder (default: ./runs/detect)')
    parser.add_argument('--name', help='run name inside --project')
    parser.add_argument('--exist-ok', action='store_true', help='reuse an existing run folder')
    parser.add_argument('--weights', type=Path,
                        help='Optional blueprint-only SSL backbone+neck or Phase-2 detector checkpoint (best.pt)')
    parser.add_argument('--real-eval-every', type=int, default=0,
                        help='Run deterministic full-page real blank/complete eval every N epochs (0 disables)')
    parser.add_argument('--real-eval-manifest', type=Path, default=None,
                        help='Optional source_manifest.json path for periodic real-page eval')
    parser.add_argument('--real-eval-blank-pages', type=int, default=24)
    parser.add_argument('--real-eval-complete-pages', type=int, default=24)
    parser.add_argument('--real-patience', type=int, default=8,
                        help='Stop after N real-page evaluations without a better real_score '
                             '(counted in evaluations, not epochs; 0 disables). Replaces '
                             "Ultralytics' tile-mAP patience whenever real eval is active.")
    parser.add_argument('--real-blank-neg-weight', type=float, default=DEFAULT_PHASE2_LOSS_CFG['real_blank_neg_weight'])
    parser.add_argument('--real-complete-far-neg-weight', type=float, default=DEFAULT_PHASE2_LOSS_CFG['real_complete_far_neg_weight'])
    parser.add_argument('--real-complete-near-neg-weight', type=float, default=DEFAULT_PHASE2_LOSS_CFG['real_complete_near_neg_weight'])
    parser.add_argument('--real-complete-wrong-cls-weight', type=float, default=DEFAULT_PHASE2_LOSS_CFG['real_complete_wrong_cls_weight'])
    parser.add_argument('--real-complete-pos-weight', type=float, default=DEFAULT_PHASE2_LOSS_CFG['real_complete_pos_weight'])
    parser.add_argument('--synthetic-complete-neg-weight', type=float, default=DEFAULT_PHASE2_LOSS_CFG['synthetic_complete_neg_weight'])
    parser.add_argument('--synthetic-complete-wrong-cls-weight', type=float, default=DEFAULT_PHASE2_LOSS_CFG['synthetic_complete_wrong_cls_weight'])
    parser.add_argument('--partial-wrong-cls-weight', type=float, default=DEFAULT_PHASE2_LOSS_CFG['partial_wrong_cls_weight'])
    parser.add_argument('--fp-boost', type=float, default=DEFAULT_PHASE2_LOSS_CFG['fp_boost'])
    parser.add_argument('--hard-neg-mining', type=float, default=DEFAULT_PHASE2_LOSS_CFG['hard_neg_mining'],
                        help='1 = extra blank/complete negative weight only on top-k hard anchors; '
                             '0 = old uniform weighting on every anchor')
    parser.add_argument('--hard-neg-topk', type=float, default=DEFAULT_PHASE2_LOSS_CFG['hard_neg_topk'],
                        help='hard anchors per tile per class that receive the extra negative weight')
    parser.add_argument('--hard-neg-base-weight', type=float,
                        default=DEFAULT_PHASE2_LOSS_CFG['hard_neg_base_weight'],
                        help='weight on all other verified far-background entries (1 = ordinary YOLO)')
    parser.add_argument('--hard-neg-tile-cap', type=float, default=DEFAULT_PHASE2_LOSS_CFG['hard_neg_tile_cap'],
                        help='per-tile cap on mean hard-negative modulation (<=0 disables)')
    parser.add_argument('--run', action='store_true', help='Explicitly start training on your machine')
    args = parser.parse_args()
    loss_cfg = {
        'real_blank_neg_weight': args.real_blank_neg_weight,
        'real_complete_far_neg_weight': args.real_complete_far_neg_weight,
        'real_complete_near_neg_weight': args.real_complete_near_neg_weight,
        'real_complete_wrong_cls_weight': args.real_complete_wrong_cls_weight,
        'real_complete_pos_weight': args.real_complete_pos_weight,
        'synthetic_complete_neg_weight': args.synthetic_complete_neg_weight,
        'synthetic_complete_wrong_cls_weight': args.synthetic_complete_wrong_cls_weight,
        'partial_wrong_cls_weight': args.partial_wrong_cls_weight,
        'fp_boost': args.fp_boost,
        'hard_neg_mining': args.hard_neg_mining,
        'hard_neg_topk': args.hard_neg_topk,
        'hard_neg_base_weight': args.hard_neg_base_weight,
        'hard_neg_tile_cap': args.hard_neg_tile_cap,
    }
    eval_manifest = args.real_eval_manifest
    if eval_manifest is None and args.real_eval_every > 0:
        candidate = args.data_yaml.resolve().parent.parent / 'source_manifest.json'
        if candidate.is_file():
            eval_manifest = candidate
    config = plan(args.data_yaml, batch=args.batch, epochs=args.epochs,
                  optimizer=args.optimizer, imgsz=args.imgsz, workers=args.workers,
                  lr0=args.lr0, fliplr=args.fliplr, flipud=args.flipud,
                  scale=args.scale, translate=args.translate,
                  loss_cfg=loss_cfg,
                  real_eval_every=args.real_eval_every,
                  real_eval_manifest=eval_manifest,
                  real_eval_blank_pages=args.real_eval_blank_pages,
                  real_eval_complete_pages=args.real_eval_complete_pages,
                  real_patience=args.real_patience,
                  init_weights=args.weights)
    # Standard YOLO loss incorrectly calls missing real-device labels
    # background. Activate the actual per-class loss when sidecar says partial.
    supervision = read_supervision(args.data_yaml)
    if supervision is not None:
        config['supervision'] = ('masked_partial_real_labels'
                                 if supervision['has_partial'] else 'complete_labels')
        config['partial_training_images'] = sum(
            len(v) < len(supervision['class_ids'])
            for v in supervision['samples'].values())
    print(json.dumps(config, indent=2))
    if not args.run:
        print('PLAN ONLY. No training or model downloads performed.')
        return
    try:
        import torch
    except ImportError:
        parser.error('PyTorch with CUDA is required to train')
    if not torch.cuda.is_available():
        parser.error('NVIDIA CUDA GPU unavailable; refusing CPU training')
    try:
        from ultralytics import YOLO
    except ImportError:
        parser.error('Install Ultralytics on your own training machine')
    model = YOLO(config['architecture'])
    ssl_trainer = None
    if supervision is not None and supervision['has_partial']:
        from blueprint_symbols.partial_supervision import build_masked_detection_trainer
        ssl_trainer = build_masked_detection_trainer(args.data_yaml, args.weights, loss_cfg=loss_cfg)
    elif args.weights:
        # Model.train() constructs a NEW DetectionModel. Loading a state dict
        # into the YOLO(yaml) object here would silently discard those weights.
        # Instead inject the verified SSL tensors into the actual training model
        # during DetectionTrainer.get_model().
        from blueprint_symbols.ssl_transfer import build_ssl_detection_trainer
        ssl_trainer = build_ssl_detection_trainer(args.weights)
    real_eval_active = bool(config.get('real_eval', {}).get('active'))
    if args.real_eval_every > 0 and not real_eval_active:
        print('WARNING: --real-eval-every set but no real-eval manifest found; '
              "real-page eval, real patience and real best.pt selection are OFF, "
              "Ultralytics' tile-mAP patience/best.pt apply.", flush=True)
    if real_eval_active:
        from blueprint_symbols.real_page_eval import build_real_eval_callback
        # Order matters: the eval callback sets trainer.real_eval_metrics,
        # then the selection callback reads it in the same epoch.
        model.add_callback(
            'on_fit_epoch_end',
            build_real_eval_callback(
                manifest_path=eval_manifest,
                every_epochs=args.real_eval_every,
                max_blank_pages=args.real_eval_blank_pages,
                max_complete_pages=args.real_eval_complete_pages,
                imgsz=args.imgsz,
            ),
        )
        selection = build_real_selection_callbacks(patience=args.real_patience)
        model.add_callback('on_train_start', selection['on_train_start'])
        model.add_callback('on_fit_epoch_end', selection['on_fit_epoch_end'])

    def write_run_config(trainer) -> None:
        if _is_main_rank():
            out = Path(trainer.save_dir) / 'phase2_run_config.json'
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_text(json.dumps(config, indent=2) + '\n', encoding='utf-8')

    model.add_callback('on_pretrain_routine_end', write_run_config)
    strides = [int(v) for v in model.model.stride]
    if 4 not in strides:
        parser.error(f'P2 stride-4 head is missing; found strides: {strides}')
    keywords = {k: v for k, v in config.items() if k in (
        'epochs', 'imgsz', 'batch', 'optimizer', 'lr0', 'amp', 'device', 'workers',
        'cache', 'mosaic', 'mixup', 'close_mosaic', 'hsv_h', 'hsv_s', 'hsv_v',
        'fliplr', 'flipud', 'degrees', 'translate', 'scale', 'shear',
        'perspective', 'patience', 'cos_lr', 'seed', 'pretrained')}
    if args.project:
        keywords['project'] = str(args.project.resolve())
    if args.name:
        keywords['name'] = args.name
    if args.exist_ok:
        keywords['exist_ok'] = True
    model.train(data=config['data'], trainer=ssl_trainer, **keywords)


if __name__ == '__main__':
    main()
