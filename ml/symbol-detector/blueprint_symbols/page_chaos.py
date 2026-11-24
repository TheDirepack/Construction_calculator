"""Page-wide geometric chaos with synchronized symbol bounding boxes.

Applied AFTER individual glyph composition and BEFORE input-only scan noise.
The entire source drawing and all newly inserted symbols are warped together,
so the SSL reconstruction target and degraded input have identical geometry.
A uint16 instance-id mask undergoes each exact transformation to recompute
post-warp boxes; severe clipping or collapse rejects that stage.
"""
from __future__ import annotations

import math

import cv2
import numpy as np


def _locally_invertible_remap(grid_x: np.ndarray, grid_y: np.ndarray) -> bool:
    """Reject foldovers where one drawn region maps onto another.

    Inspect a downsampled inverse-coordinate grid. Its Jacobian must remain
    positive and within a generous local scale budget. Pixel-retention alone
    does not detect mirror reversals or extreme local squeezing.
    """
    if grid_x.shape != grid_y.shape or grid_x.ndim != 2:
        raise ValueError("remap fields must share a two-dimensional shape")
    h, w = grid_x.shape
    if min(h, w) < 5:
        return True
    step = max(1, min(h, w) // 128)
    x = grid_x[::step, ::step]
    y = grid_y[::step, ::step]
    dxx = np.gradient(x, float(step), axis=1)
    dxy = np.gradient(x, float(step), axis=0)
    dyx = np.gradient(y, float(step), axis=1)
    dyy = np.gradient(y, float(step), axis=0)
    det = dxx * dyy - dxy * dyx
    return bool(np.isfinite(det).all() and det.min() > 0.12 and det.max() < 4.0)


def warp_whole_page(
    image: np.ndarray,
    rng: np.random.Generator,
    *,
    boxes: list[list[float]] | None = None,
    probability: float = 0.68,
    strength: float = 1.0,
) -> tuple[np.ndarray, list[list[float]], dict]:
    """Warp a drawing in-place conceptually, but never mutate the passed array.

    1..3 independently sampled modes per chosen sample:
    anisotropic stretch/shear/skew, projective perspective, smooth mesh
    elasticity, crossing waves, page curl, crease/fold, radial lens warp,
    regional crumple and horizontal scanner-feed slip.

    Returns a copy, updated bounding boxes, and audit metadata. The boxes are
    rasterized into an ID map and transformed with the identical geometric
    operations, rather than applying a corner-only approximation to nonrigid
    warps. All transforms maintain canvas dimensions and readable ink budgets.
    """
    if image.ndim != 3 or image.dtype != np.uint8 or image.shape[2] != 3:
        raise ValueError("expected uint8 HxWx3 blueprint")
    if not (math.isfinite(probability) and 0 <= probability <= 1):
        raise ValueError("probability must be in [0,1]")
    if not (math.isfinite(strength) and 0 <= strength <= 2):
        raise ValueError("strength must be in [0,2]")
    h, w = image.shape[:2]
    prior = [list(map(float, box)) for box in (boxes or [])]
    for box in prior:
        if len(box) != 4 or not all(map(math.isfinite, box)):
            raise ValueError("invalid bbox")
        if not (0 <= box[0] < box[2] <= w and 0 <= box[1] < box[3] <= h):
            raise ValueError("bbox outside blueprint")
    meta = {"severity": "unchanged", "operations": [],
            "rejected": 0, "rejection_reasons": {}, "box_count": len(prior),
            "min_symbol_ink_retention": None,
            "min_symbol_area_retention": None}
    if min(h, w) < 64 or probability == 0 or strength == 0 or rng.random() >= probability:
        return image.copy(), prior, meta

    # Each ID is a complete source box, not a segmented object. The coarse box
    # is sufficient to preserve validity and reconstruct new axis-aligned boxes
    # even under wavy fields. No instance boxes overlap by compositor design.
    if len(prior) >= 65535:
        raise ValueError("too many instances for uint16 label map")
    labels = np.zeros((h, w), np.uint16)
    for idx, box in enumerate(prior, 1):
        x0, y0, x1, y1 = box
        xx0, yy0 = max(0, int(math.floor(x0))), max(0, int(math.floor(y0)))
        xx1, yy1 = min(w, int(math.ceil(x1))), min(h, int(math.ceil(y1)))
        labels[yy0:yy1, xx0:xx1] = idx

    original_gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    base_ink = int((original_gray < 190).sum())
    base_areas = np.bincount(labels.ravel(), minlength=len(prior) + 1)
    base_symbol_ink = np.bincount(
        labels[original_gray < 190].ravel(), minlength=len(prior) + 1
    )
    paper = tuple(int(v) for v in (np.median(image[::4, ::4].reshape(-1, 3), axis=0) if min(image.shape[:2]) >= 64 else np.median(image.reshape(-1, 3), axis=0)))
    out = image.copy()
    base_names = ("stretch_shear", "projective", "elastic_mesh", "cross_waves",
                  "page_curl", "fold_bend", "radial_lens", "local_crumple",
                  "feed_slip")
    severity = str(rng.choice(("subtle", "moderate", "heavy", "extreme"),
                              p=(.23, .40, .30, .07)))
    power = strength * {"subtle": .42, "moderate": .72,
                        "heavy": 1.05, "extreme": 1.35}[severity]
    n_operations = int(rng.integers(1, 4))
    modes = [str(k) for k in rng.choice(base_names, size=n_operations, replace=False)]

    yy, xx = np.mgrid[:h, :w].astype(np.float32)
    cx, cy = (w - 1) / 2., (h - 1) / 2.
    small = float(min(h, w))
    max_move = 0.055 * small * power

    for mode in modes:
        mat2 = None
        mat3 = None
        grid_x = grid_y = None

        if mode == "stretch_shear":
            sx = float(np.exp(rng.uniform(-.20, .20) * power))
            sy = float(np.exp(rng.uniform(-.20, .20) * power))
            shear_x = float(rng.uniform(-.11, .11) * power)
            shear_y = float(rng.uniform(-.11, .11) * power)
            theta = math.radians(float(rng.uniform(-5, 5) * power))
            R = np.array([[math.cos(theta), -math.sin(theta)],
                          [math.sin(theta), math.cos(theta)]], np.float32)
            A = R @ np.array([[sx, shear_x], [shear_y, sy]], np.float32)
            if np.linalg.det(A) < .64:
                meta["rejected"] += 1
                reasons = meta["rejection_reasons"]
                reasons["foldover"] = reasons.get("foldover", 0) + 1
                continue
            trans = np.array([cx, cy], np.float32) - A @ np.array([cx, cy], np.float32)
            trans += rng.uniform(-max_move * .25, max_move * .25, 2).astype(np.float32)
            mat2 = np.column_stack((A, trans)).astype(np.float32)

        elif mode == "projective":
            source = np.array([[0., 0.], [w-1., 0.],
                               [w-1., h-1.], [0., h-1.]], np.float32)
            jitter = rng.uniform(-max_move, max_move, (4, 2)).astype(np.float32)
            # Opposite corners jitter independently: page keystone and skew.
            mat3 = cv2.getPerspectiveTransform(source, source + jitter)

        else:
            # Inverse remap output->source. Distortion amplitudes are always
            # relative to canvas size rather than fixed-pixel assumptions.
            if mode == "elastic_mesh":
                dx = cv2.resize(rng.normal(0, max_move * .52, (5, 5)).astype(np.float32),
                                (w, h), interpolation=cv2.INTER_CUBIC)
                dy = cv2.resize(rng.normal(0, max_move * .52, (5, 5)).astype(np.float32),
                                (w, h), interpolation=cv2.INTER_CUBIC)
            elif mode == "cross_waves":
                cycles_x, cycles_y = rng.uniform(.75, 3.4, 2)
                phase_x, phase_y = rng.uniform(-math.pi, math.pi, 2)
                dx = max_move * .57 * np.sin(yy / h * math.tau * cycles_y + phase_y)
                dy = max_move * .57 * np.sin(xx / w * math.tau * cycles_x + phase_x)
            elif mode == "page_curl":
                edge = float(rng.choice((-1, 1)))
                axis = int(rng.integers(2))
                if axis == 0:
                    u = (xx / max(1, w-1)) if edge > 0 else (1-xx / max(1, w-1))
                    dx = max_move * np.maximum(0., u-.35)**2 * float(rng.choice((-1, 1)))
                    dy = max_move * .24 * np.sin(yy / h * math.pi) * u
                else:
                    u = (yy / max(1, h-1)) if edge > 0 else (1-yy / max(1, h-1))
                    dy = max_move * np.maximum(0., u-.35)**2 * float(rng.choice((-1, 1)))
                    dx = max_move * .24 * np.sin(xx / w * math.pi) * u
            elif mode == "fold_bend":
                direction = float(rng.uniform(0, math.tau))
                normal = (xx-cx)*math.cos(direction) + (yy-cy)*math.sin(direction)
                offset = float(rng.uniform(-.27, .27) * small)
                width = float(rng.uniform(.04, .15) * small)
                bend = max_move * .60 * np.tanh((normal-offset) / width)
                dx = bend * math.cos(direction)
                dy = bend * math.sin(direction)
            elif mode == "radial_lens":
                rr = ((xx-cx)**2 + (yy-cy)**2) / max(1., cx*cx + cy*cy)
                coeff = float(rng.uniform(-.065, .065) * power)
                dx = (xx-cx) * rr * coeff
                dy = (yy-cy) * rr * coeff
            elif mode == "local_crumple":
                center_x = float(rng.uniform(.15*w, .85*w))
                center_y = float(rng.uniform(.15*h, .85*h))
                radius = float(rng.uniform(.10, .31) * small)
                env = np.exp(-((xx-center_x)**2 + (yy-center_y)**2) /
                             (2 * radius * radius)).astype(np.float32)
                dx = env * float(rng.uniform(-max_move, max_move))
                dy = env * float(rng.uniform(-max_move, max_move))
            else:  # feed_slip: one band shifts horizontally/vertically
                horizontal = bool(rng.integers(2))
                pos = float(rng.uniform(.20, .80) * (h if horizontal else w))
                width = float(rng.uniform(.025, .11) * small)
                delta = float(rng.uniform(-max_move, max_move))
                if horizontal:
                    weight = np.clip(1.-abs(yy-pos)/width, 0., 1.)
                    dx = delta * weight
                    dy = np.zeros_like(dx)
                else:
                    weight = np.clip(1.-abs(xx-pos)/width, 0., 1.)
                    dy = delta * weight
                    dx = np.zeros_like(dy)
            grid_x = (xx + dx).astype(np.float32)
            grid_y = (yy + dy).astype(np.float32)
            if not _locally_invertible_remap(grid_x, grid_y):
                meta["rejected"] += 1
                reasons = meta["rejection_reasons"]
                reasons["local_foldover_or_squeeze"] = (
                    reasons.get("local_foldover_or_squeeze", 0) + 1
                )
                continue

        if mat2 is not None:
            candidate = cv2.warpAffine(out, mat2, (w, h), flags=cv2.INTER_LINEAR,
                                        borderMode=cv2.BORDER_CONSTANT, borderValue=paper)
            candidate_ids = cv2.warpAffine(labels, mat2, (w, h), flags=cv2.INTER_NEAREST,
                                            borderMode=cv2.BORDER_CONSTANT, borderValue=0)
        elif mat3 is not None:
            candidate = cv2.warpPerspective(out, mat3, (w, h), flags=cv2.INTER_LINEAR,
                                             borderMode=cv2.BORDER_CONSTANT, borderValue=paper)
            candidate_ids = cv2.warpPerspective(labels, mat3, (w, h),
                                                 flags=cv2.INTER_NEAREST,
                                                 borderMode=cv2.BORDER_CONSTANT, borderValue=0)
        else:
            candidate = cv2.remap(out, grid_x, grid_y, cv2.INTER_LINEAR,
                                  borderMode=cv2.BORDER_CONSTANT, borderValue=paper)
            candidate_ids = cv2.remap(labels, grid_x, grid_y, cv2.INTER_NEAREST,
                                      borderMode=cv2.BORDER_CONSTANT, borderValue=0)

        # Check both box area AND actual symbol ink against the *original*
        # sheet. Area-only checks can accept a blank symbol-shaped rectangle.
        # Vectorized bincount avoids scanning the entire page separately for
        # each inserted symbol (up to sixteen per crop).
        reason = None
        candidate_gray = cv2.cvtColor(candidate, cv2.COLOR_BGR2GRAY)
        if prior:
            areas = np.bincount(candidate_ids.ravel(), minlength=len(prior) + 1)
            symbol_ink = np.bincount(
                candidate_ids[candidate_gray < 190].ravel(),
                minlength=len(prior) + 1,
            )
            for idx in range(1, len(prior) + 1):
                baseline = int(base_areas[idx])
                after = int(areas[idx])
                if baseline and (after < max(4, baseline * .40)
                                 or after > baseline * 2.60):
                    reason = "symbol_area_loss_or_expansion"
                    break
                baseline_ink = int(base_symbol_ink[idx])
                if baseline_ink >= 6 and symbol_ink[idx] < max(2, baseline_ink * .30):
                    reason = "symbol_ink_loss"
                    break
        if reason is None and base_ink > 64:
            remaining = int((candidate_gray < 190).sum())
            if remaining < .42 * base_ink or remaining > 2.1 * base_ink:
                reason = "page_ink_loss_or_expansion"
        if reason is None:
            out = candidate
            labels = candidate_ids
            meta["operations"].append(mode)
        else:
            meta["rejected"] += 1
            reasons = meta["rejection_reasons"]
            reasons[reason] = reasons.get(reason, 0) + 1

    new_boxes: list[list[float]] = []
    for idx, before_box in enumerate(prior, 1):
        ys, xs = np.where(labels == idx)
        if not len(xs):
            # A rejected stage must never drop a symbol. No warp can be lost.
            new_boxes.append(before_box)
        else:
            new_boxes.append([float(xs.min()), float(ys.min()),
                              float(xs.max()+1), float(ys.max()+1)])
    if meta["operations"]:
        meta["severity"] = severity
    if prior:
        final_areas = np.bincount(labels.ravel(), minlength=len(prior) + 1)
        final_dark = cv2.cvtColor(out, cv2.COLOR_BGR2GRAY) < 190
        final_symbol_ink = np.bincount(
            labels[final_dark].ravel(), minlength=len(prior) + 1
        )
        ink_ratios = [
            float(final_symbol_ink[i] / base_symbol_ink[i])
            for i in range(1, len(prior) + 1) if base_symbol_ink[i] >= 6
        ]
        area_ratios = [
            float(final_areas[i] / base_areas[i])
            for i in range(1, len(prior) + 1) if base_areas[i] >= 4
        ]
        if ink_ratios:
            meta["min_symbol_ink_retention"] = round(min(ink_ratios), 4)
        if area_ratios:
            meta["min_symbol_area_retention"] = round(min(area_ratios), 4)
    return np.ascontiguousarray(out), new_boxes, meta
