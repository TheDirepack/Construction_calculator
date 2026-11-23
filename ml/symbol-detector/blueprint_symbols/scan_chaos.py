"""Coordinate-preserving scan/photo/print damage for blueprint SSL inputs.

All operations preserve image size; original construction-plan geometry and
the clean reconstruction target are untouched. Each category is sampled
independently with continuous strengths; distributions target construction
prints, scanner artifacts and photographed paper, not invented drafting styles.
"""
from __future__ import annotations

import math

import cv2
import numpy as np


def add_scan_chaos(
    image: np.ndarray,
    rng: np.random.Generator,
    *,
    strength: float = 1.0,
) -> tuple[np.ndarray, list[str]]:
    """Extra independent photocopy/camera/plotter damage categories."""
    if image.ndim != 3 or image.shape[2] != 3 or image.dtype != np.uint8:
        raise ValueError("expected uint8 BGR image")
    if not math.isfinite(strength) or not 0 <= strength <= 2:
        raise ValueError("invalid strength")
    if strength == 0:
        return image.copy(), []
    out = image.copy()
    h, w = out.shape[:2]
    changes: list[str] = []

    def chance(base: float) -> bool:
        return bool(rng.random() < min(1., base * strength))

    # Real photocopier tonality/gamma drift rather than only gain/offset.
    if chance(.34):
        power = float(np.exp(rng.uniform(-.42, .42) * strength))
        lut = np.clip(((np.arange(256) / 255.) ** power) * 255., 0, 255).astype(np.uint8)
        out = cv2.LUT(out, lut)
        changes.append("gamma_curve")

    # Large-scale uneven shadow, curled paper, flashlight glare or lens hotspot.
    if chance(.29):
        gy, gx = np.ogrid[:h, :w]
        cx = float(rng.uniform(-.15*w, 1.15*w))
        cy = float(rng.uniform(-.15*h, 1.15*h))
        radius = max(8., float(rng.uniform(.12, .58) * max(h, w)))
        envelope = np.exp(-((gx-cx)**2 + (gy-cy)**2) / (2 * radius**2))
        delta = float(rng.uniform(12, 75) * strength) * (
            1 if rng.random() < .58 else -1)
        out = np.clip(out.astype(np.float32) + (envelope * delta)[:, :, None],
                      0, 255).astype(np.uint8)
        changes.append("local_shadow_glare")

    # A plot folded under the scanner: narrow bright/dark bent crease with halo.
    if chance(.24):
        xstart = float(rng.uniform(0, w))
        ystart = float(rng.uniform(0, h))
        theta = float(rng.uniform(0, math.tau))
        line = np.zeros((h, w), dtype=np.float32)
        length = math.hypot(h, w)
        xend = xstart + math.cos(theta) * length
        yend = ystart + math.sin(theta) * length
        cv2.line(line, (int(xstart), int(ystart)), (int(xend), int(yend)),
                 1., int(rng.integers(1, 5)))
        soft = cv2.GaussianBlur(line, (0, 0), float(rng.uniform(2., 10.)))
        intensity = float(rng.uniform(15, 100) * strength)
        sign = 1 if rng.random() < .5 else -1
        out = np.clip(out.astype(np.float32) +
                      (soft * intensity + line * intensity * .22)[:, :, None] * sign,
                      0, 255).astype(np.uint8)
        changes.append("fold_crease")

    # Mechanical roller feed or uneven ink density in per-row/per-column bands.
    if chance(.27):
        axis = int(rng.integers(2))
        n = (w if axis == 0 else h)
        noise = rng.normal(0, float(rng.uniform(2, 15) * strength), n).astype(np.float32)
        noise = cv2.GaussianBlur(noise.reshape(-1, 1), (1, 0),
                                  sigmaX=0, sigmaY=float(rng.uniform(1, 9))).reshape(-1)
        band = noise[None, :, None] if axis == 0 else noise[:, None, None]
        out = np.clip(out.astype(np.float32) + band, 0, 255).astype(np.uint8)
        changes.append("roller_banding")

    # Screen / scanner interference, halftone oscillations, or alias ripples.
    if chance(.22):
        yy, xx = np.mgrid[:h, :w]
        period = float(rng.uniform(4, 24))
        angle = float(rng.uniform(0, math.tau))
        phase = float(rng.uniform(0, math.tau))
        wave = np.sin((xx * math.cos(angle) + yy * math.sin(angle))
                      / period * math.tau + phase).astype(np.float32)
        amplitude = float(rng.uniform(2, 14) * strength)
        out = np.clip(out.astype(np.float32) + wave[:, :, None] * amplitude,
                      0, 255).astype(np.uint8)
        changes.append("moire")

    # Dotty halftone / copier screening, not uniform Gaussian noise.
    if chance(.22):
        k = int(rng.integers(4, 12))
        yy, xx = np.mgrid[:h, :w]
        dot = ((xx % k - k / 2)**2 + (yy % k - k / 2)**2 < (k * .17)**2)
        offset = float(rng.uniform(5, 24) * strength)
        out = np.clip(out.astype(np.float32) - dot[:, :, None].astype(np.float32) * offset,
                      0, 255).astype(np.uint8)
        changes.append("halftone_dots")

    # Duplicate slightly displaced ink from double-feed, scanner or photocopy.
    if chance(.24):
        dx, dy = [int(v) for v in rng.integers(-4, 5, size=2)]
        if dx == 0 and dy == 0:
            dx = 2
        displaced = cv2.warpAffine(out, np.float32([[1, 0, dx], [0, 1, dy]]),
                                   (w, h), borderMode=cv2.BORDER_REPLICATE)
        opacity = float(rng.uniform(.10, .38) * strength)
        # Darker ghost, never overwrite with white shift.
        out = np.clip(out.astype(np.float32) * (1 - opacity) +
                      np.minimum(out, displaced).astype(np.float32) * opacity,
                      0, 255).astype(np.uint8)
        changes.append("copier_double_image")

    # Dry toner/wet ink spreads very locally, including print edge halos.
    if chance(.22):
        k = int(rng.choice((3, 5)))
        blur = cv2.GaussianBlur(out, (k, k), float(rng.uniform(.6, 1.6)))
        out = cv2.addWeighted(out, float(rng.uniform(.55, .87)),
                              cv2.erode(blur, np.ones((3, 3), np.uint8)),
                              float(rng.uniform(.13, .45)), 0)
        changes.append("toner_bleed")

    # Missing narrow feed band / scratched glossy page.
    if chance(.17):
        vertical = bool(rng.integers(2))
        width = int(max(1, round(rng.uniform(1., 5.) * strength)))
        tint = int(rng.uniform(175, 255))
        pos = int(rng.integers(w if vertical else h))
        if vertical:
            out[:, max(0, pos-width):min(w, pos+width)] = tint
        else:
            out[max(0, pos-width):min(h, pos+width), :] = tint
        changes.append("blank_feed_band")

    # Random opaque weak patches / localized smudge without changing coords.
    if chance(.20):
        size = int(np.clip(rng.uniform(.02, .13) * min(w, h) * strength, 3, min(w, h)))
        x0 = int(rng.integers(0, max(1, w-size+1)))
        y0 = int(rng.integers(0, max(1, h-size+1)))
        roi = out[y0:y0+size, x0:x0+size]
        if roi.size:
            smooth = cv2.GaussianBlur(roi, (0, 0),
                                      max(.5, float(rng.uniform(1, 5) * strength)))
            blend = float(rng.uniform(.25, .75))
            out[y0:y0+size, x0:x0+size] = cv2.addWeighted(
                roi, 1-blend, smooth, blend, 0)
            changes.append("local_smear")

    # Mixed-axis raster aliasing: independently downsample x and y.
    if chance(.24):
        fx, fy = rng.uniform(max(.22, .70 - .20 * strength), .95, size=2)
        small = cv2.resize(out, (max(12, round(w*float(fx))),
                                  max(12, round(h*float(fy)))),
                           interpolation=cv2.INTER_AREA)
        out = cv2.resize(small, (w, h), interpolation=int(rng.choice(
            [cv2.INTER_NEAREST, cv2.INTER_LINEAR, cv2.INTER_CUBIC])))
        changes.append("anisotropic_rasterization")

    return out, changes
