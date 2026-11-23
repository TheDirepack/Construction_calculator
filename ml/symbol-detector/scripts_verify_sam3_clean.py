#!/usr/bin/env python3
"""
Verify and clean SAM3 provisional device detections.

For every *_SAM3.json in work/sam3_proposals/:
  * open the source PDF (resolved against the library root)
  * for each device, compare its symbol_bbox (PDF points, bottom-left,
    display-derotated space) directly against pdfplumber's rects/lines/
    curves/chars, which live in the same derotated coordinate space
  * classify each box:
      KEEP              - real device symbol art present, bbox reasonable
      TIGHTEN           - real art present but bbox loose; bbox replaced by
                          art-extent (recorded as symbol_bbox_tightened)
      REJECT_EMPTY      - box on empty / no-art area
      REJECT_TEXT       - box is mostly text chars, not device art
  * write cleaned JSON to work/sam3_cleaned/<name>_CLEANED.json
  * write a master summary report

No vision tool is available, so this is a programmatic geometry check.
Boxes that cannot be verified are rejected rather than silently trusted,
per the rule: every kept box must bound a real device symbol, tightly,
with no extra boxes.
"""
import json
import os
import glob
import sys
from collections import Counter, defaultdict

import pdfplumber

PROPOSALS = "/home/max/.t3/projects/construction-calculator/ml/symbol-detector/work/sam3_proposals"
OUTDIR = "/home/max/.t3/projects/construction-calculator/ml/symbol-detector/work/sam3_cleaned"
REPORT = "/home/max/.t3/projects/construction-calculator/ml/symbol-detector/work/sam3_cleaned_report.json"
LIB_ROOT = "/home/max/.t3/projects/construction-calculator/blueprints_and_libraries"
CELL = 100.0  # spatial grid cell size in PDF points

os.makedirs(OUTDIR, exist_ok=True)


def resolve_pdf(data):
    abs_ = data.get("source_pdf_absolute")
    if abs_ and os.path.exists(abs_):
        return abs_
    rel = data.get("source_pdf_relative")
    if rel:
        p2 = os.path.join(LIB_ROOT, rel)
        if os.path.exists(p2):
            return p2
    bn = os.path.basename(rel or "")
    for root, _dirs, files in os.walk(LIB_ROOT):
        if bn in files:
            return os.path.join(root, bn)
    return None


# ---- geometry helpers -----------------------------------------------------

def area(b):
    return max(0.0, b[2] - b[0]) * max(0.0, b[3] - b[1])


def intersect(a, b):
    ix0 = max(a[0], b[0]); iy0 = max(a[1], b[1])
    ix1 = min(a[2], b[2]); iy1 = min(a[3], b[3])
    if ix1 <= ix0 or iy1 <= iy0:
        return None
    return [ix0, iy0, ix1, iy1]


def norm_obj(obj):
    """pdfplumber object -> normalized [xmin, ymin, xmax, ymax] bottom-left."""
    return [obj["x0"], min(obj["top"], obj["bottom"]),
            obj["x1"], max(obj["top"], obj["bottom"])]


def build_grid(art_objs):
    """Uniform grid index: cell key (cx,cy) -> list of indices into art_objs."""
    grid = defaultdict(list)
    for i, ob in enumerate(art_objs):
        cx0 = int(ob[0] // CELL); cx1 = int(ob[2] // CELL)
        cy0 = int(ob[1] // CELL); cy1 = int(ob[3] // CELL)
        for cx in range(cx0, cx1 + 1):
            for cy in range(cy0, cy1 + 1):
                grid[(cx, cy)].append(i)
    return grid


def grid_query(grid, art_objs, bbox):
    """Return indices of art_objs whose cell overlaps bbox, then verify overlap."""
    x0, y0, x1, y1 = bbox
    cx0 = int(x0 // CELL); cx1 = int(x1 // CELL)
    cy0 = int(y0 // CELL); cy1 = int(y1 // CELL)
    cand = set()
    for cx in range(cx0, cx1 + 1):
        for cy in range(cy0, cy1 + 1):
            for i in grid.get((cx, cy), ()):
                cand.add(i)
    hits = []
    for i in cand:
        if intersect(bbox, art_objs[i]):
            hits.append(i)
    return hits


def classify_device(art_objs, grid, chars, dbbox, pw, ph):
    """
    Inspect precomputed page objects for real device art inside dbbox.
    Returns (verdict, detail_dict, tightened_bbox_or_None).
    """
    cbbox = [float(dbbox[0]), float(dbbox[1]), float(dbbox[2]), float(dbbox[3])]
    bbox_area = area(cbbox)
    if bbox_area <= 0:
        return "REJECT_EMPTY", {"reason": "degenerate_bbox"}, None

    hits = grid_query(grid, art_objs, cbbox)
    art_inside = []
    art_area_inside = 0.0
    art_overlap_fracs = []
    for i in hits:
        ob = art_objs[i]
        inter = intersect(cbbox, ob)
        art_inside.append(ob)
        art_area_inside += area(inter)
        art_overlap_fracs.append(area(inter) / max(area(ob), 1.0))

    chars_inside = 0
    for cb in chars:
        if intersect(cbbox, cb):
            chars_inside += 1

    n_art = len(art_inside)
    frac_art = art_area_inside / bbox_area if bbox_area else 0.0
    max_overlap = max(art_overlap_fracs) if art_overlap_fracs else 0.0

    detail = {
        "bbox_used": [round(v, 2) for v in cbbox],
        "bbox_area": round(bbox_area, 2),
        "n_vector_art_objs": n_art,
        "art_area_inside": round(art_area_inside, 2),
        "frac_bbox_covered_by_art": round(frac_art, 3),
        "max_single_obj_overlap": round(max_overlap, 3),
        "chars_inside": chars_inside,
    }

    if n_art == 0:
        verdict = "REJECT_EMPTY" if chars_inside == 0 else "REJECT_TEXT"
        return verdict, detail, None

    tighten = None
    xs0 = [o[0] for o in art_inside]; ys0 = [o[1] for o in art_inside]
    xs1 = [o[2] for o in art_inside]; ys1 = [o[3] for o in art_inside]
    union = [min(xs0), min(ys0), max(xs1), max(ys1)]
    union_area = area(union)
    bw = cbbox[2] - cbbox[0]; bh = cbbox[3] - cbbox[1]
    uw = union[2] - union[0]; uh = union[3] - union[1]
    if (union_area > 0 and union_area <= 4.0 * bbox_area and
            uw <= 2.0 * bw and uh <= 2.0 * bh):
        tighten = [
            max(0, union[0]), max(0, union[1]),
            min(pw, union[2]), min(ph, union[3]),
        ]
        if area(tighten) <= 0:
            tighten = None
    return "KEEP", detail, tighten


def process_file(path):
    name = os.path.basename(path)
    with open(path) as f:
        data = json.load(f)
    pdf_path = resolve_pdf(data)
    if pdf_path is None:
        raise FileNotFoundError("could not resolve source PDF for " + name)

    # group devices by page, preserving original order index
    by_page = defaultdict(list)
    for idx, dev in enumerate(data.get("devices", [])):
        dev["_order"] = idx
        by_page[dev.get("pdf_page", 1)].append(dev)

    results = []
    verdicts = Counter()
    with pdfplumber.open(pdf_path) as pdf:
        n_pages = len(pdf.pages)
        for pgno in sorted(by_page):
            page = pdf.pages[pgno - 1]
            art_objs = [norm_obj(o) for o in list(page.rects)
                        + list(page.lines) + list(page.curves)]
            grid = build_grid(art_objs)
            chars = [norm_obj(ch) for ch in page.chars]
            pw, ph = page.width, page.height
            for dev in by_page[pgno]:
                dbbox = dev.get("symbol_bbox")
                if not dbbox or len(dbbox) != 4:
                    verdicts["REJECT_EMPTY"] += 1
                    results.append({**dev, "verify_verdict": "REJECT_EMPTY",
                                    "verify_detail": {"reason": "no_bbox"}})
                    continue
                verdict, detail, tighten = classify_device(
                    art_objs, grid, chars, dbbox, pw, ph)
                verdicts[verdict] += 1
                rec = {**dev, "verify_verdict": verdict,
                       "verify_detail": detail}
                if tighten is not None:
                    rec["symbol_bbox_tightened"] = tighten
                results.append(rec)
        # preserve original device order
        results.sort(key=lambda r: r.get("_order", 10**9))
        # drop the helper key from output records
        for r in results:
            r.pop("_order", None)

    cleaned = dict(data)
    cleaned["devices"] = results
    cleaned["verify_status"] = "cleaned_programmatic"
    cleaned["verify_summary"] = dict(verdicts)
    cleaned["total_devices_input"] = data.get("total_devices")
    cleaned["total_devices_kept"] = verdicts["KEEP"]
    cleaned["source_pdf_resolved"] = pdf_path
    out = os.path.join(OUTDIR, name.replace("_SAM3.json", "_CLEANED.json"))
    with open(out, "w") as f:
        json.dump(cleaned, f, indent=1)

    return {
        "file": name,
        "source_pdf": data.get("source_pdf_relative"),
        "input": data.get("total_devices"),
        "verdicts": dict(verdicts),
        "kept": verdicts["KEEP"],
        "output": out,
    }


def main():
    files = sorted(glob.glob(os.path.join(PROPOSALS, "*_SAM3.json")))
    if not files:
        print("No SAM3 proposal files found in", PROPOSALS)
        return 1
    if len(sys.argv) > 1:
        files = [f for f in files if any(s in f for s in sys.argv[1:])]

    master = []
    total_in = 0
    total_kept = 0
    for path in files:
        try:
            r = process_file(path)
            master.append(r)
            total_in += r["input"] or 0
            total_kept += r["kept"]
            print(f"{r['kept']:6d}/{r['input']:6d} kept  {r['file']}  {r['verdicts']}", flush=True)
        except Exception as e:
            print("ERROR", path, e, file=sys.stderr, flush=True)
            master.append({"file": os.path.basename(path), "error": str(e)})

    master.append({
        "grand_total_input": total_in,
        "grand_total_kept": total_kept,
        "files_processed": len([m for m in master if "error" not in m]),
    })
    with open(REPORT, "w") as f:
        json.dump(master, f, indent=1)
    print("\nGRAND TOTAL kept", total_kept, "of", total_in)
    print("Report written to", REPORT)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
