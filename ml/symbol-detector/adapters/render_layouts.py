"""Render vector floor-plan layouts as drafted plan sheets (composite backgrounds).

    python adapters/render_layouts.py DATA/5_datasets OUT/pool/background_layouts --limit 4000

Sources (each layout is its own project group):
  resplan/{train,val,test}.jsonl       wall-first JSON, 1024-unit frame (~8.7k plans)
  cubicasa5k_hf/{train,val,test}.jsonl same schema, CubiCasa5K plans (~3.9k)
  houseexpo/HouseExpo/json.tar.gz      free-space outline polygons in metres (~35k)

These datasets carry walls, rooms and openings but no symbols, so the pages
are pure architecture: safe backgrounds for electrical compositing. Drafting
style is randomised per page (wall fill, line weight, door swings, window
marks, room names, dimension strings, grid bubbles) so the detector does not
learn one renderer's look. `--limit` caps pages per source (0 = all); the
real scanned and photographed pages should dominate the background mix.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import random
import tarfile
from pathlib import Path

import cv2
import numpy as np

FONTS = (cv2.FONT_HERSHEY_SIMPLEX, cv2.FONT_HERSHEY_PLAIN, cv2.FONT_HERSHEY_DUPLEX, cv2.FONT_HERSHEY_COMPLEX_SMALL)
ROOMS = ('BEDROOM', 'KITCHEN', 'BATH', 'LIVING', 'DINING', 'CLOSET', 'OFFICE', 'STORAGE', 'HALL', 'LAUNDRY',
         'GARAGE', 'MECH', 'CORRIDOR', 'LOBBY', 'CONF RM', 'BREAK RM', 'ELEC RM', 'TOILET')


class Style:
    def __init__(self, rng: random.Random):
        self.wall = rng.choice(['solid', 'outline', 'hatch', 'gray'])
        self.lw = rng.choice([1, 1, 2, 2, 3])
        self.text = rng.random() < .8
        self.dims = rng.random() < .5
        self.grid = rng.random() < .35
        self.ink = rng.randint(0, 50)
        self.paper = rng.randint(235, 255)
        self.font = rng.choice(FONTS)


def _wall_poly(a, b, t):
    (x0, y0), (x1, y1) = a, b
    dx, dy = x1 - x0, y1 - y0
    n = math.hypot(dx, dy) or 1
    ox, oy = -dy / n * t / 2, dx / n * t / 2
    return np.array([[x0 + ox, y0 + oy], [x1 + ox, y1 + oy], [x1 - ox, y1 - oy], [x0 - ox, y0 - oy]])


def draw_walls(img, walls, S: Style, rng: random.Random):
    ink = (S.ink,) * 3
    for w in walls:
        poly = np.round(_wall_poly(w['a'], w['b'], max(w['t'], 2))).astype(np.int32)
        if S.wall == 'solid':
            cv2.fillPoly(img, [poly], ink, cv2.LINE_AA)
        elif S.wall == 'gray':
            cv2.fillPoly(img, [poly], (150,) * 3, cv2.LINE_AA)
            cv2.polylines(img, [poly], True, ink, S.lw, cv2.LINE_AA)
        else:
            cv2.polylines(img, [poly], True, ink, S.lw, cv2.LINE_AA)
            if S.wall == 'hatch':
                mask = np.zeros(img.shape[:2], np.uint8)
                cv2.fillPoly(mask, [poly], 255)
                x, y, ww, hh = cv2.boundingRect(poly)
                hatch = np.zeros_like(mask)
                for k in range(-hh, ww, 7):
                    cv2.line(hatch, (x + k, y + hh), (x + k + hh, y), 255, 1)
                img[(mask > 0) & (hatch > 0)] = ink
    for w in walls:
        for o in w.get('openings', []):
            a, b = np.array(w['a'], float), np.array(w['b'], float)
            d = b - a
            L = np.linalg.norm(d) or 1
            u = d / L
            c, half = np.array(o['center'], float), o['width'] / 2
            p0, p1 = c - u * half, c + u * half
            gap = np.round(_wall_poly(p0, p1, max(w['t'], 2) + 2)).astype(np.int32)
            cv2.fillPoly(img, [gap], (S.paper,) * 3)
            nrm = np.array([-u[1], u[0]])
            if o['type'] == 'door':
                side = rng.choice([-1, 1])
                hinge = p0 if rng.random() < .5 else p1
                tip = hinge + nrm * side * o['width']
                cv2.line(img, tuple(np.int32(hinge)), tuple(np.int32(tip)), (S.ink,) * 3, S.lw, cv2.LINE_AA)
                other = p1 if hinge is p0 else p0
                r = int(o['width'])
                a0 = math.degrees(math.atan2(*(other - hinge)[::-1]))
                a1 = math.degrees(math.atan2(*(tip - hinge)[::-1]))
                lo, hi = sorted((a0, a1))
                if hi - lo > 180:
                    lo, hi = hi, lo + 360
                cv2.ellipse(img, tuple(np.int32(hinge)), (r, r), 0, lo, hi, (S.ink,) * 3, 1, cv2.LINE_AA)
            else:
                t = max(w['t'], 2) / 2
                for k in (-t, 0, t):
                    q0, q1 = p0 + nrm * k, p1 + nrm * k
                    cv2.line(img, tuple(np.int32(q0)), tuple(np.int32(q1)), (S.ink,) * 3, 1, cv2.LINE_AA)


def draw_text(img, rooms, S: Style, rng: random.Random, px_per_unit: float):
    if S.text:
        for r in rooms:
            poly = np.array(r['polygon'], float)
            if len(poly) < 3:
                continue
            cx, cy = poly.mean(axis=0)
            label = r.get('label') or rng.choice(ROOMS)
            label = label.replace('_', ' ')
            scale = max(.4, px_per_unit * rng.uniform(.6, 1.0))
            (tw, th), _ = cv2.getTextSize(label, S.font, scale, 1)
            cv2.putText(img, label, (int(cx - tw / 2), int(cy)), S.font, scale, (S.ink,) * 3, 1, cv2.LINE_AA)
            if rng.random() < .5:
                cv2.putText(img, str(rng.randint(100, 399)), (int(cx - tw / 4), int(cy + th * 1.8)),
                            S.font, scale * .8, (S.ink,) * 3, 1, cv2.LINE_AA)
    h, w = img.shape[:2]
    if S.dims:
        y = int(h * rng.uniform(.02, .06))
        xs = sorted(rng.sample(range(int(w * .08), int(w * .92)), 4))
        cv2.line(img, (xs[0], y), (xs[-1], y), (S.ink,) * 3, 1, cv2.LINE_AA)
        for x in xs:
            cv2.line(img, (x - 5, y + 5), (x + 5, y - 5), (S.ink,) * 3, 1, cv2.LINE_AA)
        for x0, x1 in zip(xs, xs[1:]):
            txt = f"{rng.randint(2, 40)}'-{rng.randint(0, 11)}\""
            cv2.putText(img, txt, ((x0 + x1) // 2 - 20, y - 6), S.font, .5, (S.ink,) * 3, 1, cv2.LINE_AA)
    if S.grid:
        for i, x in enumerate(np.linspace(w * .1, w * .9, rng.randint(3, 6))):
            cv2.line(img, (int(x), 0), (int(x), h), (S.ink + 60,) * 3, 1, cv2.LINE_AA)
            cv2.circle(img, (int(x), 22), 16, (S.ink,) * 3, 1, cv2.LINE_AA)
            cv2.putText(img, str(i + 1), (int(x) - 6, 28), S.font, .6, (S.ink,) * 3, 1, cv2.LINE_AA)


def render_wall_first(rec: dict, rng: random.Random) -> np.ndarray:
    S = Style(rng)
    frame = float(rec.get('frame', 1024))
    long_side = rng.randint(1800, 3000)
    s = long_side / frame
    pad = int(long_side * .06)
    img = np.full((long_side + 2 * pad, long_side + 2 * pad, 3), S.paper, np.uint8)
    walls = [{'a': (x0 * s + pad, y0 * s + pad), 'b': (x1 * s + pad, y1 * s + pad),
              't': w.get('thickness', 4) * s,
              'openings': [{'type': o['type'], 'center': (o['center'][0] * s + pad, o['center'][1] * s + pad),
                            'width': o['width'] * s} for o in w.get('openings', [])]}
             for w in rec['gt']['walls'] for (x0, y0), (x1, y1) in [(w['start'], w['end'])]]
    draw_walls(img, walls, S, rng)
    rooms = [{'label': r.get('label'), 'polygon': [(x * s + pad, y * s + pad) for x, y in r.get('polygon', [])]}
             for r in rec['gt'].get('rooms', [])]
    draw_text(img, rooms, S, rng, s * 1.2)
    ys, xs = np.nonzero(img.min(axis=2) < 200)
    if len(xs):
        img = img[max(0, ys.min() - pad):ys.max() + pad, max(0, xs.min() - pad):xs.max() + pad]
    return img


def render_houseexpo(doc: dict, rng: random.Random) -> np.ndarray:
    S = Style(rng)
    verts = np.array(doc['verts'], float)
    ppm = rng.uniform(45, 90)          # pixels per metre
    pad = 120
    verts = (verts - verts.min(axis=0)) * ppm + pad
    w, h = (verts.max(axis=0) + pad).astype(int)
    if w * h > 30e6:
        return None
    img = np.full((h, w, 3), S.paper, np.uint8)
    t = max(2, int(ppm * rng.uniform(.1, .25)))
    cv2.polylines(img, [np.round(verts).astype(np.int32)], True, (S.ink,) * 3, t, cv2.LINE_AA)
    draw_text(img, [], S, rng, ppm / 60)   # HouseExpo has no room names; dims and grid only
    return img


def write_page(out: Path, name: str, group: str, img: np.ndarray, source: str):
    png = out / f'{name}.png'
    cv2.imwrite(str(png), img)
    doc = {'format_version': 1, 'source_id': source, 'project_family_id': group, 'source_group_verified': True,
           'document_id': name, 'page_index': 0, 'source_type': 'rendered_layout',
           'image_path': png.name, 'image_sha256': hashlib.sha256(png.read_bytes()).hexdigest(),
           'image_width': img.shape[1], 'image_height': img.shape[0],
           'coordinate_system': 'original_page_pixels_top_left_y_down',
           'exhaustive_families': [], 'instances': [],
           'pool': {'role': 'background', 'source_path': source, 'role_reason': 'vector layout, no symbols'}}
    png.with_suffix('.json').write_text(json.dumps(doc, indent=1))


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('datasets', type=Path, help='the 5_datasets folder')
    p.add_argument('output', type=Path)
    p.add_argument('--limit', type=int, default=4000, help='pages per source (0 = all)')
    p.add_argument('--seed', type=int, default=11)
    p.add_argument('--train-only', action='store_true',
                   help='Render only train.jsonl from split datasets; useful for SSL training')
    a = p.parse_args()
    a.output.mkdir(parents=True, exist_ok=True)
    rng = random.Random(a.seed)
    counts = {}
    for name, files in (('resplan', sorted(a.datasets.glob('resplan/*.jsonl'))),
                        ('cubicasa', sorted(a.datasets.glob('cubicasa5k_hf/*.jsonl')))):
        if a.train_only:
            files = [f for f in files if f.stem == 'train']
        recs, bad = [], 0
        for f in files:
            for line in f.open():
                try:
                    recs.append(json.loads(line))
                except ValueError:
                    bad += 1          # resplan/train.jsonl ships with one truncated record
        if bad:
            counts[f'{name}_bad_lines'] = bad
        rng.shuffle(recs)
        n = 0
        for rec in recs[:a.limit or None]:
            rid = str(rec['id']).replace('/', '_')
            if (a.output / f'{rid}.json').exists():
                n += 1
                continue
            img = render_wall_first(rec, random.Random(f'{a.seed}/{rid}'))
            write_page(a.output, rid, f'layout_{rid}', img, name)
            n += 1
        counts[name] = n
    tgz = a.datasets / 'houseexpo/HouseExpo/json.tar.gz'
    if tgz.exists():
        n = 0
        with tarfile.open(tgz) as tf:
            members = [m for m in tf.getmembers() if m.name.endswith('.json')]
            rng.shuffle(members)
            for m in members[:a.limit or None]:
                rid = 'houseexpo_' + Path(m.name).stem[:16]
                if (a.output / f'{rid}.json').exists():
                    n += 1
                    continue
                img = render_houseexpo(json.load(tf.extractfile(m)), random.Random(f'{a.seed}/{rid}'))
                if img is not None:
                    write_page(a.output, rid, f'layout_{rid}', img, 'houseexpo')
                    n += 1
        counts['houseexpo'] = n
    print(json.dumps(counts))


if __name__ == '__main__':
    main()
