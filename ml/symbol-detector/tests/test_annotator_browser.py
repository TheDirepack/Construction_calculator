"""Real browser smoke for the local PDF labeling interface.

Run with:
  pip install playwright
  python -m playwright install chromium
  pytest -q tests/test_annotator_browser.py
"""
from __future__ import annotations

import json
import shutil
import threading
from http.server import ThreadingHTTPServer

import pytest

fitz = pytest.importorskip("fitz")
playwright = pytest.importorskip("playwright.sync_api")

from annotator.web import LabelingSession, make_handler


def _launch_browser(playwright_instance):
    # GitHub's Ubuntu 24.04 runner already provides Chrome; avoid downloading
    # another 100+ MB browser for every PR push. Local developers can use
    # system Chromium or the Playwright-managed browser as a fallback.
    executable = (shutil.which("google-chrome") or
                  shutil.which("google-chrome-stable") or
                  shutil.which("chromium") or shutil.which("chromium-browser"))
    options = {"executable_path": executable} if executable else {}
    return playwright_instance.chromium.launch(
        headless=True, args=["--no-sandbox"], **options,
    )


def _pick_device(page, label):
    """Set the selected box's device through the sidebar catalog, as a user does."""
    page.locator("#deviceCatalogSearch").fill(label)
    page.locator("#deviceCatalogList button", has_text=label).first.click()
    page.locator("#deviceCatalogSearch").fill("")


def _answer_confirm(page, accept=True):
    page.wait_for_selector("#confirmDialog[open]")
    page.locator("#confirmOkBtn" if accept else "#confirmCancelBtn").click()
    page.wait_for_function("() => !document.querySelector('#confirmDialog').open")


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
            browser = _launch_browser(pw)
            try:
                page = browser.new_page(viewport={"width": 1420, "height": 900})
                page.goto(f"http://127.0.0.1:{server.server_port}/")
                page.wait_for_function(
                    "() => document.querySelector('#pageIndicator').textContent === 'Page 1 / 2'"
                )
                assert "0 active" in page.locator("#count").inner_text()
                geometry_ok = page.evaluate("""async () => {
                  const {screenToImage,imageToScreen,normalizedBox,rotatedAabb,hitBox}
                    = await import('/web_ui.js');
                  const v = {scale: 2.5, x: 100, y: 200};
                  const p = screenToImage(...imageToScreen(30, 40, v), v);
                  const b = normalizedBox([100, 100], [-15, 260], 200, 150);
                  const a = rotatedAabb(50, 50, 20, 10, 90);
                  return p[0] === 30 && p[1] === 40 &&
                    JSON.stringify(b) === JSON.stringify([0,100,100,150]) &&
                    Math.abs(a[0]-45) < 1e-8 && Math.abs(a[1]-40) < 1e-8 &&
                    hitBox([10,10,50,50],10,10,1,true) === 'nw';
                }""")
                assert geometry_ok
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
                # Verifying needs a specific device; the default draft has none.
                assert page.locator("#accept").is_disabled()
                assert not page.locator("#verifyHint").is_hidden()
                _pick_device(page, "Switch - 3-Way")
                assert page.locator("#selectedLabel").inner_text() == "Switch - 3-Way"
                page.locator("#accept").click()
                assert "1 verified" in page.locator("#count").inner_text()

                page.locator("#save").click()
                page.wait_for_function(
                    "() => document.querySelector('#dirty').textContent === 'Saved'"
                )
                saved = json.loads((tmp_path / "labels" / "label_test_p0001.json").read_text())
                assert saved["exhaustive_families"] == []
                assert saved["annotation_completeness_verified"] is False
                # Training splits by project: every saved page names a verified group.
                assert saved["source_group_verified"] is True and saved["project_family_id"]
                page.locator("#verifiedNoSymbols").click()
                assert not page.locator("#verifiedNoSymbols").is_checked()
                assert "Reject or remove" in page.locator("#message").inner_text()
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
                page.locator("#verifiedNoSymbols").check()
                _answer_confirm(page)
                page.locator("#save").click()
                page.wait_for_function("() => document.querySelector('#dirty').textContent === 'Saved'")
                saved_negative = json.loads((tmp_path / "labels" / "label_test_p0002.json").read_text())
                assert saved_negative["verified_no_symbols"] is True
                assert saved_negative["instances"] == []
                page.locator("#prevPage").click()
                page.wait_for_function("() => document.querySelector('#pageIndicator').textContent === 'Page 1 / 2'")
                assert not page.locator("#verifiedNoSymbols").is_checked()
                page.locator("#nextPage").click()
                page.wait_for_function("() => document.querySelector('#pageIndicator').textContent === 'Page 2 / 2'")
                assert page.locator("#verifiedNoSymbols").is_checked()
            finally:
                browser.close()
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=10)



def test_browser_sam_class_alternative_is_not_automatically_verified(tmp_path):
    from annotator.web import sha256_file

    doc = fitz.open()
    page = doc.new_page(width=400, height=300)
    page.draw_rect(fitz.Rect(80, 80, 110, 110), color=(0, 0, 0))
    pdf = tmp_path / "source.pdf"
    doc.save(pdf)
    doc.close()
    proposals = tmp_path / "proposals_SAM3.json"
    proposals.write_text(json.dumps({
        "source_file": pdf.name,
        "source_pdf_sha256": sha256_file(pdf),
        "devices": [{
            "pdf_page": 1, "device_type": "switch",
            "subtype": "unspecified", "score": .88,
            "symbol_bbox": [80, 190, 110, 220],
            "neca_symbol_id": "NECA_SWITCH",
            "visual_group": "switch.base_letter_s",
            "alternative_labels": [
                {"device_type": "receptacle", "subtype": "duplex", "score": .78}
            ],
        }],
    }))
    session = LabelingSession(pdf=pdf, proposals=proposals,
                             out_dir=tmp_path / "review", dpi=144)
    server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(session))
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        with playwright.sync_playwright() as pw:
            browser = _launch_browser(pw)
            try:
                tab = browser.new_page(viewport={"width": 1420, "height": 900})
                tab.goto(f"http://127.0.0.1:{server.server_port}/")
                tab.wait_for_function(
                    "() => document.querySelector('#count').textContent.includes('1 pending')"
                )
                tab.locator("#items button").first.click()
                _pick_device(tab, "Receptacle - Duplex")
                assert tab.locator("#selectedLabel").inner_text() == "Receptacle - Duplex"
                assert "0 verified" in tab.locator("#count").inner_text()
                tab.locator("#save").click()
                tab.wait_for_function(
                    "() => document.querySelector('#dirty').textContent === 'Saved'"
                )
                pending = json.loads((tmp_path / "review" / "source_p0001.json").read_text())
                assert pending["instances"][0]["uncertain"] is True
                assert pending["instances"][0]["verified_ground_truth"] is False
                assert pending["instances"][0]["symbol_family"] == "receptacle"
                tab.locator("#accept").click()
                tab.locator("#save").click()
                tab.wait_for_function(
                    "() => document.querySelector('#dirty').textContent === 'Saved'"
                )
                verified = json.loads((tmp_path / "review" / "source_p0001.json").read_text())
                assert verified["instances"][0]["review_source"] == "human"
                assert verified["instances"][0]["verified_ground_truth"] is True
            finally:
                browser.close()
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=10)


def test_browser_draft_restore_rejects_different_source_even_with_null_revision(tmp_path):
    document = fitz.open()
    document.new_page(width=400, height=300).draw_rect(
        fitz.Rect(80, 80, 120, 120), color=(0, 0, 0)
    )
    pdf = tmp_path / "plan.pdf"
    document.save(pdf)
    document.close()
    session = LabelingSession(pdf=pdf, out_dir=tmp_path / "labels", dpi=144)
    state = session.page_state(0)
    assert state["revision"] is None
    source_doc = state["document"]
    key = "blueprint_labeler_draft::" + state["json_path"]
    box = {"instance_id": "draft_1", "symbol_family": "receptacle",
           "symbol_variant": "duplex", "bbox_xyxy": [120, 100, 145, 125],
           "review_status": "unreviewed", "review_source": "manual_draft",
           "verified_ground_truth": False, "uncertain": True}
    draft = {"revision": None, "image_sha256": "0" * 64,
             "source_pdf_sha256": source_doc["source_pdf_sha256"],
             "image_width": source_doc["image_width"],
             "image_height": source_doc["image_height"],
             "coordinate_system": source_doc["coordinate_system"],
             "instances": [box], "rejected_proposals": []}
    server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(session))
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        with playwright.sync_playwright() as pw:
            browser = _launch_browser(pw)
            try:
                page = browser.new_page(viewport={"width": 1300, "height": 850})
                page.goto(f"http://127.0.0.1:{server.server_port}/")
                page.wait_for_function("() => document.querySelector('#count')?.textContent?.includes('0 active')")
                def set_draft(payload):
                    page.evaluate(
                        "({key, payload}) => localStorage.setItem(key, JSON.stringify(payload))",
                        {"key": key, "payload": payload}
                    )
                set_draft(draft)
                page.reload()
                page.wait_for_function("() => document.querySelector('#count')?.textContent?.includes('0 active')")
                assert page.locator("#draftBanner").is_hidden()
                page.evaluate("() => document.querySelector('#restoreDraftBtn').click()")
                page.wait_for_function(
                    "() => document.querySelector('#message')?.textContent?.includes('cannot be restored')"
                )
                assert "0 active" in page.locator("#count").inner_text()
                assert page.evaluate("key => !!localStorage.getItem(key)", key)
                set_draft(dict(draft, image_sha256=source_doc["image_sha256"]))
                page.reload()
                page.wait_for_function("() => document.querySelector('#count')?.textContent?.includes('0 active')")
                assert page.locator("#draftBanner").is_visible()
                page.locator("#restoreDraftBtn").click()
                page.wait_for_function("() => document.querySelector('#count')?.textContent?.includes('1 pending')")
                assert "0 verified" in page.locator("#count").inner_text()
                assert not (tmp_path / "labels" / "plan_p0001.json").exists()
            finally:
                browser.close()
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=10)


def test_browser_verified_label_demoted_after_semantic_edits(tmp_path):
    """Changing a previously reviewed target must require a fresh human acceptance."""
    from PIL import Image
    image = tmp_path / "plan.png"
    Image.new("RGB", (400, 300), (245, 245, 245)).save(image)
    annotations = tmp_path / "page.json"
    annotations.write_text(json.dumps({
        "image_path": image.name, "image_width": 400, "image_height": 300,
        "coordinate_system": "original_page_pixels_top_left_y_down",
        "instances": [{
            "instance_id": "verified_1",
            "symbol_family": "receptacle", "symbol_variant": "duplex",
            "bbox_xyxy": [100, 100, 140, 140], "review_status": "accepted",
            "review_source": "human", "verified_ground_truth": True, "uncertain": False,
        }],
    }))
    session = LabelingSession(edit=annotations)
    server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(session))
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        with playwright.sync_playwright() as pw:
            browser = _launch_browser(pw)
            try:
                page = browser.new_page(viewport={"width": 1420, "height": 900})
                page.goto(f"http://127.0.0.1:{server.server_port}/")
                page.wait_for_function(
                    "() => document.querySelector('#count')?.textContent?.includes('1 verified')"
                )
                page.locator("#items button").first.click()
                page.locator("#canvas").focus()
                page.keyboard.press("ArrowRight")                  # moving a verified box re-opens review
                assert "1 pending" in page.locator("#count").inner_text()
                page.locator("#undo").click()
                assert "1 verified" in page.locator("#count").inner_text()

                page.locator("#angle").fill("25")
                page.locator("#angle").press("Tab")
                assert "1 pending" in page.locator("#count").inner_text()
                page.locator("#accept").click()
                assert "1 verified" in page.locator("#count").inner_text()

                _pick_device(page, "Switch - 3-Way")
                assert "1 pending" in page.locator("#count").inner_text()
                page.locator("#accept").click()
                assert "1 verified" in page.locator("#count").inner_text()

                page.locator("#deviceAttrSection summary").click()
                page.locator("#customAttrKey").fill("gfci")
                page.locator("#customAttrVal").fill("true")
                page.locator("#addCustomAttr").click()
                assert "1 pending" in page.locator("#count").inner_text()
                page.locator("#save").click()
                page.wait_for_function(
                    "() => document.querySelector('#dirty')?.textContent === 'Saved'"
                )
                saved = json.loads(annotations.read_text())
                assert saved["instances"][0]["bbox_rotated"]["angle_deg"] == 25
                assert saved["instances"][0]["symbol_family"] == "switch"
                assert saved["instances"][0]["symbol_variant"] == "three_way"
                assert saved["instances"][0]["device_attributes"]["gfci"] is True
                assert saved["instances"][0]["review_status"] == "unreviewed"
                assert saved["instances"][0]["verified_ground_truth"] is False
                page.locator("#accept").click()
                page.locator("#save").click()
                page.wait_for_function(
                    "() => document.querySelector('#dirty')?.textContent === 'Saved'"
                )
                assert json.loads(annotations.read_text())["instances"][0]["verified_ground_truth"] is True
                # The 1-9 family keys must not carry a subtype from the old class,
                # and a box without a specific device cannot be verified.
                page.locator("#canvas").focus()
                page.keyboard.press("1")
                assert page.locator("#selectedLabel").inner_text() == "receptacle / unspecified"
                assert "1 pending" in page.locator("#count").inner_text()
                assert page.locator("#accept").is_disabled()
            finally:
                browser.close()
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=10)


def test_browser_electrical_device_catalog_search_modal_and_save(tmp_path):
    document = fitz.open()
    page_pdf = document.new_page(width=400, height=300)
    page_pdf.draw_rect(fitz.Rect(80, 80, 120, 120), color=(0, 0, 0))
    pdf = tmp_path / "catalog_devices.pdf"
    document.save(pdf)
    document.close()

    session = LabelingSession(pdf=pdf, out_dir=tmp_path / "labels", dpi=144)
    server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(session))
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        with playwright.sync_playwright() as pw:
            browser = _launch_browser(pw)
            try:
                page = browser.new_page(viewport={"width": 1420, "height": 900})
                page.goto(f"http://127.0.0.1:{server.server_port}/")
                page.wait_for_function(
                    "() => document.querySelector('#deviceCatalogCount')?.textContent === '182'"
                )
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

                page.locator("#deviceCatalogSearch").fill("Long Range")
                page.locator("#deviceCatalogList button", has_text="Card Reader - Long Range").click()
                assert page.locator("#selectedLabel").inner_text() == "Card Reader - Long Range"

                page.locator("#openDeviceCatalogBtn").click()
                page.wait_for_function("() => !document.querySelector('#deviceCatalogModal').hidden")
                page.locator("#deviceModalSearch").fill("Horn / Strobe - Wall")
                page.locator("#deviceModalGrid button", has_text="Fire Alarm Horn / Strobe - Wall Mounted").click()
                page.wait_for_function("() => document.querySelector('#deviceCatalogModal').hidden")
                assert page.locator("#selectedLabel").inner_text() == "Fire Alarm Horn / Strobe - Wall Mounted"

                page.locator("#accept").click()
                page.locator("#save").click()
                page.wait_for_function("() => document.querySelector('#dirty').textContent === 'Saved'")
                saved = json.loads((tmp_path / "labels" / "catalog_devices_p0001.json").read_text())
                inst = saved["instances"][0]
                assert inst["symbol_family"] == "fire_alarm_device"
                assert inst["symbol_variant"] == "horn_strobe_wall"
                assert inst["discipline"] == "electrical"
                assert inst["verified_ground_truth"] is True
            finally:
                browser.close()
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=10)


def test_browser_next_pdf_starts_with_empty_page(tmp_path):
    """Switching blueprint documents must not carry the previous PDF's page over."""
    docs_dir = tmp_path / "blueprints"
    docs_dir.mkdir()
    for name in ("switch_a.pdf", "switch_b.pdf"):
        document = fitz.open()
        for _ in range(2):
            page = document.new_page(width=400, height=300)
            page.draw_rect(fitz.Rect(50, 50, 65, 65), color=(0, 0, 0))
        document.save(docs_dir / name)
        document.close()

    session = LabelingSession(pdf=docs_dir / "switch_a.pdf", blueprints_dir=docs_dir,
                              out_dir=tmp_path / "labels", dpi=144)
    server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(session))
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        with playwright.sync_playwright() as pw:
            browser = _launch_browser(pw)
            try:
                page = browser.new_page(viewport={"width": 1420, "height": 900})
                page.goto(f"http://127.0.0.1:{server.server_port}/")
                page.wait_for_function(
                    "() => document.querySelector('#pageIndicator').textContent === 'Page 1 / 2'"
                )
                assert "switch_a.pdf" in page.locator("#currentDocBadge").inner_text()

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

                page.locator("#nextPdfBtn").click()
                _answer_confirm(page)
                page.wait_for_function(
                    "() => document.querySelector('#currentDocBadge').textContent.includes('switch_b.pdf')"
                )
                page.wait_for_function(
                    "() => document.querySelector('#pageIndicator').textContent === 'Page 1 / 2'"
                )
                assert "0 active" in page.locator("#count").inner_text()
                assert session.page_state(0)["document"]["instances"] == []
                # Accepting the switch prompt saved the first PDF's own page.
                saved_a = json.loads((tmp_path / "labels" / "switch_a_p0001.json").read_text())
                assert len(saved_a["instances"]) == 1
            finally:
                browser.close()
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=10)


def test_browser_save_conflict_reloads_the_conflicting_page(tmp_path):
    """A stale page must not trap Save in a reload/conflict loop."""
    document = fitz.open()
    for _ in range(2):
        page = document.new_page(width=400, height=300)
        page.draw_rect(fitz.Rect(50, 50, 65, 65), color=(0, 0, 0))
    pdf = tmp_path / "conflict.pdf"
    document.save(pdf)
    document.close()

    session = LabelingSession(pdf=pdf, out_dir=tmp_path / "labels", dpi=144)
    server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(session))
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        with playwright.sync_playwright() as pw:
            browser = _launch_browser(pw)
            try:
                page = browser.new_page(viewport={"width": 1420, "height": 900})
                page.goto(f"http://127.0.0.1:{server.server_port}/")
                page.wait_for_function(
                    "() => document.querySelector('#pageIndicator').textContent === 'Page 1 / 2'"
                )

                # Cancelling the in-page confirmation must not attest the page.
                page.locator("#verifiedNoSymbols").check()
                _answer_confirm(page, accept=False)
                assert not page.locator("#verifiedNoSymbols").is_checked()

                # Page 1: negative attestation. Page 2: a drawn draft.
                page.locator("#verifiedNoSymbols").check()
                _answer_confirm(page)
                page.wait_for_function(
                    "() => document.querySelector('#dirty').textContent === 'Unsaved (p.1)'"
                )
                page.locator("#nextPage").click()
                page.wait_for_function(
                    "() => document.querySelector('#pageIndicator').textContent === 'Page 2 / 2'"
                )
                canvas = page.locator("#canvas").bounding_box()
                assert canvas is not None
                page.locator("#drawTool").click()
                sx, sy = canvas["x"] + 230, canvas["y"] + 190
                page.mouse.move(sx, sy)
                page.mouse.down()
                page.mouse.move(sx + 48, sy + 36, steps=4)
                page.mouse.up()
                page.wait_for_function(
                    "() => document.querySelector('#count').textContent.includes('1 pending')"
                )

                # Another client saves page 1 while this browser is on page 2.
                session.save(
                    0, revision=session.page_state(0)["revision"], instances=[],
                    rejected=[], metadata={"verified_no_symbols": False},
                )

                page.locator("#save").click()
                page.wait_for_function(
                    "() => !document.querySelector('#conflictBanner').hidden"
                )
                # The unaffected page still saved.
                assert (tmp_path / "labels" / "conflict_p0002.json").exists()
                assert "page 1 changed on disk" in page.locator("#message").inner_text()

                page.locator("#reloadFromDisk").click()
                page.wait_for_function(
                    "() => document.querySelector('#conflictBanner').hidden"
                )
                page.wait_for_function(
                    "() => document.querySelector('#pageIndicator').textContent === 'Page 1 / 2'"
                )
                # Reload must show the on-disk page, not the stale in-memory copy.
                assert not page.locator("#verifiedNoSymbols").is_checked()
                assert "0 active" in page.locator("#count").inner_text()
                assert page.locator("#dirty").inner_text() == "Saved"
                assert "Page 1" in (page.locator("#conflictText").text_content() or "")

                # Saving again after the reload must succeed instead of repeating 409.
                page.locator("#verifiedNoSymbols").check()
                _answer_confirm(page)
                page.locator("#save").click()
                page.wait_for_function(
                    "() => document.querySelector('#dirty').textContent === 'Saved'"
                )
                assert page.locator("#conflictBanner").is_hidden()
                saved = json.loads((tmp_path / "labels" / "conflict_p0001.json").read_text())
                assert saved["verified_no_symbols"] is True
            finally:
                browser.close()
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=10)


def test_browser_anchor_any_box_without_selecting_it(tmp_path):
    """Anchor mode attaches each click to the nearest box, stays on, and can anchor all."""
    document = fitz.open()
    document.new_page(width=400, height=300)
    pdf = tmp_path / "anchors.pdf"
    document.save(pdf)
    document.close()

    session = LabelingSession(pdf=pdf, out_dir=tmp_path / "labels", dpi=144)
    server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(session))
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        with playwright.sync_playwright() as pw:
            browser = _launch_browser(pw)
            try:
                page = browser.new_page(viewport={"width": 1420, "height": 900})
                page.goto(f"http://127.0.0.1:{server.server_port}/")
                page.wait_for_function(
                    "() => document.querySelector('#pageIndicator').textContent === 'Page 1 / 1'")
                canvas = page.locator("#canvas").bounding_box()
                assert canvas is not None
                boxes = [(canvas["x"] + 230, canvas["y"] + 190), (canvas["x"] + 420, canvas["y"] + 190)]
                for n, (sx, sy) in enumerate(boxes, start=1):
                    page.locator("#drawTool").click()
                    page.mouse.move(sx, sy)
                    page.mouse.down()
                    page.mouse.move(sx + 48, sy + 36, steps=4)
                    page.mouse.up()
                    page.wait_for_function(
                        f"() => document.querySelector('#count').textContent.includes('{n} pending')")

                # The second box is selected; anchor the first one anyway by
                # clicking just outside its left edge (snaps to Left Edge).
                page.keyboard.press("k")
                assert "attaches to the nearest box" in page.locator("#modeBanner").inner_text()
                lx, ly = boxes[0][0] - 3, boxes[0][1] + 18
                page.mouse.move(lx - 20, ly)
                page.mouse.move(lx, ly, steps=3)
                page.mouse.click(lx, ly)
                # Still in anchor mode: anchor the second box's centre next.
                assert not page.locator("#modeBanner").is_hidden()
                cx, cy = boxes[1][0] + 24, boxes[1][1] + 18
                page.mouse.move(cx, cy, steps=3)
                page.mouse.click(cx, cy)
                page.keyboard.press("Escape")

                page.locator("#anchorAllBoxes").click()
                page.locator("#save").click()
                page.wait_for_function("() => document.querySelector('#dirty').textContent === 'Saved'")
                saved = json.loads((tmp_path / "labels" / "anchors_p0001.json").read_text())
                first, second = sorted(saved["instances"], key=lambda i: i["bbox_xyxy"][0])
                round_families = {"receptacle", "luminaire", "junction_box", "fire_alarm_device",
                                  "sprinkler_head", "sensor"}

                def centre(item):
                    return "circle_center" if item["symbol_family"] in round_families else "visual_center"
                assert set(first["anchors"]) == {"wall_attachment", centre(first)}, first["anchors"]
                left_x = first["bbox_xyxy"][0]
                assert abs(first["anchors"]["wall_attachment"]["xy"][0] - left_x) < 1e-6
                assert set(second["anchors"]) == {centre(second)}, second["anchors"]
            finally:
                browser.close()
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=10)


def test_browser_next_pdf_walks_every_document_in_order(tmp_path):
    """Next PDF must reach the third and later documents, not bounce between two."""
    docs_dir = tmp_path / "blueprints"
    docs_dir.mkdir()
    names = ["doc_a.pdf", "doc_b.pdf", "doc_c.pdf", "doc_d.pdf"]
    for name in names:
        document = fitz.open()
        document.new_page(width=400, height=300)
        document.save(docs_dir / name)
        document.close()
    labels = tmp_path / "labels"
    labels.mkdir()
    # doc_c already has labels, so the menu lists it first; Next PDF must still
    # visit it in folder order.
    seed = LabelingSession(pdf=docs_dir / "doc_c.pdf", out_dir=labels, dpi=72)
    seed.save(0, revision=None, instances=[], rejected=[])

    session = LabelingSession(pdf=docs_dir / "doc_a.pdf", blueprints_dir=docs_dir,
                              out_dir=labels, dpi=72)
    server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(session))
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        with playwright.sync_playwright() as pw:
            browser = _launch_browser(pw)
            try:
                page = browser.new_page(viewport={"width": 1420, "height": 900})
                page.goto(f"http://127.0.0.1:{server.server_port}/")
                page.wait_for_function(
                    "() => document.querySelector('#currentDocBadge').textContent.includes('doc_a.pdf')")
                for expected in ["doc_b.pdf", "doc_c.pdf", "doc_d.pdf", "doc_a.pdf"]:
                    page.locator("#nextPdfBtn").click()
                    page.wait_for_function(
                        f"() => document.querySelector('#currentDocBadge').textContent.includes('{expected}')")
            finally:
                browser.close()
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=10)
