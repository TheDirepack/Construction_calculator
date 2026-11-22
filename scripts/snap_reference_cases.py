#!/usr/bin/env python3
"""Build line-snapping reference cases from real plan sheets.

Measured cases come from vector (CAD) PDFs. Each page is rendered once at high resolution, where
a straight stroke's center is found exactly (the centroid of its antialiased coverage), and again
at the resolutions the app snaps on, clean and degraded (blur, JPEG, noise and low contrast, low
resolution). A tap is placed near each stroke; the expected answer is the foot of the tap on the
stroke's center line. Taps where other ink is about as close as the stroke are left out, and so
are strokes that touch other lines nearby (junctions have no single right answer).

Visual cases come from scans and photos, which have no exact geometry: taps near ink, for
checking the overlays by eye (`--visual`).

Photo cases make a page look like a phone picture of a sheet: perspective, paper curl, uneven
light, blur, noise and JPEG. The exact geometry goes through the same distortion, so the answer
stays exact; it is scored across the line (the distortion moves points along it).

Output goes to blueprints_and_libraries/_snap_reference/ (ignored by git, like the library):
the cases, the images they use, and for every reference page its full-resolution SVG and
high-resolution render. The core:plans reference tests read it.

    python3 -P scripts/snap_reference_cases.py
    python3 -P scripts/snap_reference_cases.py --visual SCAN.pdf:30 --visual photo.jpg

Needs numpy, OpenCV (cv2) and poppler's pdftocairo.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import tempfile
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parent.parent
LIBRARY = ROOT / 'blueprints_and_libraries'
OUT = LIBRARY / '_snap_reference'

HIGH = 8          # pixels per point for the exact render
RADIUS = 32.0     # tap search radius in snap pixels, as the app uses (--radius)
SCALES = (2, 3, 4)  # snap pixels per point
DEGRADED_SCALE = 3

# (pdf relative to the library, pages). Vector drawing sheets, not covers.
SHEETS = [
    ('4_archives/state_dot_samples/caltrans/Caltrans_2022_Standard_Plans_97_sheets.pdf', (6, 22, 41)),
    ('4_archives/state_dot_samples/caltrans/Caltrans_Sample_Plan_Set_Retaining_Walls.pdf', (3, 6)),
    ('1_blueprints/freefarmhouse/American Farmhouse 201225 full.pdf', (4, 7, 12)),
    ('1_blueprints/freefarmhouse/Southern Farmhouse - Drawing Set - Jay Osborne 2023-02-01.pdf', (5, 8, 14)),
]


def render(pdf: Path, page: int, scale: float) -> np.ndarray:
    with tempfile.TemporaryDirectory() as folder:
        base = Path(folder) / 'page'
        # Cairo, not pdftoppm: pdftoppm snaps thin strokes to whole pixels, which moves them.
        # Cairo can assert on exit after writing the page, so the file decides success.
        subprocess.run(['pdftocairo', '-gray', '-r', f'{72 * scale:g}', '-f', str(page), '-l', str(page),
                        '-singlefile', '-png', str(pdf), str(base)], stderr=subprocess.DEVNULL)
        image = cv2.imread(str(base) + '.png', cv2.IMREAD_GRAYSCALE)
    if image is None:
        raise RuntimeError(f'could not render {pdf} page {page}')
    return image


def strokes(high: np.ndarray, rng: np.random.Generator, wanted: int, half_length: int) -> list[dict]:
    """Straight horizontal strokes in a high-resolution render: (along, center, width) in points."""
    ink = high < 128

    def coverage(rows: slice, cols: slice) -> np.ndarray:
        return (255 - high[rows, cols].astype(np.float32)) / 255
    rows, cols = ink.shape
    found = []
    seen = set()
    for _ in range(wanted * 400):
        if len(found) >= wanted:
            break
        x = int(rng.integers(half_length + 2, cols - half_length - 2))
        column = ink[:, x]
        edges = np.diff(column.astype(np.int8), prepend=0, append=0)
        starts = np.nonzero(edges == 1)[0]
        ends = np.nonzero(edges == -1)[0]
        if not len(starts):
            continue
        i = int(rng.integers(len(starts)))
        top, bottom = int(starts[i]), int(ends[i])
        if bottom - top > 3 * HIGH or top < 4 or bottom > rows - 4:
            continue
        span = slice(x - half_length, x + half_length + 1)
        if not ink[top:bottom, span].all():
            continue
        # Paper on both sides so the coverage centroid is the stroke's alone.
        if coverage(slice(top - 3, top - 1), span).max() > .02 or coverage(slice(bottom + 1, bottom + 3), span).max() > .02:
            continue
        key = (top, x // (2 * half_length))
        if key in seen:
            continue
        seen.add(key)
        band = coverage(slice(top - 1, bottom + 1), span)
        centers = (np.arange(top - 1, bottom + 1)[:, None] + .5)
        center = float(np.median((band * centers).sum(0) / band.sum(0)))
        width = float(np.median(band.sum(0)))
        found.append({'along': (x + .5) / HIGH, 'center': center / HIGH, 'width': width / HIGH,
                      'top': top, 'bottom': bottom, 'x': x})
    return found


def degrade(image: np.ndarray, kind: str, rng: np.random.Generator) -> np.ndarray:
    if kind == 'blur':
        return cv2.GaussianBlur(image, (0, 0), .9)
    if kind == 'jpeg':
        ok, data = cv2.imencode('.jpg', image, [cv2.IMWRITE_JPEG_QUALITY, 35])
        return cv2.imdecode(data, cv2.IMREAD_GRAYSCALE)
    if kind == 'faint':
        faded = 120 + image.astype(np.float32) * (230 - 120) / 255
        noisy = faded + rng.normal(0, 9, image.shape)
        return np.clip(noisy, 0, 255).astype(np.uint8)
    if kind == 'lowres':
        h, w = image.shape
        small = cv2.resize(image, (w // 2, h // 2), interpolation=cv2.INTER_AREA)
        return cv2.resize(small, (w, h), interpolation=cv2.INTER_LINEAR)
    return image


def measured(rng: np.random.Generator, per_page: int) -> list[dict]:
    cases = []
    for relative, pages in SHEETS:
        pdf = LIBRARY / relative
        if not pdf.is_file():
            print(f'skip (missing): {relative}')
            continue
        for page in pages:
            high = render(pdf, page, HIGH)
            name = f'{pdf.stem[:24].replace(" ", "_")}_p{page}'
            keep = OUT / 'source'
            keep.mkdir(parents=True, exist_ok=True)
            cv2.imwrite(str(keep / f'{name}_{HIGH * 72}dpi.png'), high)
            subprocess.run(['pdftocairo', '-svg', '-f', str(page), '-l', str(page), str(pdf), str(keep / f'{name}.svg')],
                           stderr=subprocess.DEVNULL)
            half_length = int(RADIUS * HIGH / min(SCALES)) + 8
            found = []
            for transpose in (False, True):
                source = high.T if transpose else high
                for stroke in strokes(np.ascontiguousarray(source), rng, per_page, half_length):
                    stroke['vertical'] = transpose
                    found.append(stroke)
            print(f'{name}: {len(found)} strokes')
            images = [(scale, 'clean') for scale in SCALES] + \
                     [(DEGRADED_SCALE, kind) for kind in ('blur', 'jpeg', 'faint', 'lowres', 'photo')]
            for scale, kind in images:
                file = OUT / f'{name}_s{scale}_{kind}.png'
                clean = render(pdf, page, scale)
                warp = Warp(clean.shape, rng) if kind == 'photo' else None
                image = warp.photo(clean, rng) if warp else degrade(clean, kind, rng)
                cv2.imwrite(str(file), image)
                for stroke in found:
                    case = tap_case(stroke, scale, rng, high, clean)
                    if case is None:
                        continue
                    vertical = stroke['vertical']
                    case['direction'] = [0.0, 1.0] if vertical else [1.0, 0.0]
                    if warp:
                        tap, truth = case['tap'], case['truth']
                        ahead = [truth[0] + case['direction'][0], truth[1] + case['direction'][1]]
                        case['tap'] = [round(v, 3) for v in warp.forward(*tap)]
                        case['truth'] = [round(v, 4) for v in warp.forward(*truth)]
                        moved = warp.forward(*ahead)
                        d = np.subtract(moved, case['truth'])
                        case['direction'] = [round(float(v), 5) for v in d / np.linalg.norm(d)]
                        if not (0 <= case['tap'][0] < image.shape[1] and 0 <= case['tap'][1] < image.shape[0]):
                            continue
                    case.update(image=file.name, scale=scale, variant=kind, sheet=name,
                                pdf=str(pdf.relative_to(LIBRARY)), page=page)
                    cases.append(case)
    return cases


class Warp:
    """A phone picture of a sheet: paper curl, then perspective. forward() maps clean-render
    pixel coordinates to the picture; photo() makes the picture."""

    def __init__(self, shape, rng):
        h, w = shape
        self.w, self.h = w, h
        self.curl = rng.uniform(.004, .012) * h
        self.waves = rng.uniform(.8, 1.6)
        jitter = rng.uniform(-.035, .035, (4, 2)) * [w, h]
        src = np.float32([[0, 0], [w, 0], [w, h], [0, h]])
        self.H = cv2.getPerspectiveTransform(src, (src + jitter).astype(np.float32))
        self.Hinv = np.linalg.inv(self.H)

    def bend(self, x):
        return self.curl * np.sin(np.pi * self.waves * x / self.w)

    def forward(self, x, y):
        y = y + self.bend(x)
        p = self.H @ [x, y, 1]
        return float(p[0] / p[2]), float(p[1] / p[2])

    def photo(self, image, rng):
        h, w = image.shape
        yy, xx = np.mgrid[0:h, 0:w].astype(np.float64) + .5
        q = np.tensordot(self.Hinv, np.stack([xx, yy, np.ones_like(xx)]), axes=1)
        sx, sy = q[0] / q[2], q[1] / q[2]
        sy = sy - self.bend(sx)
        out = cv2.remap(image, (sx - .5).astype(np.float32), (sy - .5).astype(np.float32),
                        cv2.INTER_LINEAR, borderValue=200)
        light = 1 - .28 * ((xx / w - rng.uniform(.2, .8)) ** 2 + (yy / h - rng.uniform(.2, .8)) ** 2)
        tint = rng.uniform(.85, .97)
        out = out.astype(np.float32) * light * tint + rng.uniform(-6, 10)
        out = cv2.GaussianBlur(out, (0, 0), rng.uniform(.7, 1.2))
        out = out + rng.normal(0, 5, out.shape)
        out = np.clip(out, 0, 255).astype(np.uint8)
        ok, data = cv2.imencode('.jpg', out, [cv2.IMWRITE_JPEG_QUALITY, 55])
        return cv2.imdecode(data, cv2.IMREAD_GRAYSCALE)


def tap_case(stroke: dict, scale: int, rng, high: np.ndarray, image: np.ndarray) -> dict | None:
    """A tap near the stroke, in snap pixels, or None when the answer would be ambiguous."""
    reach = (RADIUS * HIGH / scale)
    along = stroke['along'] + rng.uniform(-.3, .3) * reach / HIGH
    offset = rng.uniform(-.6, .6) * RADIUS  # snap pixels across the stroke
    across = stroke['center'] * scale + offset
    if stroke['vertical']:
        truth = (stroke['center'] * scale, along * scale)
        tap = (across, along * scale)
    else:
        truth = (along * scale, stroke['center'] * scale)
        tap = (along * scale, across)
    # Ink near the tap, at high resolution, as connected pieces of this crop.
    cx, cy = tap[0] / scale * HIGH, tap[1] / scale * HIGH
    r = int(reach) + 2
    y0, y1 = max(0, int(cy) - r), min(high.shape[0], int(cy) + r + 1)
    x0, x1 = max(0, int(cx) - r), min(high.shape[1], int(cx) + r + 1)
    window = cv2.connectedComponents((high[y0:y1, x0:x1] < 128).astype(np.uint8), connectivity=8)[1]
    fx, fy = int(truth[0] / scale * HIGH) - x0, int(truth[1] / scale * HIGH) - y0
    if not (0 <= fx < window.shape[1] and 0 <= fy < window.shape[0]) or window[fy, fx] == 0:
        return None
    component = window[fy, fx]
    yy, xx = np.mgrid[y0:y1, x0:x1]
    distance = np.hypot(xx + .5 - cx, yy + .5 - cy) / HIGH * scale
    inside = distance <= RADIUS
    target = (window == component) & inside
    # The stroke must be alone within reach: a junction has no single right answer.
    if stroke['vertical']:
        spread = np.abs(xx[target] + .5 - stroke['center'] * HIGH)
    else:
        spread = np.abs(yy[target] + .5 - stroke['center'] * HIGH)
    if spread.size and spread.max() > stroke['width'] * HIGH / 2 + 3:
        return None
    if merged(image, truth, stroke, scale):
        return None
    other = (window > 0) & (window != component) & inside
    edge = max(0.0, abs(offset) - stroke['width'] * scale / 2)
    if other.any():
        nearest = float(distance[other].min())
        if nearest < edge + 2:
            return None
        category = 'busy'
    else:
        category = 'clear'
    return {'tap': [round(tap[0], 3), round(tap[1], 3)], 'truth': [round(truth[0], 4), round(truth[1], 4)],
            'width': round(stroke['width'] * scale, 3), 'category': category}


def merged(image: np.ndarray, truth: tuple, stroke: dict, scale: int) -> bool:
    """Whether, at this resolution, the stroke runs into neighboring ink (a hairline seam
    between fills at high resolution can close up), so its own center is no longer visible."""
    x, y = int(truth[0]), int(truth[1])
    r = int(stroke['width'] * scale) + 6
    if stroke['vertical']:
        band = image[max(0, y - 3):y + 4, max(0, x - r):x + r + 1].astype(np.float32).mean(0)
        at = x - max(0, x - r)
    else:
        band = image[max(0, y - r):y + r + 1, max(0, x - 3):x + 4].astype(np.float32).mean(1)
        at = y - max(0, y - r)
    if not (0 <= at < len(band)):
        return True
    paper = np.percentile(band, 90)
    level = (paper + band[at]) / 2
    if paper - band[at] < 20:
        return True
    left = at
    while left > 0 and band[left - 1] < level:
        left -= 1
    right = at
    while right < len(band) - 1 and band[right + 1] < level:
        right += 1
    return right - left + 1 > stroke['width'] * scale + 3 or left == 0 or right == len(band) - 1


def visual(sources: list[str], rng: np.random.Generator, per_source: int) -> list[dict]:
    cases = []
    for index, source in enumerate(sources):
        path, _, page = source.partition(':')
        if path.lower().endswith('.pdf'):
            image = render(Path(path), int(page or 1), 3)
        else:
            image = cv2.imread(path, cv2.IMREAD_GRAYSCALE)
            if image is None:
                raise RuntimeError(f'could not read {path}')
            if image.shape[1] > 3000:
                image = cv2.resize(image, (3000, image.shape[0] * 3000 // image.shape[1]), interpolation=cv2.INTER_AREA)
        file = OUT / f'visual_{index:02d}.png'
        cv2.imwrite(str(file), image)
        background = cv2.medianBlur(image, 31)
        ink = (background.astype(np.int16) - image) > 45
        ys, xs = np.nonzero(ink[40:-40, 40:-40])
        for k in rng.choice(len(xs), size=min(per_source, len(xs)), replace=False):
            angle = rng.uniform(0, 2 * np.pi)
            distance = rng.uniform(3, 10)
            tap = (xs[k] + 40.5 + distance * np.cos(angle), ys[k] + 40.5 + distance * np.sin(angle))
            cases.append({'image': file.name, 'tap': [round(tap[0], 2), round(tap[1], 2)], 'variant': 'visual',
                          'sheet': Path(path).name + (f':{page}' if page else ''), 'scale': 3})
    return cases


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--per-page', type=int, default=40, help='strokes per page and direction')
    parser.add_argument('--visual', action='append', default=[], help='scan PDF[:page] or photo for visual checks')
    parser.add_argument('--seed', type=int, default=2026)
    parser.add_argument('--radius', type=float, default=RADIUS, help='tap search radius in snap pixels')
    args = parser.parse_args()
    globals()['RADIUS'] = args.radius
    OUT.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(args.seed)
    cases = measured(rng, args.per_page)
    cases += visual(args.visual, rng, 48)
    (OUT / 'cases.json').write_text(json.dumps({'radius': RADIUS, 'cases': cases}, indent=0))
    counts = {}
    for case in cases:
        key = (case['variant'], case.get('category', '-'))
        counts[key] = counts.get(key, 0) + 1
    print(f'{len(cases)} cases in {OUT}')
    for key, count in sorted(counts.items()):
        print(f'  {key[0]:>7} {key[1]:>6}: {count}')


if __name__ == '__main__':
    main()
