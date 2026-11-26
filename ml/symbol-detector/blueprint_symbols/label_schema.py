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
UNMAPPED = "__unmapped__"     # an excluded name: the tile exporter paints such a box out (ignore region)

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

# Former class ids -> the class they were merged into (old labels keep their meaning).
RENAMED: dict[tuple[str, str], tuple[str, str]] = {
    tuple(old.split(".", 1)): tuple(new.split(".", 1)) for old, new in _NAMING.get("renamed", {}).items()}
# Per family, subtypes that name a device by default ("" = no subtype at all).
_SUBTYPE_ALIASES: dict[str, dict[str, tuple[str, str]]] = {
    family: {sub: (family, variant) for sub, variant in table.items()}
    for family, table in _NAMING.get("subtype_aliases", {}).items()}

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
    if variant and (family, variant) in RENAMED:            # a class merged into another
        return RENAMED[(family, variant)]
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
        # already a device id (CAD/SVG/COCO class maps may name devices directly);
        # a former id merged into another device keeps its meaning (RENAMED)
        device = _parse_device(family)
        device = RENAMED.get(device, device)
        return device_class(*device) or UNMAPPED
    if family in CATALOG_FAMILIES:
        renamed = RENAMED.get((family, inst.get("symbol_variant")))
        return device_class(*(renamed or (family, inst.get("symbol_variant")))) or UNMAPPED
    if family in _NOT_A_DEVICE:
        return UNMAPPED
    return family


_AUGMENT = _CATALOG.get("augmentation", {})
MIRROR_ALLOWED = bool(_AUGMENT.get("mirror", False))
_DEFAULT_ROTATIONS = tuple(sorted({int(r) % 360 for r in _AUGMENT.get("default_rotations", (0,))} | {0}))
_ROTATIONS = {key: tuple(sorted({int(r) % 360 for r in value} | {0}))
              for key, value in (_AUGMENT.get("rotations") or {}).items()}


def allowed_rotations(class_name: str | None) -> tuple[int, ...]:
    """Training tile rotations (degrees CCW, multiples of 90) a device class
    allows, from the catalog's ``augmentation`` section: the device's own
    entry, else its family's, else the default. Always includes 0."""
    name = str(class_name or "")
    if name in _ROTATIONS:
        return _ROTATIONS[name]
    return _ROTATIONS.get(name.partition(".")[0], _DEFAULT_ROTATIONS)


def device_for_raw_type(raw: str | None) -> tuple[str, str] | None:
    """Catalog (family, variant) of a DEVICES.json ``device_type``; None to skip."""
    key = re.sub(r"[^a-z0-9]+", "_", str(raw or "").strip().lower()).strip("_")
    if key in RAW_DEVICE_TYPES:
        return RAW_DEVICE_TYPES[key]
    for words, device in _RAW_KEYWORDS:
        if any(w in key for w in words):
            return device
    return ("unknown_symbol", "unspecified")


def _key(text) -> str:
    return re.sub(r"[^a-z0-9]+", "_", str(text or "").strip().lower()).strip("_")


_WEATHERPROOF = _NAMING.get("weatherproof_suffix", "_weatherproof")


def weatherproof_attribute(attributes) -> bool:
    """Whether a source's attributes (a dict or a list of words) say weatherproof."""
    if isinstance(attributes, dict):
        return any(_key(k) in ("weatherproof", "wp", "weather_resistant") and v not in (False, None, "no")
                   for k, v in attributes.items())
    if isinstance(attributes, (list, tuple)):
        return any(_key(a) in ("weatherproof", "wp", "weather_resistant") for a in attributes)
    return False


def canonical_device(device_type: str | None = None, subtype: str | None = None, *,
                     description: str | None = None, weatherproof: bool = False) -> tuple[str, str] | None:
    """``canonical_device`` plus the weatherproof add-on: a device marked
    weatherproof becomes its ``<variant>_weatherproof`` version where the
    catalog has one (a plain receptacle: the weatherproof duplex)."""
    device = _canonical_device(device_type, subtype, description=description)
    if weatherproof and device is not None and not device[1].endswith(_WEATHERPROOF):
        variant = device[1]
        if variant == "unspecified" and device[0] in _SUBTYPE_ALIASES:
            variant = _SUBTYPE_ALIASES[device[0]].get("", (None, variant))[1]
        if (device[0], f"{variant}{_WEATHERPROOF}") in _CATALOG_SET:
            return device[0], f"{variant}{_WEATHERPROOF}"
    return device


def _canonical_device(device_type: str | None = None, subtype: str | None = None, *,
                      description: str | None = None) -> tuple[str, str] | None:
    """The one class name for a device, from whatever a source calls it.

    Every box (annotator, agents, model, DEVICES/SAM/CircuitNet/VLM-review
    proposals, training) is named by this: a catalog device
    (``receptacle.duplex``), or ``(family, "unspecified")`` while the exact
    device is not known, or None for marks that are not devices (mechanical
    equipment, home-run arrows). A source's own subtype is never used as a
    class name, so one device never gets two names.

    ``device_type``/``subtype`` may be a catalog family/variant, a v7
    taxonomy pair (``fire_alarm``/``pull_station``) or an extractor's raw
    type; ``description`` (a legend text) can pick the device within its family.
    """
    family_key, sub_key = _key(device_type), _key(subtype)
    if (family_key, sub_key) in _CATALOG_SET:
        return family_key, sub_key
    if (family_key, sub_key) in RENAMED:
        return RENAMED[(family_key, sub_key)]
    if (family_key, sub_key) in NECA_DEVICE:
        return NECA_DEVICE[(family_key, sub_key)]
    if (family_key, sub_key) in _V7_IN_FAMILY:        # luminaire / 2x4: catalog family, v7 subtype
        return _V7_IN_FAMILY[(family_key, sub_key)]
    if family_key in ("unknown_symbol", "other_device", "") and sub_key in _V7_SUBTYPE:
        return _V7_SUBTYPE[sub_key]                  # a subtype that names one device
    if family_key in CATALOG_FAMILIES:
        base: tuple[str, str] | None = (family_key, "unspecified")
    else:
        base = device_for_raw_type(family_key)
        if base is None:
            return None
    family = base[0]
    if sub_key and (family, sub_key) in _CATALOG_SET:          # same name in its family
        return family, sub_key
    if description:                     # a legend text is more specific than a bare type
        found = device_for_legend_text(description)
        if found and (family == "unknown_symbol" or found[0] == family):
            return found
    if base[1] != "unspecified":
        return base
    if sub_key in _SUBTYPE_ALIASES.get(family, {}):            # plain receptacle = duplex
        return _SUBTYPE_ALIASES[family][sub_key]
    return base


def _v7_indexes():
    in_family: dict[tuple[str, str], tuple[str, str]] = {}
    by_subtype: dict[str, set] = {}
    for (raw_type, raw_sub), device in NECA_DEVICE.items():
        base = device_for_raw_type(raw_type)
        if base is not None:
            in_family.setdefault((base[0], raw_sub), device)
        by_subtype.setdefault(raw_sub, set()).add(device)
    return in_family, {k: next(iter(v)) for k, v in by_subtype.items() if len(v) == 1}


_V7_IN_FAMILY, _V7_SUBTYPE = _v7_indexes()

_PERSON = ("human", "manual_draft")


def canonical_instance(item: dict) -> dict:
    """A box renamed to its canonical class (``canonical_device``); its
    previous names are kept in ``raw_device_type``/``raw_subtype``. A verified
    box that only maps to ``family.unspecified`` is no longer verified: its
    exact device has to be picked again."""
    family, variant = item.get("symbol_family"), item.get("symbol_variant")
    raw = (item.get("raw_device_type"), item.get("raw_subtype"))
    if raw[0] == family and raw[1] != variant and raw in _CATALOG_SET:
        # The source named an exact device that an earlier catalog had merged
        # away and that exists again (e.g. a ceiling-mounted occupancy sensor).
        return dict(item, symbol_variant=raw[1])
    if (family, variant or "unspecified") in _CATALOG_SET:
        return item
    if family in CATALOG_FAMILIES and variant in (None, "", "unspecified"):
        # Not picked yet: the subtype its source gave (if any) may still name
        # the device (a plain receptacle is a duplex).
        device = canonical_device(family, item.get("raw_subtype"))
        if device is None or device[1] == "unspecified":
            return item
    else:
        device = canonical_device(family, variant)
    if device is None or (device[0] == "unknown_symbol" and family != "unknown_symbol"):
        if item.get("review_source") in _PERSON:
            return item                    # a person's own name for it: shown for them to resolve
        device = ("unknown_symbol", "unspecified")
    if device == (family, variant):
        return item
    out = dict(item, symbol_family=device[0], symbol_variant=device[1])
    out.setdefault("raw_device_type", family)
    if variant and variant != device[1]:
        out.setdefault("raw_subtype", variant)
    if device[1] == "unspecified" and out.get("verified_ground_truth") is True:
        out.update(verified_ground_truth=False, uncertain=True, review_status="unreviewed")
    return out


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


def labels_training_ready(labels_file: str | Path) -> bool:
    """Whether a PDF's labels file is marked done for training."""
    try:
        return bool((_labels_file(Path(labels_file)).get("training_ready") or {}).get("ready"))
    except (OSError, ValueError):
        return False


def page_training_ready(path: str | Path) -> bool:
    """A page JSON counts; a page of a labels file only when its PDF is marked done."""
    ref = parse_page_ref(path)
    return True if ref is None else labels_training_ready(ref[0])


def expand_page_paths(paths) -> list[Path]:
    """Page JSONs and page references, with every labels file expanded into
    references to each of its saved pages. Labels files of PDFs not marked
    done for training (the labeler's "PDF done" flag) give nothing."""
    out: list[Path] = []
    for path in paths:
        path = Path(path)
        if parse_page_ref(path) is None and path.name.endswith(LABELS_SUFFIX) and path.is_file():
            if labels_training_ready(path):
                out += [page_ref(path, int(k)) for k in sorted(_labels_file(path)["pages"], key=int)]
        else:
            out.append(path)
    return out


LABELS_KIND = "document_labels"         # == annotator.document_labels.KIND
LABELS_FORMAT_VERSION = 2               # == annotator.document_labels.FORMAT_VERSION


def labels_file_for(output: str | Path) -> Path:
    """The labels file an importer writes for ``output``: ``output`` itself
    when it already ends in ``_labels.json``, else ``<stem>_labels.json``
    beside it (``out/page.json`` -> ``out/page_labels.json``)."""
    output = Path(output)
    if output.name.endswith(LABELS_SUFFIX):
        return output
    return output.with_name(f"{output.stem}{LABELS_SUFFIX}")


def write_labels_file(path: str | Path, pages: dict[int, dict], *, imported_by: str,
                      training_ready: bool = True, info: dict | None = None) -> Path:
    """Write imported pages as one Format-2 labels file (``<stem>_labels.json``,
    the format of ``annotator.document_labels``) and return its path.

    Importers of supplied dataset labels (DXF block exports, SVG instances,
    COCO, FloorPlanCAD) use this instead of per-page JSONs. Their labels are
    exact source metadata, not proposals, so the file is marked training-ready
    by the importer (``training_ready.set_by = "importer:<name>"``); pass
    ``training_ready=False`` to require a person's "PDF done" first. Pages are
    1-based; an ``image_path`` relative to the labels file's folder stays
    relative (``read_page`` resolves it)."""
    import os
    import time
    path = Path(path)
    if not path.name.endswith(LABELS_SUFFIX):
        raise ValueError(f"a labels file must be named <stem>{LABELS_SUFFIX}: {path.name}")
    if not pages or any(int(k) < 1 for k in pages):
        raise ValueError("pages must be numbered from 1")
    data = {"format_version": LABELS_FORMAT_VERSION, "kind": LABELS_KIND,
            "json_prefix": path.name[:-len(LABELS_SUFFIX)], **(info or {}),
            "training_ready": {"ready": bool(training_ready),
                               "set_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                               "set_by": f"importer:{imported_by}"},
            "pages": {str(int(k)): pages[k] for k in sorted(pages, key=int)}}
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    os.replace(tmp, path)
    _labels_cache.pop(str(path), None)
    return path


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
