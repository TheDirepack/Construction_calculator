"""Full-PDF native crop ingestion regression tests (no full-page PNG staging)."""
from __future__ import annotations

import json
from pathlib import Path

import cv2
import numpy as np
import pytest

fitz = pytest.importorskip("fitz")

from blueprint_symbols.pdf_crop import PdfCropSampler
from blueprint_symbols.ssl_pretrain import BlueprintSSLDataset, group_is_holdout
from scripts_index_real_pdfs import index_pdf_pages, reserved_sources
from scripts_pretrain_ssl import build_pretrain_manifests


ROLES = Path(__file__).resolve().parents[1] / "class_maps" / "page_roles.json"


def make_pdf(path: Path, pages: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    doc = fitz.open()
    for i in range(pages):
        page = doc.new_page(width=900, height=650)
        page.draw_rect(fitz.Rect(40, 40, 850, 590), color=(0, 0, 0), width=2)
        page.draw_line((100 + (i % 17) * 15, 40),
                       (100 + (i % 17) * 15, 590), color=(0, 0, 0), width=2)
        page.draw_rect(fitz.Rect(100, 100, 290, 320),
                       color=(0, 0, 0), width=1.2)
        page.draw_circle(fitz.Point(620, 365), 34, color=(0, 0, 0), width=2)
        page.insert_text((120, 180), f"ELECTRICAL SHEET {i+1} RCP",
                         fontsize=13, color=(0, 0, 0))
    doc.save(path)
    doc.close()


def test_index_all_pages_and_exclude_reserved_pdfs(tmp_path):
    root = tmp_path / "data"
    src = root / "1_blueprints" / "new_downloads_202611_site" / "drawing.pdf"
    held = root / "1_blueprints" / "new_downloads_202611_site" / "reserved.pdf"
    make_pdf(src, 83)
    make_pdf(held, 7)
    heldout = tmp_path / "work" / "heldout_real_pdfs.json"
    heldout.parent.mkdir(parents=True)
    heldout.write_text(json.dumps({
        "source_pdfs": [str(held.relative_to(root))],
        "project_family_ids": [],
    }))
    out = tmp_path / "work" / "pdf_page_index" / "index.json"
    first = index_pdf_pages(root, out, roles=ROLES, reserved_manifest=heldout)
    assert first["stats"]["indexed_pdfs"] == 1
    assert first["stats"]["indexed_pages"] == 83
    assert first["stats"]["max_pages_in_pdf"] == 83
    assert first["stats"]["excluded_reserved_pdfs"] == 1
    assert len(first["documents"]) == 1
    doc = next(iter(first["documents"].values()))
    assert {p["pdf_page_index"] for p in doc["pages"]} == set(range(83))
    assert all(p["source_pdf"] == str(src.resolve()) for p in doc["pages"])
    assert not list((tmp_path / "work").rglob("*.png"))  # no full-page rasters

    second = index_pdf_pages(root, out, roles=ROLES, reserved_manifest=heldout)
    assert second["stats"]["reused_pdfs"] == 1
    assert out.is_file()
    paths, groups = reserved_sources(heldout, root)
    assert str(held.resolve()) in paths
    assert not groups


def test_pdf_native_crops_vary_scale_and_position_without_fullpage_rasters(tmp_path):
    path = tmp_path / "large_construction_set.pdf"
    make_pdf(path, 8)
    entry = {"image_path": str(path), "source_pdf": str(path),
             "pdf_page_index": 3}
    sampler = PdfCropSampler(max_open_documents=1,
                             max_cached_thumbnails=3)
    try:
        crops = []
        observed_dpi = set()
        observed_clip = set()
        for seed in range(36):
            crop, meta = sampler.sample(entry, 256, np.random.default_rng(seed))
            assert crop.shape == (256, 256, 3)
            assert crop.dtype == np.uint8
            assert 100 <= meta["effective_dpi"] <= 400
            assert meta["requested_dpi"] == meta["effective_dpi"]
            assert meta["dpi_range"] == [100, 400]
            assert meta["physical_crop_size_points"] == pytest.approx(
                256 * 72 / meta["effective_dpi"], rel=2e-5
            )
            assert meta["clip_rect_pt"][2] - meta["clip_rect_pt"][0] == pytest.approx(
                meta["physical_crop_size_points"], abs=.003
            )
            observed_dpi.add(meta["effective_dpi"])
            observed_clip.add(tuple(meta["clip_rect_pt"]))
            crops.append(crop)
        assert len(observed_dpi) > 25
        assert len(observed_clip) > 30
        assert len({cv2.imencode(".png", im)[1].tobytes() for im in crops}) > 14
        assert len(sampler._docs) == 1
        assert len(sampler._thumbs) == 1
    finally:
        sampler.close()
    assert not list(tmp_path.glob("*.png"))


def test_native_pdf_dataset_spans_many_pages_and_keeps_clean_alignment(tmp_path):
    path = tmp_path / "plan_set.pdf"
    make_pdf(path, 61)
    groups = {"job_1": [
        {"image_path": str(path), "source_pdf": str(path),
         "pdf_page_index": i}
        for i in range(61)
    ]}
    manifest = {"buckets": {"real_pdf_pages": {
        "weight": 1.0, "groups": groups,
    }}}
    dataset = BlueprintSSLDataset(
        manifest, size=128, items_per_epoch=96, seed=567,
        max_symbols=0, clean_fraction=1., return_metadata=True,
    )
    plan = dataset._source_plan
    assert plan is not None
    # Adjacent pairs reuse the same source PDF *and page*, but produce
    # independently randomized crops. One long project rotates through
    # its pages instead of drawing the same few with replacement.
    assert all(np.array_equal(plan[i], plan[i + 1])
               for i in range(0, len(dataset), 2))
    assert len({int(plan[i, 2]) for i in range(0, len(dataset), 2)}) == 48
    pages = set()
    clip_rects = set()
    for i in range(len(dataset)):
        damaged, clean, mask, info = dataset[i]
        assert np.array_equal(damaged.numpy(), clean.numpy())
        assert not bool(mask.any())
        assert info["pdf_crop"] is not None
        pages.add(info["pdf_crop"]["pdf_page_index"])
        clip_rects.add(tuple(info["pdf_crop"]["clip_rect_pt"]))
    assert len(pages) >= 35
    assert len(clip_rects) >= 92


def test_pretraining_manifest_reads_native_pages_without_reserved_leakage(tmp_path):
    root = tmp_path / "data"
    work = tmp_path / "work"
    src = root / "1_blueprints" / "new_downloads_202611_a" / "job.pdf"
    reserved = root / "1_blueprints" / "new_downloads_202611_a" / "reserved.pdf"
    make_pdf(src, 11)
    make_pdf(reserved, 3)
    work.mkdir()
    (work / "heldout_real_pdfs.json").write_text(json.dumps({
        "source_pdfs": [str(reserved.relative_to(root))],
    }))
    index_pdf_pages(root, work / "pdf_page_index" / "index.json",
                    roles=ROLES, reserved_manifest=work/"heldout_real_pdfs.json")
    train, val, audit = build_pretrain_manifests(root, work)
    included = (train.get("buckets", {}).get("real_pdf_pages", {}).get("groups", {}))
    monitored = (val.get("buckets", {}).get("real_pdf_pages", {}).get("groups", {}))
    entries = [p for g in (included, monitored) for pages in g.values() for p in pages]
    assert len(entries) == 11
    assert all(p["source_pdf"] == str(src.resolve()) for p in entries)
    assert audit["direct_pdf_sources"] == 1
    assert audit["direct_pdf_pages_train"] + audit["direct_pdf_pages_val_monitor"] == 11
    assert all("reserved" not in str(p) for p in entries)


def test_page_balancing_gives_long_documents_more_views_without_monopoly():
    groups = {
        "short": ["/does/not/exist_1.png"],
        "long": [f"/does/not/exist_{n}.png" for n in range(400)],
    }
    dataset = BlueprintSSLDataset(
        {"buckets": {"drawings": {"groups": groups}}},
        size=128, items_per_epoch=16, max_symbols=0,
    )
    p = dataset.bucket_group_probs["drawings"]
    assert len(p) == 2
    assert .60 < max(p) < .80
    assert .20 < min(p) < .40
    assert p.sum() == pytest.approx(1.0)



def test_native_pdf_index_accepts_uppercase_extension(tmp_path):
    root = tmp_path / "data"
    upper = root / "1_blueprints" / "new_downloads_202611_upper" / "DRAWINGS.PDF"
    make_pdf(upper, 2)
    output = tmp_path / "work" / "pdf_page_index" / "index.json"
    result = index_pdf_pages(root, output, roles=ROLES)
    assert result["stats"]["indexed_pages"] == 2
    assert str(upper.resolve()) == next(iter(result["documents"].values()))["pages"][0]["source_pdf"]


def test_native_pdf_replaces_existing_raster_without_double_counting(tmp_path):
    root, work = tmp_path / "data", tmp_path / "work"
    pdf = root / "1_blueprints" / "new_downloads_202611_a" / "site.pdf"
    make_pdf(pdf, 4)
    index_pdf_pages(root, work / "pdf_page_index" / "index.json", roles=ROLES)

    background = work / "pool" / "background"
    background.mkdir(parents=True)
    img = np.full((256, 256, 3), 250, np.uint8)
    assert cv2.imwrite(str(background / "old_page.png"), img)
    record = {
        "image_path": "old_page.png", "project_family_id": "old_raster_group",
        "image_width": 256, "image_height": 256,
        "pool": {"source_path": pdf.relative_to(root).as_posix()},
    }
    (background / "old_page.json").write_text(json.dumps(record))
    train, val, audit = build_pretrain_manifests(root, work)
    assert audit["excluded_counts"].get("replaced_by_direct_pdf") == 1
    direct = (train.get("buckets", {}).get("real_pdf_pages")
              or val.get("buckets", {}).get("real_pdf_pages"))
    assert direct is not None
    assert sum(len(v) for v in direct["groups"].values()) == 4


def test_pdf_file_revision_changes_training_manifest_fingerprint(tmp_path):
    import os
    root, work = tmp_path / "data", tmp_path / "work"
    pdf = root / "1_blueprints" / "new_downloads_202611_a" / "site.pdf"
    make_pdf(pdf, 4)
    index_path = work / "pdf_page_index" / "index.json"
    index_pdf_pages(root, index_path, roles=ROLES)
    old = build_pretrain_manifests(root, work)
    old_entries = old[0]["buckets"].get("real_pdf_pages") or old[1]["buckets"]["real_pdf_pages"]
    old_signature = next(iter(old_entries["groups"].values()))[0]["pdf_source_signature"]
    st = pdf.stat()
    os.utime(pdf, ns=(st.st_atime_ns, st.st_mtime_ns + 2_000_000_000))
    index_pdf_pages(root, index_path, roles=ROLES)
    updated = build_pretrain_manifests(root, work)
    new_entries = updated[0]["buckets"].get("real_pdf_pages") or updated[1]["buckets"]["real_pdf_pages"]
    new_signature = next(iter(new_entries["groups"].values()))[0]["pdf_source_signature"]
    assert old_signature != new_signature



def test_native_pdf_dataset_passes_sampled_dpi_into_synthetic_symbols(tmp_path):
    path = tmp_path / "drawing_set.pdf"
    make_pdf(path, 3)
    manifest = {"buckets": {"real_pdf_pages": {
        "groups": {"project": [
            {"image_path": str(path), "source_pdf": str(path),
             "pdf_page_index": idx}
            for idx in range(3)
        ]}
    }}}
    dataset = BlueprintSSLDataset(
        manifest, size=256, items_per_epoch=16, seed=2026,
        max_symbols=8, clean_fraction=1.0,
        symbol_augment_probability=1.0, return_metadata=True,
    )
    all_dpis, symbols_seen = [], 0
    for i in range(len(dataset)):
        _, _, _, meta = dataset[i]
        pdf_dpi = meta["pdf_crop"]["effective_dpi"]
        all_dpis.append(pdf_dpi)
        assert 100 <= pdf_dpi <= 400
        assert meta["symbol_stats"]["drawing_dpi"] == pdf_dpi or (
            meta["symbol_stats"]["requested"] == 0 and
            "drawing_dpi" not in meta["symbol_stats"]
        )
        for item in meta["symbol_stats"].get("placements", []):
            assert item["drawing_dpi"] == pdf_dpi
            assert item["ink_color_bgr"] is not None
            x0, y0, x1, y1 = item["bbox_xyxy"]
            assert 0 <= x0 < x1 <= 256 and 0 <= y0 < y1 <= 256
            symbols_seen += 1
    assert symbols_seen > 0
    assert min(all_dpis) < 175 and max(all_dpis) > 325


def test_pdf_crop_finds_isolated_faint_plan_ink(tmp_path):
    """Rare pale device strokes must be reachable on a large mostly empty PDF."""
    source = tmp_path / "sparse_faint_electrical.pdf"
    pdf = fitz.open()
    page = pdf.new_page(width=2592, height=1728)
    for i in range(6):
        page.draw_line((255, 340 + 16 * i), (420, 340 + 16 * i),
                       color=(.82, .84, .88), width=2)
        page.draw_line((290 + 16 * i, 305), (290 + 16 * i, 460),
                       color=(.82, .84, .88), width=1.5)
    pdf.save(source)
    pdf.close()
    entry = {"image_path": str(source), "source_pdf": str(source),
             "pdf_page_index": 0}
    sampler = PdfCropSampler()
    hits = 0
    try:
        for index in range(32):
            crop, meta = sampler.sample(
                entry, 512, np.random.default_rng(2026 + index)
            )
            assert crop.shape == (512, 512, 3)
            assert 100 <= meta["effective_dpi"] <= 400
            assert meta["sampling_policy"] == "adaptive_ink_guided_v2"
            assert meta["thumbnail_ink_threshold"] >= 200
            assert 0 <= meta["ink_anchored_proposals"] <= 15
            x0, y0, x1, y1 = meta["clip_rect_pt"]
            if x0 < 420 and x1 > 255 and y0 < 470 and y1 > 305:
                hits += 1
        # Uniform 512px crops rarely intersect this small island. Anchored
        # proposals must find it without sacrificing uniform sparse views.
        assert hits >= 15
    finally:
        sampler.close()


def test_pdf_crop_blank_thumbnail_falls_back_to_uniform(tmp_path):
    source = tmp_path / "blank_sheet.pdf"
    pdf = fitz.open()
    pdf.new_page(width=900, height=650)
    pdf.save(source)
    pdf.close()
    sampler = PdfCropSampler()
    try:
        crop, meta = sampler.sample(
            {"source_pdf": str(source), "pdf_page_index": 0}, 256,
            np.random.default_rng(44)
        )
        assert crop.shape == (256, 256, 3)
        assert meta["ink_anchored_proposals"] == 0
        assert meta["thumbnail_ink_fraction"] == 0.0
    finally:
        sampler.close()
