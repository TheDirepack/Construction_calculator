"""Command-line twin of the vision-model tool API, for batch labeling agents.

The HTTP surface in ``agent_api`` needs a running server and a session token.
A coding agent working through hundreds of pages is better served by one
stateless command per step that opens the page JSON in ``--edit`` mode, does
one thing and exits. Every write still runs through ``LabelingSession.save``,
so revision checks, validation and atomic writes are identical to the browser.

  python -m annotator.agent_cli tools   PAGE.json
  python -m annotator.agent_cli context PAGE.json
  python -m annotator.agent_cli view    PAGE.json --out view.png [--region x0,y0,x1,y1] [--max-side 1400]
  python -m annotator.agent_cli act     PAGE.json --actions actions.json [--dry-run] [--agent NAME]
  python -m annotator.agent_cli next    BATCH.txt         # first page in the batch without an assessment
  python -m annotator.agent_cli status  BATCH.txt|QUEUE_DIR
  python -m annotator.agent_cli batches --queue QUEUE_DIR --out BATCH_DIR --count N

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

from annotator import agent_api, vlm_tools
from annotator.web import LabelingError, LabelingSession, RevisionConflict

# Lower rank is labeled first: scans and photos of real jobs are the rarest
# and most valuable sources; bulk downloaded drawing sets come last.
SOURCE_PRIORITY = {
    "user_scans": 0,
    "field_plans_album_1": 1,
    "rinker_hall": 2,
    "state_dot_samples": 3,
    "freefarmhouse": 4,
}


def _session(page_json: Path) -> LabelingSession:
    return LabelingSession(edit=Path(page_json))


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


def _batch_pages(path: Path) -> list[Path]:
    pages = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            pages.append(Path(line))
    return pages


def _page_status(page: Path) -> dict:
    data = json.loads(page.read_text(encoding="utf-8"))
    assessment = data.get("vlm_assessment") or None
    instances = data.get("instances") or []
    return {
        "page": str(page),
        "assessed": bool(assessment),
        "boxes": len(instances),
        "agent_boxes": sum(1 for i in instances if i.get("review_source") == "vlm_agent"),
        "symbols_present": (assessment or {}).get("symbols_present"),
        "page_kind": (assessment or {}).get("page_kind"),
    }


def cmd_tools(args) -> None:
    _emit(agent_api.tool_manifest(_session(args.page_json)))


def cmd_context(args) -> None:
    session = _session(args.page_json)
    context = agent_api.page_context(session, 0, include_walls=False, max_boxes=args.max_boxes)
    data, _, _ = session.page_document(0)
    pool = data.get("pool") or {}
    context["source"] = {
        "source_id": data.get("source_id"),
        "source_type": data.get("source_type"),
        "source_path": pool.get("source_path"),
        "pdf_page": pool.get("page"),
        "triage_reason": pool.get("role_reason"),
    }
    for key in ("image_url", "view_url", "act_url"):
        context.pop(key, None)
    _emit(context)


def _region(session, raw: str | None):
    doc, _, _ = session.page_document(0)
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
    session = _session(args.page_json)
    _check_side(args.max_side)
    regions = [r for r in (args.regions or "").split(";") if r.strip()]
    if args.region:
        regions = [args.region] + regions
    if len(regions) > 4:
        raise ValueError("at most 4 regions per montage")
    common = {"grid": not args.no_grid, "labels": not args.no_labels,
              "hide_existing": args.hide_existing, "as_image": True}
    if len(regions) <= 1:
        region, _, _ = _region(session, regions[0] if regions else None)
        image = agent_api.render_view(session, 0, region=region, max_side=args.max_side, **common)
    else:
        side = max(agent_api.MIN_MAX_SIDE, args.max_side // 2 + 100)
        image = _montage([agent_api.render_view(session, 0, region=_region(session, r)[0],
                                                max_side=side, **common) for r in regions])
    if args.legend:
        panel = _legend_panel(Path(args.page_json), image.height)
        if panel is not None:
            joined = Image.new("RGB", (image.width + panel.width + 6, image.height), (24, 26, 30))
            joined.paste(image, (0, 0)); joined.paste(panel, (image.width + 6, 0))
            image = joined
    _write_png(image, args.out)


def cmd_marks(args) -> None:
    session = _session(args.page_json)
    _check_side(args.max_side)
    region, width, height = _region(session, args.region)
    region = list(region or (0, 0, width, height))
    _, _, image_path = session.page_document(0)
    marks, source = vlm_tools.find_marks(Path(args.page_json), image_path, region,
                                         min_px=args.min_px, max_px=args.max_px, limit=args.limit)
    side = _legend_panel(Path(args.page_json), 600) if args.legend else None
    image = agent_api.render_view(session, 0, region=tuple(int(v) for v in region),
                                  max_side=args.max_side, grid=not args.no_grid, labels=True,
                                  marks=marks, side_image=side, as_image=True)
    _write_png(image, args.out, {"source": source, "count": len(marks), "marks": marks})


def cmd_text(args) -> None:
    session = _session(args.page_json)
    region, _, _ = _region(session, args.region)
    words = vlm_tools.pdf_words(Path(args.page_json), list(region) if region else None, limit=args.limit)
    if words is None:
        _emit({"available": False, "note": "source PDF not found; read text from the image instead"})
        return
    if args.grep:
        needle = args.grep.upper()
        words = [w for w in words if needle in w["text"].upper()]
    _emit({"available": True, "count": len(words), "words": words})


def cmd_legend(args) -> None:
    page = Path(args.page_json)
    if args.action == "list":
        _emit({"entries": vlm_tools.load_legend(page)})
    elif args.action == "add":
        if not (args.box or args.mark) or not args.family:
            raise ValueError("legend add needs --family and --box or --mark")
        session = _session(page)
        _, width, height = _region(session, None)
        if args.mark:
            box = agent_api._mark_box(session, args.mark)
        else:
            box = agent_api._box4([float(v) for v in args.box.split(",")])
        agent_api._check_box(box, width, height)
        _, _, image_path = session.page_document(0)
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


def _guard_write(page_json: Path) -> None:
    if Path(page_json).resolve().parent.name != "label_queue":
        raise ValueError("only label_queue pages can be written; other pool pages are read-only references")


def cmd_act(args) -> None:
    _guard_write(args.page_json)
    session = _session(args.page_json)
    body = _read_actions(args)
    revision = body.get("revision", "latest") if args.revision is None else args.revision
    if revision == "latest":
        # Batch agents own their pages exclusively, so "whatever is on disk now"
        # is the right base. Explicit revisions still get optimistic locking.
        _, revision, _ = session.page_document(0)
    elif revision in ("null", "none"):
        revision = None
    dry_run = args.dry_run or body.get("dry_run") is True
    _emit(agent_api.apply_actions(session, 0, revision=revision, actions=body.get("actions"),
                                  dry_run=dry_run, agent=args.agent))


def _batch_documents(path: Path) -> list[dict]:
    """Batch file sections: ``# source_path`` header followed by its queue pages."""
    documents: list[dict] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        if line.startswith("#"):
            documents.append({"source_path": line[1:].strip(), "pages": []})
        elif documents:
            documents[-1]["pages"].append(Path(line))
        else:
            documents.append({"source_path": "", "pages": [Path(line)]})
    return documents


def _survey_path(page_json: Path) -> Path:
    return vlm_tools._legend_dir(page_json) / "survey.json"


def cmd_next(args) -> None:
    documents = _batch_documents(args.batch)
    total = sum(len(d["pages"]) for d in documents)
    done = 0
    for number, document in enumerate(documents, start=1):
        statuses = [_page_status(p) for p in document["pages"]]
        doc_done = sum(s["assessed"] for s in statuses)
        done += doc_done
        pending = [s for s in statuses if not s["assessed"]]
        if not pending:
            continue
        first = Path(pending[0]["page"])
        survey = _survey_path(first)
        _emit({
            "document": number,
            "source_path": document["source_path"],
            "survey_done": survey.is_file(),
            "legend_entries": len(vlm_tools.load_legend(first)),
            "doc_pages_done": doc_done,
            "doc_pages_total": len(statuses),
            "next": str(first),
            "batch_done": done,
            "batch_total": total,
            "hint": ("survey this document first: doc, then legend" if not survey.is_file()
                     else "label the next page"),
        })
        return
    _emit({"next": None, "batch_done": total, "batch_total": total})


def cmd_doc(args) -> None:
    documents = _batch_documents(args.batch)
    if args.doc is None:
        rows = []
        for number, document in enumerate(documents, start=1):
            statuses = [_page_status(p) for p in document["pages"]]
            rows.append({"document": number, "source_path": document["source_path"],
                         "queue_pages": len(statuses),
                         "assessed": sum(s["assessed"] for s in statuses)})
        _emit({"documents": rows})
        return
    if not 1 <= args.doc <= len(documents):
        raise ValueError(f"--doc must be between 1 and {len(documents)}")
    document = documents[args.doc - 1]
    if not document["pages"]:
        raise ValueError("document has no pages")
    first = document["pages"][0]
    pool = vlm_tools.pool_root_for(first)
    index = vlm_tools.load_index(pool)
    known = index["documents"].get(document["source_path"], [])
    queue = {str(Path(p).resolve()) for p in document["pages"]}
    pdf = vlm_tools.find_pdf(pool, document["source_path"])
    outline = {}
    if pdf is not None:
        outline = {row["pdf_page"]: row for row in vlm_tools.document_outline(pdf)}
    rows = []
    seen_pages = set()
    for entry in known:
        mine = str(Path(entry["json"]).resolve()) in queue
        row = {"pdf_page": entry["pdf_page"], "json": entry["json"], "folder": entry["folder"],
               "yours_to_label": mine, "triage": entry["role_reason"]}
        if mine:
            status = _page_status(Path(entry["json"]))
            row["assessed"] = status["assessed"]
            row["boxes"] = status["boxes"]
        if entry["pdf_page"] in outline:
            o = outline[entry["pdf_page"]]
            row.update({"sheet": o.get("sheet"), "title": o.get("title"), "flags": o.get("flags")})
        seen_pages.add(entry["pdf_page"])
        rows.append(row)
    for number, o in outline.items():
        if number not in seen_pages:
            rows.append({"pdf_page": number, "json": None, "folder": "pdf_only",
                         "sheet": o.get("sheet"), "title": o.get("title"), "flags": o.get("flags")})
    rows.sort(key=lambda r: (r["pdf_page"] is None, r["pdf_page"] or 0))
    survey = _survey_path(first)
    _emit({
        "document": args.doc,
        "source_path": document["source_path"],
        "source_pdf_available": pdf is not None,
        "pages": rows,
        "legend_entries": len(vlm_tools.load_legend(first)),
        "survey": json.loads(survey.read_text()) if survey.is_file() else None,
        "note": ("pages with folder background/reserve are read-only references (view them, "
                 "never act on them); pdf_only pages can be seen with pdfview"),
    })


def cmd_thumbs(args) -> None:
    """Up to 16 sheets of one document as a single labelled contact sheet."""
    documents = _batch_documents(args.batch)
    if not 1 <= args.doc <= len(documents):
        raise ValueError(f"--doc must be between 1 and {len(documents)}")
    document = documents[args.doc - 1]
    first = document["pages"][0]
    index = vlm_tools.load_index(vlm_tools.pool_root_for(first))
    known = index["documents"].get(document["source_path"], [])
    rows = known[args.start:args.start + 16]
    if not rows:
        raise ValueError("no pages at that --start")
    cell = args.cell
    columns = 4 if len(rows) > 9 else 3 if len(rows) > 4 else 2 if len(rows) > 1 else 1
    grid_rows = (len(rows) + columns - 1) // columns
    sheet = Image.new("RGB", (columns * cell, grid_rows * (cell + 22)), (40, 42, 48))
    draw = ImageDraw.Draw(sheet)
    font = agent_api._font(14)
    listing = []
    for n, row in enumerate(rows):
        x, y = (n % columns) * cell, (n // columns) * (cell + 22)
        try:
            record = json.loads(Path(row["json"]).read_text(encoding="utf-8"))
            from annotator.web import _resolve_edit_image
            with Image.open(_resolve_edit_image(Path(row["json"]), record["image_path"])) as page:
                page.draft("RGB", (cell * 2, cell * 2))
                thumb = page.convert("RGB")
                thumb.thumbnail((cell - 6, cell - 6))
            sheet.paste(thumb, (x + (cell - thumb.width) // 2, y + 22 + (cell - thumb.height) // 2))
        except (OSError, KeyError, ValueError):
            pass
        mine = "LABEL" if row["folder"] == "label_queue" else "ref"
        draw.text((x + 4, y + 3), f"p{row['pdf_page']} {mine}", font=font, fill=(255, 236, 140))
        listing.append({"pdf_page": row["pdf_page"], "folder": row["folder"], "json": row["json"]})
    _write_png(sheet, args.out, {"pages": listing, "more": len(known) > args.start + 16})


def cmd_survey(args) -> None:
    documents = _batch_documents(args.batch)
    if not 1 <= args.doc <= len(documents):
        raise ValueError(f"--doc must be between 1 and {len(documents)}")
    first = documents[args.doc - 1]["pages"][0]
    record = {"note": (args.note or "")[:1500], "legend_pages": args.legend_pages or "",
              "electrical_sheets": args.electrical_sheets or "", "agent": args.agent or ""}
    _survey_path(first).write_text(json.dumps(record, indent=1), encoding="utf-8")
    _emit({"saved": record})


def cmd_pdfview(args) -> None:
    record = vlm_tools.page_record(args.page_json)
    pool = vlm_tools.pool_root_for(args.page_json)
    pdf = vlm_tools.find_pdf(pool, record["source_path"])
    if pdf is None:
        raise FileNotFoundError("source PDF for this document is not available")
    import fitz  # type: ignore
    with fitz.open(pdf) as doc:
        if not 1 <= args.pdf_page <= len(doc):
            raise ValueError(f"pdf-page must be between 1 and {len(doc)}")
        page = doc[args.pdf_page - 1]
        rect = page.rect * page.rotation_matrix
        zoom = args.max_side / max(rect.width, rect.height)
        pix = page.get_pixmap(matrix=fitz.Matrix(zoom, zoom), alpha=False)
        image = Image.frombytes("RGB", (pix.width, pix.height), pix.samples)
    _write_png(image, args.out, {"pdf_page": args.pdf_page, "note": "reference only; not a labeling page"})


def cmd_index(args) -> None:
    index = vlm_tools.build_index(Path(args.pool))
    _emit({"documents": len(index["documents"]),
           "pages": sum(len(v) for v in index["documents"].values())})


def cmd_status(args) -> None:
    target = Path(args.target)
    if target.is_dir():
        pages = sorted(p for p in target.glob("*.json"))
    else:
        pages = _batch_pages(target)
    rows = [_page_status(p) for p in pages]
    by_presence: dict[str, int] = defaultdict(int)
    by_kind: dict[str, int] = defaultdict(int)
    for row in rows:
        if row["assessed"]:
            by_presence[row["symbols_present"]] += 1
            by_kind[row["page_kind"]] += 1
    _emit({
        "pages": len(rows),
        "assessed": sum(r["assessed"] for r in rows),
        "agent_boxes": sum(r["agent_boxes"] for r in rows),
        "symbols_present": dict(by_presence),
        "page_kind": dict(by_kind),
    })


def cmd_batches(args) -> None:
    queue = Path(args.queue).resolve()
    documents: dict[tuple[int, str], list[tuple[int, Path]]] = defaultdict(list)
    for page in sorted(queue.glob("*.json")):
        data = json.loads(page.read_text(encoding="utf-8"))
        pool = data.get("pool") or {}
        source_id = str(data.get("source_id") or "")
        source_path = str(pool.get("source_path") or page.stem)
        rank = SOURCE_PRIORITY.get(source_id, 10)
        documents[(rank, source_path)].append((int(pool.get("page") or 0), page))

    # Whole documents stay together so one agent sees a drawing set's legend,
    # title block conventions and symbol style consistently.
    ordered = sorted(documents.items(), key=lambda item: (item[0][0], item[0][1]))
    count = max(1, args.count)
    bins: list[list[str]] = [[] for _ in range(count)]
    sizes = [0] * count
    for (rank, source_path), pages in ordered:
        target = min(range(count), key=lambda i: (sizes[i], i))
        bins[target].append(f"# {source_path}")
        bins[target].extend(str(p) for _, p in sorted(pages))
        sizes[target] += len(pages)

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    written = []
    for number, lines in enumerate(bins, start=1):
        path = out / f"batch_{number:02d}.txt"
        if path.exists() and not args.force:
            raise LabelingError(f"{path} exists; pass --force to rebuild batches")
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        written.append({"batch": str(path), "pages": sizes[number - 1]})
    _emit({"documents": len(documents), "pages": sum(sizes), "batches": written})


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
    p.add_argument("page_json", type=Path, help="any page of the document (legend page for add)")
    p.add_argument("--box", help="x0,y0,x1,y1 of the legend glyph (not its text)")
    p.add_argument("--family"); p.add_argument("--variant"); p.add_argument("--label")
    p.add_argument("--mark", help="use a mark id from the last marks call on this page")
    p.add_argument("--id"); p.add_argument("--out")
    p.add_argument("--no-tighten", action="store_true")
    p.set_defaults(func=cmd_legend)

    p = sub.add_parser("doc", help="documents in a batch, or every known page of one document")
    p.add_argument("batch", type=Path)
    p.add_argument("--doc", type=int)
    p.set_defaults(func=cmd_doc)

    p = sub.add_parser("thumbs", help="up to 16 sheets of a document as one contact-sheet image")
    p.add_argument("batch", type=Path)
    p.add_argument("--doc", type=int, required=True)
    p.add_argument("--start", type=int, default=0)
    p.add_argument("--cell", type=int, default=360)
    p.add_argument("--out", required=True)
    p.set_defaults(func=cmd_thumbs)

    p = sub.add_parser("survey", help="record what you learned about a document (legend pages, notes)")
    p.add_argument("batch", type=Path)
    p.add_argument("--doc", type=int, required=True)
    p.add_argument("--note"); p.add_argument("--legend-pages"); p.add_argument("--electrical-sheets")
    p.add_argument("--agent")
    p.set_defaults(func=cmd_survey)

    p = sub.add_parser("pdfview", help="render any page of the source PDF (reference only)")
    p.add_argument("page_json", type=Path)
    p.add_argument("--pdf-page", type=int, required=True, help="1-based, as listed by doc")
    p.add_argument("--out", required=True)
    p.add_argument("--max-side", type=int, default=1400)
    p.set_defaults(func=cmd_pdfview)

    p = sub.add_parser("index", help="rebuild the pool document index")
    p.add_argument("--pool", required=True)
    p.set_defaults(func=cmd_index)

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

    p = sub.add_parser("status", help="progress summary for a batch file or queue directory")
    p.add_argument("target")
    p.set_defaults(func=cmd_status)

    p = sub.add_parser("batches", help="split a label queue into per-agent batch files")
    p.add_argument("--queue", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--count", type=int, default=8)
    p.add_argument("--force", action="store_true")
    p.set_defaults(func=cmd_batches)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        args.func(args)
    except (LabelingError, RevisionConflict, ValueError, FileNotFoundError, OSError,
            json.JSONDecodeError) as exc:
        _emit({"error": str(exc) or exc.__class__.__name__, "type": exc.__class__.__name__})
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
