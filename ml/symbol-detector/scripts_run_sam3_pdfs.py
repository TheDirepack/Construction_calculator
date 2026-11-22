"""GPU-accelerated SAM 3.1 + NECA 100-2013 SVG visual-prompt detector across all blueprint PDFs.

Pre-encodes every NECA 100-2013 electrical symbol SVG's visual geometry prompt ONCE on
startup using `unsloth/sam3.1` (FP16, ~2.2 GB VRAM), then encodes each non-blank
1008x1008 blueprint tile ONCE and queries every NECA SVG one-by-one (batched in GPU
mini-batches for speed with identical per-SVG results).

Outputs one resume-safe `<stem>_<sha8>_SAM3.json` per PDF using the exact bottom-left
PDF-point schema compatible with `scripts_circuitnet_review_sheets.py`.

Usage:
  python scripts_run_sam3_pdfs.py \
      --neca-dir ../data/blueprints_and_libraries/2_component_libraries/neca_100_2013_svgs/NECA_100_Vector_Library \
      --data-root ../data/blueprints_and_libraries \
      --subdirs 1_blueprints \
      --out ../work/sam3_proposals \
      --dpi 150 --svg-batch 8 --score-thr 0.45
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import time
from collections import Counter
from pathlib import Path
from typing import Any

import cv2
import numpy as np
import pymupdf
import torch
from PIL import Image
from transformers import Sam3Model, Sam3Processor
from transformers.models.sam3.modeling_sam3 import box_cxcywh_to_xyxy, inverse_sigmoid

from blueprint_symbols.neca_svg_compositor import NecaGlyph, NecaSymbolBank


SKIP_NAME_PATTERNS = (
    'specs',
    'rfp',
    'code.pdf',
    'maximum-pulling-tension',
    'difference in room annotation',
)

# Fast text-based page pre-filter patterns (checked via PyMuPDF page.get_text() in ~2ms)
_ELEC_SHEET_RE = re.compile(
    r'\b(?:E|EL|EP|FA|FP|T|ES|ED)[-_.]?\d{1,3}(?:\.\d+)?[A-Z]?\b',
    re.IGNORECASE,
)
_ELEC_KEYWORDS = (
    'electrical', 'lighting', 'power plan', 'receptacle', 'fire alarm',
    'panelboard', 'branch circuit', 'luminaire', 'switch', 'duplex',
    'emergency lighting', 'exit sign', 'telecommunications', 'low voltage',
    'power and systems', 'lighting and power', 'electrical floor',
)
_SKIP_PAGE_KEYWORDS = (
    'cover sheet', 'title sheet', 'sheet index', 'drawing index', 'index of drawings',
    'table of contents', 'vicinity map', 'location map', 'general notes',
    'panel schedule', 'lighting fixture schedule', 'luminaire schedule',
    'riser diagram', 'one-line diagram', 'one line diagram', 'single line diagram',
    'single-line diagram', 'door schedule', 'finish schedule', 'room finish',
    'equipment schedule', 'mechanical schedule', 'plumbing schedule',
    'specifications', 'code summary', 'life safety code', 'accessibility details',
    'building elevations', 'exterior elevations', 'interior elevations',
    'building sections', 'wall sections', 'typical wall', 'foundation plan',
    'roof framing', 'structural notes', 'grading plan', 'drainage plan',
    'erosion control', 'landscape plan', 'irrigation plan', 'plumbing plan',
    'hvac plan', 'mechanical plan', 'ductwork', 'civil notes', 'survey',
)


_COVER_OR_INDEX_KEYWORDS = (
    'cover sheet', 'title sheet', 'sheet index', 'drawing index', 'index of drawings',
    'table of contents', 'vicinity map', 'location map',
)


def should_scan_page(page: pymupdf.Page, p0: int, n_pages: int, *, min_page_pts: float = 900.0) -> tuple[bool, str]:
    """Fast (~2ms) pre-filter to drop cover sheets, schedules, specs, and non-electrical disciplines."""
    pw, ph = float(page.rect.width), float(page.rect.height)
    if n_pages > 10 and max(pw, ph) < min_page_pts:
        return False, 'small_page'

    raw_text = page.get_text('text') or ''
    low = ' '.join(raw_text.lower().split())

    # Always skip cover / title / drawing-index / vicinity-map sheets BEFORE checking electrical keywords
    # (otherwise an Index of Drawings listing "E2.1 LIGHTING AND POWER PLAN" slips through!)
    for kw in _COVER_OR_INDEX_KEYWORDS:
        if kw in low:
            return False, f'cover_or_index:{kw}'
    if n_pages >= 4 and p0 == 0:
        return False, 'cover_page_1'

    # If page has almost no extractable text (scanned raster or stroked CAD font), keep it
    if len(low) < 40:
        return True, 'no_text_keep'

    # Drop obvious schedule / diagram / spec / non-electrical sheets
    has_elec_plan = any(k in low for k in (
        'electrical plan', 'lighting plan', 'power plan', 'fire alarm plan',
        'power and lighting', 'lighting and power', 'electrical floor plan',
        'power & lighting', 'lighting & power', 'branch circuit', 'receptacle',
    ))
    if not has_elec_plan:
        for kw in _SKIP_PAGE_KEYWORDS:
            if kw in low:
                return False, f'skip_kw:{kw}'

    # Strong positive electrical signal
    if has_elec_plan or any(k in low for k in _ELEC_KEYWORDS) or _ELEC_SHEET_RE.search(raw_text):
        return True, 'elec_match'

    # For large multi-discipline bid books (>8 pages), skip pages with zero electrical signal
    if n_pages > 8:
        return False, 'no_elec_signal'
    return True, 'small_doc_keep'


def deduplicate_svg_prompts(
    glyphs: list[NecaGlyph],
    geo_feats: torch.Tensor,
    geo_masks: torch.Tensor,
    *,
    sim_threshold: float = 0.90,
) -> tuple[list[NecaGlyph], torch.Tensor, torch.Tensor]:
    """Greedily keep distinct visual prototypes within each fine-grained (device_type, subtype)."""
    m = geo_masks.unsqueeze(-1).float()
    pooled = (geo_feats.float() * m).sum(dim=1) / m.sum(dim=1).clamp(min=1.0)
    pooled = torch.nn.functional.normalize(pooled, dim=-1)

    keep_indices: list[int] = []
    kept_by_key: dict[tuple[str, str], list[torch.Tensor]] = {}
    for idx, g in enumerate(glyphs):
        v = pooled[idx]
        key = (g.device_type, g.subtype)
        prev = kept_by_key.get(key, [])
        if prev:
            sims = torch.stack(prev, dim=0) @ v
            if float(sims.max()) >= sim_threshold:
                continue
        keep_indices.append(idx)
        kept_by_key.setdefault(key, []).append(v)

    idx_t = torch.tensor(keep_indices, dtype=torch.long, device=geo_feats.device)
    kept_glyphs = [glyphs[i] for i in keep_indices]
    return kept_glyphs, geo_feats.index_select(0, idx_t), geo_masks.index_select(0, idx_t)


def is_candidate_blueprint_pdf(path: Path) -> bool:
    low = path.name.lower()
    return not any(pat in low for pat in SKIP_NAME_PATTERNS)


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
            try:
                st = p.stat()
                with p.open('rb') as f:
                    head = f.read(65536)
                    tail = b''
                    if st.st_size > 65536:
                        f.seek(max(0, st.st_size - 65536))
                        tail = f.read(65536)
                sig = hashlib.sha256(f'{st.st_size}:'.encode() + head + tail).hexdigest()
            except OSError:
                continue
            if sig in seen_sha:
                continue
            seen_sha.add(sig)
            found.append(p)
    return found


def _paste_glyph(canvas_rgb: np.ndarray, alpha: np.ndarray, cx: int, cy: int, target_px: int = 44) -> list[int]:
    ah, aw = alpha.shape
    s = target_px / max(1, max(ah, aw))
    nw = max(8, int(round(aw * s)))
    nh = max(8, int(round(ah * s)))
    a = cv2.resize(alpha, (nw, nh), interpolation=cv2.INTER_AREA if s < 1.0 else cv2.INTER_LINEAR)
    gx0, gy0 = cx - nw // 2, cy - nh // 2
    gx1, gy1 = gx0 + nw, gy0 + nh
    roi = canvas_rgb[gy0:gy1, gx0:gx1].astype(np.float32)
    af = (a.astype(np.float32) / 255.0)[:, :, None]
    canvas_rgb[gy0:gy1, gx0:gx1] = np.clip(roi * (1.0 - af), 0.0, 255.0).astype(np.uint8)
    return [gx0, gy0, gx1, gy1]


def preencode_svg_prompts(
    model: Sam3Model,
    processor: Sam3Processor,
    glyphs: list[NecaGlyph],
    *,
    device: str = 'cuda',
    dtype: torch.dtype = torch.float16,
    target_px: int = 44,
) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor]:
    """Pre-encode all NECA SVG geometry prompts ONCE from clean 5x5 legend sheets.

    Returns:
      geo_feats: [N_svg, L_geo, D] on GPU
      geo_masks: [N_svg, L_geo] bool on GPU
      blank_text_feat: [1, L_txt, D] on GPU
      blank_text_mask: [1, L_txt] bool on GPU
    """
    dummy = Image.new('RGB', (1008, 1008), (255, 255, 255))
    blank_inp = processor(images=dummy, input_boxes=[[[10, 10, 50, 50]]], input_boxes_labels=[[1]], return_tensors='pt')
    with torch.inference_mode():
        blank_te = model.get_text_features(
            input_ids=blank_inp['input_ids'].to(device),
            attention_mask=blank_inp['attention_mask'].to(device),
            return_dict=True,
        )
        blank_text_feat = blank_te.pooler_output.to(dtype)
        blank_text_mask = blank_inp['attention_mask'].to(device).bool()

        geo_feat_list: list[torch.Tensor] = []
        geo_mask_list: list[torch.Tensor] = []

        # Pack 25 SVGs per 1008x1008 white sheet (5x5 grid, 200px spacing) so they never interfere
        per_grid = 25
        for start in range(0, len(glyphs), per_grid):
            chunk = glyphs[start:start + per_grid]
            sheet = np.full((1008, 1008, 3), 255, dtype=np.uint8)
            boxes = []
            for idx, g in enumerate(chunk):
                r, c = divmod(idx, 5)
                cx = 104 + c * 200
                cy = 104 + r * 200
                boxes.append(_paste_glyph(sheet, g.alpha, cx, cy, target_px=target_px))

            s_inp = processor(images=Image.fromarray(sheet), return_tensors='pt')
            s_ve = model.get_vision_features(pixel_values=s_inp['pixel_values'].to(device, dtype))
            s_fpn = s_ve.fpn_hidden_states[:-1]
            s_pos = s_ve.fpn_position_encoding[:-1]

            for lb in boxes:
                cx = (lb[0] + lb[2]) / 2.0 / 1008.0
                cy = (lb[1] + lb[3]) / 2.0 / 1008.0
                bw = (lb[2] - lb[0]) / 1008.0
                bh = (lb[3] - lb[1]) / 1008.0
                b_emb = torch.tensor([[[cx, cy, bw, bh]]], dtype=dtype, device=device)
                b_lbl = torch.tensor([[1]], dtype=torch.long, device=device)
                b_msk = torch.tensor([[True]], dtype=torch.bool, device=device)
                geo = model.geometry_encoder(
                    box_embeddings=b_emb,
                    box_mask=b_msk,
                    box_labels=b_lbl,
                    img_feats=s_fpn,
                    img_pos_embeds=s_pos,
                )
                geo_feat_list.append(geo.last_hidden_state.to(dtype))
                geo_mask_list.append(geo.attention_mask.bool())

    geo_feats = torch.cat(geo_feat_list, dim=0)
    geo_masks = torch.cat(geo_mask_list, dim=0)
    return geo_feats, geo_masks, blank_text_feat, blank_text_mask


def nms_detections(dets: list[dict[str, Any]], iou_thr: float = 0.50) -> list[dict[str, Any]]:
    """Greedy score-descending NMS over page detections (in pixel coordinates)."""
    if len(dets) <= 1:
        return dets
    dets = sorted(dets, key=lambda d: d['score'], reverse=True)
    boxes = np.array([d['box_px'] for d in dets], dtype=np.float32)
    x1, y1, x2, y2 = boxes[:, 0], boxes[:, 1], boxes[:, 2], boxes[:, 3]
    areas = np.maximum(1.0, (x2 - x1) * (y2 - y1))
    order = np.arange(len(dets))
    keep_idx: list[int] = []
    while order.size > 0:
        i = int(order[0])
        keep_idx.append(i)
        if order.size == 1:
            break
        rest = order[1:]
        xx1 = np.maximum(x1[i], x1[rest])
        yy1 = np.maximum(y1[i], y1[rest])
        xx2 = np.minimum(x2[i], x2[rest])
        yy2 = np.minimum(y2[i], y2[rest])
        inter = np.maximum(0.0, xx2 - xx1) * np.maximum(0.0, yy2 - yy1)
        iou = inter / np.maximum(1e-6, areas[i] + areas[rest] - inter)
        order = rest[iou <= iou_thr]
    return [dets[i] for i in keep_idx]


def detect_page_sam3(
    model: Sam3Model,
    processor: Sam3Processor,
    page_rgb: np.ndarray,
    glyphs: list[NecaGlyph],
    geo_feats: torch.Tensor,
    geo_masks: torch.Tensor,
    blank_text_feat: torch.Tensor,
    blank_text_mask: torch.Tensor,
    *,
    tile_size: int = 1008,
    overlap: int = 128,
    svg_batch: int = 8,
    score_thr: float = 0.45,
    max_box_px: float = 220.0,
    min_box_px: float = 10.0,
    device: str = 'cuda',
    dtype: torch.dtype = torch.float16,
) -> list[dict[str, Any]]:
    """Run SAM 3.1 across all non-blank tiles of one page, querying every NECA SVG."""
    H, W = page_rgb.shape[:2]
    step = tile_size - overlap
    n_svgs = len(glyphs)
    raw_dets: list[dict[str, Any]] = []

    ys = list(range(0, max(1, H - overlap), step))
    xs = list(range(0, max(1, W - overlap), step))

    with torch.inference_mode():
        for y0 in ys:
            for x0 in xs:
                ye = min(H, y0 + tile_size)
                xe = min(W, x0 + tile_size)
                sub = page_rgb[y0:ye, x0:xe]
                if sub.size == 0 or sub.min() > 240:
                    continue  # skip blank white tile

                tile = np.full((tile_size, tile_size, 3), 255, dtype=np.uint8)
                th, tw = sub.shape[:2]
                tile[:th, :tw] = sub

                t_inp = processor(images=Image.fromarray(tile), return_tensors='pt')
                t_ve = model.get_vision_features(pixel_values=t_inp['pixel_values'].to(device, dtype))
                t_fpn_last = t_ve.fpn_hidden_states[-2]
                t_pos_last = t_ve.fpn_position_encoding[-2]

                # Query every NECA SVG against this tile's cached vision features (batched for GPU speed)
                for s_start in range(0, n_svgs, svg_batch):
                    s_end = min(n_svgs, s_start + svg_batch)
                    B = s_end - s_start

                    gf = geo_feats[s_start:s_end]
                    gm = geo_masks[s_start:s_end]
                    tf = blank_text_feat.expand(B, -1, -1)
                    tm = blank_text_mask.expand(B, -1)
                    comb_feat = torch.cat([tf, gf], dim=1)
                    comb_mask = torch.cat([tm, gm], dim=1)

                    vf = t_fpn_last.expand(B, -1, -1, -1)
                    vp = t_pos_last.expand(B, -1, -1, -1)

                    enc = model.detr_encoder(
                        vision_features=[vf],
                        text_features=comb_feat,
                        vision_pos_embeds=[vp],
                        text_mask=comb_mask,
                    )
                    dec = model.detr_decoder(
                        vision_features=enc.last_hidden_state,
                        text_features=enc.text_features,
                        vision_pos_encoding=enc.pos_embeds_flattened,
                        text_mask=comb_mask,
                        spatial_shapes=enc.spatial_shapes,
                    )
                    logits = model.dot_product_scoring(
                        decoder_hidden_states=dec.intermediate_hidden_states,
                        text_features=enc.text_features,
                        text_mask=comb_mask,
                    )[-1].squeeze(-1)  # [B, Q]
                    pres = dec.presence_logits[-1].squeeze(-1)  # [B, 1] or [B]
                    if pres.ndim == 1:
                        pres = pres.unsqueeze(-1)
                    scores = (logits.sigmoid() * pres.sigmoid())  # [B, Q]

                    # Filter above threshold on GPU before pulling to CPU
                    hits = torch.nonzero(scores >= score_thr, as_tuple=False)
                    if hits.numel() == 0:
                        continue

                    all_box_offsets = model.detr_decoder.box_head(dec.intermediate_hidden_states)[-1]
                    ref_boxes = dec.reference_boxes[-1] if dec.reference_boxes.ndim == 4 else dec.reference_boxes
                    ref_inv = inverse_sigmoid(ref_boxes)
                    pred_boxes = box_cxcywh_to_xyxy((ref_inv + all_box_offsets).sigmoid()) * float(tile_size)

                    hits_cpu = hits.cpu().tolist()
                    scores_cpu = scores.float().cpu().numpy()
                    boxes_cpu = pred_boxes.float().cpu().numpy()

                    for b_i, q_i in hits_cpu:
                        bx0, by0, bx1, by1 = boxes_cpu[b_i, q_i].tolist()
                        bw, bh = bx1 - bx0, by1 - by0
                        if bw < min_box_px or bh < min_box_px or bw > max_box_px or bh > max_box_px:
                            continue
                        # Clip to actual non-padded tile region
                        gx0 = max(0.0, min(float(W), x0 + bx0))
                        gy0 = max(0.0, min(float(H), y0 + by0))
                        gx1 = max(0.0, min(float(W), x0 + bx1))
                        gy1 = max(0.0, min(float(H), y0 + by1))
                        if gx1 - gx0 < min_box_px or gy1 - gy0 < min_box_px:
                            continue
                        g = glyphs[s_start + b_i]
                        raw_dets.append({
                            'box_px': [gx0, gy0, gx1, gy1],
                            'score': float(scores_cpu[b_i, q_i]),
                            'device_type': g.device_type,
                            'subtype': g.subtype,
                            'broad_family': g.canonical_class,
                            'symbol_id': g.symbol_id,
                            'neca_code': g.code,
                            'neca_name': g.name,
                        })

    return nms_detections(raw_dets, iou_thr=0.50)


def render_page_rgb(page: pymupdf.Page, dpi: float, max_pixels: int) -> tuple[np.ndarray, float, float]:
    pw, ph = float(page.rect.width), float(page.rect.height)
    scale = dpi / 72.0
    if pw * scale * ph * scale > max_pixels:
        scale = math.sqrt(max_pixels / max(1.0, pw * ph))
    pix = page.get_pixmap(matrix=pymupdf.Matrix(scale, scale), colorspace=pymupdf.csRGB, alpha=False)
    rgb = np.frombuffer(pix.samples, dtype=np.uint8).reshape(pix.height, pix.width, 3)
    return rgb, pw, ph


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--model-id', type=str, default='unsloth/sam3.1')
    ap.add_argument('--neca-dir', type=Path, required=True, help='Path to NECA_100_Vector_Library')
    ap.add_argument('--data-root', type=Path, required=True)
    ap.add_argument('--subdirs', nargs='+', default=['1_blueprints'])
    ap.add_argument('--out', type=Path, required=True)
    ap.add_argument('--dpi', type=float, default=150.0)
    ap.add_argument('--max-pixels', type=int, default=20_000_000)
    ap.add_argument('--tile-size', type=int, default=1008)
    ap.add_argument('--overlap', type=int, default=128)
    ap.add_argument('--svg-batch', type=int, default=12)
    ap.add_argument('--score-thr', type=float, default=0.55)
    ap.add_argument('--svg-dedup-sim', type=float, default=0.90,
                    help='Cosine similarity threshold in SAM3 prompt space to deduplicate near-identical SVG variants (1.0 disables)')
    ap.add_argument('--min-page-pts', type=float, default=900.0)
    ap.add_argument('--limit-pdfs', type=int, default=0)
    args = ap.parse_args()

    if not torch.cuda.is_available():
        raise SystemExit('CUDA GPU not available; refusing slow CPU SAM 3.1 scan')

    dtype = torch.float16
    print(f'Loading {args.model_id} in {dtype} on {torch.cuda.get_device_name(0)}...', flush=True)
    t0 = time.time()
    model = Sam3Model.from_pretrained(args.model_id, dtype=dtype).to('cuda').eval()
    processor = Sam3Processor.from_pretrained(args.model_id)
    print(f'Model loaded in {time.time() - t0:.1f}s (VRAM={torch.cuda.memory_allocated()/1e9:.2f} GB)', flush=True)

    bank = NecaSymbolBank(args.neca_dir.resolve(), render_height=96)
    glyphs: list[NecaGlyph] = [g for cls in bank.active_classes for g in bank.by_class[cls]]
    print(f'Pre-encoding {len(glyphs)} NECA 100-2013 SVG visual prompts ({bank.summary()})...', flush=True)
    t_enc = time.time()
    geo_feats, geo_masks, blank_tf, blank_tm = preencode_svg_prompts(
        model, processor, glyphs, device='cuda', dtype=dtype, target_px=44,
    )
    if args.svg_dedup_sim < 1.0:
        orig_n = len(glyphs)
        glyphs, geo_feats, geo_masks = deduplicate_svg_prompts(
            glyphs, geo_feats, geo_masks, sim_threshold=args.svg_dedup_sim,
        )
        dedup_counts = dict(Counter(g.canonical_class for g in glyphs))
        print(
            f'Deduplicated SVGs in SAM3 prompt space (sim>={args.svg_dedup_sim}): '
            f'{orig_n} -> {len(glyphs)} distinct prototypes ({dedup_counts})',
            flush=True,
        )
    print(f'Pre-encoded {len(glyphs)} active SVG prompts in {time.time() - t_enc:.1f}s (VRAM={torch.cuda.memory_allocated()/1e9:.2f} GB)', flush=True)

    data_root = args.data_root.resolve()
    out_dir = args.out.resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    summary_path = out_dir / 'summary.jsonl'

    pdfs = discover_pdfs(data_root, args.subdirs)
    if args.limit_pdfs > 0:
        pdfs = pdfs[:args.limit_pdfs]
    print(f'Discovered {len(pdfs)} unique blueprint PDFs to scan (dpi={args.dpi}, svg_batch={args.svg_batch}, thr={args.score_thr})', flush=True)

    t_global = time.time()
    total_pages = 0
    total_devices = 0

    for idx, pdf_path in enumerate(pdfs, start=1):
        rel = str(pdf_path.relative_to(data_root))
        short_hash = hashlib.sha256(rel.encode()).hexdigest()[:8]
        safe_stem = re.sub(r'[^a-zA-Z0-9._-]+', '_', pdf_path.stem)[:80]
        out_json = out_dir / f'{safe_stem}_{short_hash}_SAM3.json'
        if out_json.is_file():
            continue

        t_pdf = time.time()
        try:
            doc = pymupdf.open(pdf_path)
        except Exception as e:
            print(f'[{idx}/{len(pdfs)}] SKIP unreadable PDF {rel}: {e}', flush=True)
            continue

        n_pages = len(doc)
        pdf_devices: list[dict[str, Any]] = []
        pages_meta: list[dict[str, Any]] = []
        cls_counts: Counter = Counter()
        scanned_here = 0

        skipped_reasons: Counter = Counter()
        try:
            for p0 in range(n_pages):
                page = doc.load_page(p0)
                keep_pg, reason = should_scan_page(page, p0, n_pages, min_page_pts=args.min_page_pts)
                if not keep_pg:
                    skipped_reasons[reason] += 1
                    continue
                t_pg = time.time()
                rgb, pw, ph = render_page_rgb(page, dpi=args.dpi, max_pixels=args.max_pixels)
                rh, rw = rgb.shape[:2]
                dets = detect_page_sam3(
                    model, processor, rgb, glyphs,
                    geo_feats, geo_masks, blank_tf, blank_tm,
                    tile_size=args.tile_size, overlap=args.overlap,
                    svg_batch=args.svg_batch, score_thr=args.score_thr,
                    device='cuda', dtype=dtype,
                )
                scanned_here += 1
                total_pages += 1
                print(
                    f'  [{idx}/{len(pdfs)}] {pdf_path.name[:34]:34s} p{p0 + 1:3d}/{n_pages:<3d} '
                    f'({reason}) -> {len(dets):4d} devs ({time.time() - t_pg:4.1f}s)',
                    flush=True,
                )

                sx_pt = pw / rw
                sy_pt = ph / rh
                page_dev_count = 0
                for d in dets:
                    x0, y0, x1, y1 = d['box_px']
                    bx0 = round(x0 * sx_pt, 2)
                    bx1 = round(x1 * sx_pt, 2)
                    by0 = round(ph - y1 * sy_pt, 2)
                    by1 = round(ph - y0 * sy_pt, 2)
                    cx = round(0.5 * (bx0 + bx1), 2)
                    cy = round(0.5 * (by0 + by1), 2)
                    cname = f'{d["device_type"]}/{d["subtype"]}'
                    cls_counts[cname] += 1
                    page_dev_count += 1
                    pdf_devices.append({
                        'pdf_page': p0 + 1,
                        'device_type': d['device_type'],
                        'subtype': d['subtype'],
                        'broad_family': d['broad_family'],
                        'neca_symbol_id': d['symbol_id'],
                        'neca_code': d['neca_code'],
                        'neca_name': d['neca_name'],
                        'score': round(d['score'], 4),
                        'symbol_bbox': [bx0, by0, bx1, by1],
                        'symbol_center': [cx, cy],
                        'box_px_tl': [round(x0, 1), round(y0, 1), round(x1, 1), round(y1, 1)],
                        'status': 'provisional_sam3_neca',
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
            'model': f'{args.model_id} + NECA_100_2013_Vector_SVG ({len(glyphs)} visual prompts)',
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
        total_devices += len(pdf_devices)
        dt = time.time() - t_pdf
        with summary_path.open('a') as sf:
            sf.write(json.dumps({
                'pdf': rel,
                'out_json': out_json.name,
                'pages_scanned': scanned_here,
                'total_devices': len(pdf_devices),
                'seconds': round(dt, 2),
            }) + '\n')
        print(
            f'[{idx:3d}/{len(pdfs)}] {pdf_path.name[:48]:48s} | '
            f'pgs={scanned_here:3d}/{n_pages:<3d} devs={len(pdf_devices):5d} ({dt:5.1f}s) | '
            f'cum_devs={total_devices}',
            flush=True,
        )

    print(
        f'DONE: {len(pdfs)} PDFs, {total_pages} pages scanned, '
        f'{total_devices} provisional devices in {time.time() - t_global:.1f}s',
        flush=True,
    )


if __name__ == '__main__':
    main()
