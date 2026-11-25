"""Walls-only drawings rendered at load time from per-line labelled CAD datasets.

ArchCAD (JSON entities, 980 px frame) and FloorPlanCAD (SVG, 100-unit viewBox)
label every line. Keeping only structure (walls, columns, beams, windows,
grid lines, railings) gives drawings that provably contain no symbols, so
pretraining can mutate them as hard as it likes. Each call draws the lines
with a new scale, line weight, ink and paper, so the same slice never looks
the same twice.

Entries in an SSL manifest look like::

    {"vector_walls": "/path/slice.json", "format": "archcad"}
"""
from __future__ import annotations

import json
import math
import re
from pathlib import Path

import cv2
import numpy as np

# ArchCAD semantic IDs: 0 axis & grid, 19 glass, 20 wall, 21/22 concrete/steel
# column, 23/24 concrete/steel beam, 26 foundation, 28 rebar. Not 27 (piles):
# grids of pile circles look like downlights.
ARCHCAD_STRUCTURE = frozenset({0, 19, 20, 21, 22, 23, 24, 26, 28})
# FloorPlanCAD semantic IDs in this release, checked by drawing each one (the
# published class order does not match): 1 wall, 2 curtain wall, 9 window.
# Not 35 (parked cars), 3-6 (door swings), 11/12 (boxes, opening marks), 19
# (squares with circle and cross: could be ceiling devices).
FLOORPLANCAD_STRUCTURE = frozenset({1, 2, 9})
ARCHCAD_FRAME = 980.0
FLOORPLANCAD_FRAME = 1000.0      # viewBox 0..100, drawn like the 1000 px PNGs
MIN_LINES = 12                   # fewer kept lines: nearly blank, not worth a sample

Polyline = np.ndarray            # (N, 2) float, frame units


def _arc(cx, cy, r, a0, a1, steps_per_rad=12.0) -> Polyline:
    """Counter-clockwise arc from a0 to a1 (radians)."""
    while a1 <= a0:
        a1 += 2 * math.pi
    n = max(2, int((a1 - a0) * steps_per_rad) + 1)
    t = np.linspace(a0, a1, n)
    return np.stack([cx + r * np.cos(t), cy + r * np.sin(t)], 1)


def archcad_lines(path: str | Path, keep=ARCHCAD_STRUCTURE) -> list[Polyline]:
    lines: list[Polyline] = []
    for e in json.loads(Path(path).read_text())["entities"]:
        if e.get("semantic") not in keep:
            continue
        kind = e.get("type")
        try:
            if kind == "LINE":
                lines.append(np.array([e["start"], e["end"]], float))
            elif kind == "CIRCLE":
                cx, cy = e["center"]
                lines.append(_arc(cx, cy, e["radius"], 0, 2 * math.pi))
            elif kind == "ARC":
                cx, cy = e["center"]
                a0, a1 = math.radians(e["start_angle"]), math.radians(e["end_angle"])
                if e.get("direction") == 1:        # clockwise: same points as end -> start CCW
                    a0, a1 = a1, a0
                lines.append(_arc(cx, cy, e["radius"], a0, a1))
            elif kind == "ELLIPSE":
                cx, cy = e["center"]
                mx, my = e["major_axis"]
                ratio = float(e["ratio"])
                t = np.linspace(float(e["start_param"]), float(e["end_param"]), 48)
                minor = np.array([-my, mx]) * ratio
                pts = (np.array([cx, cy]) + np.outer(np.cos(t), [mx, my])
                       + np.outer(np.sin(t), minor))
                lines.append(pts)
        except (KeyError, TypeError, ValueError):
            continue
    return lines


_NUM = r"-?\d*\.?\d+(?:[eE][-+]?\d+)?"
_TOKEN = re.compile(rf"[MLAZmlaz]|{_NUM}")
_ELEMENT = re.compile(r"<(path|circle|ellipse)\b([^>]*)>")
_ATTR = re.compile(r'([\w:-]+)="([^"]*)"')


def _svg_arc(x0, y0, rx, ry, phi, large, sweep, x1, y1) -> Polyline:
    """SVG endpoint arc to points (SVG implementation notes, F.6.5)."""
    if rx == 0 or ry == 0:
        return np.array([[x0, y0], [x1, y1]], float)
    rx, ry = abs(rx), abs(ry)
    c, s = math.cos(phi), math.sin(phi)
    dx, dy = (x0 - x1) / 2, (y0 - y1) / 2
    xp, yp = c * dx + s * dy, -s * dx + c * dy
    lam = (xp / rx) ** 2 + (yp / ry) ** 2
    if lam > 1:
        rx, ry = rx * math.sqrt(lam), ry * math.sqrt(lam)
    num = rx * rx * ry * ry - rx * rx * yp * yp - ry * ry * xp * xp
    den = rx * rx * yp * yp + ry * ry * xp * xp
    coef = math.sqrt(max(0.0, num / den)) if den else 0.0
    if large == sweep:
        coef = -coef
    cxp, cyp = coef * rx * yp / ry, -coef * ry * xp / rx
    cx = c * cxp - s * cyp + (x0 + x1) / 2
    cy = s * cxp + c * cyp + (y0 + y1) / 2

    def ang(ux, uy, vx, vy):
        return math.atan2(ux * vy - uy * vx, ux * vx + uy * vy)
    t0 = ang(1, 0, (xp - cxp) / rx, (yp - cyp) / ry)
    dt = ang((xp - cxp) / rx, (yp - cyp) / ry, (-xp - cxp) / rx, (-yp - cyp) / ry)
    if not sweep and dt > 0:
        dt -= 2 * math.pi
    elif sweep and dt < 0:
        dt += 2 * math.pi
    t = np.linspace(t0, t0 + dt, max(2, int(abs(dt) * 12) + 1))
    return np.stack([cx + rx * c * np.cos(t) - ry * s * np.sin(t),
                     cy + rx * s * np.cos(t) + ry * c * np.sin(t)], 1)


def _path_lines(d: str) -> list[Polyline]:
    tokens = _TOKEN.findall(d)
    out: list[Polyline] = []
    current: list[list[float]] = []
    x = y = 0.0
    i, cmd = 0, None
    while i < len(tokens):
        if tokens[i] in "MLAZmlaz":
            cmd = tokens[i]
            i += 1
            if cmd in "Zz" and current:
                current.append(current[0])
                continue
        if cmd is None:
            break
        try:
            if cmd in "Mm":
                if len(current) > 1:
                    out.append(np.array(current, float))
                nx, ny = float(tokens[i]), float(tokens[i + 1])
                x, y = (x + nx, y + ny) if cmd == "m" else (nx, ny)
                current = [[x, y]]
                i += 2
                cmd = "l" if cmd == "m" else "L"
            elif cmd in "Ll":
                nx, ny = float(tokens[i]), float(tokens[i + 1])
                x, y = (x + nx, y + ny) if cmd == "l" else (nx, ny)
                current.append([x, y])
                i += 2
            elif cmd in "Aa":
                rx, ry, rot, large, sweep, nx, ny = map(float, tokens[i:i + 7])
                if cmd == "a":
                    nx, ny = x + nx, y + ny
                pts = _svg_arc(x, y, rx, ry, math.radians(rot), int(large), int(sweep), nx, ny)
                current.extend(pts[1:].tolist())
                x, y = nx, ny
                i += 7
            else:
                i += 1
        except (IndexError, ValueError):
            break
    if len(current) > 1:
        out.append(np.array(current, float))
    return out


def floorplancad_lines(path: str | Path, keep=FLOORPLANCAD_STRUCTURE) -> list[Polyline]:
    text = Path(path).read_text(errors="replace")
    scale = FLOORPLANCAD_FRAME / 100.0
    lines: list[Polyline] = []
    for tag, attrs in _ELEMENT.findall(text):
        a = dict(_ATTR.findall(attrs))
        try:
            if int(a.get("semantic-id", -1)) not in keep:
                continue
            if tag == "path":
                lines.extend(_path_lines(a.get("d", "")))
            elif tag == "circle":
                lines.append(_arc(float(a["cx"]), float(a["cy"]), float(a["r"]), 0, 2 * math.pi))
            else:
                cx, cy, rx, ry = (float(a[k]) for k in ("cx", "cy", "rx", "ry"))
                t = np.linspace(0, 2 * math.pi, 48)
                lines.append(np.stack([cx + rx * np.cos(t), cy + ry * np.sin(t)], 1))
        except (KeyError, ValueError):
            continue
    return [p * scale for p in lines]


def wall_lines(entry: dict) -> tuple[list[Polyline], float]:
    fmt = entry.get("format")
    if fmt == "archcad":
        return archcad_lines(entry["vector_walls"]), ARCHCAD_FRAME
    if fmt == "floorplancad":
        return floorplancad_lines(entry["vector_walls"]), FLOORPLANCAD_FRAME
    raise ValueError(f"unknown vector walls format: {fmt}")


def render_wall_lines(lines: list[Polyline], frame: float, rng: np.random.Generator,
                      *, min_side: int = 600) -> np.ndarray | None:
    """Draw the lines with a random scale, line weight, ink and paper (BGR,
    light background). None if too few lines to be worth a sample."""
    if len(lines) < MIN_LINES:
        return None
    scale = float(rng.uniform(max(0.7, min_side / frame), 2.2))
    side = int(math.ceil(frame * scale))
    paper = int(rng.integers(228, 256))
    img = np.full((side, side, 3), paper, np.uint8)
    if rng.random() < 0.25:                          # tinted paper (buff, blue-grey, green-grey)
        img[:] = np.clip(np.array([paper, paper, paper]) - rng.integers(0, 22, 3), 0, 255)
    ink = int(rng.integers(0, 90))
    color = (ink, ink, ink)
    if rng.random() < 0.2:                           # coloured CAD pen
        color = tuple(int(v) for v in rng.integers(0, 160, 3))
    weight = int(rng.choice([1, 1, 2, 2, 3, 4]))
    heavy = weight + int(rng.integers(1, 4))         # some layers drawn bolder, like cut walls
    bold_share = float(rng.uniform(0, 0.5))
    aa = cv2.LINE_AA if rng.random() < 0.8 else cv2.LINE_8
    flip = rng.random() < 0.5                        # CAD y-up vs image y-down: both are fine
    for pts in lines:
        p = pts * scale
        if flip:
            p = np.stack([p[:, 0], side - p[:, 1]], 1)
        if not np.isfinite(p).all():
            continue
        w = heavy if rng.random() < bold_share else weight
        cv2.polylines(img, [np.round(p).astype(np.int32)], False, color, w, aa)
    return img


def render_vector_walls(entry: dict, rng: np.random.Generator) -> np.ndarray | None:
    lines, frame = wall_lines(entry)
    return render_wall_lines(lines, frame, rng)
