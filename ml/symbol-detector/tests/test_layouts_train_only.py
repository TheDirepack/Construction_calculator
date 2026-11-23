"""Ensure train-only CAD/layout rendering cannot draw validation/test records."""
from __future__ import annotations

import json
import sys

import cv2
import numpy as np

from adapters import render_layouts


def test_train_only_layout_generation(tmp_path, monkeypatch):
    data = tmp_path / "datasets"
    for dataset in ("resplan", "cubicasa5k_hf"):
        root = data / dataset
        root.mkdir(parents=True)
        for split in ("train", "val", "test"):
            record = {
                "id": f"{dataset}_{split}",
                "frame": 1024,
                "gt": {"walls": [], "rooms": []},
            }
            (root / f"{split}.jsonl").write_text(json.dumps(record) + "\n")

    # No need for thousands of pixels to assert the input-split boundary.
    monkeypatch.setattr(
        render_layouts, "render_wall_first",
        lambda _doc, _rng: np.full((96, 96, 3), 255, dtype=np.uint8),
    )
    output = tmp_path / "out"
    monkeypatch.setattr(sys, "argv", [
        "render_layouts.py", str(data), str(output),
        "--train-only", "--limit", "10",
    ])
    render_layouts.main()
    output_names = {p.stem for p in output.glob("*.json")}
    assert len(output_names) == 2
    assert output_names == {"resplan_train", "cubicasa5k_hf_train"}
    assert not any("val" in name or "test" in name for name in output_names)


def test_layout_rendering_keeps_existing_defaults(tmp_path, monkeypatch):
    data = tmp_path / "datasets"
    root = data / "resplan"
    root.mkdir(parents=True)
    for split in ("train", "val", "test"):
        (root / f"{split}.jsonl").write_text(
            json.dumps({"id": f"r_{split}", "gt": {"walls": []}}) + "\n"
        )
    monkeypatch.setattr(
        render_layouts, "render_wall_first",
        lambda _doc, _rng: np.full((96, 96, 3), 255, dtype=np.uint8),
    )
    output = tmp_path / "out"
    monkeypatch.setattr(sys, "argv", ["render_layouts.py", str(data), str(output)])
    render_layouts.main()
    assert {p.stem for p in output.glob("*.json")} == {"r_train", "r_val", "r_test"}
