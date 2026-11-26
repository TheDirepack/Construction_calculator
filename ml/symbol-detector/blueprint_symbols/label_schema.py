"""The one label contract shared by every labeling path and by training.

The human annotator (``annotator.web``), the vision-model agents
(``annotator.agent_cli`` / ``agent_api``), model pre-labeling
(``scripts_model_prelabel``), the ``*DEVICES.json`` importer and the
training readers (``dataset_manifest``, ``raster_training``,
``scripts_export_yolo_tiles``) all take these definitions from here:

* **Training classes are devices.** A box is saved with the exact catalog
  device (``symbol_family`` / ``symbol_variant``, e.g. ``switch`` /
  ``three_way``) and the detector learns that device: class ``switch.three_way``.
  The classes are the devices of ``annotator/electrical_device_catalog.json``;
  categories play no part. ``training_view`` / ``load_training_page`` is how
  training reads a saved page.
* **Project groups.** ``source_group`` gives the train/val/test split group of a
  source file (``class_maps/page_roles.json`` rules, else one group per file).
* **Page completeness.** ``completeness`` writes the attestation fields a page
  needs for training: positives only, exhaustively labeled, or verified empty.
* **Pending boxes.** ``pending_instance`` is the shape of every machine
  proposal (DEVICES.json, SAM, model, vision agent): unverified until a human
  verifies it.
"""
from __future__ import annotations

import copy
import json
import re
from pathlib import Path

CATALOG_PATH = Path(__file__).resolve().parent.parent / "annotator" / "electrical_device_catalog.json"
UNMAPPED = "__unmapped__"     # an excluded name: training skips tiles touching such a box

# Everything below is read from the catalog, the single source of truth for
# what each symbol is (devices, their aliases in other sources, and names
# that are not devices). Edit the catalog, not these tables.
_CATALOG = json.loads(CATALOG_PATH.read_text(encoding="utf-8"))
_NAMING = _CATALOG.get("naming", {})

CATALOG_DEVICES: tuple[tuple[str, str], ...] = tuple(
    (d["symbol_family"], d["symbol_variant"]) for d in _CATALOG["devices"])
_CATALOG_SET = frozenset(CATALOG_DEVICES)
CATALOG_FAMILIES = frozenset(f for f, _ in CATALOG_DEVICES)

# Families a page attests when "every symbol on this page is labeled".
EXHAUSTIVE_FAMILIES = sorted(CATALOG_FAMILIES)

_NOT_A_DEVICE = frozenset(_NAMING.get("not_devices", ()))

# Former coarse detector classes -> catalog device a proposal starts as.
CLASS_DEVICE: dict[str, tuple[str, str]] = {
    name: (family, "unspecified") for name, family in _NAMING.get("legacy_detector_classes", {}).items()}

# NECA 100-2013 glyph (map_neca_to_v7 device_type, subtype) -> catalog device.
# Glyphs naming no specific device are not composited for detector training.
NECA_DEVICE: dict[tuple[str, str], tuple[str, str]] = {}
# ``device_type`` strings of extracted *DEVICES.json files -> catalog device
# (None: not an electrical device, never proposed).
RAW_DEVICE_TYPES: dict[str, tuple[str, str] | None] = {}
for _d in _CATALOG["devices"]:
    _dev = (_d["symbol_family"], _d["symbol_variant"])
    for _alias in (_d.get("aliases") or {}).get("neca_v7", []):
        NECA_DEVICE[tuple(_alias.split("/", 1))] = _dev
    for _alias in (_d.get("aliases") or {}).get("devices_json", []):
        RAW_DEVICE_TYPES[_alias] = _dev
for _raw, _family in _NAMING.get("family_aliases", {}).items():
    RAW_DEVICE_TYPES.setdefault(_raw, (_family, "unspecified"))
for _raw in _NAMING.get("non_electrical_types", []):
    RAW_DEVICE_TYPES[_raw] = None


def _parse_device(name: str) -> tuple[str, str]:
    family, _, variant = name.partition(".")
    return family, variant or "unspecified"


_RAW_KEYWORDS = tuple((tuple(rule["words"]), _parse_device(rule["device"]))
                      for rule in _NAMING.get("keyword_fallback", []))

COORDINATE_SYSTEM = "original_page_pixels_top_left_y_down"
PAGE_ROLES = Path(__file__).resolve().parent.parent / "class_maps" / "page_roles.json"


def device_class(family: str | None, variant: str | None) -> str | None:
    """The training class of a catalog device (``switch.three_way``), else None."""
    if (family, variant) in _CATALOG_SET:
        return f"{family}.{variant}"
    return None


def device_for_class(name: str) -> tuple[str, str] | None:
    """Catalog device of a detector class name (``switch.three_way``, or a
    former coarse class such as ``receptacle``)."""
    family, _, variant = str(name).partition(".")
    if variant and (family, variant) in _CATALOG_SET:
        return family, variant
    return CLASS_DEVICE.get(name)


def instance_class(inst: dict) -> str | None:
    """Training class of a saved box: its exact catalog device.

    A catalog family with a variant outside the catalog (``unspecified``, a
    typo) or an older non-device name becomes ``__unmapped__``, which training
    treats as an ignore region. Non-electrical datasets (CAD floor plans:
    ``door``, ``toilet``) keep their own class names.
    """
    family = inst.get("symbol_family")
    if not family:
        return None
    if "." in family and family.split(".", 1)[0] in CATALOG_FAMILIES:
        # already a device id (CAD/SVG/COCO class maps may name devices directly)
        return family if _parse_device(family) in _CATALOG_SET else UNMAPPED
    if family in CATALOG_FAMILIES:
        return device_class(family, inst.get("symbol_variant")) or UNMAPPED
    if family in _NOT_A_DEVICE:
        return UNMAPPED
    return family


def device_for_raw_type(raw: str | None) -> tuple[str, str] | None:
    """Catalog (family, variant) of a DEVICES.json ``device_type``; None to skip."""
    key = re.sub(r"[^a-z0-9]+", "_", str(raw or "").strip().lower()).strip("_")
    if key in RAW_DEVICE_TYPES:
        return RAW_DEVICE_TYPES[key]
    for words, device in _RAW_KEYWORDS:
        if any(w in key for w in words):
            return device
    return ("unknown_symbol", "unspecified")


_LEGEND_SYNONYMS = tuple((re.compile(pattern), repl)
                         for pattern, repl in _NAMING.get("legend_synonyms", ()))
# (device, [required word alternatives, ...], excluded words) per catalog legend phrase
_LEGEND_PHRASES = tuple(
    ((d["symbol_family"], d["symbol_variant"]),
     [frozenset(w.split("|")) for w in phrase.split() if not w.startswith("-")],
     frozenset(w[1:] for w in phrase.split() if w.startswith("-")))
    for d in _CATALOG["devices"] for phrase in (d.get("aliases") or {}).get("legend", ()))


def legend_words(text: str) -> set[str]:
    """Words of a legend description after the catalog's synonym rewrites."""
    norm = " " + re.sub(r"[^a-z0-9]+", " ", str(text).lower()).strip() + " "
    for pattern, repl in _LEGEND_SYNONYMS:
        norm = pattern.sub(repl, norm)
    return set(norm.split())


def device_for_legend_text(text: str) -> tuple[str, str] | None:
    """Catalog device a legend description names (``aliases.legend``), or
    None when no phrase matches or the best match is a tie between devices.
    A match scores the number of description words its phrase covers, so
    "GFCI duplex receptacle" picks the GFCI duplex over the plain duplex."""
    words = legend_words(text)
    best: dict[tuple[str, str], int] = {}
    for device, phrase, excluded in _LEGEND_PHRASES:
        if all(alts & words for alts in phrase) and not excluded & words:
            covered = set().union(*(alts & words for alts in phrase))
            best[device] = max(best.get(device, 0), len(covered))
    if not best:
        return None
    top = max(best.values())
    winners = [d for d, n in best.items() if n == top]
    return winners[0] if len(winners) == 1 else None


def exhaustive_classes(names: list[str] | set[str]) -> set[str]:
    """Training classes covered by a page's ``exhaustive_families``: a listed
    catalog family covers every device of that family; other names (device
    classes, CAD classes) stand for themselves."""
    out: set[str] = set()
    for name in names:
        if name in CATALOG_FAMILIES:
            out.update(f"{name}.{v}" for f, v in CATALOG_DEVICES if f == name)
        else:
            out.add(name)
    return out


def training_view(data: dict) -> dict:
    """A saved page as training sees it: ``symbol_family`` becomes the device
    class (``switch.three_way``), the saved fields stay in ``device_family`` /
    ``device_variant``, and ``exhaustive_families`` becomes the device classes
    the page is exhaustive for. Idempotent; the input is not modified."""
    if data.get("label_view") == "training":
        return data
    out = copy.deepcopy(data)
    for key in ("instances", "rejected_proposals"):
        for inst in out.get(key) or []:
            if not isinstance(inst, dict):
                continue
            cls = instance_class(inst)
            if cls is not None and cls != inst.get("symbol_family"):
                inst.setdefault("device_family", inst.get("symbol_family"))
                inst.setdefault("device_variant", inst.get("symbol_variant"))
                inst["symbol_family"] = cls
    exhaustive = out.get("exhaustive_families")
    if isinstance(exhaustive, list) and all(isinstance(x, str) for x in exhaustive):
        out["exhaustive_families"] = sorted(exhaustive_classes(exhaustive))
    out["label_view"] = "training"
    return out


# One labels file per PDF (annotator.document_labels) holds every page; training
# names a page in it as "<folder>/<stem>_labels.json#p12".
LABELS_SUFFIX = "_labels.json"
_PAGE_REF = re.compile(r"^(?P<file>.+_labels\.json)#p(?P<page>\d+)$")
_labels_cache: dict[str, tuple[int, int, dict]] = {}


def page_ref(labels_file: str | Path, page: int) -> Path:
    """The reference to 1-based ``page`` of a labels file."""
    return Path(f"{Path(labels_file)}#p{int(page)}")


def parse_page_ref(path: str | Path) -> tuple[Path, int] | None:
    match = _PAGE_REF.match(str(path))
    return (Path(match["file"]), int(match["page"])) if match else None


def _labels_file(path: Path) -> dict:
    stat = path.stat()
    known = _labels_cache.get(str(path))
    if known and known[:2] == (stat.st_size, stat.st_mtime_ns):
        return known[2]
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict) or not isinstance(data.get("pages"), dict):
        raise ValueError(f"{path} is not a labels file")
    _labels_cache[str(path)] = (stat.st_size, stat.st_mtime_ns, data)
    return data


def read_page(path: str | Path) -> dict:
    """A saved page: from a page JSON, or from a PDF's labels file by
    reference (``X_labels.json#p12``, with ``image_path`` made absolute so
    ``Path(ref).parent / image_path`` still finds the image)."""
    ref = parse_page_ref(path)
    if ref is None:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    labels, page = ref
    entry = _labels_file(labels)["pages"].get(str(page))
    if entry is None:
        raise FileNotFoundError(f"page {page} is not saved in {labels}")
    entry = json.loads(json.dumps(entry))
    if entry.get("image_path"):
        entry["image_path"] = str((labels.parent / entry["image_path"]).resolve())
    return entry


def annotation_sha256(path: str | Path) -> str:
    """Content hash of one annotation page: the file's bytes, or for a page of
    a labels file, that page's own entry (other pages do not change it)."""
    import hashlib
    ref = parse_page_ref(path)
    if ref is None:
        return hashlib.sha256(Path(path).read_bytes()).hexdigest()
    entry = _labels_file(ref[0])["pages"].get(str(ref[1]))
    if entry is None:
        raise FileNotFoundError(f"page {ref[1]} is not saved in {ref[0]}")
    canonical = json.dumps(entry, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def expand_page_paths(paths) -> list[Path]:
    """Page JSONs and page references, with every labels file expanded into
    references to each of its saved pages."""
    out: list[Path] = []
    for path in paths:
        path = Path(path)
        if parse_page_ref(path) is None and path.name.endswith(LABELS_SUFFIX) and path.is_file():
            out += [page_ref(path, int(k)) for k in sorted(_labels_file(path)["pages"], key=int)]
        else:
            out.append(path)
    return out


def load_training_page(path: str | Path) -> dict:
    return training_view(read_page(path))


def training_usable(data: dict) -> bool:
    """Whether a saved page gives training anything: a verified box, or an
    attestation (verified empty / every symbol labeled). Pages holding only
    proposals teach nothing and are left out."""
    from .dataset_manifest import trusted_training_instance
    if data.get("verified_no_symbols") is True or data.get("annotation_completeness_verified") is True:
        return True
    return any(isinstance(i, dict) and trusted_training_instance(i) for i in data.get("instances") or [])


def page_fingerprint(image) -> str:
    """Content hash of a page image that survives re-rendering.

    Standard sheets (DOT details, general notes) recur across drawing sets and
    render at different sizes. The fingerprint is a difference hash of a
    64x64 grayscale thumbnail (ink edges, not bytes), so the same sheet from
    two PDFs or DPIs matches while different sheets do not.
    """
    import cv2
    import numpy as np
    gray = image if image.ndim == 2 else cv2.cvtColor(image[..., :3], cv2.COLOR_BGR2GRAY)
    small = cv2.resize(gray, (65, 64), interpolation=cv2.INTER_AREA).astype(np.int16)
    bits = (small[:, 1:] > small[:, :-1] + 2).flatten()
    return np.packbits(bits).tobytes().hex()


SAME_PAGE_MAX_BITS = 64     # of 4096: re-renders differ by ~5-20, distinct sheets by 300+


class PageFingerprints:
    """Remembers fingerprints; ``match`` finds an earlier page within
    ``SAME_PAGE_MAX_BITS`` differing bits (the same sheet re-rendered)."""

    def __init__(self) -> None:
        import numpy as np
        self._np = np
        self._bits = np.zeros((0, 4096), dtype=bool)
        self._names: list[str] = []

    def match(self, fingerprint: str) -> str | None:
        np = self._np
        bits = np.unpackbits(np.frombuffer(bytes.fromhex(fingerprint), np.uint8)).astype(bool)
        if len(self._names):
            distance = (self._bits != bits).sum(axis=1)
            best = int(distance.argmin())
            if distance[best] <= SAME_PAGE_MAX_BITS:
                return self._names[best]
        self._bits = np.vstack([self._bits, bits[None]])
        self._names.append("")
        return None

    def name_last(self, name: str) -> None:
        self._names[-1] = name


# --------------------------------------------------------------------------
# page-level fields
# --------------------------------------------------------------------------

def source_group(source: str | Path) -> tuple[str, str]:
    """Project group of a source file for the train/val/test split, and why.

    Every page of one project must land in one split. The group comes from the
    page-role rules that also group sources for pretraining
    (``class_maps/page_roles.json``): rule groups such as ``set:rinker_hall``
    join related files; otherwise each file is its own group.
    """
    source = Path(source).resolve()
    root = next((p for p in source.parents if p.name == "blueprints_and_libraries"), None)
    rel = source.relative_to(root).as_posix() if root else source.name
    try:
        from scripts_build_page_pool import group_for, load_rules, match_rule
        rule = match_rule(rel, load_rules(PAGE_ROLES))
        if rule is not None:
            return group_for(rel, rule), f"page_roles:{rule['glob']}"
    except Exception:                    # rules unavailable (standalone use): per-file group
        pass
    return "file_" + re.sub(r"[^A-Za-z0-9]+", "_", str(Path(rel).with_suffix("")))[-120:], "per_file_default"


def group_fields(source: str | Path) -> dict:
    group, rule = source_group(source)
    return {"project_family_id": group, "source_group_verified": True, "source_group_rule": rule}


def completeness(*, verified_no_symbols: bool = False, page_complete: bool = False) -> dict:
    """Training attestation fields of a page.

    * verified empty: no symbols at all, a negative for every class;
    * complete: every symbol labeled, so the page can serve validation/test;
    * otherwise positives only: unlabeled symbols are unknown, never negatives.
    """
    complete = bool(page_complete) and not verified_no_symbols
    return {
        "verified_no_symbols": bool(verified_no_symbols),
        "exhaustive_families": list(EXHAUSTIVE_FAMILIES) if complete else [],
        "annotation_completeness": "complete" if (verified_no_symbols or complete) else "partial",
        "annotation_completeness_verified": bool(verified_no_symbols or complete),
        "annotation_scope": ("verified_no_symbols" if verified_no_symbols else
                             "exhaustive_electrical" if complete else "positive_only"),
    }


def page_header(*, source: str | Path, document_id: str, page_index: int, source_type: str,
                image_path: str, image_sha256: str, width: int, height: int,
                source_sha256: str | None = None) -> dict:
    """Top-level fields of a new page JSON, the same for every labeling path."""
    source = Path(source)
    header = {
        "format_version": 1,
        "source_id": source.stem,
        "source_license": "user-owned",
        **group_fields(source),
        "document_id": document_id,
        "page_index": int(page_index),
        "source_type": source_type,
    }
    if source_sha256:
        header["source_pdf_sha256"] = source_sha256
    header.update({
        "image_path": image_path,
        "image_sha256": image_sha256,
        "image_width": int(width),
        "image_height": int(height),
        "coordinate_system": COORDINATE_SYSTEM,
        **completeness(),
        "instances": [],
        "rejected_proposals": [],
    })
    return header


# --------------------------------------------------------------------------
# boxes
# --------------------------------------------------------------------------

def incomplete_reason(item: dict) -> str | None:
    """Why a box cannot be verified yet, or None when it names a specific device."""
    family = str(item.get("symbol_family") or "")
    variant = str(item.get("symbol_variant") or "")
    if not family or family in ("unknown_symbol", "unmapped"):
        return "no device chosen"
    if not variant or variant == "unspecified":
        return "device type not chosen (variant is unspecified)"
    return None


def pending_instance(instance_id: str, family: str, variant: str, box, *, review_source: str,
                     **extra) -> dict:
    """A machine proposal: shown for review, never trained on until verified."""
    item = {
        "instance_id": instance_id,
        "symbol_family": family or "unknown_symbol",
        "symbol_variant": variant or "unspecified",
        "bbox_xyxy": [round(float(v), 3) for v in box],
        "attributes_from_text": [],
        "rotation_degrees": 0.0,
        "is_truncated": False,
        "is_occluded": False,
        "uncertain": True,
        "review_status": "unreviewed",
        "review_source": review_source,
        "verified_ground_truth": False,
    }
    item.update(extra)
    return item


def iou(a, b) -> float:
    x0, y0 = max(a[0], b[0]), max(a[1], b[1])
    x1, y1 = min(a[2], b[2]), min(a[3], b[3])
    inter = max(0.0, x1 - x0) * max(0.0, y1 - y0)
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / union if union > 0 else 0.0


# --------------------------------------------------------------------------
# *DEVICES.json extractions
# --------------------------------------------------------------------------

def devices_bbox_points(dev: dict) -> list[float] | None:
    """[x0, y0, x1, y1] in bottom-left PDF points from any DEVICES.json schema."""
    import math
    for key in ("symbol_bbox", "symbol_bbox_pts", "symbol_bbox_bl", "symbol_graphic_bbox",
                "bounding_box", "bbox_pts", "bbox"):
        val = dev.get(key)
        try:
            if isinstance(val, list) and len(val) == 4:
                x0, y0, x1, y1 = (float(v) for v in val)
            elif isinstance(val, dict) and all(k in val for k in ("x1", "y1", "x2", "y2")):
                x0, y0, x1, y1 = float(val["x1"]), float(val["y1"]), float(val["x2"]), float(val["y2"])
            else:
                continue
        except (TypeError, ValueError):
            continue
        if all(map(math.isfinite, (x0, y0, x1, y1))):
            return [min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1)]
    return None


def devices_skip_reason(dev: dict) -> str | None:
    """Why an extracted device is not a usable box proposal (None = usable)."""
    if dev.get("likely_title_block_artifact"):
        return "title_block"
    if dev.get("placement") == "text_label_proxy" or (
            dev.get("confidence") == "low" and dev.get("symbol_bbox_pts") is None
            and dev.get("symbol_bbox") is None):
        return "text_proxy"
    return None


def points_to_pixels(box_pts, page, width: int, height: int, *, already_rotated: bool = True):
    """Bottom-left PDF-point box -> top-left raster pixels of a rendered page.

    ``page`` is a PyMuPDF page. Pre-v3 extractors wrote unrotated mediabox
    coordinates for rotated pages (``already_rotated=False``).
    """
    x0, y0, x1, y1 = box_pts
    pw, ph = float(page.rect.width), float(page.rect.height)
    sx, sy = width / pw, height / ph
    if page.rotation and not already_rotated:
        import fitz  # type: ignore
        r = fitz.Rect(x0, y0, x1, y1) * page.rotation_matrix
        px0, px1 = sorted((r.x0 * sx, r.x1 * sx))
        py0, py1 = sorted((r.y0 * sy, r.y1 * sy))
    else:
        px0, px1 = x0 * sx, x1 * sx
        py0, py1 = (ph - y1) * sy, (ph - y0) * sy
    clip = lambda v, hi: float(min(max(v, 0.0), hi))
    return clip(px0, width), clip(py0, height), clip(px1, width), clip(py1, height)


def devices_rotated_coordinates(devices_json: dict) -> bool:
    coord = devices_json.get("coordinate_system")
    return isinstance(coord, dict) and "rotation" in str(coord.get("reference", "")).lower()
