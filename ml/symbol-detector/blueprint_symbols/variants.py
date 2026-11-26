"""Paired image-damage variants for detection training and HELD-OUT evaluation.

``make_variant`` operations are photometric/non-geometric in the exported
512x512 tile coordinate system, so existing object boxes remain aligned.
``rotate90`` and ``skew`` are the geometric ones: they move the boxes too.
Nothing here mirrors a tile (drawings never show mirrored text or arrows). Downsample+upsample
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
# Training variants of one base tile, in order: the clean render, the same
# vector drawing from another plotter/viewer, a scanned copy, then more.
TRAIN_VARIANTS = ("clean", "vector_random", "scan_random", "mixed_random", "lowres_random",
                  "cleaned_contrast")


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


def paper_color(img: np.ndarray) -> np.ndarray:
    """The tile's paper tone (90th percentile per channel; ink is darker)."""
    flat = img.reshape(-1, img.shape[2]) if img.ndim == 3 else img.reshape(-1, 1)
    return np.percentile(flat, 90, axis=0).astype(np.uint8)


def rotate90(img: np.ndarray, boxes, k: int):
    """Turn a square tile by ``k`` x 90 degrees counter-clockwise; boxes
    ``[(cls, (x0, y0, x1, y1)), ...]`` in tile pixels follow exactly."""
    k %= 4
    if img.shape[0] != img.shape[1]:
        raise ValueError("rotate90 needs a square tile")
    size = img.shape[0]
    out = np.ascontiguousarray(np.rot90(img, k))
    moved = []
    for cls, (x0, y0, x1, y1) in boxes:
        for _ in range(k):          # one CCW quarter turn: (x, y) -> (y, size - x)
            x0, y0, x1, y1 = y0, size - x1, y1, size - x0
        moved.append((cls, (x0, y0, x1, y1)))
    return out, moved


def skew(img: np.ndarray, boxes, degrees: float):
    """A slightly crooked scan: rotate the tile by ``degrees`` about its
    centre (paper colour fills the corners). Each box keeps its size and
    moves with its centre (symbols are compact, so the box stays tight; the
    corner-enclosing box would grow ~2.5% at 1.5 degrees). Boxes pushed
    mostly out of the tile are dropped."""
    h, w = img.shape[:2]
    matrix = cv2.getRotationMatrix2D((w / 2, h / 2), degrees, 1.0)
    paper = tuple(int(v) for v in paper_color(img))
    out = cv2.warpAffine(img, matrix, (w, h), flags=cv2.INTER_LINEAR,
                         borderMode=cv2.BORDER_CONSTANT,
                         borderValue=paper if img.ndim == 3 else paper[0])
    moved = []
    for cls, (x0, y0, x1, y1) in boxes:
        cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
        nx, ny = matrix @ np.array([cx, cy, 1.0])
        bw, bh = x1 - x0, y1 - y0
        box = (max(0., nx - bw / 2), max(0., ny - bh / 2), min(float(w), nx + bw / 2), min(float(h), ny + bh / 2))
        if (box[2] - box[0]) * (box[3] - box[1]) >= .9 * bw * bh:
            moved.append((cls, box))
    return out, moved


def _ink_contrast(img: np.ndarray) -> float:
    """Paper minus the darkest ink (0.5th percentile), in grey levels."""
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY) if img.ndim == 3 else img
    return float(np.percentile(gray, 99) - np.percentile(gray, .5))


def vector_render(image: np.ndarray, rng: np.random.Generator) -> tuple[np.ndarray, dict]:
    """How the same vector PDF looks from another plotter/viewer: line
    weight (thinner or bolder pens), lighter grey ink, a different render
    resolution, rarely a light JPEG. No noise, no blur beyond resampling.

    Drawing lines are often 1 px at 150 DPI: a full 3x3 dilation would erase
    them, so "thin" blends half of it in (fainter, narrower strokes), and the
    result keeps at least 45% of the tile's ink contrast (else the line and
    ink changes are dropped): a variant must still show its symbols."""
    meta: dict = {"operators": []}
    weight = str(rng.choice(["none", "thin", "bold"], p=[.45, .25, .30]))
    ink = float(rng.uniform(.45, .85)) if rng.random() < .45 else 1.
    fraction = float(rng.uniform(.6, .95)) if rng.random() < .5 else 1.
    quality = int(rng.integers(70, 95)) if rng.random() < .15 else None
    contrast = _ink_contrast(image)

    def render(weight: str, ink: float) -> np.ndarray:
        x = image
        if weight != "none":
            kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3))
            # dark ink on light paper: dilating luminance thins strokes, eroding thickens
            x = (cv2.addWeighted(x, .5, cv2.dilate(x, kernel), .5, 0) if weight == "thin"
                 else cv2.erode(x, kernel))
        if ink < 1:                                    # dark grey / colour-to-grey plots
            x = (255 - (255 - x.astype(np.float32)) * ink).clip(0, 255).astype(np.uint8)
        if fraction < 1:                               # rendered at a lower DPI
            x = low_resolution(x, fraction)
        return x

    x = render(weight, ink)
    if contrast > 0 and _ink_contrast(x) < .45 * contrast:
        weight, ink = "none", 1.
        x = render(weight, ink)
    if weight != "none":
        meta["operators"].append(f"{weight}_lines")
    if ink < 1:
        meta.update(ink_strength=ink)
        meta["operators"].append("gray_ink")
    if fraction < 1:
        meta.update(downsample_fraction=fraction)
        meta["operators"].append("render_resolution")
    if quality is not None:
        ok, buf = cv2.imencode(".jpg", x, [cv2.IMWRITE_JPEG_QUALITY, quality])
        if ok:
            x = cv2.imdecode(buf, cv2.IMREAD_COLOR if x.ndim == 3 else cv2.IMREAD_GRAYSCALE)
            meta.update(jpeg_quality=quality)
    return x if x is not image else image.copy(), meta


def bilevel_scan(image: np.ndarray, rng: np.random.Generator) -> tuple[np.ndarray, dict]:
    """A black-and-white (fax/CCITT G4) scan of a print: optics blur, the
    scanner's resolution, a hard threshold (thin faint lines break, bold ones
    bloat), speckle dirt, then back to the tile resolution."""
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY) if image.ndim == 3 else image.copy()
    h, w = gray.shape
    sigma = float(rng.uniform(.3, 1.1))
    x = cv2.GaussianBlur(gray, (0, 0), sigma)
    fraction = float(rng.uniform(.5, 1.))
    if fraction < .98:
        x = cv2.resize(x, (max(2, round(w * fraction)), max(2, round(h * fraction))), interpolation=cv2.INTER_AREA)
    # The scanner thresholds the print, where lines are dark; in this tile they
    # may already be grey (anti-aliased resampling), so the cut sits between
    # this tile's paper and its darkest ink, not at a fixed grey level.
    paper, dark = float(np.percentile(x, 95)), float(np.percentile(x, .3))
    cut = float(rng.uniform(.3, .7))
    threshold = paper - cut * max(1., paper - dark)
    x = np.where(x < threshold, 0, 255).astype(np.uint8)
    speckle = float(rng.uniform(0, .0012))
    if speckle:
        dots = rng.random(x.shape) < speckle
        x[dots] = 255 - x[dots]
    if x.shape != (h, w):
        x = cv2.resize(x, (w, h), interpolation=int(rng.choice((cv2.INTER_NEAREST, cv2.INTER_LINEAR))))
    out = cv2.cvtColor(x, cv2.COLOR_GRAY2BGR) if image.ndim == 3 else x
    return out, {"operators": ["blur", "scan_resolution", "threshold", "speckle"],
                 "blur_sigma": sigma, "downsample_fraction": fraction,
                 "threshold": threshold, "threshold_cut": cut, "speckle": speckle}


def symbol_ink(img: np.ndarray, boxes, *, reference: np.ndarray | None = None) -> list[int]:
    """Ink pixels inside each box ``[(cls, (x0, y0, x1, y1)), ...]``: pixels
    darker than halfway between paper and the darkest ink of ``reference``
    (default: ``img`` itself)."""
    ref = img if reference is None else reference
    gray_ref = cv2.cvtColor(ref, cv2.COLOR_BGR2GRAY) if ref.ndim == 3 else ref
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY) if img.ndim == 3 else img
    paper, dark = float(np.percentile(gray_ref, 95)), float(np.percentile(gray_ref, .3))
    cut = paper - .5 * max(1., paper - dark)
    out = []
    for _, (x0, y0, x1, y1) in boxes:
        patch = gray[int(y0):int(np.ceil(y1)), int(x0):int(np.ceil(x1))]
        out.append(int((patch < cut).sum()))
    return out


def symbols_survive(before: np.ndarray, after: np.ndarray, boxes, *, keep: float = .35) -> bool:
    """Whether every labelled symbol still shows at least ``keep`` of its ink
    after damage (a label over blank paper teaches the detector to see
    symbols that are not there). Boxes with no ink before are ignored."""
    old = symbol_ink(before, boxes)
    new = symbol_ink(after, boxes, reference=before)
    return all(n >= keep * o for o, n in zip(old, new) if o > 0)


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
    if name == "vector_random":
        out, details = vector_render(image, rng)
        meta.update(details)
        return out, meta
    if name == "scan_random":
        # A scanned PDF: greyscale scan damage (blur, resolution, noise,
        # uneven paper, JPEG) or a black-and-white bilevel scan.
        if rng.random() < .35:
            out, details = bilevel_scan(image, rng)
            meta.update(details, scan="bilevel")
            return out, meta
        actual = str(rng.choice(["mild", "medium", "severe"], p=[.35, .45, .20]))
        out, damage = degrade(image, rng, profile=actual)
        meta.update(damage.to_dict())
        meta.update(variant=name, scan="greyscale", operators=["mixed_degradation"])
        return out, meta
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
