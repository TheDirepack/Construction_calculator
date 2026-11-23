"""Efficient one-pass driver for the NMNH Baird PDF extraction (detached run).

Computes the legend once, then extracts per page. Writes progress to a log
file and the unified schema JSON once at the end.
"""

import sys, os, json, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from extractor import PDFDeviceExtractor

PDF = "/home/max/.t3/projects/construction-calculator/blueprints_and_libraries/1_blueprints/2025-0214_NMNH_Baird_Bid_Set_-_Drawings_with_Bookmarks.pdf"
OUT = "/home/max/.t3/projects/construction-calculator/blueprints_and_libraries/1_blueprints/new_downloads_202611b/NMNH_Baird_DEVICES.json"
LOG = "/home/max/.local/share/opencode/shell/nmnh_drive.log"


def log(msg):
    with open(LOG, "a", encoding="utf-8") as f:
        f.write(msg + "\n")
        f.flush()


# Monkeypatch extract_devices_from_page to reuse a single legend computation.
_orig = PDFDeviceExtractor.extract_devices_from_page


def _patched_extract_page(self, page_num, legend_entries=None):
    if page_num < 1 or page_num > self.total_pages:
        return []
    page = self._page(page_num)
    if not self._page_vector_flags[page_num - 1]:
        return []
    H = float(page.height)
    W = float(page.width)

    raw_words = page.extract_words() or []
    words = []
    for w in raw_words:
        x0, x1 = w["x0"], w["x1"]
        y0_bl = H - w["bottom"]
        y1_bl = H - w["top"]
        words.append({
            "text": w["text"],
            "x0": x0, "y0": y0_bl, "x1": x1, "y1": y1_bl,
            "cx": (x0 + x1) / 2.0, "cy": (y0_bl + y1_bl) / 2.0,
        })

    page_text = page.extract_text() or ""
    sheet_name = self._extract_sheet_name(page_text)

    walls = self._collect_wall_lines(page, H)

    candidates = []
    for r in page.rects or []:
        x0, y0, x1, y1 = self.__class__._rect_bottom_left_static(r, H)
        bw, bh = x1 - x0, y1 - y0
        if self.__class__.SYM_MIN_DIM <= max(bw, bh) <= self.__class__.SYM_MAX_DIM:
            candidates.append((x0, y0, x1, y1))
    for c in page.curves or []:
        pts = c.get("pts", [])
        if not pts:
            continue
        xs = [p[0] for p in pts]
        ys_bl = [H - y for y in [p[1] for p in pts]]
        bw = max(xs) - min(xs)
        bh = max(ys_bl) - min(ys_bl)
        if self.__class__.SYM_MIN_DIM <= max(bw, bh) <= self.__class__.SYM_MAX_DIM:
            candidates.append((min(xs), min(ys_bl), max(xs), max(ys_bl)))

    clusters = self._cluster_candidates(candidates)
    labels = self._find_device_labels(words)

    devices = []
    cluster_centers = [(cl["cx"], cl["cy"], cl) for cl in clusters]

    from extractor import (classify_device_type, _union_bbox, PAGE_MARGIN,
                           ON_WALL_THRESHOLD, WALL_DISTANCE_MAX_REPORT,
                           TITLE_BLOCK_MARGIN, TITLE_BLOCK_SHORT_LABEL,
                           LABEL_SYMBOL_MAX_DIST)
    import math

    for lbl in labels:
        lx, ly = lbl["cx"], lbl["cy"]
        if (lx < -PAGE_MARGIN or ly < -PAGE_MARGIN or
                lx > W + PAGE_MARGIN or ly > H + PAGE_MARGIN):
            continue

        best_sym = None
        best_dist = float("inf")
        for ccx, ccy, cl in cluster_centers:
            d = math.hypot(ccx - lx, ccy - ly)
            if d < best_dist:
                best_dist = d
                best_sym = cl

        symbol_attached = best_sym is not None and best_dist <= LABEL_SYMBOL_MAX_DIST

        sym_bbox = None
        sym_center = None
        num_drawings = 0
        attach_dist = 0.0
        if symbol_attached and best_sym is not None:
            sym_bbox = [round(best_sym["x0"], 2), round(best_sym["y0"], 2),
                        round(best_sym["x1"], 2), round(best_sym["y1"], 2)]
            sym_center = [round(best_sym["cx"], 2), round(best_sym["cy"], 2)]
            num_drawings = best_sym.get("num_parts", 0)
            attach_dist = round(best_dist, 2)

        scx, scy = sym_center if sym_center else (lx, ly)
        wall_pt, wall_dist = self._nearest_wall_point(scx, scy, walls)

        on_wall = None
        if wall_dist is not None:
            on_wall = wall_dist <= ON_WALL_THRESHOLD
            if wall_dist > WALL_DISTANCE_MAX_REPORT:
                wall_dist = None
                wall_pt = None
                on_wall = None
        wall_connection_point = wall_pt if on_wall else None
        wall_distance_pts = wall_dist

        device_center = list(sym_center) if sym_center else [round(lx, 2), round(ly, 2)]
        combined_bbox = _union_bbox(
            sym_bbox,
            [round(lbl["x0"], 2), round(lbl["y0"], 2), round(lbl["x1"], 2), round(lbl["y1"], 2)],
        )

        likely_artifact = None
        dcx, dcy = device_center
        near_edge = (dcx < TITLE_BLOCK_MARGIN or dcy < TITLE_BLOCK_MARGIN or
                     dcx > W - TITLE_BLOCK_MARGIN or dcy > H - TITLE_BLOCK_MARGIN)
        if near_edge:
            likely_artifact = len(lbl["text"]) <= TITLE_BLOCK_SHORT_LABEL

        dtype, subtype, conf = classify_device_type(sym_bbox, lbl["text"], legend_entries)

        devices.append({
            "page": page_num,
            "sheet_name": sheet_name,
            "device_type": dtype,
            "device_subtype": subtype,
            "device_label": lbl["text"],
            "text_bbox": [round(lbl["x0"], 2), round(lbl["y0"], 2),
                          round(lbl["x1"], 2), round(lbl["y1"], 2)],
            "text_center": [round(lbl["cx"], 2), round(lbl["cy"], 2)],
            "symbol_bbox": sym_bbox,
            "symbol_graphic_bbox": list(sym_bbox) if sym_bbox else None,
            "symbol_center": sym_center,
            "combined_bbox": combined_bbox,
            "center": list(device_center),
            "device_center": device_center,
            "on_wall": on_wall,
            "wall_connection_point": wall_connection_point,
            "wall_distance_pts": wall_distance_pts,
            "num_symbol_drawings": num_drawings,
            "distance_to_text": attach_dist,
            "symbol_attached": symbol_attached,
            "confidence": conf,
            "likely_title_block_artifact": likely_artifact,
            "source_sheet": sheet_name,
        })
    return devices


def _rect_bottom_left_static(rect, page_height):
    x0, x1 = rect["x0"], rect["x1"]
    y0_bl = page_height - rect["bottom"]
    y1_bl = page_height - rect["top"]
    return x0, y0_bl, x1, y1_bl


PDFDeviceExtractor._rect_bottom_left_static = staticmethod(_rect_bottom_left_static)
PDFDeviceExtractor.extract_devices_from_page = _patched_extract_page


def main():
    t0 = time.time()
    log(f"START {PDF}")
    with PDFDeviceExtractor(PDF) as extractor:
        log(f"Total pages: {extractor.total_pages}")
        legend_entries = extractor.extract_legend()
        log(f"Legend entries: {len(legend_entries)}")
        for e in legend_entries:
            log(f"  LEGEND p{e['source_page']} {e['device_type']}: {e['symbol_description'][:80]}")

        all_devices = []
        pages_with_devices = []
        device_id = 1

        for page_num in range(1, extractor.total_pages + 1):
            page_devices = extractor.extract_devices_from_page(page_num, legend_entries=legend_entries)
            if not page_devices:
                if page_num % 10 == 0:
                    log(f"  page {page_num}: 0 devices (elapsed {time.time()-t0:.0f}s)")
                continue
            pages_with_devices.append(page_num)
            log(f"  page {page_num}: {len(page_devices)} devices")
            for pd_ in page_devices:
                pd_["device_id"] = device_id
                device_id += 1
                all_devices.append(pd_)

        device_types_found = sorted({d["device_type"] for d in all_devices})
        from schema import DEVICE_TYPE_VOCABULARY
        vocab_set = set(DEVICE_TYPE_VOCABULARY)
        device_types_found = [t for t in device_types_found if t in vocab_set]

        total_devices = len(all_devices)
        high = sum(1 for d in all_devices if d.get("confidence") == "high")
        med = sum(1 for d in all_devices if d.get("confidence") == "medium")
        verified_count = high
        if extractor.is_scanned():
            dq_confidence = "low"
            dq_note = "Scanned/image PDF: devices[] is empty."
        elif total_devices == 0:
            dq_confidence = "low"
            dq_note = "No devices detected on any page."
        else:
            if high == total_devices:
                dq_confidence = "high"
            elif high + med == total_devices:
                dq_confidence = "medium"
            else:
                dq_confidence = "low"
            dq_note = (f"{total_devices} device(s) detected across "
                       f"{len(pages_with_devices)} page(s). "
                       f"{high} high, {med} medium, {total_devices-high-med} low-confidence.")

        result = {
            "source_file": extractor.source_file,
            "source_url": extractor.source_url,
            "total_pages": extractor.total_pages,
            "total_devices": total_devices,
            "coordinate_system": "PDF points, origin bottom-left",
            "page_size": list(extractor._page_size) if extractor._page_size else [],
            "extraction_method": extractor._extraction_method(),
            "legend_sheets_found": len(legend_entries) > 0,
            "symbol_legend_sheets": legend_entries,
            "pages_with_devices": pages_with_devices,
            "device_types_found": device_types_found,
            "data_quality": {
                "verified_device_count": verified_count,
                "confidence_overall": dq_confidence,
                "note": dq_note,
            },
            "limitations": [],
            "extraction_tool_version": "1.1.0-unified",
            "devices": all_devices,
            "schema_version": "1.1.0-unified",
        }

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(result, f, indent=2)

    log(f"WROTE {OUT}")
    log(f"Total devices: {total_devices}")
    log(f"Verified (high): {verified_count}")
    log(f"Pages with devices: {pages_with_devices}")
    log(f"Device types: {device_types_found}")
    log(f"Elapsed: {time.time()-t0:.1f}s")
    log("DONE")


if __name__ == "__main__":
    main()
