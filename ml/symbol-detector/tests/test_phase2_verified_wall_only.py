"""Phase-two wall-only supervision: only inserted glyphs are target instances."""
from __future__ import annotations

import json

import cv2
import numpy as np
import pytest

from blueprint_symbols.neca_svg_compositor import CANONICAL_CLASSES
from blueprint_symbols.raster_training import read_records
from scripts_generate_phase2_wall_only import _modes, generate


def _wall_source(tmp_path):
    image = np.full((768, 768, 3), 247, np.uint8)
    for x in (72, 160, 310, 505, 675):
        cv2.line(image, (x, 30), (x, 735), (26, 27, 30), 3)
    for y in (110, 415, 650):
        cv2.line(image, (32, y), (735, y), (26, 27, 30), 3)
    image_path = tmp_path / "walls.png"
    assert cv2.imwrite(str(image_path), image)
    return image_path


def _write_manifest(tmp_path, *, families=None, project="verified-building"):
    src = _wall_source(tmp_path)
    entry = {
        "image_path": src.name,
        "project_family_id": project,
        "source_group_verified": True,
        "verified_negative_families": list(families if families is not None else CANONICAL_CLASSES),
        # This SSL-specific tag, by itself, must never be interpreted as a
        # detector label-completeness attestation.
        "negative_control_kind": "walls_only",
    }
    manifest = tmp_path / "verified.json"
    manifest.write_text(json.dumps({"sources": [entry]}))
    return manifest


def test_wall_only_positive_and_empty_view_targets_are_distinct(tmp_path):
    manifest = _write_manifest(tmp_path)
    output = tmp_path / "phase2"
    report = generate(manifest, output, copies=4, empty_fraction=.5, max_symbols=6,
                      seed=12345, positive_retries=6)
    assert report["counts"]["positive_views"] >= 1
    assert report["counts"]["negative_views"] >= 1
    assert report["counts"]["inserted_symbols"] > 0
    docs = [json.loads(p.read_text()) for p in output.glob("wall_*.json")]
    assert len(docs) == report["counts"]["total_views"]
    assert all(doc["project_family_id"] == "verified-building" for doc in docs)
    assert all(doc["synthetic_targets_only"] and doc["wall_only_background_verified"] for doc in docs)
    assert all(set(doc["exhaustive_families"]) == set(CANONICAL_CLASSES) for doc in docs)
    assert all(len(doc["instances"]) == doc["composite"]["inserted_symbols"] for doc in docs)
    positives = [doc for doc in docs if doc["composite"]["mode"] == "inserted_symbols"]
    negatives = [doc for doc in docs if doc["composite"]["mode"] == "empty_negative"]
    assert positives and negatives
    for doc in positives:
        assert doc["instances"]
        assert doc["composite"]["requested_symbols"] >= len(doc["instances"])
        for item in doc["instances"]:
            assert item["source"]["kind"] == "synthetic_composite"
            assert item["source"]["label_method"] == "generated_placement_bbox"
            assert item["symbol_family"] in CANONICAL_CLASSES
            x0, y0, x1, y1 = item["bbox_xyxy"]
            assert 0 <= x0 < x1 <= 512 and 0 <= y0 < y1 <= 512
    for doc in negatives:
        assert doc["instances"] == []
        assert doc["composite"]["requested_symbols"] == 0
        assert doc["composite"]["inserted_symbols"] == 0
        assert doc["source_type"] == "verified_wall_only_negative"

    # The regular manifest/export path consumes the generated JSON directly.
    records = read_records([p for p in output.glob("wall_*.json")])
    assert all(rec.wall_only_background_verified for rec in records)


def test_incomplete_or_merely_ssl_negative_metadata_is_rejected(tmp_path):
    manifest = _write_manifest(tmp_path, families=["receptacle"])
    with pytest.raises(ValueError, match="not verified negative"):
        generate(manifest, tmp_path / "out", copies=2)
    data = json.loads(manifest.read_text())
    del data["sources"][0]["verified_negative_families"]
    manifest.write_text(json.dumps(data))
    with pytest.raises(ValueError, match="verified_negative_families"):
        generate(manifest, tmp_path / "out2", copies=2)
    assert not (tmp_path / "out").exists()


def test_unverified_source_group_rejected(tmp_path):
    manifest = _write_manifest(tmp_path)
    doc = json.loads(manifest.read_text())
    doc["sources"][0]["source_group_verified"] = False
    manifest.write_text(json.dumps(doc))
    with pytest.raises(ValueError, match="verified project_family_id"):
        generate(manifest, tmp_path / "out")


def test_fixed_rng_distribution_keeps_both_modes_for_reusable_sources():
    for seed in range(30):
        modes = _modes(4, .30, np.random.default_rng(seed))
        assert len(modes) == 4 and any(modes) and not all(modes)
    assert _modes(2, 1., np.random.default_rng(5)) == [True, True]
    assert _modes(2, 0., np.random.default_rng(5)) == [False, False]


def test_positive_zero_placement_never_silently_becomes_negative(tmp_path, monkeypatch):
    import scripts_generate_phase2_wall_only as script

    manifest = _write_manifest(tmp_path)
    def fake_no_placements(img, rng, **kwargs):
        return img.copy(), {"placements": []}
    monkeypatch.setattr(script, "compose_ssl_symbols", fake_no_placements)
    with pytest.raises(ValueError, match="no synthetic positive"):
        generate(manifest, tmp_path / "out", copies=2, empty_fraction=0.,
                 max_symbols=3, positive_retries=2)


def test_negative_only_has_no_inserted_symbols(tmp_path):
    manifest = _write_manifest(tmp_path)
    output = tmp_path / "allneg"
    report = generate(manifest, output, copies=3, empty_fraction=1.,
                      max_symbols=5, seed=41)
    assert report["counts"]["positive_views"] == 0
    assert report["counts"]["negative_views"] > 0
    for path in output.glob("wall_*.json"):
        doc = json.loads(path.read_text())
        assert doc["instances"] == []


def test_pdf_wall_only_crop_keeps_renderer_dpi_and_empty_branch(tmp_path):
    fitz = pytest.importorskip("fitz")
    src = tmp_path / "verified_walls.pdf"
    pdf = fitz.open()
    page = pdf.new_page(width=1300, height=900)
    for x in (150, 350, 700, 1020):
        page.draw_line((x, 60), (x, 820), color=(0, 0, 0), width=2)
    for y in (150, 500, 800):
        page.draw_line((80, y), (1200, y), color=(0, 0, 0), width=2)
    pdf.save(src)
    pdf.close()
    manifest = tmp_path / "verified_pdf.json"
    manifest.write_text(json.dumps({"sources": [{
        "source_pdf": src.name,
        "pdf_page_index": 0,
        "project_family_id": "pdf-group",
        "source_group_verified": True,
        "verified_negative_families": list(CANONICAL_CLASSES),
    }]}))
    output = tmp_path / "pdf_crops"
    r = generate(manifest, output, copies=2, empty_fraction=.5,
                 max_symbols=4, positive_retries=5, seed=23)
    assert r["counts"]["pdf_views"] == r["counts"]["total_views"]
    docs = [json.loads(p.read_text()) for p in output.glob("wall_*.json")]
    assert docs
    assert all(100 <= doc["composite"]["source_crop"]["effective_dpi"] <= 400
               for doc in docs)


def test_export_negative_sampling_prefers_verified_wall_ink(tmp_path):
    # Validating the exporter policy independent of the glyph compositor.
    from blueprint_symbols.dataset_manifest import build_manifest
    from scripts_export_yolo_tiles import run
    anns = []
    for i in range(4):
        im = np.full((512, 512, 3), 240, np.uint8)
        for x in range(35, 490, 48):
            cv2.line(im, (x, 40), (x, 470), (0, 0, 0), 3)
        cv2.putText(im, str(i), (90, 105), cv2.FONT_HERSHEY_SIMPLEX,
                    1, (0, 0, 0), 2)
        img = tmp_path / f"sheet{i}.png"
        assert cv2.imwrite(str(img), im)
        positive = i == 0
        doc = {
            "image_path": img.name, "image_width": 512, "image_height": 512,
            "project_family_id": f"independent-project-{i}",
            "source_group_verified": True,
            "exhaustive_families": ["receptacle"],
            "wall_only_background_verified": not positive,
            "synthetic_targets_only": not positive,
            "instances": ([{"instance_id": "1", "symbol_family": "receptacle",
                           "bbox_xyxy": [250, 250, 280, 280]}] if positive else []),
        }
        ann = tmp_path / f"sheet{i}.json"
        ann.write_text(json.dumps(doc))
        anns.append(ann)
    mf = build_manifest(anns, val_fraction=.25, test_fraction=.25)
    mf_path = tmp_path / "sources.json"
    mf_path.write_text(json.dumps(mf))
    report = run(mf_path, tmp_path / "prepared", samples=4, train_variants=1,
                 train_negative_fraction=0., verified_wall_negative_fraction=1.,
                 blank_negative_fraction=0., verified_wall_blank_fraction=1.,
                 eval_empty_keep_fraction=1., eval_blank_keep_fraction=1.)
    assert report["counts"]["train_images"] > 0
    assert report["verified_wall_negative_fraction"] == 1.
    assert report["unverified_annotation_completeness"] == []


def test_verified_wall_negative_requires_literal_boolean_and_family_list(tmp_path):
    manifest = _write_manifest(tmp_path)
    source = json.loads(manifest.read_text())
    base = source['sources'][0]
    for key, value in [
        ('source_group_verified', 'true'),
        ('calibration_verified', 'true'),
        ('verified_negative_families', ['receptacle', 42]),
        ('verified_negative_families', ['receptacle', {'unexpected': 'object'}]),
    ]:
        src = dict(base, **{key: value})
        manifest.write_text(json.dumps({'sources': [src]}))
        with pytest.raises(ValueError):
            generate(manifest, tmp_path / 'should-not-create', copies=2)
    # A truthy string in an old JSON must never upgrade a crop into a
    # verified hard negative merely because its other fields are present.
    image = _wall_source(tmp_path)
    ann = tmp_path / 'candidate.json'
    ann.write_text(json.dumps({
        'image_path': image.name, 'image_width': 768, 'image_height': 768,
        'instances': [], 'wall_only_background_verified': 'true',
        'synthetic_targets_only': 'true',
        'exhaustive_families': [],
    }))
    assert not read_records([ann])[0].wall_only_background_verified
