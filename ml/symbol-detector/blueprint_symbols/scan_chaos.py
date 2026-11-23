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


def add_optical_tone_mix(
    image: np.ndarray,
    rng: np.random.Generator,
    *,
    strength: float = 1.0,
    probability: float = 1.0,
) -> tuple[np.ndarray, list[str]]:
    """Independent bloom/contrast variations that can occur together.

    Photometric only: dimensions and source coordinates are unchanged.
    Contrast can increase or decrease, flatten highlights, or vary over
    the page. Bloom simulates scattered bright paper light washing into
    adjacent dark drawing strokes, not a geometric blur of the entire page.
    This function is for the SSL *input* only, never the clean target.
    """
    if image.ndim != 3 or image.shape[2] != 3 or image.dtype != np.uint8:
        raise ValueError("expected uint8 BGR image")
    if (not math.isfinite(strength) or not 0 <= strength <= 2
            or not math.isfinite(probability) or not 0 <= probability <= 1):
        raise ValueError("invalid optical tone parameters")
    if strength == 0 or rng.random() >= probability:
        return image.copy(), []

    h, w = image.shape[:2]
    out = image.astype(np.float32).copy()
    operations: list[str] = []

    # A real print/camera may have a lifted black point or crushed shadows
    # even while its paper stays very nearly white. Pivot around the
    # measured paper level rather than blindly shifting all RGB channels.
    if rng.random() < .76:
        paper = float(np.percentile(cv2.cvtColor(image, cv2.COLOR_BGR2GRAY), 85))
        gain = float(np.exp(rng.uniform(-.42, .40) * strength))
        gain = float(np.clip(gain, .45, 1.95))
        out = paper + (out - paper) * gain
        operations.append("optical_contrast_gain")

    # Toe/shoulder curve: independently compress or expand middle tones.
    # Interpolation keeps extreme cases from destroying all symbol strokes.
    if rng.random() < .40:
        u = np.clip(out / 255.0, 0.0, 1.0)
        smooth = u * u * (3.0 - 2.0 * u)
        blend = float(rng.uniform(-.70, .85) * min(strength, 1.6))
        out = 255.0 * (u + (smooth - u) * blend)
        operations.append("optical_contrast_curve")

    # Slowly varying contrast, e.g. uneven scanner exposure or one side of
    # a large photographed plan fading more than the other. No spatial warp.
    if rng.random() < .36:
        angle = float(rng.uniform(0.0, math.tau))
        yy, xx = np.mgrid[:h, :w].astype(np.float32)
        gradient = ((xx / max(1, w - 1) - .5) * math.cos(angle)
                    + (yy / max(1, h - 1) - .5) * math.sin(angle))
        magnitude = float(rng.uniform(.09, .40) * strength)
        local_gain = np.clip(1.0 + magnitude * gradient, .65, 1.35)
        out = 240.0 + (out - 240.0) * local_gain[:, :, None]
        operations.append("optical_local_contrast")

    # Optical bloom: in a bright/lensed region, white paper scatters into
    # darker strokes. Locally blurred luminance controls the highlight gate
    # while a separate broad envelope makes this a *localized* exposure.
    # The original line geometry remains fixed; only its brightness changes.
    if rng.random() < .58:
        sigma = max(.6, float(rng.uniform(1.2, 6.0) * min(strength, 1.65)))
        base = np.clip(out, 0, 255).astype(np.uint8)
        glow = cv2.GaussianBlur(base, (0, 0), sigma)
        glow_luma = cv2.cvtColor(glow, cv2.COLOR_BGR2GRAY).astype(np.float32)
        highlight_gate = np.clip((glow_luma - 165.0) / 80.0, 0.0, 1.0)
        yy, xx = np.ogrid[:h, :w]
        cx = float(rng.uniform(-.1 * w, 1.1 * w))
        cy = float(rng.uniform(-.1 * h, 1.1 * h))
        radius = max(4.0, float(rng.uniform(.18, .65) * max(w, h)))
        envelope = np.exp(
            -((xx - cx)**2 + (yy - cy)**2) / (2.0 * radius * radius)
        ).astype(np.float32)
        amount = float(rng.uniform(.09, .45) * min(strength, 1.65))
        factor = (highlight_gate * envelope * amount)[:, :, None]
        out += (255.0 - out) * factor
        operations.append("optical_highlight_bloom")

    # Rare localized shadow-ink halation provides the opposite optical
    # behavior: dark lines softly bleed into adjacent light paper.
    if rng.random() < .22:
        gray = cv2.cvtColor(np.clip(out, 0, 255).astype(np.uint8),
                            cv2.COLOR_BGR2GRAY).astype(np.float32)
        ink = 255.0 - gray
        halo = cv2.GaussianBlur(ink, (0, 0),
                                float(rng.uniform(.8, 2.8) * strength))
        halo = np.maximum(0.0, halo - ink * .55)
        opacity = float(rng.uniform(.08, .30) * strength)
        out -= halo[:, :, None] * opacity
        operations.append("optical_ink_halation")

    if not operations:
        # When selected, avoid a no-op in the non-clean SSL path.
        out = 242.0 + (out - 242.0) * float(rng.uniform(.83, 1.18))
        operations.append("optical_contrast_gain")

    return np.clip(out, 0, 255).astype(np.uint8), operations


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

    # Independent optical response may combine bloom, localized/global
    # contrast, a nonlinear tone curve and dark-line halation. Applying
    # these after the existing photocopy gamma allows realistic mixtures.
    if chance(.53):
        out, optical_ops = add_optical_tone_mix(
            out, rng, strength=strength, probability=1.0,
        )
        changes.extend(optical_ops)

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
