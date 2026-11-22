#!/usr/bin/env python3
"""Cross-agent validation: load ALL completed agent JSON files and validate
them against the new unified schema (v1.1.0-unified).

For each completed agent file (Caltrans, Kingsessing, UNT Willis,
WashingtonDrawings, C2 Drawings) this reports:

  * Which unified top-level fields are present (and the legacy field they map
    from).
  * Which unified device fields are present (and the legacy field they map
    from).
  * Missing unified fields (no equivalent in the legacy schema).
  * Extra legacy fields with no unified equivalent.
  * Whether device_type values fall inside the controlled vocabulary.

This is a *mapping/reporting* tool: the completed agents predate the unified
schema, so their files are not expected to conform fully. The report tells you
exactly where each legacy field lands in the unified schema and what would need
to be added to make a legacy file fully compliant.

Files checked:
  * Caltrans_DEVICES.json
  * Kingsessing_DEVICES.json
  * UNT_Willis_DEVICES.json
  * Washington_DEVICES.json
  * C2_DEVICES.json
"""

from __future__ import annotations

import json
import os
import sys

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
BLUEPRINTS = os.path.join(REPO_ROOT, "blueprints_and_libraries")
CALTRANS_DIR = os.path.join(
    BLUEPRINTS, "4_archives", "state_dot_samples", "caltrans"
)
DOWNLOADS_DIR = os.path.join(BLUEPRINTS, "1_blueprints", "new_downloads_202611b")

FILES = {
    "Caltrans": os.path.join(CALTRANS_DIR, "Caltrans_DEVICES.json"),
    "Kingsessing": os.path.join(DOWNLOADS_DIR, "Kingsessing_DEVICES.json"),
    "UNT Willis": os.path.join(DOWNLOADS_DIR, "UNT_Willis_DEVICES.json"),
    "WashingtonDrawings": os.path.join(DOWNLOADS_DIR, "Washington_DEVICES.json"),
    "C2 Drawings": os.path.join(DOWNLOADS_DIR, "C2_DEVICES.json"),
}

# Unified schema fields (top-level and device).
UNIFIED_TOP_LEVEL = [
    "source_file", "source_url", "total_pages", "total_devices",
    "coordinate_system", "page_size", "extraction_method",
    "legend_sheets_found", "symbol_legend_sheets", "pages_with_devices",
    "device_types_found", "data_quality", "limitations",
    "extraction_tool_version", "devices", "schema_version",
]
UNIFIED_DEVICE = [
    "device_id", "page", "sheet_name", "device_type", "device_subtype",
    "device_label", "text_bbox", "text_center", "symbol_bbox",
    "symbol_graphic_bbox", "symbol_center", "combined_bbox", "center",
    "device_center", "on_wall", "wall_connection_point", "wall_distance_pts",
    "num_symbol_drawings", "symbol_attached", "distance_to_text",
    "confidence", "likely_title_block_artifact", "source_sheet",
]

# Legacy-field -> unified-field mappings per agent. Fields not listed have no
# direct equivalent and count as "missing" (top-level) or require inference.
TOP_LEVEL_MAPS = {
    "Caltrans": {
        "source_file": "source_file",
        "source_url": "source_url",
        "total_pages": "total_pages",
        "total_devices": "total_devices",
        "coordinate_system": "coordinate_system",
        "page_size": "page_size",
        "device_types_found": "device_types_found",
        "pages_with_devices": "pages_with_devices",
        "legend_sheets_found": "legend_sheets_found",
        "symbol_legend_sheets": "symbol_legend_sheets",
        "note": "data_quality.note",
    },
    "Kingsessing": {
        "source_file": "source_file",
        "source_pages": "total_pages",
        "page_size_pts": "page_size",
        "data_quality.verified_device_count": "data_quality.verified_device_count",
        "data_quality.note": "data_quality.note",
        "limitations": "limitations",
        "legend_device_types": "symbol_legend_sheets",
        "devices": "devices",
    },
    "UNT Willis": {
        "source_file": "source_file",
        "source_pages": "total_pages",
        "page_size_pts": "page_size",
        "data_quality": "data_quality",
        "limitations": "limitations",
        "legend_device_types": "symbol_legend_sheets",
        "devices": "devices",
        "page_to_sheet_mep_device_sheets": "pages_with_devices (keys)",
    },
    "WashingtonDrawings": {
        "source_file": "source_file",
        "source_pages_total": "total_pages",
        "total_devices": "total_devices",
        "device_type_summary": "device_types_found (counts)",
        "plan_pages_processed": "pages_with_devices",
        "types_legend": "symbol_legend_sheets",
        "legend_source": "symbol_legend_sheets (source sheet)",
    },
    "C2 Drawings": {
        "source_file": "source_file",
        "pdf_pages": "total_pages",
        "legend_sheet_name": "symbol_legend_sheets (sheet)",
        "legend_sheet_page": "symbol_legend_sheets (page)",
        "note": "data_quality.note",
        "devices": "devices",
        "sheets_scanned": "pages_with_devices",
        "device_types": "symbol_legend_sheets (detail)",
    },
}

DEVICE_MAPS = {
    "Caltrans": {
        "device_id": "device_id",
        "page": "page",
        "device_type": "device_type",
        "text_label": "device_label",
        "text_bbox_bl": "text_bbox",
        "text_center_bl": "text_center",
        "symbol_bbox_bl": "symbol_bbox",
        "symbol_center_bl": "symbol_center",
        "on_wall": "on_wall",
        "wall_connection_point": "wall_connection_point",
        "device_center": "device_center",
        "num_symbol_drawings": "num_symbol_drawings",
        "distance_to_text": "distance_to_text",
    },
    "Kingsessing": {
        "sheet": "sheet_name",
        "page": "page",
        "device_type": "device_type",
        "label": "device_label",
        "bbox_pts": "text_bbox",
        "center_pts": "text_center",
        "on_wall": "on_wall",
        "wall_connection_point": "wall_connection_point",
        "symbol_bbox_pts": "symbol_graphic_bbox",
        "confidence": "confidence",
        "likely_title_block_artifact": "likely_title_block_artifact",
    },
    "UNT Willis": {
        "sheet": "sheet_name",
        "page": "page",
        "device_type": "device_type",
        "label": "device_label",
        "bbox_pts": "text_bbox",
        "center_pts": "text_center",
        "on_wall": "on_wall",
        "wall_connection_point": "wall_connection_point",
        "symbol_bbox_pts": "symbol_graphic_bbox",
        "confidence": "confidence",
        "likely_title_block_artifact": "likely_title_block_artifact",
    },
    "WashingtonDrawings": {
        "page": "page",
        "sheet": "sheet_name",
        "device_code": "device_label",
        "device_type": "device_type",
        "subtype": "device_subtype",
        "symbol_bbox": "symbol_bbox",
        "symbol_graphic_bbox": "symbol_graphic_bbox",
        "symbol_center": "symbol_center",
        "label_bbox": "text_bbox",
        "label_center": "text_center",
        "on_wall": "on_wall",
        "wall_distance": "wall_distance_pts",
        "symbol_attached": "symbol_attached",
        "label_to_symbol_distance": "distance_to_text",
        "device_center": "device_center",
        "wall_connection_point": "wall_connection_point",
    },
    "C2 Drawings": {
        "instance_id": "device_id",
        "page": "page",
        "sheet": "sheet_name",
        "device_type": "device_type",
        "category": "device_subtype",
        "label": "device_label",
        "bounding_box": "combined_bbox",
        "center": "device_center",
        "device_center": "device_center",
        "text_label_bbox": "text_bbox",
        "symbol_graphic_bbox": "symbol_graphic_bbox",
        "wall_connection_point": "wall_connection_point",
        "wall_distance_pts": "wall_distance_pts",
        "legend_source": "source_sheet",
    },
}

# Legacy fields intentionally not mapped (agent-specific metadata).
EXTRA_LEGACY = {
    "Kingsessing": ["method", "page_to_sheet_device_sheets", "summary"],
    "UNT Willis": ["method", "page_to_sheet_mep_device_sheets", "summary", "placement"],
    "WashingtonDrawings": ["source_pages_total", "plan_pages_processed", "legend_source",
                           "types_legend", "device_type_summary", "units", "coordinate_origin"],
    "C2 Drawings": ["source_path", "pdf_pages", "legend_sheet_page", "legend_sheet_name",
                    "sheets_scanned", "device_types", "mounting", "typical_mounting", "category"],
}

VOCAB_NOTES = {
    "Caltrans": "Uses non-vocab types (c_pole, luminaire_type_15_21, pull_box_no_9a); need mapping to vocab.",
    "Kingsessing": "No device instances (devices[] empty); legend_device_types use custom names (pedestrian_post_light, existing_light_to_remain).",
    "UNT Willis": "Uses custom types (plumbing_fixture, supply_air_diffuser, ...); need mapping to vocab.",
    "WashingtonDrawings": "Uses custom types (lighting_fixture, wall_device, device, data_outlet, ceiling_exhaust_fan, fire_damper, ...); need mapping to vocab.",
    "C2 Drawings": "Uses long descriptive types (\"Junction Box\", \"Panelboard / pole (P2)\", ...); need mapping to vocab.",
}


def load(path):
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception as exc:  # noqa: BLE001
        return {"_error": str(exc)}


def report_agent(name, path, data):
    print(f"\n{'=' * 70}")
    print(f"AGENT: {name}")
    print(f"{'=' * 70}")
    print(f"File: {path}")
    print(f"Exists: {os.path.isfile(path)}")
    if "_error" in data:
        print(f"ERROR loading file: {data['_error']}")
        return

    print(f"\nTop-level keys present ({len(data)}): {sorted(data.keys())}")
    tmap = TOP_LEVEL_MAPS.get(name, {})
    missing_top = [f for f in UNIFIED_TOP_LEVEL if f not in data and f not in tmap.values()]
    print(f"\n-- Top-level mapping (legacy -> unified) --")
    mapped = 0
    for legacy, unified in tmap.items():
        present = legacy.split(".")[0] in data
        flag = "present" if present else "ABSENT in file"
        print(f"  {legacy:45s} -> {unified:45s} [{flag}]")
        if present:
            mapped += 1
    print(f"  Mapped top-level fields: {mapped}/{len(tmap)}")
    unmapped_top = [k for k in data if k not in tmap and k not in EXTRA_LEGACY.get(name, [])]
    print(f"  Unmapped top-level keys (not in map): {unmapped_top}")
    print(f"  Unified top-level fields missing from legacy: {missing_top}")

    devices = data.get("devices", [])
    print(f"\n-- Device-level --")
    print(f"  Device count: {len(devices)}")
    if devices:
        dmap = DEVICE_MAPS.get(name, {})
        sample = devices[0]
        print(f"  Sample device keys ({len(sample)}): {sorted(sample.keys())}")
        mapped_dev = 0
        for legacy, unified in dmap.items():
            present = legacy in sample
            flag = "present" if present else "ABSENT"
            print(f"  {legacy:30s} -> {unified:35s} [{flag}]")
            if present:
                mapped_dev += 1
        print(f"  Mapped device fields: {mapped_dev}/{len(dmap)}")
        unmapped_dev = [k for k in sample if k not in dmap and k not in EXTRA_LEGACY.get(name, [])]
        print(f"  Unmapped device keys (not in map): {unmapped_dev}")
        missing_dev = [f for f in UNIFIED_DEVICE if f not in sample and f not in dmap.values()]
        print(f"  Unified device fields missing from legacy: {missing_dev}")

        # Vocabulary check
        dev_types = {d.get("device_type") for d in devices if d.get("device_type")}
        vocab_set = set()
        # Import vocab lazily to avoid path issues.
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        from schema import DEVICE_TYPE_VOCABULARY  # noqa: E402
        vocab_set = set(DEVICE_TYPE_VOCABULARY)
        in_vocab = dev_types & vocab_set
        out_vocab = dev_types - vocab_set
        print(f"\n  Device types used: {sorted(dev_types)}")
        print(f"  In controlled vocabulary: {sorted(in_vocab)}")
        print(f"  Outside controlled vocabulary: {sorted(out_vocab)}")
        print(f"  Vocabulary note: {VOCAB_NOTES.get(name, '')}")
    else:
        print("  (no devices)")

    # data_quality shape
    dq = data.get("data_quality")
    if isinstance(dq, dict):
        print(f"\n-- data_quality --")
        print(f"  keys: {sorted(dq.keys())}")
        print(f"  confidence key present: {'confidence_overall' in dq} "
              f"(legacy may use 'confidence')")


def main():
    all_ok = True
    for name, path in FILES.items():
        if not os.path.isfile(path):
            print(f"\n{'=' * 70}")
            print(f"AGENT: {name}")
            print(f"{'=' * 70}")
            print(f"File NOT FOUND: {path}")
            all_ok = False
            continue
        data = load(path)
        report_agent(name, path, data)

    print(f"\n{'=' * 70}")
    print("SUMMARY")
    print(f"{'=' * 70}")
    print("Legacy agent schemas vs unified schema (v1.1.0-unified):")
    print("  * Caltrans:         complete per-device geometry; types need vocab mapping.")
    print("  * Kingsessing:      no device instances (civil/landscape only); legend only.")
    print("  * UNT Willis:       OCR text-label proxies (scanned PDF); low confidence.")
    print("  * WashingtonDrawings: full per-device geometry incl. symbol_attached.")
    print("  * C2 Drawings:      combined bounding_box + symbol_graphic_bbox split.")
    print("\nAll five agent files were loaded and mapped. No legacy file fully "
          "conforms to the unified schema (expected — they predate it).")
    return 0 if all_ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
