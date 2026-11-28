"""Page-level sheet-discipline gate from the PDF text layer.

The detector's own features barely separate sheet types, and the false
detections seen in real-page traces were on non-electrical sheets (landscape
plan ``L1.3``, accessibility details ``G1.1``). This module gives the page
gate its own input: the title-block sheet number and sheet title read from the
PDF text layer with PyMuPDF.

* The sheet number is the largest text matching a sheet-number pattern
  (``E1.1``, ``EP-201``, ``FA101``, ``L1.3``...) whose prefix is a known
  discipline designator, preferring the title-block zone (bottom-right corner,
  right strip, bottom strip of the *displayed* page, so rotated pages work).
* Its prefix gives the discipline (US National CAD Standard letters plus
  common electrical sub-designators ``EP``/``EL``/``FA``/``T``/``LV``/``AV``...).
* Large text near the sheet number is read as the sheet title. Reflected
  ceiling plans (``RCP``, "reflected ceiling", "ceiling plan") are
  electrical-relevant even on A/I sheets because luminaires appear on them;
  a non-electrical prefix whose title names electrical work ("lighting",
  "fire alarm"...) is also treated as electrical-relevant, at lower confidence.

``classify_pdf_page`` returns ``None`` when the page has no text layer
(scanned pages) and a result with ``electrical_relevant=None`` when there is
text but no usable sheet number or title. ``decide_gate`` never skips or
raises the cutoff of a page whose discipline is unknown.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass
import re
from pathlib import Path
from typing import Any

GATE_MODES = ("off", "raise", "skip")
DEFAULT_GATE_MODE = "off"   # user 2026-11-28: never filter non-electrical sheets out
DEFAULT_RAISED_CONFIDENCE = 0.90
DEFAULT_MIN_SHEET_CONFIDENCE = 0.75

# Sheet designator: 1-3 letters, optional separator, a number with optional
# dotted/dashed parts and an optional trailing letter (E1.1, EP-201, FA1.01,
# A101A, M-1, C3.0, E-001). Applied to whole spans/lines only, never inside
# running text, so note references ("SEE 3/A5.1") do not count.
SHEET_NUMBER_RE = re.compile(
    r"(?P<prefix>[A-Z]{1,3})\s?[-.]?\s?(?P<num>\d{1,4}(?:[.\-]\d{1,3}){0,2}[A-Z]?)"
)
_LABEL_PREFIX_RE = re.compile(
    r"^(?:SHEET|SHT|DWG|DRAWING)(?:\s*(?:NO\.?|NUMBER|#|:))*\s*[:#]?\s*"
)

# Two/three-letter designators that are not "first letter + modifier".
_SPECIAL_PREFIXES: dict[str, tuple[str, bool]] = {
    "FA": ("fire_alarm", True),
    "LV": ("low_voltage", True),
    "AV": ("audio_visual", True),
    "SY": ("security", True),
    "MEP": ("mep", True),
    "MPE": ("mep", True),
    "ME": ("mep", True),
    "FP": ("fire_protection", False),
    "FS": ("fire_protection", False),
}
# NCS discipline letters. W (distributed energy / PV) is left out on purpose:
# PV sheets carry electrical equipment, so they stay unknown -> never gated.
_LETTER_DISCIPLINES: dict[str, tuple[str, bool]] = {
    "E": ("electrical", True),
    "T": ("telecom", True),
    "G": ("general", False),
    "C": ("civil", False),
    "L": ("landscape", False),
    "S": ("structural", False),
    "A": ("architectural", False),
    "I": ("interiors", False),
    "Q": ("equipment", False),
    "F": ("fire_protection", False),
    "P": ("plumbing", False),
    "M": ("mechanical", False),
    "H": ("hazardous_materials", False),
    "V": ("survey", False),
    "B": ("geotechnical", False),
    "R": ("resource", False),
    "K": ("food_service", False),
}

_RCP_RE = re.compile(r"\bREFLECTED\s+CEILING\b|\bCEILING\s+PLAN")
# "RCP" also means reinforced concrete pipe on civil/structural sheets.
_RCP_ABBREV_RE = re.compile(r"\bRCP\b")
_RCP_ABBREV_DISCIPLINES = ("architectural", "interiors", "general")
# Device vocabulary in the whole page text. A sheet whose number says
# non-electrical but whose text names devices this often is not gated.
_DEVICE_WORDS_RE = re.compile(
    r"\b(?:RECEPTACLES?|LUMINAIRES?|LIGHT(?:ING)?\s+FIXTURES?|PANELBOARDS?|SWITCHBOARDS?|"
    r"DUPLEX|J-BOX|JUNCTION\s+BOX(?:ES)?|FIRE\s+ALARM|GFCI|GFI|BRANCH\s+CIRCUITS?|"
    r"OCCUPANCY\s+SENSORS?|DISCONNECT\s+SWITCH(?:ES)?|EMERGENCY\s+LIGHT\w*|EXIT\s+SIGNS?)\b"
)
DEVICE_WORD_GUARD = 3
_ELECTRICAL_TITLE_RE = re.compile(
    r"\bELECTRICAL\b|\bLIGHTING\b|\bPOWER\s+PLAN|\bFIRE\s+ALARM\b|\bLOW[\s-]+VOLTAGE\b|"
    r"\bTELECOM\w*|\bCOMMUNICATIONS?\b|\bTECHNOLOGY\b|\bPHOTOMETRIC\b|\bONE[\s-]+LINE\b|"
    r"\bPANEL\s+SCHEDULES?\b|\bSECURITY\s+PLAN|\bAUDIO[\s/-]+VISUAL\b|\bSIGNAL\s+SYSTEMS?\b"
)
_TITLE_SHEET_RE = re.compile(r"\bTITLE\s+SHEET\b|\bCOVER\s+SHEET\b|\bCOVER\b")


@dataclass(frozen=True)
class SheetClass:
    """Sheet discipline read from a page's title block."""

    discipline: str                     # e.g. "electrical", "landscape", "architectural_rcp", "unknown"
    electrical_relevant: bool | None    # None: unknown, never gated
    sheet_number: str | None
    sheet_title: str | None
    confidence: float                   # 0..1, confidence in ``electrical_relevant``
    reason: str

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(frozen=True)
class GatePolicy:
    """What to do with pages classified as non-electrical.

    ``mode``: ``"off"`` (default: ignore the gate; the user wants every sheet,
    electrical or not, handled by the detector itself), ``"skip"`` (return no
    detections and run no inference) or ``"raise"`` (keep detecting but use
    ``raised_confidence`` as the output cutoff). The classification is still
    recorded with each detection run.
    Only acts when the sheet classification confidence is at least
    ``min_sheet_confidence``; unknown pages always run at the caller's cutoff.
    """

    mode: str = DEFAULT_GATE_MODE
    raised_confidence: float = DEFAULT_RAISED_CONFIDENCE
    min_sheet_confidence: float = DEFAULT_MIN_SHEET_CONFIDENCE

    def __post_init__(self) -> None:
        if self.mode not in GATE_MODES:
            raise ValueError(f"gate mode must be one of {GATE_MODES}, got {self.mode!r}")
        if not 0.0 < float(self.raised_confidence) < 1.0:
            raise ValueError("raised_confidence must be in (0, 1)")
        if not 0.0 <= float(self.min_sheet_confidence) <= 1.0:
            raise ValueError("min_sheet_confidence must be in [0, 1]")

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(frozen=True)
class GateDecision:
    action: str            # "run" | "raise" | "skip"
    confidence: float      # effective output cutoff for this page
    reason: str
    sheet: dict | None
    policy: dict

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


# Title-block tokens that look like designators but are not sheet numbers.
_NOT_SHEET_PREFIXES = frozenset({"FY", "CY", "NO", "PG", "REV", "DWG", "SHT", "SEC", "DET", "TYP"})


def discipline_for_prefix(prefix: str) -> tuple[str, bool] | None:
    """``(discipline, electrical_relevant)`` for a sheet designator prefix, or None."""
    p = prefix.upper()
    if p in _NOT_SHEET_PREFIXES:
        return None
    if p in _SPECIAL_PREFIXES:
        return _SPECIAL_PREFIXES[p]
    if p[0] == "E":
        return ("electrical", True)          # E, EP, EL, ES, ED, EG, EX...
    if p[0] == "T":
        return ("telecom", True)             # T, TN, TY, TS... (title sheets fixed by title)
    if len(p) >= 2 and p[0] == "D":          # demolition: DE electrical, DM mechanical...
        inner = _LETTER_DISCIPLINES.get(p[1])
        if inner is not None:
            return inner
        return None
    if len(p) > 2:
        return None                          # AHU, MDP, RTU: equipment tags, not sheets
    return _LETTER_DISCIPLINES.get(p[0])


def _normalize(text: str) -> str:
    return re.sub(r"\s+", " ", text.replace("‐", "-").replace("–", "-")
                  .replace("—", "-")).strip().upper()


def parse_sheet_number(text: str) -> tuple[str, str] | None:
    """``(prefix, normalized sheet number)`` if ``text`` is a whole sheet designator."""
    t = _normalize(text)
    t = _LABEL_PREFIX_RE.sub("", t)
    m = SHEET_NUMBER_RE.fullmatch(t)
    if m is None:
        return None
    prefix = m.group("prefix")
    if discipline_for_prefix(prefix) is None:
        return None
    sep = "-" if "-" in t[len(prefix):len(prefix) + 2] else ""
    return prefix, f"{prefix}{sep}{m.group('num')}"


def _page_spans(page) -> list[dict]:
    """Text lines in displayed-page coordinates normalized to [0, 1]."""
    width = float(page.rect.width) or 1.0
    height = float(page.rect.height) or 1.0
    rot = page.rotation_matrix
    out: list[dict] = []
    data = page.get_text("dict")
    for block in data.get("blocks", []):
        for line in block.get("lines", []):
            spans = [s for s in line.get("spans", []) if str(s.get("text", "")).strip()]
            if not spans:
                continue
            items = [(str(s["text"]), float(s.get("size", 0.0)), s["bbox"]) for s in spans]
            # Each span, plus the whole line when it has several spans ("E" "1.1").
            # ``line`` marks one entry per text line, for counting words once.
            texts = [(t, size, bbox, len(items) == 1) for t, size, bbox in items]
            if len(items) > 1:
                x0 = min(b[0] for _, _, b in items)
                y0 = min(b[1] for _, _, b in items)
                x1 = max(b[2] for _, _, b in items)
                y1 = max(b[3] for _, _, b in items)
                joined = " ".join(t.strip() for t, _, _ in items)
                texts.append((joined, max(size for _, size, _ in items), (x0, y0, x1, y1), True))
            for text, size, bbox, is_line in texts:
                x0, y0, x1, y1 = bbox
                pts = [rot.a * x + rot.c * y + rot.e for x, y in ((x0, y0), (x1, y1))], \
                      [rot.b * x + rot.d * y + rot.f for x, y in ((x0, y0), (x1, y1))]
                dx0, dx1 = min(pts[0]), max(pts[0])
                dy0, dy1 = min(pts[1]), max(pts[1])
                out.append({
                    "text": text,
                    "size": size,
                    "cx": (dx0 + dx1) / 2.0 / width,
                    "cy": (dy0 + dy1) / 2.0 / height,
                    "box": (dx0 / width, dy0 / height, dx1 / width, dy1 / height),
                    "line": is_line,
                })
    return out


def _zone_weight(cx: float, cy: float) -> float:
    """How title-block-like a displayed position is."""
    if cx >= 0.80 and cy >= 0.75:
        return 1.0                       # bottom-right corner
    if cx >= 0.86:
        return 0.75                      # right-hand title strip
    if cy >= 0.88:
        return 0.6                       # bottom title strip
    return 0.15


def _sheet_title(spans: list[dict], number_span: dict) -> str | None:
    """Large text near the sheet number (the title block), largest first."""
    ref_size = max(1e-6, float(number_span["size"]))
    cx, cy = number_span["cx"], number_span["cy"]
    near = []
    for s in spans:
        if s is number_span or s["text"].strip() == number_span["text"].strip():
            continue
        if abs(s["cx"] - cx) > 0.20 or abs(s["cy"] - cy) > 0.30:
            continue
        if s["size"] < 0.22 * ref_size:
            continue
        text = _normalize(s["text"])
        if not re.search(r"[A-Z]{3}", text):
            continue
        near.append((-s["size"], s["cy"], s["cx"], text))
    near.sort()
    seen: list[str] = []
    for _, _, _, text in near:
        if text not in seen and not any(text in other for other in seen):
            seen.append(text)
        if len(seen) >= 12:
            break
    return " | ".join(seen) if seen else None


def classify_spans(spans: list[dict]) -> SheetClass | None:
    """Classify from pre-extracted spans (``_page_spans`` format)."""
    if not any(re.search(r"[A-Za-z0-9]", s["text"]) for s in spans):
        return None
    max_size = max(s["size"] for s in spans) or 1.0
    candidates = []
    for s in spans:
        parsed = parse_sheet_number(s["text"])
        if parsed is None:
            continue
        weight = _zone_weight(s["cx"], s["cy"])
        score = weight * s["size"]
        candidates.append((score, weight, s, parsed))
    if not candidates:
        return _classify_without_number(spans, max_size)

    candidates.sort(key=lambda c: (-c[0], c[2]["cy"], c[2]["cx"], c[3][1]))
    score, weight, span, (prefix, number) = candidates[0]
    discipline, relevant = discipline_for_prefix(prefix)  # type: ignore[misc]
    zone_sizes = [s["size"] for s in spans if _zone_weight(s["cx"], s["cy"]) >= 0.6]
    zone_max = max(zone_sizes) if zone_sizes else max_size
    size_ratio = span["size"] / max(1e-6, zone_max)

    if weight >= 1.0:
        confidence = 0.9
    elif weight >= 0.6:
        confidence = 0.8
    else:
        confidence = 0.45
    if size_ratio < 0.25:
        confidence -= 0.25
    elif size_ratio < 0.45:
        confidence -= 0.1
    # A second strong candidate with a different discipline: lower confidence.
    for other_score, _, other_span, (other_prefix, _) in candidates[1:4]:
        other = discipline_for_prefix(other_prefix)
        if other is not None and other[1] != relevant and other_score >= 0.8 * score \
                and other_span["text"].strip() != span["text"].strip():
            confidence -= 0.3
            break
    reason = f"sheet number {number} (prefix {prefix}: {discipline})"
    if len(prefix) == 1 and re.fullmatch(r"\d", number[len(prefix):].lstrip("-")):
        # "A1", "B2": detail and grid labels look like this; small sets use it
        # for sheets too. Never confident enough to gate on alone.
        confidence = min(confidence, 0.6)
        reason += "; short designator (could be a detail/grid label)"

    title = _sheet_title(spans, span)
    title_text = title or ""
    rcp = _RCP_RE.search(title_text) or (
        discipline in _RCP_ABBREV_DISCIPLINES and _RCP_ABBREV_RE.search(title_text))
    if rcp and not relevant:
        discipline, relevant = f"{discipline}_rcp", True
        reason += "; reflected ceiling plan in title"
    elif _ELECTRICAL_TITLE_RE.search(title_text) and not relevant:
        match = _ELECTRICAL_TITLE_RE.search(title_text)
        relevant = True
        confidence = min(confidence, 0.6)
        reason += f"; title mentions {match.group(0).lower()!r}, treated as electrical-relevant"
    elif prefix.startswith("T") and _TITLE_SHEET_RE.search(title_text):
        discipline, relevant = "general", False
        reason += "; title/cover sheet"
    elif title and relevant and (_ELECTRICAL_TITLE_RE.search(title_text) or _RCP_RE.search(title_text)):
        confidence += 0.05
        reason += "; title agrees"

    if relevant is False:
        page_text = " ".join(_normalize(s["text"]) for s in spans if s.get("line", True))
        device_words = len(_DEVICE_WORDS_RE.findall(page_text))
        if device_words >= DEVICE_WORD_GUARD:
            confidence = min(confidence, 0.5)
            reason += f"; page text names electrical devices {device_words}x, not trusted as non-electrical"

    return SheetClass(
        discipline=discipline,
        electrical_relevant=relevant,
        sheet_number=number,
        sheet_title=title,
        confidence=round(max(0.05, min(0.97, confidence)), 3),
        reason=reason,
    )


def _classify_without_number(spans: list[dict], max_size: float) -> SheetClass:
    """No sheet number: read only large title-block text, never conclude non-electrical."""
    large = [s for s in spans if s["size"] >= 0.5 * max_size or (
        _zone_weight(s["cx"], s["cy"]) >= 0.6 and s["size"] >= 0.3 * max_size)]
    text = " | ".join(_normalize(s["text"]) for s in large)
    if _RCP_RE.search(text) or _ELECTRICAL_TITLE_RE.search(text):
        match = _RCP_RE.search(text) or _ELECTRICAL_TITLE_RE.search(text)
        return SheetClass("electrical", True, None, None, 0.4,
                          f"no sheet number; large text mentions {match.group(0).lower()!r}")
    return SheetClass("unknown", None, None, None, 0.0, "no sheet number in the text layer")


def classify_pdf_page(page) -> SheetClass | None:
    """Classify a PyMuPDF page; ``None`` when it has no text layer (scans)."""
    return classify_spans(_page_spans(page))


def classify_pdf(path: str | Path, page_index: int) -> SheetClass | None:
    import pymupdf

    with pymupdf.open(str(path)) as doc:
        return classify_pdf_page(doc[int(page_index)])


def decide_gate(sheet: SheetClass | dict | None, policy: GatePolicy | None,
                base_confidence: float) -> GateDecision:
    """Effective cutoff/action for one page. Unknown discipline is never gated."""
    pol = policy or GatePolicy(mode="off")
    if isinstance(sheet, dict):
        sheet = SheetClass(**sheet)
    sheet_dict = sheet.to_dict() if sheet is not None else None
    base = float(base_confidence)
    if pol.mode == "off":
        return GateDecision("run", base, "sheet gate off", sheet_dict, pol.to_dict())
    if sheet is None:
        return GateDecision("run", base, "no PDF text layer: discipline unknown, not gated",
                            None, pol.to_dict())
    if sheet.electrical_relevant is None:
        return GateDecision("run", base, f"discipline unknown ({sheet.reason}), not gated",
                            sheet_dict, pol.to_dict())
    if sheet.electrical_relevant:
        return GateDecision("run", base, f"electrical-relevant sheet ({sheet.reason})",
                            sheet_dict, pol.to_dict())
    if sheet.confidence < pol.min_sheet_confidence:
        return GateDecision(
            "run", base,
            f"non-electrical at confidence {sheet.confidence:.2f} < {pol.min_sheet_confidence:.2f}, "
            f"not gated ({sheet.reason})",
            sheet_dict, pol.to_dict())
    if pol.mode == "skip":
        return GateDecision("skip", base, f"non-electrical sheet skipped ({sheet.reason})",
                            sheet_dict, pol.to_dict())
    raised = max(base, float(pol.raised_confidence))
    return GateDecision("raise", raised,
                        f"non-electrical sheet: cutoff raised to {raised:.2f} ({sheet.reason})",
                        sheet_dict, pol.to_dict())
