"""Build phase-two supervised crops from *verified symbol-free* wall-only drawings.

Only generated symbols are positive detection labels. Unaugmented device-free
views have zero instances. An SSL negative_control_kind label is NOT sufficient:
each source entry must explicitly attest the absence of every active target
family; otherwise that source is refused instead of creating false negatives.

Input JSON (paths relative to this manifest):
{"sources": [
  {"image_path": "walls.png", "project_family_id": "building-A",
   "source_group_verified": true,
   "verified_negative_families": ["receptacle", "switch_sensor", "..."]},
  {"source_pdf": "walls.pdf", "pdf_page_index": 3,
   "project_family_id": "building-B", "source_group_verified": true,
   "verified_negative_families": ["receptacle", "switch_sensor", "..."]}
]}
The verified_negative_families list must include ALL classes in the detector
class-map, not merely classes expected to be inserted. PDF entries identify
whole verified pages: no assumption is made from an SSL source bucket/name.

Usage:
  python scripts_generate_phase2_wall_only.py verified_walls.json output/wall_phase2 \
      --class-map detector_classes.json --copies 8 --empty-fraction .3 \
      --neca-root /path/to/neca/svg_catalog

Then combine output/wall_phase2/*.json with other fully verified annotations via
scripts_prepare_training_data.py. All variants retain original project grouping.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path

import cv2
import numpy as np

from blueprint_symbols.neca_svg_compositor import CANONICAL_CLASSES, NecaSymbolBank
from blueprint_symbols.pdf_crop import PdfCropSampler
from blueprint_symbols.ssl_augmentation import compose_ssl_symbols
from blueprint_symbols.variants import make_variant


DAMAGE_MODES = ("clean", "lowres_random", "mixed_random", "cleaned_contrast")
DAMAGE_WEIGHTS = (0.30, 0.25, 0.35, 0.10)


def _classes(path: Path | None) -> set[str]:
    if path is None:
        return set(CANONICAL_CLASSES)
    data = json.loads(path.read_text())
    if isinstance(data, dict) and isinstance(data.get("class_ids"), dict):
        data = data["class_ids"]
    if isinstance(data, dict):
        names = set(data)
    elif isinstance(data, list):
        names = set(data)
    else:
        raise ValueError("class map must be a family list or name-to-index dictionary")
    if not names or not all(isinstance(x, str) and x for x in names):
        raise ValueError("class map contains invalid families")
    missing = set(CANONICAL_CLASSES) - names
    if missing:
        raise ValueError(f"phase-two class map excludes generated symbol families: {sorted(missing)}")
    return names


def _source_path(entry: dict, manifest_dir: Path) -> tuple[Path, bool]:
    is_pdf = "source_pdf" in entry
    if is_pdf and "image_path" in entry:
        raise ValueError("source entry cannot have both source_pdf and image_path")
    name = entry.get("source_pdf" if is_pdf else "image_path")
    if not isinstance(name, str) or not name.strip():
        raise ValueError("source entry needs a source_pdf or image_path")
    path = (manifest_dir / name).resolve()
    if not path.is_file():
        raise FileNotFoundError(path)
    if is_pdf:
        index = entry.get("pdf_page_index")
        if not isinstance(index, int) or isinstance(index, bool) or index < 0:
            raise ValueError(f"PDF source requires a nonnegative pdf_page_index: {path}")
    return path, is_pdf


def _read_sources(manifest: Path, classes: set[str]) -> list[tuple[dict, Path, bool]]:
    raw = json.loads(manifest.read_text())
    items = raw.get("sources") if isinstance(raw, dict) else raw
    if not isinstance(items, list) or not items:
        raise ValueError("wall-only manifest must contain nonempty sources list")
    found = []
    groups = {}
    for entry in items:
        if not isinstance(entry, dict):
            raise ValueError("source must be an object")
        group = entry.get("project_family_id")
        if not isinstance(group, str) or not group.strip() or entry.get("source_group_verified") is not True:
            raise ValueError("wall-only source requires verified project_family_id")
        attested = entry.get("verified_negative_families")
        if (not isinstance(attested, list) or
                any(not isinstance(name, str) or not name.strip() for name in attested) or
                len(attested) != len(set(attested))):
            raise ValueError("verified_negative_families must be a unique family list")
        missing = classes - set(attested)
        if missing:
            raise ValueError(f"{group}: not verified negative for all detector classes: {sorted(missing)}")
        if "calibration_verified" in entry and entry["calibration_verified"] is not True:
            raise ValueError(f"{group}: unverified raster alignment")
        path, pdf = _source_path(entry, manifest.parent)
        key = (str(path), entry["pdf_page_index"] if pdf else None)
        if key in groups:
            raise ValueError(f"source duplicated in wall-only manifest: {key}")
        groups[key] = group
        found.append((entry, path, pdf))
    return found


def _raster_crop(image: np.ndarray, size: int, rng: np.random.Generator) -> tuple[np.ndarray, dict]:
    h, w = image.shape[:2]
    if min(h, w) < 64:
        raise ValueError("wall-only raster source too small")
    span = min(min(h, w), max(64, int(round(size * math.exp(
        rng.uniform(math.log(.5), math.log(3.0))
    )))))
    x = int(rng.integers(0, w - span + 1))
    y = int(rng.integers(0, h - span + 1))
    source_crop = image[y:y + span, x:x + span]
    patch = cv2.resize(source_crop, (size, size), interpolation=(
        cv2.INTER_AREA if span > size else cv2.INTER_LINEAR
    ))
    return patch, {"source_box_pixels": [x, y, x + span, y + span],
                   "source_pixels_per_output_pixel": span / size}


def _load_raster(path: Path) -> np.ndarray:
    raw = cv2.imread(str(path), cv2.IMREAD_UNCHANGED)
    if raw is None:
        raise ValueError(f"cannot decode wall-only source: {path}")
    if raw.ndim == 2:
        return cv2.cvtColor(raw, cv2.COLOR_GRAY2BGR)
    if raw.ndim != 3 or raw.shape[2] not in (3, 4):
        raise ValueError(f"unsupported raster channels: {path}")
    if raw.shape[2] == 3:
        return raw
    rgb, alpha = raw[:, :, :3].astype(np.float32), raw[:, :, 3:4].astype(np.float32) / 255.
    return np.clip(rgb * alpha + 255 * (1 - alpha), 0, 255).astype(np.uint8)


def _modes(copies: int, empty_fraction: float, rng: np.random.Generator) -> list[bool]:
    """True means verified empty. If feasible, generate both kinds per source."""
    if copies < 1 or not 0 <= empty_fraction <= 1:
        raise ValueError("bad copies or empty-fraction")
    count = int(rng.binomial(copies, empty_fraction))
    if 0 < empty_fraction < 1 and copies >= 2:
        count = min(copies - 1, max(1, count))
    modes = [True] * count + [False] * (copies - count)
    rng.shuffle(modes)
    return modes


def _instances(placements: list[dict], classes: set[str], size: int) -> list[dict]:
    instances = []
    for idx, row in enumerate(placements):
        family = row.get("symbol_family")
        if family not in classes:
            raise ValueError(f"generated symbol not in detector class map: {family}")
        box = row.get("bbox_xyxy")
        if not isinstance(box, (list, tuple)) or len(box) != 4:
            raise ValueError("generated symbol missing bbox")
        x0, y0, x1, y1 = [float(v) for v in box]
        if not all(map(math.isfinite, (x0, y0, x1, y1))) or not (
            0 <= x0 < x1 <= size and 0 <= y0 < y1 <= size
        ):
            raise ValueError(f"invalid generated bbox: {box}")
        instances.append({
            "instance_id": f"generated_{idx:03d}",
            "symbol_family": family,
            "symbol_variant": str(row.get("symbol_variant", "unspecified")),
            "bbox_xyxy": [x0, y0, x1, y1],
            "anchors": {},
            "uncertain": False,
            "source": {"kind": "synthetic_composite",
                       "label_method": "generated_placement_bbox",
                       "generator": row.get("generator")},
        })
    return instances


def generate(
    manifest: Path, output: Path, *, class_map: Path | None = None,
    neca_root: Path | None = None, reserved: Path | None = None,
    copies: int = 8, empty_fraction: float = .30,
    max_symbols: int = 32, seed: int = 2026, size: int = 512,
    positive_retries: int = 4,
) -> dict:
    if output.exists() and any(output.iterdir()):
        raise FileExistsError(f"refusing to overwrite nonempty output directory: {output}")
    if size < 128 or max_symbols < 1 or max_symbols > 128 or positive_retries < 1:
        raise ValueError("invalid crop size, symbol limit, or retry count")
    classes = _classes(class_map)
    sources = _read_sources(manifest, classes)  # check all attestations before writing
    if reserved is not None:
        held = json.loads(reserved.read_text())
        if not isinstance(held, dict):
            raise ValueError("reserved source manifest must be an object")
        held_paths = held.get("source_pdfs", held.get("pdfs", []))
        held_groups = held.get("project_family_ids", held.get("groups", []))
        if not isinstance(held_paths, list) or not isinstance(held_groups, list):
            raise ValueError("reserved source lists must be arrays")
        reserved_groups = set(held_groups)
        reserved_paths = [Path(s).expanduser() for s in held_paths]
        for entry, path, is_pdf in sources:
            if entry["project_family_id"] in reserved_groups:
                raise ValueError(f"held-out project group used in phase-two training: {path}")
            if is_pdf:
                # Indexers typically list source_pdfs relative to the PDF
                # collection root, not relative to the reserved JSON file.
                # Match full relative path suffix as well as absolute paths.
                # This can over-exclude same-name files, never under-exclude.
                for reserved_pdf in reserved_paths:
                    if (reserved_pdf.is_absolute() and path == reserved_pdf.resolve()) or (
                        not reserved_pdf.is_absolute() and
                        (path == (reserved.parent / reserved_pdf).resolve() or
                         path.parts[-len(reserved_pdf.parts):] == reserved_pdf.parts)
                    ):
                        raise ValueError(f"held-out PDF used for phase-two training: {path}")
    neca = NecaSymbolBank(neca_root) if neca_root is not None else None
    counters = Counter({
        "positive_views": 0, "negative_views": 0,
        "inserted_symbols": 0, "total_views": 0, "pdf_views": 0,
        "skipped_unplaceable_positive": 0, "skipped_duplicate_raster": 0,
    })
    seen_images: set[str] = set()
    output.mkdir(parents=True, exist_ok=True)
    sampler = PdfCropSampler()
    rng = np.random.default_rng(seed)
    try:
        for entry, path, is_pdf in sources:
            group = entry["project_family_id"]
            source_id = hashlib.sha256(
                f"{path}\n{entry.get('pdf_page_index', '')}\n{group}".encode()
            ).hexdigest()[:14]
            raster = None if is_pdf else _load_raster(path)
            modes = _modes(copies, empty_fraction, rng)
            for copy, is_empty in enumerate(modes):
                view_seed = int(rng.integers(0, 2 ** 63))
                view_rng = np.random.default_rng(view_seed)
                if is_pdf:
                    background, crop_info = sampler.sample(
                        {"source_pdf": str(path), "pdf_page_index": entry["pdf_page_index"]},
                        size, view_rng,
                    )
                    dpi = crop_info["effective_dpi"]
                else:
                    background, crop_info = _raster_crop(raster, size, view_rng)
                    dpi = None  # an arbitrary raster's physical DPI is not known
                requested = 0
                placements = []
                page = background.copy()
                if not is_empty:
                    for _ in range(positive_retries):
                        requested = int(view_rng.integers(1, max_symbols + 1))
                        page, result = compose_ssl_symbols(
                            background, view_rng, neca_bank=neca,
                            max_symbols=max_symbols, requested_symbols=requested,
                            drawing_dpi=dpi,
                        )
                        placements = result["placements"]
                        if placements:
                            break
                    if not placements:
                        counters["skipped_unplaceable_positive"] += 1
                        continue  # never relabel a failed positive as a deliberate negative
                instances = _instances(placements, classes, size)
                if is_empty and instances:
                    raise RuntimeError("verified negative unexpectedly has generated instances")
                mode = str(view_rng.choice(DAMAGE_MODES, p=DAMAGE_WEIGHTS))
                final, damage = make_variant(page, view_rng, mode)
                digest = hashlib.sha256(final.tobytes()).hexdigest()
                if digest in seen_images:
                    counters["skipped_duplicate_raster"] += 1
                    continue
                seen_images.add(digest)
                stem = f"wall_{source_id}_{copy:04d}"
                image_path = output / f"{stem}.png"
                if not cv2.imwrite(str(image_path), final):
                    raise OSError(f"cannot save {image_path}")
                annotation = {
                    "document_id": stem,
                    "source_type": "synthetic_wall_only" if instances else "verified_wall_only_negative",
                    "image_path": image_path.name,
                    "image_width": size, "image_height": size,
                    "project_family_id": group,
                    "source_group_verified": True,
                    "wall_only_background_verified": True,
                    "synthetic_targets_only": True,
                    "verified_negative_families": sorted(set(entry["verified_negative_families"])),
                    "exhaustive_families": sorted(classes),
                    "instances": instances,
                    "composite": {
                        "source_path": str(path), "pdf_page_index": entry.get("pdf_page_index"),
                        "source_crop": crop_info,
                        "mode": "inserted_symbols" if instances else "empty_negative",
                        "requested_symbols": requested,
                        "inserted_symbols": len(instances),
                        "seed": view_seed, "degradation": damage,
                    },
                }
                annotation["image_sha256"] = hashlib.sha256(image_path.read_bytes()).hexdigest()
                image_path.with_suffix(".json").write_text(json.dumps(annotation, indent=2))
                counters["positive_views" if instances else "negative_views"] += 1
                counters["inserted_symbols"] += len(instances)
                counters["total_views"] += 1
                if is_pdf:
                    counters["pdf_views"] += 1
    finally:
        sampler.close()
    if not counters["positive_views"] and empty_fraction < 1:
        raise ValueError("no synthetic positive views could be produced; inspect symbol placement")
    if not counters["negative_views"] and empty_fraction > 0:
        raise ValueError("no verified negative views survived; inspect sources/duplicate filtering")
    report = {
        "source_count": len(sources),
        "project_groups": len({e["project_family_id"] for e, _, _ in sources}),
        "active_target_families": sorted(classes),
        "counts": dict(counters),
        "seed": seed, "copies": copies, "empty_fraction": empty_fraction,
        "training_performed": False,
        "note": "Only generated placements are targets. Empty negatives require source-level "
                "verification of all active classes. Generated crops retain source project groups.",
    }
    (output / "generation_report.json").write_text(json.dumps(report, indent=2))
    return report


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("manifest", type=Path)
    p.add_argument("output", type=Path)
    p.add_argument("--class-map", type=Path, help="active detector family map or list")
    p.add_argument("--neca-root", type=Path, help="optional NECA SVG symbols.json library")
    p.add_argument("--reserved", type=Path, help="reserved PDF/project manifest to reject")
    p.add_argument("--copies", type=int, default=8)
    p.add_argument("--empty-fraction", type=float, default=.30)
    p.add_argument("--max-symbols", type=int, default=32)
    p.add_argument("--seed", type=int, default=2026)
    p.add_argument("--size", type=int, default=512)
    p.add_argument("--positive-retries", type=int, default=4)
    a = p.parse_args()
    print(json.dumps(generate(a.manifest.resolve(), a.output.resolve(),
        class_map=a.class_map, neca_root=a.neca_root, reserved=a.reserved,
        copies=a.copies, empty_fraction=a.empty_fraction,
        max_symbols=a.max_symbols, seed=a.seed, size=a.size,
        positive_retries=a.positive_retries), indent=2))


if __name__ == "__main__":
    main()
