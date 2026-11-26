"""Per-request documents, folder limits, Host checks and import validation."""
from __future__ import annotations

import http.client
import json
import threading
from http.server import ThreadingHTTPServer
from pathlib import Path
from urllib.parse import quote

import pytest

fitz = pytest.importorskip("fitz")

from annotator.web import (  # noqa: E402
    LabelingError, LabelingSession, RevisionConflict, _is_lan_host, _is_private_ip,
    make_handler, openable_pdfs,
)


def _pdf(path: Path, *, pages: int = 2) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    document = fitz.open()
    for _ in range(pages):
        page = document.new_page(width=400, height=300)
        page.draw_rect(fitz.Rect(50, 50, 65, 65), color=(0, 0, 0))
    document.save(path)
    document.close()
    return path


def _pending(ident: str) -> dict:
    return {
        "instance_id": ident, "symbol_family": "receptacle", "symbol_variant": "duplex",
        "bbox_xyxy": [70.0, 120.0, 95.0, 145.0], "uncertain": True,
        "review_status": "unreviewed", "review_source": "human", "verified_ground_truth": False,
    }


class _Server:
    def __init__(self, session):
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(session))
        self.port = self.server.server_port
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *exc):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=10)

    def call(self, method, path, payload=None, headers=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=20)
        try:
            body = json.dumps(payload) if payload is not None else None
            conn.request(method, path, body=body, headers=headers or {})
            resp = conn.getresponse()
            raw = resp.read()
            data = json.loads(raw) if resp.getheader("Content-Type", "").startswith("application/json") else raw
            return resp.status, data, resp
        finally:
            conn.close()


def _two_pdfs(tmp_path):
    bp = tmp_path / "blueprints"
    a = _pdf(bp / "plan_a.pdf")
    b = _pdf(bp / "plan_b.pdf", pages=1)
    session = LabelingSession(pdf=a, blueprints_dir=bp, out_dir=tmp_path / "labels")
    return bp, a, b, session


def test_save_names_its_pdf_so_another_tab_switching_documents_cannot_redirect_it(tmp_path):
    _, a, b, session = _two_pdfs(tmp_path)
    headers = {"X-Labeler-Token": session.token, "Content-Type": "application/json"}
    doc_a = "&doc=" + quote(str(a), safe="")
    with _Server(session) as srv:
        code, page, _ = srv.call("GET", "/api/page?page=0" + doc_a)
        assert code == 200 and page["current_doc_name"] == "plan_a.pdf"
        assert "doc=" in page["image_url"]
        # Another tab (or an agent) moves the server's default document to plan_b.
        code, _, _ = srv.call("POST", "/api/document", {"path": str(b)}, headers)
        assert code == 200
        # Tab A's first save of a new page (revision null) still lands in plan_a.
        code, saved, _ = srv.call("POST", "/api/save?page=0" + doc_a, {
            "revision": None, "instances": [_pending("manual_p1_1")], "rejected_proposals": [],
        }, headers)
        assert code == 200, saved
        assert saved["document"]["source_id"] == "plan_a"
        code, b_page, _ = srv.call("GET", "/api/page?page=0")
        assert b_page["current_doc_name"] == "plan_b.pdf"
        assert b_page["document"]["instances"] == []
        code, image, resp = srv.call("GET", page["image_url"])
        assert code == 200 and resp.getheader("Content-Type").startswith("image/")


def test_documents_outside_the_blueprints_folder_are_refused(tmp_path):
    _, _, _, session = _two_pdfs(tmp_path)
    outside = _pdf(tmp_path / "elsewhere" / "secret.pdf")
    with pytest.raises(LabelingError):
        session.for_document(str(outside))
    with pytest.raises(LabelingError):
        session.switch_document(str(outside))
    with pytest.raises(LabelingError):
        session.for_document("../elsewhere/secret.pdf")
    assert session.for_document("plan_b.pdf").pdf.name == "plan_b.pdf"


def test_only_training_and_evaluation_drawings_are_listed_and_opened(tmp_path):
    bp, a, b, session = _two_pdfs(tmp_path)
    exam = _pdf(bp / "evaluation" / "exam.pdf", pages=1)
    hidden = _pdf(bp / "new_downloads" / "unsorted.pdf", pages=1)
    deep = _pdf(bp / "evaluation" / "old" / "nested.pdf", pages=1)
    assert openable_pdfs(bp) == [a.resolve(), b.resolve(), exam.resolve()]
    assert {d["name"] for d in session.list_documents()} == {"plan_a.pdf", "plan_b.pdf", "exam.pdf"}
    assert session.for_document("exam.pdf").pdf == exam.resolve()
    assert session.for_document(str(exam)).pdf == exam.resolve()
    for refused in (hidden, deep):
        with pytest.raises(LabelingError):
            session.for_document(str(refused))
        with pytest.raises(LabelingError):
            session.for_document(refused.name)
        with pytest.raises(LabelingError):
            session.switch_document(str(refused))
        with pytest.raises(LabelingError):
            session.prefetch(str(refused), [0])


def test_lan_machine_names_are_accepted_but_public_names_are_not():
    for host in ("localhost", "mypc", "mypc.local", "nas.lan", "box.home.arpa", "192.168.1.9", "::1"):
        assert _is_lan_host(host), host
    for host in ("attacker.example", "localtest.me", "evil.com", "8.8.8.8", "a b", ""):
        assert not _is_lan_host(host), host


def test_host_header_names_are_never_resolved():
    assert _is_private_ip("192.168.1.20")
    assert _is_private_ip("127.0.0.1")
    assert _is_private_ip("::1")
    assert not _is_private_ip("8.8.8.8")
    # A public name that resolves to a LAN address (DNS rebinding) is not trusted.
    assert not _is_private_ip("localtest.me")
    assert not _is_private_ip("attacker.example")


def test_host_header_from_a_foreign_name_is_rejected(tmp_path):
    _, _, _, session = _two_pdfs(tmp_path)
    with _Server(session) as srv:
        code, _, _ = srv.call("GET", "/api/session", headers={"Host": f"rebind.example:{srv.port}"})
        assert code == 403


def test_import_from_another_pdf_is_pending_and_checked_like_a_save(tmp_path):
    _, a, b, session = _two_pdfs(tmp_path)
    session.save(0, revision=None, instances=[{
        **_pending("manual_p1_1"), "uncertain": False, "review_status": "accepted",
        "verified_ground_truth": True,
    }], rejected=[])
    export = session.export_server()
    session.switch_document("plan_b.pdf")
    result = session.import_server(0, export["path"])
    assert result["imported_count"] == 1
    imported = session.page_state(0)["document"]["instances"]
    assert len(imported) == 1
    # Another PDF's review decision is not trusted here.
    assert imported[0]["verified_ground_truth"] is False
    assert imported[0]["review_status"] == "unreviewed"

    # A second import gets fresh IDs instead of colliding.
    session.import_server(0, export["path"])
    ids = [x["instance_id"] for x in session.page_state(0)["document"]["instances"]]
    assert len(ids) == len(set(ids)) == 2


def test_import_into_a_fully_labeled_page_clears_the_complete_mark(tmp_path):
    _, a, _, session = _two_pdfs(tmp_path)
    export = session.export_server()                 # an empty export of plan_a
    payload = json.loads(Path(export["path"]).read_text())
    payload["source_pdf_sha256"] = "0" * 64           # from somewhere else
    payload["pages"][0]["instances"] = [_pending("x1")]
    Path(export["path"]).write_text(json.dumps(payload))
    session.save(0, revision=None, instances=[], rejected=[],
                 metadata={"page_complete": True})
    session.import_server(0, export["path"])
    doc = session.page_state(0)["document"]
    assert doc["page_complete"] is False
    assert doc["annotation_completeness"] == "partial"


def test_import_only_reads_listed_sources(tmp_path):
    _, _, _, session = _two_pdfs(tmp_path)
    stray = tmp_path / "stray.json"
    stray.write_text(json.dumps({"instances": [_pending("x")]}))
    with pytest.raises(LabelingError):
        session.import_server(0, str(stray))


def test_export_stays_in_the_exports_folder(tmp_path):
    _, _, _, session = _two_pdfs(tmp_path)
    with pytest.raises(LabelingError):
        session.export_server(str(tmp_path / "anywhere.json"))
    with pytest.raises(LabelingError):
        session.export_server("../../escape.json")
    named = session.export_server("mine.json")
    assert Path(named["path"]).parent == (session.out_dir / "exports").resolve()


def test_stale_revision_is_still_refused_per_document(tmp_path):
    _, a, b, session = _two_pdfs(tmp_path)
    b_session = session.for_document(str(b))
    b_session.save(0, revision=None, instances=[_pending("m1")], rejected=[])
    with pytest.raises(RevisionConflict):
        b_session.save(0, revision=None, instances=[], rejected=[])
    assert session.for_document(str(b)) is b_session
