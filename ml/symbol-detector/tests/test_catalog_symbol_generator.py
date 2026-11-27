"""Tests for 100%-coverage synthetic CAD catalog symbol generator."""
from __future__ import annotations

import json
import sys
import time
from collections import Counter
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import scripts_generate_catalog_symbols as G  # noqa: E402
from blueprint_symbols.label_schema import CATALOG_DEVICES, CATALOG_PATH  # noqa: E402
from blueprint_symbols.symbol_library import legend_symbol_glyphs  # noqa: E402
from blueprint_symbols.verified_symbols import ink_alpha  # noqa: E402


def test_every_catalog_device_has_explicit_renderer() -> None:
    catalog = json.loads(CATALOG_PATH.read_text(encoding="utf-8"))
    assert len(catalog["devices"]) == len(CATALOG_DEVICES)
    G.verify_catalog_support(catalog["devices"])


def test_full_catalog_generation_and_symbol_library_loading(tmp_path: Path) -> None:
    out_dir = tmp_path / "synthetic_catalog_symbols"
    audit_dir = tmp_path / "synthetic_audit"

    t0 = time.perf_counter()
    G.main([
        "--catalog", str(CATALOG_PATH),
        "--output", str(out_dir),
        "--audit-dir", str(audit_dir),
        "--styles-per-device", "5",
        "--seed", "2026",
    ])
    elapsed = time.perf_counter() - t0
    assert elapsed < 5.0, f"Full catalog generation too slow: {elapsed:.2f}s"

    crops_doc = json.loads((out_dir / "crops.json").read_text(encoding="utf-8"))
    review_doc = json.loads((out_dir / "review.json").read_text(encoding="utf-8"))
    assert crops_doc["format_version"] == 1
    assert len(crops_doc["crops"]) == len(review_doc)

    catalog = json.loads(CATALOG_PATH.read_text(encoding="utf-8"))
    expected_devices = {f"{d['symbol_family']}.{d['symbol_variant']}" for d in catalog["devices"]}
    counts = Counter()

    for rec in crops_doc["crops"]:
        assert rec["method"] == "synthetic_cad"
        assert rec["device"] in expected_devices
        assert rec["legend_text"]
        assert rec["style"]
        rev = review_doc[rec["id"]]
        assert rev["status"] == "accepted"
        assert rev["device"] == rec["device"]
        assert rev["by"] == G.GENERATOR_BY

        png_path = out_dir / "crops" / f"{rec['id']}.png"
        assert png_path.is_file()
        gray = cv2.imread(str(png_path), cv2.IMREAD_GRAYSCALE)
        assert gray is not None and gray.ndim == 2
        h, w = gray.shape
        assert min(h, w) >= 4
        assert max(h, w) <= 192
        assert gray[0, 0] == 255 and gray[-1, -1] == 255
        assert int(gray.min()) <= 35
        alpha = ink_alpha(gray)
        assert alpha is not None and min(alpha.shape) >= 3
        counts[rec["device"]] += 1

    assert set(counts) == expected_devices
    assert all(n >= 4 for n in counts.values())

    for d in catalog["devices"]:
        dev_id = f"{d['symbol_family']}.{d['symbol_variant']}"
        if int(d.get("source_count") or 0) == 0:
            assert counts[dev_id] >= 5, f"Zero-source device {dev_id} has only {counts[dev_id]} styles"

    glyphs, report = legend_symbol_glyphs(out_dir)
    assert report["devices"] == len(expected_devices)
    assert report["counts"].get("not_accepted_device", 0) == 0
    assert report["counts"].get("rejected", 0) == 0
    assert report["counts"].get("unreadable", 0) == 0
    assert len(glyphs) == len(crops_doc["crops"])

    sheets = sorted(audit_dir.glob("synth_*.png"))
    assert len(sheets) >= len(catalog["categories"])


def test_generation_is_deterministic(tmp_path: Path) -> None:
    subset = ["luminaire.exit_sign_single_face_double_arrow", "receptacle.duplex_gfci", "control_relay.power_pack"]
    out_a = tmp_path / "run_a"
    out_b = tmp_path / "run_b"
    G.generate_catalog_symbols(CATALOG_PATH, out_a, audit_dir=None, styles_per_device=5, seed=2026, only_devices=subset)
    G.generate_catalog_symbols(CATALOG_PATH, out_b, audit_dir=None, styles_per_device=5, seed=2026, only_devices=subset)

    assert (out_a / "crops.json").read_bytes() == (out_b / "crops.json").read_bytes()
    assert (out_a / "review.json").read_bytes() == (out_b / "review.json").read_bytes()
    pngs_a = sorted((out_a / "crops").glob("*.png"))
    pngs_b = sorted((out_b / "crops").glob("*.png"))
    assert [p.name for p in pngs_a] == [p.name for p in pngs_b]
    for pa, pb in zip(pngs_a, pngs_b):
        assert pa.read_bytes() == pb.read_bytes()


def test_exit_sign_variants_visual_self_checks() -> None:
    """Verify structural distinctions across all 6 exit face x arrow classes + combo.

    Each variant is drawn with the same random state, so pen width, ink and
    radius match and ink differences come only from the faces and arrows.
    Arrow layouts differ per variant (one long arrow vs two short ones), so
    only base -> arrows and single -> double face are compared."""
    variants = [
        "exit_sign_single_face", "exit_sign_single_face_single_arrow", "exit_sign_single_face_double_arrow",
        "exit_sign_double_face", "exit_sign_double_face_double_arrow", "exit_sign_double_face_four_arrow",
    ]
    for seed in range(3):
        for style in range(6):
            imgs = {v: G._draw_exit_sign(v, style, np.random.default_rng(seed)) for v in variants}
            ink = {v: int((img < 128).sum()) for v, img in imgs.items()}
            sf_0, sf_1, sf_2, df_0, df_2, df_4 = (ink[v] for v in variants)
            where = f"seed {seed} style {style}"
            assert df_0 > sf_0, f"{where}: double_face should have more ink than single_face"
            assert sf_1 > sf_0 and sf_2 > sf_0, f"{where}: single-face arrows should add ink"
            assert df_2 > df_0 and df_4 > df_0, f"{where}: double-face arrows should add ink"
            for i, a in enumerate(variants):
                for b in variants[i + 1:]:
                    assert imgs[a].shape != imgs[b].shape or not np.array_equal(imgs[a], imgs[b]), (
                        f"{where}: {a} and {b} render identically")

    combo_0 = G.render_symbol("luminaire", "exit_emergency_combo", 0, seed=2026)
    assert min(combo_0.shape) >= 24 and int((combo_0 < 128).sum()) > 100


def test_emergency_luminaires_mirror_base_with_emergency_marker() -> None:
    for base in sorted(G.BASE_LUMINAIRE_VARIANTS):
        base_img = G.render_symbol("luminaire", base, 0, seed=2026)
        em_img = G.render_symbol("luminaire", f"{base}_emergency", 0, seed=2026)
        assert int((em_img < 128).sum()) > int((base_img < 128).sum()), (
            f"{base}_emergency style 0 should add emergency ink over {base}"
        )
        em_tag = G.render_symbol("luminaire", f"{base}_emergency", 3, seed=2026)
        assert not np.array_equal(em_tag, G.render_symbol("luminaire", base, 3, seed=2026))


def test_bank_caps_synthetic_share_when_real_drawings_exist() -> None:
    from blueprint_symbols.neca_svg_compositor import SYNTHETIC_SHARE, NecaGlyph, NecaSymbolBank

    def glyph(i: int, source: str, variant: str) -> NecaGlyph:
        return NecaGlyph(f"{source}:{i}", "", "x", f"{source}:x", "receptacle", "receptacle", variant,
                         np.zeros((8, 8), np.uint8), "receptacle", variant)

    bank = NecaSymbolBank.__new__(NecaSymbolBank)
    bank.devices_only, bank.extra_by_source, bank.library_share, bank.by_device = True, {}, 0.5, {}
    bank.add_glyphs([glyph(0, "legend_symbol_crops", "duplex")]
                    + [glyph(i, "synthetic_catalog_symbols", "duplex") for i in range(1, 9)]
                    + [glyph(i, "synthetic_catalog_symbols", "quad") for i in range(9, 12)])
    rng = np.random.default_rng(0)
    picks = [bank.sample(rng) for _ in range(4000)]
    duplex = [g for g in picks if g.variant == "duplex"]
    share = sum(g.section.startswith("synthetic") for g in duplex) / len(duplex)
    assert abs(share - SYNTHETIC_SHARE) < 0.05          # 8 of 9 drawings, but only ~35% of picks
    assert any(g.variant == "quad" for g in picks)      # synthetic-only devices are still placed
