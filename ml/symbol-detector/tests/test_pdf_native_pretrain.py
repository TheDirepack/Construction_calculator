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
            assert 25 < meta["effective_dpi"] < 5000
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
