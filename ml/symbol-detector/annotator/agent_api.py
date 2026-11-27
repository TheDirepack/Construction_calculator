"""Machine-facing tool layer for the PDF annotator, built for vision models.

The browser editor is for a human at a screen. A vision-language model needs
different affordances: a compact description of the page it is looking at, a
rendering it can actually resolve, and a single write verb that validates and
saves atomically. This module provides exactly four things:

  describe  GET  /api/agent/tools     tool schemas, vocabulary, coordinate rules
  read      GET  /api/agent/context   the page's boxes, revision and page flags
  see       GET  /api/agent/view      a labelled rendering, full page or a crop
  act       POST /api/agent/act       batched box edits, validated and saved

Design rules this module follows:

* It is an adapter, not a second implementation. Revision checks, box
  validation, device-attribute validation and atomic writes all run inside
  ``LabelingSession.validate_review_payload`` / ``LabelingSession.save``.
  Snapping runs through ``LabelingSession.resolve_snap_preview``.
* Boxes written here are records of machine observation, never ground truth.
  They are saved as ``review_status="unreviewed"``, ``uncertain=True`` and
  ``review_source="vlm_agent"`` so a human still has to verify them. Only the
  existing human Verify action can produce an accepted annotation.
* Rendering is bounded. A rendered sheet is ~17 megapixels, which no vision
  model can read in one glance, so ``view`` caps output resolution, reports
  the exact scale, and can crop/zoom and draw a coordinate grid.
"""
from __future__ import annotations

import copy
import datetime as _dt
import io
import math
import zlib
from functools import lru_cache
from pathlib import Path
from urllib.parse import parse_qs

from PIL import Image, ImageDraw, ImageFont

from annotator.model import FAMILY_DISCIPLINES, RotatedBox
from blueprint_symbols.label_schema import pending_instance

DEFAULT_MAX_SIDE = 1400
MIN_MAX_SIDE = 128
MAX_MAX_SIDE = 4000
CAPTION_HEIGHT = 26
MAX_ACTIONS = 500
MAX_CONTEXT_BOXES = 2000
PAGE_KINDS = (
    "electrical_plan", "electrical_schedule_or_detail", "other_discipline_plan",
    "title_or_text_sheet", "photo_unreadable", "other",
)
SYMBOLS_PRESENT = ("yes", "no", "unsure")

_PALETTE = (
    (214, 39, 40), (31, 119, 180), (44, 160, 44), (148, 103, 189),
    (255, 127, 14), (23, 190, 207), (227, 119, 194), (140, 86, 75),
)

_FONT_CANDIDATES = (
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    "/usr/share/fonts/dejavu/DejaVuSans.ttf",
)


# --------------------------------------------------------------------------
# query parsing
# --------------------------------------------------------------------------

def _one(query: str, allowed: set[str]) -> dict[str, str]:
    """Parse a query string, rejecting unknown or repeated parameters."""
    parsed = parse_qs(query, keep_blank_values=True)
    unknown = sorted(set(parsed) - allowed)
    if unknown:
        raise ValueError("unsupported query parameter: " + ", ".join(unknown))
    for key, values in parsed.items():
        if len(values) != 1:
            raise ValueError(f"{key} must be given exactly once")
    return {key: values[0] for key, values in parsed.items()}


def _flag(raw: str, name: str) -> bool:
    if raw in ("1", "true", "yes", "on"):
        return True
    if raw in ("0", "false", "no", "off"):
        return False
    raise ValueError(f"{name} must be a boolean (1/0)")


def _int(raw: str, name: str, low: int, high: int) -> int:
    try:
        value = int(raw)
    except (TypeError, ValueError):
        raise ValueError(f"{name} must be an integer") from None
    if not low <= value <= high:
        raise ValueError(f"{name} must be between {low} and {high}")
    return value


def page_from_query(session, query: str, extra: set[str] | None = None) -> tuple[int, dict[str, str]]:
    """Resolve ``?page=`` plus any endpoint-specific parameters."""
    params = _one(query, {"page"} | (extra or set()))
    raw = params.pop("page", str(session.start_page))
    try:
        page = session._page_index(int(raw))
    except (TypeError, ValueError):
        raise ValueError("invalid page number") from None
    return page, params


def parse_context_query(session, query: str) -> tuple[int, dict]:
    """``GET /api/agent/context`` parameters as kwargs for :func:`page_context`."""
    page, params = page_from_query(session, query, {"include_walls", "max_boxes"})
    return page, {
        "include_walls": _flag(params.get("include_walls", "0"), "include_walls"),
        "max_boxes": _int(params.get("max_boxes", str(MAX_CONTEXT_BOXES)), "max_boxes", 1, 150_000),
    }


def parse_view_query(session, query: str) -> tuple[int, dict]:
    """``GET /api/agent/view`` parameters as kwargs for :func:`render_view`."""
    page, params = page_from_query(
        session, query, {"region", "max_side", "grid", "labels", "hide_existing"}
    )
    region = params.get("region")
    if region is not None:
        doc, _, _ = session.page_document(page)
        region = _parse_region(region, int(doc["image_width"]), int(doc["image_height"]))
    return page, {
        "region": region,
        "max_side": _int(params.get("max_side", str(DEFAULT_MAX_SIDE)), "max_side", MIN_MAX_SIDE, MAX_MAX_SIDE),
        "grid": _flag(params.get("grid", "1"), "grid"),
        "labels": _flag(params.get("labels", "1"), "labels"),
        "hide_existing": _flag(params.get("hide_existing", "0"), "hide_existing"),
    }


# --------------------------------------------------------------------------
# vocabulary
# --------------------------------------------------------------------------

def _catalog_index(session) -> dict[tuple[str, str], dict]:
    index: dict[tuple[str, str], dict] = {}
    for device in (session.device_catalog or {}).get("devices", []):
        family = device.get("symbol_family")
        variant = device.get("symbol_variant")
        if family and variant:
            index[(family, variant)] = {
                "label": device.get("label", ""),
                "category": device.get("category", ""),
                "source_count": device.get("source_count", 0),
            }
    return index


def _catalog_entry(session, family: str, variant: str) -> dict | None:
    return _catalog_index(session).get((family, variant))


def _vocabulary(session) -> dict:
    catalog = _catalog_index(session)
    families = {}
    for family, variants in sorted(session.variants_by_family.items()):
        entries = []
        for variant in variants:
            entry: dict = {"variant": variant}
            meta = catalog.get((family, variant))
            if meta:
                entry["label"] = meta["label"]
                entry["category"] = meta["category"]
            entries.append(entry)
        families[family] = {
            "discipline": FAMILY_DISCIPLINES.get(family, "unmapped"),
            "variants": entries,
        }
    return families


# --------------------------------------------------------------------------
# describe
# --------------------------------------------------------------------------

def tool_manifest(session) -> dict:
    """Self-describing contract for a vision model: verbs, units, vocabulary."""
    return {
        "api_version": 1,
        "session": {
            "mode": "edit" if session.edit else "pdf",
            "document": session.pdf.name if session.pdf else (session.edit.name if session.edit else ""),
            "page_count": session.page_count,
            "initial_page": session.start_page,
            "render_dpi": session.dpi,
            "write_auth": "send the X-Labeler-Token header from GET /api/session on every POST",
        },
        "coordinates": {
            "system": "original_page_pixels_top_left_y_down",
            "units": f"pixels of the rendered page at {session.dpi} dpi; origin is the top-left corner, x grows right, y grows down",
            "box": "[x0, y0, x1, y1] with x0 < x1 and y0 < y1, entirely inside [0,0,width,height]",
            "rotation_deg": "in-plane rotation, CCW in image coordinates (y down), so a positive angle looks clockwise on screen; 0 for an upright symbol",
            "anchors": "optional named points [x,y] in the same pixel space, e.g. circle_center for round symbols",
        },
        "verbs": {
            "describe": {"method": "GET", "path": "/api/agent/tools"},
            "read": {"method": "GET", "path": "/api/agent/context", "query": {"page": "optional page index"},
                     "params": {"include_walls": "1 to return every wall segment", "max_boxes": "cap on returned boxes"}},
            "see": {"method": "GET", "path": "/api/agent/view",
                    "query": {"page": "optional page index",
                              "region": "x0,y0,x1,y1 in page pixels; omit for the whole sheet",
                              "max_side": f"longest output edge in pixels, default {DEFAULT_MAX_SIDE}, max {MAX_MAX_SIDE}",
                              "grid": "1 to draw a labelled coordinate grid, default 1",
                              "labels": "1 to label each existing box with its index, default 1",
                              "hide_existing": "1 to render the sheet with no boxes drawn"}},
            "act": {"method": "POST", "path": "/api/agent/act",
                    "body": {"page": "optional page index query parameter",
                             "revision": "revision from the last read; null only for a never-saved page",
                             "dry_run": "true to validate and preview without writing",
                             "actions": f"list of up to {MAX_ACTIONS} actions, applied in order"}},
        },
        "actions": {
            "add_box": {
                "fields": {"family": "required symbol family", "variant": "required symbol variant",
                           "box": "required [x0,y0,x1,y1]", "rotation_deg": "optional",
                           "id": "optional instance id, generated when omitted",
                           "attributes": "optional device attributes object",
                           "text_attributes": "optional list of strings transcribed from the drawing",
                           "truncated": "symbol runs off the sheet edge", "occluded": "symbol is hidden by something",
                           "snap": "true to attach to verified wall geometry when the session has any"},
                "returns": {"id": "instance id to use for later edits"},
            },
            "update_box": {
                "fields": {"id": "required", "box": "optional replacement [x0,y0,x1,y1]",
                           "rotation_deg": "optional", "family": "optional", "variant": "optional",
                           "attributes": "optional replacement attribute object",
                           "text_attributes": "optional replacement string list"},
            },
            "delete_box": {"fields": {"id": "required"}},
            "snap_box": {"fields": {"id": "required", "apply": "true to move the box onto the wall centerline"}},
            "set_page": {
                "fields": {
                    "verified_no_symbols": "required boolean: set/clear the page-level 'No target symbols on this page' tag (same as the web GUI); requires zero active boxes when true",
                },
            },
            "assess_page": {
                "fields": {"page_kind": "required, one of " + ", ".join(PAGE_KINDS),
                           "symbols_present": "required, one of " + ", ".join(SYMBOLS_PRESENT),
                           "labeling_complete": "required boolean: you believe every visible symbol on the sheet now has a box",
                           "note": "optional short note (<= 500 chars) for the human reviewer"},
                "note": "advisory only; it never marks the page as a verified negative",
            },
        },
        "vocabulary": _vocabulary(session),
        "workflow": [
            "read /api/agent/context to get the page size, revision and any existing boxes",
            "see /api/agent/view with max_side around 1200 to find candidate regions on the whole sheet",
            "see again with region set to a candidate area and max_side 1200-2000 to judge the symbol shape at readable zoom",
            "act with the boxes, then see the same region again to confirm the boxes sit on the symbols",
            "act again with dry_run true when unsure; it validates without writing",
            "finish every page with one assess_page action",
        ],
        "rules": [
            "boxes written here are proposals: they are stored unreviewed, uncertain and unverified, and a human must accept them",
            "every write must carry the revision from the context you read; a stale revision is rejected with 409",
            "a box is the outer bounds of the symbol glyph, not of the text label that names it",
            "use set_page with verified_no_symbols true (requiring no active boxes) to tag a page as symbol-free just like the web GUI, paired with assess_page",
            "an existing box is not evidence that a neighbouring symbol is already labelled; check the region view",
        ],
    }


# --------------------------------------------------------------------------
# read
# --------------------------------------------------------------------------

def _compact_box(session, item: dict) -> dict:
    family = str(item.get("symbol_family", "unknown_symbol"))
    variant = str(item.get("symbol_variant", "unspecified"))
    out = {
        "id": item.get("instance_id"),
        "family": family,
        "variant": variant,
        "box": [round(float(v), 1) for v in item.get("bbox_xyxy", [])],
    }
    meta = _catalog_entry(session, family, variant)
    if meta and meta.get("label"):
        out["label"] = meta["label"]
    rotation = float(item.get("rotation_degrees") or 0.0)
    if rotation:
        out["rotation_deg"] = round(rotation, 2)
    status = item.get("review_status")
    if status == "accepted":
        out["status"] = "verified"
    elif status == "unreviewed" or item.get("uncertain"):
        out["status"] = "proposed"
    else:
        out["status"] = "draft"
    if item.get("is_truncated"):
        out["truncated"] = True
    if item.get("is_occluded"):
        out["occluded"] = True
    attributes = item.get("device_attributes")
    if isinstance(attributes, dict) and attributes:
        out["attributes"] = attributes
    texts = item.get("attributes_from_text")
    if isinstance(texts, list) and texts:
        out["text_attributes"] = texts
    return out


def _counts(boxes: list[dict]) -> dict:
    by_family: dict[str, int] = {}
    for box in boxes:
        key = f"{box.get('family')}/{box.get('variant')}"
        by_family[key] = by_family.get(key, 0) + 1
    return {"total": len(boxes), "by_family": dict(sorted(by_family.items()))}


def page_context(session, index: int, *, include_walls: bool = False,
                 max_boxes: int = MAX_CONTEXT_BOXES) -> dict:
    doc, revision, _ = session.page_document(index)
    instances = doc.get("instances") or []
    boxes = [_compact_box(session, item) for item in instances[:max_boxes]]
    walls = session.walls or []
    wall_summary: dict = {"count": len(walls)}
    if walls:
        xs = [c for w in walls for c in (w["start"][0], w["end"][0])]
        ys = [c for w in walls for c in (w["start"][1], w["end"][1])]
        wall_summary["bbox"] = [round(min(xs), 1), round(min(ys), 1), round(max(xs), 1), round(max(ys), 1)]
        wall_summary["note"] = "verified wall centerlines available for snapping"
    else:
        wall_summary["note"] = "no verified wall geometry in this session; snapping is a no-op"
    if include_walls:
        wall_summary["segments"] = walls

    return {
        "page_index": index,
        "page_count": session.page_count,
        "document": session.pdf.name if session.pdf else (session.edit.name if session.edit else ""),
        "revision": revision,
        "width": int(doc["image_width"]),
        "height": int(doc["image_height"]),
        "coordinates": "[x0,y0,x1,y1] in page pixels, origin top-left, y down",
        "boxes": boxes,
        "boxes_truncated": len(instances) > len(boxes),
        "counts": _counts(boxes),
        "page_flags": {
            "verified_no_symbols": bool(doc.get("verified_no_symbols", False)),
            "annotation_completeness": doc.get("annotation_completeness", "partial"),
            "vlm_assessment": doc.get("vlm_assessment"),
        },
        "walls": wall_summary,
        "image_url": f"/api/image?page={index}",
        "view_url": f"/api/agent/view?page={index}",
        "act_url": f"/api/agent/act?page={index}",
    }


# --------------------------------------------------------------------------
# see
# --------------------------------------------------------------------------

@lru_cache(maxsize=24)
def _font(size: int) -> ImageFont.FreeTypeFont:
    for candidate in _FONT_CANDIDATES:
        path = Path(candidate)
        if path.is_file():
            try:
                return ImageFont.truetype(str(path), size)
            except OSError:
                continue
    try:
        return ImageFont.load_default(size=size)
    except TypeError:                                    # Pillow < 10.1
        return ImageFont.load_default()


def _box_color(family: str) -> tuple[int, int, int]:
    return _PALETTE[zlib.crc32(family.encode("utf-8")) % len(_PALETTE)]


def _parse_region(raw: str, width: int, height: int) -> tuple[int, int, int, int]:
    parts = raw.split(",")
    if len(parts) != 4:
        raise ValueError("region must be four numbers x0,y0,x1,y1")
    try:
        x0, y0, x1, y1 = (float(p) for p in parts)
    except ValueError:
        raise ValueError("region must contain four numbers") from None
    x0, y0 = max(0, int(math.floor(x0))), max(0, int(math.floor(y0)))
    x1, y1 = min(width, int(math.ceil(x1))), min(height, int(math.ceil(y1)))
    if x1 - x0 < 8 or y1 - y0 < 8:
        raise ValueError("region must be at least 8x8 pixels inside the page")
    return x0, y0, x1, y1


def _nice_step(span: float) -> int:
    target = max(span / 10.0, 1.0)
    for step in (25, 50, 100, 200, 250, 500, 1000, 2000, 5000):
        if step >= target:
            return step
    return 10000


def render_view(session, index: int, *, region: str | tuple[int, int, int, int] | None = None,
                max_side: int = DEFAULT_MAX_SIDE, grid: bool = True,
                labels: bool = True, hide_existing: bool = False,
                marks: list[dict] | None = None, side_image: Image.Image | None = None,
                as_image: bool = False) -> tuple[bytes, str] | Image.Image:
    """Render the page (or a region) with existing boxes, a grid and a scale caption.

    ``marks`` are numbered candidate boxes (``{"id": "m3", "box": [...]}``) drawn
    in a distinct style; ``side_image`` (e.g. the document legend) is pasted to
    the right of the view so one image carries both.
    """
    doc, revision, image_path = session.page_document(index)
    page_w, page_h = int(doc["image_width"]), int(doc["image_height"])
    if isinstance(region, str):
        region = _parse_region(region, page_w, page_h)
    x0, y0, x1, y1 = region or (0, 0, page_w, page_h)
    crop_w, crop_h = x1 - x0, y1 - y0
    scale = min(1.0, max_side / float(max(crop_w, crop_h)))
    out_w, out_h = max(1, round(crop_w * scale)), max(1, round(crop_h * scale))

    with Image.open(image_path) as source:
        page = source.convert("RGB").crop((x0, y0, x1, y1))
        page = page.resize((out_w, out_h), Image.LANCZOS)

    canvas = Image.new("RGB", (out_w, out_h + CAPTION_HEIGHT), (24, 26, 30))
    canvas.paste(page, (0, CAPTION_HEIGHT))
    draw = ImageDraw.Draw(canvas, "RGBA")
    origin_y = CAPTION_HEIGHT

    def to_out(px: float, py: float) -> tuple[float, float]:
        return ((px - x0) * scale, origin_y + (py - y0) * scale)

    if grid:
        step = _nice_step(max(crop_w, crop_h))
        small = _font(11)
        start_x = int(math.ceil(x0 / step)) * step
        start_y = int(math.ceil(y0 / step)) * step
        for gx in range(start_x, x1 + 1, step):
            dx, _ = to_out(gx, y0)
            draw.line([(dx, origin_y), (dx, origin_y + out_h)], fill=(90, 140, 190, 90), width=1)
            if dx + 34 < out_w:
                draw.text((dx + 2, origin_y + 2), str(gx), font=small, fill=(255, 236, 140, 230))
        for gy in range(start_y, y1 + 1, step):
            _, dy = to_out(x0, gy)
            draw.line([(0, dy), (out_w, dy)], fill=(90, 140, 190, 90), width=1)
            if dy + 12 < origin_y + out_h:
                draw.text((2, dy + 1), str(gy), font=small, fill=(255, 236, 140, 230))

    instances = doc.get("instances") or []
    if not hide_existing:
        label_font = _font(13)
        line_width = max(2, int(round(2.4 * min(1.0, scale * 4))))
        for number, item in enumerate(instances, start=1):
            box = item.get("bbox_xyxy")
            if not box or len(box) != 4:
                continue
            bx0, by0, bx1, by1 = (float(v) for v in box)
            if bx1 < x0 or bx0 > x1 or by1 < y0 or by0 > y1:
                continue
            family = str(item.get("symbol_family", "unknown_symbol"))
            color = _box_color(family)
            ax0, ay0 = to_out(bx0, by0)
            ax1, ay1 = to_out(bx1, by1)
            draw.rectangle([ax0, ay0, ax1, ay1], outline=(*color, 255), width=line_width)
            rotated = item.get("bbox_rotated")
            if isinstance(rotated, dict):
                corners = RotatedBox(
                    float(rotated["cx"]), float(rotated["cy"]),
                    float(rotated["w"]), float(rotated["h"]),
                    float(rotated.get("angle_deg", 0.0)),
                ).corners()
                points = [to_out(px, py) for px, py in corners]
                draw.line(points + [points[0]], fill=(*color, 255), width=line_width)
            if labels:
                tag = f"{number}:{family}"
                tw = draw.textlength(tag, font=label_font)
                ty = max(origin_y, ay0 - 15)
                draw.rectangle([ax0, ty, ax0 + tw + 5, ty + 15], fill=(*color, 225))
                draw.text((ax0 + 2, ty + 1), tag, font=label_font, fill=(255, 255, 255, 255))

    if marks:
        mark_font = _font(12)
        for mark in marks:
            bx0, by0, bx1, by1 = (float(v) for v in mark["box"])
            ax0, ay0 = to_out(bx0, by0)
            ax1, ay1 = to_out(bx1, by1)
            draw.rectangle([ax0 - 1, ay0 - 1, ax1 + 1, ay1 + 1], outline=(0, 170, 255, 255), width=2)
            tag = str(mark["id"])
            tw = draw.textlength(tag, font=mark_font)
            tx, ty = ax1 + 1, max(origin_y, ay0 - 13)
            draw.rectangle([tx, ty, tx + tw + 4, ty + 13], fill=(0, 110, 200, 235))
            draw.text((tx + 2, ty), tag, font=mark_font, fill=(255, 255, 255, 255))

    caption = (
        f"page {index + 1}/{session.page_count}   region {x0},{y0},{x1},{y1}   "
        f"scale {scale:.4f}   image_xy = ({x0},{y0}) + display_xy / {scale:.4f}   "
        f"boxes {len(instances)}   rev {str(revision)[:12] or 'unsaved'}"
    )
    draw.text((8, 5), caption, font=_font(14), fill=(230, 235, 245, 255))

    if side_image is not None:
        side = side_image.convert("RGB")
        if side.height > canvas.height:
            ratio = canvas.height / side.height
            side = side.resize((max(1, int(side.width * ratio)), canvas.height), Image.LANCZOS)
        joined = Image.new("RGB", (canvas.width + side.width + 6, canvas.height), (24, 26, 30))
        joined.paste(canvas, (0, 0))
        joined.paste(side, (canvas.width + 6, 0))
        canvas = joined
    if as_image:
        return canvas

    buffer = io.BytesIO()
    canvas.save(buffer, format="PNG", optimize=True)
    return buffer.getvalue(), "image/png"


# --------------------------------------------------------------------------
# act
# --------------------------------------------------------------------------

def validate_assessment(raw: object) -> dict:
    """Normalise an advisory page assessment. It is never a verified negative."""
    if not isinstance(raw, dict):
        raise ValueError("assessment must be an object")
    allowed = {"page_kind", "symbols_present", "labeling_complete", "note", "agent",
               "assessed_at", "advisory"}
    unknown = sorted(set(raw) - allowed)
    if unknown:
        raise ValueError("unsupported assessment field: " + ", ".join(unknown))
    if raw.get("advisory", True) is not True:
        raise ValueError("assessments are always advisory")
    if raw.get("page_kind") not in PAGE_KINDS:
        raise ValueError("page_kind must be one of " + ", ".join(PAGE_KINDS))
    if raw.get("symbols_present") not in SYMBOLS_PRESENT:
        raise ValueError("symbols_present must be one of " + ", ".join(SYMBOLS_PRESENT))
    if type(raw.get("labeling_complete")) is not bool:
        raise ValueError("labeling_complete must be a boolean")
    out = {key: raw[key] for key in ("page_kind", "symbols_present", "labeling_complete")}
    for key, limit in (("note", 500), ("agent", 120), ("assessed_at", 40)):
        if key in raw and raw[key] is not None:
            if not isinstance(raw[key], str) or len(raw[key]) > limit:
                raise ValueError(f"{key} must be a string of at most {limit} characters")
            out[key] = raw[key].strip()
    out["advisory"] = True
    return out


def _utc_now() -> str:
    return _dt.datetime.now(_dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _box4(raw: object) -> list[float]:
    if not isinstance(raw, (list, tuple)) or len(raw) != 4:
        raise ValueError("box must be [x0, y0, x1, y1]")
    values = []
    for value in raw:
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise ValueError("box coordinates must be numbers")
        values.append(float(value))
    if not all(math.isfinite(v) for v in values):
        raise ValueError("box coordinates must be finite")
    return values


def _check_box(box: list[float], width: int, height: int) -> None:
    """Fast, well-attributed pre-check. web.validate_instances still runs on save."""
    x0, y0, x1, y1 = box
    if not (0 <= x0 < x1 <= width and 0 <= y0 < y1 <= height):
        raise ValueError(
            f"box {box} must have positive area inside the page (0,0,{width},{height})"
        )


def _apply_rotation(item: dict, box: list[float], angle: float) -> None:
    if abs(angle) <= 1e-6:
        item["bbox_xyxy"] = [round(v, 3) for v in box]
        item.pop("bbox_rotated", None)
        item["rotation_degrees"] = 0.0
        return
    if abs(angle) > 360:
        raise ValueError("rotation_deg must be within -360..360")
    cx, cy = (box[0] + box[2]) / 2.0, (box[1] + box[3]) / 2.0
    rotated = RotatedBox(cx, cy, box[2] - box[0], box[3] - box[1], angle)
    item["bbox_rotated"] = rotated.to_dict()
    item["bbox_xyxy"] = [round(v, 3) for v in rotated.aabb()]
    item["rotation_degrees"] = round(angle, 3)


def _new_id(instances: list[dict], page: int) -> str:
    used = {item.get("instance_id") for item in instances}
    prefix = f"a{page}_"
    counter = 1
    for item in instances:
        ident = str(item.get("instance_id") or "")
        if ident.startswith(prefix) and ident[len(prefix):].isdigit():
            counter = max(counter, int(ident[len(prefix):]) + 1)
    candidate = f"{prefix}{counter}"
    while candidate in used:
        counter += 1
        candidate = f"{prefix}{counter}"
    return candidate


def _find(instances: list[dict], ident: object) -> dict:
    if not isinstance(ident, str) or not ident:
        raise ValueError("id must be a non-empty string")
    for item in instances:
        if item.get("instance_id") == ident:
            return item
    raise ValueError(f"unknown box id {ident!r}; read /api/agent/context for current ids")


def _vocabulary_warning(session, ident: str, family: str, variant: str, warnings: list[str]) -> None:
    known = session.variants_by_family.get(family)
    if known is None:
        warnings.append(f"{ident}: family {family!r} is not in the vocabulary; check /api/agent/tools")
    elif variant != "unspecified" and variant not in known:
        warnings.append(f"{ident}: variant {variant!r} is not in the vocabulary for {family!r}")


def _mark_box(session, ident: object, page: int = 0) -> list[float]:
    """Box of a numbered candidate from the last ``marks`` call on this page."""
    from annotator import vlm_tools
    marks = vlm_tools.load_marks(session.page_address(page))
    if not isinstance(ident, str) or ident not in marks:
        raise ValueError(f"unknown mark {ident!r}; run marks on this region first")
    return [float(v) for v in marks[ident]]


def _maybe_tighten(session, page, action, box, *, angle: float, width, height) -> tuple[list[float], str | None]:
    if action.get("tighten", True) is False or abs(angle) > 1e-6:
        return box, None
    if type(action.get("tighten", True)) is not bool:
        raise ValueError("tighten must be a boolean")
    from annotator import vlm_tools
    _, _, image_path = session.page_document(page)
    new, note = vlm_tools.tighten_box(image_path, box)
    _check_box(new, width, height)
    return new, note


def _add_box(session, page, instances, action, *, width, height, warnings) -> dict:
    family = str(action.get("family") or action.get("symbol_family") or "").strip()
    if not family:
        raise ValueError("add_box requires 'family'")
    variant = str(action.get("variant") or action.get("symbol_variant") or "unspecified").strip()
    if "mark" in action:
        box = _mark_box(session, action["mark"], page)
    else:
        box = _box4(action.get("box") if "box" in action else action.get("bbox_xyxy"))
    _check_box(box, width, height)
    original = list(box)
    box, tighten_note = _maybe_tighten(
        session, page, action, box,
        angle=float(action.get("rotation_deg", action.get("angle_deg", 0.0)) or 0.0),
        width=width, height=height)

    ident = action.get("id")
    if ident is None:
        ident = _new_id(instances, page)
    elif not isinstance(ident, str) or not ident:
        raise ValueError("id must be a non-empty string")
    elif any(item.get("instance_id") == ident for item in instances):
        raise ValueError(f"id {ident!r} already exists on this page")

    item = pending_instance(
        ident, family, variant, box, review_source="vlm_agent",
        attributes_from_text=[str(s).strip() for s in (action.get("text_attributes") or []) if str(s).strip()],
        is_truncated=bool(action.get("truncated", False)),
        is_occluded=bool(action.get("occluded", False)))
    attributes = action.get("attributes", action.get("device_attributes"))
    if isinstance(attributes, dict) and attributes:
        item["device_attributes"] = attributes
    anchors = action.get("anchors")
    if isinstance(anchors, dict) and anchors:
        item["anchors"] = anchors

    angle = float(action.get("rotation_deg", action.get("angle_deg", 0.0)) or 0.0)
    _apply_rotation(item, box, angle)
    _check_box(item["bbox_xyxy"], width, height)

    if action.get("snap"):
        if session.walls:
            snap = session.resolve_snap_preview(page, _without_snap(item))
            item["snap"] = snap
            shrunk = snap.get("shrunk_bbox_xyxy")
            if shrunk:
                _apply_rotation(item, [float(v) for v in shrunk], angle)
                _check_box(item["bbox_xyxy"], width, height)
        else:
            warnings.append(f"{ident}: snap skipped, session has no verified wall geometry")

    _vocabulary_warning(session, ident, family, variant, warnings)
    instances.append(item)
    result = {"kind": "add_box", "id": ident, "box": item["bbox_xyxy"]}
    if "mark" in action:
        result["mark"] = action["mark"]
    if tighten_note and tighten_note.startswith("unchanged: no ink"):
        warnings.append(f"{ident}: no ink near the box centre; the box is probably on empty paper")
    if tighten_note:
        result["tighten"] = tighten_note
        if tighten_note == "tightened":
            result["box_before_tighten"] = [round(v, 1) for v in original]
    return result


def _without_snap(item: dict) -> dict:
    clean = dict(item)
    clean.pop("snap", None)
    return clean


def _update_box(session, page, instances, action, *, width, height, warnings) -> dict:
    item = _find(instances, action.get("id"))
    changed = []
    tighten_note = None
    if "box" in action or "bbox_xyxy" in action or "mark" in action:
        if "mark" in action:
            box = _mark_box(session, action["mark"], page)
        else:
            box = _box4(action.get("box") if "box" in action else action.get("bbox_xyxy"))
        _check_box(box, width, height)
        angle = float(action.get("rotation_deg", item.get("rotation_degrees") or 0.0) or 0.0)
        box, tighten_note = _maybe_tighten(session, page, action, box, angle=angle,
                                           width=width, height=height)
        _apply_rotation(item, box, angle)
        changed.append("box")
    elif action.get("tighten") is True:
        angle = float(item.get("rotation_degrees") or 0.0)
        box = [float(v) for v in item["bbox_xyxy"]]
        box, tighten_note = _maybe_tighten(session, page, action, box, angle=angle,
                                           width=width, height=height)
        _apply_rotation(item, box, angle)
        changed.append("box")
    elif "rotation_deg" in action:
        angle = float(action["rotation_deg"])
        current = item.get("bbox_rotated")
        if isinstance(current, dict):
            # Rotate about the existing oriented box so its size is preserved.
            box = [
                float(current["cx"]) - float(current["w"]) / 2.0,
                float(current["cy"]) - float(current["h"]) / 2.0,
                float(current["cx"]) + float(current["w"]) / 2.0,
                float(current["cy"]) + float(current["h"]) / 2.0,
            ]
        else:
            box = [float(v) for v in item["bbox_xyxy"]]
        _apply_rotation(item, box, angle)
        changed.append("rotation_deg")
    _check_box([float(v) for v in item["bbox_xyxy"]], width, height)

    for field, key in (("family", "symbol_family"), ("variant", "symbol_variant")):
        if field in action:
            value = str(action[field]).strip()
            if not value:
                raise ValueError(f"{field} must be a non-empty string")
            item[key] = value
            changed.append(field)
    if "text_attributes" in action:
        item["attributes_from_text"] = [str(s).strip() for s in (action["text_attributes"] or []) if str(s).strip()]
        changed.append("text_attributes")
    if "attributes" in action:
        attributes = action["attributes"] or {}
        if not isinstance(attributes, dict):
            raise ValueError("attributes must be an object")
        if attributes:
            item["device_attributes"] = attributes
        else:
            item.pop("device_attributes", None)
        changed.append("attributes")
    for field, key in (("truncated", "is_truncated"), ("occluded", "is_occluded"), ("uncertain", "uncertain")):
        if field in action:
            if type(action[field]) is not bool:
                raise ValueError(f"{field} must be a boolean")
            item[key] = action[field]
            changed.append(field)
    if not changed:
        raise ValueError("update_box needs at least one field to change")
    if "family" in changed or "variant" in changed:
        _vocabulary_warning(session, item["instance_id"], item["symbol_family"],
                            item["symbol_variant"], warnings)
    result = {"kind": "update_box", "id": item["instance_id"], "changed": changed,
              "box": [round(float(v), 3) for v in item["bbox_xyxy"]]}
    if tighten_note:
        result["tighten"] = tighten_note
    return result


def _delete_box(instances, action) -> dict:
    item = _find(instances, action.get("id"))
    instances.remove(item)
    return {"kind": "delete_box", "id": item["instance_id"]}


def _snap_box(session, page, instances, action, *, warnings) -> dict:
    item = _find(instances, action.get("id"))
    if not session.walls:
        warnings.append(f"{item['instance_id']}: snap skipped, session has no verified wall geometry")
        return {"kind": "snap_box", "id": item["instance_id"], "snap": None, "applied": False}
    snap = session.resolve_snap_preview(page, _without_snap(item))
    applied = False
    if action.get("apply") and snap.get("shrunk_bbox_xyxy"):
        shrunk = [float(v) for v in snap["shrunk_bbox_xyxy"]]
        _apply_rotation(item, shrunk, float(item.get("rotation_degrees") or 0.0))
        item["snap"] = snap
        applied = True
    return {"kind": "snap_box", "id": item["instance_id"], "snap": snap, "applied": applied,
            "box": [round(float(v), 3) for v in item["bbox_xyxy"]]}


def apply_actions(session, index: int, *, revision: object, actions: object,
                  dry_run: bool = False, agent: str | None = None) -> dict:
    """Apply a batch of box edits through the session's normal validation gates."""
    if not isinstance(actions, list) or not actions:
        raise ValueError("actions must be a non-empty list")
    if len(actions) > MAX_ACTIONS:
        raise ValueError(f"at most {MAX_ACTIONS} actions per request")

    doc, actual_revision, _ = session.page_document(index)
    width, height = int(doc["image_width"]), int(doc["image_height"])
    instances = copy.deepcopy(doc.get("instances") or [])
    rejected = copy.deepcopy(doc.get("rejected_proposals") or [])
    metadata: dict = {}
    applied: list[dict] = []
    warnings: list[str] = []

    for position, action in enumerate(actions):
        if not isinstance(action, dict):
            raise ValueError(f"action {position} must be an object")
        kind = action.get("kind") or action.get("op")
        try:
            if kind == "add_box":
                result = _add_box(session, index, instances, action, width=width, height=height,
                                  warnings=warnings)
            elif kind == "update_box":
                result = _update_box(session, index, instances, action, width=width, height=height,
                                     warnings=warnings)
            elif kind == "delete_box":
                result = _delete_box(instances, action)
            elif kind == "snap_box":
                result = _snap_box(session, index, instances, action, warnings=warnings)
            elif kind == "assess_page":
                fields = {k: v for k, v in action.items() if k not in ("kind", "op")}
                if agent:
                    fields["agent"] = agent[:120]
                fields["assessed_at"] = _utc_now()
                assessment = validate_assessment(fields)
                metadata["vlm_assessment"] = assessment
                result = {"kind": "assess_page", **{k: assessment[k] for k in
                          ("page_kind", "symbols_present", "labeling_complete")}}
            elif kind == "set_page":
                allowed = {"kind", "op", "verified_no_symbols"}
                unknown = sorted(set(action) - allowed)
                if unknown:
                    raise ValueError("unsupported set_page field: " + ", ".join(unknown))
                if "verified_no_symbols" not in action or type(action["verified_no_symbols"]) is not bool:
                    raise ValueError("verified_no_symbols must be a boolean")
                value = action["verified_no_symbols"]
                if value and instances:
                    if any(item.get("verified_ground_truth") is True for item in instances):
                        raise ValueError("cannot mark a page with human-verified boxes as symbol-free")
                    raise ValueError(
                        "verified symbol-free pages cannot contain active boxes; delete unverified proposals first"
                    )
                metadata["verified_no_symbols"] = value
                if value:
                    metadata["page_complete"] = False
                result = {"kind": "set_page", "verified_no_symbols": value}
            else:
                raise ValueError(
                    f"unknown action kind {kind!r}; use add_box, update_box, delete_box, snap_box, set_page or assess_page"
                )
        except (KeyError, TypeError, ValueError) as exc:
            message = str(exc) or exc.__class__.__name__
            raise ValueError(f"action {position} ({kind}): {message}") from None
        applied.append(result)

    if dry_run:
        session.validate_review_payload(index, revision=revision, instances=instances,
                                       rejected=rejected, metadata=metadata or None)
        new_revision = actual_revision
        written = False
        page_flags = {
            "verified_no_symbols": bool(
                metadata["verified_no_symbols"]
                if "verified_no_symbols" in metadata
                else doc.get("verified_no_symbols", False)
            )
        }
        if "vlm_assessment" in metadata:
            page_flags["vlm_assessment"] = metadata["vlm_assessment"]
        elif doc.get("vlm_assessment"):
            page_flags["vlm_assessment"] = doc["vlm_assessment"]
    else:
        new_revision = session.save(index, revision=revision, instances=instances,
                                    rejected=rejected, metadata=metadata or None)
        written = True
        saved = session.page_state(index)["document"]
        instances = saved.get("instances") or []
        page_flags = {"verified_no_symbols": bool(saved.get("verified_no_symbols", False))}
        if saved.get("vlm_assessment"):
            page_flags["vlm_assessment"] = saved["vlm_assessment"]

    touched = [entry["id"] for entry in applied if entry.get("id")]
    boxes = [_compact_box(session, item) for item in instances
             if item.get("instance_id") in touched]

    return {
        "page_index": index,
        "written": written,
        "dry_run": bool(dry_run),
        "revision": new_revision,
        "applied": applied,
        "warnings": warnings,
        "boxes": boxes,
        "counts": _counts([_compact_box(session, item) for item in instances]),
        "page_flags": page_flags,
        "view_url": f"/api/agent/view?page={index}",
    }
