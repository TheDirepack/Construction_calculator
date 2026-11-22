# PDF Device-Extraction Tool Library

Standardized, reusable tool for extracting device coordinates (receptacles,
switches, light fixtures, HVAC devices, fire-alarm devices, etc.) from
vector-based architectural/MEP/Electrical construction PDFs.

**Version:** `1.1.0-unified` (schema `1.1.0-unified`)

This library unifies the five divergent, agent-specific device-extraction
schemas (Caltrans, Kingsessing, UNT Willis, WashingtonDrawings, C2 Drawings)
into a single canonical schema, incorporating every best practice each agent
demonstrated:

| Agent | Contributions adopted |
|-------|----------------------|
| Caltrans | text/symbol bboxes, wall connection points, num_symbol_drawings, distance_to_text |
| Kingsessing | data_quality, limitations, per-sheet mapping, legend_device_types, summary |
| UNT Willis | scanned-PDF handling, likely_title_block_artifact, low-confidence proxies |
| WashingtonDrawings | symbol_attached flag, wall_distance, device_code/sheet, label & symbol bboxes |
| C2 Drawings | combined_bbox (symbol+text), symbol_graphic_bbox (symbol only), wall_distance_pts, category, mounting |

**Stack:** `pdfplumber` + `pypdf` only. No vision tools, no external API
calls, no internet required.

**Coordinates:** All values are PDF points with origin at bottom-left.
Page rotation (90/180/270) is detected and derotated automatically via
pdfplumber.

---

## Location

```
scripts/pdf_device_extractor/
├── __init__.py        # Package init; exports main classes/functions
├── schema.py          # Unified JSON schema (dataclasses), v1.1.0-unified
├── extractor.py       # PDFDeviceExtractor class
├── cli.py             # Command-line interface
├── README.md          # This file
└── test_on_caltrans.py  # Regression test against existing Caltrans_DEVICES.json
test_all_completed.py      # Validates all completed agent JSONs against the schema
```

---

## Quick start

### Command line

```bash
python scripts/pdf_device_extractor/cli.py <pdf_path> <output_json_path>
```

Example:

```bash
python scripts/pdf_device_extractor/cli.py \
    blueprints_and_libraries/4_archives/state_dot_samples/caltrans/Caltrans_2022_Standard_Plans_97_sheets.pdf \
    /tmp/opencode/caltrans_devices.json
```

Prints a summary (pages, devices, method, confidence) and writes the unified
schema JSON to `<output_json_path>`.

### Legacy output mode

Emit output in a historical agent's field-naming convention:

```bash
python scripts/pdf_device_extractor/cli.py <pdf_path> <out.json> \
    --legacy-format caltrans   # or: kingsessing | washington | c2
```

### Python API

```python
from pdf_device_extractor import PDFDeviceExtractor, DEVICE_TYPE_VOCABULARY

with PDFDeviceExtractor("drawing_set.pdf") as extractor:
    if extractor.is_scanned():
        print("Warning: scanned PDF — device coordinates unavailable without vision tools")
    result = extractor.extract_all()          # dict conforming to unified schema
    extractor.save_json("devices.json")       # also writes to disk

# Validate against the controlled vocabulary
for device in result["devices"]:
    assert device["device_type"] in DEVICE_TYPE_VOCABULARY, device["device_type"]

print(f"Extracted {result['total_devices']} devices from {result['total_pages']} pages")
print(f"Method: {result['extraction_method']}, Confidence: {result['data_quality']['confidence_overall']}")
```

`PDFDeviceExtractor` is a context manager (calls `.close()` on exit). You can
also instantiate and call `.close()` manually.

Key methods:

| Method | Returns | Description |
|--------|---------|-------------|
| `is_scanned(page_num=None)` | `bool` | True if a page (or whole PDF) is scanned/image-only |
| `detect_page_rotation(page)` | `int` | Raw `/Rotate` value (0/90/180/270) |
| `extract_legend()` | `list[dict]` | Find legend/schedule sheets, extract device-type definitions |
| `is_wall_line(vector_obj, page_height)` | `bool` | True if a vector rect/line/curve looks like a wall |
| `is_symbol_attached(text_bbox, symbol_candidates, page)` | `bool` | Vector glyph near the text label? |
| `detect_wall_connection(symbol_bbox, wall_lines, page)` | `list[float]\|None` | Point on nearest wall centerline closest to symbol |
| `classify_device_type(symbol_glyph, nearby_text, legend)` | `(type, subtype, conf)` | Controlled-vocab classification |
| `extract_devices_from_page(page_num)` | `list[dict]` | Extract all devices from one 1-based page |
| `extract_all()` | `dict` | Full extraction; returns the unified schema dict |
| `save_json(output_path)` | `None` | Save full extraction result as JSON |

---

## Unified JSON schema (version 1.1.0-unified)

All coordinates are PDF points, origin bottom-left.

### Top-level object

| Field | Type | Description |
|-------|------|-------------|
| `source_file` | `str` | Basename of the source PDF |
| `source_url` | `str \| null` | Source URL, if known (optional) |
| `total_pages` | `int` | Number of pages in the PDF |
| `total_devices` | `int` | Total number of devices detected |
| `coordinate_system` | `str` | Always `"PDF points, origin bottom-left"` |
| `page_size` | `list[float]` | `[width, height]` in PDF points (first page) |
| `extraction_method` | `str` | `"vector" \| "scanned" \| "mixed"` |
| `legend_sheets_found` | `bool` | Whether any legend/schedule sheets were detected |
| `symbol_legend_sheets` | `list[object]` | Device-type definitions from legend sheets |
| `pages_with_devices` | `list[int]` | 1-based page numbers containing devices |
| `device_types_found` | `list[str]` | Device-type vocabulary values actually detected |
| `data_quality` | `object` | `{verified_device_count, confidence_overall, note}` |
| `limitations` | `list[str]` | Human-readable limitations / caveats |
| `extraction_tool_version` | `str` | `"1.1.0-unified"` |
| `devices` | `list[object]` | Device instances (see below) |
| `schema_version` | `str` | Schema version, e.g. `"1.1.0-unified"` |

### `symbol_legend_sheets` entries

| Field | Type | Description |
|-------|------|-------------|
| `device_type` | `str` | Controlled-vocabulary device type |
| `symbol_description` | `str` | Human-readable symbol description |
| `source_sheet` | `str` | Sheet identifier where the legend entry appears |
| `source_page` | `int` | 1-based page number of the legend sheet |

### `data_quality`

| Field | Type | Description |
|-------|------|-------------|
| `verified_device_count` | `int` | Count of devices with `"high"` confidence |
| `confidence_overall` | `str` | Overall `"high" \| "medium" \| "low"` |
| `note` | `str` | Human-readable quality note |

### Device object

| Field | Type | Description |
|-------|------|-------------|
| `device_id` | `int` | Sequential ID across the whole document |
| `page` | `int` | 1-based page number |
| `sheet_name` | `str \| null` | Sheet the device appears on |
| `device_type` | `str` | Controlled vocabulary (see below) |
| `device_subtype` | `str \| null` | More specific subtype (e.g. `"GFCI receptacle"`) |
| `device_label` | `str` | Text label found near the symbol |
| `text_bbox` | `list[float] \| null` | `[x1, y1, x2, y2]` text bbox, PDF points |
| `text_center` | `list[float] \| null` | `[x, y]` text center, PDF points |
| `symbol_bbox` | `list[float] \| null` | `[x1, y1, x2, y2]` symbol graphic **only**, PDF points |
| `symbol_graphic_bbox` | `list[float] \| null` | Same as `symbol_bbox` (C2 naming) |
| `symbol_center` | `list[float] \| null` | `[x, y]` symbol center, PDF points |
| `combined_bbox` | `list[float] \| null` | `[x1, y1, x2, y2]` symbol + text together (C2 `bounding_box`) |
| `center` | `list[float] \| null` | Alias for `device_center`, `[x, y]` |
| `device_center` | `list[float] \| null` | `[x, y]` center of symbol (or text if no symbol) |
| `on_wall` | `bool \| null` | True if symbol is on/near a wall |
| `wall_connection_point` | `list[float] \| null` | `[x, y]` point on wall centerline, or null |
| `wall_distance_pts` | `float \| null` | Distance to nearest wall, PDF points |
| `num_symbol_drawings` | `int` | Count of vector drawing primitives in the symbol |
| `symbol_attached` | `bool` | Vector glyph present near the text label (Washington found 49%) |
| `distance_to_text` | `float` | Distance from symbol center to text label (pts) |
| `confidence` | `str` | `"high" \| "medium" \| "low"` |
| `likely_title_block_artifact` | `bool \| null` | Likely title-block / border text, not a device |
| `source_sheet` | `str \| null` | Sheet the device appears on (mirrors `sheet_name`) |

---

## Coordinate system

* **Unit:** PDF points (1 point = 1/72 inch)
* **Origin:** Bottom-left of the page; Y increases upward
* **Rotation:** pdfplumber derotates pages automatically; all returned
  coordinates are in the upright, derotated coordinate system. The raw `/Rotate`
  value can be read via `detect_page_rotation(page)`.

pdfplumber uses a top-origin (`top=0` at top of page) internally. This library
converts to bottom-left via `y_bl = page.height - y_top` before any geometry
or matching is performed.

---

## Controlled vocabulary for `device_type`

Every `device_type` must be one of these values. `"other"` is the fallback
for anything not listed. The vocabulary is a superset of every value used by
the five completed agents.

| Value | Description |
|-------|-------------|
| `receptacle` | Standard electrical receptacle / outlet |
| `switch_single_pole` | Single-pole wall switch |
| `switch_3way` | 3-way wall switch |
| `switch_4way` | 4-way wall switch |
| `switch_dimmer` | Dimmer switch |
| `light_fixture` | General light fixture / luminaire symbol |
| `hvac_thermostat` | HVAC room thermostat |
| `hvac_sensor` | HVAC sensor (CO2, humidity, occupancy, etc.) |
| `fire_alarm_smoke_detector` | Fire alarm smoke detector |
| `fire_alarm_pull_station` | Fire alarm manual pull station |
| `fire_sprinkler_head` | Fire sprinkler head |
| `receptacle_gfci` | GFCI receptacle |
| `receptacle_usb` | USB receptacle |
| `junction_box` | Junction box |
| `pull_box` | Pull box |
| `pole` | Lighting / utility pole |
| `luminaire` | Luminaire (lighting fixture) |
| `fire_extinguisher` | Fire extinguisher |
| `exit_sign` | Exit sign / illuminated egress sign |
| `occupancy_sensor` | Occupancy / vacancy sensor |
| `electric_unit_heater` | Electric unit heater |
| `instantaneous_water_heater` | Instantaneous / tankless water heater |
| `thermostat` | Generic thermostat / temperature controller |
| `other` | Other / unclassified device |

---

## How to add new device types

1. **Add the vocabulary value** to `DEVICE_TYPE_VOCABULARY` in `schema.py`
   (and a description in `DEVICE_TYPE_DESCRIPTIONS`).
2. **Add a classification rule** to `_CLASSIFICATION_RULES` in `extractor.py`.
   Rules are `(pattern, device_type, device_subtype, confidence)` tuples,
   checked in order — more specific patterns first.
3. **Update this README** if the new type should appear in the vocabulary table.

Example:

```python
_add_rule(r"\bfire.alarm.horn\b|\bhorn\b|\bstrobe\b",
          "fire_alarm_smoke_detector", "fire alarm horn/strobe", "medium")
```

---

## Known limitations

* **Scanned PDFs:** Device symbols and walls embedded in scanned page images
  cannot be located or classified without image-analysis/vision tooling.
  `devices[]` is left empty and `limitations` explains the gap; no data is
  fabricated. Mixed PDFs process vector pages normally and skip scanned pages.
* **Dense hatching / complex linework:** Compact symbol graphics embedded in
  dense site hatching (e.g. lighting plans) may not be segmentable into
  discrete symbol clusters.
* **No vision tool:** Symbol-shape classification and title-block vs device
  disambiguation are heuristic. Per-device `confidence` and
  `likely_title_block_artifact` should be treated as best-effort.
* **Legend extraction:** Device-type definitions are recovered by keyword
  heuristics on legend/schedule sheets; results are best-effort and may be
  incomplete.

---

## Changelog: v1.0.0 → v1.1.0 (unification)

* **v1.0.0** — Initial unified schema based on the Caltrans, Kingsessing, and
  UNT Willis agents. Field names used `_bl` suffixes; `data_quality.confidence`;
  vocabulary lacked life-safety and HVAC-equipment types.
* **v1.1.0-unified** — Subsumes all five completed agents:
  * Renamed `data_quality.confidence` → `confidence_overall`; added
    `extraction_tool_version` (`"1.1.0-unified"`); `schema_version` →
    `1.1.0-unified`.
  * Added per-device fields: `sheet_name`, `symbol_graphic_bbox`,
    `combined_bbox`, `center` (alias of `device_center`),
    `wall_distance_pts`, `symbol_attached`, `likely_title_block_artifact`,
    `source_sheet`.
  * Expanded controlled vocabulary with `fire_extinguisher`, `exit_sign`,
    `occupancy_sensor`, `electric_unit_heater`,
    `instantaneous_water_heater`, `thermostat`.
  * New extractor methods: `is_scanned(page_num)`, `detect_page_rotation`,
    `is_wall_line`, `detect_wall_connection(...)`, `is_symbol_attached(...)`,
    `classify_device_type(...)`.
  * CLI gained optional `--legacy-format caltrans|kingsessing|washington|c2`
    to emit historical agent schemas.

---

## Testing

Run the Caltrans regression test:

```bash
python scripts/pdf_device_extractor/test_on_caltrans.py
```

Run the cross-agent validation test:

```bash
python scripts/pdf_device_extractor/test_all_completed.py
```
