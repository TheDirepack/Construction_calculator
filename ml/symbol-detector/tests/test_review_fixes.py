"""Regression tests for the label-format, training-data and annotator fixes.

No PyTorch needed: these run on the laptop."""
import hashlib
import json
import re
from pathlib import Path
from types import SimpleNamespace

import cv2
import numpy as np
import pytest

from blueprint_symbols import label_schema
from blueprint_symbols.dataset_manifest import trusted_training_instance

ROOT = Path(__file__).resolve().parents[1]


def _verified(family, box, ident="b1"):
    return {"instance_id": ident, "symbol_family": family.split(".")[0],
            "symbol_variant": family.split(".")[1], "bbox_xyxy": list(box),
            "review_status": "accepted", "review_source": "human",
            "verified_ground_truth": True, "uncertain": False}


def _page(tmp_path, name, boxes, *, group="g1", size=(400, 300), complete=True):
    """A Format-2 labels file (one page) with a drawn square per box; returns its page ref."""
    w, h = size
    img = np.full((h, w, 3), 255, np.uint8)
    for b in boxes:
        x0, y0, x1, y1 = map(int, b["bbox_xyxy"])
        cv2.rectangle(img, (x0 + 2, y0 + 2), (x1 - 3, y1 - 3), (0, 0, 0), 2)
    folder = tmp_path / name
    folder.mkdir(parents=True)
    cv2.imwrite(str(folder / "page.png"), img)
    page = {"format_version": 1, "project_family_id": group, "source_group_verified": True,
            "image_path": "page.png", "image_width": w, "image_height": h,
            "coordinate_system": label_schema.COORDINATE_SYSTEM,
            **label_schema.completeness(page_complete=complete), "instances": boxes}
    labels = label_schema.write_labels_file(folder / f"{name}_labels.json", {3: page}, imported_by="test")
    return label_schema.page_ref(labels, 3)


def _manifest(refs_by_split):
    """A minimal source manifest over page refs (what validate_manifest_sources checks)."""
    rows, splits, groups = [], {}, {}
    for split, refs in refs_by_split.items():
        splits[split] = [str(Path(r).resolve()) for r in refs]
        groups[split] = []
        for r in refs:
            page = label_schema.load_training_page(r)
            image = Path(page["image_path"])
            groups[split].append(page["project_family_id"])
            rows.append({"annotation": str(Path(r).resolve()), "project_group": page["project_family_id"],
                         "annotation_sha256": label_schema.annotation_sha256(r), "image": str(image),
                         "image_sha256": hashlib.sha256(image.read_bytes()).hexdigest()})
    classes = sorted({i["symbol_family"] for r in rows
                      for i in label_schema.load_training_page(r["annotation"])["instances"]})
    return {"splits": splits, "split_groups": groups, "source_images": rows,
            "class_ids": {c: n for n, c in enumerate(classes)}}


# ------------------------------------------------------------------ 1. Format-2 refs

def test_score_full_pages_reads_labels_file_refs(tmp_path):
    from scripts_score_full_pages import score_full_pages
    train = _page(tmp_path, "train", [_verified("receptacle.duplex", (20, 20, 60, 60))], group="g0")
    val = _page(tmp_path, "val", [_verified("receptacle.duplex", (100, 100, 140, 140))], group="g1")
    test = _page(tmp_path, "test", [_verified("receptacle.duplex", (100, 100, 140, 140))], group="g2")
    manifest = _manifest({"train": [train], "val": [val], "test": [test]})
    (tmp_path / "m.json").write_text(json.dumps(manifest))
    (tmp_path / "p.json").write_text(json.dumps({"predictions": [
        {"annotation_path": str(test), "instances": [
            {"symbol_family": "receptacle.duplex", "bbox_xyxy": [101, 101, 140, 140], "confidence": .9}]}]}))
    result = score_full_pages(tmp_path / "m.json", tmp_path / "p.json", split="test")
    assert result["overall"]["tp"] == 1 and result["overall"]["fn"] == 0


def test_predict_full_pages_reads_labels_file_refs(tmp_path):
    from scripts_predict_full_pages import predict_split
    train = _page(tmp_path, "train", [_verified("receptacle.duplex", (20, 20, 60, 60))], group="g0")
    val = _page(tmp_path, "val", [_verified("receptacle.duplex", (100, 100, 140, 140))], group="g1")
    test = _page(tmp_path, "test", [_verified("receptacle.duplex", (100, 100, 140, 140))], group="g2")
    manifest = _manifest({"train": [train], "val": [val], "test": [test]})

    class Arr:
        def __init__(self, a): self.a = np.asarray(a)
        def cpu(self): return self
        def numpy(self): return self.a

    class Model:
        names = {0: "receptacle.duplex"}

        def predict(self, source, **_):
            return [SimpleNamespace(boxes=SimpleNamespace(xyxy=Arr([[100, 100, 140, 140]]),
                                                          cls=Arr([0]), conf=Arr([.9])))]

    out = predict_split(Model(), manifest, split="val", scales=(1.,), confidence=.1, device="cpu")
    assert out["predictions"][0]["annotation_path"] == str(Path(val).resolve())
    assert out["predictions"][0]["instances"]


def test_ssl_views_read_labels_file_refs(tmp_path):
    from blueprint_symbols.ssl_data import SSLViewDataset
    ref = _page(tmp_path, "train", [_verified("receptacle.duplex", (20, 20, 60, 60))])
    page = label_schema.load_training_page(ref)
    data = SSLViewDataset({"sources": [{"image_path": page["image_path"], "group": "g1",
                                        "image_sha256": "x", "annotation": str(ref),
                                        "kind": "labeled_training"}]}, size=128, items_per_epoch=8)
    for i in range(8):
        clean, damaged, meta = data[i]
        assert clean.shape == (128, 128, 3)


# ------------------------------------------------------------------ 1b. importers write Format-2

def test_write_labels_file_is_a_document_labels_file(tmp_path):
    from annotator import document_labels
    assert (label_schema.LABELS_KIND, label_schema.LABELS_FORMAT_VERSION) == (
        document_labels.KIND, document_labels.FORMAT_VERSION)
    ref = _page(tmp_path, "doc", [_verified("switch.three_way", (10, 10, 40, 40))])
    labels, page = label_schema.parse_page_ref(ref)
    assert document_labels.pages(labels.parent, "doc")[page]["instances"]
    assert label_schema.labels_training_ready(labels)
    assert label_schema.expand_page_paths([labels]) == [ref]
    assert label_schema.labels_file_for(tmp_path / "x.json").name == "x_labels.json"


def test_floorplancad_import_writes_labels_files(tmp_path):
    from adapters.import_floorplancad_fiftyone import convert
    (tmp_path / "src" / "data").mkdir(parents=True)
    rgba = np.zeros((100, 100, 4), np.uint8)
    rgba[20:40, 20:40] = (0, 0, 0, 255)
    cv2.imwrite(str(tmp_path / "src" / "data" / "0001-0002.png"), rgba)
    (tmp_path / "src" / "samples.json").write_text(json.dumps({"samples": [{
        "filepath": "data/0001-0002.png", "ground_truth": {"detections": [
            {"label": "sym", "bounding_box": [.2, .2, .2, .2]}]}}]}))
    convert(tmp_path / "src", tmp_path / "out", {"sym": "receptacle.duplex"})
    labels = tmp_path / "out" / "fpcad_0001-0002_labels.json"
    assert not list((tmp_path / "out").glob("fpcad_*[0-9].json"))         # no per-page JSON
    [ref] = label_schema.expand_page_paths([labels])
    page = label_schema.load_training_page(ref)
    assert Path(page["image_path"]).is_file()
    assert page["instances"][0]["symbol_family"] == "receptacle.duplex"


def test_coco_import_writes_labels_files(tmp_path):
    from blueprint_symbols.coco_import import convert_coco
    cv2.imwrite(str(tmp_path / "page.png"), np.full((41, 73, 3), 255, np.uint8))
    (tmp_path / "coco.json").write_text(json.dumps({
        "images": [{"id": 1, "file_name": "page.png", "width": 73, "height": 41}],
        "categories": [{"id": 4, "name": "switch"}],
        "annotations": [{"id": 11, "image_id": 1, "category_id": 4, "bbox": [3, 4, 7, 8]}]}))
    convert_coco(tmp_path / "coco.json", tmp_path, tmp_path / "out", {"switch": "switch.three_way"})
    assert sorted(p.name for p in (tmp_path / "out").iterdir()) == ["image_00000001_labels.json"]
    [ref] = label_schema.expand_page_paths([tmp_path / "out" / "image_00000001_labels.json"])
    assert label_schema.load_training_page(ref)["instances"][0]["symbol_family"] == "switch.three_way"


def test_no_importer_writes_per_page_json():
    """DXF/SVG/COCO/FloorPlanCAD importers go through write_labels_file."""
    for rel in ("scripts_autolabel_dxf.py", "scripts_import_svg.py", "blueprint_symbols/coco_import.py",
                "adapters/import_floorplancad_fiftyone.py", "adapters/import_cubicasa5k.py",
                "scripts_import_primitives.py"):
        text = (ROOT / rel).read_text()
        assert "write_labels_file" in text, rel
        assert not re.search(r"\.write_text\(json\.dumps\((output|result|doc)\b", text), rel


# ------------------------------------------------------------------ 2. renamed device ids

def test_renamed_dotted_device_ids_keep_their_meaning():
    for old, new in label_schema._NAMING["renamed"].items():
        assert label_schema.instance_class({"symbol_family": old}) == new
        family, variant = old.split(".")
        assert label_schema.instance_class({"symbol_family": family, "symbol_variant": variant}) == new
    assert label_schema.instance_class({"symbol_family": "receptacle.no_such"}) == label_schema.UNMAPPED


# ------------------------------------------------------------------ 3. photo documents

def test_photo_documents_train_and_are_pushed(tmp_path):
    from annotator import label_store
    bp = tmp_path / "blueprints_and_libraries" / "1_blueprints"
    (bp / "devices").mkdir(parents=True)
    photo_partial = tmp_path / "photo_partial.png"
    cv2.imwrite(str(photo_partial), np.full((120, 160, 3), 255, np.uint8))
    partial_doc = label_store.create_document(bp, photo_partial, image_page={
        "instances": [_verified("receptacle.duplex", (10, 10, 40, 40))]})
    assert partial_doc.kind == "image"
    assert label_store.labeled_pages(bp)["train"] == []
    assert label_store.stage_push(bp, tmp_path / "stage_empty")[2] == 0

    photo = tmp_path / "photo.png"
    cv2.imwrite(str(photo), np.full((120, 160, 3), 240, np.uint8))
    doc = label_store.create_document(bp, photo, image_page={
        **label_schema.completeness(page_complete=True),
        "instances": [_verified("receptacle.duplex", (10, 10, 40, 40))]})
    assert doc.kind == "image"
    assert label_store.document_role(doc) == "train"
    assert label_store.labeled_pages(bp)["train"] == [doc.page_json(1)]
    staged, images, count = label_store.stage_push(bp, tmp_path / "stage")
    assert count == 1 and images == [doc.source.relative_to(bp)]
    meta = json.loads((tmp_path / "stage" / doc.folder.relative_to(bp) / "document.json").read_text())
    assert not Path(meta["source"]).is_absolute()


# ------------------------------------------------------------------ 4. masked, not dropped, tiles

def test_ignore_regions_are_painted_out_and_targets_kept():
    from scripts_export_yolo_tiles import mask_ignored
    img = np.full((64, 64, 3), 255, np.uint8)
    img[10:20, 10:20] = 0                    # pending box: painted out
    img[40:50, 40:50] = 0                    # verified target
    target = [(0, (38., 38., 52., 52.))]
    out, n = mask_ignored(img, [[8., 8., 22., 22.], [36., 36., 54., 54.]], target)
    assert n == 2
    assert out[10:20, 10:20].min() == 255            # ignore region gone
    assert out[40:50, 40:50].max() == 0              # target untouched, even under an ignore box


def test_export_keeps_tiles_with_ignore_regions():
    text = (ROOT / "scripts_export_yolo_tiles.py").read_text()
    assert "skipped_ignore_regions" not in text


# ------------------------------------------------------------------ 5/6. scale, flips, rotations

def test_training_tiles_keep_symbols_near_native_size(tmp_path):
    from blueprint_symbols.raster_training import RasterTileDataset, read_records
    from scripts_export_yolo_tiles import (MIN_SYMBOL_PIXELS, PIXEL_BANDS, TRAIN_RANDOM_SCALES,
                                           TRAIN_SCALE_LIMITS)
    boxes = [_verified("receptacle.duplex", (x, 100, x + 32, 132), f"b{x}") for x in (100, 400, 700)]
    ref = _page(tmp_path, "p", boxes, size=(1200, 900))
    data = RasterTileDataset(read_records([ref]), {"receptacle.duplex": 0}, size=512,
                             virtual_tiles_per_image=60, seed=3, source_scales=TRAIN_RANDOM_SCALES,
                             target_symbol_pixels=PIXEL_BANDS, scale_limits=TRAIN_SCALE_LIMITS,
                             min_symbol_pixels=MIN_SYMBOL_PIXELS)
    sizes = []
    for i in range(len(data)):
        _, targets, info = data.sample(i)
        assert TRAIN_SCALE_LIMITS[0] - 1e-9 <= info["tile"].source_scale <= max(
            TRAIN_SCALE_LIMITS[1], TRAIN_RANDOM_SCALES[1]) + 1e-9
        sizes += [max(b[2] - b[0], b[3] - b[1]) for _, b in targets]
    assert min(sizes) >= MIN_SYMBOL_PIXELS - .5      # a 32 px symbol never becomes a 2-5 px speck
    assert 20 <= float(np.median(sizes)) <= 60


def test_downscaled_tiles_keep_one_pixel_lines():
    from blueprint_symbols.multiscale import ScaleTile, sample_tile
    img = np.full((600, 600, 3), 255, np.uint8)
    img[:, 300] = 0                    # a 1 px line between bilinear sample points (3j+1)
    tile = ScaleTile(0., 0., 3., 128, 600, 600)
    column_min = sample_tile(img, tile)[..., 0].min(axis=0)
    assert column_min.min() < 225                            # still visible after a 3x shrink


def test_rotate90_moves_boxes_with_the_ink():
    from blueprint_symbols.variants import rotate90
    img = np.full((100, 100, 3), 255, np.uint8)
    img[10:30, 60:90] = 0
    box = (60., 10., 90., 30.)
    for k in range(4):
        out, [(_, (x0, y0, x1, y1))] = rotate90(img, [(0, box)], k)
        ys, xs = np.where(out[..., 0] == 0)
        assert (x0, y0, x1, y1) == (xs.min(), ys.min(), xs.max() + 1, ys.max() + 1)


def test_rotation_policy_comes_from_the_catalog_and_never_mirrors():
    assert label_schema.MIRROR_ALLOWED is False
    assert label_schema.allowed_rotations("luminaire.troffer_2x4") == (0, 90, 180, 270)
    assert label_schema.allowed_rotations("luminaire.exit_sign_single_face_single_arrow") == (0, 90, 270)
    assert label_schema.allowed_rotations("switch.three_way") == (0, 90, 270)    # "S3": not upside down
    assert label_schema.allowed_rotations("receptacle.duplex") == (0, 90, 180, 270)


def test_train_variants_rotate_only_as_allowed_and_keep_boxes_on_ink():
    from scripts_export_yolo_tiles import train_variant
    img = np.full((512, 512, 3), 255, np.uint8)
    cv2.rectangle(img, (200, 200), (240, 230), (0, 0, 0), 2)
    cv2.putText(img, "S3", (205, 222), cv2.FONT_HERSHEY_SIMPLEX, .5, (0, 0, 0), 1)
    targets = [(0, (198., 198., 243., 233.))]
    seen = set()
    for i in range(40):
        out, moved, damage = train_variant(img, targets, {"switch.three_way": 0}, base_index=i,
                                           variant_index=1 + i % 3, seed=5)
        seen.add(damage["rotation_degrees"])
        assert len(moved) == 1
        x0, y0, x1, y1 = map(int, moved[0][1])
        gray = cv2.cvtColor(out, cv2.COLOR_BGR2GRAY)
        assert gray[y0:y1, x0:x1].min() < 160                 # the label still sits on ink
    assert seen <= {0, 90, 270} and len(seen) > 1


def test_flips_are_refused_and_launcher_has_no_downscaled_eval(tmp_path):
    from scripts_train_ultralytics import plan
    (tmp_path / "data.yaml").write_text("names: {}\n")
    with pytest.raises(ValueError, match="mirror"):
        plan(tmp_path / "data.yaml", fliplr=.5)
    script = (ROOT / "remote" / "run_phase2_train.sh").read_text()
    assert "--eval-scales 1 " in script and "16" not in re.findall(r"--eval-scales (\S+)", script)[0]
    assert "--fliplr 0 --flipud 0" in script


def test_vector_and_scan_variants_keep_symbols_visible():
    from blueprint_symbols.variants import make_variant, symbols_survive
    img = np.full((256, 256, 3), 255, np.uint8)
    cv2.rectangle(img, (100, 100), (140, 130), (0, 0, 0), 1)            # 1 px pen
    boxes = [(0, (99., 99., 142., 132.))]
    for name in ("vector_random", "scan_random"):
        for seed in range(30):
            out, _ = make_variant(img, np.random.default_rng(seed), name)
            if name == "vector_random":
                assert symbols_survive(img, out, boxes), seed


# ------------------------------------------------------------------ 6. merging across tiles

def test_merge_drops_partial_and_cross_class_duplicates():
    from blueprint_symbols.multiscale import ScaleTile, touches_inner_edge
    from blueprint_symbols.postprocess import Detection, merge_tiles
    from blueprint_symbols.pseudolabel import consolidate_predictions
    full = Detection(0, .9, (100., 100., 140., 140.))
    partial = Detection(0, .6, (100., 100., 120., 140.))       # cut by a tile edge
    other_class = Detection(1, .7, (101., 101., 140., 140.))
    assert merge_tiles([full, partial, other_class]) == [full]
    merged = consolidate_predictions([
        {"symbol_family": "a", "bbox_xyxy": [100, 100, 140, 140], "confidence": .9, "source_scale": 1.},
        {"symbol_family": "a", "bbox_xyxy": [100, 100, 118, 140], "confidence": .5, "source_scale": 1.},
        {"symbol_family": "b", "bbox_xyxy": [101, 101, 140, 140], "confidence": .6, "source_scale": 1.}])
    assert len(merged) == 1 and merged[0]["alternative_labels"][0]["symbol_family"] == "b"
    inner = ScaleTile(352., 0., 1., 512, 2000, 512)
    assert touches_inner_edge(inner, (0., 100., 30., 140.))          # left edge inside the page
    assert not touches_inner_edge(inner, (40., 0., 80., 40.))        # top edge is the page edge


# ------------------------------------------------------------------ 7. annotator web

def test_edit_mode_image_path_must_be_an_image(tmp_path):
    from annotator.web import LabelingError, _resolve_edit_image
    secret = tmp_path / "id_ed25519"
    secret.write_text("PRIVATE KEY")
    fake = tmp_path / "notes.png"
    fake.write_text("not an image")
    real = tmp_path / "page.png"
    cv2.imwrite(str(real), np.full((8, 8, 3), 255, np.uint8))
    page = tmp_path / "sub" / "p.json"
    page.parent.mkdir()
    for bad in ("../id_ed25519", str(secret), "../notes.png"):
        with pytest.raises(LabelingError):
            _resolve_edit_image(page, bad)
    assert _resolve_edit_image(page, "../page.png") == real.resolve()


def test_security_headers():
    text = (ROOT / "annotator" / "web.py").read_text()
    for header in ("X-Frame-Options", "Referrer-Policy", "Cross-Origin-Opener-Policy",
                   "Content-Security-Policy", "X-Content-Type-Options"):
        assert f'"{header}"' in text


def test_proposal_pixels_follow_both_renders_pixel_caps():
    from annotator.web import MAX_PDF_PIXELS, proposal_pixel_factor
    big = SimpleNamespace(rect=SimpleNamespace(width=48 * 72., height=36 * 72.))   # 48x36 in sheet
    # The annotator caps this sheet at 30M px; a scanner at 200 DPI capped at 20M px.
    ours = (MAX_PDF_PIXELS / (48 * 72 * 36 * 72)) ** .5
    theirs = (20_000_000 / (48 * 72 * 36 * 72)) ** .5
    assert proposal_pixel_factor(big, 150, 200, 20_000_000) == pytest.approx(ours / theirs)
    assert proposal_pixel_factor(big, 150, 200, 20_000_000) != pytest.approx(150 / 200)
    small = SimpleNamespace(rect=SimpleNamespace(width=612., height=792.))
    assert proposal_pixel_factor(small, 150, 200) == pytest.approx(150 / 200)


def test_document_record_names_proposals_like_the_annotator():
    from annotator.web import proposal_device
    # a legend description picks the exact device in its family
    assert proposal_device({"device_type": "receptacle",
                            "description": "GFCI DUPLEX RECEPTACLE"}) == ("receptacle", "duplex_gfci")
    text = (ROOT / "annotator" / "web.py").read_text()
    start = text.index("def document_record")
    body = text[start:text.index("\n    def ", start + 10)]
    assert "proposal_device(device)" in body and "canonical_device(\n" not in body


# ------------------------------------------------------------------ 8. agent guide

def test_vlm_guide_vocabulary_is_the_catalog():
    catalog = json.loads((ROOT / "annotator" / "electrical_device_catalog.json").read_text())
    text = (ROOT / "annotator" / "VLM_LABELING.md").read_text()
    table = dict(re.findall(r"^\| `([a-z_]+)` \| ([a-z0-9_, ]+) \|$", text, re.M))
    expected: dict[str, list[str]] = {}
    for d in catalog["devices"]:
        expected.setdefault(d["symbol_family"], []).append(d["symbol_variant"])
    assert {f: v.split(", ") for f, v in table.items()} == expected
    assert "_labels.json" in text
