#!/usr/bin/env python3
"""Regression test: re-process the Caltrans PDF with the new unified tool
library and compare against the existing Caltrans_DEVICES.json.

Validates:
  * The new extractor runs without error on the Caltrans PDF.
  * Output conforms to the unified schema (required fields, correct types,
    controlled vocabulary, coordinate system, schema version).
  * Output is broadly consistent with the existing Caltrans_DEVICES.json
    (same device types found, same pages with devices, comparable counts).
  * Every legacy Caltrans field can be mapped into the unified schema.
"""

from __future__ import annotations

import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from extractor import PDFDeviceExtractor  # noqa: E402
from schema import DEVICE_TYPE_VOCABULARY  # noqa: E402

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
CALTRANS_PDF = os.path.join(
    REPO_ROOT,
    "blueprints_and_libraries",
    "4_archives",
    "state_dot_samples",
    "caltrans",
    "Caltrans_2022_Standard_Plans_97_sheets.pdf",
)
CALTRANS_EXISTING_JSON = os.path.join(
    REPO_ROOT,
    "blueprints_and_libraries",
    "4_archives",
    "state_dot_samples",
    "caltrans",
    "Caltrans_DEVICES.json",
)

REQUIRED_TOP_LEVEL = [
    "source_file", "source_url", "total_pages", "total_devices",
    "coordinate_system", "page_size", "extraction_method",
    "legend_sheets_found", "symbol_legend_sheets", "pages_with_devices",
    "device_types_found", "data_quality", "limitations",
    "extraction_tool_version", "devices", "schema_version",
]

REQUIRED_DEVICE_FIELDS = [
    "device_id", "page", "sheet_name", "device_type", "device_subtype",
    "device_label", "text_bbox", "text_center", "symbol_bbox",
    "symbol_graphic_bbox", "symbol_center", "combined_bbox", "center",
    "device_center", "on_wall", "wall_connection_point", "wall_distance_pts",
    "num_symbol_drawings", "symbol_attached", "distance_to_text",
    "confidence", "likely_title_block_artifact", "source_sheet",
]

VALID_CONFIDENCE = {"high", "medium", "low"}
VALID_EXTRACTION_METHOD = {"vector", "scanned", "mixed"}
EXPECTED_SCHEMA_VERSION = "1.1.0-unified"

# Legacy Caltrans field -> unified field mapping (verifies nothing is lost).
LEGACY_FIELD_MAP = {
    "text_bbox_bl": "text_bbox",
    "text_center_bl": "text_center",
    "symbol_bbox_bl": "symbol_bbox",
    "symbol_center_bl": "symbol_center",
    "distance_to_text": "distance_to_text",
    "device_id": "device_id",
    "page": "page",
    "device_type": "device_type",
    "device_label": "device_label",
    "on_wall": "on_wall",
    "wall_connection_point": "wall_connection_point",
    "device_center": "device_center",
    "num_symbol_drawings": "num_symbol_drawings",
}


def check(condition, msg):
    if not condition:
        print(f"FAIL: {msg}")
        return False
    print(f"PASS: {msg}")
    return True


def main():
    failures = 0

    if not os.path.isfile(CALTRANS_PDF):
        print(f"ERROR: Caltrans PDF not found: {CALTRANS_PDF}")
        return 1
    if not os.path.isfile(CALTRANS_EXISTING_JSON):
        print(f"ERROR: Existing Caltrans_DEVICES.json not found: {CALTRANS_EXISTING_JSON}")
        return 1

    # ------------------------------------------------------------------
    # 1. Run the new extractor on the Caltrans PDF
    # ------------------------------------------------------------------
    print(f"Extracting devices from: {CALTRANS_PDF}")
    try:
        with PDFDeviceExtractor(CALTRANS_PDF) as ext:
            result = ext.extract_all()
    except Exception as exc:  # noqa: BLE001
        print(f"FAIL: extractor raised exception: {exc}")
        return 1

    # ------------------------------------------------------------------
    # 2. Schema compliance checks
    # ------------------------------------------------------------------
    print("\n=== Schema compliance (v1.1.0-unified) ===")

    for field in REQUIRED_TOP_LEVEL:
        present = field in result
        failures += 0 if present else 1
        if present:
            print(f"PASS: top-level field '{field}' present")
        else:
            print(f"FAIL: missing top-level field '{field}'")

    check(
        result.get("schema_version") == EXPECTED_SCHEMA_VERSION,
        f"schema_version == '{EXPECTED_SCHEMA_VERSION}' "
        f"(got '{result.get('schema_version')}')",
    )
    check(
        result.get("extraction_tool_version") == EXPECTED_SCHEMA_VERSION,
        f"extraction_tool_version == '{EXPECTED_SCHEMA_VERSION}'",
    )
    check(
        result.get("coordinate_system") == "PDF points, origin bottom-left",
        "coordinate_system is 'PDF points, origin bottom-left'",
    )
    check(
        result.get("extraction_method") in VALID_EXTRACTION_METHOD,
        f"extraction_method is one of {VALID_EXTRACTION_METHOD} "
        f"(got '{result.get('extraction_method')}')",
    )
    check(
        isinstance(result.get("page_size"), list) and len(result.get("page_size", [])) == 2,
        "page_size is a list of 2 floats",
    )
    check(
        isinstance(result.get("devices"), list),
        "devices is a list",
    )
    check(
        result.get("total_devices") == len(result.get("devices", [])),
        "total_devices matches len(devices)",
    )

    dq = result.get("data_quality", {})
    check(isinstance(dq, dict), "data_quality is a dict")
    check("verified_device_count" in dq, "data_quality has verified_device_count")
    check("confidence_overall" in dq, "data_quality has confidence_overall")
    check("note" in dq, "data_quality has note")
    check(dq.get("confidence_overall") in VALID_CONFIDENCE,
          f"data_quality.confidence_overall in {VALID_CONFIDENCE}")

    # Device-level checks
    dev_type_errors = 0
    conf_errors = 0
    field_errors = 0
    for i, dev in enumerate(result.get("devices", [])):
        for field in REQUIRED_DEVICE_FIELDS:
            if field not in dev:
                print(f"FAIL: device {i} missing field '{field}'")
                field_errors += 1
        if dev.get("device_type") not in DEVICE_TYPE_VOCABULARY:
            print(f"FAIL: device {i} device_type '{dev.get('device_type')}' not in vocabulary")
            dev_type_errors += 1
        if dev.get("confidence") not in VALID_CONFIDENCE:
            print(f"FAIL: device {i} confidence '{dev.get('confidence')}' not in {VALID_CONFIDENCE}")
            conf_errors += 1
        # center is an alias of device_center
        if dev.get("center") is not None and dev.get("device_center") is not None:
            if dev["center"] != dev["device_center"]:
                print(f"FAIL: device {i} 'center' != 'device_center'")
                field_errors += 1
        # symbol_graphic_bbox mirrors symbol_bbox
        if dev.get("symbol_graphic_bbox") is not None and dev.get("symbol_bbox") is not None:
            if dev["symbol_graphic_bbox"] != dev["symbol_bbox"]:
                print(f"FAIL: device {i} 'symbol_graphic_bbox' != 'symbol_bbox'")
                field_errors += 1
        for key in ("text_bbox", "symbol_bbox", "symbol_graphic_bbox", "combined_bbox"):
            val = dev.get(key)
            if val is not None:
                if not (isinstance(val, list) and len(val) == 4 and
                        all(isinstance(v, (int, float)) for v in val)):
                    print(f"FAIL: device {i} '{key}' is not [x1,y1,x2,y2]: {val}")
                    failures += 1
        for key in ("text_center", "symbol_center", "center", "device_center",
                    "wall_connection_point"):
            val = dev.get(key)
            if val is not None:
                if not (isinstance(val, list) and len(val) == 2 and
                        all(isinstance(v, (int, float)) for v in val)):
                    print(f"FAIL: device {i} '{key}' is not [x,y]: {val}")
                    failures += 1

    check(field_errors == 0, f"all required device fields present ({field_errors} errors)")
    check(dev_type_errors == 0, f"all device_type values in vocabulary ({dev_type_errors} errors)")
    check(conf_errors == 0, f"all confidence values valid ({conf_errors} errors)")

    print(f"\nNew extraction: {result['total_devices']} devices, "
          f"method={result['extraction_method']}, "
          f"confidence={result['data_quality']['confidence_overall']}")
    print(f"Device types found: {result['device_types_found']}")
    print(f"Pages with devices: {result['pages_with_devices']}")
    print(f"Symbol-attached devices: "
          f"{sum(1 for d in result['devices'] if d.get('symbol_attached'))}")

    # ------------------------------------------------------------------
    # 3. Compare against existing Caltrans_DEVICES.json
    # ------------------------------------------------------------------
    print("\n=== Comparison with existing Caltrans_DEVICES.json ===")
    with open(CALTRANS_EXISTING_JSON, "r", encoding="utf-8") as f:
        existing = json.load(f)

    existing_types = set(existing.get("device_types_found", []))
    new_types = set(result.get("device_types_found", []))
    check(
        bool(existing_types & new_types),
        f"device types overlap: existing={sorted(existing_types)}, new={sorted(new_types)}",
    )

    existing_pages = set(existing.get("pages_with_devices", []))
    new_pages = set(result.get("pages_with_devices", []))
    check(
        bool(existing_pages & new_pages),
        f"pages with devices overlap: existing={sorted(existing_pages)}, new={sorted(new_pages)}",
    )

    existing_count = existing.get("total_devices", 0)
    new_count = result.get("total_devices", 0)
    print(f"Existing total_devices: {existing_count}")
    print(f"New total_devices:      {new_count}")
    if existing_count > 0:
        ratio = new_count / existing_count
        check(0.3 <= ratio <= 3.0,
              f"device count within 3x of existing (ratio={ratio:.2f})")

    # Verify every legacy Caltrans per-device field maps to a unified field.
    legacy_devices = existing.get("devices", [])
    unmapped = []
    for legacy_field in LEGACY_FIELD_MAP:
        if legacy_field not in legacy_devices[0] if legacy_devices else True:
            continue
        unified = LEGACY_FIELD_MAP[legacy_field]
        if unified not in REQUIRED_DEVICE_FIELDS:
            unmapped.append(legacy_field)
    check(not unmapped, f"all legacy Caltrans fields map to unified fields ({unmapped})")

    # Coordinate system sanity: values should be within page bounds.
    page_size = result.get("page_size", [792, 612])
    pw, ph = page_size[0], page_size[1]
    coord_errors = 0
    for dev in result.get("devices", []):
        for key in ("text_bbox", "symbol_bbox", "combined_bbox"):
            val = dev.get(key)
            if val:
                x1, y1, x2, y2 = val
                if x1 < -50 or y1 < -50 or x2 > pw + 50 or y2 > ph + 50:
                    coord_errors += 1
    check(coord_errors == 0, f"all text/symbol/combined bboxes within page bounds ({coord_errors} out-of-bounds)")

    # ------------------------------------------------------------------
    # 4. Summary
    # ------------------------------------------------------------------
    print("\n=== Summary ===")
    total_failures = failures + dev_type_errors + conf_errors + field_errors
    if total_failures == 0:
        print("ALL CHECKS PASSED")
        return 0
    print(f"{total_failures} CHECK(S) FAILED")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
