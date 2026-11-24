"""Tests for the vision-model tool layer over the annotator session."""
from __future__ import annotations

import http.client
import json
import threading
from http.server import ThreadingHTTPServer

import pytest

fitz = pytest.importorskip("fitz")
from annotator import agent_api
from annotator.web import LabelingError, LabelingSession, RevisionConflict, make_handler


def _pdf(path, *, pages: int = 2):
    doc = fitz.open()
    for i in range(pages):
        page = doc.new_page(width=400, height=300)
        page.draw_rect(fitz.Rect(40 + i, 70, 60 + i, 90), color=(0, 0, 0))
        page.insert_text((120, 110), "RECEPTACLE S3")
    doc.save(path)
    doc.close()
    return path


def _session(tmp_path, *, pages: int = 2):
    return LabelingSession(pdf=_pdf(tmp_path / "plans.pdf", pages=pages),
                           out_dir=tmp_path / "labels", dpi=144)


def _verified() -> dict:
    return {
        "instance_id": "manual_p1_1",
        "symbol_family": "receptacle",
        "symbol_variant": "duplex",
        "bbox_xyxy": [70.0, 120.0, 95.0, 145.0],
        "uncertain": False,
        "review_status": "accepted",
        "review_source": "human",
        "verified_ground_truth": True,
    }


def _add(box=(100.0, 200.0, 140.0, 240.0), **extra) -> dict:
    action = {"kind": "add_box", "family": "receptacle", "variant": "duplex", "box": list(box)}
    action.update(extra)
    return action


# ---------------------------------------------------------------- describe

def test_manifest_states_coordinates_verbs_and_device_vocabulary(tmp_path):
    session = _session(tmp_path, pages=1)
    manifest = agent_api.tool_manifest(session)

    assert manifest["coordinates"]["system"] == "original_page_pixels_top_left_y_down"
    assert "top-left" in manifest["coordinates"]["units"]
    assert "[x0, y0, x1, y1]" in manifest["coordinates"]["box"]
    assert set(manifest["verbs"]) == {"describe", "read", "see", "act"}
    assert set(manifest["actions"]) == {"add_box", "update_box", "delete_box", "snap_box", "assess_page"}

    families = manifest["vocabulary"]
    assert "receptacle" in families
    variants = {entry["variant"] for entry in families["receptacle"]["variants"]}
    assert {"duplex", "quad"}.issubset(variants)
    duplex = next(entry for entry in families["receptacle"]["variants"] if entry["variant"] == "duplex")
    assert duplex["label"] == "Receptacle - Duplex"
    assert families["receptacle"]["discipline"] == "electrical"

    # Every catalogued device must be reachable through the advertised vocabulary.
    catalogued = {(d["symbol_family"], d["symbol_variant"]) for d in session.device_catalog["devices"]}
    advertised = {(family, entry["variant"]) for family, data in families.items()
                  for entry in data["variants"]}
    assert catalogued - advertised == set()


def test_manifest_and_context_reject_unknown_query_parameters(tmp_path):
    session = _session(tmp_path, pages=1)
    with pytest.raises(ValueError, match="unsupported query parameter: zoom"):
        agent_api.parse_view_query(session, "page=0&zoom=4")
    with pytest.raises(ValueError, match="unsupported query parameter: page_size"):
        agent_api.parse_context_query(session, "page=0&page_size=10")
    with pytest.raises(ValueError, match="must be given exactly once"):
        agent_api.parse_context_query(session, "page=0&page=1")
    with pytest.raises(ValueError, match="invalid page number"):
        agent_api.parse_context_query(session, "page=999")


# ------------------------------------------------------------------- read

def test_context_lists_existing_boxes_and_page_flags(tmp_path):
    session = _session(tmp_path, pages=1)
    revision = session.save(0, revision=None, instances=[_verified()], rejected=[])

    context = agent_api.page_context(session, 0)
    assert context["revision"] == revision
    assert (context["width"], context["height"]) == (800, 600)
    assert context["page_flags"]["verified_no_symbols"] is False
    assert context["counts"] == {"total": 1, "by_family": {"receptacle/duplex": 1}}
    box = context["boxes"][0]
    assert box["id"] == "manual_p1_1"
    assert box["box"] == [70.0, 120.0, 95.0, 145.0]
    assert box["status"] == "verified"
    assert box["label"] == "Receptacle - Duplex"
    assert context["walls"]["count"] == 0
    assert "snapping is a no-op" in context["walls"]["note"]
    assert context["view_url"] == "/api/agent/view?page=0"


def test_context_marks_machine_proposals_as_proposed(tmp_path):
    session = _session(tmp_path, pages=1)
    agent_api.apply_actions(session, 0, revision=None, actions=[_add()])
    box = agent_api.page_context(session, 0)["boxes"][0]
    assert box["status"] == "proposed"
    assert box["id"].startswith("a0_")


# -------------------------------------------------------------------- see

def test_view_is_bounded_and_reports_scale(tmp_path):
    session = _session(tmp_path, pages=1)
    from PIL import Image
    import io

    data, mime = agent_api.render_view(session, 0, max_side=300)
    assert mime == "image/png" and data.startswith(b"\x89PNG")
    image = Image.open(io.BytesIO(data))
    # 800x600 page scaled so its long edge is 300, plus the caption strip.
    assert image.width == 300
    assert image.height == 225 + agent_api.CAPTION_HEIGHT

    cropped, _ = agent_api.render_view(session, 0, region="100,100,500,400", max_side=400)
    crop_image = Image.open(io.BytesIO(cropped))
    assert crop_image.width == 400 and crop_image.height == 300 + agent_api.CAPTION_HEIGHT

    native, _ = agent_api.render_view(session, 0, region="100,100,300,250", max_side=2000)
    native_image = Image.open(io.BytesIO(native))
    assert native_image.width == 200          # never upscales past 1:1


def test_view_rejects_bad_regions(tmp_path):
    session = _session(tmp_path, pages=1)
    for region, message in (("1,2,3", "four"), ("0,0,4,4", "at least 8x8"), ("a,b,c,d", "numbers")):
        with pytest.raises(ValueError, match=message):
            agent_api.parse_view_query(session, f"page=0&region={region}")


# -------------------------------------------------------------------- act

def test_act_adds_unreviewed_proposal_and_advances_revision(tmp_path):
    session = _session(tmp_path, pages=1)
    result = agent_api.apply_actions(session, 0, revision=None, actions=[_add()])

    assert result["written"] is True and result["dry_run"] is False
    assert len(result["revision"]) == 64
    added = result["applied"][0]
    assert added["kind"] == "add_box" and added["id"] == "a0_1"
    assert result["boxes"][0]["status"] == "proposed"
    assert result["counts"]["total"] == 1

    saved = json.loads((tmp_path / "labels" / "plans_p0001.json").read_text())
    stored = saved["instances"][0]
    assert stored["review_source"] == "vlm_agent"
    assert stored["review_status"] == "unreviewed"
    assert stored["verified_ground_truth"] is False
    assert stored["uncertain"] is True
    assert saved["annotation_completeness"] == "partial"
    assert result["revision"] == agent_api.page_context(session, 0)["revision"]


def test_act_generates_sequential_ids_and_accepts_explicit_ids(tmp_path):
    session = _session(tmp_path, pages=1)
    first = agent_api.apply_actions(session, 0, revision=None,
                                    actions=[_add(), _add(box=(200.0, 300.0, 240.0, 340.0),
                                                          id="my_symbol")])
    assert [entry["id"] for entry in first["applied"]] == ["a0_1", "my_symbol"]
    second = agent_api.apply_actions(session, 0, revision=first["revision"],
                                     actions=[_add(box=(300.0, 300.0, 340.0, 340.0))])
    assert second["applied"][0]["id"] == "a0_2"


def test_act_rejects_stale_revision(tmp_path):
    session = _session(tmp_path, pages=1)
    agent_api.apply_actions(session, 0, revision=None, actions=[_add()])
    with pytest.raises(RevisionConflict):
        agent_api.apply_actions(session, 0, revision=None, actions=[_add()])
    assert agent_api.page_context(session, 0)["counts"]["total"] == 1


def test_act_dry_run_validates_without_writing(tmp_path):
    session = _session(tmp_path, pages=1)
    result = agent_api.apply_actions(session, 0, revision=None, actions=[_add()], dry_run=True)
    assert result["written"] is False and result["dry_run"] is True
    assert result["revision"] is None
    assert not (tmp_path / "labels" / "plans_p0001.json").exists()
    assert agent_api.page_context(session, 0)["boxes"] == []
    # A dry run still reports every gate that a real write would hit.
    with pytest.raises(ValueError, match="action 0"):
        agent_api.apply_actions(session, 0, revision=None,
                                actions=[_add(box=(700.0, 500.0, 900.0, 640.0))], dry_run=True)


def test_act_reports_the_failing_action_index_and_message(tmp_path):
    session = _session(tmp_path, pages=1)
    actions = [_add(), {"kind": "add_box", "family": "receptacle", "box": [1, 2, 3, 4, 5]}]
    with pytest.raises(ValueError, match=r"action 1 \(add_box\): box must be \[x0, y0, x1, y1\]"):
        agent_api.apply_actions(session, 0, revision=None, actions=actions)
    with pytest.raises(ValueError, match=r"action 0 \(teleport_box\): unknown action kind"):
        agent_api.apply_actions(session, 0, revision=None, actions=[{"kind": "teleport_box"}])
    with pytest.raises(ValueError, match="at most 500 actions"):
        agent_api.apply_actions(session, 0, revision=None, actions=[_add()] * 501)
    assert agent_api.page_context(session, 0)["boxes"] == []


def test_act_updates_moves_and_deletes_boxes(tmp_path):
    session = _session(tmp_path, pages=1)
    created = agent_api.apply_actions(session, 0, revision=None, actions=[_add()])
    ident = created["applied"][0]["id"]

    updated = agent_api.apply_actions(session, 0, revision=created["revision"], actions=[
        {"kind": "update_box", "id": ident, "box": [300.0, 300.0, 340.0, 340.0],
         "family": "luminaire", "variant": "recessed_downlight"},
    ])
    box = updated["boxes"][0]
    assert box["box"] == [300.0, 300.0, 340.0, 340.0]
    assert box["family"] == "luminaire" and box["label"] == "Light Fixture - Recessed Downlight"

    deleted = agent_api.apply_actions(session, 0, revision=updated["revision"],
                                      actions=[{"kind": "delete_box", "id": ident}])
    assert deleted["applied"] == [{"kind": "delete_box", "id": ident}]
    assert deleted["counts"]["total"] == 0
    with pytest.raises(ValueError, match="unknown box id"):
        agent_api.apply_actions(session, 0, revision=deleted["revision"],
                                actions=[{"kind": "delete_box", "id": ident}])


def test_act_supports_rotation_and_keeps_aabb_consistent(tmp_path):
    session = _session(tmp_path, pages=1)
    result = agent_api.apply_actions(session, 0, revision=None,
                                     actions=[_add(box=(300.0, 300.0, 400.0, 320.0), rotation_deg=90)])
    saved = json.loads((tmp_path / "labels" / "plans_p0001.json").read_text())["instances"][0]
    assert saved["rotation_degrees"] == 90
    assert saved["bbox_rotated"]["angle_deg"] == 90
    x0, y0, x1, y1 = saved["bbox_xyxy"]
    assert x1 - x0 == pytest.approx(20, abs=0.1)
    assert y1 - y0 == pytest.approx(100, abs=0.1)
    assert result["boxes"][0]["rotation_deg"] == 90


def test_act_delegates_attribute_validation_to_the_session_gate(tmp_path):
    session = _session(tmp_path, pages=1)
    with pytest.raises(ValueError, match="voltage_v must be a finite positive number"):
        agent_api.apply_actions(session, 0, revision=None,
                                actions=[_add(attributes={"voltage_v": "120"})])
    ok = agent_api.apply_actions(session, 0, revision=None,
                                 actions=[_add(attributes={"voltage_v": 120, "gfci": True})])
    assert ok["boxes"][0]["attributes"] == {"voltage_v": 120, "gfci": True}


def test_agents_cannot_attest_negatives(tmp_path):
    session = _session(tmp_path, pages=1)
    with pytest.raises(ValueError, match="cannot attest pages as symbol-free"):
        agent_api.apply_actions(session, 0, revision=None,
                                actions=[{"kind": "set_page", "verified_no_symbols": True}])
    assert agent_api.page_context(session, 0)["page_flags"]["verified_no_symbols"] is False


def test_assess_page_is_stored_as_advisory_metadata(tmp_path):
    session = _session(tmp_path, pages=1)
    assess = {"kind": "assess_page", "page_kind": "other_discipline_plan",
              "symbols_present": "no", "labeling_complete": True, "note": "plumbing riser"}
    result = agent_api.apply_actions(session, 0, revision=None, actions=[assess], agent="nemotron")
    stored = result["page_flags"]["vlm_assessment"]
    assert stored["symbols_present"] == "no" and stored["advisory"] is True
    assert stored["agent"] == "nemotron" and stored["assessed_at"].endswith("Z")
    assert result["page_flags"]["verified_no_symbols"] is False

    saved = json.loads((tmp_path / "labels" / "plans_p0001.json").read_text())
    assert saved["vlm_assessment"]["page_kind"] == "other_discipline_plan"
    assert saved["annotation_completeness"] == "partial"
    assert saved["verified_no_symbols"] is False
    context = agent_api.page_context(session, 0)
    assert context["page_flags"]["vlm_assessment"]["note"] == "plumbing riser"

    # A later box edit keeps the assessment.
    after = agent_api.apply_actions(session, 0, revision=result["revision"], actions=[_add()])
    assert after["page_flags"]["vlm_assessment"]["page_kind"] == "other_discipline_plan"

    for bad, message in (({"page_kind": "kitchen"}, "page_kind"),
                         ({"symbols_present": "maybe"}, "symbols_present"),
                         ({"labeling_complete": "yes"}, "labeling_complete"),
                         ({"verified_no_symbols": True}, "unsupported assessment field")):
        with pytest.raises(ValueError, match=message):
            agent_api.apply_actions(session, 0, revision=after["revision"],
                                    actions=[{**assess, **bad}], dry_run=True)


def test_snap_is_skipped_without_verified_wall_geometry(tmp_path):
    session = _session(tmp_path, pages=1)
    result = agent_api.apply_actions(session, 0, revision=None, actions=[_add(snap=True)])
    assert any("snap skipped" in warning for warning in result["warnings"])
    assert result["written"] is True


# --------------------------------------------------------------- transport

def test_http_agent_surface_round_trip(tmp_path):
    session = _session(tmp_path, pages=1)
    server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(session))
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()

    def call(method, path, payload=None, headers=None):
        connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=30)
        body = json.dumps(payload) if payload is not None else None
        connection.request(method, path, body=body, headers=headers or {})
        response = connection.getresponse()
        data = response.read()
        response_headers = dict(response.getheaders())
        connection.close()
        return response.status, data, response_headers

    token = {"X-Labeler-Token": session.token, "Content-Type": "application/json"}
    try:
        code, data, _ = call("GET", "/api/agent/tools")
        assert code == 200 and json.loads(data)["verbs"]["see"]["path"] == "/api/agent/view"

        code, data, _ = call("GET", "/api/agent/context?page=0")
        assert code == 200 and json.loads(data)["revision"] is None

        code, data, headers = call("GET", "/api/agent/view?page=0&max_side=200&grid=0")
        assert code == 200 and data.startswith(b"\x89PNG")
        assert headers["Cache-Control"] == "no-store"

        code, data, _ = call("GET", "/api/agent/view?page=0&region=0,0,4,4")
        assert code == 409 and "8x8" in json.loads(data)["error"]

        payload = {"revision": None, "actions": [_add()]}
        code, _, _ = call("POST", "/api/agent/act?page=0", payload)
        assert code == 403  # writes still require the session token

        code, data, _ = call("POST", "/api/agent/act?page=0", payload, token)
        assert code == 200
        written = json.loads(data)
        assert written["written"] is True and written["boxes"][0]["status"] == "proposed"

        code, data, _ = call("POST", "/api/agent/act?page=0", payload, token)
        assert code == 409 and "reload page" in json.loads(data)["error"]

        code, data, _ = call("POST", "/api/agent/act?page=0",
                             {"revision": written["revision"], "actions": "nope"}, token)
        assert code == 400 and "non-empty list" in json.loads(data)["error"]

        code, _, _ = call("POST", "/api/agent/act?page=0",
                          {"revision": written["revision"], "actions": [], "dry_run": "yes"}, token)
        assert code == 400

        # The human editor still loads the same document and sees the proposal.
        code, data, _ = call("GET", "/api/page?page=0")
        page = json.loads(data)
        assert page["revision"] == written["revision"]
        assert page["document"]["instances"][0]["review_source"] == "vlm_agent"
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=10)


def test_unknown_vocabulary_is_written_but_flagged(tmp_path):
    session = _session(tmp_path, pages=1)
    result = agent_api.apply_actions(session, 0, revision=None,
                                     actions=[_add(variant="hallucinated_variant")])
    assert result["written"] is True
    assert any("not in the vocabulary" in warning for warning in result["warnings"])


# --------------------------------------------------------------- batch CLI

def _queue_page(directory, name, *, source_id, source_path, page, draw=None):
    from PIL import Image, ImageDraw
    directory.mkdir(parents=True, exist_ok=True)
    image = Image.new("RGB", (400, 300), "white")
    if draw:
        draw(ImageDraw.Draw(image))
    image.save(directory / f"{name}.png")
    (directory / f"{name}.json").write_text(json.dumps({
        "format_version": 1, "source_id": source_id, "document_id": name, "page_index": 0,
        "source_type": "rendered_drawing", "image_path": f"{name}.png",
        "image_width": 400, "image_height": 300,
        "coordinate_system": "original_page_pixels_top_left_y_down",
        "exhaustive_families": [], "instances": [],
        "pool": {"role": "label_queue", "source_path": source_path, "page": page},
    }))
    return directory / f"{name}.json"


def test_cli_batches_next_act_and_status(tmp_path, capsys):
    from annotator import agent_cli

    queue = tmp_path / "label_queue"
    scan = [_queue_page(queue, f"scan_p{i}", source_id="user_scans", source_path="scan.pdf", page=i)
            for i in range(3)]
    bulk = [_queue_page(queue, f"bulk_p{i}", source_id="new_downloads", source_path="bulk.pdf", page=i)
            for i in range(2)]

    def run(*argv):
        code = agent_cli.main([str(a) for a in argv])
        return code, json.loads(capsys.readouterr().out)

    code, built = run("batches", "--queue", queue, "--out", tmp_path / "batches", "--count", "2")
    assert code == 0 and built["documents"] == 2 and built["pages"] == 5
    first = (tmp_path / "batches" / "batch_01.txt").read_text().splitlines()
    assert first[0] == "# scan.pdf" and len(first) == 4      # whole documents stay together
    code, again = run("batches", "--queue", queue, "--out", tmp_path / "batches", "--count", "2")
    assert code == 2 and "--force" in again["error"]

    batch = tmp_path / "batches" / "batch_01.txt"
    code, nxt = run("next", batch)
    assert nxt["next"] == str(scan[0].resolve()) and nxt["doc_pages_total"] == 3
    assert nxt["survey_done"] is False and nxt["document"] == 1

    code, context = run("context", scan[0])
    assert context["source"]["source_path"] == "scan.pdf" and context["width"] == 400

    actions = tmp_path / "actions.json"
    actions.write_text(json.dumps([
        _add(box=(10.0, 10.0, 40.0, 40.0)),
        {"kind": "assess_page", "page_kind": "electrical_plan", "symbols_present": "yes",
         "labeling_complete": True},
    ]))
    code, acted = run("act", scan[0], "--actions", actions, "--agent", "test-vlm")
    assert code == 0 and acted["written"] is True
    assert acted["page_flags"]["vlm_assessment"]["agent"] == "test-vlm"

    code, nxt = run("next", batch)
    assert nxt["next"] == str(scan[1].resolve()) and nxt["batch_done"] == 1

    code, status = run("status", queue)
    assert status == {"pages": 5, "assessed": 1, "agent_boxes": 1,
                      "symbols_present": {"yes": 1}, "page_kind": {"electrical_plan": 1}}

    view = tmp_path / "v.png"
    code, written = run("view", scan[0], "--out", view, "--region", "0,0,200,150")
    assert code == 0 and view.read_bytes().startswith(b"\x89PNG")

    code, error = run("act", scan[0], "--actions", actions, "--revision", "0" * 64)
    assert code == 2 and error["type"] == "RevisionConflict"
    assert bulk[0].exists()


def _plan(d):
    """A wall, a receptacle-like circle with two ticks, and a line of text."""
    d.line([(0, 150), (400, 150)], fill="black", width=3)           # wall
    d.ellipse([200, 130, 224, 154], outline="black", width=2)       # device glyph
    d.line([(206, 142), (218, 142)], fill="black", width=2)
    for i, x in enumerate(range(40, 120, 9)):                       # a row of "letters"
        d.rectangle([x, 60, x + 6, 70], outline="black")


def test_marks_tighten_legend_and_document_tools(tmp_path, capsys):
    from annotator import agent_cli

    queue = tmp_path / "pool" / "label_queue"
    reserve = tmp_path / "pool" / "reserve"
    plan = _queue_page(queue, "set_p2", source_id="s", source_path="set.pdf", page=2, draw=_plan)
    legend_page = _queue_page(reserve, "set_p0", source_id="s", source_path="set.pdf", page=0,
                              draw=lambda d: d.ellipse([20, 20, 44, 44], outline="black", width=2))

    def run(*argv):
        code = agent_cli.main([str(a) for a in argv])
        out = capsys.readouterr().out
        return code, json.loads(out)

    batch = tmp_path / "batch.txt"
    batch.write_text(f"# set.pdf\n{plan}\n")
    import os
    os.chdir(tmp_path)                                   # relative --pool, as used on the remote
    code, built = run("index", "--pool", "pool")
    assert built == {"documents": 1, "pages": 2}

    code, doc = run("doc", batch, "--doc", 1)
    assert [p["folder"] for p in doc["pages"]] == ["reserve", "label_queue"]
    assert doc["pages"][1]["yours_to_label"] is True and doc["source_pdf_available"] is False

    # Marks find the device glyph, not the wall or the run of letters.
    code, marks = run("marks", plan, "--out", tmp_path / "m.png", "--region", "0,0,400,300",
                      "--min-px", "12", "--max-px", "60")
    assert marks["source"] == "raster"
    hits = [m for m in marks["marks"] if m["box"][0] >= 190 and m["box"][2] <= 235]
    assert len(hits) == 1, marks["marks"]
    assert not any(m["box"][1] < 80 for m in marks["marks"])          # text row dropped
    mark_id = hits[0]["id"]

    # Writing by mark id; tightening keeps the box on the glyph.
    actions = tmp_path / "a.json"
    actions.write_text(json.dumps([{"kind": "add_box", "family": "receptacle", "variant": "duplex",
                                    "mark": mark_id}]))
    code, acted = run("act", plan, "--actions", actions)
    assert code == 0, acted
    x0, y0, x1, y1 = acted["boxes"][0]["box"]
    assert 195 <= x0 <= 202 and 222 <= x1 <= 229 and 125 <= y0 <= 132

    # A loose hand-drawn box is tightened onto the ink.
    actions.write_text(json.dumps([{"kind": "add_box", "family": "receptacle",
                                    "box": [185, 115, 240, 158]}]))
    code, acted = run("act", plan, "--actions", actions)
    tight = acted["applied"][0]
    assert tight["tighten"] == "tightened" and tight["box"][0] >= 195 and tight["box"][2] <= 229

    # A box on empty paper is flagged.
    actions.write_text(json.dumps([{"kind": "add_box", "family": "receptacle",
                                    "box": [300, 200, 330, 230]}]))
    code, acted = run("act", plan, "--actions", actions, "--dry-run")
    assert any("empty paper" in w for w in acted["warnings"])

    # Reference pages are read-only.
    code, refused = run("act", legend_page, "--actions", actions)
    assert code == 2 and "read-only" in refused["error"]

    # Legend entries come from any page of the document and are shared across it.
    code, added = run("legend", "add", legend_page, "--box", "15,15,50,50", "--family", "receptacle",
                      "--variant", "duplex", "--label", "DUPLEX RECEPTACLE")
    assert added["added"]["id"] == "L1" and added["entries"] == 1
    code, listed = run("legend", "list", plan)
    assert listed["entries"][0]["label"] == "DUPLEX RECEPTACLE"

    code, viewed = run("view", plan, "--out", tmp_path / "v.png",
                       "--regions", "0,0,200,150;200,0,400,150;0,150,200,300", "--legend")
    from PIL import Image
    image = Image.open(tmp_path / "v.png")
    assert image.width > 400                                          # montage + legend panel

    code, nxt = run("next", batch)
    assert nxt["legend_entries"] == 1 and nxt["survey_done"] is False
    code, saved = run("survey", batch, "--doc", 1, "--note", "legend on p0", "--legend-pages", "0")
    code, nxt = run("next", batch)
    assert nxt["survey_done"] is True

    code, text = run("text", plan)
    assert text["available"] is False
