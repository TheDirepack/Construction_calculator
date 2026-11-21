"""Paired image-damage variants for detection training and HELD-OUT evaluation.

Every operation is photometric/non-geometric in the exported 512x512 tile
coordinate system, so existing object boxes remain aligned. Downsample+upsample
is intentionally lossy: no interpolation can reconstruct the lost features.
This is *not* a substitute for rendering vector art at a genuinely low DPI.
"""
from __future__ import annotations

import cv2
import numpy as np
from .degradation import degrade

# fixed variants are reproducible, unlike a broad random stress label.
EVAL_VARIANTS = ("clean", "cleaned_contrast", "lowres_75", "lowres_50", "lowres_25",
                 "blur_only", "jpeg_only", "thin_strokes", "thick_strokes",
                 "medium", "severe", "extreme")
TRAIN_VARIANTS = ("clean", "lowres_random", "mixed_random", "cleaned_contrast")


def _valid(img: np.ndarray):
    if not isinstance(img, np.ndarray) or img.dtype != np.uint8 or img.ndim not in (2, 3):
        raise ValueError("uint8 grayscale/BGR required")
    if min(img.shape[:2]) < 4:
        raise ValueError("tile too small")


def low_resolution(img: np.ndarray, fraction: float, *, interpolation: int = cv2.INTER_LINEAR) -> np.ndarray:
    """Reproduce a lower-resolution scan, then fit model's fixed input size.

    Pixel center mapping is handled by OpenCV resize; the spatial label geometry
    is unchanged after the image is returned to its original canvas dimensions.
    """
    _valid(img)
    if not .05 <= fraction <= 1.:
        raise ValueError("bad downsample fraction")
    h, w = img.shape[:2]
    if fraction == 1.:
        return img.copy()
    nw, nh = max(2, round(w * fraction)), max(2, round(h * fraction))
    reduced = cv2.resize(img, (nw, nh), interpolation=cv2.INTER_AREA)
    return cv2.resize(reduced, (w, h), interpolation=interpolation)


def make_variant(image: np.ndarray, rng: np.random.Generator, name: str) -> tuple[np.ndarray, dict]:
    """Produce one independently named/inspectable corruption family.

    'mixed_random' samples mild through extreme multi-defect examples.
    Fixed eval variants avoid accidentally changing their meaning across runs.
    """
    _valid(image)
    meta = {"variant": name, "downsample_fraction": 1., "operators": []}
    if name == "clean":
        return image.copy(), meta
    if name.startswith("lowres_") and name != "lowres_random":
        try:
            fraction = int(name.split("_")[1]) / 100
        except (ValueError, IndexError) as e:
            raise ValueError(f"bad lowres variant: {name}") from e
        out = low_resolution(image, fraction)
        meta.update(downsample_fraction=fraction, operators=["area_downscale", "linear_reconstruct"])
        return out, meta
    if name == "cleaned_contrast":
        # A reversible-looking print-cleanup approximation. Contrast local
        # enhancement does not alter symbol coordinates or crop geometry.
        # Keep optional: overaggressive cleaning can remove very fine strokes.
        if image.ndim == 2:
            out = cv2.createCLAHE(clipLimit=1.8, tileGridSize=(8,8)).apply(image)
        else:
            lab=cv2.cvtColor(image,cv2.COLOR_BGR2LAB)
            lab[:,:,0]=cv2.createCLAHE(clipLimit=1.8, tileGridSize=(8,8)).apply(lab[:,:,0])
            out=cv2.cvtColor(lab,cv2.COLOR_LAB2BGR)
        meta.update(operators=["clahe_luminance"], clip_limit=1.8)
        return out,meta
    if name == "lowres_random":
        fraction = float(rng.uniform(.20, .9))
        out = low_resolution(image, fraction, interpolation=int(rng.choice([cv2.INTER_LINEAR, cv2.INTER_NEAREST])))
        meta.update(downsample_fraction=fraction, operators=["area_downscale", "reconstruct"])
        return out, meta
    if name == "blur_only":
        sigma = 1.25
        meta.update(operators=["gaussian_blur"], sigma=sigma)
        return cv2.GaussianBlur(image, (0, 0), sigma), meta
    if name == "jpeg_only":
        quality = 30
        success, buf = cv2.imencode(".jpg", image, [cv2.IMWRITE_JPEG_QUALITY, quality])
        if not success:
            raise RuntimeError("jpeg encoder failed")
        mode = cv2.IMREAD_GRAYSCALE if image.ndim == 2 else cv2.IMREAD_COLOR
        meta.update(operators=["jpeg"], jpeg_quality=quality)
        return cv2.imdecode(buf, mode), meta
    if name in ("thin_strokes", "thick_strokes"):
        kernel = cv2.getStructuringElement(cv2.MORPH_CROSS, (3, 3))
        # Dark ink on light paper: dilation of luminance thins dark strokes.
        out = (cv2.dilate(image, kernel, iterations=1) if name == "thin_strokes"
               else cv2.erode(image, kernel, iterations=1))
        meta.update(operators=[name])
        return out, meta
    if name in ("mild", "medium", "severe", "extreme", "mixed_random"):
        actual = str(rng.choice(["mild", "medium", "severe", "extreme"], p=[.20, .40, .30, .10])) if name == "mixed_random" else name
        out, damage = degrade(image, rng, profile=actual)
        meta.update(damage.to_dict())
        meta["variant"] = name
        meta["operators"] = ["mixed_degradation"]
        return out, meta
    raise ValueError(f"unknown variant: {name}")
