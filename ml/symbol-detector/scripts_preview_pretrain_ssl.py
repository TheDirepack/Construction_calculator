"""Preview real SSL training samples without starting GPU training.

  python scripts_preview_pretrain_ssl.py work/pretrain_v3/train_manifest.json \
      --neca-dir data/blueprints_and_libraries/2_component_libraries/neca_100_2013_svgs/NECA_100_Vector_Library \
      --out work/pretrain_v3/previews --samples 32

Every image shows the clean target (left) and degraded/masked input (right).
The JSON report records requested and actual inserted symbols, shortfall,
mask coverage, and input/target difference. No labels are fabricated.
"""
from __future__ import annotations

import argparse
from collections import Counter
import json
import time
from pathlib import Path

import cv2
import numpy as np

from blueprint_symbols.ssl_pretrain import BlueprintSSLDataset


def preview(manifest_path: Path, output: Path, *, neca_dir: Path | None = None,
            samples: int = 32, seed: int = 2026, size: int = 512,
            max_symbols: int = 16,
            symbol_chaos_probability: float = .88,
            symbol_chaos_strength: float = 1.0,
            placement_chaos: float = .85,
            page_chaos_probability: float = .70,
            page_chaos_strength: float = 1.0,
            clean_fraction: float = .20) -> dict:
    if samples < 1 or samples > 10000:
        raise ValueError("samples must be 1..10000")
    manifest = json.loads(manifest_path.read_text())
    dataset = BlueprintSSLDataset(
        manifest, size=size, items_per_epoch=samples, seed=seed,
        neca_dir=neca_dir, max_symbols=max_symbols, return_metadata=True,
        include_pre_page_preview=True,
        symbol_chaos_probability=symbol_chaos_probability,
        symbol_chaos_strength=symbol_chaos_strength,
        placement_chaos=placement_chaos,
        page_chaos_probability=page_chaos_probability,
        page_chaos_strength=page_chaos_strength,
        clean_fraction=clean_fraction,
    )
    output.mkdir(parents=True, exist_ok=True)
    rows = []
    generation_ms = []
    for i in range(samples):
        started = time.perf_counter()
        degraded, clean, mask, meta = dataset[i]
        generation_ms.append((time.perf_counter() - started) * 1000.0)
        pre_page = meta.pop("pre_page_bgr")
        def as_bgr(tensor):
            rgb = (tensor.permute(1, 2, 0).numpy() * 255.0).round()
            return cv2.cvtColor(np.clip(rgb, 0, 255).astype(np.uint8),
                                cv2.COLOR_RGB2BGR)
        display = np.concatenate([as_bgr(clean), as_bgr(degraded)], axis=1)
        # Dedicated geometry preview: left = symbol-composited page before
        # whole-page deformation; right = same page after all warp stages.
        # This makes it possible to see the actual geometric transformation.
        geometry = np.concatenate([pre_page, as_bgr(clean)], axis=1)
        geometry_path = output / f"sample_{i:04d}_geometry.png"
        if not cv2.imwrite(str(geometry_path), geometry):
            raise OSError(f"Failed to save {geometry_path}")
        path = output / f"sample_{i:04d}.png"
        if not cv2.imwrite(str(path), display):
            raise OSError(f"Failed to save {path}")
        # Separate annotation overlay preserves the two-panel training pair.
        # Only newly inserted glyphs get rectangles; original devices have
        # no fabricated boxes or labels.
        overlay = as_bgr(clean)
        for glyph in meta["symbol_stats"].get("placements", []):
            x0, y0, x1, y1 = [int(round(v)) for v in glyph["bbox_xyxy"]]
            has_warp = bool(glyph.get("symbol_chaos", {}).get("operations"))
            color = ((30, 20, 220) if has_warp else (30, 180, 30)
                     if glyph["generator"] == "neca_svg" else (180, 60, 20))
            cv2.rectangle(overlay, (x0, y0), (x1, y1), color, 1)
            cv2.putText(overlay, glyph["symbol_family"],
                        (max(0, x0), max(10, y0 - 3)),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.30, color, 1, cv2.LINE_AA)
        overlay_path = output / f"sample_{i:04d}_overlay.png"
        if not cv2.imwrite(str(overlay_path), overlay):
            raise OSError(f"Failed to save {overlay_path}")
        rows.append(meta)
    added = [r["symbol_stats"]["placed"] for r in rows]
    requested = [r["symbol_stats"]["requested"] for r in rows]
    placements = [g for row in rows for g in row["symbol_stats"].get("placements", [])]
    families = Counter(g["symbol_family"] for g in placements)
    severities = Counter(row["degradation"]["severity"] for row in rows)
    degradation_categories = Counter(
        operation for row in rows for operation in row["degradation"]["operations"]
    )
    page_operations = Counter(
        operation for row in rows for operation in row["page_chaos"]["operations"]
    )
    page_severities = Counter(row["page_chaos"]["severity"] for row in rows)
    page_rejection_reasons = Counter(
        reason for row in rows
        for reason, count in row["page_chaos"].get("rejection_reasons", {}).items()
        for _ in range(count)
    )
    page_rejections = sum(row["page_chaos"]["rejected"] for row in rows)
    retained_ink = [
        row["page_chaos"]["min_symbol_ink_retention"] for row in rows
        if row["page_chaos"].get("min_symbol_ink_retention") is not None
    ]
    retained_area = [
        row["page_chaos"]["min_symbol_area_retention"] for row in rows
        if row["page_chaos"].get("min_symbol_area_retention") is not None
    ]
    warp_operations = Counter(
        operation for g in placements
        for operation in g.get("symbol_chaos", {}).get("operations", [])
    )
    placement_modes = Counter(g.get("placement_mode", "unknown") for g in placements)
    warped_symbols = sum(bool(g.get("symbol_chaos", {}).get("operations")) for g in placements)
    variants = {g["symbol_variant"] for g in placements}
    neca_variants = {g["symbol_variant"] for g in placements if g["generator"] == "neca_svg"}
    all_neca_ids = (
        {glyph.symbol_id for glyphs in dataset.neca_bank.by_class.values() for glyph in glyphs}
        if dataset.neca_bank else set()
    )
    full_clean_count = sum(bool(row.get("full_clean_view")) for row in rows)
    pdf_views = [row for row in rows if row.get("pdf_crop") is not None]
    pdf_page_ids = {
        (row["source"], row["pdf_crop"]["pdf_page_index"]) for row in pdf_views
    }
    pdf_clips = {
        (row["source"], row["pdf_crop"]["pdf_page_index"],
         tuple(row["pdf_crop"]["clip_rect_pt"]))
        for row in pdf_views
    }
    pdf_dpis = [row["pdf_crop"]["effective_dpi"] for row in pdf_views]
    dpi_bins = ((100, 150), (150, 200), (200, 250),
                (250, 300), (300, 350), (350, 400))
    dpi_hist = {
        f"{lo}-{hi}": sum(
            lo <= dpi < hi or (hi == 400 and dpi == hi)
            for dpi in pdf_dpis
        )
        for lo, hi in dpi_bins
    }
    dpi_symbol_mismatches = sum(
        abs(glyph.get("drawing_dpi", -1) - row["pdf_crop"]["effective_dpi"]) > .01
        for row in pdf_views
        for glyph in row["symbol_stats"].get("placements", [])
    )
    report = {
        "pdf_dpi_histogram": dpi_hist,
        "pdf_symbol_dpi_mismatch_count": dpi_symbol_mismatches,
        "matched_symbol_color_variants": len({
            tuple(g["ink_color_bgr"]) for g in placements
            if g.get("ink_color_bgr") is not None
        }),
        "pdf_native_views": len(pdf_views),
        "pdf_unique_sources": len({row["source"] for row in pdf_views}),
        "pdf_unique_pages": len(pdf_page_ids),
        "pdf_unique_clips": len(pdf_clips),
        "pdf_crop_dpi_min": min(pdf_dpis) if pdf_dpis else None,
        "pdf_crop_dpi_max": max(pdf_dpis) if pdf_dpis else None,
        "samples": samples,
        "sample_generation_median_ms": round(float(np.median(generation_ms)), 3),
        "sample_generation_p95_ms": round(float(np.percentile(generation_ms, 95)), 3),
        "sample_generation_total_seconds": round(sum(generation_ms) / 1000., 3),
        "full_clean_views": full_clean_count,
        "full_clean_fraction_observed": round(full_clean_count / samples, 4),
        "placed_by_family": dict(sorted(families.items())),
        "symbol_warp_operations": dict(sorted(warp_operations.items())),
        "page_warp_operations": dict(sorted(page_operations.items())),
        "page_warp_severity": dict(sorted(page_severities.items())),
        "page_warp_rejected_stages": page_rejections,
        "page_warp_rejection_reasons": dict(sorted(page_rejection_reasons.items())),
        "worst_inserted_symbol_ink_retention": (
            round(min(retained_ink), 4) if retained_ink else None
        ),
        "worst_inserted_symbol_area_retention": (
            round(min(retained_area), 4) if retained_area else None
        ),
        "warped_symbols": warped_symbols,
        "placement_modes": dict(sorted(placement_modes.items())),
        "degradation_by_severity": dict(sorted(severities.items())),
        "degradation_by_category": dict(sorted(degradation_categories.items())),
        "distinct_inserted_variants": len(variants),
        "eligible_neca_svg_variants": len(all_neca_ids),
        "neca_svg_variants_seen": len(neca_variants),
        "neca_svg_variant_coverage": round(len(neca_variants) / len(all_neca_ids), 4)
        if all_neca_ids else None,
        "requested_total": sum(requested),
        "placed_total": sum(added),
        "placement_shortfall": sum(requested) - sum(added),
        "zero_additions": sum(n == 0 for n in added),
        "max_added_in_a_crop": max(added),
        "clean_views": sum(r["mean_absolute_degradation"] == 0 for r in rows),
        "mean_absolute_degradation": float(np.mean(
            [r["mean_absolute_degradation"] for r in rows])),
        "rows": rows,
    }
    (output / "preview_report.json").write_text(json.dumps(report, indent=2))
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--neca-dir", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--samples", type=int, default=32)
    parser.add_argument("--seed", type=int, default=2026)
    parser.add_argument("--size", type=int, default=512)
    parser.add_argument("--max-symbols", type=int, default=16)
    parser.add_argument("--symbol-chaos-probability", type=float, default=.88)
    parser.add_argument("--symbol-chaos-strength", type=float, default=1.)
    parser.add_argument("--placement-chaos", type=float, default=.85)
    parser.add_argument("--page-chaos-probability", type=float, default=.70)
    parser.add_argument("--page-chaos-strength", type=float, default=1.0)
    parser.add_argument("--clean-fraction", type=float, default=.20)
    args = parser.parse_args()
    result = preview(args.manifest, args.out, neca_dir=args.neca_dir,
                     samples=args.samples, seed=args.seed, size=args.size,
                     max_symbols=args.max_symbols,
                     symbol_chaos_probability=args.symbol_chaos_probability,
                     symbol_chaos_strength=args.symbol_chaos_strength,
                     placement_chaos=args.placement_chaos,
                     page_chaos_probability=args.page_chaos_probability,
                     page_chaos_strength=args.page_chaos_strength,
                     clean_fraction=args.clean_fraction)
    print(json.dumps({k: v for k, v in result.items() if k != "rows"}, indent=2))


if __name__ == "__main__":
    main()
