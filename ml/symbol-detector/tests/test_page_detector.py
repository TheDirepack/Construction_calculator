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

    def predict_page(self, image, *, confidence=0.25, overlap=192, batch=8, **kwargs):
        self.last_kwargs = kwargs
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


# --------------------------------------------------------------------------
# shared tile fusion (merge_tile_detections) and the page gate
# --------------------------------------------------------------------------

from blueprint_symbols.multiscale import plan_pyramid_tiles  # noqa: E402
from blueprint_symbols.page_detector import (  # noqa: E402
    is_blank_tile,
    merge_tile_detections,
    tile_raw_detections,
)
from blueprint_symbols.sheet_gate import GatePolicy, SheetClass  # noqa: E402


def _raw(cls, box, conf, tile, scale=1.0):
    return {"symbol_family": cls, "bbox_xyxy": list(box), "confidence": conf,
            "source_scale": scale, "tile_index": tile}


def _two_by_two_tiles():
    # 832 x 832 page, 512 tiles, overlap 192 -> origins 0 and 320 on each axis.
    tiles = plan_pyramid_tiles(832, 832, tile_size=512, overlap=192)
    assert [(t.x, t.y) for t in tiles] == [(0, 0), (320, 0), (0, 320), (320, 320)]
    return tiles


def test_merge_fuses_same_class_tiles_and_counts_distinct_tiles():
    tiles = _two_by_two_tiles()
    box = (400.0, 400.0, 440.0, 440.0)          # inside all four tiles
    raw = [_raw("receptacle.duplex", box, 0.98, 0),
           _raw("receptacle.duplex", (401, 401, 441, 441), 0.96, 1),
           _raw("receptacle.duplex", (399, 400, 439, 440), 0.97, 2),
           _raw("receptacle.duplex", (400, 399, 440, 439), 0.95, 3),
           # a second box from tile 0 for the same object: one tile, not two
           _raw("receptacle.duplex", (402, 402, 440, 440), 0.90, 0)]
    [det] = merge_tile_detections(raw, confidence=0.25, tiles=tiles)
    assert det["supporting_detections"] == 4
    assert det["raw_detections"] == 5
    assert det["covering_tiles"] == 4 and det["tile_agreement"] == 1.0
    assert det["confidence"] == pytest.approx(0.98)          # max is the default score
    assert det["score_mode"] == "max"
    assert 399.0 < det["bbox_xyxy"][0] < 402.0                # weighted box fusion
    assert det["multiscale_agreement"] is None                # one scale run
    assert det["uncertain"] is False and det["review_required"] is False
    assert det["review_reasons"] == []
    assert det["supporting_tiles"] == [0, 1, 2, 3]
    assert det["tile_origins"] == []           # raw built by hand carries no tile_origin
    # Output stays compatible with consolidate_predictions consumers.
    for key in ("symbol_family", "bbox_xyxy", "confidence", "supporting_detections",
                "independent_scales", "multiscale_agreement", "uncertain", "review_required", "source"):
        assert key in det


def test_merge_flags_and_scores_a_box_only_some_covering_tiles_saw():
    tiles = _two_by_two_tiles()
    raw = [_raw("panelboard.panelboard", (400, 400, 470, 425), 0.46, 1)]
    merged = merge_tile_detections(raw, confidence=0.25, tiles=tiles)
    [det] = merged
    assert det["supporting_detections"] == 1 and det["covering_tiles"] == 4
    assert det["tile_agreement"] == pytest.approx(0.25)
    assert set(det["review_reasons"]) >= {"mid_confidence", "tile_disagreement"}
    assert det["uncertain"] is True and det["review_required"] is True
    assert det["wbf_confidence"] == pytest.approx(0.46 / 4)
    # WBF-style score pulls it under the cutoff.
    assert merge_tile_detections(raw, confidence=0.25, tiles=tiles, score_mode="wbf") == []
    mean = merge_tile_detections(raw, confidence=0.25, tiles=tiles, score_mode="mean")
    assert mean[0]["confidence"] == pytest.approx(0.46)
    # A raised output cutoff (page gate) drops it, the default keeps it.
    assert merge_tile_detections(raw, confidence=0.25, tiles=tiles, min_output_confidence=0.9) == []
    with pytest.raises(ValueError):
        merge_tile_detections(raw, confidence=0.25, score_mode="median")


def test_merge_cross_class_alternatives_and_unstable_scores():
    tiles = _two_by_two_tiles()
    raw = [_raw("switch.single_pole", (400, 400, 430, 430), 0.92, 0),
           _raw("switch.single_pole", (400, 400, 430, 430), 0.55, 3),
           _raw("switch.three_way", (401, 401, 431, 431), 0.60, 1)]
    [det] = merge_tile_detections(raw, confidence=0.25, tiles=tiles)
    assert det["symbol_family"] == "switch.single_pole"
    assert det["alternative_labels"] == [
        {"symbol_family": "switch.three_way", "confidence": 0.60, "supporting_detections": 1}]
    assert "class_disagreement" in det["review_reasons"]
    assert "unstable_score" in det["review_reasons"]
    assert det["class_agreement"] == pytest.approx(2 / 3, abs=1e-3)
    # Deployment's old post-mapping dedup (IoU 0.45 any class) is part of the merge.
    raw2 = [_raw("a", (100, 100, 140, 140), 0.9, 0), _raw("b", (113, 100, 153, 140), 0.8, 1)]   # IoU 0.51
    assert len(merge_tile_detections(raw2, confidence=0.25)) == 1
    assert len(merge_tile_detections(raw2, confidence=0.25, dedup_iou=None)) == 2


def test_merge_multiscale_agreement_and_class_consistent_filter():
    raw = [
        # true symbol: same class at both scales
        _raw("receptacle.duplex", (100, 100, 130, 130), 0.9, 0, scale=1.0),
        _raw("receptacle.duplex", (101, 100, 131, 130), 0.8, 10, scale=2.0),
        # false positive hopping classes between scales
        _raw("luminaire.troffer_2x4", (500, 500, 540, 530), 0.7, 1, scale=1.0),
        _raw("data_outlet.telephone_outlet", (300, 300, 330, 330), 0.6, 11, scale=2.0),
    ]
    merged = merge_tile_detections(raw, confidence=0.25, scales=(1.0, 2.0))
    by_cls = {d["symbol_family"]: d for d in merged}
    assert by_cls["receptacle.duplex"]["multiscale_agreement"] is True
    assert by_cls["receptacle.duplex"]["independent_scales"] == [1.0, 2.0]
    assert by_cls["luminaire.troffer_2x4"]["multiscale_agreement"] is False
    assert "scale_disagreement" in by_cls["luminaire.troffer_2x4"]["review_reasons"]
    stats: dict = {}
    filtered = merge_tile_detections(raw, confidence=0.25, scales=(1.0, 2.0),
                                     min_agreeing_scales=2, stats=stats)
    assert [d["symbol_family"] for d in filtered] == ["receptacle.duplex"]
    assert stats["scale_filtered"] == 2
    # Without tile ids every raw box counts as its own tile (old callers).
    legacy = merge_tile_detections(
        [{k: v for k, v in r.items() if k != "tile_index"} for r in raw[:2]], confidence=0.25)
    assert legacy[0]["supporting_detections"] == 2


def test_merge_allowed_classes_and_max_raw():
    raw = [_raw("receptacle.duplex", (10, 10, 40, 40), 0.9, 0),
           _raw("nonexistent.class_xyz", (12, 10, 42, 40), 0.95, 0),
           _raw("receptacle.duplex", (200, 200, 230, 230), 0.5, 1)]
    # An unmapped class must not swallow a mapped one at the same spot.
    stats: dict = {}
    out = merge_tile_detections(raw, confidence=0.25, allowed_classes={"receptacle.duplex"}, stats=stats)
    assert [d["symbol_family"] for d in out] == ["receptacle.duplex"] * 2
    assert stats["not_allowed_class"] == 1
    capped = merge_tile_detections(raw, confidence=0.25, max_raw=1)
    assert len(capped) == 1 and capped[0]["confidence"] == pytest.approx(0.95)


class _BlobSession:
    """Fake ONNX detector that 'detects' ink blobs in each tile.

    Black blobs are class 0, blue blobs class 1. The score depends on where
    the blob sits in the tile, like the real model's tile-offset dependence.
    """

    def __init__(self, *_args, **_kwargs):
        self.calls = 0
        self.tiles_seen = 0

    def get_inputs(self):
        return [type("I", (), {"name": "images"})()]

    def run(self, _outputs, feed):
        self.calls += 1
        batch = feed["images"]
        n, _, size, _ = batch.shape
        anchors = 8
        out = np.zeros((n, 6, anchors), dtype=np.float32)
        for i in range(n):
            self.tiles_seen += 1
            rgb = batch[i]
            ink = (rgb.min(axis=0) < 0.5).astype(np.uint8)
            count, _, stats, _ = cv2.connectedComponentsWithStats(ink, connectivity=8)
            for j in range(1, min(count, anchors + 1)):
                x, y, bw, bh, _ = stats[j]
                cx, cy = x + bw / 2.0, y + bh / 2.0
                dist = float(np.hypot(cx - size / 2.0, cy - size / 2.0)) / (size / np.sqrt(2.0))
                score = 0.97 - 0.5 * dist
                blue = float(rgb[2, y + bh // 2, x + bw // 2]) > 0.5
                out[i, :4, j - 1] = [cx, cy, bw, bh]
                out[i, 5 if blue else 4, j - 1] = score
        return [out]


def _blob_detector(tmp_path, monkeypatch):
    import sys
    import types
    fake_ort = types.SimpleNamespace(
        SessionOptions=lambda: types.SimpleNamespace(intra_op_num_threads=1, inter_op_num_threads=1),
        InferenceSession=_BlobSession,
    )
    monkeypatch.setitem(sys.modules, "onnxruntime", fake_ort)
    model_dir = tmp_path / "phase2_blob"
    model_dir.mkdir()
    onnx = model_dir / "best.onnx"
    onnx.write_bytes(b"fake-blob-onnx")
    Path(f"{onnx}.json").write_text(json.dumps({
        "imgsz": 512, "output_layout": "xywh_cls_probs", "epoch": 9, "best_fitness": 0.5,
        "recommended_confidence": 0.4,
        "names": {"0": "receptacle.duplex", "1": "switch.single_pole"},
    }), encoding="utf-8")
    return LocalPageDetector(onnx)


def _blob_page():
    page = np.full((1100, 1500, 3), 255, dtype=np.uint8)
    cv2.rectangle(page, (400, 400), (430, 430), (0, 0, 0), -1)        # in the overlap of 4 tiles
    cv2.rectangle(page, (100, 120), (124, 140), (0, 0, 0), -1)        # one tile only
    cv2.rectangle(page, (1100, 700), (1130, 725), (255, 0, 0), -1)    # blue -> class 1
    cv2.rectangle(page, (1300, 200), (1330, 230), (0, 0, 0), -1)
    return page


def test_parity_deployment_and_evaluation_callers_give_identical_boxes(tmp_path, monkeypatch):
    detector = _blob_detector(tmp_path, monkeypatch)
    page = _blob_page()
    conf = 0.25

    # Deployment caller.
    deployed, info = detector.detect_page(page, confidence=conf)
    instances, stats = detector.predict_page(page, confidence=conf)

    # Evaluation-style caller (real_page_eval): run the model per tile itself,
    # decode, convert with tile_raw_detections and merge with the same function.
    h, w = page.shape[:2]
    tiles = plan_pyramid_tiles(w, h, tile_size=512, overlap=192, source_scales=(1.0,))
    session = _BlobSession()
    raw = []
    for index, tile in enumerate(tiles):
        patch = LocalPageDetector._extract_tile_native(page, tile)
        tensor = patch[:, :, ::-1].astype(np.float32).transpose(2, 0, 1)[None] / 255.0
        pred = session.run(None, {"images": tensor})[0]
        dets = decode_onnx_tile_outputs(pred[0], confidence=conf, output_layout="xywh_cls_probs")
        raw.extend(tile_raw_detections(tile, dets, names=detector.names, width=w, height=h,
                                       tile_index=index))
    evaluated = merge_tile_detections(raw, confidence=conf, tiles=tiles)

    def boxes(dets):
        return [(d["symbol_family"], [round(v, 3) for v in d["bbox_xyxy"]],
                 round(d["confidence"], 6), d["supporting_detections"], d["covering_tiles"])
                for d in dets]

    assert len(deployed) == 4
    assert boxes(deployed) == boxes(evaluated)
    assert [i["bbox_xyxy"] for i in instances] == [[round(v, 3) for v in d["bbox_xyxy"]]
                                                   for d in evaluated]
    assert info["skipped_blank_tiles"] > 0          # skipping blank tiles changes nothing
    assert any(is_blank_tile(LocalPageDetector._extract_tile_native(page, t)) for t in tiles)

    # The 4-tile blob is seen by all four tiles; the one-tile blob by one.
    by_box = {tuple(round(v) for v in d["bbox_xyxy"]): d for d in deployed}
    shared = by_box[(400, 400, 431, 431)]
    assert shared["supporting_detections"] == 4 and shared["tile_agreement"] == 1.0
    lone = by_box[(100, 120, 125, 141)]
    assert lone["supporting_detections"] == 1 and lone["covering_tiles"] == 1
    # Only real disagreement or a mid-band score raises the review flag.
    assert not all(d["review_required"] for d in deployed)
    for d in deployed:
        assert d["review_required"] == bool(d["review_reasons"])


def test_predict_page_records_provenance_for_review(tmp_path, monkeypatch):
    detector = _blob_detector(tmp_path, monkeypatch)
    instances, stats = detector.predict_page(_blob_page(), confidence=0.3,
                                             confidence_source="recommended", dpi=150)
    assert instances
    info = instances[0]["model_info"]
    assert info["weights_sha256"] == hashlib.sha256(b"fake-blob-onnx").hexdigest()
    assert info["weights"].endswith("best.onnx")
    assert info["confidence_threshold"] == pytest.approx(0.3)
    assert info["confidence_source"] == "recommended"
    assert info["recommended_confidence"] == pytest.approx(0.4)
    assert info["effective_confidence"] == pytest.approx(0.3)
    assert info["dpi"] == 150
    assert info["image_size"] == [1500, 1100]
    [grid] = info["tile_grid"]
    assert grid["origin_xy"] == [0.0, 0.0] and grid["step_px"] == 320.0
    assert grid["columns"] == 5 and grid["rows"] == 3     # last tile flush with the edge
    assert info["merge"]["function"].endswith("merge_tile_detections")
    assert info["sheet_gate"]["action"] == "run"
    inst = instances[0]
    for key in ("model_supporting_detections", "model_covering_tiles", "model_tile_agreement",
                "model_tile_origins", "model_review_required", "model_review_reasons"):
        assert key in inst
    assert inst["uncertain"] is True            # unreviewed proposals always stay uncertain
    assert stats["provenance"] == info
    json.dumps(instances)                        # serializable for the labels file


def test_predict_page_sheet_gate_skip_raise_and_unknown(tmp_path, monkeypatch):
    detector = _blob_detector(tmp_path, monkeypatch)
    page = _blob_page()
    landscape = SheetClass("landscape", False, "L1.3", "LANDSCAPE PLAN", 0.9, "test")
    unknown = SheetClass("unknown", None, None, None, 0.0, "no sheet number")

    base, _ = detector.predict_page(page, confidence=0.25)
    calls = detector._ort_session.calls
    skipped, sstats = detector.predict_page(page, confidence=0.25, sheet=landscape,
                                            gate=GatePolicy(mode="skip"))
    assert skipped == [] and detector._ort_session.calls == calls     # no inference at all
    assert sstats["sheet_gate"]["action"] == "skip" and sstats["evaluated_tiles"] == 0

    raised, rstats = detector.predict_page(page, confidence=0.25, sheet=landscape,
                                           gate=GatePolicy(mode="raise", raised_confidence=0.8))
    assert rstats["effective_confidence"] == pytest.approx(0.8)
    assert raised and all(x["model_confidence"] >= 0.8 for x in raised)
    assert len(raised) < len(base)
    expected = [x["bbox_xyxy"] for x in base if x["model_confidence"] >= 0.8]
    assert [x["bbox_xyxy"] for x in raised] == expected

    for sheet in (unknown, None):
        same, ustats = detector.predict_page(page, confidence=0.25, sheet=sheet,
                                             gate=GatePolicy(mode="skip"))
        assert [x["bbox_xyxy"] for x in same] == [x["bbox_xyxy"] for x in base]
        assert ustats["sheet_gate"]["action"] == "run"


def test_evaluate_page_applies_sheet_gate_from_pdf_title_block(tmp_path, monkeypatch):
    fitz = pytest.importorskip("fitz")
    doc = fitz.open()
    page = doc.new_page(width=1224, height=792)
    page.draw_rect(fitz.Rect(40, 40, 80, 80), color=(0, 0, 0))
    page.insert_text((1224 - 220, 792 - 120), "LANDSCAPE PLAN", fontsize=14)
    page.insert_text((1224 - 150, 792 - 30), "L1.3", fontsize=30)
    pdf = tmp_path / "landscape.pdf"
    doc.save(pdf)
    doc.close()

    monkeypatch.delenv("BPS_DETECTOR_WEIGHTS", raising=False)
    stub = _StubDetector(lambda conf: [])
    monkeypatch.setattr("blueprint_symbols.page_detector.get_cached_detector", lambda *_a, **_kw: stub)
    from annotator.web import LabelingError
    session = LabelingSession(pdf=pdf, out_dir=tmp_path / "labels", dpi=72)

    session.evaluate_page(0, confidence=0.3)            # default: every sheet is detected
    assert stub.last_kwargs["sheet"] is None and stub.last_kwargs["gate"].mode == "off"

    session.evaluate_page(0, confidence=0.3, confidence_source="manual", sheet_gate="skip")
    kwargs = stub.last_kwargs
    assert kwargs["sheet"].sheet_number == "L1.3" and kwargs["sheet"].electrical_relevant is False
    assert kwargs["gate"].mode == "skip" and kwargs["gate"].raised_confidence == pytest.approx(0.9)
    assert kwargs["confidence_source"] == "manual" and kwargs["dpi"] == 72

    session.evaluate_page(0, confidence=0.3, sheet_gate="off")
    assert stub.last_kwargs["sheet"] is None and stub.last_kwargs["gate"].mode == "off"
    session.sheet_gate_mode = "raise"
    session.evaluate_page(0, confidence=0.3)
    assert stub.last_kwargs["gate"].mode == "raise"
    with pytest.raises(LabelingError):
        session.evaluate_page(0, confidence=0.3, sheet_gate="drop")
    with pytest.raises(LabelingError):
        session.evaluate_page(0, confidence=0.3, confidence_source="guess")


def test_vectorized_tile_cover_matches_scalar_rule():
    import random
    from blueprint_symbols.page_detector import _TileCover, _tile_sees_whole
    rng = random.Random(3)
    tiles = plan_pyramid_tiles(1500, 1100, tile_size=512, overlap=192, source_scales=(1.0, 2.0))
    cover = _TileCover(dict(enumerate(tiles)))
    for _ in range(400):
        x, y = rng.uniform(0, 1480), rng.uniform(0, 1080)
        box = (x, y, min(1500.0, x + rng.uniform(3, 60)), min(1100.0, y + rng.uniform(3, 60)))
        expected = {i for i, t in enumerate(tiles) if _tile_sees_whole(t, box)}
        assert cover.covering(box) == expected
