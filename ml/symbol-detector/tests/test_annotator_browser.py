"""Real browser smoke for the local PDF labeling interface.

Run with:
  pip install playwright
  python -m playwright install chromium
  pytest -q tests/test_annotator_browser.py
"""
from __future__ import annotations

import json
import threading
from http.server import ThreadingHTTPServer

import pytest

fitz = pytest.importorskip("fitz")
playwright = pytest.importorskip("playwright.sync_api")

from annotator.web import LabelingSession, make_handler


def test_browser_draw_verify_rotate_save_and_navigate(tmp_path):
    document = fitz.open()
    for _ in range(2):
        page = document.new_page(width=400, height=300)
        page.draw_rect(fitz.Rect(50, 50, 65, 65), color=(0, 0, 0))
    pdf = tmp_path / "label_test.pdf"
    document.save(pdf)
    document.close()

    session = LabelingSession(pdf=pdf, out_dir=tmp_path / "labels", dpi=144)
    server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(session))
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        with playwright.sync_playwright() as pw:
            browser = pw.chromium.launch(headless=True, args=["--no-sandbox"])
            try:
                page = browser.new_page(viewport={"width": 1420, "height": 900})
                page.goto(f"http://127.0.0.1:{server.server_port}/")
                page.wait_for_function(
                    "() => document.querySelector('#pageIndicator').textContent === 'Page 1 / 2'"
                )
                assert "0 active" in page.locator("#count").inner_text()
                page.locator("#drawTool").click()
                canvas = page.locator("#canvas").bounding_box()
                assert canvas is not None
                sx, sy = canvas["x"] + 230, canvas["y"] + 190
                page.mouse.move(sx, sy)
                page.mouse.down()
                page.mouse.move(sx + 48, sy + 36, steps=4)
                page.mouse.up()
                page.wait_for_function(
                    "() => document.querySelector('#count').textContent.includes('1 pending')"
                )
                # A manual rectangle is a draft until a real human verifies it.
                assert "0 verified" in page.locator("#count").inner_text()
                page.locator("#undo").click()
                assert "0 active" in page.locator("#count").inner_text()
                page.locator("#redo").click()
                assert "1 pending" in page.locator("#count").inner_text()

                page.locator("#angle").fill("30")
                page.locator("#angle").press("Tab")
                page.locator("#family").fill("switch")
                page.locator("#family").press("Tab")
                page.locator("#variant").fill("three_way")
                page.locator("#variant").press("Tab")
                page.locator("#accept").click()
                assert "1 verified" in page.locator("#count").inner_text()

                page.locator("#save").click()
                page.wait_for_function(
                    "() => document.querySelector('#dirty').textContent === 'Saved'"
                )
                saved = json.loads((tmp_path / "labels" / "label_test_p0001.json").read_text())
                assert saved["exhaustive_families"] == []
                assert saved["annotation_completeness_verified"] is False
                instance = saved["instances"][0]
                assert instance["symbol_family"] == "switch"
                assert instance["symbol_variant"] == "three_way"
                assert instance["review_status"] == "accepted"
                assert instance["verified_ground_truth"] is True
                assert instance["bbox_rotated"]["angle_deg"] == 30

                page.locator("#nextPage").click()
                page.wait_for_function(
                    "() => document.querySelector('#pageIndicator').textContent === 'Page 2 / 2'"
                )
                assert "0 active" in page.locator("#count").inner_text()
                assert not (tmp_path / "labels" / "label_test_p0002.json").exists()
                assert session.page_state(1)["document"]["annotation_completeness_verified"] is False
            finally:
                browser.close()
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=10)
