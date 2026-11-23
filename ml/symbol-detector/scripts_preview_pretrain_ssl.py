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
import json
from pathlib import Path

import cv2
import numpy as np

from blueprint_symbols.ssl_pretrain import BlueprintSSLDataset


def preview(manifest_path: Path, output: Path, *, neca_dir: Path | None = None,
            samples: int = 32, seed: int = 2026, size: int = 512,
            max_symbols: int = 16) -> dict:
    if samples < 1 or samples > 10000:
        raise ValueError("samples must be 1..10000")
    manifest = json.loads(manifest_path.read_text())
    dataset = BlueprintSSLDataset(
        manifest, size=size, items_per_epoch=samples, seed=seed,
        neca_dir=neca_dir, max_symbols=max_symbols, return_metadata=True,
    )
    output.mkdir(parents=True, exist_ok=True)
    rows = []
    for i in range(samples):
        degraded, clean, mask, meta = dataset[i]
        def as_bgr(tensor):
            rgb = (tensor.permute(1, 2, 0).numpy() * 255.0).round()
            return cv2.cvtColor(np.clip(rgb, 0, 255).astype(np.uint8),
                                cv2.COLOR_RGB2BGR)
        display = np.concatenate([as_bgr(clean), as_bgr(degraded)], axis=1)
        path = output / f"sample_{i:04d}.png"
        if not cv2.imwrite(str(path), display):
            raise OSError(f"Failed to save {path}")
        rows.append(meta)
    added = [r["symbol_stats"]["placed"] for r in rows]
    requested = [r["symbol_stats"]["requested"] for r in rows]
    report = {
        "samples": samples,
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
    args = parser.parse_args()
    result = preview(args.manifest, args.out, neca_dir=args.neca_dir,
                     samples=args.samples, seed=args.seed, size=args.size,
                     max_symbols=args.max_symbols)
    print(json.dumps({k: v for k, v in result.items() if k != "rows"}, indent=2))


if __name__ == "__main__":
    main()
