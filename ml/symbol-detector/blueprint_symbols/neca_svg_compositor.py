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
    alpha: np.ndarray  # uint8 (H, W) cropped tight ink alpha mask


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

    def __init__(self, library_root: Path, *, render_height: int = 128) -> None:
        self.library_root = Path(library_root)
        meta_path = self.library_root / 'symbols.json'
        if not meta_path.is_file():
            raise FileNotFoundError(f'NECA symbols.json not found at {meta_path}')
        meta = json.loads(meta_path.read_text())
        self.by_class: dict[str, list[NecaGlyph]] = {c: [] for c in CANONICAL_CLASSES}

        for entry in meta.get('symbols', []):
            sec = entry.get('section', '')
            cls = NECA_SECTION_TO_CLASS.get(sec)
            if cls is None:
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
            self.by_class[cls].append(NecaGlyph(
                symbol_id=entry['id'],
                code=entry.get('code', ''),
                name=entry.get('name', ''),
                section=sec,
                canonical_class=cls,
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
    tag: str = 'neca',
) -> tuple[np.ndarray, list[dict]]:
    """Composite NECA 100-2013 SVG devices onto a clean blueprint page with zero wall clipping."""
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

    while len(placed) < per_page and attempts < max_attempts:
        attempts += 1
        glyph = bank.sample(rng)
        target_px = float(np.exp(rng.uniform(math.log(lo_px), math.log(hi_px))))
        want_wall = has_walls and (rng.random() < wall_fraction)

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
            gh, gw = alpha.shape
            if gw + 16 >= w or gh + 16 >= h:
                continue
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
                    'annotated': True,
                }
            },
            'rotation_degrees_image': angle,
            'source': {
                'kind': 'neca_100_2013_svg_composite',
                'label_method': 'known_placement_transform',
                'target_px': round(target_px, 2),
                'placement_mode': 'wall_touching_shifted' if is_wall else 'free_space_center',
            },
        })

    return page, placed
