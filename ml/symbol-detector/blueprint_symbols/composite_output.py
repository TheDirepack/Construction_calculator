"""Collision-resistant provenance keys for synthetic blueprint composite outputs.

Different source folders often contain same-named page JSONs (e.g. page_0001).
Output names and augmentation seeds must depend on the original source path,
not just its basename. Require a fresh output directory to avoid overwriting
previously exported labels and images on reruns.
"""
from __future__ import annotations

import hashlib
from pathlib import Path


def _key(source: Path) -> str:
    return str(Path(source).resolve())


def source_stem(source: Path, kind: str, copy: int) -> str:
    """Stable within a source collection; distinct across same-named folders."""
    if kind not in ('el', 'neca') or copy < 0:
        raise ValueError('invalid composite kind or copy')
    identity = hashlib.sha256(_key(source).encode('utf-8')).hexdigest()[:20]
    return f'{Path(source).stem}_{identity}_{kind}{copy}'


def source_seed(source: Path, *, namespace: str, seed: int, copy: int) -> int:
    if not namespace or copy < 0:
        raise ValueError('invalid composite seed arguments')
    material = f'{namespace}\0{seed}\0{_key(source)}\0{copy}'
    return int.from_bytes(hashlib.sha256(material.encode('utf-8')).digest()[:8], 'big')


def create_output_dir(path: Path) -> None:
    """Atomic exclusivity: never overwrite an earlier compositing run."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.mkdir(exist_ok=False)
