"""Source-resolution snapping anchors for detected blueprint symbols.

This does NOT classify walls: the caller supplies verified wall *centerline*
segments, e.g. from CAD, reviewed geometry, or a future wall classifier.
Unclassified line detections must never be silently promoted to walls.

Source coordinates are continuous pixel-edge coordinates, not display pixels.
An inferred circle must come from original raster pixels or a trusted explicit
CAD anchor. The bounding-box center is only a marked provisional fallback.
"""
from __future__ import annotations

from dataclasses import dataclass, asdict
from typing import Mapping, Iterable
import math
import cv2
import numpy as np

Point = tuple[float, float]
Box = tuple[float, float, float, float]


@dataclass(frozen=True)
class WallSegment:
    start: Point
    end: Point
    source: str = 'verified_wall_centerline'

    def __post_init__(self):
        a, b = np.asarray(self.start, dtype=float), np.asarray(self.end, dtype=float)
        if a.shape != (2,) or b.shape != (2,) or not np.isfinite(a).all() or not np.isfinite(b).all():
            raise ValueError('wall segment endpoints must be finite xy pairs')
        if np.linalg.norm(b - a) < 1e-8:
            raise ValueError('wall segment has zero length')


@dataclass(frozen=True)
class CircleEstimate:
    center: Point
    radius: float
    method: str = 'hough_circle'


@dataclass(frozen=True)
class SnapResult:
    point: Point
    kind: str
    quality: str
    center: Point
    center_source: str
    wall_index: int | None = None
    wall_distance_px: float | None = None
    note: str = ''

    def as_dict(self) -> dict:
        return asdict(self)


def _validate_box(box: Box) -> None:
    if len(box) != 4 or not all(math.isfinite(float(v)) for v in box):
        raise ValueError('bbox must be four finite numbers')
    if box[2] <= box[0] or box[3] <= box[1]:
        raise ValueError('bbox must have positive area')


def detect_circle_in_box(image: np.ndarray, box: Box, *, pad_ratio: float = .10,
                         min_radius_px: float = 3.0) -> CircleEstimate | None:
    """Small-circle candidate in the *original* raster, not a page resize.

    This conservative v1 algorithm is not guaranteed on broken / partial circles
    or heavily overlapping annotation. Circle proposals use Hough; a stronger
    learned mask or robust arc fit can later replace this implementation.
    """
    _validate_box(box)
    if image.ndim == 3 and image.shape[2] >= 3:
        gray = cv2.cvtColor(image[:, :, :3], cv2.COLOR_BGR2GRAY)
    elif image.ndim == 2:
        gray = image
    else:
        raise ValueError('expected grayscale or BGR raster')
    if gray.dtype != np.uint8:
        raise ValueError('image must be uint8')
    height, width = gray.shape
    x0, y0, x1, y1 = (float(v) for v in box)
    span = max(x1-x0, y1-y0)
    pad = span * pad_ratio
    left = max(0, int(math.floor(x0-pad)))
    top = max(0, int(math.floor(y0-pad)))
    right = min(width, int(math.ceil(x1+pad)))
    bottom = min(height, int(math.ceil(y1+pad)))
    if right-left < 8 or bottom-top < 8:
        return None
    crop = gray[top:bottom, left:right]
    crop = cv2.GaussianBlur(crop, (3, 3), 0.65)
    rmin = max(3, int(math.floor(min_radius_px)))
    rmax = min(int(min(right-left, bottom-top)*.5), int(math.ceil(span * .58)))
    if rmax <= rmin:
        return None
    circles = cv2.HoughCircles(crop, cv2.HOUGH_GRADIENT, dp=1,
                              minDist=max(6, int(span*.25)),
                              param1=100, param2=max(8, int(span*.28)),
                              minRadius=rmin, maxRadius=rmax)
    if circles is None:
        return None
    center_box = np.array([(x0+x1)*.5,(y0+y1)*.5])
    candidates = []
    for cx, cy, r in circles[0]:
        xy = np.array([float(cx)+left+.5,float(cy)+top+.5])
        # Do not take a nearby circle belonging to some other glyph.
        if not (x0-pad <= xy[0] <= x1+pad and y0-pad <= xy[1] <= y1+pad):
            continue
        dist = np.linalg.norm(xy-center_box)
        if dist > span*.35:
            continue
        # Larger circular outline preferred over tiny interior dots; center
        # proximity remains primary, because bbox comes from symbol detector.
        score = dist / max(span,1) - .08 * (r / max(span,1))
        candidates.append((score, xy, float(r)))
    if not candidates:
        return None
    _, xy, r = min(candidates,key=lambda v:v[0])
    return CircleEstimate((float(xy[0]),float(xy[1])),r)


def _point_to_finite_wall(p: Point, wall: WallSegment, *, endpoint_margin: float) -> tuple[Point,float] | None:
    """Normal intersection with the supporting wall line; reject far outside ends."""
    pos = np.asarray(p, dtype=np.float64)
    a = np.asarray(wall.start, dtype=np.float64)
    delta = np.asarray(wall.end, dtype=np.float64) - a
    length = float(np.linalg.norm(delta))
    u = float((pos-a) @ delta / (length*length))
    if u < -endpoint_margin/length or u > 1 + endpoint_margin/length:
        return None
    projected = a + u*delta
    return ((float(projected[0]),float(projected[1])),float(np.linalg.norm(projected-pos)))


def resolve_snap(box: Box, *, circle_center: Point | None = None,
                 circle_source: str = 'circle_fit',
                 walls: Iterable[WallSegment] = (),
                 wall_attachable: bool = False,
                 max_wall_distance_px: float | None = None,
                 wall_relevance_factor: float = .75,
                 ambiguity_px: float = 1.5,
                 symbol_axis: Point | None = None) -> SnapResult:
    """Resolve centerline-to-wall snap, else actual circle center.

    For generic receptacles, the symbol centerline intersects a wall at the
    perpendicular projection of the circle center onto verified wall centerline.
    If a trusted symbol axis is provided, use its intersection with the wall
    instead (axis is an *unoriented* direction in image xy coordinates).

    Only verified wall geometry should be supplied. A nearby line from raw
    contrast detection can be conduit, text, a dimension line, or a wall.
    When walls tie within `ambiguity_px`, do not invent a definitive attachment.
    The nearest wall must be within a **local**, symbol-sized threshold.
    """
    _validate_box(box)
    x0,y0,x1,y1 = map(float,box)
    w,h = x1-x0,y1-y0
    if circle_center is not None:
        pos = np.asarray(circle_center,dtype=np.float64)
        if pos.shape != (2,) or not np.isfinite(pos).all():
            raise ValueError('invalid circle_center')
        center = tuple(map(float,pos))
        center_kind = 'visual_center' if circle_source == 'explicit_visual_center' else 'circle_center'
        center_quality = 'source_geometry' if circle_source.startswith('explicit_') else 'estimated'
    else:
        center = ((x0+x1)*.5,(y0+y1)*.5)
        center_kind, center_quality = 'bbox_center_provisional','provisional'
        circle_source = 'detector_bbox'
    if not wall_attachable:
        return SnapResult(center, center_kind, center_quality, center,circle_source,
                          note='No wall-attachment policy for this symbol family')

    radius = max(w,h)*.5
    threshold = float(max_wall_distance_px) if max_wall_distance_px is not None else radius*(1 + wall_relevance_factor)
    if threshold <= 0 or not math.isfinite(threshold):
        raise ValueError('max wall distance must be positive finite')
    axis = None
    if symbol_axis is not None:
        vec = np.asarray(symbol_axis,dtype=float)
        if vec.shape != (2,) or not np.isfinite(vec).all() or np.linalg.norm(vec)<1e-8:
            raise ValueError('invalid symbol_axis')
        axis=vec/np.linalg.norm(vec)

    candidates: list[tuple[float,int,Point]] = []
    for idx,wall in enumerate(walls):
        out = _point_to_finite_wall(center,wall,endpoint_margin=max(1., radius*.15))
        if out is None:
            continue
        projected, distance = out
        if distance > threshold:
            continue
        if axis is not None:
            # Intersect center+axis*t with the finite wall centerline.
            a = np.asarray(wall.start,dtype=float)
            b = np.asarray(wall.end,dtype=float)
            v=b-a
            det = axis[0]*v[1]-axis[1]*v[0]
            if abs(det) < 1e-7:
                continue
            diff=a-np.asarray(center)
            t=(diff[0]*v[1]-diff[1]*v[0])/det
            u=(diff[0]*axis[1]-diff[1]*axis[0])/det
            if not (-max(1.,radius*.15)/np.linalg.norm(v) <= u <= 1+max(1.,radius*.15)/np.linalg.norm(v)):
                continue
            point=np.asarray(center)+t*axis
            distance=float(np.linalg.norm(point-np.asarray(center)))
            if distance > threshold:
                continue
            projected=(float(point[0]),float(point[1]))
        candidates.append((distance,idx,projected))
    candidates.sort(key=lambda item:item[0])
    if not candidates:
        return SnapResult(center,center_kind,center_quality,center,circle_source,
                          note='No eligible verified wall within local symbol range')
    dist,idx,point=candidates[0]
    if len(candidates)>1 and candidates[1][0]-dist<=ambiguity_px and np.linalg.norm(np.asarray(candidates[1][2])-np.asarray(point))>ambiguity_px:
        return SnapResult(center,'ambiguous_wall', 'uncertain',center,circle_source,
                          note='Several plausible walls; no attachment selected')
    quality=center_quality
    return SnapResult(point,'wall_centerline_intersection',quality,center,circle_source,
                      wall_index=idx,wall_distance_px=dist,
                      note='Perpendicular to verified wall' if axis is None else 'Along provided symbol axis')


def resolve_annotation(image: np.ndarray, instance: Mapping, walls: Iterable[WallSegment]=(),
                       *, wall_families: frozenset[str] = frozenset({'receptacle'})) -> SnapResult:
    """Resolve detection/CAD instance to snap point, preserving source truth.

    Recognizes explicit `circle_center` / `visual_center` anchors from CAD,
    otherwise attempts a raster circle fit. Other symbol families currently
    receive provisional centers, not fabricated semantic CAD anchors.
    """
    box=tuple(instance['bbox_xyxy'])
    family=str(instance.get('symbol_family','')).casefold()
    center=None
    source='circle_fit'
    for key in ('circle_center','visual_center'):
        data=instance.get('anchors',{}).get(key)
        if isinstance(data,dict) and data.get('annotated') and data.get('visible') and data.get('xy') is not None:
            center=tuple(map(float,data['xy']))
            source='explicit_'+key
            break
    if center is None and family in wall_families:
        estimate=detect_circle_in_box(image,box)
        if estimate is not None:
            center=estimate.center
    axis=None
    if instance.get('symbol_axis_xy') is not None:
        axis=tuple(instance['symbol_axis_xy'])
    return resolve_snap(box, circle_center=center, circle_source=source,
                        walls=walls, wall_attachable=family in wall_families,
                        symbol_axis=axis)
