"""Convert the official CubiCasa5K release (model.svg per plan) into normalized page JSON.

    python adapters/import_cubicasa5k.py DATA/5_datasets/cubicasa5k_official/cubicasa5k \
        OUT/normalized/cubicasa5k --class-map class_maps/cubicasa.json

Layout: <root>/{high_quality,high_quality_architectural,colorful}/<id>/
        F1_scaled.png (the raster the SVG coordinates refer to), model.svg.

Objects: <g id="Door">, <g id="Window"> and <g class="FixedFurniture <Type> ...">.
A box is the bounds of ALL geometry inside the object (polygons, polylines,
lines, rects, circles, ellipses, paths), after every ancestor transform, so a
door's box includes its swing, not only the opening polygon CubiCasa's own
loader uses. Boxes are clipped to the image.

CubiCasa annotates every door, window and fixture icon on a plan, so each
target family in the class map is recorded in `exhaustive_families`. Every
plan is its own project group. Images are referenced by absolute path, not
copied.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

import numpy as np

NUM = r'[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?'


def parse_transform(t: str | None) -> np.ndarray:
    M = np.eye(3)
    if not t:
        return M
    for name, args in re.findall(r'(\w+)\s*\(([^)]*)\)', t):
        v = [float(x) for x in re.findall(NUM, args)]
        if name == 'matrix' and len(v) == 6:
            T = np.array([[v[0], v[2], v[4]], [v[1], v[3], v[5]], [0, 0, 1]])
        elif name == 'translate':
            T = np.array([[1, 0, v[0]], [0, 1, v[1] if len(v) > 1 else 0], [0, 0, 1]])
        elif name == 'scale':
            sx = v[0]; sy = v[1] if len(v) > 1 else v[0]
            T = np.diag([sx, sy, 1.0])
        elif name == 'rotate':
            a = np.radians(v[0]); c, s = np.cos(a), np.sin(a)
            R = np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])
            if len(v) == 3:
                T = np.array([[1, 0, v[1]], [0, 1, v[2]], [0, 0, 1]]) @ R @ np.array([[1, 0, -v[1]], [0, 1, -v[2]], [0, 0, 1]])
            else:
                T = R
        else:
            continue
        M = M @ T
    return M


def local_points(el: ET.Element) -> list[tuple[float, float]]:
    tag = el.tag.rsplit('}', 1)[-1]
    g = lambda k, d=0.0: float(el.get(k, d) or d)
    if tag in ('polygon', 'polyline'):
        v = [float(x) for x in re.findall(NUM, el.get('points', ''))]
        return list(zip(v[0::2], v[1::2]))
    if tag == 'line':
        return [(g('x1'), g('y1')), (g('x2'), g('y2'))]
    if tag == 'rect':
        x, y, w, h = g('x'), g('y'), g('width'), g('height')
        return [(x, y), (x + w, y + h)]
    if tag in ('circle', 'ellipse'):
        cx, cy = g('cx'), g('cy')
        rx = g('r') if tag == 'circle' else g('rx')
        ry = g('r') if tag == 'circle' else g('ry')
        return [(cx - rx, cy - ry), (cx + rx, cy + ry), (cx - rx, cy + ry), (cx + rx, cy - ry)]
    if tag == 'path' and el.get('d'):
        try:
            from svgpathtools import parse_path
            p = parse_path(el.get('d'))
            if len(p) == 0:
                return []
            x0, x1, y0, y1 = p.bbox()
            return [(x0, y0), (x1, y1), (x0, y1), (x1, y0)]
        except Exception:
            return []
    return []


def object_kind(el: ET.Element) -> str | None:
    if el.get('id') in ('Door', 'Window'):
        return el.get('id')
    cls = el.get('class', '')
    if cls.startswith('FixedFurniture '):
        return cls.split()[1]
    return None


def collect(el: ET.Element, M: np.ndarray, out: list):
    """Walk the tree; for each object element, gather all transformed points under it."""
    M = M @ parse_transform(el.get('transform'))
    kind = object_kind(el)
    if kind is not None:
        pts = []

        def gather(node, Mn):
            Mn = Mn @ parse_transform(node.get('transform')) if node is not el else Mn
            for x, y in local_points(node):
                q = Mn @ np.array([x, y, 1.0])
                pts.append((q[0], q[1]))
            for ch in node:
                gather(ch, Mn)
        gather(el, M)
        out.append((kind, pts))
        return                                  # objects are not nested in each other
    for ch in el:
        collect(ch, M, out)


def convert(root: Path, out: Path, cmap: dict) -> dict:
    import cv2
    out.mkdir(parents=True, exist_ok=True)
    targets = sorted({v for v in cmap.values() if v not in ('drop', 'ignore')})
    counts, unknown = Counter(), Counter()
    for svg in sorted(root.rglob('model.svg')):
        plan = svg.parent
        img_path = plan / 'F1_scaled.png'
        if not img_path.is_file():
            counts['missing_image'] += 1
            continue
        img = cv2.imread(str(img_path), cv2.IMREAD_UNCHANGED)
        if img is None:
            counts['unreadable_image'] += 1
            continue
        h, w = img.shape[:2]
        try:
            tree = ET.parse(svg)
        except ET.ParseError:
            counts['bad_svg'] += 1
            continue
        objs: list = []
        collect(tree.getroot(), np.eye(3), objs)
        pid = f'{plan.parent.name}_{plan.name}'
        instances = []
        for k, (kind, pts) in enumerate(objs):
            fam = cmap.get(kind)
            if fam is None:
                unknown[kind] += 1
                continue
            if fam == 'drop' or not pts:
                counts['dropped' if pts else 'no_geometry'] += 1
                continue
            a = np.array(pts)
            x0, y0 = np.clip(a.min(0), 0, [w, h])
            x1, y1 = np.clip(a.max(0), 0, [w, h])
            if x1 - x0 < 2 or y1 - y0 < 2:
                counts['degenerate'] += 1
                continue
            ign = fam == 'ignore'
            instances.append({'instance_id': f'cc5k_{pid}_{k:04d}',
                              'symbol_family': '__unmapped__' if ign else fam, 'symbol_variant': kind,
                              'bbox_xyxy': [float(x0), float(y0), float(x1), float(y1)], 'anchors': {},
                              'uncertain': ign,
                              'source': {'kind': 'cubicasa_svg', 'label_method': 'supplied_dataset_annotation'}})
            counts['ignored' if ign else 'labeled'] += 1
        doc = {'format_version': 1, 'source_id': 'cubicasa5k', 'source_license': 'CC BY-NC 4.0',
               'project_family_id': f'cubicasa_{pid}', 'source_group_verified': True,
               'document_id': pid, 'page_index': 0, 'source_type': 'scanned_floorplan',
               'image_path': str(img_path.resolve()),
               'image_sha256': hashlib.sha256(img_path.read_bytes()).hexdigest(),
               'image_width': w, 'image_height': h,
               'coordinate_system': 'original_page_pixels_top_left_y_down',
               'exhaustive_families': targets, 'instances': instances,
               'cubicasa': {'category': plan.parent.name, 'plan_id': plan.name}}
        (out / f'cc5k_{pid}.json').write_text(json.dumps(doc, indent=1))
        counts['pages'] += 1
        if counts['pages'] % 500 == 0:
            print(counts['pages'], 'plans', flush=True)
    if unknown:
        raise SystemExit(f'object types missing from the class map: {dict(unknown)}')
    return dict(counts)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('root', type=Path)
    p.add_argument('output', type=Path)
    p.add_argument('--class-map', type=Path, required=True)
    a = p.parse_args()
    cmap = {k: v for k, v in json.loads(a.class_map.read_text()).items() if not k.startswith('_')}
    print(json.dumps(convert(a.root, a.output, cmap), indent=1))


if __name__ == '__main__':
    main()
