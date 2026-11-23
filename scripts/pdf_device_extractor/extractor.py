"""PDF device-extraction engine for construction blueprint PDFs.

Unified v1.1.0 tool that subsumes the methods of all five completed agents:

* Caltrans            -> text/symbol bboxes, wall connection points, num_symbol_drawings
* Kingsessing         -> data_quality, limitations, per-sheet mapping, legend_device_types
* UNT Willis          -> scanned-PDF handling, likely_title_block_artifact, low-confidence proxies
* WashingtonDrawings  -> symbol_attached flag, wall_distance, device_code, sheet, label/symbol bboxes
* C2 Drawings         -> combined_bbox (symbol+text), symbol_graphic_bbox (symbol only),
                         wall_distance_pts, instance_id, category, mounting, legend_source

Uses only pdfplumber + pypdf. No vision tools, no external API calls, no internet required.

All coordinates are PDF points with origin at bottom-left. pdfplumber handles page
rotation (derotation) automatically; its page.width/page.height and the coordinates it
returns for words/rects/curves are already in the derotated, upright coordinate system.
We convert its top-origin y values to bottom-left via ``y_bl = page.height - y_top``.

Extraction strategy for vector pages:
  1. Open PDF, detect per-page rotation and scanned/vector status.
  2. Extract words + vector drawings (rects, curves, lines).
  3. Detect wall lines (long, thin strokes/rects).
  4. Extract legend/schedule sheet device-type definitions.
  5. Cluster compact vector drawings into symbol candidates.
  6. Identify device-label text (keyword + heuristic filtering).
  7. Match each label to the nearest symbol cluster (symbol_attached).
  8. Classify device type (legend -> keyword rules -> symbol-shape hint).
  9. Compute wall connection point, distances, combined bbox, title-block artifact flag.

Scanned pages (no text layer, no vector graphics) are reported but not fabricated:
devices[] entries are omitted and limitations are recorded.
"""

from __future__ import annotations

import math
import os
import re
from typing import Any, Dict, List, Optional, Tuple

import pdfplumber
import pypdf

from schema import (
    COORDINATE_SYSTEM,
    DEVICE_TYPE_DESCRIPTIONS,
    DEVICE_TYPE_VOCABULARY,
    EXTRACTION_TOOL_VERSION,
    SCHEMA_VERSION,
    DataQuality,
    Device,
    PDFDeviceExtractionResult,
    SymbolLegendEntry,
    _dataclass_to_dict,
)

# ---------------------------------------------------------------------------
# Configuration constants
# ---------------------------------------------------------------------------

# A page is treated as "vector" (extractable) if it has any extractable text
# or vector graphics. Below that it is treated as scanned/blank.
MIN_TEXT_CHARS_FOR_VECTOR = 3

# Wall detection: a wall is a long, thin stroke or rect.
WALL_LONG_MIN = 80.0        # pts: minimum long dimension to be a wall
WALL_SHORT_MAX = 25.0       # pts: maximum short dimension to be a wall

# Symbol candidate detection: compact vector drawings (both dims small).
SYM_MIN_DIM = 2.0           # pts
SYM_MAX_DIM = 70.0          # pts

# Symbol clustering: parts within this distance (pts) are merged.
SYM_CLUSTER_GAP = 9.0       # pts

# Label-to-symbol matching: max distance (pts) to attach a symbol.
LABEL_SYMBOL_MAX_DIST = 30.0

# Wall connection: symbol this close to a wall is "on wall".
ON_WALL_THRESHOLD = 12.0    # pts

# Margin from page edge to ignore title-block / border artifacts.
PAGE_MARGIN = 20.0

# Margin within which a short-coded label near the page edge is treated as a
# likely title-block / border artifact (UNT Willis approach).
TITLE_BLOCK_MARGIN = 50.0
TITLE_BLOCK_SHORT_LABEL = 6  # max label length to be flagged as artifact

# Minimum cluster size (in pts) to be considered a real symbol vs a speck.
SYM_MIN_SIZE = 3.0          # pts (min of width or height)

# Maximum distance (pts) to report a wall_distance when no on_wall connection.
WALL_DISTANCE_MAX_REPORT = 300.0


# ---------------------------------------------------------------------------
# Device-type classification
# ---------------------------------------------------------------------------

# Map of lowercase keyword -> (device_type, device_subtype, confidence)
# Order matters: more specific keywords should be checked first.
_CLASSIFICATION_RULES: List[Tuple[re.Pattern, str, Optional[str], str]] = []


def _add_rule(pattern: str, dtype: str, subtype: Optional[str], conf: str) -> None:
    _CLASSIFICATION_RULES.append((re.compile(pattern, re.IGNORECASE), dtype, subtype, conf))


# Fire alarm (specific first)
_add_rule(r"\bpull\b|\bpull.station\b|\bmanual.call\b", "fire_alarm_pull_station", "manual pull station", "medium")
_add_rule(r"\bsmoke\b|\bsmoke.detector\b", "fire_alarm_smoke_detector", "smoke detector", "medium")
_add_rule(r"\bfire.alarm\b|\bFA\b", "fire_alarm_smoke_detector", "fire alarm device", "low")

# Fire sprinkler
_add_rule(r"\bsprinkl\b|\bhead\b", "fire_sprinkler_head", "sprinkler head", "medium")

# Fire extinguisher / exit sign (life safety)
_add_rule(r"\bfire.extinguisher\b|\bfe\b", "fire_extinguisher", "fire extinguisher", "medium")
_add_rule(r"\bexit\b|\begress\b|\bx1\b", "exit_sign", "exit light", "medium")

# Receptacles (GFCI/USB before generic)
_add_rule(r"\bgfci\b", "receptacle_gfci", "GFCI receptacle", "medium")
_add_rule(r"\busb\b", "receptacle_usb", "USB receptacle", "medium")
_add_rule(r"\breceptacle\b|\boutlet\b|\bdup\b|\brcpt\b|\brecp\b", "receptacle", "duplex receptacle", "medium")

# Switches (specific before generic)
_add_rule(r"\b3.?way\b|\bthree.?way\b", "switch_3way", "3-way switch", "medium")
_add_rule(r"\b4.?way\b|\bfour.?way\b", "switch_4way", "4-way switch", "medium")
_add_rule(r"\bdimm\b", "switch_dimmer", "dimmer switch", "medium")
_add_rule(r"\bsingle.?pole\b|\b1.?pole\b", "switch_single_pole", "single-pole switch", "medium")
_add_rule(r"\bswitch\b|\bsw\b", "switch_single_pole", "wall switch", "low")

# Lighting / luminaires
_add_rule(r"\bluminaire\b", "luminaire", "luminaire", "medium")
_add_rule(r"\blight\b|\bfixture\b", "light_fixture", "light fixture", "low")

# HVAC
_add_rule(r"\bthermostat\b|\btstat\b|\bt-stat\b", "hvac_thermostat", "thermostat", "medium")
_add_rule(r"\bsensor\b|\boccupancy\b|\bvacancy\b", "hvac_sensor", "sensor", "low")

# Boxes / poles / equipment
_add_rule(r"\bpull.box\b", "pull_box", "pull box", "medium")
_add_rule(r"\bjunction.box\b|\bjbox\b|\bj-?b\b", "junction_box", "junction box", "medium")
_add_rule(r"\bpole\b", "pole", "lighting pole", "medium")
_add_rule(r"\belectric.unit.heater\b|\beuh\b", "electric_unit_heater", "unit heater", "medium")
_add_rule(r"\binstantaneous\b|\btankless\b|\biwh\b", "instantaneous_water_heater", "water heater", "medium")


def _symbol_shape_hint(symbol_bbox: Optional[List[float]]) -> Optional[str]:
    """Weak device-type hint from symbol geometry (aspect ratio).

    Used only as a tiebreaker when text classification is low-confidence.
    """
    if not symbol_bbox:
        return None
    x0, y0, x1, y1 = symbol_bbox
    w = x1 - x0
    h = y1 - y0
    if w <= 0 or h <= 0:
        return None
    aspect = max(w, h) / min(w, h)
    if aspect > 2.0:
        # Tall/narrow or wide/short -> typically a switch or wall device.
        return "switch_single_pole"
    if aspect < 1.6:
        # Roughly square -> typically a receptacle / junction box.
        return "receptacle"
    return None


def classify_device_type(
    symbol_glyph: Optional[List[float]],
    nearby_text: str,
    legend: Optional[List[Dict[str, Any]]] = None,
) -> Tuple[str, Optional[str], str]:
    """Classify a device into (device_type, device_subtype, confidence).

    Strategy (highest confidence first):
      1. Legend match: nearby_text matches a legend device_type / description.
      2. Keyword rules on nearby_text.
      3. Symbol-shape hint as a tiebreaker when keyword confidence is "low".

    Falls back to ("other", None, "low") when nothing matches.
    """
    text = (nearby_text or "").strip()

    # 1. Legend match (Washington/C2 approach: legend maps codes -> device types).
    if legend:
        tl = text.lower()
        for entry in legend:
            desc = (entry.get("symbol_description") or "").lower()
            dtype = (entry.get("device_type") or "").lower()
            if tl and (tl in desc or tl == dtype or desc in tl):
                return entry["device_type"], entry.get("symbol_description"), "medium"

    # 2. Keyword rules.
    if text:
        for pattern, dtype, subtype, conf in _CLASSIFICATION_RULES:
            if pattern.search(text):
                # Shape-hint tiebreaker: only promote a low-confidence keyword
                # match when the symbol geometry strongly suggests another type.
                if conf == "low":
                    hint = _symbol_shape_hint(symbol_glyph)
                    if hint and hint != dtype:
                        # Keep keyword result but note ambiguity via "low".
                        return dtype, subtype, conf
                return dtype, subtype, conf

    # 3. Pure shape hint (no usable text).
    hint = _symbol_shape_hint(symbol_glyph)
    if hint:
        return hint, None, "low"

    return "other", None, "low"


# ---------------------------------------------------------------------------
# Geometry helpers
# ---------------------------------------------------------------------------

def _pt_seg_distance(px: float, py: float, x1: float, y1: float, x2: float, y2: float) -> float:
    """Distance from point (px,py) to line segment (x1,y1)-(x2,y2)."""
    dx = x2 - x1
    dy = y2 - y1
    if dx == 0 and dy == 0:
        return math.hypot(px - x1, py - y1)
    t = ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy)
    t = max(0.0, min(1.0, t))
    return math.hypot(px - (x1 + t * dx), py - (y1 + t * dy))


def _pt_seg_closest(px: float, py: float, x1: float, y1: float, x2: float, y2: float) -> Tuple[float, float]:
    """Closest point on segment (x1,y1)-(x2,y2) to (px,py)."""
    dx = x2 - x1
    dy = y2 - y1
    if dx == 0 and dy == 0:
        return x1, y1
    t = ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy)
    t = max(0.0, min(1.0, t))
    return x1 + t * dx, y1 + t * dy


def _rect_bottom_left(rect: Dict[str, Any], page_height: float) -> Tuple[float, float, float, float]:
    """Convert a pdfplumber rect (top-origin) to bottom-left (x1,y1,x2,y2)."""
    x0, x1 = rect["x0"], rect["x1"]
    y0_bl = page_height - rect["bottom"]
    y1_bl = page_height - rect["top"]
    return x0, y0_bl, x1, y1_bl


def _line_bottom_left(ln: Dict[str, Any], page_height: float) -> Tuple[float, float, float, float]:
    """Convert a pdfplumber line (top-origin) to bottom-left (x1,y1,x2,y2)."""
    x0, x1 = ln["x0"], ln["x1"]
    y0_bl = page_height - ln["top"]
    y1_bl = page_height - ln["bottom"]
    return x0, y0_bl, x1, y1_bl


def _union_bbox(
    a: Optional[List[float]], b: Optional[List[float]]
) -> Optional[List[float]]:
    """Union of two [x1,y1,x2,y2] bboxes; returns the non-None one if only one given."""
    if a is None:
        return list(b) if b else None
    if b is None:
        return list(a)
    return [min(a[0], b[0]), min(a[1], b[1]), max(a[2], b[2]), max(a[3], b[3])]


# ---------------------------------------------------------------------------
# Main extractor
# ---------------------------------------------------------------------------

class PDFDeviceExtractor:
    """Extract device coordinates from vector-based architectural/MEP/Electrical PDFs.

    Parameters
    ----------
    pdf_path : str
        Path to the PDF file to process.
    """

    def __init__(self, pdf_path: str):
        self.pdf_path = os.path.abspath(pdf_path)
        self.source_file = os.path.basename(pdf_path)
        self.source_url: Optional[str] = None

        # Open with both libraries: pdfplumber for content extraction,
        # pypdf for page count / metadata / raw rotation values.
        self._plumber = pdfplumber.open(self.pdf_path)
        self._pypdf = pypdf.PdfReader(self.pdf_path)

        self.total_pages = len(self._plumber.pages)
        self._page_vector_flags: List[bool] = []
        self._page_has_content: List[bool] = []
        self._page_size: List[float] = []
        self._page_rotation: List[int] = []

        self._detect_page_types()

    # -- lifecycle ----------------------------------------------------------

    def close(self) -> None:
        try:
            self._plumber.close()
        except Exception:
            pass

    def __enter__(self) -> "PDFDeviceExtractor":
        return self

    def __exit__(self, *args: Any) -> None:
        self.close()

    # -- scanned/vector / rotation detection --------------------------------

    def _detect_page_types(self) -> None:
        """Classify each page as vector (extractable text/graphics) or scanned."""
        self._page_vector_flags = []
        self._page_has_content = []
        self._page_size = []
        self._page_rotation = []

        for i, page in enumerate(self._plumber.pages):
            w, h = float(page.width), float(page.height)
            if i == 0:
                self._page_size = [w, h]

            text = page.extract_text() or ""
            words = page.extract_words() or []
            rects = page.rects or []
            lines = page.lines or []
            curves = page.curves or []
            images = page.images or []

            has_text = len(text.strip()) >= MIN_TEXT_CHARS_FOR_VECTOR or len(words) > 0
            has_vector = len(rects) > 0 or len(lines) > 0 or len(curves) > 0
            has_image = len(images) > 0

            is_vector = has_text or has_vector
            has_content = has_text or has_vector or has_image

            self._page_vector_flags.append(is_vector)
            self._page_has_content.append(has_content)
            self._page_rotation.append(self.detect_page_rotation(page))

    def detect_page_rotation(self, page) -> int:
        """Return the page's raw /Rotate value (0, 90, 180, 270).

        pdfplumber already derotates pages for content extraction, so all
        returned coordinates are in the upright coordinate system. This method
        reports the raw rotation for diagnostics / legacy consumers.
        """
        try:
            rotate = page.get("/Rotate")
            if rotate is None:
                # pypdf PageObject may expose via the underlying object.
                rotate = self._pypdf.pages[self._plumber.pages.index(page)].get("/Rotate")
            return int(rotate or 0)
        except Exception:
            return 0

    def is_scanned(self, page_num: Optional[int] = None) -> bool:
        """Return True if a page (or the whole PDF) is scanned/image-only.

        If ``page_num`` is given (1-based), report for that page only. A page is
        scanned when it has no extractable text and no vector graphics (only
        images or nothing).
        """
        if page_num is not None:
            idx = page_num - 1
            if idx < 0 or idx >= self.total_pages:
                return True
            return not self._page_vector_flags[idx]
        if not self._page_vector_flags:
            return True
        return not any(self._page_vector_flags)

    def _extraction_method(self) -> str:
        if not self._page_vector_flags:
            return "scanned"
        if all(self._page_vector_flags):
            return "vector"
        return "mixed"

    # -- page access --------------------------------------------------------

    def _page(self, page_num: int):
        """Return pdfplumber page object for a 1-based page number."""
        return self._plumber.pages[page_num - 1]

    def _extract_sheet_name(self, page_text: str) -> Optional[str]:
        """Best-effort extraction of a sheet title / identifier from page text.

        Matches lines like "E3.11 Electrical Enlarged Floor Plan - C Wing" or
        "ES-100 SITE PLAN - ELECTRICAL". Returns None when nothing plausible
        is found (Washington/C2 sheet names).
        """
        if not page_text:
            return None
        sheet_pat = re.compile(r"^[A-Z][A-Z0-9 .,&/-]*\d[A-Z0-9 .,&/-]*$")
        for line in page_text.splitlines():
            line = line.strip()
            if len(line) < 4 or len(line) > 100:
                continue
            if sheet_pat.match(line) and re.search(r"\d", line) and re.search(r"[A-Za-z]", line):
                return line
        return None

    # -- wall detection -----------------------------------------------------

    def is_wall_line(self, vector_obj: Dict[str, Any], page_height: float) -> bool:
        """Determine if a vector line/curve/rect is a wall (thick, long, continuous).

        Parameters
        ----------
        vector_obj : dict
            A pdfplumber rect/line/curve object (top-origin y values).
        page_height : float
            Page height, used to convert y values to bottom-left.

        Returns
        -------
        bool
            True if the object looks like a wall.
        """
        kind = vector_obj.get("object_type", "")
        if kind == "rect":
            x0, y0, x1, y1 = _rect_bottom_left(vector_obj, page_height)
        elif kind == "line":
            x0, y0, x1, y1 = _line_bottom_left(vector_obj, page_height)
        elif kind == "curve":
            pts = vector_obj.get("pts", [])
            if not pts:
                return False
            xs = [p[0] for p in pts]
            ys_bl = [page_height - p[1] for p in pts]
            x0, x1, y0, y1 = min(xs), max(xs), min(ys_bl), max(ys_bl)
        else:
            return False

        bw = abs(x1 - x0)
        bh = abs(y1 - y0)
        longdim = max(bw, bh)
        shdim = min(bw, bh)
        return longdim >= WALL_LONG_MIN and shdim <= WALL_SHORT_MAX

    def _collect_wall_lines(self, page, page_height: float) -> List[Tuple[float, float, float, float]]:
        """Return wall segments as [(x1,y1,x2,y2), ...] in bottom-left coords."""
        walls: List[Tuple[float, float, float, float]] = []
        for r in page.rects or []:
            if self.is_wall_line(r, page_height):
                walls.append(_rect_bottom_left(r, page_height))
        for ln in page.lines or []:
            if self.is_wall_line(ln, page_height):
                walls.append(_line_bottom_left(ln, page_height))
        return walls

    # -- symbol-attached detection ------------------------------------------

    def is_symbol_attached(
        self, text_bbox: Optional[List[float]], symbol_candidates: List[Dict[str, Any]], page
    ) -> bool:
        """Is there a vector glyph near the text label? (WashingtonDrawings approach)

        Returns True if any symbol candidate's center lies within
        LABEL_SYMBOL_MAX_DIST of the text label center.
        """
        if not text_bbox or not symbol_candidates:
            return False
        tx = (text_bbox[0] + text_bbox[2]) / 2.0
        ty = (text_bbox[1] + text_bbox[3]) / 2.0
        for cl in symbol_candidates:
            d = math.hypot(cl["cx"] - tx, cl["cy"] - ty)
            if d <= LABEL_SYMBOL_MAX_DIST:
                return True
        return False

    # -- legend extraction --------------------------------------------------

    def extract_legend(self) -> List[Dict[str, Any]]:
        """Find legend/schedule sheets and extract device-type definitions.

        Heuristic: scan pages for keywords like "legend", "symbol", "schedule"
        in their text. On candidate pages, pull out short text lines that look
        like device definitions (a symbol tag followed by a description).

        Returns a list of dicts with keys: device_type, symbol_description,
        source_sheet, source_page.
        """
        legend_entries: List[Dict[str, Any]] = []
        legend_keywords = re.compile(r"\b(legend|symbol|schedule|device|key)\b", re.IGNORECASE)

        for i, page in enumerate(self._plumber.pages, start=1):
            if not self._page_vector_flags[i - 1]:
                continue
            text = page.extract_text() or ""
            if not legend_keywords.search(text):
                continue

            words = page.extract_words() or []
            if not words:
                continue

            sheet_name = self._extract_sheet_name(text) or f"page {i}"

            # Group words into lines by similar 'top' coordinate.
            lines: Dict[float, List[Dict[str, Any]]] = {}
            for w in words:
                key = round(w["top"], 1)
                lines.setdefault(key, []).append(w)

            for top, line_words in sorted(lines.items()):
                line_words.sort(key=lambda w: w["x0"])
                line_text = " ".join(w["text"] for w in line_words).strip()
                if len(line_text) < 3 or len(line_text) > 80:
                    continue
                if not re.search(
                    r"\b(receptacle|switch|light|thermostat|sensor|smoke|detector|sprinkl|"
                    r"junction|pull.box|pole|fixture|outlet|extinguisher|exit|occupancy|"
                    r"heater|damper|box|panel|meter)\b",
                    line_text, re.IGNORECASE,
                ):
                    continue
                dtype, subtype, _conf = classify_device_type(None, line_text)
                legend_entries.append({
                    "device_type": dtype,
                    "symbol_description": line_text,
                    "source_sheet": sheet_name,
                    "source_page": i,
                })

        # Deduplicate by (device_type, source_page).
        seen = set()
        unique: List[Dict[str, Any]] = []
        for e in legend_entries:
            key = (e["device_type"], e["source_page"])
            if key in seen:
                continue
            seen.add(key)
            unique.append(e)
        return unique

    # -- device extraction from a single page -------------------------------

    def _nearest_wall_point(
        self, sx: float, sy: float, walls: List[Tuple[float, float, float, float]]
    ) -> Tuple[Optional[List[float]], Optional[float]]:
        """Find the point on the nearest wall centerline closest to (sx, sy).

        Returns (point [x, y] or None, distance or None).
        """
        if not walls:
            return None, None
        best_pt: Optional[Tuple[float, float]] = None
        best_dist = float("inf")
        for (x1, y1, x2, y2) in walls:
            d = _pt_seg_distance(sx, sy, x1, y1, x2, y2)
            if d < best_dist:
                best_dist = d
                best_pt = _pt_seg_closest(sx, sy, x1, y1, x2, y2)
        if best_pt is None:
            return None, None
        return [round(best_pt[0], 2), round(best_pt[1], 2)], round(best_dist, 2)

    def detect_wall_connection(
        self, symbol_bbox: List[float], wall_lines: List[List[float]], page
    ) -> Optional[List[float]]:
        """Find the center-line intersection point on the nearest wall.

        Parameters
        ----------
        symbol_bbox : [x1, y1, x2, y2]
            Symbol bounding box in PDF points.
        wall_lines : list of [x1, y1, x2, y2]
            Wall line segments in PDF points (bottom-left origin).
        page : pdfplumber page
            Page object (kept for API compatibility / future derotation).

        Returns
        -------
        [x, y] or None
            Point on the nearest wall centerline closest to the symbol center,
            or None if no walls are given.
        """
        if not symbol_bbox or not wall_lines:
            return None
        sx = (symbol_bbox[0] + symbol_bbox[2]) / 2.0
        sy = (symbol_bbox[1] + symbol_bbox[3]) / 2.0
        walls = [tuple(w) for w in wall_lines]
        pt, _ = self._nearest_wall_point(sx, sy, walls)
        return pt

    def extract_devices_from_page(
        self, page_num: int, legend_entries: Optional[List[Dict[str, Any]]] = None
    ) -> List[Dict[str, Any]]:
        """Extract all devices from a single page (1-based page number).

        Returns a list of device dicts conforming to the unified schema
        (without device_id, which is assigned in extract_all).

        Parameters
        ----------
        page_num : int
            1-based page number.
        legend_entries : list[dict] | None
            Pre-computed legend entries (from ``extract_legend``). Computed
            once in ``extract_all`` and passed in to avoid O(pages^2)
            re-scanning every page.
        """
        if page_num < 1 or page_num > self.total_pages:
            return []

        page = self._page(page_num)
        if not self._page_vector_flags[page_num - 1]:
            # Scanned page: cannot extract devices without vision tools.
            return []

        H = float(page.height)
        W = float(page.width)

        # ---- words (bottom-left origin) ----------------------------------
        raw_words = page.extract_words() or []
        words: List[Dict[str, Any]] = []
        for w in raw_words:
            x0, x1 = w["x0"], w["x1"]
            y0_bl = H - w["bottom"]
            y1_bl = H - w["top"]
            words.append({
                "text": w["text"],
                "x0": x0, "y0": y0_bl, "x1": x1, "y1": y1_bl,
                "cx": (x0 + x1) / 2.0, "cy": (y0_bl + y1_bl) / 2.0,
            })

        # ---- sheet name --------------------------------------------------
        page_text = page.extract_text() or ""
        sheet_name = self._extract_sheet_name(page_text)

        # ---- walls: long thin rects/lines --------------------------------
        walls = self._collect_wall_lines(page, H)

        # ---- symbol candidates: compact rects + curves -------------------
        candidates: List[Tuple[float, float, float, float]] = []
        for r in page.rects or []:
            x0, y0, x1, y1 = _rect_bottom_left(r, H)
            bw, bh = x1 - x0, y1 - y0
            if SYM_MIN_DIM <= max(bw, bh) <= SYM_MAX_DIM:
                candidates.append((x0, y0, x1, y1))
        for c in page.curves or []:
            pts = c.get("pts", [])
            if not pts:
                continue
            xs = [p[0] for p in pts]
            ys_bl = [H - y for y in [p[1] for p in pts]]
            bw = max(xs) - min(xs)
            bh = max(ys_bl) - min(ys_bl)
            if SYM_MIN_DIM <= max(bw, bh) <= SYM_MAX_DIM:
                candidates.append((min(xs), min(ys_bl), max(xs), max(ys_bl)))

        # ---- cluster candidates into symbols -----------------------------
        clusters = self._cluster_candidates(candidates)

        # ---- identify device-label phrases -------------------------------
        labels = self._find_device_labels(words)

        # ---- legend (for classification context) -------------------------
        # legend_entries is pre-computed once in extract_all() and passed in;
        # do NOT recompute it here (that made extraction O(pages^2)).
        if legend_entries is None:
            legend_entries = self.extract_legend()

        # ---- match labels to nearest symbol cluster ----------------------
        devices: List[Dict[str, Any]] = []
        cluster_centers = [(cl["cx"], cl["cy"], cl) for cl in clusters]

        for lbl in labels:
            lx, ly = lbl["cx"], lbl["cy"]

            # Skip labels far outside the drawing area (title-block artifacts).
            if (lx < -PAGE_MARGIN or ly < -PAGE_MARGIN or
                    lx > W + PAGE_MARGIN or ly > H + PAGE_MARGIN):
                continue

            best_sym = None
            best_dist = float("inf")
            for ccx, ccy, cl in cluster_centers:
                d = math.hypot(ccx - lx, ccy - ly)
                if d < best_dist:
                    best_dist = d
                    best_sym = cl

            symbol_attached = best_sym is not None and best_dist <= LABEL_SYMBOL_MAX_DIST

            sym_bbox: Optional[List[float]] = None
            sym_center: Optional[List[float]] = None
            num_drawings = 0
            attach_dist = 0.0

            if symbol_attached and best_sym is not None:
                sym_bbox = [
                    round(best_sym["x0"], 2), round(best_sym["y0"], 2),
                    round(best_sym["x1"], 2), round(best_sym["y1"], 2),
                ]
                sym_center = [round(best_sym["cx"], 2), round(best_sym["cy"], 2)]
                num_drawings = best_sym.get("num_parts", 0)
                attach_dist = round(best_dist, 2)

            # Skip text-only labels with no associated symbol graphic. A label
            # matched to a keyword in general notes / schedules / title blocks
            # but with no nearby symbol is an unverified artifact, not a device.
            if sym_bbox is None:
                continue

            # ---- wall connection point & distance ------------------------
            scx, scy = sym_center if sym_center else (lx, ly)
            wall_pt, wall_dist = self._nearest_wall_point(scx, scy, walls)

            on_wall: Optional[bool] = None
            if wall_dist is not None:
                on_wall = wall_dist <= ON_WALL_THRESHOLD
                if wall_dist > WALL_DISTANCE_MAX_REPORT:
                    wall_dist = None
                    wall_pt = None
                    on_wall = None
            wall_connection_point = wall_pt if on_wall else None
            wall_distance_pts = wall_dist

            # ---- device_center / center ----------------------------------
            device_center = list(sym_center) if sym_center else [round(lx, 2), round(ly, 2)]

            # ---- combined_bbox (symbol + text together, C2 bounding_box) --
            combined_bbox = _union_bbox(
                sym_bbox,
                [round(lbl["x0"], 2), round(lbl["y0"], 2), round(lbl["x1"], 2), round(lbl["y1"], 2)],
            )

            # ---- likely title-block artifact (UNT Willis approach) -------
            likely_artifact: Optional[bool] = None
            dcx, dcy = device_center
            near_edge = (
                dcx < TITLE_BLOCK_MARGIN or dcy < TITLE_BLOCK_MARGIN or
                dcx > W - TITLE_BLOCK_MARGIN or dcy > H - TITLE_BLOCK_MARGIN
            )
            if near_edge:
                likely_artifact = len(lbl["text"]) <= TITLE_BLOCK_SHORT_LABEL

            # ---- classification ------------------------------------------
            dtype, subtype, conf = classify_device_type(sym_bbox, lbl["text"], legend_entries)

            devices.append({
                "page": page_num,
                "sheet_name": sheet_name,
                "device_type": dtype,
                "device_subtype": subtype,
                "device_label": lbl["text"],
                "text_bbox": [round(lbl["x0"], 2), round(lbl["y0"], 2),
                              round(lbl["x1"], 2), round(lbl["y1"], 2)],
                "text_center": [round(lbl["cx"], 2), round(lbl["cy"], 2)],
                "symbol_bbox": sym_bbox,
                "symbol_graphic_bbox": list(sym_bbox) if sym_bbox else None,
                "symbol_center": sym_center,
                "combined_bbox": combined_bbox,
                "center": list(device_center),
                "device_center": device_center,
                "on_wall": on_wall,
                "wall_connection_point": wall_connection_point,
                "wall_distance_pts": wall_distance_pts,
                "num_symbol_drawings": num_drawings,
                "distance_to_text": attach_dist,
                "symbol_attached": symbol_attached,
                "confidence": conf,
                "likely_title_block_artifact": likely_artifact,
                "source_sheet": sheet_name,
            })

        return devices

    # -- helpers used by extract_devices_from_page --------------------------

    def _cluster_candidates(
        self, candidates: List[Tuple[float, float, float, float]]
    ) -> List[Dict[str, Any]]:
        """Union-find cluster nearby symbol candidates into symbol groups."""
        n = len(candidates)
        if n == 0:
            return []

        parent = list(range(n))

        def find(a: int) -> int:
            while parent[a] != a:
                parent[a] = parent[parent[a]]
                a = parent[a]
            return a

        def union(a: int, b: int) -> None:
            ra, rb = find(a), find(b)
            if ra != rb:
                parent[rb] = ra

        for i in range(n):
            x0a, y0a, x1a, y1a = candidates[i]
            cxa, cya = (x0a + x1a) / 2.0, (y0a + y1a) / 2.0
            for j in range(i + 1, n):
                x0b, y0b, x1b, y1b = candidates[j]
                cxb, cyb = (x0b + x1b) / 2.0, (y0b + y1b) / 2.0
                if abs(cxa - cxb) <= SYM_CLUSTER_GAP and abs(cya - cyb) <= SYM_CLUSTER_GAP:
                    union(i, j)

        groups: Dict[int, List[int]] = {}
        for i in range(n):
            groups.setdefault(find(i), []).append(i)

        clusters: List[Dict[str, Any]] = []
        for idxs in groups.values():
            xs0 = [candidates[i][0] for i in idxs]
            ys0 = [candidates[i][1] for i in idxs]
            xs1 = [candidates[i][2] for i in idxs]
            ys1 = [candidates[i][3] for i in idxs]
            x0, y0 = min(xs0), min(ys0)
            x1, y1 = max(xs1), max(ys1)
            w, h = x1 - x0, y1 - y0
            if w < SYM_MIN_SIZE or h < SYM_MIN_SIZE:
                continue
            clusters.append({
                "x0": x0, "y0": y0, "x1": x1, "y1": y1,
                "cx": (x0 + x1) / 2.0, "cy": (y0 + y1) / 2.0,
                "num_parts": len(idxs),
            })
        return clusters

    def _find_device_labels(self, words: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        """Identify words/phrases that are device labels.

        Filters out pure numbers, dimensions, grid markers, and common
        non-device text. Groups adjacent same-line words into phrases when
        they form a multi-word device label.
        """
        non_device = {
            "typ", "n.t.s", "nts", "a.f.f", "aff", "up", "dn", "new",
            "existing", "demo", "n", "e", "s", "w",
        }

        candidates: List[Dict[str, Any]] = []
        for w in words:
            t = w["text"].strip()
            if not t:
                continue
            if t.lower() in non_device:
                continue
            if re.fullmatch(r"\d+", t):
                continue
            if re.fullmatch(r"\+?\d+(\.\d+)?(\'\s*\d+)?[\"\']?", t):
                continue
            if re.fullmatch(r"[A-Z]\d?", t.upper()) and len(t) <= 2:
                continue
            if re.fullmatch(r"\d+(\.\d+)?", t):
                continue
            candidates.append(w)

        by_line: Dict[float, List[Dict[str, Any]]] = {}
        for w in candidates:
            key = round(w["cy"], 1)
            by_line.setdefault(key, []).append(w)

        phrases: List[Dict[str, Any]] = []
        for cy, line_words in by_line.items():
            line_words.sort(key=lambda w: w["x0"])
            current: List[Dict[str, Any]] = []
            for w in line_words:
                if current and (w["x0"] - current[-1]["x1"]) > 8.0:
                    phrases.append(self._merge_phrase(current))
                    current = [w]
                else:
                    current.append(w)
            if current:
                phrases.append(self._merge_phrase(current))

        labels: List[Dict[str, Any]] = []
        for p in phrases:
            text = p["text"]
            if not re.search(
                r"\b(receptacle|outlet|gfci|usb|switch|dimmer|light|luminaire|fixture|"
                r"thermostat|tstat|sensor|occupancy|vacancy|smoke|detector|sprinkl|"
                r"junction|pull.box|pole|jbox|fire.alarm|3.?way|4.?way|single.?pole|"
                r"extinguisher|exit|heater|tankless|damper|box|panel|meter)\b",
                text, re.IGNORECASE,
            ):
                continue
            labels.append(p)
        return labels

    @staticmethod
    def _merge_phrase(words: List[Dict[str, Any]]) -> Dict[str, Any]:
        x0 = min(w["x0"] for w in words)
        y0 = min(w["y0"] for w in words)
        x1 = max(w["x1"] for w in words)
        y1 = max(w["y1"] for w in words)
        return {
            "text": " ".join(w["text"] for w in words),
            "x0": x0, "y0": y0, "x1": x1, "y1": y1,
            "cx": (x0 + x1) / 2.0, "cy": (y0 + y1) / 2.0,
        }

    # -- full extraction ----------------------------------------------------

    def extract_all(self) -> Dict[str, Any]:
        """Run a full extraction and return the unified schema as a dict."""
        limitations: List[str] = []

        if self.is_scanned():
            limitations.append(
                "PDF is fully scanned: device symbols live inside page images. "
                "No image-analysis/vision tool is available, so symbol graphics "
                "cannot be located or classified. devices[] is empty."
            )
        else:
            for i, is_vec in enumerate(self._page_vector_flags, start=1):
                if not is_vec and self._page_has_content[i - 1]:
                    limitations.append(
                        f"Page {i} is scanned/image-based (no text layer); devices "
                        "on this page cannot be extracted without vision tooling."
                    )
                    break  # report once

        # Legend extraction (best effort; may be empty).
        legend_entries = self.extract_legend()
        legend_sheets_found = len(legend_entries) > 0

        # Per-page device extraction.
        all_devices: List[Device] = []
        pages_with_devices: List[int] = []
        device_id = 1

        for page_num in range(1, self.total_pages + 1):
            page_devices = self.extract_devices_from_page(page_num, legend_entries=legend_entries)
            if not page_devices:
                continue
            pages_with_devices.append(page_num)
            for pd_ in page_devices:
                device = Device(
                    device_id=device_id,
                    page=pd_["page"],
                    sheet_name=pd_.get("sheet_name"),
                    device_type=pd_["device_type"],
                    device_subtype=pd_.get("device_subtype"),
                    device_label=pd_["device_label"],
                    text_bbox=pd_.get("text_bbox"),
                    text_center=pd_.get("text_center"),
                    symbol_bbox=pd_.get("symbol_bbox"),
                    symbol_graphic_bbox=pd_.get("symbol_graphic_bbox"),
                    symbol_center=pd_.get("symbol_center"),
                    combined_bbox=pd_.get("combined_bbox"),
                    center=pd_.get("center"),
                    device_center=pd_.get("device_center"),
                    on_wall=pd_.get("on_wall"),
                    wall_connection_point=pd_.get("wall_connection_point"),
                    wall_distance_pts=pd_.get("wall_distance_pts"),
                    num_symbol_drawings=pd_.get("num_symbol_drawings", 0),
                    symbol_attached=pd_.get("symbol_attached", False),
                    confidence=pd_.get("confidence", "low"),
                    likely_title_block_artifact=pd_.get("likely_title_block_artifact"),
                    source_sheet=pd_.get("source_sheet"),
                )
                all_devices.append(device)
                device_id += 1

        # Device types found (vocabulary-filtered).
        device_types_found = sorted({d.device_type for d in all_devices})
        vocab_set = set(DEVICE_TYPE_VOCABULARY)
        device_types_found = [t for t in device_types_found if t in vocab_set]

        total_devices = len(all_devices)
        verified_count = sum(1 for d in all_devices if d.confidence == "high")

        # Overall confidence note.
        if self.is_scanned():
            dq_confidence = "low"
            dq_note = (
                "Scanned/image PDF: no symbol graphics or wall relationships "
                "could be extracted. devices[] is empty."
            )
        elif total_devices == 0:
            dq_confidence = "low"
            dq_note = "No devices detected on any page."
        else:
            high = sum(1 for d in all_devices if d.confidence == "high")
            med = sum(1 for d in all_devices if d.confidence == "medium")
            if high == total_devices:
                dq_confidence = "high"
            elif high + med == total_devices:
                dq_confidence = "medium"
            else:
                dq_confidence = "low"
            dq_note = (
                f"{total_devices} device(s) detected across "
                f"{len(pages_with_devices)} page(s). "
                f"{high} high-confidence, {med} medium-confidence, "
                f"{total_devices - high - med} low-confidence."
            )

        data_quality = DataQuality(
            verified_device_count=verified_count,
            confidence_overall=dq_confidence,
            note=dq_note,
        )

        result = PDFDeviceExtractionResult(
            source_file=self.source_file,
            source_url=self.source_url,
            total_pages=self.total_pages,
            total_devices=total_devices,
            coordinate_system=COORDINATE_SYSTEM,
            page_size=list(self._page_size) if self._page_size else [],
            extraction_method=self._extraction_method(),
            legend_sheets_found=legend_sheets_found,
            symbol_legend_sheets=[
                SymbolLegendEntry(
                    device_type=e["device_type"],
                    symbol_description=e["symbol_description"],
                    source_sheet=e["source_sheet"],
                    source_page=e["source_page"],
                )
                for e in legend_entries
            ],
            pages_with_devices=pages_with_devices,
            device_types_found=device_types_found,
            data_quality=data_quality,
            limitations=limitations,
            devices=all_devices,
        )

        return result.to_dict()

    # -- save ---------------------------------------------------------------

    def save_json(self, output_path: str, indent: int = 2) -> None:
        """Save the full extraction result as JSON to ``output_path``."""
        result = self.extract_all()
        import json

        with open(output_path, "w", encoding="utf-8") as f:
            json.dump(result, f, indent=indent)
