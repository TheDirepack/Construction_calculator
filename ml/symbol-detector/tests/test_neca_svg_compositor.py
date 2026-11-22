from __future__ import annotations

import cv2
import numpy as np

from blueprint_symbols.neca_svg_compositor import (
    NecaGlyph,
    _rect_sum,
    composite_neca_page,
    extract_wall_mask,
)


class _StubNecaBank:
    def __init__(self) -> None:
        alpha = np.zeros((48, 48), dtype=np.uint8)
        cv2.circle(alpha, (24, 24), 16, 255, 3)
        cv2.line(alpha, (8, 24), (40, 24), 255, 3)
        self._glyphs = [
            NecaGlyph(
                symbol_id='neca_stub_duplex',
                code='1.01',
                name='Duplex Receptacle',
                section='Outlets and Receptacles',
                canonical_class='receptacle',
                alpha=alpha,
            ),
            NecaGlyph(
                symbol_id='neca_stub_luminaire',
                code='3.01',
                name='Ceiling Luminaire',
                section='Luminaires',
                canonical_class='luminaire',
                alpha=alpha,
            ),
        ]

    def sample(self, rng: np.random.Generator) -> NecaGlyph:
        return self._glyphs[int(rng.integers(len(self._glyphs)))]


def _make_walled_blueprint(h: int = 900, w: int = 1200) -> np.ndarray:
    img = np.full((h, w, 3), 255, dtype=np.uint8)
    # Outer perimeter walls and two interior partition walls (6 px thick)
    cv2.rectangle(img, (80, 80), (w - 80, h - 80), (20, 20, 20), 6)
    cv2.line(img, (480, 80), (480, h - 80), (20, 20, 20), 6)
    cv2.line(img, (480, 450), (w - 80, 450), (20, 20, 20), 6)
    return img


def test_neca_compositor_zero_wall_clipping_and_vectors() -> None:
    img = _make_walled_blueprint()
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    wall_mask = extract_wall_mask(gray)
    wall_int = cv2.integral(wall_mask)
    assert wall_mask.sum() > 2000

    doc = {'document_id': 'test_plan', 'instances': []}
    out_img, placed = composite_neca_page(
        doc,
        img,
        _StubNecaBank(),
        np.random.default_rng(12345),
        per_page=20,
        wall_fraction=0.60,
        size_px_range=(22.0, 56.0),
        tag='unit',
    )

    assert out_img.shape == img.shape
    assert len(placed) >= 12
    wall_placed = [p for p in placed if p['on_wall']]
    free_placed = [p for p in placed if not p['on_wall']]
    assert len(wall_placed) >= 4
    assert len(free_placed) >= 3

    # Verify scale randomization actually varies placed sizes
    widths = [p['bbox_xyxy'][2] - p['bbox_xyxy'][0] for p in placed]
    assert max(widths) - min(widths) >= 8.0

    for p in placed:
        x0, y0, x1, y1 = (int(round(v)) for v in p['bbox_xyxy'])
        # Strict invariant: ZERO wall pixels inside any placed bounding box
        assert _rect_sum(wall_int, x0, y0, x1, y1) == 0, f'Wall clip detected for {p}'

        cx = 0.5 * (p['bbox_xyxy'][0] + p['bbox_xyxy'][2])
        cy = 0.5 * (p['bbox_xyxy'][1] + p['bbox_xyxy'][3])
        vx, vy = p['target_vector']
        wx, wy = p['wall_connection_point']

        if p['on_wall']:
            # Must point outward from center to a box edge touching the wall
            assert abs(vx) + abs(vy) > 5.0
            assert abs((cx + vx) - wx) < 0.5
            assert abs((cy + vy) - wy) < 0.5
            # Expanding box by 2 px must touch wall ink
            tx0, ty0 = max(0, x0 - 2), max(0, y0 - 2)
            tx1, ty1 = min(img.shape[1], x1 + 2), min(img.shape[0], y1 + 2)
            assert _rect_sum(wall_int, tx0, ty0, tx1, ty1) > 0
        else:
            # Free-space placement: extra vector is [0, 0] at center, >= 6 px from walls
            assert [vx, vy] == [0.0, 0.0]
            assert abs(wx - cx) < 0.5
            assert abs(wy - cy) < 0.5
            px0, py0 = max(0, x0 - 6), max(0, y0 - 6)
            px1, py1 = min(img.shape[1], x1 + 6), min(img.shape[0], y1 + 6)
            assert _rect_sum(wall_int, px0, py0, px1, py1) == 0
