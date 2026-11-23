"""Regression tests for upgraded manual labeling features (existing JSON, oriented boxes, extra snap vectors, walls, and full attributes)."""
from __future__ import annotations

import http.client
import json
import math
import threading
from http.server import ThreadingHTTPServer
from pathlib import Path

import pytest
from PIL import Image

from annotator.model import Instance, RotatedBox, Sheet
from annotator.web import (
    LabelingError,
    LabelingSession,
    RevisionConflict,
    make_handler,
    sha256_file,
    validate_instances,
)


def _png(path: Path, width: int = 400, height: int = 300) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.new("RGB", (width, height), (245, 245, 245)).save(path, format="PNG")
    return path


def test_edit_existing_legacy_json_and_directory_switching(tmp_path: Path):
    img1 = _png(tmp_path / "images" / "p1.png")
    img2 = _png(tmp_path / "images" / "p2.png")
    j1 = tmp_path / "sheet_01.json"
    j2 = tmp_path / "sheet_02.json"
    j1.write_text(json.dumps({
        "format_version": 1,
        "source_id": "legacy_01",
        "document_id": "E1.01",
        "page_index": 0,
        "source_type": "cad_raster",
        "image_path": "images/p1.png",
        "image_sha256": "REPLACE_WITH_REAL_HASH",
        "image_width": 400,
        "image_height": 300,
        "coordinate_system": "original_page_pixels_top_left_y_down",
        "instances": [{
            "instance_id": "fixture_0001",
            "symbol_family": "receptacle",
            "symbol_variant": "duplex",
            "bbox_xyxy": [100.0, 100.0, 140.0, 140.0],
            "anchors": {"circle_center": {"xy": [120.0, 120.0], "visible": True, "annotated": True}},
            "attributes_from_text": ["GFI"],
        }],
    }))
    j2.write_text(json.dumps({
        "format_version": 1,
        "source_id": "legacy_02",
        "document_id": "E1.02",
        "page_index": 1,
        "image_path": "images/p2.png",
        "image_sha256": sha256_file(img2),
        "image_width": 400,
        "image_height": 300,
        "coordinate_system": "original_page_pixels_top_left_y_down",
        "instances": [],
    }))

    session = LabelingSession(edit=tmp_path)
    assert session.page_count == 2
    p0 = session.page_state(0)
    assert p0["json_filename"] == "sheet_01.json"
    assert len(p0["sibling_files"]) == 2
    assert p0["json_path"] == str(j1)
    assert p0["document"]["image_sha256"] == sha256_file(img1)

    inst = dict(p0["document"]["instances"][0])
    inst["device_attributes"] = {"voltage_v": 120, "circuit_label": "LP1-14", "gfci": True}
    inst["snap_vector"] = {"origin": [120.0, 120.0], "direction": [0.0, -5.0]}
    rev = session.save(0, revision=p0["revision"], instances=[inst], rejected=[], metadata={"notes": "checked"})
    assert len(rev) == 64

    disk = json.loads(j1.read_text(encoding="utf-8"))
    saved_inst = disk["instances"][0]
    assert saved_inst["discipline"] == "electrical"
    assert saved_inst["device_type"] == "receptacle"
    assert saved_inst["device_attributes"]["circuit_label"] == "LP1-14"
    assert math.hypot(*saved_inst["snap_vector"]["direction"]) == pytest.approx(1.0, abs=1e-3)
    assert disk["notes"] == "checked"
    assert disk["annotation_completeness"] == "partial"
    assert disk["annotation_completeness_verified"] is False

    # Verify Sheet.load roundtrip keeps all fields intact
    loaded = Sheet.load(j1)
    assert loaded.instances[0].device_attributes["voltage_v"] == 120


def test_walls_api_snap_and_oriented_box_validation(tmp_path: Path):
    _png(tmp_path / "images" / "p1.png")
    j1 = tmp_path / "sheet_01.json"
    j1.write_text(json.dumps({
        "format_version": 1,
        "image_path": "images/p1.png",
        "image_width": 400,
        "image_height": 300,
        "coordinate_system": "original_page_pixels_top_left_y_down",
        "instances": [],
    }))
    walls = tmp_path / "walls.json"
    walls.write_text(json.dumps({"walls": [{"start": [20, 100], "end": [380, 100]}]}))

    session = LabelingSession(edit=j1, walls=walls)
    server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(session))
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        conn = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=10)
        rot = RotatedBox(120.0, 120.0, 30.0, 20.0, 30.0)
        inst = {
            "instance_id": "r1",
            "symbol_family": "receptacle",
            "symbol_variant": "duplex",
            "bbox_xyxy": [round(v, 3) for v in rot.aabb()],
            "bbox_rotated": rot.to_dict(),
            "anchors": {"circle_center": {"xy": [120.0, 120.0], "visible": True, "annotated": True}},
            "snap_vector": {"origin": [120.0, 120.0], "direction": [0.0, -1.0]},
            "review_status": "accepted",
            "review_source": "human",
            "verified_ground_truth": True,
            "uncertain": False,
        }
        conn.request(
            "POST",
            "/api/snap?page=0",
            body=json.dumps({"instance": inst}),
            headers={"X-Labeler-Token": session.token, "Content-Type": "application/json"},
        )
        resp = conn.getresponse()
        body = json.loads(resp.read())
        conn.close()
        assert resp.status == 200
        assert body["snap"]["kind"] == "wall_centerline_intersection"
        assert body["snap"]["point"] == pytest.approx([120.0, 100.0], abs=0.05)
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=10)


def test_validate_anchors_snap_vector_and_device_attributes():
    base = {
        "instance_id": "ok_1",
        "symbol_family": "receptacle",
        "symbol_variant": "duplex",
        "bbox_xyxy": [10.0, 10.0, 40.0, 40.0],
    }
    validate_instances([base], 200, 200)
    with pytest.raises(LabelingError):
        validate_instances([dict(base, anchors={"circle_center": {"xy": [250.0, 10.0]}})], 200, 200)
    with pytest.raises(LabelingError):
        validate_instances([dict(base, snap_vector={"origin": [20.0, 20.0], "direction": [0.0, 0.0]})], 200, 200)
    with pytest.raises(LabelingError):
        validate_instances([dict(base, device_attributes={"voltage_v": -10})], 200, 200)


def test_edited_image_changed_after_open_refuses_stale_labels(tmp_path: Path):
    from annotator.web import RevisionConflict

    img = _png(tmp_path / "original.png")
    label_path = tmp_path / "labels.json"
    label_path.write_text(json.dumps({
        "image_path": "original.png", "image_width": 400, "image_height": 300,
        "coordinate_system": "original_page_pixels_top_left_y_down", "instances": [],
    }))
    session = LabelingSession(edit=label_path)
    before = session.page_state(0)
    assert before["json_path"] == str(label_path)
    # Size and dimensions stay unchanged; changed pixels must invalidate the cached image.
    Image.new("RGB", (400, 300), (30, 30, 30)).save(img, format="PNG")
    with pytest.raises(RevisionConflict, match="source image changed"):
        session.page_state(0)


def test_edited_image_reference_changed_after_open_refuses_stale_geometry(tmp_path: Path):
    _png(tmp_path / "first.png")
    _png(tmp_path / "second.png")
    label_path = tmp_path / "labels.json"
    data = {
        "image_path": "first.png", "image_width": 400, "image_height": 300,
        "coordinate_system": "original_page_pixels_top_left_y_down", "instances": [],
    }
    label_path.write_text(json.dumps(data))
    session = LabelingSession(edit=label_path)
    session.page_state(0)
    data["image_path"] = "second.png"
    label_path.write_text(json.dumps(data))
    # A changed JSON image reference must not silently reuse the previous raster.
    with pytest.raises(RevisionConflict, match="source image reference changed"):
        session.page_state(0)
