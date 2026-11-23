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
    return out


def apply_runtime_corruptions(
    clean_bgr: np.ndarray,
    rng: np.random.Generator,
    *,
    mask_prob: float = 0.85,
    clean_fraction: float = 0.20,
) -> tuple[np.ndarray, np.ndarray]:
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

    # Requested fully clean views are genuinely unmasked; mask_prob=1 is an
    # explicit testing/training override that still guarantees MIM masking.
    if rng.random() < clean_fraction and mask_prob < 1:
        return x, mask

    severity = str(rng.choice(("light", "moderate", "heavy", "extreme"),
                              p=(0.34, 0.37, 0.23, 0.06)))
    strength = {"light": 0.45, "moderate": 0.80,
                "heavy": 1.15, "extreme": 1.55}[severity]

    def chance(p: float) -> bool:
        return bool(rng.random() < min(1.0, strength * p))

    # Illumination, white balance, paper color, locally uneven exposure.
    if chance(0.68):
        gain = float(rng.uniform(1 - 0.22 * strength, 1 + 0.22 * strength))
        offset = float(rng.uniform(-18 * strength, 18 * strength))
        xf = x.astype(np.float32) * gain + offset
        if chance(0.46):
            gx = np.linspace(-1, 1, w, dtype=np.float32)[None, :]
            gy = np.linspace(-1, 1, h, dtype=np.float32)[:, None]
            theta = float(rng.uniform(-np.pi, np.pi))
            field = (gx * np.cos(theta) + gy * np.sin(theta)) * float(rng.uniform(5, 30)) * strength
            xf += field[:, :, None]
        if chance(0.28):
            xf *= rng.uniform(1 - 0.08 * strength, 1 + 0.08 * strength, (1, 1, 3))
        x = np.clip(xf, 0, 255).astype(np.uint8)

    # Optical blur, including movement along arbitrary scan directions.
    if chance(0.52):
        sigma = float(rng.uniform(0.25, 1.4 * strength + 0.25))
        x = cv2.GaussianBlur(x, (0, 0), sigma)
    if chance(0.23):
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
        kernel = cv2.getStructuringElement(cv2.MORPH_CROSS, (3, 3))
        x = (cv2.erode(x, kernel, iterations=1) if rng.random() < 0.5
             else cv2.dilate(x, kernel, iterations=1))

    # Missing/broken ink spots, paper speckles, dust and sensor impulse pixels.
    if chance(0.30):
        n = int(h * w * rng.uniform(0.00003, 0.0012) * strength)
        if n:
            ys = rng.integers(0, h, n)
            xs = rng.integers(0, w, n)
            x[ys, xs] = 255 if rng.random() < 0.55 else 0
    if chance(0.20):
        n = int(rng.integers(1, 7))
        for _ in range(n):
            px = int(rng.integers(w))
            py = int(rng.integers(h))
            radius = max(1, int(rng.uniform(1, 4) * strength))
            cv2.circle(x, (px, py), radius, (255, 255, 255)
                       if rng.random() < .6 else (80, 80, 80), -1)

    # Feed/scanner streaks and banding (both axes).
    if chance(0.20):
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
        sigma = float(rng.uniform(0.6, 9.0) * strength)
        x = np.clip(x.astype(np.float32) + rng.normal(0, sigma, x.shape), 0, 255).astype(np.uint8)
    if chance(0.12):
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
            ok, buffer = cv2.imencode(".jpg", x, [cv2.IMWRITE_JPEG_QUALITY, quality])
        if ok:
            decoded = cv2.imdecode(buffer, cv2.IMREAD_COLOR)
            if decoded is not None:
                x = decoded

    # Random masked reconstruction. The clean target includes the same
    # synthetic symbol additions but no corruption.
    if rng.random() < mask_prob:
        patch = int(rng.choice((8, 16, 32, 64)))
        gh, gw = math.ceil(h / patch), math.ceil(w / patch)
        ratio = float(rng.uniform(.15, .55))
        grid = (rng.random((gh, gw)) < ratio).astype(np.float32)
        mask = np.repeat(np.repeat(grid, patch, axis=0), patch, axis=1)[:h, :w]
        paper = np.median(x.reshape(-1, 3), axis=0).astype(np.float32)
        fill = np.clip(paper[None, None, :] + rng.normal(0, 4, x.shape), 0, 255).astype(np.uint8)
        x = np.where(mask[:, :, None] > .5, fill, x)

    return x, mask


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
        neca_dir: str | Path | None = None,
        max_symbols: int = 16,
        symbol_min_px: float = 12.0,
        symbol_max_px: float = 80.0,
        symbol_augment_probability: float = 1.00,
        procedural_fraction: float = 0.25,
        clean_fraction: float = 0.20,
        labeled_focus_probability: float = 0.65,
    ) -> None:
        super().__init__()
        self.size = int(size)
        self.items_per_epoch = int(items_per_epoch)
        self.seed = int(seed)
        self.epoch = 0
        self.deterministic_eval = bool(deterministic_eval)
        if not 0 <= symbol_augment_probability <= 1 or not 0 <= clean_fraction <= 1 or not 0 <= labeled_focus_probability <= 1:
            raise ValueError("invalid SSL sampling probabilities")
        self.max_symbols = int(max_symbols)
        self.symbol_min_px = float(symbol_min_px)
        self.symbol_max_px = float(symbol_max_px)
        self.symbol_augment_probability = float(symbol_augment_probability)
        self.procedural_fraction = float(procedural_fraction)
        self.clean_fraction = float(clean_fraction)
        self.labeled_focus_probability = float(labeled_focus_probability)
        self.neca_bank = None
        if neca_dir is not None:
            from .neca_svg_compositor import NecaSymbolBank
            self.neca_bank = NecaSymbolBank(Path(neca_dir), include_modifiers=False)

        self.bucket_names: list[str] = []
        self.bucket_weights: list[float] = []
        self.bucket_groups: dict[str, list[list[str | dict]]] = {}

        for name, info in sorted(manifest['buckets'].items()):
            groups = [paths for paths in info['groups'].values() if paths]
            if not groups:
                continue
            self.bucket_names.append(name)
            self.bucket_weights.append(float(info.get('weight', 1.0)))
            self.bucket_groups[name] = groups

        if not self.bucket_names:
            raise ValueError('SSL manifest has no non-empty source buckets')

        total_w = sum(self.bucket_weights)
        self.bucket_probs = np.array([w / total_w for w in self.bucket_weights], dtype=np.float64)
        self._cache_path: str | None = None
        self._cache_img: np.ndarray | None = None

    def set_epoch(self, epoch: int) -> None:
        self.epoch = int(epoch)

    def __len__(self) -> int:
        return self.items_per_epoch

    def _read_cached(self, path: str) -> np.ndarray | None:
        if self._cache_path == path and self._cache_img is not None:
            return self._cache_img
        img = load_bgr_white_bg(path)
        if img is None:
            return None
        if img.nbytes <= 24 * (1 << 20):
            self._cache_path = path
            self._cache_img = img
        else:
            self._cache_path = None
            self._cache_img = None
        return img

    def __getitem__(self, index: int) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
        ep = 0 if self.deterministic_eval else self.epoch
        rng = np.random.default_rng(np.random.SeedSequence([self.seed, ep, index]))

        img = None
        for _ in range(8):
            b_idx = int(rng.choice(len(self.bucket_names), p=self.bucket_probs))
            groups = self.bucket_groups[self.bucket_names[b_idx]]
            group = groups[int(rng.integers(len(groups)))]
            entry = group[int(rng.integers(len(group)))]
            path = entry["image_path"] if isinstance(entry, dict) else entry
            boxes = entry.get("boxes") if isinstance(entry, dict) else None
            img = self._read_cached(path)
            if img is not None and min(img.shape[:2]) >= 64:
                break

        if img is None:
            raise RuntimeError('Failed to decode any blueprint image after 8 attempts')

        clean = sample_label_guided_crop(
            img, self.size, rng, boxes,
            focus_probability=self.labeled_focus_probability,
        )
        clean = apply_geometric_variability(clean, rng)
        if self.max_symbols > 0 and rng.random() < self.symbol_augment_probability:
            from .ssl_augmentation import compose_ssl_symbols
            clean, _ = compose_ssl_symbols(
                clean, rng, neca_bank=self.neca_bank,
                max_symbols=self.max_symbols,
                min_size_px=self.symbol_min_px,
                max_size_px=self.symbol_max_px,
                procedural_fraction=self.procedural_fraction,
            )
        degraded, mask = apply_runtime_corruptions(
            clean, rng, clean_fraction=self.clean_fraction,
        )

        # BGR uint8 -> RGB float32 [0, 1] CHW
        clean_rgb = np.ascontiguousarray(clean[:, :, ::-1]).transpose(2, 0, 1)
        deg_rgb = np.ascontiguousarray(degraded[:, :, ::-1]).transpose(2, 0, 1)

        clean_t = torch.from_numpy(clean_rgb).float().div_(255.0)
        deg_t = torch.from_numpy(deg_rgb).float().div_(255.0)
        mask_t = torch.from_numpy(mask).unsqueeze(0)
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

        # Probe output channel dimensions of P2..P5 neck taps dynamically.
        with torch.no_grad():
            dummy = torch.zeros(1, 3, imgsz, imgsz)
            feats = self.extract_neck_features(dummy)
        chs = [f.shape[1] for f in feats]

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
    """Ink- and mask-weighted L1 + Sobel edge gradient loss for line-art blueprints."""

    def __init__(self, mask_weight: float = 2.5, ink_weight: float = 4.0, edge_weight: float = 0.5) -> None:
        super().__init__()
        self.mask_weight = float(mask_weight)
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
        # Ink pixels are dark regions on the clean target
        lum = target.mean(dim=1, keepdim=True)
        ink = (lum < 0.80).float()
        weights = 1.0 + self.mask_weight * mask + self.ink_weight * ink

        pixel_l1 = (pred - target).abs()
        weighted_l1 = (pixel_l1 * weights).sum() / weights.sum().clamp_min(1.0)

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
