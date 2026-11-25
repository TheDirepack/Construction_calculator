"""Symbol drawings from crop libraries, for compositing and pretraining.

A crop library is a folder with ``crops/<id>.png``, ``crops.json`` (each crop
and the device its legend text, block name or caption proposes) and
``review.json`` (accepted / rejected, optionally a corrected device):

* ``2_component_libraries/legend_symbol_crops`` (scripts_extract_legend_symbols.py)
* ``2_component_libraries/harvested_symbols`` (scripts_harvest_symbols.py)

Detector training (``labeled_only=True``) takes only crops review accepted
as a catalog device. Self-supervised pretraining (``labeled_only=False``)
takes every crop review did not reject; it needs no labels.

These come from outside drawing sets, standards and CAD libraries, never the
evaluation set, so they need no held-out filtering.
"""
from __future__ import annotations

import hashlib
import json
from collections import Counter
from pathlib import Path

import cv2

from .label_schema import device_class, device_for_class
from .neca_svg_compositor import NecaGlyph
from .verified_symbols import ink_alpha


def library_glyphs(root: str | Path, *, labeled_only: bool = True) -> tuple[list[NecaGlyph], dict]:
    root = Path(root)
    library = root.name
    crops = json.loads((root / "crops.json").read_text())["crops"]
    review_path = root / "review.json"
    review = json.loads(review_path.read_text()) if review_path.exists() else {}
    glyphs: list[NecaGlyph] = []
    stats: Counter = Counter()
    per_device: Counter = Counter()
    for crop in crops:
        decision = review.get(crop["id"]) or {}
        status = decision.get("status")
        fam_var = None
        if status == "accepted":
            device = decision.get("device") or crop.get("device")
            fam_var = device_for_class(device) if device else None
            if fam_var and device_class(*fam_var) != device:
                fam_var = None
        if labeled_only and fam_var is None:
            stats["not_accepted_device"] += 1
            continue
        if status == "rejected":
            stats["rejected"] += 1
            continue
        gray = cv2.imread(str(root / "crops" / f"{crop['id']}.png"), cv2.IMREAD_GRAYSCALE)
        alpha = ink_alpha(gray) if gray is not None else None
        if alpha is None:
            stats["unreadable"] += 1
            continue
        family, variant = fam_var or ("", "")
        name = f"{family}.{variant}" if fam_var else (crop.get("text") or crop["id"])
        glyphs.append(NecaGlyph(
            symbol_id=f"{library}:{crop['id']}", code="", name=str(name)[:80],
            section=f"{library}:{crop.get('method') or crop['source_file'].split('/', 1)[0]}",
            canonical_class=library, device_type=family, subtype=variant,
            alpha=alpha, family=family, variant=variant))
        if fam_var:
            per_device[name] += 1
        stats["glyphs"] += 1
    return glyphs, {"library": library, "counts": dict(stats), "devices": len(per_device),
                    "per_device": dict(sorted(per_device.items()))}


def legend_symbol_glyphs(root: str | Path) -> tuple[list[NecaGlyph], dict]:
    """Accepted catalog-device crops of a library (detector training)."""
    return library_glyphs(root, labeled_only=True)


def glyph_fingerprint(glyphs: list[NecaGlyph]) -> str:
    """Digest of glyph ids, labels and pixels (pretraining resume safety)."""
    digest = hashlib.sha256()
    for g in sorted(glyphs, key=lambda g: g.symbol_id):
        digest.update(f"{g.symbol_id}|{g.family}.{g.variant}|{g.alpha.shape}".encode())
        digest.update(g.alpha.tobytes())
    return digest.hexdigest()
