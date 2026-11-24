"""End-to-end tests for the local, non-exhaustive PDF annotation editor."""
from __future__ import annotations

import copy
import hashlib
import http.client
import json
import sys
import threading
from http.server import ThreadingHTTPServer
from pathlib import Path

import pytest

fitz = pytest.importorskip("fitz")
from annotator.model import Sheet
from annotator.web import (
    LabelingError, LabelingSession, RevisionConflict, make_handler,
    sha256_file, validate_instances,
)


def _pdf(path: Path, *, pages: int = 2) -> Path:
    doc = fitz.open()
    for i in range(pages):
        page = doc.new_page(width=400, height=300)
        page.draw_rect(fitz.Rect(40 + i, 70, 60 + i, 90), color=(0, 0, 0))
        page.insert_text((120, 110), "RECEPTACLE S3")
    doc.save(path)
    doc.close()
    return path


def _box(ident: str = "manual_p1_1") -> dict:
    return {
        "instance_id": ident,
        "symbol_family": "receptacle",
        "symbol_variant": "duplex",
        "bbox_xyxy": [70.0, 120.0, 95.0, 145.0],
        "uncertain": False,
        "review_status": "accepted",
        "review_source": "human",
        "verified_ground_truth": True,
    }


def test_pdf_labeler_saves_safe_positive_only_page(tmp_path):
    pdf = _pdf(tmp_path / "electrical.pdf")
    session = LabelingSession(pdf=pdf, out_dir=tmp_path / "labels", dpi=144)
    view = session.page_state(0)
    assert view["revision"] is None
    assert view["page_count"] == 2
    assert (view["document"]["image_width"], view["document"]["image_height"]) == (800, 600)
    assert view["document"]["source_pdf_sha256"] == sha256_file(pdf)
    assert view["document"]["annotation_completeness"] == "partial"
    assert view["document"]["exhaustive_families"] == []
    assert not view["document"]["annotation_completeness_verified"]
    assert view["document"]["verified_no_symbols"] is False
    # _raster must cache the expensive PDF rendering across the UI metadata,
    # image request and save (not render the same 100+ megapixel sheet 3 times).
    before = session._render_cache[0]
    image = session.image_bytes(0)
    assert image.startswith(b"\x89PNG\r\n\x1a\n")
    assert session._render_cache[0] == before

    revision = session.save(0, revision=None, instances=[_box()], rejected=[])
    assert len(revision) == 64
    saved = session.page_state(0)
    assert saved["revision"] == revision
    assert saved["document"]["instances"][0]["verified_ground_truth"] is True
    assert saved["document"]["annotation_scope"] == "positive_only"
    assert saved["document"]["exhaustive_families"] == []
    assert saved["document"]["annotation_completeness_verified"] is False
    # A distinct, otherwise unannotated page is not considered a negative.
    next_page = session.page_state(1)
    assert next_page["revision"] is None
    assert next_page["document"]["instances"] == []
    assert next_page["document"]["annotation_completeness_verified"] is False
    assert (tmp_path / "labels" / "electrical_p0001.json").is_file()


def test_verified_no_symbols_roundtrip_and_conflict_rejection(tmp_path):
    session = LabelingSession(pdf=_pdf(tmp_path / "plans.pdf"), out_dir=tmp_path / "labels")
    with pytest.raises(LabelingError, match="active boxes"):
        session.save(0, revision=None, instances=[_box()], rejected=[],
                     metadata={"verified_no_symbols": True})
    with pytest.raises(LabelingError, match="must be boolean"):
        session.save(0, revision=None, instances=[], rejected=[],
                     metadata={"verified_no_symbols": "true"})
    assert session.page_state(0)["revision"] is None
    first = session.save(0, revision=None, instances=[], rejected=[],
                         metadata={"verified_no_symbols": True})
    doc = session.page_state(0)["document"]
    assert doc["verified_no_symbols"] is True
    assert doc["annotation_completeness_verified"] is True
    assert doc["annotation_scope"] == "verified_no_symbols"
    assert doc["exhaustive_families"] == []  # Expanded against current model classes on export.
    with pytest.raises(LabelingError, match="active boxes"):
        session.save(0, revision=first, instances=[_box()], rejected=[])
    session.save(0, revision=first, instances=[_box()], rejected=[],
                 metadata={"verified_no_symbols": False})
    doc = session.page_state(0)["document"]
    assert doc["verified_no_symbols"] is False
    assert doc["annotation_completeness_verified"] is False
    assert doc["annotation_scope"] == "positive_only"


def test_page_presence_proposals_are_read_only_and_source_hash_bound(tmp_path):
    from scripts_triage_pdf_symbol_pages import scan_pdfs
    pdf = _pdf(tmp_path / "source.pdf")
    queue = scan_pdfs([pdf])
    suggestions = tmp_path / "page_presence.json"
    suggestions.write_text(json.dumps(queue))
    session = LabelingSession(pdf=pdf, out_dir=tmp_path / "labels",
                              page_presence=suggestions)
    page = session.page_state(0)
    assert page["page_presence_suggestion"]["suggestion"] == "possible_symbols"
    assert page["document"]["verified_no_symbols"] is False
    assert not (tmp_path / "labels" / "source_p0001.json").exists()
    forged = json.loads(suggestions.read_text())
    forged["documents"][0]["pages"][0]["verified_no_symbols"] = True
    suggestions.write_text(json.dumps(forged))
    with pytest.raises(LabelingError, match="untrusted"):
        LabelingSession(pdf=pdf, out_dir=tmp_path / "another",
                        page_presence=suggestions)
    forged["documents"][0]["pages"][0]["verified_no_symbols"] = False
    forged["documents"][0]["source_pdf_sha256"] = "0" * 64
    suggestions.write_text(json.dumps(forged))
    with pytest.raises(LabelingError, match="SHA256"):
        LabelingSession(pdf=pdf, out_dir=tmp_path / "different",
                        page_presence=suggestions)


def test_reject_unreviewed_proposals_and_preserve_provenance(tmp_path):
    pdf = _pdf(tmp_path / "plan.pdf", pages=1)
    proposals = tmp_path / "detected_SAM3.json"
    proposals.write_text(json.dumps({
        "source_file": "plan.pdf",
        "source_pdf_sha256": sha256_file(pdf),
        "devices": [{
            "pdf_page": 1, "device_type": "switch", "subtype": "unspecified",
            "symbol_bbox": [100, 110, 130, 140],
            "score": .91, "visual_group": "switch.base_letter_s",
            "neca_symbol_id": "NECA_4_01",
        }],
    }))
    sess = LabelingSession(pdf=pdf, out_dir=tmp_path / "annotated",
                           proposals=proposals, dpi=144)
    first = sess.page_state(0)
    box = first["document"]["instances"][0]
    assert box["bbox_xyxy"] == [200.0, 320.0, 260.0, 380.0]
    assert box["uncertain"] is True
    assert box["verified_ground_truth"] is False
    assert box["review_status"] == "unreviewed"
    assert box["proposal_visual_group"] == "switch.base_letter_s"
    assert box["proposal_score"] == .91
    accepted = copy.deepcopy(box)
    accepted.update({
        "review_status": "accepted", "review_source": "human",
        "verified_ground_truth": True, "uncertain": False,
        "symbol_variant": "single_pole",
    })
    rev = sess.save(0, revision=None, instances=[accepted], rejected=[])
    assert sess.page_state(0)["document"]["instances"][0]["review_status"] == "accepted"
    rejected = dict(accepted, review_status="rejected", verified_ground_truth=False,
                    uncertain=True)
    sess.save(0, revision=rev, instances=[], rejected=[rejected])
    final = sess.page_state(0)["document"]
    assert final["instances"] == []
    assert len(final["rejected_proposals"]) == 1
    assert final["exhaustive_families"] == []
    assert not final["annotation_completeness_verified"]


def test_pdf_revision_conflicts_and_wrong_proposal_document(tmp_path):
    pdf = _pdf(tmp_path / "paper.pdf", pages=1)
    session = LabelingSession(pdf=pdf, out_dir=tmp_path / "out")
    rev = session.save(0, revision=None, instances=[], rejected=[])
    with pytest.raises(RevisionConflict, match="saved annotations changed"):
        session.save(0, revision=None, instances=[], rejected=[])
    assert session.page_state(0)["revision"] == rev
    with pytest.raises(LabelingError, match="page index"):
        session.page_state(2)
    with pytest.raises(LabelingError, match="exactly one"):
        LabelingSession()
    proposal = tmp_path / "wrong.json"
    proposal.write_text(json.dumps({"source_file":"paper.pdf","source_pdf_sha256":"0"*64,
                                    "devices":[]}))
    with pytest.raises(LabelingError, match="SHA256"):
        LabelingSession(pdf=pdf, proposals=proposal)
    # A modified PDF cannot reuse current annotation coordinates.
    doc = fitz.open(pdf)
    page = doc[0]
    page.draw_line((1,1),(30,30))
    altered = tmp_path / "altered.pdf"
    doc.save(altered)
    doc.close()
    altered.replace(pdf)
    with pytest.raises(RevisionConflict, match="source PDF changed"):
        session.page_state(0)


def test_validation_rejects_bad_geometry_and_fake_review_acceptance():
    good = _box()
    validate_instances([good], 200, 200)
    for invalid in (
        dict(good, bbox_xyxy=[0, 0, 250, 10]),
        dict(good, bbox_xyxy=[10, 10, 10, 20]),
        dict(good, bbox_xyxy=[float("nan"), 0, 20, 20]),
        dict(good, review_status="accepted", review_source="sam3"),
        dict(good, uncertain=True),
        dict(good, verified_ground_truth=False),
        dict(good, review_status="rejected"),
    ):
        with pytest.raises(LabelingError):
            validate_instances([invalid], 200, 200)
    with pytest.raises(LabelingError, match="unique"):
        validate_instances([good, good], 200, 200)
    with pytest.raises(LabelingError, match="rotated"):
        validate_instances([dict(good, bbox_rotated={"cx":20})], 200, 200)


def test_http_local_ui_routes_save_token_and_conflict(tmp_path):
    pdf = _pdf(tmp_path / "page.pdf", pages=1)
    session = LabelingSession(pdf=pdf, out_dir=tmp_path / "output")
    server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(session))
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()

    def call(method, path, payload=None, headers=None):
        connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=10)
        body = json.dumps(payload) if payload is not None else None
        connection.request(method, path, body=body, headers=headers or {})
        response = connection.getresponse()
        data = response.read()
        headers = dict(response.getheaders())
        connection.close()
        return response.status, data, headers

    try:
        code, html, headers = call("GET", "/")
        assert code == 200 and b"Symbol labels" in html
        assert "frame-ancestors 'none'" in headers["Content-Security-Policy"]
        code, js, _ = call("GET", "/web_ui.js")
        assert code == 200 and b"screenToImage" in js
        code, data, _ = call("GET", "/api/page?page=0")
        assert code == 200
        original = json.loads(data)
        assert original["revision"] is None
        code, image, _ = call("GET", "/api/image?page=0")
        assert code == 200 and image.startswith(b"\x89PNG")
        code, _, _ = call("GET", "/api/image?page=../secret")
        assert code != 200
        code, data, _ = call("POST", "/api/save?page=0", {
            "revision": None, "instances": [_box()], "rejected_proposals": [],
        })
        assert code == 403  # CSRF/session token required
        headers = {"X-Labeler-Token": session.token, "Content-Type": "application/json"}
        payload = {"revision": None, "instances": [_box()], "rejected_proposals": []}
        code, data, _ = call("POST", "/api/save?page=0", payload, headers)
        assert code == 200 and json.loads(data)["saved"] is True
        code, data, _ = call("POST", "/api/save?page=0", payload, headers)
        assert code == 409  # optimistic locking, not silent overwrite
        code, data, _ = call("POST", "/api/save?page=0", payload,
                            {**headers, "Origin": "https://another-site.example"})
        assert code == 403
        code, data, _ = call("GET", "/etc/passwd")
        assert code == 404
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=10)


def test_cli_sheet_model_roundtrip_preserves_review_metadata(tmp_path):
    pdf = _pdf(tmp_path / "drawing.pdf", pages=1)
    session = LabelingSession(pdf=pdf, out_dir=tmp_path / "output")
    box = dict(_box(), proposal_score=.91, proposal_neca_symbol_id="NECA_X",
               modifier_evidence=[{"attribute":"control_features","value":"dimmer"}])
    session.save(0, revision=None, instances=[box], rejected=[])
    file = tmp_path / "output" / "drawing_p0001.json"
    legacy_cli = Sheet.load(file)
    copy_path = tmp_path / "roundtrip.json"
    legacy_cli.save(copy_path)
    copy_doc = json.loads(copy_path.read_text())
    assert copy_doc["instances"][0]["review_status"] == "accepted"
    assert copy_doc["instances"][0]["review_source"] == "human"
    assert copy_doc["instances"][0]["verified_ground_truth"] is True
    assert copy_doc["instances"][0]["proposal_score"] == .91
    assert copy_doc["instances"][0]["modifier_evidence"][0]["value"] == "dimmer"
    assert copy_doc["exhaustive_families"] == []
    assert copy_doc["source_pdf_sha256"] == sha256_file(pdf)


def test_legacy_cli_uses_portable_raster_reference_and_revokes_old_coverage(tmp_path, monkeypatch):
    from annotator.tool import main as cli
    pdf = _pdf(tmp_path / "electrical.pdf", pages=1)
    output = tmp_path / "labels" / "reviewed.json"
    monkeypatch.setattr(sys, "argv", [
        "annotator.tool", "--pdf", str(pdf), "--page", "0",
        "--output", str(output),
        "--add-box", "d1", "receptacle", "80", "100", "100", "120",
    ])
    cli()
    assert output.is_file()
    saved = json.loads(output.read_text())
    image = (output.parent / saved["image_path"]).resolve()
    assert image.is_file()
    assert image.parent == output.parent / "images"
    assert saved["image_sha256"] == hashlib.sha256(image.read_bytes()).hexdigest()
    assert saved["annotation_scope"] == "positive_only"
    assert saved["annotation_completeness_verified"] is False
    saved["annotation_completeness_verified"] = True
    saved["exhaustive_families"] = ["receptacle"]
    output.write_text(json.dumps(saved))
    copied = tmp_path / "different" / "edited.json"
    monkeypatch.setattr(sys, "argv", [
        "annotator.tool", "--edit", str(output), "--output", str(copied),
        "--classify", "d1", "switch",
    ])
    cli()
    result = json.loads(copied.read_text())
    assert result["instances"][0]["symbol_family"] == "switch"
    assert result["annotation_completeness_verified"] is False
    assert result["exhaustive_families"] == []
    assert (copied.parent / result["image_path"]).resolve() == image
    assert not list(copied.parent.glob("*.tmp"))


def test_document_browser_switching_and_server_export_import(tmp_path):
    from annotator.web import _is_private_ip

    assert _is_private_ip("127.0.0.1")
    assert _is_private_ip("192.168.1.93")
    assert _is_private_ip("10.0.0.5")
    assert _is_private_ip("172.16.0.1")
    assert not _is_private_ip("8.8.8.8")

    bp_dir = tmp_path / "blueprints"
    bp_dir.mkdir()
    pdf1 = _pdf(bp_dir / "plan_A.pdf", pages=2)
    pdf2 = _pdf(bp_dir / "plan_B.pdf", pages=1)

    labels_dir = tmp_path / "labels"
    session = LabelingSession(pdf=pdf1, blueprints_dir=bp_dir, out_dir=labels_dir)

    docs = session.list_documents()
    assert len(docs) == 2
    assert docs[0]["name"] == "plan_A.pdf"
    assert docs[0]["is_current"] is True

    # Save a box on plan_A page 0
    session.save(0, revision=None, instances=[_box()], rejected=[])

    # Server export
    export_res = session.export_server()
    assert export_res["success"] is True
    assert Path(export_res["path"]).is_file()
    assert export_res["total_instances"] == 1
    assert export_res["pages_count"] == 2
    assert export_res["annotated_pages_count"] == 1

    # Switch to plan_B
    new_state = session.switch_document("plan_B.pdf")
    assert session.pdf == pdf2
    assert new_state["page_count"] == 1
    assert new_state["current_doc_name"] == "plan_B.pdf"

    # Server import candidate list
    sources = session.list_server_sources(0)
    assert any(s["type"] == "server_export" for s in sources)

    # Server import into plan_B
    import_res = session.import_server(0, export_res["path"])
    assert import_res["success"] is True
    assert import_res["imported_count"] == 1
    assert len(session.page_state(0)["document"]["instances"]) == 1


def test_http_lan_routes_and_document_selection(tmp_path):
    bp_dir = tmp_path / "blueprints"
    bp_dir.mkdir()
    pdf1 = _pdf(bp_dir / "first.pdf", pages=1)
    pdf2 = _pdf(bp_dir / "second.pdf", pages=1)

    session = LabelingSession(pdf=pdf1, blueprints_dir=bp_dir, out_dir=tmp_path / "labels")
    server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(session))
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()

    def call(method, path, payload=None, headers=None):
        conn = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=10)
        body = json.dumps(payload) if payload is not None else None
        conn.request(method, path, body=body, headers=headers or {})
        resp = conn.getresponse()
        data = resp.read()
        hdrs = dict(resp.getheaders())
        conn.close()
        return resp.status, data, hdrs

    try:
        # GET /api/documents
        code, data, _ = call("GET", "/api/documents")
        assert code == 200
        docs = json.loads(data)["documents"]
        assert len(docs) == 2

        # POST /api/document (switching document)
        headers = {"X-Labeler-Token": session.token, "Content-Type": "application/json"}
        code, data, _ = call("POST", "/api/document", {"path": str(pdf2)}, headers)
        assert code == 200
        res = json.loads(data)
        assert res["switched"] is True
        assert res["page_state"]["current_doc_name"] == "second.pdf"

        # POST with LAN Origin header (e.g. 192.168.1.93)
        lan_headers = {
            "X-Labeler-Token": session.token,
            "Content-Type": "application/json",
            "Host": f"127.0.0.1:{server.server_port}",
            "Origin": f"http://127.0.0.1:{server.server_port}",
        }
        code, data, _ = call("POST", "/api/server_export", {}, lan_headers)
        assert code == 200
        assert json.loads(data)["success"] is True
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=10)


def test_electrical_device_catalog_completeness_and_http_endpoint(tmp_path):
    from annotator.model import discipline_for, load_electrical_device_catalog
    from blueprint_symbols.catalog import FAMILIES

    catalog = load_electrical_device_catalog()
    assert catalog["summary"]["categories_count"] == 11
    assert catalog["summary"]["total_devices"] == 104
    assert len(catalog["categories"]) == 11
    assert len(catalog["devices"]) == 104

    pdf = _pdf(tmp_path / "catalog_plan.pdf", pages=1)
    session = LabelingSession(pdf=pdf, out_dir=tmp_path / "labels")

    seen_pairs = set()
    by_label = {}
    for dev in catalog["devices"]:
        fam = dev["symbol_family"]
        var = dev["symbol_variant"]
        assert (fam, var) not in seen_pairs
        seen_pairs.add((fam, var))
        assert discipline_for(fam) == "electrical"
        assert fam in FAMILIES["electrical"]
        assert var in session.variants_by_family[fam]
        by_label[dev["label"]] = dev

    assert by_label["Card Reader - Long Range"]["symbol_family"] == "security_device"
    assert by_label["Card Reader - Long Range"]["symbol_variant"] == "card_reader_long_range"
    assert by_label["Fire Alarm Horn / Strobe - Wall Mounted"]["symbol_family"] == "fire_alarm_device"
    assert by_label["Fire Alarm Horn / Strobe - Wall Mounted"]["symbol_variant"] == "horn_strobe_wall"

    server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(session))
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        conn = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=10)
        try:
            conn.request("GET", "/api/session")
            r1 = conn.getresponse()
            session_body = json.loads(r1.read())
            assert r1.status == 200
            assert len(session_body["device_catalog"]["devices"]) == 104

            conn.request("GET", "/api/device_catalog")
            r2 = conn.getresponse()
            cat_body = json.loads(r2.read())
            assert r2.status == 200
            assert len(cat_body["devices"]) == 104
            assert len(cat_body["categories"]) == 11
            labels = {d["label"]: d for d in cat_body["devices"]}
            assert labels["Card Reader - Long Range"]["symbol_family"] == "security_device"
            assert labels["Card Reader - Long Range"]["symbol_variant"] == "card_reader_long_range"
            assert labels["Fire Alarm Horn / Strobe - Wall Mounted"]["symbol_family"] == "fire_alarm_device"
            assert labels["Fire Alarm Horn / Strobe - Wall Mounted"]["symbol_variant"] == "horn_strobe_wall"
        finally:
            conn.close()
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=10)


