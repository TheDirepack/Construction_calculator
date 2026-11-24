"""The SSL loader speed-ups must not change what the model sees."""
from __future__ import annotations

import numpy as np
import pytest

fitz = pytest.importorskip("fitz")
cv2 = pytest.importorskip("cv2")

from blueprint_symbols.pdf_crop import PdfCropSampler


def _vector_pdf(path, *, rotate: int = 0):
    doc = fitz.open()
    page = doc.new_page(width=1224, height=792)
    for i in range(0, 1200, 37):
        page.draw_line((i, 20), (i + 40, 770), color=(0, 0, 0), width=0.6)
    for x in range(60, 1150, 90):
        page.draw_circle((x, 400), 9, color=(0, 0, 0.6), width=0.8)
        page.insert_text((x - 10, 430), "LR1-%d" % x, fontsize=6)
    if rotate:
        page.set_rotation(rotate)
    doc.save(path)
    doc.close()
    return str(path)


def _entry(pdf):
    return {"source_pdf": pdf, "image_path": pdf, "pdf_page_index": 0}


def test_display_list_renders_match_direct_page_renders(tmp_path):
    pdf = _vector_pdf(tmp_path / "plan.pdf")
    sampler = PdfCropSampler()
    doc = sampler._open(pdf)
    page = doc.load_page(0)
    for zoom, clip in ((0.3, None), (1.7, fitz.Rect(100, 100, 400, 400)),
                       (5.2, fitz.Rect(600, 300, 700, 400))):
        for cs in (fitz.csGRAY, fitz.csRGB):
            direct = page.get_pixmap(matrix=fitz.Matrix(zoom, zoom), colorspace=cs,
                                     alpha=False, clip=clip)
            listed = sampler._render(pdf, 0, page, matrix=fitz.Matrix(zoom, zoom),
                                     colorspace=cs, clip=clip)
            assert (direct.width, direct.height) == (listed.width, listed.height)
            assert direct.samples == listed.samples
    assert len(sampler._dlists) == 1
    sampler.close()


def test_rotated_pages_skip_the_display_list(tmp_path):
    pdf = _vector_pdf(tmp_path / "rot.pdf", rotate=90)
    sampler = PdfCropSampler()
    page = sampler._open(pdf).load_page(0)
    assert sampler._display_list(pdf, 0, page) is None
    crop, meta = sampler.sample(_entry(pdf), 256, np.random.default_rng(3), crop_seed=11)
    assert crop.shape == (256, 256, 3)
    sampler.close()


def test_crops_identical_with_and_without_caches(tmp_path):
    pdf = _vector_pdf(tmp_path / "plan.pdf")
    cache = tmp_path / "thumbs"
    plain = PdfCropSampler(max_display_lists=1)
    plain._display_list = lambda *a, **k: None          # force direct page renders
    cached_first = PdfCropSampler(thumbnail_cache_dir=cache)
    for seed in (1, 2, 3, 4):
        a, ma = plain.sample(_entry(pdf), 512, np.random.default_rng(seed), crop_seed=seed)
        b, mb = cached_first.sample(_entry(pdf), 512, np.random.default_rng(seed), crop_seed=seed)
        assert np.array_equal(a, b) and ma == mb
    assert list(cache.rglob("*.png")), "thumbnail was not written to the disk cache"

    # A fresh sampler (a new worker or a new epoch) reads the thumbnail from disk.
    reread = PdfCropSampler(thumbnail_cache_dir=cache)
    reread._render = None                              # any render of the thumbnail would fail
    page = reread._open(pdf).load_page(0)
    from_disk = reread._density(pdf, 0, page)
    fresh = plain._density(pdf, 0, plain._open(pdf).load_page(0))
    assert np.array_equal(from_disk[0], fresh[0]) and from_disk[1:3] == fresh[1:3]
    assert np.array_equal(from_disk[3], fresh[3]) and from_disk[4] == fresh[4]
    for s in (plain, cached_first, reread):
        s.close()


def test_load_bgr_white_bg_dark_sheet_detection_unchanged(tmp_path):
    pytest.importorskip("torch")
    from blueprint_symbols.ssl_pretrain import load_bgr_white_bg
    dark = np.zeros((300, 400, 3), np.uint8)
    cv2.line(dark, (10, 10), (390, 290), (0, 200, 255), 3)
    path = tmp_path / "dark.png"
    cv2.imwrite(str(path), dark)
    out = load_bgr_white_bg(path)
    expected = cv2.cvtColor(255 - dark.max(axis=2), cv2.COLOR_GRAY2BGR)
    assert np.array_equal(out, expected)


def test_wall_detection_is_deterministic_with_vectorised_probe():
    from blueprint_symbols.wall_alignment import detect_wall_sites
    rng_img = np.random.default_rng(0)
    gray = np.full((512, 512), 245, np.uint8)
    for y in range(40, 500, 60):
        cv2.line(gray, (20, y), (490, y + int(rng_img.integers(-5, 5))), 0, 2)
    cv2.line(gray, (256, 20), (256, 490), 0, 3)
    sites_a, mask_a = detect_wall_sites(gray, np.random.default_rng(5))
    sites_b, mask_b = detect_wall_sites(gray, np.random.default_rng(5))
    assert sites_a == sites_b and np.array_equal(mask_a, mask_b) and sites_a


def test_uint8_batches_convert_to_the_same_floats():
    torch = pytest.importorskip("torch")
    from blueprint_symbols.ssl_pretrain import batch_to_device
    rng = np.random.default_rng(1)
    img = rng.integers(0, 256, (2, 3, 64, 64), dtype=np.uint8)
    mask = (rng.random((2, 1, 64, 64)) < .3).astype(np.uint8)
    deg, clean, m = batch_to_device(torch.from_numpy(img), torch.from_numpy(img),
                                    torch.from_numpy(mask), torch.device("cpu"))
    reference = torch.from_numpy(img).float().div_(255.0)
    assert deg.dtype == torch.float32 and torch.equal(deg, reference) and torch.equal(clean, reference)
    assert m.dtype == torch.float32 and torch.equal(m, torch.from_numpy(mask).float())
    # Float batches (previews, older callers) pass through unchanged.
    f = torch.rand(2, 3, 8, 8)
    assert torch.equal(batch_to_device(f, f, f[:, :1], torch.device("cpu"))[0], f)
