"""Build or extend the label store (``annotator.label_store``) from source documents.

Two sources of documents, both safe to re-run:

* ``--from-pool POOL``: one-time migration from the old page pool. Every
  ``label_queue`` page becomes a page to label of its source PDF (located under
  ``--data-root`` by path or normalised filename). Labels, VLM assessments,
  legend crops and surveys move over, with boxes rescaled from the pool raster
  to the store's own render of the PDF page. Photos (no PDF) are copied into
  the store, since the pool holds the only copy.
* ``--scan PDF_DIR``: triage PDFs straight from disk. Pages whose text mentions
  electrical work become pages to label; PDFs with no such page are skipped.
  Evaluation and held-out PDFs are never added.

  PYTHONPATH=code .venv/bin/python code/scripts_build_label_store.py \\
      --store work/labels --data-root data/blueprints_and_libraries \\
      --from-pool work/pool --heldout work/heldout_real_pdfs.json
  ... --scan data/blueprints_and_libraries/1_blueprints      # new PDFs later
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


def _norm(name: str) -> str:
    stem = Path(name).name
    stem = stem[:-4] if stem.lower().endswith(".pdf") else stem
    return re.sub(r"[^a-z0-9]+", "_", stem.lower()).strip("_")


def heldout_names(data_root: Path, heldout: Path | None) -> set[str]:
    names: set[str] = set()
    eval_dir = data_root / "1_blueprints" / "evaluation"
    if eval_dir.is_dir():
        names.update(_norm(p.name) for p in eval_dir.rglob("*") if p.is_file())
    if heldout is not None and heldout.is_file():
        data = json.loads(heldout.read_text())
        names.update(_norm(str(x)) for x in data.get("source_pdfs", []))
    return names


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


def scan_pdfs(store: Path, pdf_dir: Path, blocked: set[str], *, dry_run: bool) -> dict:
    """Triage PDFs not yet in the store. PDFs already checked and found without
    electrical pages are remembered (by size and mtime) so daily runs stay cheap."""
    stats: dict[str, int] = defaultdict(int)
    known = {Path(d.source).resolve() for d in label_store.iter_documents(store)} if store.is_dir() else set()
    scanned_path = store / SCANNED_FILE
    scanned = json.loads(scanned_path.read_text()) if scanned_path.is_file() else {}
    for pdf in sorted(pdf_dir.rglob("*.pdf")):
        if "evaluation" in pdf.relative_to(pdf_dir).parts or _norm(pdf.name) in blocked:
            stats["skipped_heldout"] += 1
            continue
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
                store.mkdir(parents=True, exist_ok=True)
                scanned_path.write_text(json.dumps(scanned))
            continue
        stats["new_documents"] += 1
        stats["pages_to_label"] += len(targets)
        if not dry_run:
            doc = label_store.create_document(store, pdf, targets=targets,
                                              meta={"source_rel": str(pdf.relative_to(pdf_dir.parent)),
                                                    "origin": "pdf_scan"})
            (doc.folder / "outline.json").write_text(json.dumps(
                {"sha256": doc.sha256, "pages": {row["pdf_page"]: row for row in outline}}))
    return dict(stats)


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--store", type=Path, required=True)
    p.add_argument("--data-root", type=Path, required=True)
    p.add_argument("--from-pool", type=Path, help="migrate label_queue pages from this pool directory")
    p.add_argument("--scan", type=Path, help="triage PDFs under this directory")
    p.add_argument("--heldout", type=Path, help="held-out PDF manifest (never added)")
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
        blocked = heldout_names(args.data_root.resolve(), args.heldout)
        report["scan"] = scan_pdfs(store, args.scan.resolve(), blocked, dry_run=args.dry_run)
    print(json.dumps(report, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
