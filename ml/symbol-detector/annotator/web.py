"""Local browser editor for blueprint training-label boxes.

Run from ml/symbol-detector:
    python -m annotator.web --pdf plans.pdf --out-dir labels/ --open-browser
    python -m annotator.web --edit labels/page.json --open-browser
    python -m annotator.web --edit labels/ --walls walls.json --open-browser
    python -m annotator.web --pdf plans.pdf --proposals scan_SAM3.json
    python -m annotator.web --pdf plans.pdf --page-presence review_queue.json

All coordinates are original rendered-page pixels, top-left origin. Pages
remain positive-only and non-exhaustive by default. SAM proposals are
unverified until individually accepted; the UI never attests full coverage.
No cloud requests, framework dependency, or remote listen address.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import math
import os
import re
import secrets
import socket
import struct
import tempfile
import threading
import time
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from annotator.model import (
    FAMILY_DISCIPLINES,
    FAMILY_VARIANT_SUGGESTIONS,
    Instance,
    RotatedBox,
    Sheet,
    SnapVector,
    discipline_for,
)

try:
    from blueprint_symbols.electrical_device_attributes import (
        ATTRIBUTE_SCHEMA,
        FLAG_ATTRIBUTES,
        LIST_FIELDS,
        NUMERIC_ATTRIBUTES,
        STRING_ATTRIBUTES,
        validate_device_attributes,
    )
except ImportError:
    # Standalone editor fallback: still enforce recognized attribute types and
    # reject invalid values even when the project-wide schema is not installed.
    ATTRIBUTE_SCHEMA = {}
    NUMERIC_ATTRIBUTES = {
        "voltage_v", "amperage_a", "wattage_w", "power_va",
        "pole_count", "wire_count", "phase_count",
    }
    STRING_ATTRIBUTES = {
        "nema_configuration", "circuit_label", "panel_label",
        "fixture_type_mark", "schedule_reference",
    }
    FLAG_ATTRIBUTES = {
        "emergency_power", "weatherproof", "gfci", "afci",
        "dimming", "switched", "dedicated", "floor_mount",
    }
    LIST_FIELDS = {
        "control_features", "protective_features", "sensor_types",
        "notification_features", "outlet_services",
    }

    def validate_device_attributes(device_type: str, attributes: dict) -> None:
        if not isinstance(attributes, dict):
            raise ValueError("attributes must be an object")
        for key, value in attributes.items():
            if key in NUMERIC_ATTRIBUTES:
                if type(value) not in (int, float) or not math.isfinite(value) or value <= 0:
                    raise ValueError(f"{key} must be a finite positive number")
                if key == "phase_count" and value not in (1, 3):
                    raise ValueError("phase_count must be 1 or 3")
                if key in ("pole_count", "wire_count") and not float(value).is_integer():
                    raise ValueError(f"{key} must be integer-valued")
            elif key in STRING_ATTRIBUTES:
                if not isinstance(value, str) or not value.strip():
                    raise ValueError(f"{key} must be a nonempty string")
            elif key in FLAG_ATTRIBUTES:
                if type(value) is not bool:
                    raise ValueError(f"{key} must be boolean")
            elif key in LIST_FIELDS:
                if not isinstance(value, list) or len(value) != len(set(map(str, value))):
                    raise ValueError(f"{key} must be a list of distinct values")
                if not all(isinstance(v, str) and v.strip() for v in value):
                    raise ValueError(f"{key} must contain nonempty strings")
            elif key == "evidence":
                if not isinstance(value, dict):
                    raise ValueError("evidence must be an object")
            elif value is None:
                raise ValueError("omit unknown attributes instead of writing null")


MAX_BODY_BYTES = 4 * 1024 * 1024
MAX_PDF_PIXELS = 30_000_000
ID_PATTERN = re.compile(r"^[a-zA-Z0-9_.:-]{1,120}$")
SHA256_PATTERN = re.compile(r"^[0-9a-fA-F]{64}$")
MODULE_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = MODULE_DIR.parents[2] if len(MODULE_DIR.parents) > 2 and (MODULE_DIR.parents[2] / "blueprints_and_libraries").is_dir() else Path.cwd()
DEFAULT_BLUEPRINTS_DIR = PROJECT_ROOT / "blueprints_and_libraries" / "1_blueprints"
DEFAULT_WORK_DIR = MODULE_DIR.parent / "work"

FAMILY_TO_ATTRIBUTE_SCHEMA = {
    "switch": "switch",
    "receptacle": "receptacle",
    "luminaire": "light_fixture",
    "light_fixture": "light_fixture",
    "fire_alarm_device": "fire_alarm_detector",
    "fire_alarm_detector": "fire_alarm_detector",
    "fire_alarm_notification": "fire_alarm_notification",
    "alarm_device": "fire_alarm_notification",
    "data_outlet": "data_outlet",
    "telecom_device": "data_outlet",
}


class LabelingError(ValueError):
    pass


class RevisionConflict(LabelingError):
    pass


def get_lan_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def _is_private_ip(host_part: str) -> bool:
    try:
        ip = socket.gethostbyname(host_part)
        parts = [int(p) for p in ip.split(".")]
        if len(parts) == 4:
            if parts[0] in (127, 10, 0):
                return True
            if parts[0] == 172 and 16 <= parts[1] <= 31:
                return True
            if parts[0] == 192 and parts[1] == 168:
                return True
    except Exception:
        pass
    return False


def _find_proposals_for_pdf(pdf: Path, work_dir: Path | None = None) -> Path | None:
    work = work_dir or DEFAULT_WORK_DIR
    stem = pdf.stem
    cleaned = sorted((work / "sam3_cleaned").glob(f"{stem}*_CLEANED.json"))
    if cleaned:
        return cleaned[0]
    circuitnet = sorted((work / "circuitnet_proposals").glob(f"{stem}*.json"))
    if circuitnet:
        return circuitnet[0]
    sam3 = sorted((work / "sam3_proposals").glob(f"{stem}*.json"))
    if sam3:
        return sam3[0]
    if (pdf.parent / "devices").is_dir():
        devs = sorted((pdf.parent / "devices").glob(f"{stem}*.json"))
        if devs:
            return devs[0]
    devs2 = sorted(pdf.parent.glob(f"{stem}_devices.json"))
    if devs2:
        return devs2[0]
    return None


def _import_fitz():
    try:
        import fitz  # type: ignore
        return fitz
    except ImportError as exc:
        raise LabelingError(
            "PyMuPDF (fitz) is required for --pdf mode. Install pymupdf or use --edit on an existing JSON."
        ) from exc


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for part in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(part)
    return digest.hexdigest()


def _atomic_bytes(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temp = tempfile.mkstemp(prefix="." + path.name + ".", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as dst:
            dst.write(data)
            dst.flush()
            os.fsync(dst.fileno())
        os.replace(temp, path)
    finally:
        if os.path.exists(temp):
            os.unlink(temp)


def _image_dimensions(path: Path) -> tuple[int, int] | None:
    """Read image (width, height) from PNG header or Pillow if available."""
    try:
        header = path.read_bytes()[:24]
        if len(header) >= 24 and header[:8] == b"\x89PNG\r\n\x1a\n" and header[12:16] == b"IHDR":
            w, h = struct.unpack(">II", header[16:24])
            return int(w), int(h)
    except OSError:
        pass
    try:
        from PIL import Image
        with Image.open(path) as img:
            return int(img.width), int(img.height)
    except Exception:
        return None


def _image_mime(path: Path) -> str:
    suffix = path.suffix.lower()
    if suffix in (".jpg", ".jpeg"):
        return "image/jpeg"
    if suffix == ".webp":
        return "image/webp"
    if suffix == ".gif":
        return "image/gif"
    if suffix == ".bmp":
        return "image/bmp"
    return "image/png"


def _resolve_edit_image(json_path: Path, raw_image_path: str) -> Path:
    """Resolve an annotation JSON's image_path relative to JSON parent, CWD, or absolute."""
    if not raw_image_path:
        raise LabelingError(f"annotation JSON {json_path.name} is missing image_path")
    candidate = Path(raw_image_path)
    attempts = []
    if candidate.is_absolute():
        attempts.append(candidate.resolve())
    else:
        attempts.append((json_path.parent / candidate).resolve())
        attempts.append((json_path.parent.parent / candidate).resolve())
        attempts.append((Path.cwd() / candidate).resolve())
    for path in attempts:
        if path.is_file():
            return path
    raise LabelingError(f"annotation image unavailable: {attempts[0]}")


def _valid_box(box: object, width: int, height: int) -> None:
    if not isinstance(box, list) or len(box) != 4:
        raise LabelingError("bbox_xyxy must contain four coordinates")
    if any(type(v) not in (int, float) or not math.isfinite(v) for v in box):
        raise LabelingError("bbox_xyxy coordinates must be finite numbers")
    x0, y0, x1, y1 = box
    if not (0 <= x0 < x1 <= width and 0 <= y0 < y1 <= height):
        raise LabelingError("bbox_xyxy must have positive area within the rendered image")


def _validate_anchors(anchors: object, width: int, height: int) -> None:
    if not isinstance(anchors, dict):
        raise LabelingError("anchors must be an object")
    for name, value in anchors.items():
        if not isinstance(name, str) or not name.strip() or len(name) > 80:
            raise LabelingError("anchor names must be nonempty strings")
        if not isinstance(value, dict):
            raise LabelingError(f"anchor {name!r} must be an object")
        xy = value.get("xy")
        if not isinstance(xy, list) or len(xy) != 2:
            raise LabelingError(f"anchor {name!r} xy must be [x, y]")
        if any(type(v) not in (int, float) or not math.isfinite(v) for v in xy):
            raise LabelingError(f"anchor {name!r} coordinates must be finite numbers")
        if not (0 <= xy[0] <= width and 0 <= xy[1] <= height):
            raise LabelingError(f"anchor {name!r} must be within image bounds")
        if "visible" in value and type(value["visible"]) is not bool:
            raise LabelingError(f"anchor {name!r} visible must be boolean")
        if "annotated" in value and type(value["annotated"]) is not bool:
            raise LabelingError(f"anchor {name!r} annotated must be boolean")


def _validate_snap_vector(sv: object, width: int, height: int) -> None:
    if not isinstance(sv, dict):
        raise LabelingError("snap_vector must be an object")
    origin = sv.get("origin")
    direction = sv.get("direction")
    if (not isinstance(origin, list) or len(origin) != 2 or
            not isinstance(direction, list) or len(direction) != 2):
        raise LabelingError("snap_vector requires 2D origin and direction lists")
    if any(type(v) not in (int, float) or not math.isfinite(v) for v in (*origin, *direction)):
        raise LabelingError("snap_vector coordinates must be finite numbers")
    if not (0 <= origin[0] <= width and 0 <= origin[1] <= height):
        raise LabelingError("snap_vector origin must be within image bounds")
    if math.hypot(direction[0], direction[1]) < 1e-6:
        raise LabelingError("snap_vector direction must have non-zero length")


def validate_instances(instances: object, width: int, height: int) -> list[dict]:
    if not isinstance(instances, list) or len(instances) > 150_000:
        raise LabelingError("instances must be a bounded list")
    seen: set[str] = set()
    for item in instances:
        if not isinstance(item, dict):
            raise LabelingError("each instance must be an object")
        ident = item.get("instance_id")
        if not isinstance(ident, str) or not ID_PATTERN.fullmatch(ident) or ident in seen:
            raise LabelingError("instance IDs must be unique, nonempty, and safe")
        seen.add(ident)
        family = item.get("symbol_family")
        variant = item.get("symbol_variant", "unspecified")
        if (not isinstance(family, str) or not family.strip() or len(family) > 100 or
                not isinstance(variant, str) or not variant.strip() or len(variant) > 160):
            raise LabelingError("each instance needs a symbol family and variant")
        _valid_box(item.get("bbox_xyxy"), width, height)
        rotated = item.get("bbox_rotated")
        if rotated is not None:
            if not isinstance(rotated, dict):
                raise LabelingError("bbox_rotated must be an object")
            values = [rotated.get(k) for k in ("cx", "cy", "w", "h", "angle_deg")]
            if any(type(v) not in (int, float) or not math.isfinite(v) for v in values):
                raise LabelingError("bbox_rotated must contain finite numbers")
            if values[2] <= 0 or values[3] <= 0 or not -360 <= values[4] <= 360:
                raise LabelingError("invalid rotated dimensions or angle")
        if type(item.get("uncertain", False)) is not bool:
            raise LabelingError("uncertain must be boolean")
        if "is_occluded" in item and type(item["is_occluded"]) is not bool:
            raise LabelingError("is_occluded must be boolean")
        if "is_truncated" in item and type(item["is_truncated"]) is not bool:
            raise LabelingError("is_truncated must be boolean")
        status = item.get("review_status")
        if status is not None and status not in ("unreviewed", "accepted"):
            raise LabelingError("rejected proposals must be removed from active instances")
        if status == "accepted":
            if (item.get("review_source") != "human" or
                    item.get("verified_ground_truth") is not True or
                    item.get("uncertain") is True):
                raise LabelingError("accepted annotations require explicit human verification")
        if status == "unreviewed" and (
                item.get("verified_ground_truth") is True or
                item.get("uncertain") is not True):
            raise LabelingError("unreviewed proposals must remain uncertain and unverified")
        if item.get("verified_ground_truth") is True and item.get("uncertain") is True:
            raise LabelingError("a verified annotation cannot remain uncertain")
        if rotated is not None:
            oriented = RotatedBox(*values).aabb()
            if any(abs(a - b) > 0.08 for a, b in zip(oriented, item["bbox_xyxy"])):
                raise LabelingError("bbox_rotated and bbox_xyxy disagree")
        if "attributes_from_text" in item and item["attributes_from_text"] is not None:
            attrs = item["attributes_from_text"]
            if not isinstance(attrs, list) or any(not isinstance(s, str) for s in attrs):
                raise LabelingError("attributes_from_text must be a list of strings")
        if "anchors" in item and item["anchors"] is not None:
            _validate_anchors(item["anchors"], width, height)
        if "snap_vector" in item and item["snap_vector"] is not None:
            _validate_snap_vector(item["snap_vector"], width, height)
        if "device_attributes" in item and item["device_attributes"] is not None:
            schema_key = FAMILY_TO_ATTRIBUTE_SCHEMA.get(family, family)
            try:
                validate_device_attributes(schema_key, item["device_attributes"])
            except ValueError as exc:
                raise LabelingError(f"invalid device_attributes: {exc}") from exc
    return instances


def _normalize_instance_for_save(item: dict) -> dict:
    """Keep derived fields synchronized while preserving all extra keys."""
    out = copy.deepcopy(item)
    family = str(out["symbol_family"]).strip()
    variant = str(out.get("symbol_variant") or "unspecified").strip()
    out["symbol_family"] = family
    out["symbol_variant"] = variant
    out["discipline"] = discipline_for(family)
    out["device_type"] = family
    out["bbox_xyxy"] = [round(float(v), 3) for v in out["bbox_xyxy"]]
    if out.get("bbox_rotated") is not None:
        rb = out["bbox_rotated"]
        angle = round(float(rb["angle_deg"]), 3)
        if abs(angle) <= 1e-6:
            out.pop("bbox_rotated", None)
            out["rotation_degrees"] = 0.0
        else:
            cx = round(float(rb["cx"]), 3)
            cy = round(float(rb["cy"]), 3)
            w = round(float(rb["w"]), 3)
            h = round(float(rb["h"]), 3)
            out["bbox_rotated"] = {"cx": cx, "cy": cy, "w": w, "h": h, "angle_deg": angle}
            out["bbox_xyxy"] = [round(v, 3) for v in RotatedBox(cx, cy, w, h, angle).aabb()]
            out["rotation_degrees"] = angle
    else:
        out.pop("bbox_rotated", None)
        out["rotation_degrees"] = round(float(out.get("rotation_degrees", 0.0) or 0.0), 3)
    out["is_occluded"] = bool(out.get("is_occluded", False))
    out["is_truncated"] = bool(out.get("is_truncated", False))
    if "attributes_from_text" in out and out["attributes_from_text"] is not None:
        out["attributes_from_text"] = [s.strip() for s in out["attributes_from_text"] if s.strip()]
    else:
        out["attributes_from_text"] = []
    if "anchors" in out:
        if not out["anchors"]:
            out.pop("anchors", None)
        else:
            cleaned_anchors = {}
            for name, anchor in out["anchors"].items():
                cleaned_anchors[name.strip()] = {
                    "xy": [round(float(anchor["xy"][0]), 3), round(float(anchor["xy"][1]), 3)],
                    "visible": bool(anchor.get("visible", True)),
                    "annotated": bool(anchor.get("annotated", True)),
                }
            out["anchors"] = cleaned_anchors
    if "snap_vector" in out:
        if not out["snap_vector"]:
            out.pop("snap_vector", None)
        else:
            sv = out["snap_vector"]
            dx, dy = float(sv["direction"][0]), float(sv["direction"][1])
            norm = math.hypot(dx, dy)
            if norm > 1e-9:
                dx, dy = dx / norm, dy / norm
            out["snap_vector"] = {
                "origin": [round(float(sv["origin"][0]), 3), round(float(sv["origin"][1]), 3)],
                "direction": [round(dx, 4), round(dy, 4)],
            }
    if "device_attributes" in out and not out["device_attributes"]:
        out.pop("device_attributes", None)
    return out


def _normalize_loaded_document(data: dict) -> dict:
    """Normalize legacy or external annotation JSONs without dropping fields."""
    doc = copy.deepcopy(data)
    doc.setdefault("coordinate_system", "original_page_pixels_top_left_y_down")
    doc.setdefault("rejected_proposals", [])
    doc.setdefault("instances", [])
    doc.setdefault("verified_no_symbols", False)
    normalized_instances = []
    for idx, raw in enumerate(doc["instances"]):
        if not isinstance(raw, dict):
            continue
        inst = dict(raw)
        if not inst.get("instance_id"):
            inst["instance_id"] = f"legacy_{idx + 1}"
        family = str(inst.get("symbol_family") or inst.get("device_type") or "unknown_symbol")
        inst["symbol_family"] = family
        inst["symbol_variant"] = str(inst.get("symbol_variant") or "unspecified")
        inst.setdefault("discipline", discipline_for(family))
        inst.setdefault("device_type", family)
        inst.setdefault("attributes_from_text", [])
        inst.setdefault("is_occluded", False)
        inst.setdefault("is_truncated", False)
        if "bbox_rotated" in inst and inst["bbox_rotated"] is not None:
            inst.setdefault("rotation_degrees", float(inst["bbox_rotated"].get("angle_deg", 0.0)))
        else:
            inst.setdefault("rotation_degrees", 0.0)
        normalized_instances.append(inst)
    doc["instances"] = normalized_instances
    return doc


def _is_annotation_json(path: Path) -> bool:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return isinstance(data, dict) and isinstance(data.get("instances"), list) and "image_path" in data
    except Exception:
        return False


def _load_walls(path: Path | None) -> list[dict]:
    if path is None:
        return []
    raw = json.loads(Path(path).read_text(encoding="utf-8"))
    items = raw.get("walls", []) if isinstance(raw, dict) else raw
    if not isinstance(items, list):
        raise LabelingError("walls JSON must be a list or contain a 'walls' list")
    cleaned = []
    for w in items:
        if not isinstance(w, dict):
            continue
        s, e = w.get("start"), w.get("end")
        if (isinstance(s, list) and len(s) == 2 and isinstance(e, list) and len(e) == 2 and
                all(type(v) in (int, float) and math.isfinite(v) for v in (*s, *e)) and
                math.hypot(e[0] - s[0], e[1] - s[1]) > 1e-6):
            cleaned.append({
                "start": [float(s[0]), float(s[1])],
                "end": [float(e[0]), float(e[1])],
                "source": str(w.get("source", "verified_wall_centerline")),
            })
    return cleaned


def _geometric_wall_snap(instance_dict: dict, walls: list[dict], max_distance_px: float = 48.0) -> dict:
    """Compute wall-centerline intersection using explicit snap_vector or box axis."""
    bb = instance_dict["bbox_xyxy"]
    anchors = instance_dict.get("anchors") or {}
    sv = instance_dict.get("snap_vector")
    if isinstance(anchors.get("circle_center"), dict) and anchors["circle_center"].get("xy"):
        ox, oy = map(float, anchors["circle_center"]["xy"])
        origin_source = "circle_center"
    elif isinstance(anchors.get("visual_center"), dict) and anchors["visual_center"].get("xy"):
        ox, oy = map(float, anchors["visual_center"]["xy"])
        origin_source = "visual_center"
    elif isinstance(sv, dict) and sv.get("origin"):
        ox, oy = map(float, sv["origin"])
        origin_source = "snap_vector_origin"
    else:
        ox, oy = (bb[0] + bb[2]) / 2.0, (bb[1] + bb[3]) / 2.0
        origin_source = "bbox_center"

    axis = None
    if isinstance(sv, dict) and sv.get("direction"):
        dx, dy = float(sv["direction"][0]), float(sv["direction"][1])
        n = math.hypot(dx, dy)
        if n > 1e-9:
            axis = (dx / n, dy / n)
    elif instance_dict.get("bbox_rotated"):
        ang = math.radians(float(instance_dict["bbox_rotated"].get("angle_deg", 0.0)))
        axis = (math.cos(ang), math.sin(ang))

    best = None
    for idx, w in enumerate(walls):
        sx, sy = w["start"]
        ex, ey = w["end"]
        wx, wy = ex - sx, ey - sy
        wlen2 = wx * wx + wy * wy
        if wlen2 < 1e-9:
            continue
        if axis is not None:
            # Ray/line intersection: (ox, oy) + t * axis = (sx, sy) + u * (wx, wy)
            denom = axis[0] * wy - axis[1] * wx
            if abs(denom) > 1e-6:
                t = ((sx - ox) * wy - (sy - oy) * wx) / denom
                u = ((sx - ox) * axis[1] - (sy - oy) * axis[0]) / denom
                if 0.0 <= u <= 1.0 and abs(t) <= max_distance_px:
                    px, py = ox + t * axis[0], oy + t * axis[1]
                    dist = abs(t)
                    if best is None or dist < best["distance_px"]:
                        best = {
                            "kind": "wall_centerline_intersection",
                            "point": [round(px, 3), round(py, 3)],
                            "wall_index": idx,
                            "distance_px": round(dist, 3),
                            "quality": "source_geometry" if origin_source != "bbox_center" else "estimated",
                            "origin_source": origin_source,
                        }
                    continue
        # Fallback: perpendicular projection onto wall segment
        u = max(0.0, min(1.0, ((ox - sx) * wx + (oy - sy) * wy) / wlen2))
        px, py = sx + u * wx, sy + u * wy
        dist = math.hypot(px - ox, py - oy)
        if dist <= max_distance_px and (best is None or dist < best["distance_px"]):
            best = {
                "kind": "wall_centerline_intersection",
                "point": [round(px, 3), round(py, 3)],
                "wall_index": idx,
                "distance_px": round(dist, 3),
                "quality": "source_geometry" if origin_source != "bbox_center" else "estimated",
                "origin_source": origin_source,
            }
    if best is not None:
        return best
    return {
        "kind": origin_source if origin_source != "bbox_center" else "bbox_center_provisional",
        "point": [round(ox, 3), round(oy, 3)],
        "wall_index": None,
        "quality": "source_geometry" if origin_source in ("circle_center", "visual_center") else "provisional",
    }


def _attribute_schema_payload() -> dict:
    return {
        "schemas": ATTRIBUTE_SCHEMA,
        "family_map": FAMILY_TO_ATTRIBUTE_SCHEMA,
        "numeric": sorted(NUMERIC_ATTRIBUTES),
        "string": sorted(STRING_ATTRIBUTES),
        "flags": sorted(FLAG_ATTRIBUTES),
        "list_fields": sorted(LIST_FIELDS),
    }



def _load_neca_subtypes(catalog_root: Path | None) -> dict[str, list[str]]:
    merged: dict[str, list[str]] = {k: list(v) for k, v in FAMILY_VARIANT_SUGGESTIONS.items()}
    if catalog_root is None:
        return merged
    try:
        from blueprint_symbols.neca_prompt_catalog import list_neca_prompt_specs
        specs, _ = list_neca_prompt_specs(Path(catalog_root))
    except Exception:
        return merged
    neca_to_family = {
        "light_fixture": "luminaire",
        "fire_alarm_detector": "fire_alarm_device",
        "fire_alarm_notification": "fire_alarm_device",
        "fire_alarm_control": "fire_alarm_device",
        "occupancy_sensor": "sensor",
        "power_distribution_equipment": "panelboard",
        "motor_control": "disconnect",
        "audio_visual_device": "telecom_device",
        "communications_equipment": "telecom_device",
        "security_device": "telecom_device",
        "nurse_call_device": "telecom_device",
    }
    for spec in specs:
        fam = neca_to_family.get(spec.device_type, spec.device_type)
        bucket = merged.setdefault(fam, [])
        if spec.subtype and spec.subtype not in bucket:
            bucket.append(spec.subtype)
    return merged


class LabelingSession:
    def __init__(
        self, *, pdf: Path | None = None, edit: Path | None = None,
        blueprints_dir: Path | None = None,
        out_dir: Path | None = None, page: int = 0, dpi: int = 150,
        proposals: Path | None = None, walls: Path | None = None, neca_catalog: Path | None = None,
        page_presence: Path | None = None,
    ) -> None:
        if (pdf is None) == (edit is None):
            raise LabelingError("specify exactly one of --pdf or --edit")
        if not 30 <= dpi <= 600:
            raise LabelingError("DPI must be between 30 and 600")
        self.pdf = Path(pdf).resolve() if pdf else None
        self.edit = Path(edit).resolve() if edit else None
        self.blueprints_dir = Path(blueprints_dir).resolve() if blueprints_dir else (
            self.pdf.parent if self.pdf else DEFAULT_BLUEPRINTS_DIR
        )
        self._initial_out_dir = Path(out_dir).resolve() if out_dir else None
        self.edit_files: list[Path] = []
        if self.pdf and not self.pdf.is_file():
            raise FileNotFoundError(self.pdf)
        if self.edit:
            if self.edit.is_dir():
                found = sorted(p.resolve() for p in self.edit.glob("*.json") if _is_annotation_json(p))
                if not found:
                    raise LabelingError(f"no annotation JSON files found in {self.edit}")
                self.edit_files = found
                self.edit = found[0]
            elif self.edit.is_file():
                self.edit_files = [self.edit]
            else:
                raise FileNotFoundError(self.edit)
        self.dpi = dpi
        self.lock = threading.RLock()
        self.token = secrets.token_urlsafe(32)
        self._render_cache: dict[int, tuple[Path, int, int, str]] = {}
        self.source_hash = sha256_file(self.pdf) if self.pdf else None
        self.source_stat = self.pdf.stat() if self.pdf else None
        self.walls = _load_walls(walls)
        self.variants_by_family = _load_neca_subtypes(neca_catalog)
        if self.pdf:
            fitz = _import_fitz()
            with fitz.open(self.pdf) as doc:
                self.page_count = len(doc)
            if self.page_count < 1:
                raise LabelingError("PDF has no pages")
        else:
            self.page_count = len(self.edit_files)
        self.start_page = page
        if page < 0 or page >= self.page_count:
            raise LabelingError("initial page outside source document")
        name = re.sub(r"[^a-zA-Z0-9_-]+", "_", self.pdf.stem)[:48] if self.pdf else ""
        self.out_dir = (self._initial_out_dir if self._initial_out_dir else
                        Path.cwd() / "annotations" / f"{name}_{self.source_hash[:12]}" if self.pdf else
                        self.edit.parent)
        self.out_dir.mkdir(parents=True, exist_ok=True)
        # Read-only, source-hash-matched heuristics. They never attest negatives.
        self.page_presence: dict[int, dict] = {}
        if page_presence is not None:
            if not self.pdf:
                raise LabelingError("--page-presence requires --pdf")
            queue = json.loads(Path(page_presence).read_text(encoding="utf-8"))
            if (not isinstance(queue, dict) or queue.get("format_version") != 1
                    or queue.get("purpose") != "page_symbol_presence_review_queue"
                    or not isinstance(queue.get("documents"), list)):
                raise LabelingError("invalid page presence review queue")
            matches = [row for row in queue["documents"]
                       if isinstance(row, dict) and row.get("source_pdf_sha256") == self.source_hash]
            if len(matches) != 1 or not isinstance(matches[0].get("pages"), list):
                raise LabelingError("page presence queue does not uniquely match selected PDF SHA256")
            for row in matches[0]["pages"]:
                if not isinstance(row, dict):
                    raise LabelingError("invalid page presence suggestion")
                idx = row.get("page_index")
                status = row.get("suggestion")
                if (type(idx) is not int or not 0 <= idx < self.page_count
                        or idx in self.page_presence
                        or status not in ("possible_symbols", "review_required", "blank_candidate")
                        or row.get("verified_no_symbols") is not False
                        or row.get("eligible_as_supervised_negative") is not False):
                    raise LabelingError("untrusted or inconsistent page presence suggestion")
                self.page_presence[idx] = {
                    "suggestion": status,
                    "risk_terms": [str(x)[:80] for x in row.get("risk_terms", [])[:32]]
                    if isinstance(row.get("risk_terms", []), list) else [],
                }
        self.proposals = None
        if proposals is not None:
            if not self.pdf:
                raise LabelingError("--proposals requires --pdf")
            self.proposals = json.loads(Path(proposals).read_text(encoding="utf-8"))
            if not isinstance(self.proposals, dict) or not isinstance(self.proposals.get("devices"), list):
                raise LabelingError("proposal file lacks a devices list")
            if (self.proposals.get("source_pdf_sha256") and
                    self.proposals["source_pdf_sha256"] != self.source_hash):
                raise LabelingError("proposal source PDF SHA256 differs from selected drawing")
            if (not self.proposals.get("source_pdf_sha256") and
                    self.proposals.get("source_file") != self.pdf.name):
                raise LabelingError("unhashed proposals must match the PDF filename")
        elif self.pdf:
            auto_prop = _find_proposals_for_pdf(self.pdf)
            if auto_prop and auto_prop.is_file():
                try:
                    loaded = json.loads(auto_prop.read_text(encoding="utf-8"))
                    if isinstance(loaded, dict) and isinstance(loaded.get("devices"), list):
                        if not loaded.get("source_pdf_sha256") or loaded["source_pdf_sha256"] == self.source_hash:
                            self.proposals = loaded
                except Exception:
                    pass

    def _assert_pdf_unchanged(self) -> None:
        if self.pdf:
            current = self.pdf.stat()
            if (current.st_size != self.source_stat.st_size or
                    current.st_mtime_ns != self.source_stat.st_mtime_ns):
                raise RevisionConflict("source PDF changed during labeling; reopen the session")

    def _page_index(self, index: int) -> int:
        if not isinstance(index, int) or not 0 <= index < self.page_count:
            raise LabelingError("page index out of range")
        return index

    def _json_path(self, index: int) -> Path:
        if self.edit:
            return self.edit_files[index]
        return self.out_dir / f"{self.pdf.stem[:48]}_p{index + 1:04d}.json"

    def _raster(self, index: int) -> tuple[Path, int, int, str]:
        self._assert_pdf_unchanged()
        if index in self._render_cache:
            cached = self._render_cache[index]
            if cached[0].is_file():
                if sha256_file(cached[0]) != cached[3]:
                    raise RevisionConflict("source image changed during labeling; reopen the session")
                return cached
            self._render_cache.pop(index)
        if self.edit:
            json_path = self._json_path(index)
            data = json.loads(json_path.read_text(encoding="utf-8"))
            image = _resolve_edit_image(json_path, str(data.get("image_path", "")))
            dims = _image_dimensions(image)
            width = int(data.get("image_width") or (dims[0] if dims else 0))
            height = int(data.get("image_height") or (dims[1] if dims else 0))
            if width <= 0 or height <= 0:
                raise LabelingError(f"could not determine image dimensions for {image}")
            if dims and (dims[0] != width or dims[1] != height):
                raise RevisionConflict("annotation dimensions differ from rendered image")
            result = image, width, height, sha256_file(image)
            self._render_cache[index] = result
            return result
        fitz = _import_fitz()
        with fitz.open(self.pdf) as doc:
            sheet = doc[index]
            factor = self.dpi / 72.0
            if sheet.rect.width * factor * sheet.rect.height * factor > MAX_PDF_PIXELS:
                factor = math.sqrt(MAX_PDF_PIXELS / (sheet.rect.width * sheet.rect.height))
            pix = sheet.get_pixmap(matrix=fitz.Matrix(factor, factor), alpha=False)
            width, height = pix.width, pix.height
            path = self.out_dir / "images" / f"page_{index + 1:04d}_{self.source_hash[:12]}_{width}x{height}.png"
            png = pix.tobytes("png")
            if not path.is_file() or path.read_bytes() != png:
                _atomic_bytes(path, png)
            result = path, width, height, sha256_file(path)
            self._render_cache[index] = result
            return result

    def _proposal_instances(self, index: int, width: int, height: int) -> list[dict]:
        if self.proposals is None:
            return []
        fitz = _import_fitz()
        with fitz.open(self.pdf) as doc:
            page = doc[index]
            pw, ph = float(page.rect.width), float(page.rect.height)
        found = []
        for number, proposal in enumerate(self.proposals.get("devices", [])):
            if proposal.get("pdf_page") != index + 1:
                continue
            pts = proposal.get("symbol_bbox") or proposal.get("box_px_tl")
            if not isinstance(pts, list) or len(pts) != 4:
                continue
            try:
                x0, y0, x1, y1 = map(float, pts)
                if proposal.get("box_px_tl") and pts == proposal.get("box_px_tl"):
                    render_dpi = float(self.proposals.get("render_dpi") or 150)
                    factor = (self.dpi / render_dpi) if render_dpi > 0 else 1.0
                    box = [
                        max(0., min(float(width), x0 * factor)),
                        max(0., min(float(height), y0 * factor)),
                        max(0., min(float(width), x1 * factor)),
                        max(0., min(float(height), y1 * factor)),
                    ]
                else:
                    box = [
                        max(0., min(float(width), x0 * width / pw)),
                        max(0., min(float(height), (ph - y1) * height / ph)),
                        max(0., min(float(width), x1 * width / pw)),
                        max(0., min(float(height), (ph - y0) * height / ph)),
                    ]
                _valid_box(box, width, height)
            except (ValueError, TypeError, ZeroDivisionError):
                continue
            family = str(proposal.get("device_type") or proposal.get("broad_family") or "unknown_symbol")
            variant = str(proposal.get("subtype") or proposal.get("neca_name") or "unspecified")
            found.append({
                "instance_id": f"proposal_p{index + 1}_{number}",
                "symbol_family": family,
                "symbol_variant": variant,
                "discipline": discipline_for(family),
                "device_type": family,
                "bbox_xyxy": [round(v, 3) for v in box],
                "attributes_from_text": [],
                "rotation_degrees": 0.0,
                "is_truncated": False,
                "is_occluded": False,
                "uncertain": True,
                "review_status": "unreviewed",
                "review_source": proposal.get("status") or "sam3",
                "verified_ground_truth": False,
                "proposal_score": proposal.get("score"),
                "proposal_visual_group": proposal.get("visual_group"),
                "proposal_neca_symbol_id": proposal.get("neca_symbol_id"),
                "proposal_alternative_labels": proposal.get("alternative_labels", []),
            })
        return found

    def _new_sheet(self, index: int, image: Path, width: int, height: int, image_hash: str) -> dict:
        page_path = self._json_path(index)
        if self.pdf:
            image_rel = str(image.relative_to(page_path.parent))
            instances = self._proposal_instances(index, width, height)
            result = {
                "format_version": 1, "source_id": self.pdf.stem,
                "source_license": "user-owned",
                "project_family_id": self.pdf.stem,
                "document_id": self.pdf.name,
                "page_index": index,
                "source_type": "real_construction_pdf_manual",
                "source_pdf_sha256": self.source_hash,
                "image_path": image_rel,
                "image_sha256": image_hash,
                "image_width": width, "image_height": height,
                "coordinate_system": "original_page_pixels_top_left_y_down",
                "exhaustive_families": [],
                "verified_no_symbols": False,
                "annotation_completeness": "partial",
                "annotation_completeness_verified": False,
                "annotation_scope": "positive_only",
                "instances": instances,
                "rejected_proposals": [],
            }
            return result
        return json.loads(page_path.read_text(encoding="utf-8"))

    def _load(self, index: int) -> tuple[dict, str | None, Path]:
        self._page_index(index)
        image, width, height, image_hash = self._raster(index)
        path = self._json_path(index)
        if path.is_file():
            raw = path.read_bytes()
            data = json.loads(raw)
            if self.edit and _resolve_edit_image(path, str(data.get("image_path", ""))) != image:
                raise RevisionConflict("source image reference changed during labeling; reopen the session")
            revision = hashlib.sha256(raw).hexdigest()
            if self.pdf and data.get("source_pdf_sha256") != self.source_hash:
                raise RevisionConflict("annotation belongs to another PDF revision")
            if data.get("image_width") is not None and int(data["image_width"]) != width:
                raise RevisionConflict("annotation dimensions differ from rendered image")
            if data.get("image_height") is not None and int(data["image_height"]) != height:
                raise RevisionConflict("annotation dimensions differ from rendered image")
            data["image_width"] = width
            data["image_height"] = height
            stored_sha = data.get("image_sha256")
            if stored_sha not in (None, "", image_hash):
                if self.edit and not SHA256_PATTERN.fullmatch(str(stored_sha)):
                    data["image_sha256"] = image_hash
                else:
                    raise RevisionConflict("annotation image bytes changed")
            elif not stored_sha:
                data["image_sha256"] = image_hash
            data = _normalize_loaded_document(data)
        else:
            data = self._new_sheet(index, image, width, height, image_hash)
            revision = None
        if data.get("coordinate_system") != "original_page_pixels_top_left_y_down":
            raise LabelingError("unsupported annotation coordinate system")
        return data, revision, image

    def list_sibling_jsons(self) -> list[dict]:
        base_dir = self.out_dir if self.pdf else self.edit.parent
        files = []
        for path in sorted(base_dir.glob("*.json")):
            if _is_annotation_json(path):
                files.append({"name": path.name, "path": str(path)})
        return files

    def open_existing_json(self, filename: str) -> int:
        if not self.edit:
            raise LabelingError("switching JSON files is only available in --edit mode")
        if "/" in filename or "\\" in filename or ".." in filename:
            raise LabelingError("invalid JSON filename")
        target = (self.edit.parent / filename).resolve()
        if target.parent != self.edit.parent or not target.is_file():
            raise LabelingError(f"annotation JSON not found: {filename}")
        if not _is_annotation_json(target):
            raise LabelingError(f"{filename} is not a valid annotation JSON file")
        with self.lock:
            if target in self.edit_files:
                return self.edit_files.index(target)
            self.edit_files.append(target)
            self.page_count = len(self.edit_files)
            return self.page_count - 1

    def resolve_snap_preview(self, index: int, instance_dict: dict) -> dict:
        with self.lock:
            baseline, _, img_path = self._load(index)
            width, height = int(baseline["image_width"]), int(baseline["image_height"])
            validate_instances([instance_dict], width, height)
            try:
                import cv2
                from annotator.snapping import resolve_snap_for_instance
                img = cv2.imread(str(img_path))
                if img is not None:
                    inst = Instance.from_dict(instance_dict)
                    return resolve_snap_for_instance(
                        inst, img, walls=self.walls, wall_attachable=bool(self.walls),
                        snap_vector=inst.snap_vector,
                    )
            except ImportError:
                pass
            return _geometric_wall_snap(instance_dict, self.walls)

    def page_state(self, index: int) -> dict:
        with self.lock:
            data, revision, _ = self._load(index)
            json_path = self._json_path(index)
            doc_name = self.pdf.name if self.pdf else (self.edit.name if self.edit else "")
            doc_path = str(self.pdf.resolve()) if self.pdf else (str(self.edit.resolve()) if self.edit else "")
            return {
                "page_index": index,
                "page_count": self.page_count,
                "revision": revision,
                "document": data,
                "image_url": f"/api/image?page={index}",
                "families": sorted(FAMILY_DISCIPLINES),
                "family_disciplines": FAMILY_DISCIPLINES,
                "variants_by_family": self.variants_by_family,
                "attribute_schema": _attribute_schema_payload(),
                "proposals_enabled": self.proposals is not None,
                "page_presence_suggestion": self.page_presence.get(index),
                "mode": "edit" if self.edit else "pdf",
                "json_filename": json_path.name,
                "json_path": str(json_path),
                "current_doc_name": doc_name,
                "current_doc_path": doc_path,
                "sibling_files": self.list_sibling_jsons() if self.edit else [],
                "walls": self.walls,
            }

    def list_documents(self) -> list[dict]:
        bp_dir = self.blueprints_dir if (self.blueprints_dir and self.blueprints_dir.is_dir()) else DEFAULT_BLUEPRINTS_DIR
        if not bp_dir.is_dir():
            if self.pdf:
                return [{
                    "name": self.pdf.name, "stem": self.pdf.stem, "path": str(self.pdf),
                    "rel_path": self.pdf.name, "has_proposals": self.proposals is not None,
                    "has_annotations": True, "is_current": True,
                }]
            return []

        pdf_paths = sorted(bp_dir.rglob("*.pdf"))
        docs = []
        current_resolved = self.pdf.resolve() if self.pdf else None

        for p in pdf_paths:
            resolved = p.resolve()
            stem = p.stem
            is_current = (resolved == current_resolved)
            prop_file = _find_proposals_for_pdf(p)
            has_prop = (prop_file is not None)

            annotated = False
            if self.out_dir and self.out_dir.is_dir():
                if list(self.out_dir.glob(f"{stem[:48]}_p*.json")):
                    annotated = True
            if not annotated and (DEFAULT_WORK_DIR / "annotator_labels").is_dir():
                if list((DEFAULT_WORK_DIR / "annotator_labels").glob(f"{stem[:48]}_p*.json")):
                    annotated = True

            try:
                rel = str(p.relative_to(bp_dir))
            except ValueError:
                rel = p.name

            docs.append({
                "name": p.name,
                "stem": stem,
                "path": str(resolved),
                "rel_path": rel,
                "has_proposals": has_prop,
                "proposal_file": prop_file.name if prop_file else None,
                "has_annotations": annotated,
                "is_current": is_current,
            })

        def sort_key(d):
            prio = 3
            if d["is_current"]:
                prio = 0
            elif d["has_annotations"]:
                prio = 1
            elif d["has_proposals"]:
                prio = 2
            return (prio, d["rel_path"].lower())

        docs.sort(key=sort_key)
        return docs

    def switch_document(self, path_or_name: str) -> dict:
        target = Path(path_or_name).resolve()
        if not target.is_file():
            candidate = (self.blueprints_dir / path_or_name).resolve()
            if candidate.is_file():
                target = candidate
            else:
                matches = list(self.blueprints_dir.rglob(path_or_name))
                if matches and matches[0].is_file():
                    target = matches[0].resolve()
                else:
                    raise FileNotFoundError(f"blueprint PDF not found: {path_or_name}")

        with self.lock:
            self.pdf = target
            self.edit = None
            self.edit_files = []
            self._render_cache.clear()
            self.source_hash = sha256_file(self.pdf)
            self.source_stat = self.pdf.stat()
            fitz = _import_fitz()
            with fitz.open(self.pdf) as doc:
                self.page_count = len(doc)
            if self.page_count < 1:
                raise LabelingError("PDF has no pages")
            self.start_page = 0

            if not self._initial_out_dir:
                name = re.sub(r"[^a-zA-Z0-9_-]+", "_", self.pdf.stem)[:48]
                self.out_dir = Path.cwd() / "annotations" / f"{name}_{self.source_hash[:12]}"
                self.out_dir.mkdir(parents=True, exist_ok=True)

            prop_file = _find_proposals_for_pdf(self.pdf)
            if prop_file and prop_file.is_file():
                try:
                    loaded = json.loads(prop_file.read_text(encoding="utf-8"))
                    if isinstance(loaded, dict) and isinstance(loaded.get("devices"), list):
                        self.proposals = loaded
                    else:
                        self.proposals = None
                except Exception:
                    self.proposals = None
            else:
                self.proposals = None

            return self.page_state(0)

    def export_server(self, target_path: str | None = None) -> dict:
        with self.lock:
            if not self.pdf and not self.edit:
                raise LabelingError("no active document to export")

            stem = self.pdf.stem if self.pdf else self.edit.stem
            doc_name = self.pdf.name if self.pdf else self.edit.name

            if target_path:
                export_file = Path(target_path).resolve()
            else:
                export_dir = self.out_dir / "exports"
                export_dir.mkdir(parents=True, exist_ok=True)
                export_file = export_dir / f"{stem}_full_export.json"

            export_file.parent.mkdir(parents=True, exist_ok=True)

            annotated_pages = []
            total_instances = 0
            verified_instances = 0
            instances_by_family: dict[str, int] = {}

            for page_idx in range(self.page_count):
                page_json = self._json_path(page_idx)
                if page_json.is_file():
                    try:
                        p_data = json.loads(page_json.read_text(encoding="utf-8"))
                        insts = p_data.get("instances", [])
                        total_instances += len(insts)
                        for inst in insts:
                            fam = str(inst.get("symbol_family") or "unknown")
                            instances_by_family[fam] = instances_by_family.get(fam, 0) + 1
                            if inst.get("verified_ground_truth") is True:
                                verified_instances += 1
                        annotated_pages.append({
                            "page_index": page_idx,
                            "page_number": page_idx + 1,
                            "json_path": str(page_json),
                            "verified_no_symbols": p_data.get("verified_no_symbols", False),
                            "annotation_completeness": p_data.get("annotation_completeness", "partial"),
                            "instance_count": len(insts),
                            "instances": insts,
                            "rejected_proposals": p_data.get("rejected_proposals", []),
                        })
                    except Exception:
                        pass

            export_payload = {
                "format_version": 1,
                "export_type": "full_document_annotations",
                "document_name": doc_name,
                "source_pdf_sha256": self.source_hash,
                "exported_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                "total_pdf_pages": self.page_count,
                "annotated_pages_count": len(annotated_pages),
                "total_instances": total_instances,
                "verified_instances": verified_instances,
                "instances_by_family": instances_by_family,
                "pages": annotated_pages,
            }

            encoded = (json.dumps(export_payload, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
            _atomic_bytes(export_file, encoded)

            return {
                "success": True,
                "path": str(export_file),
                "filename": export_file.name,
                "pages_count": len(annotated_pages),
                "total_instances": total_instances,
                "verified_instances": verified_instances,
            }

    def list_server_sources(self, page_index: int) -> list[dict]:
        with self.lock:
            sources = []
            stem = self.pdf.stem if self.pdf else ""

            cleaned_dir = DEFAULT_WORK_DIR / "sam3_cleaned"
            if cleaned_dir.is_dir():
                for f in sorted(cleaned_dir.glob("*.json")):
                    sources.append({
                        "name": f.name,
                        "path": str(f.resolve()),
                        "type": "sam3_cleaned",
                        "is_recommended": bool(stem and stem in f.name),
                    })

            cn_dir = DEFAULT_WORK_DIR / "circuitnet_proposals"
            if cn_dir.is_dir():
                for f in sorted(cn_dir.glob("*.json")):
                    sources.append({
                        "name": f.name,
                        "path": str(f.resolve()),
                        "type": "circuitnet",
                        "is_recommended": bool(stem and stem in f.name),
                    })

            if self.pdf and (self.pdf.parent / "devices").is_dir():
                for f in sorted((self.pdf.parent / "devices").glob("*.json")):
                    sources.append({
                        "name": f.name,
                        "path": str(f.resolve()),
                        "type": "blueprint_devices",
                        "is_recommended": True,
                    })

            if self.out_dir and (self.out_dir / "exports").is_dir():
                for f in sorted((self.out_dir / "exports").glob("*.json")):
                    sources.append({
                        "name": f.name,
                        "path": str(f.resolve()),
                        "type": "server_export",
                        "is_recommended": bool(stem and stem in f.name),
                    })

            sources.sort(key=lambda s: (0 if s["is_recommended"] else 1, s["name"].lower()))
            return sources

    def import_server(self, page_index: int, source_path: str) -> dict:
        target = Path(source_path).resolve()
        if not target.is_file():
            raise FileNotFoundError(f"server file not found: {source_path}")

        with self.lock:
            raw = json.loads(target.read_text(encoding="utf-8"))
            imported_count = 0

            baseline, revision, _ = self._load(page_index)
            width, height = int(baseline["image_width"]), int(baseline["image_height"])

            current_instances = list(baseline.get("instances", []))
            existing_ids = {inst["instance_id"] for inst in current_instances}

            if isinstance(raw, dict) and isinstance(raw.get("devices"), list):
                self.proposals = raw
                new_proposals = self._proposal_instances(page_index, width, height)
                for prop in new_proposals:
                    if prop["instance_id"] not in existing_ids:
                        current_instances.append(prop)
                        existing_ids.add(prop["instance_id"])
                        imported_count += 1
            elif isinstance(raw, dict) and isinstance(raw.get("pages"), list):
                for p in raw["pages"]:
                    if p.get("page_index") == page_index:
                        for inst in p.get("instances", []):
                            if inst.get("instance_id") not in existing_ids:
                                current_instances.append(inst)
                                existing_ids.add(inst["instance_id"])
                                imported_count += 1
            elif isinstance(raw, dict) and isinstance(raw.get("instances"), list):
                for inst in raw["instances"]:
                    if isinstance(inst, dict):
                        inst_id = inst.get("instance_id")
                        if not inst_id or inst_id in existing_ids:
                            inst_id = f"imported_p{page_index + 1}_{len(current_instances) + 1}"
                            inst = dict(inst, instance_id=inst_id)
                        current_instances.append(inst)
                        existing_ids.add(inst_id)
                        imported_count += 1
            else:
                raise LabelingError(f"unrecognized annotation or proposal format in {target.name}")

            baseline["instances"] = [_normalize_instance_for_save(inst) for inst in current_instances]
            self.save(
                page_index,
                revision=revision,
                instances=baseline["instances"],
                rejected=baseline.get("rejected_proposals", []),
                metadata={"verified_no_symbols": False},
            )

            return {
                "success": True,
                "imported_count": imported_count,
                "filename": target.name,
                "page_state": self.page_state(page_index),
            }

    def image_bytes(self, index: int) -> bytes:
        with self.lock:
            _, _, path = self._load(index)
            return path.read_bytes()

    def image_response(self, index: int) -> tuple[bytes, str]:
        with self.lock:
            _, _, path = self._load(index)
            return path.read_bytes(), _image_mime(path)

    def validate_review_payload(self, index: int, *, revision: str | None,
                                instances: object, rejected: object,
                                metadata: object = None) -> tuple[dict, list[dict]]:
        """Check a proposed edit without writing it. Save reuses these exact gates."""
        with self.lock:
            baseline, actual_revision, _ = self._load(index)
            if revision != actual_revision:
                raise RevisionConflict("saved annotations changed; reload page before overwriting")
            width, height = int(baseline["image_width"]), int(baseline["image_height"])
            validate_instances(instances, width, height)
            if metadata is not None and not isinstance(metadata, dict):
                raise LabelingError("metadata must be an object")
            negative = (metadata.get("verified_no_symbols", baseline.get("verified_no_symbols", False))
                        if metadata is not None else baseline.get("verified_no_symbols", False))
            if type(negative) is not bool:
                raise LabelingError("metadata.verified_no_symbols must be boolean")
            if negative and instances:
                raise LabelingError("verified symbol-free pages cannot contain active boxes; reject or remove proposals first")
            if not isinstance(rejected, list) or len(rejected) > 150_000:
                raise LabelingError("rejected_proposals must be a bounded list")
            rejected_ids: set[str] = set()
            active_ids = {item["instance_id"] for item in instances}
            cleaned_rejected = []
            for item in rejected:
                if (not isinstance(item, dict) or
                        item.get("review_status") != "rejected" or
                        item.get("review_source") != "human" or
                        item.get("verified_ground_truth") is True or
                        item.get("uncertain") is not True or
                        not isinstance(item.get("instance_id"), str) or
                        not ID_PATTERN.fullmatch(item["instance_id"]) or
                        item["instance_id"] in active_ids or
                        item["instance_id"] in rejected_ids):
                    raise LabelingError("rejected boxes must be unique, human-reviewed and unverified")
                _valid_box(item.get("bbox_xyxy"), width, height)
                rejected_ids.add(item["instance_id"])
                cleaned_rejected.append(_normalize_instance_for_save(item))
            return baseline, cleaned_rejected

    def save(self, index: int, *, revision: str | None, instances: object,
             rejected: object, metadata: object = None) -> str:
        with self.lock:
            baseline, cleaned_rejected = self.validate_review_payload(
                index, revision=revision, instances=instances, rejected=rejected, metadata=metadata
            )

            if metadata is not None:
                if not isinstance(metadata, dict):
                    raise LabelingError("metadata must be an object")
                for key in ("source_id", "project_family_id", "document_id", "source_license", "notes"):
                    if key in metadata:
                        val = metadata[key]
                        if not isinstance(val, str) or len(val) > 500:
                            raise LabelingError(f"metadata.{key} must be a short string")
                        baseline[key] = val.strip()

            if metadata is not None and "verified_no_symbols" in metadata:
                baseline["verified_no_symbols"] = metadata["verified_no_symbols"]
            baseline["instances"] = [_normalize_instance_for_save(item) for item in instances]
            baseline["rejected_proposals"] = cleaned_rejected
            baseline["exhaustive_families"] = []
            negative = baseline.get("verified_no_symbols", False)
            baseline["annotation_completeness"] = "complete" if negative else "partial"
            baseline["annotation_completeness_verified"] = negative
            baseline["annotation_scope"] = "verified_no_symbols" if negative else "positive_only"
            encoded = (json.dumps(baseline, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
            _atomic_bytes(self._json_path(index), encoded)
            return hashlib.sha256(encoded).hexdigest()


def make_handler(session: LabelingSession):
    class Handler(BaseHTTPRequestHandler):
        def _reply(self, code: int, body: bytes, mime: str) -> None:
            self.send_response(code)
            self.send_header("Content-Type", mime)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Cross-Origin-Resource-Policy", "same-origin")
            self.send_header(
                "Content-Security-Policy",
                "default-src 'none'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; "
                "script-src 'self'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'"
            )
            self.end_headers()
            self.wfile.write(body)

        def _json(self, code: int, data: dict) -> None:
            self._reply(code, json.dumps(data, ensure_ascii=False).encode("utf-8"), "application/json; charset=utf-8")

        def _trusted_host(self) -> bool:
            host_header = self.headers.get("Host", "").lower()
            port = self.server.server_port
            if ":" in host_header:
                host_name, h_port = host_header.split(":", 1)
                if h_port != str(port):
                    return False
            else:
                host_name = host_header

            if host_name in ("127.0.0.1", "localhost", "0.0.0.0") or host_name.endswith(".local"):
                return True
            return _is_private_ip(host_name)

        def _page(self, query: str) -> int:
            q = parse_qs(query, keep_blank_values=True)
            if set(q) - {"page"} or len(q.get("page", [])) > 1:
                raise LabelingError("unsupported page query")
            raw = q.get("page", [str(session.start_page)])[0]
            try:
                return session._page_index(int(raw))
            except (ValueError, TypeError):
                raise LabelingError("invalid page number") from None

        def do_GET(self) -> None:
            if not self._trusted_host():
                self._json(403, {"error": "local host header required"})
                return
            url = urlsplit(self.path)
            try:
                if url.path == "/":
                    self._reply(200, (MODULE_DIR / "web_ui.html").read_bytes(), "text/html; charset=utf-8")
                elif url.path == "/web_ui.js":
                    self._reply(200, (MODULE_DIR / "web_ui.js").read_bytes(), "text/javascript; charset=utf-8")
                elif url.path == "/api/session":
                    self._json(200, {
                        "token": session.token,
                        "initial_page": session.start_page,
                        "mode": "edit" if session.edit else "pdf",
                        "current_doc_name": session.pdf.name if session.pdf else (session.edit.name if session.edit else ""),
                        "current_doc_path": str(session.pdf.resolve()) if session.pdf else (str(session.edit.resolve()) if session.edit else ""),
                    })
                elif url.path == "/api/page":
                    self._json(200, session.page_state(self._page(url.query)))
                elif url.path == "/api/image":
                    data, mime = session.image_response(self._page(url.query))
                    self._reply(200, data, mime)
                elif url.path == "/api/files":
                    self._json(200, {"files": session.list_sibling_jsons()})
                elif url.path == "/api/documents":
                    self._json(200, {"documents": session.list_documents()})
                elif url.path == "/api/server_sources":
                    self._json(200, {"sources": session.list_server_sources(self._page(url.query))})
                else:
                    self._json(404, {"error": "unknown endpoint"})
            except (LabelingError, ValueError, FileNotFoundError) as exc:
                self._json(409, {"error": str(exc)})

        def do_POST(self) -> None:
            if not self._trusted_host() or self.headers.get("X-Labeler-Token") != session.token:
                self._json(403, {"error": "local session authorization required"})
                return
            origin = self.headers.get("Origin")
            host_header = self.headers.get("Host", "").lower()
            if origin is not None:
                origin_clean = origin.rstrip("/").lower()
                allowed = {
                    f"http://{host_header}",
                    f"https://{host_header}",
                    f"http://127.0.0.1:{self.server.server_port}",
                    f"http://localhost:{self.server.server_port}",
                }
                if origin_clean not in allowed:
                    self._json(403, {"error": "foreign origin rejected"})
                    return
            url = urlsplit(self.path)
            valid_endpoints = (
                "/api/save", "/api/validate", "/api/open", "/api/snap",
                "/api/document", "/api/server_export", "/api/server_import",
            )
            if url.path not in valid_endpoints:
                self._json(404, {"error": "unknown endpoint"})
                return
            length_header = self.headers.get("Content-Length", "")
            try:
                length = int(length_header)
                if length < 1 or length > MAX_BODY_BYTES:
                    raise LabelingError("annotation request is too large or empty")
                raw = self.rfile.read(length)
                data = json.loads(raw)
                if not isinstance(data, dict):
                    raise LabelingError("request body must be an object")
                if url.path == "/api/document":
                    target_path = data.get("path") or data.get("name")
                    if not target_path:
                        raise LabelingError("document path or name required")
                    new_state = session.switch_document(str(target_path))
                    self._json(200, {"switched": True, "page_state": new_state})
                    return
                if url.path == "/api/server_export":
                    result = session.export_server(data.get("path"))
                    self._json(200, result)
                    return
                if url.path == "/api/server_import":
                    src_path = data.get("path")
                    if not src_path:
                        raise LabelingError("server source path required")
                    page_idx = self._page(url.query)
                    result = session.import_server(page_idx, str(src_path))
                    self._json(200, result)
                    return
                if url.path == "/api/open":
                    filename = str(data.get("filename", ""))
                    page_idx = session.open_existing_json(filename)
                    self._json(200, {"page_index": page_idx, "page_count": session.page_count})
                    return
                if url.path == "/api/snap":
                    page_idx = self._page(url.query)
                    inst = data.get("instance")
                    if not isinstance(inst, dict):
                        raise LabelingError("instance object required")
                    snap = session.resolve_snap_preview(page_idx, inst)
                    self._json(200, {"snap": snap})
                    return
                page_idx = self._page(url.query)
                if url.path == "/api/validate":
                    session.validate_review_payload(
                        page_idx, revision=data.get("revision"),
                        instances=data.get("instances"),
                        rejected=data.get("rejected_proposals"),
                        metadata=data.get("metadata"),
                    )
                    self._json(200, {"valid": True})
                    return
                new_revision = session.save(
                    page_idx,
                    revision=data.get("revision"),
                    instances=data.get("instances"),
                    rejected=data.get("rejected_proposals"),
                    metadata=data.get("metadata"),
                )
                saved_state = session.page_state(page_idx)
                self._json(200, {
                    "revision": new_revision,
                    "saved": True,
                    "document": saved_state["document"],
                })
            except RevisionConflict as exc:
                self._json(409, {"error": str(exc)})
            except (LabelingError, ValueError, TypeError, KeyError) as exc:
                self._json(400, {"error": str(exc)})

        def log_message(self, format: str, *args) -> None:
            pass

    return Handler


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=False)
    mode.add_argument("--pdf", type=Path)
    mode.add_argument("--edit", type=Path, help="Existing annotation JSON file or directory of JSON files")
    parser.add_argument("--blueprints-dir", type=Path, default=DEFAULT_BLUEPRINTS_DIR,
                        help="Root directory containing blueprint PDFs")
    parser.add_argument("--out-dir", type=Path)
    parser.add_argument("--proposals", type=Path)
    parser.add_argument("--page-presence", type=Path,
                        help="Read-only page candidates from scripts_triage_pdf_symbol_pages.py")
    parser.add_argument("--walls", type=Path, help="Optional JSON file with verified wall centerlines for snap guidance")
    parser.add_argument("--neca-catalog", type=Path, help="Optional NECA symbol library directory to populate live subtypes")
    parser.add_argument("--page", type=int, default=0, help="Initial 0-based page/file index")
    parser.add_argument("--dpi", type=int, default=150)
    parser.add_argument("--host", type=str, default="0.0.0.0", help="Listen host (default 0.0.0.0 for LAN access)")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--open-browser", action="store_true")
    args = parser.parse_args()

    chosen_pdf = args.pdf
    chosen_edit = args.edit
    if chosen_pdf is None and chosen_edit is None:
        bp_dir = Path(args.blueprints_dir).resolve()
        if bp_dir.is_dir():
            pdfs = sorted(bp_dir.rglob("*.pdf"))
            if pdfs:
                best = pdfs[0]
                for p in pdfs:
                    if _find_proposals_for_pdf(p):
                        best = p
                        break
                chosen_pdf = best
            else:
                raise SystemExit(f"No PDF files found in blueprints directory {bp_dir}")
        else:
            raise SystemExit("Specify --pdf or --edit, or ensure blueprints directory exists")

    session = LabelingSession(
        pdf=chosen_pdf, edit=chosen_edit, blueprints_dir=args.blueprints_dir,
        out_dir=args.out_dir, page=args.page,
        dpi=args.dpi, proposals=args.proposals, walls=args.walls,
        neca_catalog=args.neca_catalog, page_presence=args.page_presence,
    )
    lan_ip = get_lan_ip()
    with ThreadingHTTPServer((args.host, args.port), make_handler(session)) as server:
        port = server.server_port
        print(f"Manual labeler running!")
        print(f"  Local URL:   http://127.0.0.1:{port}/")
        print(f"  Network URL: http://{lan_ip}:{port}/")
        print(f"Annotations: {session.out_dir} (positive-only; no exhaustive labels inferred)")
        if args.open_browser:
            webbrowser.open(f"http://127.0.0.1:{port}/")
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
