"""1 in 4 verified pages of training drawings are held out for the real-page test."""
from __future__ import annotations

import json
import shutil
from pathlib import Path

import cv2
import numpy as np

from annotator.label_store import split_holdout
from blueprint_symbols.dataset_manifest import build_manifest
from blueprint_symbols.real_page_eval import select_real_eval_pages


def test_split_holdout_quarter_per_drawing_and_kind() -> None:
    pages = ([("a.pdf", n, "blank", f"a#b{n}") for n in range(1, 136)]
             + [("a.pdf", n, "complete", f"a#c{n}") for n in range(200, 213)]
             + [("b.pdf", n, "blank", f"b#b{n}") for n in range(1, 6)]
             + [("b.pdf", n, "complete", f"b#c{n}") for n in range(10, 13)])
    train, held = split_holdout(pages, 4)
    assert sorted(train + held) == sorted(p[3] for p in pages)
    assert not set(train) & set(held)
    count = lambda prefix: sum(h.startswith(prefix) for h in held)
    assert (count("a#b"), count("a#c"), count("b#b"), count("b#c")) == (34, 3, 1, 0)   # <4 pages: none held
    assert set(split_holdout(list(reversed(pages)), 4)[1]) == set(held)       # order-independent
    assert split_holdout(pages, 0) == ([p[3] for p in pages], [])


def _sheet(path: Path, seed: int, *, symbol: bool) -> None:
    rng = np.random.default_rng(seed)
    img = np.full((640, 640, 3), 246, np.uint8)
    for _ in range(10):
        p1 = tuple(int(v) for v in rng.integers(20, 620, 2))
        p2 = tuple(int(v) for v in rng.integers(20, 620, 2))
        cv2.line(img, p1, p2, (20, 20, 20), int(rng.integers(2, 5)))
    cv2.putText(img, f"SHEET {seed}", (40, 60), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (0, 0, 0), 2)
    if symbol:
        cv2.circle(img, (300, 300), 16, (0, 0, 0), 2)
    assert cv2.imwrite(str(path), img)


def _page(tmp: Path, name: str, group: str, idx: int, *, blank: bool, seed: int) -> Path:
    _sheet(tmp / f"{name}.png", seed, symbol=not blank)
    inst = [] if blank else [{
        "instance_id": f"{name}_0", "symbol_family": "receptacle", "symbol_variant": "duplex",
        "bbox_xyxy": [282.0, 282.0, 318.0, 318.0], "uncertain": False, "review_status": "accepted",
        "review_source": "human", "verified_ground_truth": True}]
    page = {"image_path": f"{name}.png", "image_width": 640, "image_height": 640,
            "project_family_id": group, "document_id": f"{group}.pdf", "page_index": idx,
            "source_group_verified": True, "source_type": "real_construction_pdf_manual",
            "exhaustive_families": ["receptacle"], "annotation_completeness_verified": not blank,
            "verified_no_symbols": blank, "instances": inst}
    path = tmp / f"{name}.json"
    path.write_text(json.dumps(page), encoding="utf-8")
    return path


def test_manifest_keeps_holdout_out_of_training_and_eval_uses_only_holdout(tmp_path: Path) -> None:
    files, held = [], []
    for g in range(3):
        files.append(_page(tmp_path, f"t_blank_{g}", f"g{g}", 0, blank=True, seed=10 + g))
        files.append(_page(tmp_path, f"t_comp_{g}", f"g{g}", 1, blank=False, seed=20 + g))
    held.append(_page(tmp_path, "h_blank", "g0", 5, blank=True, seed=30))
    held.append(_page(tmp_path, "h_comp", "g1", 6, blank=False, seed=31))
    # A held-out page that repeats a training sheet (same raster) never counts.
    shutil.copy(tmp_path / "t_blank_1.png", tmp_path / "h_copy.png")
    copy = json.loads((tmp_path / "t_blank_1.json").read_text())
    copy.update(image_path="h_copy.png", page_index=9)
    (tmp_path / "h_copy.json").write_text(json.dumps(copy))
    held.append(tmp_path / "h_copy.json")

    mf = build_manifest(files, val_fraction=0.25, test_fraction=0.25, holdout_files=held)
    trained = {str(Path(a).resolve()) for refs in mf["splits"].values() for a in refs}
    holdout = {r["annotation"] for r in mf["real_holdout"]}
    assert holdout == {str((tmp_path / "h_blank.json").resolve()), str((tmp_path / "h_comp.json").resolve())}
    assert not trained & holdout
    assert any("repeat a training page" in w for w in mf["warnings"])

    mf_path = tmp_path / "source_manifest.json"
    mf_path.write_text(json.dumps(mf), encoding="utf-8")
    sel = select_real_eval_pages(mf_path, max_blank_pages=1000, max_complete_pages=1000)
    assert [r["ref"] for r in sel["blank_pages"]] == [str((tmp_path / "h_blank.json").resolve())]
    assert [r["ref"] for r in sel["complete_pages"]] == [str((tmp_path / "h_comp.json").resolve())]
    assert {r["split"] for r in sel["blank_pages"] + sel["complete_pages"]} == {"holdout"}
