"""Unified JSON schema for construction PDF device extraction.

Version 1.1.0-unified — subsumes the divergent schemas used by the
Caltrans, Kingsessing, UNT Willis, WashingtonDrawings, and C2 Drawings
extraction agents into a single canonical format.

All coordinates are in PDF points with origin at bottom-left.
Page rotation is handled automatically (derotation via pdfplumber).
Dataclasses are used (stdlib only) so the library works without Pydantic.
"""

from dataclasses import dataclass, field, fields, is_dataclass
from typing import Any, Dict, List, Optional

SCHEMA_VERSION = "1.1.0-unified"
EXTRACTION_TOOL_VERSION = "1.1.0-unified"
COORDINATE_SYSTEM = "PDF points, origin bottom-left"

VALID_CONFIDENCE = {"high", "medium", "low"}
VALID_EXTRACTION_METHOD = {"vector", "scanned", "mixed"}

# Controlled vocabulary for device_type. Every device must use one of these
# values; "other" is the fallback for anything not listed. The vocabulary is a
# superset of the values used by every completed agent (Caltrans, Kingsessing,
# UNT Willis, WashingtonDrawings, C2 Drawings) so legacy data can be mapped in
# without information loss.
DEVICE_TYPE_VOCABULARY: List[str] = [
    "receptacle",
    "switch_single_pole",
    "switch_3way",
    "switch_4way",
    "switch_dimmer",
    "light_fixture",
    "hvac_thermostat",
    "hvac_sensor",
    "fire_alarm_smoke_detector",
    "fire_alarm_pull_station",
    "fire_sprinkler_head",
    "receptacle_gfci",
    "receptacle_usb",
    "junction_box",
    "pull_box",
    "pole",
    "luminaire",
    "fire_extinguisher",
    "exit_sign",
    "occupancy_sensor",
    "electric_unit_heater",
    "instantaneous_water_heater",
    "thermostat",
    "other",
]

DEVICE_TYPE_DESCRIPTIONS: Dict[str, str] = {
    "receptacle": "Standard electrical receptacle / outlet",
    "switch_single_pole": "Single-pole wall switch",
    "switch_3way": "3-way wall switch",
    "switch_4way": "4-way wall switch",
    "switch_dimmer": "Dimmer switch",
    "light_fixture": "General light fixture / luminaire symbol",
    "hvac_thermostat": "HVAC room thermostat",
    "hvac_sensor": "HVAC sensor (CO2, humidity, occupancy, etc.)",
    "fire_alarm_smoke_detector": "Fire alarm smoke detector",
    "fire_alarm_pull_station": "Fire alarm manual pull station",
    "fire_sprinkler_head": "Fire sprinkler head",
    "receptacle_gfci": "GFCI receptacle",
    "receptacle_usb": "USB receptacle",
    "junction_box": "Junction box",
    "pull_box": "Pull box",
    "pole": "Lighting / utility pole",
    "luminaire": "Luminaire (lighting fixture)",
    "fire_extinguisher": "Fire extinguisher",
    "exit_sign": "Exit sign / illuminated egress sign",
    "occupancy_sensor": "Occupancy / vacancy sensor",
    "electric_unit_heater": "Electric unit heater",
    "instantaneous_water_heater": "Instantaneous / tankless water heater",
    "thermostat": "Generic thermostat / temperature controller",
    "other": "Other / unclassified device",
}


def _dataclass_to_dict(obj: Any) -> Any:
    """Recursively convert dataclasses (and nested lists/dataclasses) to dicts."""
    if isinstance(obj, list):
        return [_dataclass_to_dict(i) for i in obj]
    if isinstance(obj, dict):
        return {k: _dataclass_to_dict(v) for k, v in obj.items()}
    if is_dataclass(obj):
        result: Dict[str, Any] = {}
        for f in fields(obj):
            result[f.name] = _dataclass_to_dict(getattr(obj, f.name))
        return result
    return obj


@dataclass
class SymbolLegendEntry:
    """A device-type definition taken from a symbol legend / schedule sheet."""

    device_type: str
    symbol_description: str
    source_sheet: str
    source_page: int


@dataclass
class DataQuality:
    """Quality metadata for the extraction result."""

    verified_device_count: int
    confidence_overall: str
    note: str


@dataclass
class Device:
    """A single detected device instance on a plan sheet.

    Coordinates are PDF points, bottom-left origin.
    """

    device_id: int
    page: int
    sheet_name: Optional[str] = None
    device_type: str = "other"
    device_subtype: Optional[str] = None
    device_label: str = ""
    text_bbox: Optional[List[float]] = None          # [x1, y1, x2, y2]
    text_center: Optional[List[float]] = None        # [x, y]
    symbol_bbox: Optional[List[float]] = None        # symbol graphic ONLY [x1, y1, x2, y2]
    symbol_graphic_bbox: Optional[List[float]] = None  # alias of symbol_bbox (C2 naming)
    symbol_center: Optional[List[float]] = None        # [x, y]
    combined_bbox: Optional[List[float]] = None        # symbol + text together [x1, y1, x2, y2]
    center: Optional[List[float]] = None               # alias for device_center [x, y]
    device_center: Optional[List[float]] = None        # [x, y] center of symbol (or text)
    on_wall: Optional[bool] = None
    wall_connection_point: Optional[List[float]] = None  # [x, y] point on wall centerline
    wall_distance_pts: Optional[float] = None          # distance to nearest wall (pts)
    num_symbol_drawings: int = 0
    symbol_attached: bool = False                      # vector glyph present near text label
    confidence: str = "low"                            # "high" | "medium" | "low"
    likely_title_block_artifact: Optional[bool] = None
    source_sheet: Optional[str] = None                 # sheet the device appears on


@dataclass
class PDFDeviceExtractionResult:
    """Top-level result of a full PDF device extraction, conforming to the
    unified schema (version ``SCHEMA_VERSION``)."""

    source_file: str
    source_url: Optional[str] = None
    total_pages: int = 0
    total_devices: int = 0
    coordinate_system: str = COORDINATE_SYSTEM
    page_size: List[float] = field(default_factory=list)
    extraction_method: str = "vector"
    legend_sheets_found: bool = False
    symbol_legend_sheets: List[SymbolLegendEntry] = field(default_factory=list)
    pages_with_devices: List[int] = field(default_factory=list)
    device_types_found: List[str] = field(default_factory=list)
    data_quality: Optional[DataQuality] = None
    limitations: List[str] = field(default_factory=list)
    extraction_tool_version: str = field(default=EXTRACTION_TOOL_VERSION)
    devices: List[Device] = field(default_factory=list)
    schema_version: str = field(default=SCHEMA_VERSION)

    def to_dict(self) -> Dict[str, Any]:
        return _dataclass_to_dict(self)

    def to_json(self, indent: int = 2) -> str:
        import json

        return json.dumps(self.to_dict(), indent=indent)
