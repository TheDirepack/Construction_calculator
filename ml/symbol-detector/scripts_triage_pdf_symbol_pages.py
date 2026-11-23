"""Conservative PDF page-presence triage. Never generates supervised negatives.

Usage:
    python scripts_triage_pdf_symbol_pages.py drawings/ --output review_queue.json

Page absence cannot be certified by OCR, sheet titles, image matching or a
detector with zero predictions. All results require manual full-page review
before annotator JSON may set verified_no_symbols=true. Drawn/scanned pages
with no extractable text stay unverified, including structural/architectural
sheets. This tool creates a separate candidate index, not training labels.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
from typing import Any


# High-recall *positive risk* hints. Not a proof of object presence or absence.
SHEET_FILENAME_RISK = re.compile(r"^(?:E|EL|EP|ED|ES|FA|FP|T|M|P)[._-]?\d+(?:[._-]\d+)?[A-Z]?$", re.I)

SYMBOL_RISK = re.compile(
    r"\b(?:electrical|lighting|luminaire|receptacles?|outlets?|"
    r"switch(?:es|ing)?|fire\s*alarms?|smoke\s*detect(?:or|ion)|"
    r"power\s*(?:plan|distribution|panel)|panelboards?|"
    r"junction\s*boxes?|disconnect(?:s)?|"
    r"emergency\s*(?:light|exit)|exit\s*signs?|"
    r"communications?|telecom|data\s*devices?|"
    r"devices?\s*legend|symbol\s*(?:legend|key|schedule)|"
    r"GFCI|GFI|FACP|CCTV|homeruns?)\b",
    re.IGNORECASE,
)


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _has_page_annotations(page: Any) -> bool:
    # Annotations and widgets have independent visible appearance streams.
    for getter in (page.annots, page.widgets):
        iterator = getter()
        if iterator is not None and next(iterator, None) is not None:
            return True
    return False


def triage_page(page: Any, *, source_stem: str = "") -> dict[str, Any]:
    text = page.get_text("text")
    matches = sorted({hit.group(0).casefold() for hit in SYMBOL_RISK.finditer(text)})[:32]
    # Useful for single-page raster PDFs like E2.1.pdf and FA-02.pdf.
    # It only raises review priority; it cannot prove anything absent.
    filename_risk = bool(SHEET_FILENAME_RISK.fullmatch(source_stem))
    content = bool(page.get_contents())
    annotations = _has_page_annotations(page)
    # Images can have no extractable text. Inline images not listed here still
    # have a nonempty PDF content stream and cannot be marked as blank.
    image_resources = bool(page.get_images(full=True))
    completely_empty = not content and not annotations and not image_resources and not text.strip()
    if completely_empty:
        suggestion, reasons = "blank_candidate", ["no_content_stream_or_visible_annotation"]
    elif matches or filename_risk:
        suggestion, reasons = "possible_symbols", (
            (["electrical_or_symbol_risk_text"] if matches else []) +
            (["sheet_filename_risk"] if filename_risk else [])
        )
    else:
        suggestion, reasons = "review_required", ["drawn_or_scanned_content_cannot_be_excluded"]
    if image_resources:
        reasons.append("embedded_raster_image")
    if annotations:
        reasons.append("page_annotation_or_widget")
    if content and not text.strip():
        reasons.append("no_extractable_text")
    return {
        "page_index": int(page.number),
        "suggestion": suggestion,
        "review_required": True,
        "verified_no_symbols": False,
        "eligible_as_supervised_negative": False,
        "reasons": reasons,
        "risk_terms": matches,
        "sheet_filename_risk": filename_risk,
        "has_page_content_stream": content,
        "has_embedded_images": image_resources,
        "has_page_annotations": annotations,
        "text_characters": len(text),
    }


def triage_pdf(pdf_path: Path) -> dict[str, Any]:
    try:
        import fitz
    except ImportError as exc:
        raise RuntimeError("PyMuPDF is required: pip install pymupdf") from exc
    path = pdf_path.resolve()
    with fitz.open(path) as doc:
        if not doc.is_pdf or doc.needs_pass:
            raise ValueError(f"not a readable PDF: {path}")
        pages = []
        for page in doc:
            try:
                pages.append(triage_page(page, source_stem=path.stem))
            except Exception as exc:
                pages.append({
                    "page_index": page.number,
                    "suggestion": "review_required",
                    "review_required": True,
                    "verified_no_symbols": False,
                    "eligible_as_supervised_negative": False,
                    "reasons": ["page_decode_failed"],
                    "error": f"{type(exc).__name__}: {exc}",
                })
    return {
        "source_pdf": str(path),
        "source_pdf_sha256": file_sha256(path),
        "pages": pages,
    }


def discover_pdfs(inputs: list[Path]) -> list[Path]:
    files: set[Path] = set()
    for item in inputs:
        if item.is_dir():
            files.update(p.resolve() for p in item.rglob("*")
                         if p.is_file() and p.suffix.casefold() == ".pdf")
        elif item.is_file() and item.suffix.casefold() == ".pdf":
            files.add(item.resolve())
        else:
            raise ValueError(f"not an existing PDF or directory: {item}")
    if not files:
        raise ValueError("no PDFs found")
    return sorted(files)


def scan_pdfs(paths: list[Path]) -> dict[str, Any]:
    docs = []
    failures = []
    counts: Counter[str] = Counter()
    for path in paths:
        try:
            result = triage_pdf(path)
        except Exception as exc:
            failures.append({"source_pdf": str(path.resolve()),
                             "error": f"{type(exc).__name__}: {exc}",
                             "verified_no_symbols": False})
            continue
        docs.append(result)
        counts.update(row["suggestion"] for row in result["pages"])
    return {
        "format_version": 1,
        "purpose": "page_symbol_presence_review_queue",
        "safety_policy": "No automated negative assertions. Only full-page human review sets verified_no_symbols true.",
        "documents": docs,
        "failures": failures,
        "counts": dict(counts),
        "training_labels_written": False,
    }


def write_json_atomic(path: Path, data: dict, *, overwrite: bool = False) -> None:
    if path.exists() and not overwrite:
        raise FileExistsError(f"refusing to overwrite existing triage report: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = (json.dumps(data, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    fd, name = tempfile.mkstemp(prefix="." + path.name + ".", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inputs", type=Path, nargs="+", help="PDFs or recursive PDF directories")
    parser.add_argument("--output", required=True, type=Path,
                        help="Review suggestions JSON. Does not edit annotation JSONs.")
    parser.add_argument("--overwrite", action="store_true",
                        help="explicitly replace an existing triage report (never training annotations)")
    args = parser.parse_args(argv)
    result = scan_pdfs(discover_pdfs(args.inputs))
    write_json_atomic(args.output, result, overwrite=args.overwrite)
    print(json.dumps({
        "documents": len(result["documents"]),
        "failures": len(result["failures"]),
        "pages": sum(result["counts"].values()),
        "suggestions": result["counts"],
        "verified_negatives_created": 0,
        "output": str(args.output),
    }, indent=2))
    return 0 if not result["failures"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
