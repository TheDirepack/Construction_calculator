"""Build or extend the label store (``annotator.label_store``) from source documents.

Two sources of documents, both safe to re-run:

* ``--from-pool POOL``: one-time migration from the old page pool. Every
  ``label_queue`` page becomes a page to label of its source PDF (located under
  ``--data-root`` by path or normalised filename). Labels, VLM assessments,
  legend crops and surveys move over, with boxes rescaled from the pool raster
  to the store's own render of the PDF page. Photos (no PDF) are copied into
  the store, since the pool holds the only copy.
* ``--scan BLUEPRINTS``: triage the training drawings (``1_blueprints/*.pdf``)
  and the evaluation drawings (``1_blueprints/evaluation/*.pdf``) straight
  from disk (``blueprint_symbols.blueprint_sets``). Pages whose text mentions
  electrical work become pages to label; PDFs with no such page are skipped.
  Pages with extracted ``*_DEVICES.json`` boxes (the ``devices/`` folder
  beside each PDF) are always pages to label: the annotator and the agents
  open them with those boxes as proposals.
  Both sets are labeled; only training leaves evaluation drawings out, decided
  at training time by which folder the PDF is in.

  PYTHONPATH=code .venv/bin/python code/scripts_build_label_store.py \\
      --store data/blueprints_and_libraries/1_blueprints \\
      --data-root data/blueprints_and_libraries \\
      --scan data/blueprints_and_libraries/1_blueprints

``--store`` is the ``1_blueprints`` folder: each drawing's labels go to the
``devices`` folder beside it (``annotator.label_store``).
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

from annotator import label_store, vlm_tools
from annotator.web import LabelingError, RevisionConflict

PHOTO_SUFFIXES = {".jpg", ".jpeg", ".png", ".tif", ".tiff", ".webp"}


# --------------------------------------------------------------------------
# geometry: pool raster -> store render
# --------------------------------------------------------------------------

def _scale_xy(xy, sx: float, sy: float):
    return [round(float(xy[0]) * sx, 3), round(float(xy[1]) * sy, 3)]


def scale_instance(item: dict, sx: float, sy: float) -> dict:
    out = json.loads(json.dumps(item))
    if isinstance(out.get("bbox_xyxy"), list) and len(out["bbox_xyxy"]) == 4:
        x0, y0, x1, y1 = out["bbox_xyxy"]
        out["bbox_xyxy"] = [round(x0 * sx, 3), round(y0 * sy, 3), round(x1 * sx, 3), round(y1 * sy, 3)]
    rot = out.get("bbox_rotated")
    if isinstance(rot, dict):
        scale = (sx + sy) / 2
        rot.update({"cx": round(rot["cx"] * sx, 3), "cy": round(rot["cy"] * sy, 3),
                    "w": round(rot["w"] * scale, 3), "h": round(rot["h"] * scale, 3)})
    for anchor in (out.get("anchors") or {}).values():
        if isinstance(anchor, dict) and isinstance(anchor.get("xy"), list):
            anchor["xy"] = _scale_xy(anchor["xy"], sx, sy)
    snap = out.get("snap_vector")
    if isinstance(snap, dict):
        for key in ("origin", "origin_xy", "xy"):
            if isinstance(snap.get(key), list) and len(snap[key]) == 2:
                snap[key] = _scale_xy(snap[key], sx, sy)
    return out


def migrate_page(doc: label_store.Document, page: int, old: dict) -> str:
    """Copy one pool page's labels into the store page; returns what happened."""
    instances = old.get("instances") or []
    rejected = old.get("rejected_proposals") or []
    assessment = old.get("vlm_assessment")
    if not (instances or rejected or assessment):
        return "empty"
    target = doc.page_json(page)
    if target.is_file():
        return "exists"
    session, index, _ = label_store.open_page(target)
    data, revision, _ = session.page_document(index)
    sx = int(data["image_width"]) / float(old["image_width"])
    sy = int(data["image_height"]) / float(old["image_height"])
    metadata = {"vlm_assessment": assessment} if assessment else None
    session.save(index, revision=revision,
                 instances=[scale_instance(i, sx, sy) for i in instances],
                 rejected=[scale_instance(r, sx, sy) for r in rejected], metadata=metadata)
    return "migrated"


def _old_doc_key(source_path: str) -> str:
    stem = re.sub(r"[^A-Za-z0-9]+", "_", str(source_path)).strip("_")
    return stem[-120:] or "unknown"


def migrate_legend(pool: Path, source_path: str, doc: label_store.Document,
                   scales: dict[str, tuple[float, float, int]]) -> int:
    old = pool / "vlm_legends" / _old_doc_key(source_path)
    if not old.is_dir():
        return 0
    legend = doc.folder / vlm_tools.LEGEND_DIR
    moved = 0
    if (old / "legend.json").is_file() and not (legend / "legend.json").is_file():
        legend.mkdir(parents=True, exist_ok=True)
        entries = json.loads((old / "legend.json").read_text()).get("entries", [])
        for entry in entries:
            png = old / f"{entry['id']}.png"
            if png.is_file():
                (legend / png.name).write_bytes(png.read_bytes())
            sx, sy, page = scales.get(str(entry.get("from_page")), (1.0, 1.0, None))
            if page is not None:
                entry["from_page"] = str(doc.page_json(page))
                entry["box"] = [round(entry["box"][0] * sx, 1), round(entry["box"][1] * sy, 1),
                                round(entry["box"][2] * sx, 1), round(entry["box"][3] * sy, 1)]
        (legend / "legend.json").write_text(json.dumps({"entries": entries}, indent=1))
        moved = len(entries)
    survey = old / "survey.json"
    if survey.is_file() and not doc.survey_path.is_file():
        record = json.loads(survey.read_text())
        record.setdefault("label_pages", [])
        record.setdefault("skip_pages", [])
        doc.survey_path.write_text(json.dumps(record, indent=1))
    return moved


def from_pool(store: Path, pool: Path, data_root: Path, *, dry_run: bool) -> dict:
    queue = pool / "label_queue"
    roots = [data_root, data_root.parent / "pdf_sources"]
    groups: dict[str, list[tuple[Path, dict]]] = defaultdict(list)
    for path in sorted(queue.glob("*.json")):
        data = json.loads(path.read_text())
        source = str((data.get("pool") or {}).get("source_path") or "")
        groups[source].append((path, data))
    stats: dict[str, int] = defaultdict(int)
    for source, pages in sorted(groups.items()):
        first = pages[0][1]
        meta = {"source_rel": source, "source_id": first.get("source_id"),
                "project_family_id": first.get("project_family_id"), "origin": "pool_label_queue"}
        suffix = Path(source).suffix.lower()
        if suffix == ".pdf":
            pdf = vlm_tools.find_pdf(roots, source)
            if pdf is None:
                print(f"missing PDF, skipped: {source}", file=sys.stderr)
                stats["missing_pdf"] += 1
                continue
            targets = {int(d["pool"]["page"]): str(d["pool"].get("role_reason") or "pool label queue")
                       for _, d in pages}
            stats["pdf_documents"] += 1
            stats["pages_to_label"] += len(targets)
            if dry_run:
                continue
            doc = label_store.create_document(store, pdf, targets=targets, meta=meta)
            scales: dict[str, tuple[float, float, int]] = {}
            for path, data in pages:
                page = int(data["pool"]["page"])
                try:
                    result = migrate_page(doc, page, data)
                except (LabelingError, RevisionConflict, ValueError) as exc:
                    print(f"could not migrate {path.name}: {exc}", file=sys.stderr)
                    result = "failed"
                stats[f"pages_{result}"] += 1
                if result in ("migrated", "exists"):
                    new = json.loads(doc.page_json(page).read_text())
                    scales[str(path)] = (new["image_width"] / data["image_width"],
                                         new["image_height"] / data["image_height"], page)
            stats["legend_entries"] += migrate_legend(pool, source, doc, scales)
        elif suffix in PHOTO_SUFFIXES:
            for path, data in pages:
                image = queue / data["image_path"]
                stats["photo_documents"] += 1
                if dry_run:
                    continue
                doc = label_store.create_document(store, image, meta=meta, image_page=data)
                stats["legend_entries"] += migrate_legend(pool, source, doc, {})
        else:
            stats[f"skipped_{suffix.lstrip('.') or 'unknown'}"] += len(pages)
    return dict(stats)


SCANNED_FILE = "scanned_pdfs.json"


def _meta(pdf: Path, sets, origin: str) -> dict:
    # "evaluation" is informational (the annotator shows it); training decides
    # from the folder at the time it runs.
    return {"source_rel": str(pdf.relative_to(sets.root.parent)), "origin": origin,
            "evaluation": sets.role(pdf) == "evaluation"}


def _drawings(sets) -> list[Path]:
    return sets.pdfs("train") + sets.pdfs("evaluation")


def scan_pdfs(store: Path, sets, *, dry_run: bool) -> dict:
    """Triage PDFs not yet in the store. PDFs already checked and found without
    electrical pages are remembered (by size and mtime) so daily runs stay cheap."""
    stats: dict[str, int] = defaultdict(int)
    known = {Path(d.source).resolve() for d in label_store.iter_documents(store, skip_missing=True)} if store.is_dir() else set()
    scanned_path = label_store.state_dir(store) / SCANNED_FILE
    scanned = json.loads(scanned_path.read_text()) if scanned_path.is_file() else {}
    for pdf in _drawings(sets):
        if pdf.resolve() in known:
            stats["already_in_store"] += 1
            continue
        stat = pdf.stat()
        if scanned.get(str(pdf.resolve())) == [stat.st_size, stat.st_mtime_ns]:
            stats["already_scanned"] += 1
            continue
        try:
            outline = vlm_tools.document_outline(pdf, max_pages=2000)
        except Exception as exc:                                 # unreadable PDF
            print(f"unreadable, skipped: {pdf.name}: {exc}", file=sys.stderr)
            stats["unreadable"] += 1
            continue
        targets = {row["pdf_page"]: "text:" + ",".join(row["flags"]) for row in outline
                   if "electrical" in row["flags"]}
        if not targets:
            stats["no_electrical_pages"] += 1
            scanned[str(pdf.resolve())] = [stat.st_size, stat.st_mtime_ns]
            if not dry_run:
                scanned_path.parent.mkdir(parents=True, exist_ok=True)
                scanned_path.write_text(json.dumps(scanned))
            continue
        stats["new_documents"] += 1
        stats["pages_to_label"] += len(targets)
        if not dry_run:
            doc = label_store.create_document(store, pdf, targets=targets,
                                              meta=_meta(pdf, sets, "pdf_scan"))
            (doc.folder / "outline.json").write_text(json.dumps(
                {"sha256": doc.sha256, "pages": {row["pdf_page"]: row for row in outline}}))
    return dict(stats)


def devices_pages(sets) -> dict[Path, dict[int, str]]:
    """PDF -> {page: reason} for pages with extracted DEVICES.json boxes
    (label_store.devices_json_for: the same files the annotator proposes from)."""
    from blueprint_symbols.label_schema import device_for_raw_type, devices_skip_reason
    out: dict[Path, dict[int, str]] = defaultdict(dict)
    for pdf in _drawings(sets):
        path = label_store.devices_json_for(pdf)
        if path is None:
            continue
        counts: dict[int, int] = defaultdict(int)
        for dev in json.loads(path.read_text()).get("devices") or []:
            page = dev.get("pdf_page") or dev.get("page")
            if (isinstance(page, int) and page >= 1 and not devices_skip_reason(dev)
                    and device_for_raw_type(dev.get("device_type") or dev.get("symbol_name"))):
                counts[page] += 1
        for page, n in counts.items():
            out[pdf][page] = f"devices_json:{n}"
    return out


def add_devices_pages(store: Path, sets, *, dry_run: bool) -> dict:
    stats: dict[str, int] = defaultdict(int)
    for pdf, targets in sorted(devices_pages(sets).items()):
        stats["documents"] += 1
        stats["pages"] += len(targets)
        if sets.role(pdf) == "evaluation":
            stats["evaluation_documents"] += 1
        if not dry_run:
            label_store.create_document(store, pdf, targets=targets,
                                        meta=_meta(pdf, sets, "devices_json"))
    return dict(stats)


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--store", type=Path, required=True)
    p.add_argument("--data-root", type=Path, required=True)
    p.add_argument("--from-pool", type=Path, help="migrate label_queue pages from this pool directory")
    p.add_argument("--scan", type=Path,
                   help="the 1_blueprints folder (or data root): triage its training and evaluation PDFs")
    p.add_argument("--dry-run", action="store_true")
    args = p.parse_args(argv)
    if not (args.from_pool or args.scan):
        p.error("give --from-pool and/or --scan")
    store = args.store.resolve()
    report = {}
    if args.from_pool:
        report["from_pool"] = from_pool(store, args.from_pool.resolve(), args.data_root.resolve(),
                                        dry_run=args.dry_run)
    if args.scan:
        from blueprint_symbols.blueprint_sets import blueprint_sets
        sets = blueprint_sets(args.scan)
        report["scan"] = scan_pdfs(store, sets, dry_run=args.dry_run)
        report["devices_json"] = add_devices_pages(store, sets, dry_run=args.dry_run)
    print(json.dumps(report, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
