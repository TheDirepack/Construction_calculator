"""Line-snap resolution for annotated symbol boxes.

Wraps the project snapping module, adding:
 * rotated-box handling (snap to wall lines expressed in image coordinates),
 * the *extra snap vector* (origin + direction) used to bias the snap,
 * shrink-to-line for snapped boxes.
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Any

import numpy as np

from blueprint_symbols.snapping import WallSegment, resolve_snap


@dataclass
class SnapVector:
    """Extra vector (origin + direction) that biases/anchors the wall-snap search."""
    origin: tuple[float, float]
    direction: tuple[float, float]  # unoriented direction, image xy

    def unit(self) -> tuple[float, float]:
        n = math.hypot(*self.direction)
        if n < 1e-9:
            raise ValueError("snap vector direction has zero length")
        return (self.direction[0] / n, self.direction[1] / n)


def _to_wall_segments(walls: list[dict] | None) -> list[WallSegment]:
    out: list[WallSegment] = []
    for w in walls or []:
        if isinstance(w, WallSegment):
            out.append(w)
        elif isinstance(w, dict):
            out.append(WallSegment((float(w["start"][0]), float(w["start"][1])),
                                   (float(w["end"][0]), float(w["end"][1])),
                                   w.get("source", "verified_wall_centerline")))
    return out


def shrink_box_to_line(box: tuple[float, float, float, float],
                       line_start: tuple[float, float],
                       line_end: tuple[float, float],
                       max_shrink_px: float = 8.0) -> tuple[float, float, float, float]:
    """Shrink an axis-aligned box inward toward the supporting line.

    The box is pulled in along the axis normal to the nearest box edge
    until its edge sits on the line (or the max shrink is reached).
    """
    x0, y0, x1, y1 = (float(v) for v in box)
    a = np.asarray(line_start, dtype=float)
    b = np.asarray(line_end, dtype=float)
    d = b - a
    length = float(np.linalg.norm(d))
    if length < 1e-9:
        return box
    # line unit normal in image coords (y down)
    n = np.asarray([-d[1], d[0]], dtype=float) / length

    a_dot_n = float(a @ n)
    dist_x0 = x0 * n[0] + y0 * n[1] - a_dot_n
    dist_x1 = x1 * n[0] + y0 * n[1] - a_dot_n
    dist_y0 = x0 * n[0] + y0 * n[1] - a_dot_n
    dist_y1 = x0 * n[0] + y1 * n[1] - a_dot_n
    max_dist = max(abs(dist_x0), abs(dist_x1), abs(dist_y0), abs(dist_y1))
    if max_dist < 1e-9:
        return box
    shrink = min(max_shrink_px, max_dist)
    if shrink <= 1e-9:
        return box
    new_x0, new_x1 = x0, x1
    new_y0, new_y1 = y0, y1
    if abs(n[0]) >= abs(n[1]):
        new_x0 = x0 - shrink if dist_x0 > 0 else x0 + shrink
        new_x1 = x1 - shrink if dist_x1 > 0 else x1 + shrink
        if new_x0 > new_x1:
            new_x0, new_x1 = new_x1, new_x0
    else:
        new_y0 = y0 - shrink if dist_y0 > 0 else y0 + shrink
        new_y1 = y1 - shrink if dist_y1 > 0 else y1 + shrink
        if new_y0 > new_y1:
            new_y0, new_y1 = new_y1, new_y0
    return (new_x0, new_y0, new_x1, new_y1)


def resolve_snap_for_instance(instance: Any, image: np.ndarray,
                              walls: list[dict] | None = None,
                              wall_attachable: bool = False,
                              max_wall_distance_px: float | None = None,
                              snap_vector: SnapVector | None = None) -> dict:
    """Run the line-snap resolution for one instance; returns the snap dict."""
    box = tuple(instance.box.aabb())
    segs = _to_wall_segments(walls)

    # An explicit user snap_vector takes priority over the box angle so
    # oriented boxes can still bias wall snapping along a distinct direction.
    sv_axis = snap_vector.unit() if snap_vector is not None else None
    symbol_axis = None
    if instance.box.angle_deg != 0.0:
        a = math.radians(instance.box.angle_deg)
        symbol_axis = (math.cos(a), math.sin(a))

    # Prefer explicit circle_center anchor or snap_vector origin when available.
    circle_center = None
    circle_source = 'detector_bbox'
    anchors = getattr(instance, 'anchors', None) or {}
    if isinstance(anchors.get('circle_center'), dict) and anchors['circle_center'].get('xy'):
        xy = anchors['circle_center']['xy']
        circle_center = (float(xy[0]), float(xy[1]))
        circle_source = 'source_geometry'
    elif snap_vector is not None:
        circle_center = (float(snap_vector.origin[0]), float(snap_vector.origin[1]))
        circle_source = 'snap_vector_origin'

    result = resolve_snap(
        box,
        circle_center=circle_center,
        circle_source=circle_source,
        walls=segs,
        wall_attachable=wall_attachable and bool(segs),
        symbol_axis=sv_axis or symbol_axis,
        max_wall_distance_px=max_wall_distance_px,
    )
    out = result.as_dict() if hasattr(result, 'as_dict') else dict(result.__dict__)

    if out.get('kind') == 'wall_centerline_intersection' and out.get('point') is not None:
        if out.get('wall_index') is not None and 0 <= out['wall_index'] < len(segs):
            w = segs[out['wall_index']]
            shrunk = shrink_box_to_line(box, w.start, w.end, max_shrink_px=8.0)
            out['shrunk_bbox_xyxy'] = [round(v, 3) for v in shrunk]
    return out


def apply_snap_shrink(instance: Any, image: np.ndarray,
                      walls: list[dict] | None = None,
                      wall_attachable: bool = False,
                      max_wall_distance_px: float | None = None,
                      snap_vector: SnapVector | None = None) -> dict:
    """Run snap and also update the instance's bbox to the shrunk version if snapped."""
    snap = resolve_snap_for_instance(instance, image, walls, wall_attachable,
                                     max_wall_distance_px, snap_vector)
    if 'shrunk_bbox_xyxy' in snap:
        shrunk = snap['shrunk_bbox_xyxy']
        cx = (shrunk[0] + shrunk[2]) / 2.0
        cy = (shrunk[1] + shrunk[3]) / 2.0
        w = shrunk[2] - shrunk[0]
        h = shrunk[3] - shrunk[1]
        if abs(instance.box.angle_deg) <= 1e-6:
            instance.box = instance.box.__class__(cx, cy, w, h, 0.0)
        else:
            # Preserve oriented dimensions and angle; shift center toward the shrunk hull center.
            instance.box = instance.box.__class__(
                cx, cy, instance.box.w, instance.box.h, instance.box.angle_deg
            )
        snap['applied_shrink'] = True
    return snap
