"""Self-hosted SAM 3.1 provisional electrical-symbol proposals.

Default: scan each distinct NECA base silhouette once per nonblank blueprint
tile, without querying every compound switch/receptacle variant. Small switch
suffix marks are matched locally and attached as provisional attributes to
the SAME device bounding box. Keep all detected boxes/labels reviewable; never
treat automatically generated proposals as exhaustive ground truth.

The tile image encoder runs once per tile and feeds batched exemplar-geometry
queries. Reference rasters/embeddings and PDF resume outputs are stored locally.
`--scan-mode flat` and `hierarchical` preserve explicit A/B controls.
`--catalog-only --visual-audit-out ...` exports every NECA visual grouping.

Example:
  python scripts_run_sam3_pdfs.py --neca-dir <NECA_Vector_Library> \\
    --data-root <blueprint-root> --out <local-output>
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

from blueprint_symbols.neca_prompt_catalog import (SamNecaPrompt, list_neca_prompt_specs,
    choose_neca_prompts, load_neca_sam_prompts)
from blueprint_symbols.proposal_fusion import merge_sam_proposals
from blueprint_symbols.neca_visual_groups import choose_visual_base_prototypes, audit_neca_visual_groups
from blueprint_symbols.switch_suffix_templates import (
    build_switch_suffix_templates, search_switch_suffixes,
)
from blueprint_symbols.neca_modifier_attachment import attach_device_modifiers
from blueprint_symbols.sam_precision import filter_sam_detections, tile_origins
from blueprint_symbols.sam_shared_canvas import SharedCanvas, build_shared_canvas
from blueprint_symbols.sam_resume import completed_sam_output, atomic_write_json
from blueprint_symbols.sam_precision_profile import (
    load_family_thresholds, threshold_for_family,
)
from blueprint_symbols.preencoded_prompt_cache import (
    prompt_feature_cache_path, load_prompt_features, save_prompt_features,
    resolve_model_weight_identity,
)
from blueprint_symbols.hierarchical_sam import (build_hierarchical_prompt_plan,
    active_fine_indices, accepted_fine_match, explain_query_savings)


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
    glyphs: list[SamNecaPrompt],
    geo_feats: torch.Tensor,
    geo_masks: torch.Tensor,
    *,
    sim_threshold: float = 0.90,
) -> tuple[list[SamNecaPrompt], torch.Tensor, torch.Tensor]:
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
    glyphs: list[SamNecaPrompt],
    *,
    device: str = 'cuda',
    dtype: torch.dtype = torch.float16,
    target_px: int = 44,
    geometry_batch: int = 4,
) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor]:
    """Pre-encode NECA geometry from a shared reference sheet in bounded GPU batches.

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

            # Each SVG previously triggered a separate geometry-encoder call.
            # Batch prompt boxes against the SAME reference image embedding;
            # small batches bound GPU VRAM on the self-hosted 6GB machine.
            if geometry_batch < 1:
                raise ValueError('geometry_batch must be positive')
            box_start = 0
            active_batch = geometry_batch
            while box_start < len(boxes):
                group = boxes[box_start:box_start + active_batch]
                coords = []
                for lb in group:
                    cx = (lb[0] + lb[2]) / 2.0 / 1008.0
                    cy = (lb[1] + lb[3]) / 2.0 / 1008.0
                    bw = (lb[2] - lb[0]) / 1008.0
                    bh = (lb[3] - lb[1]) / 1008.0
                    coords.append([[cx, cy, bw, bh]])
                B = len(group)
                b_emb = torch.tensor(coords, dtype=dtype, device=device)
                b_lbl = torch.ones((B, 1), dtype=torch.long, device=device)
                b_msk = torch.ones((B, 1), dtype=torch.bool, device=device)
                try:
                    geo = model.geometry_encoder(
                        box_embeddings=b_emb,
                        box_mask=b_msk,
                        box_labels=b_lbl,
                        img_feats=[t.expand(B, *t.shape[1:]) for t in s_fpn],
                        img_pos_embeds=[t.expand(B, *t.shape[1:]) for t in s_pos],
                    )
                except torch.cuda.OutOfMemoryError:
                    if active_batch == 1:
                        raise
                    active_batch = max(1, active_batch // 2)
                    torch.cuda.empty_cache()
                    print(f'SAM reference geometry OOM: retrying with batch={active_batch}', flush=True)
                    continue
                geo_feat_list.append(geo.last_hidden_state.to(dtype))
                geo_mask_list.append(geo.attention_mask.bool())
                box_start += B

    geo_feats = torch.cat(geo_feat_list, dim=0)
    geo_masks = torch.cat(geo_mask_list, dim=0)
    return geo_feats, geo_masks, blank_text_feat, blank_text_mask


def nms_detections(dets: list[dict[str, Any]], iou_thr: float = 0.50) -> list[dict[str, Any]]:
    """Consolidate prompt hits while retaining alternative semantic labels.

    NECA exemplar prompt scores are provisional, not calibrated class confidence.
    """
    return merge_sam_proposals(dets, iou_threshold=iou_thr)


def detect_page_sam3(
    model: Sam3Model,
    processor: Sam3Processor,
    page_rgb: np.ndarray,
    glyphs: list[SamNecaPrompt],
    geo_feats: torch.Tensor,
    geo_masks: torch.Tensor,
    blank_text_feat: torch.Tensor,
    blank_text_mask: torch.Tensor,
    *,
    tile_size: int = 1008,
    overlap: int = 128,
    svg_batch: int = 8,
    score_thr: float = 0.72,
    coarse_score_thr: float = 0.50,
    prompt_plan: dict[str, Any] | None = None,
    shared_canvas: SharedCanvas | None = None,
    fallback_every_tiles: int = 0,
    fine_match_iou: float = 0.10,
    emit_generic: bool = False,
    max_box_aspect: float = 6.0,
    min_ink_fraction: float = 0.018,
    max_ink_fraction: float = 0.85,
    coarse_family_limit: int = 50,
    fine_family_limit: int = 24,
    max_detections_per_tile: int = 64,
    family_thresholds: dict[str, dict[str, float]] | None = None,
    query_stats: Counter | None = None,
    max_box_px: float = 220.0,
    min_box_px: float = 10.0,
    device: str = 'cuda',
    dtype: torch.dtype = torch.float16,
) -> list[dict[str, Any]]:
    """Cache a tile's vision embedding; batch broad families, then targeted variants.

    With prompt_plan=None, preserve the original flat scan for A/B evaluation.
    Each category is a separate prompt query *in a batch*, not a joint
    multiclass SAM classification. Broad exemplar subtypes are never trusted.
    """
    if tile_size <= overlap or svg_batch < 1:
        raise ValueError('tile_size must exceed overlap; svg_batch must be positive')
    if fallback_every_tiles < 0 or not 0 <= fine_match_iou <= 1:
        raise ValueError('invalid hierarchical SAM fallback / geometry thresholds')
    if coarse_family_limit < 0 or fine_family_limit < 0 or max_detections_per_tile < 0 or max_box_aspect < 1.0:
        raise ValueError('invalid SAM precision gate configuration')
    H, W = page_rgb.shape[:2]
    target_width = shared_canvas.target_width if shared_canvas is not None else tile_size
    if shared_canvas is not None and shared_canvas.tile_size != tile_size:
        raise ValueError('shared reference canvas does not match SAM tile size')
    ys = tile_origins(H, tile_size, overlap)
    xs = tile_origins(W, target_width, overlap)
    raw_dets: list[dict[str, Any]] = []
    tile_no = 0

    with torch.inference_mode():
        for y0 in ys:
            for x0 in xs:
                ye = min(H, y0 + tile_size)
                xe = min(W, x0 + target_width)
                sub = page_rgb[y0:ye, x0:xe]
                if sub.size == 0 or sub.min() > 240:
                    continue
                tile_no += 1
                if shared_canvas is None:
                    tile = np.full((tile_size, tile_size, 3), 255, dtype=np.uint8)
                    th, tw = sub.shape[:2]
                    tile[:th, :tw] = sub
                else:
                    tile = shared_canvas.with_target(sub)
                t_inp = processor(images=Image.fromarray(tile), return_tensors='pt')
                # The expensive vision encoder executes ONCE per tile.
                t_ve = model.get_vision_features(pixel_values=t_inp['pixel_values'].to(device, dtype))
                t_fpn_last = t_ve.fpn_hidden_states[-2]
                t_pos_last = t_ve.fpn_position_encoding[-2]

                def query_indices(indices: list[int], stage: str, threshold: float) -> list[dict[str, Any]]:
                    found: list[dict[str, Any]] = []
                    if query_stats is not None:
                        query_stats[f'{stage}_prompts'] += len(indices)
                    def run_batch(subset: list[int]) -> list[dict[str, Any]]:
                        # Local scope releases intermediate activations if CUDA
                        # runs out of memory, allowing a smaller retry.
                        B = len(subset)
                        batch_found: list[dict[str, Any]] = []
                        idx_t = torch.tensor(subset, dtype=torch.long, device=geo_feats.device)
                        if shared_canvas is None:
                            # Experimental legacy off-image prompt transfer.
                            gf = geo_feats.index_select(0, idx_t)
                            gm = geo_masks.index_select(0, idx_t)
                        else:
                            # Native SAM3 visual prompts reference the SAME
                            # image whose features are searched by DETR.
                            ref_boxes = torch.tensor(
                                [shared_canvas.reference_boxes[i] for i in subset],
                                dtype=dtype, device=device,
                            ).unsqueeze(1)
                            geometry = model.geometry_encoder(
                                box_embeddings=ref_boxes,
                                box_mask=torch.ones((B, 1), dtype=torch.bool, device=device),
                                box_labels=torch.ones((B, 1), dtype=torch.long, device=device),
                                img_feats=[
                                    t.expand(B, *t.shape[1:])
                                    for t in t_ve.fpn_hidden_states[:-1]
                                ],
                                img_pos_embeds=[
                                    t.expand(B, *t.shape[1:])
                                    for t in t_ve.fpn_position_encoding[:-1]
                                ],
                            )
                            gf = geometry.last_hidden_state.to(dtype)
                            gm = geometry.attention_mask.bool()
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
                        )[-1].squeeze(-1)
                        pres = dec.presence_logits[-1].squeeze(-1)
                        if pres.ndim == 1:
                            pres = pres.unsqueeze(-1)
                        scores = logits.sigmoid() * pres.sigmoid()
                        if scores.ndim != 2 or scores.shape[0] != B or scores.shape[1] < 1:
                            raise RuntimeError(
                                f'SAM3 score tensor shape mismatch: expected [B,Q] '
                                f'for batch {B}, got {tuple(scores.shape)}'
                            )
                        if family_thresholds:
                            # Apply independent family cutoffs before CPU copy.
                            thrs = torch.tensor([
                                threshold_for_family(
                                    glyphs[i].device_type, stage, threshold,
                                    family_thresholds,
                                )
                                for i in subset
                            ], dtype=scores.dtype, device=scores.device).unsqueeze(-1)
                            hits = torch.nonzero(scores >= thrs, as_tuple=False)
                        else:
                            hits = torch.nonzero(scores >= threshold, as_tuple=False)
                        if hits.numel() == 0:
                            return []

                        offsets = model.detr_decoder.box_head(dec.intermediate_hidden_states)[-1]
                        refs = dec.reference_boxes[-1] if dec.reference_boxes.ndim == 4 else dec.reference_boxes
                        boxes = box_cxcywh_to_xyxy(
                            (inverse_sigmoid(refs) + offsets).sigmoid()
                        ) * float(tile_size)
                        if boxes.ndim != 3 or boxes.shape[:2] != scores.shape or boxes.shape[-1] != 4:
                            raise RuntimeError(
                                f'SAM3 box tensor shape mismatch: expected '
                                f'{tuple(scores.shape)} + [4], got {tuple(boxes.shape)}'
                            )
                        # Exactly one CPU synchronization per batch. Pulling
                        # individual boxes/scores causes hundreds of tiny
                        # cuda syncs on dense electrical sheets.
                        hits_cpu = hits.cpu().tolist()
                        boxes_cpu = boxes.float().cpu().numpy()
                        scores_cpu = scores.float().cpu().numpy()
                        for b_i, q_i in hits_cpu:
                            bx0, by0, bx1, by1 = boxes_cpu[b_i, q_i].tolist()
                            if shared_canvas is not None and (
                                bx0 < 0 or bx1 > target_width or bx0 >= target_width
                            ):
                                # Ref-strip detections aren't blueprint devices.
                                if query_stats is not None:
                                    query_stats['filtered_reference_strip'] += 1
                                continue
                            bw, bh = bx1 - bx0, by1 - by0
                            if bw < min_box_px or bh < min_box_px or bw > max_box_px or bh > max_box_px:
                                continue
                            gx0 = max(0.0, min(float(W), x0 + bx0))
                            gy0 = max(0.0, min(float(H), y0 + by0))
                            gx1 = max(0.0, min(float(W), x0 + bx1))
                            gy1 = max(0.0, min(float(H), y0 + by1))
                            if gx1 - gx0 < min_box_px or gy1 - gy0 < min_box_px:
                                continue
                            glyph = glyphs[subset[b_i]]
                            batch_found.append({
                                'box_px': [gx0, gy0, gx1, gy1],
                                'score': float(scores_cpu[b_i, q_i]),
                                'stage': stage,
                                'visual_group': glyph.visual_group,
                                'exemplar_attributes': glyph.encoded_attributes,
                                'device_type': glyph.device_type,
                                # Coarse stage is a family proposal, not a verified variant.
                                'subtype': glyph.subtype if stage not in ('coarse', 'visual_base') else 'unspecified',
                                'exemplar_subtype': glyph.subtype,
                                'broad_family': glyph.canonical_class,
                                'symbol_id': glyph.symbol_id,
                                'neca_code': glyph.code,
                                'neca_name': glyph.name,
                                'category': glyph.category,
                                'subcategory': glyph.subcategory if stage not in ('coarse', 'visual_base') else 'unspecified',
                            })
                        return batch_found

                    active_batch = svg_batch
                    start_index = 0
                    while start_index < len(indices):
                        subset = indices[start_index:start_index + active_batch]
                        try:
                            found.extend(run_batch(subset))
                        except torch.cuda.OutOfMemoryError:
                            if active_batch <= 1:
                                raise
                            active_batch = max(1, active_batch // 2)
                            if query_stats is not None:
                                query_stats['sam_inference_oom_retries'] += 1
                            torch.cuda.empty_cache()
                            continue
                        start_index += len(subset)
                    return found

                def gate(detections: list[dict[str, Any]], family_limit: int,
                         *, enforce_tile_owner: bool = True,
                         apply_caps: bool = True):
                    return filter_sam_detections(
                        detections, page_rgb, tile_x=x0, tile_y=y0,
                        tile_size=tile_size, overlap=overlap,
                        use_owner=enforce_tile_owner,
                        max_aspect_ratio=max_box_aspect,
                        min_ink_fraction=min_ink_fraction,
                        max_ink_fraction=max_ink_fraction,
                        minimum_box_size=min_box_px,
                        max_per_family=family_limit if apply_caps else 0,
                        max_total=max_detections_per_tile if apply_caps else 0,
                        tile_width=target_width,
                        stats=query_stats,
                    )

                if prompt_plan is None:
                    tile_dets = gate(
                        query_indices(list(range(len(glyphs))), 'flat', score_thr),
                        fine_family_limit,
                    )
                elif prompt_plan.get('mode') == 'grouped':
                    # Exactly one pass over all visually distinct base shapes.
                    # Switch S3/$3/etc are NOT extra queries; component
                    # semantics are attached analytically downstream.
                    candidates = gate(
                        query_indices(list(range(len(glyphs))), 'visual_base', score_thr),
                        fine_family_limit, apply_caps=False,
                    )
                    # Merge repeated physical matches BEFORE a hard quota.
                    # Otherwise duplicate prompts at one symbol can exhaust
                    # the cap and starve independent devices elsewhere.
                    merged = nms_detections(candidates, iou_thr=0.50)
                    tile_dets = gate(merged, fine_family_limit)
                    if query_stats is not None:
                        query_stats['grouped_raw_candidates'] += len(candidates)
                        query_stats['grouped_after_tile_fusion'] += len(merged)
                        query_stats['grouped_visual_proposals'] += len(tile_dets)
                else:
                    coarse = gate(
                        query_indices(prompt_plan['coarse_indices'], 'coarse', coarse_score_thr),
                        coarse_family_limit, enforce_tile_owner=False,
                    )
                    active_types = {d['device_type'] for d in coarse}
                    fallback = bool(fallback_every_tiles and tile_no % fallback_every_tiles == 0)
                    if query_stats is not None:
                        query_stats['tiles'] += 1
                        query_stats['fallback_tiles'] += int(fallback)
                        query_stats['active_family_sum'] += len(active_types)
                    fine_indices = active_fine_indices(
                        prompt_plan, active_types, fallback_full=fallback)
                    fine = query_indices(fine_indices, 'fine', score_thr)
                    if not fallback:
                        fine_matched = [d for d in fine if accepted_fine_match(
                            d, coarse, min_iou=fine_match_iou)]
                        if query_stats is not None:
                            query_stats['filtered_no_coarse_family_match'] += len(fine) - len(fine_matched)
                        fine = fine_matched
                    fine = gate(fine, fine_family_limit)
                    if query_stats is not None:
                        query_stats['coarse_boxes'] += len(coarse)
                        query_stats['fine_boxes'] += len(fine)
                        query_stats['generic_boxes_suppressed'] += 0 if emit_generic else len(coarse)
                    # Crucial precision default: coarse hits route fine queries,
                    # but never become VLM candidates unless explicitly enabled.
                    tile_dets = fine + (coarse if emit_generic else [])
                raw_dets.extend(tile_dets)
    if query_stats is not None:
        query_stats['vision_encoded_tiles'] += tile_no
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
    ap.add_argument('--prompt-allowlist', type=Path, default=None,
                    help='Optional JSON list of reviewed standalone NECA symbol IDs to use as prompts')
    ap.add_argument('--prompt-denylist', type=Path, default=None,
                    help='Optional local JSON list of known noisy / false-positive NECA reference IDs')
    ap.add_argument('--prompts-per-subcategory', type=int, default=0)
    ap.add_argument('--prompts-per-category', type=int, default=0)
    ap.add_argument('--max-prompts', type=int, default=0,
                    help='Catalog budget; default 0 loads all eligible fine exemplars for hierarchical mode. Flat mode: set 96 or 192 for speed')
    ap.add_argument('--native-only', action='store_true',
                    help='Exclude raster-traced NECA symbols; default retains traced Annex A alternatives')
    ap.add_argument('--reference-geometry-batch', type=int, default=4,
                    help='Bounded multi-exemplar geometry encoding on cached reference sheets (VRAM tradeoff)')
    ap.add_argument('--shared-reference-px', type=int, default=24,
                    help='Visual reference glyph pixels on same-image SAM tile (grouped mode)')
    ap.add_argument('--shared-slot-pitch', type=int, default=36,
                    help='Spacing for NECA glyphs on the same-image reference strip')
    ap.add_argument('--reference-render-height', type=int, default=96,
                    help='Height used when rasterizing individual NECA vectors before prompt encoding')
    ap.add_argument('--no-reference-quality', action='store_true',
                    help='Disable cluttered/filled reference glyph rejection for A/B comparison')
    ap.add_argument('--no-core-crop', action='store_true',
                    help='Disable automatic external circuit/schedule label cropping on known NECA device families')
    ap.add_argument('--no-prompt-cache', action='store_true',
                    help='Disable local raster and preencoded-geometry caches for debugging')
    ap.add_argument('--catalog-only', action='store_true',
                    help='Audit selected NECA categories and prompts, then exit without loading a model/GPU')
    ap.add_argument('--visual-audit-out', type=Path, default=None,
                    help='Write JSON classification for every NECA source SVG (including ignored/modifier entries)')
    ap.add_argument('--visual-prototypes-per-group', type=int, default=1,
                    help='Use one clean representative per visibly distinct base silhouette by default')
    ap.add_argument('--allow-missing-visual-groups', action='store_true',
                    help='Opt-in to dropping a distinct core silhouette if all its references fail quality screening')
    ap.add_argument('--no-switch-suffixes', action='store_true',
                    help='Disable fast local suffix templates for S3/S4/SLV and other compound switches')
    ap.add_argument('--switch-suffix-score', type=float, default=0.92,
                    help='Minimum local glyph-template correlation for switch modifiers')
    ap.add_argument('--switch-suffix-margin', type=float, default=0.06,
                    help='Reject similar-scoring ambiguous suffix interpretations')
    ap.add_argument('--scan-mode', choices=['grouped', 'hierarchical', 'flat'], default='grouped',
                    help='Grouped (default) uses supported same-image references; hierarchical/flat are experimental cross-image transfer baselines')
    ap.add_argument('--coarse-per-family', type=int, default=2)
    ap.add_argument('--fine-per-family', type=int, default=12,
                    help='Subtype prompts per active family; 0 = all in catalog')
    ap.add_argument('--coarse-score-thr', type=float, default=0.50,
                    help='Lower proposal threshold for high-recall family gating (not calibrated)')
    ap.add_argument('--fallback-every-tiles', type=int, default=0,
                    help='Full fine scan every N nonblank tiles to recover coarse misses; 0 = disabled, 1 = every tile')
    ap.add_argument('--fine-match-iou', type=float, default=0.10)
    ap.add_argument('--score-thr', type=float, default=0.72,
                    help='Final fine-instance threshold, higher by default to limit VLM false positives')
    ap.add_argument('--family-thresholds', type=Path, default=None,
                    help='Optional local JSON {device_family: {coarse: 0..1, fine: 0..1}} overrides')
    ap.add_argument('--emit-generic', action='store_true',
                    help='Export coarse-only candidates too (high-recall, many false positives)')
    ap.add_argument('--max-box-aspect', type=float, default=6.0)
    ap.add_argument('--min-ink-fraction', type=float, default=0.018)
    ap.add_argument('--max-ink-fraction', type=float, default=0.85)
    ap.add_argument('--coarse-family-limit', type=int, default=50,
                    help='Max raw family proposals per tile before class-gated fine search; 0 disables')
    ap.add_argument('--fine-family-limit', type=int, default=24,
                    help='Max final proposals per family per tile before global NMS; 0 disables')
    ap.add_argument('--max-detections-per-tile', type=int, default=64,
                    help='Hard ceiling on initial proposals per tile after geometric/ink gating; 0 disables')
    ap.add_argument('--svg-dedup-sim', type=float, default=0.90,
                    help='Cosine similarity threshold in SAM3 prompt space to deduplicate near-identical SVG variants (1.0 disables)')
    ap.add_argument('--min-page-pts', type=float, default=900.0)
    ap.add_argument('--limit-pdfs', type=int, default=0)
    ap.add_argument('--max-pages-per-pdf', type=int, default=0,
                    help='0=all; quick weighted smoke can process only N eligible electrical pages per PDF')
    args = ap.parse_args()
    if args.max_pages_per_pdf < 0:
        ap.error('--max-pages-per-pdf must be nonnegative')

    specs, catalog_report = list_neca_prompt_specs(
        args.neca_dir.resolve(), allow_raster_traces=not args.native_only)
    if args.prompt_denylist:
        denied = json.loads(args.prompt_denylist.read_text())
        if not isinstance(denied, list) or not all(isinstance(i, str) for i in denied):
            ap.error('--prompt-denylist must be a JSON list of NECA symbol IDs')
        denied_ids = set(denied)
        unknown_ids = denied_ids - {g.symbol_id for g in specs}
        if unknown_ids:
            ap.error(f'unknown denied NECA symbols: {sorted(unknown_ids)[:12]}')
        specs = [g for g in specs if g.symbol_id not in denied_ids]
        catalog_report['manually_excluded_noisy_references'] = sorted(denied_ids)
    allowlist = None
    if args.prompt_allowlist:
        ids = json.loads(args.prompt_allowlist.read_text())
        if not isinstance(ids, list) or not ids or not all(isinstance(i, str) for i in ids):
            ap.error('--prompt-allowlist must be a nonempty JSON array of NECA IDs')
        allowlist = set(ids)
    try:
        if args.scan_mode == 'grouped':
            grouped_bank = [p for p in specs if
                            allowlist is None or p.symbol_id in allowlist]
            if allowlist is not None and len(grouped_bank) != len(allowlist):
                ap.error('grouped allowlist includes unknown or excluded NECA IDs')
            # Load one backup reference per group where available.
            # Only the best surviving raster is queried at inference time.
            selected = choose_visual_base_prototypes(
                grouped_bank, per_group=max(2, args.visual_prototypes_per_group))
        else:
            selected = choose_neca_prompts(
                specs, per_subcategory=args.prompts_per_subcategory,
                per_category=args.prompts_per_category, total=args.max_prompts,
                allowlist=allowlist)
    except ValueError as exc:
        ap.error(str(exc))
    if not selected:
        ap.error('selected reference set empty (grouped mode excludes modifier/composite-only IDs)')
    if args.visual_audit_out is not None:
        all_entries = json.loads((args.neca_dir / 'symbols.json').read_text())['symbols']
        args.visual_audit_out.parent.mkdir(parents=True, exist_ok=True)
        args.visual_audit_out.write_text(json.dumps(
            audit_neca_visual_groups(all_entries), indent=2,
        ))
    chosen_counts = dict(Counter(g.category for g in selected))
    catalog_summary = {
        **catalog_report,
        'selected': len(selected),
        'selected_by_category': chosen_counts,
        'selected_subcategories': len({(g.category, g.subcategory) for g in selected}),
        'selected_visual_groups': len({g.visual_group for g in selected}),
        'selected_roles': dict(Counter(g.prompt_role for g in selected)),
        'prompt_budgets': {
            'per_subcategory': args.prompts_per_subcategory,
            'per_category': args.prompts_per_category,
            'total': args.max_prompts,
        },
        'selected_ids': [g.symbol_id for g in selected],
    }
    print(json.dumps(catalog_summary, indent=2), flush=True)
    if args.catalog_only:
        return
    if not torch.cuda.is_available():
        raise SystemExit('CUDA GPU not available; use --catalog-only for CPU preflight')

    dtype = torch.bfloat16 if torch.cuda.is_bf16_supported() else torch.float16
    print(f'Loading {args.model_id} in {dtype} on {torch.cuda.get_device_name(0)}...', flush=True)
    t0 = time.time()
    model = Sam3Model.from_pretrained(args.model_id, dtype=dtype).to('cuda').eval()
    processor = Sam3Processor.from_pretrained(args.model_id)
    expected_image_size = getattr(processor.image_processor, 'size', {})
    if isinstance(expected_image_size, dict):
        eh = expected_image_size.get('height')
        ew = expected_image_size.get('width')
        if eh is not None and ew is not None and (
                int(eh) != args.tile_size or int(ew) != args.tile_size):
            ap.error(
                f'--tile-size={args.tile_size} does not match the SAM image '
                f'processor input {ew}x{eh}; this runner requires identical '
                'square input and model coordinates.'
            )
    resolved_model_revision = resolve_model_weight_identity(
        args.model_id, getattr(model.config, '_commit_hash', None),
    )
    if resolved_model_revision is None:
        ap.error(
            'cannot establish model weight revision; refusing stale resume '
            'and reference embeddings. Load an immutable Hugging Face model '
            'revision or a local model directory containing actual '
            '.safetensors/.bin checkpoint shards.'
        )
    print(f'Model loaded in {time.time() - t0:.1f}s (VRAM={torch.cuda.memory_allocated()/1e9:.2f} GB)', flush=True)

    cache_root = args.out.resolve() / '_sam_prompt_cache'
    reference_stats: Counter = Counter()
    glyphs = load_neca_sam_prompts(
        selected, args.neca_dir.resolve(),
        render_height=args.reference_render_height,
        cache_dir=None if args.no_prompt_cache else cache_root / 'raster',
        check_quality=not args.no_reference_quality,
        trim_external_labels=not args.no_core_crop,
        reference_stats=reference_stats,
    )
    if not glyphs:
        ap.error('no usable NECA prompt SVGs after reference quality screening')
    if args.scan_mode == 'grouped':
        accepted_count = len(glyphs)
        glyphs = choose_visual_base_prototypes(
            glyphs, per_group=args.visual_prototypes_per_group)
        print(f'Grouped references: {accepted_count} quality-accepted -> '
              f'{len(glyphs)} model queries (one per silhouette by default)',
              flush=True)
    print(f'NECA reference preflight: {len(glyphs)}/{len(selected)} usable; '
          f'details={dict(reference_stats)}', flush=True)
    t_enc = time.time()
    shared_canvas = None
    if args.scan_mode == 'grouped':
        try:
            shared_canvas = build_shared_canvas(
                glyphs, tile_size=args.tile_size,
                slot_pitch=args.shared_slot_pitch,
                reference_px=args.shared_reference_px,
            )
        except ValueError as exc:
            ap.error(f'unsupported shared NECA layout: {exc}')
        # Supported SAM3 geometry prompts must refer to THIS tile's FPN,
        # not cached prompt tokens from a different reference image.
        visual_tokens = processor(text='visual', return_tensors='pt')
        with torch.inference_mode():
            blank_tf = model.get_text_features(
                input_ids=visual_tokens['input_ids'].to('cuda'),
                attention_mask=visual_tokens['attention_mask'].to('cuda'),
                return_dict=True,
            ).pooler_output.to(dtype)
        blank_tm = visual_tokens['attention_mask'].to('cuda').bool()
        geo_feats = torch.empty((0, 2, blank_tf.shape[-1]), device='cuda', dtype=dtype)
        geo_masks = torch.empty((0, 2), device='cuda', dtype=torch.bool)
        print(f'SAM same-image exemplar canvas: target_width={shared_canvas.target_width}; '
              f'visual references={len(shared_canvas.reference_boxes)}; '
              f'precomputed cross-image embeddings=0', flush=True)
    else:
        # Legacy experimental separate-image visual embeddings: not an
        # officially supported cross-image exemplar feature.
        feature_cache = None
        cached_features = None
        if not args.no_prompt_cache and resolved_model_revision is not None:
            feature_cache = prompt_feature_cache_path(
                cache_root / 'encoded', glyphs,
                model_id=args.model_id,
                model_revision=resolved_model_revision,
                model_config=model.config.to_json_string(),
                dtype=dtype, target_px=44,
            )
            cached_features = load_prompt_features(
                feature_cache, glyph_count=len(glyphs), device='cuda', dtype=dtype,
            )
        if cached_features is not None:
            print('Loaded experimental cross-image geometry embeddings', flush=True)
            geo_feats, geo_masks, blank_tf, blank_tm = cached_features
        else:
            geo_feats, geo_masks, blank_tf, blank_tm = preencode_svg_prompts(
                model, processor, glyphs, device='cuda', dtype=dtype,
                target_px=44, geometry_batch=args.reference_geometry_batch,
            )
            if feature_cache is not None:
                save_prompt_features(
                    feature_cache, (geo_feats, geo_masks, blank_tf, blank_tm),
                )
                print('Cached experimental cross-image geometry embeddings', flush=True)
    if args.scan_mode != 'grouped' and args.svg_dedup_sim < 1.0:
        # Global cosine dedup can silently erase an entire visual group;
        # grouped mode already deduplicates by *core silhouette* metadata.
        orig_n = len(glyphs)
        glyphs, geo_feats, geo_masks = deduplicate_svg_prompts(
            glyphs, geo_feats, geo_masks, sim_threshold=args.svg_dedup_sim,
        )
        dedup_counts = dict(Counter(g.category for g in glyphs))
        print(
            f'Deduplicated SVGs in SAM3 prompt space (sim>={args.svg_dedup_sim}): '
            f'{orig_n} -> {len(glyphs)} distinct prototypes ({dedup_counts})',
            flush=True,
        )
    print(f'SAM visual prompt setup completed in {time.time() - t_enc:.1f}s '
          f'(VRAM={torch.cuda.memory_allocated()/1e9:.2f} GB)', flush=True)
    if args.scan_mode == 'grouped':
        missing_visual_groups = sorted(
            {x.visual_group for x in selected} - {x.visual_group for x in glyphs}
        )
        print(f'SAM grouped coverage: {len({g.visual_group for g in glyphs})} '
              f'active visual groups; {len(missing_visual_groups)} missing after raster screening',
              flush=True)
        if missing_visual_groups:
            print('Dropped visual groups: '+', '.join(missing_visual_groups), flush=True)
            if not args.allow_missing_visual_groups:
                ap.error(
                    f'{len(missing_visual_groups)} of '
                    f'{len({x.visual_group for x in selected})} distinct base groups '
                    'failed reference screening; adjust the reference source, or '
                    'use --allow-missing-visual-groups for experimental partial scans'
                )
    prompt_plan = None
    if args.scan_mode == 'grouped':
        prompt_plan = {'mode': 'grouped',
                       'families': sorted({g.device_type for g in glyphs})}
    if args.scan_mode == 'hierarchical':
        try:
            prompt_plan = build_hierarchical_prompt_plan(
                glyphs, coarse_per_family=args.coarse_per_family,
                fine_per_family=args.fine_per_family)
        except ValueError as exc:
            ap.error(str(exc))
        print(f'SAM hierarchical: {len(prompt_plan["families"])} device families; '
              f'{prompt_plan["coarse_count"]} coarse queries per tile; '
              f'{prompt_plan["fine_catalog_count"]} subtype prompts available for gated pass', flush=True)

    try:
        family_thresholds = load_family_thresholds(
            args.family_thresholds, {g.device_type for g in glyphs},
        )
    except (ValueError, OSError, json.JSONDecodeError) as exc:
        ap.error(f'family threshold file invalid: {exc}')

    switch_suffix_refs = []
    if args.scan_mode == 'grouped' and not args.no_switch_suffixes and any(
            g.device_type == 'switch' for g in glyphs):
        switch_suffix_refs = build_switch_suffix_templates(
            args.neca_dir.resolve(), render_height=128,
            cache_dir=None if args.no_prompt_cache else cache_root / 'suffix')
        print(f'Loaded {len(switch_suffix_refs)} local NECA switch suffix templates',
              flush=True)

    # Prompt/model changes must invalidate resume cache; otherwise revised
    # detectors silently read old labels and appear to run impossibly fast.
    # Only inference-affecting inputs belong in this fingerprint.
    # Cache statistics differ between first run and warm runs.
    reference_digest = hashlib.sha256()
    for ref in glyphs:
        reference_digest.update(ref.symbol_id.encode('utf-8'))
        reference_digest.update(hashlib.sha256(ref.alpha.tobytes()).digest())
    run_config = {
        'model_id': args.model_id,
        'inference_dtype': str(dtype),
        'model_resolved_revision': resolved_model_revision,
        'model_config_hash': hashlib.sha256(
            model.config.to_json_string().encode('utf-8')
        ).hexdigest(),
        'reference_ink_sha256': reference_digest.hexdigest(),
        'prompt_ids': [g.symbol_id for g in glyphs],
        'render_dpi': args.dpi,
        'max_pixels': args.max_pixels,
        'max_pages_per_pdf': args.max_pages_per_pdf,
        'tile_size': args.tile_size,
        'tile_overlap': args.overlap,
        'score_threshold': args.score_thr,
        'family_thresholds': family_thresholds,
        'prompt_dedup_similarity': args.svg_dedup_sim,
        'min_page_pts': args.min_page_pts,
        'prompt_budgets': catalog_summary['prompt_budgets'],
        'native_only': args.native_only,
        'reference_render_height': args.reference_render_height,
        'reference_geometry_batch': args.reference_geometry_batch,
        'reference_quality': not args.no_reference_quality,
        'trim_external_reference_labels': not args.no_core_crop,
        'prompt_feature_cache_schema': 2,
        'scan_mode': args.scan_mode,
        'visual_prototypes_per_group': args.visual_prototypes_per_group,
        'shared_image_prompting': shared_canvas is not None,
        'shared_reference_px': args.shared_reference_px if shared_canvas else None,
        'shared_slot_pitch': args.shared_slot_pitch if shared_canvas else None,
        'shared_target_width': shared_canvas.target_width if shared_canvas else None,
        'allow_missing_visual_groups': args.allow_missing_visual_groups,
        'switch_suffix_refs': [t.symbol_id for t in switch_suffix_refs],
        'switch_suffix_template_content_sha256': hashlib.sha256(
            b''.join(hashlib.sha256(t.ink.tobytes()).digest()
                     for t in switch_suffix_refs)
        ).hexdigest() if switch_suffix_refs else None,
        'switch_suffix_enabled': not args.no_switch_suffixes,
        'switch_suffix_score': args.switch_suffix_score,
        'switch_suffix_margin': args.switch_suffix_margin,
        'active_visual_groups': [g.visual_group for g in glyphs],
        'coarse_per_family': args.coarse_per_family,
        'fine_per_family': args.fine_per_family,
        'coarse_score_thr': args.coarse_score_thr,
        'fallback_every_tiles': args.fallback_every_tiles,
        'fine_match_iou': args.fine_match_iou,
        'emit_generic': args.emit_generic,
        'max_box_aspect': args.max_box_aspect,
        'min_ink_fraction': args.min_ink_fraction,
        'max_ink_fraction': args.max_ink_fraction,
        'coarse_family_limit': args.coarse_family_limit,
        'fine_family_limit': args.fine_family_limit,
        'max_detections_per_tile': args.max_detections_per_tile,
        'sam_precision_version': 2,
        'sam_hierarchy_version': 1,
    }
    run_config_hash = hashlib.sha256(
        json.dumps(run_config, sort_keys=True).encode('utf-8')
    ).hexdigest()[:12]
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
        source_stat = pdf_path.stat()
        source_fingerprint = f'{rel}/{source_stat.st_size}/{source_stat.st_mtime_ns}'
        short_hash = hashlib.sha256(source_fingerprint.encode('utf-8')).hexdigest()[:8]
        safe_stem = re.sub(r'[^a-zA-Z0-9._-]+', '_', pdf_path.stem)[:80]
        out_json = out_dir / f'{safe_stem}_{short_hash}_{run_config_hash}_SAM3.json'
        if completed_sam_output(
                out_json, run_config_hash=run_config_hash,
                source_pdf_relative=rel):
            continue
        if out_json.exists():
            print(f'[{idx}/{len(pdfs)}] Recomputing incomplete/stale output '
                  f'{out_json.name}', flush=True)

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
        pdf_query_stats: Counter = Counter()

        skipped_reasons: Counter = Counter()
        try:
            for p0 in range(n_pages):
                if args.max_pages_per_pdf > 0 and scanned_here >= args.max_pages_per_pdf:
                    break
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
                    coarse_score_thr=args.coarse_score_thr,
                    prompt_plan=prompt_plan,
                    shared_canvas=shared_canvas,
                    fallback_every_tiles=args.fallback_every_tiles,
                    fine_match_iou=args.fine_match_iou,
                    emit_generic=args.emit_generic,
                    max_box_aspect=args.max_box_aspect,
                    min_ink_fraction=args.min_ink_fraction,
                    max_ink_fraction=args.max_ink_fraction,
                    coarse_family_limit=args.coarse_family_limit,
                    fine_family_limit=args.fine_family_limit,
                    max_detections_per_tile=args.max_detections_per_tile,
                    family_thresholds=family_thresholds,
                    query_stats=pdf_query_stats,
                    device='cuda', dtype=dtype,
                )
                if switch_suffix_refs and dets:
                    marks = search_switch_suffixes(
                        rgb, dets, switch_suffix_refs,
                        min_score=args.switch_suffix_score,
                        min_margin=args.switch_suffix_margin,
                    )
                    dets, unmatched_marks = attach_device_modifiers(dets, marks)
                    pdf_query_stats['switch_suffix_marks_found'] += len(marks)
                    pdf_query_stats['switch_suffix_marks_unmatched'] += len(unmatched_marks)
                    pdf_query_stats['switch_suffix_marks_attached'] += (
                        len(marks) - len(unmatched_marks))
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
                        'category': d['category'],
                        'subcategory': d['subcategory'],
                        'score': round(d['score'], 4),
                        'alternative_labels': d.get('alternative_labels', []),
                        'same_region_prompt_hits': d.get('same_region_prompt_hits', 1),
                        'needs_class_review': d.get('needs_class_review', False),
                        'detection_stage': d.get('stage', 'flat'),
                        'exemplar_subtype': d.get('exemplar_subtype'),
                        'visual_group': d.get('visual_group'),
                        'analytic_attributes': d.get('analytic_attributes', {}),
                        'modifier_evidence': d.get('modifier_evidence', []),
                        'modifier_conflicts': d.get('modifier_conflicts', []),
                        'verified_ground_truth': False,
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
            'annotations_are_exhaustive': False,
            'verified_ground_truth': False,
            'prompt_selection': ('allowlisted' if args.prompt_allowlist
                                 else 'visual_groups' if args.scan_mode == 'grouped'
                                 else 'hierarchical_neca'),
            'prompt_catalog': {
                **catalog_summary,
                'active_after_render_and_dedup': len(glyphs),
                'reference_quality_stats': dict(reference_stats),
            },
            'run_config': run_config,
            'run_config_hash': run_config_hash,
            'query_stats': dict(pdf_query_stats),
            'device_family_vocabulary': prompt_plan['families'] if prompt_plan else sorted({g.device_type for g in glyphs}),
            'coarse_fine_policy': ('distinct_base_visual_groups_with_analytic_modifiers'
                if args.scan_mode == 'grouped' else
                'family_proposal_then_gated_variant_evidence'
                if prompt_plan else 'all_prototypes'),
            'prompt_taxonomy': {
                dtype: sorted({g.subtype for g in specs if g.device_type == dtype})
                for dtype in sorted({g.device_type for g in specs})
            },
            'prompt_category_taxonomy': {
                category: sorted({g.subcategory for g in specs if g.category == category})
                for category in sorted({g.category for g in specs})
            },
            'hierarchy': 'NECA: category > subcategory; separate from detector device_type > subtype',
            'coordinate_system': {
                'origin': 'bottom_left',
                'units': 'PDF_points',
                'reference': 'display_oriented_page_after_applying_pdf_rotation',
                'symbol_bbox_format': '[x_min,y_min,x_max,y_max]',
            },
            'total_pdf_pages': n_pages,
            'pages_scanned': scanned_here,
            'page_limit_applied': bool(args.max_pages_per_pdf and scanned_here >= args.max_pages_per_pdf),
            'skip_reasons': dict(skipped_reasons),
            'pages_with_devices': len(pages_meta),
            'total_devices': len(pdf_devices),
            'by_class': dict(cls_counts),
            'pages': pages_meta,
            'devices': pdf_devices,
        }
        atomic_write_json(out_json, payload)
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
