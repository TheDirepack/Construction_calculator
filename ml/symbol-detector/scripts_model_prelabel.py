"""Pre-label the label store with a fine-tuned detector, as reviewable proposals.

Runs a post-trained Ultralytics checkpoint over the pages to label of every
document in the label store (``annotator.label_store``; or only the documents
in some batch files), rendering each page straight from its PDF, with tiled
multi-scale inference, and writes its detections into each page as
**unreviewed model proposals**. Humans (in the
annotator) and the VLM agents then accept, fix or delete them; nothing here is
ground truth, and ``dataset_manifest`` keeps ignoring these boxes until a
human accepts them.

Rules this script follows:

* Writes go through ``LabelingSession.save`` (revision check, validation,
  atomic write), exactly like the browser and the agent tools.
* Accepted (human-verified) boxes are never touched. A re-run with the same
  ``--model-tag`` replaces only that model's still-unreviewed proposals.
* A detection is dropped when it overlaps an existing box or a box a human
  already **rejected** on that page, so rejected mistakes are not re-proposed.
* Every drawing is labeled, the evaluation set included: the split between
  training and evaluation only matters when training (``label_store.labeled_pages``).
* Pages attested as verified symbol-free are skipped.

Outputs (under ``--out``): ``summary.json`` with per-page counts, and
``review_priority.txt``, a batch file (store document folders, the format of
``work/labels/batches``) that orders documents by how many uncertain
detections they carry, so review time goes where the model is least sure.

Example (on the training machine, after phase-2 training):

  PYTHONPATH=code .venv/bin/python code/scripts_model_prelabel.py \\
      --weights work/phase2_v3/runs/phase2_mixed_ssl_v3/weights/best.pt \\
      --store work/labels --out work/model_proposals --device 0
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import time
from collections import defaultdict
from pathlib import Path

import cv2
import numpy as np

from blueprint_symbols.multiscale import plan_pyramid_tiles, sample_tile, touches_inner_edge
from annotator import label_store
from blueprint_symbols.label_schema import CLASS_DEVICE, device_for_class, iou as _iou, pending_instance
from blueprint_symbols.pseudolabel import consolidate_predictions

# Detector classes are catalog devices (``switch.three_way``), mapped back by
# label_schema.device_for_class. Former coarse classes start as the family with
# variant unspecified. Override or extend with --class-map.
DEFAULT_CLASS_MAP: dict[str, tuple[str, str]] = dict(CLASS_DEVICE)
SAFE_TAG = re.compile(r"[^A-Za-z0-9_.-]+")
MID_CONFIDENCE = (0.25, 0.60)


# --------------------------------------------------------------------------
# inference
# --------------------------------------------------------------------------

def predict_page(model, image: np.ndarray, *, scales: tuple[float, ...], confidence: float,
                 tile_size: int = 512, overlap: int = 160, device: str = "0",
                 batch: int = 16) -> tuple[list[dict], dict]:
    """Tiled multi-scale detections on one page, merged across tiles/scales.

    Returns consolidated candidates with ``symbol_family`` set to the model's
    own class name (mapping to the annotator vocabulary happens later).
    """
    h, w = image.shape[:2]
    names = {int(k): str(v) for k, v in model.names.items()}
    tiles = plan_pyramid_tiles(w, h, tile_size=tile_size, overlap=overlap, source_scales=scales)
    raw: list[dict] = []
    for start in range(0, len(tiles), batch):
        chunk = tiles[start:start + batch]
        patches = [sample_tile(image, tile) for tile in chunk]
        results = model.predict(source=patches, conf=confidence, device=device,
                                imgsz=tile_size, verbose=False)
        for tile, result in zip(chunk, results):
            boxes = getattr(result, "boxes", None)
            if boxes is None or len(boxes) == 0:
                continue
            xyxy = boxes.xyxy.cpu().numpy()
            classes = boxes.cls.cpu().numpy().astype(int)
            scores = boxes.conf.cpu().numpy()
            for box, cls, score in zip(xyxy, classes, scores):
                if touches_inner_edge(tile, tuple(float(v) for v in box)):
                    continue        # cut by this tile's edge; an overlapping tile sees it whole
                bb = list(tile.to_page_box(tuple(float(v) for v in box)))
                bb = [max(0.0, min(w, bb[0])), max(0.0, min(h, bb[1])),
                      max(0.0, min(w, bb[2])), max(0.0, min(h, bb[3]))]
                if bb[2] - bb[0] < 2 or bb[3] - bb[1] < 2:
                    continue
                raw.append({"symbol_family": names.get(int(cls), str(int(cls))),
                            "bbox_xyxy": bb, "confidence": float(score),
                            "source_scale": float(tile.source_scale)})
    # Cross-class suppression waits until classes are mapped to catalog devices
    # (merge_into_page dedups across classes): an unmapped model class must not
    # swallow a mapped one here.
    merged = consolidate_predictions(raw, min_confidence=confidence, cross_class_iou=None) if raw else []
    return merged, {"tiles": len(tiles), "raw_detections": len(raw)}


# --------------------------------------------------------------------------
# merging into a page
# --------------------------------------------------------------------------

def is_model_proposal(item: dict, tag: str) -> bool:
    return (item.get("review_source") == "model" and item.get("model_tag") == tag
            and item.get("review_status") == "unreviewed")


def merge_into_page(doc: dict, detections: list[dict], *, tag: str, model_info: dict,
                    class_map: dict[str, tuple[str, str]], width: int, height: int,
                    overlap_iou: float = 0.45) -> tuple[list[dict], dict]:
    """New instance list: existing boxes kept, this model's old proposals
    replaced, new detections added unless they duplicate a kept or rejected box."""
    existing = doc.get("instances") or []
    kept = [item for item in existing if not is_model_proposal(item, tag)]
    rejected = [r.get("bbox_xyxy") for r in (doc.get("rejected_proposals") or [])
                if isinstance(r.get("bbox_xyxy"), list) and len(r["bbox_xyxy"]) == 4]
    blockers = [[float(v) for v in item["bbox_xyxy"]] for item in kept] + \
               [[float(v) for v in box] for box in rejected]
    stats = {"replaced": len(existing) - len(kept), "added": 0, "dup_existing": 0,
             "dup_rejected": 0, "unmapped_class": 0, "mid_confidence": 0}
    # Rejected boxes keep their IDs; a new proposal must not reuse one.
    used = {str(item.get("instance_id")) for item in kept} | {
        str(r.get("instance_id")) for r in (doc.get("rejected_proposals") or [])}
    safe = SAFE_TAG.sub("_", tag)[:60]
    new_items = []
    counter = 1
    for det in sorted(detections, key=lambda d: -d["confidence"]):
        mapped = class_map.get(det["symbol_family"]) or device_for_class(det["symbol_family"])
        if mapped is None:
            stats["unmapped_class"] += 1
            continue
        box = [max(0.0, min(float(width), det["bbox_xyxy"][0])),
               max(0.0, min(float(height), det["bbox_xyxy"][1])),
               max(0.0, min(float(width), det["bbox_xyxy"][2])),
               max(0.0, min(float(height), det["bbox_xyxy"][3]))]
        if box[2] - box[0] < 2 or box[3] - box[1] < 2:
            continue
        hits_kept = any(_iou(box, b) >= overlap_iou for b in blockers[:len(kept)])
        hits_rejected = any(_iou(box, b) >= overlap_iou for b in blockers[len(kept):])
        if hits_kept or hits_rejected:
            stats["dup_existing" if hits_kept else "dup_rejected"] += 1
            continue
        if any(_iou(box, other["bbox_xyxy"]) >= overlap_iou for other in new_items):
            continue
        while f"m_{safe}_{counter}" in used:
            counter += 1
        ident = f"m_{safe}_{counter}"
        used.add(ident)
        family, variant = mapped
        conf = round(float(det["confidence"]), 4)
        if MID_CONFIDENCE[0] <= conf < MID_CONFIDENCE[1]:
            stats["mid_confidence"] += 1
        new_items.append(pending_instance(
            ident, family, variant, [round(v, 2) for v in box], review_source="model",
            model_tag=tag, model_confidence=conf, model_class=det["symbol_family"],
            model_supporting_detections=int(det.get("supporting_detections", 1)),
            model_scales=det.get("independent_scales", []), model_info=model_info))
    stats["added"] = len(new_items)
    return kept + new_items, stats


# --------------------------------------------------------------------------
# page selection
# --------------------------------------------------------------------------

def list_pages(store: Path | None, batches: list[Path]):
    """(document, 1-based page) pairs to predict: each document's pages to label."""
    from annotator import label_store
    folders: list[Path] = []
    for batch in batches:
        folders.extend(label_store.batch_documents(batch))
    if store is not None:
        folders.extend(d.folder for d in label_store.iter_documents(store, skip_missing=True))
    seen, out = set(), []
    for folder in folders:
        key = str(Path(folder).resolve())
        if key in seen:
            continue
        seen.add(key)
        doc = label_store.load_document(folder)
        out.extend((doc, page) for page in doc.pages_to_label())
    return out


def load_class_map(path: Path | None) -> dict[str, tuple[str, str]]:
    mapping = dict(DEFAULT_CLASS_MAP)
    if path is not None:
        raw = json.loads(path.read_text())
        if not isinstance(raw, dict):
            raise ValueError("--class-map must be a JSON object")
        for model_class, target in raw.items():
            if target is None:
                mapping.pop(model_class, None)          # explicitly drop a class
            elif isinstance(target, str):
                mapping[model_class] = (target, "unspecified")
            elif isinstance(target, list) and len(target) == 2:
                mapping[model_class] = (str(target[0]), str(target[1]))
            else:
                raise ValueError(f"bad class map entry for {model_class!r}")
    return mapping


def write_priority(rows: list[dict], path: Path) -> None:
    """Batch file of document folders, most uncertain detections first. The
    per-page order is in summary.json (``mid_confidence`` per page)."""
    by_doc: dict[str, list[dict]] = defaultdict(list)
    for row in rows:
        by_doc[row["document"]].append(row)
    def doc_score(items):
        return sum(r["mid_confidence"] for r in items) + 0.1 * sum(r["added"] for r in items)
    lines = [folder for folder, items in sorted(by_doc.items(), key=lambda kv: -doc_score(kv[1]))]
    path.write_text("\n".join(lines) + "\n")


# --------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------

def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def run(args, model=None) -> dict:
    from annotator import label_store
    from annotator.web import RevisionConflict

    pages = list_pages(args.store, args.batch or [])
    if args.limit:
        pages = pages[:args.limit]
    if not pages:
        raise SystemExit("no pages to label in the selected documents")
    class_map = load_class_map(args.class_map)

    weights = Path(args.weights).resolve()
    if not weights.is_file():
        raise SystemExit(f"weights not found: {weights}")
    weights_sha = _sha256(weights)
    tag = args.model_tag or f"{weights.parent.parent.name or weights.stem}-{weights_sha[:8]}"
    model_info = {"weights": str(weights), "weights_sha256": weights_sha,
                  "confidence_threshold": args.conf, "scales": list(args.scales),
                  "run_at": time.strftime("%Y-%m-%dT%H:%M:%S")}

    if model is None:
        from ultralytics import YOLO
        model = YOLO(str(weights))
    unmapped = sorted({str(n) for n in model.names.values()} - set(class_map))
    if unmapped:
        print(f"warning: model classes without an annotator mapping are dropped: {unmapped}",
              file=sys.stderr)

    out_dir = Path(args.out) / SAFE_TAG.sub("_", tag)
    out_dir.mkdir(parents=True, exist_ok=True)
    summary_path = out_dir / "summary.json"
    previous = json.loads(summary_path.read_text()) if summary_path.is_file() else {"pages": {}}
    rows = list(previous.get("pages", {}).values())
    done = set(previous.get("pages", {}))

    totals = defaultdict(int)
    started = time.time()
    for number, (document, page_number) in enumerate(pages, start=1):
        page = document.page_json(page_number)
        key = str(page)
        if key in done and not args.force:
            totals["resumed"] += 1
            continue
        session, index, _ = label_store.open_page(page)
        doc, revision, image_path = session.page_document(index)
        if doc.get("verified_no_symbols"):
            totals["skipped_verified_negative"] += 1
            continue
        image = cv2.imread(str(image_path), cv2.IMREAD_COLOR)
        if image is None:
            totals["unreadable"] += 1
            continue
        detections, pstats = predict_page(
            model, image, scales=tuple(args.scales), confidence=args.conf,
            device=args.device, batch=args.tile_batch)
        instances, mstats = merge_into_page(
            doc, detections, tag=tag, model_info=model_info, class_map=class_map,
            width=int(doc["image_width"]), height=int(doc["image_height"]))
        if not args.dry_run:
            try:
                session.save(index, revision=revision, instances=instances,
                             rejected=doc.get("rejected_proposals") or [])
            except RevisionConflict:
                totals["revision_conflict"] += 1       # someone edited it meanwhile; retry later
                continue
        row = {"page": key, "document": document.folder.name, "pdf_page": page_number,
               "source_path": document.name, **pstats, **mstats}
        rows = [r for r in rows if r["page"] != key] + [row]
        previous.setdefault("pages", {})[key] = row
        for name in ("added", "replaced", "dup_existing", "dup_rejected", "unmapped_class"):
            totals[name] += mstats[name]
        totals["pages_written"] += 1
        if number % 25 == 0 or number == len(pages):
            elapsed = time.time() - started
            print(f"[{number}/{len(pages)}] added={totals['added']} "
                  f"{elapsed / max(1, totals['pages_written']):.1f}s/page", flush=True)
            previous.update({"model_tag": tag, "model_info": model_info, "dry_run": args.dry_run})
            summary_path.write_text(json.dumps(previous, indent=1))

    previous.update({"model_tag": tag, "model_info": model_info, "dry_run": args.dry_run,
                     "totals": dict(totals)})
    summary_path.write_text(json.dumps(previous, indent=1))
    write_priority(rows, out_dir / "review_priority.txt")
    return {"model_tag": tag, "out": str(out_dir), "totals": dict(totals)}


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--weights", required=True, help="fine-tuned Ultralytics checkpoint (best.pt)")
    p.add_argument("--store", type=Path, help="label store root (e.g. work/labels): every document")
    p.add_argument("--batch", type=Path, action="append",
                   help="store batch file(s) of document folders (instead of the whole store)")
    p.add_argument("--out", type=Path, required=True, help="summary/priority output root")
    p.add_argument("--model-tag", help="name stored on every proposal; default <run>-<sha8>")
    p.add_argument("--conf", type=float, default=0.20)
    p.add_argument("--scales", type=lambda s: [float(v) for v in s.split(",")], default=[0.75, 1.0, 1.5])
    p.add_argument("--device", default="0")
    p.add_argument("--tile-batch", type=int, default=16)
    p.add_argument("--class-map", type=Path,
                   help="JSON {model_class: family | [family, variant] | null}, merged over the defaults")
    p.add_argument("--limit", type=int, default=0)
    p.add_argument("--force", action="store_true", help="redo pages already in this tag's summary")
    p.add_argument("--dry-run", action="store_true", help="predict and report, write nothing to pages")
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.store is None and not args.batch:
        raise SystemExit("give --store and/or --batch")
    if not 0 < args.conf < 1:
        raise SystemExit("--conf must be in (0, 1)")
    print(json.dumps(run(args), indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
