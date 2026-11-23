"""Bounded, *per-symbol* drawing distortion and free-placement diversification.

This module operates on the symbol's alpha mask before it is composited onto
a construction plan. Unlike whole-image noise, it independently changes each
symbol's geometry, print strokes and local raster quality. There is no semantic
class remapping: impossible-to-recognize outcomes are rejected by conservative
geometric/ink guards (which cannot guarantee human legibility).

Existing supervised compositors retain their original behavior unless these
helpers are explicitly enabled. Use one numpy Generator for reproducibility.
"""
from __future__ import annotations

import math

import cv2
import numpy as np


def _trim(alpha: np.ndarray, threshold: int = 12) -> np.ndarray:
    ys, xs = np.nonzero(alpha > threshold)
    if not len(xs):
        return alpha
    return np.ascontiguousarray(alpha[ys.min():ys.max() + 1, xs.min():xs.max() + 1])


def _valid(candidate: np.ndarray, reference: np.ndarray) -> bool:
    """Reject erased, excessively enlarged, or highly collapsed glyphs."""
    if candidate.ndim != 2 or candidate.dtype != np.uint8:
        return False
    ys, xs = np.nonzero(candidate > 32)
    if not len(xs):
        return False
    h, w = candidate.shape
    rh, rw = reference.shape
    ink0 = max(1, int((reference > 32).sum()))
    ink1 = len(xs)
    if ink1 < max(5, round(ink0 * 0.32)) or ink1 > ink0 * 3.4:
        return False
    if min(w, h) < 3 or w > max(12, rw * 2.25) or h > max(12, rh * 2.25):
        return False
    if max(w / max(1, rw), h / max(1, rh)) > 2.25:
        return False
    if min(w / max(1, rw), h / max(1, rh)) < 0.36:
        return False

    # Thin electrical symbols are distinguished by stroke continuity and
    # holes. A random field of disconnected dots can satisfy the same ink
    # fraction and bbox limits yet no longer resemble any original device.
    ref_binary = (reference > 64).astype(np.uint8)
    cand_binary = (candidate > 64).astype(np.uint8)
    ref_components = cv2.connectedComponents(ref_binary, connectivity=8)[0] - 1
    cand_components = cv2.connectedComponents(cand_binary, connectivity=8)[0] - 1
    if cand_components > max(ref_components + 5, ref_components * 3):
        return False
    # Keep at least one enclosing loop if the source symbol actually had
    # one. Closed rings/circles encode important device distinctions.
    def holes(binary: np.ndarray) -> int:
        _, hierarchy = cv2.findContours(
            binary, cv2.RETR_CCOMP, cv2.CHAIN_APPROX_SIMPLE
        )
        if hierarchy is None:
            return 0
        return int((hierarchy[0, :, 3] >= 0).sum())

    if holes(ref_binary) >= 1 and holes(cand_binary) == 0:
        return False
    return True


def _affine(alpha: np.ndarray, rng: np.random.Generator, power: float) -> np.ndarray:
    """Independent x/y stretch, simultaneous shear, slight skew and rotation."""
    h, w = alpha.shape
    sx = float(np.exp(rng.uniform(-0.44, 0.44) * power))
    sy = float(np.exp(rng.uniform(-0.44, 0.44) * power))
    shx = float(rng.uniform(-0.32, 0.32) * power)
    shy = float(rng.uniform(-0.32, 0.32) * power)
    angle = float(rng.uniform(-23, 23) * power) * math.pi / 180
    A = np.array([[sx, shx], [shy, sy]], dtype=np.float32)
    R = np.array([[math.cos(angle), -math.sin(angle)],
                  [math.sin(angle), math.cos(angle)]], dtype=np.float32)
    A = R @ A
    # Avoid foldover. Multiple independent stretches do NOT imply flips.
    if np.linalg.det(A) < 0.30:
        return alpha
    corners = np.array([[0, 0], [w, 0], [w, h], [0, h]], np.float32)
    transformed = corners @ A.T
    low, high = transformed.min(axis=0), transformed.max(axis=0)
    ow, oh = np.maximum(2, np.ceil(high - low).astype(int) + 2)
    if ow > max(24, w * 3) or oh > max(24, h * 3):
        return alpha
    affine = np.column_stack([A, -low + 1]).astype(np.float32)
    result = cv2.warpAffine(alpha, affine, (int(ow), int(oh)),
                            flags=cv2.INTER_LINEAR, borderValue=0)
    return _trim(result)


def _perspective(alpha: np.ndarray, rng: np.random.Generator, power: float) -> np.ndarray:
    """Four independently displaced corners: keystone and quadrilateral twist."""
    h, w = alpha.shape
    pad = max(4, round(max(w, h) * 0.23))
    src = cv2.copyMakeBorder(alpha, pad, pad, pad, pad, cv2.BORDER_CONSTANT, value=0)
    hh, ww = src.shape
    a = np.array([[0, 0], [ww-1, 0], [ww-1, hh-1], [0, hh-1]], np.float32)
    jitter = rng.uniform(-min(w, h) * 0.15 * power,
                          min(w, h) * 0.15 * power, a.shape).astype(np.float32)
    b = a + jitter
    warped = cv2.warpPerspective(src, cv2.getPerspectiveTransform(a, b),
                                  (ww, hh), flags=cv2.INTER_LINEAR, borderValue=0)
    return _trim(warped)


def _elastic(alpha: np.ndarray, rng: np.random.Generator, power: float, mode: str) -> np.ndarray:
    """Nonlinear x/y mesh, curving, waviness, local bulge, swirl, and pinch."""
    h, w = alpha.shape
    pad = max(5, round(max(w, h) * 0.22))
    src = cv2.copyMakeBorder(alpha, pad, pad, pad, pad, cv2.BORDER_CONSTANT, value=0)
    hh, ww = src.shape
    y, x = np.mgrid[:hh, :ww].astype(np.float32)
    cx, cy = (ww - 1) / 2, (hh - 1) / 2
    scale = max(1., float(min(w, h)))
    amount = scale * float(rng.uniform(0.035, 0.13) * power)
    phase0, phase1 = rng.uniform(-math.pi, math.pi, 2)
    waves_x, waves_y = rng.uniform(0.7, 2.7, 2)
    if mode == "mesh":
        # Bicubic random displacement field rather than one global warp.
        dx = cv2.resize(rng.normal(0, amount, (4, 4)).astype(np.float32),
                        (ww, hh), interpolation=cv2.INTER_CUBIC)
        dy = cv2.resize(rng.normal(0, amount, (4, 4)).astype(np.float32),
                        (ww, hh), interpolation=cv2.INTER_CUBIC)
    elif mode == "ripple":
        dx = amount * np.sin(y / hh * math.tau * waves_y + phase0)
        dy = amount * np.sin(x / ww * math.tau * waves_x + phase1)
    elif mode == "fold":
        dx = amount * np.tanh((y - cy) / max(2, h * .13)) * np.sin(phase0)
        dy = amount * np.tanh((x - cx) / max(2, w * .13)) * np.sin(phase1)
    else:  # radial pinch/bulge and local twist
        u, v = x - cx, y - cy
        radius = np.sqrt(u * u + v * v)
        falloff = np.clip(1. - radius / max(1, scale * .75), 0, 1) ** 2
        ang = float(rng.uniform(-0.60, 0.60)) * power * falloff
        magnify = 1. + float(rng.uniform(-.22, .22)) * power * falloff
        dx = magnify * (np.cos(ang) * u - np.sin(ang) * v) - u
        dy = magnify * (np.sin(ang) * u + np.cos(ang) * v) - v
    result = cv2.remap(src, (x + dx).astype(np.float32),
                       (y + dy).astype(np.float32), cv2.INTER_LINEAR,
                       borderMode=cv2.BORDER_CONSTANT, borderValue=0)
    return _trim(result)


def _print_damage(alpha: np.ndarray, rng: np.random.Generator, power: float, mode: str) -> np.ndarray:
    h, w = alpha.shape
    out = alpha.copy()
    if mode == "stroke_gain":
        kernel = cv2.getStructuringElement(
            int(rng.choice([cv2.MORPH_ELLIPSE, cv2.MORPH_RECT, cv2.MORPH_CROSS])),
            (3, 3),
        )
        out = cv2.dilate(out, kernel) if rng.random() < .5 else cv2.erode(out, kernel)
    elif mode == "dry_pen":
        # Remove small random runs of ink, without deleting the entire stroke.
        for _ in range(int(rng.integers(1, max(3, round(3 + power * 7))))):
            x, y = int(rng.integers(w)), int(rng.integers(h))
            length = max(1, int(rng.uniform(1, 0.17 * max(h, w) + 1) * power))
            ang = float(rng.uniform(0, math.tau))
            end = (int(x + math.cos(ang) * length),
                   int(y + math.sin(ang) * length))
            cv2.line(out, (x, y), end, 0, 1)
    elif mode == "misregistration":
        dx = int(rng.choice([-2, -1, 1, 2]))
        dy = int(rng.choice([-2, -1, 1, 2]))
        ghost = cv2.warpAffine(out, np.float32([[1, 0, dx], [0, 1, dy]]),
                               (w, h), borderValue=0)
        out = np.maximum(out, (ghost.astype(np.float32) * rng.uniform(.18, .48)).astype(np.uint8))
    elif mode == "low_dpi":
        factor = float(rng.uniform(.40, .83))
        sh = max(4, int(round(h * factor)))
        sw = max(4, int(round(w * factor)))
        small = cv2.resize(out, (sw, sh), interpolation=cv2.INTER_AREA)
        out = cv2.resize(small, (w, h), interpolation=int(rng.choice(
            [cv2.INTER_LINEAR, cv2.INTER_NEAREST, cv2.INTER_CUBIC])))
    elif mode == "ink_fade":
        brightness = float(rng.uniform(.45, .84))
        out = np.clip(out.astype(np.float32) * brightness, 0, 255).astype(np.uint8)
    elif mode == "blur":
        out = cv2.GaussianBlur(out, (0, 0), float(rng.uniform(.35, 1.1) * power))
    return out


def distort_symbol_alpha(
    alpha: np.ndarray,
    rng: np.random.Generator,
    *,
    probability: float = 0.85,
    strength: float = 1.0,
) -> tuple[np.ndarray, dict]:
    """Independent multi-axis symbol warp with an explicit readability budget.

    Candidate transforms are accepted only if appreciable ink remains and the
    symbol's bounds remain usable for placement. Not a human recognizability
    guarantee. `probability=0` preserves the input byte-for-byte.
    """
    if alpha.ndim != 2 or alpha.dtype != np.uint8 or not alpha.size:
        raise ValueError("symbol mask must be non-empty uint8 [height, width]")
    if not 0 <= probability <= 1 or not math.isfinite(strength) or not 0 <= strength <= 2:
        raise ValueError("invalid symbol chaos parameters")
    if min(alpha.shape) < 3:
        return alpha.copy(), {"severity": "unchanged", "operations": [], "rejected": 0}
    if probability == 0 or strength == 0 or rng.random() >= probability:
        return alpha.copy(), {"severity": "unchanged", "operations": [], "rejected": 0}

    profile = str(rng.choice(("subtle", "moderate", "heavy", "extreme"),
                             p=(.29, .37, .28, .06)))
    power = strength * {"subtle": .45, "moderate": .77,
                        "heavy": 1.05, "extreme": 1.30}[profile]
    operations: list[str] = []
    rejected = 0
    original = _trim(alpha)
    out = original.copy()
    # 1..4 independent stages; a high-strength warp is not guaranteed
    # to remain recognizable, hence the per-stage validity checks.
    count = int(rng.integers(1, 5))
    modes = ["axis_stretch_shear", "perspective", "elastic_mesh",
             "cross_ripple", "fold", "pinch_twist", "stroke_gain",
             "dry_pen", "misregistration", "low_dpi", "ink_fade", "blur"]
    for mode in rng.choice(modes, size=count, replace=False):
        if mode == "axis_stretch_shear":
            candidate = _affine(out, rng, power)
        elif mode == "perspective":
            candidate = _perspective(out, rng, power)
        elif mode in ("elastic_mesh", "cross_ripple", "fold", "pinch_twist"):
            kinds = {"elastic_mesh": "mesh", "cross_ripple": "ripple",
                     "fold": "fold", "pinch_twist": "radial"}
            candidate = _elastic(out, rng, power, kinds[str(mode)])
        else:
            candidate = _print_damage(out, rng, power, str(mode))
        candidate = _trim(candidate)
        if _valid(candidate, original):
            out = candidate
            operations.append(str(mode))
        else:
            rejected += 1
    return np.ascontiguousarray(out), {
        "severity": profile if operations else "unchanged",
        "operations": operations,
        "rejected": rejected,
    }


def sample_free_origin(
    rng: np.random.Generator,
    page_shape: tuple[int, int],
    glyph_shape: tuple[int, int],
    occupied: list[list[float]],
    *,
    chaos: float = 0.8,
) -> tuple[int, int, str]:
    """Diversify independent free placements, never waive collision checks.

    Strategies: uniform, page-edge, corner, near-existing-symbol, near-center,
    horizontally/vertically biased bands, and clustered near previous glyphs.
    Caller remains responsible for wall, crowding and bbox rejection.
    """
    h, w = page_shape
    gh, gw = glyph_shape
    xhi, yhi = w - gw - 5, h - gh - 5
    if min(xhi, yhi) < 5:
        raise ValueError("glyph does not fit drawable page")
    if not 0 <= chaos <= 1:
        raise ValueError("chaos must be in [0,1]")
    if rng.random() > chaos:
        return int(rng.integers(4, xhi + 1)), int(rng.integers(4, yhi + 1)), "uniform"

    mode = str(rng.choice(("uniform", "edge", "corner", "cluster", "central",
                           "horizontal_band", "vertical_band"),
                          p=(.22, .15, .12, .23, .10, .09, .09)))

    def clipped(x: float, y: float) -> tuple[int, int, str]:
        return int(np.clip(round(x), 4, xhi)), int(np.clip(round(y), 4, yhi)), mode

    if mode == "edge":
        if rng.random() < .5:
            return clipped(float(rng.choice((rng.uniform(4, min(xhi, 18)),
                                             rng.uniform(max(4, xhi - 16), xhi)))),
                           rng.uniform(4, yhi))
        return clipped(rng.uniform(4, xhi), float(rng.choice((
            rng.uniform(4, min(yhi, 18)), rng.uniform(max(4, yhi - 16), yhi)))))
    if mode == "corner":
        return clipped(float(rng.choice((4, xhi))) + rng.normal(0, 10),
                       float(rng.choice((4, yhi))) + rng.normal(0, 10))
    if mode == "cluster" and occupied:
        box = occupied[int(rng.integers(len(occupied)))]
        cx, cy = (box[0] + box[2]) / 2, (box[1] + box[3]) / 2
        distance = float(rng.uniform(max(gw, gh) * .9, max(gw, gh) * 3.0))
        theta = float(rng.uniform(0, math.tau))
        return clipped(cx + math.cos(theta) * distance - gw / 2,
                       cy + math.sin(theta) * distance - gh / 2)
    if mode == "central":
        return clipped(rng.normal((xhi + 4) / 2, w * .16),
                       rng.normal((yhi + 4) / 2, h * .16))
    if mode == "horizontal_band":
        return clipped(rng.uniform(4, xhi), rng.normal(yhi / 2, h * .12))
    if mode == "vertical_band":
        return clipped(rng.normal(xhi / 2, w * .12), rng.uniform(4, yhi))
    return clipped(rng.uniform(4, xhi), rng.uniform(4, yhi))
