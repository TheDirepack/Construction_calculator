"""Conservative automatic labels from real CAD block insertion metadata.

An INSERT is an explicit object instance, unlike arbitrary disconnected CAD
strokes. No attempt is made to guess semantic class from random geometry.
User supplied block-name metadata and CAD->raster calibration are required.
All bbox and anchor coordinates are in original raster pixels, including
subpixel fractions. Failed/unknown entities are *reported*, never silently
used as negatives or invented labels.
"""
from __future__ import annotations
from dataclasses import dataclass
from pathlib import Path
from collections import Counter
import json
import math
import numpy as np


@dataclass(frozen=True)
class PixelTransform:
    """3x3 homography from 2D CAD world coordinates to raster image *edges*."""
    H: np.ndarray

    def __post_init__(self) -> None:
        h = np.asarray(self.H, dtype=np.float64)
        if h.shape != (3, 3) or not np.isfinite(h).all() or abs(np.linalg.det(h)) < 1e-10:
            raise ValueError('invalid or singular 3x3 mapping')
        object.__setattr__(self, 'H', h)

    @staticmethod
    def from_extents(extents: tuple[float, float, float, float],
                     width: int, height: int, *, preserve_aspect: bool = True) -> 'PixelTransform':
        """CAD y-up extent to raster y-down, no cropping, with optional letterbox.

        Only valid for rasters rendered from these *exact* CAD extents; if the
        raster is cropped, rotated or laid out differently, use calibration.
        """
        minx, miny, maxx, maxy = extents
        if width <= 0 or height <= 0 or not all(map(math.isfinite, extents)) or maxx <= minx or maxy <= miny:
            raise ValueError('invalid CAD extents or raster size')
        sx, sy = width / (maxx-minx), height / (maxy-miny)
        if preserve_aspect:
            sx = sy = min(sx, sy)
        pad_x, pad_y = (width-sx*(maxx-minx))/2, (height-sy*(maxy-miny))/2
        H = np.array([[sx, 0, pad_x-sx*minx],
                      [0, -sy, pad_y+sy*maxy], [0, 0, 1]], dtype=np.float64)
        return PixelTransform(H)

    def point(self, p: tuple[float, float]) -> tuple[float, float]:
        arr = self.H @ np.array([p[0], p[1], 1.0], dtype=np.float64)
        if abs(arr[2]) < 1e-10:
            raise ValueError('point projected to infinity')
        return (float(arr[0]/arr[2]), float(arr[1]/arr[2]))

    def bbox(self, box: tuple[float, float, float, float]) -> tuple[float, float, float, float]:
        x0, y0, x1, y1 = box
        if x1 <= x0 or y1 <= y0:
            raise ValueError('invalid bbox')
        pts = [self.point((x,y)) for x in (x0,x1) for y in (y0,y1)]
        xs, ys = zip(*pts)
        return (min(xs), min(ys), max(xs), max(ys))

    def inverse(self) -> 'PixelTransform':
        return PixelTransform(np.linalg.inv(self.H))


@dataclass(frozen=True)
class LabelReport:
    instances: list[dict]
    unknown_blocks: dict[str, int]
    skipped: dict[str, int]


def _block_key(name: str) -> str:
    return name.strip().casefold()


def labels_from_dxf(dxf_path: str | Path, block_map: dict, transform: PixelTransform,
                    *, document_id: str = 'cad_document') -> LabelReport:
    """Extract exact symbol instances from explicitly named DXF block refs.

    block_map example:
      {"RCP_DUPLEX": {"symbol_family":"receptacle", "symbol_variant":"duplex",
         "anchors_local":{"visual_center":[0,0],"wall_attachment":[0,-2]}}}

    `anchors_local` are measured in block-local CAD coordinates relative to
    block base; ezdxf's block transform handles rotation/scale/translation.
    The default snap anchor is BLOCK INSERT origin, *not* claimed wall center.
    Blocks with no trusted metadata are skipped and counted.
    """
    try:
        import ezdxf
        from ezdxf import bbox
        from ezdxf.math import Vec3
    except ImportError as ex:
        raise RuntimeError('pip install ezdxf for DXF imports') from ex
    doc = ezdxf.readfile(str(dxf_path))
    mapped = {_block_key(k): v for k,v in block_map.items()}
    unknown: Counter = Counter()
    skipped: Counter = Counter()
    output: list[dict] = []
    for entity in doc.modelspace():
        if entity.dxftype() != 'INSERT':
            continue
        # Multi-inserts carry arrays of symbols, not one gigantic instance.
        refs = list(entity.multi_insert()) if entity.mcount > 1 else [entity]
        for ref in refs:
            block_name = ref.dxf.name
            info = mapped.get(_block_key(block_name))
            if info is None:
                unknown[block_name] += 1
                continue
            if not isinstance(info, dict) or not info.get('symbol_family'):
                skipped['invalid_block_metadata'] += 1
                continue
            try:
                # Text/attributes can wildly inflate symbol bbox. Explicitly
                # omit text when finding graphic stroke extents.
                graphics = [e for e in ref.virtual_entities() if e.dxftype() not in
                            {'TEXT','MTEXT','ATTRIB','ATTDEF','DIMENSION'}]
                ext = bbox.extents(graphics)
                if not ext.has_data or not ext.extmin or not ext.extmax:
                    skipped['no_graphic_geometry'] += 1
                    continue
                xyxy = transform.bbox((ext.extmin.x, ext.extmin.y, ext.extmax.x, ext.extmax.y))
                anchors = {}
                matrix = ref.matrix44()
                local = info.get('anchors_local') or {}
                for name, pt in local.items():
                    if len(pt) != 2:
                        raise ValueError('anchor must contain two coordinates')
                    world = matrix.transform(Vec3(float(pt[0]), float(pt[1]), 0))
                    anchors[name] = {'xy': list(transform.point((world.x,world.y))),
                                     'visible': True, 'annotated': True}
                # A CAD INSERT origin may be arbitrary and is NOT an exact
                # physical snap point unless the source mapping explicitly
                # says so. Recover unique circle centers from real geometry.
                circles = [g for g in graphics if g.dxftype() == 'CIRCLE']
                if len(circles) == 1 and 'circle_center' not in anchors:
                    center = circles[0].dxf.center
                    anchors['circle_center'] = {
                        'xy': list(transform.point((center.x,center.y))),
                        'visible': True, 'annotated': True,
                        'method': 'unique_dxf_circle_center'
                    }
                origin = matrix.transform(Vec3(0,0,0))
                x_axis = matrix.transform(Vec3(1,0,0))
                p0, p1 = transform.point((origin.x, origin.y)), transform.point((x_axis.x,x_axis.y))
                angle = math.degrees(math.atan2(p1[1]-p0[1],p1[0]-p0[0]))
                output.append({
                    'instance_id': f'{document_id}_{len(output):06d}',
                    'symbol_family': info['symbol_family'],
                    'symbol_variant': info.get('symbol_variant', 'unspecified'),
                    'bbox_xyxy': list(xyxy), 'anchors': anchors,
                    'rotation_degrees_image': angle,
                    'source': {'kind':'dxf_insert', 'document_id':document_id,
                               'block_name':block_name, 'entity_handle':ref.dxf.get('handle'),
                               'label_method':'cad_metadata_exact_export_transform'},
                })
            except (ValueError, TypeError, ArithmeticError) as ex:
                skipped[type(ex).__name__] += 1
    return LabelReport(output, dict(unknown), dict(skipped))


def load_cad_mapping(path: str | Path) -> tuple[dict, PixelTransform]:
    """JSON mapping must supply a validated 3x3 CAD->image transform."""
    data = json.loads(Path(path).read_text())
    if 'block_map' not in data or 'cad_to_pixel_homography' not in data:
        raise ValueError('mapping requires block_map and cad_to_pixel_homography')
    return data['block_map'], PixelTransform(np.array(data['cad_to_pixel_homography'],dtype=np.float64))
