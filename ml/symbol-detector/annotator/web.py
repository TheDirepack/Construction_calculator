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
No cloud requests or framework dependency. It listens on the LAN by default
and opens only drawings in 1_blueprints/ and 1_blueprints/evaluation/; labels
are written to the devices/ folder beside each PDF (annotator.label_store).
"""
from __future__ import annotations

import argparse
import contextlib
import copy
import hashlib
import hmac
import ipaddress
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
from collections import OrderedDict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, quote, urlsplit

from annotator import agent_api
from blueprint_symbols.label_schema import (
    weatherproof_attribute,
    canonical_device,
    canonical_instance,
    completeness,
    device_for_raw_type,
    devices_bbox_points,
    devices_rotated_coordinates,
    devices_skip_reason,
    group_fields,
    incomplete_reason,
    page_header,
    pending_instance,
    points_to_pixels,
)
from annotator.model import (
    FAMILY_DISCIPLINES,
    FAMILY_VARIANT_SUGGESTIONS,
    Instance,
    RotatedBox,
    Sheet,
    SnapVector,
    discipline_for,
    load_electrical_device_catalog,
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
    "security_device": "security_device",
    "cctv_device": "cctv_device",
    "audio_visual_device": "audio_visual_device",
    "sensor": "sensor",
    "disconnect": "disconnect",
    "motor_control": "motor_control",
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


LAN_NAME_SUFFIXES = (".local", ".lan", ".home", ".internal", ".home.arpa")


def _is_private_ip(host_part: str) -> bool:
    """True for a literal loopback / private / unspecified IP address.

    Names are never resolved: a DNS lookup would let any website whose name
    resolves to a LAN address (DNS rebinding) pass the Host-header check.
    """
    try:
        ip = ipaddress.ip_address(host_part.strip("[]"))
    except ValueError:
        return False
    return ip.is_loopback or ip.is_private or ip.is_unspecified


def _is_lan_host(host_name: str) -> bool:
    """A Host header this server answers: a private IP, or a LAN machine name
    (``mypc``, ``mypc.local``). Public DNS names never qualify."""
    host_name = host_name.lower().rstrip(".")
    if _is_private_ip(host_name) or host_name == "localhost":
        return True
    if not re.fullmatch(r"[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?", host_name):
        return False
    return "." not in host_name or host_name.endswith(LAN_NAME_SUFFIXES)


def openable_pdfs(blueprints_dir: Path) -> list[Path]:
    """The drawings the labeler shows: training PDFs directly in ``1_blueprints``
    and evaluation PDFs directly in ``1_blueprints/evaluation``. Other
    subfolders (new downloads, archives) belong to neither set and stay hidden."""
    from blueprint_symbols.blueprint_sets import EVALUATION_DIR
    found = []
    for folder in (Path(blueprints_dir), Path(blueprints_dir) / EVALUATION_DIR):
        if not folder.is_dir():
            continue
        rfolder = folder.resolve()
        paths = []
        with os.scandir(rfolder) as entries:
            for entry in entries:
                if entry.name.lower().endswith(".pdf") and entry.is_file():
                    paths.append(Path(entry.path).resolve() if entry.is_symlink() else (rfolder / entry.name))
        paths.sort()
        found += paths
    return found


def _devices_json_for(pdf: Path) -> Path | None:
    """The *_DEVICES.json extracted from this PDF, anywhere under its
    1_blueprints folder (shared lookup: label_store.devices_json_for)."""
    from annotator import label_store
    return label_store.devices_json_for(pdf)


def _find_proposal_sources(pdf: Path, work_dir: Path | None = None) -> list[Path]:
    """Every machine-proposal file for a PDF, strongest first: its extracted
    ``*_DEVICES.json`` (the file people and agents edit), VLM-reviewed
    detections, then cleaned SAM runs, CircuitNet runs, and raw SAM runs that
    were never cleaned."""
    work = work_dir or DEFAULT_WORK_DIR
    stem = pdf.stem
    found: list[Path] = []
    devices = _devices_json_for(pdf)
    if devices is not None:
        found.append(devices)
    found += sorted(pdf.parent.glob(f"{stem}_devices.json")) + sorted(pdf.parent.glob(f"{stem}_DEVICES.json"))
    # VLM-reviewed CircuitNet/SAM detections (scripts_circuitnet_review_sheets.py apply).
    found += sorted((work / "circuitnet_refined_devices").glob(f"{stem}_*_REFINED_DEVICES.json"))
    cleaned = sorted((work / "sam3_cleaned").glob(f"{stem}_*_CLEANED.json"))
    found += cleaned
    found += sorted((work / "circuitnet_proposals").glob(f"{stem}_*.json"))
    cleaned_runs = {p.name[:-len("_CLEANED.json")] for p in cleaned}
    found += [p for p in sorted((work / "sam3_proposals").glob(f"{stem}_*.json"))
              if p.name.rsplit("_", 1)[0] not in cleaned_runs]
    return list(dict.fromkeys(p.resolve() for p in found if p.is_file()))


def _find_proposals_for_pdf(pdf: Path, work_dir: Path | None = None) -> Path | None:
    """The strongest machine-proposal file for a PDF (``_find_proposal_sources``)."""
    found = _find_proposal_sources(pdf, work_dir)
    return found[0] if found else None


def _index_by_stem_prefix(folder: Path, suffix: str) -> dict[str, list[tuple[str, Path]]]:
    """Index JSON files in ``folder`` by every underscore prefix in the file name."""
    by_prefix: dict[str, list[tuple[str, Path]]] = {}
    if not folder.is_dir():
        return by_prefix
    entries = sorted((p.name, p.resolve()) for p in folder.iterdir()
                     if p.is_file() and p.name.endswith(suffix))
    for name, resolved in entries:
        idx = name.find("_")
        while idx > 0:
            by_prefix.setdefault(name[:idx], []).append((name, resolved))
            idx = name.find("_", idx + 1)
    return by_prefix


def _build_proposal_lookup(bp_dir: Path, work_dir: Path | None = None):
    """Return a batch resolver matching ``_find_proposals_for_pdf(pdf, work_dir)``
    with a single scan per directory instead of globs per PDF."""
    from blueprint_symbols.blueprint_sets import DEVICES_DIR, EVALUATION_DIR, devices_files
    work = work_dir or DEFAULT_WORK_DIR
    local_files: dict[tuple[Path, str], Path] = {}
    devices_by_parent: dict[Path, dict[str, Path]] = {}
    for folder in (Path(bp_dir), Path(bp_dir) / EVALUATION_DIR):
        if not folder.is_dir():
            continue
        rfolder = folder.resolve()
        devices_by_parent[rfolder] = devices_files(rfolder / DEVICES_DIR)
        with os.scandir(rfolder) as entries:
            for entry in entries:
                if entry.name.endswith(("_devices.json", "_DEVICES.json")) and entry.is_file():
                    local_files[(rfolder, entry.name)] = (
                        Path(entry.path).resolve() if entry.is_symlink() else (rfolder / entry.name)
                    )
    refined = _index_by_stem_prefix(work / "circuitnet_refined_devices", "_REFINED_DEVICES.json")
    cleaned = _index_by_stem_prefix(work / "sam3_cleaned", "_CLEANED.json")
    cn = _index_by_stem_prefix(work / "circuitnet_proposals", ".json")
    sam = _index_by_stem_prefix(work / "sam3_proposals", ".json")

    def lookup(pdf: Path) -> Path | None:
        stem = pdf.stem
        if any(ch in stem for ch in "[?*"):
            return _find_proposals_for_pdf(pdf, work)
        parent = pdf.parent
        dev_map = devices_by_parent.get(parent)
        devices = dev_map.get(pdf.name.casefold()) if dev_map is not None else _devices_json_for(pdf)
        if devices is not None:
            return devices
        for candidate_name in (f"{stem}_devices.json", f"{stem}_DEVICES.json"):
            hit = local_files.get((parent, candidate_name))
            if hit is not None:
                return hit
        min_ref = len(stem) + 1 + len("_REFINED_DEVICES.json")
        for _, path in ((n, p) for n, p in refined.get(stem, ()) if len(n) >= min_ref):
            return path
        min_cln = len(stem) + 1 + len("_CLEANED.json")
        cln_hits = [(n, p) for n, p in cleaned.get(stem, ()) if len(n) >= min_cln]
        if cln_hits:
            return cln_hits[0][1]
        min_json = len(stem) + 1 + len(".json")
        for _, path in ((n, p) for n, p in cn.get(stem, ()) if len(n) >= min_json):
            return path
        cleaned_runs = {n[:-len("_CLEANED.json")] for n, _ in cln_hits}
        for name, path in sam.get(stem, ()):
            if len(name) >= min_json and name.rsplit("_", 1)[0] not in cleaned_runs:
                return path
        return None

    return lookup


# Bump when proposal files are turned into boxes differently (fields kept,
# anchors, which entries count): saved pages then get refreshed machine boxes.
PROPOSAL_CONVERSION = 5


def _parse_proposals(raw: bytes, source_hash: str | None, pdf_name: str | None = None) -> dict | None:
    """A proposal file's content, if it is one and matches this PDF revision
    (and, when the file names its PDF, this PDF)."""
    try:
        loaded = json.loads(raw)
    except ValueError:
        return None
    if not isinstance(loaded, dict) or not isinstance(loaded.get("devices"), list):
        return None
    if loaded.get("source_pdf_sha256") and loaded["source_pdf_sha256"] != source_hash:
        return None
    named = loaded.get("source_file")
    if pdf_name and isinstance(named, str) and named and Path(named).name.casefold() != pdf_name.casefold():
        return None
    return loaded


def _load_proposals(pdf: Path, source_hash: str | None) -> dict | None:
    """Machine proposals for a PDF, if any match it (never on another revision)."""
    path = _find_proposals_for_pdf(pdf)
    if path is None or not path.is_file():
        return None
    try:
        return _parse_proposals(path.read_bytes(), source_hash)
    except OSError:
        return None


# Fields of a proposal that are geometry (converted to the box and anchors) or
# bulky vector ids; everything else it says about the device is kept.
_PROPOSAL_GEOMETRY = {"box_px_tl", "symbol_bbox", "symbol_bbox_pts", "symbol_bbox_bl",
                      "symbol_graphic_bbox", "bounding_box", "bbox_pts", "bbox", "vector_path_ids",
                      "wall_vector_path_ids", "target_vector_pts", "verify_detail", "pdf_page", "page"}


def _proposal_details(proposal: dict) -> dict:
    out = {}
    for key, value in proposal.items():
        if key in _PROPOSAL_GEOMETRY or value in (None, "", [], {}):
            continue
        if isinstance(value, (list, dict)) and len(json.dumps(value, default=str)) > 2000:
            continue
        out[key] = value
    return out


def _proposal_anchors(proposal: dict, page, width: int, height: int, rotated: bool) -> dict:
    """The device's centre and, for a wall-mounted device, its wall
    connection point (PDF points) as annotator anchors (not human-placed)."""
    anchors = {}
    for name, keys, needs_wall in (("centre", ("device_center", "symbol_center"), False),
                                   ("wall_attachment", ("wall_connection_point",), True)):
        if needs_wall and proposal.get("on_wall") is not True:
            continue
        for key in keys:
            value = proposal.get(key)
            if isinstance(value, dict):
                value = [value.get("x"), value.get("y")]
            try:
                x, y = (float(v) for v in value)
            except (TypeError, ValueError):
                continue
            if not (math.isfinite(x) and math.isfinite(y)):
                continue
            px, py, _, _ = points_to_pixels([x, y, x, y], page, width, height, already_rotated=rotated)
            anchors[name] = {"xy": [round(px, 2), round(py, 2)], "visible": True, "annotated": False}
            break
    return anchors


def _box_iou(a, b) -> float:
    try:
        ax0, ay0, ax1, ay1 = (float(v) for v in a)
        bx0, by0, bx1, by1 = (float(v) for v in b)
    except (TypeError, ValueError):
        return 0.0
    w = min(ax1, bx1) - max(ax0, bx0)
    h = min(ay1, by1) - max(ay0, by0)
    if w <= 0 or h <= 0:
        return 0.0
    inter = w * h
    union = (ax1 - ax0) * (ay1 - ay0) + (bx1 - bx0) * (by1 - by0) - inter
    return inter / union if union > 0 else 0.0


def _machine_pending(item: dict) -> bool:
    """A machine proposal nobody has accepted, verified or redrawn yet."""
    return (str(item.get("instance_id", "")).startswith("proposal_")
            and item.get("review_status") != "accepted"
            and item.get("verified_ground_truth") is not True
            and item.get("review_source") not in ("human", "manual_draft"))


def default_out_dir(pdf: Path, source_hash: str) -> Path:
    """A PDF's label folder: ``devices/<stem>_<hash12>`` beside the PDF
    (``1_blueprints/devices`` or ``1_blueprints/evaluation/devices``), or
    ``$BPS_LABELS_DIR/<stem>_<hash12>`` when that flat store is set.

    The browser annotator, the labeling agents and model pre-labeling all
    resolve folders through ``annotator.label_store``, so every path reads and
    writes the same page files.
    """
    from annotator import label_store
    if os.environ.get("BPS_LABELS_DIR"):
        return label_store.document_folder(label_store.default_store(), Path(pdf), source_hash)
    return label_store.labels_folder_beside(Path(pdf), source_hash)


def _store_json_prefix(out_dir: Path) -> str | None:
    """Label-store folders fix the page JSON name prefix in document.json."""
    meta = Path(out_dir) / "document.json"
    if meta.is_file():
        try:
            prefix = json.loads(meta.read_text(encoding="utf-8")).get("json_prefix")
        except (OSError, ValueError):
            return None
        if isinstance(prefix, str) and re.fullmatch(r"[A-Za-z0-9_.-]{1,64}", prefix):
            return prefix
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





_SHA_CACHE: dict[tuple[str, int, int], str] = {}


def cached_sha256(path: Path) -> str:
    """SHA-256 of a source file, remembered by (path, size, mtime): drawing sets
    run to hundreds of MB, and switching back to one must not re-read it."""
    path = Path(path).resolve()
    stat = path.stat()
    key = (str(path), stat.st_size, stat.st_mtime_ns)
    if key not in _SHA_CACHE:
        _SHA_CACHE[key] = sha256_file(path)
    return _SHA_CACHE[key]


def proposal_device(proposal: dict) -> tuple[str, str] | None:
    """The catalog device a proposal (DEVICES.json / SAM / CircuitNet entry)
    becomes, named exactly as the annotator names its box: device type,
    subtype, legend description (or NECA name, or device code) and the
    weatherproof attribute. ``document_record`` and the proposal counts use
    this too, so a box counted there is the box shown."""
    raw = proposal.get("device_type") or proposal.get("broad_family")
    code = proposal.get("device_code")
    description = proposal.get("description") or proposal.get("neca_name") or (
        f"{code} {str(raw).replace('_', ' ')}" if code else None)
    return canonical_device(raw, proposal.get("subtype"), description=description,
                            weatherproof=weatherproof_attribute(proposal.get("attributes")))


PROPOSER_MAX_PIXELS = 20_000_000     # --max-pixels default of the SAM / CircuitNet PDF scanners


def render_scale(page_width_pt: float, page_height_pt: float, dpi: float, max_pixels: int) -> float:
    """Pixels per PDF point of a page rendered at ``dpi`` under a pixel-count cap
    (the rule of ``render_pdf_page`` and of the PDF proposal scanners)."""
    factor = dpi / 72.0
    if page_width_pt * factor * page_height_pt * factor > max_pixels:
        factor = math.sqrt(max_pixels / max(1.0, page_width_pt * page_height_pt))
    return factor


def proposal_pixel_factor(page, dpi: float, render_dpi: float, max_pixels=None) -> float:
    """Scale from a proposal file's pixel boxes (``box_px_tl``, rendered by its
    scanner at ``render_dpi`` with that scanner's own pixel cap) to this page
    image (rendered at ``dpi`` under ``MAX_PDF_PIXELS``). Either render may be
    clamped (large sheets), so the plain DPI ratio is wrong for them: compare
    the pixels-per-point of the two renders."""
    pw, ph = float(page.rect.width), float(page.rect.height)
    if render_dpi <= 0 or pw <= 0 or ph <= 0:
        return 1.0
    theirs = render_scale(pw, ph, render_dpi, int(max_pixels or PROPOSER_MAX_PIXELS))
    return render_scale(pw, ph, dpi, MAX_PDF_PIXELS) / theirs


_MAX_IMAGE_BYTES_CACHE = 64 * 1024 * 1024
_IMAGE_BYTES_CACHE: "OrderedDict[tuple[str, int, int], bytes]" = OrderedDict()
_IMAGE_BYTES_TOTAL = 0
_IMAGE_BYTES_LOCK = threading.Lock()

_RENDER_LOCKS: dict[tuple[str, int, int], threading.Lock] = {}
_RENDER_LOCKS_GUARD = threading.Lock()

_WORKER_FITZ_CACHE: "OrderedDict[str, tuple[int, int, object]]" = OrderedDict()
_MAX_WORKER_FITZ_DOCS = 2


def _render_slot_lock(pdf_key: str, index: int, dpi: int) -> threading.Lock:
    slot = (pdf_key, index, dpi)
    with _RENDER_LOCKS_GUARD:
        if len(_RENDER_LOCKS) > 512:
            for old_key in list(_RENDER_LOCKS.keys())[:256]:
                if not _RENDER_LOCKS[old_key].locked():
                    _RENDER_LOCKS.pop(old_key, None)
        lock = _RENDER_LOCKS.get(slot)
        if lock is None:
            lock = threading.Lock()
            _RENDER_LOCKS[slot] = lock
        return lock


def _read_image_bytes(path: Path) -> tuple[bytes, int, int]:
    """Read image bytes using a bounded LRU cache keyed by (path, size, mtime)."""
    global _IMAGE_BYTES_TOTAL
    resolved = str(path.resolve())
    st = path.stat()
    key = (resolved, st.st_size, st.st_mtime_ns)
    with _IMAGE_BYTES_LOCK:
        cached = _IMAGE_BYTES_CACHE.get(key)
        if cached is not None:
            _IMAGE_BYTES_CACHE.move_to_end(key)
            return cached, st.st_size, st.st_mtime_ns
    data = path.read_bytes()
    if len(data) <= _MAX_IMAGE_BYTES_CACHE // 2:
        with _IMAGE_BYTES_LOCK:
            if key not in _IMAGE_BYTES_CACHE:
                _IMAGE_BYTES_CACHE[key] = data
                _IMAGE_BYTES_TOTAL += len(data)
                while _IMAGE_BYTES_TOTAL > _MAX_IMAGE_BYTES_CACHE and _IMAGE_BYTES_CACHE:
                    _, evicted = _IMAGE_BYTES_CACHE.popitem(last=False)
                    _IMAGE_BYTES_TOTAL -= len(evicted)
            else:
                _IMAGE_BYTES_CACHE.move_to_end(key)
    return data, st.st_size, st.st_mtime_ns


def _worker_open_pdf(fitz, source: Path):
    """Keep up to ``_MAX_WORKER_FITZ_DOCS`` open PyMuPDF handles in a prefetch worker."""
    key = str(source.resolve())
    st = source.stat()
    sig = (st.st_size, st.st_mtime_ns)
    cached = _WORKER_FITZ_CACHE.get(key)
    if cached is not None:
        if cached[:2] == sig:
            _WORKER_FITZ_CACHE.move_to_end(key)
            return cached[2]
        _WORKER_FITZ_CACHE.pop(key, None)
        try:
            cached[2].close()
        except Exception:
            pass
    doc = fitz.open(source)
    _WORKER_FITZ_CACHE[key] = (sig[0], sig[1], doc)
    while len(_WORKER_FITZ_CACHE) > _MAX_WORKER_FITZ_DOCS:
        _, (_, _, old_doc) = _WORKER_FITZ_CACHE.popitem(last=False)
        try:
            old_doc.close()
        except Exception:
            pass
    return doc


def _pixmap_for_page(fitz, doc, index: int, dpi: int):
    sheet = doc[index]
    factor = dpi / 72.0
    if sheet.rect.width * factor * sheet.rect.height * factor > MAX_PDF_PIXELS:
        factor = math.sqrt(MAX_PDF_PIXELS / (sheet.rect.width * sheet.rect.height))
    return sheet.get_pixmap(matrix=fitz.Matrix(factor, factor), alpha=False)


def _write_pixmap_png(pix, index: int, dpi: int, images_dir: Path, source_hash: str) -> tuple[Path, int, int]:
    width, height = pix.width, pix.height
    path = Path(images_dir) / f"page_{index + 1:04d}_{source_hash[:12]}_d{dpi}_{width}x{height}.png"
    png = pix.tobytes("png")
    if not path.is_file() or path.read_bytes() != png:
        _atomic_bytes(path, png)
    return path, width, height


def render_pdf_page(pdf: Path, index: int, dpi: int, images_dir: Path, source_hash: str) -> tuple[Path, int, int]:
    """Render one PDF page to ``images_dir`` (the annotator's page-image cache).

    Module level so a prefetch worker process can call it; the file name
    carries the PDF hash, DPI and size, so a later session finds it by glob.
    """
    fitz = _import_fitz()
    with fitz.open(pdf) as doc:
        pix = _pixmap_for_page(fitz, doc, index, dpi)
    return _write_pixmap_png(pix, index, dpi, images_dir, source_hash)


_DEFAULT_RENDER_PDF_PAGE = render_pdf_page


def _find_rendered(images_dir: Path, index: int, dpi: int, source_hash: str) -> tuple[Path, int, int] | None:
    rendered = sorted(Path(images_dir).glob(f"page_{index + 1:04d}_{source_hash[:12]}_d{dpi}_*.png"))
    if len(rendered) == 1:
        match = re.search(r"_(\d+)x(\d+)\.png$", rendered[0].name)
        if match:
            return rendered[0], int(match.group(1)), int(match.group(2))
    return None


def _prefetch_job(pdf: str, pages: list[int], dpi: int, out_dir: str | None,
                  sha: str | None = None) -> tuple[str, str]:
    """Worker-process side of prefetch: pre-render pages.

    The caller passes the PDF hash when it knows it; otherwise the worker hashes
    the file once and remembers it (a drawing set can be 700 MB: re-hashing it
    for every page turn made prefetch slower than rendering).
    """
    source = Path(pdf)
    sha = sha or cached_sha256(source)
    images = Path(out_dir) if out_dir else default_out_dir(source, sha)
    images = images / "images"
    fitz = _import_fitz()
    use_cached = (render_pdf_page is _DEFAULT_RENDER_PDF_PAGE)
    doc = _worker_open_pdf(fitz, source) if use_cached else None
    if doc is None:
        with fitz.open(source) as tmp_doc:
            count = len(tmp_doc)
    else:
        count = len(doc)
    for index in pages:
        if 0 <= index < count and _find_rendered(images, index, dpi, sha) is None:
            if use_cached:
                pix = _pixmap_for_page(fitz, doc, index, dpi)
                _write_pixmap_png(pix, index, dpi, images, sha)
            else:
                render_pdf_page(source, index, dpi, images, sha)
    return str(source), sha


class _Prefetcher:
    """Background worker processes that pre-render pages the user is about to open.

    Separate processes, not threads: MuPDF is not safe to drive from two
    threads at once, and the render must not stall the page being viewed.
    """

    def __init__(self) -> None:
        self._pool = None
        self._seen: set[tuple] = set()
        self._queued: dict[tuple, list] = {}      # (pdf, dpi, out_dir) -> futures not yet done
        self._lock = threading.Lock()

    def submit(self, pdf: Path, pages: list[int], dpi: int, out_dir: Path | None,
               sha: str | None = None) -> bool:
        key = (str(pdf), tuple(pages), dpi, str(out_dir))
        stream = (str(pdf), dpi, str(out_dir))
        with self._lock:
            if key in self._seen:
                return False
            self._seen.add(key)
            # Latest wins: pages requested for this PDF earlier and not started
            # yet are ones the user has already paged past. They are cancelled
            # after the lock is released: cancel() runs their done-callback,
            # which takes this lock.
            stale = self._queued.pop(stream, [])
            if self._pool is None:
                import concurrent.futures
                import multiprocessing
                self._pool = concurrent.futures.ProcessPoolExecutor(
                    max_workers=2, mp_context=multiprocessing.get_context("spawn"))
            future = self._pool.submit(_prefetch_job, str(pdf), list(pages), dpi,
                                       str(out_dir) if out_dir else None, sha)
            self._queued.setdefault(stream, []).append(future)
        for old in stale:
            old.cancel()

        def remember(done) -> None:
            with self._lock:
                queued = self._queued.get(stream, [])
                if done in queued:
                    queued.remove(done)
            if done.cancelled():
                with self._lock:
                    self._seen.discard(key)        # may be asked for again later
                return
            try:
                path, sha = done.result()
            except Exception:
                with self._lock:
                    self._seen.discard(key)        # let a later request retry
                return
            stat = Path(path).stat()
            _SHA_CACHE[(str(Path(path).resolve()), stat.st_size, stat.st_mtime_ns)] = sha
        future.add_done_callback(remember)
        return True


PREFETCHER = _Prefetcher()


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


IMAGE_SUFFIXES = (".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".tif", ".tiff")
_IMAGE_MAGIC = (b"\x89PNG\r\n\x1a\n", b"\xff\xd8\xff", b"RIFF", b"GIF87a", b"GIF89a", b"BM",
                b"II*\x00", b"MM\x00*")


def _is_image_file(path: Path) -> bool:
    """An image by name and by its first bytes (not just any file renamed)."""
    if path.suffix.lower() not in IMAGE_SUFFIXES or not path.is_file():
        return False
    try:
        with open(path, "rb") as handle:
            head = handle.read(12)
    except OSError:
        return False
    if head.startswith(b"RIFF"):
        return head[8:12] == b"WEBP"
    return any(head.startswith(magic) for magic in _IMAGE_MAGIC)


def _resolve_edit_image(json_path: Path, raw_image_path: str) -> Path:
    """Resolve an annotation JSON's image_path relative to JSON parent, CWD, or absolute.

    The path comes from a file's contents and ``/api/image`` serves its bytes,
    so only an actual image file is accepted (image suffix and image header):
    a crafted ``image_path`` such as ``../../.ssh/id_ed25519`` is refused.
    """
    if not raw_image_path:
        raise LabelingError(f"annotation JSON {json_path.name} is missing image_path")
    if "\x00" in str(raw_image_path):
        raise LabelingError("invalid image_path")
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
            if not _is_image_file(path):
                raise LabelingError(f"annotation image_path is not an image file: {path.name}")
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
                            "wall_angle_deg": round(math.degrees(math.atan2(wy, wx)), 2),
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
                "wall_angle_deg": round(math.degrees(math.atan2(wy, wx)), 2),
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



def _load_neca_subtypes(catalog_root: Path | None = None) -> dict[str, list[str]]:
    """Variants allowed per family. Electrical families come only from the
    device catalog (the single source of truth: anything else would not be a
    training class); other disciplines keep the suggestion lists."""
    merged: dict[str, list[str]] = {k: list(v) for k, v in FAMILY_VARIANT_SUGGESTIONS.items()}
    catalog: dict[str, list[str]] = {}
    for dev in load_electrical_device_catalog().get("devices", []):
        catalog.setdefault(dev["symbol_family"], []).append(dev["symbol_variant"])
    for family, variants in catalog.items():
        merged[family] = variants + ["unspecified"]
    return merged


class LabelingSession:
    def __init__(
        self, *, pdf: Path | None = None, edit: Path | None = None,
        blueprints_dir: Path | None = None,
        out_dir: Path | None = None, page: int = 0, dpi: int = 150,
        proposals: Path | None = None, walls: Path | None = None, neca_catalog: Path | None = None,
        page_presence: Path | None = None,
        detector_weights: Path | None = None,
        source_sha256: str | None = None, auto_proposals: bool = True,
        token: str | None = None, lock: threading.RLock | None = None,
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
        # Sessions for other PDFs opened through this server share the lock
        # (one page file is never checked and written by two threads at once)
        # and the access token.
        self.lock = lock or threading.RLock()
        self._documents_lock = threading.RLock()
        self._render_lock = threading.Lock()
        self._fitz_lock = threading.Lock()
        self._fitz_doc = None
        self._fitz_stat: tuple[int, int] | None = None
        self.token = token or secrets.token_urlsafe(32)
        self._neca_catalog = neca_catalog
        self._detector_weights = Path(detector_weights).expanduser().resolve() if detector_weights else None
        self._documents: dict[Path, LabelingSession] = {}
        self._overview_cache: dict[str, tuple[int, int, dict]] = {}   # page file -> size, mtime, counts
        self._proposed_counts_cache: tuple | None = None
        self._saved_overview_cache: tuple | None = None
        self._render_cache: dict[int, tuple[Path, int, int, str]] = {}
        self._render_stats: dict[int, tuple[int, int]] = {}
        # Callers that already know the PDF's hash (the label store records it
        # with size and mtime) skip re-reading a large drawing set per command.
        self.source_hash = (source_sha256 or cached_sha256(self.pdf)) if self.pdf else None
        self.source_stat = self.pdf.stat() if self.pdf else None
        self.walls = _load_walls(walls)
        self.variants_by_family = _load_neca_subtypes(neca_catalog)
        self.device_catalog = load_electrical_device_catalog()
        if self.pdf:
            fitz = _import_fitz()
            doc = fitz.open(self.pdf)
            self.page_count = len(doc)
            if self.page_count < 1:
                doc.close()
                raise LabelingError("PDF has no pages")
            self._fitz_doc = doc
            self._fitz_stat = (self.source_stat.st_size, self.source_stat.st_mtime_ns)
        else:
            self.page_count = len(self.edit_files)
        self.start_page = page
        if page < 0 or page >= self.page_count:
            raise LabelingError("initial page outside source document")
        self.out_dir = (self._initial_out_dir if self._initial_out_dir else
                        default_out_dir(self.pdf, self.source_hash) if self.pdf else
                        self.edit.parent)
        self.out_dir.mkdir(parents=True, exist_ok=True)
        self.json_prefix = (_store_json_prefix(self.out_dir) or self.pdf.stem[:48]) if self.pdf else None
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
        self.proposals_sha: str | None = None
        self._proposals_fixed = Path(proposals).resolve() if proposals is not None else None
        self._proposals_sig: tuple | None = None
        self.proposal_sets: list[tuple[str, dict]] = []
        self._proposals_found_at = 0.0
        self._stored_revisions: dict[int, str | None] = {}
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
        self.auto_proposals = auto_proposals
        self._origin_pdf = self.pdf
        if self.pdf:
            self._sync_proposals(force=True)
        if self.pdf and not self._initial_out_dir:
            self._register_document()
        if self.pdf:
            self._init_proposals_record()

    MAX_OPEN_DOCUMENTS = 16
    PROPOSALS_REDISCOVER_SECONDS = 5.0

    def _sync_proposals(self, force: bool = False) -> None:
        """Keep ``self.proposals`` equal to the proposal file on disk, so an
        edited ``*_DEVICES.json`` shows up without restarting the server. The
        known file is checked (one stat) on every page load; a newly added
        file is looked for every few seconds."""
        if not self.pdf:
            return
        now = time.monotonic()
        if self._proposals_fixed is not None:
            paths = [self._proposals_fixed]
        elif not self.auto_proposals:
            paths = []
        elif (force or self._proposals_sig is None
              or now - self._proposals_found_at > self.PROPOSALS_REDISCOVER_SECONDS):
            paths = _find_proposal_sources(self.pdf)
            self._proposals_found_at = now
        else:
            paths = [Path(entry[0]) for entry in self._proposals_sig]
        signature = []
        for path in paths:
            try:
                stat = path.stat()
            except OSError:
                continue
            signature.append((str(path), stat.st_size, stat.st_mtime_ns))
        signature = tuple(signature)
        if not signature:
            if self._proposals_sig:                  # the files they came from are gone
                self.proposals, self.proposals_sha, self.proposal_sets = None, None, []
            self._proposals_sig = ()                 # looked: none (checked again in a few seconds)
            return
        if signature == self._proposals_sig:
            return
        sets, hashes = [], []
        for path_text, _, _ in signature:
            try:
                raw = Path(path_text).read_bytes()
            except OSError:
                continue
            loaded = _parse_proposals(raw, self.source_hash,
                                      None if self._proposals_fixed else self.pdf.name)
            if loaded is not None:
                sets.append((path_text, loaded))
                hashes.append(f"{Path(path_text).name}:{hashlib.sha256(raw).hexdigest()}")
        self._proposals_sig = signature
        self.proposal_sets = sets
        if not sets:
            self.proposals, self.proposals_sha = None, None
            return
        self.proposals = sets[0][1] if len(sets) == 1 else {
            "devices": [d for _, data in sets for d in data.get("devices", [])]}
        # The conversion version is part of it: a better conversion refreshes
        # pending machine boxes once, like an edited proposal file does.
        self.proposals_sha = hashlib.sha256(
            "\n".join([f"conversion:{PROPOSAL_CONVERSION}", *hashes]).encode()).hexdigest()

    def _proposals_record(self) -> dict:
        return {"paths": [path for path, _ in self.proposal_sets], "sha256": self.proposals_sha}

    def document_record(self) -> dict:
        """What the labels file records about the document and every source
        merged into it: per proposal file its kind, hash, model/extraction
        metadata, how many devices became boxes and why the others did not;
        and the DEVICES files' non-box findings (legend, page results,
        unresolved candidates, coverage notes)."""
        sources, extracted = [], {}
        for path_text, data in self.proposal_sets:
            path = Path(path_text)
            name = path.name
            kind = ("devices_json" if name.upper().endswith("_DEVICES.JSON") and "REFINED" not in name.upper()
                    else "vlm_reviewed" if "REFINED_DEVICES" in name.upper()
                    else "sam3_cleaned" if name.endswith("_CLEANED.json")
                    else "circuitnet" if "CIRCUITNET" in name.upper() else "sam3")
            why: dict[str, int] = {}
            usable = 0
            for device in data.get("devices", []):
                if not isinstance(device, dict):
                    continue
                number = device.get("pdf_page") or device.get("page")
                reason = (devices_skip_reason(device)
                          or ("page_outside_document" if type(number) is not int
                              or not 1 <= number <= self.page_count else None)
                          or ("not_an_electrical_device" if proposal_device(device) is None else None)
                          or ("no_box" if devices_bbox_points(device) is None
                              and not device.get("box_px_tl") else None))
                if reason:
                    why[reason] = why.get(reason, 0) + 1
                else:
                    usable += 1
            try:
                digest = sha256_file(path)
            except OSError:
                digest = None
            sources.append({
                "file": str(path), "kind": kind, "sha256": digest,
                "devices": len(data.get("devices", [])), "devices_used": usable, "devices_left_out": why,
                "vlm_rejected": len(data.get("rejected_devices") or []),
                **{k: data[k] for k in ("schema_version", "model", "status", "extraction_revision",
                                        "render_dpi", "score_threshold", "verify_status",
                                        "extraction_status", "coverage_limit") if k in data},
            })
            if kind == "devices_json":
                legend = []
                for entry in data.get("legend_types") or []:
                    if isinstance(entry, dict):
                        device = canonical_device(entry.get("device_type"), entry.get("subtype"),
                                                  description=entry.get("description"))
                        entry = dict(entry, catalog_class=f"{device[0]}.{device[1]}" if device else None)
                    legend.append(entry)
                data = dict(data, legend_types=legend)
                extracted[name] = {k: data[k] for k in (
                    "legend_types", "page_results", "plan_pages_processed", "unresolved_candidates",
                    "review_backlog", "source_profile", "classification_rules", "taxonomy_note",
                    "summary", "extraction_status", "coverage_limit", "exit_sign_schema_ref")
                    if k in data}
        return {"document": {"file": self.pdf.name, "path": str(self.pdf), "sha256": self.source_hash,
                             "page_count": self.page_count},
                "sources": sources, "extracted": extracted}

    def _init_proposals_record(self) -> None:
        """Pages saved before the proposal source was recorded count as
        reviewed against the file as it is now; later edits to it show up."""
        from annotator import document_labels
        try:
            data = document_labels.read(self.out_dir, self.json_prefix)
        except (OSError, ValueError):
            return
        if data["pages"] and "proposals" not in data:
            document_labels.set_proposals_record(self.out_dir, self.json_prefix,
                                                 self._proposals_record())

    def _baseline_proposals_sha(self, data: dict) -> str | None:
        if "proposals_sha256" in data:
            return data["proposals_sha256"]
        from annotator import document_labels
        return (document_labels.proposals_record(self.out_dir, self.json_prefix) or {}).get("sha256")

    def _refresh_proposals(self, data: dict, index: int, width: int, height: int) -> dict:
        """A saved page after its proposal file changed: untouched machine
        boxes are replaced by the file's current boxes for this page. Accepted,
        verified, hand-drawn and rejected boxes stay, and a new proposal on top
        of one of them (IoU >= 0.5) is left out."""
        kept = [i for i in data.get("instances", []) if isinstance(i, dict) and not _machine_pending(i)]
        rejected = [r for r in data.get("rejected_proposals") or [] if isinstance(r, dict)]
        blockers = [i.get("bbox_xyxy") for i in kept + rejected]
        taken = {i.get("instance_id") for i in kept + rejected}
        added = []
        for item in self._proposal_instances(index, width, height):
            if any(_box_iou(item["bbox_xyxy"], box) >= 0.5 for box in blockers):
                continue
            ident, n = item["instance_id"], 1
            while ident in taken:
                ident, n = f"{item['instance_id']}_r{n}", n + 1
            taken.add(ident)
            added.append(dict(item, instance_id=ident))
        out = dict(data, instances=kept + added, proposals_sha256=self.proposals_sha)
        if added:
            out["verified_no_symbols"] = False
        return out

    def resolve_pdf(self, path_or_name: str) -> Path:
        """A blueprint PDF by absolute path, path relative to the blueprints
        folder, or file name. Only training and evaluation drawings
        (:func:`openable_pdfs`) and the PDF the server was started with open."""
        raw = str(path_or_name or "").strip()
        if not raw or "\x00" in raw:
            raise LabelingError("document path or name required")
        bp_dir = self.blueprints_dir
        target = Path(raw).resolve() if Path(raw).is_absolute() else None
        if target is None or not target.is_file():
            candidate = (bp_dir / raw).resolve() if bp_dir else None
            if candidate is not None and candidate.is_file():
                target = candidate
            elif bp_dir and "/" not in raw and "\\" not in raw:
                # A name in both folders is the evaluation copy (blueprint_sets rule).
                matches = [p for p in openable_pdfs(bp_dir) if p.name == raw]
                target = matches[-1] if matches else None
        if target is None or not target.is_file():
            raise LabelingError(f"blueprint PDF not found: {raw}")
        from blueprint_symbols.blueprint_sets import EVALUATION_DIR
        allowed = (target in (self._origin_pdf, self.pdf) or
                   (bp_dir is not None and target.parent in (bp_dir, bp_dir / EVALUATION_DIR)))
        if target.suffix.lower() != ".pdf" or not allowed:
            raise LabelingError("only drawings in the blueprints folder or its evaluation "
                                "folder can be opened")
        return target

    def for_document(self, doc: str | None) -> "LabelingSession":
        """The session for the PDF a request names (``?doc=``).

        Every browser tab sends the PDF it is editing, so two tabs on different
        drawings never read or save each other's pages.
        """
        if not doc:
            return self
        if self.edit:
            if Path(doc).resolve() in (self.edit, *self.edit_files):
                return self
            raise LabelingError("this server edits annotation JSON files; it has no PDFs to open")
        target = self.resolve_pdf(doc)
        with self._documents_lock:
            if target == self.pdf:
                return self
            existing = self._documents.get(target)
            if existing is not None:
                stat = target.stat()
                if (stat.st_size, stat.st_mtime_ns) == (existing.source_stat.st_size,
                                                        existing.source_stat.st_mtime_ns):
                    return existing
                del self._documents[target]          # PDF replaced on disk: reopen it
                existing._close_fitz_doc()
        opened = LabelingSession(
            pdf=target, blueprints_dir=self.blueprints_dir, out_dir=self._initial_out_dir,
            dpi=self.dpi, neca_catalog=self._neca_catalog,
            detector_weights=self._detector_weights, auto_proposals=self.auto_proposals,
            token=self.token, lock=None,
        )
        opened._origin_pdf = self._origin_pdf
        opened.sheet_gate_mode = self.sheet_gate_mode
        opened.sheet_gate_confidence = self.sheet_gate_confidence
        with self._documents_lock:
            existing = self._documents.get(target)
            if existing is not None:
                stat = target.stat()
                if (stat.st_size, stat.st_mtime_ns) == (existing.source_stat.st_size,
                                                        existing.source_stat.st_mtime_ns):
                    opened._close_fitz_doc()
                    return existing
                del self._documents[target]
                existing._close_fitz_doc()
            while len(self._documents) >= self.MAX_OPEN_DOCUMENTS:
                evicted = self._documents.pop(next(iter(self._documents)))
                evicted._close_fitz_doc()
            self._documents[target] = opened
            return opened

    def _register_document(self) -> None:
        """Record the open PDF in the label store (document.json), so agents,
        status reports and training see the pages labeled here."""
        from annotator import label_store
        if not (self.out_dir / label_store.DOCUMENT_FILE).is_file():
            label_store.create_document(self.out_dir.parent, self.pdf, sha256=self.source_hash,
                                        meta={"origin": "annotator"})
        self.json_prefix = _store_json_prefix(self.out_dir) or self.json_prefix

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

    def page_address(self, index: int) -> Path:
        """How agents name a page: ``<folder>/<prefix>_pNNNN.json`` (the page
        file in --edit mode; for a PDF, an address inside the labels file)."""
        if self.edit:
            return self.edit_files[index]
        return self.out_dir / f"{self.json_prefix}_p{index + 1:04d}.json"

    def _json_path(self, index: int) -> Path:
        """The JSON holding a page: the page file in --edit mode, else the
        document's labels file (every page of a PDF is in one file)."""
        if self.edit:
            return self.edit_files[index]
        from annotator import document_labels
        return document_labels.labels_path(self.out_dir, self.json_prefix)

    def _close_fitz_doc(self) -> None:
        with self._fitz_lock:
            if self._fitz_doc is not None:
                try:
                    self._fitz_doc.close()
                except Exception:
                    pass
                self._fitz_doc = None
                self._fitz_stat = None

    @contextlib.contextmanager
    def _with_fitz_doc(self):
        fitz = _import_fitz()
        with self._fitz_lock:
            st = self.pdf.stat()
            sig = (st.st_size, st.st_mtime_ns)
            if self._fitz_doc is None or self._fitz_stat != sig:
                if self._fitz_doc is not None:
                    try:
                        self._fitz_doc.close()
                    except Exception:
                        pass
                    self._fitz_doc = None
                self._fitz_doc = fitz.open(self.pdf)
                self._fitz_stat = sig
            yield fitz, self._fitz_doc

    def _render_page_with_cached_doc(self, index: int, images_dir: Path) -> tuple[Path, int, int]:
        with self._with_fitz_doc() as (fitz, doc):
            pix = _pixmap_for_page(fitz, doc, index, self.dpi)
        return _write_pixmap_png(pix, index, self.dpi, images_dir, self.source_hash)

    def _raster(self, index: int) -> tuple[Path, int, int, str]:
        self._assert_pdf_unchanged()
        if self.edit:
            cached = self._render_cache.get(index)
            if cached is not None:
                if cached[0].is_file():
                    if sha256_file(cached[0]) != cached[3]:
                        raise RevisionConflict("source image changed during labeling; reopen the session")
                    return cached
                self._render_cache.pop(index, None)
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

        pdf_path, source_hash, dpi = self.pdf, self.source_hash, self.dpi
        with self._render_lock:
            cached = self._render_cache.get(index)
            cached_stat = self._render_stats.get(index)
        if cached is not None:
            path = cached[0]
            try:
                st = path.stat()
            except OSError:
                with self._render_lock:
                    if self._render_cache.get(index) == cached:
                        self._render_cache.pop(index, None)
                        self._render_stats.pop(index, None)
            else:
                sig = (st.st_size, st.st_mtime_ns)
                if cached_stat == sig:
                    return cached
                if sha256_file(path) != cached[3]:
                    raise RevisionConflict("source image changed during labeling; reopen the session")
                with self._render_lock:
                    if self._render_cache.get(index) == cached:
                        self._render_stats[index] = sig
                return cached

        # A page rendered earlier for this PDF revision and DPI is reused: the
        # render is deterministic and the annotation pins its SHA-256 anyway.
        images_dir = self.out_dir / "images"
        found = _find_rendered(images_dir, index, dpi, source_hash)
        if found is None:
            if render_pdf_page is _DEFAULT_RENDER_PDF_PAGE:
                with _render_slot_lock(str(pdf_path), index, dpi):
                    found = _find_rendered(images_dir, index, dpi, source_hash)
                    if found is None:
                        found = self._render_page_with_cached_doc(index, images_dir)
            else:
                found = render_pdf_page(pdf_path, index, dpi, images_dir, source_hash)
        path, width, height = found
        image_hash = sha256_file(path)
        st = path.stat()
        result = path, width, height, image_hash
        with self._render_lock:
            if (self.pdf, self.source_hash, self.dpi) == (pdf_path, source_hash, dpi):
                self._render_cache[index] = result
                self._render_stats[index] = (st.st_size, st.st_mtime_ns)
        return result

    def _proposal_instances(self, index: int, width: int, height: int,
                            sets: list[tuple[str, dict]] | None = None) -> list[dict]:
        """Pending boxes for a page: the extracted DEVICES.json devices and the
        SAM/CircuitNet proposals for this PDF, strongest source first. A box
        from a later source on top of one already found (IoU >= 0.5) is left out."""
        if sets is None:
            sets = self.proposal_sets or ([("", self.proposals)] if self.proposals else [])
        found: list[dict] = []
        # Boxes a VLM review dropped as false positives hide that box from
        # every other source (the raw run it reviewed included).
        dropped = [d["bbox_xyxy"] for _, proposals in sets if proposals.get("rejected_devices")
                   for d in self._proposal_set_instances(proposals, index, width, height, "",
                                                         rejected=True)]
        for order, (path_text, proposals) in enumerate(sets):
            tag = "" if order == 0 else f"s{order}_"
            for item in self._proposal_set_instances(proposals, index, width, height, tag):
                if order and any(_box_iou(item["bbox_xyxy"], f["bbox_xyxy"]) >= 0.5 for f in found):
                    continue
                if any(_box_iou(item["bbox_xyxy"], box) >= 0.5 for box in dropped):
                    continue
                if path_text:
                    item["proposal_file"] = Path(path_text).name
                found.append(item)
        return found

    def _proposal_set_instances(self, proposals: dict, index: int, width: int, height: int,
                                tag: str, *, rejected: bool = False) -> list[dict]:
        """Boxes of one proposal file on one page, with everything else the
        file says about each device kept on the box (``proposal_details``)
        and its centre / wall point as anchors. ``rejected``: the devices a
        reviewer (a VLM review sheet) dropped as false positives instead."""
        fitz = _import_fitz()
        found = []
        rotated = devices_rotated_coordinates(proposals)
        render_dpi = float(proposals.get("render_dpi") or 150)
        # Extracted drawing-set devices carry the PDF name; SAM/CircuitNet runs do not.
        default_source = "devices_json" if proposals.get("source_file") and not \
            proposals.get("render_dpi") else "sam3"
        if rejected:
            entries = [(f"x{n}", d) for n, d in enumerate(proposals.get("rejected_devices") or [])]
        else:
            entries = [(str(n), d) for n, d in enumerate(proposals.get("devices", []))]
            # Unresolved candidates that do have a symbol box are boxes to review too.
            entries += [(f"u{n}", dict(d, device_type=d.get("device_type") or d.get("inferred_type"),
                                       subtype=d.get("subtype") or d.get("inferred_subtype"),
                                       status="devices_json_unresolved"))
                        for n, d in enumerate(proposals.get("unresolved_candidates") or [])
                        if isinstance(d, dict) and devices_bbox_points(d) is not None]
        with self._with_fitz_doc() as (_, doc):
            page = doc[index]
            for number, proposal in entries:
                if not isinstance(proposal, dict):
                    continue
                if (proposal.get("pdf_page") or proposal.get("page")) != index + 1:
                    continue
                if devices_skip_reason(proposal):
                    continue
                try:
                    pixels = proposal.get("box_px_tl")
                    if isinstance(pixels, list) and len(pixels) == 4:
                        factor = proposal_pixel_factor(page, self.dpi, render_dpi,
                                                       proposals.get("max_pixels"))
                        x0, y0, x1, y1 = (float(v) * factor for v in pixels)
                        box = [max(0., min(float(width), x0)), max(0., min(float(height), y0)),
                               max(0., min(float(width), x1)), max(0., min(float(height), y1))]
                    else:
                        pts = devices_bbox_points(proposal)
                        if pts is None:
                            continue
                        box = list(points_to_pixels(pts, page, width, height, already_rotated=rotated))
                    _valid_box(box, width, height)
                except (ValueError, TypeError, ZeroDivisionError):
                    continue
                if rejected:
                    found.append({"bbox_xyxy": box})
                    continue
                raw = proposal.get("device_type") or proposal.get("broad_family")
                subtype = proposal.get("subtype")
                # The catalog class (the annotator's device list), whatever the
                # source calls it; None: not an electrical device.
                device = proposal_device(proposal)
                if device is None:
                    continue
                family, variant = device
                extra = {"raw_subtype": subtype} if subtype else {}
                anchors = _proposal_anchors(proposal, page, width, height, rotated)
                if anchors:
                    extra["anchors"] = anchors
                details = _proposal_details(proposal)
                if details:
                    extra["proposal_details"] = details
                if proposal.get("review_source"):
                    extra["proposal_reviewer"] = proposal["review_source"]
                found.append(pending_instance(
                    f"proposal_p{index + 1}_{tag}{number}", family, variant, box,
                    review_source=str(proposal.get("status") or default_source),
                    discipline=discipline_for(family), device_type=family,
                    raw_device_type=raw, proposal_score=proposal.get("score"),
                    proposal_visual_group=proposal.get("visual_group"),
                    proposal_neca_symbol_id=proposal.get("neca_symbol_id"),
                    proposal_alternative_labels=proposal.get("alternative_labels", []), **extra))
        return found

    def _new_sheet(self, index: int, image: Path, width: int, height: int, image_hash: str) -> dict:
        page_path = self._json_path(index)
        if self.pdf:
            result = page_header(
                source=self.pdf, document_id=self.pdf.name, page_index=index,
                source_type="real_construction_pdf_manual", source_sha256=self.source_hash,
                image_path=str(image.relative_to(self.out_dir)), image_sha256=image_hash,
                width=width, height=height)
            result["instances"] = self._proposal_instances(index, width, height)
            result["proposals_sha256"] = self.proposals_sha
            return result
        return json.loads(page_path.read_text(encoding="utf-8"))

    def _stored_page(self, index: int) -> tuple[dict | None, str | None, Path]:
        """The saved page, its revision and the folder its image_path is relative to."""
        path = self._json_path(index)
        if self.edit:
            if not path.is_file():
                return None, None, path.parent
            raw = path.read_bytes()
            return json.loads(raw), hashlib.sha256(raw).hexdigest(), path.parent
        from annotator import document_labels
        data, revision = document_labels.get_page(self.out_dir, self.json_prefix, index + 1)
        return data, revision, self.out_dir

    def _load(self, index: int) -> tuple[dict, str | None, Path]:
        self._page_index(index)
        self._sync_proposals()
        image, width, height, image_hash = self._raster(index)
        path = self._json_path(index)
        data, revision, base = self._stored_page(index)
        self._stored_revisions[index] = revision
        if data is not None:
            if self.edit and _resolve_edit_image(path, str(data.get("image_path", ""))) != image:
                raise RevisionConflict("source image reference changed during labeling; reopen the session")
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
            if self.pdf and image.parent.parent == base:
                # Same bytes (hash checked above), possibly under a newer render
                # name: training reads image_path, so it must name this file.
                data["image_path"] = str(image.relative_to(base))
            data = _normalize_loaded_document(data)
            if self.pdf and self._baseline_proposals_sha(data) != self.proposals_sha:
                data = self._refresh_proposals(data, index, width, height)
                revision = hashlib.sha256(f"{revision}:{self.proposals_sha}".encode()).hexdigest()
        else:
            data = self._new_sheet(index, image, width, height, image_hash)
            revision = None
        if data.get("coordinate_system") != "original_page_pixels_top_left_y_down":
            raise LabelingError("unsupported annotation coordinate system")
        return data, revision, image

    def page_pixel_size(self, index: int) -> tuple[int, int]:
        """The rendered size of a page without rendering it (same scale and
        pixel-count cap as ``render_pdf_page``)."""
        found = _find_rendered(self.out_dir / "images", index, self.dpi, self.source_hash)
        if found:
            return found[1], found[2]
        with self._with_fitz_doc() as (fitz, doc):
            sheet = doc[index]
            factor = self.dpi / 72.0
            if sheet.rect.width * factor * sheet.rect.height * factor > MAX_PDF_PIXELS:
                factor = math.sqrt(MAX_PDF_PIXELS / (sheet.rect.width * sheet.rect.height))
            rect = (sheet.rect * fitz.Matrix(factor, factor)).irect
            return rect.width, rect.height

    def merge_pages(self, incoming: dict[int, list[dict]], *, with_proposals: bool = True) -> dict:
        """Merge labels from elsewhere (older page files, other machines,
        agents, models) into this PDF's labels file, without rendering.

        ``incoming`` maps page index -> page JSONs of this PDF. Per page, every
        box is kept once: where two overlap (IoU >= 0.5) the more settled one
        stays (verified > accepted > drawn by a person > machine proposal). A
        box a person rejected anywhere is not proposed again. A page a person
        verified as having no symbols keeps no machine boxes. With
        ``with_proposals``, pages with machine proposals (DEVICES, SAM,
        CircuitNet) are written too, so every box is in the one file.
        Boxes that fail the annotator's checks are left out and counted.
        """
        from annotator import document_labels
        report = {"pages_written": 0, "boxes_added": 0, "boxes_upgraded": 0,
                  "rejected_added": 0, "invalid_dropped": 0}
        with self.lock:
            self._sync_proposals()
            pages = set(incoming)
            if with_proposals:
                for _, data in self.proposal_sets:
                    for device in [*data.get("devices", []), *(data.get("unresolved_candidates") or [])]:
                        number = device.get("pdf_page") or device.get("page") if isinstance(device, dict) else None
                        if type(number) is int and 1 <= number <= self.page_count:
                            pages.add(number - 1)
            for index in sorted(pages):
                if not 0 <= index < self.page_count:
                    continue
                if self._merge_page(index, incoming.get(index, []), report):
                    report["pages_written"] += 1
            if report["pages_written"] or document_labels.pages(self.out_dir, self.json_prefix):
                record = self.document_record()
                record["page_sources"] = sorted({str(p.get("_merged_from")) for pages_ in incoming.values()
                                                 for p in pages_ if p.get("_merged_from")})
                document_labels.set_document_info(self.out_dir, self.json_prefix, record)
        return report

    def _merge_page(self, index: int, sources: list[dict], report: dict) -> bool:
        from annotator import document_labels
        width, height = self.page_pixel_size(index)
        stored, stored_revision = document_labels.get_page(self.out_dir, self.json_prefix, index + 1)
        if stored is not None:
            page = _normalize_loaded_document(stored)
            if self._baseline_proposals_sha(page) != self.proposals_sha:
                page = self._refresh_proposals(page, index, width, height)
        else:
            image = f"images/page_{index + 1:04d}_{self.source_hash[:12]}_d{self.dpi}_{width}x{height}.png"
            page = page_header(
                source=self.pdf, document_id=self.pdf.name, page_index=index,
                source_type="real_construction_pdf_manual", source_sha256=self.source_hash,
                image_path=image, image_sha256=None, width=width, height=height)
            page["instances"] = self._proposal_instances(index, width, height)
        instances = [canonical_instance(dict(i)) for i in page.get("instances", []) if isinstance(i, dict)]
        rejected = [canonical_instance(dict(r)) for r in page.get("rejected_proposals") or []
                    if isinstance(r, dict)]
        negative = page.get("verified_no_symbols") is True
        complete = page.get("page_complete") is True
        for source in sources:
            sw, sh = source.get("image_width"), source.get("image_height")
            sx = width / float(sw) if sw else 1.0
            sy = height / float(sh) if sh else 1.0
            for item in source.get("rejected_proposals") or []:
                if not isinstance(item, dict) or not item.get("bbox_xyxy"):
                    continue
                item = canonical_instance(_scaled(item, sx, sy))
                if any(_box_iou(item["bbox_xyxy"], r.get("bbox_xyxy")) >= 0.5 for r in rejected):
                    continue
                rejected.append(item)
                report["rejected_added"] += 1
            for item in source.get("instances") or []:
                if not isinstance(item, dict) or not item.get("bbox_xyxy"):
                    continue
                item = canonical_instance(_scaled(item, sx, sy))
                twin = next((i for i in instances if _box_iou(item["bbox_xyxy"], i.get("bbox_xyxy")) >= 0.5), None)
                if twin is None:
                    instances.append(item)
                    report["boxes_added"] += 1
                elif _review_rank(item) > _review_rank(twin):
                    instances[instances.index(twin)] = item
                    report["boxes_upgraded"] += 1
            negative = negative or source.get("verified_no_symbols") is True
            complete = complete or source.get("page_complete") is True
            for key in ("vlm_assessment", "notes"):
                if source.get(key) and not page.get(key):
                    page[key] = source[key]
        # A box a person rejected is not kept as a pending machine proposal.
        instances = [i for i in instances if _review_rank(i) > 0 or not any(
            _box_iou(i.get("bbox_xyxy"), r.get("bbox_xyxy")) >= 0.5 for r in rejected)]
        if negative and not any(_review_rank(i) > 0 for i in instances):
            instances = []
        negative = negative and not instances
        # Ids unique across boxes and rejected boxes; each box checked on its own.
        taken: set[str] = set()
        clean = []
        for item in instances:
            if item.get("verified_ground_truth") is True and incomplete_reason(item):
                item.update(verified_ground_truth=False, uncertain=True, review_status="unreviewed")
            ident, n = str(item.get("instance_id") or "merged"), 1
            while ident in taken or not ID_PATTERN.fullmatch(ident):
                ident, n = f"merged_p{index + 1}_{n}", n + 1
            item["instance_id"] = ident
            try:
                validate_instances([item], width, height)
            except (LabelingError, ValueError, TypeError):
                report["invalid_dropped"] += 1
                continue
            taken.add(ident)
            clean.append(_normalize_instance_for_save(item))
        clean_rejected = []
        for item in rejected:
            item.update(review_status="rejected", review_source="human", verified_ground_truth=False,
                        uncertain=True)
            ident, n = str(item.get("instance_id") or "rejected"), 1
            while ident in taken or not ID_PATTERN.fullmatch(ident):
                ident, n = f"rejected_p{index + 1}_{n}", n + 1
            item["instance_id"] = ident
            try:
                _valid_box(item.get("bbox_xyxy"), width, height)
            except (LabelingError, ValueError, TypeError):
                report["invalid_dropped"] += 1
                continue
            taken.add(ident)
            clean_rejected.append(_normalize_instance_for_save(item))
        complete = complete and not negative and all(i.get("verified_ground_truth") is True for i in clean)
        page.update(instances=clean, rejected_proposals=clean_rejected, page_complete=complete,
                    proposals_sha256=self.proposals_sha, image_width=width, image_height=height)
        page.update(group_fields(self.pdf))
        page.update(completeness(verified_no_symbols=negative, page_complete=complete))
        if stored is not None and page == stored:
            return False
        if stored is None and not clean and not clean_rejected and not negative and not sources:
            return False
        document_labels.write_page(self.out_dir, self.json_prefix, index + 1, page,
                                   expected_revision=stored_revision)
        return True

    def set_training_ready(self, ready: object) -> dict:
        """Mark this PDF done for now (training uses it) or not done."""
        from annotator import document_labels
        from blueprint_symbols.label_schema import training_usable
        if not self.pdf:
            raise LabelingError("only a PDF can be marked done for training")
        if type(ready) is not bool:
            raise LabelingError("ready must be true or false")
        with self.lock:
            record = document_labels.set_training_ready(self.out_dir, self.json_prefix, ready)
            pages = document_labels.pages(self.out_dir, self.json_prefix)
            usable = sorted(n for n, data in pages.items() if training_usable(data))
        return {"training_ready": record["ready"], "set_at": record["set_at"],
                "usable_pages": usable, "saved_pages": len(pages)}

    def is_training_ready(self) -> bool:
        from annotator import document_labels
        return bool(self.pdf) and document_labels.training_ready(self.out_dir, self.json_prefix)

    def _proposed_page_counts(self) -> dict[int, int]:
        if self.proposals_sha is not None and self._proposed_counts_cache is not None:
            cached_sha, cached_obj, cached_counts = self._proposed_counts_cache
            if cached_sha == self.proposals_sha and cached_obj is self.proposals:
                return dict(cached_counts)
        proposed: dict[int, int] = {}
        proposals = self.proposals or {}
        entries = list(proposals.get("devices", []))
        for cand in proposals.get("unresolved_candidates") or []:
            if isinstance(cand, dict) and devices_bbox_points(cand) is not None:
                entries.append(dict(
                    cand,
                    device_type=cand.get("device_type") or cand.get("inferred_type"),
                    subtype=cand.get("subtype") or cand.get("inferred_subtype"),
                ))
        for device in entries:
            if not isinstance(device, dict):
                continue
            number = device.get("pdf_page") or device.get("page")
            if type(number) is not int or not 1 <= number <= self.page_count:
                continue
            if devices_skip_reason(device) or proposal_device(device) is None:
                continue
            proposed[number] = proposed.get(number, 0) + 1
        if self.proposals_sha is not None:
            self._proposed_counts_cache = (self.proposals_sha, self.proposals, dict(proposed))
        return proposed

    def page_overview(self) -> dict:
        """Which pages need review, without opening them: counts from every
        saved page (the document's labels file, parsed once per change), and
        for pages never opened, the machine proposals that will appear as
        pending boxes on first open. A saved page whose proposal file changed
        since counts the new proposals too (an estimate until it is opened)."""
        cache = self._overview_cache
        rows: dict[int, dict] = {}
        with self.lock:
            for index in range(self.page_count) if self.edit else ():
                self._overview_row(self._json_path(index), index, rows, cache)
            if self.pdf:
                from annotator import document_labels
                self._sync_proposals()
                raw_doc = document_labels.read(self.out_dir, self.json_prefix)
                if (self.proposals_sha is not None and self._saved_overview_cache is not None
                        and self._saved_overview_cache[0] is raw_doc
                        and self._saved_overview_cache[1] == self.proposals_sha
                        and self._saved_overview_cache[2] is self.proposals):
                    return {"page_count": self.page_count, "pages": list(self._saved_overview_cache[3])}
                proposed = self._proposed_page_counts()
                record_sha = (raw_doc.get("proposals") or {}).get("sha256")
                for key, data in raw_doc["pages"].items():
                    number = int(key)
                    if not 1 <= number <= self.page_count:
                        continue
                    row = rows[number - 1] = {"page": number - 1, **_page_counts(data)}
                    baseline = data.get("proposals_sha256", record_sha)
                    if self.proposals is not None and baseline != self.proposals_sha and proposed.get(number):
                        items = [i for i in data.get("instances") or [] if isinstance(i, dict)]
                        settled = sum(1 for i in items if str(i.get("instance_id", "")).startswith("proposal_")
                                      and not _machine_pending(i))
                        settled += len(data.get("rejected_proposals") or [])
                        human_pending = sum(1 for i in items if not _machine_pending(i)
                                            and i.get("review_status") != "accepted")
                        row["pending"] = human_pending + max(0, proposed[number] - settled)
                for number, count in proposed.items():
                    if number - 1 not in rows:
                        rows[number - 1] = {"page": number - 1, "saved": False, "pending": count,
                                            "verified": 0, "complete": False, "no_symbols": False}
                pages_list = [rows[k] for k in sorted(rows)]
                if self.proposals_sha is not None:
                    self._saved_overview_cache = (raw_doc, self.proposals_sha, self.proposals, pages_list)
                return {"page_count": self.page_count, "pages": list(pages_list)}
        return {"page_count": self.page_count, "pages": [rows[k] for k in sorted(rows)]}

    @staticmethod
    def _overview_row(path: Path, index: int, rows: dict, cache: dict) -> None:
        try:
            stat = path.stat()
        except OSError:
            return
        known = cache.get(str(path))
        if known is None or known[:2] != (stat.st_size, stat.st_mtime_ns):
            try:
                data = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                return
            cache[str(path)] = (stat.st_size, stat.st_mtime_ns, _page_counts(data))
        rows[index] = {"page": index, **cache[str(path)][2]}

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

    def detector_info(self) -> dict:
        from blueprint_symbols.page_detector import describe_detector_weights
        return describe_detector_weights(self._detector_weights, work_dir=DEFAULT_WORK_DIR)

    # Page gate for Evaluate page (set from --sheet-gate / --sheet-gate-confidence,
    # copied to sessions opened for other PDFs). Unknown sheets are never gated.
    sheet_gate_mode: str = "off"
    sheet_gate_confidence: float = 0.90

    def sheet_class(self, index: int):
        """Sheet discipline of a PDF page from its text layer (None: no PDF/text)."""
        if not self.pdf:
            return None
        from blueprint_symbols.sheet_gate import classify_pdf_page
        try:
            with self._with_fitz_doc() as (_fitz, doc):
                return classify_pdf_page(doc[index])
        except Exception:                      # unreadable text layer: unknown, not gated
            return None

    def evaluate_page(self, index: int, *, confidence: object = 0.25,
                      confidence_source: object = "request",
                      sheet_gate: object = None) -> dict:
        """Run deterministic local detector inference on one page without mutating disk."""
        if (isinstance(confidence, bool) or not isinstance(confidence, (int, float))
                or not math.isfinite(confidence)):
            raise LabelingError("confidence must be a finite number between 0.01 and 0.99")
        conf = float(confidence)
        if not 0.01 <= conf <= 0.99:
            raise LabelingError("confidence must be between 0.01 and 0.99")
        if confidence_source not in ("request", "manual", "recommended", "default"):
            raise LabelingError("confidence_source must be request, manual, recommended or default")
        from blueprint_symbols.sheet_gate import GATE_MODES, GatePolicy
        mode = self.sheet_gate_mode if sheet_gate is None else sheet_gate
        if mode not in GATE_MODES:
            raise LabelingError(f"sheet_gate must be one of {', '.join(GATE_MODES)}")
        policy = GatePolicy(mode=mode, raised_confidence=self.sheet_gate_confidence)
        with self.lock:
            _, _, img_path = self._load(index)
        import cv2
        from blueprint_symbols.page_detector import get_cached_detector

        image = cv2.imread(str(img_path), cv2.IMREAD_COLOR)
        if image is None:
            raise LabelingError(f"could not read rendered page image: {img_path.name}")
        sheet = self.sheet_class(index) if mode != "off" else None
        detector = get_cached_detector(self._detector_weights, work_dir=DEFAULT_WORK_DIR)
        instances, stats = detector.predict_page(
            image, confidence=conf, sheet=sheet, gate=policy,
            confidence_source=str(confidence_source), dpi=self.dpi,
        )
        for inst in instances:
            family = inst["symbol_family"]
            inst["discipline"] = discipline_for(family)
            inst["device_type"] = family
        validate_instances(instances, int(image.shape[1]), int(image.shape[0]))
        return {"instances": instances, **stats}

    def page_document(self, index: int) -> tuple[dict, str | None, Path]:
        """Public accessor for the page JSON, its revision and rendered image path."""
        with self.lock:
            return self._load(index)

    def page_state(self, index: int) -> dict:
        with self.lock:
            data, revision, _ = self._load(index)
            json_path = self._json_path(index)
            doc_name = self.pdf.name if self.pdf else (self.edit.name if self.edit else "")
            doc_path = str(self.pdf.resolve()) if self.pdf else (str(self.edit.resolve()) if self.edit else "")
            image_hash = str(data.get("image_sha256") or "")
            return {
                "page_index": index,
                "page_count": self.page_count,
                "revision": revision,
                "document": data,
                "image_url": f"/api/image?page={index}"
                + (f"&doc={quote(str(self.pdf), safe='')}" if self.pdf else "")
                + (f"&v={image_hash[:16]}" if image_hash else ""),
                "families": sorted(FAMILY_DISCIPLINES),
                "family_disciplines": FAMILY_DISCIPLINES,
                "variants_by_family": self.variants_by_family,
                "attribute_schema": _attribute_schema_payload(),
                "proposals_enabled": self.proposals is not None,
                "proposals_version": self.proposals_sha,
                "training_ready": self.is_training_ready(),
                "page_presence_suggestion": self.page_presence.get(index),
                "mode": "edit" if self.edit else "pdf",
                "json_filename": json_path.name if self.edit else f"{json_path.name} (page {index + 1})",
                "json_path": str(json_path),
                "current_doc_name": doc_name,
                "current_doc_path": doc_path,
                "sibling_files": self.list_sibling_jsons() if self.edit else [],
                "walls": self.walls,
            }

    def _ready_stems(self) -> set[str]:
        """Sanitized stems of PDFs marked done for training."""
        from annotator import document_labels, label_store
        roots = []
        if os.environ.get("BPS_LABELS_DIR"):
            roots += label_store.store_roots(label_store.default_store())
        if self.blueprints_dir:
            roots += label_store.store_roots(self.blueprints_dir)
        stems = set()
        for root in roots:
            for labels in root.glob(f"*/*{document_labels.SUFFIX}") if root.is_dir() else ():
                prefix = labels.name[:-len(document_labels.SUFFIX)]
                if document_labels.training_ready(labels.parent, prefix):
                    stems.add(labels.parent.name.rsplit("_", 1)[0])
        return stems

    def _labeled_stems(self) -> set[str]:
        """Sanitized PDF stems that have saved pages in the label store."""
        from annotator import document_labels, label_store
        roots = []
        if os.environ.get("BPS_LABELS_DIR"):
            roots += label_store.store_roots(label_store.default_store())
        if self.blueprints_dir:
            roots += label_store.store_roots(self.blueprints_dir)
        if self._initial_out_dir:
            roots.append(self._initial_out_dir)
        stems: set[str] = set()
        for root in roots:
            if not root.is_dir():
                continue
            for folder in root.iterdir():
                if folder.is_dir() and document_labels.has_labels(folder):
                    stems.add(folder.name.rsplit("_", 1)[0])
            if document_labels.has_labels(root):                       # flat --out-dir
                for page in root.glob(f"*{document_labels.SUFFIX}"):
                    stems.add(re.sub(r"[^a-zA-Z0-9_-]+", "_", page.name[:-len(document_labels.SUFFIX)])[:48])
                for page in root.glob("*_p[0-9][0-9][0-9][0-9]*.json"):
                    stems.add(re.sub(r"[^a-zA-Z0-9_-]+", "_", page.name.rsplit("_p", 1)[0])[:48])
        return stems

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

        pdf_paths = openable_pdfs(bp_dir)
        docs = []
        current_resolved = self.pdf.resolve() if self.pdf else None
        labeled = self._labeled_stems()
        proposal_lookup = _build_proposal_lookup(bp_dir)

        ready_stems = self._ready_stems()
        for resolved in pdf_paths:
            stem = resolved.stem
            is_current = (resolved == current_resolved)
            prop_file = proposal_lookup(resolved)
            has_prop = (prop_file is not None)
            safe_stem = re.sub(r"[^a-zA-Z0-9_-]+", "_", stem)[:48]
            annotated = safe_stem in labeled
            ready = safe_stem in ready_stems

            try:
                rel = str(resolved.relative_to(bp_dir))
            except ValueError:
                rel = resolved.name

            docs.append({
                "name": resolved.name,
                "stem": stem,
                "path": str(resolved),
                "rel_path": rel,
                "has_proposals": has_prop,
                "proposal_file": prop_file.name if prop_file else None,
                "has_annotations": annotated,
                "training_ready": ready,
                "is_current": is_current,
            })

        # The order must not depend on which document is open: "Next PDF" walks
        # this list, and putting the current one first made it bounce between
        # the first two entries.
        def sort_key(d):
            prio = 2
            if d["has_annotations"]:
                prio = 0
            elif d["has_proposals"]:
                prio = 1
            return (prio, d["rel_path"].lower())

        docs.sort(key=sort_key)
        return docs

    def prefetch(self, path: str | None, pages: list[int]) -> dict:
        """Pre-render pages of this or another blueprint PDF in the background."""
        if (not isinstance(pages, list) or not pages
                or not all(type(p) is int and 0 <= p < 1_000_000 for p in pages[:8])):
            raise LabelingError("pages must be a list of page indexes")
        pages = pages[:8]
        if path:
            target = self.resolve_pdf(path)
        elif self.pdf:
            target = self.pdf
        else:
            return {"queued": False}
        known = self if target == self.pdf else self._documents.get(target)
        if known is not None:
            pages = [p for p in pages if p < known.page_count]
            if not pages:
                return {"queued": False}
        sha = known.source_hash if known is not None else _SHA_CACHE.get(
            (str(target), target.stat().st_size, target.stat().st_mtime_ns))
        queued = PREFETCHER.submit(target, pages, self.dpi, self._initial_out_dir, sha)
        return {"queued": queued}

    def switch_document(self, path_or_name: str) -> dict:
        """Change the PDF that requests without ``?doc=`` use (agents, old tabs)."""
        target = self.resolve_pdf(path_or_name)

        with self.lock:
            self._close_fitz_doc()
            self.pdf = target
            self.edit = None
            self.edit_files = []
            with self._render_lock:
                self._render_cache.clear()
                self._render_stats.clear()
            # Wall centerlines and page-presence suggestions were derived from
            # the previous PDF; keeping them would guide the new document with
            # another drawing's geometry and page verdicts.
            self.walls = []
            self.page_presence = {}
            self.source_hash = cached_sha256(self.pdf)
            self.source_stat = self.pdf.stat()
            fitz = _import_fitz()
            doc = fitz.open(self.pdf)
            self.page_count = len(doc)
            if self.page_count < 1:
                doc.close()
                raise LabelingError("PDF has no pages")
            with self._fitz_lock:
                self._fitz_doc = doc
                self._fitz_stat = (self.source_stat.st_size, self.source_stat.st_mtime_ns)
            self.start_page = 0

            self.json_prefix = self.pdf.stem[:48]
            if not self._initial_out_dir:
                self.out_dir = default_out_dir(self.pdf, self.source_hash)
                self.out_dir.mkdir(parents=True, exist_ok=True)
                self._register_document()
            self.json_prefix = _store_json_prefix(self.out_dir) or self.json_prefix
            self._proposals_fixed, self._proposals_sig = None, None
            self._proposed_counts_cache = None
            self._saved_overview_cache = None
            self._stored_revisions.clear()
            self._sync_proposals(force=True)
            self._init_proposals_record()

            return self.page_state(0)

    def export_server(self, target_path: str | None = None) -> dict:
        with self.lock:
            if not self.pdf and not self.edit:
                raise LabelingError("no active document to export")

            stem = self.pdf.stem if self.pdf else self.edit.stem
            doc_name = self.pdf.name if self.pdf else self.edit.name

            export_dir = (self.out_dir / "exports").resolve()
            if target_path:
                export_file = Path(target_path)
                export_file = (export_file if export_file.is_absolute() else export_dir / export_file).resolve()
                if export_dir not in export_file.parents or export_file.suffix.lower() != ".json":
                    raise LabelingError(f"exports are written as .json files inside {export_dir}")
            else:
                export_file = export_dir / f"{stem}_full_export.json"

            export_file.parent.mkdir(parents=True, exist_ok=True)

            annotated_pages = []
            total_instances = 0
            verified_instances = 0
            instances_by_family: dict[str, int] = {}

            if self.pdf:
                from annotator import document_labels
                saved = document_labels.pages(self.out_dir, self.json_prefix)
                stored = {n - 1: d for n, d in saved.items() if 1 <= n <= self.page_count}
            else:
                stored = {}
                for page_idx in range(self.page_count):
                    try:
                        stored[page_idx] = json.loads(self._json_path(page_idx).read_text(encoding="utf-8"))
                    except (OSError, ValueError):
                        pass
            for page_idx in range(self.page_count):
                page_json = self._json_path(page_idx)
                saved_page = page_idx in stored
                # Unsaved pages are listed without rendering them (a long set
                # would otherwise render every sheet to export).
                p_data = stored.get(page_idx) or {
                    "image_width": None, "image_height": None,
                    "instances": [], "rejected_proposals": [],
                    "verified_no_symbols": False,
                    "annotation_completeness": "unreviewed",
                }

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
                    "image_width": p_data.get("image_width"),
                    "image_height": p_data.get("image_height"),
                    "json_path": str(page_json) if saved_page else None,
                    "verified_no_symbols": p_data.get("verified_no_symbols", False),
                    "annotation_completeness": p_data.get("annotation_completeness", "partial" if saved_page else "unreviewed"),
                    "instance_count": len(insts),
                    "instances": insts,
                    "rejected_proposals": p_data.get("rejected_proposals", []),
                })

            export_payload = {
                "format_version": 1,
                "export_type": "full_document_annotations",
                "document_name": doc_name,
                "source_pdf_sha256": self.source_hash,
                "exported_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                "total_pdf_pages": self.page_count,
                "annotated_pages_count": len([p for p in annotated_pages if p["instance_count"] > 0 or p["verified_no_symbols"]]),
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
                "annotated_pages_count": len([p for p in annotated_pages if p["instance_count"] > 0 or p["verified_no_symbols"]]),
                "total_instances": total_instances,
                "verified_instances": verified_instances,
                "export_payload": export_payload,
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
        """Add boxes from a listed server source (proposals, CircuitNet/SAM
        output, DEVICES.json or a full-document export) as pending boxes.

        Every page goes through the same checks and revision guard as a save,
        and nothing is written unless every affected page passes them.
        """
        target = Path(source_path).resolve()
        with self.lock:
            listed = {Path(s["path"]) for s in self.list_server_sources(page_index)}
            if target not in listed:
                raise LabelingError("import only from the sources listed for this document")
            if not target.is_file():
                raise FileNotFoundError(f"server file not found: {source_path}")
            raw = json.loads(target.read_text(encoding="utf-8"))

            incoming: dict[int, list[dict]] = {}
            negatives: dict[int, bool] = {}
            if isinstance(raw, dict) and isinstance(raw.get("devices"), list):
                baseline, _, _ = self._load(page_index)
                incoming[page_index] = self._proposal_instances(          # this file, this page
                    page_index, int(baseline["image_width"]), int(baseline["image_height"]),
                    sets=[("", raw)])
            elif isinstance(raw, dict) and isinstance(raw.get("pages"), list):
                # Review decisions carry over only from an export of this exact PDF.
                same_pdf = bool(self.source_hash) and raw.get("source_pdf_sha256") == self.source_hash
                for p in raw["pages"]:
                    p_idx = p.get("page_index") if isinstance(p, dict) else None
                    if type(p_idx) is not int or not 0 <= p_idx < self.page_count:
                        continue
                    incoming.setdefault(p_idx, []).extend(
                        x if same_pdf else _as_pending_import(x)
                        for x in p.get("instances", []) if isinstance(x, dict))
                    if same_pdf and p.get("verified_no_symbols") is True and not p.get("instances"):
                        negatives[p_idx] = True
            elif isinstance(raw, dict) and isinstance(raw.get("instances"), list):
                incoming[page_index] = [_as_pending_import(x) for x in raw["instances"] if isinstance(x, dict)]
            else:
                raise LabelingError(f"unrecognized annotation or proposal format in {target.name}")

            plans = []
            imported_count = 0
            for p_idx in sorted(set(incoming) | set(negatives)):
                baseline, revision, _ = self._load(p_idx)
                instances = list(baseline.get("instances", []))
                taken = {inst["instance_id"] for inst in instances}
                taken |= {inst.get("instance_id") for inst in baseline.get("rejected_proposals", [])}
                added = 0
                for inst in incoming.get(p_idx, []):
                    inst_id = inst.get("instance_id")
                    if not isinstance(inst_id, str) or not ID_PATTERN.fullmatch(inst_id) or inst_id in taken:
                        n = len(instances) + 1
                        while f"imported_p{p_idx + 1}_{n}" in taken:
                            n += 1
                        inst = dict(inst, instance_id=f"imported_p{p_idx + 1}_{n}")
                    instances.append(inst)
                    taken.add(inst["instance_id"])
                    added += 1
                if not added and not negatives.get(p_idx):
                    continue
                metadata = {
                    "verified_no_symbols": not instances and bool(
                        negatives.get(p_idx) or baseline.get("verified_no_symbols") is True),
                    # A page with new pending boxes is no longer fully labeled.
                    "page_complete": (baseline.get("page_complete") is True and
                                      all(x.get("verified_ground_truth") is True for x in instances)),
                }
                instances = [_normalize_instance_for_save(x) for x in instances]
                rejected = baseline.get("rejected_proposals", [])
                self.validate_review_payload(p_idx, revision=revision, instances=instances,
                                             rejected=rejected, metadata=metadata)
                plans.append((p_idx, revision, instances, rejected, metadata))
                imported_count += added

            for p_idx, revision, instances, rejected, metadata in plans:
                self.save(p_idx, revision=revision, instances=instances,
                          rejected=rejected, metadata=metadata)

            return {
                "success": True,
                "imported_count": imported_count,
                "filename": target.name,
                "pages_imported": len(plans),
                "page_state": self.page_state(page_index),
            }

    def image_payload(self, index: int, *, cached_only: bool = False) -> tuple[bytes, str, str] | None:
        """Return ``(bytes, mime, image_sha256)`` or ``None`` when ``cached_only`` and not yet rendered."""
        if self.edit:
            with self.lock:
                _, _, path = self._load(index)
            data = path.read_bytes()
            return data, _image_mime(path), hashlib.sha256(data).hexdigest()
        self._page_index(index)
        self._assert_pdf_unchanged()
        if cached_only:
            with self._render_lock:
                cached = self._render_cache.get(index)
            if cached is None and _find_rendered(self.out_dir / "images", index, self.dpi, self.source_hash) is None:
                return None
        path, _, _, image_hash = self._raster(index)
        data, _, _ = _read_image_bytes(path)
        return data, _image_mime(path), image_hash

    def image_bytes(self, index: int) -> bytes:
        data, _, _ = self.image_payload(index)
        return data

    def image_response(self, index: int) -> tuple[bytes, str]:
        data, mime, _ = self.image_payload(index)
        return data, mime

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
            complete = (metadata.get("page_complete", baseline.get("page_complete", False))
                        if metadata is not None else baseline.get("page_complete", False))
            if type(complete) is not bool:
                raise LabelingError("metadata.page_complete must be boolean")
            for item in instances:
                if item.get("verified_ground_truth") is True and incomplete_reason(item):
                    raise LabelingError(f"box {item.get('instance_id')} is verified but "
                                        f"{incomplete_reason(item)}; pick the exact device first")
                if complete and not negative and item.get("verified_ground_truth") is not True:
                    raise LabelingError("a page marked fully labeled cannot have pending boxes; "
                                        "verify or remove them, or clear 'every symbol labeled'")
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
        # Every writer (annotator, agents, model pre-labeling, imports) saves
        # catalog class names only (label_schema.canonical_device).
        if isinstance(instances, list):
            instances = [canonical_instance(i) if isinstance(i, dict) else i for i in instances]
        if isinstance(rejected, list):
            rejected = [canonical_instance(i) if isinstance(i, dict) else i for i in rejected]
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

            if metadata is not None and "vlm_assessment" in metadata:
                baseline["vlm_assessment"] = agent_api.validate_assessment(metadata["vlm_assessment"])
            if metadata is not None and "verified_no_symbols" in metadata:
                baseline["verified_no_symbols"] = metadata["verified_no_symbols"]
            if metadata is not None and "page_complete" in metadata:
                baseline["page_complete"] = metadata["page_complete"]
            baseline["instances"] = [_normalize_instance_for_save(item) for item in instances]
            baseline["rejected_proposals"] = cleaned_rejected
            if self.pdf:
                # Older saves lacked a verified project group, which the
                # training split refuses; derive it from the PDF every time.
                baseline.update(group_fields(self.pdf))
            baseline.update(completeness(
                verified_no_symbols=baseline.get("verified_no_symbols", False) is True,
                page_complete=baseline.get("page_complete") is True))
            if self.edit:
                encoded = (json.dumps(baseline, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
                _atomic_bytes(self._json_path(index), encoded)
                return hashlib.sha256(encoded).hexdigest()
            from annotator import document_labels
            baseline["proposals_sha256"] = self.proposals_sha
            try:
                return document_labels.write_page(
                    self.out_dir, self.json_prefix, index + 1, baseline,
                    expected_revision=self._stored_revisions.get(index))
            except document_labels.StaleRevision as exc:
                raise RevisionConflict("page changed on disk; reload before saving") from exc


def _page_counts(data: dict) -> dict:
    items = [i for i in data.get("instances") or [] if isinstance(i, dict)]
    return {
        "saved": True,
        "pending": sum(1 for i in items if i.get("review_status") != "accepted"),
        "verified": sum(1 for i in items if i.get("verified_ground_truth") is True),
        "complete": data.get("page_complete") is True,
        "no_symbols": data.get("verified_no_symbols") is True,
    }


def _review_rank(item: dict) -> int:
    """How settled a box is: verified > accepted > drawn or edited by a person >
    an agent's or model's box on a labeled page > raw machine proposal."""
    if item.get("verified_ground_truth") is True:
        return 4
    if item.get("review_status") == "accepted":
        return 3
    if item.get("review_source") in ("human", "manual_draft"):
        return 2
    return 0 if _machine_pending(item) else 1


def _scaled(item: dict, sx: float, sy: float) -> dict:
    out = dict(item)
    if sx == 1.0 and sy == 1.0:
        return out
    box = item.get("bbox_xyxy")
    if isinstance(box, list) and len(box) == 4:
        out["bbox_xyxy"] = [round(float(box[0]) * sx, 3), round(float(box[1]) * sy, 3),
                            round(float(box[2]) * sx, 3), round(float(box[3]) * sy, 3)]
    corners = item.get("obb_corners") or item.get("oriented_corners")
    if isinstance(corners, list):
        key = "obb_corners" if "obb_corners" in item else "oriented_corners"
        try:
            out[key] = [[round(float(x) * sx, 3), round(float(y) * sy, 3)] for x, y in corners]
        except (TypeError, ValueError):
            out.pop(key, None)
    return out


def _as_pending_import(item: dict) -> dict:
    """An imported box from another source: shown for review, never trusted."""
    return dict(item, uncertain=True, review_status="unreviewed", review_source="import",
                verified_ground_truth=False)


def _etag_matches(header_value: str | None, etag: str) -> bool:
    if not header_value:
        return False
    raw = header_value.strip()
    if raw == "*":
        return True
    target = etag.strip('"')
    for part in raw.split(","):
        token = part.strip()
        if token.startswith("W/"):
            token = token[2:].strip()
        if token.strip('"') == target:
            return True
    return False


def make_handler(session: LabelingSession):
    # The page and its script are read once, so a long-running server always
    # serves the UI that matches its own API, even after the files are edited.
    ui_html = (MODULE_DIR / "web_ui.html").read_bytes()
    ui_js = (MODULE_DIR / "web_ui.js").read_bytes()

    class Handler(BaseHTTPRequestHandler):
        def _send_common_headers(self, extra_headers: dict[str, str] | None = None) -> None:
            extra = extra_headers or {}
            has_cache_ctrl = False
            for name, value in extra.items():
                if name.lower() == "cache-control":
                    has_cache_ctrl = True
                self.send_header(name, value)
            if not has_cache_ctrl:
                self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Cross-Origin-Resource-Policy", "same-origin")
            self.send_header("Cross-Origin-Opener-Policy", "same-origin")
            self.send_header("X-Frame-Options", "DENY")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
            self.send_header(
                "Content-Security-Policy",
                "default-src 'none'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; "
                "script-src 'self'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'"
            )

        def _reply(self, code: int, body: bytes, mime: str,
                   extra_headers: dict[str, str] | None = None) -> None:
            self.send_response(code)
            self.send_header("Content-Type", mime)
            self.send_header("Content-Length", str(len(body)))
            self._send_common_headers(extra_headers)
            self.end_headers()
            self.wfile.write(body)

        def _reply_not_modified(self, extra_headers: dict[str, str] | None = None) -> None:
            self.send_response(304)
            self._send_common_headers(extra_headers)
            self.end_headers()

        def _json(self, code: int, data: dict, extra_headers: dict[str, str] | None = None) -> None:
            self._reply(code, json.dumps(data, ensure_ascii=False).encode("utf-8"),
                        "application/json; charset=utf-8", extra_headers)

        def _doc_page(self, query: str) -> tuple[LabelingSession, int]:
            """The session of the PDF named by ``?doc=`` and the ``?page=`` index."""
            q = parse_qs(query, keep_blank_values=True)
            if set(q) - {"page", "doc"} or any(len(v) > 1 for v in q.values()):
                raise LabelingError("unsupported page query")
            target = session.for_document(q.get("doc", [""])[0] or None)
            raw = q.get("page", [str(target.start_page)])[0]
            try:
                return target, target._page_index(int(raw))
            except (ValueError, TypeError):
                raise LabelingError("invalid page number") from None

        def _doc_page_image(self, query: str) -> tuple[LabelingSession, int, str | None, bool]:
            """Parse ``/api/image`` query allowing optional cache token ``v`` and ``cached`` flag."""
            q = parse_qs(query, keep_blank_values=True)
            if set(q) - {"page", "doc", "v", "cached"} or any(len(v) > 1 for v in q.values()):
                raise LabelingError("unsupported page query")
            target = session.for_document(q.get("doc", [""])[0] or None)
            raw = q.get("page", [str(target.start_page)])[0]
            try:
                page_idx = target._page_index(int(raw))
            except (ValueError, TypeError):
                raise LabelingError("invalid page number") from None
            v_param = (q.get("v", [""])[0] or "").strip() or None
            raw_cached = (q.get("cached", [""])[0] or "").strip().lower()
            if raw_cached in ("", "0", "false"):
                cached_only = False
            elif raw_cached in ("1", "true"):
                cached_only = True
            else:
                raise LabelingError("invalid cached parameter")
            return target, page_idx, v_param, cached_only

        def _trusted_host(self) -> bool:
            host_header = self.headers.get("Host", "").lower()
            port = self.server.server_port
            if ":" in host_header:
                host_name, h_port = host_header.split(":", 1)
                if h_port != str(port):
                    return False
            else:
                host_name = host_header

            return _is_lan_host(host_name)

        def do_GET(self) -> None:
            if not self._trusted_host():
                self._json(403, {"error": "local host header required"})
                return
            url = urlsplit(self.path)
            try:
                if url.path == "/":
                    self._reply(200, ui_html, "text/html; charset=utf-8")
                elif url.path == "/web_ui.js":
                    self._reply(200, ui_js, "text/javascript; charset=utf-8")
                elif url.path == "/api/session":
                    self._json(200, {
                        "token": session.token,
                        "initial_page": session.start_page,
                        "mode": "edit" if session.edit else "pdf",
                        "current_doc_name": session.pdf.name if session.pdf else (session.edit.name if session.edit else ""),
                        "current_doc_path": str(session.pdf.resolve()) if session.pdf else (str(session.edit.resolve()) if session.edit else ""),
                        "device_catalog": session.device_catalog,
                        "detector": session.detector_info(),
                    })
                elif url.path == "/api/device_catalog":
                    self._json(200, session.device_catalog)
                elif url.path == "/api/page":
                    target, page_idx = self._doc_page(url.query)
                    self._json(200, target.page_state(page_idx))
                elif url.path == "/api/image":
                    target, page_idx, v_param, cached_only = self._doc_page_image(url.query)
                    payload = target.image_payload(page_idx, cached_only=cached_only)
                    if payload is None:
                        self._json(202, {"ready": False})
                        return
                    data, mime, image_hash = payload
                    etag = f'"{image_hash}"'
                    cache_ctrl = (
                        "private, max-age=3600, immutable"
                        if v_param and v_param == image_hash[:16]
                        else "private, no-cache"
                    )
                    headers = {
                        "ETag": etag,
                        "X-Image-Sha256": image_hash,
                        "Cache-Control": cache_ctrl,
                    }
                    if _etag_matches(self.headers.get("If-None-Match"), etag):
                        self._reply_not_modified(headers)
                    else:
                        self._reply(200, data, mime, headers)
                elif url.path == "/api/overview":
                    q = parse_qs(url.query, keep_blank_values=True)
                    if set(q) - {"doc"} or any(len(v) > 1 for v in q.values()):
                        raise LabelingError("unsupported overview query")
                    target = session.for_document(q.get("doc", [""])[0] or None)
                    self._json(200, target.page_overview())
                elif url.path == "/api/files":
                    self._json(200, {"files": session.list_sibling_jsons()})
                elif url.path == "/api/documents":
                    self._json(200, {"documents": session.list_documents()})
                elif url.path == "/api/server_sources":
                    target, page_idx = self._doc_page(url.query)
                    self._json(200, {"sources": target.list_server_sources(page_idx)})
                elif url.path == "/api/agent/tools":
                    self._json(200, agent_api.tool_manifest(session))
                elif url.path == "/api/agent/context":
                    page_idx, kwargs = agent_api.parse_context_query(session, url.query)
                    self._json(200, agent_api.page_context(session, page_idx, **kwargs))
                elif url.path == "/api/agent/view":
                    page_idx, kwargs = agent_api.parse_view_query(session, url.query)
                    data, mime = agent_api.render_view(session, page_idx, **kwargs)
                    self._reply(200, data, mime)
                else:
                    self._json(404, {"error": "unknown endpoint"})
            except (LabelingError, ValueError, FileNotFoundError) as exc:
                self._json(409, {"error": str(exc)})

        def do_POST(self) -> None:
            if not self._trusted_host() or not hmac.compare_digest(
                    self.headers.get("X-Labeler-Token", "").encode(), session.token.encode()):
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
                "/api/save", "/api/validate", "/api/open", "/api/snap", "/api/evaluate_page",
                "/api/document", "/api/server_export", "/api/server_import",
                "/api/agent/act", "/api/prefetch", "/api/training_ready",
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
                if url.path == "/api/agent/act":
                    page_idx = agent_api.page_from_query(session, url.query)[0]
                    dry_run = data.get("dry_run", False)
                    if type(dry_run) is not bool:
                        raise LabelingError("dry_run must be boolean")
                    result = agent_api.apply_actions(
                        session, page_idx,
                        revision=data.get("revision"),
                        actions=data.get("actions"),
                        dry_run=dry_run,
                    )
                    self._json(200, result)
                    return
                if url.path == "/api/training_ready":
                    q = parse_qs(url.query, keep_blank_values=True)
                    if set(q) - {"doc"} or any(len(v) > 1 for v in q.values()):
                        raise LabelingError("unsupported query")
                    target = session.for_document(q.get("doc", [""])[0] or None)
                    self._json(200, target.set_training_ready(data.get("ready")))
                    return
                if url.path == "/api/prefetch":
                    self._json(200, session.prefetch(data.get("path"), data.get("pages", [0])))
                    return
                if url.path == "/api/evaluate_page":
                    target, page_idx = self._doc_page(url.query)
                    result = target.evaluate_page(
                        page_idx, confidence=data.get("confidence", 0.25),
                        confidence_source=data.get("confidence_source", "request"),
                        sheet_gate=data.get("sheet_gate"),
                    )
                    self._json(200, result)
                    return
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
                    target, page_idx = self._doc_page(url.query)
                    result = target.import_server(page_idx, str(src_path))
                    self._json(200, result)
                    return
                if url.path == "/api/open":
                    filename = str(data.get("filename", ""))
                    page_idx = session.open_existing_json(filename)
                    self._json(200, {"page_index": page_idx, "page_count": session.page_count})
                    return
                if url.path == "/api/snap":
                    target, page_idx = self._doc_page(url.query)
                    inst = data.get("instance")
                    if not isinstance(inst, dict):
                        raise LabelingError("instance object required")
                    snap = target.resolve_snap_preview(page_idx, inst)
                    self._json(200, {"snap": snap})
                    return
                target, page_idx = self._doc_page(url.query)
                if url.path == "/api/validate":
                    target.validate_review_payload(
                        page_idx, revision=data.get("revision"),
                        instances=data.get("instances"),
                        rejected=data.get("rejected_proposals"),
                        metadata=data.get("metadata"),
                    )
                    self._json(200, {"valid": True})
                    return
                new_revision = target.save(
                    page_idx,
                    revision=data.get("revision"),
                    instances=data.get("instances"),
                    rejected=data.get("rejected_proposals"),
                    metadata=data.get("metadata"),
                )
                saved_state = target.page_state(page_idx)
                self._json(200, {
                    "revision": new_revision,
                    "saved": True,
                    "document": saved_state["document"],
                })
            except RevisionConflict as exc:
                self._json(409, {"error": str(exc)})
            except FileNotFoundError as exc:
                self._json(404, {"error": str(exc)})
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
    parser.add_argument("--out-dir", type=Path,
                        help="Put every PDF's pages in this one folder instead of the label store")
    parser.add_argument("--store", type=Path,
                        help="Flat label store to use instead of the devices folder beside each PDF "
                             "(same as $BPS_LABELS_DIR)")
    parser.add_argument("--proposals", type=Path)
    parser.add_argument("--page-presence", type=Path,
                        help="Read-only page candidates from scripts_triage_pdf_symbol_pages.py")
    parser.add_argument("--walls", type=Path, help="Optional JSON file with verified wall centerlines for snap guidance")
    parser.add_argument("--neca-catalog", type=Path, help="Optional NECA symbol library directory to populate live subtypes")
    parser.add_argument("--detector-weights", type=Path,
                        help="Optional trained blueprint symbol detector checkpoint (.onnx or .pt) for Evaluate page")
    parser.add_argument("--sheet-gate", choices=("off", "raise", "skip"), default="off",
                        help="Evaluate page on sheets whose title block reads non-electrical "
                             "(L, C, S, A without RCP, G, M, P...): off (default; every sheet is "
                             "detected normally), skip the page, or raise the cutoff to "
                             "--sheet-gate-confidence. Pages of "
                             "unknown discipline are never gated.")
    parser.add_argument("--sheet-gate-confidence", type=float, default=0.90,
                        help="Cutoff used on non-electrical sheets with --sheet-gate raise (default 0.90)")
    parser.add_argument("--page", type=int, default=0, help="Initial 0-based page/file index")
    parser.add_argument("--dpi", type=int, default=150)
    parser.add_argument("--host", type=str, default="0.0.0.0", help="Listen host (default 0.0.0.0 for LAN access)")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--open-browser", action="store_true")
    args = parser.parse_args()
    if args.store:
        os.environ["BPS_LABELS_DIR"] = str(args.store.expanduser().resolve())

    chosen_pdf = args.pdf
    chosen_edit = args.edit
    if chosen_pdf is None and chosen_edit is None:
        bp_dir = Path(args.blueprints_dir).resolve()
        if bp_dir.is_dir():
            pdfs = openable_pdfs(bp_dir)
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
        detector_weights=args.detector_weights,
    )
    if not 0.0 < args.sheet_gate_confidence < 1.0:
        raise SystemExit("--sheet-gate-confidence must be between 0 and 1")
    session.sheet_gate_mode = args.sheet_gate
    session.sheet_gate_confidence = args.sheet_gate_confidence
    lan_ip = get_lan_ip()
    with ThreadingHTTPServer((args.host, args.port), make_handler(session)) as server:
        port = server.server_port
        print(f"Manual labeler running!")
        print(f"  Local URL:   http://127.0.0.1:{port}/")
        print(f"  Network URL: http://{lan_ip}:{port}/")
        print(f"Labels: {session.out_dir}")
        if args.open_browser:
            webbrowser.open(f"http://127.0.0.1:{port}/")
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
