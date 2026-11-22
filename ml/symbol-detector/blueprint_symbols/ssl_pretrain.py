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
    """Decode an image as uint8 BGR, flattening RGBA onto white paper."""
    raw = cv2.imread(str(path), cv2.IMREAD_UNCHANGED)
    if raw is None:
        return None
    if raw.ndim == 2:
        return cv2.cvtColor(raw, cv2.COLOR_GRAY2BGR)
    if raw.shape[2] == 3:
        return raw
    if raw.shape[2] == 4:
        bgr = raw[:, :, :3].astype(np.float32)
        alpha = raw[:, :, 3:4].astype(np.float32) / 255.0
        flat = np.clip(bgr * alpha + 255.0 * (1.0 - alpha), 0.0, 255.0).round().astype(np.uint8)
        return flat
    return raw[:, :, :3]


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
    for _ in range(4):
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
        if ink_frac >= 0.008:
            return crop
        if ink_frac > best_ink:
            best_ink = ink_frac
            best_crop = crop

    return best_crop if best_crop is not None else cv2.resize(image, (size, size), interpolation=cv2.INTER_AREA)


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
) -> tuple[np.ndarray, np.ndarray]:
    """Apply runtime resolution filtering, common filtering, JPEG compression noise, and block masking.

    Returns:
      degraded_bgr: uint8 (H, W, 3)
      mask: float32 (H, W) with 1.0 on masked pixels, 0.0 elsewhere
    """
    x = clean_bgr.copy()
    h, w = x.shape[:2]

    # 1. Photometric / illumination / paper tint variability
    if rng.random() < 0.85:
        gain = float(rng.uniform(0.75, 1.25))
        offset = float(rng.uniform(-22.0, 22.0))
        xf = x.astype(np.float32) * gain + offset
        # Spatially varying illumination (scanner/camera shadow gradient)
        if rng.random() < 0.50:
            limit = float(rng.uniform(8.0, 40.0))
            gx = np.linspace(-1.0, 1.0, w, dtype=np.float32)[None, :]
            gy = np.linspace(-1.0, 1.0, h, dtype=np.float32)[:, None]
            direction = float(rng.uniform(-1.0, 1.0))
            field = limit * (direction * gx + (1.0 - abs(direction)) * gy)
            xf = xf + field[:, :, None]
        # Subtle paper tint / white-balance shift
        if rng.random() < 0.30:
            tint = rng.uniform(0.90, 1.10, size=(1, 1, 3)).astype(np.float32)
            xf = xf * tint
        x = np.clip(xf, 0.0, 255.0).astype(np.uint8)

    if rng.random() < 0.20:
        g = cv2.cvtColor(x, cv2.COLOR_BGR2GRAY)
        x = cv2.cvtColor(g, cv2.COLOR_GRAY2BGR)

    # 2. Common filtering: Gaussian blur, directional motion blur, median/box/sharpen, stroke morphology
    if rng.random() < 0.60:
        sigma = float(rng.uniform(0.25, 1.9))
        x = cv2.GaussianBlur(x, (0, 0), sigma)

    if rng.random() < 0.25:
        ksize = int(rng.choice((3, 5, 7)))
        kernel = np.zeros((ksize, ksize), dtype=np.float32)
        mode = int(rng.integers(0, 4))
        if mode == 0:
            kernel[ksize // 2, :] = 1.0 / ksize
        elif mode == 1:
            kernel[:, ksize // 2] = 1.0 / ksize
        elif mode == 2:
            np.fill_diagonal(kernel, 1.0 / ksize)
        else:
            np.fill_diagonal(np.fliplr(kernel), 1.0 / ksize)
        x = cv2.filter2D(x, -1, kernel)

    if rng.random() < 0.25:
        filt = int(rng.integers(0, 3))
        if filt == 0:
            x = cv2.medianBlur(x, 3)
        elif filt == 1:
            x = cv2.blur(x, (3, 3))
        else:
            # Unsharp mask (common PDF/scanner oversharpening)
            blurred = cv2.GaussianBlur(x, (0, 0), 1.0)
            amount = float(rng.uniform(0.4, 1.2))
            x = cv2.addWeighted(x, 1.0 + amount, blurred, -amount, 0)

    # 3. Resolution filtering: lossy area downscale + varied reconstruction upsample
    if rng.random() < 0.80:
        # Mix of mild, medium, severe, and extreme resolution bottlenecks
        band = float(rng.random())
        if band < 0.30:
            frac = float(rng.uniform(0.65, 0.92))
        elif band < 0.70:
            frac = float(rng.uniform(0.40, 0.68))
        else:
            frac = float(rng.uniform(0.22, 0.42))
        nw, nh = max(16, int(round(w * frac))), max(16, int(round(h * frac)))
        reduced = cv2.resize(x, (nw, nh), interpolation=cv2.INTER_AREA)
        up_interp = int(rng.choice((cv2.INTER_LINEAR, cv2.INTER_CUBIC, cv2.INTER_NEAREST)))
        x = cv2.resize(reduced, (w, h), interpolation=up_interp)

    # 4. Stroke morphology (ink bleed / faded toner)
    if rng.random() < 0.30:
        k = cv2.getStructuringElement(cv2.MORPH_CROSS, (3, 3))
        if rng.random() < 0.5:
            x = cv2.erode(x, k, iterations=1)
        else:
            x = cv2.dilate(x, k, iterations=1)

    # 5. Additive Gaussian sensor / scan noise
    if rng.random() < 0.65:
        sigma = float(rng.uniform(1.0, 14.0))
        x = np.clip(x.astype(np.float32) + rng.normal(0.0, sigma, x.shape), 0.0, 255.0).astype(np.uint8)

    # 6. Runtime JPEG compression noise (blockiness + ringing around thin drafting lines)
    if rng.random() < 0.80:
        if rng.random() < 0.45:
            quality = int(rng.integers(15, 48))
        else:
            quality = int(rng.integers(48, 93))
        ok, buf = cv2.imencode('.jpg', x, [cv2.IMWRITE_JPEG_QUALITY, quality])
        if ok:
            decoded = cv2.imdecode(buf, cv2.IMREAD_COLOR)
            if decoded is not None:
                x = decoded

    # 7. Multi-scale block masking (MIM)
    mask = np.zeros((h, w), dtype=np.float32)
    if rng.random() < mask_prob:
        patch = int(rng.choice((16, 32)))
        gh, gw = h // patch, w // patch
        ratio = float(rng.uniform(0.30, 0.55))
        grid = (rng.random((gh, gw)) < ratio).astype(np.float32)
        mask = np.repeat(np.repeat(grid, patch, axis=0), patch, axis=1)
        if mask.shape != (h, w):
            mask = cv2.resize(mask, (w, h), interpolation=cv2.INTER_NEAREST)
        paper_tone = np.median(x.reshape(-1, 3), axis=0).astype(np.float32)
        fill = np.clip(paper_tone[None, None, :] + rng.normal(0.0, 6.0, x.shape), 0.0, 255.0).astype(np.uint8)
        m3 = mask[:, :, None] > 0.5
        x = np.where(m3, fill, x)

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
    ) -> None:
        super().__init__()
        self.size = int(size)
        self.items_per_epoch = int(items_per_epoch)
        self.seed = int(seed)
        self.epoch = 0
        self.deterministic_eval = bool(deterministic_eval)

        self.bucket_names: list[str] = []
        self.bucket_weights: list[float] = []
        self.bucket_groups: dict[str, list[list[str]]] = {}

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
            path = group[int(rng.integers(len(group)))]
            img = self._read_cached(path)
            if img is not None and min(img.shape[:2]) >= 64:
                break

        if img is None:
            raise RuntimeError('Failed to decode any blueprint image after 8 attempts')

        clean = sample_random_crop(img, self.size, rng)
        clean = apply_geometric_variability(clean, rng)
        degraded, mask = apply_runtime_corruptions(clean, rng)

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
