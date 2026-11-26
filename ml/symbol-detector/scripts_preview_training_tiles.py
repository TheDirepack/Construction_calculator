"""Contact sheet of exported training tiles, made by the real export path.

    python scripts_preview_training_tiles.py OUT.png [labels files / page refs ...]
        [--store 1_blueprints] [--include-not-ready] [--tiles 24] [--seed 2026]

Runs exactly what ``scripts_export_yolo_tiles.run`` does to a training page:
``RasterTileDataset.sample`` (symbol-centred crop at a sampled symbol size),
``mask_ignored`` (clipped / pending / unmapped boxes painted out) and
``train_variant`` (rotation, vector line-weight and scan damage), and draws
the exported YOLO boxes on each tile, so a person can check that symbols are
legible, variations look like real vector and scanned PDFs, and labels sit on
their symbols.

Read-only: pages come from labels files (``<stem>_labels.json``, every saved
page with a verified box) and their already rendered page images; nothing is
written into the label store. Without paths it scans ``--store``'s ``devices``
folders. Only PDFs marked done for training are used unless
``--include-not-ready``.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np

from blueprint_symbols.dataset_manifest import trusted_training_instance
from blueprint_symbols.label_schema import (UNMAPPED, labels_training_ready, load_training_page,
                                            page_ref, parse_page_ref)
from blueprint_symbols.raster_training import RasterTileDataset, read_records
from scripts_export_yolo_tiles import (MIN_SYMBOL_PIXELS, PIXEL_BANDS, TRAIN_RANDOM_SCALES,
                                       TRAIN_SCALE_LIMITS, mask_ignored, train_variant)

TILE = 512


def find_pages(paths: list[Path], *, include_not_ready: bool) -> list[Path]:
    """Page references with at least one verified box whose image is on disk."""
    files: list[Path] = []
    for path in paths:
        if parse_page_ref(path):
            files.append(path)
        elif path.is_dir():
            files += sorted(path.glob("devices/*/*_labels.json"))
            files += sorted(path.glob("evaluation/devices/*/*_labels.json"))
            files += sorted(path.glob("*/*_labels.json"))
        else:
            files.append(path)
    out: list[Path] = []
    for f in dict.fromkeys(files):
        refs = [f] if parse_page_ref(f) else []
        if not refs:
            if not include_not_ready and not labels_training_ready(f):
                continue
            data = json.loads(Path(f).read_text(encoding="utf-8"))
            refs = [page_ref(f, int(k)) for k in sorted(data.get("pages", {}), key=int)]
        for ref in refs:
            page = load_training_page(ref)
            if not Path(page.get("image_path", "")).is_file():
                continue
            if any(trusted_training_instance(i) and i.get("symbol_family") not in (None, UNMAPPED)
                   for i in page.get("instances") or []):
                out.append(ref)
    return out


def _label(img: np.ndarray, text: str, org, color) -> None:
    cv2.putText(img, text, org, cv2.FONT_HERSHEY_SIMPLEX, .38, (255, 255, 255), 3, cv2.LINE_AA)
    cv2.putText(img, text, org, cv2.FONT_HERSHEY_SIMPLEX, .38, color, 1, cv2.LINE_AA)


def preview(pages: list[Path], output: Path, *, tiles: int = 24, seed: int = 2026,
            variants: int = 3, cols: int = 6, cell: int = 320) -> dict:
    records = read_records(pages)
    names = sorted({i["symbol_family"] for r in records for i in r.instances
                    if trusted_training_instance(i) and i.get("symbol_family") not in (None, UNMAPPED)})
    classes = {n: k for k, n in enumerate(names)}
    # the exporter's dataset settings (scripts_export_yolo_tiles.run)
    data = RasterTileDataset(records, classes, size=TILE, virtual_tiles_per_image=64, seed=seed,
                             source_scales=TRAIN_RANDOM_SCALES, target_symbol_pixels=PIXEL_BANDS,
                             degradation_profile=None, scale_limits=TRAIN_SCALE_LIMITS,
                             min_symbol_pixels=MIN_SYMBOL_PIXELS)
    rng = np.random.default_rng(seed)
    order = rng.permutation(len(data))
    cells, stats = [], {"tiles": 0, "boxes": 0, "masked_regions": 0, "empty_tiles": 0,
                        "symbol_px": [], "variants": {}}
    for index in order:
        if len(cells) >= tiles:
            break
        rgb, targets, info = data.sample(int(index))
        if not targets and stats["empty_tiles"] >= max(1, tiles // 8):
            continue                       # mostly positives: that is what needs checking
        image = cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR)
        image, masked = mask_ignored(image, info["ignore_boxes"], targets)
        variant_index = len(cells) % variants
        out, boxes, damage = train_variant(image, targets, classes, base_index=int(index),
                                           variant_index=variant_index, seed=seed)
        view = out.copy() if out.ndim == 3 else cv2.cvtColor(out, cv2.COLOR_GRAY2BGR)
        for cls, (x0, y0, x1, y1) in boxes:
            cv2.rectangle(view, (int(x0), int(y0)), (int(np.ceil(x1)), int(np.ceil(y1))), (0, 0, 230), 1)
            _label(view, names[cls].split(".", 1)[1][:22], (int(x0), max(10, int(y0) - 3)), (0, 0, 200))
            stats["symbol_px"].append(round(max(x1 - x0, y1 - y0), 1))
        view = cv2.resize(view, (cell, cell), interpolation=cv2.INTER_AREA)
        caption = f"{damage.get('variant')} r{damage.get('rotation_degrees', 0):g} s{info['tile'].source_scale:.2f}"
        _label(view, caption, (4, cell - 6), (160, 60, 0))
        cells.append(view)
        stats["tiles"] += 1
        stats["boxes"] += len(boxes)
        stats["masked_regions"] += masked
        stats["empty_tiles"] += not targets
        stats["variants"][damage.get("variant")] = stats["variants"].get(damage.get("variant"), 0) + 1
    if not cells:
        raise ValueError("no training tiles: no verified boxes on the given pages")
    rows = -(-len(cells) // cols)
    sheet = np.full((rows * (cell + 4), cols * (cell + 4), 3), 90, np.uint8)
    for n, c in enumerate(cells):
        r, k = divmod(n, cols)
        sheet[r * (cell + 4):r * (cell + 4) + cell, k * (cell + 4):k * (cell + 4) + cell] = c
    output.parent.mkdir(parents=True, exist_ok=True)
    if not cv2.imwrite(str(output), sheet):
        raise OSError(output)
    px = sorted(stats.pop("symbol_px"))
    stats.update(pages=len(pages), classes=len(classes), output=str(output),
                 symbol_px_in_tile={"min": px[0], "median": px[len(px) // 2], "max": px[-1]} if px else None,
                 display_scale=f"each {TILE}px tile shown at {cell}px")
    return stats


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("output", type=Path)
    p.add_argument("paths", nargs="*", type=Path, help="labels files, page refs or a 1_blueprints folder")
    p.add_argument("--store", type=Path, help="label store (1_blueprints) to scan when no paths are given")
    p.add_argument("--include-not-ready", action="store_true",
                   help="also use PDFs not yet marked done for training (preview only)")
    p.add_argument("--tiles", type=int, default=24)
    p.add_argument("--seed", type=int, default=2026)
    a = p.parse_args()
    paths = list(a.paths)
    if not paths:
        from annotator.label_store import default_store
        paths = [a.store or default_store()]
    pages = find_pages(paths, include_not_ready=a.include_not_ready)
    print(json.dumps(preview(pages, a.output, tiles=a.tiles, seed=a.seed), indent=2))


if __name__ == "__main__":
    main()
