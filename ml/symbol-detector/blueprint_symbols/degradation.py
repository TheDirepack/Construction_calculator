"""Reproducible raster/photo damage for TRAINING and diagnostic stress sets.

Coordinates and canvas size are unchanged. Source-space scale changes must be
handled by an annotation-aware crop/warp, *before* calling this module.
The effects deliberately approximate information loss, not superresolution.
Do not mistake synthetic corruption for evidence of performance on real scans.
"""
from __future__ import annotations
from dataclasses import dataclass, asdict
import cv2
import numpy as np

PROFILES = ("clean", "mild", "medium", "severe", "extreme")
TRAIN_WEIGHTS = (0.12, 0.21, 0.31, 0.26, 0.10)

@dataclass(frozen=True)
class DamageReport:
    profile: str
    downsample_fraction: float
    blur_sigma: float
    motion_kernel: int
    jpeg_quality: int | None
    stroke_effect: str
    noise_sigma: float
    illumination_delta: float

    def to_dict(self) -> dict:
        return asdict(self)


def choose_profile(rng: np.random.Generator, weights=TRAIN_WEIGHTS) -> str:
    if len(weights) != len(PROFILES) or not np.isfinite(weights).all() or any(x < 0 for x in weights) or sum(weights) <= 0:
        raise ValueError("invalid degradation weights")
    return str(rng.choice(PROFILES, p=np.asarray(weights, float)/sum(weights)))


def degrade(image: np.ndarray, rng: np.random.Generator, *, profile: str = "medium") -> tuple[np.ndarray, DamageReport]:
    """Non-geometric corruptions for byte BGR/gray images; labels unchanged.

    Downsample+upsample creates a real low-pass / aliasing bottleneck. For a
    20px target, fraction .5 makes an intermediate 10px representation.
    These ranges are *experiment knobs*, not established physical camera laws.
    """
    if profile not in PROFILES:
        raise ValueError(f"unknown profile: {profile}")
    if not isinstance(image, np.ndarray) or image.dtype != np.uint8 or image.ndim not in (2, 3):
        raise ValueError("expected uint8 grayscale or color image")
    if image.shape[0] < 4 or image.shape[1] < 4:
        raise ValueError("image too small")
    if profile == "clean":
        return image.copy(), DamageReport(profile, 1., 0., 0, None, "none", 0., 0.)
    # ratio low/high; blur sigma low/high; noise sigma; illumination range; jpeg range
    params = {
        "mild": ((.82, 1.), (0., .45), (0., 2.5), 8., (72, 98), .06),
        "medium": ((.58, .92), (.2, 1.), (1., 6.), 18., (42, 86), .16),
        "severe": ((.37, .76), (.5, 1.8), (2., 12.), 33., (24, 66), .35),
        "extreme": ((.22, .54), (.8, 2.4), (4., 16.), 49., (12, 45), .48),
    }
    scale_rng,blur_rng,noise_rng,light_limit,jpeg_rng,stroke_prob = params[profile]
    x=image.copy()
    h,w=x.shape[:2]
    fraction=float(rng.uniform(*scale_rng))
    blurred_sigma=float(rng.uniform(*blur_rng))
    motion_size=0
    if blurred_sigma > .08 and rng.random() < .85:
        x=cv2.GaussianBlur(x,(0,0),blurred_sigma)
    if rng.random() < (0.12 if profile=='mild' else .35):
        motion_size=int(rng.choice((3,5,7)))
        kernel=np.zeros((motion_size,motion_size),np.float32)
        if rng.random() < .5:kernel[motion_size//2,:]=1./motion_size
        else:kernel[:,motion_size//2]=1./motion_size
        x=cv2.filter2D(x,-1,kernel)
    # Use area filtering for information-destroying downsamples.
    nw,nh=max(2,round(w*fraction)),max(2,round(h*fraction))
    if (nh,nw)!=(h,w):
        reduced=cv2.resize(x,(nw,nh),interpolation=cv2.INTER_AREA)
        x=cv2.resize(reduced,(w,h),interpolation=int(rng.choice((cv2.INTER_LINEAR,cv2.INTER_NEAREST,cv2.INTER_CUBIC))))
    stroke="none"
    if rng.random()<stroke_prob:
        # Rare dilation/erosion approximates thick toner/faded line weight.
        stroke=str(rng.choice(("ink_bleed","faded_strokes")))
        k=cv2.getStructuringElement(cv2.MORPH_CROSS,(3,3))
        x=cv2.erode(x,k,iterations=1) if stroke=="ink_bleed" else cv2.dilate(x,k,iterations=1)
    illum=float(rng.uniform(-light_limit,light_limit))
    if abs(illum) > 1:
        # Spatially changing paper brightness, no warp of symbol geometry.
        gx=np.linspace(-1,1,w,dtype=np.float32)[None,:]
        gy=np.linspace(-1,1,h,dtype=np.float32)[:,None]
        direction=float(rng.uniform(-1,1))
        field=illum*(direction*gx+(1-abs(direction))*gy)
        if x.ndim==3:field=field[:,:,None]
        x=np.clip(x.astype(np.float32)+field,0,255).astype(np.uint8)
    noise_sigma=float(rng.uniform(*noise_rng))
    if noise_sigma > .2:
        x=np.clip(x.astype(np.float32)+rng.normal(0,noise_sigma,x.shape),0,255).astype(np.uint8)
    quality=int(rng.integers(jpeg_rng[0],jpeg_rng[1]+1))
    if x.ndim==3:
        ok,b=cv2.imencode('.jpg',x,[cv2.IMWRITE_JPEG_QUALITY,quality])
        if ok:x=cv2.imdecode(b,cv2.IMREAD_COLOR)
    else:
        ok,b=cv2.imencode('.jpg',x,[cv2.IMWRITE_JPEG_QUALITY,quality])
        if ok:x=cv2.imdecode(b,cv2.IMREAD_GRAYSCALE)
    return x, DamageReport(profile,fraction,blurred_sigma,motion_size,quality,stroke,noise_sigma,illum)
