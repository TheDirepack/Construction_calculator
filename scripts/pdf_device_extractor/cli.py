#!/usr/bin/env python3
"""Command-line interface for the PDF device-extraction library.

Usage:
    python pdf_device_extractor/cli.py <pdf_path> <output_json_path>
    python pdf_device_extractor/cli.py <pdf_path> <output_json_path> \
        --legacy-format caltrans|kingsessing|washington|c2

Examples:
    python scripts/pdf_device_extractor/cli.py \\
        blueprints_and_libraries/4_archives/state_dot_samples/caltrans/Caltrans_2022_Standard_Plans_97_sheets.pdf \\
        /tmp/opencode/caltrans_devices.json

    # Emit output in the legacy Caltrans field-naming convention
    python scripts/pdf_device_extractor/cli.py drawings.pdf /tmp/opencode/out.json \
        --legacy-format caltrans
"""

from __future__ import annotations

import argparse
import json
import os
import sys

# Allow running both as a script (python pdf_device_extractor/cli.py) and
# as a module (python -m pdf_device_extractor.cli).
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from extractor import PDFDeviceExtractor  # noqa: E402
from schema import DEVICE_TYPE_VOCABULARY  # noqa: E402


# ---------------------------------------------------------------------------
# Legacy-format converters (map unified schema -> historical agent schemas)
# ---------------------------------------------------------------------------

def _to_caltrans_device(dev: Dict) -> Dict:
    out = {
        "device_id": dev.get("device_id"),
        "page": dev.get("page"),
        "device_type": dev.get("device_type"),
        "text_label": dev.get("device_label"),
        "text_bbox_bl": dev.get("text_bbox"),
        "text_center_bl": dev.get("text_center"),
        "symbol_bbox_bl": dev.get("symbol_bbox"),
        "symbol_center_bl": dev.get("symbol_center"),
        "on_wall": dev.get("on_wall"),
        "wall_connection_point": dev.get("wall_connection_point"),
        "device_center": dev.get("device_center"),
        "num_symbol_drawings": dev.get("num_symbol_drawings", 0),
        "distance_to_text": dev.get("distance_to_text"),
    }
    return out


def to_caltrans(result: Dict) -> Dict:
    return {
        "source_file": result.get("source_file"),
        "source_url": result.get("source_url"),
        "total_pages": result.get("total_pages"),
        "total_devices": result.get("total_devices"),
        "coordinate_system": result.get("coordinate_system"),
        "page_size": result.get("page_size"),
        "device_types_found": result.get("device_types_found"),
        "pages_with_devices": result.get("pages_with_devices"),
        "legend_sheets_found": result.get("legend_sheets_found"),
        "symbol_legend_sheets": result.get("symbol_legend_sheets"),
        "note": result.get("data_quality", {}).get("note") if isinstance(result.get("data_quality"), dict) else None,
        "devices": [_to_caltrans_device(d) for d in result.get("devices", [])],
    }


def _to_kingsessing_device(dev: Dict) -> Dict:
    return {
        "sheet": dev.get("sheet_name"),
        "page": dev.get("page"),
        "device_type": dev.get("device_type"),
        "label": dev.get("device_label"),
        "bbox_pts": dev.get("text_bbox"),
        "center_pts": dev.get("text_center"),
        "placement": "text_label_proxy" if dev.get("likely_title_block_artifact") else "symbol",
        "on_wall": dev.get("on_wall"),
        "wall_connection_point": dev.get("wall_connection_point"),
        "symbol_bbox_pts": dev.get("symbol_graphic_bbox"),
        "confidence": dev.get("confidence"),
        "likely_title_block_artifact": dev.get("likely_title_block_artifact"),
    }


def to_kingsessing(result: Dict) -> Dict:
    dq = result.get("data_quality", {}) or {}
    devices = result.get("devices", [])
    by_type: Dict[str, int] = {}
    by_sheet: Dict[str, int] = {}
    for d in devices:
        by_type[d.get("device_type", "other")] = by_type.get(d.get("device_type", "other"), 0) + 1
        sheet = d.get("sheet_name") or "unknown"
        by_sheet[sheet] = by_sheet.get(sheet, 0) + 1
    return {
        "source_file": result.get("source_file"),
        "source_pages": result.get("total_pages"),
        "page_size_pts": result.get("page_size"),
        "coordinate_origin": "bottom-left",
        "units": "PDF points",
        "method": result.get("extraction_method"),
        "data_quality": {
            "verified_device_count": dq.get("verified_device_count", 0),
            "note": dq.get("note", ""),
        },
        "limitations": result.get("limitations", []),
        "legend_device_types": result.get("symbol_legend_sheets", []),
        "page_to_sheet_device_sheets": {},  # best-effort; not reconstructed here
        "devices": [_to_kingsessing_device(d) for d in devices],
        "summary": {
            "verified_device_count": dq.get("verified_device_count", 0),
            "total_device_instances": len(devices),
            "device_plan_sheets_processed": len(result.get("pages_with_devices", [])),
            "pages_scanned": result.get("total_pages", 0),
            "by_type": by_type,
            "by_sheet": by_sheet,
            "device_categories_found": result.get("device_types_found", []),
            "device_categories_absent_from_part1": [
                t for t in DEVICE_TYPE_VOCABULARY if t not in set(result.get("device_types_found", []))
            ],
        },
    }


def _to_washington_device(dev: Dict) -> Dict:
    return {
        "page": dev.get("page"),
        "sheet": dev.get("sheet_name"),
        "device_code": dev.get("device_label"),
        "device_type": dev.get("device_type"),
        "subtype": dev.get("device_subtype"),
        "symbol_bbox": dev.get("symbol_bbox"),
        "symbol_center": dev.get("symbol_center"),
        "label_bbox": dev.get("text_bbox"),
        "label_center": dev.get("text_center"),
        "on_wall": dev.get("on_wall"),
        "wall_distance": dev.get("wall_distance_pts"),
        "symbol_attached": dev.get("symbol_attached", False),
        "label_to_symbol_distance": dev.get("distance_to_text"),
        "device_center": dev.get("device_center"),
        "wall_connection_point": dev.get("wall_connection_point"),
    }


def to_washington(result: Dict) -> Dict:
    return {
        "source_file": result.get("source_file"),
        "total_devices": result.get("total_devices"),
        "units": "PDF points (origin bottom-left)",
        "coordinate_origin": "bottom-left",
        "device_type_summary": {
            t: sum(1 for d in result.get("devices", []) if d.get("device_type") == t)
            for t in result.get("device_types_found", [])
        },
        "devices": [_to_washington_device(d) for d in result.get("devices", [])],
    }


def _to_c2_device(dev: Dict) -> Dict:
    return {
        "instance_id": dev.get("device_id"),
        "page": dev.get("page"),
        "sheet": dev.get("sheet_name"),
        "device_type": dev.get("device_type"),
        "category": dev.get("device_subtype") or "other",
        "typical_mounting": "wall" if dev.get("on_wall") else "ceiling",
        "legend_source": dev.get("source_sheet"),
        "label": dev.get("device_label"),
        "bounding_box": dev.get("combined_bbox"),
        "center": dev.get("device_center"),
        "device_center": dev.get("device_center"),
        "text_label_bbox": dev.get("text_bbox"),
        "symbol_graphic_bbox": dev.get("symbol_graphic_bbox"),
        "mounting": "wall" if dev.get("on_wall") else "freestanding",
        "wall_connection_point": dev.get("wall_connection_point"),
        "wall_distance_pts": dev.get("wall_distance_pts"),
    }


def to_c2(result: Dict) -> Dict:
    return {
        "source_file": result.get("source_file"),
        "note": "Legacy C2-format conversion from unified schema v1.1.0-unified.",
        "devices": [_to_c2_device(d) for d in result.get("devices", [])],
    }


LEGACY_CONVERTERS = {
    "caltrans": to_caltrans,
    "kingsessing": to_kingsessing,
    "washington": to_washington,
    "c2": to_c2,
}


def convert_legacy(result: Dict, fmt: str) -> Dict:
    converter = LEGACY_CONVERTERS.get(fmt.lower())
    if converter is None:
        raise ValueError(f"Unknown legacy format: {fmt!r}")
    return converter(result)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Extract device coordinates from a construction PDF.",
    )
    parser.add_argument("pdf_path", help="Path to the source PDF file.")
    parser.add_argument("output_path", help="Path to write the result JSON.")
    parser.add_argument(
        "--legacy-format",
        choices=sorted(LEGACY_CONVERTERS),
        help=(
            "Emit output in a legacy agent schema (caltrans, kingsessing, "
            "washington, c2) instead of the unified schema."
        ),
    )
    parser.add_argument(
        "--source-url",
        help="Optional source URL to record in the output.",
    )
    args = parser.parse_args(argv)

    if not os.path.isfile(args.pdf_path):
        print(f"Error: PDF not found: {args.pdf_path}", file=sys.stderr)
        return 1

    try:
        with PDFDeviceExtractor(args.pdf_path) as extractor:
            if args.source_url:
                extractor.source_url = args.source_url
            result = extractor.extract_all()

            if args.legacy_format:
                result = convert_legacy(result, args.legacy_format)

            extractor.save_json(args.output_path)
    except Exception as exc:  # noqa: BLE001
        print(f"Error during extraction: {exc}", file=sys.stderr)
        return 1

    # Note: after conversion, `result` may be a legacy dict; print a concise
    # summary using unified keys when available.
    print(f"Source file:    {result.get('source_file')}")
    print(f"Total pages:    {result.get('total_pages')}")
    print(f"Total devices:  {result.get('total_devices')}")
    if result.get("extraction_method"):
        print(f"Method:         {result.get('extraction_method')}")
    print(f"Pages w/devices: {result.get('pages_with_devices')}")
    print(f"Device types:   {result.get('device_types_found')}")
    dq = result.get("data_quality")
    if isinstance(dq, dict):
        print(f"Confidence:     {dq.get('confidence_overall', dq.get('confidence'))}")
    print(f"Output:         {args.output_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
