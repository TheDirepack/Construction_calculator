"""Mine hard negatives: where a checkpoint fires on verified-blank TRAINING pages.

    python scripts_mine_hard_negatives.py runs/phase2/weights/best.pt \
        --label-store ../data/blueprints_and_libraries/1_blueprints \
        --data-root ../data/blueprints_and_libraries \
        --output work/hard_negatives.json --floor 0.05

Runs the same deterministic whole-page tiled inference as the real-page eval
(``real_page_eval.predict_full_page_torch``, scale 1, every tile) over the
``train`` pages of the label stores that are verified to hold no device
symbols. Pages held out for the real-page test (``holdout``) and evaluation
drawings are never read, nor training pages whose image is a copy of one.
Every detection at or above ``--floor`` is a false positive by attestation;
nearby ones are grouped into 512 px page windows (scale 1, as inference saw
them), most confident first.

The output (``format: phase2_hard_negative_windows``) is read by
scripts_export_yolo_tiles.py (``--hard-negatives`` or the
``PHASE2_HARD_NEGATIVES`` environment variable): each window becomes extra
verified-empty training tiles with several variants. Needs PyTorch and
Ultralytics (the training box).
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path

import numpy as np

FORMAT = 'phase2_hard_negative_windows'
TILE = 512


def _page_image(ref: str | Path, page: dict) -> Path:
    from blueprint_symbols.label_schema import parse_page_ref
    parsed = parse_page_ref(ref)
    base = Path(parsed[0]) if parsed is not None else Path(ref)
    return (base.parent / str(page['image_path'])).resolve()


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, 'rb') as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b''):
            digest.update(chunk)
    return digest.hexdigest()


def mining_pages(label_stores: list[Path], *, data_root: Path | None = None) -> list[dict]:
    """Verified-blank pages of the ``train`` split only, one per distinct image.

    Never ``holdout`` (the real-page test) or ``evaluation`` pages, and never a
    training page whose image bytes equal one of those (a duplicate sheet)."""
    from annotator import label_store
    from blueprint_symbols.label_schema import read_page

    rows: list[dict] = []
    seen: set[str] = set()
    for store in label_stores:
        pages = label_store.labeled_pages(Path(store), data_root=data_root)
        protected: set[str] = set()
        for role in ('holdout', 'evaluation'):
            for ref in pages[role]:
                try:
                    doc = read_page(ref)
                    protected.add(str(doc.get('image_sha256') or _sha256(_page_image(ref, doc))))
                except (OSError, ValueError, KeyError):
                    continue
        for ref in pages['train']:
            try:
                doc = read_page(ref)
            except (OSError, ValueError):
                continue
            if doc.get('verified_no_symbols') is not True or (doc.get('instances') or []):
                continue
            image = _page_image(ref, doc)
            if not image.is_file():
                continue
            digest = str(doc.get('image_sha256') or _sha256(image))
            if digest in protected or digest in seen:
                continue
            seen.add(digest)
            rows.append({
                'page_ref': str(ref),
                'image_path': str(image),
                'image_sha256': digest,
                'document_id': str(doc.get('document_id') or ''),
                'project_family_id': str(doc.get('project_family_id') or ''),
                'page_index': doc.get('page_index'),
                'width': int(doc.get('image_width') or 0),
                'height': int(doc.get('image_height') or 0),
            })
    return rows


def windows_from_detections(dets: list[dict], width: int, height: int, *, tile: int = TILE,
                            max_windows: int = 24) -> list[dict]:
    """Group detections into ``tile``-px page windows, most confident first.

    Each window is centred on its strongest detection (clipped into the page)
    and takes every later detection whose centre lies in its middle half, so
    a row of dashes firing together gives one window, not twenty."""
    order = sorted(dets, key=lambda d: -float(d['confidence']))
    windows: list[dict] = []
    for det in order:
        x0, y0, x1, y1 = (float(v) for v in det['bbox_xyxy'])
        cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
        home = next((win for win in windows
                     if abs(cx - win['_c'][0]) <= tile / 4 and abs(cy - win['_c'][1]) <= tile / 4), None)
        row = {'symbol_family': str(det['symbol_family']), 'confidence': round(float(det['confidence']), 4),
               'bbox_xyxy': [round(v, 2) for v in (x0, y0, x1, y1)]}
        if home is not None:
            home['detections'].append(row)
            continue
        if len(windows) >= max_windows:
            continue
        span_w, span_h = min(tile, width), min(tile, height)
        wx = float(np.clip(cx - span_w / 2, 0, max(0, width - span_w)))
        wy = float(np.clip(cy - span_h / 2, 0, max(0, height - span_h)))
        windows.append({'_c': (cx, cy), 'window_xyxy': [round(wx, 2), round(wy, 2),
                                                       round(wx + span_w, 2), round(wy + span_h, 2)],
                        'max_confidence': row['confidence'], 'detections': [row]})
    for win in windows:
        del win['_c']
    return windows


def write_windows(path: Path, pages: list[dict], per_page: dict[str, list[dict]], *,
                  weights: str, floor: float, max_windows: int = 0) -> dict:
    """The windows file: every page's windows, most confident first overall."""
    rows = []
    for page in pages:
        for win in per_page.get(page['image_sha256'], []):
            rows.append({**{k: page[k] for k in ('page_ref', 'image_path', 'image_sha256', 'document_id',
                                                 'project_family_id', 'page_index')},
                         'split': 'train', 'source_scale': 1.0, **win})
    rows.sort(key=lambda r: (-r['max_confidence'], r['image_sha256'], r['window_xyxy']))
    if max_windows > 0:
        rows = rows[:max_windows]
    payload = {
        'format': FORMAT, 'format_version': 1,
        'created': datetime.now(timezone.utc).isoformat(timespec='seconds'),
        'weights': weights, 'floor': floor, 'tile': TILE,
        'pages_mined': len(pages),
        'pages_with_detections': sum(1 for p in pages if per_page.get(p['image_sha256'])),
        'windows': rows,
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + '.tmp')
    tmp.write_text(json.dumps(payload, indent=1))
    tmp.replace(path)
    return payload


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('weights', type=Path, help='Phase-2 detector checkpoint (best.pt / last.pt)')
    p.add_argument('--label-store', type=Path, action='append', required=True)
    p.add_argument('--data-root', type=Path,
                   help='blueprints_and_libraries root (decides train vs evaluation drawings)')
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--floor', type=float, default=0.05, help='lowest confidence that counts as a detection')
    p.add_argument('--overlap', type=int, default=192)
    p.add_argument('--batch', type=int, default=8)
    p.add_argument('--max-pages', type=int, default=0, help='0 = every verified-blank training page')
    p.add_argument('--windows-per-page', type=int, default=24)
    p.add_argument('--max-windows', type=int, default=3000, help='keep the most confident (0 = all)')
    p.add_argument('--device', default=None, help='torch device (default: cuda if available)')
    a = p.parse_args()
    if not 0 < a.floor < 1:
        raise SystemExit('--floor must be in (0, 1)')

    import cv2
    import torch
    from ultralytics import YOLO
    from blueprint_symbols.real_page_eval import predict_full_page_torch

    pages = mining_pages(a.label_store, data_root=a.data_root)
    if a.max_pages > 0:
        pages = pages[:a.max_pages]
    print(f'{len(pages)} verified-blank TRAINING pages to mine (holdout/evaluation never read)', flush=True)
    yolo = YOLO(str(a.weights))
    model = yolo.model
    names = {int(k): str(v) for k, v in yolo.names.items()}
    device = torch.device(a.device or ('cuda' if torch.cuda.is_available() else 'cpu'))
    model.to(device)
    per_page: dict[str, list[dict]] = {}
    total = 0
    for n, page in enumerate(pages, 1):
        img = cv2.imread(page['image_path'], cv2.IMREAD_COLOR)
        if img is None:
            print(f'unreadable: {page["image_path"]}', flush=True)
            continue
        dets, _ = predict_full_page_torch(model, img, names=names, imgsz=TILE, overlap=a.overlap,
                                          confidence=a.floor, batch_size=a.batch, device=device,
                                          max_raw=4000)
        if dets:
            wins = windows_from_detections(dets, img.shape[1], img.shape[0], max_windows=a.windows_per_page)
            per_page[page['image_sha256']] = wins
            total += len(dets)
        if n % 10 == 0 or n == len(pages):
            print(f'{n}/{len(pages)} pages, {total} detections >= {a.floor}', flush=True)
    payload = write_windows(a.output, pages, per_page, weights=str(a.weights.resolve()),
                            floor=a.floor, max_windows=a.max_windows)
    by_class: dict[str, int] = {}
    for row in payload['windows']:
        for det in row['detections']:
            by_class[det['symbol_family']] = by_class.get(det['symbol_family'], 0) + 1
    print(json.dumps({'output': str(a.output), 'pages_mined': payload['pages_mined'],
                      'pages_with_detections': payload['pages_with_detections'],
                      'windows': len(payload['windows']),
                      'detections_by_class': dict(sorted(by_class.items(), key=lambda kv: -kv[1])[:20])},
                     indent=2), flush=True)


if __name__ == '__main__':
    main()
