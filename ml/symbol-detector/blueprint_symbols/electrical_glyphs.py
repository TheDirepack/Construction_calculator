"""Procedurally drawn electrical plan symbols (RGBA, black ink on transparent).

None of the downloaded labeled datasets contain electrical devices, so these
glyphs bootstrap the electrical classes. They follow the common US plan
conventions (duplex receptacle = circle with two parallel strokes, switch =
"S" with a subscript, and so on), drawn from scratch with varied stroke
width, proportions, lead lines and lettering so the detector does not latch
onto one drafting style.

They are a stand-in, not a replacement for real sheets: validation and test
numbers on composited glyphs say nothing about real drawings. Keep a real,
hand-checked electrical test set separate (see remote/README.md).

Each glyph returns (rgba, anchors_normalized) where anchors are in [0, 1]
glyph coordinates: `visual_center` always; `wall_attachment` when the symbol
has a lead line to a wall.
"""
from __future__ import annotations

import math
from typing import Callable

import cv2
import numpy as np

Glyph = tuple[np.ndarray, dict[str, tuple[float, float]]]
FONTS = (cv2.FONT_HERSHEY_SIMPLEX, cv2.FONT_HERSHEY_PLAIN, cv2.FONT_HERSHEY_DUPLEX,
         cv2.FONT_HERSHEY_COMPLEX_SMALL)


class _Canvas:
    """Draw at 4x then downsample, so thin strokes stay anti-aliased."""

    SS = 4

    def __init__(self, w: int, h: int, rng: np.random.Generator):
        self.w, self.h, self.rng = w, h, rng
        self.ink = np.zeros((h * self.SS, w * self.SS), np.uint8)
        self.t = max(1, int(round(rng.uniform(1.0, 2.6) * self.SS)))

    def p(self, x: float, y: float) -> tuple[int, int]:
        return int(round(x * self.SS)), int(round(y * self.SS))

    def line(self, a, b, t=None):
        cv2.line(self.ink, self.p(*a), self.p(*b), 255, t or self.t, cv2.LINE_AA)

    def circle(self, c, r, fill=False, t=None):
        cv2.circle(self.ink, self.p(*c), int(round(r * self.SS)), 255,
                   -1 if fill else (t or self.t), cv2.LINE_AA)

    def rect(self, a, b, fill=False):
        cv2.rectangle(self.ink, self.p(*a), self.p(*b), 255, -1 if fill else self.t, cv2.LINE_AA)

    def poly(self, pts, fill=False):
        arr = np.array([self.p(*q) for q in pts], np.int32)
        if fill:
            cv2.fillPoly(self.ink, [arr], 255, cv2.LINE_AA)
        else:
            cv2.polylines(self.ink, [arr], True, 255, self.t, cv2.LINE_AA)

    def text(self, s: str, center, height: float):
        font = FONTS[int(self.rng.integers(len(FONTS)))]
        thick = max(1, int(round(self.t * self.rng.uniform(.6, 1.1))))
        (tw, th), _ = cv2.getTextSize(s, font, 1.0, thick)
        scale = height * self.SS / max(th, 1)
        (tw, th), _ = cv2.getTextSize(s, font, scale, thick)
        cx, cy = self.p(*center)
        cv2.putText(self.ink, s, (cx - tw // 2, cy + th // 2), font, scale, 255, thick, cv2.LINE_AA)

    def done(self, anchors: dict) -> Glyph:
        small = cv2.resize(self.ink, (self.w, self.h), interpolation=cv2.INTER_AREA)
        ys, xs = np.nonzero(small > 8)
        if len(xs) == 0:
            raise ValueError('empty glyph')
        x0, x1, y0, y1 = xs.min(), xs.max() + 1, ys.min(), ys.max() + 1
        crop = small[y0:y1, x0:x1]
        rgba = np.zeros((*crop.shape, 4), np.uint8)
        ink = int(self.rng.integers(0, 60))
        rgba[:, :, :3] = ink
        rgba[:, :, 3] = crop
        cw, ch = x1 - x0, y1 - y0
        moved = {k: ((u * self.w - x0) / cw, (v * self.h - y0) / ch) for k, (u, v) in anchors.items()}
        return rgba, moved


def _lead(c: _Canvas, cx, cy, r) -> tuple[float, float] | None:
    """Optional short lead from the device to the wall (below the symbol)."""
    if c.rng.random() < .55:
        end = cy + r + c.rng.uniform(.25, .6) * r
        c.line((cx, cy + r), (cx, end))
        return cx / c.w, end / c.h
    return None


def receptacle(rng: np.random.Generator) -> tuple[str, Glyph]:
    variant = str(rng.choice(['duplex', 'duplex', 'duplex', 'single', 'quad', 'gfci',
                              'weatherproof', 'special', 'floor', 'split_wired']))
    r = rng.uniform(9, 13)
    c = _Canvas(96, 64, rng)
    cx, cy = 32, 26
    tick = r * rng.uniform(1.35, 1.8)
    if variant == 'floor':
        c.rect((cx - r * 1.5, cy - r * 1.5), (cx + r * 1.5, cy + r * 1.5))
    if variant == 'split_wired':
        c.circle((cx, cy), r)
        c.poly([(cx - r * .7, cy - r * .7), (cx + r * .7, cy - r * .7), (cx, cy + r * .1)], fill=True)
    elif variant == 'special':
        c.circle((cx, cy), r)
        c.poly([(cx - r * .55, cy + r * .4), (cx + r * .55, cy + r * .4), (cx, cy - r * .6)], fill=True)
    else:
        c.circle((cx, cy), r)
    strokes = {'single': 1, 'quad': 2}.get(variant, 2)
    gap = r * rng.uniform(.28, .4)
    offsets = [0.0] if strokes == 1 else [-gap, gap]
    if variant not in ('special', 'split_wired'):
        for o in offsets:
            c.line((cx - tick, cy + o), (cx + tick, cy + o))
    if variant == 'quad':
        for o in offsets:
            c.line((cx + o, cy - tick), (cx + o, cy + tick))
    label = {'gfci': str(rng.choice(['GFI', 'GFCI', 'GF'])), 'weatherproof': 'WP'}.get(variant)
    if label:
        c.text(label, (cx + r + 9 + 3 * len(label), cy - r * .2), rng.uniform(6, 9))
    anchors = {'visual_center': (cx / c.w, cy / c.h)}
    wall = _lead(c, cx, cy, r if variant != 'floor' else r * 1.5)
    if wall:
        anchors['wall_attachment'] = wall
    return variant, c.done(anchors)


def switch(rng: np.random.Generator) -> tuple[str, Glyph]:
    variant = str(rng.choice(['single_pole', 'single_pole', 'three_way', 'four_way',
                              'dimmer', 'low_voltage', 'occupancy']))
    sub = {'three_way': '3', 'four_way': '4', 'dimmer': 'D', 'low_voltage': 'LV', 'occupancy': 'OS'}.get(variant, '')
    c = _Canvas(56, 56, rng)
    h = rng.uniform(13, 18)
    c.text('S', (24, 24), h)
    if sub:
        c.text(sub, (24 + h * .55 + 3 * len(sub), 24 + h * .45), h * .5)
    anchors = {'visual_center': (24 / 56, 24 / 56)}
    if rng.random() < .4:
        c.line((24, 24 + h * .75), (24, 24 + h * 1.2))
        anchors['wall_attachment'] = (24 / 56, (24 + h * 1.2) / 56)
    return variant, c.done(anchors)


def luminaire(rng: np.random.Generator) -> tuple[str, Glyph]:
    variant = str(rng.choice(['troffer_2x4', 'troffer_2x2', 'recessed_can', 'ceiling_outlet',
                              'wall_sconce', 'strip', 'exit_sign']))
    c = _Canvas(96, 96, rng)
    cx, cy = 48, 48
    if variant in ('troffer_2x4', 'troffer_2x2', 'strip'):
        w = rng.uniform(16, 22)
        L = {'troffer_2x4': 2.0, 'troffer_2x2': 1.0, 'strip': 4.0}[variant] * w
        hw = w / 2 if variant != 'strip' else w / 5
        c.rect((cx - L / 2, cy - hw), (cx + L / 2, cy + hw))
        if variant != 'strip' and rng.random() < .7:
            c.line((cx - L / 2, cy - hw), (cx + L / 2, cy + hw))
        if rng.random() < .4:
            c.rect((cx - L / 2, cy - hw), (cx - L / 2 + L * .15, cy + hw), fill=True)
    elif variant == 'recessed_can':
        r = rng.uniform(7, 11)
        c.circle((cx, cy), r)
        if rng.random() < .5:
            c.circle((cx, cy), r * .45, fill=True)
    elif variant == 'ceiling_outlet':
        r = rng.uniform(8, 12)
        c.circle((cx, cy), r)
        d = r * .7
        c.line((cx - d, cy - d), (cx + d, cy + d))
        c.line((cx - d, cy + d), (cx + d, cy - d))
    elif variant == 'wall_sconce':
        r = rng.uniform(8, 12)
        cv2.ellipse(c.ink, c.p(cx, cy), (int(r * 4), int(r * 4)), 0, 180, 360, 255, c.t, cv2.LINE_AA)
        c.line((cx - r, cy), (cx + r, cy))
    else:
        w = rng.uniform(16, 22)
        c.rect((cx - w, cy - w * .45), (cx + w, cy + w * .45))
        c.text('EXIT' if rng.random() < .5 else 'X', (cx, cy), w * .45)
    return variant, c.done({'visual_center': (cx / 96, cy / 96)})


def junction_box(rng: np.random.Generator) -> tuple[str, Glyph]:
    c = _Canvas(48, 48, rng)
    r = rng.uniform(8, 11)
    c.circle((24, 24), r)
    c.text('J', (24, 24), r * 1.1)
    return 'junction_box', c.done({'visual_center': (.5, .5)})


def data_outlet(rng: np.random.Generator) -> tuple[str, Glyph]:
    variant = str(rng.choice(['data', 'voice', 'voice_data', 'tv']))
    c = _Canvas(88, 56, rng)
    s = rng.uniform(10, 14)
    pts = [(28 - s, 22 - s * .6), (28 + s, 22 - s * .6), (28, 22 + s * .9)]
    if rng.random() < .5:
        c.poly(pts)
    else:
        c.poly(pts, fill=True)
    tag = {'voice': 'V', 'voice_data': 'VD', 'tv': 'TV'}.get(variant)
    if tag:
        c.text(tag, (28 + s + 6 + 3 * len(tag), 22), s * .6)
    anchors = {'visual_center': (28 / c.w, 22 / c.h)}
    if rng.random() < .5:
        c.line((28, 22 + s * .9), (28, 22 + s * 1.4))
        anchors['wall_attachment'] = (28 / c.w, (22 + s * 1.4) / c.h)
    return variant, c.done(anchors)


def fire_alarm_device(rng: np.random.Generator) -> tuple[str, Glyph]:
    variant = str(rng.choice(['smoke_detector', 'heat_detector', 'pull_station', 'horn_strobe']))
    c = _Canvas(56, 56, rng)
    r = rng.uniform(9, 12)
    if variant in ('smoke_detector', 'heat_detector'):
        c.circle((28, 28), r)
        c.text('SD' if variant == 'smoke_detector' else 'HD', (28, 28), r * .8)
    else:
        c.rect((28 - r, 28 - r), (28 + r, 28 + r))
        c.text('F' if variant == 'pull_station' else 'HS', (28, 28), r * .9)
    return variant, c.done({'visual_center': (.5, .5)})


def panelboard(rng: np.random.Generator) -> tuple[str, Glyph]:
    variant = str(rng.choice(['surface', 'recessed']))
    c = _Canvas(96, 48, rng)
    L, H = rng.uniform(40, 70), rng.uniform(9, 14)
    a, b = (48 - L / 2, 24 - H / 2), (48 + L / 2, 24 + H / 2)
    c.rect(a, b)
    if variant == 'surface':
        c.poly([a, (b[0], a[1]), (b[0], b[1])], fill=True)
    else:
        for x in np.arange(a[0], b[0], H * .6):
            c.line((x, b[1]), (min(x + H, b[0]), a[1]), t=max(1, c.t // 2))
    return variant, c.done({'visual_center': (.5, .5)})


GLYPHS: dict[str, Callable[[np.random.Generator], tuple[str, Glyph]]] = {
    'receptacle': receptacle,
    'switch': switch,
    'luminaire': luminaire,
    'junction_box': junction_box,
    'data_outlet': data_outlet,
    'fire_alarm_device': fire_alarm_device,
    'panelboard': panelboard,
}

# Receptacles are the priority; weight them up.
DEFAULT_WEIGHTS = {'receptacle': .32, 'switch': .18, 'luminaire': .18, 'junction_box': .07,
                   'data_outlet': .10, 'fire_alarm_device': .08, 'panelboard': .07}


def sample_glyph(rng: np.random.Generator, weights: dict[str, float] = DEFAULT_WEIGHTS
                 ) -> tuple[str, str, np.ndarray, dict]:
    names = list(weights)
    p = np.array([weights[n] for n in names], np.float64)
    family = names[int(rng.choice(len(names), p=p / p.sum()))]
    variant, (rgba, anchors) = GLYPHS[family](rng)
    return family, variant, rgba, anchors


def rotate_glyph(rgba: np.ndarray, anchors: dict, degrees: float, scale: float) -> tuple[np.ndarray, dict]:
    """Rotate/scale about the centre with an expanded canvas; anchors follow."""
    h, w = rgba.shape[:2]
    M = cv2.getRotationMatrix2D((w / 2, h / 2), degrees, scale)
    cos, sin = abs(M[0, 0]), abs(M[0, 1])
    nw, nh = int(math.ceil(h * sin + w * cos)) + 2, int(math.ceil(h * cos + w * sin)) + 2
    M[0, 2] += nw / 2 - w / 2
    M[1, 2] += nh / 2 - h / 2
    out = cv2.warpAffine(rgba, M, (nw, nh), flags=cv2.INTER_LINEAR,
                         borderMode=cv2.BORDER_CONSTANT, borderValue=(0, 0, 0, 0))
    moved = {}
    for k, (u, v) in anchors.items():
        x, y = M @ np.array([u * w, v * h, 1.0])
        moved[k] = (float(x), float(y))      # pixel coords in the rotated canvas
    return out, moved
