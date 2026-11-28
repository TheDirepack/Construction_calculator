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

import json
import math
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np


# Map NECA 100-2013 floor-plan device sections -> sampling groups. The groups
# only balance which kinds of glyph get placed; every placed symbol is labeled
# with its exact catalog device (label_schema.NECA_DEVICE), which is what the
# detector learns. Non-device sections (Raceways, Schematic/One-Line,
# Switchboard Meters) are excluded unless explicitly overridden in
# class_maps/neca_symbol_overrides.json.
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

from .label_schema import NECA_DEVICE, device_class  # noqa: E402

DEFAULT_NECA_OVERRIDES_PATH = (
    Path(__file__).resolve().parent.parent / 'class_maps' / 'neca_symbol_overrides.json'
)

ANNOTATION_SECTIONS = frozenset({
    'Luminaires - Orientation Modifiers',
    'Luminaires - Extended Modifiers',
    'Luminaires - Emergency Modifiers',
})

SAMPLING_GROUPS = (
    'fire_alarm',
    'junction_box',
    'luminaire',
    'other_device',
    'receptacle',
    'switch_sensor',
)

# Libraries of generated (not real-drawing) symbols, and the most a device's
# placements may use them when real drawings of that device exist.
SYNTHETIC_SOURCES = frozenset({'synthetic_catalog_symbols'})
SYNTHETIC_SHARE = 0.35

CLASS_SAMPLING_WEIGHTS = {
    'receptacle': 0.28,
    'luminaire': 0.22,
    'switch_sensor': 0.18,
    'fire_alarm': 0.14,
    'junction_box': 0.08,
    'other_device': 0.10,
}

_FAMILY_TO_SAMPLING_GROUP: dict[str, str] = {
    'receptacle': 'receptacle',
    'switch': 'switch_sensor',
    'sensor': 'switch_sensor',
    'control_relay': 'switch_sensor',
    'luminaire': 'luminaire',
    'fire_alarm_device': 'fire_alarm',
    'junction_box': 'junction_box',
}

_DEVICE_TO_V7: dict[tuple[str, str], tuple[str, str]] = {}
for _v7_pair, _cat_dev in NECA_DEVICE.items():
    _DEVICE_TO_V7.setdefault(_cat_dev, _v7_pair)


@dataclass(frozen=True)
class NecaGlyph:
    symbol_id: str
    code: str
    name: str
    section: str
    canonical_class: str   # sampling group (SAMPLING_GROUPS), not a training label
    device_type: str  # v7 taxonomy device_type (e.g. receptacle, switch, light_fixture, fire_alarm, data_outlet, ...)
    subtype: str      # v7 taxonomy subtype (e.g. duplex, quad, gfci_duplex, three_way, dimmer, three_way_dimmer, ...)
    alpha: np.ndarray  # uint8 (H, W) cropped tight ink alpha mask
    family: str = ''  # catalog device = the training label (label_schema.NECA_DEVICE)
    variant: str = ''


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

    # Telecom jacks are data outlets; AV equipment (intercom, volume
    # control, equipment rack, CATV, microphone) is not a data jack.
    if 'teledata' in s:
        if 'telephone' in n and ('data' in n or 'combo' in n or 'combination' in n):
            return 'data_outlet', 'data_telephone'
        if 'telephone' in n or 'phone' in n:
            return 'data_outlet', 'telephone'
        return 'data_outlet', 'data'
    if 'communications' in s:
        if 'speaker' in n:
            return 'speaker', 'speaker'
        return 'other_device', 'unspecified'

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


def _parse_override_target(sym_id: str, value: object) -> tuple[str, str] | None:
    """Validate and normalize one NECA override target into ``(family, variant)`` or ``None``."""
    if value is None:
        return None
    if isinstance(value, str):
        family, sep, variant = value.strip().partition('.')
        if not sep:
            raise ValueError(f'Invalid NECA override device for {sym_id!r}: {value!r}')
    elif isinstance(value, (tuple, list)) and len(value) == 2:
        family, variant = str(value[0]).strip(), str(value[1]).strip()
    else:
        raise ValueError(f'Invalid NECA override value for {sym_id!r}: {value!r}')
    if device_class(family, variant) is None:
        raise ValueError(
            f'NECA override for {sym_id!r} names unknown or inactive catalog device: '
            f'{family}.{variant}'
        )
    return family, variant


def _normalize_overrides(overrides: Mapping[str, object]) -> dict[str, tuple[str, str] | None]:
    """Normalize either a raw overrides JSON payload or a flat ``sym_id -> target`` mapping."""
    raw: Mapping[str, object] = overrides
    if 'overrides' in overrides and isinstance(overrides['overrides'], Mapping):
        raw = overrides['overrides']
    return {str(sym_id): _parse_override_target(str(sym_id), val) for sym_id, val in raw.items()}


def load_neca_overrides(path: Path | str | None = None) -> dict[str, tuple[str, str] | None]:
    """Load and validate NECA symbol overrides from JSON.

    When ``path`` is omitted, loads ``DEFAULT_NECA_OVERRIDES_PATH`` if present
    (returning an empty mapping if the overrides file does not exist).
    """
    if path is None:
        if not DEFAULT_NECA_OVERRIDES_PATH.is_file():
            return {}
        target_path = DEFAULT_NECA_OVERRIDES_PATH
    else:
        target_path = Path(path)
        if not target_path.is_file():
            raise FileNotFoundError(f'NECA overrides file not found at {target_path}')
    payload = json.loads(target_path.read_text(encoding='utf-8'))
    if not isinstance(payload, Mapping):
        raise ValueError(f'Expected JSON object in {target_path}')
    return _normalize_overrides(payload)


def resolve_neca_device(
    sym_id: str,
    section: str,
    name: str,
    overrides: Mapping[str, object] | None = None,
) -> tuple[str, str] | None:
    """Resolve a NECA 100-2013 symbol to an active catalog ``(family, variant)`` or ``None``.

    Entries in ``overrides`` (defaulting to ``class_maps/neca_symbol_overrides.json``)
    take precedence over section filtering and ``map_neca_to_v7``, supporting both
    explicit ``null`` exclusion and cross-section symbol mappings.
    """
    override_map = load_neca_overrides() if overrides is None else _normalize_overrides(overrides)
    if sym_id in override_map:
        return override_map[sym_id]

    if section not in NECA_SECTION_TO_CLASS or section in ANNOTATION_SECTIONS:
        return None
    if section == 'Raceways - Boxes and Busways':
        low_name = (name or '').lower()
        if not any(k in low_name for k in ('box', 'handhole')):
            return None
    dev_type, subtype = map_neca_to_v7(section, name)
    return NECA_DEVICE.get((dev_type, subtype))


def _resolve_v7_pair(
    section: str,
    name: str,
    device: tuple[str, str] | None,
) -> tuple[str, str]:
    """Return compatible ``(device_type, subtype)`` metadata/placement hints for a NECA symbol."""
    dev_type, subtype = map_neca_to_v7(section, name)
    if device is None:
        return dev_type, subtype
    if NECA_DEVICE.get((dev_type, subtype)) == device:
        return dev_type, subtype
    if device in _DEVICE_TO_V7:
        return _DEVICE_TO_V7[device]
    if dev_type == 'light_fixture' and device[0] == 'luminaire':
        return dev_type, subtype
    return device


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
    """Loads and caches NECA 100-2013 vector SVG device symbols, grouped for
    sampling. Each glyph carries its catalog device when it names one.

    ``devices_only`` loads only those glyphs: detector training data must label
    every placed symbol with a device, while self-supervised pretraining wants
    every shape.
    """

    def __init__(self, library_root: Path, *, render_height: int = 128,
                 include_modifiers: bool = True, devices_only: bool = False,
                 overrides_path: Path | str | None = None) -> None:
        self.library_root = Path(library_root)
        meta_path = self.library_root / 'symbols.json'
        if not meta_path.is_file():
            raise FileNotFoundError(f'NECA symbols.json not found at {meta_path}')
        meta = json.loads(meta_path.read_text())
        self.by_class: dict[str, list[NecaGlyph]] = {c: [] for c in SAMPLING_GROUPS}
        overrides = load_neca_overrides(overrides_path)

        entries = meta.get('symbols', [])
        # Many NECA numbered-row SVGs contain two or more *alternatives*
        # inside one cell. Never place that composite as one labeled device
        # when individually extracted variants are available.
        split_rows = {
            (entry.get('section'), entry.get('code'))
            for entry in entries if entry.get('kind') == 'printed_alternative'
        }
        for entry in entries:
            sym_id = entry['id']
            sec = entry.get('section', '')
            name = entry.get('name', '')
            kind = entry.get('kind', 'numbered_row')
            if not include_modifiers and 'modifier' in sec.lower():
                continue

            if sym_id in overrides:
                device = overrides[sym_id]
                if device is None:
                    continue
            else:
                cls = NECA_SECTION_TO_CLASS.get(sec)
                if cls is None or sec in ANNOTATION_SECTIONS:
                    continue
                if kind == 'numbered_row' and (sec, entry.get('code')) in split_rows:
                    continue
                # Mounting-row figures are arrays of different mountings/shapes;
                # only the isolated round/rectangular figure variants are devices.
                if sec == 'Luminaires - Mounting Modifiers' and kind != 'printed_alternative':
                    continue
                # Diagram annotations, raceway poles and busways are not boxes.
                if sec == 'Raceways - Boxes and Busways':
                    low_name = name.lower()
                    if not any(k in low_name for k in ('box', 'handhole')):
                        continue
                device = resolve_neca_device(sym_id, sec, name, overrides=overrides)

            if device is None and devices_only:     # names no specific device
                continue
            cls = (
                _FAMILY_TO_SAMPLING_GROUP.get(device[0], 'other_device')
                if device is not None
                else NECA_SECTION_TO_CLASS.get(sec, 'other_device')
            )
            dev_type, subtype = _resolve_v7_pair(sec, name, device)
            device_pair = device or ('', '')
            svg_path = self.library_root / entry['file']
            if not svg_path.is_file():
                continue
            alpha = _rasterize_svg_alpha(svg_path, render_height=render_height)
            if alpha is None:
                continue
            self.by_class[cls].append(NecaGlyph(
                symbol_id=sym_id,
                code=entry.get('code', ''),
                name=name,
                section=sec,
                canonical_class=cls,
                device_type=dev_type,
                subtype=subtype,
                alpha=alpha,
                family=device_pair[0],
                variant=device_pair[1],
            ))

        self.active_classes = [c for c in SAMPLING_GROUPS if self.by_class[c]]
        if not self.active_classes:
            raise ValueError(f'No usable NECA device SVGs loaded from {library_root}')
        w = np.array([CLASS_SAMPLING_WEIGHTS[c] for c in self.active_classes], dtype=np.float64)
        self.class_probs = w / w.sum()
        self.devices_only = devices_only
        self.extra_by_source: dict[str, list[NecaGlyph]] = {}
        self.library_share = 0.5
        self.by_device: dict[str, list[NecaGlyph]] = {}
        for pool in self.by_class.values():
            for glyph in pool:
                if glyph.family:
                    self.by_device.setdefault(f'{glyph.family}.{glyph.variant}', []).append(glyph)

    def add_glyphs(self, glyphs: list[NecaGlyph], *, library_share: float | None = None) -> None:
        """Add other drawings: crops of verified labels, legend sheets, CAD blocks.

        Detector training (``devices_only``) needs a catalog device on every
        glyph; they join that device's pool. For pretraining, any drawing may
        be added, labelled or not: ``sample`` then draws from these added
        drawings with probability ``library_share`` (each source library
        equally often), else from NECA as before.
        """
        for glyph in glyphs:
            if not glyph.family and self.devices_only:
                raise ValueError('added glyphs must name a catalog device')
            if glyph.family:
                self.by_device.setdefault(f'{glyph.family}.{glyph.variant}', []).append(glyph)
            if not self.devices_only:
                self.extra_by_source.setdefault(glyph.section.split(':', 1)[0], []).append(glyph)
        if library_share is not None:
            if not 0 <= library_share <= 1:
                raise ValueError('library_share must be in [0,1]')
            self.library_share = float(library_share)

    def sample(self, rng: np.random.Generator) -> NecaGlyph:
        if self.devices_only:
            # Detector training: every device equally often (then any of its
            # drawings), so rare devices are not drowned out by receptacles.
            # Generated drawings fill devices with no real drawings, but
            # where real ones exist they get at most SYNTHETIC_SHARE of picks.
            keys = sorted(self.by_device)
            pool = self.by_device[keys[int(rng.integers(len(keys)))]]
            synthetic = [g.section.split(':', 1)[0] in SYNTHETIC_SOURCES for g in pool]
            if any(synthetic) and not all(synthetic):
                want = bool(rng.random() < SYNTHETIC_SHARE)
                pool = [g for g, syn in zip(pool, synthetic) if syn == want]
            return pool[int(rng.integers(len(pool)))]
        if self.extra_by_source and rng.random() < self.library_share:
            sources = sorted(self.extra_by_source)
            pool = self.extra_by_source[sources[int(rng.integers(len(sources)))]]
            return pool[int(rng.integers(len(pool)))]
        cls = self.active_classes[int(rng.choice(len(self.active_classes), p=self.class_probs))]
        pool = self.by_class[cls]
        return pool[int(rng.integers(len(pool)))]

    def summary(self) -> dict[str, int]:
        return {c: len(self.by_class[c]) for c in self.active_classes}

    @property
    def families(self) -> list[str]:
        """Catalog device families this bank places (for completeness claims)."""
        return sorted({key.split('.', 1)[0] for key in self.by_device})

    @property
    def devices(self) -> list[str]:
        """Training classes (``family.variant``) this bank can place."""
        return sorted(self.by_device)


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


# --------------------------------------------------------------------------- symbol size

def sample_symbol_px(rng: np.random.Generator, lo: float, hi: float, *,
                     large_px: float | None = None, large_share: float = 0.0) -> float:
    """Long side of one placed symbol, in page pixels.

    Log-uniform over ``[lo, hi]``; with ``large_share`` > 0 that share of
    draws is log-uniform over ``[large_px, hi]`` instead and the rest over
    ``[lo, large_px]``. Real 150-DPI sheets draw devices up to ~115 px, and a
    detector that never saw 90-140 px symbols misses them (a troffer scored
    0.99 at 85 px, 0.37 at 114 px, ~0 at 130 px). ``large_share=0`` is the
    old single draw, so existing seeds place the same sizes.
    """
    if not 0 < lo <= hi:
        raise ValueError('invalid symbol size range')
    if not 0 <= large_share <= 1:
        raise ValueError('large_share must be in [0, 1]')
    if large_share <= 0 or large_px is None or not lo < large_px < hi:
        return float(np.exp(rng.uniform(math.log(lo), math.log(hi))))
    a, b = (large_px, hi) if rng.random() < large_share else (lo, large_px)
    return float(np.exp(rng.uniform(math.log(a), math.log(b))))


# --------------------------------------------------------------------------- panelboard context

# A bare dark bar is what the model learned "panelboard" to be: a lone black
# 60-75 x 20 px rectangle scored 0.66, and so did dashes of bold match lines
# and ADA wall bars. Real panels sit on a wall WITH a tag ("LP-1", "PANEL A").
# These variants get a tag beside most placements and a fixed, lower wall
# probability (placement_policy's 5:1 wall odds made ~9 of 10 a bar beside a
# wall), so a bar beside a wall is not by itself the panelboard pattern.
PANEL_CONTEXT_VARIANTS = frozenset({('panelboard', 'panelboard'), ('panelboard', 'switchboard_switchgear')})
PANEL_WALL_PROBABILITY = 0.45
PANEL_TAG_PROBABILITY = {'wall': 0.85, 'free': 0.65}
_PANEL_TAG_PREFIXES = ('LP', 'PP', 'HP', 'DP', 'RP', 'LA', 'HA', 'PNL', 'EP', 'LV', 'MDP', 'CP')


def panel_tag_text(rng: np.random.Generator) -> str:
    """A panel designation as drawn beside a panelboard."""
    kind = int(rng.integers(6))
    prefix = _PANEL_TAG_PREFIXES[int(rng.integers(len(_PANEL_TAG_PREFIXES)))]
    letter = 'ABCDEFGH'[int(rng.integers(8))]
    num = int(rng.integers(1, 10))
    if kind == 0:
        return f'PANEL {letter}'
    if kind == 1:
        return f'PANEL "{prefix}{num}"'
    if kind == 2:
        return f'{prefix}-{num}{letter if rng.random() < .3 else ""}'
    if kind == 3:
        return f'{prefix}{num}'
    if kind == 4:
        return f'{prefix}-{num}{letter}'
    return f'{letter}{num}'


def _text_alpha(text: str, height: float, rng: np.random.Generator, *, ss: int = 3) -> np.ndarray:
    """Anti-aliased text ink mask, tight crop, about ``height`` px tall."""
    fonts = (cv2.FONT_HERSHEY_SIMPLEX, cv2.FONT_HERSHEY_PLAIN, cv2.FONT_HERSHEY_DUPLEX,
             cv2.FONT_HERSHEY_COMPLEX_SMALL)
    font = fonts[int(rng.integers(len(fonts)))]
    thick = max(1, int(round(ss * rng.uniform(.7, 1.6) * max(1.0, height / 12.0))))
    (_, th), _ = cv2.getTextSize(text, font, 1.0, thick)
    scale = height * ss / max(th, 1)
    (tw, th), base = cv2.getTextSize(text, font, scale, thick)
    canvas = np.zeros((th + base + 4 * ss, tw + 4 * ss), np.uint8)
    cv2.putText(canvas, text, (2 * ss, th + 2 * ss), font, scale, 255, thick, cv2.LINE_AA)
    small = cv2.resize(canvas, (max(1, canvas.shape[1] // ss), max(1, canvas.shape[0] // ss)),
                       interpolation=cv2.INTER_AREA)
    ys, xs = np.nonzero(small > 18)
    if len(xs) == 0:
        return small
    return np.ascontiguousarray(small[ys.min():ys.max() + 1, xs.min():xs.max() + 1])


def _blend_alpha(page: np.ndarray, x0: int, y0: int, alpha: np.ndarray, ink_col) -> None:
    """Darken ``page`` with ink ``alpha`` at (x0, y0), clipped to the page."""
    h, w = page.shape[:2]
    ah, aw = alpha.shape
    ax0, ay0 = max(0, -x0), max(0, -y0)
    px0, py0 = max(0, x0), max(0, y0)
    px1, py1 = min(w, x0 + aw), min(h, y0 + ah)
    if px1 <= px0 or py1 <= py0:
        return
    a = (alpha[ay0:ay0 + (py1 - py0), ax0:ax0 + (px1 - px0)].astype(np.float32) / 255.0)[:, :, None]
    roi = page[py0:py1, px0:px1].astype(np.float32)
    ink = np.asarray(ink_col, np.float32).reshape(1, 1, 3)
    page[py0:py1, px0:px1] = np.clip(roi * (1.0 - a) + np.minimum(roi, ink) * a, 0, 255).astype(np.uint8)


def _place_panel_tag(page, rng, box, normal, occupied, wall_int, ink_col) -> dict | None:
    """Draw a panel tag beside ``box``: away from the wall when ``normal``
    (wall -> open side) is given, else on a random side. Returns its record."""
    h, w = page.shape[:2]
    x0, y0, x1, y1 = box
    text = panel_tag_text(rng)
    alpha = _text_alpha(text, float(rng.uniform(8.0, 16.0)), rng)
    th, tw = alpha.shape
    gap = float(rng.uniform(3.0, 12.0))
    cx, cy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
    sides = ['right', 'left', 'below', 'above']
    if normal is not None:
        nx, ny = normal
        first = (('right' if nx > 0 else 'left') if abs(nx) >= abs(ny)
                 else ('below' if ny > 0 else 'above'))
        # beside the panel along the wall, or out in the room
        rest = [s for s in sides if s != first]
        order = [first] + [rest[int(i)] for i in rng.permutation(len(rest))]
    else:
        order = [sides[int(i)] for i in rng.permutation(len(sides))]
    for side in order:
        if side == 'right':
            tx, ty = x1 + gap, cy - th / 2.0
        elif side == 'left':
            tx, ty = x0 - gap - tw, cy - th / 2.0
        elif side == 'below':
            tx, ty = cx - tw / 2.0, y1 + gap
        else:
            tx, ty = cx - tw / 2.0, y0 - gap - th
        ix, iy = int(round(tx)), int(round(ty))
        if ix < 2 or iy < 2 or ix + tw > w - 2 or iy + th > h - 2:
            continue
        tbox = [float(ix), float(iy), float(ix + tw), float(iy + th)]
        if _overlaps(tbox, occupied, margin=2.0):
            continue
        if _rect_sum(wall_int, ix, iy, ix + tw, iy + th) > 0:
            continue
        _blend_alpha(page, ix, iy, alpha, ink_col)
        return {'text': text, 'bbox_xyxy': [round(v, 2) for v in tbox], 'side': side}
    return None


# --------------------------------------------------------------------------- procedural hard negatives

# Unlabelled look-alikes of what fired on blank sheets: bold dashed match /
# limit-of-work lines (rows of equal dashes, aspect 3-6:1), wall stubs and
# ADA grab bars beside walls, long solid bars, filled column squares,
# concentric circles, tree / shrub outlines, plumbing outlines (drinking
# fountain) and small dimension text. Drawn ONLY on composites whose
# background is attested symbol-free (complete for every class), so every
# distractor pixel is known background. Shapes that ARE catalog symbols are
# avoided: no panelboard-shaped bar (aspect 2.5-5) stands alone; plain squares
# with a circle (high bay) or rectangles with diagonals (troffer) are not drawn.
HARD_NEGATIVE_KINDS: dict[str, float] = {
    'dash_row': .22,
    'wall_stub': .12,
    'grab_bar': .08,
    'solid_bar': .08,
    'column_squares': .12,
    'concentric_circles': .08,
    'tree': .10,
    'plumbing_outline': .05,
    'dimension_text': .15,
}
_DIM_TEXTS = ("12'-6\"", "3'-0\"", "10'-0\"", "4'-8 1/2\"", "2'-10\"", '1/A-501', 'TYP.', 'EQ', 'EQ.',
              'A.F.F.', 'SIM.', '101', '204A', 'W2', 'NOTE 3', '(E)', 'N.I.C.', 'C.L.', '48"', '36" MIN.',
              'CLR.', 'UP', 'DN', 'B.O.', 'T.O.S.', '7', '12', 'MATCH LINE', 'LIMIT OF WORK', 'ADA')


class _Stamp:
    """Supersampled local ink canvas for one distractor."""

    SS = 3

    def __init__(self, w: float, h: float):
        self.w, self.h = max(2, int(math.ceil(w))), max(2, int(math.ceil(h)))
        self.ink = np.zeros((self.h * self.SS, self.w * self.SS), np.uint8)

    def p(self, x, y):
        return int(round(x * self.SS)), int(round(y * self.SS))

    def t(self, px: float) -> int:
        return max(1, int(round(px * self.SS)))

    def poly(self, pts, *, fill=False, closed=True, t=1.5):
        arr = np.array([self.p(*q) for q in pts], np.int32)
        if fill:
            cv2.fillPoly(self.ink, [arr], 255, cv2.LINE_AA)
        else:
            cv2.polylines(self.ink, [arr], closed, 255, self.t(t), cv2.LINE_AA)

    def circle(self, c, r, *, fill=False, t=1.5):
        cv2.circle(self.ink, self.p(*c), max(1, int(round(r * self.SS))), 255,
                   -1 if fill else self.t(t), cv2.LINE_AA)

    def line(self, a, b, t=1.5):
        cv2.line(self.ink, self.p(*a), self.p(*b), 255, self.t(t), cv2.LINE_AA)

    def alpha(self) -> np.ndarray:
        return cv2.resize(self.ink, (self.w, self.h), interpolation=cv2.INTER_AREA)


def _rotated_rect(cx, cy, length, thick, ux, uy):
    """Corners of a length x thick rectangle centred at (cx, cy) along (ux, uy)."""
    vx, vy = -uy, ux
    hl, ht = length / 2.0, thick / 2.0
    return [(cx - ux * hl - vx * ht, cy - uy * hl - vy * ht), (cx + ux * hl - vx * ht, cy + uy * hl - vy * ht),
            (cx + ux * hl + vx * ht, cy + uy * hl + vy * ht), (cx - ux * hl + vx * ht, cy - uy * hl + vy * ht)]


def _stamp_polys(polys: list[list[tuple[float, float]]], *, fill=True, t=1.5) -> tuple[np.ndarray, int, int]:
    """Rasterise page-coordinate polygons into (alpha, x0, y0)."""
    pts = np.array([q for poly in polys for q in poly], np.float64)
    x0, y0 = int(math.floor(pts[:, 0].min())) - 2, int(math.floor(pts[:, 1].min())) - 2
    x1, y1 = pts[:, 0].max() + 2, pts[:, 1].max() + 2
    st = _Stamp(x1 - x0, y1 - y0)
    for poly in polys:
        st.poly([(x - x0, y - y0) for x, y in poly], fill=fill, t=t)
    return st.alpha(), x0, y0


def _draw_kind(kind: str, rng: np.random.Generator, page_shape, wall_sites) -> list[tuple[np.ndarray, int, int]] | None:
    """One distractor as stamps [(alpha, x0, y0), ...] in page coordinates, or None."""
    h, w = page_shape[:2]

    def free_xy(margin):
        if w <= 2 * margin + 4 or h <= 2 * margin + 4:
            return None
        return float(rng.uniform(margin, w - margin)), float(rng.uniform(margin, h - margin))

    if kind == 'dash_row':
        # bold dashed match / limit-of-work line: equal dashes, aspect 3-6:1
        dash = float(rng.uniform(18.0, 80.0))
        thick = float(np.clip(dash / rng.uniform(3.0, 6.0), 3.0, 22.0))
        gap = dash * float(rng.uniform(.35, 1.1))
        n = int(rng.integers(4, 13))
        dash_dot = rng.random() < .25          # long-short centre/limit line
        angle = 0.0 if rng.random() < .45 else (90.0 if rng.random() < .8 else float(rng.choice([45.0, 135.0])))
        ux, uy = math.cos(math.radians(angle)), math.sin(math.radians(angle))
        span = n * (dash + gap)
        start = free_xy(20)
        if start is None:
            return None
        sx, sy = start
        sx -= ux * span / 2
        sy -= uy * span / 2
        stamps = []
        pos = 0.0
        for i in range(n):
            seg = dash
            if dash_dot and i % 2 == 1:
                seg = max(thick, dash * .18)
            cx, cy = sx + ux * (pos + seg / 2), sy + uy * (pos + seg / 2)
            pos += seg + gap
            if not (thick < cx < w - thick and thick < cy < h - thick):
                continue
            stamps.append(_stamp_polys([_rotated_rect(cx, cy, seg, thick, ux, uy)]))
        return stamps if len(stamps) >= 3 else None

    if kind in ('wall_stub', 'grab_bar'):
        if not wall_sites:
            return None
        site = wall_sites[int(rng.integers(len(wall_sites)))]
        nx, ny = site.nx, site.ny
        norm = math.hypot(nx, ny) or 1.0
        nx, ny = nx / norm, ny / norm
        length = float(rng.uniform(30.0, 120.0))
        if kind == 'wall_stub':
            # a partition stub growing out of the wall, perpendicular to it
            thick = float(rng.uniform(4.0, 14.0))
            cx, cy = site.x + nx * (length / 2 - 1), site.y + ny * (length / 2 - 1)
            return [_stamp_polys([_rotated_rect(cx, cy, length, thick, nx, ny)])]
        # thin bar parallel to the wall a few px off it (never panel-shaped)
        thick = float(min(rng.uniform(2.0, 6.0), length / 6.0))
        off = float(rng.uniform(3.0, 14.0)) + thick / 2
        cx, cy = site.x + nx * off, site.y + ny * off
        rect = _rotated_rect(cx, cy, length, thick, -ny, nx)
        if rng.random() < .5:
            return [_stamp_polys([rect])]
        return [_stamp_polys([rect], fill=False, t=float(rng.uniform(1.0, 2.0)))]

    if kind == 'solid_bar':
        length = float(rng.uniform(30.0, 120.0))
        thick = float(np.clip(length / rng.uniform(6.0, 14.0), 2.0, 14.0))
        c = free_xy(length)
        if c is None:
            return None
        ux, uy = (1.0, 0.0) if rng.random() < .5 else (0.0, 1.0)
        return [_stamp_polys([_rotated_rect(c[0], c[1], length, thick, ux, uy)])]

    if kind == 'column_squares':
        side = float(rng.uniform(8.0, 40.0))
        n = int(rng.integers(1, 6))
        spacing = float(rng.uniform(120.0, 420.0))
        c = free_xy(side + 4)
        if c is None:
            return None
        ux, uy = (1.0, 0.0) if rng.random() < .5 else (0.0, 1.0)
        style = int(rng.integers(3))       # filled concrete / steel H column / hatched outline
        stamps = []
        for i in range(n):
            cx, cy = c[0] + ux * i * spacing, c[1] + uy * i * spacing
            if not (side < cx < w - side and side < cy < h - side):
                break
            if style == 0:
                stamps.append(_stamp_polys([_rotated_rect(cx, cy, side, side, 1.0, 0.0)]))
                continue
            st = _Stamp(side + 4, side + 4)
            lo_, hi_ = 2.0, side + 2.0
            if style == 1:                 # wide-flange steel column: two flanges and a web
                fl = max(2.0, side * .18)
                st.poly([(lo_, lo_), (hi_, lo_), (hi_, lo_ + fl), (lo_, lo_ + fl)], fill=True)
                st.poly([(lo_, hi_ - fl), (hi_, hi_ - fl), (hi_, hi_), (lo_, hi_)], fill=True)
                mid = (lo_ + hi_) / 2
                st.poly([(mid - fl / 2, lo_), (mid + fl / 2, lo_), (mid + fl / 2, hi_), (mid - fl / 2, hi_)],
                        fill=True)
                a = st.alpha()
            else:                          # hatched (masonry / concrete in section)
                sq = [(lo_, lo_), (hi_, lo_), (hi_, hi_), (lo_, hi_)]
                st.poly(sq, t=1.5)
                hatch, mask = _Stamp(side + 4, side + 4), _Stamp(side + 4, side + 4)
                for k in np.arange(-side, side, max(2.5, side / 6)):
                    hatch.line((lo_ + k, lo_), (lo_ + k + side, hi_), t=1.0)
                mask.poly(sq, fill=True)
                a = np.maximum(st.alpha(), np.minimum(hatch.alpha(), mask.alpha()))
            stamps.append((a, int(round(cx - side / 2 - 2)), int(round(cy - side / 2 - 2))))
        return stamps or None

    if kind == 'concentric_circles':
        r = float(rng.uniform(10.0, 60.0))
        c = free_xy(r + 4)
        if c is None:
            return None
        st = _Stamp(2 * r + 6, 2 * r + 6)
        cc = (r + 3, r + 3)
        t = float(rng.uniform(1.0, 2.5))
        if rng.random() < .6:          # 3-4 rings (manhole, tree trunk, survey mark)
            for k in range(int(rng.integers(3, 5))):
                st.circle(cc, r * (1 - .22 * k), t=t)
        else:                          # thick-walled pipe / round column outline
            st.circle(cc, r, t=t)
            st.circle(cc, r * float(rng.uniform(.76, .9)), t=t)
        if rng.random() < .35:
            st.circle(cc, max(1.0, r * .08), fill=True)
        return [(st.alpha(), int(c[0] - r - 3), int(c[1] - r - 3))]

    if kind == 'tree':
        r = float(rng.uniform(20.0, 70.0))
        c = free_xy(r * 1.3 + 4)
        if c is None:
            return None
        lobes = int(rng.integers(6, 15))
        amp = float(rng.uniform(.08, .22))
        th = np.linspace(0, 2 * math.pi, lobes * 12, endpoint=False)
        jitter = rng.normal(0, .03, th.shape)
        rad = r * (1 + amp * np.abs(np.sin(lobes * th / 2)) + jitter)
        size = 2 * r * (1 + amp + .15) + 8
        st = _Stamp(size, size)
        cc = size / 2
        pts = [(cc + q * math.cos(a), cc + q * math.sin(a)) for q, a in zip(rad, th)]
        t = float(rng.uniform(1.0, 2.2))
        st.poly(pts, t=t)
        if rng.random() < .7:          # branches / scribble inside
            for _ in range(int(rng.integers(3, 9))):
                a = float(rng.uniform(0, 2 * math.pi))
                q = r * float(rng.uniform(.3, .85))
                st.line((cc, cc), (cc + q * math.cos(a), cc + q * math.sin(a)), t=t * .8)
        if rng.random() < .5:
            st.circle((cc, cc), max(1.5, r * .06), fill=True)
        return [(st.alpha(), int(c[0] - cc), int(c[1] - cc))]

    if kind == 'plumbing_outline':
        bw, bh = float(rng.uniform(22.0, 50.0)), float(rng.uniform(18.0, 40.0))
        c = free_xy(max(bw, bh) + 4)
        if c is None:
            return None
        st = _Stamp(bw + 8, bh + bw * .5 + 8)
        t = float(rng.uniform(1.0, 2.0))
        x0, y0 = 4.0, 4.0
        arc = [(x0 + bw / 2 + bw / 2 * math.cos(a), y0 + bh + bw * .4 * math.sin(a))
               for a in np.linspace(0, math.pi, 16)]
        st.poly([(x0, y0), (x0 + bw, y0)] + arc + [(x0, y0)], t=t)
        cx, cy = x0 + bw / 2, y0 + bh * .7
        if rng.random() < .5:
            cv2.ellipse(st.ink, st.p(cx, cy), (int(bw * .3 * st.SS), int(bh * .3 * st.SS)), 0, 0, 360,
                        255, st.t(t), cv2.LINE_AA)
        else:
            st.circle((cx, cy), min(bw, bh) * .12, fill=True)
        return [(st.alpha(), int(c[0] - st.w / 2), int(c[1] - st.h / 2))]

    if kind == 'dimension_text':
        text = _DIM_TEXTS[int(rng.integers(len(_DIM_TEXTS)))]
        alpha = _text_alpha(text, float(rng.uniform(5.0, 12.0)), rng)
        th_, tw_ = alpha.shape
        c = free_xy(max(tw_, th_) + 40)
        if c is None:
            return None
        tx, ty = int(c[0] - tw_ / 2), int(c[1] - th_ / 2)
        stamps = [(alpha, tx, ty)]
        if rng.random() < .55:         # with its dimension line and ticks
            span = tw_ + float(rng.uniform(20.0, 160.0))
            ly = ty + th_ + float(rng.uniform(3.0, 7.0))
            st = _Stamp(span + 12, 14)
            st.line((6, 7), (span + 6, 7), t=1.0)
            for xx in (6, span + 6):
                st.line((xx - 4, 11), (xx + 4, 3), t=1.4)
            stamps.append((st.alpha(), int(c[0] - span / 2 - 6), int(ly - 7)))
        return stamps

    raise ValueError(f'unknown hard-negative kind: {kind}')


def draw_hard_negatives(
    page: np.ndarray,
    rng: np.random.Generator,
    *,
    count: int,
    avoid: list[list[float]],
    wall_sites=(),
    ink_int: np.ndarray | None = None,
    ink_bgr=None,
    kinds: Mapping[str, float] | None = None,
    margin: float = 10.0,
    max_clutter_ink: float = 0.12,
) -> list[dict]:
    """Draw ``count`` unlabelled distractors onto ``page`` (in place), never
    within ``margin`` px of an ``avoid`` box (placed symbols, tags). The caller
    guarantees the page is attested symbol-free apart from those boxes.
    Returns one record per distractor (kind and bbox) for provenance."""
    weights = dict(HARD_NEGATIVE_KINDS if kinds is None else kinds)
    names = sorted(weights)
    p = np.array([weights[k] for k in names], np.float64)
    if count <= 0 or p.sum() <= 0:
        return []
    p /= p.sum()
    h, w = page.shape[:2]
    ink = (np.asarray(ink_bgr, np.float32) if ink_bgr is not None else np.zeros(3, np.float32))
    drawn: list[list[float]] = []
    out: list[dict] = []
    attempts = 0
    while len(out) < count and attempts < count * 12:
        attempts += 1
        kind = names[int(rng.choice(len(names), p=p))]
        stamps = _draw_kind(kind, rng, page.shape, list(wall_sites))
        if not stamps:
            continue
        kept = []
        for alpha, x0, y0 in stamps:
            ah, aw = alpha.shape
            box = [float(x0), float(y0), float(x0 + aw), float(y0 + ah)]
            if x0 < 0 or y0 < 0 or x0 + aw > w or y0 + ah > h:
                continue
            if _overlaps(box, avoid, margin=margin) or _overlaps(box, drawn, margin=2.0):
                continue
            if (ink_int is not None and kind not in ('dash_row', 'wall_stub', 'grab_bar') and
                    _rect_sum(ink_int, x0, y0, x0 + aw, y0 + ah) / max(1, aw * ah) > max_clutter_ink):
                continue
            kept.append((alpha, x0, y0, box))
        if not kept or (kind == 'dash_row' and len(kept) < 3):
            continue
        col = np.clip(ink + rng.normal(0.0, 6.0, 3), 0.0, 180.0)
        for alpha, x0, y0, box in kept:
            _blend_alpha(page, x0, y0, alpha, col)
        boxes = [k[3] for k in kept]
        drawn += boxes
        union = [min(b[0] for b in boxes), min(b[1] for b in boxes),
                 max(b[2] for b in boxes), max(b[3] for b in boxes)]
        out.append({'kind': kind, 'parts': len(kept), 'bbox_xyxy': [round(v, 2) for v in union]})
    return out


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
    drawing_dpi: float | None = None,
    match_local_ink: bool = False,
    ink_reference: np.ndarray | None = None,
    tag: str = 'neca',
    precomputed_walls: tuple | None = None,
    large_px: float | None = None,
    large_share: float = 0.0,
    absolute_size: bool = False,
    max_upscale: float | None = None,
    hard_negatives: int = 0,
    distractor_log: list | None = None,
) -> tuple[np.ndarray, list[dict]]:
    """Composite NECA 100-2013 SVG devices onto a clean blueprint page with zero wall clipping.

    Sizes: ``sample_symbol_px`` over ``size_px_range`` (``large_px`` /
    ``large_share`` weight the large end). ``absolute_size`` takes the range
    as page pixels as-is (150-DPI pages, where inference runs at scale 1);
    otherwise it scales with page size (legacy) or ``drawing_dpi``.
    ``max_upscale`` caps how far a glyph is enlarged past its own pixels
    (a 40 px crop blown up to 140 px is blur, not a drawing).

    Panelboards (``PANEL_CONTEXT_VARIANTS``) get a panel tag beside most
    placements and a fixed wall probability, so the detector learns the panel
    in its context rather than "dark bar beside a wall".

    ``hard_negatives``: this many unlabelled distractors
    (``draw_hard_negatives``) are drawn after the symbols, ONLY when ``doc``
    is attested symbol-free (``verified_no_symbols``), i.e. the composite is
    completely labelled for every placed class and every distractor pixel is
    verified background. Their records go to ``distractor_log``.
    """
    if not 0 <= placement_chaos <= 1:
        raise ValueError("placement_chaos must be a probability")
    from .symbol_chaos import distort_symbol_alpha, sample_free_origin
    from .ink_style import local_drawing_ink_bgr, sized_symbol_range
    from .placement_policy import wall_placement_probability
    from .wall_alignment import (
        detect_wall_sites, wall_rotation_degrees, snap_symbol_to_wall,
    )

    page = image.copy()
    reference = image if ink_reference is None else ink_reference
    if reference.shape != image.shape or reference.dtype != image.dtype:
        raise ValueError("ink_reference must match the source drawing shape/dtype")
    h, w = page.shape[:2]
    gray = cv2.cvtColor(page, cv2.COLOR_BGR2GRAY)
    ink_mask = (gray < 175).astype(np.uint8)
    # Callers that also run the procedural compositor detect walls once on the
    # source crop (same RNG position as here) and share them.
    wall_sites, fitted_wall_mask = (precomputed_walls if precomputed_walls is not None
                                    else detect_wall_sites(gray, rng))
    wall_mask = np.maximum(extract_wall_mask(gray), fitted_wall_mask)
    ink_int = cv2.integral(ink_mask)
    wall_int = cv2.integral(wall_mask)
    has_walls = bool(wall_sites)

    # Only the non-local ink path uses a page-wide ink colour (no RNG involved).
    ink_bgr = (None if match_local_ink else
               np.percentile(page[ink_mask.astype(bool)], 10, axis=0) if ink_mask.any() else np.zeros(3))
    occupied: list[list[float]] = [list(map(float, i['bbox_xyxy'])) for i in doc.get('instances', [])]

    # Scale symbol range mildly with page resolution so 5000px sheets get appropriately sized symbols
    if drawing_dpi is not None:
        # SVG source has no authoritative physical size; choose an apparent
        # symbol size at 200dpi, then scale at the exact PDF-render DPI.
        lo_px, hi_px = sized_symbol_range(size_px_range, drawing_dpi, (h, w))
    elif absolute_size:
        lo_px, hi_px = float(size_px_range[0]), float(size_px_range[1])
    else:
        res_factor = float(np.clip(max(h, w) / 2800.0, 0.70, 1.85))
        lo_px = size_px_range[0] * res_factor
        hi_px = size_px_range[1] * res_factor
    large_at = None
    if large_px is not None and large_share > 0:
        large_at = float(large_px) * (hi_px / float(size_px_range[1]))

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
        target_px = sample_symbol_px(rng, lo_px, hi_px, large_px=large_at, large_share=large_share)
        if max_upscale is not None:
            native = float(max(glyph.alpha.shape[:2]))
            target_px = min(target_px, max(lo_px, native * float(max_upscale)))
        panel_context = (glyph.family, glyph.variant) in PANEL_CONTEXT_VARIANTS
        # A common per-page preference is modulated by how devices usually
        # mount: switches/receptacles near walls, troffers/downlights mostly
        # in free/ceiling locations. All classes retain random variation.
        # Some NECA floor-mounted receptacles have a generic v7 subtype,
        # but their source legend text still identifies floor mounting.
        placement_subtype = (
            "floor" if glyph.device_type == "receptacle" and
            "floor" in glyph.name.lower() else glyph.subtype
        )
        glyph_wall_probability = wall_placement_probability(
            glyph.device_type, placement_subtype, wall_probability
        )
        if panel_context and 0.0 < wall_probability < 1.0:
            glyph_wall_probability = PANEL_WALL_PROBABILITY
        want_wall = has_walls and (rng.random() < glyph_wall_probability)
        chaos_details = {"severity": "unchanged", "operations": [], "rejected": 0}
        placement_mode = "wall" if want_wall else "uniform"

        if want_wall and wall_sites:
            site = wall_sites[wall_cursor % len(wall_sites)]
            wall_cursor += 1
            wx, wy = site.x, site.y
            angle = wall_rotation_degrees(site.nx, site.ny)
            alpha = _resize_and_rotate_alpha(glyph.alpha, target_px, angle)
            if symbol_chaos_probability:
                alpha, chaos_details = distort_symbol_alpha(
                    alpha, rng, probability=symbol_chaos_probability,
                    strength=symbol_chaos_strength,
                )
            gh, gw = alpha.shape
            if gw + 8 >= w or gh + 8 >= h:
                continue
            # For non-orthogonal walls, an axis-aligned bounding rectangle
            # intersects the line even when the *rotated ink* does not.
            # Align the wallward ink edge and reject actual wall overlap.
            position = snap_symbol_to_wall(
                alpha, site, wall_mask, margin=4,
            )
            if position is None:
                continue
            x0, y0 = position
            box = [float(x0), float(y0), float(x0 + gw), float(y0 + gh)]
            if _overlaps(box, occupied):
                continue
            clutter = _rect_sum(ink_int, x0, y0, x0 + gw, y0 + gh) / max(1, gw * gh)
            if clutter > max_clutter_ink:
                continue

            bcx = x0 + gw / 2.0
            bcy = y0 + gh / 2.0
            contact = [float(wx), float(wy)]
            vec = [round(wx - bcx, 2), round(wy - bcy, 2)]
            is_wall = True
            wall_normal = (float(site.nx), float(site.ny))
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
            wall_normal = None

        # Blend cleanly onto the clean blueprint (before any noise/compression!)
        roi = page[y0:y0 + gh, x0:x0 + gw].astype(np.float32)
        a = (alpha.astype(np.float32) / 255.0)[:, :, None]
        ink_col = (
            np.asarray(local_drawing_ink_bgr(reference, box), dtype=np.float32)
            if match_local_ink else
            np.clip(ink_bgr + rng.normal(0.0, 6.0, 3), 0.0, 180.0).astype(np.float32)
        )
        blended = roi * (1.0 - a) + np.minimum(roi, ink_col) * a
        page[y0:y0 + gh, x0:x0 + gw] = np.clip(blended, 0.0, 255.0).astype(np.uint8)
        occupied.append(box)
        context_tag = None
        if panel_context and rng.random() < PANEL_TAG_PROBABILITY['wall' if is_wall else 'free']:
            context_tag = _place_panel_tag(page, rng, box, wall_normal, occupied, wall_int, ink_col)
            if context_tag is not None:
                occupied.append(list(context_tag['bbox_xyxy']))

        placed.append({
            'instance_id': f'neca_{tag}_{len(placed):03d}',
            # The catalog device is the label; a glyph naming no device keeps
            # its sampling group (used only by self-supervised pretraining).
            'symbol_family': glyph.family or glyph.canonical_class,
            'symbol_variant': glyph.variant or 'unspecified',
            'device_type': glyph.family or glyph.device_type,
            'neca_symbol_id': glyph.symbol_id,
            'neca_v7_device': [glyph.device_type, glyph.subtype],
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
                'drawing_dpi': drawing_dpi,
                'ink_color_bgr': [int(round(v)) for v in ink_col],
                'placement_mode': 'wall_aligned_raster' if is_wall else placement_mode,
                'wall_angle_degrees_image': round(angle, 3) if is_wall else None,
                'wall_contact_method': 'raster_line_fit' if is_wall else None,
                'wall_sampling_probability': round(glyph_wall_probability, 4),
                'context_tag': context_tag,
            },
        })

    if hard_negatives > 0 and doc.get('verified_no_symbols') is True and not doc.get('instances'):
        drng = np.random.default_rng(int(rng.integers(2 ** 63)))
        n = int(drng.integers(max(1, hard_negatives // 2), hard_negatives + hard_negatives // 2 + 1))
        records = draw_hard_negatives(
            page, drng, count=n, avoid=occupied, wall_sites=wall_sites, ink_int=ink_int,
            ink_bgr=(ink_bgr if ink_bgr is not None else
                     np.percentile(image[ink_mask.astype(bool)], 10, axis=0) if ink_mask.any() else np.zeros(3)),
        )
        if distractor_log is not None:
            distractor_log.extend(records)

    return page, placed
