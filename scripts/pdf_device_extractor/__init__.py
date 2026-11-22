"""PDF device-extraction tool library for construction blueprint PDFs.

Unified v1.1.0 tool that subsumes the divergent schemas used by the
Caltrans, Kingsessing, UNT Willis, WashingtonDrawings, and C2 Drawings
extraction agents into a single canonical format.

This package provides a standardized, reusable tool for extracting device
coordinates (receptacles, switches, light fixtures, HVAC devices, fire-alarm
devices, etc.) from vector-based architectural/MEP/Electrical PDFs.

All coordinates are PDF points with origin at bottom-left. Page rotation is
handled automatically (derotation via pdfplumber). Scanned/image-only PDFs
are detected and degraded gracefully (no data fabrication).

Typical usage:
    from pdf_device_extractor import PDFDeviceExtractor

    with PDFDeviceExtractor("drawing_set.pdf") as extractor:
        result = extractor.extract_all()          # dict conforming to unified schema
        extractor.save_json("devices.json")       # also writes to disk

Or from the command line:
    python pdf_device_extractor/cli.py <pdf_path> <output_json_path>
"""

from extractor import PDFDeviceExtractor
from schema import (
    SCHEMA_VERSION,
    EXTRACTION_TOOL_VERSION,
    COORDINATE_SYSTEM,
    DEVICE_TYPE_VOCABULARY,
    DEVICE_TYPE_DESCRIPTIONS,
    VALID_CONFIDENCE,
    VALID_EXTRACTION_METHOD,
    Device,
    DataQuality,
    PDFDeviceExtractionResult,
    SymbolLegendEntry,
)

__all__ = [
    "PDFDeviceExtractor",
    "Device",
    "DataQuality",
    "PDFDeviceExtractionResult",
    "SymbolLegendEntry",
    "SCHEMA_VERSION",
    "EXTRACTION_TOOL_VERSION",
    "COORDINATE_SYSTEM",
    "DEVICE_TYPE_VOCABULARY",
    "DEVICE_TYPE_DESCRIPTIONS",
    "VALID_CONFIDENCE",
    "VALID_EXTRACTION_METHOD",
]

__version__ = SCHEMA_VERSION
