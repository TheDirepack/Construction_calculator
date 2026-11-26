"""One labels JSON per document (annotator.document_labels), and edits to a
drawing's *_DEVICES.json showing up in the annotator."""
from __future__ import annotations

import json
import multiprocessing
import shutil
import threading
from http.server import ThreadingHTTPServer
from pathlib import Path

import pytest

fitz = pytest.importorskip("fitz")

from annotator import document_labels, label_store, web  # noqa: E402
from annotator.web import LabelingSession, RevisionConflict, make_handler  # noqa: E402


@pytest.fixture(autouse=True)
def _real_layout(monkeypatch):
    monkeypatch.delenv("BPS_LABELS_DIR", raising=False)


def _pdf(path: Path, pages: int = 3) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    document = fitz.open()
    for number in range(pages):
        page = document.new_page(width=400, height=300)
        page.insert_text((20, 30), f"SHEET E{number + 1}")
    document.save(path)
    document.close()
    return path.resolve()


def _verified(ident, box=(70.0, 120.0, 95.0, 145.0)):
    return {"instance_id": ident, "symbol_family": "receptacle", "symbol_variant": "duplex",
            "bbox_xyxy": list(box), "uncertain": False, "review_status": "accepted",
            "review_source": "human", "verified_ground_truth": True}


def _device(page, box, kind="duplex_receptacle"):
    return {"pdf_page": page, "device_type": kind, "box_px_tl": list(box)}


@pytest.fixture
def drawing(tmp_path):
    """A training drawing with an extracted DEVICES file beside it."""
    bp = tmp_path / "blueprints_and_libraries" / "1_blueprints"
    pdf = _pdf(bp / "plans.pdf")
    devices = bp / "devices" / "plans_DEVICES.json"
    devices.parent.mkdir(parents=True)

    def write_devices(items):
        devices.write_text(json.dumps({"source_file": "plans.pdf", "devices": items}))

    write_devices([_device(1, (100, 100, 130, 130)), _device(1, (300, 100, 330, 130)),
                   _device(1, (500, 100, 530, 130))])
    return bp, pdf, write_devices


def _boxes(state):
    return sorted((round(i["bbox_xyxy"][0]), i["review_status"]) for i in state["document"]["instances"])


# ------------------------------------------------------------------ storage

def test_one_labels_file_holds_every_saved_page_with_independent_revisions(drawing):
    bp, pdf, _ = drawing
    session = LabelingSession(pdf=pdf, blueprints_dir=bp, auto_proposals=False)
    first = session.save(2, revision=None, instances=[_verified("c")], rejected=[])
    session.save(0, revision=None, instances=[_verified("a")], rejected=[])
    files = sorted(p.name for p in session.out_dir.glob("*.json"))
    assert files == ["document.json", "plans_labels.json"]
    data = json.loads((session.out_dir / "plans_labels.json").read_text())
    assert data["kind"] == "document_labels" and list(data["pages"]) == ["1", "3"]
    assert data["pages"]["3"]["instances"][0]["instance_id"] == "c"
    # Saving page 1 did not change page 3's revision: no false conflicts.
    assert session.page_state(2)["revision"] == first
    session.save(2, revision=first, instances=[_verified("c2")], rejected=[])
    assert [i["instance_id"] for i in session.page_state(2)["document"]["instances"]] == ["c2"]
    # The page's image path is relative to the document folder.
    assert (session.out_dir / data["pages"]["1"]["image_path"]).is_file()


def test_old_page_files_are_converted_once_and_kept(drawing):
    bp, pdf, _ = drawing
    session = LabelingSession(pdf=pdf, blueprints_dir=bp, auto_proposals=False)
    folder, prefix = session.out_dir, session.json_prefix
    session.save(0, revision=None, instances=[_verified("new_1")], rejected=[])
    entry = document_labels.pages(folder, prefix)[1]
    # Page files as older versions wrote them (page 1 also exists in the labels file).
    (folder / f"{prefix}_p0001.json").write_text(json.dumps(dict(entry, instances=[_verified("old_1")])))
    (folder / f"{prefix}_p0002.json").write_text(json.dumps(
        dict(entry, page_index=1, image_sha256=None, instances=[_verified("old_2")])))

    again = LabelingSession(pdf=pdf, blueprints_dir=bp, auto_proposals=False)
    assert [i["instance_id"] for i in again.page_state(1)["document"]["instances"]] == ["old_2"]
    assert [i["instance_id"] for i in again.page_state(0)["document"]["instances"]] == ["new_1"]
    assert not list(folder.glob(f"{prefix}_p*.json"))
    kept = sorted(p.name for p in (folder / document_labels.CONVERTED_DIR).iterdir())
    assert kept == [f"{prefix}_p0001.json", f"{prefix}_p0002.json"]
    assert document_labels.convert(folder, prefix) == 0          # nothing left to convert


def _write_pages(folder, start):
    for number in range(start, start + 25):
        document_labels.write_page(Path(folder), "doc", number, {"instances": [], "n": number})


def test_two_processes_saving_pages_of_one_document_keep_every_page(tmp_path):
    context = multiprocessing.get_context("spawn")
    workers = [context.Process(target=_write_pages, args=(str(tmp_path), start)) for start in (1, 101)]
    for worker in workers:
        worker.start()
    for worker in workers:
        worker.join(60)
        assert worker.exitcode == 0
    assert sorted(document_labels.pages(tmp_path, "doc")) == [*range(1, 26), *range(101, 126)]


def test_a_page_saved_by_another_writer_meanwhile_is_a_conflict(drawing):
    bp, pdf, _ = drawing
    browser = LabelingSession(pdf=pdf, blueprints_dir=bp, auto_proposals=False)
    agent, index, _ = label_store.open_page(label_store.load_document(browser.out_dir).page_json(1))
    seen = browser.page_state(0)["revision"]
    _, revision, _ = agent.page_document(index)
    agent.save(index, revision=revision, instances=[_verified("agent")], rejected=[])
    with pytest.raises(RevisionConflict):
        browser.save(0, revision=seen, instances=[_verified("browser")], rejected=[])
    # A writer that checked the revision but lost the race to the file is refused too.
    with pytest.raises(document_labels.StaleRevision):
        document_labels.write_page(browser.out_dir, browser.json_prefix, 1, {}, expected_revision=None)


def test_export_lists_every_page_without_rendering_unsaved_ones(drawing, monkeypatch):
    bp, pdf, _ = drawing
    session = LabelingSession(pdf=pdf, blueprints_dir=bp, auto_proposals=False)
    session.save(1, revision=None, instances=[_verified("b")], rejected=[])
    monkeypatch.setattr(web, "render_pdf_page",
                        lambda *a, **k: (_ for _ in ()).throw(AssertionError("rendered")))
    session._render_cache.clear()
    result = session.export_server()
    pages = result["export_payload"]["pages"]
    assert [p["instance_count"] for p in pages] == [0, 1, 0]
    assert pages[0]["json_path"] is None and pages[1]["json_path"].endswith("plans_labels.json")


# ------------------------------------------------------------------ DEVICES edits

def test_an_edited_devices_json_shows_on_a_page_already_saved(drawing):
    bp, pdf, write_devices = drawing
    session = LabelingSession(pdf=pdf, blueprints_dir=bp)
    state = session.page_state(0)
    assert _boxes(state) == [(100, "unreviewed"), (300, "unreviewed"), (500, "unreviewed")]
    first, second, third = sorted(state["document"]["instances"], key=lambda i: i["bbox_xyxy"][0])
    accepted = dict(first, review_status="accepted", review_source="human",
                    verified_ground_truth=True, uncertain=False)
    session.save(0, revision=state["revision"], instances=[accepted, third],
                 rejected=[dict(second, review_status="rejected", review_source="human")])
    saved_revision = session.page_state(0)["revision"]
    assert _boxes(session.page_state(0)) == [(100, "accepted"), (500, "unreviewed")]

    # Someone edits the DEVICES file: the third box moves, one device is added.
    write_devices([_device(1, (100, 100, 130, 130)), _device(1, (300, 100, 330, 130)),
                   _device(1, (600, 200, 630, 230)), _device(1, (700, 400, 730, 430))])
    state = session.page_state(0)
    # Accepted kept (not duplicated), rejected not proposed again, the moved and
    # the new device pending; the old position of the moved one is gone.
    assert _boxes(state) == [(100, "accepted"), (600, "unreviewed"), (700, "unreviewed")]
    assert len(state["document"]["rejected_proposals"]) == 1
    assert state["revision"] != saved_revision                 # open tabs see the change
    assert len({i["instance_id"] for i in state["document"]["instances"]}) == 3

    # Saving records the DEVICES revision: the page is not refreshed again.
    session.save(0, revision=state["revision"], instances=state["document"]["instances"],
                 rejected=state["document"]["rejected_proposals"])
    again = session.page_state(0)
    assert _boxes(again) == _boxes(state)
    assert again["revision"] == session.page_state(0)["revision"]

    # Removing a device from the file removes its pending box, never a reviewed one.
    write_devices([_device(1, (700, 400, 730, 430))])
    assert _boxes(session.page_state(0)) == [(100, "accepted"), (700, "unreviewed")]


def test_hand_drawn_and_verified_boxes_survive_a_devices_edit(drawing):
    bp, pdf, write_devices = drawing
    session = LabelingSession(pdf=pdf, blueprints_dir=bp)
    state = session.page_state(0)
    proposal = sorted(state["document"]["instances"], key=lambda i: i["bbox_xyxy"][0])[0]
    redrawn = dict(proposal, bbox_xyxy=[110.0, 110.0, 140.0, 140.0], review_source="manual_draft")
    drawn = dict(_verified("manual_p1_9", (200.0, 250.0, 230.0, 280.0)))
    session.save(0, revision=state["revision"], instances=[redrawn, drawn], rejected=[])
    write_devices([_device(1, (700, 400, 730, 430))])
    state = session.page_state(0)
    ids = sorted(i["instance_id"] for i in state["document"]["instances"])
    assert redrawn["instance_id"] in ids and "manual_p1_9" in ids and len(ids) == 3


def test_emptying_the_devices_json_removes_its_pending_boxes(drawing, monkeypatch):
    from blueprint_symbols import blueprint_sets
    monkeypatch.setattr(LabelingSession, "PROPOSALS_REDISCOVER_SECONDS", 0.0)
    monkeypatch.setattr(blueprint_sets, "_DEVICES_TTL_S", 0.0)
    bp, pdf, write_devices = drawing
    session = LabelingSession(pdf=pdf, blueprints_dir=bp)
    state = session.page_state(0)
    first = sorted(state["document"]["instances"], key=lambda i: i["bbox_xyxy"][0])[0]
    kept = dict(first, review_status="accepted", review_source="human",
                verified_ground_truth=True, uncertain=False)
    session.save(0, revision=state["revision"], instances=[kept] + state["document"]["instances"][1:],
                 rejected=[])
    write_devices([])
    assert _boxes(session.page_state(0)) == [(100, "accepted")]


def test_pages_saved_before_the_devices_record_are_not_flooded(drawing):
    bp, pdf, write_devices = drawing
    session = LabelingSession(pdf=pdf, blueprints_dir=bp, auto_proposals=False)
    session.save(0, revision=None, instances=[_verified("human_only")], rejected=[])
    # As older versions saved it: no record of the proposal file at all.
    folder, prefix = session.out_dir, session.json_prefix
    entry = document_labels.pages(folder, prefix)[1]
    entry = {k: v for k, v in entry.items() if k != "proposals_sha256"}
    data = json.loads(document_labels.labels_path(folder, prefix).read_text())
    data["pages"]["1"] = entry
    data.pop("proposals", None)
    document_labels.labels_path(folder, prefix).write_text(json.dumps(data))

    opened = LabelingSession(pdf=pdf, blueprints_dir=bp)
    assert [i["instance_id"] for i in opened.page_state(0)["document"]["instances"]] == ["human_only"]
    # From now on, edits to the DEVICES file do show up.
    write_devices([_device(1, (600, 200, 630, 230))])
    assert _boxes(opened.page_state(0)) == [(70, "accepted"), (600, "unreviewed")]


def test_a_devices_file_added_later_is_picked_up_without_a_restart(tmp_path, monkeypatch):
    bp = tmp_path / "blueprints_and_libraries" / "1_blueprints"
    pdf = _pdf(bp / "late.pdf")
    from blueprint_symbols import blueprint_sets
    monkeypatch.setattr(LabelingSession, "PROPOSALS_REDISCOVER_SECONDS", 0.0)
    monkeypatch.setattr(blueprint_sets, "_DEVICES_TTL_S", 0.0)
    session = LabelingSession(pdf=pdf, blueprints_dir=bp)
    assert session.page_state(1)["document"]["instances"] == []
    (bp / "devices").mkdir(exist_ok=True)
    (bp / "devices" / "late_DEVICES.json").write_text(json.dumps(
        {"source_file": "late.pdf", "devices": [_device(2, (100, 100, 130, 130)),
                                                _device(3, (100, 100, 130, 130))]}))
    assert len(session.page_state(1)["document"]["instances"]) == 1
    rows = {r["page"]: r["pending"] for r in session.page_overview()["pages"]}
    assert rows == {1: 1, 2: 1}
    assert session.page_state(1)["proposals_enabled"] is True


def test_overview_counts_new_devices_on_saved_pages(drawing):
    bp, pdf, write_devices = drawing
    session = LabelingSession(pdf=pdf, blueprints_dir=bp)
    state = session.page_state(0)
    accepted = [dict(i, review_status="accepted", review_source="human", verified_ground_truth=True,
                     uncertain=False) for i in state["document"]["instances"]]
    session.save(0, revision=state["revision"], instances=accepted, rejected=[])
    assert {r["page"]: r["pending"] for r in session.page_overview()["pages"]} == {0: 0}
    write_devices([_device(1, (100, 100, 130, 130)), _device(1, (300, 100, 330, 130)),
                   _device(1, (500, 100, 530, 130)), _device(1, (700, 400, 730, 430))])
    assert {r["page"]: r["pending"] for r in session.page_overview()["pages"]} == {0: 1}
    assert _boxes(session.page_state(0)).count((700, "unreviewed")) == 1


def test_devices_json_wins_over_an_older_sam_run(drawing, monkeypatch, tmp_path):
    bp, pdf, _ = drawing
    work = tmp_path / "work"
    (work / "sam3_cleaned").mkdir(parents=True)
    (work / "sam3_cleaned" / "plans_x_CLEANED.json").write_text(json.dumps(
        {"render_dpi": 150, "devices": [_device(1, (10, 10, 20, 20))]}))
    monkeypatch.setattr(web, "DEFAULT_WORK_DIR", work)
    assert web._find_proposals_for_pdf(pdf).name == "plans_DEVICES.json"


# ------------------------------------------------------------------ training and pushing

def test_training_reads_pages_straight_from_the_labels_file(drawing, tmp_path):
    from blueprint_symbols import label_schema
    bp, pdf, _ = drawing
    session = LabelingSession(pdf=pdf, blueprints_dir=bp, auto_proposals=False)
    revision = session.save(1, revision=None, instances=[_verified("t")], rejected=[])
    _mark_done(bp)
    [ref] = label_store.labeled_pages(bp)["train"]
    assert str(ref) == f"{session.out_dir / 'plans_labels.json'}#p2"
    data = label_schema.read_page(ref)
    assert Path(data["image_path"]).is_absolute() and (ref.parent / data["image_path"]).is_file()
    assert label_schema.load_training_page(ref)["instances"][0]["instance_id"] == "t"
    doc, page = label_store.page_source(ref)
    assert (doc.folder, page) == (session.out_dir, 2)
    assert label_schema.expand_page_paths([session.out_dir / "plans_labels.json"]) == [ref]
    assert not (tmp_path / "label_pages").exists()               # no copies are made
    # Un-verifying the page takes it out of training.
    pending = dict(_verified("t"), review_status="unreviewed", verified_ground_truth=False, uncertain=True)
    session.save(1, revision=revision, instances=[pending], rejected=[])
    _mark_done(bp)
    assert label_store.labeled_pages(bp)["train"] == []


def test_merged_pages_get_their_image_recorded_when_training_lists_them(drawing, tmp_path):
    import scripts_unify_labels as merge_script
    bp, pdf, _ = drawing
    session = LabelingSession(pdf=pdf, blueprints_dir=bp, auto_proposals=False)
    width, height = session.page_pixel_size(0)
    old = tmp_path / "old"
    old.mkdir()
    (old / "plans_p0001.json").write_text(json.dumps({
        "document_id": pdf.name, "page_index": 0, "source_pdf_sha256": web.sha256_file(pdf),
        "image_width": width, "image_height": height, "instances": [_verified("v")],
        "rejected_proposals": []}))
    merge_script.merge(bp, [old], apply=True, with_proposals=False, backup_root=tmp_path / "b")
    assert document_labels.pages(session.out_dir, "plans")[1]["image_sha256"] is None
    shutil.rmtree(session.out_dir / "images", ignore_errors=True)
    _mark_done(bp)
    [ref] = label_store.labeled_pages(bp)["train"]
    stored = document_labels.pages(session.out_dir, "plans")[1]
    assert stored["image_sha256"] and (session.out_dir / stored["image_path"]).is_file()


def test_pushed_pages_merge_into_the_remote_labels_file(drawing, tmp_path):
    bp, pdf, _ = drawing
    local = LabelingSession(pdf=pdf, blueprints_dir=bp, auto_proposals=False)
    local.save(0, revision=None, instances=[_verified("local_1")], rejected=[])
    local.save(1, revision=None, instances=[dict(_verified("draft"), review_status="unreviewed",
                                                 verified_ground_truth=False, uncertain=True)],
               rejected=[])                                       # not usable: stays here

    remote_root = tmp_path / "remote" / "blueprints_and_libraries"
    remote_bp = remote_root / "1_blueprints"
    (remote_bp / "devices").mkdir(parents=True)
    shutil.copy2(pdf, remote_bp / pdf.name)
    remote = LabelingSession(pdf=remote_bp / pdf.name, blueprints_dir=remote_bp, auto_proposals=False)
    remote.save(0, revision=None, instances=[_verified("remote_1")], rejected=[])
    remote.save(2, revision=None, instances=[_verified("agent_3")], rejected=[])

    stage = tmp_path / "stage"
    staged, images, count = label_store.stage_push(bp, stage)
    assert count == 1 and len(staged) == 1 and len(images) == 1
    for rel in staged:                                            # what rsync does
        (remote_bp / rel).parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(stage / rel, remote_bp / rel)
    result = label_store.merge_pushed(remote_bp, remote_root)
    assert result["pages_written"] == 1 and result["pages_replaced"] == 1

    pages = document_labels.pages(remote.out_dir, remote.json_prefix)
    assert sorted(pages) == [1, 3]
    assert pages[1]["instances"][0]["instance_id"] == "local_1"
    assert pages[3]["instances"][0]["instance_id"] == "agent_3"
    assert sorted(p.name for p in remote.out_dir.glob("*.json")) == ["document.json", "plans_labels.json"]
    assert label_store.merge_pushed(remote_bp, remote_root)["pages_written"] == 0


# ------------------------------------------------------------------ browser

def test_browser_shows_a_devices_edit_when_returning_to_a_page(drawing):
    from test_annotator_browser import _launch_browser
    sync_api = pytest.importorskip("playwright.sync_api")
    bp, pdf, write_devices = drawing
    session = LabelingSession(pdf=pdf, blueprints_dir=bp)
    server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(session))
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        with sync_api.sync_playwright() as pw:
            try:
                browser = _launch_browser(pw)
            except Exception as exc:  # pragma: no cover - no browser installed
                pytest.skip(f"no browser: {exc}")
            page = browser.new_page(viewport={"width": 1400, "height": 900})
            page.goto(f"http://127.0.0.1:{server.server_port}/")
            count = "() => document.querySelector('#count').textContent"
            page.wait_for_function("() => document.querySelector('#count').textContent.includes('3 pending')")
            page.keyboard.press("]")
            page.wait_for_function("() => document.querySelector('#pageIndicator').textContent === 'Page 2 / 3'"
                                   " && !window.__labelerState.busy")
            write_devices([_device(1, (100, 100, 130, 130))])
            page.keyboard.press("[")
            page.wait_for_function("() => document.querySelector('#pageIndicator').textContent === 'Page 1 / 3'"
                                   " && !window.__labelerState.busy")
            page.wait_for_function("() => document.querySelector('#count').textContent.includes('1 pending')")
            assert page.evaluate(count).startswith("1 active")
            browser.close()
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=10)


# ------------------------------------------------------------------ merging everything into one file

def test_merge_puts_every_source_for_a_pdf_into_its_one_labels_file(drawing, tmp_path):
    import scripts_unify_labels as merge_script
    bp, pdf, _ = drawing
    sha = web.sha256_file(pdf)
    width, height = LabelingSession(pdf=pdf, blueprints_dir=bp).page_pixel_size(0)

    def page(index, instances=(), rejected=(), scale=1.0, **extra):
        return {"document_id": pdf.name, "page_index": index, "source_pdf_sha256": sha,
                "image_width": round(width * scale), "image_height": round(height * scale),
                "coordinate_system": "original_page_pixels_top_left_y_down",
                "instances": list(instances), "rejected_proposals": list(rejected), **extra}

    old = tmp_path / "old_annotator"                  # an older tool, rendered at half size
    old.mkdir()
    human = _verified("old_human", (50.0, 50.0, 65.0, 65.0))            # = DEVICES box 1 at half size
    no = dict(_verified("old_rejected", (150.0, 50.0, 165.0, 65.0)), review_status="rejected",
              verified_ground_truth=False, uncertain=True)               # = DEVICES box 2
    (old / "plans_p0001.json").write_text(json.dumps(page(0, [human], [no], scale=0.5)))
    agent_box = {"instance_id": "agent_1", "symbol_family": "switch", "symbol_variant": "single_pole",
                 "bbox_xyxy": [400.0, 400.0, 430.0, 430.0], "uncertain": True,
                 "review_status": "unreviewed", "review_source": "vlm_agent", "verified_ground_truth": False}
    over_proposal = dict(agent_box, instance_id="agent_2", bbox_xyxy=[101.0, 101.0, 131.0, 131.0])
    (old / "plans_p0002.json").write_text(json.dumps(page(1, [agent_box])))
    (old / "plans_p0001.agent.json").write_text(json.dumps(page(0, [over_proposal])))
    (old / "other_revision_p0001.json").write_text(json.dumps(dict(page(0, [agent_box]),
                                                                   source_pdf_sha256="0" * 64)))
    remote = tmp_path / "remote_labels" / "plans_x"                   # another machine's labels file
    remote.mkdir(parents=True)
    (remote / "plans_labels.json").write_text(json.dumps({
        "kind": "document_labels", "format_version": 1, "json_prefix": "plans",
        "pages": {"3": page(2, verified_no_symbols=True)}}))
    sources = [old, tmp_path / "remote_labels"]
    before = {p: p.read_bytes() for s in sources for p in s.rglob("*") if p.is_file()}

    dry = merge_script.merge(bp, sources, apply=False, backup_root=tmp_path / "backups")
    [row] = dry["documents"]
    assert row["pages_from_files"] == 3 and row["page_files"] == 4
    assert not list((bp / "devices").glob("*/plans_labels.json"))

    done = merge_script.merge(bp, sources, apply=True, backup_root=tmp_path / "backups")
    [row] = done["documents"]
    assert row["pages_written"] == 3 and row["invalid_dropped"] == 0
    assert {p: p.read_bytes() for s in sources for p in s.rglob("*") if p.is_file()} == before
    session = LabelingSession(pdf=pdf, blueprints_dir=bp)
    stored = document_labels.pages(session.out_dir, session.json_prefix)
    assert sorted(stored) == [1, 2, 3]
    first = session.page_state(0)                   # opens without re-render conflicts
    assert _boxes(first) == [(100, "accepted"), (500, "unreviewed")]     # human won, rejected gone
    assert {i["review_source"] for i in first["document"]["instances"]} == {"human", "devices_json"}
    assert first["document"]["instances"][0]["instance_id"] == "old_human"
    assert [r["bbox_xyxy"][0] for r in first["document"]["rejected_proposals"]] == [300.0]
    assert [i["instance_id"] for i in session.page_state(1)["document"]["instances"]] == ["agent_1"]
    third = session.page_state(2)["document"]
    assert third["verified_no_symbols"] is True and third["instances"] == []
    # The merged page saves normally from the annotator.
    session.save(0, revision=first["revision"], instances=first["document"]["instances"],
                 rejected=first["document"]["rejected_proposals"])

    again = merge_script.merge(bp, sources, apply=True, backup_root=tmp_path / "backups")
    assert again["documents"][0]["pages_written"] == 0                  # idempotent
    assert list((tmp_path / "backups").rglob("plans_labels.json"))      # changed files were copied first


def test_merge_writes_proposal_pages_of_drawings_never_opened(drawing, tmp_path):
    import scripts_unify_labels as merge_script
    bp, pdf, _ = drawing
    done = merge_script.merge(bp, [tmp_path / "nothing"], apply=True, backup_root=tmp_path / "b")
    [row] = done["documents"]
    assert row["pages_written"] == 1 and row["proposal_files"] == ["plans_DEVICES.json"]
    session = LabelingSession(pdf=pdf, blueprints_dir=bp)
    assert sorted(document_labels.pages(session.out_dir, session.json_prefix)) == [1]
    assert len(session.page_state(0)["document"]["instances"]) == 3
    assert {r["page"]: r["pending"] for r in session.page_overview()["pages"]} == {0: 3}


def test_merge_prefers_an_agent_box_over_the_machine_proposal_under_it(drawing, tmp_path):
    import scripts_unify_labels as merge_script
    bp, pdf, _ = drawing
    session = LabelingSession(pdf=pdf, blueprints_dir=bp)
    width, height = session.page_pixel_size(0)
    old = tmp_path / "agents"
    old.mkdir()
    agent = {"instance_id": "agent_7", "symbol_family": "receptacle", "symbol_variant": "quad",
             "bbox_xyxy": [101.0, 101.0, 131.0, 131.0], "uncertain": True, "review_status": "unreviewed",
             "review_source": "vlm_agent", "verified_ground_truth": False}
    (old / "plans_p0001.json").write_text(json.dumps({
        "document_id": pdf.name, "page_index": 0, "source_pdf_sha256": web.sha256_file(pdf),
        "image_width": width, "image_height": height, "instances": [agent], "rejected_proposals": []}))
    merge_script.merge(bp, [old], apply=True, backup_root=tmp_path / "b")
    items = LabelingSession(pdf=pdf, blueprints_dir=bp).page_state(0)["document"]["instances"]
    assert sorted((round(i["bbox_xyxy"][0]), i["review_source"]) for i in items) == \
        [(101, "vlm_agent"), (300, "devices_json"), (500, "devices_json")]


# ------------------------------------------------------------------ unifying device JSONs (format 2)

def test_unify_keeps_what_the_devices_json_says_and_records_its_sources(tmp_path):
    import scripts_unify_labels as unify
    bp = tmp_path / "blueprints_and_libraries" / "1_blueprints"
    pdf = _pdf(bp / "rich.pdf", pages=2)
    (bp / "devices").mkdir(parents=True, exist_ok=True)
    (bp / "devices" / "rich_DEVICES.json").write_text(json.dumps({
        "schema_version": "3.1.0", "source_file": "rich.pdf", "extraction_revision": "v7",
        "legend_types": [{"code": "D", "description": "duplex receptacle"}],
        "page_results": [{"pdf_page": 1, "sheet": "E1.1", "devices": 2}],
        "devices": [
            {"id": "E1-001", "pdf_page": 1, "sheet": "E1.1", "device_code": "D",
             "device_type": "receptacle", "subtype": "duplex", "attributes": {"weatherproof": True},
             "symbol_bbox": [100, 200, 112, 212], "symbol_center": [106, 206],
             "on_wall": True, "wall_connection_point": [106, 214], "confidence": "high",
             "verification": "vector glyph", "vector_path_ids": list(range(40))},
            {"id": "E1-002", "pdf_page": 1, "device_type": "exhaust_fan",
             "symbol_bbox": [200, 200, 220, 220]},                       # mechanical: left out
            {"id": "E1-003", "pdf_page": 1, "device_type": "switch", "placement": "text_label_proxy",
             "symbol_bbox": [10, 10, 20, 20]},                           # a text label: left out
            {"id": "E1-004", "pdf_page": 9, "device_type": "switch",
             "symbol_bbox": [10, 10, 20, 20]}],                          # no page 9
        "unresolved_candidates": [
            {"pdf_page": 2, "inferred_type": "switch", "inferred_subtype": "single_pole",
             "symbol_bbox": [50, 50, 60, 60], "reason": "no legend entry"},
            {"pdf_page": 2, "inferred_type": "switch", "label_bbox": [70, 70, 80, 80],
             "reason": "label only"}]}))
    report = unify.merge(bp, [], apply=True, backup_root=tmp_path / "b", review_roots=[])
    assert report["documents"][0]["pages_written"] == 2
    session = LabelingSession(pdf=pdf, blueprints_dir=bp)
    data = json.loads(document_labels.labels_path(session.out_dir, "rich").read_text())
    assert data["format_version"] == 2
    assert data["document"]["file"] == "rich.pdf" and data["document"]["page_count"] == 2
    [source] = data["sources"]
    assert source["kind"] == "devices_json" and source["devices_used"] == 1
    assert source["devices_left_out"] == {"not_an_electrical_device": 1, "text_proxy": 1,
                                          "page_outside_document": 1}
    assert data["extracted"]["rich_DEVICES.json"]["legend_types"][0]["code"] == "D"
    assert len(data["extracted"]["rich_DEVICES.json"]["unresolved_candidates"]) == 2

    [box] = session.page_state(0)["document"]["instances"]
    details = box["proposal_details"]
    assert (details["id"], details["sheet"], details["device_code"]) == ("E1-001", "E1.1", "D")
    assert details["attributes"] == {"weatherproof": True} and "vector_path_ids" not in details
    assert set(box["anchors"]) == {"centre", "wall_attachment"}
    assert box["anchors"]["wall_attachment"]["annotated"] is False
    cx = (box["bbox_xyxy"][0] + box["bbox_xyxy"][2]) / 2
    assert abs(box["anchors"]["centre"]["xy"][0] - cx) < 1
    assert box["anchors"]["wall_attachment"]["xy"][1] < box["bbox_xyxy"][1]      # above, in pixels
    # The unresolved candidate with a symbol box is a box to review; the label-only one is not.
    [unresolved] = session.page_state(1)["document"]["instances"]
    assert unresolved["review_source"] == "devices_json_unresolved"
    assert unresolved["proposal_details"]["reason"] == "no legend entry"
    # Reviewing the box keeps its details through a save.
    state = session.page_state(0)
    session.save(0, revision=state["revision"], instances=state["document"]["instances"], rejected=[])
    assert session.page_state(0)["document"]["instances"][0]["proposal_details"]["id"] == "E1-001"


def test_vlm_reviewed_detections_replace_the_raw_ones_in_the_labels_file(tmp_path, monkeypatch):
    import scripts_unify_labels as unify
    bp = tmp_path / "blueprints_and_libraries" / "1_blueprints"
    pdf = _pdf(bp / "net.pdf", pages=1)
    work = tmp_path / "work"
    monkeypatch.setattr(web, "DEFAULT_WORK_DIR", work)
    monkeypatch.setattr(unify, "DEFAULT_WORK_DIR", work)
    (work / "circuitnet_proposals").mkdir(parents=True)
    (work / "circuitnet_proposals" / "net_ab12_CIRCUITNET.json").write_text(json.dumps({
        "source_file": "net.pdf", "render_dpi": 150, "model": "circuits.onnx",
        "devices": [_device(1, (200, 200, 230, 230), "receptacle"),
                    _device(1, (600, 400, 630, 430), "receptacle")]}))
    review = work / "circuitnet_review_zips" / "net_ab12"
    review.mkdir(parents=True)
    base = {"pdf_page": 1, "provisional_device_type": "receptacle", "score": 0.9}
    (review / "net_ab12_batch_001.json").write_text(json.dumps({
        "schema": "blueprint_device_refinement_batch_v2", "source_file": "net.pdf", "batch_index": 1,
        "items": [
            dict(base, device_index=0, review_status="accepted", keep=True,
                 crop_pdf_rect_bl=[96, 189.6, 110.4, 204], refined_bbox_cell=[0, 0, 1000, 1000],
                 refined_device_type="receptacle", refined_subtype="duplex", on_wall=False,
                 wall_contact_cell=[500, 500], attributes={}, review_source="vlm_reviewer"),
            dict(base, device_index=1, review_status="rejected", keep=False,
                 provisional_bbox_pdf_bl=[288, 93.6, 302.4, 108])]}))

    dry = unify.merge(bp, [], apply=False, backup_root=tmp_path / "b",
                      review_roots=[work / "circuitnet_review_zips"])
    assert dry["vlm_reviews"]["reviewed_pdfs"] == 1 and not (work / "circuitnet_refined_devices").exists()
    done = unify.merge(bp, [], apply=True, backup_root=tmp_path / "b",
                       review_roots=[work / "circuitnet_review_zips"])
    assert done["vlm_reviews"] == {"reviewed_pdfs": 1, "kept": 1, "removed": 1, "unreadable": {},
                                   "written": ["net_ab12_REFINED_DEVICES.json"]}
    session = LabelingSession(pdf=pdf, blueprints_dir=bp)
    [box] = session.page_state(0)["document"]["instances"]
    assert box["review_source"] == "reviewed_provisional" and box["symbol_variant"] == "duplex"
    assert box["proposal_reviewer"] == "vlm_reviewer" and box["verified_ground_truth"] is False
    kinds = {s["kind"]: s for s in json.loads(session._json_path(0).read_text())["sources"]}
    assert kinds["vlm_reviewed"]["vlm_rejected"] == 1 and kinds["circuitnet"]["model"] == "circuits.onnx"


def test_training_finds_pages_by_globbing_the_labels_files(drawing, tmp_path):
    import scripts_prepare_training_data as prep
    bp, pdf, _ = drawing
    session = LabelingSession(pdf=pdf, blueprints_dir=bp, auto_proposals=False)
    session.save(0, revision=None, instances=[_verified("a")], rejected=[])
    session.save(2, revision=None, instances=[_verified("c")], rejected=[])
    pattern = str(bp / "devices" / "*" / "*_labels.json")
    assert prep.discover([pattern]) == []                     # not marked done: nothing trains
    _mark_done(bp)
    found = prep.discover([pattern])
    labels = session.out_dir / "plans_labels.json"
    assert found == [Path(f"{labels}#p1"), Path(f"{labels}#p3")]
    assert prep.complete_pages([str(p) for p in found]) == []



# ------------------------------------------------------------------ "PDF done -> training"

def test_training_uses_only_pdfs_marked_done(drawing, tmp_path):
    from blueprint_symbols import label_schema
    from blueprint_symbols.dataset_manifest import build_manifest
    bp, pdf, _ = drawing
    session = LabelingSession(pdf=pdf, blueprints_dir=bp, auto_proposals=False)
    session.save(0, revision=None, instances=[_verified("a")], rejected=[])
    labels = session.out_dir / "plans_labels.json"
    assert session.page_state(0)["training_ready"] is False
    assert label_store.labeled_pages(bp)["train"] == []
    assert label_schema.expand_page_paths([labels]) == []
    ref = label_schema.page_ref(labels, 1)
    assert not label_schema.page_training_ready(ref)
    with pytest.raises(ValueError, match="no training annotation"):
        build_manifest([ref], val_fraction=.25, test_fraction=.25)    # an explicit page is refused too

    result = session.set_training_ready(True)
    assert result["training_ready"] is True and result["usable_pages"] == [1]
    assert session.page_state(0)["training_ready"] is True
    assert label_store.labeled_pages(bp)["train"] == [ref]
    record = json.loads(labels.read_text())["training_ready"]
    assert record["ready"] is True and record["set_by"] == "annotator" and record["set_at"]
    # Later saves keep the flag; unmarking takes the PDF out again.
    state = session.page_state(1)
    session.save(1, revision=state["revision"], instances=[_verified("b")], rejected=[])
    assert len(label_store.labeled_pages(bp)["train"]) == 2
    session.set_training_ready(False)
    assert label_store.labeled_pages(bp)["train"] == []
    with pytest.raises(web.LabelingError):
        session.set_training_ready("yes")


def test_done_flag_over_http_and_in_the_pdf_list(drawing):
    import http.client
    bp, pdf, _ = drawing
    other = _pdf(bp / "other.pdf")
    session = LabelingSession(pdf=pdf, blueprints_dir=bp, auto_proposals=False)
    server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(session))
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        def post(query, body, token=session.token):
            conn = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=20)
            conn.request("POST", "/api/training_ready" + query, body=json.dumps(body),
                         headers={"Content-Type": "application/json", "X-Labeler-Token": token})
            resp = conn.getresponse()
            data = json.loads(resp.read())
            conn.close()
            return resp.status, data
        from urllib.parse import quote
        assert post("?doc=" + quote(str(other), safe=""), {"ready": True}, token="wrong")[0] == 403
        status, data = post("?doc=" + quote(str(other), safe=""), {"ready": True})
        assert status == 200 and data["training_ready"] is True
        assert post("?doc=" + quote(str(other), safe="") + "&page=1", {"ready": True})[0] == 400
        listed = {d["name"]: d for d in session.list_documents()}
        assert listed["other.pdf"]["training_ready"] is True
        assert listed["plans.pdf"]["training_ready"] is False
        assert session.for_document(str(other)).is_training_ready()
        assert not session.is_training_ready()
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=10)


def test_the_done_flag_travels_with_pushed_labels(drawing, tmp_path):
    bp, pdf, _ = drawing
    local = LabelingSession(pdf=pdf, blueprints_dir=bp, auto_proposals=False)
    local.save(0, revision=None, instances=[_verified("local_1")], rejected=[])
    local.set_training_ready(True)
    remote_root = tmp_path / "remote" / "blueprints_and_libraries"
    remote_bp = remote_root / "1_blueprints"
    (remote_bp / "devices").mkdir(parents=True)
    shutil.copy2(pdf, remote_bp / pdf.name)
    remote = LabelingSession(pdf=remote_bp / pdf.name, blueprints_dir=remote_bp, auto_proposals=False)
    assert not remote.is_training_ready()
    staged, _, _ = label_store.stage_push(bp, tmp_path / "stage")
    for rel in staged:
        (remote_bp / rel).parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(tmp_path / "stage" / rel, remote_bp / rel)
    label_store.merge_pushed(remote_bp, remote_root)
    assert remote.is_training_ready()


def test_browser_marks_a_pdf_done(drawing):
    from test_annotator_browser import _launch_browser
    sync_api = pytest.importorskip("playwright.sync_api")
    bp, pdf, _ = drawing
    session = LabelingSession(pdf=pdf, blueprints_dir=bp, auto_proposals=False)
    session.save(0, revision=None, instances=[_verified("a")], rejected=[])
    server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(session))
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        with sync_api.sync_playwright() as pw:
            try:
                browser = _launch_browser(pw)
            except Exception as exc:  # pragma: no cover
                pytest.skip(f"no browser: {exc}")
            page = browser.new_page(viewport={"width": 1400, "height": 900})
            page.goto(f"http://127.0.0.1:{server.server_port}/")
            page.wait_for_function("() => window.__labelerState && window.__labelerState.doc"
                                   " && !window.__labelerState.busy")
            box = page.locator("#trainingReady")
            assert not box.is_checked()
            box.check()
            page.wait_for_function("() => document.querySelector('#message').textContent.startsWith('PDF marked done')")
            assert "1 reviewed page(s)" in page.locator("#message").inner_text()
            assert session.is_training_ready()
            page.reload()
            page.wait_for_function("() => window.__labelerState && window.__labelerState.doc"
                                   " && !window.__labelerState.busy")
            assert page.locator("#trainingReady").is_checked()
            page.locator("#trainingReady").uncheck()
            page.wait_for_function("() => document.querySelector('#message').textContent.startsWith('PDF unmarked')")
            assert not session.is_training_ready()
            browser.close()
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=10)


def _mark_done(store):
    """The labeler's "PDF done -> training" flag on every PDF of a store."""
    from annotator import document_labels, label_store as _ls
    for doc in _ls.iter_documents(store, skip_missing=True):
        if doc.kind == "pdf":
            document_labels.set_training_ready(doc.folder, doc.json_prefix, True)
