"""Command-line twin of the vision-model tool API, for batch labeling agents.

Agents label documents in the label store (``annotator.label_store``): one
folder per source PDF, pages rendered straight from the PDF on demand. A page
is named by its JSON path inside that folder (``<doc>/<name>_p0012.json``) as
printed by ``next`` and ``doc``; the JSON is created on the first write. Every
write runs through ``LabelingSession.save``, so revision checks, validation
and atomic writes are identical to the browser.

  python -m annotator.agent_cli next    BATCH.txt         # current document + next page
  python -m annotator.agent_cli doc     BATCH.txt --doc N # every page of document N
  python -m annotator.agent_cli thumbs  BATCH.txt --doc N --out t.png
  python -m annotator.agent_cli context PAGE.json
  python -m annotator.agent_cli view    PAGE.json --out view.png [--region x0,y0,x1,y1]
  python -m annotator.agent_cli act     PAGE.json --actions actions.json [--dry-run] [--agent NAME]
  python -m annotator.agent_cli status  BATCH.txt|STORE_DIR
  python -m annotator.agent_cli batches --store STORE_DIR --count N

All commands print one JSON object on stdout (``view`` with ``--out -`` prints
the PNG instead). Failures print ``{"error": ...}`` and exit with status 2.
"""
from __future__ import annotations

import argparse
import base64
import io
import json
import sys
from collections import defaultdict
from pathlib import Path

from PIL import Image, ImageDraw

from annotator import agent_api, label_store, vlm_tools
from annotator.web import LabelingError, RevisionConflict

# Lower rank is labeled first: photos of real jobs are the rarest and most
# valuable sources; bulk downloaded drawing sets come last.
SOURCE_PRIORITY = {
    "field_plans_album_1": 0,
    "rinker_hall": 1,
    "state_dot_samples": 2,
    "freefarmhouse": 3,
}
OUTLINE_FILE = "outline.json"


def _open(page_json: Path):
    return label_store.open_page(Path(page_json))


def _emit(payload: dict) -> None:
    sys.stdout.write(json.dumps(payload, ensure_ascii=False, indent=1) + "\n")


def _read_actions(args) -> object:
    if args.actions_b64:
        raw = base64.b64decode(args.actions_b64).decode("utf-8")
    elif args.actions and args.actions != "-":
        raw = Path(args.actions).read_text(encoding="utf-8")
    else:
        raw = sys.stdin.read()
    data = json.loads(raw)
    if isinstance(data, dict):
        return data
    return {"actions": data}


def cmd_tools(args) -> None:
    session, _, _ = _open(args.page_json)
    _emit(agent_api.tool_manifest(session))


def cmd_context(args) -> None:
    session, index, doc = _open(args.page_json)
    context = agent_api.page_context(session, index, include_walls=False, max_boxes=args.max_boxes)
    context["source"] = {
        "document": doc.name,
        "kind": doc.kind,
        "pdf_page": label_store.page_number(args.page_json),
        "page_count": doc.page_count,
        "triage_reason": doc.targets.get(label_store.page_number(args.page_json)),
    }
    for key in ("image_url", "view_url", "act_url"):
        context.pop(key, None)
    _emit(context)


def _region(session, index: int, raw: str | None):
    doc, _, _ = session.page_document(index)
    width, height = int(doc["image_width"]), int(doc["image_height"])
    if not raw:
        return None, width, height
    return agent_api._parse_region(raw, width, height), width, height


def _check_side(max_side: int) -> None:
    if not agent_api.MIN_MAX_SIDE <= max_side <= agent_api.MAX_MAX_SIDE:
        raise ValueError(f"max-side must be between {agent_api.MIN_MAX_SIDE} and {agent_api.MAX_MAX_SIDE}")


def _write_png(image: Image.Image, out: str, extra: dict | None = None) -> None:
    buffer = io.BytesIO()
    image.save(buffer, format="PNG", optimize=True)
    data = buffer.getvalue()
    if out == "-":
        sys.stdout.buffer.write(data)
        sys.stdout.buffer.flush()
        if extra:
            # The PNG owns stdout; the wrapper relays this metadata from stderr.
            sys.stderr.write(json.dumps(extra, ensure_ascii=False) + "\n")
            sys.stderr.flush()
        return
    path = Path(out)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    _emit({"written": str(path), "bytes": len(data), **(extra or {})})


def _legend_panel(page_json: Path, height: int) -> Image.Image | None:
    return vlm_tools.legend_sheet(page_json, height=max(200, height), font=agent_api._font(11))


def _montage(images: list[Image.Image]) -> Image.Image:
    """Up to four rendered regions in a 2x2 grid (or a row/column for fewer)."""
    columns = 1 if len(images) == 1 else 2
    rows = (len(images) + columns - 1) // columns
    cell_w = max(i.width for i in images)
    cell_h = max(i.height for i in images)
    sheet = Image.new("RGB", (columns * cell_w + (columns - 1) * 8, rows * cell_h + (rows - 1) * 8),
                      (60, 60, 66))
    for n, image in enumerate(images):
        sheet.paste(image, ((n % columns) * (cell_w + 8), (n // columns) * (cell_h + 8)))
    return sheet


def cmd_view(args) -> None:
    session, index, _ = _open(args.page_json)
    _check_side(args.max_side)
    regions = [r for r in (args.regions or "").split(";") if r.strip()]
    if args.region:
        regions = [args.region] + regions
    if len(regions) > 4:
        raise ValueError("at most 4 regions per montage")
    common = {"grid": not args.no_grid, "labels": not args.no_labels,
              "hide_existing": args.hide_existing, "as_image": True}
    if len(regions) <= 1:
        region, _, _ = _region(session, index, regions[0] if regions else None)
        image = agent_api.render_view(session, index, region=region, max_side=args.max_side, **common)
    else:
        side = max(agent_api.MIN_MAX_SIDE, args.max_side // 2 + 100)
        image = _montage([agent_api.render_view(session, index, region=_region(session, index, r)[0],
                                                max_side=side, **common) for r in regions])
    if args.legend:
        panel = _legend_panel(Path(args.page_json), image.height)
        if panel is not None:
            joined = Image.new("RGB", (image.width + panel.width + 6, image.height), (24, 26, 30))
            joined.paste(image, (0, 0)); joined.paste(panel, (image.width + 6, 0))
            image = joined
    _write_png(image, args.out)


def cmd_marks(args) -> None:
    session, index, _ = _open(args.page_json)
    _check_side(args.max_side)
    region, width, height = _region(session, index, args.region)
    region = list(region or (0, 0, width, height))
    _, _, image_path = session.page_document(index)
    marks, source = vlm_tools.find_marks(Path(args.page_json), image_path, region,
                                         min_px=args.min_px, max_px=args.max_px, limit=args.limit)
    side = _legend_panel(Path(args.page_json), 600) if args.legend else None
    image = agent_api.render_view(session, index, region=tuple(int(v) for v in region),
                                  max_side=args.max_side, grid=not args.no_grid, labels=True,
                                  marks=marks, side_image=side, as_image=True)
    _write_png(image, args.out, {"source": source, "count": len(marks), "marks": marks})


def cmd_text(args) -> None:
    session, index, _ = _open(args.page_json)
    region, width, height = _region(session, index, args.region)
    words = vlm_tools.pdf_words(Path(args.page_json), (width, height),
                                list(region) if region else None, limit=args.limit)
    if words is None:
        _emit({"available": False, "note": "this document is a photo; read text from the image"})
        return
    if args.grep:
        needle = args.grep.upper()
        words = [w for w in words if needle in w["text"].upper()]
    _emit({"available": True, "count": len(words), "words": words})


def cmd_legend(args) -> None:
    page = Path(args.page_json)
    label_store.page_number(page)                    # refuse paths outside the store early
    if args.action == "list":
        _emit({"entries": vlm_tools.load_legend(page)})
    elif args.action == "add":
        if not (args.box or args.mark) or not args.family:
            raise ValueError("legend add needs --family and --box or --mark")
        session, index, _ = _open(page)
        _, width, height = _region(session, index, None)
        if args.mark:
            box = agent_api._mark_box(session, args.mark, index)
        else:
            box = agent_api._box4([float(v) for v in args.box.split(",")])
        agent_api._check_box(box, width, height)
        _, _, image_path = session.page_document(index)
        if not args.no_tighten:
            box, _ = vlm_tools.tighten_box(image_path, box, pad=3)
        entry = vlm_tools.add_legend_entry(page, image_path, box, family=args.family,
                                           variant=args.variant or "unspecified", label=args.label or "")
        _emit({"added": entry, "entries": len(vlm_tools.load_legend(page))})
    elif args.action == "remove":
        _emit({"removed": vlm_tools.remove_legend_entry(page, args.id or "")})
    elif args.action == "show":
        sheet = vlm_tools.legend_sheet(page, font=agent_api._font(11))
        if sheet is None:
            _emit({"entries": 0, "note": "no legend entries for this document yet"})
            return
        _write_png(sheet, args.out or "-")


def cmd_act(args) -> None:
    session, index, _ = _open(args.page_json)
    body = _read_actions(args)
    revision = body.get("revision", "latest") if args.revision is None else args.revision
    if revision == "latest":
        # Batch agents own their documents exclusively, so "whatever is on disk
        # now" is the right base. Explicit revisions still get optimistic locking.
        _, revision, _ = session.page_document(index)
    elif revision in ("null", "none"):
        revision = None
    dry_run = args.dry_run or body.get("dry_run") is True
    _emit(agent_api.apply_actions(session, index, revision=revision, actions=body.get("actions"),
                                  dry_run=dry_run, agent=args.agent))


# --------------------------------------------------------------------------
# documents and batches
# --------------------------------------------------------------------------

def _batch_docs(batch: Path) -> list[label_store.Document]:
    return [label_store.load_document(folder) for folder in label_store.batch_documents(batch)]


def _pick(batch: Path, number: int) -> label_store.Document:
    documents = label_store.batch_documents(batch)
    if not 1 <= number <= len(documents):
        raise ValueError(f"--doc must be between 1 and {len(documents)}")
    return label_store.load_document(documents[number - 1])


def _outline(doc: label_store.Document) -> dict[int, dict]:
    """Sheet ids, titles and keyword flags per page, cached in the document folder."""
    if doc.kind != "pdf":
        return {}
    path = doc.folder / OUTLINE_FILE
    if path.is_file():
        cached = json.loads(path.read_text(encoding="utf-8"))
        if cached.get("sha256") == doc.sha256:
            return {int(k): v for k, v in cached["pages"].items()}
    rows = vlm_tools.document_outline(doc.source, max_pages=2000)
    pages = {row["pdf_page"]: row for row in rows}
    path.write_text(json.dumps({"sha256": doc.sha256, "pages": pages}), encoding="utf-8")
    return pages


def _survey_needed(doc: label_store.Document) -> bool:
    return doc.kind == "pdf" and doc.page_count > 1 and not doc.survey_path.is_file()


def cmd_next(args) -> None:
    documents = _batch_docs(args.batch)
    total = done = 0
    current = None
    for number, doc in enumerate(documents, start=1):
        statuses = [label_store.page_status(doc, p) for p in doc.pages_to_label()]
        doc_done = sum(s["assessed"] for s in statuses)
        total += len(statuses)
        done += doc_done
        pending = [s for s in statuses if not s["assessed"]]
        if current is None and (pending or _survey_needed(doc)):
            current = (number, doc, statuses, doc_done, pending)
    if current is None:
        _emit({"next": None, "batch_done": done, "batch_total": total})
        return
    number, doc, statuses, doc_done, pending = current
    survey_done = not _survey_needed(doc)
    first = pending[0] if pending else None
    _emit({
        "document": number,
        "name": doc.name,
        "kind": doc.kind,
        "page_count": doc.page_count,
        "survey_done": survey_done,
        "legend_entries": len(vlm_tools.load_legend(doc.page_json(1))),
        "doc_pages_done": doc_done,
        "doc_pages_total": len(statuses),
        "next": first["page"] if first else None,
        "pdf_page": first["pdf_page"] if first else None,
        "triage": doc.targets.get(first["pdf_page"]) if first else None,
        "batch_done": done,
        "batch_total": total,
        "hint": ("survey this document first: doc, then legend, then survey" if not survey_done
                 else "label the next page"),
    })


def cmd_doc(args) -> None:
    if args.doc is None:
        rows = []
        for number, doc in enumerate(_batch_docs(args.batch), start=1):
            pages = doc.pages_to_label()
            rows.append({"document": number, "name": doc.name, "kind": doc.kind,
                         "page_count": doc.page_count, "pages_to_label": len(pages),
                         "assessed": sum(label_store.page_status(doc, p)["assessed"] for p in pages)})
        _emit({"documents": rows})
        return
    doc = _pick(args.batch, args.doc)
    outline = _outline(doc)
    to_label = set(doc.pages_to_label())
    rows = []
    for page in range(1, doc.page_count + 1):
        row = {"pdf_page": page, "page": str(doc.page_json(page)), "to_label": page in to_label}
        if page in doc.targets:
            row["triage"] = doc.targets[page]
        if page in outline:
            o = outline[page]
            row.update({"sheet": o.get("sheet"), "title": o.get("title"), "flags": o.get("flags")})
        if doc.page_json(page).is_file():
            status = label_store.page_status(doc, page)
            row.update({"assessed": status["assessed"], "boxes": status["boxes"]})
        rows.append(row)
    _emit({
        "document": args.doc,
        "name": doc.name,
        "kind": doc.kind,
        "page_count": doc.page_count,
        "pages": rows,
        "legend_entries": len(vlm_tools.load_legend(doc.page_json(1))),
        "survey": doc.survey(),
        "note": ("every page can be viewed and labeled with its `page` path; to_label pages are "
                 "the ones `next` will hand you (add more with survey --label-pages)"),
    })


def _page_thumbnail(doc: label_store.Document, page: int, side: int) -> Image.Image:
    if doc.kind != "pdf":
        with Image.open(doc.source) as image:
            image.draft("RGB", (side * 2, side * 2))
            thumb = image.convert("RGB")
        thumb.thumbnail((side, side))
        return thumb
    import fitz  # type: ignore
    with fitz.open(doc.source) as pdf:
        sheet = pdf[page - 1]
        rect = sheet.rect
        zoom = side / max(rect.width, rect.height)
        pix = sheet.get_pixmap(matrix=fitz.Matrix(zoom, zoom), alpha=False)
        return Image.frombytes("RGB", (pix.width, pix.height), pix.samples)


def cmd_thumbs(args) -> None:
    """Up to 16 pages of one document as a single labelled contact sheet, rendered from the PDF."""
    doc = _pick(args.batch, args.doc)
    pages = list(range(1, doc.page_count + 1))[args.start:args.start + 16]
    if not pages:
        raise ValueError("no pages at that --start")
    to_label = set(doc.pages_to_label())
    cell = args.cell
    columns = 4 if len(pages) > 9 else 3 if len(pages) > 4 else 2 if len(pages) > 1 else 1
    grid_rows = (len(pages) + columns - 1) // columns
    sheet = Image.new("RGB", (columns * cell, grid_rows * (cell + 22)), (40, 42, 48))
    draw = ImageDraw.Draw(sheet)
    font = agent_api._font(14)
    for n, page in enumerate(pages):
        x, y = (n % columns) * cell, (n // columns) * (cell + 22)
        try:
            thumb = _page_thumbnail(doc, page, cell - 6)
            sheet.paste(thumb, (x + (cell - thumb.width) // 2, y + 22 + (cell - thumb.height) // 2))
        except Exception:                                    # one bad page must not sink the sheet
            pass
        tag = "LABEL" if page in to_label else ""
        draw.text((x + 4, y + 3), f"p{page} {tag}", font=font, fill=(255, 236, 140))
    _write_png(sheet, args.out, {"pages": pages, "more": doc.page_count > args.start + 16})


def cmd_survey(args) -> None:
    doc = _pick(args.batch, args.doc)
    previous = doc.survey() or {}
    record = {
        "note": (args.note or previous.get("note") or "")[:1500],
        "legend_pages": label_store.parse_pages(args.legend_pages, doc.page_count)
        if args.legend_pages is not None else previous.get("legend_pages", []),
        "label_pages": label_store.parse_pages(args.label_pages, doc.page_count)
        if args.label_pages is not None else previous.get("label_pages", []),
        "skip_pages": label_store.parse_pages(args.skip_pages, doc.page_count)
        if args.skip_pages is not None else previous.get("skip_pages", []),
        "electrical_sheets": (args.electrical_sheets or previous.get("electrical_sheets") or "")[:300],
        "agent": args.agent or previous.get("agent") or "",
    }
    doc.survey_path.write_text(json.dumps(record, indent=1), encoding="utf-8")
    _emit({"saved": record, "pages_to_label": doc.pages_to_label()})


def _status_rows(docs) -> list[dict]:
    rows = []
    for doc in docs:
        rows.extend(label_store.page_status(doc, p) for p in doc.pages_to_label())
    return rows


def cmd_status(args) -> None:
    target = Path(args.target)
    docs = list(label_store.iter_documents(target)) if target.is_dir() else _batch_docs(target)
    rows = _status_rows(docs)
    by_presence: dict[str, int] = defaultdict(int)
    by_kind: dict[str, int] = defaultdict(int)
    for row in rows:
        if row["assessed"]:
            by_presence[row["symbols_present"]] += 1
            by_kind[row["page_kind"]] += 1
    _emit({
        "documents": len(docs),
        "surveyed": sum(1 for d in docs if not _survey_needed(d)),
        "pages": len(rows),
        "assessed": sum(r["assessed"] for r in rows),
        "agent_boxes": sum(r["agent_boxes"] for r in rows),
        "model_boxes": sum(r["model_boxes"] for r in rows),
        "symbols_present": dict(by_presence),
        "page_kind": dict(by_kind),
    })


def cmd_batches(args) -> None:
    """Split the store's documents into per-agent batch files.

    Existing batch files are kept and only documents not yet in any batch are
    added (to the smallest batches), so agents keep their place when new PDFs
    arrive. ``--force`` redistributes everything.
    """
    store = Path(args.store).resolve()
    out = store / label_store.BATCH_DIR
    out.mkdir(parents=True, exist_ok=True)
    count = max(1, args.count)
    bins: list[list[str]] = [[] for _ in range(count)]
    if not args.force:
        for number in range(count):
            path = out / f"batch_{number + 1:02d}.txt"
            if path.is_file():
                bins[number] = [line.strip() for line in path.read_text(encoding="utf-8").splitlines()
                                if line.strip() and not line.startswith("#")]
    assigned = {name for lines in bins for name in lines}
    docs = list(label_store.iter_documents(store))
    sizes_by_name = {d.folder.name: max(1, len(d.pages_to_label())) for d in docs}
    sizes = [sum(sizes_by_name.get(name, 1) for name in lines) for lines in bins]
    fresh = [d for d in docs if d.folder.name not in assigned]
    fresh.sort(key=lambda d: (SOURCE_PRIORITY.get(str(d.meta.get("source_id") or ""), 10), d.name))
    # Whole documents stay together so one agent sees a drawing set's legend,
    # title block conventions and symbol style consistently.
    for doc in fresh:
        target = min(range(count), key=lambda i: (sizes[i], i))
        bins[target].append(doc.folder.name)
        sizes[target] += sizes_by_name[doc.folder.name]
    written = []
    for number, lines in enumerate(bins, start=1):
        path = out / f"batch_{number:02d}.txt"
        path.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")
        written.append({"batch": str(path), "documents": len(lines), "pages": sizes[number - 1]})
    _emit({"documents": len(docs), "added": len(fresh), "batches": written})


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="python -m annotator.agent_cli", description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("tools", help="tool manifest: verbs, coordinates, vocabulary")
    p.add_argument("page_json", type=Path)
    p.set_defaults(func=cmd_tools)

    p = sub.add_parser("context", help="page size, revision, existing boxes, assessment")
    p.add_argument("page_json", type=Path)
    p.add_argument("--max-boxes", type=int, default=agent_api.MAX_CONTEXT_BOXES)
    p.set_defaults(func=cmd_context)

    p = sub.add_parser("view", help="render the page, a region, or up to 4 regions as one PNG")
    p.add_argument("page_json", type=Path)
    p.add_argument("--out", required=True, help="PNG path, or - for stdout")
    p.add_argument("--region", help="x0,y0,x1,y1 in page pixels")
    p.add_argument("--regions", help="up to 4 regions separated by ';' rendered as one 2x2 image")
    p.add_argument("--max-side", type=int, default=agent_api.DEFAULT_MAX_SIDE)
    p.add_argument("--legend", action="store_true", help="append the document legend to the right")
    p.add_argument("--no-grid", action="store_true")
    p.add_argument("--no-labels", action="store_true")
    p.add_argument("--hide-existing", action="store_true")
    p.set_defaults(func=cmd_view)

    p = sub.add_parser("marks", help="numbered symbol candidates in a region, drawn on a view")
    p.add_argument("page_json", type=Path)
    p.add_argument("--out", required=True)
    p.add_argument("--region", help="x0,y0,x1,y1; default whole page")
    p.add_argument("--max-side", type=int, default=1200)
    p.add_argument("--min-px", type=float, default=10)
    p.add_argument("--max-px", type=float, default=90)
    p.add_argument("--limit", type=int, default=80)
    p.add_argument("--legend", action="store_true")
    p.add_argument("--no-grid", action="store_true")
    p.set_defaults(func=cmd_marks)

    p = sub.add_parser("text", help="words from the source PDF with page-pixel boxes")
    p.add_argument("page_json", type=Path)
    p.add_argument("--region")
    p.add_argument("--grep")
    p.add_argument("--limit", type=int, default=300)
    p.set_defaults(func=cmd_text)

    p = sub.add_parser("legend", help="per-document legend library: list, add, remove, show")
    p.add_argument("action", choices=("list", "add", "remove", "show"))
    p.add_argument("page_json", type=Path, help="any page of the document (the legend page for add)")
    p.add_argument("--box", help="x0,y0,x1,y1 of the legend glyph (not its text)")
    p.add_argument("--family"); p.add_argument("--variant"); p.add_argument("--label")
    p.add_argument("--mark", help="use a mark id from the last marks call on this page")
    p.add_argument("--id"); p.add_argument("--out")
    p.add_argument("--no-tighten", action="store_true")
    p.set_defaults(func=cmd_legend)

    p = sub.add_parser("doc", help="documents in a batch, or every page of one document")
    p.add_argument("batch", type=Path)
    p.add_argument("--doc", type=int)
    p.set_defaults(func=cmd_doc)

    p = sub.add_parser("thumbs", help="up to 16 pages of a document as one contact-sheet image")
    p.add_argument("batch", type=Path)
    p.add_argument("--doc", type=int, required=True)
    p.add_argument("--start", type=int, default=0)
    p.add_argument("--cell", type=int, default=360)
    p.add_argument("--out", required=True)
    p.set_defaults(func=cmd_thumbs)

    p = sub.add_parser("survey", help="record what you learned about a document")
    p.add_argument("batch", type=Path)
    p.add_argument("--doc", type=int, required=True)
    p.add_argument("--note")
    p.add_argument("--legend-pages", help="PDF pages holding the symbol legend, e.g. 3 or 3-4")
    p.add_argument("--label-pages", help="extra PDF pages to label (electrical plans), e.g. 12,14-18")
    p.add_argument("--skip-pages", help="queued PDF pages that need no labeling (not electrical)")
    p.add_argument("--electrical-sheets", help="sheet ids as printed, e.g. E1.1-E1.4")
    p.add_argument("--agent")
    p.set_defaults(func=cmd_survey)

    p = sub.add_parser("act", help="apply a batch of box edits and save")
    p.add_argument("page_json", type=Path)
    p.add_argument("--actions", help="JSON file with a list of actions or {revision, actions}; - for stdin")
    p.add_argument("--actions-b64", help=argparse.SUPPRESS)
    p.add_argument("--revision", help="revision from context; default 'latest'")
    p.add_argument("--dry-run", action="store_true")
    p.add_argument("--agent", help="agent/model name recorded on assessments")
    p.set_defaults(func=cmd_act)

    p = sub.add_parser("next", help="next unfinished document/page in a batch")
    p.add_argument("batch", type=Path)
    p.set_defaults(func=cmd_next)

    p = sub.add_parser("status", help="progress summary for a batch file or the whole store")
    p.add_argument("target")
    p.set_defaults(func=cmd_status)

    p = sub.add_parser("batches", help="assign store documents to per-agent batch files")
    p.add_argument("--store", required=True)
    p.add_argument("--count", type=int, default=9)
    p.add_argument("--force", action="store_true", help="redistribute every document")
    p.set_defaults(func=cmd_batches)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        args.func(args)
    except (LabelingError, RevisionConflict, ValueError, FileNotFoundError, OSError,
            json.JSONDecodeError, KeyError) as exc:
        _emit({"error": str(exc) or exc.__class__.__name__, "type": exc.__class__.__name__})
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
