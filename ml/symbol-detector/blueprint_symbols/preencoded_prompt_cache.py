"""Self-hosted, content-addressed cache of SAM 3.1 NECA prompt embeddings.

CairoSVG rasterizing and geometry-encoding hundreds of references every run is
slow. Cache CPU copies keyed to model revision/config, source alpha bytes,
prompt order and render settings. Never load arbitrary Python pickles.
"""
from __future__ import annotations

import hashlib
import json
import os
import pickle
from pathlib import Path
from typing import Any

import torch

_CACHE_SCHEMA = 2


def prompt_feature_cache_path(
    root: Path, glyphs: list[Any], *, model_id: str,
    model_revision: str | None, model_config: str, dtype: torch.dtype,
    target_px: int,
) -> Path:
    refs = [
        {
            'id': g.symbol_id,
            'ink_sha256': hashlib.sha256(g.alpha.tobytes()).hexdigest(),
        }
        for g in glyphs
    ]
    fingerprint = {
        'schema': _CACHE_SCHEMA,
        'model_id': model_id,
        'revision': model_revision,
        'config': model_config,
        'dtype': str(dtype),
        'target_px': target_px,
        'references': refs,
    }
    key = hashlib.sha256(
        json.dumps(fingerprint, sort_keys=True).encode('utf-8')
    ).hexdigest()
    return Path(root) / (key + '.pt')


def load_prompt_features(
    path: Path, *, glyph_count: int, device: str,
    dtype: torch.dtype,
) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor] | None:
    if not path.is_file():
        return None
    # weights_only is mandatory even though the cache is local.
    try:
        stored = torch.load(str(path), map_location='cpu', weights_only=True)
        if (not isinstance(stored, dict)
                or stored.get('schema') != _CACHE_SCHEMA):
            return None
        features = stored.get('features')
        if not isinstance(features, (tuple, list)) or len(features) != 4:
            return None
        geo, gm, txt, tm = features
        if (not all(isinstance(t, torch.Tensor) for t in features)
                or geo.ndim != 3 or geo.shape[0] != glyph_count
                or gm.shape != geo.shape[:2]
                or txt.ndim != 3 or txt.shape[0] != 1
                or tm.shape != txt.shape[:2]):
            return None
        return (
            geo.to(device=device, dtype=dtype),
            gm.to(device=device, dtype=torch.bool),
            txt.to(device=device, dtype=dtype),
            tm.to(device=device, dtype=torch.bool),
        )
    except (OSError, ValueError, RuntimeError, TypeError, EOFError,
            pickle.UnpicklingError, KeyError, AttributeError):
        return None


def save_prompt_features(
    path: Path,
    tensors: tuple[torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor],
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    saved = [t.detach().to('cpu').contiguous() for t in tensors]
    tmp = path.with_name(f'{path.stem}.{os.getpid()}.tmp.pt')
    torch.save({'schema': _CACHE_SCHEMA, 'features': saved}, str(tmp))
    tmp.replace(path)
