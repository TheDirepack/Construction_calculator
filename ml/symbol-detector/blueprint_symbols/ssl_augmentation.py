"""Random multi-symbol composition for blueprint-only SSL pretraining.

Reuse the same verified NECA SVG and procedural CAD-style glyph compositors as
supervised synthetic training. Draw on the CLEAN crop *before* corruption so
both the reconstruction target and its input contain the same symbols.
No pseudo-labels are emitted or treated as annotations.
"""
from __future__ import annotations

import numpy as np

from .neca_svg_compositor import NecaSymbolBank, composite_neca_page


def compose_ssl_symbols(
    clean: np.ndarray,
    rng: np.random.Generator,
    *,
    neca_bank: NecaSymbolBank | None = None,
    max_symbols: int = 16,
    min_size_px: float = 12.0,
    max_size_px: float = 80.0,
    procedural_fraction: float = 0.25,
) -> tuple[np.ndarray, dict]:
    """Composite 0..max_symbols randomly scaled glyphs on a source crop.

    NECA samples span every eligible symbol in the source catalog. Procedural
    glyphs cover seven electrical families; with no NECA bank they are used
    exclusively. Existing drawing symbols remain in the source image.
    Requested count can exceed successful placements on dense drawings; the
    metadata exposes actual placement counts for auditing.
    """
    if not 0 <= max_symbols <= 128:
        raise ValueError("max_symbols must be between 0 and 128")
    if not 0 < min_size_px <= max_size_px:
        raise ValueError("invalid symbol size range")
    if not 0 <= procedural_fraction <= 1:
        raise ValueError("invalid procedural_fraction")
    if clean.ndim != 3 or clean.shape[2] != 3 or clean.dtype != np.uint8:
        raise ValueError("expected uint8 BGR drawing crop")

    requested = int(rng.integers(0, max_symbols + 1))
    metadata = {"requested": requested, "placed": 0, "neca": 0, "procedural": 0}
    if not requested:
        metadata["shortfall"] = 0
        metadata["placements"] = []
        return clean.copy(), metadata

    neca_count = int(rng.binomial(requested, 1 - procedural_fraction)) if neca_bank else 0
    proc_count = requested - neca_count
    page = clean.copy()
    instances = []

    if neca_count:
        page, instances = composite_neca_page(
            {"instances": []}, page, neca_bank, rng,
            per_page=neca_count, wall_fraction=0.55,
            size_px_range=(min_size_px, max_size_px), tag="ssl",
        )
        metadata["neca"] = len(instances)

    # If wall-aware NECA placement could not fit all selected glyphs, try
    # procedural symbols for the shortfall instead of silently starving
    # crowded crops. The actual count may still fall short on dense pages.
    proc_count += neca_count - len(instances)
    if proc_count:
        # Keep the existing procedural compositor's stroke matching, wall
        # placement, collision handling and anti-aliased drawing.
        from scripts_composite_electrical import composite_page

        page, procedural = composite_page(
            {"instances": instances}, page, rng,
            per_page=proc_count,
            scale_range=(min_size_px / 40.0, max_size_px / 40.0),
            max_ink=0.16, tag="ssl", stroke_match=True,
            wall_fraction=0.55, canonical_classes=True,
        )
        metadata["procedural"] = len(procedural)

    metadata["placed"] = metadata["neca"] + metadata["procedural"]
    metadata["shortfall"] = metadata["requested"] - metadata["placed"]
    metadata["placements"] = [
        {
            "symbol_family": inst["symbol_family"],
            "symbol_variant": inst["symbol_variant"],
            "bbox_xyxy": inst["bbox_xyxy"],
            "generator": "neca_svg" if inst in instances else "procedural",
        }
        for inst in [*instances, *(procedural if proc_count else [])]
    ]
    return page, metadata
