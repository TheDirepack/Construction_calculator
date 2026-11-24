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
                page.on("dialog", lambda dialog: dialog.accept())
                page.locator("#verifiedNoSymbols").check()
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
                assert "NECA_SWITCH" in tab.locator("#modelSource").inner_text()
                tab.locator("#proposalAlternatives button").first.click()
                assert tab.locator("#family").input_value() == "receptacle"
                assert tab.locator("#variant").input_value() == "duplex"
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


def test_browser_import_requires_source_match_and_unverifies(tmp_path):
    """Mismatched-image imports cannot silently preserve human verification."""
    from PIL import Image

    image = tmp_path / "sheet.png"
    Image.new("RGB", (400, 300), (245, 245, 245)).save(image)
    annotations = tmp_path / "page.json"
    annotations.write_text(json.dumps({
        "image_path": "sheet.png", "image_width": 400, "image_height": 300,
        "coordinate_system": "original_page_pixels_top_left_y_down", "instances": [],
    }))
    imported = tmp_path / "different_sheet.json"
    imported.write_text(json.dumps({
        "image_sha256": "0" * 64, "image_width": 400, "image_height": 300,
        "coordinate_system": "original_page_pixels_top_left_y_down",
        "instances": [{
            "instance_id": "imported_1", "symbol_family": "receptacle",
            "symbol_variant": "duplex", "bbox_xyxy": [115.0, 108.0, 145.0, 138.0],
            "review_status": "accepted", "review_source": "human",
            "verified_ground_truth": True, "uncertain": False,
        }],
        "rejected_proposals": [{
            "instance_id": "rejected_1", "symbol_family": "switch",
            "symbol_variant": "unspecified", "bbox_xyxy": [200.0, 200.0, 230.0, 230.0],
            "review_status": "rejected", "review_source": "human",
            "uncertain": True, "verified_ground_truth": False,
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
                page = browser.new_page(viewport={"width": 1300, "height": 850})
                page.goto(f"http://127.0.0.1:{server.server_port}/")
                page.wait_for_function(
                    "() => document.querySelector('#count')?.textContent?.includes('0 active')"
                )
                page.once("dialog", lambda dialog: dialog.dismiss())
                page.locator("#importJsonFile").set_input_files(str(imported))
                assert "0 active" in page.locator("#count").inner_text()

                page.once("dialog", lambda dialog: dialog.accept())
                page.locator("#importJsonFile").set_input_files(str(imported))
                page.wait_for_function(
                    "() => document.querySelector('#count')?.textContent?.includes('1 pending')"
                )
                assert "0 verified" in page.locator("#count").inner_text()
                page.locator("#save").click()
                page.wait_for_function(
                    "() => document.querySelector('#dirty').textContent === 'Saved'"
                )
                saved = json.loads(annotations.read_text())
                assert saved["instances"][0]["review_status"] == "unreviewed"
                assert saved["instances"][0]["review_source"] == "import"
                assert saved["instances"][0]["verified_ground_truth"] is False
                assert saved["instances"][0]["uncertain"] is True
                assert saved["rejected_proposals"] == []
            finally:
                browser.close()
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=10)


def test_browser_invalid_import_keeps_current_verified_labels(tmp_path):
    from PIL import Image
    from annotator.web import sha256_file

    image = tmp_path / "plan.png"
    Image.new("RGB", (400, 300), (245, 245, 245)).save(image)
    active = tmp_path / "labels.json"
    active.write_text(json.dumps({
        "image_path": image.name, "image_width": 400, "image_height": 300,
        "coordinate_system": "original_page_pixels_top_left_y_down",
        "instances": [{
            "instance_id": "old_1", "symbol_family": "receptacle",
            "symbol_variant": "duplex", "bbox_xyxy": [100, 100, 130, 130],
            "review_status": "accepted", "review_source": "human",
            "verified_ground_truth": True, "uncertain": False,
        }],
    }))
    incoming = tmp_path / "incoming.json"
    header = {"image_sha256": sha256_file(image), "image_width": 400,
              "image_height": 300, "coordinate_system": "original_page_pixels_top_left_y_down"}
    valid = {"instance_id": "new_1", "symbol_family": "receptacle",
             "symbol_variant": "duplex", "bbox_xyxy": [140, 140, 170, 170],
             "review_status": "unreviewed", "review_source": "import",
             "verified_ground_truth": False, "uncertain": True,
             "device_attributes": {"voltage_v": 120, "gfci": True}}
    session = LabelingSession(edit=active)
    server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(session))
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    original_bytes = active.read_bytes()
    try:
        with playwright.sync_playwright() as pw:
            browser = _launch_browser(pw)
            try:
                page = browser.new_page(viewport={"width": 1300, "height": 850})
                page.goto(f"http://127.0.0.1:{server.server_port}/")
                page.wait_for_function("() => document.querySelector('#count')?.textContent?.includes('1 active')")
                assert "1 verified" in page.locator("#count").inner_text()
                for instances, expected in [
                    ([valid, dict(valid)], "unique"),
                    ([dict(valid, device_attributes={"voltage_v": -4})], "device_attributes"),
                    ([dict(valid, bbox_xyxy=[-1, 140, 170, 170])], "bbox"),
                ]:
                    incoming.write_text(json.dumps(dict(header, instances=instances)))
                    page.locator("#importJsonFile").set_input_files(str(incoming))
                    page.wait_for_function(
                        "part => document.querySelector('#message')?.textContent?.includes(part)", arg=expected
                    )
                    assert "1 verified" in page.locator("#count").inner_text()
                    assert page.locator("#dirty").inner_text() == "Saved"
                    assert active.read_bytes() == original_bytes
                incoming.write_text(json.dumps(dict(header, instances=[valid])))
                page.locator("#importJsonFile").set_input_files(str(incoming))
                page.wait_for_function("() => document.querySelector('#count')?.textContent?.includes('1 pending')")
                assert "0 verified" in page.locator("#count").inner_text()
                page.locator("#save").click()
                page.wait_for_function("() => document.querySelector('#dirty').textContent === 'Saved'")
                saved = json.loads(active.read_text())
                assert saved["instances"][0]["instance_id"] == "new_1"
                assert saved["instances"][0]["device_attributes"] == {"voltage_v": 120, "gfci": True}
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
                page.locator("#x0").fill("105")
                page.locator("#x0").press("Tab")
                assert "1 pending" in page.locator("#count").inner_text()
                page.locator("#undo").click()
                assert "1 verified" in page.locator("#count").inner_text()

                page.locator("#angle").fill("25")
                page.locator("#angle").press("Tab")
                assert "1 pending" in page.locator("#count").inner_text()
                page.locator("#accept").click()
                assert "1 verified" in page.locator("#count").inner_text()

                page.locator("#family").fill("switch")
                page.locator("#family").press("Tab")
                assert page.locator("#variant").input_value() == "unspecified"
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
                assert saved["instances"][0]["symbol_variant"] == "unspecified"
                assert saved["instances"][0]["device_attributes"]["gfci"] is True
                assert saved["instances"][0]["review_status"] == "unreviewed"
                assert saved["instances"][0]["verified_ground_truth"] is False
                page.locator("#accept").click()
                page.locator("#save").click()
                page.wait_for_function(
                    "() => document.querySelector('#dirty')?.textContent === 'Saved'"
                )
                assert json.loads(annotations.read_text())["instances"][0]["verified_ground_truth"] is True
                # Quick class changes must not carry a subtype from the old class.
                page.locator("#variant").fill("single_pole")
                page.locator("#variant").press("Tab")
                page.locator("#accept").click()
                page.locator('.quick-family[data-family="receptacle"]').click()
                assert page.locator("#variant").input_value() == "unspecified"
                assert "1 pending" in page.locator("#count").inner_text()
            finally:
                browser.close()
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=10)


def test_browser_late_wall_snap_does_not_overwrite_other_selection(tmp_path):
    """A result for box A must never be attached to box B after a selection change."""
    from PIL import Image

    image = tmp_path / "wall.png"
    Image.new("RGB", (400, 300), (245, 245, 245)).save(image)
    annotation = tmp_path / "wall_labels.json"
    annotation.write_text(json.dumps({
        "image_path": image.name, "image_width": 400, "image_height": 300,
        "coordinate_system": "original_page_pixels_top_left_y_down",
        "instances": [
            {"instance_id": "a", "symbol_family": "receptacle",
             "symbol_variant": "duplex", "bbox_xyxy": [70, 85, 105, 120],
             "review_status": "accepted", "review_source": "human",
             "verified_ground_truth": True, "uncertain": False},
            {"instance_id": "b", "symbol_family": "switch",
             "symbol_variant": "single_pole", "bbox_xyxy": [250, 85, 285, 120],
             "review_status": "accepted", "review_source": "human",
             "verified_ground_truth": True, "uncertain": False},
        ],
    }))
    walls = tmp_path / "walls.json"
    walls.write_text(json.dumps({"walls": [
        {"start": [20, 125], "end": [380, 125]},
    ]}))
    session = LabelingSession(edit=annotation, walls=walls)
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
                    "() => document.querySelector('#count')?.textContent?.includes('2 verified')"
                )
                page.locator("#items button").first.click()
                page.evaluate("""() => {
                  const realFetch = window.fetch.bind(window);
                  window.fetch = (url, options) => {
                    if (typeof url === "string" && url.startsWith("/api/snap?")) {
                      return new Promise(resolve => {
                        window.__releaseSnap = () => resolve(new Response(
                          JSON.stringify({snap: {
                            kind: "wall_centerline_intersection",
                            point: [88, 125], quality: "high"
                          }}), {
                            status: 200,
                            headers: {"Content-Type": "application/json"}
                          }));
                      });
                    }
                    return realFetch(url, options);
                  };
                }""")
                page.locator("#testWallSnap").click()
                page.wait_for_function("() => typeof window.__releaseSnap === 'function'")
                page.locator("#items button").nth(1).click()
                page.evaluate("() => window.__releaseSnap()")
                page.wait_for_function(
                    "() => document.querySelector('#message')?.textContent?.includes('Discarded stale wall snap')"
                )
                assert "2 verified" in page.locator("#count").inner_text()
                assert page.locator("#dirty").inner_text() == "Saved"
                assert "Snap preview" not in page.locator("#snapPreviewInfo").inner_text()
                assert all("snap" not in item for item in session.page_state(0)["document"]["instances"])
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
                    "() => document.querySelector('#deviceCatalogCount')?.textContent === '104'"
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
                assert page.locator("#family").input_value() == "security_device"
                assert page.locator("#variant").input_value() == "card_reader_long_range"
                assert page.locator("#disciplineBadge").inner_text() == "electrical"

                page.locator("#navDevicesBtn").click()
                page.wait_for_function("() => !document.querySelector('#deviceCatalogModal').hidden")
                page.locator("#deviceModalSearch").fill("Horn / Strobe - Wall")
                page.locator("#deviceModalGrid button", has_text="Fire Alarm Horn / Strobe - Wall Mounted").click()
                page.wait_for_function("() => document.querySelector('#deviceCatalogModal').hidden")
                assert page.locator("#family").input_value() == "fire_alarm_device"
                assert page.locator("#variant").input_value() == "horn_strobe_wall"

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
