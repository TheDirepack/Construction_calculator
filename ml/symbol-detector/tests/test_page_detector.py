"""Tests for deterministic local full-page inference and the Evaluate page UI."""
from __future__ import annotations

import hashlib
import json
import threading
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

import cv2
import numpy as np
import pytest

from annotator import document_labels
from annotator.web import LabelingSession, make_handler
from blueprint_symbols.label_schema import pending_instance
from blueprint_symbols.page_detector import (
    LocalPageDetector,
    decode_onnx_tile_outputs,
    describe_detector_weights,
    nms_xyxy,
    resolve_detector_weights,
)


def test_nms_xyxy_is_deterministic_and_breaks_ties_stably():
    boxes = np.array([
        [10.0, 10.0, 50.0, 50.0],
        [12.0, 12.0, 52.0, 52.0],   # high IoU with 0, identical score
        [200.0, 200.0, 240.0, 240.0],
    ])
    scores = np.array([0.8, 0.8, 0.7])
    assert nms_xyxy(boxes, scores, 0.5) == [0, 2]
    assert nms_xyxy(np.zeros((0, 4)), np.zeros((0,)), 0.5) == []


def test_decode_onnx_tile_outputs_supports_raw_and_end2end_layouts():
    # Raw (4 + nc, anchors) layout with class-aware NMS:
    # anchor 0: class 0 at [100, 100, 40, 40] score 0.90
    # anchor 1: class 0 overlapping [102, 102, 40, 40] score 0.60 -> suppressed
    # anchor 2: class 1 same spot [100, 100, 40, 40] score 0.75 -> kept for cross-class merge
    # anchor 3: class 0 below threshold -> dropped
    raw = np.zeros((6, 4), dtype=np.float32)
    raw[:, 0] = [100.0, 100.0, 40.0, 40.0, 0.90, 0.05]
    raw[:, 1] = [102.0, 102.0, 40.0, 40.0, 0.60, 0.05]
    raw[:, 2] = [100.0, 100.0, 40.0, 40.0, 0.05, 0.75]
    raw[:, 3] = [200.0, 200.0, 30.0, 30.0, 0.15, 0.10]

    dets = decode_onnx_tile_outputs(raw[None], confidence=0.25, iou_threshold=0.50)
    assert len(dets) == 2
    assert dets[0][1] == 0 and pytest.approx(dets[0][2], abs=1e-5) == 0.90
    assert dets[1][1] == 1 and pytest.approx(dets[1][2], abs=1e-5) == 0.75

    # End-to-end (max_det, 6) layout: [x1, y1, x2, y2, score, cls]
    e2e = np.array([
        [10.0, 20.0, 40.0, 50.0, 0.85, 2.0],
        [60.0, 60.0, 90.0, 90.0, 0.10, 1.0],
        [50.0, 50.0, 50.0, 60.0, 0.95, 0.0],  # degenerate width -> skipped
    ], dtype=np.float32)
    e2e_dets = decode_onnx_tile_outputs(e2e[None], confidence=0.25)
    assert e2e_dets == [((10.0, 20.0, 40.0, 50.0), 2, pytest.approx(0.85, abs=1e-5))]


def test_resolve_and_describe_detector_weights(tmp_path, monkeypatch):
    monkeypatch.delenv("BPS_DETECTOR_WEIGHTS", raising=False)
    assert resolve_detector_weights(work_dir=tmp_path) is None
    assert describe_detector_weights(work_dir=tmp_path)["available"] is False

    missing = tmp_path / "does_not_exist.onnx"
    with pytest.raises(FileNotFoundError):
        resolve_detector_weights(missing, work_dir=tmp_path)
    assert describe_detector_weights(missing, work_dir=tmp_path)["available"] is False

    run_dir = tmp_path / "models" / "phase2_v8_init"
    run_dir.mkdir(parents=True)
    pt = run_dir / "best.pt"
    onnx = run_dir / "best.onnx"
    pt.write_bytes(b"pt-bytes")
    onnx.write_bytes(b"onnx-bytes")
    pt_sha = hashlib.sha256(b"pt-bytes").hexdigest()
    Path(f"{onnx}.json").write_text(json.dumps({
        "epoch": 22,
        "best_fitness": 0.58673,
        "source_pt_sha256": pt_sha,
        "names": {"0": "receptacle.duplex", "1": "switch.single_pole"},
    }), encoding="utf-8")
    (tmp_path / "models" / "latest").symlink_to("phase2_v8_init")

    # Prefer matching ONNX even when explicit .pt path is supplied.
    assert resolve_detector_weights(pt, work_dir=tmp_path) == onnx.resolve()
    resolved = resolve_detector_weights(work_dir=tmp_path)
    assert resolved == onnx.resolve()
    info = describe_detector_weights(work_dir=tmp_path)
    assert info["available"] is True
    assert info["model_name"] == "phase2_v8_init"
    assert info["epoch"] == 22
    assert info["best_fitness"] == pytest.approx(0.58673)
    assert info["classes"] == 2


def test_local_page_detector_is_deterministic_without_randomization(tmp_path, monkeypatch):
    # Fake an ONNX detector backed by deterministic synthetic outputs (no GPU/torch needed).
    class _FakeInput:
        name = "images"

    class _FakeSession:
        def __init__(self, *_args, **_kwargs):
            self.calls = 0

        def get_inputs(self):
            return [_FakeInput()]

        def run(self, _outputs, feed):
            self.calls += 1
            n = feed["images"].shape[0]
            # Emit one valid symbol and one unmapped class inside each tile.
            out = np.zeros((n, 6, 2), dtype=np.float32)
            out[:, :, 0] = [140.0, 140.0, 32.0, 32.0, 0.91, 0.01]
            out[:, :, 1] = [260.0, 260.0, 28.0, 28.0, 0.01, 0.88]
            return [out]

    import sys
    import types
    fake_ort = types.SimpleNamespace(
        SessionOptions=lambda: types.SimpleNamespace(intra_op_num_threads=1, inter_op_num_threads=1),
        InferenceSession=_FakeSession,
    )
    monkeypatch.setitem(sys.modules, "onnxruntime", fake_ort)

    model_dir = tmp_path / "phase2_test"
    model_dir.mkdir()
    onnx = model_dir / "best.onnx"
    onnx.write_bytes(b"fake-onnx")
    Path(f"{onnx}.json").write_text(json.dumps({
        "imgsz": 512,
        "output_layout": "xywh_cls_probs",
        "epoch": 5,
        "best_fitness": 0.42,
        "names": {"0": "receptacle.duplex", "1": "nonexistent.class_xyz"},
    }), encoding="utf-8")

    detector = LocalPageDetector(onnx)
    image = np.full((640, 640, 3), 245, dtype=np.uint8)
    first_insts, first_stats = detector.predict_page(image, confidence=0.25)
    second_insts, second_stats = detector.predict_page(image, confidence=0.25)

    assert [ {k: v for k, v in x.items() if k != "model_info"} for x in first_insts ] == [
        {k: v for k, v in x.items() if k != "model_info"} for x in second_insts
    ]
    assert first_stats["tiles"] == 4
    assert first_stats["evaluated_tiles"] == 4
    assert first_stats["skipped_blank_tiles"] == 0
    assert first_stats["unmapped_class"] > 0
    assert len(first_insts) >= 1
    assert first_insts[0]["symbol_family"] == "receptacle"
    assert first_insts[0]["symbol_variant"] == "duplex"
    assert first_insts[0]["review_source"] == "model"
    assert first_insts[0]["uncertain"] is True
    assert first_stats["epoch"] == 5

    calls_before = detector._ort_session.calls
    blank = np.full((640, 640, 3), 255, dtype=np.uint8)
    blank_insts, blank_stats = detector.predict_page(blank, confidence=0.25)
    assert blank_insts == []
    assert blank_stats["tiles"] == 4
    assert blank_stats["skipped_blank_tiles"] == 4
    assert blank_stats["evaluated_tiles"] == 0
    assert detector._ort_session.calls == calls_before


class _StubDetector:
    def __init__(self, boxes_fn):
        self._boxes_fn = boxes_fn

    def predict_page(self, image, *, confidence=0.25, overlap=192, batch=8):
        instances = self._boxes_fn(confidence)
        return instances, {
            "tiles": 4,
            "raw_detections": len(instances),
            "detections": len(instances),
            "unmapped_class": 0,
            "elapsed_ms": 12.5,
            "model_tag": "phase2_stub-01234567",
            "model_name": "phase2_stub",
            "model_path": "/tmp/stub/best.onnx",
            "epoch": 22,
            "best_fitness": 0.58673,
            "confidence": float(confidence),
            "tile_size": 512,
            "overlap": overlap,
        }


def _post_json(url: str, payload: dict, token: str):
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", "X-Labeler-Token": token},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=10) as resp:
        return resp.status, json.loads(resp.read().decode("utf-8"))


def test_api_evaluate_page_404_validation_and_non_mutating_200(tmp_path, monkeypatch):
    fitz = pytest.importorskip("fitz")
    doc = fitz.open()
    page = doc.new_page(width=400, height=300)
    page.draw_rect(fitz.Rect(40, 40, 80, 80), color=(0, 0, 0))
    pdf = tmp_path / "sample.pdf"
    doc.save(pdf)
    doc.close()

    monkeypatch.delenv("BPS_DETECTOR_WEIGHTS", raising=False)
    monkeypatch.setattr("annotator.web.DEFAULT_WORK_DIR", tmp_path / "empty_work")

    session = LabelingSession(pdf=pdf, out_dir=tmp_path / "labels", dpi=144)
    server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(session))
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    base = f"http://127.0.0.1:{server.server_port}"
    try:
        sess_info = json.loads(urllib.request.urlopen(f"{base}/api/session", timeout=10).read().decode("utf-8"))
        assert sess_info["detector"]["available"] is False

        with pytest.raises(urllib.error.HTTPError) as exc404:
            _post_json(f"{base}/api/evaluate_page?page=0", {"confidence": 0.25}, session.token)
        assert exc404.value.code == 404

        for bad_conf in (True, 0.0, 1.5, "abc"):
            with pytest.raises(urllib.error.HTTPError) as exc400:
                _post_json(f"{base}/api/evaluate_page?page=0", {"confidence": bad_conf}, session.token)
            assert exc400.value.code == 400

        stub = _StubDetector(lambda conf: [
            pending_instance(
                "m_stub_1", "switch", "single_pole", [100.0, 90.0, 130.0, 120.0],
                review_source="model", model_tag="phase2_stub-01234567",
                model_confidence=0.88, model_class="switch.single_pole",
            )
        ])
        monkeypatch.setattr("blueprint_symbols.page_detector.get_cached_detector", lambda *_a, **_kw: stub)
        status, res = _post_json(f"{base}/api/evaluate_page?page=0", {"confidence": 0.25}, session.token)
        assert status == 200
        assert res["tiles"] == 4
        assert len(res["instances"]) == 1
        assert res["instances"][0]["symbol_family"] == "switch"
        assert res["instances"][0]["review_source"] == "model"
        # Evaluation must not mutate saved labels on disk until the user clicks Save.
        assert not document_labels.has_labels(tmp_path / "labels")
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=10)


def test_browser_evaluate_page_overlays_boxes_undo_and_save(tmp_path, monkeypatch):
    import shutil

    fitz = pytest.importorskip("fitz")
    playwright = pytest.importorskip("playwright.sync_api")

    def _launch_browser(pw):
        exe = (
            shutil.which("google-chrome")
            or shutil.which("google-chrome-stable")
            or shutil.which("chromium")
            or shutil.which("chromium-browser")
        )
        opts = {"executable_path": exe} if exe else {}
        return pw.chromium.launch(headless=True, args=["--no-sandbox"], **opts)

    doc = fitz.open()
    page = doc.new_page(width=400, height=300)
    page.draw_rect(fitz.Rect(50, 50, 80, 80), color=(0, 0, 0))
    pdf = tmp_path / "eval_ui.pdf"
    doc.save(pdf)
    doc.close()

    monkeypatch.delenv("BPS_DETECTOR_WEIGHTS", raising=False)
    models_latest = tmp_path / "work" / "models" / "phase2_stub"
    models_latest.mkdir(parents=True)
    onnx = models_latest / "best.onnx"
    onnx.write_bytes(b"stub")
    Path(f"{onnx}.json").write_text(json.dumps({
        "epoch": 22, "best_fitness": 0.58673, "names": {"0": "switch.single_pole"},
    }), encoding="utf-8")
    (tmp_path / "work" / "models" / "latest").symlink_to("phase2_stub")
    monkeypatch.setattr("annotator.web.DEFAULT_WORK_DIR", tmp_path / "work")

    def fake_boxes(conf):
        items = [
            # Overlaps existing verified box -> skipped by UI deduplication
            pending_instance(
                "m_stub_1", "receptacle", "duplex", [51.0, 51.0, 81.0, 81.0],
                review_source="model", model_tag="phase2_stub", model_confidence=0.95,
            ),
            # Overlaps rejected proposal -> skipped by UI deduplication
            pending_instance(
                "m_stub_2", "receptacle", "duplex", [201.0, 201.0, 231.0, 231.0],
                review_source="model", model_tag="phase2_stub", model_confidence=0.91,
            ),
            # New detection -> added
            pending_instance(
                "m_stub_3", "switch", "single_pole", [120.0, 120.0, 155.0, 155.0],
                review_source="model", model_tag="phase2_stub", model_confidence=0.87,
            ),
        ]
        if conf >= 0.60:
            items.append(pending_instance(
                "m_stub_4", "luminaire", "troffer_2x4", [300.0, 100.0, 340.0, 130.0],
                review_source="model", model_tag="phase2_stub", model_confidence=0.93,
            ))
        return items

    monkeypatch.setattr("blueprint_symbols.page_detector.get_cached_detector", lambda *_a, **_kw: _StubDetector(fake_boxes))

    out_dir = tmp_path / "labels"
    session = LabelingSession(pdf=pdf, out_dir=out_dir, dpi=144)
    session.save(
        0,
        revision=None,
        instances=[{
            "instance_id": "human_1",
            "symbol_family": "receptacle",
            "symbol_variant": "duplex",
            "bbox_xyxy": [50.0, 50.0, 80.0, 80.0],
            "review_status": "accepted",
            "review_source": "human",
            "verified_ground_truth": True,
            "uncertain": False,
        }],
        rejected=[{
            "instance_id": "rej_1",
            "symbol_family": "receptacle",
            "symbol_variant": "duplex",
            "bbox_xyxy": [200.0, 200.0, 230.0, 230.0],
            "review_status": "rejected",
            "review_source": "human",
            "verified_ground_truth": False,
            "uncertain": True,
        }],
    )

    server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(session))
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        with playwright.sync_playwright() as pw:
            browser = _launch_browser(pw)
            try:
                pg = browser.new_page(viewport={"width": 1420, "height": 900})
                pg.goto(f"http://127.0.0.1:{server.server_port}/")
                pg.wait_for_function("() => document.querySelector('#pageIndicator').textContent === 'Page 1 / 1'")
                pg.wait_for_function("() => !document.querySelector('#evaluatePageBtn').disabled")

                pg.locator("#evaluatePageBtn").click()
                pg.wait_for_function("() => document.querySelector('#count').textContent.includes('1 verified · 1 pending')")
                assert "Pending · 87%" in pg.locator("#items").inner_text()

                # Re-evaluating replaces prior unreviewed model boxes cleanly and keeps verified box.
                pg.locator("#evalConfidence").select_option("0.65")
                pg.locator("#evaluatePageBtn").click()
                pg.wait_for_function("() => document.querySelector('#count').textContent.includes('1 verified · 2 pending')")

                # Undo restores previous evaluation snapshot, then redo / save persists to disk.
                pg.keyboard.press("Control+z")
                pg.wait_for_function("() => document.querySelector('#count').textContent.includes('1 verified · 1 pending')")
                pg.keyboard.press("Control+y")
                pg.wait_for_function("() => document.querySelector('#count').textContent.includes('1 verified · 2 pending')")

                pg.keyboard.press("Control+s")
                pg.wait_for_function("() => document.querySelector('#message').textContent.startsWith('Saved')")
            finally:
                browser.close()
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=10)

    saved = document_labels.pages(out_dir, "eval_ui")[1]
    assert len(saved["instances"]) == 3
    sources = {x["instance_id"]: x["review_source"] for x in saved["instances"]}
    assert sources["human_1"] == "human"
    assert sum(1 for v in sources.values() if v == "model") == 2
