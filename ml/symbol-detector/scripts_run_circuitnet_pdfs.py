"""Fast batched-GPU CircuitNet provisional device detector across all blueprint PDFs.

Runs constructelligence/electrical-circuit-connectivity (circuits.onnx) on CUDA GPU
using batched 512x512 tiles, skipping blank tiles and avoiding slow wire skeletonization,
and writes one provisional-detections JSON per PDF referencing the exact source PDF.

Usage:
  python scripts_run_circuitnet_pdfs.py \
      --circuitnet-dir ~/Projects/blueprint-symbols/circuitnet \
      --data-root ~/Projects/blueprint-symbols/data/blueprints_and_libraries \
      --out ~/Projects/blueprint-symbols/work/circuitnet_proposals \
      --dpi 120 --batch 16
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
import time
from collections import Counter
from pathlib import Path

import numpy as np
import pymupdf


SKIP_NAME_PATTERNS = (
    'specs',
    'rfp',
    'code.pdf',
    'maximum-pulling-tension',
    'difference in room annotation',
)


def is_candidate_blueprint_pdf(path: Path) -> bool:
    low = path.name.lower()
    if any(pat in low for pat in SKIP_NAME_PATTERNS):
        return False
    return True


def run_sheet_batched(sess, gray: np.ndarray, *, tile_size: int = 512, overlap: int = 64,
                      batch_size: int = 16, num_classes: int = 13) -> tuple[np.ndarray, np.ndarray]:
    """Batched GPU tile inference for CircuitNet; skips completely blank white tiles."""
    H, W = gray.shape
    gh, gw = (H + 1) // 2, (W + 1) // 2
    peaks = np.zeros((num_classes, gh, gw), np.float32)
    size = np.zeros((2, gh, gw), np.float32)
    step = tile_size - 2 * overlap
    n = step // 2
    o = overlap // 2

    batch_tiles: list[np.ndarray] = []
    batch_meta: list[tuple[int, int, int, int, int, int]] = []

    def flush() -> None:
        if not batch_tiles:
            return
        arr = np.stack(batch_tiles, axis=0)[:, None, :, :]  # [B, 1, 512, 512]
        p_out, s_out, _ = sess.run(None, {'image': arr})
        for idx, (ya, yb, xa, xb, sy0, sx0) in enumerate(batch_meta):
            sy1 = sy0 + (yb - ya)
            sx1 = sx0 + (xb - xa)
            peaks[:, ya:yb, xa:xb] = p_out[idx, :, sy0:sy1, sx0:sx1]
            size[:, ya:yb, xa:xb] = s_out[idx, :, sy0:sy1, sx0:sx1]
        batch_tiles.clear()
        batch_meta.clear()

    for y0 in range(-overlap, max(1, H - overlap), step):
        for x0 in range(-overlap, max(1, W - overlap), step):
            ys, xs = max(0, y0), max(0, x0)
            ye, xe = min(H, y0 + tile_size), min(W, x0 + tile_size)
            sub = gray[ys:ye, xs:xe]
            if sub.size == 0 or sub.min() > 240:
                continue  # skip completely blank white tile
            cy0, cx0 = (y0 + overlap) // 2, (x0 + overlap) // 2
            ya, xa = max(0, cy0), max(0, cx0)
            yb, xb = min(gh, cy0 + n), min(gw, cx0 + n)
            if yb <= ya or xb <= xa:
                continue
            tile = np.full((tile_size, tile_size), 255.0, np.float32)
            tile[ys - y0:ye - y0, xs - x0:xe - x0] = sub
            batch_tiles.append(tile)
            batch_meta.append((ya, yb, xa, xb, o + ya - cy0, o + xa - cx0))
            if len(batch_tiles) >= batch_size:
                flush()
    flush()
    return peaks, size


def render_page_gray(page: pymupdf.Page, dpi: float, max_pixels: int) -> tuple[np.ndarray, float, float]:
    pw, ph = float(page.rect.width), float(page.rect.height)
    scale = dpi / 72.0
    if pw * scale * ph * scale > max_pixels:
        scale = math.sqrt(max_pixels / max(1.0, pw * ph))
    pix = page.get_pixmap(matrix=pymupdf.Matrix(scale, scale), colorspace=pymupdf.csGRAY, alpha=False)
    arr = np.frombuffer(pix.samples, dtype=np.uint8).reshape(pix.height, pix.width).astype(np.float32)
    return arr, pw, ph


def discover_pdfs(data_root: Path, subdirs: list[str]) -> list[Path]:
    found: list[Path] = []
    seen_sha: set[str] = set()
    for sub in subdirs:
        base = data_root / sub
        if not base.is_dir():
            continue
        for p in sorted(base.rglob('*.pdf')):
            if not is_candidate_blueprint_pdf(p):
                continue
            # Deduplicate identical PDFs by fast size + first/last 64KB hash
            try:
                st = p.stat()
                with p.open('rb') as f:
                    head = f.read(65536)
                    if st.st_size > 65536:
                        f.seek(max(0, st.st_size - 65536))
                        tail = f.read(65536)
                    else:
                        tail = b''
                sig = hashlib.sha256(f'{st.st_size}:'.encode() + head + tail).hexdigest()
            except OSError:
                continue
            if sig in seen_sha:
                continue
            seen_sha.add(sig)
            found.append(p)
    return found


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--circuitnet-dir', type=Path, required=True)
    ap.add_argument('--data-root', type=Path, required=True)
    ap.add_argument('--out', type=Path, required=True)
    ap.add_argument('--subdirs', nargs='+', default=['1_blueprints', '4_archives/state_dot_samples'])
    ap.add_argument('--dpi', type=float, default=120.0,
                    help='Render DPI (120 DPI puts standard 150-dpi 20px receptacles right at CircuitNet 16px training scale)')
    ap.add_argument('--max-pixels', type=int, default=20_000_000)
    ap.add_argument('--batch', type=int, default=16)
    ap.add_argument('--score-thr', type=float, default=0.35)
    ap.add_argument('--min-page-pts', type=float, default=900.0,
                    help='Skip standard letter-size (<900pt) text/spec pages unless PDF has <=10 pages')
    ap.add_argument('--limit-pdfs', type=int, default=0, help='Optional cap for smoke testing (0 = all)')
    args = ap.parse_args()

    # Preload torch CUDA/cuDNN libraries before creating ONNX Runtime CUDA session
    import torch  # noqa: F401
    import onnxruntime as ort

    cdir = args.circuitnet_dir.resolve()
    sys.path.insert(0, str(cdir))
    import decode as D

    sess_opts = ort.SessionOptions()
    sess_opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    sess = ort.InferenceSession(
        str(cdir / 'circuits.onnx'),
        sess_options=sess_opts,
        providers=['CUDAExecutionProvider', 'CPUExecutionProvider'],
    )
    print('ONNX Runtime providers:', sess.get_providers(), flush=True)
    if 'CUDAExecutionProvider' not in sess.get_providers():
        raise SystemExit('CUDAExecutionProvider not active; refusing slow CPU scan')

    cfg = dict(D.CFG)
    cfg['heat_thr'] = float(args.score_thr)

    data_root = args.data_root.resolve()
    out_dir = args.out.resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    summary_path = out_dir / 'summary.jsonl'

    pdfs = discover_pdfs(data_root, args.subdirs)
    if args.limit_pdfs > 0:
        pdfs = pdfs[:args.limit_pdfs]
    print(f'Discovered {len(pdfs)} unique blueprint PDFs to scan (dpi={args.dpi}, batch={args.batch})', flush=True)

    t_global = time.time()
    total_pages_scanned = 0
    total_devices_found = 0

    for idx, pdf_path in enumerate(pdfs, start=1):
        rel = str(pdf_path.relative_to(data_root))
        short_hash = hashlib.sha256(rel.encode()).hexdigest()[:8]
        safe_stem = re.sub(r'[^a-zA-Z0-9._-]+', '_', pdf_path.stem)[:80]
        out_json = out_dir / f'{safe_stem}_{short_hash}_CIRCUITNET.json'
        if out_json.is_file():
            continue

        t0 = time.time()
        try:
            doc = pymupdf.open(pdf_path)
        except Exception as e:
            print(f'[{idx}/{len(pdfs)}] SKIP unreadable PDF {rel}: {e}', flush=True)
            continue

        n_pages = len(doc)
        pdf_devices: list[dict] = []
        pages_meta: list[dict] = []
        cls_counts: Counter = Counter()
        scanned_here = 0

        try:
            for p0 in range(n_pages):
                page = doc.load_page(p0)
                pw, ph = float(page.rect.width), float(page.rect.height)
                # Skip letter/legal text-only spec pages inside large bid books
                if n_pages > 10 and max(pw, ph) < args.min_page_pts:
                    continue
                gray, pw, ph = render_page_gray(page, dpi=args.dpi, max_pixels=args.max_pixels)
                rh, rw = gray.shape
                peaks, size = run_sheet_batched(
                    sess, gray, batch_size=args.batch, num_classes=len(D.CLASSES)
                )
                dets = D.detect(peaks, size, cfg)
                scanned_here += 1
                total_pages_scanned += 1

                sx_pt = pw / rw
                sy_pt = ph / rh
                page_dev_count = 0
                for d in dets:
                    x0, y0, x1, y1 = (float(v) for v in d['box'])
                    # Convert top-left render pixels -> bottom-left display PDF points
                    bx0 = round(x0 * sx_pt, 2)
                    bx1 = round(x1 * sx_pt, 2)
                    by0 = round(ph - y1 * sy_pt, 2)
                    by1 = round(ph - y0 * sy_pt, 2)
                    cx = round(0.5 * (bx0 + bx1), 2)
                    cy = round(0.5 * (by0 + by1), 2)
                    cname = D.cname(int(d['cls']))
                    cls_counts[cname] += 1
                    page_dev_count += 1
                    pdf_devices.append({
                        'pdf_page': p0 + 1,
                        'device_type': cname,
                        'circuitnet_cls_id': int(d['cls']),
                        'score': round(float(d['score']), 4),
                        'symbol_bbox': [bx0, by0, bx1, by1],
                        'symbol_center': [cx, cy],
                        'box_px_tl': [round(x0, 1), round(y0, 1), round(x1, 1), round(y1, 1)],
                        'status': 'provisional_circuitnet',
                    })

                if page_dev_count > 0:
                    pages_meta.append({
                        'pdf_page': p0 + 1,
                        'page_size_pts': [round(pw, 2), round(ph, 2)],
                        'render_size_px': [rw, rh],
                        'device_count': page_dev_count,
                    })
        finally:
            doc.close()

        payload = {
            'source_file': pdf_path.name,
            'source_pdf_relative': rel,
            'source_pdf_absolute': str(pdf_path),
            'model': 'constructelligence/electrical-circuit-connectivity (circuits.onnx)',
            'status': 'provisional_detections_for_refinement',
            'render_dpi': args.dpi,
            'score_threshold': args.score_thr,
            'coordinate_system': {
                'origin': 'bottom_left',
                'units': 'PDF_points',
                'reference': 'display_oriented_page_after_applying_pdf_rotation',
                'symbol_bbox_format': '[x_min,y_min,x_max,y_max]',
            },
            'total_pdf_pages': n_pages,
            'pages_scanned': scanned_here,
            'pages_with_devices': len(pages_meta),
            'total_devices': len(pdf_devices),
            'by_class': dict(cls_counts),
            'pages': pages_meta,
            'devices': pdf_devices,
        }
        out_json.write_text(json.dumps(payload))
        total_devices_found += len(pdf_devices)
        dt = time.time() - t0
        summary_row = {
            'pdf': rel,
            'out_json': out_json.name,
            'pages_scanned': scanned_here,
            'total_devices': len(pdf_devices),
            'seconds': round(dt, 2),
        }
        with summary_path.open('a') as sf:
            sf.write(json.dumps(summary_row) + '\n')
        print(
            f'[{idx:3d}/{len(pdfs)}] {pdf_path.name[:50]:50s} | '
            f'pgs={scanned_here:3d}/{n_pages:<3d} devs={len(pdf_devices):5d} ({dt:5.1f}s) | '
            f'cum_devs={total_devices_found}',
            flush=True,
        )

    print(
        f'DONE: {len(pdfs)} PDFs, {total_pages_scanned} pages scanned, '
        f'{total_devices_found} provisional devices in {time.time() - t_global:.1f}s',
        flush=True,
    )


if __name__ == '__main__':
    main()
