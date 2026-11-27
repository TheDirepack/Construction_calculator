"""Tests for annotator HTTP/browser image caching, fast overview/document lists, and Review button behavior."""
from __future__ import annotations

import http.client
import json
import os
import shutil
import threading
import time
from pathlib import Path
from http.server import ThreadingHTTPServer
from urllib.parse import quote

import pytest

fitz = pytest.importorskip("fitz")

from annotator import document_labels, web  # noqa: E402
from annotator.web import LabelingSession, make_handler  # noqa: E402


def _make_pdf(path: Path, pages: int = 3) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    doc = fitz.open()
    for i in range(pages):
        page = doc.new_page(width=240, height=180)
        page.insert_text((20, 40), f"SHEET E{i + 1}")
        page.draw_rect(fitz.Rect(50 + i * 5, 60, 70 + i * 5, 80), color=(0, 0, 0))
    doc.save(path)
    doc.close()
    return path.resolve()


def _pending(ident: str) -> dict:
    return {
        "instance_id": ident,
        "symbol_family": "receptacle",
        "symbol_variant": "duplex",
        "bbox_xyxy": [10.0, 10.0, 30.0, 30.0],
        "uncertain": True,
        "review_status": "unreviewed",
        "review_source": "human",
        "verified_ground_truth": False,
    }


def _verified(ident: str) -> dict:
    return {
        **_pending(ident),
        "uncertain": False,
        "review_status": "accepted",
        "verified_ground_truth": True,
    }


class _Served:
    def __init__(self, session: LabelingSession, *, overview_delay: float = 0.0) -> None:
        base = make_handler(session)
        self.image_requests: list[str] = []
        self.overview_requests: list[str] = []

        class TimedHandler(base):
            def do_GET(handler_self):
                if handler_self.path.startswith("/api/image"):
                    self.image_requests.append(handler_self.path)
                elif handler_self.path.startswith("/api/overview"):
                    self.overview_requests.append(handler_self.path)
                    if overview_delay > 0:
                        time.sleep(overview_delay)
                super().do_GET()

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), TimedHandler)
        self.url = f"http://127.0.0.1:{self.server.server_port}/"
        self.worker = threading.Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self):
        self.worker.start()
        return self

    def __exit__(self, *exc):
        self.server.shutdown()
        self.server.server_close()
        self.worker.join(timeout=10)

    def request(self, method: str, path: str, headers: dict[str, str] | None = None):
        conn = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=20)
        conn.request(method, path, headers=headers or {})
        resp = conn.getresponse()
        body = resp.read()
        resp_headers = {k: v for k, v in resp.getheaders()}
        conn.close()
        return resp.status, body, resp_headers


def test_http_image_caching_etag_304_and_cached_only_probe(tmp_path):
    pdf = _make_pdf(tmp_path / "blueprints" / "cache_test.pdf", pages=3)
    session = LabelingSession(pdf=pdf, out_dir=tmp_path / "labels", dpi=72, auto_proposals=False)

    with _Served(session) as srv:
        # Page 1 is unrendered at startup: cached=1 must return 202 without rendering.
        status, body, headers = srv.request("GET", "/api/image?page=1&cached=1")
        assert status == 202
        assert json.loads(body) == {"ready": False}
        assert headers.get("Cache-Control") == "no-store"

        # Page state exposes immutable cache-busted image URL (&v=<sha16>).
        status, state_body, state_headers = srv.request("GET", "/api/page?page=0")
        assert status == 200
        assert state_headers.get("Cache-Control") == "no-store"
        page0 = json.loads(state_body)
        img_url = page0["image_url"]
        sha = page0["document"]["image_sha256"]
        assert f"&v={sha[:16]}" in img_url

        # Full image GET with matching v returns immutable private cache headers + ETag.
        status, img_bytes, img_headers = srv.request("GET", img_url)
        assert status == 200
        assert img_bytes.startswith(b"\x89PNG\r\n\x1a\n")
        assert img_headers.get("X-Image-Sha256") == sha
        assert img_headers.get("ETag") == f'"{sha}"'
        assert img_headers.get("Cache-Control") == "private, max-age=3600, immutable"

        # Conditional GET with If-None-Match returns 304 and empty body.
        status, empty_body, not_mod_headers = srv.request(
            "GET", img_url, headers={"If-None-Match": img_headers["ETag"]}
        )
        assert status == 304
        assert empty_body == b""
        assert not_mod_headers.get("ETag") == f'"{sha}"'
        assert not_mod_headers.get("X-Image-Sha256") == sha

        # GET without v uses no-cache + ETag instead of max-age immutable.
        status, _, no_v_headers = srv.request("GET", "/api/image?page=0")
        assert status == 200
        assert no_v_headers.get("Cache-Control") == "private, no-cache"

        # Once page 1 is rendered, cached=1 serves 200 with PNG + X-Image-Sha256.
        session.page_state(1)
        status, p1_bytes, p1_headers = srv.request("GET", "/api/image?page=1&cached=1")
        assert status == 200
        assert p1_bytes.startswith(b"\x89PNG\r\n\x1a\n")
        assert len(p1_headers.get("X-Image-Sha256", "")) == 64

        # /api/page must continue rejecting unknown query keys like ?v=.
        status, _, _ = srv.request("GET", "/api/page?page=0&v=123")
        assert status == 409

        # /api/agent/view must preserve Cache-Control: no-store.
        status, _, view_headers = srv.request("GET", "/api/agent/view?page=0&max_side=160&grid=0")
        assert status == 200
        assert view_headers.get("Cache-Control") == "no-store"


def test_page_overview_cache_and_invalidation(tmp_path, monkeypatch):
    bp = tmp_path / "1_blueprints"
    pdf = _make_pdf(bp / "panel_set.pdf", pages=5)
    prop_file = bp / "panel_set_7c25e88e_CLEANED.json"
    work_dir = tmp_path / "work"
    cleaned_dir = work_dir / "sam3_cleaned"
    cleaned_dir.mkdir(parents=True)
    cleaned_path = cleaned_dir / prop_file.name
    cleaned_path.write_text(
        json.dumps({
            "source_file": pdf.name,
            "render_dpi": 72,
            "devices": [
                {"pdf_page": 3, "device_type": "duplex_receptacle", "box_px_tl": [10, 10, 30, 30]},
                {"pdf_page": 4, "device_type": "duplex_receptacle", "box_px_tl": [20, 20, 40, 40]},
            ],
        }),
        encoding="utf-8",
    )
    monkeypatch.setattr(web, "DEFAULT_WORK_DIR", work_dir)

    session = LabelingSession(pdf=pdf, blueprints_dir=bp, out_dir=tmp_path / "labels", dpi=72)
    assert session.proposals_sha is not None
    session.save(0, revision=None, instances=[_pending("p0")], rejected=[])

    # First overview populates caches.
    first = session.page_overview()
    assert {r["page"]: r["pending"] for r in first["pages"]} == {0: 1, 2: 1, 3: 1}

    # Subsequent overview call reuses cached proposed counts without calling proposal_device again.
    monkeypatch.setattr(
        web,
        "proposal_device",
        lambda *a, **k: (_ for _ in ()).throw(AssertionError("proposal_device should be cached")),
    )
    second = session.page_overview()
    assert second == first

    # Saving invalidates saved overview cache immediately while still reusing proposed counts cache.
    rev0 = session.page_state(0)["revision"]
    session.save(0, revision=rev0, instances=[_verified("p0")], rejected=[])
    third = session.page_overview()
    assert {r["page"]: r["pending"] for r in third["pages"]} == {0: 0, 2: 1, 3: 1}

    # Touching labels file externally invalidates too.
    labels_file = session._json_path(0)
    raw = json.loads(labels_file.read_text(encoding="utf-8"))
    raw["pages"]["2"] = dict(raw["pages"]["1"], page_index=1)
    raw["pages"]["2"]["instances"] = [_pending("ext")]
    labels_file.write_text(json.dumps(raw), encoding="utf-8")
    os.utime(labels_file, ns=(time.time_ns() + 1_000_000, time.time_ns() + 1_000_000))
    fourth = session.page_overview()
    assert {r["page"]: r["pending"] for r in fourth["pages"]}[1] == 1


def test_document_labels_shallow_write_preserves_other_pages_and_converts_legacy(tmp_path):
    bp = tmp_path / "1_blueprints"
    pdf = _make_pdf(bp / "shallow.pdf", pages=3)
    session = LabelingSession(pdf=pdf, blueprints_dir=bp, auto_proposals=False, dpi=72)
    folder, prefix = session.out_dir, session.json_prefix

    session.save(0, revision=None, instances=[_verified("v1")], rejected=[])
    before_read = document_labels.read(folder, prefix)
    page1_snapshot = json.loads(json.dumps(before_read["pages"]["1"]))

    # Writing page 2 must not mutate the previously cached read() dict or page 1.
    session.save(1, revision=None, instances=[_pending("p2")], rejected=[])
    assert before_read["pages"]["1"] == page1_snapshot
    assert "2" not in before_read["pages"]

    # Dropping a legacy page file afterwards must still be discovered and converted.
    document_labels.convert(folder, prefix)
    legacy = dict(page1_snapshot, page_index=2, image_sha256=None, instances=[_pending("leg3")])
    (folder / f"{prefix}_p0003.json").write_text(json.dumps(legacy), encoding="utf-8")
    os.utime(folder, ns=(time.time_ns() + 2_000_000, time.time_ns() + 2_000_000))
    assert document_labels.convert(folder, prefix) == 1
    assert [i["instance_id"] for i in document_labels.pages(folder, prefix)[3]["instances"]] == ["leg3"]


def test_list_documents_batch_proposal_lookup_matches_find_proposals_for_pdf(tmp_path, monkeypatch):
    bp = tmp_path / "1_blueprints"
    eval_dir = bp / "evaluation"
    work = tmp_path / "work"
    (bp / "devices").mkdir(parents=True)
    (eval_dir / "devices").mkdir(parents=True)
    for sub in ("circuitnet_refined_devices", "sam3_cleaned", "circuitnet_proposals", "sam3_proposals"):
        (work / sub).mkdir(parents=True)

    p_dev = _make_pdf(bp / "alpha_plan.pdf", 1)
    p_local = _make_pdf(bp / "bravo_plan.pdf", 1)
    p_ref = _make_pdf(bp / "charlie_plan.pdf", 1)
    p_cln = _make_pdf(bp / "delta_plan.pdf", 1)
    p_cn = _make_pdf(bp / "echo_plan.pdf", 1)
    p_sam = _make_pdf(bp / "foxtrot_plan.pdf", 1)
    p_eval = _make_pdf(eval_dir / "golf_eval.pdf", 1)
    p_none = _make_pdf(bp / "hotel_empty.pdf", 1)

    (bp / "devices" / "alpha_DEVICES.json").write_text(
        json.dumps({"source_file": p_dev.name, "devices": [{"pdf_page": 1}]}),
        encoding="utf-8",
    )
    (bp / f"{p_local.stem}_devices.json").write_text(json.dumps({"devices": []}), encoding="utf-8")
    (work / "circuitnet_refined_devices" / f"{p_ref.stem}_r1_REFINED_DEVICES.json").write_text("{}", encoding="utf-8")
    (work / "sam3_cleaned" / f"{p_cln.stem}_run1_CLEANED.json").write_text("{}", encoding="utf-8")
    (work / "sam3_proposals" / f"{p_cln.stem}_run1_raw.json").write_text("{}", encoding="utf-8")
    (work / "circuitnet_proposals" / f"{p_cn.stem}_cn.json").write_text("{}", encoding="utf-8")
    (work / "sam3_proposals" / f"{p_sam.stem}_run2_raw.json").write_text("{}", encoding="utf-8")
    (eval_dir / "devices" / "golf_DEVICES.json").write_text(
        json.dumps({"source_file": p_eval.name, "devices": [{"pdf_page": 1}]}),
        encoding="utf-8",
    )

    monkeypatch.setattr(web, "DEFAULT_WORK_DIR", work)
    session = LabelingSession(pdf=p_dev, blueprints_dir=bp, out_dir=tmp_path / "labels", dpi=72)
    docs = session.list_documents()
    by_path = {Path(d["path"]).resolve(): d for d in docs}

    for pdf in (p_dev, p_local, p_ref, p_cln, p_cn, p_sam, p_eval, p_none):
        expected = web._find_proposals_for_pdf(pdf, work)
        row = by_path[pdf.resolve()]
        assert row["has_proposals"] is (expected is not None), pdf.name
        assert row["proposal_file"] == (expected.name if expected else None), pdf.name


def test_document_labels_summary_large_file_without_growing_parsed_cache(tmp_path):
    bp = tmp_path / "1_blueprints"
    pdf = _make_pdf(bp / "large_summary.pdf", 2)
    session = LabelingSession(pdf=pdf, blueprints_dir=bp, auto_proposals=False, dpi=72)
    folder, prefix = session.out_dir, session.json_prefix
    many = [_verified(f"b_{i}") for i in range(260)]
    session.save(0, revision=None, instances=many, rejected=[])
    labels_file = document_labels.labels_path(folder, prefix)
    assert labels_file.stat().st_size >= 64 * 1024

    document_labels._cache.clear()
    document_labels._meta_cache.clear()
    assert document_labels.has_labels(folder) is True
    assert document_labels.training_ready(folder, prefix) is False
    assert str(labels_file) not in document_labels._cache

    # External write sets training_ready=True and advances mtime: summary picks it up without caching parsed dict.
    data = json.loads(labels_file.read_text(encoding="utf-8"))
    data["training_ready"] = {"ready": True, "set_at": "2026-11-27T00:00:00Z", "set_by": "test"}
    labels_file.write_text(json.dumps(data, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    os.utime(labels_file, ns=(time.time_ns() + 2_000_000, time.time_ns() + 2_000_000))
    assert document_labels.training_ready(folder, prefix) is True
    assert document_labels.has_labels(folder) is True
    assert str(labels_file) not in document_labels._cache


playwright = pytest.importorskip("playwright.sync_api")


def _launch_browser(playwright_instance):
    executable = (
        shutil.which("google-chrome")
        or shutil.which("google-chrome-stable")
        or shutil.which("chromium")
        or shutil.which("chromium-browser")
    )
    options = {"executable_path": executable} if executable else {}
    return playwright_instance.chromium.launch(headless=True, args=["--no-sandbox"], **options)


def _wait_page(page, number: int, total: int):
    page.wait_for_function(
        f"() => document.querySelector('#pageIndicator').textContent === 'Page {number} / {total}'"
        " && !window.__labelerState.busy && window.__labelerState.pendingPage === null"
    )


def test_browser_image_cache_and_double_click_review(tmp_path):
    pdf = _make_pdf(tmp_path / "blueprints" / "browser_cache.pdf", pages=4)
    session = LabelingSession(pdf=pdf, out_dir=tmp_path / "labels", dpi=72, auto_proposals=False)
    session.save(2, revision=None, instances=[_pending("rev_p3")], rejected=[])

    with _Served(session, overview_delay=0.25) as srv:
        with playwright.sync_playwright() as pw:
            browser = _launch_browser(pw)
            try:
                page = browser.new_page(viewport={"width": 1280, "height": 840})
                page.goto(srv.url)
                _wait_page(page, 1, 4)

                # Step to page 2 and immediately back to page 1: page 1 image must come from imageCache.
                page.locator("#nextPage").click()
                _wait_page(page, 2, 4)
                page.locator("#prevPage").click()
                _wait_page(page, 1, 4)

                p0_fetches = [u for u in srv.image_requests if "page=0" in u]
                assert len(p0_fetches) == 1, f"expected 1 fetch for page 0, got {p0_fetches}"

                # Trigger two rapid Review actions while /api/overview is delayed: only 1 overview call runs
                # and lands cleanly on page 3 (the pending page).
                srv.overview_requests.clear()
                page.keyboard.press("Shift+N")
                page.keyboard.press("Shift+N")
                _wait_page(page, 3, 4)
                assert len(srv.overview_requests) == 1, srv.overview_requests
                assert "1 pending" in page.locator("#count").inner_text()
                page.close()
            finally:
                browser.close()
