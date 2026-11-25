"""Rasterize DXF geometry (a block, or a layout) with OpenCV.

ezdxf turns every entity (lines, arcs, polylines, splines, hatches, text,
nested INSERTs) into flattened paths; they are drawn black on white at a
chosen pixel size. Used to cut symbols out of CAD block libraries.
"""
from __future__ import annotations

import re
from collections.abc import Iterable

import cv2
import numpy as np

TEXT_TYPES = frozenset({"TEXT", "MTEXT", "ATTRIB", "ATTDEF"})
SKIP_TYPES = frozenset({"DIMENSION", "VIEWPORT", "IMAGE", "WIPEOUT", "OLE2FRAME", "POINT", "XLINE", "RAY"})
# Layers that annotate a drawing rather than draw it (A-DIMS, M-CENTER, ANNO-TEXT, TITLE...).
ANNOTATION_LAYER = re.compile(r"dim|anno|note|title|border|frame|viewport|defpoint|center|cntr|grid|revision", re.I)


def _flat_entities(entities: Iterable, *, text: bool, depth: int = 0):
    """Primitive entities, INSERTs expanded (up to 6 levels)."""
    for e in entities:
        kind = e.dxftype()
        if kind in SKIP_TYPES or (not text and kind in TEXT_TYPES):
            continue
        try:
            if ANNOTATION_LAYER.search(e.dxf.layer or ""):
                continue
        except Exception:
            pass
        if kind == "INSERT":
            if depth >= 6:
                continue
            try:
                yield from _flat_entities(e.virtual_entities(), text=text, depth=depth + 1)
                if text:
                    yield from (a for a in e.attribs)
            except Exception:       # broken block references are common in downloads
                continue
            continue
        yield e


def entity_polylines(entities: Iterable, *, text: bool = True, distance: float = 0.0,
                     max_entities: int = 20000):
    """[(points Nx2 float, filled bool)] in drawing units. ``distance`` is
    the flattening tolerance (0: 1/1500 of the drawing's extents)."""
    from ezdxf import bbox
    from ezdxf import path as ezpath

    entities = list(entities)
    if not distance:
        ext = bbox.extents(entities, fast=True)
        span = max(ext.size.x, ext.size.y) if ext.has_data else 0.0
        distance = span / 1500 if span > 0 else 0.01
    out = []
    for n, e in enumerate(_flat_entities(entities, text=text)):
        if n >= max_entities:
            break
        kind = e.dxftype()
        try:
            if kind in TEXT_TYPES:
                from ezdxf.addons import text2path
                paths = text2path.make_paths_from_entity(e)
                filled = True
            elif kind == "HATCH":
                if not e.dxf.solid_fill:
                    continue            # pattern hatches are drawn as their outline only
                paths = ezpath.from_hatch(e)
                filled = True
            elif kind in ("SOLID", "TRACE", "3DFACE"):
                paths = [ezpath.make_path(e)]
                filled = kind != "3DFACE"
            else:
                paths = [ezpath.make_path(e)]
                filled = False
        except Exception:
            continue
        for p in paths:
            try:
                for sub in p.sub_paths():
                    pts = np.array([(v.x, v.y) for v in sub.flattening(distance, segments=4)], np.float64)
                    if len(pts) >= 2 and np.isfinite(pts).all():
                        out.append((pts, filled and len(pts) >= 3))
            except Exception:
                continue
    return out


def extents(polylines) -> tuple[float, float, float, float] | None:
    if not polylines:
        return None
    allp = np.concatenate([p for p, _ in polylines])
    x0, y0 = allp.min(axis=0)
    x1, y1 = allp.max(axis=0)
    return float(x0), float(y0), float(x1), float(y1)


def rasterize(polylines, size_px: int, *, margin: int = 4, thickness: int = 1,
              box: tuple[float, float, float, float] | None = None) -> tuple[np.ndarray, float, tuple]:
    """Gray image (ink 0 on 255) with the drawing's longer side ~size_px.
    Returns (image, pixels per unit, (x0, y0) drawing origin of the box)."""
    box = box or extents(polylines)
    if box is None:
        raise ValueError("nothing to draw")
    x0, y0, x1, y1 = box
    span = max(x1 - x0, y1 - y0)
    if span <= 0:
        raise ValueError("empty extents")
    s = (size_px - 2 * margin) / span
    w = int(np.ceil((x1 - x0) * s)) + 2 * margin
    h = int(np.ceil((y1 - y0) * s)) + 2 * margin
    img = np.full((h, w), 255, np.uint8)
    for pts, filled in polylines:
        px = np.empty_like(pts)
        px[:, 0] = (pts[:, 0] - x0) * s + margin
        px[:, 1] = (y1 - pts[:, 1]) * s + margin       # y up -> y down
        ipts = np.round(px * 16).astype(np.int32).reshape(-1, 1, 2)
        if filled:
            cv2.fillPoly(img, [ipts], 0, lineType=cv2.LINE_AA, shift=4)
        else:
            cv2.polylines(img, [ipts], False, 0, thickness, lineType=cv2.LINE_AA, shift=4)
    return img, s, (x0, y1)
