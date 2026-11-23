"""Pretraining preview and actual symbol-count audit smoke test."""
import json

import cv2
import numpy as np
import pytest

pytest.importorskip("torch")
from scripts_preview_pretrain_ssl import preview


def test_preview_creates_aligned_pairs_and_count_audit(tmp_path):
    image = np.full((700, 900, 3), 246, dtype=np.uint8)
    cv2.rectangle(image, (35, 35), (865, 665), (30, 30, 30), 3)
    cv2.line(image, (420, 35), (420, 665), (30, 30, 30), 2)
    source = tmp_path / "page.png"
    assert cv2.imwrite(str(source), image)
    manifest = {
        "kind": "blueprint_ssl_train",
        "buckets": {"real": {"weight": 1.0, "groups": {"project_one": [str(source)]}}},
    }
    manifest_path = tmp_path / "train_manifest.json"
    manifest_path.write_text(json.dumps(manifest))
    out = tmp_path / "previews"
    report = preview(manifest_path, out, samples=5, max_symbols=16, seed=42)
    assert report["samples"] == 5
    assert report["placement_shortfall"] == report["requested_total"] - report["placed_total"]
    assert report["placed_total"] <= report["requested_total"] <= 5 * 16
    assert len(report["rows"]) == 5
    assert (out / "preview_report.json").is_file()
    for i in range(5):
        path = out / f"sample_{i:04d}.png"
        img = cv2.imread(str(path))
        assert img is not None and img.shape == (512, 1024, 3)
        assert 0 <= report["rows"][i]["mask_fraction"] <= 1
