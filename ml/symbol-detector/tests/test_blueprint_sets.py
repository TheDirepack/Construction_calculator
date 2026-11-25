"""Training vs evaluation drawings come from the folders at runtime."""
from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from blueprint_symbols.blueprint_sets import blueprint_sets, devices_for  # noqa: E402
from blueprint_symbols.dataset_manifest import build_manifest  # noqa: E402
from test_label_unification import _page, _sheet, _verified  # noqa: E402


def _touch(path: Path) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"%PDF-1.4\n")
    return path


def test_root_trains_evaluation_folder_evaluates_other_folders_neither(tmp_path):
    blue = tmp_path / "data" / "1_blueprints"
    a = _touch(blue / "A-plans.pdf")
    _touch(blue / "UPPER.PDF")
    e = _touch(blue / "evaluation" / "E-plans.pdf")
    _touch(blue / "evaluation" / "A-PLANS.pdf")          # same name in both: evaluation wins
    _touch(blue / "scraped" / "S-plans.pdf")
    sets = blueprint_sets(tmp_path / "data")
    assert sets.root == blue.resolve()
    assert sets.role(a) == "evaluation" and sets.role(e) == "evaluation"
    assert sets.role(blue / "UPPER.PDF") == "train"
    assert sets.role(blue / "scraped" / "S-plans.pdf") is None
    assert [p.name for p in sets.training_pdfs()] == ["UPPER.PDF"]
    assert blueprint_sets(blue).root == blueprint_sets(a).root == blue.resolve()


def test_devices_come_from_the_devices_folder_beside_the_pdf(tmp_path):
    blue = tmp_path / "1_blueprints"
    train = _touch(blue / "job.pdf")
    held = _touch(blue / "evaluation" / "held.pdf")
    for folder, source in ((blue / "devices", "job.pdf"), (blue / "evaluation" / "devices", "held.pdf"),
                           (blue / "elsewhere", "job.pdf")):
        folder.mkdir(parents=True, exist_ok=True)
        (folder / f"{folder.parent.name}_{folder.name}_DEVICES.json").write_text(json.dumps(
            {"source_file": source, "devices": [{"pdf_page": 1, "device_type": "receptacle"}]}))
    assert devices_for(train).parent == (blue / "devices").resolve()
    assert devices_for(held).parent == (blue / "evaluation" / "devices").resolve()
    assert devices_for(_touch(blue / "other.pdf")) is None


def _pages(tmp_path, n, prefix, *, complete):
    out = []
    for k in range(n):
        img = _sheet(tmp_path / f"{prefix}{k}.png", sum(map(ord, prefix)) + k, text=f"{prefix} {k}")
        page = _page(tmp_path / f"{prefix}{k}.json", img, f"{prefix}{k}", empty=False,
                     instances=[_verified("receptacle", "duplex", box=(10, 10, 30, 30))])
        if not complete:
            data = json.loads(page.read_text())
            data["exhaustive_families"] = []
            page.write_text(json.dumps(data))
        out.append(page)
    return out


def test_evaluation_pages_alone_are_validation_and_test(tmp_path):
    train = _pages(tmp_path, 3, "train", complete=False)          # partial labels train
    held = _pages(tmp_path, 2, "eval", complete=True)
    m = build_manifest(train + held, allow_partial_training=True, evaluation_files=held)
    assert m["holdout"] == "evaluation_folder"
    assert sorted(m["split_groups"]["train"]) == ["train0", "train1", "train2"]
    assert sorted(m["split_groups"]["val"] + m["split_groups"]["test"]) == ["eval0", "eval1"]

    one = build_manifest(train + held[:1], allow_partial_training=True, evaluation_files=held[:1])
    assert one["split_groups"]["val"] == one["split_groups"]["test"] == ["eval0"]


def test_evaluation_pages_must_be_fully_labelled(tmp_path):
    train = _pages(tmp_path, 2, "train", complete=False)
    held = _pages(tmp_path, 1, "eval", complete=False)
    with pytest.raises(ValueError, match="not fully labelled"):
        build_manifest(train + held, allow_partial_training=True, evaluation_files=held)
