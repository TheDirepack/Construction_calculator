#!/usr/bin/env python3
"""Unify every label of a drawing into its one labels file (format 2).

For each PDF in ``1_blueprints/`` and ``1_blueprints/evaluation/`` this
writes ``devices/<stem>_<sha12>/<stem>_labels.json``, the one file the
annotator, the VLM labeling agents, model pre-labeling and training all use::

    {"format_version": 2, "kind": "document_labels", "json_prefix": "X",
     "document":  {"file", "path", "sha256", "page_count"},
     "sources":   [{"file", "kind", "sha256", "devices", "devices_used",
                    "devices_left_out": {reason: n}, "vlm_rejected", "model", ...}],
     "extracted": {"X_DEVICES.json": {"legend_types", "page_results",
                    "unresolved_candidates", "plan_pages_processed", ...}},
     "page_sources": [older page files merged in],
     "proposals": {"paths", "sha256"},
     "pages": {"12": {annotator page: "instances", "rejected_proposals", ...}}}

What goes in:

* every valid device of the drawing's machine/LLM outputs, as pending boxes:
  the extracted ``*_DEVICES.json`` (LLM extraction, incl. unresolved
  candidates that have a symbol box), VLM-reviewed detections
  (``work/circuitnet_refined_devices``, made here from any reviewed batch in
  ``work/sam3_review_sheets`` / ``work/circuitnet_review_zips``), cleaned
  SAM runs, CircuitNet runs and raw SAM runs never cleaned. Each box keeps
  what its source said about it (``proposal_details``: id, sheet, device code,
  attributes, confidence, verification...) and its centre / wall connection
  point as anchors. A box a VLM review dropped hides the raw box under it.
  Left out, and counted per reason in ``sources``: text-label proxies and
  title-block artifacts, mechanical/plumbing equipment, devices without a box,
  pages outside the PDF.
* page JSONs from older tools and machines (``--from``, searched
  recursively): your boxes, agent pages, model pages, other machines'
  ``*_labels.json``. A page counts only when it records this PDF's SHA-256.

Per page every box is kept once; where two overlap (IoU >= 0.5) the more
settled one stays: verified > accepted > drawn by a person > an agent's or
model's box > raw machine proposal. Boxes a person rejected are not proposed
again. Nothing is rendered.

Dry run by default. ``--apply`` writes, after copying every labels file it
changes to ``work/merge_backups/<time>/``. Source files are only read.

    PYTHONPATH=. python3 scripts_unify_labels.py            # what would change
    PYTHONPATH=. python3 scripts_unify_labels.py --apply
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
import time
from collections import defaultdict
from pathlib import Path

from annotator import document_labels, label_store
from annotator.web import (DEFAULT_BLUEPRINTS_DIR, DEFAULT_WORK_DIR, LabelingSession,
                           _find_proposal_sources, cached_sha256, openable_pdfs)

PAGE_NAME = re.compile(r"_p(\d{4,5})\.json$")
SKIP_DIRS = {"images", "exports", "legend", "marks", document_labels.CONVERTED_DIR, "label_pages",
             "merge_backups"}
DEFAULT_FROM = ("annotator_labels", "labels", "remote_labels")
REVIEW_SHEETS = ("sam3_review_sheets", "circuitnet_review_zips")
REFINED_DIR = "circuitnet_refined_devices"


def import_vlm_reviews(review_roots: list[Path], out_dir: Path, *, apply: bool) -> dict:
    """VLM review sheets (scripts_circuitnet_review_sheets.py render) whose
    batch JSONs a VLM has filled in become ``*_REFINED_DEVICES.json`` files,
    which every labels file then draws from. Batches nobody reviewed change
    nothing."""
    from scripts_circuitnet_review_sheets import apply_batch_updates_to_devices
    report = {"reviewed_pdfs": 0, "kept": 0, "removed": 0, "written": [], "unreadable": {}}
    for root in review_roots:
        if not root.is_dir():
            continue
        for folder in sorted(p for p in root.iterdir() if p.is_dir()):
            batches = []
            for path in sorted(folder.glob("*_batch_*.json")):
                try:
                    batches.append(json.loads(path.read_text(encoding="utf-8")))
                except (OSError, ValueError):
                    continue
            if not batches:
                continue
            try:
                refined = apply_batch_updates_to_devices(batches)
            except (KeyError, ValueError, TypeError) as exc:
                report["unreadable"][folder.name] = str(exc)[:200]     # fix the batch and re-run
                continue
            summary = refined.get("summary", {})
            if not summary.get("kept_devices") and not summary.get("removed_false_positives"):
                continue                       # nothing reviewed yet
            report["reviewed_pdfs"] += 1
            report["kept"] += summary.get("kept_devices", 0)
            report["removed"] += summary.get("removed_false_positives", 0)
            target = out_dir / f"{folder.name}_REFINED_DEVICES.json"
            encoded = json.dumps(refined, indent=2)
            if apply and (not target.is_file() or target.read_text(encoding="utf-8") != encoded):
                out_dir.mkdir(parents=True, exist_ok=True)
                target.write_text(encoded, encoding="utf-8")
                report["written"].append(target.name)
    return report


def _page_entries(path: Path):
    """(page index, page JSON) pairs in a file: one annotator page, or every
    page of a labels file. Anything else yields nothing."""
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError, UnicodeDecodeError):
        return
    if not isinstance(data, dict):
        return
    if data.get("kind") == document_labels.KIND and isinstance(data.get("pages"), dict):
        for key, page in data["pages"].items():
            if isinstance(page, dict) and str(key).isdigit():
                yield int(key) - 1, page
        return
    if not isinstance(data.get("instances"), list) or not data.get("source_pdf_sha256"):
        return
    index = data.get("page_index")
    if type(index) is not int:
        match = PAGE_NAME.search(path.name)
        if not match:
            return
        index = int(match.group(1)) - 1
    yield index, data


def collect_pages(folders: list[Path]) -> dict[str, dict[int, list[tuple[Path, dict]]]]:
    """PDF SHA-256 -> page index -> [(file, page JSON)] from every folder."""
    found: dict[str, dict[int, list]] = defaultdict(lambda: defaultdict(list))
    for folder in folders:
        if not folder.is_dir():
            continue
        for path in sorted(folder.rglob("*.json")):
            if SKIP_DIRS.intersection(path.relative_to(folder).parts[:-1]):
                continue
            for index, page in _page_entries(path):
                sha = page.get("source_pdf_sha256")
                if sha:
                    found[sha][index].append((path, page))
    return found


def _names(pages: dict[str, dict[int, list]]) -> dict[str, set[str]]:
    """PDF file name (casefolded) -> SHA-256s the page files name it with."""
    out: dict[str, set[str]] = defaultdict(set)
    for sha, by_page in pages.items():
        for entries in by_page.values():
            for _, page in entries:
                name = Path(str(page.get("document_id") or page.get("source_id") or "")).name
                if name:
                    out[name.casefold()].add(sha)
                    out[(name + ".pdf").casefold()].add(sha)
    return out


def merge(blueprints: Path, sources: list[Path], *, apply: bool, with_proposals: bool = True,
          only: set[str] | None = None, backup_root: Path | None = None,
          review_roots: list[Path] | None = None) -> dict:
    blueprints = Path(blueprints).resolve()
    reviews = import_vlm_reviews(list(review_roots or []), DEFAULT_WORK_DIR / REFINED_DIR, apply=apply)
    pages = collect_pages(sources)
    named = _names(pages)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    backups = Path(backup_root or DEFAULT_WORK_DIR / "merge_backups") / stamp
    report: dict = {"apply": apply, "blueprints": str(blueprints),
                    "sources": [str(s) for s in sources], "documents": [],
                    "page_files_without_pdf": 0, "vlm_reviews": reviews}
    matched_shas: set[str] = set()
    for pdf in openable_pdfs(blueprints):
        if only and pdf.name.casefold() not in only:
            continue
        proposal_files = _find_proposal_sources(pdf) if with_proposals else []
        candidates = named.get(pdf.name.casefold(), set())
        if not proposal_files and not candidates:
            continue
        sha = cached_sha256(pdf)
        incoming = {index: [dict(page, _merged_from=str(path)) for path, page in entries]
                    for index, entries in pages.get(sha, {}).items()}
        matched_shas.add(sha)
        files = sorted({str(path) for entries in pages.get(sha, {}).values() for path, _ in entries})
        row = {"pdf": str(pdf.relative_to(blueprints)), "page_files": len(files),
               "pages_from_files": len(incoming), "proposal_files": [p.name for p in proposal_files]}
        if not incoming and not proposal_files:
            continue
        if apply:
            session = LabelingSession(pdf=pdf, blueprints_dir=blueprints, source_sha256=sha)
            labels = document_labels.labels_path(session.out_dir, session.json_prefix)
            if labels.is_file():
                target = backups / session.out_dir.relative_to(blueprints) / labels.name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(labels, target)
            row.update(session.merge_pages(incoming, with_proposals=with_proposals))
            row["labels_file"] = str(labels.relative_to(blueprints))
        report["documents"].append(row)
    report["page_files_without_pdf"] = sum(
        len({str(p) for entries in by_page.values() for p, _ in entries})
        for sha, by_page in pages.items() if sha not in matched_shas)
    if apply:
        report["backups"] = str(backups) if backups.is_dir() else None
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--blueprints", type=Path, default=DEFAULT_BLUEPRINTS_DIR,
                        help="the 1_blueprints folder (default: this checkout's)")
    parser.add_argument("--from", dest="sources", type=Path, action="append",
                        help="folder of older page JSONs or labels files (repeatable; default: "
                             "work/annotator_labels, work/labels, work/remote_labels)")
    parser.add_argument("--reviews", type=Path, action="append",
                        help="folder of VLM review sheets (repeatable; default: "
                             "work/sam3_review_sheets, work/circuitnet_review_zips)")
    parser.add_argument("--pdf", action="append", help="only this PDF file name (repeatable)")
    parser.add_argument("--no-proposals", action="store_true",
                        help="do not write DEVICES/SAM/CircuitNet boxes into the file")
    parser.add_argument("--apply", action="store_true", help="write (default: dry run)")
    args = parser.parse_args(argv)
    sources = args.sources or [DEFAULT_WORK_DIR / name for name in DEFAULT_FROM]
    report = merge(args.blueprints, [s.resolve() for s in sources], apply=args.apply,
                   with_proposals=not args.no_proposals,
                   review_roots=[r.resolve() for r in (args.reviews or
                                 [DEFAULT_WORK_DIR / name for name in REVIEW_SHEETS])],
                   only={n.casefold() for n in args.pdf} if args.pdf else None)
    json.dump(report, sys.stdout, indent=1)
    print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
