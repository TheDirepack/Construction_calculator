"""Deterministic, multi-style CAD/blueprint symbol generator for 100% catalog coverage.

Generates crisp 4x-supersampled grayscale symbol crops (dark ink 0..35 on white 255
background, <= 192 px long side) for every `(symbol_family, symbol_variant)` device in
``annotator/electrical_device_catalog.json``, writing a standard crop library
(``crops/<id>.png``, ``crops.json``, ``review.json``) loadable by
``blueprint_symbols.symbol_library.legend_symbol_glyphs`` plus paginated category
contact sheets (``synth_<cat_idx>_<slug>.png``) for visual review.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
from pathlib import Path
from typing import Callable

import cv2
import numpy as np

GENERATOR_BY = "synthetic_generator_v1"
MAX_SIDE_PX = 192
PAD_PX = 2
FONTS = (
    cv2.FONT_HERSHEY_SIMPLEX,
    cv2.FONT_HERSHEY_DUPLEX,
    cv2.FONT_HERSHEY_COMPLEX_SMALL,
)
SANS_FONTS = (
    cv2.FONT_HERSHEY_SIMPLEX,
    cv2.FONT_HERSHEY_DUPLEX,
)

# Base luminaire variants that also have matching `<base>_emergency` catalog entries.
BASE_LUMINAIRE_VARIANTS = frozenset({
    "chandelier",
    "downlight",
    "high_bay",
    "linear",
    "low_bay",
    "pendant",
    "pole_floodlight",
    "pole_luminaire",
    "recessed_downlight",
    "spotlight",
    "strip_luminaire",
    "surface_mounted",
    "track_light",
    "troffer_1x4",
    "troffer_2x2",
    "troffer_2x4",
    "vanity",
    "wall_mounted",
    "wall_sconce",
    "wallpack",
})


def _default_repo_root() -> Path:
    here = Path(__file__).resolve()
    for parent in here.parents:
        if (parent / "ml" / "symbol-detector" / "annotator" / "electrical_device_catalog.json").is_file():
            return parent
    return Path.cwd()


def _rng_for(seed: int, device: str, style_idx: int) -> np.random.Generator:
    digest = hashlib.sha256(f"{seed}|{device}|{style_idx}".encode("utf-8")).digest()
    return np.random.default_rng(int.from_bytes(digest[:8], "little"))


class CadCanvas:
    """4x-supersampled CAD ink canvas producing crisp anti-aliased grayscale crops."""

    SS = 4

    def __init__(
        self,
        w: int,
        h: int,
        stroke: float = 1.8,
        ink_darkness: int = 12,
        font: int = cv2.FONT_HERSHEY_SIMPLEX,
    ) -> None:
        self.w = int(w)
        self.h = int(h)
        self.stroke = float(stroke)
        self.ink_darkness = int(np.clip(ink_darkness, 0, 35))
        self.font = font
        self.ink = np.zeros((self.h * self.SS, self.w * self.SS), dtype=np.uint8)
        self.t = max(2, int(round(self.stroke * self.SS)))

    def p(self, x: float, y: float) -> tuple[int, int]:
        return int(round(x * self.SS)), int(round(y * self.SS))

    def _thick(self, t: float | None) -> int:
        if t is None:
            return self.t
        return max(1, int(round(t * self.SS)))

    def line(self, a: tuple[float, float], b: tuple[float, float], t: float | None = None, color: int = 255) -> None:
        cv2.line(self.ink, self.p(*a), self.p(*b), color, self._thick(t), cv2.LINE_AA)

    def dashed_line(
        self,
        a: tuple[float, float],
        b: tuple[float, float],
        dash: float = 4.5,
        gap: float = 3.5,
        t: float | None = None,
    ) -> None:
        dx, dy = b[0] - a[0], b[1] - a[1]
        dist = math.hypot(dx, dy)
        if dist < 1e-3:
            return
        ux, uy = dx / dist, dy / dist
        pos = 0.0
        while pos < dist:
            end = min(dist, pos + dash)
            self.line((a[0] + ux * pos, a[1] + uy * pos), (a[0] + ux * end, a[1] + uy * end), t=t)
            pos += dash + gap

    def circle(
        self,
        c: tuple[float, float],
        r: float,
        fill: bool = False,
        t: float | None = None,
        color: int = 255,
    ) -> None:
        cv2.circle(
            self.ink,
            self.p(*c),
            max(1, int(round(r * self.SS))),
            color,
            -1 if fill else self._thick(t),
            cv2.LINE_AA,
        )

    def dashed_circle(self, c: tuple[float, float], r: float, n_dashes: int = 16, t: float | None = None) -> None:
        step = 360.0 / n_dashes
        for i in range(n_dashes):
            a0 = i * step
            a1 = a0 + step * 0.56
            self.ellipse(c, r, r, start=a0, end=a1, fill=False, t=t)

    def ellipse(
        self,
        c: tuple[float, float],
        rx: float,
        ry: float,
        angle: float = 0.0,
        start: float = 0.0,
        end: float = 360.0,
        fill: bool = False,
        t: float | None = None,
        color: int = 255,
    ) -> None:
        cv2.ellipse(
            self.ink,
            self.p(*c),
            (max(1, int(round(rx * self.SS))), max(1, int(round(ry * self.SS)))),
            angle,
            start,
            end,
            color,
            -1 if fill else self._thick(t),
            cv2.LINE_AA,
        )

    def rect(
        self,
        a: tuple[float, float],
        b: tuple[float, float],
        fill: bool = False,
        t: float | None = None,
        color: int = 255,
    ) -> None:
        cv2.rectangle(self.ink, self.p(*a), self.p(*b), color, -1 if fill else self._thick(t), cv2.LINE_AA)

    def round_rect(
        self,
        a: tuple[float, float],
        b: tuple[float, float],
        radius: float,
        fill: bool = False,
        t: float | None = None,
        color: int = 255,
    ) -> None:
        x0, y0 = min(a[0], b[0]), min(a[1], b[1])
        x1, y1 = max(a[0], b[0]), max(a[1], b[1])
        r = min(radius, (x1 - x0) * 0.49, (y1 - y0) * 0.49)
        if r <= 0.5:
            self.rect((x0, y0), (x1, y1), fill=fill, t=t, color=color)
            return
        pts: list[tuple[float, float]] = []
        corners = [
            (x1 - r, y1 - r, 0.0, 90.0),
            (x0 + r, y1 - r, 90.0, 180.0),
            (x0 + r, y0 + r, 180.0, 270.0),
            (x1 - r, y0 + r, 270.0, 360.0),
        ]
        for ccx, ccy, deg0, deg1 in corners:
            for k in range(7):
                ang = math.radians(deg0 + (deg1 - deg0) * (k / 6.0))
                pts.append((ccx + r * math.cos(ang), ccy + r * math.sin(ang)))
        self.poly(pts, fill=fill, closed=True, t=t, color=color)

    def poly(
        self,
        pts: list[tuple[float, float]],
        fill: bool = False,
        closed: bool = True,
        t: float | None = None,
        color: int = 255,
    ) -> None:
        arr = np.array([self.p(*q) for q in pts], dtype=np.int32)
        if fill:
            cv2.fillPoly(self.ink, [arr], color, cv2.LINE_AA)
        else:
            cv2.polylines(self.ink, [arr], closed, color, self._thick(t), cv2.LINE_AA)

    def regular_poly(
        self,
        c: tuple[float, float],
        r: float,
        sides: int,
        rot_deg: float = 0.0,
        fill: bool = False,
        t: float | None = None,
        color: int = 255,
    ) -> list[tuple[float, float]]:
        pts = [
            (
                c[0] + r * math.cos(math.radians(rot_deg + 360.0 * i / sides)),
                c[1] + r * math.sin(math.radians(rot_deg + 360.0 * i / sides)),
            )
            for i in range(sides)
        ]
        self.poly(pts, fill=fill, closed=True, t=t, color=color)
        return pts

    def wedge_sector(
        self,
        c: tuple[float, float],
        r: float,
        deg0: float,
        deg1: float,
        fill: bool = True,
        steps: int = 24,
        t: float | None = None,
        color: int = 255,
    ) -> None:
        pts = [c]
        for i in range(steps + 1):
            ang = math.radians(deg0 + (deg1 - deg0) * (i / steps))
            pts.append((c[0] + r * math.cos(ang), c[1] + r * math.sin(ang)))
        self.poly(pts, fill=fill, closed=True, t=t, color=color)

    def chord_segment(
        self,
        c: tuple[float, float],
        r: float,
        deg0: float,
        deg1: float,
        fill: bool = True,
        steps: int = 24,
        t: float | None = None,
        color: int = 255,
    ) -> None:
        pts = [
            (
                c[0] + r * math.cos(math.radians(deg0 + (deg1 - deg0) * (i / steps))),
                c[1] + r * math.sin(math.radians(deg0 + (deg1 - deg0) * (i / steps))),
            )
            for i in range(steps + 1)
        ]
        self.poly(pts, fill=fill, closed=True, t=t, color=color)

    def hatch_poly(
        self,
        pts: list[tuple[float, float]],
        step: float = 4.5,
        angle_deg: float = 45.0,
        t: float | None = None,
    ) -> None:
        """Fill a polygon region with clean parallel CAD hatch lines."""
        xs = [q[0] for q in pts]
        ys = [q[1] for q in pts]
        pad = 4.0
        x0 = max(0, int(math.floor((min(xs) - pad) * self.SS)))
        y0 = max(0, int(math.floor((min(ys) - pad) * self.SS)))
        x1 = min(self.w * self.SS, int(math.ceil((max(xs) + pad) * self.SS)))
        y1 = min(self.h * self.SS, int(math.ceil((max(ys) + pad) * self.SS)))
        if x1 <= x0 or y1 <= y0:
            return
        mw, mh = x1 - x0, y1 - y0
        clip_mask = np.zeros((mh, mw), dtype=np.uint8)
        arr = np.array(
            [(int(round(q[0] * self.SS)) - x0, int(round(q[1] * self.SS)) - y0) for q in pts],
            dtype=np.int32,
        )
        cv2.fillPoly(clip_mask, [arr], 255, cv2.LINE_AA)

        lines_mask = np.zeros((mh, mw), dtype=np.uint8)
        rad = math.radians(angle_deg)
        dx, dy = math.cos(rad), math.sin(rad)
        nx, ny = -dy, dx
        cx, cy = mw / 2.0, mh / 2.0
        span = math.hypot(mw, mh)
        step_4x = max(4.0, step * self.SS)
        thick_4x = max(2, int(round((t if t is not None else self.stroke * 0.65) * self.SS)))
        k_max = int(math.ceil(span / step_4x))
        for k in range(-k_max, k_max + 1):
            off = k * step_4x
            px, py = cx + nx * off, cy + ny * off
            p1 = (int(round(px - dx * span)), int(round(py - dy * span)))
            p2 = (int(round(px + dx * span)), int(round(py + dy * span)))
            cv2.line(lines_mask, p1, p2, 255, thick_4x, cv2.LINE_AA)

        hatched = np.minimum(clip_mask, lines_mask)
        roi = self.ink[y0:y1, x0:x1]
        np.maximum(roi, hatched, out=roi)

    def _text_params(
        self,
        s: str,
        height: float,
        font: int | None = None,
        thick_scale: float = 0.85,
    ) -> tuple[int, float, int, int, int]:
        f = self.font if font is None else font
        thick = max(2, int(round(self.t * thick_scale)))
        (_, base_h), _ = cv2.getTextSize(s, f, 1.0, thick)
        scale = (height * self.SS) / max(base_h, 1)
        (tw, th), _ = cv2.getTextSize(s, f, scale, thick)
        return f, scale, thick, tw, th

    def text_size(
        self,
        s: str,
        height: float,
        font: int | None = None,
        thick_scale: float = 0.85,
    ) -> tuple[float, float]:
        _, _, _, tw, th = self._text_params(s, height, font=font, thick_scale=thick_scale)
        return tw / self.SS, th / self.SS

    def text(
        self,
        s: str,
        center: tuple[float, float],
        height: float,
        font: int | None = None,
        thick_scale: float = 0.85,
        color: int = 255,
    ) -> tuple[float, float]:
        f, scale, thick, tw, th = self._text_params(s, height, font=font, thick_scale=thick_scale)
        cx, cy = self.p(*center)
        cv2.putText(self.ink, s, (cx - tw // 2, cy + th // 2), f, scale, color, thick, cv2.LINE_AA)
        return tw / self.SS, th / self.SS

    def dollar_s(self, center: tuple[float, float], height: float, font: int | None = None) -> tuple[float, float]:
        """Classic US architectural '$' switch symbol: clean 'S' with vertical stem."""
        tw, th = self.text("S", center, height, font=font, thick_scale=0.88)
        self.line((center[0], center[1] - th * 0.68), (center[0], center[1] + th * 0.68), t=self.stroke * 0.85)
        return tw, th * 1.36

    def arrow(
        self,
        p0: tuple[float, float],
        p1: tuple[float, float],
        head_len: float = 7.0,
        head_w: float = 3.8,
        filled: bool = True,
        t: float | None = None,
    ) -> None:
        dx, dy = p1[0] - p0[0], p1[1] - p0[1]
        dist = math.hypot(dx, dy)
        if dist < 1e-3:
            return
        ux, uy = dx / dist, dy / dist
        px, py = -uy, ux
        hl = min(head_len, dist * 0.65)
        bx, by = p1[0] - ux * hl, p1[1] - uy * hl
        if filled:
            self.line(p0, (p1[0] - ux * (hl * 0.55), p1[1] - uy * (hl * 0.55)), t=t)
            self.poly(
                [p1, (bx + px * head_w, by + py * head_w), (bx - px * head_w, by - py * head_w)],
                fill=True,
            )
        else:
            self.line(p0, p1, t=t)
            self.poly(
                [(bx + px * head_w, by + py * head_w), p1, (bx - px * head_w, by - py * head_w)],
                fill=False,
                closed=False,
                t=t,
            )

    def arcs_wave(
        self,
        c: tuple[float, float],
        r0: float,
        r_step: float,
        count: int,
        deg0: float,
        deg1: float,
        t: float | None = None,
    ) -> None:
        for i in range(count):
            r = r0 + i * r_step
            self.ellipse(c, r, r, start=deg0, end=deg1, fill=False, t=t or self.stroke * 0.85)

    def render_crop(self, pad: int = PAD_PX, max_side: int = MAX_SIDE_PX) -> np.ndarray:
        small = cv2.resize(self.ink, (self.w, self.h), interpolation=cv2.INTER_AREA)
        ys, xs = np.nonzero(small > 12)
        if len(xs) == 0:
            raise ValueError("Rendered synthetic symbol has no ink")
        crop = small[int(ys.min()):int(ys.max()) + 1, int(xs.min()):int(xs.max()) + 1]
        ch, cw = crop.shape
        max_inner = max_side - 2 * pad
        if max(ch, cw) > max_inner:
            scale = max_inner / float(max(ch, cw))
            nw = max(3, int(round(cw * scale)))
            nh = max(3, int(round(ch * scale)))
            crop = cv2.resize(crop, (nw, nh), interpolation=cv2.INTER_AREA)
        padded = np.pad(crop, ((pad, pad), (pad, pad)), mode="constant", constant_values=0)
        ink_val = float(self.ink_darkness)
        gray = np.round(255.0 - (padded.astype(np.float32) / 255.0) * (255.0 - ink_val)).astype(np.uint8)
        return np.ascontiguousarray(gray)


# ---------------------------------------------------------------------------
# Shared geometry helpers
# ---------------------------------------------------------------------------

def _make_canvas(
    rng: np.random.Generator,
    w: int = 180,
    h: int = 180,
    *,
    sans_only: bool = False,
) -> tuple[CadCanvas, float, float]:
    stroke = float(rng.uniform(1.45, 2.25))
    ink = int(rng.integers(0, 34))
    pool = SANS_FONTS if sans_only else FONTS
    font = int(pool[int(rng.integers(len(pool)))])
    return CadCanvas(w, h, stroke=stroke, ink_darkness=ink, font=font), w / 2.0, h / 2.0


def _circle_pts(c: tuple[float, float], r: float, steps: int = 36) -> list[tuple[float, float]]:
    return [
        (c[0] + r * math.cos(2.0 * math.pi * i / steps), c[1] + r * math.sin(2.0 * math.pi * i / steps))
        for i in range(steps)
    ]


def _draw_badged_symbol(
    c: CadCanvas,
    shape: str,
    label: str,
    cx: float,
    cy: float,
    *,
    min_w: float = 28.0,
    min_h: float = 24.0,
    text_h: float = 12.5,
    pad_x: float = 5.5,
    pad_y: float = 5.0,
    double_border: bool = False,
    sub: str | None = None,
) -> tuple[float, float, float, float]:
    """Draw a cleanly sized enclosure around `label` and optional `sub`, returning bbox (x0, y0, x1, y1)."""
    label_font = cv2.FONT_HERSHEY_SIMPLEX if c.font == cv2.FONT_HERSHEY_COMPLEX_SMALL and len(label) > 1 else None
    tw, th = c.text_size(label, text_h, font=label_font)
    sub_w, sub_h = (0.0, 0.0)
    if sub:
        sub_h = max(8.5, text_h * 0.56)
        sub_w, sub_h = c.text_size(sub, sub_h, font=cv2.FONT_HERSHEY_SIMPLEX)
    content_w = tw + (sub_w + 2.5 if sub else 0.0)
    content_h = max(th, th * 0.55 + sub_h * 0.7 if sub else th)

    if shape in ("rect", "square", "round_rect"):
        bw = max(min_w, content_w + 2.0 * pad_x)
        bh = max(min_h, content_h + 2.0 * pad_y)
        if shape == "square":
            bw = bh = max(bw, bh)
        x0, y0, x1, y1 = cx - bw / 2.0, cy - bh / 2.0, cx + bw / 2.0, cy + bh / 2.0
        if shape == "round_rect":
            c.round_rect((x0, y0), (x1, y1), min(bw, bh) * 0.35)
            if double_border:
                c.round_rect((x0 + 2.8, y0 + 2.8), (x1 - 2.8, y1 - 2.8), max(2.0, min(bw, bh) * 0.25), t=c.stroke * 0.7)
        else:
            c.rect((x0, y0), (x1, y1))
            if double_border:
                c.rect((x0 + 2.8, y0 + 2.8), (x1 - 2.8, y1 - 2.8), t=c.stroke * 0.7)
    elif shape in ("circle", "double_circle"):
        r = max(min_w * 0.5, min_h * 0.5, 0.5 * math.hypot(content_w, content_h) + pad_x)
        c.circle((cx, cy), r)
        if shape == "double_circle" or double_border:
            c.circle((cx, cy), r - 2.8, t=c.stroke * 0.75)
        x0, y0, x1, y1 = cx - r, cy - r, cx + r, cy + r
    elif shape == "diamond":
        r = max(min_w * 0.55, 0.5 * (content_w + content_h) + pad_x * 1.1)
        c.regular_poly((cx, cy), r, 4, rot_deg=0.0)
        if double_border:
            c.regular_poly((cx, cy), r - 3.2, 4, rot_deg=0.0, t=c.stroke * 0.7)
        x0, y0, x1, y1 = cx - r, cy - r, cx + r, cy + r
    elif shape == "hexagon":
        r = max(min_w * 0.55, content_w / 1.45 + pad_x, content_h / 1.3 + pad_y)
        c.regular_poly((cx, cy), r, 6, rot_deg=0.0)
        if double_border:
            c.regular_poly((cx, cy), r - 3.0, 6, rot_deg=0.0, t=c.stroke * 0.7)
        x0, y0, x1, y1 = cx - r, cy - r * 0.866, cx + r, cy + r * 0.866
    elif shape == "octagon":
        r = max(min_w * 0.55, 0.5 * math.hypot(content_w, content_h) + pad_x * 0.95)
        c.regular_poly((cx, cy), r, 8, rot_deg=22.5)
        if double_border:
            c.regular_poly((cx, cy), r - 2.8, 8, rot_deg=22.5, t=c.stroke * 0.7)
        x0, y0, x1, y1 = cx - r, cy - r, cx + r, cy + r
    else:
        raise ValueError(f"Unknown badge shape: {shape}")

    if sub:
        main_cx = cx - (sub_w + 1.5) * 0.45
        c.text(label, (main_cx, cy - sub_h * 0.12), text_h, font=label_font)
        c.text(
            sub,
            (main_cx + tw * 0.5 + sub_w * 0.5 + 1.8, cy + th * 0.34),
            max(8.5, text_h * 0.56),
            font=cv2.FONT_HERSHEY_SIMPLEX,
        )
    else:
        c.text(label, (cx, cy), text_h, font=label_font)
    return x0, y0, x1, y1


def _draw_wall_stem(c: CadCanvas, cx: float, bottom_y: float, length: float, with_bar: bool = False) -> None:
    c.line((cx, bottom_y), (cx, bottom_y + length))
    if with_bar:
        c.line((cx - 8.0, bottom_y + length), (cx + 8.0, bottom_y + length), t=c.stroke * 0.9)


def _draw_switch_glyph(
    c: CadCanvas,
    cx: float,
    cy: float,
    h: float,
    sub: str = "",
    *,
    dollar: bool = False,
    stem: bool = False,
    sub_inline: bool = False,
) -> None:
    if dollar:
        tw, th = c.dollar_s((cx, cy), h)
    else:
        tw, th = c.text("S", (cx, cy), h)
    if sub:
        sh = max(9.5, h * (0.62 if sub_inline else 0.52))
        sw, _ = c.text_size(sub, sh, font=cv2.FONT_HERSHEY_SIMPLEX)
        sx = cx + tw * 0.5 + sw * 0.5 + 2.2
        sy = cy if sub_inline else cy + th * 0.35
        c.text(sub, (sx, sy), sh, font=cv2.FONT_HERSHEY_SIMPLEX)
    if stem:
        _draw_wall_stem(c, cx, cy + h * 0.68, 7.5, with_bar=False)


def _place_tag(c: CadCanvas, tag: str, box: tuple[float, float, float, float], pos: int, tag_h: float = 10.5) -> None:
    x0, y0, x1, y1 = box
    font = cv2.FONT_HERSHEY_SIMPLEX if len(tag) > 1 else None
    tw, th = c.text_size(tag, tag_h, font=font)
    cy = 0.5 * (y0 + y1)
    cx = 0.5 * (x0 + x1)
    mode = pos % 4
    if mode == 0:
        c.text(tag, (x1 + tw * 0.5 + 4.5, cy), tag_h, font=font)
    elif mode == 1:
        c.text(tag, (x1 + tw * 0.5 + 4.0, y0 + th * 0.35), tag_h, font=font)
    elif mode == 2:
        c.text(tag, (cx, y0 - th * 0.55 - 3.5), tag_h, font=font)
    else:
        c.text(tag, (cx, y1 + th * 0.55 + 4.5), tag_h, font=font)


# ---------------------------------------------------------------------------
# 1. Receptacles (17 devices)
# ---------------------------------------------------------------------------

def _draw_receptacle_base(
    c: CadCanvas,
    kind: str,
    cx: float,
    cy: float,
    r: float,
    style: int,
    *,
    stem: bool = False,
    wall_bar: bool = False,
) -> tuple[float, float, float, float]:
    """Draw core US receptacle geometry and return bounding box (x0, y0, x1, y1)."""
    tick = r * (1.42 + 0.08 * (style % 3))
    gap = r * 0.34
    if kind == "single":
        c.circle((cx, cy), r)
        if style % 2 == 0:
            c.line((cx - tick, cy), (cx + tick, cy))
        else:
            c.line((cx - r, cy), (cx + tick * 1.05, cy))
        if stem:
            _draw_wall_stem(c, cx, cy + r, 7.5, with_bar=wall_bar)
        return cx - tick, cy - r, cx + tick, cy + r
    if kind == "quad":
        if style == 2:
            dx = r * 1.15
            rr = r * 0.82
            tk = rr * 1.38
            gp = rr * 0.33
            for sgn in (-1.0, 1.0):
                ccx = cx + sgn * dx
                c.circle((ccx, cy), rr)
                for o in (-gp, gp):
                    c.line((ccx - tk, cy + o), (ccx + tk, cy + o))
            c.line((cx - dx - tk, cy + rr), (cx + dx + tk, cy + rr), t=c.stroke * 0.85)
            return cx - dx - tk, cy - rr, cx + dx + tk, cy + rr
        if style == 4:
            sq = r * 1.32
            c.rect((cx - sq, cy - sq), (cx + sq, cy + sq))
            c.circle((cx, cy), r * 0.88)
            for o in (-gap * 0.85, gap * 0.85):
                c.line((cx - tick * 0.9, cy + o), (cx + tick * 0.9, cy + o))
                c.line((cx + o, cy - tick * 0.9), (cx + o, cy + tick * 0.9))
            return cx - sq, cy - sq, cx + sq, cy + sq
        c.circle((cx, cy), r)
        for o in (-gap, gap):
            c.line((cx - tick, cy + o), (cx + tick, cy + o))
            c.line((cx + o, cy - tick), (cx + o, cy + tick))
        if stem:
            _draw_wall_stem(c, cx, cy + r, 7.0, with_bar=wall_bar)
        return cx - tick, cy - tick, cx + tick, cy + tick
    if kind == "special":
        c.circle((cx, cy), r)
        if style in (0, 2, 4):
            pts = [(cx, cy - r * 0.68), (cx - r * 0.60, cy + r * 0.44), (cx + r * 0.60, cy + r * 0.44)]
            c.poly(pts, fill=(style != 4))
            if style == 2:
                for deg in (-90.0, 30.0, 150.0):
                    rad = math.radians(deg)
                    c.line(
                        (cx + r * 0.9 * math.cos(rad), cy + r * 0.9 * math.sin(rad)),
                        (cx + r * 1.48 * math.cos(rad), cy + r * 1.48 * math.sin(rad)),
                    )
        else:
            for deg in (-90.0, 35.0, 145.0):
                rad = math.radians(deg)
                c.line(
                    (cx + r * 0.25 * math.cos(rad), cy + r * 0.25 * math.sin(rad)),
                    (cx + r * 1.45 * math.cos(rad), cy + r * 1.45 * math.sin(rad)),
                )
        if stem:
            _draw_wall_stem(c, cx, cy + r, 7.5, with_bar=wall_bar)
        return cx - r * 1.45, cy - r * 1.45, cx + r * 1.45, cy + r * 1.45
    c.circle((cx, cy), r)
    if style == 3:
        for o in (-gap, gap):
            c.line((cx + o, cy - tick), (cx + o, cy + tick))
    else:
        for o in (-gap, gap):
            c.line((cx - tick, cy + o), (cx + tick, cy + o))
    if stem:
        _draw_wall_stem(c, cx, cy + r, 7.5, with_bar=wall_bar)
    return cx - tick, cy - r, cx + tick, cy + r


def _draw_receptacle(variant: str, style: int, rng: np.random.Generator) -> np.ndarray:
    c, cx, cy = _make_canvas(rng, 210, 180)
    r = float(rng.uniform(11.5, 14.5))
    if variant == "duplex":
        _draw_receptacle_base(c, "duplex", cx, cy, r, style, stem=(style % 2 == 0), wall_bar=(style == 4))
    elif variant == "duplex_weatherproof":
        box = _draw_receptacle_base(c, "duplex", cx - 6, cy, r, style % 3, stem=(style % 2 == 0))
        _place_tag(c, "WP", box, style % 4, tag_h=11.0)
    elif variant == "quad":
        _draw_receptacle_base(c, "quad", cx, cy, r, style, stem=(style in (0, 3)), wall_bar=(style == 3))
    elif variant == "quad_weatherproof":
        box = _draw_receptacle_base(c, "quad", cx - 6, cy, r, style % 4, stem=(style % 2 == 0))
        _place_tag(c, "WP", box, style % 4, tag_h=11.0)
    elif variant == "single":
        _draw_receptacle_base(c, "single", cx, cy, r, style, stem=(style in (0, 2, 4)), wall_bar=(style == 4))
    elif variant == "single_weatherproof":
        box = _draw_receptacle_base(c, "single", cx - 6, cy, r, style % 3, stem=(style % 2 == 0))
        _place_tag(c, "WP", box, style % 4, tag_h=11.0)
    elif variant == "special_purpose":
        _draw_receptacle_base(c, "special", cx, cy, r, style, stem=(style in (0, 1, 3)), wall_bar=(style == 3))
    elif variant == "special_purpose_weatherproof":
        box = _draw_receptacle_base(c, "special", cx - 6, cy, r, style % 4, stem=(style % 2 == 0))
        _place_tag(c, "WP", box, style % 4, tag_h=11.0)
    elif variant == "duplex_above_counter":
        box = _draw_receptacle_base(c, "duplex", cx - 5, cy, r, style % 3, stem=(style % 2 == 0), wall_bar=(style >= 2))
        _place_tag(c, "AC" if style != 4 else "+44\"", box, 0 if style < 3 else 1, tag_h=10.5)
    elif variant == "duplex_gfci":
        box = _draw_receptacle_base(c, "duplex", cx - 7, cy, r, style % 3, stem=(style % 2 == 0))
        if style == 4:
            c.line((cx - 7 - r * 0.4, cy - r * 1.4), (cx - 7 - r * 0.4, cy + r * 1.4))
            c.line((cx - 7 + r * 0.4, cy - r * 1.4), (cx - 7 + r * 0.4, cy + r * 1.4))
        tag = ("GFCI", "GFI", "GF", "GFCI", "GFI")[style % 5]
        _place_tag(c, tag, box, style % 3, tag_h=10.5)
    elif variant == "duplex_gfci_weatherproof":
        box = _draw_receptacle_base(c, "duplex", cx - 10, cy, r, style % 3, stem=(style % 2 == 0))
        labels = ["GFCI/WP", "GFI WP", "WP/GF", "GFCI", "WP GFI", "GFI/WP"]
        lbl = labels[style % len(labels)]
        if style == 3:
            _place_tag(c, "GFCI", box, 0, tag_h=10.0)
            _place_tag(c, "WP", box, 3, tag_h=10.0)
        else:
            _place_tag(c, lbl, box, style % 2, tag_h=10.0)
    elif variant == "quad_gfci":
        box = _draw_receptacle_base(c, "quad", cx - 8, cy, r, style % 4, stem=(style % 2 == 0))
        tag = ("GFCI", "GFI", "GF", "GFCI", "GFI", "GFCI")[style % 6]
        _place_tag(c, tag, box, style % 3, tag_h=10.5)
    elif variant == "floor_mounted":
        sq = r * 1.48
        c.rect((cx - sq, cy - sq), (cx + sq, cy + sq))
        if style == 2:
            c.rect((cx - sq - 3.2, cy - sq - 3.2), (cx + sq + 3.2, cy + sq + 3.2), t=c.stroke * 0.75)
        elif style == 3:
            for sx in (-1, 1):
                for sy in (-1, 1):
                    c.line((cx + sx * sq, cy + sy * sq), (cx + sx * (sq + 4), cy + sy * (sq + 4)))
        _draw_receptacle_base(c, "duplex", cx, cy, r * 0.85, style % 2, stem=False)
        if style in (1, 4):
            _place_tag(c, "F", (cx - sq, cy - sq, cx + sq, cy + sq), 1, tag_h=10.5)
    elif variant == "usb_charger_duplex":
        box = _draw_receptacle_base(c, "duplex", cx - 6, cy, r, style % 3, stem=(style % 2 == 0))
        _place_tag(c, "USB", box, style % 4, tag_h=10.5)
    elif variant == "isolated_ground":
        box = _draw_receptacle_base(c, "duplex", cx, cy, r * 0.88, style % 2, stem=(style in (2, 4)))
        if style in (0, 1, 3):
            tr = r * 1.85
            pts = [(cx, cy - tr * 1.05), (cx - tr * 0.98, cy + tr * 0.72), (cx + tr * 0.98, cy + tr * 0.72)]
            c.poly(pts, fill=False)
            if style == 3:
                tr2 = r * 2.15
                pts2 = [(cx, cy - tr2 * 1.05), (cx - tr2 * 0.98, cy + tr2 * 0.72), (cx + tr2 * 0.98, cy + tr2 * 0.72)]
                c.poly(pts2, fill=False, t=c.stroke * 0.7)
            if style == 1:
                _place_tag(c, "IG", (cx - tr, cy - tr, cx + tr, cy + tr * 0.72), 0, tag_h=10.5)
        else:
            _place_tag(c, "IG", box, 0 if style == 2 else 1, tag_h=11.0)
    elif variant == "ceiling_mounted":
        if style == 0:
            _draw_receptacle_base(c, "duplex", cx, cy, r, 0, stem=False)
            c.line((cx, cy - r * 1.7), (cx, cy + r * 1.7))
            c.line((cx - r * 0.9, cy - r * 1.7), (cx + r * 0.9, cy - r * 1.7))
        elif style == 1:
            _draw_receptacle_base(c, "duplex", cx, cy, r * 0.85, 0, stem=False)
            c.dashed_circle((cx, cy), r * 1.55, n_dashes=14)
        elif style in (2, 3):
            box = _draw_receptacle_base(c, "duplex", cx - 5, cy, r, 0, stem=False)
            _place_tag(c, "CM" if style == 2 else "CLG", box, 0, tag_h=10.5)
        else:
            _draw_receptacle_base(c, "duplex", cx, cy, r, 0, stem=False)
            for deg in (45.0, 135.0, 225.0, 315.0):
                rad = math.radians(deg)
                c.line(
                    (cx + r * math.cos(rad), cy + r * math.sin(rad)),
                    (cx + r * 1.55 * math.cos(rad), cy + r * 1.55 * math.sin(rad)),
                )
    elif variant == "tamper_resistant":
        box = _draw_receptacle_base(c, "duplex", cx - 6, cy, r, style % 3, stem=(style % 2 == 0))
        _place_tag(c, "TR", box, style % 4, tag_h=10.5)
    return c.render_crop()


# ---------------------------------------------------------------------------
# 2. Switches & Controls (Switch 20 + Control Relay 8)
# ---------------------------------------------------------------------------

def _draw_switch(variant: str, style: int, rng: np.random.Generator) -> np.ndarray:
    c, cx, cy = _make_canvas(rng, 180, 160, sans_only=True)
    h = float(rng.uniform(17.5, 22.0))
    dollar = (style % 2 == 1)
    stem = (style in (2, 4))

    sub_map = {
        "single_pole": "",
        "double_pole": "2" if style < 5 else "2P",
        "three_way": "3",
        "four_way": "4",
        "three_way_dimmer": "D3" if style % 2 == 0 else "3D",
        "four_way_dimmer": "D4" if style % 2 == 0 else "4D",
        "key_operated": "K" if style < 4 else "KEY",
        "momentary": "MC" if style % 2 == 0 else "M",
        "time_switch": "T" if style < 4 else "TM",
        "low_voltage": "LV",
        "occupancy_sensor_dimming": "OSD",
        "vacancy_sensor": "VS",
    }

    if variant == "dimmer":
        if style == 3:
            _draw_badged_symbol(c, "square", "D", cx, cy, min_w=26, min_h=26, text_h=14.0)
        elif style == 4:
            _draw_switch_glyph(c, cx - 5, cy, h, "DIM", dollar=False, stem=True)
        else:
            _draw_switch_glyph(c, cx - 3, cy, h, "D", dollar=dollar, stem=stem)
    elif variant == "pilot_light":
        if style == 4:
            _draw_switch_glyph(c, cx - 3, cy, h, "", dollar=False, stem=False)
            c.circle((cx + h * 0.65, cy - h * 0.42), 3.6, fill=True)
        elif style == 5:
            _draw_switch_glyph(c, cx - 4, cy, h, "PL", dollar=False, stem=True)
        else:
            _draw_switch_glyph(c, cx - 3, cy, h, "P", dollar=dollar, stem=stem)
    elif variant == "occupancy_sensor":
        if style == 3:
            _draw_badged_symbol(c, "diamond", "OS", cx, cy, min_w=32, min_h=32, text_h=12.5)
        elif style == 4:
            box = _draw_badged_symbol(c, "rect", "OS", cx, cy, min_w=30, min_h=24, text_h=12.5)
            _draw_wall_stem(c, cx, box[3], 7.0)
        else:
            _draw_switch_glyph(c, cx - 5, cy, h, "OS", dollar=dollar, stem=stem)
    elif variant == "low_voltage_dimmer":
        if style == 3:
            _draw_badged_symbol(c, "rect", "DS", cx, cy, min_w=34, min_h=22, text_h=13.0)
        elif style == 4:
            _draw_badged_symbol(c, "rect", "LVD", cx, cy, min_w=36, min_h=22, text_h=11.5)
        else:
            _draw_switch_glyph(c, cx - 7, cy, h, "LVD", dollar=dollar, stem=stem)
    elif variant == "wireless":
        if style in (0, 2, 4):
            _draw_switch_glyph(c, cx - 6, cy, h, "W" if style == 2 else "", dollar=(style == 4), stem=(style == 2))
            c.arcs_wave((cx + 5, cy), 6.0, 4.5, 2, -48.0, 48.0)
        elif style == 3:
            box = _draw_badged_symbol(c, "rect", "WS", cx - 5, cy, min_w=28, min_h=22, text_h=12.0)
            c.arcs_wave((box[2] + 2, cy), 5.0, 4.2, 2, -45.0, 45.0)
        else:
            _draw_switch_glyph(c, cx - 7, cy, h, "RF", dollar=(style == 5), stem=False)
            c.arcs_wave((cx + 12, cy - 5), 5.0, 4.0, 2, -55.0, 35.0)
    elif variant == "wireless_dimmer":
        if style in (0, 2, 4):
            _draw_switch_glyph(c, cx - 8, cy, h, "WD" if style != 4 else "D", dollar=(style == 2), stem=(style == 2))
            c.arcs_wave((cx + 10, cy), 5.5, 4.2, 2, -48.0, 48.0)
        elif style == 3:
            box = _draw_badged_symbol(c, "rect", "WSD", cx - 5, cy, min_w=34, min_h=22, text_h=11.0)
            c.arcs_wave((box[2] + 2, cy), 5.0, 4.2, 2, -45.0, 45.0)
        else:
            _draw_switch_glyph(c, cx - 8, cy, h, "RFD", dollar=(style == 5), stem=False)
            c.arcs_wave((cx + 14, cy - 4), 5.0, 4.0, 2, -50.0, 40.0)
    elif variant == "wireless_multi_button":
        if style in (0, 3):
            bw, bh = 30.0, 28.0
            c.rect((cx - bw / 2 - 4, cy - bh / 2), (cx + bw / 2 - 4, cy + bh / 2))
            for r_i in (-5.5, 5.5):
                for c_i in (-6.0, 6.0):
                    c.rect((cx - 4 + c_i - 3.0, cy + r_i - 3.0), (cx - 4 + c_i + 3.0, cy + r_i + 3.0), fill=(style == 3))
            c.arcs_wave((cx + bw / 2 - 1, cy), 5.5, 4.2, 2, -45.0, 45.0)
        elif style in (1, 4):
            _draw_switch_glyph(c, cx - 8, cy, h, "WM" if style == 1 else "SC", dollar=(style == 4), stem=False)
            c.arcs_wave((cx + 11, cy), 5.5, 4.2, 2, -48.0, 48.0)
        else:
            box = _draw_badged_symbol(c, "rect", "WS4" if style == 2 else "SC", cx - 5, cy, min_w=34, min_h=22, text_h=11.0)
            c.arcs_wave((box[2] + 2, cy), 5.0, 4.0, 2, -45.0, 45.0)
    elif variant == "emergency_power_off_pushbutton":
        if style == 0:
            _draw_badged_symbol(c, "circle", "EPO", cx, cy, min_w=32, min_h=32, text_h=11.0)
        elif style == 1:
            _draw_badged_symbol(c, "square", "EPO", cx, cy, min_w=32, min_h=32, text_h=11.0)
        elif style == 2:
            _draw_badged_symbol(c, "double_circle", "EPO", cx, cy, min_w=34, min_h=34, text_h=10.5)
        elif style == 3:
            _draw_badged_symbol(c, "octagon", "EPO", cx, cy, min_w=34, min_h=34, text_h=10.5)
        else:
            box = _draw_badged_symbol(c, "square", "EPO", cx, cy, min_w=34, min_h=34, text_h=10.5, double_border=True)
            _draw_wall_stem(c, cx, box[3], 6.5)
    else:
        sub = sub_map[variant]
        shift = -4.0 if sub else 0.0
        _draw_switch_glyph(c, cx + shift, cy, h, sub, dollar=dollar, stem=stem, sub_inline=(style == 5 and len(sub) >= 2))
    return c.render_crop()


def _draw_control_relay(variant: str, style: int, rng: np.random.Generator) -> np.ndarray:
    c, cx, cy = _make_canvas(rng, 180, 150, sans_only=True)
    if variant == "power_pack":
        lbl = ("PP", "SP", "PP", "SP", "PP", "SP")[style % 6]
        box = _draw_badged_symbol(
            c, "rect", lbl, cx, cy, min_w=36, min_h=22, text_h=13.0, double_border=(style == 2)
        )
        if style == 3:
            c.line((box[0] - 6, cy), (box[0], cy))
            c.line((box[2], cy), (box[2] + 6, cy))
        elif style == 4:
            c.poly([(box[0], box[1]), (box[0] + 8, box[1]), (box[0], box[1] + 8)], fill=True)
        elif style == 5:
            c.line((box[0], box[3]), (box[0] + 6, box[1]), t=c.stroke * 0.7)
    elif variant == "power_pack_dimming":
        lbl = ("PP-D", "PPD", "SP-D", "PP-D", "PPD", "SPD")[style % 6]
        box = _draw_badged_symbol(
            c, "rect", lbl, cx, cy, min_w=42, min_h=22, text_h=11.5, double_border=(style == 3)
        )
        if style == 4:
            c.line((box[0] - 6, cy), (box[0], cy))
            c.line((box[2], cy), (box[2] + 6, cy))
        elif style == 5:
            c.text("0-10V", (cx, box[3] + 8.5), 8.5, font=cv2.FONT_HERSHEY_SIMPLEX)
    elif variant == "power_pack_auxiliary":
        lbl = ("PP-A", "PPA", "SP-A", "AUX", "PP-A", "SR")[style % 6]
        box = _draw_badged_symbol(
            c, "rect", lbl, cx, cy, min_w=42, min_h=22, text_h=11.5, double_border=(style == 4)
        )
        if style == 5:
            c.line((box[0] - 6, cy), (box[0], cy))
            c.line((box[2], cy), (box[2] + 6, cy))
    elif variant == "room_controller":
        lbl = ("RC", "RC", "LRC", "RC", "RLC", "RC")[style % 6]
        box = _draw_badged_symbol(
            c, "rect", lbl, cx, cy, min_w=38, min_h=24, text_h=13.0, double_border=(style in (1, 5))
        )
        if style == 3:
            c.line((box[0], box[1] + 4.5), (box[2], box[1] + 4.5), t=c.stroke * 0.7)
        elif style == 4:
            for k in (-8.0, 0.0, 8.0):
                c.line((cx + k, box[3]), (cx + k, box[3] + 4.5))
    elif variant == "time_clock":
        if style in (0, 3):
            _draw_badged_symbol(c, "rect", "TC", cx, cy, min_w=34, min_h=24, text_h=13.0, double_border=(style == 3))
        elif style in (1, 4):
            r = 16.0
            c.circle((cx, cy), r)
            c.line((cx, cy), (cx, cy - r * 0.62))
            c.line((cx, cy), (cx + r * 0.50, cy + r * 0.15))
            if style == 4:
                c.text("TC", (cx + r + 11.0, cy), 10.5)
        else:
            box = _draw_badged_symbol(c, "circle", "TC", cx, cy, min_w=32, min_h=32, text_h=12.5)
            c.line((box[0], cy), (box[0] - 5, cy))
    elif variant == "lighting_relay":
        lbl = ("LR", "LR", "RL", "LR", "LCR", "LR")[style % 6]
        shape = "circle" if style == 3 else "rect"
        box = _draw_badged_symbol(c, shape, lbl, cx, cy, min_w=34, min_h=24, text_h=12.5, double_border=(style == 4))
        if style == 1:
            c.line((box[0] - 5, cy), (box[0], cy))
            c.line((box[2], cy), (box[2] + 5, cy))
        elif style == 5:
            c.poly([(box[0], box[3]), (box[0] + 7, box[3]), (box[0], box[3] - 7)], fill=True)
    elif variant == "contactor":
        lbl = ("LC", "C", "LC", "CN", "MC")[style % 5]
        box = _draw_badged_symbol(c, "rect", lbl, cx, cy, min_w=34, min_h=24, text_h=12.5, double_border=(style == 2))
        if style in (1, 4):
            c.line((box[0] - 6, cy - 5), (box[0], cy - 5))
            c.line((box[0] - 6, cy + 5), (box[0], cy + 5))
            c.line((box[2], cy - 5), (box[2] + 6, cy - 5))
            c.line((box[2], cy + 5), (box[2] + 6, cy + 5))
    elif variant == "emergency_transfer_device":
        lbl = ("ELTD", "UL924", "ETD", "ELTD", "ALTD", "ELTD")[style % 6]
        shape = "diamond" if style == 3 else "rect"
        box = _draw_badged_symbol(
            c, shape, lbl, cx, cy, min_w=42, min_h=24, text_h=10.5, double_border=(style == 4)
        )
        if style == 5:
            c.poly([(box[0], box[1]), (box[0] + 8, box[1]), (box[0], box[1] + 8)], fill=True)
    return c.render_crop()


# ---------------------------------------------------------------------------
# 3. Sensors (15 devices)
# ---------------------------------------------------------------------------

def _draw_sensor(variant: str, style: int, rng: np.random.Generator) -> np.ndarray:
    c, cx, cy = _make_canvas(rng, 180, 170)
    shapes = ("circle", "octagon", "diamond", "round_rect", "double_circle")

    if variant == "occupancy_sensor":
        lbl = "OC" if style == 3 else "OS"
        sh = shapes[style % len(shapes)]
        box = _draw_badged_symbol(c, sh, lbl, cx, cy, min_w=30, min_h=28, text_h=12.0)
        if style == 4:
            r = 0.5 * (box[2] - box[0]) + 4.0
            c.arcs_wave((cx, cy), r, 4.0, 1, -40.0, 40.0)
            c.arcs_wave((cx, cy), r, 4.0, 1, 140.0, 220.0)
    elif variant == "occupancy_sensor_wall":
        if style in (0, 3):
            r = 16.0
            c.line((cx - r * 1.3, cy + 6), (cx + r * 1.3, cy + 6), t=c.stroke * 1.1)
            c.ellipse((cx, cy + 6), r, r, start=180.0, end=360.0)
            c.text("OS", (cx, cy - 2), 10.5, font=cv2.FONT_HERSHEY_SIMPLEX)
            if style == 3:
                c.dashed_line((cx, cy + 6), (cx - r * 1.5, cy - r * 1.2))
                c.dashed_line((cx, cy + 6), (cx + r * 1.5, cy - r * 1.2))
        elif style in (1, 4):
            box = _draw_badged_symbol(c, "circle", "OS", cx, cy, min_w=28, min_h=28, text_h=11.5)
            _draw_wall_stem(c, cx, box[3], 6.5, with_bar=True)
            if style == 4:
                c.arcs_wave((cx, cy), 18.5, 4.5, 1, 215.0, 325.0)
        else:
            r = 22.0
            c.wedge_sector((cx - 8, cy + 8), r, -85.0, -5.0, fill=False)
            c.text("OS", (cx + 1, cy - 1), 10.0, font=cv2.FONT_HERSHEY_SIMPLEX)
    elif variant == "occupancy_sensor_ceiling":
        if style in (0, 2, 4):
            sh = "double_circle" if style == 2 else "circle"
            box = _draw_badged_symbol(c, sh, "OS" if style != 4 else "OC", cx, cy, min_w=30, min_h=30, text_h=11.5)
            r = 0.5 * (box[2] - box[0])
            degs = (45.0, 135.0, 225.0, 315.0) if style == 2 else (0.0, 90.0, 180.0, 270.0)
            for deg in degs:
                rad = math.radians(deg)
                c.line(
                    (cx + r * math.cos(rad), cy + r * math.sin(rad)),
                    (cx + (r + 5.0) * math.cos(rad), cy + (r + 5.0) * math.sin(rad)),
                )
        elif style == 3:
            _draw_badged_symbol(c, "circle", "OS", cx, cy, min_w=28, min_h=28, text_h=11.5)
            c.dashed_circle((cx, cy), 20.5, n_dashes=14)
        else:
            box = _draw_badged_symbol(c, "octagon", "OS", cx, cy, min_w=30, min_h=30, text_h=11.5)
            c.text("C", (cx, box[3] + 6.5), 9.0, font=cv2.FONT_HERSHEY_SIMPLEX)
    elif variant == "occupancy_sensor_dual_technology":
        if style == 0:
            r = 21.0
            c.regular_poly((cx, cy), r, 4, rot_deg=0.0)
            c.regular_poly((cx, cy), r * 0.72, 4, rot_deg=0.0, t=c.stroke * 0.75)
            c.circle((cx, cy), r * 0.28)
            for deg in (0.0, 90.0, 180.0, 270.0):
                rad = math.radians(deg)
                c.circle((cx + r * 0.5 * math.cos(rad), cy + r * 0.5 * math.sin(rad)), 1.8, fill=True)
        elif style in (1, 2, 3):
            sh = ("diamond", "circle", "octagon")[style - 1]
            _draw_badged_symbol(c, sh, "DT", cx, cy, min_w=30, min_h=30, text_h=12.0)
        elif style == 4:
            _draw_badged_symbol(c, "double_circle", "OS-DT", cx, cy, min_w=36, min_h=36, text_h=10.0)
        else:
            _draw_badged_symbol(c, "round_rect", "DT", cx, cy, min_w=34, min_h=24, text_h=12.0, double_border=True)
    elif variant == "occupancy_sensor_wireless":
        sh = ("circle", "octagon", "diamond", "round_rect", "circle", "double_circle")[style % 6]
        lbl = "OS" if style < 4 else ("WOS" if style == 4 else "OC")
        box = _draw_badged_symbol(c, sh, lbl, cx - 5, cy, min_w=28, min_h=26, text_h=11.0)
        c.arcs_wave((box[2] + 1, cy), 5.0, 4.0, 2, -48.0, 48.0)
    elif variant == "occupancy_sensor_wireless_wall":
        if style in (0, 2, 4):
            r = 15.0
            c.line((cx - r * 1.3 - 3, cy + 6), (cx + r * 1.3 - 3, cy + 6), t=c.stroke * 1.05)
            c.ellipse((cx - 3, cy + 6), r, r, start=180.0, end=360.0)
            c.text("OS", (cx - 3, cy - 2), 10.0, font=cv2.FONT_HERSHEY_SIMPLEX)
            c.arcs_wave((cx + r - 1, cy - 2), 5.0, 4.0, 2, -55.0, 25.0)
        else:
            box = _draw_badged_symbol(c, "circle", "OS", cx - 5, cy, min_w=28, min_h=28, text_h=11.0)
            _draw_wall_stem(c, cx - 5, box[3], 6.5, with_bar=True)
            c.arcs_wave((box[2] + 1, cy), 5.0, 4.0, 2, -48.0, 48.0)
    elif variant == "occupancy_sensor_wireless_ceiling":
        sh = "double_circle" if style in (1, 4) else "circle"
        box = _draw_badged_symbol(c, sh, "OS", cx - 5, cy, min_w=28, min_h=28, text_h=11.0)
        r = 0.5 * (box[2] - box[0])
        for deg in (90.0, 180.0, 270.0):
            rad = math.radians(deg)
            c.line(
                (cx - 5 + r * math.cos(rad), cy + r * math.sin(rad)),
                (cx - 5 + (r + 4.5) * math.cos(rad), cy + (r + 4.5) * math.sin(rad)),
            )
        c.arcs_wave((box[2] + 1, cy), 5.0, 4.0, 2, -48.0, 48.0)
    elif variant == "vacancy_sensor":
        lbl = "V" if style in (2, 4) else "VS"
        sh = ("circle", "octagon", "circle", "diamond", "circle")[style % 5]
        box = _draw_badged_symbol(c, sh, lbl, cx, cy, min_w=28, min_h=28, text_h=12.5 if lbl == "V" else 12.0)
        if style == 4:
            _draw_wall_stem(c, cx, box[3], 6.5, with_bar=True)
    elif variant == "daylight_photosensor":
        lbl = "DL" if style != 3 else "DS"
        sh = ("circle", "double_circle", "octagon", "circle", "round_rect")[style % 5]
        box = _draw_badged_symbol(c, sh, lbl, cx, cy, min_w=28, min_h=28, text_h=11.5)
        if style in (1, 3):
            r = 0.5 * (box[2] - box[0])
            for i in range(8):
                rad = math.radians(i * 45.0)
                c.line(
                    (cx + (r + 1.0) * math.cos(rad), cy + (r + 1.0) * math.sin(rad)),
                    (cx + (r + 5.5) * math.cos(rad), cy + (r + 5.5) * math.sin(rad)),
                )
    elif variant == "photocell":
        if style == 0:
            _draw_badged_symbol(c, "square", "PC", cx, cy, min_w=28, min_h=28, text_h=12.0)
        elif style in (1, 3):
            box = _draw_badged_symbol(c, "circle", "PC", cx, cy, min_w=28, min_h=28, text_h=11.5)
            r = 0.5 * (box[2] - box[0])
            for i in range(6):
                rad = math.radians(i * 60.0)
                c.line(
                    (cx + r * math.cos(rad), cy + r * math.sin(rad)),
                    (cx + (r + 5.0) * math.cos(rad), cy + (r + 5.0) * math.sin(rad)),
                )
        elif style == 2:
            r = 14.0
            c.circle((cx - 8, cy), r)
            c.chord_segment((cx - 8, cy), r, -90.0, 90.0, fill=True)
            c.text("PC", (cx + r + 5, cy), 11.0, font=cv2.FONT_HERSHEY_SIMPLEX)
        elif style == 4:
            _draw_badged_symbol(c, "diamond", "PC", cx, cy, min_w=32, min_h=32, text_h=11.5)
        else:
            box = _draw_badged_symbol(c, "square", "PC", cx, cy, min_w=28, min_h=28, text_h=12.0)
            _draw_wall_stem(c, cx, box[3], 6.5, with_bar=True)
    elif variant == "thermostat":
        if style == 0:
            _draw_badged_symbol(c, "circle", "T", cx, cy, min_w=28, min_h=28, text_h=13.5)
        elif style == 1:
            _draw_badged_symbol(c, "double_circle", "T", cx, cy, min_w=30, min_h=30, text_h=13.0)
        elif style == 2:
            _draw_badged_symbol(c, "octagon", "T", cx, cy, min_w=30, min_h=30, text_h=13.0)
        elif style == 3:
            _draw_badged_symbol(c, "round_rect", "TSTAT", cx, cy, min_w=42, min_h=22, text_h=10.5)
        else:
            box = _draw_badged_symbol(c, "circle", "T", cx, cy, min_w=28, min_h=28, text_h=13.5)
            _draw_wall_stem(c, cx, box[3], 6.5, with_bar=False)
    elif variant == "humidistat":
        lbl = ("H", "HSTAT", "H", "RH", "H", "H")[style % 6]
        sh = ("circle", "round_rect", "octagon", "circle", "diamond", "double_circle")[style % 6]
        box = _draw_badged_symbol(c, sh, lbl, cx, cy, min_w=28, min_h=26, text_h=12.5 if len(lbl) <= 2 else 10.0)
        if style == 5:
            _draw_wall_stem(c, cx, box[3], 6.5)
    elif variant == "co2_sensor":
        sh = ("circle", "octagon", "diamond", "square", "hexagon", "double_circle")[style % 6]
        box = _draw_badged_symbol(c, sh, "CO2", cx, cy, min_w=32, min_h=30, text_h=11.0)
        if style == 5:
            _draw_wall_stem(c, cx, box[3], 6.5)
    elif variant == "co_sensor":
        sh = ("circle", "square", "diamond", "octagon", "double_circle", "round_rect")[style % 6]
        box = _draw_badged_symbol(c, sh, "CO", cx, cy, min_w=28, min_h=28, text_h=12.0)
        if style == 5:
            _draw_wall_stem(c, cx, box[3], 6.5)
    elif variant == "differential_pressure_sensor":
        lbl = ("DP", "dP", "DPS", "DP", "dP", "DP")[style % 6]
        sh = ("circle", "square", "rect", "diamond", "hexagon", "double_circle")[style % 6]
        box = _draw_badged_symbol(c, sh, lbl, cx, cy, min_w=30, min_h=28, text_h=11.5)
        if style in (1, 4):
            c.line((box[0] - 6, cy - 5), (box[0], cy - 5))
            c.line((box[0] - 6, cy + 5), (box[0], cy + 5))
    return c.render_crop()


# ---------------------------------------------------------------------------
# 4. Luminaires (20 base + 20 _emergency + battery_light + remote_head + 8 exit)
# ---------------------------------------------------------------------------

def _draw_luminaire_geometry(
    c: CadCanvas,
    base: str,
    cx: float,
    cy: float,
    style: int,
    *,
    em_mode: int | None = None,
) -> None:
    """Draw a base luminaire silhouette, optionally augmented with an emergency marker mode (0..5)."""
    s = style % 5

    def _fill_poly_em(pts: list[tuple[float, float]]) -> None:
        if em_mode in (0, 1, 4):
            c.poly(pts, fill=True)
        elif em_mode in (2, 5):
            c.hatch_poly(pts, step=4.2, angle_deg=45.0)

    def _apply_rect_em(x0: float, y0: float, x1: float, y1: float, *, tag_left: bool = False) -> None:
        if em_mode is None:
            return
        if em_mode == 0:
            _fill_poly_em([(x0, y0), (x1, y0), (x0, y1)])
        elif em_mode == 1:
            mx = 0.5 * (x0 + x1)
            _fill_poly_em([(x0, y0), (mx, y0), (mx, y1), (x0, y1)])
        elif em_mode == 2:
            _fill_poly_em([(x0, y0), (x1, y0), (x1, y1), (x0, y1)])
        elif em_mode == 3:
            tx = (x0 - 13.0) if tag_left else (x1 + 12.0)
            c.text("EM", (tx, cy), 9.5, font=cv2.FONT_HERSHEY_SIMPLEX)
        elif em_mode == 4:
            _fill_poly_em([(x0, y0), (x0 + 0.38 * (x1 - x0), y0), (x0, y1)])
            tx = (x0 - 13.0) if tag_left else (x1 + 12.0)
            c.text("EM", (tx, cy), 9.5, font=cv2.FONT_HERSHEY_SIMPLEX)
        else:
            mx = 0.5 * (x0 + x1)
            c.hatch_poly([(x0, y0), (mx, y0), (mx, y1), (x0, y1)], step=4.0, angle_deg=45.0)
            c.circle((x1 - 4.5, y0 + 4.5), 2.8, fill=True)

    def _apply_circle_em(ccx: float, ccy: float, r: float, *, tag_left: bool = False, tag: bool = True) -> None:
        if em_mode is None:
            return
        if em_mode in (0, 1):
            c.chord_segment((ccx, ccy), r, 135.0, 315.0, fill=True)
        elif em_mode == 2:
            c.hatch_poly(_circle_pts((ccx, ccy), r, 24), step=3.8, angle_deg=45.0)
        elif em_mode == 3:
            if tag:
                tx = (ccx - r - 12.0) if tag_left else (ccx + r + 11.5)
                c.text("EM", (tx, ccy), 9.5, font=cv2.FONT_HERSHEY_SIMPLEX)
        elif em_mode == 4:
            c.chord_segment((ccx, ccy), r, 90.0, 270.0, fill=True)
            if tag:
                tx = (ccx - r - 12.0) if tag_left else (ccx + r + 11.5)
                c.text("EM", (tx, ccy), 9.5, font=cv2.FONT_HERSHEY_SIMPLEX)
        else:
            c.wedge_sector((ccx, ccy), r, 180.0, 360.0, fill=True)
            c.circle((ccx + r + 4.0, ccy - r * 0.5), 2.5, fill=True)

    if base in ("troffer_2x4", "troffer_2x2", "troffer_1x4"):
        dims = {
            "troffer_2x4": (84.0, 42.0),
            "troffer_2x2": (48.0, 48.0),
            "troffer_1x4": (116.0, 29.0),
        }[base]
        bw, bh = dims
        x0, y0, x1, y1 = cx - bw / 2.0, cy - bh / 2.0, cx + bw / 2.0, cy + bh / 2.0
        _apply_rect_em(x0, y0, x1, y1)
        c.rect((x0, y0), (x1, y1))
        if s == 0:
            c.line((x0, y0), (x1, y1))
        elif s == 1:
            c.line((x0, y0), (x1, y1))
            c.line((x0, y1), (x1, y0))
        elif s == 2:
            if base == "troffer_1x4":
                for f in (0.25, 0.5, 0.75):
                    lx = x0 + bw * f
                    c.line((lx, y0), (lx, y1), t=c.stroke * 0.75)
            else:
                c.circle((cx, cy), bh * 0.28)
        elif s == 3:
            cols = 4 if base == "troffer_1x4" else (3 if base == "troffer_2x4" else 2)
            for k in range(1, cols):
                lx = x0 + bw * (k / cols)
                c.line((lx, y0), (lx, y1), t=c.stroke * 0.7)
            if base == "troffer_2x2":
                c.line((x0, cy), (x1, cy), t=c.stroke * 0.7)
        else:
            inset = min(bw, bh) * 0.22
            c.rect((x0 + inset, y0 + inset), (x1 - inset, y1 - inset), t=c.stroke * 0.75)
    elif base == "linear":
        bw, bh = 124.0, 18.0
        x0, y0, x1, y1 = cx - bw / 2.0, cy - bh / 2.0, cx + bw / 2.0, cy + bh / 2.0
        _apply_rect_em(x0, y0, x1, y1)
        c.rect((x0, y0), (x1, y1))
        if s == 1:
            c.line((x0, cy), (x1, cy), t=c.stroke * 0.7)
        elif s == 2:
            c.line((x0 + 12, y0), (x0 + 12, y1), t=c.stroke * 0.7)
            c.line((x1 - 12, y0), (x1 - 12, y1), t=c.stroke * 0.7)
        elif s == 3:
            c.circle((cx - 32, cy), 2.2, fill=True)
            c.circle((cx + 32, cy), 2.2, fill=True)
        elif s == 4:
            c.line((x0, y0 + 4.5), (x1, y0 + 4.5), t=c.stroke * 0.6)
            c.line((x0, y1 - 4.5), (x1, y1 - 4.5), t=c.stroke * 0.6)
    elif base == "strip_luminaire":
        bw = 116.0
        x0, x1 = cx - bw / 2.0, cx + bw / 2.0
        if s == 2:
            c.rect((x0, cy - 4.0), (x1, cy + 4.0))
            if em_mode in (0, 1, 4):
                c.rect((x0, cy - 4.0), (cx, cy + 4.0), fill=True)
            elif em_mode in (2, 5):
                c.hatch_poly([(x0, cy - 4.0), (x1, cy - 4.0), (x1, cy + 4.0), (x0, cy + 4.0)], step=3.5)
        else:
            c.line((x0, cy - (2.0 if s in (1, 4) else 0.0)), (x1, cy - (2.0 if s in (1, 4) else 0.0)))
            if s in (1, 4):
                c.line((x0, cy + 2.0), (x1, cy + 2.0))
            if em_mode in (0, 1, 4):
                c.rect((x0, cy - 4.5), (cx, cy + 4.5), fill=True)
            elif em_mode in (2, 5):
                c.hatch_poly([(x0, cy - 4.5), (cx, cy - 4.5), (cx, cy + 4.5), (x0, cy + 4.5)], step=3.5)
        c.line((x0, cy - 7.0), (x0, cy + 7.0))
        c.line((x1, cy - 7.0), (x1, cy + 7.0))
        if s == 3:
            c.circle((cx, cy), 4.5, fill=(em_mode is not None))
        elif s == 4:
            for fx in (cx - 28.0, cx, cx + 28.0):
                c.line((fx, cy - 4.5), (fx, cy + 4.5), t=c.stroke * 0.7)
        if em_mode in (3, 4):
            c.text("EM", (x1 + 12.0, cy), 9.5, font=cv2.FONT_HERSHEY_SIMPLEX)
        elif em_mode == 5:
            c.circle((cx, cy - 9.0), 2.8, fill=True)
    elif base == "downlight":
        r = 16.0
        _apply_circle_em(cx, cy, r)
        c.circle((cx, cy), r, t=c.stroke * (1.3 if s == 3 else 1.0))
        if s == 1:
            c.circle((cx, cy), 3.0, fill=True)
        elif s == 2:
            c.line((cx - 5.5, cy), (cx + 5.5, cy), t=c.stroke * 0.8)
            c.line((cx, cy - 5.5), (cx, cy + 5.5), t=c.stroke * 0.8)
        elif s == 4:
            c.line((cx - r * 0.75, cy), (cx + r * 0.75, cy), t=c.stroke * 0.7)
            c.line((cx, cy - r * 0.75), (cx, cy + r * 0.75), t=c.stroke * 0.7)
    elif base == "recessed_downlight":
        r = 17.0
        _apply_circle_em(cx, cy, r)
        c.circle((cx, cy), r)
        if s == 0:
            c.circle((cx, cy), r * 0.58, t=c.stroke * 0.8)
        elif s == 1:
            d = r * 1.18 * 0.707
            c.line((cx - d, cy + d), (cx + d, cy - d))
        elif s == 2:
            for deg in (0.0, 90.0, 180.0, 270.0):
                rad = math.radians(deg)
                c.line(
                    (cx + r * math.cos(rad), cy + r * math.sin(rad)),
                    (cx + (r + 5.0) * math.cos(rad), cy + (r + 5.0) * math.sin(rad)),
                )
        elif s == 3:
            c.dashed_circle((cx, cy), r + 4.2, n_dashes=14)
        else:
            for deg in (45.0, 135.0, 225.0, 315.0):
                rad = math.radians(deg)
                c.line(
                    (cx + r * 0.45 * math.cos(rad), cy + r * 0.45 * math.sin(rad)),
                    (cx + r * math.cos(rad), cy + r * math.sin(rad)),
                )
    elif base == "surface_mounted":
        if s == 1:
            r = 18.0
            _apply_circle_em(cx, cy, r)
            c.circle((cx, cy), r)
            c.circle((cx, cy), r - 3.2, t=c.stroke * 0.7)
            c.line((cx - r * 0.6, cy), (cx + r * 0.6, cy), t=c.stroke * 0.7)
            c.line((cx, cy - r * 0.6), (cx, cy + r * 0.6), t=c.stroke * 0.7)
        elif s == 3:
            x0, y0, x1, y1 = cx - 22.0, cy - 22.0, cx + 22.0, cy + 22.0
            _apply_rect_em(x0, y0, x1, y1)
            c.rect((x0, y0), (x1, y1))
            c.rect((x0 + 3.5, y0 + 3.5), (x1 - 3.5, y1 - 3.5), t=c.stroke * 0.7)
        else:
            bw, bh = 58.0, 26.0
            x0, y0, x1, y1 = cx - bw / 2.0, cy - bh / 2.0, cx + bw / 2.0, cy + bh / 2.0
            _apply_rect_em(x0, y0, x1, y1)
            if s == 2:
                c.round_rect((x0, y0), (x1, y1), 7.5)
            else:
                c.rect((x0, y0), (x1, y1))
            if s == 0:
                c.line((x0 + 3, cy), (x1 - 3, cy), t=c.stroke * 0.75)
            elif s == 4:
                c.line((x0, cy - 4.5), (x1, cy - 4.5), t=c.stroke * 0.65)
                c.line((x0, cy + 4.5), (x1, cy + 4.5), t=c.stroke * 0.65)
    elif base == "pendant":
        if s in (0, 2, 3):
            r = 16.5
            _apply_circle_em(cx, cy, r)
            c.circle((cx, cy), r)
            c.circle((cx, cy), 3.4, fill=True)
            if s == 2:
                c.line((cx - r, cy), (cx + r, cy), t=c.stroke * 0.7)
                c.line((cx, cy - r), (cx, cy + r), t=c.stroke * 0.7)
            elif s == 3:
                c.line((cx, cy - r), (cx, cy - r - 9.0))
                c.circle((cx, cy - r - 11.0), 2.4, fill=True)
        else:
            bw, bh = 62.0, 20.0
            x0, y0, x1, y1 = cx - bw / 2.0, cy - bh / 2.0, cx + bw / 2.0, cy + bh / 2.0
            _apply_rect_em(x0, y0, x1, y1)
            c.rect((x0, y0), (x1, y1))
            c.circle((cx - 14.0, cy), 2.8, fill=True)
            c.circle((cx + 14.0, cy), 2.8, fill=True)
    elif base == "wall_mounted":
        wall_y = cy + 16.0
        c.line((cx - 22.0, wall_y), (cx + 22.0, wall_y), t=c.stroke * 1.1)
        if s in (0, 3):
            r = 14.5
            c.line((cx, wall_y), (cx, cy + r))
            _apply_circle_em(cx, cy, r)
            c.circle((cx, cy), r)
            if s == 3:
                c.circle((cx, cy), 3.5, fill=True)
        elif s == 1:
            x0, y0, x1, y1 = cx - 22.0, cy - 8.0, cx + 22.0, cy + 7.0
            c.line((cx - 12.0, y1), (cx - 12.0, wall_y))
            c.line((cx + 12.0, y1), (cx + 12.0, wall_y))
            _apply_rect_em(x0, y0, x1, y1)
            c.rect((x0, y0), (x1, y1))
        else:
            bw = 42.0 if s == 2 else 30.0
            bh = 16.0 if s == 2 else 20.0
            x0, y0, x1, y1 = cx - bw / 2.0, cy - bh / 2.0, cx + bw / 2.0, cy + bh / 2.0
            c.line((cx, y1), (cx, wall_y))
            _apply_rect_em(x0, y0, x1, y1)
            if s == 4:
                c.round_rect((x0, y0), (x1, y1), 6.0)
            else:
                c.rect((x0, y0), (x1, y1))
    elif base == "wall_sconce":
        wall_y = cy + 12.0
        c.line((cx - 20.0, wall_y), (cx + 20.0, wall_y), t=c.stroke * 1.1)
        if s in (0, 2, 3):
            r = 14.5
            base_y = wall_y - (5.0 if s == 2 else 0.0)
            if s == 2:
                c.line((cx, wall_y), (cx, base_y))
            if em_mode in (0, 1, 4):
                c.wedge_sector((cx, base_y), r, 180.0, 270.0 if em_mode != 1 else 360.0, fill=True)
            elif em_mode in (2, 5):
                pts = [
                    (cx + r * math.cos(math.radians(a)), base_y + r * math.sin(math.radians(a)))
                    for a in range(180, 361, 15)
                ]
                c.hatch_poly(pts, step=3.5)
            c.ellipse((cx, base_y), r, r, start=180.0, end=360.0)
            c.line((cx - r, base_y), (cx + r, base_y))
            if s == 3:
                c.ellipse((cx, base_y), r * 0.55, r * 0.55, start=180.0, end=360.0, t=c.stroke * 0.7)
            if em_mode in (3, 4):
                c.text("EM", (cx + r + 11.0, base_y - 6.0), 9.5, font=cv2.FONT_HERSHEY_SIMPLEX)
            elif em_mode == 5:
                c.circle((cx, base_y - r - 4.0), 2.5, fill=True)
        elif s == 1:
            pts = [(cx - 8.0, wall_y), (cx + 8.0, wall_y), (cx + 15.0, cy - 10.0), (cx - 15.0, cy - 10.0)]
            if em_mode in (0, 1, 4):
                _fill_poly_em([(cx - 8.0, wall_y), (cx, wall_y), (cx, cy - 10.0), (cx - 15.0, cy - 10.0)])
            elif em_mode in (2, 5):
                c.hatch_poly(pts, step=3.8)
            c.poly(pts, closed=True)
            if em_mode in (3, 4):
                c.text("EM", (cx + 24.0, cy), 9.5, font=cv2.FONT_HERSHEY_SIMPLEX)
        else:
            pts = [(cx, wall_y), (cx - 12.0, cy - 4.0), (cx, cy - 16.0), (cx + 12.0, cy - 4.0)]
            if em_mode in (0, 1, 4):
                _fill_poly_em([(cx, wall_y), (cx - 12.0, cy - 4.0), (cx, cy - 16.0)])
            elif em_mode in (2, 5):
                c.hatch_poly(pts, step=3.8)
            c.poly(pts, closed=True)
            if em_mode in (3, 4):
                c.text("EM", (cx + 21.0, cy - 4.0), 9.5, font=cv2.FONT_HERSHEY_SIMPLEX)
    elif base == "wallpack":
        wx = cx - 12.0
        c.line((wx, cy - 20.0), (wx, cy + 20.0), t=c.stroke * 1.25)
        x0, y0, x1, y1 = wx, cy - 12.0, wx + 22.0, cy + 12.0
        if s == 2:
            pts = [(wx, cy - 13.0), (wx + 20.0, cy - 9.0), (wx + 20.0, cy + 9.0), (wx, cy + 13.0)]
            if em_mode in (0, 1, 4):
                _fill_poly_em([(wx, cy - 13.0), (wx + 20.0, cy - 9.0), (wx, cy + 13.0)])
            elif em_mode in (2, 5):
                c.hatch_poly(pts, step=3.8)
            c.poly(pts, closed=True)
            if em_mode in (3, 4):
                c.text("EM", (wx - 13.0, cy), 9.5, font=cv2.FONT_HERSHEY_SIMPLEX)
        else:
            _apply_rect_em(x0, y0, x1, y1, tag_left=True)
            c.rect((x0, y0), (x1, y1))
            if s == 3:
                c.ellipse((x1, cy), 5.0, 9.0, start=-90.0, end=90.0, t=c.stroke * 0.75)
        for dy in (-10.0, 0.0, 10.0):
            p0 = (x1 + 4.0, cy + dy * 0.45)
            p1 = (x1 + 21.0, cy + dy * 1.15)
            if s in (0, 2, 4):
                c.arrow(p0, p1, head_len=5.5, head_w=3.0)
            else:
                c.line(p0, p1)
    elif base == "chandelier":
        r_outer = 23.0
        n_arms = 6 if s in (0, 2, 4) else 8
        _apply_circle_em(cx, cy, 8.5, tag=False)   # the EM tag goes outside the arms
        c.circle((cx, cy), 8.5)
        if s == 2:
            c.circle((cx, cy), 14.5, t=c.stroke * 0.75)
        for k in range(n_arms):
            ang = math.radians(k * (360.0 / n_arms))
            c.line(
                (cx + 8.5 * math.cos(ang), cy + 8.5 * math.sin(ang)),
                (cx + r_outer * math.cos(ang), cy + r_outer * math.sin(ang)),
                t=c.stroke * 0.8,
            )
            tip = (cx + (r_outer + 3.0) * math.cos(ang), cy + (r_outer + 3.0) * math.sin(ang))
            if s != 1:
                fill_tip = (em_mode is not None and k % 2 == 0 and em_mode in (0, 1, 4))
                c.circle(tip, 3.2, fill=fill_tip)
        if s == 1:
            c.circle((cx, cy), r_outer, t=c.stroke * 0.8)
        if em_mode in (3, 4):
            c.text("EM", (cx + r_outer + 20.0, cy), 9.5, font=cv2.FONT_HERSHEY_SIMPLEX)
    elif base == "high_bay":
        hw = 21.0
        x0, y0, x1, y1 = cx - hw, cy - hw, cx + hw, cy + hw
        if s in (1, 4):
            pts = c.regular_poly((cx, cy), hw * 1.08, 8 if s == 1 else 4, rot_deg=22.5 if s == 1 else 45.0)
            if em_mode in (0, 1, 4):
                _fill_poly_em([pts[0], pts[1], pts[2], (cx, cy)])
            elif em_mode in (2, 5):
                c.hatch_poly(pts, step=4.2)
            c.circle((cx, cy), hw * 0.45)
            c.text("HB", (cx, cy), 9.5, font=cv2.FONT_HERSHEY_SIMPLEX)
            if em_mode in (3, 4):
                c.text("EM", (cx + hw + 13.0, cy), 9.5, font=cv2.FONT_HERSHEY_SIMPLEX)
        else:
            _apply_rect_em(x0, y0, x1, y1)
            c.rect((x0, y0), (x1, y1))
            c.circle((cx, cy), hw * 0.48)
            if s == 0:
                c.line((x0, y0), (x1, y1), t=c.stroke * 0.7)
                c.line((x0, y1), (x1, y0), t=c.stroke * 0.7)
            elif s == 2:
                for sx in (-1, 1):
                    for sy in (-1, 1):
                        c.line(
                            (cx + sx * hw, cy + sy * (hw * 0.5)),
                            (cx + sx * (hw * 0.5), cy + sy * hw),
                            t=c.stroke * 0.8,
                        )
            else:
                c.line((x0, cy), (x1, cy), t=c.stroke * 0.7)
                c.line((cx, y0), (cx, y1), t=c.stroke * 0.7)
    elif base == "low_bay":
        hw = 19.0
        x0, y0, x1, y1 = cx - hw, cy - hw, cx + hw, cy + hw
        if s in (2, 4):
            pts = c.regular_poly((cx, cy), hw * 1.05, 8 if s == 2 else 4, rot_deg=22.5 if s == 2 else 45.0)
            if em_mode in (0, 1, 4):
                _fill_poly_em([pts[0], pts[1], pts[2], (cx, cy)])
            elif em_mode in (2, 5):
                c.hatch_poly(pts, step=4.2)
            c.text("LB", (cx, cy), 10.5, font=cv2.FONT_HERSHEY_SIMPLEX)
            if em_mode in (3, 4):
                c.text("EM", (cx + hw + 13.0, cy), 9.5, font=cv2.FONT_HERSHEY_SIMPLEX)
        else:
            _apply_rect_em(x0, y0, x1, y1)
            c.rect((x0, y0), (x1, y1))
            c.circle((cx, cy), hw * 0.42)
            if s == 1:
                c.line((x0, cy), (x1, cy), t=c.stroke * 0.75)
            elif s == 3:
                for off in (-8.0, 8.0):
                    c.line((cx + off, y0 + 3), (cx + off, y1 - 3), t=c.stroke * 0.65)
    elif base == "track_light":
        rw = 104.0
        x0, x1 = cx - rw / 2.0, cx + rw / 2.0
        c.line((x0, cy), (x1, cy), t=c.stroke * 1.1)
        if s == 2:
            c.line((x0, cy - 4.0), (x1, cy - 4.0), t=c.stroke * 0.85)
        xs = (-32.0, 0.0, 32.0) if s != 3 else (-36.0, -12.0, 12.0, 36.0)
        if s in (2, 4):
            xs = (-24.0, 24.0)
        for idx, ox in enumerate(xs):
            hx = cx + ox
            fill_head = (em_mode in (0, 1, 4) and idx == 0)
            if s in (0, 3):
                pts = [(hx - 8.0, cy - 11.0), (hx + 8.0, cy - 11.0), (hx, cy)]
                c.poly(pts, fill=fill_head)
                if em_mode in (2, 5) and idx == 0:
                    c.hatch_poly(pts, step=3.2)
            elif s == 1:
                c.circle((hx, cy), 6.0, fill=fill_head)
                c.arrow((hx, cy), (hx + 8.0, cy + 11.0), head_len=4.5, head_w=2.6)
            elif s == 2:
                c.rect((hx - 6.0, cy), (hx + 6.0, cy + 12.0), fill=fill_head)
            else:
                c.circle((hx, cy), 5.5, fill=fill_head)
                c.poly([(hx, cy + 5.5), (hx - 6.0, cy + 15.0), (hx + 6.0, cy + 15.0)], fill=fill_head)
        if em_mode in (3, 4):
            c.text("EM", (x1 + 12.0, cy), 9.5, font=cv2.FONT_HERSHEY_SIMPLEX)
        elif em_mode == 5:
            c.circle((x0 + 6.0, cy - 15.0), 2.8, fill=True)
    elif base == "spotlight":
        if s == 3:
            x0, y0, x1, y1 = cx - 22.0, cy - 9.0, cx - 2.0, cy + 9.0
            _apply_rect_em(x0, y0, x1, y1, tag_left=True)
            c.rect((x0, y0), (x1, y1))
            c.poly([(x1, cy), (x1 + 18.0, cy - 10.0), (x1 + 18.0, cy + 10.0)], fill=False)
        else:
            r = 13.0
            _apply_circle_em(cx - 8.0, cy, r, tag_left=True)
            c.circle((cx - 8.0, cy), r)
            cone = [(cx - 8.0 + r, cy), (cx + 22.0, cy - 10.0), (cx + 22.0, cy + 10.0)]
            c.poly(cone, fill=(em_mode in (0, 1) and s == 0))
            if s == 1:
                cone2 = [(cx - 8.0, cy + r), (cx - 18.0, cy + r + 15.0), (cx + 2.0, cy + r + 15.0)]
                c.poly(cone2, fill=False)
            elif s == 2:
                c.arrow((cx + 22.0, cy), (cx + 33.0, cy), head_len=5.5, head_w=3.0)
            elif s == 4:
                for dy in (-9.0, 0.0, 9.0):
                    c.line((cx + 24.0, cy + dy * 0.5), (cx + 33.0, cy + dy), t=c.stroke * 0.7)
    elif base == "pole_luminaire":
        px = cx - 22.0
        if s != 3:
            if s in (1, 4):
                c.rect((px - 5.0, cy - 5.0), (px + 5.0, cy + 5.0), fill=(em_mode is not None))
            else:
                c.circle((px, cy), 5.0, fill=(em_mode is not None))
        if s == 0:
            c.line((px + 5.0, cy), (px + 15.0, cy))
            x0, y0, x1, y1 = px + 15.0, cy - 9.0, px + 47.0, cy + 9.0
            _apply_rect_em(x0, y0, x1, y1)
            c.rect((x0, y0), (x1, y1))
        elif s == 1:
            for sgn in (-1.0, 1.0):
                ax0 = cx + sgn * 12.0
                c.line((cx + sgn * 5.0, cy), (ax0, cy))
                hx0, hx1 = min(ax0, cx + sgn * 38.0), max(ax0, cx + sgn * 38.0)
                if em_mode in (0, 1, 4) and sgn < 0:
                    c.rect((hx0, cy - 8.0), (hx1, cy + 8.0), fill=True)
                elif em_mode in (2, 5) and sgn < 0:
                    c.hatch_poly([(hx0, cy - 8.0), (hx1, cy - 8.0), (hx1, cy + 8.0), (hx0, cy + 8.0)], step=3.5)
                c.rect((hx0, cy - 8.0), (hx1, cy + 8.0))
            if em_mode in (3, 4):
                c.text("EM", (cx + 50.0, cy), 9.5, font=cv2.FONT_HERSHEY_SIMPLEX)
        elif s == 2:
            c.line((px + 5.0, cy), (px + 16.0, cy))
            pts = [
                (px + 16.0, cy - 4.0),
                (px + 46.0, cy - 11.0),
                (px + 52.0, cy),
                (px + 46.0, cy + 11.0),
                (px + 16.0, cy + 4.0),
            ]
            if em_mode in (0, 1, 4):
                _fill_poly_em([(px + 16.0, cy - 4.0), (px + 46.0, cy - 11.0), (px + 52.0, cy), (px + 16.0, cy)])
            elif em_mode in (2, 5):
                c.hatch_poly(pts, step=3.8)
            c.poly(pts, closed=True)
            if em_mode in (3, 4):
                c.text("EM", (px + 64.0, cy), 9.5, font=cv2.FONT_HERSHEY_SIMPLEX)
        elif s == 3:
            c.circle((cx, cy), 5.0)
            for deg in (0.0, 90.0, 180.0, 270.0):
                rad = math.radians(deg)
                bx, by = cx + 18.0 * math.cos(rad), cy + 18.0 * math.sin(rad)
                c.line((cx + 5.0 * math.cos(rad), cy + 5.0 * math.sin(rad)), (bx, by))
                c.rect((bx - 6.0, by - 5.0), (bx + 6.0, by + 5.0), fill=(em_mode in (0, 1) and deg == 0.0))
            if em_mode in (3, 4):
                c.text("EM", (cx + 35.0, cy), 9.5, font=cv2.FONT_HERSHEY_SIMPLEX)
        else:
            _apply_circle_em(cx, cy, 15.0)
            c.circle((cx, cy), 15.0)
            c.rect((cx - 4.5, cy - 4.5), (cx + 4.5, cy + 4.5))
    elif base == "pole_floodlight":
        px = cx - 18.0
        c.circle((px, cy), 5.0, fill=True)

        def _draw_flood(angle_deg: float, filled: bool = False) -> None:
            rad = math.radians(angle_deg)
            ux, uy = math.cos(rad), math.sin(rad)
            nx, ny = -uy, ux
            p0 = (px + ux * 7.0, cy + uy * 7.0)
            c.line((px, cy), p0)
            p1 = (px + ux * 28.0 + nx * 10.0, cy + uy * 28.0 + ny * 10.0)
            p2 = (px + ux * 28.0 - nx * 10.0, cy + uy * 28.0 - ny * 10.0)
            pts = [p0, p1, p2]
            if filled:
                c.poly(pts, fill=True)
            elif em_mode in (2, 5):
                c.hatch_poly(pts, step=3.5)
            c.poly(pts, closed=True)
            if s in (0, 2):
                for off in (-6.0, 0.0, 6.0):
                    r0 = (px + ux * 31.0 + nx * off, cy + uy * 31.0 + ny * off)
                    r1 = (px + ux * 40.0 + nx * off * 1.4, cy + uy * 40.0 + ny * off * 1.4)
                    c.line(r0, r1, t=c.stroke * 0.7)

        angles = {0: (0.0,), 1: (-32.0, 32.0), 2: (0.0,), 3: (-45.0, 45.0), 4: (-40.0, 0.0, 40.0)}[s]
        for idx, ang in enumerate(angles):
            _draw_flood(ang, filled=(em_mode in (0, 1, 4) and idx == 0))
        if em_mode in (3, 4):
            c.text("EM", (px - 15.0, cy), 9.5, font=cv2.FONT_HERSHEY_SIMPLEX)
    elif base == "vanity":
        wall_y = cy + 12.0
        bw = 78.0
        x0, x1 = cx - bw / 2.0, cx + bw / 2.0
        c.line((x0 - 4.0, wall_y), (x1 + 4.0, wall_y), t=c.stroke * 1.1)
        if s in (0, 4):
            rx0, ry0, rx1, ry1 = x0, cy - 8.0, x1, cy + 6.0
            _apply_rect_em(rx0, ry0, rx1, ry1)
            c.rect((rx0, ry0), (rx1, ry1))
            c.line((cx - 18.0, ry1), (cx - 18.0, wall_y))
            c.line((cx + 18.0, ry1), (cx + 18.0, wall_y))
            for bx in (cx - 24.0, cx, cx + 24.0):
                c.circle((bx, cy - 1.0), 3.0, fill=(em_mode in (0, 1, 4) and bx < cx))
        elif s in (1, 3):
            c.line((cx, wall_y), (cx, cy + 5.0))
            _apply_rect_em(x0, cy - 5.0, x1, cy + 5.0)
            c.round_rect((x0, cy - 5.0), (x1, cy + 5.0), 4.0)
            c.line((x0, cy - 8.0), (x0, cy + 8.0))
            c.line((x1, cy - 8.0), (x1, cy + 8.0))
        else:
            c.line((x0, cy), (x1, cy))
            for idx, bx in enumerate((-27.0, -9.0, 9.0, 27.0)):
                c.circle((cx + bx, cy), 5.0, fill=(em_mode in (0, 1, 4) and idx < 2))
            if em_mode in (3, 4):
                c.text("EM", (x1 + 14.0, cy), 9.5, font=cv2.FONT_HERSHEY_SIMPLEX)


def _draw_exit_sign(variant: str, style: int, rng: np.random.Generator) -> np.ndarray:
    """Render exit sign face/arrow variants with exact face counts (1 vs 2) and arrow counts (0, 1, 2, 4)."""
    c, cx, cy = _make_canvas(rng, 220, 180)
    if variant == "exit_emergency_combo":
        if style in (0, 2, 4):
            bw, bh = 56.0, 24.0
            x0, y0, x1, y1 = cx - bw / 2.0, cy - bh / 2.0 + 6.0, cx + bw / 2.0, cy + bh / 2.0 + 6.0
            c.rect((x0, y0), (x1, y1))
            if style == 2:
                c.rect((x0, y1 - 5.0), (x1, y1), fill=True)
                c.text("EXIT", (cx, cy + 3.5), 11.0, font=cv2.FONT_HERSHEY_SIMPLEX)
            else:
                c.text("EXIT", (cx, cy + 6.0), 12.0, font=cv2.FONT_HERSHEY_SIMPLEX)
            for sgn in (-1.0, 1.0):
                hx = cx + sgn * 18.0
                c.line((hx, y0), (hx, y0 - 6.0))
                pts = [(hx, y0 - 6.0), (hx + sgn * 12.0, y0 - 16.0), (hx - sgn * 2.0, y0 - 18.0)]
                c.poly(pts, fill=(style != 4))
            if style == 4:
                c.arrow((x0 - 2.0, cy + 6.0), (x0 - 16.0, cy + 6.0), head_len=6.0, head_w=3.2)
                c.arrow((x1 + 2.0, cy + 6.0), (x1 + 16.0, cy + 6.0), head_len=6.0, head_w=3.2)
        else:
            r = 16.0
            c.rect((cx - 28.0, cy - 16.0), (cx + 28.0, cy - 4.0))
            c.circle((cx, cy + 2.0), r)
            d = r * 0.707
            c.line((cx - d, cy + 2.0 - d), (cx + d, cy + 2.0 + d))
            c.line((cx - d, cy + 2.0 + d), (cx + d, cy + 2.0 - d))
            c.wedge_sector((cx, cy + 2.0), r, 45.0, 135.0, fill=True)
            if style == 3:
                c.wedge_sector((cx, cy + 2.0), r, 225.0, 315.0, fill=True)
            for sgn in (-1.0, 1.0):
                bx = cx + sgn * 26.0
                c.poly([(bx, cy - 4.0), (bx + sgn * 14.0, cy + 10.0), (bx + sgn * 18.0, cy - 6.0)], fill=(style == 1))
        return c.render_crop()

    if variant == "exit_sign":
        if style in (0, 3):
            bw, bh = 54.0, 24.0
            c.rect((cx - bw / 2.0, cy - bh / 2.0), (cx + bw / 2.0, cy + bh / 2.0))
            c.text("EXIT", (cx, cy), 12.5, font=cv2.FONT_HERSHEY_SIMPLEX)
            if style == 3:
                c.rect((cx - bw / 2.0, cy + bh / 2.0 - 5.0), (cx + bw / 2.0, cy + bh / 2.0), fill=True)
        elif style in (1, 4):
            r = 19.0
            c.circle((cx, cy), r)
            d = r * 0.707
            c.line((cx - d, cy - d), (cx + d, cy + d))
            c.line((cx - d, cy + d), (cx + d, cy - d))
            c.wedge_sector((cx, cy), r, 45.0, 135.0, fill=True)
            if style == 4:
                c.line((cx, cy - r), (cx, cy - r - 6.0))
                c.line((cx - 10.0, cy - r - 6.0), (cx + 10.0, cy - r - 6.0))
        elif style == 2:
            c.round_rect((cx - 28.0, cy - 13.0), (cx + 28.0, cy + 13.0), 11.0)
            c.text("EXIT", (cx, cy), 11.5, font=cv2.FONT_HERSHEY_SIMPLEX)
        else:
            r = 19.0
            c.circle((cx, cy), r)
            c.text("X", (cx, cy), 15.0)
        return c.render_crop()

    is_double_face = "double_face" in variant
    if "four_arrow" in variant:
        arrow_count = 4
    elif "double_arrow" in variant:
        arrow_count = 2
    elif "single_arrow" in variant:
        arrow_count = 1
    else:
        arrow_count = 0

    s = style % 6
    if s in (0, 1, 2, 3):
        r = float(rng.uniform(19.0, 22.0))
        c.circle((cx, cy), r)
        if s == 3:
            c.chord_segment((cx, cy), r, 35.0, 145.0, fill=True)
            if is_double_face:
                c.chord_segment((cx, cy), r, 215.0, 325.0, fill=True)
            c.text("EX", (cx, cy), 10.5, font=cv2.FONT_HERSHEY_SIMPLEX)
        else:
            d = r * 0.707
            c.line((cx - d, cy - d), (cx + d, cy + d))
            c.line((cx - d, cy + d), (cx + d, cy - d))
            if s == 1:
                c.wedge_sector((cx, cy), r, -45.0, 45.0, fill=True)
                if is_double_face:
                    c.wedge_sector((cx, cy), r, 135.0, 225.0, fill=True)
            else:
                c.wedge_sector((cx, cy), r, 45.0, 135.0, fill=True)
                if is_double_face:
                    c.wedge_sector((cx, cy), r, 225.0, 315.0, fill=True)
            if s == 2 and not is_double_face:
                c.line((cx, cy - r), (cx, cy - r - 8.0))
                c.line((cx - 10.0, cy - r - 8.0), (cx + 10.0, cy - r - 8.0))

        if arrow_count == 1:
            if s == 1:
                c.arrow((cx + r + 8.0, cy + r * 0.95), (cx + r + 8.0, cy - r * 0.95), head_len=6.5, head_w=3.5)
            elif s == 2:
                c.arrow((cx + r * 0.95, cy + r + 8.0), (cx - r * 0.95, cy + r + 8.0), head_len=6.5, head_w=3.5)
            else:
                c.arrow((cx - r * 0.95, cy + r + 8.0), (cx + r * 0.95, cy + r + 8.0), head_len=6.5, head_w=3.5)
        elif arrow_count == 2 and not is_double_face:
            if s == 1:
                c.arrow((cx + r + 8.0, cy), (cx + r + 8.0, cy - r - 5.0), head_len=6.0, head_w=3.4)
                c.arrow((cx + r + 8.0, cy), (cx + r + 8.0, cy + r + 5.0), head_len=6.0, head_w=3.4)
            elif s == 0:
                ay = cy + r + 7.5
                c.arrow((cx, ay), (cx - r - 6.0, ay), head_len=6.0, head_w=3.4)
                c.arrow((cx, ay), (cx + r + 6.0, ay), head_len=6.0, head_w=3.4)
            else:
                c.arrow((cx - r - 2.0, cy + 4.0), (cx - r - 18.0, cy + 4.0), head_len=6.0, head_w=3.4)
                c.arrow((cx + r + 2.0, cy + 4.0), (cx + r + 18.0, cy + 4.0), head_len=6.0, head_w=3.4)
        elif arrow_count == 2 and is_double_face:
            if s == 1:
                c.arrow((cx - r - 8.0, cy + r * 0.9), (cx - r - 8.0, cy - r * 0.9), head_len=6.5, head_w=3.4)
                c.arrow((cx + r + 8.0, cy - r * 0.9), (cx + r + 8.0, cy + r * 0.9), head_len=6.5, head_w=3.4)
            else:
                c.arrow((cx - r * 0.95, cy - r - 8.0), (cx + r * 0.95, cy - r - 8.0), head_len=6.5, head_w=3.4)
                dir_top = 1.0 if s == 0 else -1.0
                c.arrow(
                    (cx - dir_top * r * 0.95, cy + r + 8.0),
                    (cx + dir_top * r * 0.95, cy + r + 8.0),
                    head_len=6.5,
                    head_w=3.4,
                )
        elif arrow_count == 4:
            if s == 1:
                for x_off in (-r - 8.0, r + 8.0):
                    c.arrow((cx + x_off, cy), (cx + x_off, cy - r - 5.0), head_len=6.0, head_w=3.3)
                    c.arrow((cx + x_off, cy), (cx + x_off, cy + r + 5.0), head_len=6.0, head_w=3.3)
            elif s in (0, 3):
                for y_off in (-r - 7.5, r + 7.5):
                    c.arrow((cx, cy + y_off), (cx - r - 6.0, cy + y_off), head_len=6.0, head_w=3.3)
                    c.arrow((cx, cy + y_off), (cx + r + 6.0, cy + y_off), head_len=6.0, head_w=3.3)
            else:
                for y_off in (-r * 0.55, r * 0.55):
                    c.arrow((cx - r - 2.0, cy + y_off), (cx - r - 17.0, cy + y_off), head_len=5.8, head_w=3.2)
                    c.arrow((cx + r + 2.0, cy + y_off), (cx + r + 17.0, cy + y_off), head_len=5.8, head_w=3.2)
    elif s == 4:
        bw, bh = 52.0, 24.0
        x0, y0, x1, y1 = cx - bw / 2.0, cy - bh / 2.0, cx + bw / 2.0, cy + bh / 2.0
        c.rect((x0, y0), (x1, y1))
        bar_h = 5.5
        c.rect((x0, y1 - bar_h), (x1, y1), fill=True)
        if is_double_face:
            c.rect((x0, y0), (x1, y0 + bar_h), fill=True)
            c.text("EXIT", (cx, cy), 10.0, font=cv2.FONT_HERSHEY_SIMPLEX)
        else:
            c.text("EXIT", (cx, cy - 2.5), 11.0, font=cv2.FONT_HERSHEY_SIMPLEX)
        if arrow_count == 1:
            c.arrow((x1 + 2.0, cy), (x1 + 18.0, cy), head_len=6.2, head_w=3.4)
        elif arrow_count == 2 and not is_double_face:
            c.arrow((x0 - 2.0, cy), (x0 - 18.0, cy), head_len=6.2, head_w=3.4)
            c.arrow((x1 + 2.0, cy), (x1 + 18.0, cy), head_len=6.2, head_w=3.4)
        elif arrow_count == 2 and is_double_face:
            c.arrow((x1 + 2.0, y0 + 3.5), (x1 + 18.0, y0 + 3.5), head_len=5.8, head_w=3.1)
            c.arrow((x0 - 2.0, y1 - 3.5), (x0 - 18.0, y1 - 3.5), head_len=5.8, head_w=3.1)
        elif arrow_count == 4:
            for yy in (y0 + 3.5, y1 - 3.5):
                c.arrow((x0 - 2.0, yy), (x0 - 17.0, yy), head_len=5.5, head_w=3.0)
                c.arrow((x1 + 2.0, yy), (x1 + 17.0, yy), head_len=5.5, head_w=3.0)
    else:
        rx, ry = 28.0, 14.5
        c.round_rect((cx - rx, cy - ry), (cx + rx, cy + ry), ry * 0.92)
        c.rect((cx - rx * 0.68, cy + ry - 5.0), (cx + rx * 0.68, cy + ry), fill=True)
        if is_double_face:
            c.rect((cx - rx * 0.68, cy - ry), (cx + rx * 0.68, cy - ry + 5.0), fill=True)
            c.text("EXIT", (cx, cy), 9.5, font=cv2.FONT_HERSHEY_SIMPLEX)
        else:
            c.text("EXIT", (cx, cy - 2.0), 10.5, font=cv2.FONT_HERSHEY_SIMPLEX)
        if arrow_count == 1:
            c.arrow((cx + rx + 2.0, cy), (cx + rx + 18.0, cy), head_len=6.2, head_w=3.4)
        elif arrow_count == 2 and not is_double_face:
            c.arrow((cx - rx - 2.0, cy), (cx - rx - 18.0, cy), head_len=6.2, head_w=3.4)
            c.arrow((cx + rx + 2.0, cy), (cx + rx + 18.0, cy), head_len=6.2, head_w=3.4)
        elif arrow_count == 2 and is_double_face:
            c.arrow((cx - rx * 0.7, cy - ry - 6.5), (cx + rx * 0.7, cy - ry - 6.5), head_len=6.0, head_w=3.2)
            c.arrow((cx - rx * 0.7, cy + ry + 6.5), (cx + rx * 0.7, cy + ry + 6.5), head_len=6.0, head_w=3.2)
        elif arrow_count == 4:
            for yy in (cy - 5.0, cy + 5.0):
                c.arrow((cx - rx - 2.0, yy), (cx - rx - 17.0, yy), head_len=5.5, head_w=3.0)
                c.arrow((cx + rx + 2.0, yy), (cx + rx + 17.0, yy), head_len=5.5, head_w=3.0)

    return c.render_crop()


def _draw_luminaire(variant: str, style: int, rng: np.random.Generator) -> np.ndarray:
    if variant.startswith("exit_"):
        return _draw_exit_sign(variant, style, rng)

    c, cx, cy = _make_canvas(rng, 220, 180)
    if variant == "emergency_battery_light":
        bw, bh = 44.0, 18.0
        x0, y0, x1, y1 = cx - bw / 2.0, cy - bh / 2.0 + 6.0, cx + bw / 2.0, cy + bh / 2.0 + 6.0
        c.rect((x0, y0), (x1, y1))
        if style == 1:
            c.rect((x0, y0), (cx, y1), fill=True)
        elif style == 2:
            c.text("EM", (cx, cy + 6.0), 10.5, font=cv2.FONT_HERSHEY_SIMPLEX)
        elif style == 3:
            c.text("BU", (cx, cy + 6.0), 10.5, font=cv2.FONT_HERSHEY_SIMPLEX)
        elif style == 4:
            c.hatch_poly([(x0, y0), (x1, y0), (x1, y1), (x0, y1)], step=4.0)
        for sgn in (-1.0, 1.0):
            hx = cx + sgn * 16.0
            c.line((hx, y0), (hx, y0 - 5.0))
            pts = [(hx, y0 - 5.0), (hx + sgn * 13.0, y0 - 17.0), (hx - sgn * 3.0, y0 - 19.0)]
            c.poly(pts, fill=(style in (0, 1, 4)))
        return c.render_crop()

    if variant == "emergency_remote_head":
        c.line((cx - 16.0, cy + 12.0), (cx + 16.0, cy + 12.0), t=c.stroke * 1.15)
        if style == 1:
            c.line((cx, cy + 12.0), (cx, cy + 4.0))
            c.poly([(cx, cy + 4.0), (cx - 10.0, cy - 13.0), (cx + 10.0, cy - 13.0)], fill=True)
        else:
            for sgn in (-1.0, 1.0):
                c.line((cx, cy + 12.0), (cx + sgn * 10.0, cy + 2.0))
                pts = [(cx + sgn * 10.0, cy + 2.0), (cx + sgn * 22.0, cy - 8.0), (cx + sgn * 8.0, cy - 16.0)]
                c.poly(pts, fill=(style != 2))
            if style == 3:
                c.text("RH", (cx, cy + 22.0), 9.5, font=cv2.FONT_HERSHEY_SIMPLEX)
            elif style == 4:
                c.rect((cx - 16.0, cy + 12.0), (cx + 16.0, cy + 18.0))
        return c.render_crop()

    if variant.endswith("_emergency"):
        base = variant[:-len("_emergency")]
        _draw_luminaire_geometry(c, base, cx, cy, style % 5, em_mode=style % 6)
        return c.render_crop()

    if variant in BASE_LUMINAIRE_VARIANTS:
        _draw_luminaire_geometry(c, variant, cx, cy, style % 5, em_mode=None)
        return c.render_crop()

    raise ValueError(f"Unhandled luminaire variant: {variant}")


# ---------------------------------------------------------------------------
# 5. Distribution, Disconnects, Transformers, Junction Boxes, Motor Control
# ---------------------------------------------------------------------------

def _draw_panelboard(variant: str, style: int, rng: np.random.Generator) -> np.ndarray:
    c, cx, cy = _make_canvas(rng, 210, 150, sans_only=True)
    if variant == "lighting_control_panel":
        bw, bh = 54.0, 24.0
        x0, y0, x1, y1 = cx - bw / 2.0, cy - bh / 2.0, cx + bw / 2.0, cy + bh / 2.0
        if style == 0:
            _draw_badged_symbol(c, "rect", "LCP", cx, cy, min_w=46, min_h=24, text_h=12.5)
        elif style == 1:
            c.rect((x0, y0), (x1, y1))
            c.poly([(x0, y0), (x0 + 18.0, y0), (x0, y1)], fill=True)
            c.text("LCP", (cx + 7.0, cy), 11.5)
        elif style == 2:
            c.hatch_poly([(x0, y0), (x0 + 16.0, y0), (x0 + 16.0, y1), (x0, y1)], step=4.0)
            c.rect((x0, y0), (x1, y1))
            c.text("LCP", (cx + 8.0, cy), 11.5)
        elif style == 3:
            _draw_badged_symbol(c, "rect", "LCP", cx, cy, min_w=48, min_h=24, text_h=12.0, double_border=True)
        else:
            c.rect((x0, y0), (x1, y1))
            c.line((x0 - 6.0, y0), (x1 + 6.0, y0), t=c.stroke * 1.1)
            c.text("LCP", (cx, cy), 12.0)
    elif variant == "panelboard":
        bw, bh = float(rng.uniform(54.0, 74.0)), float(rng.uniform(16.0, 22.0))
        x0, y0, x1, y1 = cx - bw / 2.0, cy - bh / 2.0, cx + bw / 2.0, cy + bh / 2.0
        if style == 0:
            c.rect((x0, y0), (x1, y1), fill=True)
        elif style == 1:
            c.poly([(x0, y0), (x1, y0), (x1, y1)], fill=True)
            c.rect((x0, y0), (x1, y1))
        elif style == 2:
            c.hatch_poly([(x0, y0), (x1, y0), (x1, y1), (x0, y1)], step=4.2, angle_deg=45.0)
            c.rect((x0, y0), (x1, y1))
        elif style == 3:
            c.hatch_poly([(x0, y0), (x1, y0), (x1, y1), (x0, y1)], step=4.2, angle_deg=45.0)
            c.rect((x0, y0), (x1, y1))
            c.line((x0 - 8.0, y0), (x1 + 8.0, y0), t=c.stroke * 1.1)
        else:
            c.poly([(x0, y0), (x1, y0), (x1, y1), (x0, y1)], closed=True)
            c.poly([(x0, y1), (x1, y1), (x0, y0)], fill=True)
            c.text("PNL", (cx + bw * 0.5 + 15.0, cy), 9.5)
    elif variant == "automatic_transfer_switch":
        if style == 2:
            c.rect((cx - 24.0, cy - 13.0), (cx + 8.0, cy + 13.0))
            c.line((cx + 8.0, cy), (cx + 18.0, cy))
            c.line((cx + 18.0, cy), (cx + 18.0, cy - 11.0))
            c.text("ATS", (cx - 8.0, cy), 10.5)
        else:
            box = _draw_badged_symbol(
                c, "rect", "ATS", cx, cy, min_w=46, min_h=24, text_h=12.0, double_border=(style == 3)
            )
            if style == 1:
                c.poly([(box[0], box[1]), (box[0] + 10.0, box[1]), (box[0], box[3])], fill=True)
            elif style == 4:
                c.arrow((box[0] - 12.0, cy - 5.0), (box[0], cy - 5.0), head_len=4.5, head_w=2.5)
                c.arrow((box[0] - 12.0, cy + 5.0), (box[0], cy + 5.0), head_len=4.5, head_w=2.5)
    elif variant == "switchboard_switchgear":
        bw, bh = 88.0, 26.0
        x0, y0, x1, y1 = cx - bw / 2.0, cy - bh / 2.0, cx + bw / 2.0, cy + bh / 2.0
        if style == 0:
            c.hatch_poly([(x0, y0), (x1, y0), (x1, y1), (x0, y1)], step=4.5, angle_deg=-45.0)
            c.rect((x0, y0), (x1, y1))
        elif style == 1:
            c.rect((x0, y0), (x1, y1))
            for k in (1, 2, 3):
                lx = x0 + bw * (k / 4.0)
                c.line((lx, y0), (lx, y1), t=c.stroke * 0.8)
        elif style == 2:
            _draw_badged_symbol(c, "rect", "SWBD", cx, cy, min_w=76, min_h=26, text_h=12.0)
        elif style == 3:
            c.rect((x0, y0), (x1, y1), t=c.stroke * 1.25)
            c.rect((x0 + 3.5, y0 + 3.5), (x1 - 3.5, y1 - 3.5), t=c.stroke * 0.7)
            c.line((cx, y0), (cx, y1), t=c.stroke * 0.8)
        else:
            c.poly([(x0, y0), (x1, y0), (x0, y1)], fill=True)
            c.rect((x0, y0), (x1, y1))
            c.line((cx, y0), (cx, y1), t=c.stroke * 0.8)
    return c.render_crop()


def _draw_junction_box(variant: str, style: int, rng: np.random.Generator) -> np.ndarray:
    c, cx, cy = _make_canvas(rng, 160, 160)
    if variant == "junction_box":
        if style == 0:
            _draw_badged_symbol(c, "circle", "J", cx, cy, min_w=28, min_h=28, text_h=14.0)
        elif style == 1:
            _draw_badged_symbol(c, "square", "J", cx, cy, min_w=28, min_h=28, text_h=14.0)
        elif style == 2:
            _draw_badged_symbol(c, "circle", "JB", cx, cy, min_w=30, min_h=30, text_h=12.0)
        elif style == 3:
            box = _draw_badged_symbol(c, "circle", "J", cx, cy, min_w=26, min_h=26, text_h=13.5)
            r = 0.5 * (box[2] - box[0])
            for deg in (0.0, 90.0, 180.0, 270.0):
                rad = math.radians(deg)
                c.line(
                    (cx + r * math.cos(rad), cy + r * math.sin(rad)),
                    (cx + (r + 7.5) * math.cos(rad), cy + (r + 7.5) * math.sin(rad)),
                )
        else:
            _draw_badged_symbol(c, "square", "JB", cx, cy, min_w=30, min_h=30, text_h=12.0)
    elif variant == "pull_box":
        if style in (0, 2, 3):
            box = _draw_badged_symbol(
                c, "square", "PB", cx, cy, min_w=32, min_h=30, text_h=12.5, double_border=(style == 3)
            )
            if style == 2:
                c.line((box[0] - 7, cy), (box[0], cy))
                c.line((box[2], cy), (box[2] + 7, cy))
        elif style == 1:
            hw = 17.0
            c.rect((cx - hw, cy - hw), (cx + hw, cy + hw))
        else:
            hw = 18.0
            c.rect((cx - hw, cy - hw), (cx + hw, cy + hw))
            c.line((cx - hw, cy - hw), (cx + hw, cy + hw), t=c.stroke * 0.7)
            c.line((cx - hw, cy + hw), (cx + hw, cy - hw), t=c.stroke * 0.7)
            c.rect((cx - 9, cy - 7), (cx + 9, cy + 7), fill=True, color=0)
            c.text("PB", (cx, cy), 10.5, font=cv2.FONT_HERSHEY_SIMPLEX)
    return c.render_crop()


def _draw_transformer(variant: str, style: int, rng: np.random.Generator) -> np.ndarray:
    c, cx, cy = _make_canvas(rng, 180, 160, sans_only=True)
    if style == 0:
        _draw_badged_symbol(c, "square", "T", cx, cy, min_w=32, min_h=32, text_h=15.0)
    elif style == 1:
        _draw_badged_symbol(c, "square", "T", cx, cy, min_w=34, min_h=34, text_h=14.0, double_border=True)
    elif style == 2:
        _draw_badged_symbol(c, "rect", "XFMR", cx, cy, min_w=48, min_h=24, text_h=11.0)
    elif style == 3:
        box = _draw_badged_symbol(c, "square", "T", cx, cy, min_w=34, min_h=34, text_h=14.0)
        c.poly([(box[0], box[1]), (box[0] + 8, box[1]), (box[0], box[1] + 8)], fill=True)
    else:
        c.rect((cx - 22, cy - 16), (cx + 22, cy + 16))
        c.circle((cx - 6.5, cy), 8.5)
        c.circle((cx + 6.5, cy), 8.5)
    return c.render_crop()


def _draw_motor_control(variant: str, style: int, rng: np.random.Generator) -> np.ndarray:
    c, cx, cy = _make_canvas(rng, 180, 160, sans_only=True)
    if variant == "vfd":
        lbl = "VSD" if style == 4 else "VFD"
        box = _draw_badged_symbol(
            c,
            "square" if style == 3 else "rect",
            lbl,
            cx,
            cy,
            min_w=44,
            min_h=24,
            text_h=12.5,
            double_border=(style == 1),
        )
        if style == 2:
            c.line((box[0], box[3] - 4.0), (box[2], box[3] - 4.0), t=c.stroke * 0.65)
        elif style == 3:
            c.line((cx, box[1] - 5.0), (cx, box[1]))
            c.line((cx, box[3]), (cx, box[3] + 5.0))
    elif variant == "motor_starter":
        if style == 0:
            box = _draw_badged_symbol(c, "circle", "M", cx, cy, min_w=28, min_h=28, text_h=13.5)
            c.line((box[0] - 8.0, cy), (box[0], cy))
            c.line((box[2], cy), (box[2] + 8.0, cy))
        elif style in (1, 4):
            _draw_badged_symbol(c, "square", "MS", cx, cy, min_w=32, min_h=30, text_h=12.5, double_border=(style == 4))
        elif style == 2:
            box = _draw_badged_symbol(c, "square", "M", cx, cy, min_w=30, min_h=30, text_h=13.5)
            c.line((box[2], cy - 4.0), (box[2] + 6.5, cy - 11.0))
        else:
            _draw_badged_symbol(c, "circle", "MS", cx, cy, min_w=30, min_h=30, text_h=12.0)
    return c.render_crop()


def _draw_disconnect(variant: str, style: int, rng: np.random.Generator) -> np.ndarray:
    c, cx, cy = _make_canvas(rng, 190, 160, sans_only=True)
    bw, bh = 32.0, 24.0
    x0, y0, x1, y1 = cx - bw / 2.0, cy - bh / 2.0, cx + bw / 2.0, cy + bh / 2.0

    def _draw_blade_box(
        x_shift: float = 0.0,
        with_handle: bool = True,
        dbl: bool = False,
    ) -> tuple[float, float, float, float]:
        bx0, bx1 = x0 + x_shift, x1 + x_shift
        c.rect((bx0, y0), (bx1, y1))
        if dbl:
            c.rect((bx0 + 3.0, y0 + 3.0), (bx1 - 3.0, y1 - 3.0), t=c.stroke * 0.7)
        c.line((bx0, y1), (bx1, y0), t=c.stroke * 0.9)
        if with_handle:
            c.line((bx1, cy - 3.0), (bx1 + 6.5, cy - 11.0))
        return bx0, y0, bx1, y1

    if variant == "disconnect_switch":
        if style == 0:
            _draw_blade_box(with_handle=False)
        elif style == 1:
            _draw_blade_box(with_handle=True)
        elif style == 2:
            _draw_badged_symbol(c, "rect", "DISC", cx, cy, min_w=42, min_h=24, text_h=11.0)
        elif style == 3:
            box = _draw_badged_symbol(c, "square", "D", cx, cy, min_w=28, min_h=28, text_h=13.5)
            c.line((box[2], cy - 3.0), (box[2] + 7.0, cy - 11.0))
        else:
            _draw_blade_box(with_handle=True, dbl=True)
    elif variant == "fused":
        if style in (0, 3):
            box = _draw_blade_box(-8.0, with_handle=(style == 3))
            _place_tag(c, "F", box, 0, tag_h=11.5)
        elif style in (1, 4):
            lbl = "FD" if style == 1 else "FDS"
            box = _draw_badged_symbol(
                c, "rect", lbl, cx, cy, min_w=36, min_h=24, text_h=12.0, double_border=(style == 4)
            )
            c.line((box[2], cy - 3.0), (box[2] + 6.5, cy - 11.0))
        else:
            _draw_blade_box(0.0, with_handle=True)
            c.rect((cx - 5.0, cy - 3.0), (cx + 5.0, cy + 3.0), fill=True)
    elif variant == "non_fused":
        if style in (0, 3):
            box = _draw_blade_box(-9.0, with_handle=(style == 3))
            _place_tag(c, "NF", box, 0, tag_h=11.0)
        elif style in (1, 4):
            lbl = "NF" if style == 1 else "NFD"
            box = _draw_badged_symbol(c, "rect", lbl, cx, cy, min_w=36, min_h=24, text_h=11.5)
            c.line((box[2], cy - 3.0), (box[2] + 6.5, cy - 11.0))
        else:
            box = _draw_badged_symbol(c, "rect", "UFD", cx, cy, min_w=40, min_h=24, text_h=11.0)
            c.line((box[2], cy - 3.0), (box[2] + 6.5, cy - 11.0))
    elif variant == "motor_rated":
        if style in (0, 3):
            box = _draw_blade_box(-8.0, with_handle=(style == 3))
            _place_tag(c, "M" if style == 0 else "HP", box, 0, tag_h=11.0)
        elif style in (1, 4):
            lbl = "MD" if style == 1 else "MRD"
            box = _draw_badged_symbol(c, "rect", lbl, cx, cy, min_w=38, min_h=24, text_h=11.5)
            c.line((box[2], cy - 3.0), (box[2] + 6.5, cy - 11.0))
        else:
            box = _draw_badged_symbol(c, "square", "M", cx, cy, min_w=30, min_h=30, text_h=13.0, double_border=(style == 5))
            c.line((box[2], cy - 3.0), (box[2] + 7.0, cy - 11.0))
    elif variant == "weatherproof":
        if style in (0, 3, 5):
            box = _draw_blade_box(-10.0, with_handle=(style != 5), dbl=(style == 5))
            _place_tag(c, "WP" if style != 3 else "3R", box, 0, tag_h=11.0)
        elif style in (1, 4):
            lbl = "D-WP" if style == 1 else "WP-D"
            box = _draw_badged_symbol(c, "rect", lbl, cx, cy, min_w=44, min_h=24, text_h=11.0)
            c.line((box[2], cy - 3.0), (box[2] + 6.5, cy - 11.0))
        else:
            box = _draw_badged_symbol(c, "square", "D", cx - 8.0, cy, min_w=28, min_h=28, text_h=13.0)
            c.line((box[2], cy - 3.0), (box[2] + 6.0, cy - 10.0))
            c.text("WP", (box[2] + 15.0, cy + 5.0), 10.0)
    return c.render_crop()


# ---------------------------------------------------------------------------
# 6. Fire Alarm & Life Safety (22 fire_alarm_device variants)
# ---------------------------------------------------------------------------

def _draw_fire_alarm(variant: str, style: int, rng: np.random.Generator) -> np.ndarray:
    c, cx, cy = _make_canvas(rng, 200, 170)

    def _strobe_rays(rx: float, ry: float, r_inner: float, r_outer: float) -> None:
        for deg in (45.0, 135.0, 225.0, 315.0):
            rad = math.radians(deg)
            c.line(
                (rx + r_inner * math.cos(rad), ry + r_inner * math.sin(rad)),
                (rx + r_outer * math.cos(rad), ry + r_outer * math.sin(rad)),
            )

    if variant == "smoke_detector":
        if style == 0:
            _draw_badged_symbol(c, "circle", "S", cx, cy, min_w=28, min_h=28, text_h=13.5)
        elif style == 1:
            _draw_badged_symbol(c, "circle", "SD", cx, cy, min_w=30, min_h=30, text_h=12.0)
        elif style == 2:
            _draw_badged_symbol(c, "hexagon", "S", cx, cy, min_w=30, min_h=30, text_h=12.5)
        elif style == 3:
            _draw_badged_symbol(c, "double_circle", "SD", cx, cy, min_w=32, min_h=32, text_h=11.5)
        else:
            _draw_badged_symbol(c, "hexagon", "SD", cx, cy, min_w=32, min_h=32, text_h=11.5)
    elif variant == "fire_alarm_control_panel":
        lbl = ("FACP", "FACP", "FCP", "FACP", "FACP")[style % 5]
        box = _draw_badged_symbol(
            c, "rect", lbl, cx, cy, min_w=54, min_h=26, text_h=12.5, double_border=(style == 1)
        )
        if style == 3:
            c.line((box[0], box[1] + 5.0), (box[2], box[1] + 5.0), t=c.stroke * 0.75)
        elif style == 4:
            c.poly([(box[0], box[1]), (box[0] + 9.0, box[1]), (box[0], box[1] + 9.0)], fill=True)
    elif variant == "annunciator_panel":
        lbl = ("FAA", "FAAP", "FSA", "FAA", "ANN")[style % 5]
        box = _draw_badged_symbol(
            c, "rect", lbl, cx, cy, min_w=48, min_h=24, text_h=12.5, double_border=(style == 3)
        )
        if style == 4:
            c.line((box[0], box[1] + 5.0), (box[2], box[1] + 5.0), t=c.stroke * 0.75)
    elif variant == "voice_evacuation_panel":
        lbl = ("EVAC", "VEP", "EVCP", "FAVP", "EVAC")[style % 5]
        _draw_badged_symbol(c, "rect", lbl, cx, cy, min_w=52, min_h=24, text_h=12.0, double_border=(style == 4))
    elif variant == "duct_smoke_detector":
        if style in (0, 1, 3):
            lbl = ("S", "SD", "S", "DSD")[style]
            box = _draw_badged_symbol(c, "circle", lbl, cx, cy, min_w=28, min_h=28, text_h=12.0)
            r = 0.5 * (box[2] - box[0])
            for sy in (-r - 4.0, r + 4.0):
                if style in (0, 3):
                    c.dashed_line((cx - r * 1.9, cy + sy), (cx + r * 1.9, cy + sy), dash=5.0, gap=3.5)
                else:
                    c.line((cx - r * 1.9, cy + sy), (cx + r * 1.9, cy + sy))
        elif style == 2:
            _draw_badged_symbol(c, "hexagon", "S", cx, cy, min_w=30, min_h=30, text_h=12.0, sub="D")
        else:
            box = _draw_badged_symbol(c, "circle", "SD", cx, cy, min_w=28, min_h=28, text_h=11.5)
            c.rect((box[0] - 8.0, cy - 18.0), (box[2] + 8.0, cy + 18.0), t=c.stroke * 0.7)
    elif variant == "manual_pull_station":
        lbl = ("F", "F", "MPS", "FA", "P")[style % 5]
        box = _draw_badged_symbol(
            c, "square", lbl, cx, cy, min_w=28, min_h=28, text_h=13.5 if len(lbl) == 1 else 10.5
        )
        if style == 1:
            c.poly([(box[0] - 2.0, box[1]), (cx, box[1] - 10.0), (box[2] + 2.0, box[1])], closed=False)
        elif style == 3:
            c.poly([(box[0], box[1]), (box[0] + 7.0, box[1]), (box[0], box[1] + 7.0)], fill=True)
    elif variant == "heat_detector":
        if style == 0:
            _draw_badged_symbol(c, "hexagon", "H", cx, cy, min_w=30, min_h=30, text_h=13.0)
        elif style == 1:
            _draw_badged_symbol(c, "circle", "HD", cx, cy, min_w=30, min_h=30, text_h=12.0)
        elif style == 2:
            r = 15.0
            c.circle((cx, cy), r)
            c.line((cx, cy - r * 0.7), (cx, cy + r * 0.45))
            c.circle((cx, cy + r * 0.48), 3.6, fill=True)
        elif style == 3:
            _draw_badged_symbol(c, "circle", "H", cx, cy, min_w=28, min_h=28, text_h=13.5)
        else:
            _draw_badged_symbol(c, "hexagon", "HD", cx, cy, min_w=32, min_h=32, text_h=11.5, double_border=True)
    elif variant == "heat_detector_rate_of_rise":
        if style in (0, 3):
            r = 15.0
            c.circle((cx - 8.0, cy), r)
            c.line((cx - 8.0, cy - r * 0.7), (cx - 8.0, cy + r * 0.45))
            c.circle((cx - 8.0, cy + r * 0.48), 3.6, fill=True)
            c.text("R" if style == 0 else "R/F", (cx + r + 5.0, cy + 5.0), 11.0, font=cv2.FONT_HERSHEY_SIMPLEX)
        elif style == 1:
            box = _draw_badged_symbol(c, "hexagon", "H", cx - 8.0, cy, min_w=28, min_h=28, text_h=12.0)
            _place_tag(c, "ROR", box, 0, tag_h=10.5)
        elif style == 2:
            box = _draw_badged_symbol(c, "circle", "HD", cx - 8.0, cy, min_w=28, min_h=28, text_h=11.5)
            _place_tag(c, "ROR", box, 0, tag_h=10.5)
        else:
            _draw_badged_symbol(c, "hexagon", "HR", cx, cy, min_w=32, min_h=32, text_h=11.5)
    elif variant == "smoke_detector_sounder_base":
        if style in (0, 2, 4, 5):
            sh = "double_circle" if style == 2 else ("hexagon" if style == 5 else "circle")
            box = _draw_badged_symbol(c, sh, "S" if style != 2 else "SD", cx - 6.0, cy, min_w=28, min_h=28, text_h=12.0)
            _place_tag(c, "SB" if style != 4 else "S", box, 0 if style != 5 else 1, tag_h=10.0)
        elif style == 1:
            _draw_badged_symbol(c, "hexagon", "S", cx, cy, min_w=30, min_h=30, text_h=12.0, sub="SB")
        else:
            box = _draw_badged_symbol(c, "circle", "S", cx - 5.0, cy, min_w=28, min_h=28, text_h=12.5)
            c.arcs_wave((box[2] + 1.0, cy), 4.5, 4.0, 2, -48.0, 48.0)
    elif variant == "fire_alarm_strobe":
        if style == 0:
            r = 12.0
            c.circle((cx, cy), r)
            _strobe_rays(cx, cy, r, r + 8.0)
        elif style == 1:
            box = _draw_badged_symbol(c, "square", "S", cx - 5.0, cy, min_w=26, min_h=26, text_h=13.0)
            c.poly([(box[2], cy), (box[2] + 12.0, cy - 8.5), (box[2] + 12.0, cy + 8.5)], fill=True)
        elif style == 2:
            r = 15.0
            c.regular_poly((cx, cy), r, 4, rot_deg=0.0)
            c.circle((cx, cy), 5.5)
            _strobe_rays(cx, cy, r * 0.72, r + 6.0)
        elif style == 3:
            hw = 14.0
            c.rect((cx - hw, cy - hw), (cx + hw, cy + hw))
            c.circle((cx, cy), 5.5)
            _strobe_rays(cx, cy, hw * 1.15, hw * 1.65)
        else:
            _draw_badged_symbol(c, "square", "VS", cx, cy, min_w=28, min_h=28, text_h=11.5)
            _strobe_rays(cx, cy, 18.0, 25.0)
    elif variant == "fire_alarm_horn":
        if style == 0:
            hw = 13.0
            c.rect((cx - hw - 5.0, cy - hw), (cx + hw - 5.0, cy + hw))
            c.poly([(cx + hw - 5.0, cy), (cx + hw + 11.0, cy - 11.0), (cx + hw + 11.0, cy + 11.0)], fill=False)
        elif style == 1:
            box = _draw_badged_symbol(c, "square", "H", cx - 6.0, cy, min_w=26, min_h=26, text_h=13.0)
            c.poly([(box[2], cy), (box[2] + 12.0, cy - 9.0), (box[2] + 12.0, cy + 9.0)], fill=True)
        elif style == 2:
            hw = 14.0
            c.rect((cx - hw, cy - hw), (cx + hw, cy + hw))
            c.poly([
                (cx - 8.0, cy - 4.0),
                (cx - 2.0, cy - 4.0),
                (cx + 8.0, cy - 10.0),
                (cx + 8.0, cy + 10.0),
                (cx - 2.0, cy + 4.0),
                (cx - 8.0, cy + 4.0),
            ])
        elif style == 3:
            _draw_badged_symbol(c, "square", "FH", cx, cy, min_w=28, min_h=28, text_h=12.0)
        else:
            _draw_badged_symbol(c, "square", "FAH", cx, cy, min_w=34, min_h=26, text_h=10.5)
    elif variant == "horn_and_strobe":
        if style == 0:
            box = _draw_badged_symbol(c, "square", "F", cx - 6.0, cy, min_w=26, min_h=26, text_h=13.0)
            c.poly([(box[2], cy), (box[2] + 12.0, cy - 9.5), (box[2] + 12.0, cy + 9.5)], fill=False)
        elif style == 1:
            hw = 14.0
            c.rect((cx - hw - 6.0, cy - hw), (cx + hw - 6.0, cy + hw))
            c.circle((cx - 6.0, cy), 6.5)
            _strobe_rays(cx - 6.0, cy, hw * 0.9, hw * 1.4)
            c.poly([(cx + hw - 6.0, cy), (cx + hw + 9.0, cy - 10.0), (cx + hw + 9.0, cy + 10.0)], fill=False)
        elif style == 2:
            _draw_badged_symbol(c, "square", "HS", cx, cy, min_w=28, min_h=28, text_h=12.5)
            _strobe_rays(cx, cy, 18.5, 25.5)
        elif style == 3:
            _draw_badged_symbol(c, "square", "H/S", cx, cy, min_w=32, min_h=28, text_h=10.5)
            _strobe_rays(cx, cy, 20.0, 26.5)
        else:
            box = _draw_badged_symbol(c, "square", "HS", cx - 5.0, cy, min_w=28, min_h=28, text_h=12.0)
            c.poly([(box[2], cy), (box[2] + 11.0, cy - 8.5), (box[2] + 11.0, cy + 8.5)], fill=True)
    elif variant == "speaker_and_strobe":
        if style == 0:
            box = _draw_badged_symbol(c, "square", "F", cx - 12.0, cy, min_w=24, min_h=24, text_h=12.5)
            _draw_badged_symbol(c, "circle", "S", box[2] + 12.5, cy, min_w=24, min_h=24, text_h=12.5)
        elif style == 1:
            hw = 14.0
            c.rect((cx - hw, cy - hw), (cx + hw, cy + hw))
            c.poly([
                (cx - 8.0, cy - 4.0),
                (cx - 2.0, cy - 4.0),
                (cx + 7.0, cy - 9.0),
                (cx + 7.0, cy + 9.0),
                (cx - 2.0, cy + 4.0),
                (cx - 8.0, cy + 4.0),
            ])
            _strobe_rays(cx, cy, hw * 1.15, hw * 1.65)
        elif style == 2:
            _draw_badged_symbol(c, "square", "SS", cx, cy, min_w=28, min_h=28, text_h=12.5)
            _strobe_rays(cx, cy, 18.5, 25.5)
        elif style == 3:
            hw = 15.0
            c.rect((cx - hw, cy - hw), (cx + hw, cy + hw))
            c.text("S", (cx, cy), 13.0)
            _strobe_rays(cx, cy, hw * 1.15, hw * 1.65)
        else:
            _draw_badged_symbol(c, "square", "S/S", cx, cy, min_w=32, min_h=28, text_h=10.5)
            _strobe_rays(cx, cy, 20.0, 26.5)
    elif variant == "fire_alarm_bell":
        if style in (0, 1):
            r = 16.0
            c.ellipse((cx, cy + 3.0), r, r * 0.88, start=180.0, end=360.0)
            c.line((cx - r, cy + 3.0), (cx + r, cy + 3.0))
            c.line((cx - 5.0, cy + 3.0), (cx - 5.0, cy + 12.0))
            c.line((cx + 5.0, cy + 3.0), (cx + 5.0, cy + 12.0))
            c.line((cx - 11.0, cy + 12.0), (cx - 5.0, cy + 12.0))
            c.line((cx + 5.0, cy + 12.0), (cx + 11.0, cy + 12.0))
            if style == 1:
                c.circle((cx, cy + 8.5), 2.8, fill=True)
        elif style == 2:
            _draw_badged_symbol(c, "circle", "FB", cx, cy, min_w=28, min_h=28, text_h=11.5)
        elif style == 3:
            _draw_badged_symbol(c, "circle", "B", cx, cy, min_w=26, min_h=26, text_h=13.0)
            c.circle((cx, cy + 16.0), 2.8, fill=True)
        else:
            hw = 16.0
            c.rect((cx - hw, cy - hw), (cx + hw, cy + hw))
            c.ellipse((cx, cy + 4.0), 10.0, 10.0, start=180.0, end=360.0)
            c.line((cx - 10.0, cy + 4.0), (cx + 10.0, cy + 4.0))
    elif variant == "fire_alarm_module":
        if style == 0:
            _draw_badged_symbol(c, "round_rect", "AIM", cx, cy, min_w=42, min_h=22, text_h=11.5)
        elif style == 1:
            _draw_badged_symbol(c, "rect", "CM", cx, cy, min_w=34, min_h=22, text_h=12.0)
        elif style == 2:
            _draw_badged_symbol(c, "rect", "MM", cx, cy, min_w=34, min_h=22, text_h=12.0)
        elif style == 3:
            _draw_badged_symbol(c, "rect", "IAM", cx, cy, min_w=38, min_h=22, text_h=11.5)
        else:
            _draw_badged_symbol(c, "circle", "CR", cx, cy, min_w=28, min_h=28, text_h=11.5)
    elif variant == "tamper_switch":
        if style == 0:
            _draw_badged_symbol(c, "square", "TS", cx, cy, min_w=28, min_h=28, text_h=12.5)
        elif style == 1:
            box = _draw_badged_symbol(c, "square", "TS", cx, cy, min_w=28, min_h=28, text_h=12.5)
            c.dashed_line((cx - 24.0, box[3] + 6.0), (cx + 24.0, box[3] + 6.0))
            c.line((cx, box[3]), (cx, box[3] + 6.0))
        elif style == 2:
            r = 12.0
            c.circle((cx, cy - 4.0), r)
            d = r * 0.707
            c.line((cx - d, cy - 4.0 - d), (cx + d, cy - 4.0 + d))
            c.line((cx, cy - 4.0 + r), (cx, cy + 16.0))
            c.dashed_line((cx - 20.0, cy + 16.0), (cx + 20.0, cy + 16.0))
        elif style == 3:
            _draw_badged_symbol(c, "diamond", "TS", cx, cy, min_w=32, min_h=32, text_h=11.5)
        else:
            _draw_badged_symbol(c, "square", "TS", cx, cy, min_w=30, min_h=30, text_h=12.0, double_border=True)
    elif variant == "waterflow_switch":
        if style == 0:
            _draw_badged_symbol(c, "square", "FS", cx, cy, min_w=28, min_h=28, text_h=12.5)
        elif style == 1:
            _draw_badged_symbol(c, "square", "WF", cx, cy, min_w=30, min_h=28, text_h=12.0)
        elif style == 2:
            c.regular_poly((cx, cy + 5.0), 11.0, 4, rot_deg=0.0)
            c.circle((cx, cy - 13.0), 5.5)
            c.line((cx, cy - 7.5), (cx, cy - 6.0))
            c.dashed_line((cx - 22.0, cy + 5.0), (cx - 11.0, cy + 5.0))
            c.dashed_line((cx + 11.0, cy + 5.0), (cx + 22.0, cy + 5.0))
        elif style == 3:
            _draw_badged_symbol(c, "rect", "WFS", cx, cy, min_w=38, min_h=24, text_h=11.5)
        else:
            _draw_badged_symbol(c, "diamond", "FS", cx, cy, min_w=32, min_h=32, text_h=11.5)
    elif variant == "area_of_refuge_station":
        lbl = ("AOR", "AR", "ARC", "REF", "AOR", "AR")[style % 6]
        sh = ("square", "square", "rect", "round_rect", "circle", "square")[style % 6]
        box = _draw_badged_symbol(
            c, sh, lbl, cx, cy, min_w=34, min_h=28, text_h=11.5, double_border=(style == 5)
        )
        if style == 2:
            c.circle((box[2] - 5.0, box[1] + 5.0), 2.5, fill=True)
    elif variant == "carbon_monoxide_detector":
        if style in (0, 3):
            tr = 14.0
            pts = [
                (cx - 8.0, cy - tr * 0.9),
                (cx - 8.0 - tr * 0.9, cy + tr * 0.65),
                (cx - 8.0 + tr * 0.9, cy + tr * 0.65),
            ]
            c.poly(pts, fill=(style == 0))
            c.text("CO", (cx + 12.0, cy + 5.0), 10.5, font=cv2.FONT_HERSHEY_SIMPLEX)
        elif style in (1, 4):
            _draw_badged_symbol(
                c, "hexagon", "CO" if style == 1 else "C", cx, cy, min_w=30, min_h=30, text_h=12.0,
                sub=("O" if style == 4 else None),
            )
        else:
            _draw_badged_symbol(c, "double_circle", "CO", cx, cy, min_w=32, min_h=32, text_h=11.5)
    elif variant == "elevator_recall_detector":
        if style == 0:
            _draw_badged_symbol(c, "hexagon", "S", cx, cy, min_w=30, min_h=30, text_h=12.5, sub="E")
        elif style in (1, 2):
            _draw_badged_symbol(c, "circle", "S" if style == 1 else "SD", cx, cy, min_w=30, min_h=30, text_h=12.0, sub="E")
        elif style == 3:
            _draw_badged_symbol(c, "circle", "ER", cx, cy, min_w=30, min_h=30, text_h=12.0)
        elif style == 4:
            _draw_badged_symbol(c, "square", "ER", cx, cy, min_w=30, min_h=30, text_h=12.0)
        else:
            _draw_badged_symbol(c, "diamond", "SE", cx, cy, min_w=34, min_h=34, text_h=11.5)
    elif variant == "emergency_responder_radio":
        if style in (0, 3):
            box = _draw_badged_symbol(
                c, "rect", "BDA" if style == 0 else "DAS", cx, cy + 6.0, min_w=42, min_h=22, text_h=11.5
            )
            c.line((cx, box[1]), (cx, box[1] - 12.0))
            c.line((cx - 7.0, box[1] - 12.0), (cx + 7.0, box[1] - 12.0))
            c.arcs_wave((cx, box[1] - 12.0), 5.0, 4.0, 2, 225.0, 315.0)
        elif style in (1, 4):
            _draw_badged_symbol(
                c, "rect", "ERRCS" if style == 4 else "BDA", cx, cy, min_w=48, min_h=24, text_h=10.5, double_border=True
            )
        elif style == 2:
            box = _draw_badged_symbol(c, "diamond", "BDA", cx, cy, min_w=38, min_h=38, text_h=10.5)
            c.arcs_wave((box[2] - 2.0, cy), 4.5, 4.0, 2, -45.0, 45.0)
        else:
            box = _draw_badged_symbol(c, "circle", "DAS", cx, cy, min_w=34, min_h=34, text_h=11.0)
            c.line((cx, box[1]), (cx, box[1] - 10.0))
            c.circle((cx, box[1] - 12.0), 2.5, fill=True)
    elif variant == "door_holder":
        if style == 0:
            _draw_badged_symbol(c, "square", "DH", cx, cy, min_w=28, min_h=28, text_h=12.5)
        elif style == 1:
            r = 10.0
            c.circle((cx, cy + 4.0), r)
            c.circle((cx, cy + 4.0), 2.8, fill=True)
            c.line((cx, cy + 4.0 - r), (cx, cy - 15.0))
            c.line((cx - 10.0, cy - 15.0), (cx + 10.0, cy - 15.0))
        elif style == 2:
            _draw_badged_symbol(c, "square", "MDH", cx, cy, min_w=36, min_h=26, text_h=11.0)
        elif style == 3:
            box = _draw_badged_symbol(c, "circle", "DH", cx, cy, min_w=28, min_h=28, text_h=12.0)
            _draw_wall_stem(c, cx, box[3], 6.0, with_bar=True)
        else:
            c.line((cx - 16.0, cy + 12.0), (cx + 16.0, cy + 12.0), t=c.stroke * 1.1)
            c.rect((cx - 11.0, cy - 8.0), (cx + 11.0, cy + 12.0))
            c.text("DH", (cx, cy + 2.0), 10.5, font=cv2.FONT_HERSHEY_SIMPLEX)
    return c.render_crop()


# ---------------------------------------------------------------------------
# 7. Security & Access Control (15 devices) + CCTV (4 devices)
# ---------------------------------------------------------------------------

def _draw_security(variant: str, style: int, rng: np.random.Generator) -> np.ndarray:
    c, cx, cy = _make_canvas(rng, 190, 160, sans_only=True)

    if variant == "card_reader":
        if style in (0, 2, 4):
            box = _draw_badged_symbol(c, "square", "CR", cx, cy, min_w=28, min_h=28, text_h=12.5, double_border=(style == 4))
            if style == 2:
                _draw_wall_stem(c, cx, box[3], 6.5)
        elif style == 1:
            box = _draw_badged_symbol(c, "rect", "CR", cx, cy, min_w=32, min_h=24, text_h=12.0)
            c.line((box[0], box[1] + 4.0), (box[2], box[1] + 4.0), t=c.stroke * 0.7)
        else:
            _draw_badged_symbol(c, "diamond", "CR", cx, cy, min_w=34, min_h=34, text_h=11.5)
    elif variant == "card_reader_long_range":
        if style in (0, 2):
            _draw_badged_symbol(c, "square" if style == 0 else "rect", "CR", cx, cy, min_w=32, min_h=28, text_h=11.5, sub="LR")
        elif style == 1:
            _draw_badged_symbol(c, "rect", "CR-LR", cx, cy, min_w=46, min_h=24, text_h=10.5)
        elif style == 3:
            box = _draw_badged_symbol(c, "square", "CR", cx - 6.0, cy, min_w=28, min_h=28, text_h=11.5, sub="LR")
            c.arcs_wave((box[2] + 1.0, cy), 5.0, 4.2, 2, -45.0, 45.0)
        elif style == 4:
            _draw_badged_symbol(c, "rect", "LRCR", cx, cy, min_w=42, min_h=24, text_h=11.0)
        else:
            box = _draw_badged_symbol(c, "square", "CR", cx, cy, min_w=30, min_h=30, text_h=12.0)
            _place_tag(c, "LR", box, 0, tag_h=10.0)
    elif variant == "card_reader_proximity":
        if style in (0, 2):
            _draw_badged_symbol(c, "square", "CR", cx, cy, min_w=30, min_h=28, text_h=12.0, sub="P")
        elif style == 1:
            _draw_badged_symbol(c, "rect", "CR-P", cx, cy, min_w=42, min_h=24, text_h=11.0)
        elif style == 3:
            box = _draw_badged_symbol(c, "square", "CR", cx - 5.0, cy, min_w=28, min_h=28, text_h=12.0)
            c.arcs_wave((box[2] + 1.0, cy), 5.0, 4.0, 2, -45.0, 45.0)
        elif style == 4:
            _draw_badged_symbol(c, "rect", "PRX", cx, cy, min_w=38, min_h=24, text_h=11.5)
        else:
            _draw_badged_symbol(c, "rect", "PROX", cx, cy, min_w=44, min_h=24, text_h=10.5)
    elif variant == "glass_break_detector":
        sh = ("square", "circle", "hexagon", "diamond", "square", "circle")[style % 6]
        lbl = "GBD" if style == 4 else "GB"
        box = _draw_badged_symbol(c, sh, lbl, cx, cy, min_w=30, min_h=30, text_h=11.5, double_border=(style == 5))
        if style == 5:
            r = 0.5 * (box[2] - box[0])
            for deg in (30.0, 150.0, 270.0):
                rad = math.radians(deg)
                c.line(
                    (cx + r * math.cos(rad), cy + r * math.sin(rad)),
                    (cx + (r + 5.0) * math.cos(rad), cy + (r + 5.0) * math.sin(rad)),
                )
    elif variant == "panic_duress_button":
        lbl = ("PB", "P", "DB", "PB", "PB")[style % 5]
        sh = ("square", "square", "square", "circle", "double_circle")[style % 5]
        box = _draw_badged_symbol(c, sh, lbl, cx, cy, min_w=28, min_h=28, text_h=12.5)
        if style == 4:
            c.circle((cx, box[3] + 5.0), 2.5, fill=True)
    elif variant == "door_release_button":
        lbl = ("DOR", "DR", "DRB", "DR", "DRB")[style % 5]
        sh = ("circle", "square", "circle", "double_circle", "square")[style % 5]
        _draw_badged_symbol(c, sh, lbl, cx, cy, min_w=30, min_h=28, text_h=11.0 if len(lbl) >= 3 else 12.5)
    elif variant == "request_to_exit":
        if style == 2:
            c.rect((cx - 14.0, cy - 14.0), (cx + 14.0, cy + 14.0))
            c.circle((cx, cy), 4.2, fill=True)
        else:
            lbl = ("RX", "REX", "REX", "RTE", "REX")[style % 5]
            sh = "circle" if style == 4 else "square"
            _draw_badged_symbol(c, sh, lbl, cx, cy, min_w=28, min_h=28, text_h=11.5)
    elif variant == "gate_operator":
        lbl = ("GOP", "GO", "GOP", "GATE", "GOP", "GO")[style % 6]
        sh = ("circle", "square", "square", "rect", "double_circle", "rect")[style % 6]
        box = _draw_badged_symbol(c, sh, lbl, cx, cy, min_w=32, min_h=28, text_h=11.0)
        if style == 5:
            c.arrow((box[2], cy), (box[2] + 11.0, cy), head_len=5.0, head_w=2.8)
    elif variant == "gate_release_button":
        lbl = ("GR", "GR", "GRB", "GR", "GRB", "GR")[style % 6]
        sh = ("circle", "square", "circle", "double_circle", "square", "round_rect")[style % 6]
        _draw_badged_symbol(c, sh, lbl, cx, cy, min_w=30, min_h=28, text_h=11.5)
    elif variant == "door_contact_switch":
        lbl = ("DC", "DPS", "DC", "DC", "DPS")[style % 5]
        sh = ("square", "square", "rect", "circle", "square")[style % 5]
        box = _draw_badged_symbol(c, sh, lbl, cx, cy, min_w=28, min_h=26, text_h=11.5, double_border=(style == 4))
        if style == 2:
            c.line((box[0] - 5.0, cy), (box[0], cy))
            c.line((box[2], cy), (box[2] + 5.0, cy))
    elif variant == "electric_door_strike":
        lbl = ("ES", "EDS", "ES", "ES", "STR")[style % 5]
        sh = ("square", "square", "rect", "circle", "square")[style % 5]
        box = _draw_badged_symbol(c, sh, lbl, cx, cy, min_w=28, min_h=28, text_h=11.5)
        if style == 2:
            c.line((box[2], cy - 5.0), (box[2] + 6.0, cy))
            c.line((box[2] + 6.0, cy), (box[2], cy + 5.0))
    elif variant == "security_keypad":
        if style == 3:
            hw = 15.0
            c.rect((cx - hw, cy - hw), (cx + hw, cy + hw))
            for dx in (-6.5, 0.0, 6.5):
                for dy in (-6.5, 0.0, 6.5):
                    c.circle((cx + dx, cy + dy), 1.8, fill=True)
        else:
            lbl = ("K", "KP", "KPD", "KP", "KP")[style % 5]
            _draw_badged_symbol(c, "square", lbl, cx, cy, min_w=28, min_h=28, text_h=12.0, double_border=(style == 4))
    elif variant == "magnetic_door_lock":
        lbl = ("ML", "MAG", "MDL", "EML", "ML")[style % 5]
        sh = ("square", "rect", "square", "rect", "square")[style % 5]
        box = _draw_badged_symbol(c, sh, lbl, cx, cy, min_w=30, min_h=26, text_h=11.5)
        if style == 4:
            c.line((box[0], box[1] - 4.0), (box[2], box[1] - 4.0), t=c.stroke * 1.2)
    elif variant == "security_alarm_strobe":
        lbl = ("SL", "SAS", "SL", "SL", "SL", "SAS")[style % 6]
        sh = ("square", "square", "circle", "diamond", "square", "rect")[style % 6]
        box = _draw_badged_symbol(c, sh, lbl, cx, cy, min_w=28, min_h=28, text_h=12.0)
        if style in (2, 3, 4):
            r = 0.5 * (box[2] - box[0]) + 1.0
            for deg in (45.0, 135.0, 225.0, 315.0):
                rad = math.radians(deg)
                c.line(
                    (cx + r * math.cos(rad), cy + r * math.sin(rad)),
                    (cx + (r + 5.5) * math.cos(rad), cy + (r + 5.5) * math.sin(rad)),
                )
    elif variant == "access_control_panel":
        lbl = ("ACP", "SCP", "ACP", "ACP", "ACU")[style % 5]
        box = _draw_badged_symbol(
            c, "rect", lbl, cx, cy, min_w=46, min_h=24, text_h=12.0, double_border=(style == 2)
        )
        if style == 3:
            c.line((box[0], box[1] + 5.0), (box[2], box[1] + 5.0), t=c.stroke * 0.75)
    return c.render_crop()


def _draw_cctv(variant: str, style: int, rng: np.random.Generator) -> np.ndarray:
    c, cx, cy = _make_canvas(rng, 200, 170, sans_only=True)
    if variant == "dvr_nvr":
        lbl = ("DVR", "NVR", "DVR", "DVR/NVR", "NVR", "DVR")[style % 6]
        box = _draw_badged_symbol(
            c, "rect", lbl, cx, cy, min_w=46, min_h=24, text_h=11.0 if len(lbl) > 3 else 12.0,
            double_border=(style == 2),
        )
        if style == 4:
            for k in (-12.0, -4.0, 4.0):
                c.circle((cx + k, box[3] - 4.0), 1.5, fill=True)
        elif style == 5:
            c.line((box[0], box[1] + 4.5), (box[2], box[1] + 4.5), t=c.stroke * 0.7)
    elif variant == "camera_mini_dome":
        r = 16.0
        if style == 0:
            c.circle((cx, cy), r)
            c.rect((cx - 4.5, cy - 9.0), (cx + 4.5, cy + 9.0), fill=True)
        elif style == 1:
            c.circle((cx - 4.0, cy), r)
            c.circle((cx - 4.0, cy), 6.0, fill=True)
            c.dashed_line((cx - 4.0, cy), (cx + 22.0, cy - 12.0))
            c.dashed_line((cx - 4.0, cy), (cx + 22.0, cy + 12.0))
        elif style == 2:
            _draw_badged_symbol(c, "circle", "MD", cx, cy, min_w=30, min_h=30, text_h=11.5)
            c.circle((cx, cy), 18.5, t=c.stroke * 0.65)
        elif style == 3:
            c.circle((cx, cy), r)
            c.chord_segment((cx, cy), r * 0.72, -90.0, 90.0, fill=True)
        elif style == 4:
            c.rect((cx - 17.0, cy - 17.0), (cx + 17.0, cy + 17.0))
            c.circle((cx, cy), 11.0)
            c.circle((cx, cy), 5.0, fill=True)
        else:
            c.circle((cx, cy), r)
            c.circle((cx, cy), 6.5, fill=True)
            _draw_wall_stem(c, cx, cy + r, 6.5, with_bar=True)
    elif variant == "security_camera":
        bw, bh = 28.0, 18.0
        x0, y0, x1, y1 = cx - 22.0, cy - bh / 2.0, cx - 22.0 + bw, cy + bh / 2.0
        if style == 3:
            box = _draw_badged_symbol(c, "rect", "CAM", cx - 8.0, cy, min_w=34, min_h=20, text_h=10.5)
            x0, y0, x1, y1 = box
        else:
            c.rect((x0, y0), (x1, y1), fill=(style == 4))
            if style in (0, 2):
                c.text("C", ((x0 + x1) / 2.0, cy), 11.0)
        c.poly([(x1, cy), (x1 + 14.0, cy - 9.0), (x1 + 14.0, cy + 9.0)], fill=(style == 1))
        if style == 2:
            c.dashed_line((x1 + 14.0, cy - 9.0), (x1 + 26.0, cy - 16.0))
            c.dashed_line((x1 + 14.0, cy + 9.0), (x1 + 26.0, cy + 16.0))
        if style in (1, 4):
            c.line((x0, cy), (x0 - 8.0, cy))
            c.line((x0 - 8.0, cy - 9.0), (x0 - 8.0, cy + 9.0))
    elif variant == "camera_ptz":
        if style in (0, 2, 5):
            box = _draw_badged_symbol(
                c,
                "octagon" if style == 5 else "circle",
                "PTZ",
                cx,
                cy,
                min_w=32,
                min_h=32,
                text_h=10.5,
                double_border=(style == 2),
            )
            r = 0.5 * (box[2] - box[0]) + 4.5
            c.ellipse((cx, cy), r, r, start=-45.0, end=45.0)
            c.ellipse((cx, cy), r, r, start=135.0, end=225.0)
            c.arrow(
                (cx + r * 0.707, cy - r * 0.707 - 2.0),
                (cx + r * 0.707 + 2.0, cy - r * 0.707 + 4.0),
                head_len=4.5,
                head_w=2.5,
            )
        elif style in (1, 3):
            box = _draw_badged_symbol(c, "rect", "PTZ", cx - 8.0, cy, min_w=34, min_h=20, text_h=10.5)
            c.poly([(box[2], cy), (box[2] + 13.0, cy - 8.5), (box[2] + 13.0, cy + 8.5)], fill=(style == 3))
        else:
            box = _draw_badged_symbol(c, "circle", "PTZ", cx, cy, min_w=32, min_h=32, text_h=10.5)
            _draw_wall_stem(c, cx, box[3], 6.5, with_bar=True)
    return c.render_crop()


# ---------------------------------------------------------------------------
# 8. Telecommunications / Data (9 devices) & Audio/Visual (6 devices)
# ---------------------------------------------------------------------------

def _draw_telecom_triangle(
    c: CadCanvas,
    cx: float,
    cy: float,
    s: float,
    fill_mode: str = "open",
    *,
    stem: bool = False,
    wall_bar: bool = False,
) -> tuple[float, float, float, float]:
    """Draw standard US downward telecom triangle and return bbox."""
    top_y = cy - s * 0.72
    bot_y = cy + s * 0.98
    pts = [(cx - s, top_y), (cx + s, top_y), (cx, bot_y)]
    if fill_mode == "full":
        c.poly(pts, fill=True)
    elif fill_mode == "half_left":
        c.poly([(cx - s, top_y), (cx, top_y), (cx, bot_y)], fill=True)
        c.poly(pts, closed=True)
    elif fill_mode == "half_right":
        c.poly([(cx, top_y), (cx + s, top_y), (cx, bot_y)], fill=True)
        c.poly(pts, closed=True)
    else:
        c.poly(pts, closed=True)
    if stem:
        _draw_wall_stem(c, cx, bot_y, 7.0, with_bar=wall_bar)
    return cx - s, top_y, cx + s, bot_y


def _draw_data_outlet(variant: str, style: int, rng: np.random.Generator) -> np.ndarray:
    c, cx, cy = _make_canvas(rng, 200, 170)
    s = float(rng.uniform(13.5, 16.5))

    if variant == "data":
        _draw_telecom_triangle(c, cx, cy, s, "open", stem=(style in (1, 3)), wall_bar=(style == 3))
        if style == 2:
            c.text("D", (cx + s + 8.0, cy), 11.0)
        elif style == 4:
            c.line((cx - s, cy - s * 0.72 - 3.5), (cx + s, cy - s * 0.72 - 3.5), t=c.stroke * 0.85)
    elif variant == "telephone_outlet":
        if style in (0, 1, 4):
            box = _draw_telecom_triangle(c, cx, cy, s, "full", stem=(style in (1, 4)), wall_bar=(style == 4))
            if style == 4:
                _place_tag(c, "T", box, 0, tag_h=10.5)
        else:
            box = _draw_telecom_triangle(c, cx - 5.0, cy, s, "open", stem=(style == 3))
            _place_tag(c, "V" if style == 2 else "TEL", box, 0, tag_h=10.5)
    elif variant == "voice_data_combo":
        if style in (0, 3):
            _draw_telecom_triangle(c, cx, cy, s, "half_left" if style == 0 else "half_right", stem=(style == 3))
        elif style in (1, 2):
            box = _draw_telecom_triangle(c, cx - 7.0, cy, s, "open", stem=(style == 2))
            _place_tag(c, "VD" if style == 1 else "V/D", box, 0, tag_h=10.5)
        else:
            box = _draw_telecom_triangle(c, cx - 7.0, cy, s, "half_left", stem=False)
            _place_tag(c, "V/D", box, 0, tag_h=10.0)
    elif variant == "catv_outlet":
        if style == 2:
            box = _draw_badged_symbol(c, "diamond", "TV", cx, cy, min_w=32, min_h=32, text_h=11.0)
            _draw_wall_stem(c, cx, box[3], 7.0)
        elif style == 4:
            _draw_badged_symbol(c, "rect", "CATV", cx, cy, min_w=42, min_h=24, text_h=11.0)
        else:
            box = _draw_telecom_triangle(c, cx - 7.0, cy, s, "open", stem=(style == 3))
            _place_tag(c, "CATV" if style == 1 else "TV", box, 0, tag_h=10.5)
    elif variant == "data_floor":
        sq = s * 1.35
        c.rect((cx - sq, cy - sq), (cx + sq, cy + sq))
        if style == 2:
            c.rect((cx - sq - 3.2, cy - sq - 3.2), (cx + sq + 3.2, cy + sq + 3.2), t=c.stroke * 0.75)
        elif style == 4:
            for sx in (-1, 1):
                for sy in (-1, 1):
                    c.line((cx + sx * sq, cy + sy * sq), (cx + sx * (sq + 4.0), cy + sy * (sq + 4.0)))
        _draw_telecom_triangle(c, cx, cy, s * 0.72, "open", stem=False)
        if style in (1, 3):
            _place_tag(c, "F" if style == 1 else "D", (cx - sq, cy - sq, cx + sq, cy + sq), 1, tag_h=10.5)
    elif variant == "wap":
        if style == 0:
            _draw_badged_symbol(c, "rect", "WAP", cx, cy, min_w=40, min_h=24, text_h=11.5)
        elif style == 1:
            _draw_badged_symbol(c, "circle", "WAP", cx, cy, min_w=32, min_h=32, text_h=11.0)
        elif style == 2:
            _draw_badged_symbol(c, "diamond", "WAP", cx, cy, min_w=36, min_h=36, text_h=10.5)
        elif style == 3:
            c.circle((cx, cy + 10.0), 3.2, fill=True)
            c.arcs_wave((cx, cy + 10.0), 8.0, 5.5, 3, 225.0, 315.0)
            c.text("WAP", (cx, cy + 21.0), 9.5, font=cv2.FONT_HERSHEY_SIMPLEX)
        else:
            box = _draw_telecom_triangle(c, cx - 8.0, cy, s * 0.88, "open", stem=False)
            c.arcs_wave((cx + s + 4.0, cy), 5.0, 4.2, 2, -45.0, 45.0)
            c.text("WAP", (cx, box[3] + 9.0), 9.5, font=cv2.FONT_HERSHEY_SIMPLEX)
    elif variant == "fiber_optic_outlet":
        if style in (0, 3, 5):
            box = _draw_telecom_triangle(c, cx - 7.0, cy, s, "open", stem=(style == 3))
            _place_tag(c, "FO" if style != 5 else "F/O", box, 0, tag_h=10.5)
        elif style == 1:
            _draw_badged_symbol(c, "diamond", "FO", cx, cy, min_w=32, min_h=32, text_h=11.5)
        elif style == 2:
            _draw_badged_symbol(c, "square", "FO", cx, cy, min_w=28, min_h=28, text_h=12.0)
        else:
            _draw_telecom_triangle(c, cx - 7.0, cy, s, "open", stem=False)
            _draw_telecom_triangle(c, cx - 7.0, cy, s * 0.56, "open", stem=False)
            c.text("FO", (cx + s + 4.0, cy), 10.5, font=cv2.FONT_HERSHEY_SIMPLEX)
    elif variant == "data_two_gang":
        if style in (0, 1, 3):
            dx = s * 0.92
            _draw_telecom_triangle(c, cx - dx, cy, s * 0.82, "open", stem=(style == 1))
            _draw_telecom_triangle(c, cx + dx, cy, s * 0.82, "open", stem=(style == 1))
            c.line((cx - dx - s * 0.9, cy + s * 0.85), (cx + dx + s * 0.9, cy + s * 0.85), t=c.stroke * 0.85)
            if style == 3:
                c.text("2G", (cx, cy - s - 4.0), 9.5, font=cv2.FONT_HERSHEY_SIMPLEX)
        elif style == 2:
            box = _draw_telecom_triangle(c, cx - 7.0, cy, s, "open", stem=False)
            _place_tag(c, "2D", box, 0, tag_h=10.5)
        else:
            c.rect((cx - 28.0, cy - 15.0), (cx + 28.0, cy + 15.0))
            _draw_telecom_triangle(c, cx - 12.0, cy, 9.0, "open", stem=False)
            _draw_telecom_triangle(c, cx + 12.0, cy, 9.0, "open", stem=False)
    elif variant == "voice_data_two_gang":
        dx = s * 0.95
        if style in (0, 1, 4):
            m1 = "full" if style == 0 else "half_left"
            m2 = "open" if style == 0 else "half_right"
            _draw_telecom_triangle(c, cx - dx, cy, s * 0.82, m1, stem=(style == 4))
            _draw_telecom_triangle(c, cx + dx, cy, s * 0.82, m2, stem=(style == 4))
            c.line((cx - dx - s * 0.9, cy + s * 0.85), (cx + dx + s * 0.9, cy + s * 0.85), t=c.stroke * 0.85)
            if style == 1:
                c.text("V/D", (cx, cy - s - 4.0), 9.5, font=cv2.FONT_HERSHEY_SIMPLEX)
        elif style in (2, 3):
            box = _draw_telecom_triangle(c, cx - 10.0, cy, s, "half_left", stem=False)
            _place_tag(c, "VD-2G" if style == 2 else "2G", box, 0, tag_h=10.0)
        else:
            c.rect((cx - 30.0, cy - 16.0), (cx + 30.0, cy + 16.0))
            _draw_telecom_triangle(c, cx - 13.0, cy, 9.0, "full", stem=False)
            _draw_telecom_triangle(c, cx + 13.0, cy, 9.0, "open", stem=False)
    return c.render_crop()


def _draw_audio_visual(variant: str, style: int, rng: np.random.Generator) -> np.ndarray:
    c, cx, cy = _make_canvas(rng, 190, 160)
    if variant == "speaker":
        if style == 0:
            _draw_badged_symbol(c, "circle", "S", cx, cy, min_w=28, min_h=28, text_h=13.5)
        elif style == 1:
            box = _draw_badged_symbol(c, "circle", "S", cx, cy, min_w=28, min_h=28, text_h=13.0)
            _draw_wall_stem(c, cx, box[3], 6.5, with_bar=True)
        elif style in (2, 4):
            c.rect((cx - 14.0, cy - 6.0), (cx - 5.0, cy + 6.0))
            c.poly([(cx - 5.0, cy - 6.0), (cx + 8.0, cy - 14.0), (cx + 8.0, cy + 14.0), (cx - 5.0, cy + 6.0)])
            if style == 4:
                c.arcs_wave((cx + 8.0, cy), 6.0, 4.5, 2, -45.0, 45.0)
        else:
            hw = 15.0
            c.rect((cx - hw, cy - hw), (cx + hw, cy + hw))
            c.circle((cx, cy), 10.5)
            c.text("S", (cx, cy), 11.0)
    elif variant == "intercom":
        lbl = ("IC", "IC", "INT", "IC", "IC")[style % 5]
        sh = ("square", "circle", "rect", "square", "octagon")[style % 5]
        box = _draw_badged_symbol(c, sh, lbl, cx, cy, min_w=28, min_h=28, text_h=12.0)
        if style == 3:
            for dx in (-6.0, 0.0, 6.0):
                c.circle((cx + dx, box[3] - 4.0), 1.3, fill=True)
    elif variant == "intercom_weatherproof":
        if style in (0, 3, 5):
            box = _draw_badged_symbol(
                c, "square", "IC", cx - 4.0, cy, min_w=28, min_h=28, text_h=12.0, double_border=(style == 5)
            )
            _place_tag(c, "WP", box, 0 if style == 0 else 1, tag_h=10.0)
            if style == 3:
                _draw_wall_stem(c, cx - 4.0, box[3], 6.0)
        elif style == 1:
            _draw_badged_symbol(c, "rect", "IC-WP", cx, cy, min_w=46, min_h=24, text_h=10.5)
        elif style == 2:
            box = _draw_badged_symbol(c, "circle", "IC", cx - 5.0, cy, min_w=28, min_h=28, text_h=11.5)
            _place_tag(c, "WP", box, 0, tag_h=10.0)
        else:
            _draw_badged_symbol(c, "rect", "INT-WP", cx, cy, min_w=52, min_h=24, text_h=10.0)
    elif variant == "intercom_substation":
        lbl = ("IS", "ICS", "IC-S", "IC", "IS", "SS")[style % 6]
        sh = ("square", "rect", "rect", "square", "circle", "square")[style % 6]
        box = _draw_badged_symbol(c, sh, lbl, cx, cy, min_w=28, min_h=28, text_h=11.5)
        if style == 3:
            c.arrow((box[0] + 4.0, box[3] - 4.0), (box[2] - 4.0, box[1] + 4.0), head_len=4.5, head_w=2.4)
        elif style == 5:
            c.circle((cx, box[3] - 4.5), 2.0, fill=True)
    elif variant == "intercom_master_station":
        lbl = ("MI", "IMS", "ICM", "MI", "IC-M")[style % 5]
        sh = "square" if style in (0, 3) else "rect"
        _draw_badged_symbol(c, sh, lbl, cx, cy, min_w=34, min_h=26, text_h=12.0, double_border=(style == 3))
    elif variant == "clock_outlet":
        if style == 0:
            r = 12.5
            c.circle((cx - 4.0, cy), r)
            c.line((cx - 4.0 + r, cy), (cx - 4.0 + r + 9.0, cy))
            c.line((cx - 4.0 + r + 9.0, cy - 8.5), (cx - 4.0 + r + 9.0, cy + 8.5))
        elif style == 1:
            _draw_badged_symbol(c, "circle", "C", cx, cy, min_w=28, min_h=28, text_h=13.5)
        elif style == 2:
            r = 15.0
            c.circle((cx, cy), r)
            c.line((cx, cy), (cx, cy - r * 0.65))
            c.line((cx, cy), (cx + r * 0.55, cy))
        elif style == 3:
            _draw_badged_symbol(c, "circle", "CLK", cx, cy, min_w=32, min_h=32, text_h=10.5)
        else:
            r = 14.0
            c.circle((cx, cy), r)
            c.line((cx, cy), (cx, cy - r * 0.6))
            c.line((cx, cy), (cx + r * 0.5, cy + r * 0.25))
            _draw_wall_stem(c, cx, cy + r, 6.5, with_bar=True)
    return c.render_crop()


# ---------------------------------------------------------------------------
# Family dispatch & 100% catalog coverage verification
# ---------------------------------------------------------------------------

FAMILY_DRAWERS: dict[str, Callable[[str, int, np.random.Generator], np.ndarray]] = {
    "receptacle": _draw_receptacle,
    "switch": _draw_switch,
    "control_relay": _draw_control_relay,
    "sensor": _draw_sensor,
    "luminaire": _draw_luminaire,
    "panelboard": _draw_panelboard,
    "junction_box": _draw_junction_box,
    "transformer": _draw_transformer,
    "motor_control": _draw_motor_control,
    "disconnect": _draw_disconnect,
    "fire_alarm_device": _draw_fire_alarm,
    "security_device": _draw_security,
    "cctv_device": _draw_cctv,
    "data_outlet": _draw_data_outlet,
    "audio_visual_device": _draw_audio_visual,
}

SUPPORTED_VARIANTS: dict[str, frozenset[str]] = {
    "receptacle": frozenset({
        "duplex", "duplex_weatherproof", "quad", "quad_weatherproof",
        "single", "single_weatherproof", "special_purpose", "special_purpose_weatherproof",
        "duplex_above_counter", "duplex_gfci", "floor_mounted", "usb_charger_duplex",
        "isolated_ground", "duplex_gfci_weatherproof", "ceiling_mounted",
        "tamper_resistant", "quad_gfci",
    }),
    "switch": frozenset({
        "single_pole", "double_pole", "three_way", "four_way", "dimmer",
        "three_way_dimmer", "four_way_dimmer", "pilot_light", "key_operated",
        "momentary", "time_switch", "occupancy_sensor", "occupancy_sensor_dimming",
        "vacancy_sensor", "low_voltage", "low_voltage_dimmer", "wireless",
        "wireless_dimmer", "wireless_multi_button", "emergency_power_off_pushbutton",
    }),
    "control_relay": frozenset({
        "power_pack", "power_pack_dimming", "power_pack_auxiliary", "room_controller",
        "time_clock", "lighting_relay", "contactor", "emergency_transfer_device",
    }),
    "sensor": frozenset({
        "occupancy_sensor", "occupancy_sensor_wall", "occupancy_sensor_ceiling",
        "occupancy_sensor_dual_technology", "occupancy_sensor_wireless",
        "occupancy_sensor_wireless_wall", "occupancy_sensor_wireless_ceiling",
        "vacancy_sensor", "daylight_photosensor", "photocell",
        "thermostat", "humidistat", "co2_sensor", "co_sensor", "differential_pressure_sensor",
    }),
    "panelboard": frozenset({
        "lighting_control_panel", "panelboard", "automatic_transfer_switch", "switchboard_switchgear",
    }),
    "luminaire": frozenset(
        BASE_LUMINAIRE_VARIANTS
        | {f"{b}_emergency" for b in BASE_LUMINAIRE_VARIANTS}
        | {
            "emergency_battery_light",
            "emergency_remote_head",
            "exit_sign",
            "exit_sign_single_face",
            "exit_sign_single_face_single_arrow",
            "exit_sign_single_face_double_arrow",
            "exit_sign_double_face",
            "exit_sign_double_face_double_arrow",
            "exit_sign_double_face_four_arrow",
            "exit_emergency_combo",
        }
    ),
    "junction_box": frozenset({"junction_box", "pull_box"}),
    "transformer": frozenset({"transformer"}),
    "motor_control": frozenset({"vfd", "motor_starter"}),
    "disconnect": frozenset({"disconnect_switch", "fused", "non_fused", "motor_rated", "weatherproof"}),
    "fire_alarm_device": frozenset({
        "smoke_detector", "fire_alarm_control_panel", "fire_alarm_strobe", "annunciator_panel",
        "duct_smoke_detector", "manual_pull_station", "heat_detector", "horn_and_strobe",
        "speaker_and_strobe", "voice_evacuation_panel", "heat_detector_rate_of_rise",
        "smoke_detector_sounder_base", "fire_alarm_horn", "fire_alarm_bell", "fire_alarm_module",
        "tamper_switch", "waterflow_switch", "area_of_refuge_station",
        "carbon_monoxide_detector", "elevator_recall_detector", "emergency_responder_radio",
        "door_holder",
    }),
    "security_device": frozenset({
        "card_reader", "glass_break_detector", "panic_duress_button", "door_release_button",
        "request_to_exit", "gate_operator", "gate_release_button", "card_reader_long_range",
        "door_contact_switch", "electric_door_strike", "security_keypad",
        "card_reader_proximity", "magnetic_door_lock", "security_alarm_strobe",
        "access_control_panel",
    }),
    "cctv_device": frozenset({"dvr_nvr", "camera_mini_dome", "security_camera", "camera_ptz"}),
    "data_outlet": frozenset({
        "data", "wap", "catv_outlet", "data_floor", "telephone_outlet",
        "voice_data_combo", "fiber_optic_outlet", "voice_data_two_gang", "data_two_gang",
    }),
    "audio_visual_device": frozenset({
        "speaker", "intercom_weatherproof", "intercom_substation",
        "clock_outlet", "intercom_master_station", "intercom",
    }),
}

STYLE_NAMES = (
    "cad_primary",
    "cad_alternate",
    "cad_stemmed",
    "cad_compact",
    "cad_heavy",
    "cad_extended",
)


def is_scarce_or_complex(device_entry: dict) -> bool:
    """Devices with zero/scarce real legend instances or complex multi-part geometry get extra style diversity."""
    fam = device_entry["symbol_family"]
    var = device_entry["symbol_variant"]
    src = int(device_entry.get("source_count") or 0)
    if src <= 5:
        return True
    if fam == "luminaire" and (var.startswith("exit_") or var.endswith("_emergency") or "floodlight" in var):
        return True
    if fam == "fire_alarm_device" and var in ("horn_and_strobe", "speaker_and_strobe", "emergency_responder_radio"):
        return True
    if fam in ("control_relay", "cctv_device"):
        return True
    return False


def styles_count_for_device(device_entry: dict, base_styles: int) -> int:
    base = max(4, min(6, int(base_styles)))
    if is_scarce_or_complex(device_entry):
        return min(6, max(5, base + 1))
    return base


def verify_catalog_support(devices: list[dict]) -> None:
    missing = []
    for d in devices:
        fam = d["symbol_family"]
        var = d["symbol_variant"]
        if fam not in FAMILY_DRAWERS or var not in SUPPORTED_VARIANTS.get(fam, frozenset()):
            missing.append(f"{fam}.{var}")
    if missing:
        raise RuntimeError(f"Unsupported catalog devices with no renderer: {missing}")


def render_symbol(family: str, variant: str, style_idx: int, seed: int) -> np.ndarray:
    drawer = FAMILY_DRAWERS.get(family)
    if drawer is None or variant not in SUPPORTED_VARIANTS.get(family, frozenset()):
        raise KeyError(f"No synthetic symbol renderer for {family}.{variant}")
    rng = _rng_for(seed, f"{family}.{variant}", style_idx)
    return drawer(variant, style_idx, rng)


# ---------------------------------------------------------------------------
# Contact sheet rendering
# ---------------------------------------------------------------------------

def _slugify(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "_", text.lower()).strip("_")


def render_contact_sheets(
    catalog: dict,
    records_by_device: dict[str, list[tuple[dict, np.ndarray]]],
    audit_dir: Path,
    per_page: int = 12,
) -> list[Path]:
    audit_dir.mkdir(parents=True, exist_ok=True)
    for old in audit_dir.glob("synth_*.png"):
        old.unlink()

    categories = catalog.get("categories") or sorted({d["category"] for d in catalog["devices"]})
    by_cat: dict[str, list[dict]] = {cat: [] for cat in categories}
    for d in catalog["devices"]:
        by_cat.setdefault(d["category"], []).append(d)

    written: list[Path] = []
    label_col_w = 300
    cell_w, cell_h = 138, 114
    max_cols = 6
    sheet_w = label_col_w + max_cols * cell_w + 16
    header_h = 38

    for cat_idx, cat in enumerate(categories, start=1):
        cat_devs = [d for d in by_cat.get(cat, []) if f"{d['symbol_family']}.{d['symbol_variant']}" in records_by_device]
        if not cat_devs:
            continue
        slug = _slugify(cat)
        total_pages = (len(cat_devs) + per_page - 1) // per_page
        for page_i in range(total_pages):
            chunk = cat_devs[page_i * per_page : (page_i + 1) * per_page]
            sheet_h = header_h + len(chunk) * cell_h + 12
            sheet = np.full((sheet_h, sheet_w, 3), 255, dtype=np.uint8)
            suffix = f"_p{page_i + 1}" if total_pages > 1 else ""
            title = (
                f"[{cat_idx:02d}] {cat} ({len(cat_devs)} devices)"
                f"{f' - Page {page_i + 1}/{total_pages}' if total_pages > 1 else ''}"
            )
            cv2.rectangle(sheet, (0, 0), (sheet_w, header_h - 4), (235, 240, 248), -1)
            cv2.putText(sheet, title, (12, 24), cv2.FONT_HERSHEY_SIMPLEX, 0.58, (20, 40, 90), 2, cv2.LINE_AA)

            for row_i, dev in enumerate(chunk):
                dev_id = f"{dev['symbol_family']}.{dev['symbol_variant']}"
                oy = header_h + row_i * cell_h
                cv2.rectangle(sheet, (4, oy), (sheet_w - 6, oy + cell_h - 4), (215, 215, 215), 1)
                cv2.putText(sheet, dev_id, (12, oy + 26), cv2.FONT_HERSHEY_SIMPLEX, 0.44, (15, 50, 140), 1, cv2.LINE_AA)
                lbl = dev["label"][:42]
                cv2.putText(sheet, lbl, (12, oy + 48), cv2.FONT_HERSHEY_SIMPLEX, 0.39, (40, 40, 40), 1, cv2.LINE_AA)
                items = records_by_device[dev_id]
                src_n = int(dev.get("source_count") or 0)
                cv2.putText(
                    sheet,
                    f"styles={len(items)}  src_cnt={src_n}",
                    (12, oy + 72),
                    cv2.FONT_HERSHEY_SIMPLEX,
                    0.38,
                    (90, 90, 90),
                    1,
                    cv2.LINE_AA,
                )

                for col_i, (_, gray) in enumerate(items[:max_cols]):
                    ox = label_col_w + col_i * cell_w
                    cv2.rectangle(sheet, (ox + 2, oy + 4), (ox + cell_w - 4, oy + cell_h - 8), (235, 235, 235), 1)
                    gh, gw = gray.shape
                    avail_w, avail_h = cell_w - 16, cell_h - 32
                    scale = min(1.0, avail_w / max(gw, 1), avail_h / max(gh, 1))
                    if scale < 1.0:
                        dw, dh = max(1, int(round(gw * scale))), max(1, int(round(gh * scale)))
                        disp = cv2.resize(gray, (dw, dh), interpolation=cv2.INTER_AREA)
                    else:
                        disp = gray
                        dh, dw = disp.shape
                    px = ox + 2 + (cell_w - 6 - dw) // 2
                    py = oy + 8 + (avail_h - dh) // 2
                    sheet[py:py + dh, px:px + dw] = np.repeat(disp[:, :, None], 3, axis=2)
                    cv2.putText(
                        sheet,
                        f"s{col_i} {gw}x{gh}",
                        (ox + 8, oy + cell_h - 12),
                        cv2.FONT_HERSHEY_SIMPLEX,
                        0.35,
                        (100, 100, 100),
                        1,
                        cv2.LINE_AA,
                    )

            out_path = audit_dir / f"synth_{cat_idx:02d}_{slug}{suffix}.png"
            cv2.imwrite(str(out_path), sheet, [cv2.IMWRITE_PNG_COMPRESSION, 1])
            written.append(out_path)

    return written


# ---------------------------------------------------------------------------
# Main generation pipeline & CLI
# ---------------------------------------------------------------------------

def generate_catalog_symbols(
    catalog_path: Path,
    output_dir: Path,
    *,
    audit_dir: Path | None = None,
    styles_per_device: int = 5,
    seed: int = 2026,
    only_devices: list[str] | None = None,
) -> dict:
    catalog = json.loads(Path(catalog_path).read_text(encoding="utf-8"))
    devices = catalog["devices"]
    verify_catalog_support(devices)

    selected = devices
    if only_devices:
        wanted = set(only_devices)
        selected = [d for d in devices if f"{d['symbol_family']}.{d['symbol_variant']}" in wanted]

    output_dir = Path(output_dir)
    crops_dir = output_dir / "crops"
    crops_dir.mkdir(parents=True, exist_ok=True)

    crops_meta: list[dict] = []
    review_meta: dict[str, dict] = {}
    records_by_device: dict[str, list[tuple[dict, np.ndarray]]] = {}
    written_ids: set[str] = set()

    for dev in selected:
        fam = dev["symbol_family"]
        var = dev["symbol_variant"]
        dev_id = f"{fam}.{var}"
        n_styles = styles_count_for_device(dev, styles_per_device)
        samples = dev.get("sample_legend_texts") or []
        legend_text = str(samples[0]) if samples else str(dev["label"])
        dev_records: list[tuple[dict, np.ndarray]] = []

        for s_idx in range(n_styles):
            gray = render_symbol(fam, var, s_idx, seed)
            crop_id = f"syn_{fam}__{var}__s{s_idx:02d}"
            style_name = STYLE_NAMES[s_idx % len(STYLE_NAMES)]
            cv2.imwrite(str(crops_dir / f"{crop_id}.png"), gray, [cv2.IMWRITE_PNG_COMPRESSION, 1])
            written_ids.add(crop_id)

            rec = {
                "id": crop_id,
                "method": "synthetic_cad",
                "device": dev_id,
                "legend_text": legend_text,
                "style": style_name,
                "source_file": f"synthetic_cad/{dev_id}",
            }
            crops_meta.append(rec)
            review_meta[crop_id] = {
                "status": "accepted",
                "device": dev_id,
                "by": GENERATOR_BY,
            }
            dev_records.append((rec, gray))
        records_by_device[dev_id] = dev_records

    for png in crops_dir.glob("*.png"):
        if png.stem not in written_ids:
            png.unlink()

    crops_payload = {
        "format_version": 1,
        "about": (
            "Deterministic multi-style CAD/blueprint synthetic symbols covering 100% of "
            "electrical_device_catalog.json (scripts_generate_catalog_symbols.py)."
        ),
        "seed": int(seed),
        "generator": GENERATOR_BY,
        "crops": crops_meta,
    }
    (output_dir / "crops.json").write_text(
        json.dumps(crops_payload, indent=1, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    (output_dir / "review.json").write_text(
        json.dumps(review_meta, indent=1, ensure_ascii=False) + "\n", encoding="utf-8"
    )

    audit_sheets: list[Path] = []
    if audit_dir is not None:
        audit_sheets = render_contact_sheets(catalog, records_by_device, Path(audit_dir))

    return {
        "devices": len(records_by_device),
        "crops": len(crops_meta),
        "audit_sheets": [str(p) for p in audit_sheets],
        "output_dir": str(output_dir),
    }


def main(argv: list[str] | None = None) -> None:
    repo_root = _default_repo_root()
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument(
        "--catalog",
        type=Path,
        default=repo_root / "ml" / "symbol-detector" / "annotator" / "electrical_device_catalog.json",
    )
    p.add_argument(
        "--output",
        type=Path,
        default=repo_root / "blueprints_and_libraries" / "2_component_libraries" / "synthetic_catalog_symbols",
    )
    p.add_argument(
        "--audit-dir",
        type=Path,
        default=repo_root / "scratch" / "synthetic_audit",
        help="Folder for paginated category contact sheets (pass --no-audit to skip).",
    )
    p.add_argument("--no-audit", action="store_true", help="Skip rendering contact sheets.")
    p.add_argument("--styles-per-device", type=int, default=5, help="Base styles per device (4-6).")
    p.add_argument("--seed", type=int, default=2026)
    p.add_argument("--devices", nargs="*", default=None, help="Optional subset of family.variant devices.")
    args = p.parse_args(argv)

    audit_dir = None if args.no_audit else args.audit_dir
    summary = generate_catalog_symbols(
        args.catalog,
        args.output,
        audit_dir=audit_dir,
        styles_per_device=args.styles_per_device,
        seed=args.seed,
        only_devices=args.devices,
    )
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
