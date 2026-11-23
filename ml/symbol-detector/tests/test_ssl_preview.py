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
    assert report["distinct_inserted_variants"] >= 0
    assert sum(report["degradation_by_severity"].values()) == 5
    assert sum(report["page_warp_severity"].values()) == 5
    assert isinstance(report["page_warp_operations"], dict)
    assert isinstance(report["degradation_by_category"], dict)
    assert report["eligible_neca_svg_variants"] == 0  # procedural-only fixture
    assert sum(report["placed_by_family"].values()) == report["placed_total"]
    assert (out / "preview_report.json").is_file()
    for i in range(5):
        path = out / f"sample_{i:04d}.png"
        img = cv2.imread(str(path))
        assert img is not None and img.shape == (512, 1024, 3)
        overlay = cv2.imread(str(out / f"sample_{i:04d}_overlay.png"))
        assert overlay is not None and overlay.shape == (512, 512, 3)
        geometry = cv2.imread(str(out / f"sample_{i:04d}_geometry.png"))
        assert geometry is not None and geometry.shape == (512, 1024, 3)
        # Geometry pair includes the transformed clean page on the right.
        assert np.array_equal(geometry[:, 512:, :], img[:, :512, :])
        placements = report["rows"][i]["symbol_stats"]["placements"]
        assert len(placements) == report["rows"][i]["symbol_stats"]["placed"]
        for placed in placements:
            assert placed["symbol_family"]
            assert placed["symbol_variant"]
            assert len(placed["bbox_xyxy"]) == 4
        assert 0 <= report["rows"][i]["mask_fraction"] <= 1



def test_real_vector_svg_catalog_through_ssl_preview(tmp_path):
    """Exercise CairoSVG -> NECA bank -> compositor -> preview coverage."""
    bank = tmp_path / "bank"
    bank.mkdir()
    (bank / "duplex.svg").write_text(
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">'
        '<circle cx="32" cy="32" r="22" fill="none" stroke="black" stroke-width="5"/>'
        '<line x1="12" y1="32" x2="52" y2="32" stroke="black" stroke-width="4"/>'
        '</svg>'
    )
    (bank / "symbols.json").write_text(json.dumps({
        "symbols": [{
            "id": "duplex_vector", "file": "duplex.svg",
            "section": "Outlets and Receptacles",
            "name": "Duplex Receptacle", "code": "1.01",
        }],
    }))
    image = np.full((700, 700, 3), 250, dtype=np.uint8)
    cv2.rectangle(image, (80, 80), (620, 620), (0, 0, 0), 3)
    source = tmp_path / "clean.png"
    assert cv2.imwrite(str(source), image)
    manifest = {
        "buckets": {"verified": {"weight": 1.0,
                                 "groups": {"project": [str(source)]}}},
    }
    manifest_file = tmp_path / "manifest.json"
    manifest_file.write_text(json.dumps(manifest))
    audit = preview(manifest_file, tmp_path / "out", neca_dir=bank,
                    max_symbols=16, samples=15, seed=703)
    assert audit["eligible_neca_svg_variants"] == 1
    assert audit["neca_svg_variants_seen"] == 1
    assert audit["neca_svg_variant_coverage"] == 1.0
    assert audit["placed_total"] > 0
    assert audit["placed_total"] <= audit["requested_total"]
