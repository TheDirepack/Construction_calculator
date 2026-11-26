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
    assert label_store.labeled_pages(bp)["train"] == []


def test_merged_pages_get_their_image_recorded_when_training_lists_them(drawing, tmp_path):
    import scripts_merge_labels as merge_script
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
    import scripts_merge_labels as merge_script
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
    import scripts_merge_labels as merge_script
    bp, pdf, _ = drawing
    done = merge_script.merge(bp, [tmp_path / "nothing"], apply=True, backup_root=tmp_path / "b")
    [row] = done["documents"]
    assert row["pages_written"] == 1 and row["proposal_files"] == ["plans_DEVICES.json"]
    session = LabelingSession(pdf=pdf, blueprints_dir=bp)
    assert sorted(document_labels.pages(session.out_dir, session.json_prefix)) == [1]
    assert len(session.page_state(0)["document"]["instances"]) == 3
    assert {r["page"]: r["pending"] for r in session.page_overview()["pages"]} == {0: 3}


def test_merge_prefers_an_agent_box_over_the_machine_proposal_under_it(drawing, tmp_path):
    import scripts_merge_labels as merge_script
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
