#!/usr/bin/env python3
"""Put every label of a drawing into its one labels file.

For each PDF in ``1_blueprints/`` and ``1_blueprints/evaluation/`` this merges,
into ``devices/<stem>_<sha12>/<stem>_labels.json``:

* page JSONs from older tools and machines (``--from`` folders, searched
  recursively): the old annotator output (``work/annotator_labels``), the old
  flat label store (``work/labels``), labels pulled from the training machine
  (``work/remote_labels``: agent and model pages), and other machines'
  ``*_labels.json`` files;
* the machine proposals: the drawing's ``*_DEVICES.json`` and its SAM and
  CircuitNet runs, as pending boxes on their pages.

A page JSON counts for a PDF only when it records that PDF's SHA-256. Per
page, every box is kept once; where two overlap the more settled one stays
(verified > accepted > drawn by a person > machine proposal), so agent and
model boxes give way to a person's. Boxes a person rejected are not proposed
again. Nothing is rendered.

Dry run by default (prints what would be merged). ``--apply`` writes, after
copying every labels file it changes to ``work/merge_backups/<time>/``.
Source files are only read.

    PYTHONPATH=. python3 scripts_merge_labels.py            # what would change
    PYTHONPATH=. python3 scripts_merge_labels.py --apply
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
          only: set[str] | None = None, backup_root: Path | None = None) -> dict:
    blueprints = Path(blueprints).resolve()
    pages = collect_pages(sources)
    named = _names(pages)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    backups = Path(backup_root or DEFAULT_WORK_DIR / "merge_backups") / stamp
    report: dict = {"apply": apply, "blueprints": str(blueprints),
                    "sources": [str(s) for s in sources], "documents": [],
                    "page_files_without_pdf": 0}
    matched_shas: set[str] = set()
    for pdf in openable_pdfs(blueprints):
        if only and pdf.name.casefold() not in only:
            continue
        proposal_files = _find_proposal_sources(pdf) if with_proposals else []
        candidates = named.get(pdf.name.casefold(), set())
        if not proposal_files and not candidates:
            continue
        sha = cached_sha256(pdf)
        incoming = {index: [page for _, page in entries]
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
    parser.add_argument("--pdf", action="append", help="only this PDF file name (repeatable)")
    parser.add_argument("--no-proposals", action="store_true",
                        help="do not write DEVICES/SAM/CircuitNet boxes into the file")
    parser.add_argument("--apply", action="store_true", help="write (default: dry run)")
    args = parser.parse_args(argv)
    sources = args.sources or [DEFAULT_WORK_DIR / name for name in DEFAULT_FROM]
    report = merge(args.blueprints, [s.resolve() for s in sources], apply=args.apply,
                   with_proposals=not args.no_proposals,
                   only={n.casefold() for n in args.pdf} if args.pdf else None)
    json.dump(report, sys.stdout, indent=1)
    print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
