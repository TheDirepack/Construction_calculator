"""Random multi-symbol composition for blueprint-only SSL pretraining.

Reuse the same verified NECA SVG and procedural CAD-style glyph compositors as
supervised synthetic training. Draw on the CLEAN crop *before* corruption so
both the reconstruction target and its input contain the same symbols.
No pseudo-labels are emitted or treated as annotations.
"""
from __future__ import annotations

from collections import Counter

import cv2
import numpy as np

from .neca_svg_compositor import NecaSymbolBank, composite_neca_page
from .ink_style import sized_symbol_range


def compose_ssl_symbols(
    clean: np.ndarray,
    rng: np.random.Generator,
    *,
    neca_bank: NecaSymbolBank | None = None,
    max_symbols: int = 16,
    requested_symbols: int | None = None,
    min_size_px: float = 12.0,
    max_size_px: float = 80.0,
    procedural_fraction: float = 0.25,
    symbol_chaos_probability: float = 0.88,
    symbol_chaos_strength: float = 1.0,
    placement_chaos: float = 0.85,
    drawing_dpi: float | None = None,
) -> tuple[np.ndarray, dict]:
    """Composite 0..max_symbols randomly scaled glyphs on a source crop.

    requested_symbols forces an exact requested count for supervised generation;
    None retains the original random count for SSL pretraining.

    NECA samples span every eligible symbol in the source catalog. Procedural
    glyphs cover seven electrical families; with no NECA bank they are used
    exclusively. Existing drawing symbols remain in the source image.
    Requested count can exceed successful placements on dense drawings; the
    metadata exposes actual placement counts for auditing.
    """
    if not 0 <= max_symbols <= 128:
        raise ValueError("max_symbols must be between 0 and 128")
    if requested_symbols is not None and not (isinstance(requested_symbols, int) and
                                              not isinstance(requested_symbols, bool) and
                                              0 <= requested_symbols <= max_symbols):
        raise ValueError("requested_symbols must be an integer in [0, max_symbols]")
    if not 0 < min_size_px <= max_size_px:
        raise ValueError("invalid symbol size range")
    if not 0 <= procedural_fraction <= 1:
        raise ValueError("invalid procedural_fraction")
    if not 0 <= symbol_chaos_probability <= 1 or not 0 <= symbol_chaos_strength <= 2:
        raise ValueError("invalid symbol chaos controls")
    if not 0 <= placement_chaos <= 1:
        raise ValueError("placement_chaos must be in [0,1]")
    if clean.ndim != 3 or clean.shape[2] != 3 or clean.dtype != np.uint8:
        raise ValueError("expected uint8 BGR drawing crop")
    # Reference sizes are specified at 200 dpi; use the PDF's actual sampled
    # DPI for both NECA and procedural glyphs. Clamping keeps all candidates
    # physically plausible inside the current crop.
    glyph_min_px, glyph_max_px = sized_symbol_range(
        (min_size_px, max_size_px), drawing_dpi, clean.shape[:2]
    )

    requested = (int(rng.integers(0, max_symbols + 1))
                 if requested_symbols is None else requested_symbols)
    metadata = {"requested": requested, "placed": 0, "neca": 0,
                "procedural": 0, "drawing_dpi": drawing_dpi,
                "symbol_size_range_px": [round(glyph_min_px, 3),
                                         round(glyph_max_px, 3)]}
    if not requested:
        metadata["shortfall"] = 0
        metadata["placements"] = []
        metadata["symbol_warp_operations"] = {}
        metadata["placement_modes"] = {}
        metadata["warped_symbols"] = 0
        return clean.copy(), metadata

    neca_count = int(rng.binomial(requested, 1 - procedural_fraction)) if neca_bank else 0
    # Vary placement distribution *per drawing*, not only per symbol.
    wall_fraction = float(rng.uniform(0.12, 0.88))
    proc_count = requested - neca_count
    page = clean.copy()
    instances = []
    # Detect walls once, on the undecorated crop, and share them with both
    # compositors. Computed exactly where the first compositor used to (same
    # RNG position), so NECA placement is unchanged; the procedural pass no
    # longer re-detects walls on a page that already carries NECA strokes.
    from .wall_alignment import detect_wall_sites
    shared_walls = detect_wall_sites(cv2.cvtColor(page, cv2.COLOR_BGR2GRAY), rng)

    if neca_count:
        page, instances = composite_neca_page(
            {"instances": []}, page, neca_bank, rng,
            per_page=neca_count, wall_fraction=wall_fraction,
            # Already scaled to the requested crop DPI: suppress the
            # legacy page-dimension scale factor in the NECA compositor.
            size_px_range=(min_size_px, max_size_px), tag="ssl",
            drawing_dpi=drawing_dpi, match_local_ink=True,
            ink_reference=clean,
            symbol_chaos_probability=symbol_chaos_probability,
            symbol_chaos_strength=symbol_chaos_strength,
            placement_chaos=placement_chaos,
            precomputed_walls=shared_walls,
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
            # Procedural compositor performs the same 200dpi -> drawing
            # DPI scaling internally; pass reference-unit sizes, not sizes
            # already DPI-adjusted (which would double the scale).
            scale_range=(min_size_px / 40.0, max_size_px / 40.0),
            max_ink=0.16, tag="ssl",
            stroke_match=True,  # already DPI-normalized in the compositor
            drawing_dpi=drawing_dpi, match_local_ink=True,
            ink_reference=clean,
            wall_fraction=wall_fraction, canonical_classes=True,
            symbol_chaos_probability=symbol_chaos_probability,
            symbol_chaos_strength=symbol_chaos_strength,
            placement_chaos=placement_chaos,
            precomputed_walls=shared_walls,
        )
        metadata["procedural"] = len(procedural)

    metadata["placed"] = metadata["neca"] + metadata["procedural"]
    metadata["shortfall"] = metadata["requested"] - metadata["placed"]
    combined = [(inst, "neca_svg") for inst in instances]
    combined.extend((inst, "procedural") for inst in (procedural if proc_count else []))
    metadata["placements"] = [
        {
            "symbol_family": inst["symbol_family"],
            "symbol_variant": inst["symbol_variant"],
            "bbox_xyxy": inst["bbox_xyxy"],
            "generator": generator,
            "symbol_chaos": inst.get("symbol_chaos", {}),
            "placement_mode": inst["source"].get("placement_mode", "unknown"),
            "wall_sampling_probability": inst["source"].get("wall_sampling_probability"),
            "drawing_dpi": inst["source"].get("drawing_dpi"),
            "ink_color_bgr": inst["source"].get("ink_color_bgr"),
            "target_size_px": inst["source"].get("target_px"),
            "glyph_scale": inst["source"].get("glyph_scale"),
        }
        for inst, generator in combined
    ]
    warp_ops = Counter(
        operation for inst, _ in combined
        for operation in inst.get("symbol_chaos", {}).get("operations", [])
    )
    metadata["symbol_warp_operations"] = dict(warp_ops)
    metadata["placement_modes"] = dict(Counter(
        inst["source"].get("placement_mode", "unknown") for inst, _ in combined
    ))
    metadata["warped_symbols"] = sum(
        bool(inst.get("symbol_chaos", {}).get("operations")) for inst, _ in combined
    )
    return page, metadata


def mutate_linework(image: np.ndarray, rng: np.random.Generator) -> tuple[np.ndarray, dict]:
    """Restyle the drawn lines of a symbol-free (walls-only) crop.

    Only for pages proven to hold no symbols: thickening, thinning or breaking
    lines could otherwise alter a real symbol. Each change is independent, so
    one wall slice yields many different-looking drawings. Returns the image
    and a record of the changes made.
    """
    out = image.copy()
    done: list[str] = []
    gray = cv2.cvtColor(out, cv2.COLOR_BGR2GRAY)
    paper = float(np.percentile(gray, 90))
    ink = gray < paper - 40
    if not ink.any():
        return out, {"changes": done}

    r = rng.random()
    if r < .30:                                   # heavier pen / bolder cut walls
        k = int(rng.integers(2, 4))
        out = cv2.erode(out, np.ones((k, k), np.uint8))
        done.append(f"thicker_{k}")
    elif r < .45:                                 # finer pen, only where lines survive it
        thin = cv2.dilate(out, np.ones((2, 2), np.uint8))
        kept = (cv2.cvtColor(thin, cv2.COLOR_BGR2GRAY) < paper - 40).sum() / max(1, ink.sum())
        if kept > .45:
            out = thin
            done.append("thinner")

    gray = cv2.cvtColor(out, cv2.COLOR_BGR2GRAY)
    ink = gray < paper - 40
    if rng.random() < .30:                        # scan dropouts: lines broken in places
        h, w = ink.shape
        holes = np.zeros((h, w), np.uint8)
        for _ in range(int(rng.integers(20, 160))):
            x, y = int(rng.integers(w)), int(rng.integers(h))
            s = int(rng.integers(2, 7))
            holes[y:y + s, x:x + s] = 1
        broken = (holes.astype(bool) & ink)
        out[broken] = int(paper)
        done.append("broken_lines")
    if rng.random() < .30:                        # pen colour
        color = rng.integers(0, 170, 3)
        out[ink] = (out[ink].astype(np.float32) * .3 + color * .7).astype(np.uint8)
        done.append("ink_colour")
    if rng.random() < .20:                        # misregistered / ghosted print
        dx, dy = (int(v) for v in rng.integers(-4, 5, 2))
        ghost = np.roll(out, (dy, dx), axis=(0, 1))
        out = np.minimum(out, (ghost.astype(np.float32) * .35 + 255 * .65).astype(np.uint8))
        done.append("ghost")
    if rng.random() < .40:                        # paper tint and uneven lighting
        h, w = out.shape[:2]
        tint = rng.integers(-28, 6, 3).astype(np.float32)
        yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
        angle = rng.uniform(0, 2 * np.pi)
        ramp = (np.cos(angle) * xx / w + np.sin(angle) * yy / h) * float(rng.uniform(0, 30))
        out = np.clip(out.astype(np.float32) + tint - ramp[..., None], 0, 255).astype(np.uint8)
        done.append("paper")
    return out, {"changes": done}
