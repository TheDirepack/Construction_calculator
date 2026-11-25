"""Composite backgrounds from the image datasets, for post-training.

Two kinds, both found at runtime in the data folders:

* **walls only** (proven symbol-free): ArchCAD / FloorPlanCAD walls drawn from
  their per-line labels (vector_walls.py) and the rendered wall layouts
  (adapters/render_layouts.py). Every symbol on a composite made from them is
  one we placed, so the page is completely labelled: everything else is a
  true negative. Each page gets a fresh drawing style and line-work mutation.
* **other dataset images** (ArchCAD and FloorPlanCAD rasters, CubiCasa5K
  floors): may contain symbols, so composites stay positive-only, unless the
  image is listed in ``<data root>/symbol_free_images.txt`` (one path per
  line, relative to the data root; ``#`` starts a comment), flagged by hand.

The real training drawings should stay the dominant background source.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

import cv2
import numpy as np

from .label_schema import completeness, page_header

SYMBOL_FREE_LIST = 'symbol_free_images.txt'


def read_symbol_free_list(data_root: Path, path: Path | None = None) -> set[str]:
    """Hand-flagged symbol-free images, as resolved absolute paths."""
    path = path or data_root / SYMBOL_FREE_LIST
    if not path.is_file():
        return set()
    out = set()
    for line in path.read_text().splitlines():
        line = line.split('#', 1)[0].strip()
        if line:
            p = Path(line)
            out.add(str((p if p.is_absolute() else data_root / p).resolve()))
    return out


def vector_wall_sources(data_root: Path) -> list[dict]:
    ds = data_root / '5_datasets'
    out = [{'image_path': str(p), 'vector_walls': str(p), 'format': 'archcad'}
           for p in sorted((ds / 'archcad_400k' / 'extracted' / 'json').rglob('*.json'))]
    out += [{'image_path': str(p), 'vector_walls': str(p), 'format': 'floorplancad'}
            for p in sorted((ds / 'floorplancad_full' / 'extracted').glob('*.svg'))]
    return out


def dataset_images(data_root: Path) -> list[Path]:
    ds = data_root / '5_datasets'
    return (sorted((ds / 'archcad_400k' / 'extracted' / 'png').rglob('*.png'))
            + sorted((ds / 'floorplancad_full' / 'extracted').glob('*.png'))
            + sorted((ds / 'cubicasa5k_official').rglob('F*_scaled.png')))


def _write_page(out_dir: Path, stem: str, img: np.ndarray, *, source: Path,
                source_type: str, symbol_free: bool, reason: str) -> Path:
    img_path = out_dir / f'{stem}.png'
    if not cv2.imwrite(str(img_path), img):
        raise OSError(img_path)
    doc = page_header(source=source, document_id=stem, page_index=0, source_type=source_type,
                      image_path=img_path.name,
                      image_sha256=hashlib.sha256(img_path.read_bytes()).hexdigest(),
                      width=img.shape[1], height=img.shape[0])
    doc.update(completeness(verified_no_symbols=symbol_free))
    doc['symbol_free_reason'] = reason if symbol_free else None
    json_path = out_dir / f'{stem}.json'
    json_path.write_text(json.dumps(doc, indent=1))
    return json_path


def walls_only_backgrounds(data_root: Path, out_dir: Path, count: int, *, seed: int,
                           layouts_dir: Path | None = None) -> list[Path]:
    """``count`` proven symbol-free pages, each freshly styled and mutated:
    walls drawn from labelled CAD lines, and (if given) rendered wall layouts."""
    from .ssl_augmentation import mutate_linework
    from .ssl_pretrain import load_bgr_white_bg
    from .vector_walls import render_vector_walls

    vectors = vector_wall_sources(data_root)
    layouts = sorted(layouts_dir.glob('*.json')) if layouts_dir and layouts_dir.is_dir() else []
    if not vectors and not layouts:
        return []
    rng = np.random.default_rng(seed)
    out_dir.mkdir(parents=True, exist_ok=True)
    written: list[Path] = []
    tries = 0
    while len(written) < count and tries < count * 10:
        tries += 1
        use_layout = bool(layouts) and (not vectors or rng.random() < .35)
        if use_layout:
            jpath = layouts[int(rng.integers(len(layouts)))]
            try:
                img = load_bgr_white_bg(jpath.parent / json.loads(jpath.read_text())['image_path'])
            except (OSError, ValueError, KeyError):
                continue
            source, kind, reason = jpath, 'walls_only_layout', 'rendered wall layout (walls and openings only)'
        else:
            entry = vectors[int(rng.integers(len(vectors)))]
            try:
                img = render_vector_walls(entry, rng)
            except (OSError, ValueError, KeyError):
                img = None
            source = Path(entry['vector_walls'])
            kind = 'walls_only_cad'
            reason = f"{entry['format']} structure lines only (walls, columns, windows, grid)"
        if img is None:
            continue
        img, _ = mutate_linework(img, rng)
        stem = f'walls_{len(written):05d}_{source.stem[:40]}'
        written.append(_write_page(out_dir, stem, img, source=source, source_type=kind,
                                   symbol_free=True, reason=reason))
    print(f'Walls-only backgrounds: {len(written)} ({len(vectors)} CAD slices, '
          f'{len(layouts)} layouts available)', flush=True)
    return written


def other_dataset_backgrounds(data_root: Path, out_dir: Path, count: int, *, seed: int,
                              symbol_free: set[str] | None = None) -> list[Path]:
    """``count`` random dataset images as backgrounds: positive-only unless
    hand-flagged symbol-free."""
    from .ssl_pretrain import load_bgr_white_bg

    images = dataset_images(data_root)
    if not images:
        return []
    flagged = symbol_free or set()
    rng = np.random.default_rng(seed)
    out_dir.mkdir(parents=True, exist_ok=True)
    picks = rng.choice(len(images), size=min(count, len(images)), replace=False)
    written = []
    for n, i in enumerate(sorted(int(i) for i in picks)):
        path = images[i]
        img = load_bgr_white_bg(path)
        if img is None or min(img.shape[:2]) < 256:
            continue
        is_free = str(path.resolve()) in flagged
        stem = f'dataset_{n:05d}_{path.parent.name[:20]}_{path.stem[:40]}'
        written.append(_write_page(out_dir, stem, img, source=path, source_type='dataset_image',
                                   symbol_free=is_free, reason='flagged in ' + SYMBOL_FREE_LIST))
    print(f'Other dataset backgrounds: {len(written)} '
          f'({sum(1 for p in written if json.loads(p.read_text())["verified_no_symbols"])} hand-flagged symbol-free)',
          flush=True)
    return written
