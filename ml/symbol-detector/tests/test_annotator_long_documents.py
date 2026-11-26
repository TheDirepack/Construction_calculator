"""Page navigation on long documents: server limits, prefetch, and the browser.

Browser tests need Playwright and Chromium (skipped otherwise).
"""
from __future__ import annotations

import concurrent.futures
import http.client
import json
import os
import threading
import time
from http.server import ThreadingHTTPServer
from pathlib import Path
from urllib.parse import quote

import pytest

fitz = pytest.importorskip("fitz")

from annotator import document_labels  # noqa: E402
from annotator import label_store, web  # noqa: E402
from annotator.web import LabelingError, LabelingSession, make_handler  # noqa: E402


def _long_pdf(path: Path, pages: int) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    document = fitz.open()
    for number in range(pages):
        page = document.new_page(width=200, height=150)
        page.insert_text((20, 40), f"SHEET {number + 1}")
        page.draw_rect(fitz.Rect(50, 60, 65, 75), color=(0, 0, 0))
    document.save(path)
    document.close()
    return path


@pytest.fixture(scope="module")
def long_pdf(tmp_path_factory):
    return _long_pdf(tmp_path_factory.mktemp("long") / "blueprints" / "long_set.pdf", 400)


@pytest.fixture(scope="module")
def huge_pdf(tmp_path_factory):
    """More than 9,999 pages: page files get 5-digit numbers. Built by doubling
    one sheet (much faster than drawing 10,002 pages one by one)."""
    path = tmp_path_factory.mktemp("huge") / "blueprints" / "huge_set.pdf"
    path.parent.mkdir(parents=True)
    document = fitz.open()
    sheet = document.new_page(width=200, height=150)
    sheet.insert_text((20, 40), "SHEET")
    while len(document) < 10_002:
        copy = fitz.open()
        copy.insert_pdf(document)
        document.insert_pdf(copy, to_page=min(len(copy), 10_002 - len(document)) - 1)
    document.save(path)
    document.close()
    return path


def _pending(ident):
    return {"instance_id": ident, "symbol_family": "receptacle", "symbol_variant": "duplex",
            "bbox_xyxy": [10.0, 10.0, 20.0, 20.0], "uncertain": True,
            "review_status": "unreviewed", "review_source": "human", "verified_ground_truth": False}


# ------------------------------------------------------------------ server

def test_any_page_of_a_long_document_opens_and_saves(long_pdf, tmp_path):
    session = LabelingSession(pdf=long_pdf, out_dir=tmp_path / "labels", dpi=30)
    assert session.page_count == 400
    for page in (0, 1, 199, 398, 399):
        state = session.page_state(page)
        assert state["page_index"] == page and state["page_count"] == 400
        assert state["document"]["page_index"] == page
        assert f"page={page}" in state["image_url"]
    revision = session.save(399, revision=None, instances=[_pending("last_1")], rejected=[])
    assert session.page_state(399)["revision"] == revision
    assert session.page_address(399).name.endswith("_p0400.json")
    assert list(document_labels.pages(tmp_path / "labels", session.json_prefix)) == [400]
    for bad in (-1, 400, 10_000):
        with pytest.raises(LabelingError):
            session.page_state(bad)


def test_page_requests_out_of_range_are_refused_over_http(long_pdf, tmp_path):
    session = LabelingSession(pdf=long_pdf, out_dir=tmp_path / "labels", dpi=30)
    server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(session))
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        def get(query):
            conn = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=20)
            conn.request("GET", "/api/page?" + query)
            resp = conn.getresponse()
            body = resp.read()
            conn.close()
            return resp.status, json.loads(body)

        assert get("page=399")[0] == 200
        for query in ("page=400", "page=-1", "page=abc", "page=1&page=2", "page=1&extra=1"):
            status, body = get(query)
            assert status == 409 and "error" in body, query
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=10)


def test_documents_over_ten_thousand_pages_use_five_digit_page_numbers(huge_pdf, tmp_path):
    store = tmp_path / "labels"
    session = LabelingSession(pdf=huge_pdf, out_dir=store, dpi=30)
    assert session.page_count == 10_002
    session.save(10_001, revision=None, instances=[_pending("far_1")], rejected=[])
    path = session.page_address(10_001)
    assert path.name.endswith("_p10002.json")
    assert label_store.page_number(path) == 10_002
    assert list(document_labels.pages(store, session.json_prefix)) == [10_002]
    assert session.page_state(10_001)["document"]["instances"][0]["instance_id"] == "far_1"
    # The document list sees a document whose only labels are on 5-digit pages.
    assert "huge_set" in session._labeled_stems()


def test_prefetch_accepts_late_pages_and_drops_pages_past_the_end(huge_pdf, tmp_path, monkeypatch):
    calls = []
    monkeypatch.setattr(web.PREFETCHER, "submit", lambda *a, **k: calls.append((a, k)) or True)
    session = LabelingSession(pdf=huge_pdf, out_dir=tmp_path / "labels", dpi=30)
    assert session.prefetch(None, [10_000, 10_001])["queued"] is True
    (pdf, pages, dpi, out_dir, sha), _ = calls[-1]
    assert pages == [10_000, 10_001]
    assert sha == session.source_hash               # the worker never re-hashes the PDF
    assert session.prefetch(None, [10_001, 10_002, 20_000])["queued"] is True
    assert calls[-1][0][1] == [10_001]
    assert session.prefetch(None, [10_002])["queued"] is False
    for bad in ([], [-1], ["1"], [1_000_000], "3"):
        with pytest.raises(LabelingError):
            session.prefetch(None, bad)


def test_prefetch_job_uses_the_given_hash_and_hashes_each_pdf_once(long_pdf, tmp_path, monkeypatch):
    sha = web.cached_sha256(long_pdf)
    web._SHA_CACHE.clear()
    count = {"n": 0}
    real = web.sha256_file

    def counting(path):
        if Path(path).suffix == ".pdf":
            count["n"] += 1
        return real(path)

    monkeypatch.setattr(web, "sha256_file", counting)
    out = tmp_path / "out"
    web._prefetch_job(str(long_pdf), [0], 30, str(out), sha)
    assert count["n"] == 0
    web._prefetch_job(str(long_pdf), [1], 30, str(out))
    web._prefetch_job(str(long_pdf), [2], 30, str(out))
    assert count["n"] == 1
    assert len(list((out / "images").glob("page_*.png"))) == 3


class _ManualPool:
    """Executor whose jobs only run when the test says so."""

    def __init__(self):
        self.jobs = []

    def submit(self, fn, *args):
        future = concurrent.futures.Future()
        self.jobs.append((future, fn, args))
        return future

    def run(self, index):
        future, fn, args = self.jobs[index]
        if future.set_running_or_notify_cancel():
            future.set_result((args[0], "0" * 64))


def test_prefetcher_cancels_pages_the_user_already_paged_past(tmp_path):
    prefetcher = web._Prefetcher()
    pool = prefetcher._pool = _ManualPool()
    pdf = tmp_path / "a.pdf"
    other = tmp_path / "b.pdf"
    for path in (pdf, other):
        path.write_bytes(b"%PDF")
    for page in (1, 2, 3):
        assert prefetcher.submit(pdf, [page], 30, None, "x" * 64)
    assert prefetcher.submit(other, [0], 30, None, "y" * 64)
    assert [f.cancelled() for f, _, _ in pool.jobs] == [True, True, False, False]
    # A cancelled page can be asked for again later.
    assert prefetcher.submit(pdf, [1], 30, None, "x" * 64)
    assert pool.jobs[2][0].cancelled()
    pool.run(4)
    assert not prefetcher.submit(pdf, [1], 30, None, "x" * 64)      # done: not repeated
    assert prefetcher._queued[(str(pdf), 30, "None")] == []


# ----------------------------------------------------------------- browser

playwright = pytest.importorskip("playwright.sync_api")
from test_annotator_browser import _launch_browser  # noqa: E402


class _Served:
    """The real handler, with page loads slowed so clicks land mid-load."""

    def __init__(self, session, delay=0.0):
        base = make_handler(session)
        log = self.page_requests = []

        class Slow(base):
            def do_GET(self):
                if self.path.startswith("/api/page"):
                    log.append(self.path)
                    time.sleep(delay)
                super().do_GET()

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Slow)
        self.url = f"http://127.0.0.1:{self.server.server_port}/"
        self.worker = threading.Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self):
        self.worker.start()
        return self

    def __exit__(self, *exc):
        self.server.shutdown()
        self.server.server_close()
        self.worker.join(timeout=10)


@pytest.fixture(scope="module")
def chrome():
    with playwright.sync_playwright() as pw:
        instance = _launch_browser(pw)
        try:
            yield instance
        finally:
            instance.close()


def _open(chrome, url, pages):
    page = chrome.new_page(viewport={"width": 1420, "height": 900})
    page.goto(url)
    _wait_page(page, 1, pages)
    return page


def _wait_page(page, number, total):
    page.wait_for_function(
        f"() => document.querySelector('#pageIndicator').textContent === 'Page {number} / {total}'"
        " && !window.__labelerState.busy && window.__labelerState.pendingPage === null")


def _state(page, expr):
    return page.evaluate(f"() => {{ const s = window.__labelerState; return {expr}; }}")


def test_browser_jumps_to_any_page_and_home_end_and_bounds(chrome, long_pdf, tmp_path):
    session = LabelingSession(pdf=long_pdf, out_dir=tmp_path / "labels", dpi=30)
    with _Served(session) as served:
        page = _open(chrome, served.url, 400)
        assert page.locator("#pageJump").get_attribute("placeholder") == "Go to (1-400)"
        page.locator("#pageJump").fill("250")
        page.locator("#pageJump").press("Enter")
        _wait_page(page, 250, 400)
        assert "SHEET" not in page.locator("#message").inner_text()      # sanity: footer message

        page.locator("#canvas").focus()
        page.keyboard.press("End")
        _wait_page(page, 400, 400)
        assert page.locator("#nextPage").is_disabled()
        page.keyboard.press("]")
        page.wait_for_function(
            "() => document.querySelector('#message').textContent.includes('Already on the last page')")
        _wait_page(page, 400, 400)

        page.keyboard.press("Home")
        _wait_page(page, 1, 400)
        assert page.locator("#prevPage").is_disabled()

        for typed in ("0", "401", "-3"):
            page.locator("#pageJump").fill(typed)
            page.locator("#pageJump").press("Enter")
            page.wait_for_function("() => document.querySelector('#message').textContent.includes('Already on the')")
            _wait_page(page, 1, 400)
        page.close()


def test_browser_clicks_during_a_slow_load_are_queued_not_lost(chrome, long_pdf, tmp_path):
    session = LabelingSession(pdf=long_pdf, out_dir=tmp_path / "labels", dpi=30)
    with _Served(session, delay=0.6) as served:
        page = _open(chrome, served.url, 400)
        served.page_requests.clear()
        for _ in range(5):
            page.locator("#nextPage").click()
        _wait_page(page, 6, 400)
        # Page 2 was already loading; pages 3-5 were skipped, not loaded one by one.
        assert len(served.page_requests) == 2, served.page_requests
        assert "page=1" in served.page_requests[0] and "page=5" in served.page_requests[1]

        served.page_requests.clear()
        page.locator("#canvas").focus()
        page.keyboard.press("]")
        page.keyboard.press("]")
        page.keyboard.press("[")
        _wait_page(page, 7, 400)                   # +1 +1 -1 from page 6
        page.close()


def test_browser_unsaved_pages_survive_a_long_walk_and_are_all_saved(chrome, long_pdf, tmp_path):
    session = LabelingSession(pdf=long_pdf, out_dir=tmp_path / "labels", dpi=30)
    with _Served(session) as served:
        page = _open(chrome, served.url, 400)
        edited = [1, 2, 3]
        for number in edited:
            if number != 1:
                page.locator("#pageJump").fill(str(number))
                page.locator("#pageJump").press("Enter")
                _wait_page(page, number, 400)
            page.locator("#drawTool").click()
            box = page.locator("#canvas").bounding_box()
            sx, sy = box["x"] + box["width"] / 2 - 20, box["y"] + box["height"] / 2 - 20
            page.mouse.move(sx, sy)
            page.mouse.down()
            page.mouse.move(sx + 40, sy + 30, steps=4)
            page.mouse.up()
            page.wait_for_function("() => document.querySelector('#count').textContent.includes('1 pending')")

        for number in range(10, 41):               # 31 more pages: far past the cache limit
            page.locator("#pageJump").fill(str(number))
            page.locator("#pageJump").press("Enter")
            _wait_page(page, number, 400)
        cached = _state(page, "Object.keys(s.pageCache).map(Number)")
        dirty = _state(page, "Object.entries(s.pageCache).filter(([p, c]) => c.dirty).map(([p]) => Number(p))")
        assert sorted(dirty) == [0, 1, 2]
        assert len(cached) <= 3 + 12
        assert 2 not in document_labels.pages(tmp_path / "labels", "long_set")

        page.locator("#pageJump").fill("2")
        page.locator("#pageJump").press("Enter")
        _wait_page(page, 2, 400)
        assert "1 pending" in page.locator("#count").inner_text()      # kept in memory, unsaved

        page.keyboard.press("Control+s")
        page.wait_for_function("() => document.querySelector('#message').textContent.startsWith('Saved')")
        for number in edited:
            saved = document_labels.pages(tmp_path / "labels", "long_set")[number]
            assert len(saved["instances"]) == 1, number
        assert len(document_labels.pages(tmp_path / "labels", "long_set")) == 3   # visited pages are not written
        page.close()


def test_browser_prefetches_the_next_two_pages(chrome, long_pdf, tmp_path, monkeypatch):
    requested = []
    monkeypatch.setattr(web.PREFETCHER, "submit", lambda pdf, pages, *a, **k: requested.append(list(pages)) or True)
    session = LabelingSession(pdf=long_pdf, blueprints_dir=long_pdf.parent,
                              out_dir=tmp_path / "labels", dpi=30)
    with _Served(session) as served:
        page = _open(chrome, served.url, 400)
        page.wait_for_function("() => true")
        deadline = time.time() + 10
        while [1, 2] not in requested and time.time() < deadline:
            time.sleep(0.05)
        assert [1, 2] in requested
        page.locator("#pageJump").fill("399")
        page.locator("#pageJump").press("Enter")
        _wait_page(page, 399, 400)
        deadline = time.time() + 10
        while [399] not in requested and time.time() < deadline:
            time.sleep(0.05)
        assert [399] in requested                  # only the last page is left ahead
        page.close()


def test_browser_switching_document_mid_load_drops_the_queued_page(chrome, tmp_path):
    bp = tmp_path / "blueprints"
    first = _long_pdf(bp / "a_set.pdf", 30)
    _long_pdf(bp / "b_set.pdf", 5)
    session = LabelingSession(pdf=first, blueprints_dir=bp, out_dir=tmp_path / "labels", dpi=30)
    with _Served(session, delay=0.4) as served:
        page = _open(chrome, served.url, 30)
        page.locator("#pageJump").fill("20")
        page.locator("#pageJump").press("Enter")
        page.locator("#nextPage").click()           # queued behind page 20
        _wait_page(page, 21, 30)
        page.locator("#nextPdfBtn").click()
        page.wait_for_function(
            "() => document.querySelector('#currentDocBadge').textContent.includes('b_set.pdf')")
        _wait_page(page, 1, 5)
        assert _state(page, "s.pendingPage") is None
        assert page.locator("#pageJump").get_attribute("placeholder") == "Go to (1-5)"
        page.close()


# ---------------------------------------------------------- review overview

def _verified(ident):
    return {**_pending(ident), "uncertain": False, "review_status": "accepted",
            "verified_ground_truth": True}


def _proposals(pdf, pages):
    return {"source_file": pdf.name, "render_dpi": 30, "devices": [
        {"pdf_page": number, "device_type": "duplex_receptacle", "box_px_tl": [10, 10, 30, 30]}
        for number in pages]}


def test_overview_counts_saved_pages_and_unopened_proposal_pages(long_pdf, tmp_path, monkeypatch):
    session = LabelingSession(pdf=long_pdf, out_dir=tmp_path / "labels", dpi=30)
    session.proposals = _proposals(long_pdf, [50, 50, 300, 999])     # 999: past the end, ignored
    # Mechanical equipment is not an electrical label: never a pending box.
    session.proposals["devices"].append({"pdf_page": 60, "device_type": "exhaust_fan",
                                         "box_px_tl": [1, 1, 5, 5]})
    # An unknown name does become a pending "unknown symbol" box when opened.
    session.proposals["devices"].append({"pdf_page": 70, "device_type": "mystery_thing",
                                         "box_px_tl": [1, 1, 5, 5]})
    session.save(9, revision=None, instances=[_pending("a"), _verified("b")], rejected=[])
    session.save(19, revision=None, instances=[_verified("c")], rejected=[])
    session.save(299, revision=None, instances=[], rejected=[],
                 metadata={"verified_no_symbols": True})              # opened: proposals rejected
    rows = {r["page"]: r for r in session.page_overview()["pages"]}
    assert rows[9] == {"page": 9, "saved": True, "pending": 1, "verified": 1,
                       "complete": False, "no_symbols": False}
    assert rows[19]["pending"] == 0 and rows[19]["verified"] == 1
    assert rows[49] == {"page": 49, "saved": False, "pending": 2, "verified": 0,
                        "complete": False, "no_symbols": False}
    assert rows[299]["saved"] and rows[299]["pending"] == 0 and rows[299]["no_symbols"]
    assert 59 not in rows and 998 not in rows
    assert rows[69]["pending"] == 1
    # Exactly what opening those pages produces.
    assert len(session.page_state(49)["document"]["instances"]) == 2
    assert session.page_state(59)["document"]["instances"] == []
    assert session.page_overview()["page_count"] == 400

    # The labels file is parsed once, and again only after it changes.
    reads = []
    real = Path.read_text
    monkeypatch.setattr(Path, "read_text", lambda self, *a, **k: reads.append(self.name) or real(self, *a, **k))
    session.page_overview()
    assert [n for n in reads if n.endswith("_labels.json")] == []
    session.save(9, revision=session.page_state(9)["revision"],
                 instances=[_verified("b")], rejected=[])
    reads.clear()
    rows = {r["page"]: r for r in session.page_overview()["pages"]}
    assert rows[9]["pending"] == 0
    assert [n for n in reads if n.endswith("_labels.json")] == []    # the writer cached what it wrote
    os.utime(session._json_path(9), ns=(time.time_ns(), time.time_ns()))   # changed by someone else
    session.page_overview()
    assert [n for n in reads if n.endswith("_labels.json")] == [session._json_path(9).name]


def test_overview_of_a_ten_thousand_page_document_is_fast(huge_pdf, tmp_path):
    session = LabelingSession(pdf=huge_pdf, out_dir=tmp_path / "labels", dpi=30)
    session.proposals = _proposals(huge_pdf, list(range(1, 10_003, 7)))
    session.save(10_001, revision=None, instances=[_pending("x")], rejected=[])
    started = time.perf_counter()
    overview = session.page_overview()
    assert time.perf_counter() - started < 2.0
    rows = {r["page"]: r for r in overview["pages"]}
    assert rows[10_001]["saved"] and rows[10_001]["pending"] == 1
    assert len(rows) == len(range(1, 10_003, 7)) + (0 if 10_002 % 7 == 1 else 1)


def test_overview_over_http_follows_the_requested_document(tmp_path):
    bp = tmp_path / "blueprints"
    a = _long_pdf(bp / "a.pdf", 3)
    b = _long_pdf(bp / "b.pdf", 3)
    session = LabelingSession(pdf=a, blueprints_dir=bp, out_dir=tmp_path / "labels", dpi=30)
    session.for_document(str(b)).save(2, revision=None, instances=[_pending("p")], rejected=[])
    with _Served(session) as served:
        def get(path):
            conn = http.client.HTTPConnection("127.0.0.1", served.server.server_port, timeout=20)
            conn.request("GET", path)
            resp = conn.getresponse()
            body = json.loads(resp.read())
            conn.close()
            return resp.status, body
        status, body = get("/api/overview")
        assert status == 200 and body["pages"] == []
        status, body = get("/api/overview?doc=" + quote(str(b), safe=""))
        assert status == 200 and [r["page"] for r in body["pages"]] == [2]
        assert get("/api/overview?page=1")[0] == 409


def test_browser_next_page_to_review_skips_finished_pages_and_wraps(chrome, long_pdf, tmp_path):
    session = LabelingSession(pdf=long_pdf, out_dir=tmp_path / "labels", dpi=30)
    session.proposals = _proposals(long_pdf, [120])                  # never opened yet
    session.save(39, revision=None, instances=[_pending("a")], rejected=[])
    session.save(79, revision=None, instances=[_verified("b")], rejected=[])   # done: skipped
    with _Served(session) as served:
        page = _open(chrome, served.url, 400)
        page.locator("#canvas").focus()
        page.keyboard.press("Shift+N")
        _wait_page(page, 40, 400)
        page.locator("#nextReviewPage").click()
        _wait_page(page, 120, 400)
        assert "1 pending" in page.locator("#count").inner_text()     # the proposal box

        # Verifying this tab's pages (unsaved) removes them from the review walk.
        page.locator("#items button.item-btn").first.click()
        page.locator("#deviceCatalogSearch").fill("Receptacle - Duplex")
        page.locator("#deviceCatalogList button", has_text="Receptacle - Duplex").first.click()
        page.locator("#accept").click()
        page.wait_for_function("() => document.querySelector('#count').textContent.includes('1 verified')"
                               " || !document.querySelector('#count').textContent.includes('pending')")
        page.locator("#canvas").focus()
        page.keyboard.press("Shift+N")
        _wait_page(page, 40, 400)                                     # wrapped back to page 40
        page.locator("#items button.item-btn").first.click()
        page.locator("#deviceCatalogSearch").fill("Receptacle - Duplex")
        page.locator("#deviceCatalogList button", has_text="Receptacle - Duplex").first.click()
        page.locator("#accept").click()
        page.locator("#canvas").focus()
        page.keyboard.press("Shift+N")
        page.wait_for_function(
            "() => document.querySelector('#message').textContent.includes('No page in this document has pending boxes')")
        _wait_page(page, 40, 400)
        page.close()


def _draw_box(page):
    page.locator("#drawTool").click()
    box = page.locator("#canvas").bounding_box()
    sx, sy = box["x"] + box["width"] / 2 - 20, box["y"] + box["height"] / 2 - 20
    page.mouse.move(sx, sy)
    page.mouse.down()
    page.mouse.move(sx + 40, sy + 30, steps=4)
    page.mouse.up()


def _jump(page, number, total):
    page.locator("#pageJump").fill(str(number))
    page.locator("#pageJump").press("Enter")
    _wait_page(page, number, total)


def test_browser_undo_history_stays_with_its_page(chrome, long_pdf, tmp_path):
    session = LabelingSession(pdf=long_pdf, out_dir=tmp_path / "labels", dpi=30)
    with _Served(session) as served:
        page = _open(chrome, served.url, 400)
        _draw_box(page)
        page.wait_for_function("() => document.querySelector('#count').textContent.includes('1 pending')")
        _jump(page, 2, 400)
        assert page.locator("#undo").is_disabled()            # page 2 has no history
        page.locator("#canvas").focus()
        page.keyboard.press("Control+z")
        _jump(page, 1, 400)
        assert "1 pending" in page.locator("#count").inner_text()   # untouched by page 2's undo
        assert not page.locator("#undo").is_disabled()
        page.locator("#canvas").focus()
        page.keyboard.press("Control+z")
        page.wait_for_function("() => document.querySelector('#count').textContent.startsWith('0 active')")
        page.keyboard.press("Control+y")
        page.wait_for_function("() => document.querySelector('#count').textContent.includes('1 pending')")
        page.close()


def test_browser_returning_to_an_edited_page_does_not_offer_its_own_draft(chrome, long_pdf, tmp_path):
    session = LabelingSession(pdf=long_pdf, out_dir=tmp_path / "labels", dpi=30)
    with _Served(session) as served:
        page = _open(chrome, served.url, 400)
        _draw_box(page)
        page.wait_for_function("() => document.querySelector('#count').textContent.includes('1 pending')")
        _jump(page, 2, 400)
        _jump(page, 1, 400)
        assert "1 pending" in page.locator("#count").inner_text()
        assert page.locator("#draftBanner").is_hidden()
        # After a reload the edits exist only in the draft, which is then offered.
        page.reload()
        _wait_page(page, 1, 400)
        page.wait_for_selector("#draftBanner:not([hidden])")
        page.locator("#restoreDraftBtn").click()
        page.wait_for_function("() => document.querySelector('#count').textContent.includes('1 pending')")
        page.close()


def test_browser_returning_to_a_page_saved_elsewhere_shows_the_new_copy(chrome, long_pdf, tmp_path):
    session = LabelingSession(pdf=long_pdf, out_dir=tmp_path / "labels", dpi=30)
    with _Served(session) as served:
        page = _open(chrome, served.url, 400)
        _jump(page, 5, 400)
        _jump(page, 6, 400)
        # An agent (or another tab) labels page 5 meanwhile.
        session.save(4, revision=None, instances=[_pending("agent_1")], rejected=[])
        _jump(page, 5, 400)
        assert page.locator("#count").inner_text().startswith("1 active")
        assert page.locator("#conflictBanner").is_hidden()
        page.close()


def test_browser_warns_on_return_when_an_edited_page_changed_on_disk(chrome, long_pdf, tmp_path):
    session = LabelingSession(pdf=long_pdf, out_dir=tmp_path / "labels", dpi=30)
    with _Served(session) as served:
        page = _open(chrome, served.url, 400)
        _jump(page, 5, 400)
        _draw_box(page)
        page.wait_for_function("() => document.querySelector('#count').textContent.includes('1 pending')")
        _jump(page, 6, 400)
        session.save(4, revision=None, instances=[_pending("agent_1")], rejected=[])
        _jump(page, 5, 400)
        assert page.locator("#count").inner_text().startswith("1 active")   # this tab's edit is kept
        page.wait_for_selector("#conflictBanner:not([hidden])")
        assert "changed on disk" in page.locator("#conflictText").inner_text()
        page.locator("#reloadFromDisk").click()
        _wait_page(page, 5, 400)
        page.wait_for_function("() => window.__labelerState.doc.instances[0]?.instance_id === 'agent_1'")
        page.close()
