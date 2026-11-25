"""Walls-only (proven symbol-free) drawings: vector rendering, mutation, pretraining
and post-training backgrounds."""
from __future__ import annotations

import json
import math
from pathlib import Path

import cv2
import numpy as np
import pytest

from blueprint_symbols import vector_walls as vw
from blueprint_symbols.ssl_augmentation import mutate_linework


def _archcad(path: Path, *, walls: int = 20, extra=()) -> Path:
    ents = [{"type": "LINE", "start": [40 + 40 * i, 50], "end": [40 + 40 * i, 900], "semantic": 20}
            for i in range(walls)]
    ents += list(extra)
    path.write_text(json.dumps({"entities": ents}))
    return path


def _floorplancad(path: Path, *, walls: int = 20) -> Path:
    paths = "".join(
        f'<path d="M {5 + 4 * i},10 L {5 + 4 * i},90" semantic-id="1" stroke="rgb(0,0,0)"/>'
        for i in range(walls))
    paths += '<path d="M 10,10 A 5,5 0 0,1 15,15" semantic-id="3"/>'          # door swing: left out
    paths += '<path d="M 50,50 L 60,50 L 60,60 Z" semantic-id="35"/>'         # parked car: left out
    paths += '<path d="M 20,20 L 30,20"/>'                                     # unlabelled: left out
    path.write_text(f'<svg viewBox="0 0 100 100">{paths}</svg>')
    return path


def test_archcad_keeps_only_structure_and_draws_clockwise_arcs(tmp_path):
    f = _archcad(tmp_path / "a.json", walls=3, extra=[
        {"type": "CIRCLE", "center": [500, 500], "radius": 30, "semantic": 100},   # unlabelled other
        {"type": "CIRCLE", "center": [500, 500], "radius": 30, "semantic": 27},    # pile: downlight look-alike
        {"type": "LINE", "start": [0, 0], "end": [9, 9], "semantic": 1},           # door
        # clockwise quarter arc from 90 deg down to 0 deg (ArchCAD direction 1)
        {"type": "ARC", "center": [100, 100], "radius": 10, "start_angle": 90, "end_angle": 0,
         "direction": 1, "semantic": 20},
    ])
    lines = vw.archcad_lines(f)
    assert len(lines) == 4                          # 3 walls + the arc
    arc = lines[-1]
    sweep = abs(math.atan2(arc[-1][1] - 100, arc[-1][0] - 100) - math.atan2(arc[0][1] - 100, arc[0][0] - 100))
    assert sweep == pytest.approx(math.pi / 2, abs=1e-6)   # the short way, not 270 degrees


def test_floorplancad_keeps_walls_and_scales_to_pixels(tmp_path):
    lines = vw.floorplancad_lines(_floorplancad(tmp_path / "f.svg", walls=4))
    assert len(lines) == 4
    assert lines[0].min() >= 40 and lines[0].max() <= 910


def test_render_skips_nearly_blank_and_varies_style(tmp_path):
    rng = np.random.default_rng(0)
    assert vw.render_vector_walls({"vector_walls": str(_archcad(tmp_path / "b.json", walls=3)),
                                   "format": "archcad"}, rng) is None
    entry = {"vector_walls": str(_archcad(tmp_path / "c.json")), "format": "archcad"}
    a = vw.render_vector_walls(entry, np.random.default_rng(1))
    b = vw.render_vector_walls(entry, np.random.default_rng(2))
    assert a is not None and b is not None and a.dtype == np.uint8
    assert a.shape != b.shape or not np.array_equal(a, b)
    assert (cv2.cvtColor(a, cv2.COLOR_BGR2GRAY) < 128).any()


def test_mutate_linework_restyles_without_changing_size():
    img = np.full((256, 256, 3), 255, np.uint8)
    for x in range(20, 240, 30):
        cv2.line(img, (x, 10), (x, 245), (0, 0, 0), 2)
    seen = set()
    for seed in range(40):
        out, meta = mutate_linework(img, np.random.default_rng(seed))
        assert out.shape == img.shape and out.dtype == np.uint8
        seen.update(c.split("_")[0] for c in meta["changes"])
    assert {"thicker", "broken", "ink", "paper"} <= seen
    blank = np.full((64, 64, 3), 255, np.uint8)
    out, meta = mutate_linework(blank, np.random.default_rng(0))
    assert meta["changes"] == [] and np.array_equal(out, blank)


def _data_root(tmp_path: Path) -> Path:
    root = tmp_path / "data"
    aj = root / "5_datasets/archcad_400k/extracted/json/json"
    ap = root / "5_datasets/archcad_400k/extracted/png/png"
    fp = root / "5_datasets/floorplancad_full/extracted"
    for d in (aj, ap, fp):
        d.mkdir(parents=True)
    page = np.full((400, 400, 3), 255, np.uint8)
    cv2.line(page, (10, 200), (390, 200), (0, 0, 0), 2)
    for i in range(3):
        _archcad(aj / f"s{i}.json")
        cv2.imwrite(str(ap / f"s{i}.png"), page)
    _floorplancad(fp / "0001-0001.svg")
    cv2.imwrite(str(fp / "0001-0001.png"), 255 - page)        # FloorPlanCAD: light lines on black
    return root


def test_post_training_backgrounds_walls_only_complete_others_positive_only(tmp_path):
    pytest.importorskip("torch")                  # load_bgr_white_bg lives in ssl_pretrain
    from blueprint_symbols.composite_supervision import composite_supervision
    from blueprint_symbols.dataset_backgrounds import (
        other_dataset_backgrounds, read_symbol_free_list, walls_only_backgrounds)

    root = _data_root(tmp_path)
    walls = walls_only_backgrounds(root, tmp_path / "walls", 5, seed=1)
    assert len(walls) == 5
    for p in walls:
        doc = json.loads(p.read_text())
        assert doc["verified_no_symbols"] is True and doc["instances"] == []
        assert doc["symbol_free_reason"]
        sup = composite_supervision(doc, [{"symbol_family": "receptacle.duplex"}],
                                    generated_families={"receptacle.duplex", "switch.single_pole"})
        assert set(sup["exhaustive_families"]) == {"receptacle.duplex", "switch.single_pole"}

    flagged = root / "5_datasets/archcad_400k/extracted/png/png/s1.png"
    (root / "symbol_free_images.txt").write_text(
        "# checked by hand\n5_datasets/archcad_400k/extracted/png/png/s1.png\n")
    others = other_dataset_backgrounds(root, tmp_path / "others", 10, seed=1,
                                       symbol_free=read_symbol_free_list(root))
    assert len(others) == 4                                   # 3 ArchCAD + 1 FloorPlanCAD
    by_free = {json.loads(p.read_text())["verified_no_symbols"]: p for p in others}
    assert sum(json.loads(p.read_text())["verified_no_symbols"] for p in others) == 1
    free_doc = json.loads(by_free[True].read_text())
    assert Path(free_doc["source_id"]).name == flagged.stem
    for p in others:
        doc = json.loads(p.read_text())
        img = cv2.imread(str(p.parent / doc["image_path"]))
        assert img.mean() > 128                                # dark CAD sheets turned to white paper
        if not doc["verified_no_symbols"]:
            sup = composite_supervision(doc, [{"symbol_family": "receptacle.duplex"}],
                                        generated_families={"receptacle.duplex"})
            assert sup["exhaustive_families"] == []           # may hold real symbols: positive-only


def test_pretraining_walls_only_bucket_is_symbol_free_and_heavily_mutated(tmp_path):
    pytest.importorskip("torch")
    from blueprint_symbols.ssl_pretrain import BlueprintSSLDataset
    from scripts_pretrain_ssl import build_pretrain_manifests

    root = _data_root(tmp_path)
    work = tmp_path / "work"
    work.mkdir()
    train, val, audit = build_pretrain_manifests(root, work)
    bucket = train["buckets"]["walls_only_cad"]
    assert bucket["symbol_free"] is True
    assert audit["included_counts"]["walls_only_archcad"] == 3
    assert audit["included_counts"]["walls_only_floorplancad"] == 1
    assert "symbol_free" not in train["buckets"]["archcad_train"]   # raw slices may hold symbols

    manifest = {"buckets": {"walls_only_cad": bucket}}
    ds = BlueprintSSLDataset(manifest, size=256, items_per_epoch=6, seed=5, clean_fraction=1.0,
                             max_symbols=8, page_chaos_probability=0.0, return_metadata=True)
    for i in range(len(ds)):
        _, _, _, info = ds[i]
        assert info["symbol_free_source"] is True
        assert info["full_clean_view"] is False               # never shown clean
        assert info["mutation"]["level"] == "heavy"
        assert info["mutation"]["max_symbols"] == 16
        assert info["mutation"]["page_chaos_probability"] == 1.0
