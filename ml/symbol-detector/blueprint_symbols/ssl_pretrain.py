"""Blueprint-only self-supervised pretraining for YOLO26n-P2 backbone + P2-P5 neck.

Random-initialized YOLO26n-P2 (zero COCO / general-purpose weights) pretrained on
blueprint/CAD/floor-plan imagery via joint masked-image-modeling (MIM) and
runtime denoising/restoration.

All randomization, geometric variability, resolution filtering, common filtering
(Gaussian/motion blur, median/box/sharpen, stroke morphology, illumination, noise),
and JPEG compression noise are applied on-the-fly at runtime inside the DataLoader.
"""
from __future__ import annotations

import copy
import hashlib
import json
import math
from collections import OrderedDict
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import cv2
import numpy as np
import torch
from torch import nn
import torch.nn.functional as F
from torch.utils.data import Dataset


def load_bgr_white_bg(path: str | Path) -> np.ndarray | None:
    """Decode an image as uint8 BGR on white paper.

    - Transparent-background CAD rasters (e.g. FloorPlanCAD RGBA with colored/light
      CAD layer strokes) are converted to crisp black lines on pure white paper.
    - Dark-background CAD rasters (light/colored lines on black) are inverted to
      black lines on white paper.
    """
    raw = cv2.imread(str(path), cv2.IMREAD_UNCHANGED)
    if raw is None:
        return None
    if raw.ndim == 2:
        bgr = cv2.cvtColor(raw, cv2.COLOR_GRAY2BGR)
    elif raw.shape[2] == 4:
        alpha = raw[:, :, 3]
        if float((alpha < 12).mean()) > 0.50:
            # Transparent CAD line-art (FloorPlanCAD etc.): convert any drawn stroke to black on white.
            bw = 255 - alpha
            return cv2.cvtColor(bw, cv2.COLOR_GRAY2BGR)
        bgr_f = raw[:, :, :3].astype(np.float32)
        a_f = alpha[:, :, None].astype(np.float32) / 255.0
        bgr = np.clip(bgr_f * a_f + 255.0 * (1.0 - a_f), 0.0, 255.0).round().astype(np.uint8)
    else:
        bgr = raw[:, :, :3]

    # Dark-background CAD sheet (e.g. AutoCAD modelspace black background with colored lines)
    max_ch = bgr.max(axis=2)
    if float((max_ch < 40).mean()) > 0.65:
        bw = 255 - max_ch
        return cv2.cvtColor(bw, cv2.COLOR_GRAY2BGR)
    return bgr


def sample_random_crop(image: np.ndarray, size: int, rng: np.random.Generator) -> np.ndarray:
    """Sample a random multi-scale 512x512 crop, preferring non-empty drawing regions."""
    h, w = image.shape[:2]
    max_dim = max(h, w)
    # Continuous scale variability across physical drawing resolutions.
    if max_dim <= 1200:
        lo, hi = 0.45, min(2.0, max(0.8, max_dim / size))
    elif max_dim <= 2600:
        lo, hi = 0.55, min(2.8, max(1.0, max_dim / size))
    else:
        lo, hi = 0.65, min(3.5, max(1.2, max_dim / size))

    best_crop = None
    best_ink = -1.0
    allow_sparse = bool(rng.random() < 0.05)
    for _ in range(6):
        scale = float(np.exp(rng.uniform(math.log(lo), math.log(hi))))
        span = int(round(size * scale))
        span = max(64, min(span, max(h, w)))
        if w > span:
            x0 = int(rng.integers(0, w - span + 1))
            x1 = x0 + span
        else:
            x0, x1 = 0, w
        if h > span:
            y0 = int(rng.integers(0, h - span + 1))
            y1 = y0 + span
        else:
            y0, y1 = 0, h

        patch = image[y0:y1, x0:x1]
        ph, pw = patch.shape[:2]
        if ph != span or pw != span:
            # Pad to square with median border color (paper tone)
            border_val = tuple(int(v) for v in np.median(patch.reshape(-1, 3), axis=0))
            pad_b = max(0, span - ph)
            pad_r = max(0, span - pw)
            patch = cv2.copyMakeBorder(patch, 0, pad_b, 0, pad_r, cv2.BORDER_CONSTANT, value=border_val)

        interp = cv2.INTER_AREA if span > size else cv2.INTER_LINEAR
        crop = cv2.resize(patch, (size, size), interpolation=interp)

        gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
        ink_frac = float((gray < 190).mean())
        if allow_sparse or ink_frac >= 0.015:
            return crop
        if ink_frac > best_ink:
            best_ink = ink_frac
            best_crop = crop

    return best_crop if best_crop is not None else cv2.resize(image, (size, size), interpolation=cv2.INTER_AREA)


def sample_label_guided_crop(
    image: np.ndarray,
    size: int,
    rng: np.random.Generator,
    boxes: list[list[float]] | None,
    *,
    focus_probability: float = 0.65,
) -> np.ndarray:
    """Prefer verified device neighborhoods while retaining random plan crops.

    Boxes are optional positive-only annotations; no completeness assumption
    is made. Scale selection targets roughly 12..96 output pixels for the
    chosen glyph so small devices are not erased by wholesale page resize.
    """
    valid = []
    h, w = image.shape[:2]
    if boxes and rng.random() < focus_probability:
        for item in boxes:
            if len(item) != 4:
                continue
            x0, y0, x1, y1 = map(float, item)
            if all(math.isfinite(v) for v in (x0, y0, x1, y1)) and (
                0 <= x0 < x1 <= w and 0 <= y0 < y1 <= h
            ):
                valid.append((x0, y0, x1, y1))
    if not valid:
        return sample_random_crop(image, size, rng)

    x0, y0, x1, y1 = valid[int(rng.integers(len(valid)))]
    device_px = max(x1 - x0, y1 - y0)
    target_px = float(np.exp(rng.uniform(math.log(12), math.log(96))))
    min_span = min(max(h, w), max(64, math.ceil(device_px * 1.5)))
    span = int(np.clip(round(size * device_px / target_px), min_span, max(h, w)))
    # Keep the selected box in the crop, even near source-image borders.
    left = max(0, math.ceil(x1 - span))
    right = min(max(0, w - span), math.floor(x0))
    top = max(0, math.ceil(y1 - span))
    bottom = min(max(0, h - span), math.floor(y0))
    cx0 = int(rng.integers(left, right + 1)) if right >= left else max(0, min(w - span, round(x0)))
    cy0 = int(rng.integers(top, bottom + 1)) if bottom >= top else max(0, min(h - span, round(y0)))
    patch = image[cy0:min(h, cy0 + span), cx0:min(w, cx0 + span)]
    ph, pw = patch.shape[:2]
    if ph != span or pw != span:
        paper = tuple(int(v) for v in np.median(patch.reshape(-1, 3), axis=0))
        patch = cv2.copyMakeBorder(patch, 0, span - ph, 0, span - pw,
                                   cv2.BORDER_CONSTANT, value=paper)
    return cv2.resize(patch, (size, size),
                      interpolation=cv2.INTER_AREA if span > size else cv2.INTER_LINEAR)

def apply_geometric_variability(clean: np.ndarray, rng: np.random.Generator) -> np.ndarray:
    """Apply geometric transforms identically to the clean crop (target & input stay aligned)."""
    out = clean
    if rng.random() < 0.5:
        out = cv2.flip(out, 1)
    if rng.random() < 0.5:
        out = cv2.flip(out, 0)
    k = int(rng.integers(0, 4))
    if k:
        out = np.ascontiguousarray(np.rot90(out, k))
    # Subtle rotation variability (skewed scans / handheld photos)
    if rng.random() < 0.25:
        h, w = out.shape[:2]
        angle = float(rng.uniform(-6.0, 6.0))
        border_val = tuple(int(v) for v in np.median(out.reshape(-1, 3), axis=0))
        mat = cv2.getRotationMatrix2D((w * 0.5, h * 0.5), angle, 1.0)
        out = cv2.warpAffine(out, mat, (w, h), flags=cv2.INTER_LINEAR,
                             borderMode=cv2.BORDER_CONSTANT, borderValue=border_val)
    # Photo perspective / curled-sheet approximation. Transform BOTH SSL
    # branches identically here; never warp only the reconstruction input.
    if rng.random() < 0.22:
        h, w = out.shape[:2]
        src = np.float32([[0, 0], [w - 1, 0], [w - 1, h - 1], [0, h - 1]])
        move = min(h, w) * float(rng.uniform(0.006, 0.035))
        dst = src + rng.uniform(-move, move, src.shape).astype(np.float32)
        mat = cv2.getPerspectiveTransform(src, dst)
        paper = tuple(int(v) for v in np.median(out.reshape(-1, 3), axis=0))
        out = cv2.warpPerspective(out, mat, (w, h), flags=cv2.INTER_LINEAR,
                                  borderMode=cv2.BORDER_CONSTANT, borderValue=paper)
    return out


def apply_runtime_corruptions(
    clean_bgr: np.ndarray,
    rng: np.random.Generator,
    *,
    mask_prob: float = 0.85,
    clean_fraction: float = 0.20,
    return_metadata: bool = False,
    protected_boxes: list[list[float]] | None = None,
) -> tuple:
    """Random, independent scan/camera/PDF corruptions at varying severity.

    A true, untouched clean-view fraction is intentionally retained. Other
    views independently sample photometric, optical, raster, printing,
    scanner, noise and codec transformations. All are coordinate preserving;
    geometric transforms of clean and input happen earlier, together.
    """
    if not 0 <= clean_fraction <= 1 or not 0 <= mask_prob <= 1:
        raise ValueError("probabilities must be in [0,1]")
    x = clean_bgr.copy()
    h, w = x.shape[:2]
    mask = np.zeros((h, w), np.float32)
    operations: list[str] = []

    # Requested fully clean views are genuinely unmasked; mask_prob=1 is an
    # explicit testing/training override that still guarantees MIM masking.
    if rng.random() < clean_fraction and mask_prob < 1:
        meta = {"severity": "clean", "operations": []}
        return (x, mask, meta) if return_metadata else (x, mask)

    severity = str(rng.choice(("light", "moderate", "heavy", "extreme"),
                              p=(0.34, 0.37, 0.23, 0.06)))
    strength = {"light": 0.45, "moderate": 0.80,
                "heavy": 1.15, "extreme": 1.55}[severity]

    def chance(p: float) -> bool:
        return bool(rng.random() < min(1.0, strength * p))

    # Illumination, white balance, paper color, locally uneven exposure.
    if chance(0.68):
        operations.append("illumination")
        gain = float(rng.uniform(1 - 0.22 * strength, 1 + 0.22 * strength))
        offset = float(rng.uniform(-18 * strength, 18 * strength))
        xf = x.astype(np.float32) * gain + offset
        if chance(0.46):
            operations.append("uneven_lighting")
            gx = np.linspace(-1, 1, w, dtype=np.float32)[None, :]
            gy = np.linspace(-1, 1, h, dtype=np.float32)[:, None]
            theta = float(rng.uniform(-np.pi, np.pi))
            field = (gx * np.cos(theta) + gy * np.sin(theta)) * float(rng.uniform(5, 30)) * strength
            xf += field[:, :, None]
        if chance(0.28):
            operations.append("white_balance")
            xf *= rng.uniform(1 - 0.08 * strength, 1 + 0.08 * strength, (1, 1, 3))
        x = np.clip(xf, 0, 255).astype(np.uint8)

    # Optical blur, including movement along arbitrary scan directions.
    if chance(0.52):
        operations.append("gaussian_blur")
        sigma = float(rng.uniform(0.25, 1.4 * strength + 0.25))
        x = cv2.GaussianBlur(x, (0, 0), sigma)
    if chance(0.23):
        operations.append("motion_blur")
        k = int(rng.choice((3, 5, 7, 9)))
        ker = np.zeros((k, k), np.float32)
        if rng.random() < 0.5:
            ker[k // 2, :] = 1.0 / k
        else:
            ker[:, k // 2] = 1.0 / k
        x = cv2.filter2D(x, -1, ker)

    # Camera/software filtering, haloing, scanner despeckle, binary threshold.
    if chance(0.30):
        op = int(rng.integers(0, 5))
        operations.append(("median_filter", "box_filter", "sharpen",
                           "local_contrast", "thresholding")[op])
        if op == 0:
            x = cv2.medianBlur(x, 3)
        elif op == 1:
            x = cv2.blur(x, (3, 3))
        elif op == 2:
            blur = cv2.GaussianBlur(x, (0, 0), 1.0)
            x = cv2.addWeighted(x, 1.0 + 0.75 * strength, blur, -0.75 * strength, 0)
        elif op == 3:
            gray = cv2.cvtColor(x, cv2.COLOR_BGR2GRAY)
            clahe = cv2.createCLAHE(clipLimit=1.0 + strength, tileGridSize=(8, 8))
            x = cv2.cvtColor(clahe.apply(gray), cv2.COLOR_GRAY2BGR)
        else:
            gray = cv2.cvtColor(x, cv2.COLOR_BGR2GRAY)
            threshold = int(rng.uniform(145, 215))
            x = cv2.cvtColor(np.where(gray < threshold, 0, 255).astype(np.uint8),
                             cv2.COLOR_GRAY2BGR)

    # Simulate original low-DPI scans and interpolation artifacts.
    if chance(0.67):
        operations.append("resampling")
        bands = {
            "light": (0.72, 0.96), "moderate": (0.48, 0.86),
            "heavy": (0.28, 0.73), "extreme": (0.16, 0.55),
        }
        low, high = bands[severity]
        frac = float(rng.uniform(low, high))
        reduced = cv2.resize(x, (max(16, round(w * frac)), max(16, round(h * frac))),
                             interpolation=cv2.INTER_AREA)
        interp = int(rng.choice((cv2.INTER_NEAREST, cv2.INTER_LINEAR, cv2.INTER_CUBIC)))
        x = cv2.resize(reduced, (w, h), interpolation=interp)

    # Toner bleed, faded lines and broken print strokes.
    if chance(0.30):
        operations.append("toner_morphology")
        kernel = cv2.getStructuringElement(cv2.MORPH_CROSS, (3, 3))
        x = (cv2.erode(x, kernel, iterations=1) if rng.random() < 0.5
             else cv2.dilate(x, kernel, iterations=1))

    # Missing/broken ink spots, paper speckles, dust and sensor impulse pixels.
    if chance(0.30):
        operations.append("salt_pepper")
        n = int(h * w * rng.uniform(0.00003, 0.0012) * strength)
        if n:
            ys = rng.integers(0, h, n)
            xs = rng.integers(0, w, n)
            x[ys, xs] = 255 if rng.random() < 0.55 else 0
    if chance(0.20):
        operations.append("dust_dropouts")
        n = int(rng.integers(1, 7))
        for _ in range(n):
            px = int(rng.integers(w))
            py = int(rng.integers(h))
            radius = max(1, int(rng.uniform(1, 4) * strength))
            cv2.circle(x, (px, py), radius, (255, 255, 255)
                       if rng.random() < .6 else (80, 80, 80), -1)

    # Feed/scanner streaks and banding (both axes).
    if chance(0.20):
        operations.append("scanner_streaks")
        axis = int(rng.integers(2))
        n = int(rng.integers(1, 4))
        for _ in range(n):
            pos = int(rng.integers(w if axis == 0 else h))
            thickness = max(1, int(rng.uniform(1, 3) * strength))
            tint = int(rng.uniform(190, 255)) if rng.random() < .65 else int(rng.uniform(20, 85))
            if axis == 0:
                cv2.line(x, (pos, 0), (pos, h - 1), (tint,) * 3, thickness)
            else:
                cv2.line(x, (0, pos), (w - 1, pos), (tint,) * 3, thickness)

    # Grain, sensor and halftone noise.
    if chance(0.55):
        operations.append("sensor_noise")
        sigma = float(rng.uniform(0.6, 9.0) * strength)
        x = np.clip(x.astype(np.float32) + rng.normal(0, sigma, x.shape), 0, 255).astype(np.uint8)
    if chance(0.12):
        operations.append("bit_depth")
        # Quantization/banding in low-bit-depth scans.
        levels = int(rng.choice((4, 8, 16, 32)))
        x = np.round(x.astype(np.float32) / 255 * (levels - 1)) * (255 / (levels - 1))
        x = np.clip(x, 0, 255).astype(np.uint8)

    # JPEG or WebP codec ringing/block artifacts.
    if chance(0.70):
        codec = ".jpg" if rng.random() < .80 else ".webp"
        qmin = {"light": 75, "moderate": 48, "heavy": 27, "extreme": 12}[severity]
        quality = int(rng.integers(qmin, 96))
        key = cv2.IMWRITE_JPEG_QUALITY if codec == ".jpg" else cv2.IMWRITE_WEBP_QUALITY
        try:
            ok, buffer = cv2.imencode(codec, x, [key, quality])
        except cv2.error:
            # Some headless OpenCV builds omit optional WebP codecs.
            codec = ".jpg"
            ok, buffer = cv2.imencode(".jpg", x, [cv2.IMWRITE_JPEG_QUALITY, quality])
        if ok:
            decoded = cv2.imdecode(buffer, cv2.IMREAD_COLOR)
            if decoded is not None:
                x = decoded
                operations.append("jpeg" if codec == ".jpg" else "webp")

    # Additional independent paper, plotter, copier and scan degradation:
    # gamma curve, spotlight shadow/glare, fold crease, roller banding, moire,
    # halftone, double-feed ghosting, toner bleed, blank feed band, localized
    # smudge and asymmetric rasterization. Keep size/coordinates unchanged.
    from .scan_chaos import add_scan_chaos
    x, extra_operations = add_scan_chaos(x, rng, strength=strength)
    operations.extend(extra_operations)

    # Weak legibility floor: preserve a minimum fraction of original line
    # contrast before masked-image modeling. This is NOT an OCR/semantic
    # recognizability guarantee, but avoids completely bleached positives.
    orig_gray = cv2.cvtColor(clean_bgr, cv2.COLOR_BGR2GRAY)
    original_ink = orig_gray < 185
    if int(original_ink.sum()) >= 32:
        orig_contrast = float((255 - orig_gray[original_ink].astype(np.float32)).mean())
        current_gray = cv2.cvtColor(x, cv2.COLOR_BGR2GRAY)
        current_contrast = float((255 - current_gray[original_ink].astype(np.float32)).mean())
        if orig_contrast > 20 and current_contrast < orig_contrast * .22:
            x = cv2.addWeighted(x, .62, clean_bgr, .38, 0)
            operations.append("line_contrast_rescue")

    # Random masked reconstruction. The clean target includes the same
    # synthetic symbol additions but no corruption.
    if rng.random() < mask_prob:
        operations.append("masked_blocks")
        patch = int(rng.choice((8, 16, 32, 64)))
        gh, gw = math.ceil(h / patch), math.ceil(w / patch)
        ratio = float(rng.uniform(.15, .55))
        grid = (rng.random((gh, gw)) < ratio).astype(np.float32)
        mask = np.repeat(np.repeat(grid, patch, axis=0), patch, axis=1)[:h, :w].copy()
        # MIM may hide *part* of a device, but erasing every stroke in a
        # known synthetic symbol makes its class unrecoverable from the input.
        # Cap masked area inside each warped symbol box at 60%. This does not
        # protect unannotated devices and cannot guarantee readability after
        # the independent optical/noise degradations.
        protected_reduced = 0
        for raw in protected_boxes or []:
            if len(raw) != 4 or not all(math.isfinite(v) for v in raw):
                raise ValueError("invalid protected symbol bbox")
            x0, y0, x1, y1 = (
                max(0, int(math.floor(raw[0]))),
                max(0, int(math.floor(raw[1]))),
                min(w, int(math.ceil(raw[2]))),
                min(h, int(math.ceil(raw[3]))),
            )
            if x1 <= x0 or y1 <= y0:
                continue
            region = mask[y0:y1, x0:x1]
            if float(region.mean()) > .60:
                if rng.random() < .5:
                    reveal = max(1, int(math.ceil((x1-x0) * .5)))
                    region[:, :reveal] = 0
                else:
                    reveal = max(1, int(math.ceil((y1-y0) * .5)))
                    region[:reveal, :] = 0
                # Existing unmasked pixels might be concentrated on the
                # revealed side; never restore *more* masking.
                if float(region.mean()) > .60:
                    region[:, :max(1, int(math.ceil((x1-x0) * .5)))] = 0
                protected_reduced += 1
        if protected_reduced:
            operations.append("masked_symbol_visibility_guard")
        paper = np.median(x.reshape(-1, 3), axis=0).astype(np.float32)
        fill = np.clip(paper[None, None, :] + rng.normal(0, 4, x.shape), 0, 255).astype(np.uint8)
        x = np.where(mask[:, :, None] > .5, fill, x)

    # Independent Bernoulli choices can all miss on a light sample. Keep
    # the explicit clean_fraction as the only intentionally unchanged path.
    if not operations:
        sigma = max(0.8, 1.8 * strength)
        x = np.clip(x.astype(np.float32) + rng.normal(0, sigma, x.shape),
                    0, 255).astype(np.uint8)
        operations.append("sensor_noise")

    meta = {"severity": severity, "operations": operations}
    return (x, mask, meta) if return_metadata else (x, mask)


class BlueprintSSLDataset(Dataset):
    """Source-balanced, group-balanced, runtime-augmented blueprint SSL dataset.

    Expects a manifest dict with:
      buckets: {bucket_name: {weight: float, groups: {group_id: [image_path, ...]}}}
    """

    def __init__(
        self,
        manifest: dict[str, Any],
        *,
        size: int = 512,
        items_per_epoch: int = 8192,
        seed: int = 2026,
        deterministic_eval: bool = False,
        crop_reuse_views: int = 4,
        crop_bank_size: int = 32,
        fresh_crop_probability: float = 0.20,
        neca_dir: str | Path | None = None,
        max_symbols: int = 16,
        symbol_min_px: float = 12.0,
        symbol_max_px: float = 80.0,
        symbol_augment_probability: float = 1.00,
        procedural_fraction: float = 0.25,
        clean_fraction: float = 0.20,
        labeled_focus_probability: float = 0.65,
        symbol_chaos_probability: float = 0.88,
        symbol_chaos_strength: float = 1.0,
        placement_chaos: float = 0.85,
        page_chaos_probability: float = 0.70,
        page_chaos_strength: float = 1.0,
        return_metadata: bool = False,
        include_pre_page_preview: bool = False,
    ) -> None:
        super().__init__()
        self.size = int(size)
        self.items_per_epoch = int(items_per_epoch)
        self.seed = int(seed)
        self.epoch = 0
        self.deterministic_eval = bool(deterministic_eval)
        if not 1 <= crop_reuse_views <= 32 or not 1 <= crop_bank_size <= 128:
            raise ValueError("invalid crop reuse views or crop bank size")
        if not 0 <= fresh_crop_probability <= 1:
            raise ValueError("fresh_crop_probability must be in [0,1]")
        self.crop_reuse_views = int(crop_reuse_views)
        self.crop_bank_size = int(crop_bank_size)
        self.fresh_crop_probability = float(fresh_crop_probability)
        if not 0 <= symbol_augment_probability <= 1 or not 0 <= clean_fraction <= 1 or not 0 <= labeled_focus_probability <= 1:
            raise ValueError("invalid SSL sampling probabilities")
        self.max_symbols = int(max_symbols)
        self.symbol_min_px = float(symbol_min_px)
        self.symbol_max_px = float(symbol_max_px)
        self.symbol_augment_probability = float(symbol_augment_probability)
        self.procedural_fraction = float(procedural_fraction)
        self.clean_fraction = float(clean_fraction)
        self.labeled_focus_probability = float(labeled_focus_probability)
        if not 0 <= symbol_chaos_probability <= 1 or not 0 <= symbol_chaos_strength <= 2 or not 0 <= placement_chaos <= 1:
            raise ValueError("Invalid per-symbol chaos controls")
        self.symbol_chaos_probability = float(symbol_chaos_probability)
        self.symbol_chaos_strength = float(symbol_chaos_strength)
        self.placement_chaos = float(placement_chaos)
        if not 0 <= page_chaos_probability <= 1 or not 0 <= page_chaos_strength <= 2:
            raise ValueError("Invalid whole-page chaos controls")
        self.page_chaos_probability = float(page_chaos_probability)
        self.page_chaos_strength = float(page_chaos_strength)
        self.return_metadata = bool(return_metadata)
        self.include_pre_page_preview = bool(include_pre_page_preview)
        if self.include_pre_page_preview and not self.return_metadata:
            raise ValueError("include_pre_page_preview requires return_metadata")
        self.neca_bank = None
        if neca_dir is not None:
            from .neca_svg_compositor import NecaSymbolBank
            self.neca_bank = NecaSymbolBank(Path(neca_dir), include_modifiers=False)

        self.bucket_names: list[str] = []
        self.bucket_weights: list[float] = []
        self.bucket_groups: dict[str, list[list[str | dict]]] = {}
        self.bucket_group_probs: dict[str, np.ndarray] = {}

        for name, info in sorted(manifest['buckets'].items()):
            groups = [paths for paths in info['groups'].values() if paths]
            if not groups:
                continue
            self.bucket_names.append(name)
            self.bucket_weights.append(float(info.get('weight', 1.0)))
            self.bucket_groups[name] = groups
            # Mix per-project uniform coverage with square-root page-count
            # weighting. Hundreds-page construction PDFs get sampled more,
            # without monopolizing the epoch at the expense of short jobs.
            sizes = np.sqrt(np.array([len(g) for g in groups], np.float64))
            self.bucket_group_probs[name] = (
                .45 / len(groups) + .55 * sizes / sizes.sum()
            )

        if not self.bucket_names:
            raise ValueError('SSL manifest has no non-empty source buckets')

        total_w = sum(self.bucket_weights)
        self.bucket_probs = np.array([w / total_w for w in self.bucket_weights], dtype=np.float64)
        self._raster_cache: OrderedDict[str, np.ndarray] = OrderedDict()
        self._raster_cache_bytes = 0
        self._raster_cache_budget = 96 * (1 << 20)  # per worker, not per rank
        self._raster_crop_cache: OrderedDict[tuple, np.ndarray] = OrderedDict()
        self._raster_crop_bytes = 0
        self._raster_crop_budget = 24 * (1 << 20)  # worker-local, bounded
        self._pdf_sampler = None
        self._source_burst = self.crop_reuse_views
        self._source_plan: np.ndarray | None = None
        self.set_epoch(0)

    def _build_source_plan(self, epoch: int) -> np.ndarray:
        """Arrange consecutive *views* of a shared base crop.

        Each burst uses one source page and crop-bank slot. PDF views keep
        a common physical center but independently choose real 100–400 DPI
        rasterization scales. Symbol, rotation, warp, and degradation RNGs
        remain independent; raster scans use exact replayed source pixels.
        Small stable per-page banks get revisited across epochs; an optional
        fresh-site fraction prevents limited-bank spatial overfitting.
        """
        rng = np.random.default_rng(
            np.random.SeedSequence([self.seed, epoch, 0x5A2CE])
        )
        count = self.items_per_epoch
        # columns: bucket, project group, page/image, crop slot
        plan = np.zeros((count, 4), dtype=np.int32)
        cursors: dict[tuple[int, int], int] = {}
        permutations: dict[tuple[int, int], np.ndarray] = {}
        page_visits: dict[tuple[int, int, int], int] = {}
        for offset in range(0, count, self._source_burst):
            b = int(rng.choice(len(self.bucket_names), p=self.bucket_probs))
            name = self.bucket_names[b]
            g = int(rng.choice(len(self.bucket_groups[name]),
                               p=self.bucket_group_probs[name]))
            key = (b, g)
            if key not in permutations:
                entries = len(self.bucket_groups[name][g])
                permutations[key] = rng.permutation(entries)
                cursors[key] = int(rng.integers(entries))
            order = permutations[key]
            idx = int(order[cursors[key] % len(order)])
            cursors[key] += 1
            # Each visited page cycles across the full spatial slot bank
            # without replacement *within this epoch*. Epoch offset changes
            # the starting slot; long pages do not keep revisiting a handful
            # of randomly chosen regions while others are never sampled.
            visit_key = (b, g, idx)
            visit = page_visits.get(visit_key, 0)
            page_visits[visit_key] = visit + 1
            if rng.random() < self.fresh_crop_probability:
                # Fresh view is a new location outside the stable bank.
                slot = -1 - offset
            else:
                page_id = f"{self.seed}/{b}/{g}/{idx}"
                start = int.from_bytes(
                    hashlib.sha256(page_id.encode()).digest()[:4], "big"
                ) % self.crop_bank_size
                slot = (start + epoch + visit) % self.crop_bank_size
            plan[offset:min(count, offset + self._source_burst)] = (b, g, idx, slot)
        return plan

    def _base_crop_seed(self, source: str, page: int, slot: int,
                        epoch: int) -> int:
        """Stable 64-bit source crop seed, independent of worker/index RNG."""
        scope = (f"{self.seed}/bank/{source}/{page}/{slot}/{self.size}"
                 if slot >= 0 else
                 f"{self.seed}/fresh/{epoch}/{source}/{page}/{slot}/{self.size}")
        return int.from_bytes(hashlib.sha256(scope.encode()).digest()[:8],
                              "big")

    def set_epoch(self, epoch: int) -> None:
        self.epoch = int(epoch)
        self._source_plan = self._build_source_plan(
            0 if self.deterministic_eval else self.epoch
        )

    def __len__(self) -> int:
        return self.items_per_epoch

    def _read_cached(self, path: str) -> np.ndarray | None:
        cached = self._raster_cache.get(path)
        if cached is not None:
            self._raster_cache.move_to_end(path)
            return cached
        img = load_bgr_white_bg(path)
        if img is None:
            return None
        if img.nbytes <= self._raster_cache_budget:
            while (self._raster_cache and
                   self._raster_cache_bytes + img.nbytes > self._raster_cache_budget):
                _, old = self._raster_cache.popitem(last=False)
                self._raster_cache_bytes -= old.nbytes
            self._raster_cache[path] = img
            self._raster_cache_bytes += img.nbytes
        return img

    def __getitem__(self, index: int) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
        ep = 0 if self.deterministic_eval else self.epoch
        rng = np.random.default_rng(np.random.SeedSequence([self.seed, ep, index]))

        clean = None
        pdf_crop_meta = None
        crop_cache_hit = False
        path = ""
        b_idx = 0
        crop_slot = -1 - index
        for attempt in range(8):
            if attempt == 0 and self._source_plan is not None:
                b_idx, group_idx, entry_idx, crop_slot = map(
                    int, self._source_plan[index]
                )
            else:
                crop_slot = -1 - index - attempt * self.items_per_epoch
                b_idx = int(rng.choice(len(self.bucket_names), p=self.bucket_probs))
                name = self.bucket_names[b_idx]
                groups = self.bucket_groups[name]
                group_idx = int(rng.choice(len(groups), p=self.bucket_group_probs[name]))
                entry_idx = int(rng.integers(len(groups[group_idx])))
            name = self.bucket_names[b_idx]
            groups = self.bucket_groups[name]
            group = groups[group_idx]
            entry = group[entry_idx]
            path = entry["image_path"] if isinstance(entry, dict) else entry
            crop_page = int(entry.get("pdf_page_index", -1)) if isinstance(entry, dict) else -1
            crop_seed = self._base_crop_seed(
                str(entry.get("source_pdf") or path) if isinstance(entry, dict)
                else str(path), crop_page, crop_slot, ep
            )
            crop_rng = np.random.default_rng(crop_seed)
            if isinstance(entry, dict) and "pdf_page_index" in entry:
                # Clip-render at the final needed scale; never load a 24MP
                # full-page raster merely to discard nearly all of it.
                if self._pdf_sampler is None:
                    from .pdf_crop import PdfCropSampler
                    self._pdf_sampler = PdfCropSampler()
                try:
                    clean, pdf_crop_meta = self._pdf_sampler.sample(
                        entry, self.size, crop_rng, crop_seed=crop_seed,
                        crop_slot=crop_slot if crop_slot >= 0 else None,
                        bank_size=self.crop_bank_size if crop_slot >= 0 else None,
                        dpi_variant=index % self.crop_reuse_views,
                        dpi_variants=self.crop_reuse_views,
                    )
                except (OSError, ValueError, IndexError, RuntimeError):
                    continue
                break
            boxes = entry.get("boxes") if isinstance(entry, dict) else None
            # Raster scans and verified-label crops also replay a stable
            # physical region. Cache the *base* crop, never its augmented
            # version, so every encounter still gets independent transforms.
            crop_key = (str(path), int(self.size), int(crop_seed),
                        self.labeled_focus_probability)
            cached = self._raster_crop_cache.get(crop_key)
            if cached is not None:
                self._raster_crop_cache.move_to_end(crop_key)
                clean = cached.copy()
                crop_cache_hit = True
                break
            img = self._read_cached(path)
            if img is not None and min(img.shape[:2]) >= 64:
                clean = sample_label_guided_crop(
                    img, self.size, crop_rng, boxes,
                    focus_probability=self.labeled_focus_probability,
                )
                if clean.nbytes <= self._raster_crop_budget:
                    self._raster_crop_cache[crop_key] = clean.copy()
                    self._raster_crop_bytes += clean.nbytes
                    while self._raster_crop_bytes > self._raster_crop_budget:
                        _, removed = self._raster_crop_cache.popitem(last=False)
                        self._raster_crop_bytes -= removed.nbytes
                break

        if clean is None:
            raise RuntimeError('Failed to sample a blueprint after 8 attempts')
        # Decide on a genuinely clean crop before any rotation/skew/page
        # deformation. Truly clean examples retain the crop's source geometry,
        # whereas the other examples receive all independent chaos stages.
        full_clean_view = bool(rng.random() < self.clean_fraction)
        if not full_clean_view:
            clean = apply_geometric_variability(clean, rng)
        symbol_stats = {"requested": 0, "placed": 0, "shortfall": 0,
                        "neca": 0, "procedural": 0}
        if self.max_symbols > 0 and rng.random() < self.symbol_augment_probability:
            from .ssl_augmentation import compose_ssl_symbols
            clean, symbol_stats = compose_ssl_symbols(
                clean, rng, neca_bank=self.neca_bank,
                max_symbols=self.max_symbols,
                min_size_px=self.symbol_min_px,
                max_size_px=self.symbol_max_px,
                procedural_fraction=self.procedural_fraction,
                symbol_chaos_probability=(
                    0.0 if full_clean_view else self.symbol_chaos_probability
                ),
                symbol_chaos_strength=self.symbol_chaos_strength,
                placement_chaos=self.placement_chaos,
                drawing_dpi=(pdf_crop_meta["effective_dpi"]
                             if pdf_crop_meta is not None else None),
            )

        # Page-wide deformation happens after individual symbol composition.
        # Geometrically warp the complete plan and its symbols, *before*
        # creating the aligned degraded input and clean SSL target. The exact
        # placed boxes are mapped through the same transforms for previews.
        from .page_chaos import warp_whole_page
        placements = symbol_stats.get("placements", [])
        pre_page = clean.copy() if self.include_pre_page_preview else None
        clean, warped_boxes, page_chaos_meta = warp_whole_page(
            clean, rng,
            boxes=[item["bbox_xyxy"] for item in placements],
            probability=(0.0 if full_clean_view else self.page_chaos_probability),
            strength=self.page_chaos_strength,
        )
        for item, warped_box in zip(placements, warped_boxes):
            item["bbox_xyxy"] = warped_box

        if self.return_metadata:
            degraded, mask, degradation_meta = apply_runtime_corruptions(
                clean, rng, clean_fraction=(1.0 if full_clean_view else 0.0),
                protected_boxes=[item["bbox_xyxy"] for item in placements],
                return_metadata=True,
            )
        else:
            degraded, mask = apply_runtime_corruptions(
                clean, rng, clean_fraction=(1.0 if full_clean_view else 0.0),
                protected_boxes=[item["bbox_xyxy"] for item in placements],
            )

        # BGR uint8 -> RGB float32 [0, 1] CHW
        clean_rgb = np.ascontiguousarray(clean[:, :, ::-1]).transpose(2, 0, 1)
        deg_rgb = np.ascontiguousarray(degraded[:, :, ::-1]).transpose(2, 0, 1)

        clean_t = torch.from_numpy(clean_rgb).float().div_(255.0)
        deg_t = torch.from_numpy(deg_rgb).float().div_(255.0)
        mask_t = torch.from_numpy(mask).unsqueeze(0)
        if self.return_metadata:
            details = {
                "source": str(path), "bucket": self.bucket_names[b_idx],
                "pdf_crop": pdf_crop_meta,
                "base_crop": {
                    "seed": crop_seed,
                    "bank_slot": crop_slot if crop_slot >= 0 else None,
                    "fresh": crop_slot < 0,
                    "reuse_views": self.crop_reuse_views,
                    "background_labels_exhaustive": False,
                    "clean_crop_cache_hit": (
                        bool(pdf_crop_meta.get("clean_crop_cache_hit"))
                        if pdf_crop_meta is not None else crop_cache_hit
                    ),
                },
                "full_clean_view": full_clean_view,
                "geometric_variability_enabled": not full_clean_view,
                "symbol_stats": symbol_stats,
                "page_chaos": page_chaos_meta,
                "degradation": degradation_meta,
                "mask_fraction": float(mask.mean()),
                "mean_absolute_degradation": float(
                    np.abs(degraded.astype(np.float32) - clean.astype(np.float32)).mean()
                ),
                "index": int(index), "epoch": int(ep),
            }
            if pre_page is not None:
                details["pre_page_bgr"] = pre_page
            return deg_t, clean_t, mask_t, details
        return deg_t, clean_t, mask_t


class _ConvBlock(nn.Sequential):
    def __init__(self, in_ch: int, out_ch: int):
        super().__init__(
            nn.Conv2d(in_ch, out_ch, 3, padding=1, bias=False),
            nn.BatchNorm2d(out_ch),
            nn.SiLU(inplace=True),
            nn.Conv2d(out_ch, out_ch, 3, padding=1, bias=False),
            nn.BatchNorm2d(out_ch),
            nn.SiLU(inplace=True),
        )


class Yolo26P2SSLModel(nn.Module):
    """Wraps Ultralytics YOLO26n-P2 (layers 0..28: backbone + P2-P5 neck) with a
    lightweight multi-scale reconstruction decoder for self-supervised pretraining.
    """

    def __init__(self, architecture: str = 'yolo26n-p2.yaml', imgsz: int = 512) -> None:
        super().__init__()
        from ultralytics import YOLO

        yolo = YOLO(architecture)
        self.det_model = yolo.model
        self.detect_idx = len(self.det_model.model) - 1
        detect_layer = self.det_model.model[self.detect_idx]
        if detect_layer.__class__.__name__ != 'Detect':
            raise ValueError(f'Expected final layer to be Detect, got {detect_layer.__class__.__name__}')
        self.tap_indices: list[int] = list(detect_layer.f)
        self.save_set: set[int] = set(self.det_model.save) | set(self.tap_indices)

        # Probe P2..P5 channel counts without contaminating BatchNorm running
        # statistics. The zero-image probe is *not* a training example.
        was_training = self.det_model.training
        self.det_model.eval()
        try:
            with torch.inference_mode():
                dummy = torch.zeros(1, 3, imgsz, imgsz)
                feats = self.extract_neck_features(dummy)
            chs = [f.shape[1] for f in feats]
        finally:
            self.det_model.train(was_training)

        dec_ch = 64
        self.projs = nn.ModuleList([
            nn.Sequential(
                nn.Conv2d(c, dec_ch, 1, bias=False),
                nn.BatchNorm2d(dec_ch),
                nn.SiLU(inplace=True),
            )
            for c in chs
        ])
        self.fuse = _ConvBlock(dec_ch, dec_ch)
        self.up1 = _ConvBlock(dec_ch, 48)   # 128 -> 256
        self.up2 = _ConvBlock(48, 32)       # 256 -> 512
        self.head = nn.Conv2d(32, 3, 1)

    def extract_neck_features(self, x: torch.Tensor) -> list[torch.Tensor]:
        y: list[torch.Tensor | None] = []
        h = x
        for i in range(self.detect_idx):
            m = self.det_model.model[i]
            if m.f != -1:
                h = y[m.f] if isinstance(m.f, int) else [h if j == -1 else y[j] for j in m.f]
            h = m(h)
            y.append(h if i in self.save_set else None)
        return [y[idx] for idx in self.tap_indices]  # [P2, P3, P4, P5]

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        feats = self.extract_neck_features(x)
        target_hw = feats[0].shape[-2:]  # P2 stride-4 resolution (128x128 for 512 input)
        acc = self.projs[0](feats[0])
        for proj, f in zip(self.projs[1:], feats[1:]):
            acc = acc + F.interpolate(proj(f), size=target_hw, mode='bilinear', align_corners=False)
        h = self.fuse(acc)
        h = F.interpolate(h, scale_factor=2.0, mode='bilinear', align_corners=False)
        h = self.up1(h)
        h = F.interpolate(h, scale_factor=2.0, mode='bilinear', align_corners=False)
        h = self.up2(h)
        return torch.sigmoid(self.head(h))

    def backbone_neck_state_dict(self) -> dict[str, torch.Tensor]:
        """Return only backbone + neck weights (layers 0..detect_idx-1), excluding Detect."""
        prefix = f'model.{self.detect_idx}.'
        return {
            k: v.detach().cpu()
            for k, v in self.det_model.state_dict().items()
            if not k.startswith(prefix)
        }


class BlueprintSSLLoss(nn.Module):
    """Ink-balanced, masked L1 + Sobel edge restoration loss.

    Thin electrical lines are often <2% of a drawing crop. Multiplying their
    per-pixel weight by four still leaves almost all reconstruction loss on
    white paper. Normalize ink and paper losses separately, then give ink
    a stable fraction regardless of how sparse the drawing is.
    """

    def __init__(self, mask_weight: float = 2.5, ink_weight: float = 2.0, edge_weight: float = 0.5) -> None:
        super().__init__()
        if mask_weight < 0 or ink_weight <= 0 or edge_weight < 0:
            raise ValueError("SSL loss weights must be nonnegative (ink_weight > 0)")
        self.mask_weight = float(mask_weight)
        # Ratio between mean ink error and mean paper error (2 = 2:1).
        self.ink_weight = float(ink_weight)
        self.edge_weight = float(edge_weight)
        kx = torch.tensor([[-1.0, 0.0, 1.0], [-2.0, 0.0, 2.0], [-1.0, 0.0, 1.0]]).view(1, 1, 3, 3)
        ky = torch.tensor([[-1.0, -2.0, -1.0], [0.0, 0.0, 0.0], [1.0, 2.0, 1.0]]).view(1, 1, 3, 3)
        self.register_buffer('kx', kx)
        self.register_buffer('ky', ky)

    def _edges(self, img: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
        gray = img.mean(dim=1, keepdim=True)
        gx = F.conv2d(gray, self.kx, padding=1)
        gy = F.conv2d(gray, self.ky, padding=1)
        return gx, gy

    def forward(
        self,
        pred: torch.Tensor,
        target: torch.Tensor,
        mask: torch.Tensor,
    ) -> tuple[torch.Tensor, dict[str, float]]:
        # Weight masked regions without allowing empty paper to drown out
        # the few real symbol strokes. Keep channel-sum error to preserve the
        # previous L1 scale relative to the Sobel edge term.
        lum = target.mean(dim=1, keepdim=True)
        ink = (lum < 0.80).float()
        weights = 1.0 + self.mask_weight * mask

        pixel_l1 = (pred - target).abs().sum(dim=1, keepdim=True)
        ink_mass = weights * ink
        paper_mass = weights * (1.0 - ink)
        axes = (1, 2, 3)
        ink_den = ink_mass.sum(dim=axes)
        paper_den = paper_mass.sum(dim=axes)
        ink_l1 = (pixel_l1 * ink_mass).sum(dim=axes) / ink_den.clamp_min(1.0)
        paper_l1 = (pixel_l1 * paper_mass).sum(dim=axes) / paper_den.clamp_min(1.0)
        ink_share = self.ink_weight / (1.0 + self.ink_weight)
        balanced_l1 = ink_share * ink_l1 + (1.0 - ink_share) * paper_l1
        # A genuinely all-white or all-ink crop must not lose loss scale.
        balanced_l1 = torch.where(ink_den == 0, paper_l1, balanced_l1)
        balanced_l1 = torch.where(paper_den == 0, ink_l1, balanced_l1)
        weighted_l1 = balanced_l1.mean()

        px, py = self._edges(pred)
        tx, ty = self._edges(target)
        edge_l1 = ((px - tx).abs() + (py - ty).abs()).mean()

        loss = weighted_l1 + self.edge_weight * edge_l1
        stats = {
            'loss': float(loss.detach().item()),
            'l1': float(weighted_l1.detach().item()),
            'edge': float(edge_l1.detach().item()),
        }
        return loss, stats


def group_is_holdout(group_id: str, val_fraction: float = 0.10, seed: str = 'bps-ssl-v1') -> bool:
    """Deterministic group-level holdout decision (entire project stays together)."""
    h = int.from_bytes(hashlib.sha256(f'{seed}/{group_id}'.encode()).digest()[:8], 'big')
    return (h / float(1 << 64)) < val_fraction
