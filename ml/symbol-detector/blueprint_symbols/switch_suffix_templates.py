"""Match small NECA switch suffix glyphs near SAM's S/$ base detections.

High-precision local template matching avoids querying every full compound
switch variant (S3, S4, SLV, SK, ST, ...). Multiple disjoint suffixes may
attach to the same switch. Symbols are only candidate attributes; no new boxes.
"""
from __future__ import annotations
from dataclasses import dataclass
from pathlib import Path
import hashlib
import os

import cv2
import numpy as np

from blueprint_symbols.neca_visual_groups import SWITCH_SUFFIX_ATTRIBUTES


@dataclass(frozen=True)
class SuffixTemplate:
    symbol_id: str
    notation: str
    attribute: str
    value: str
    ink: np.ndarray
    base_height: int
    base_width: int


def split_switch_suffix(alpha: np.ndarray) -> tuple[np.ndarray, int, int] | None:
    """Extract components to the right of the dominant S/$ glyph."""
    if alpha is None or alpha.ndim != 2:
        return None
    fg = np.ascontiguousarray((alpha > 64).astype(np.uint8))
    n, _, stats, _ = cv2.connectedComponentsWithStats(fg, 8)
    if n < 3:
        return None
    significant = [(i, stats[i]) for i in range(1, n)
                   if stats[i, cv2.CC_STAT_AREA] >= 3]
    if len(significant) < 2:
        return None
    leftmost = min(significant, key=lambda x: x[1][cv2.CC_STAT_LEFT])[1]
    candidates = [stat for _, stat in significant
                  if stat[cv2.CC_STAT_LEFT] <= leftmost[cv2.CC_STAT_LEFT] + 2]
    base = max(candidates, key=lambda x: x[cv2.CC_STAT_AREA])
    boundary = int(base[cv2.CC_STAT_LEFT] + base[cv2.CC_STAT_WIDTH])
    if boundary + 1 >= alpha.shape[1]:
        return None
    mark = alpha[:, boundary + 1:]
    ys, xs = np.where(mark > 45)
    if len(xs) < 6:
        return None
    crop = np.ascontiguousarray(mark[ys.min():ys.max()+1, xs.min():xs.max()+1])
    return crop, int(base[cv2.CC_STAT_HEIGHT]), int(base[cv2.CC_STAT_WIDTH])


def build_switch_suffix_templates(
    root: Path, *, render_height: int = 128,
    cache_dir: Path | None = None,
) -> list[SuffixTemplate]:
    """Read the user-provided local NECA SVGs (not a hosted image service)."""
    from blueprint_symbols.neca_svg_compositor import _rasterize_svg_alpha
    refs = []
    if cache_dir is not None:
        cache_dir = Path(cache_dir)
        cache_dir.mkdir(parents=True, exist_ok=True)
    for code, (key, value) in SWITCH_SUFFIX_ATTRIBUTES.items():
        for notation in ('letter_s', 'dollar'):
            sid = f'NECA_4_{code}__{notation}'
            path = Path(root) / 'svg' / f'{sid}.svg'
            if not path.is_file():
                continue
            cached = None
            if cache_dir is not None:
                digest = hashlib.sha256(
                    b'neca-suffix-v1/' + str(render_height).encode() +
                    b'/' + path.read_bytes()
                ).hexdigest()
                cached = cache_dir / f'{digest}.png'
            alpha = cv2.imread(str(cached), cv2.IMREAD_GRAYSCALE) if (
                cached is not None and cached.is_file()) else None
            if alpha is None:
                alpha = _rasterize_svg_alpha(path, render_height=render_height)
                if alpha is not None and cached is not None:
                    temp = cached.with_name(f'{cached.stem}.{os.getpid()}.tmp.png')
                    if cv2.imwrite(str(temp), alpha):
                        temp.replace(cached)
            split = split_switch_suffix(alpha)
            if split is None:
                continue
            ink, bh, bw = split
            refs.append(SuffixTemplate(
                sid, notation, key, value, ink, bh, bw))
    return refs


def search_switch_suffixes(
    page_rgb: np.ndarray, base_detections: list[dict],
    refs: list[SuffixTemplate], *, min_score: float = .92,
    min_margin: float = .06,
) -> list[dict]:
    """Return visually supported local modifier candidates, never devices."""
    if not 0 < min_score <= 1 or not 0 <= min_margin <= 1:
        raise ValueError('invalid suffix matching thresholds')
    gray = cv2.cvtColor(page_rgb, cv2.COLOR_RGB2GRAY) if page_rgb.ndim == 3 else page_rgb
    h, w = gray.shape
    marks = []
    for det in base_detections:
        if det.get('device_type') != 'switch':
            continue
        visual = str(det.get('visual_group', ''))
        if visual not in ('switch.base_letter_s', 'switch.base_dollar'):
            continue
        notation = 'letter_s' if visual.endswith('letter_s') else 'dollar'
        x0, y0, x1, y1 = map(float, det['box_px'])
        bw, bh = x1 - x0, y1 - y0
        if min(bw, bh) < 4:
            continue
        xa, xb = max(0, int(x0 + bw*.72)), min(w, int(x1 + bw*2.5))
        ya, yb = max(0, int(y0 + bh*.38)), min(h, int(y1 + bh*1.1))
        if xb-xa < 4 or yb-ya < 4:
            continue
        region = 255 - gray[ya:yb, xa:xb]
        best = []
        for ref in refs:
            if ref.notation != notation:
                continue
            scale = bh / max(1, ref.base_height)
            for factor in (.85, 1., 1.15):
                tw = max(2, int(round(ref.ink.shape[1] * scale * factor)))
                th = max(2, int(round(ref.ink.shape[0] * scale * factor)))
                if th > region.shape[0] or tw > region.shape[1]:
                    continue
                resized = cv2.resize(ref.ink, (tw, th), interpolation=cv2.INTER_AREA)
                if np.std(resized) < 5:
                    continue
                response = cv2.matchTemplate(region, resized, cv2.TM_CCOEFF_NORMED)
                _, value, _, point = cv2.minMaxLoc(response)
                if value >= min_score:
                    best.append((float(value), ref, point, tw, th))
        best.sort(key=lambda x: -x[0])
        selected = []
        for hit in best:
            score, ref, (xx, yy), ww, hh = hit
            same_box = []
            for prev in selected:
                _, _, (px, py), pw, ph = prev
                iw = max(0, min(xx+ww, px+pw) - max(xx, px))
                ih = max(0, min(yy+hh, py+ph) - max(yy, py))
                coverage = iw*ih / max(1, min(ww*hh, pw*ph))
                if coverage > .30:
                    same_box.append(prev)
            if same_box:
                # If two meanings fit the exact same ink equally well,
                # neither is reliable enough for automatic attachment.
                if any(ref.value != old[1].value and
                       abs(score-old[0]) < min_margin for old in same_box):
                    for old in same_box:
                        selected.remove(old)
                continue
            selected.append(hit)
        for score, ref, (xx, yy), ww, hh in selected[:4]:
            marks.append({
                'box_px': [float(xa+xx), float(ya+yy),
                           float(xa+xx+ww), float(ya+yy+hh)],
                'target_family': 'switch', 'attribute': ref.attribute,
                'value': ref.value, 'score': score,
                'source': 'neca_suffix_template',
                'neca_symbol_id': ref.symbol_id,
            })
    return marks
