#!/usr/bin/env python3
"""Extract device symbols + labels from WashingtonDrawings plan sheets.
Uses vector glyph detection + nearest text label, bottom-left origin PDF points."""
import fitz, json, os, math, re, sys

PDF = '/home/max/.t3/projects/construction-calculator/blueprints_and_libraries/1_blueprints/new_downloads_202611b/WashingtonDrawings (1).pdf'

# ---------- device code -> (type, subtype) from E0.01 electrical symbol legend ----------
CODE_MAP = {
    'F':  ('lighting_fixture', 'led_luminaire'),
    'J':  ('junction_box', 'junction_box'),
    'R':  ('receptacle', 'duplex'),
    'X':  ('exit_sign', 'exit_light'),
    'OC': ('occupancy_sensor', 'wall_mount'),
    'D':  ('wall_device', 'wall_device'),
    'K':  ('switch', 'key_operated'),
    'C':  ('receptacle', 'clock'),
    'PC': ('receptacle', 'clock'),
    'HB': ('receptacle', 'special_purpose'),
    'FE': ('fire_extinguisher', 'fire_extinguisher'),
    'FD': ('fire_damper', 'fire_damper'),
    'CEF':('ceiling_exhaust_fan', 'exhaust_fan'),
    'EUH':('electric_unit_heater', 'unit_heater'),
    'IWH':('instantaneous_water_heater', 'water_heater'),
    'LC1':('dimmer_switch', 'wall_box_dimmer'),
    'S':  ('fire_alarm_device', 'smoke_detector'),
    'SW': ('switch', 'wall_switch'),
    'T':  ('thermostat', 'thermostat'),
    'TS': ('data_outlet', 'telecom_data'),
    'JB': ('junction_box', 'junction_box'),
}

SINGLE_LETTER_DEVICES = set('FJRXDK')   # device tags used on these plans (A,B,C,S,T are keynotes/other)
# multi-letter device codes actually used in the legend/plans
MULTI_DEVICE_CODES = {'OC','CEF','EUH','IWH','FE','FD','PC','HB','LC1','JB','TS','SW','WP'}
# text tokens that are NOT device labels (keynotes, panel names, conditions, dims)
NON_DEVICE = {'(E)', '(N)', '(R)', '(RR)', '(ER)', '(TYP)', 'TYP', 'NTS', 'AFF',
              'UP', 'N', 'EXISTING', 'NEW', 'DEMO'}


def is_device_label(t):
    if t in NON_DEVICE:
        return False
    u = t.upper()
    if u in CODE_MAP:
        return True
    if u in MULTI_DEVICE_CODES:
        return True
    if re.fullmatch(r'\d+', t):          # pure keynote number
        return False
    if re.fullmatch(r'[A-Z]\d+', t):     # panel P1-P11, circuit
        return False
    if re.fullmatch(r'\+?\d+(\.\d+)?["\']?', t):  # dimension like +18"
        return False
    if len(t) == 1 and t.upper() in SINGLE_LETTER_DEVICES:
        return True
    return False


def classify(code):
    return CODE_MAP.get(code.upper(), ('device', code.upper()))


def dist_pt_seg(px, py, x1, y1, x2, y2):
    dx, dy = x2 - x1, y2 - y1
    if dx == 0 and dy == 0:
        return math.hypot(px - x1, py - y1)
    t = ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy)
    t = max(0.0, min(1.0, t))
    return math.hypot(px - (x1 + t * dx), py - (y1 + t * dy))


def process_page(page, page_num, wall_threshold=22.0, sym_min=3.5, sym_max=34.0):
    H = page.rect.height
    W = page.rect.width

    # ---- text spans ----
    texts = []
    for b in page.get_text('dict')['blocks']:
        if b['type'] != 0:
            continue
        for line in b['lines']:
            for span in line['spans']:
                t = span['text'].strip()
                if not t:
                    continue
                x0, y0, x1, y1 = span['bbox']
                cx = (x0 + x1) / 2
                cy = H - (y0 + y1) / 2   # bottom-left origin center
                texts.append({
                    't': t, 'x0': x0, 'y0': H - y1, 'x1': x1, 'y1': H - y0,
                    'cx': cx, 'cy': cy, 'size': span['size'],
                })

    # ---- wall lines: long straight strokes (thin wall lines + thick wall rects) ----
    walls = []  # (x1,y1,x2,y2)
    parts = []  # candidate symbol parts
    for dr in page.get_drawings():
        wd = dr.get('width', 0) or 0
        ftype = dr.get('type')
        rect = dr['rect']
        x0, y0, x1, y1 = rect
        bx0, by0, bx1, by1 = x0, H - y1, x1, H - y0
        bw, bh = bx1 - bx0, by1 - by0
        longdim = max(bw, bh); shdim = min(bw, bh)
        if ftype == 's' and 0.3 <= wd <= 3.0:
            # substantial wall stroke: long, not the giant title-block border
            if 100 <= longdim <= 2000 and shdim < 70:
                walls.append((bx0, by0, bx1, by1))
        # symbol part: stroked, modest size, not a hairline long wire
        if ftype == 's' and wd > 0:
            if sym_min <= bw <= sym_max and sym_min <= bh <= sym_max:
                # not a single thin wire
                if not (min(bw, bh) < 1.2 and max(bw, bh) > 16):
                    parts.append({'x0': bx0, 'y0': by0, 'x1': bx1, 'y1': by1,
                                  'cx': (bx0 + bx1) / 2, 'cy': (by0 + by1) / 2,
                                  'w': bw, 'h': bh, 'width': wd})

    # ---- cluster symbol parts into device symbols ----
    used = [False] * len(parts)
    clusters = []
    for i in range(len(parts)):
        if used[i]:
            continue
        used[i] = True
        mem = [i]
        changed = True
        while changed:
            changed = False
            ci = parts[mem[-1]]
            for j in range(len(parts)):
                if used[j]:
                    continue
                cj = parts[j]
                dx = abs(ci['cx'] - cj['cx']); dy = abs(ci['cy'] - cj['cy'])
                if dx <= 9 and dy <= 9:
                    used[j] = True
                    mem.append(j); changed = True
                    ci = cj
        # union bbox
        xs0 = [parts[m]['x0'] for m in mem]; ys0 = [parts[m]['y0'] for m in mem]
        xs1 = [parts[m]['x1'] for m in mem]; ys1 = [parts[m]['y1'] for m in mem]
        clusters.append({'x0': min(xs0), 'y0': min(ys0), 'x1': max(xs1), 'y1': max(ys1),
                         'cx': (min(xs0) + max(xs1)) / 2, 'cy': (min(ys0) + max(ys1)) / 2,
                         'parts': mem})

    # ---- match each symbol cluster to nearest text label (reverse index) ----
    # Build device records from reliable text device-labels, attach nearest symbol glyph.
    devices = []
    # cluster centers for nearest-symbol lookup
    cluster_centers = [(cl['cx'], cl['cy'], cl) for cl in clusters]
    for tx in texts:
        if not is_device_label(tx['t']):
            continue
        cx, cy = tx['cx'], tx['cy']
        # skip label text outside the visible page (title-block / stamp artifacts)
        if cx < -2 or cy < -2 or cx > W + 2 or cy > H + 2:
            continue
        # nearest symbol cluster
        best = None; bestd = 1e9
        for (ccx, ccy, cl) in cluster_centers:
            d = math.hypot(ccx - cx, ccy - cy)
            if d < bestd:
                bestd = d; best = cl
        sym = best if best and bestd <= 20 and min(best['x1']-best['x0'], best['y1']-best['y0']) >= 3.0 else None
        if sym:
            sbx0, sby0, sbx1, sby1 = sym['x0'], sym['y0'], sym['x1'], sym['y1']
            scx, scy = sym['cx'], sym['cy']
        else:
            # fallback: use the label's own box as the device location
            sbx0, sby0 = tx['x0'], tx['y0']
            sbx1, sby1 = tx['x1'], tx['y1']
            scx, scy = cx, cy
        # wall test
        mindwall = 1e9; wallpt = None
        for (wx1, wy1, wx2, wy2) in walls:
            d = dist_pt_seg(scx, scy, wx1, wy1, wx2, wy2)
            if d < mindwall:
                mindwall = d
                dx, dy = wx2 - wx1, wy2 - wy1
                if dx == 0 and dy == 0:
                    t = 0.0
                else:
                    t = ((scx - wx1) * dx + (scy - wy1) * dy) / (dx * dx + dy * dy)
                t = max(0.0, min(1.0, t))
                wallpt = (wx1 + t * dx, wy1 + t * dy)
        on_wall = mindwall <= wall_threshold
        dtype, subtype = classify(tx['t'])
        rec = {
            'page': page_num,
            'device_code': tx['t'],
            'device_type': dtype,
            'subtype': subtype,
            'symbol_bbox': [round(sbx0, 2), round(sby0, 2), round(sbx1, 2), round(sby1, 2)],
            'symbol_center': [round(scx, 2), round(scy, 2)],
            'label_bbox': [round(tx['x0'], 2), round(tx['y0'], 2), round(tx['x1'], 2), round(tx['y1'], 2)],
            'label_center': [round(cx, 2), round(cy, 2)],
            'label_to_symbol_distance': round(bestd if sym else 0.0, 2),
            'on_wall': on_wall,
            'wall_distance': round(mindwall, 2),
            'symbol_attached': bool(sym),
        }
        if on_wall and wallpt:
            rec['wall_connection_point'] = [round(wallpt[0], 2), round(wallpt[1], 2)]
        else:
            rec['device_center'] = [round(scx, 2), round(scy, 2)]
        devices.append(rec)
    return {'page': page_num, 'size': [round(W, 2), round(H, 2)],
            'wall_count': len(walls), 'symbol_clusters': len(clusters), 'devices': devices}


def main(pages, out):
    doc = fitz.open(PDF)
    result = {'source': PDF, 'pages': {str(p): process_page(doc[p - 1], p) for p in pages}}
    with open(out, 'w') as f:
        json.dump(result, f, indent=1)
    print('wrote', out)
    for p in pages:
        pg = result['pages'][str(p)] if str(p) in result['pages'] else None
    # result['pages'] keyed by int? json keys are str
    total = 0
    for p in pages:
        pg = result['pages'][str(p)]
        total += len(pg['devices'])
        print(f"page {p}: walls={pg['wall_count']} clusters={pg['symbol_clusters']} devices={len(pg['devices'])}")
    print('TOTAL devices:', total)


if __name__ == '__main__':
    args = sys.argv[1:]
    out = '/tmp/opencode/wash_devices.json'
    pages = []
    for a in args:
        if a.startswith('--out='):
            out = a.split('=', 1)[1]
        elif a.isdigit():
            pages.append(int(a))
    if not pages:
        pages = [47, 48, 49, 50, 51, 52, 58, 59, 60, 61, 62, 63]
    main(pages, out)
