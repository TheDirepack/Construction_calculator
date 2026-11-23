"""Self-hosted SAM proposal precision thresholds, configurable per device family.

Thresholds are empirical *filters*, not calibrated likelihoods. Prefer a small
fully reviewed validation subset when tuning; don't tune on partially labeled
whole pages (unknown objects cannot be assumed negatives).
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any


def validate_family_thresholds(data: Any, known_families: set[str]) -> dict[str, dict[str, float]]:
    if not isinstance(data, dict):
        raise ValueError('family threshold settings must be a JSON object')
    extra = set(data) - known_families
    if extra:
        raise ValueError(f'unknown family thresholds: {sorted(extra)}')
    result: dict[str, dict[str, float]] = {}
    for family, values in data.items():
        if not isinstance(values, dict) or not values:
            raise ValueError(f'{family}: expected {{"coarse": ..., "fine": ...}}')
        unknown_stages = set(values) - {'coarse', 'fine'}
        if unknown_stages:
            raise ValueError(f'{family}: unknown stages {sorted(unknown_stages)}')
        item = {}
        for stage, value in values.items():
            if type(value) not in (int, float) or not 0 < value <= 1:
                raise ValueError(f'{family}.{stage}: confidence threshold must be in (0,1]')
            item[stage] = float(value)
        result[family] = item
    return result


def load_family_thresholds(path: Path | None, known_families: set[str]):
    if path is None:
        return {}
    return validate_family_thresholds(json.loads(Path(path).read_text()), known_families)


def threshold_for_family(
    family: str, stage: str, base: float,
    overrides: dict[str, dict[str, float]],
) -> float:
    return overrides.get(family, {}).get(
        'coarse' if stage == 'coarse' else 'fine', base,
    )
