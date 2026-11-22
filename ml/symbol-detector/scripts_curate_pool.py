"""Balance and sort the background pool before compositing.

    python scripts_curate_pool.py work/pool --max-per-group 60

Every page in OUT/background gets two scores, written into its JSON `pool` block:
  line_ratio   share of ink lying on long straight horizontal/vertical runs
               (drafted plans are high; text and photos are low)
  paper_ratio  share of pixels within +-20 grey levels of the page's dominant tone.
               Drafted sheets, even on tinted or aged paper, are mostly paper plus
               ink (0.65-0.99); photographs and colour renders spread across all
               tones (UW Cities median 0.23, PhotoCAD 0.29).

Then:
  * pages with paper_ratio < --paper-min move to OUT/negative: nothing is
    composited onto them, and the exporter keeps them as verified
    "no electrical symbol here" pages. A drawing misfiled here is still used,
    only without composites, so the threshold errs on the safe side.
  * groups with more than --max-per-group pages (e.g. a 300-page pattern book)
    keep their most drawing-like pages; the rest move to OUT/reserve, which is
    never used, so one source cannot dominate the mix. Groups matching
    --uncapped (real construction sets) are never capped.
Moves are reversible: put the files back and re-run with other thresholds.
"""
from __future__ import annotations

import argparse
import json
from collections import defaultdict, Counter
from pathlib import Path

import cv2
import numpy as np


def scores(path: Path) -> tuple[float, float]:
    g = cv2.imread(str(path), cv2.IMREAD_GRAYSCALE)
    if g is None:
        return 0.0, 1.0
    s = 1600 / max(g.shape)
    if s < 1:
        g = cv2.resize(g, None, fx=s, fy=s, interpolation=cv2.INTER_AREA)
    ink = (g < 150).astype(np.uint8)
    n = int(ink.sum()) or 1
    k = max(15, int(max(g.shape) / 60))
    long_h = cv2.morphologyEx(ink, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (k, 1)))
    long_v = cv2.morphologyEx(ink, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (1, k)))
    line_ratio = float(((long_h | long_v) > 0).sum()) / n
    hist = np.bincount(g.ravel(), minlength=256)
    m = int(hist.argmax())
    paper_ratio = float(hist[max(0, m - 20):m + 21].sum() / g.size)
    return line_ratio, paper_ratio


def move(doc_path: Path, doc: dict, dest: Path):
    dest.mkdir(parents=True, exist_ok=True)
    img = doc_path.parent / doc['image_path']
    img.rename(dest / img.name)
    (dest / doc_path.name).write_text(json.dumps(doc, indent=1))
    doc_path.unlink()


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('pool', type=Path)
    p.add_argument('--max-per-group', type=int, default=25)
    p.add_argument('--paper-min', type=float, default=.35)
    p.add_argument('--uncapped', default='rinker_hall,njatc_plant,freefarmhouse,state_dot,'
                                         'library_of_congress,wikimedia_us_drawings')
    p.add_argument('--reset', action='store_true', help='move negative/ and reserve/ back first')
    a = p.parse_args()
    bg = a.pool / 'background'
    if a.reset:
        for folder in ('negative', 'reserve'):
            for j in sorted((a.pool / folder).glob('*.json')):
                d = json.loads(j.read_text())
                d['pool']['role'] = 'background'
                move(j, d, bg)
    uncapped = [u for u in a.uncapped.split(',') if u]
    docs = []
    for j in sorted(bg.glob('*.json')):
        d = json.loads(j.read_text())
        pool = d.setdefault('pool', {})
        if 'paper_ratio' not in pool:
            pool['line_ratio'], pool['paper_ratio'] = scores(bg / d['image_path'])
            pool.pop('photo_ratio', None)
            j.write_text(json.dumps(d, indent=1))
        docs.append((j, d))
    counts = Counter()
    keep = defaultdict(list)
    for j, d in docs:
        pl = d['pool']
        if pl['paper_ratio'] < a.paper_min:
            pl['role'] = 'negative'
            move(j, d, a.pool / 'negative')
            counts['to_negative'] += 1
        else:
            keep[d['project_family_id']].append((j, d))
    for group, items in keep.items():
        if len(items) <= a.max_per_group or any(u in group for u in uncapped):
            counts['kept'] += len(items)
            continue
        items.sort(key=lambda x: -x[1]['pool']['line_ratio'])
        counts['kept'] += a.max_per_group
        for j, d in items[a.max_per_group:]:
            d['pool']['role'] = 'reserve'
            move(j, d, a.pool / 'reserve')
            counts['to_reserve'] += 1
    print(json.dumps(dict(counts)))


if __name__ == '__main__':
    main()
