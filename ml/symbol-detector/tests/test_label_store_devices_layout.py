"""Labels live in the devices folder beside each drawing (annotator.label_store)."""
from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
from pathlib import Path

import pytest

fitz = pytest.importorskip("fitz")

from annotator import agent_cli, label_store  # noqa: E402
from annotator.web import LabelingSession  # noqa: E402
from blueprint_symbols.blueprint_sets import devices_files  # noqa: E402

HERE = Path(__file__).resolve().parents[1]


@pytest.fixture(autouse=True)
def _no_flat_store(monkeypatch):
    # conftest points every test at a flat store; these tests use the real layout.
    monkeypatch.delenv("BPS_LABELS_DIR", raising=False)


def _pdf(path: Path, pages: int = 2) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    document = fitz.open()
    for _ in range(pages):
        page = document.new_page(width=400, height=300)
        page.draw_rect(fitz.Rect(50, 50, 65, 65), color=(0, 0, 0))
    document.save(path)
    document.close()
    return path


def _verified(ident="manual_p1_1"):
    return {"instance_id": ident, "symbol_family": "receptacle", "symbol_variant": "duplex",
            "bbox_xyxy": [70.0, 120.0, 95.0, 145.0], "uncertain": False,
            "review_status": "accepted", "review_source": "human", "verified_ground_truth": True}


@pytest.fixture
def blueprints(tmp_path):
    bp = tmp_path / "blueprints_and_libraries" / "1_blueprints"
    train = _pdf(bp / "train_set.pdf")
    exam = _pdf(bp / "evaluation" / "exam_set.pdf")
    devices = bp / "devices"
    devices.mkdir(parents=True)
    (devices / "train_set_DEVICES.json").write_text(json.dumps({
        "source_file": "train_set.pdf",
        "devices": [{"pdf_page": 1, "device_type": "duplex_receptacle",
                     "box_px_tl": [60, 60, 90, 90]}],
    }))
    return bp, train.resolve(), exam.resolve()


def test_documents_are_created_beside_their_drawings(blueprints):
    bp, train, exam = blueprints
    t = label_store.create_document(bp, train)
    e = label_store.create_document(bp, exam)
    assert t.folder.parent == bp / "devices"
    assert e.folder.parent == bp / "evaluation" / "devices"
    assert {d.source for d in label_store.iter_documents(bp)} == {train, exam}
    # The DEVICES lookup still sees only the DEVICES files.
    assert set(devices_files(bp / "devices")) == {"train_set.pdf"}


def test_annotator_saves_into_the_devices_folder(blueprints):
    bp, train, exam = blueprints
    for pdf in (train, exam):
        session = LabelingSession(pdf=pdf, blueprints_dir=bp)
        assert session.out_dir.parent == pdf.parent / "devices"
        session.save(0, revision=None, instances=[_verified()], rejected=[])
        assert (session.out_dir / label_store.DOCUMENT_FILE).is_file()
        # One labels file per document, beside document.json.
        assert [p.name for p in session.out_dir.glob("*.json")] and \
            {p.name for p in session.out_dir.glob("*.json")} == {"document.json", f"{pdf.stem}_labels.json"}
    _mark_done(bp)
    pages = label_store.labeled_pages(bp)
    folder = lambda view: label_store.page_source(view)[0].folder.parent  # noqa: E731
    assert [folder(p) for p in pages["train"]] == [bp / "devices"]
    assert [folder(p) for p in pages["evaluation"]] == [bp / "evaluation" / "devices"]
    listed = {d["name"]: d for d in LabelingSession(pdf=train, blueprints_dir=bp).list_documents()}
    assert listed["train_set.pdf"]["has_annotations"] and listed["exam_set.pdf"]["has_annotations"]
    # Nothing was written in the drawing folders outside devices/.
    written = {p.relative_to(bp).parts[0] for p in bp.rglob("*") if p.is_file()}
    assert written == {"train_set.pdf", "devices", "evaluation"}
    assert {p.relative_to(bp / "evaluation").parts[0] for p in (bp / "evaluation").rglob("*")
            if p.is_file()} == {"exam_set.pdf", "devices"}


def test_a_drawing_moved_to_evaluation_keeps_its_labels(blueprints):
    bp, train, _ = blueprints
    session = LabelingSession(pdf=train, blueprints_dir=bp)
    session.save(0, revision=None, instances=[_verified()], rejected=[])
    old_folder = session.out_dir
    moved = bp / "evaluation" / train.name
    shutil.move(train, moved)
    again = LabelingSession(pdf=moved, blueprints_dir=bp)
    assert again.out_dir == old_folder
    assert len(again.page_state(0)["document"]["instances"]) == 1
    doc = label_store.create_document(bp, moved)
    assert doc.folder == old_folder
    assert len(list(label_store.iter_documents(bp))) == 1
    _mark_done(bp)
    assert label_store.labeled_pages(bp)["evaluation"]          # its role follows the PDF


def test_agent_batches_live_in_devices_and_find_evaluation_documents(blueprints):
    bp, train, exam = blueprints
    label_store.create_document(bp, train, targets={1: "t"})
    label_store.create_document(bp, exam, targets={1: "t"})
    agent_cli.cmd_batches(argparse.Namespace(store=str(bp), count=1, force=False))
    batch = bp / "devices" / "batches" / "batch_01.txt"
    assert batch.is_file()
    folders = label_store.batch_documents(batch)
    assert {f.parent for f in folders} == {bp / "devices", bp / "evaluation" / "devices"}
    assert all((f / label_store.DOCUMENT_FILE).is_file() for f in folders)


def test_flat_store_override_still_works(tmp_path, monkeypatch, blueprints):
    bp, train, _ = blueprints
    flat = tmp_path / "flat_store"
    monkeypatch.setenv("BPS_LABELS_DIR", str(flat))
    session = LabelingSession(pdf=train, blueprints_dir=bp)
    assert session.out_dir.parent == flat
    assert not any((bp / "devices").glob("*/document.json"))


def test_migration_copies_into_devices_and_never_touches_the_old_store(tmp_path, monkeypatch, blueprints):
    import scripts_migrate_label_store as migration

    bp, train, exam = blueprints
    old = tmp_path / "work" / "labels"
    monkeypatch.setenv("BPS_LABELS_DIR", str(old))
    for pdf in (train, exam):
        LabelingSession(pdf=pdf, blueprints_dir=bp).save(0, revision=None,
                                                         instances=[_verified()], rejected=[])
    (old / "batches").mkdir()
    (old / "batches" / "batch_01.txt").write_text("\n".join(p.name for p in old.iterdir()
                                                            if p.name != "batches") + "\n")
    _mark_done(old)                                  # flagged before the move: the flag moves too
    monkeypatch.delenv("BPS_LABELS_DIR")
    before = {p: p.read_bytes() for p in old.rglob("*") if p.is_file()}

    dry = migration.migrate(old, bp, apply=False)
    assert dry["documents"] == 2 and dry["files_to_copy"] > 0 and not dry["conflicts"]
    assert not any((bp / "devices").glob("*/document.json"))

    done = migration.migrate(old, bp, apply=True)
    assert done["documents_verified"] == 2 and not done["conflicts"]
    assert {p: p.read_bytes() for p in old.rglob("*") if p.is_file()} == before
    assert len(label_store.labeled_pages(bp)["train"]) == 1
    assert len(label_store.labeled_pages(bp)["evaluation"]) == 1
    batch = label_store.state_dir(bp) / "batches" / "batch_01.txt"
    assert len(label_store.batch_documents(batch)) == 2
    assert all(f.is_dir() for f in label_store.batch_documents(batch))

    again = migration.migrate(old, bp, apply=True)
    assert again.get("files_copied", 0) == 0 and not again["conflicts"]

    page = next((bp / "devices").glob("*/*_labels.json"))
    page.write_text(page.read_text().replace("duplex", "quad"))
    clash = migration.migrate(old, bp, apply=True)
    assert clash["conflicts"] == [str(page)]
    assert "quad" in page.read_text()                       # the newer copy is kept


def _sync_blueprint_filters() -> list[str]:
    text = (HERE / "remote" / "sync.sh").read_text()
    block = text[text.index("rsync -a --delete --info=stats1"):]
    block = block[:block.index('"$REPO/blueprints_and_libraries/1_blueprints/"')]
    return re.findall(r"--(?:include|exclude)='[^']+'", block)


@pytest.mark.skipif(shutil.which("rsync") is None, reason="rsync not installed")
def test_blueprint_mirror_never_sends_or_deletes_label_folders(tmp_path, blueprints):
    bp, train, exam = blueprints
    remote = tmp_path / "remote_1_blueprints"
    shutil.copytree(bp, remote)
    agent_doc = label_store.create_document(remote, remote / train.name)   # labeled on the remote
    (agent_doc.folder / "x_p0001.json").write_text("{}")
    (remote / "devices" / "batches").mkdir()
    (remote / "devices" / "batches" / "batch_01.txt").write_text(agent_doc.folder.name + "\n")
    local_doc = label_store.create_document(bp, exam)                       # labeled here
    (bp / "devices" / "stale_DEVICES.json").write_text("{}")

    filters = [f.replace("'", "") for f in _sync_blueprint_filters()]
    subprocess.run(["rsync", "-a", "--delete", *filters, f"{bp}/", f"{remote}/"], check=True)

    assert (agent_doc.folder / "x_p0001.json").is_file()                    # not deleted
    assert (remote / "devices" / "batches" / "batch_01.txt").is_file()
    assert not (remote / "evaluation" / "devices" / local_doc.folder.name).exists()   # not sent
    assert (remote / "devices" / "stale_DEVICES.json").is_file()            # DEVICES files still mirror
    assert (remote / "devices" / "train_set_DEVICES.json").is_file()


def _mark_done(store):
    """The labeler's "PDF done -> training" flag on every PDF of a store."""
    from annotator import document_labels, label_store as _ls
    for doc in _ls.iter_documents(store, skip_missing=True):
        if doc.kind == "pdf":
            document_labels.set_training_ready(doc.folder, doc.json_prefix, True)
