"""NECA 100-2013 vector SVG symbol compositor with strict zero-wall-clipping placement.

Places real NECA 100-2013 vector SVG electrical devices onto clean blueprint pages
BEFORE any noise, blur, resolution loss, or JPEG compression is added:
  - Randomizes symbol choice and scale.
  - Detects walls via morphological line extraction + exact distance transform.
  - Strictly prevents any symbol from clipping through walls (zero wall pixels inside bbox).
  - Near a wall: shifts the symbol outward until it sits cleanly touching the outside of
    the wall, and sets the extra vector (`wall_connection_point` / `target_vector`) to the
    exact wall contact point.
  - Away from walls: places in open space with zero wall overlap and sets the extra vector
    at the symbol center (`target_vector = [0.0, 0.0]`).
"""
from __future__ import annotations

import io
import json
import math
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np


# Map NECA 100-2013 floor-plan device sections -> 6 canonical Phase 2 classes.
# Non-device sections (Raceways, Schematic/One-Line, Switchboard Meters) are excluded.
NECA_SECTION_TO_CLASS: dict[str, str] = {
    'Outlets and Receptacles': 'receptacle',
    'Switches and Sensors': 'switch_sensor',
    'Luminaires': 'luminaire',
    'Luminaires - Mounting Modifiers': 'luminaire',
    'Luminaires - Orientation Modifiers': 'luminaire',
    'Luminaires - Extended Modifiers': 'luminaire',
    'Luminaires - Emergency Modifiers': 'luminaire',
    'Fire Alarm Sensors': 'fire_alarm',
    'Fire Alarm Indicators': 'fire_alarm',
    'Fire Alarm Communications and Panels': 'fire_alarm',
    'Annex A - Alternate Fire Safety': 'fire_alarm',
    'Raceways - Boxes and Busways': 'junction_box',
    'Communications - Teledata': 'other_device',
    'Communications - Audio-Visual': 'other_device',
    'Communications - Equipment': 'other_device',
    'Security': 'other_device',
    'Nurse Call System': 'other_device',
    'Power Distribution Equipment': 'other_device',
    'Motorized and HVAC Equipment': 'other_device',
}

CANONICAL_CLASSES = (
    'fire_alarm',
    'junction_box',
    'luminaire',
    'other_device',
    'receptacle',
    'switch_sensor',
)

CLASS_SAMPLING_WEIGHTS = {
    'receptacle': 0.28,
    'luminaire': 0.22,
    'switch_sensor': 0.18,
    'fire_alarm': 0.14,
    'junction_box': 0.08,
    'other_device': 0.10,
}


@dataclass(frozen=True)
class NecaGlyph:
    symbol_id: str
    code: str
    name: str
    section: str
    canonical_class: str
    device_type: str  # v7 taxonomy device_type (e.g. receptacle, switch, light_fixture, fire_alarm, data_outlet, ...)
    subtype: str      # v7 taxonomy subtype (e.g. duplex, quad, gfci_duplex, three_way, dimmer, three_way_dimmer, ...)
    alpha: np.ndarray  # uint8 (H, W) cropped tight ink alpha mask


def map_neca_to_v7(section: str, name: str) -> tuple[str, str]:
    """Map a NECA 100-2013 symbol (section, name) to exact v7 (device_type, subtype)."""
    s = (section or '').lower()
    n = (name or '').lower()

    # Receptacles / outlets
    if 'outlet' in s or 'receptacle' in s or 'receptacle' in n:
        is_gfci = any(k in n for k in ('gfci', 'gfi', 'ground fault'))
        is_sw = 'switch' in n
        if 'quad' in n or 'quadruplex' in n or 'four' in n:
            if is_gfci and is_sw:
                return 'receptacle', 'gfci_switched_quad'
            if is_gfci:
                return 'receptacle', 'gfci_quad'
            if is_sw:
                return 'receptacle', 'switched_quad'
            return 'receptacle', 'quad'
        if 'duplex' in n:
            if is_gfci and is_sw:
                return 'receptacle', 'gfci_switched_duplex'
            if is_gfci:
                return 'receptacle', 'gfci_duplex'
            if is_sw:
                return 'receptacle', 'switched_duplex'
            return 'receptacle', 'duplex'
        if 'single' in n or 'simplex' in n:
            if is_gfci:
                return 'receptacle', 'gfci_single'
            if is_sw:
                return 'receptacle', 'switched_single'
            return 'receptacle', 'single'
        if any(k in n for k in ('special', 'range', 'dryer', '250v', '208v', 'twist', 'locking', 'clock', 'floor')):
            return 'receptacle', 'special_purpose'
        return 'receptacle', 'unspecified'

    # Switches & sensors
    if 'switch' in s or 'sensor' in s:
        if 'thermostat' in n:
            return 'thermostat', 'thermostat'
        if 'temperature' in n:
            return 'temperature_sensor', 'temperature_sensor'
        if 'occupancy' in n:
            return ('switch', 'occupancy_sensor_switch') if 'switch' in n else ('occupancy_sensor', 'occupancy_sensor')
        if 'vacancy' in n:
            return ('switch', 'vacancy_sensor_switch') if 'switch' in n else ('occupancy_sensor', 'vacancy_sensor')
        is_dim = 'dimmer' in n
        if 'four-way' in n or 'four way' in n or '4-way' in n or '4 way' in n:
            return 'switch', ('four_way_dimmer' if is_dim else 'four_way')
        if 'three-way' in n or 'three way' in n or '3-way' in n or '3 way' in n:
            return 'switch', ('three_way_dimmer' if is_dim else 'three_way')
        if is_dim:
            return 'switch', 'dimmer'
        if 'double-pole' in n or 'double pole' in n or '2-pole' in n:
            return 'switch', 'double_pole'
        if 'single-pole' in n or 'single pole' in n or '1-pole' in n:
            return 'switch', 'single_pole'
        if 'key' in n:
            return 'switch', 'key_operated'
        if 'timer' in n or 'time' in n:
            return 'switch', 'timer'
        if 'pilot' in n:
            return 'switch', 'pilot_light'
        if 'low voltage' in n or 'low-voltage' in n:
            return 'switch', 'low_voltage_control'
        if 'momentary' in n or 'pushbutton' in n:
            return 'switch', 'momentary'
        if 'scene' in n:
            return 'switch', 'scene_control'
        if 'motor' in n:
            return 'switch', 'motor_starter_switch'
        if 'sensor' in n:
            return 'switch', 'sensor_switch'
        return 'switch', 'unspecified'

    # Luminaires / light fixtures
    if 'luminaire' in s:
        if 'exit' in n:
            return 'light_fixture', 'exit_sign'
        if '2x2' in n or '2 x 2' in n:
            return 'light_fixture', '2x2'
        if '2x4' in n or '2 x 4' in n:
            return 'light_fixture', '2x4'
        if 'wall' in n or 'sconce' in n or 'bracket' in n:
            return 'light_fixture', 'wall_light'
        if 'strip' in n or 'continuous' in n:
            return 'light_fixture', 'strip'
        if 'linear' in n or 'fluorescent' in n or 'rectangular' in n:
            return 'light_fixture', 'linear'
        if 'recessed' in n or 'downlight' in n or 'incandescent' in n or 'pendant' in n or 'ceiling' in n:
            return 'light_fixture', 'downlight'
        return 'light_fixture', 'other'

    # Fire alarm
    if 'fire alarm' in s or 'fire safety' in s:
        if 'duct' in n and 'smoke' in n:
            return 'fire_alarm', 'duct_smoke_detector'
        if 'beam' in n and 'smoke' in n:
            return 'fire_alarm', 'beam_smoke_detector'
        if 'smoke' in n and ('co' in n or 'carbon' in n):
            return 'fire_alarm', 'smoke_co_detector'
        if 'smoke' in n:
            return 'fire_alarm', 'smoke_detector'
        if 'heat' in n or 'thermal' in n:
            return 'fire_alarm', 'heat_detector'
        if 'pull' in n or 'manual' in n or 'master box' in n:
            return 'fire_alarm', 'pull_station'
        if 'horn' in n and ('strobe' in n or 'visual' in n):
            return 'fire_alarm', 'horn_strobe'
        if 'speaker' in n and ('strobe' in n or 'visual' in n):
            return 'fire_alarm', 'speaker_strobe'
        if 'strobe' in n or 'visual' in n or 'beacon' in n:
            return 'fire_alarm', 'strobe'
        if 'horn' in n or 'siren' in n or 'klaxon' in n:
            return 'fire_alarm', 'horn'
        if 'speaker' in n:
            return 'fire_alarm', 'speaker'
        if 'bell' in n:
            return 'fire_alarm', 'sprinkler_bell'
        if 'annunciator' in n:
            return 'fire_alarm', 'annunciator'
        if 'panel' in n or 'facp' in n:
            return 'fire_alarm', 'control_panel'
        if 'relay' in n:
            return 'fire_alarm', 'relay_module'
        if 'test' in n:
            return 'fire_alarm', 'remote_test_station'
        return 'fire_alarm', 'module'

    # Junction boxes
    if 'box' in s:
        return 'junction_box', 'junction_box'

    # Communications / teledata / AV
    if 'teledata' in s or 'communications' in s:
        if 'speaker' in n:
            return 'speaker', 'speaker'
        if 'telephone' in n and ('data' in n or 'combo' in n or 'combination' in n):
            return 'data_outlet', 'data_telephone'
        if 'telephone' in n or 'phone' in n:
            return 'data_outlet', 'telephone'
        return 'data_outlet', 'data'

    # Security
    if 'security' in s:
        if 'camera' in n or 'cctv' in n:
            return 'security_device', 'security_camera'
        if 'card' in n or 'reader' in n:
            return 'security_device', 'access_card_reader'
        if 'contact' in n or 'magnetic' in n:
            return 'security_device', 'magnetic_door_contact'
        if 'exit' in n or 'rex' in n:
            return 'security_device', 'request_to_exit'
        if 'panic' in n or 'duress' in n:
            return 'security_device', 'panic_button'
        if 'lock' in n or 'strike' in n:
            return 'security_device', 'door_lock'
        if 'panel' in n:
            return 'security_device', 'access_control_panel'
        return 'security_device', 'door_controller'

    # HVAC / Motorized / Power
    if 'hvac' in s or 'motorized' in s:
        if 'exhaust' in n and 'fan' in n:
            return 'exhaust_fan', 'exhaust_fan'
        if 'ceiling' in n and 'fan' in n:
            return 'ceiling_fan', 'ceiling_fan'
        if 'pump' in n:
            return 'pump', 'circulator_pump'
        if 'thermostat' in n:
            return 'thermostat', 'thermostat'
        return 'hvac_device', 'equipment_other'

    return 'other_device', 'unspecified'


def _rasterize_svg_alpha(svg_path: Path, render_height: int = 128) -> np.ndarray | None:
    """Render an SVG to a tight-cropped uint8 alpha mask (255 = ink, 0 = transparent)."""
    import cairosvg

    png_bytes = cairosvg.svg2png(url=str(svg_path), output_height=render_height)
    arr = np.frombuffer(png_bytes, dtype=np.uint8)
    rgba = cv2.imdecode(arr, cv2.IMREAD_UNCHANGED)
    if rgba is None or rgba.ndim != 3:
        return None
    if rgba.shape[2] == 4:
        rgb = rgba[:, :, :3].astype(np.float32)
        a = rgba[:, :, 3].astype(np.float32) / 255.0
        # White paper + dark ink: ink strength is darkness * alpha
        lum = rgb.mean(axis=2)
        ink = np.clip((255.0 - lum) * a, 0.0, 255.0).astype(np.uint8)
    else:
        ink = (255 - cv2.cvtColor(rgba, cv2.COLOR_BGR2GRAY)).astype(np.uint8)

    ys, xs = np.nonzero(ink > 18)
    if len(xs) < 12:
        return None
    crop = ink[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    h, w = crop.shape
    # Skip extreme strip/line-only art (aspect > 6:1) that isn't a compact device symbol
    if max(h, w) / max(1, min(h, w)) > 6.0:
        return None
    return np.ascontiguousarray(crop)


class NecaSymbolBank:
    """Loads and caches NECA 100-2013 vector SVG device symbols grouped by canonical class."""

    def __init__(self, library_root: Path, *, render_height: int = 128,
                 include_modifiers: bool = True) -> None:
        self.library_root = Path(library_root)
        meta_path = self.library_root / 'symbols.json'
        if not meta_path.is_file():
            raise FileNotFoundError(f'NECA symbols.json not found at {meta_path}')
        meta = json.loads(meta_path.read_text())
        self.by_class: dict[str, list[NecaGlyph]] = {c: [] for c in CANONICAL_CLASSES}

        for entry in meta.get('symbols', []):
            sec = entry.get('section', '')
            cls = NECA_SECTION_TO_CLASS.get(sec)
            if cls is None or (not include_modifiers and 'modifier' in sec.lower()):
                continue
            # For Raceways - Boxes and Busways, keep only actual box/pole devices (skip busways/trays)
            if sec == 'Raceways - Boxes and Busways':
                low_name = entry.get('name', '').lower()
                if not any(k in low_name for k in ('box', 'pole', 'handhole')):
                    continue
            svg_path = self.library_root / entry['file']
            if not svg_path.is_file():
                continue
            alpha = _rasterize_svg_alpha(svg_path, render_height=render_height)
            if alpha is None:
                continue
            dev_type, subtype = map_neca_to_v7(sec, entry.get('name', ''))
            self.by_class[cls].append(NecaGlyph(
                symbol_id=entry['id'],
                code=entry.get('code', ''),
                name=entry.get('name', ''),
                section=sec,
                canonical_class=cls,
                device_type=dev_type,
                subtype=subtype,
                alpha=alpha,
            ))

        self.active_classes = [c for c in CANONICAL_CLASSES if self.by_class[c]]
        if not self.active_classes:
            raise ValueError(f'No usable NECA device SVGs loaded from {library_root}')
        w = np.array([CLASS_SAMPLING_WEIGHTS[c] for c in self.active_classes], dtype=np.float64)
        self.class_probs = w / w.sum()

    def sample(self, rng: np.random.Generator) -> NecaGlyph:
        cls = self.active_classes[int(rng.choice(len(self.active_classes), p=self.class_probs))]
        pool = self.by_class[cls]
        return pool[int(rng.integers(len(pool)))]

    def summary(self) -> dict[str, int]:
        return {c: len(self.by_class[c]) for c in self.active_classes}


def extract_wall_mask(gray: np.ndarray) -> np.ndarray:
    """Extract binary wall mask (uint8 0/1) of drafted horizontal, vertical, and structural walls."""
    h, w = gray.shape
    ink = (gray < 175).astype(np.uint8)
    k = max(24, min(90, max(h, w) // 55))
    long_h = cv2.morphologyEx(ink, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (k, 1)))
    long_v = cv2.morphologyEx(ink, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (1, k)))
    # Also include thick solid wall fills
    thick = cv2.morphologyEx(ink, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (5, 5)))
    walls = ((long_h | long_v | thick) > 0).astype(np.uint8)
    return walls


def _overlaps(box: list[float], boxes: list[list[float]], margin: float = 4.0) -> bool:
    x0, y0, x1, y1 = box
    for b in boxes:
        if not (x1 + margin < b[0] or b[2] + margin < x0 or y1 + margin < b[1] or b[3] + margin < y0):
            return True
    return False


def _rect_sum(integral: np.ndarray, x0: int, y0: int, x1: int, y1: int) -> int:
    return int(integral[y1, x1] - integral[y0, x1] - integral[y1, x0] + integral[y0, x0])


def _resize_and_rotate_alpha(alpha: np.ndarray, target_max_px: float, angle_deg: float) -> np.ndarray:
    ah, aw = alpha.shape
    s = target_max_px / max(1, max(ah, aw))
    nw = max(6, int(round(aw * s)))
    nh = max(6, int(round(ah * s)))
    resized = cv2.resize(alpha, (nw, nh), interpolation=cv2.INTER_AREA if s < 1.0 else cv2.INTER_LINEAR)
    if angle_deg % 360 == 0:
        rot = resized
    elif angle_deg % 360 == 90:
        rot = cv2.rotate(resized, cv2.ROTATE_90_CLOCKWISE)
    elif angle_deg % 360 == 180:
        rot = cv2.rotate(resized, cv2.ROTATE_180)
    elif angle_deg % 360 == 270:
        rot = cv2.rotate(resized, cv2.ROTATE_90_COUNTERCLOCKWISE)
    else:
        M = cv2.getRotationMatrix2D((nw / 2.0, nh / 2.0), angle_deg, 1.0)
        cos, sin = abs(M[0, 0]), abs(M[0, 1])
        rw = int(math.ceil(nh * sin + nw * cos)) + 2
        rh = int(math.ceil(nh * cos + nw * sin)) + 2
        M[0, 2] += rw / 2.0 - nw / 2.0
        M[1, 2] += rh / 2.0 - nh / 2.0
        rot = cv2.warpAffine(resized, M, (rw, rh), flags=cv2.INTER_LINEAR, borderValue=0)

    ys, xs = np.nonzero(rot > 18)
    if len(xs) == 0:
        return rot
    return np.ascontiguousarray(rot[ys.min():ys.max() + 1, xs.min():xs.max() + 1])


def composite_neca_page(
    doc: dict,
    image: np.ndarray,
    bank: NecaSymbolBank,
    rng: np.random.Generator,
    *,
    per_page: int = 24,
    wall_fraction: float = 0.60,
    size_px_range: tuple[float, float] = (20.0, 64.0),
    max_clutter_ink: float = 0.08,
    symbol_chaos_probability: float = 0.0,
    symbol_chaos_strength: float = 1.0,
    placement_chaos: float = 0.0,
    tag: str = 'neca',
) -> tuple[np.ndarray, list[dict]]:
    """Composite NECA 100-2013 SVG devices onto a clean blueprint page with zero wall clipping."""
    if not 0 <= placement_chaos <= 1:
        raise ValueError("placement_chaos must be a probability")
    from .symbol_chaos import distort_symbol_alpha, sample_free_origin

    page = image.copy()
    h, w = page.shape[:2]
    gray = cv2.cvtColor(page, cv2.COLOR_BGR2GRAY)
    ink_mask = (gray < 175).astype(np.uint8)
    wall_mask = extract_wall_mask(gray)

    ink_int = cv2.integral(ink_mask)
    wall_int = cv2.integral(wall_mask)

    wall_ys, wall_xs = np.nonzero(wall_mask > 0)
    has_walls = len(wall_xs) > 50

    # Sample candidate wall points for wall-adjacent placements
    if has_walls:
        n_cand = min(1500, len(wall_xs))
        c_idx = rng.choice(len(wall_xs), size=n_cand, replace=False)
        cand_walls = list(zip(wall_xs[c_idx].tolist(), wall_ys[c_idx].tolist()))
        rng.shuffle(cand_walls)
    else:
        cand_walls = []

    ink_bgr = np.percentile(page[ink_mask.astype(bool)], 10, axis=0) if ink_mask.any() else np.zeros(3)
    occupied: list[list[float]] = [list(map(float, i['bbox_xyxy'])) for i in doc.get('instances', [])]

    # Scale symbol range mildly with page resolution so 5000px sheets get appropriately sized symbols
    res_factor = float(np.clip(max(h, w) / 2800.0, 0.70, 1.85))
    lo_px = size_px_range[0] * res_factor
    hi_px = size_px_range[1] * res_factor

    placed: list[dict] = []
    wall_cursor = 0
    attempts = 0
    max_attempts = max(200, per_page * 60)
    wall_probability = (
        float(rng.uniform(.08, .92))
        if placement_chaos and rng.random() < placement_chaos else wall_fraction
    )

    while len(placed) < per_page and attempts < max_attempts:
        attempts += 1
        glyph = bank.sample(rng)
        target_px = float(np.exp(rng.uniform(math.log(lo_px), math.log(hi_px))))
        # Per-page wall/free ratio; original supervised callers remain fixed.
        want_wall = has_walls and (rng.random() < wall_probability)
        chaos_details = {"severity": "unchanged", "operations": [], "rejected": 0}
        placement_mode = "wall" if want_wall else "uniform"

        if want_wall and cand_walls:
            wx, wy = cand_walls[wall_cursor % len(cand_walls)]
            wall_cursor += 1
            if wx < 20 or wx >= w - 20 or wy < 20 or wy >= h - 20:
                continue

            # Determine which orthogonal side of the wall is open space (lowest wall/ink density)
            probe = max(16, int(round(target_px * 0.7)))
            dirs = [
                (0, -1, 0.0),    # above wall
                (0, 1, 180.0),   # below wall
                (-1, 0, 90.0),   # left of wall
                (1, 0, 270.0),   # right of wall
            ]
            best_dir = None
            best_score = 1e9
            for dx, dy, ang in dirs:
                px0 = max(0, min(w - 1, wx + (dx * probe if dx < 0 else (2 if dx > 0 else -probe // 2))))
                px1 = max(px0 + 1, min(w, wx + (dx * probe if dx > 0 else (-2 if dx < 0 else probe // 2))))
                py0 = max(0, min(h - 1, wy + (dy * probe if dy < 0 else (2 if dy > 0 else -probe // 2))))
                py1 = max(py0 + 1, min(h, wy + (dy * probe if dy > 0 else (-2 if dy < 0 else probe // 2))))
                area = max(1, (px1 - px0) * (py1 - py0))
                w_cnt = _rect_sum(wall_int, px0, py0, px1, py1)
                i_cnt = _rect_sum(ink_int, px0, py0, px1, py1)
                score = (w_cnt * 10.0 + i_cnt) / area
                if score < best_score:
                    best_score = score
                    best_dir = (dx, dy, ang)

            if best_dir is None or best_score > 0.25:
                continue
            nx, ny, angle = best_dir
            alpha = _resize_and_rotate_alpha(glyph.alpha, target_px, angle)
            if symbol_chaos_probability:
                alpha, chaos_details = distort_symbol_alpha(
                    alpha, rng, probability=symbol_chaos_probability,
                    strength=symbol_chaos_strength,
                )
            gh, gw = alpha.shape
            if gw + 8 >= w or gh + 8 >= h:
                continue

            # Start centered on the wall point and SHIFT outward along (nx, ny) until:
            # 1) ZERO wall pixels are inside the bounding box (no wall clipping!)
            # 2) The back edge of the box sits cleanly touching the outside of the wall!
            if ny == -1:
                x0 = int(round(wx - gw / 2.0))
                y0 = wy - gh
            elif ny == 1:
                x0 = int(round(wx - gw / 2.0))
                y0 = wy
            elif nx == -1:
                x0 = wx - gw
                y0 = int(round(wy - gh / 2.0))
            else:
                x0 = wx
                y0 = int(round(wy - gh / 2.0))

            # Shift outward up to 32 px until completely clear of the wall
            shifted = False
            max_shift = max(24, int(round(target_px * 0.6)))
            for s in range(0, max_shift + 1):
                cx0 = x0 + nx * s
                cy0 = y0 + ny * s
                cx1 = cx0 + gw
                cy1 = cy0 + gh
                if cx0 < 4 or cy0 < 4 or cx1 >= w - 4 or cy1 >= h - 4:
                    break
                if _rect_sum(wall_int, cx0, cy0, cx1, cy1) == 0:
                    # Confirm shifting 1..3 px back toward the wall actually touches the wall!
                    tx0 = max(0, cx0 - nx * 2)
                    ty0 = max(0, cy0 - ny * 2)
                    tx1 = min(w, cx1 - nx * 2)
                    ty1 = min(h, cy1 - ny * 2)
                    if _rect_sum(wall_int, tx0, ty0, tx1, ty1) > 0:
                        x0, y0 = cx0, cy0
                        shifted = True
                    break
            if not shifted:
                continue

            box = [float(x0), float(y0), float(x0 + gw), float(y0 + gh)]
            if _overlaps(box, occupied):
                continue
            clutter = _rect_sum(ink_int, x0, y0, x0 + gw, y0 + gh) / max(1, gw * gh)
            if clutter > max_clutter_ink:
                continue

            # Exact contact point on the outside surface of the wall
            bcx = x0 + gw / 2.0
            bcy = y0 + gh / 2.0
            if ny == -1:
                contact = [bcx, float(y0 + gh)]
            elif ny == 1:
                contact = [bcx, float(y0)]
            elif nx == -1:
                contact = [float(x0 + gw), bcy]
            else:
                contact = [float(x0), bcy]
            vec = [round(contact[0] - bcx, 2), round(contact[1] - bcy, 2)]
            is_wall = True
        else:
            # Free-space placement: NOT touching any wall -> extra vector in the center
            angle = float(rng.choice([0.0, 90.0, 180.0, 270.0]))
            alpha = _resize_and_rotate_alpha(glyph.alpha, target_px, angle)
            if symbol_chaos_probability:
                alpha, chaos_details = distort_symbol_alpha(
                    alpha, rng, probability=symbol_chaos_probability,
                    strength=symbol_chaos_strength,
                )
            gh, gw = alpha.shape
            if gw + 16 >= w or gh + 16 >= h:
                continue
            if placement_chaos:
                x0, y0, placement_mode = sample_free_origin(
                    rng, (h, w), (gh, gw), occupied, chaos=placement_chaos,
                )
            else:
                x0 = int(rng.integers(8, w - gw - 8))
                y0 = int(rng.integers(8, h - gh - 8))
            # Strictly ZERO wall pixels inside box AND at least 6px clearance from walls
            pad = 6
            if _rect_sum(wall_int, max(0, x0 - pad), max(0, y0 - pad), min(w, x0 + gw + pad), min(h, y0 + gh + pad)) > 0:
                continue
            box = [float(x0), float(y0), float(x0 + gw), float(y0 + gh)]
            if _overlaps(box, occupied):
                continue
            clutter = _rect_sum(ink_int, x0, y0, x0 + gw, y0 + gh) / max(1, gw * gh)
            if clutter > max_clutter_ink:
                continue

            bcx = x0 + gw / 2.0
            bcy = y0 + gh / 2.0
            contact = [round(bcx, 2), round(bcy, 2)]
            vec = [0.0, 0.0]
            is_wall = False

        # Blend cleanly onto the clean blueprint (before any noise/compression!)
        roi = page[y0:y0 + gh, x0:x0 + gw].astype(np.float32)
        a = (alpha.astype(np.float32) / 255.0)[:, :, None]
        ink_col = np.clip(ink_bgr + rng.normal(0.0, 6.0, 3), 0.0, 180.0).astype(np.float32)
        blended = roi * (1.0 - a) + np.minimum(roi, ink_col) * a
        page[y0:y0 + gh, x0:x0 + gw] = np.clip(blended, 0.0, 255.0).astype(np.uint8)
        occupied.append(box)

        placed.append({
            'instance_id': f'neca_{tag}_{len(placed):03d}',
            'symbol_family': glyph.canonical_class,
            'device_type': glyph.device_type,
            'subtype': glyph.subtype,
            'symbol_variant': glyph.symbol_id,
            'neca_code': glyph.code,
            'neca_name': glyph.name,
            'bbox_xyxy': [round(v, 2) for v in box],
            'on_wall': is_wall,
            'wall_connection_point': [round(contact[0], 2), round(contact[1], 2)],
            'target_vector': vec,
            'anchors': {
                'wall_connection_point': {
                    'xy': [round(contact[0], 2), round(contact[1], 2)],
                    'visible': True,
                    'annotated': not bool(chaos_details["operations"]),
                }
            },
            'rotation_degrees_image': angle,
            'symbol_chaos': chaos_details,
            'source': {
                'kind': 'neca_100_2013_svg_composite',
                'label_method': ('warped_bbox_only' if chaos_details["operations"]
                                 else 'known_placement_transform'),
                'target_px': round(target_px, 2),
                'placement_mode': 'wall_touching_shifted' if is_wall else placement_mode,
            },
        })

    return page, placed
