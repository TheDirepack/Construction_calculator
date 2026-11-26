"""Every labeling path writes one format, and training reads it.

The browser annotator, the vision-model agents (label store + agent API),
model pre-labeling and the *DEVICES.json importer share
``blueprint_symbols.label_schema``; training reads saved pages through
``load_training_page``.
"""
from __future__ import annotations

import json
from pathlib import Path

import pytest

fitz = pytest.importorskip("fitz")
pytest.importorskip("cv2")

from annotator import label_store
from annotator.web import LabelingSession
from blueprint_symbols import label_schema
from blueprint_symbols.dataset_manifest import build_manifest


def _pdf(path: Path, text: str) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    doc = fitz.open()
    page = doc.new_page(width=400, height=300)
    page.insert_text((20, 280), text)
    page.draw_circle((100, 100), 8)
    doc.save(path)
    doc.close()
    return path


def _verified(family: str, variant: str, ident: str = "b1", box=(90, 90, 112, 112)) -> dict:
    return {"instance_id": ident, "symbol_family": family, "symbol_variant": variant,
            "bbox_xyxy": list(box), "review_status": "accepted", "review_source": "human",
            "verified_ground_truth": True, "uncertain": False}


def test_training_classes_are_exact_catalog_devices():
    catalog = json.loads((Path(__file__).parents[1] / "annotator" /
                          "electrical_device_catalog.json").read_text())
    for d in catalog["devices"]:
        inst = {"symbol_family": d["symbol_family"], "symbol_variant": d["symbol_variant"]}
        assert label_schema.instance_class(inst) == f"{d['symbol_family']}.{d['symbol_variant']}"
        assert label_schema.device_for_class(label_schema.instance_class(inst)) == \
            (d["symbol_family"], d["symbol_variant"])
    assert len(label_schema.exhaustive_classes(label_schema.EXHAUSTIVE_FAMILIES)) == len(catalog["devices"])
    # No device chosen, or an older coarse name: ignored, never a class.
    assert label_schema.instance_class({"symbol_family": "junction_box",
                                        "symbol_variant": "unspecified"}) == label_schema.UNMAPPED
    assert label_schema.instance_class({"symbol_family": "switch_sensor"}) == label_schema.UNMAPPED
    # Non-electrical datasets keep their own classes.
    assert label_schema.instance_class({"symbol_family": "door"}) == "door"
    # Every NECA glyph mapping lands on a catalog device.
    assert all(label_schema.device_class(*dev) for dev in label_schema.NECA_DEVICE.values())
    assert label_schema.device_for_raw_type("light_fixture") == ("luminaire", "unspecified")
    assert label_schema.device_for_raw_type("hvac_device") is None


def test_training_view_keeps_the_saved_device_and_expands_completeness():
    page = {"instances": [_verified("switch", "three_way")], "exhaustive_families": ["switch"]}
    view = label_schema.training_view(page)
    inst = view["instances"][0]
    assert inst["symbol_family"] == "switch.three_way"
    assert (inst["device_family"], inst["device_variant"]) == ("switch", "three_way")
    assert "switch.three_way" in view["exhaustive_families"]
    assert "switch.dimmer" in view["exhaustive_families"]
    assert not any(c.startswith("sensor.") for c in view["exhaustive_families"])
    assert label_schema.training_view(view) == view
    assert page["instances"][0]["symbol_family"] == "switch"          # input untouched


def test_annotator_pages_split_and_train_on_detector_classes(tmp_path):
    blue = tmp_path / "blueprints_and_libraries" / "1_blueprints"
    store = label_store.default_store()
    # Device classes need each device in several projects to fill train/val/test.
    extras = [("switch", "three_way"), ("sensor", "occupancy_sensor_ceiling"), ("switch", "three_way"),
              ("sensor", "occupancy_sensor_ceiling")]
    for n, extra in enumerate(extras):
        session = LabelingSession(pdf=_pdf(blue / f"job_{n}.pdf", f"PROJECT {n}"))
        boxes = [_verified("receptacle", "duplex", "r", (10, 10, 30, 30)),
                 _verified(*extra, "c", (40, 10, 60, 30)),
                 _verified("luminaire", "troffer_2x4", "l", (70, 10, 120, 40))]
        session.save(0, revision=None, instances=boxes, rejected=[], metadata={"page_complete": True})
    pages = label_store.training_pages(store)
    assert len(pages) == 4
    manifest = build_manifest(pages, val_fraction=.25, test_fraction=.25)
    seen = {c for split in manifest["per_split_class_instances"].values() for c in split}
    assert seen == {"receptacle.duplex", "luminaire.troffer_2x4", "switch.three_way",
                    "sensor.occupancy_sensor_ceiling"}
    assert set(manifest["class_ids"]) <= seen
    every_device = label_schema.exhaustive_classes(label_schema.EXHAUSTIVE_FAMILIES)
    for row in manifest["source_images"]:
        assert set(row["exhaustive_families"]) == every_device


def test_browser_and_agents_open_the_same_page_with_the_same_proposals(tmp_path):
    blue = tmp_path / "blueprints_and_libraries" / "1_blueprints"
    pdf = _pdf(blue / "plans_1.pdf", "E1.1 POWER PLAN")
    (blue / "devices").mkdir()
    (blue / "devices" / "plans_DEVICES.json").write_text(json.dumps({
        "source_file": "plans_1.pdf",
        "devices": [
            {"pdf_page": 1, "device_type": "receptacle", "symbol_bbox_pts": [92, 192, 108, 208]},
            {"pdf_page": 1, "device_type": "hvac_device", "symbol_bbox_pts": [200, 100, 230, 130]},
            {"pdf_page": 1, "device_type": "switch", "placement": "text_label_proxy",
             "symbol_bbox_pts": [10, 10, 20, 20]},
        ]}))
    web = LabelingSession(pdf=pdf)                       # browser annotator, default store
    store = label_store.default_store()
    doc = label_store.load_document(web.out_dir)         # registered in the store
    assert web.out_dir.parent == store
    assert web.page_address(0) == doc.page_json(1)
    assert web._json_path(0) == doc.labels_path
    first, _, _ = web.page_document(0)
    assert [(i["symbol_family"], i["review_source"], i["verified_ground_truth"])
            for i in first["instances"]] == [("receptacle", "devices_json", False)]
    assert first["project_family_id"] == "file_1_blueprints_plans_1"

    agent, index, _ = label_store.open_page(doc.page_json(1))   # the agents' entry point
    second, revision, _ = agent.page_document(index)
    assert second == first
    # Proposals alone give training nothing; a human verifying one does.
    agent.save(index, revision=revision, instances=second["instances"], rejected=[])
    assert label_store.training_pages(store) == []
    _, revision, _ = web.page_document(0)
    box = dict(second["instances"][0], symbol_variant="duplex", review_status="accepted",
               review_source="human", verified_ground_truth=True, uncertain=False)
    web.save(0, revision=revision, instances=[box], rejected=[])
    [view] = label_store.training_pages(store)
    assert label_store.view_source(view)[0].folder == doc.folder
    assert label_store.view_source(view)[1] == 1
    trained = label_schema.load_training_page(view)["instances"][0]
    assert trained["symbol_family"] == "receptacle.duplex"
    assert (trained["device_family"], trained["device_variant"]) == ("receptacle", "duplex")


def test_store_scan_adds_devices_json_pages(tmp_path):
    import scripts_build_label_store as build
    blue = tmp_path / "blueprints_and_libraries" / "1_blueprints"
    _pdf(blue / "plans_1.pdf", "SITE PLAN")
    (blue / "devices").mkdir()
    (blue / "devices" / "plans_DEVICES.json").write_text(json.dumps({
        "source_file": "plans_1.pdf",
        "devices": [{"pdf_page": 1, "device_type": "light_fixture", "symbol_bbox_pts": [1, 1, 9, 9]}]}))
    store = tmp_path / "store"
    from blueprint_symbols.blueprint_sets import blueprint_sets
    stats = build.add_devices_pages(store, blueprint_sets(blue), dry_run=False)
    assert stats == {"documents": 1, "pages": 1}
    (doc,) = label_store.iter_documents(store)
    assert doc.pages_to_label() == [1] and doc.targets[1] == "devices_json:1"


def _sheet(path: Path, seed: int, size=(600, 400), text="GENERAL NOTES") -> Path:
    import cv2
    import numpy as np
    rng = np.random.default_rng(seed)
    img = np.full((size[1], size[0], 3), 255, np.uint8)
    for _ in range(25):
        x, y = int(rng.integers(0, size[0] - 60)), int(rng.integers(0, size[1] - 20))
        cv2.rectangle(img, (x, y), (x + int(rng.integers(20, 60)), y + int(rng.integers(8, 20))), (0, 0, 0), 2)
    cv2.putText(img, text, (20, size[1] - 20), cv2.FONT_HERSHEY_SIMPLEX, 1, (0, 0, 0), 2)
    cv2.imwrite(str(path), img)
    return path


def _page(path: Path, image: Path, group: str, *, empty: bool, instances=()) -> Path:
    import cv2
    h, w = cv2.imread(str(image)).shape[:2]
    path.write_text(json.dumps({
        "image_path": image.name, "image_width": w, "image_height": h,
        "project_family_id": group, "source_group_verified": True,
        "verified_no_symbols": empty, "exhaustive_families": [] if empty else label_schema.EXHAUSTIVE_FAMILIES,
        "instances": list(instances)}))
    return path


def test_repeated_symbol_free_standard_sheets_train_once(tmp_path):
    import cv2
    pages = []
    for n in range(4):                               # four projects with real labels
        img = _sheet(tmp_path / f"plan{n}.png", n, text=f"PLAN {n}")
        pages.append(_page(tmp_path / f"plan{n}.json", img, f"job{n}", empty=False,
                           instances=[_verified("receptacle", "duplex", box=(10, 10, 30, 30))]))
    # The same DOT standard sheet in three drawing sets, once at another DPI.
    std = _sheet(tmp_path / "std_a.png", 99)
    pages.append(_page(tmp_path / "std_a.json", std, "job0", empty=True))
    copy = tmp_path / "std_b.png"
    copy.write_bytes(std.read_bytes())
    pages.append(_page(tmp_path / "std_b.json", copy, "job1", empty=True))
    big = cv2.resize(cv2.imread(str(std)), (900, 600), interpolation=cv2.INTER_CUBIC)
    cv2.imwrite(str(tmp_path / "std_c.png"), big)
    pages.append(_page(tmp_path / "std_c.json", tmp_path / "std_c.png", "job2", empty=True))
    other = _sheet(tmp_path / "std_other.png", 7)          # a different empty sheet stays
    pages.append(_page(tmp_path / "std_other.json", other, "job3", empty=True))
    manifest = build_manifest(pages, val_fraction=.25, test_fraction=.25)
    kept = {Path(r["annotation"]).name for r in manifest["source_images"] if r["verified_no_symbols"]}
    assert kept == {"std_a.json", "std_other.json"}
    dropped = {Path(r["annotation"]).name for r in manifest["deduplicated_negative_pages"]}
    assert dropped == {"std_b.json", "std_c.json"}


def test_verified_labels_become_composite_drawings_of_their_device(tmp_path):
    import cv2
    import numpy as np
    from blueprint_symbols.verified_symbols import verified_symbol_glyphs
    img = np.full((200, 300, 3), 255, np.uint8)
    cv2.circle(img, (50, 50), 15, (0, 0, 0), 2)
    cv2.rectangle(img, (150, 40), (190, 60), (0, 0, 0), 2)
    cv2.imwrite(str(tmp_path / "p.png"), img)
    boxes = [_verified("receptacle", "duplex", "a", (32, 32, 68, 68)),
             _verified("luminaire", "troffer_2x4", "b", (146, 36, 194, 64)),
             dict(_verified("switch", "dimmer", "c", (200, 100, 230, 130)), verified_ground_truth=False,
                  review_status="unreviewed", uncertain=True)]
    train = _page(tmp_path / "p.json", tmp_path / "p.png", "train_only", empty=False, instances=boxes)
    data = json.loads(train.read_text()); data["exhaustive_families"] = []
    train.write_text(json.dumps(data))
    glyphs, report = verified_symbol_glyphs([train])
    assert report["per_device"] == {"receptacle.duplex": 1, "luminaire.troffer_2x4": 1}
    assert {(g.family, g.variant) for g in glyphs} == {("receptacle", "duplex"), ("luminaire", "troffer_2x4")}
    assert all(g.alpha.max() == 255 or g.alpha.max() > 150 for g in glyphs)
    # A project that could be held out for evaluation lends no drawings.
    data["annotation_completeness_verified"] = True
    train.write_text(json.dumps(data))
    glyphs, report = verified_symbol_glyphs([train])
    assert glyphs == [] and report["counts"]["skipped_holdout_able_project"] == 2


def test_everything_is_labeled_and_only_training_skips_the_evaluation_set(tmp_path):
    import scripts_build_label_store as build
    blue = tmp_path / "blueprints_and_libraries" / "1_blueprints"
    from blueprint_symbols.blueprint_sets import blueprint_sets
    eval_pdf = _pdf(blue / "evaluation" / "eval_set.pdf", "E2.1 LIGHTING PLAN")
    side_pdf = _pdf(blue / "side_set.pdf", "SITE")
    _pdf(blue / "other_folder" / "elsewhere.pdf", "SITE")         # in neither set
    (blue / "evaluation" / "devices").mkdir(parents=True)
    (blue / "evaluation" / "devices" / "Eval_DEVICES.json").write_text(json.dumps({
        "source_file": "eval_set.pdf",
        "devices": [{"pdf_page": 1, "device_type": "light_fixture", "symbol_bbox_pts": [92, 192, 108, 208]}]}))
    (blue / "devices").mkdir()
    (blue / "devices" / "Side_DEVICES.json").write_text(json.dumps({
        "source_file": "side_set.pdf",
        "devices": [{"pdf_page": 1, "device_type": "receptacle", "symbol_bbox_pts": [92, 192, 108, 208]}]}))
    (blue / "other_folder" / "Elsewhere_DEVICES.json").write_text(json.dumps({  # not a devices/ folder
        "source_file": "elsewhere.pdf",
        "devices": [{"pdf_page": 1, "device_type": "receptacle", "symbol_bbox_pts": [92, 192, 108, 208]}]}))
    store = label_store.default_store()

    stats = build.add_devices_pages(store, blueprint_sets(blue), dry_run=False)
    assert stats["documents"] == 2 and stats["evaluation_documents"] == 1
    docs = {d.source.name: d for d in label_store.iter_documents(store)}
    assert docs["eval_set.pdf"].meta["evaluation"] is True
    assert docs["side_set.pdf"].meta["evaluation"] is False

    for pdf, family in ((eval_pdf, "luminaire"), (side_pdf, "receptacle")):
        web = LabelingSession(pdf=pdf)                       # proposals from the folders' DEVICES.json
        page, revision, _ = web.page_document(0)
        assert [i["symbol_family"] for i in page["instances"]] == [family]
        box = dict(page["instances"][0], symbol_variant="duplex" if family == "receptacle" else "troffer_2x4",
                   review_status="accepted", review_source="human", verified_ground_truth=True,
                   uncertain=False)
        web.save(0, revision=revision, instances=[box], rejected=[])

    split = label_store.labeled_pages(store)
    assert [p.parent.name.startswith("eval_set") for p in split["evaluation"]] == [True]
    assert [p.parent.name.startswith("side_set") for p in split["train"]] == [True]
    assert label_store.training_pages(store) == split["train"]
    # Moving the PDF into evaluation/ moves its labelled pages there, at once.
    side_pdf.rename(blue / "evaluation" / "side_set.pdf")
    moved = label_store.labeled_pages(store, data_root=blue.parent)
    assert moved["train"] == [] and len(moved["evaluation"]) == 2


def test_training_split_skips_documents_whose_pdf_is_gone(tmp_path):
    blue = tmp_path / "blueprints_and_libraries" / "1_blueprints"
    kept = _pdf(blue / "kept.pdf", "E1 LIGHTING")
    gone = _pdf(blue / "gone.pdf", "E2 LIGHTING")
    store = label_store.default_store()
    for pdf, family in ((kept, "receptacle"), (gone, "receptacle")):
        label_store.create_document(store, pdf, targets={1: "test"})
        web = LabelingSession(pdf=pdf)
        page, revision, _ = web.page_document(0)
        box = {**_verified(family, "duplex"), "bbox_xyxy": [10, 10, 30, 30]}
        web.save(0, revision=revision, instances=[box], rejected=[])
    (blue / "_set_aside").mkdir()
    gone.rename(blue / "_set_aside" / "gone.pdf")          # neither training nor evaluation now
    split = label_store.labeled_pages(store, data_root=blue.parent)
    assert [p.parent.name.startswith("kept") for p in split["train"]] == [True]
    assert split["evaluation"] == []
