"""A heuristic no-match must never produce a trusted detector negative."""
from __future__ import annotations
import json
from pathlib import Path
import pytest
import fitz
from PIL import Image, ImageDraw
from io import BytesIO
from scripts_triage_pdf_symbol_pages import scan_pdfs, triage_pdf, write_json_atomic


def _new_pdf(path: Path, kind: str):
    with fitz.open() as doc:
        p = doc.new_page(width=612, height=792)
        if kind == "text":
            p.insert_text((40, 50), "ARCHITECTURAL GENERAL NOTES")
        elif kind == "electrical":
            p.insert_text((40, 50), "E2.1 ELECTRICAL LIGHTING PLAN RECEPTACLE")
            p.draw_circle((100, 100), 9)
        elif kind == "unlabeled_vector":
            p.draw_circle((100, 100), 9)
            p.draw_line((100, 91), (100, 109))
        elif kind == "scan":
            im = Image.new("RGB", (500, 500), "white")
            draw = ImageDraw.Draw(im)
            draw.arc((200, 200, 220, 220), 0, 360, fill="black", width=2)
            buffer = BytesIO()
            im.save(buffer, format="PNG")
            p.insert_image(fitz.Rect(0, 0, 612, 792), stream=buffer.getvalue())
        elif kind != "blank":
            raise ValueError(kind)
        doc.save(path)


@pytest.mark.parametrize("kind,expected", [
    ("blank", "blank_candidate"), ("text", "review_required"),
    ("electrical", "possible_symbols"), ("unlabeled_vector", "review_required"),
    ("scan", "review_required"),
])
def test_no_implicit_negatives(tmp_path, kind, expected):
    file = tmp_path / f"{kind}.pdf"
    _new_pdf(file, kind)
    page = triage_pdf(file)["pages"][0]
    assert page["suggestion"] == expected
    assert page["review_required"] is True
    assert page["verified_no_symbols"] is False
    assert page["eligible_as_supervised_negative"] is False


def test_deterministic_and_no_annotation_mutation(tmp_path):
    files = []
    for kind in ("blank", "electrical", "scan"):
        file = tmp_path / f"{kind}.pdf"
        _new_pdf(file, kind)
        files.append(file)
    first, second = scan_pdfs(files), scan_pdfs(files)
    assert first == second
    assert sum(first["counts"].values()) == 3
    target = tmp_path / "triage.json"
    write_json_atomic(target, first)
    assert json.loads(target.read_text()) == first
    assert not list(tmp_path.glob("*annotations*.json"))


def test_corrupt_pdf_fails_closed(tmp_path):
    bad = tmp_path / "bad.pdf"
    bad.write_bytes(b"not-a-pdf")
    result = scan_pdfs([bad])
    assert not result["documents"] and len(result["failures"]) == 1
    assert result["failures"][0]["verified_no_symbols"] is False
