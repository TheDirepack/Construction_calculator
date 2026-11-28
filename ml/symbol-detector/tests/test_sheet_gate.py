"""Sheet-discipline page gate read from synthetic PDF title blocks."""
from __future__ import annotations

import pytest

pymupdf = pytest.importorskip("pymupdf")

from blueprint_symbols.sheet_gate import (  # noqa: E402
    GatePolicy,
    classify_pdf,
    classify_pdf_page,
    decide_gate,
    parse_sheet_number,
)

W, H = 2448.0, 1584.0      # 34" x 22" sheet in points


def _sheet_pdf(tmp_path, name, *, number=None, title=(), notes=(), rotate=0,
               extra=(), page_size=(W, H)):
    """One-page PDF with a title block in the bottom-right corner."""
    doc = pymupdf.open()
    pw, ph = page_size
    if rotate in (90, 270):
        page = doc.new_page(width=ph, height=pw)   # unrotated media, displayed landscape
        page.set_rotation(rotate)
    else:
        page = doc.new_page(width=pw, height=ph)
    # Insert in displayed coordinates (derotate into page space).
    derot = page.derotation_matrix

    def put(x, y, text, size, rotate_text=0):
        point = pymupdf.Point(x, y) * derot
        page.insert_text(point, text, fontsize=size, rotate=(rotate_text - rotate) % 360)

    page.draw_rect(pymupdf.Rect(100, 100, 400, 400) * derot, color=(0, 0, 0))
    for i, line in enumerate(notes):
        put(150, 500 + 16 * i, line, 10)
    for i, line in enumerate(title):
        put(pw - 380, ph - 200 + 28 * i, line, 20)
    if number:
        put(pw - 300, ph - 50, number, 44)
    for x, y, text, size in extra:
        put(x, y, text, size)
    path = tmp_path / f"{name}.pdf"
    doc.save(path)
    doc.close()
    return path


def test_parse_sheet_number_accepts_designators_and_rejects_tags():
    assert parse_sheet_number("E1.1") == ("E", "E1.1")
    assert parse_sheet_number("EP-201") == ("EP", "EP-201")
    assert parse_sheet_number("FA1.01") == ("FA", "FA1.01")
    assert parse_sheet_number("Sheet No. E-101") == ("E", "E-101")
    assert parse_sheet_number("DM2.1") == ("DM", "DM2.1")
    assert parse_sheet_number("AHU-1") is None           # equipment tag
    assert parse_sheet_number("FY2023-24") is None       # fiscal year
    assert parse_sheet_number("SEE 3/A5.1") is None      # reference in running text
    assert parse_sheet_number("101") is None             # room number


def test_electrical_sheet_is_relevant(tmp_path):
    pdf = _sheet_pdf(tmp_path, "e", number="E2.1", title=("LIGHTING & POWER PLAN",))
    sheet = classify_pdf(pdf, 0)
    assert sheet.electrical_relevant is True
    assert sheet.discipline == "electrical"
    assert sheet.sheet_number == "E2.1"
    assert sheet.confidence >= 0.9
    assert decide_gate(sheet, GatePolicy(), 0.3).action == "run"


@pytest.mark.parametrize("number,title,discipline", [
    ("L1.3", "LANDSCAPE PLAN", "landscape"),
    ("G1.1", "ACCESSIBILITY DETAILS", "general"),
    ("C3.00", "GRADING PLAN", "civil"),
    ("M2.1", "MECHANICAL PLAN", "mechanical"),
])
def test_non_electrical_sheet_raises_cutoff_or_skips(tmp_path, number, title, discipline):
    pdf = _sheet_pdf(tmp_path, number.replace(".", "_"), number=number, title=(title,))
    sheet = classify_pdf(pdf, 0)
    assert sheet.electrical_relevant is False
    assert sheet.discipline == discipline
    assert sheet.sheet_number == number
    assert sheet.confidence >= 0.75
    assert title in (sheet.sheet_title or "")

    raised = decide_gate(sheet, GatePolicy(mode="raise", raised_confidence=0.9), 0.3)
    assert raised.action == "raise" and raised.confidence == pytest.approx(0.9)
    # Never lowers a caller's stricter cutoff.
    assert decide_gate(sheet, GatePolicy(mode="raise"), 0.95).confidence == pytest.approx(0.95)
    assert decide_gate(sheet, GatePolicy(mode="skip"), 0.3).action == "skip"
    assert decide_gate(sheet, GatePolicy(mode="off"), 0.3).action == "run"
    assert decide_gate(sheet, None, 0.3).action == "run"
    # Not confident enough: run at the caller's cutoff.
    strict = GatePolicy(mode="skip", min_sheet_confidence=0.99)
    assert decide_gate(sheet, strict, 0.3).action == "run"


def test_reflected_ceiling_plan_on_a_sheet_is_electrical_relevant(tmp_path):
    pdf = _sheet_pdf(tmp_path, "rcp", number="A4.1", title=("REFLECTED CEILING PLAN",))
    sheet = classify_pdf(pdf, 0)
    assert sheet.electrical_relevant is True
    assert sheet.discipline == "architectural_rcp"
    assert decide_gate(sheet, GatePolicy(mode="skip"), 0.3).action == "run"

    abbrev = classify_pdf(_sheet_pdf(tmp_path, "rcp2", number="A-201", title=("RCP - LEVEL 2",)), 0)
    assert abbrev.electrical_relevant is True
    # On civil sheets RCP is reinforced concrete pipe.
    pipe = classify_pdf(_sheet_pdf(tmp_path, "pipe", number="C5.01", title=("18in RCP STORM PROFILE",)), 0)
    assert pipe.electrical_relevant is False


def test_rotated_page_title_block_is_found(tmp_path):
    pdf = _sheet_pdf(tmp_path, "rot", number="L1.4", title=("LANDSCAPE PLAN",), rotate=270)
    with pymupdf.open(pdf) as doc:
        assert doc[0].rotation == 270
        sheet = classify_pdf_page(doc[0])
    assert sheet.sheet_number == "L1.4"
    assert sheet.electrical_relevant is False and sheet.confidence >= 0.75


def test_no_text_layer_is_unknown_and_never_gated(tmp_path):
    pdf = _sheet_pdf(tmp_path, "scan")          # drawing only, no text
    assert classify_pdf(pdf, 0) is None
    for mode in ("raise", "skip"):
        decision = decide_gate(None, GatePolicy(mode=mode), 0.3)
        assert decision.action == "run" and decision.confidence == pytest.approx(0.3)
        assert "unknown" in decision.reason


def test_text_without_sheet_number_is_unknown_not_gated(tmp_path):
    pdf = _sheet_pdf(tmp_path, "notes", notes=("GENERAL NOTES", "ALL WORK PER CODE"))
    sheet = classify_pdf(pdf, 0)
    assert sheet is not None and sheet.electrical_relevant is None
    assert sheet.sheet_number is None
    assert decide_gate(sheet, GatePolicy(mode="skip"), 0.3).action == "run"


def test_non_electrical_title_mentioning_electrical_work_is_not_gated(tmp_path):
    pdf = _sheet_pdf(tmp_path, "aelec", number="A2.1", title=("ELECTRICAL COORDINATION PLAN",))
    sheet = classify_pdf(pdf, 0)
    assert sheet.electrical_relevant is True
    assert decide_gate(sheet, GatePolicy(mode="skip"), 0.3).action == "run"


def test_detail_labels_and_device_vocabulary_do_not_gate(tmp_path):
    # Big detail callouts "A1"/"A2" in the bottom strip, real number small.
    pdf = _sheet_pdf(
        tmp_path, "details",
        extra=((200, H - 60, "A1", 30), (900, H - 60, "A2", 30),
               (W - 200, H - 40, "E-501", 10)),
        notes=("PANELBOARD CIRCUIT LIST", "RECEPTACLE AT 18 IN AFF",
               "LUMINAIRE TYPE A", "GFCI RECEPTACLE"),
    )
    sheet = classify_pdf(pdf, 0)
    assert decide_gate(sheet, GatePolicy(mode="skip"), 0.3).action == "run"

    # A plumbing sheet whose notes are full of electrical devices.
    noisy = classify_pdf(_sheet_pdf(
        tmp_path, "noisy", number="P2.1", title=("PLUMBING PLAN",),
        notes=("PROVIDE GFCI RECEPTACLE", "JUNCTION BOX FOR WATER HEATER",
               "DISCONNECT SWITCH BY EC", "PANELBOARD LP-1"),
    ), 0)
    assert noisy.electrical_relevant is False and noisy.confidence < 0.75
    assert decide_gate(noisy, GatePolicy(mode="skip"), 0.3).action == "run"


def test_gate_policy_validation_and_serialization():
    assert GatePolicy().mode == "off"          # never filter sheets unless asked
    with pytest.raises(ValueError):
        GatePolicy(mode="drop")
    with pytest.raises(ValueError):
        GatePolicy(raised_confidence=1.0)
    decision = decide_gate({"discipline": "landscape", "electrical_relevant": False,
                            "sheet_number": "L1.3", "sheet_title": "LANDSCAPE PLAN",
                            "confidence": 0.9, "reason": "test"}, GatePolicy(mode="raise"), 0.25)
    d = decision.to_dict()
    assert d["action"] == "raise" and d["sheet"]["sheet_number"] == "L1.3"
    assert d["policy"]["mode"] == "raise"
