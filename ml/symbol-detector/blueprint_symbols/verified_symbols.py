"""Drawings of devices cut from human-verified labels, for compositing.

NECA 100 gives one reference drawing per device; real drawing sets draw each
device their own way. Every verified box in the label store is a real drawing
of a known catalog device, so it can be composited onto other backgrounds next
to the NECA glyphs (``NecaSymbolBank.add_glyphs``).

Held-out evaluation must not see its own project's drawings during training.
Only a project with a page marked "every symbol labeled" can be held out
(positive-only projects always train), so crops come only from projects
with no such page.
"""
from __future__ import annotations

from collections import Counter, defaultdict
from pathlib import Path

import cv2
import numpy as np

from .dataset_manifest import trusted_training_instance
from .label_schema import CATALOG_DEVICES, UNMAPPED, load_training_page
from .neca_svg_compositor import NecaGlyph

INK_FLOOR = 40          # 255 - gray below this is paper, not ink
MIN_SIDE_PX = 8


def ink_alpha(gray: np.ndarray) -> np.ndarray | None:
    """Ink mask of a grayscale crop (255 = full ink), trimmed to the ink."""
    ink = 255.0 - gray.astype(np.float32)
    alpha = np.clip((ink - INK_FLOOR) * 255.0 / (255.0 - INK_FLOOR), 0, 255).astype(np.uint8)
    ys, xs = np.nonzero(alpha > 32)
    if len(xs) == 0:
        return None
    alpha = alpha[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    return alpha if min(alpha.shape) >= 3 else None


def verified_symbol_glyphs(pages: list[Path], *, pad: int = 1) -> tuple[list[NecaGlyph], dict]:
    """Glyphs for every trusted, catalog-device box on ``pages`` (saved page
    JSONs, e.g. ``label_store.training_pages``), skipping projects that could
    be held out."""
    views = [(Path(p), load_training_page(p)) for p in pages]
    holdout_able = {v.get("project_family_id") for _, v in views
                    if v.get("annotation_completeness_verified") is True
                    and v.get("verified_no_symbols") is not True}
    labels = {f"{f}.{v}" for f, v in CATALOG_DEVICES}
    glyphs: list[NecaGlyph] = []
    stats: Counter = Counter()
    per_device: dict[str, int] = defaultdict(int)
    for path, view in views:
        boxes = [i for i in view.get("instances") or []
                 if trusted_training_instance(i) and i.get("symbol_family") in labels]
        if not boxes:
            continue
        if view.get("project_family_id") in holdout_able:
            stats["skipped_holdout_able_project"] += len(boxes)
            continue
        gray = cv2.imread(str((path.parent / view["image_path"]).resolve()), cv2.IMREAD_GRAYSCALE)
        if gray is None:
            stats["unreadable_image"] += 1
            continue
        h, w = gray.shape
        for inst in boxes:
            x0, y0, x1, y1 = (float(v) for v in inst["bbox_xyxy"])
            if min(x1 - x0, y1 - y0) < MIN_SIDE_PX:
                stats["too_small"] += 1
                continue
            crop = gray[max(0, int(y0) - pad):min(h, int(np.ceil(y1)) + pad),
                        max(0, int(x0) - pad):min(w, int(np.ceil(x1)) + pad)]
            alpha = ink_alpha(crop)
            if alpha is None:
                stats["no_ink"] += 1
                continue
            device = inst["symbol_family"]
            family, variant = device.split(".", 1)
            glyphs.append(NecaGlyph(
                symbol_id=f"verified:{path.stem}:{inst.get('instance_id')}",
                code="", name=device, section="verified_label_store",
                canonical_class="verified", device_type=family, subtype=variant,
                alpha=alpha, family=family, variant=variant))
            per_device[device] += 1
            stats["glyphs"] += 1
    assert UNMAPPED not in per_device
    return glyphs, {"counts": dict(stats), "per_device": dict(sorted(per_device.items()))}
