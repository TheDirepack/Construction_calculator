"""Data model for the PDF training annotator.

Annotation JSON is the *existing* dataset format (see dataset_annotation_example.json),
extended in a backward-compatible way:

* axis-aligned boxes keep `bbox_xyxy`.
* rotated boxes add `bbox_rotated = {cx, cy, w, h, angle_deg}` where angle is the
  in-plane rotation of the box about (cx, cy). `bbox_xyxy` is still written as the
  tight axis-aligned hull so old readers keep working.
* `snap_vector` stores the extra (origin + direction) vector used to bias the
  wall-snap search. Optional.
* `device_type` records the classified device family.
* `device_attributes` optionally stores structured electrical/device attributes
  (see blueprint_symbols/electrical_device_attributes.py).

Every extension is additive; a file without them loads fine.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any
import math
import json
import os
import tempfile


FAMILY_DISCIPLINES: dict[str, str] = {
    # electrical
    "receptacle": "electrical", "switch": "electrical", "luminaire": "electrical",
    "junction_box": "electrical", "panelboard": "electrical", "transformer": "electrical",
    "disconnect": "electrical", "circuit_breaker": "electrical", "motor": "electrical",
    "generator": "electrical", "fire_alarm_device": "electrical", "data_outlet": "electrical",
    "telecom_device": "electrical", "conduit_fitting": "electrical", "grounding_symbol": "electrical",
    "electrical_meter": "electrical", "control_relay": "electrical", "sensor": "electrical",
    # mechanical
    "valve": "mechanical", "pump": "mechanical", "pipe_fitting": "mechanical",
    "pipe_connection": "mechanical", "strainer": "mechanical", "pressure_gauge": "mechanical",
    "flow_meter": "mechanical", "actuator": "mechanical", "heat_exchanger": "mechanical",
    "mechanical_equipment": "mechanical", "equipment_connection": "mechanical", "instrument": "mechanical",
    # hvac
    "air_terminal": "hvac", "diffuser": "hvac", "grille": "hvac", "fan": "hvac",
    "damper": "hvac", "fire_damper": "hvac", "thermostat": "hvac",
    "temperature_sensor": "hvac", "vav_terminal": "hvac", "air_handler": "hvac",
    "fan_coil": "hvac", "coil": "hvac", "duct_fitting": "hvac", "duct_connection": "hvac",
    "exhaust_hood": "hvac",
    # plumbing
    "plumbing_fixture": "plumbing", "drain": "plumbing", "water_heater": "plumbing",
    "plumbing_valve": "plumbing",
    # architectural
    "door": "architectural", "window": "architectural", "staircase": "architectural",
    "elevator": "architectural", "structural_column": "architectural",
    # fire protection
    "fire_protection_device": "fire_protection", "sprinkler_head": "fire_protection",
    "alarm_device": "fire_protection",
    # other
    "unknown_symbol": "other",
}


# Curated subtypes aligned with blueprint_symbols/neca_prompt_catalog.py and
# blueprint_symbols/electrical_device_attributes.py. Users can still enter any
# project-specific custom subtype in the UI.
FAMILY_VARIANT_SUGGESTIONS: dict[str, list[str]] = {
    # Electrical
    "receptacle": [
        "duplex", "quad", "single", "gfci", "weatherproof", "duplex_receptacle_floor",
        "duplex_receptacle_ceiling", "duplex_receptacle_emergency", "combination_floor_box",
        "multioutlet_assembly", "special_purpose", "dedicated", "afci", "unspecified",
    ],
    "switch": [
        "single_pole", "three_way", "four_way", "double_pole", "dimmer", "occupancy_sensor",
        "vacancy_sensor", "timer", "low_voltage", "key_operated", "pilot_light",
        "weatherproof", "motor_control", "double_throw", "unspecified",
    ],
    "luminaire": [
        "downlight", "troffer_2x4", "troffer_2x2", "linear", "strip_luminaire", "exit_sign",
        "emergency_battery_light", "wall_sconce", "wallpack", "pendant", "track_light",
        "spotlight", "pole_luminaire", "unspecified",
    ],
    "fire_alarm_device": [
        "smoke_detector", "heat_detector", "duct_smoke_detector", "horn_and_strobe",
        "speaker_and_strobe", "strobe_unit", "horn_unit", "manual_pull_station",
        "bell_and_strobe", "fire_alarm_control_panel", "annunciator_panel",
        "beam_smoke_detector", "carbon_monoxide_detector", "tamper_switch",
        "waterflow_switch", "door_holder", "unspecified",
    ],
    "data_outlet": [
        "data_wall", "telephone_data_wall", "telephone_wall", "data_floor",
        "telephone_data_floor", "fiber", "coax", "audio_visual", "wap", "unspecified",
    ],
    "telecom_device": [
        "equipment_rack", "equipment_cabinet", "terminal_cabinet", "intercom",
        "speaker", "camera", "card_reader", "nurse_call", "unspecified",
    ],
    "junction_box": [
        "wall", "ceiling", "floor", "pull_box", "combination_floor_box",
        "power_pole", "telecom_pole", "unspecified",
    ],
    "panelboard": [
        "lighting_or_power_panel", "distribution_panel", "subpanel", "switchboard",
        "mcc", "transfer_switch", "unspecified",
    ],
    "disconnect": [
        "non_fused", "fused", "motor_rated", "weatherproof", "magnetic_motor_starter",
        "vfd_asd", "unspecified",
    ],
    "circuit_breaker": ["single_pole", "two_pole", "three_pole", "gfci", "afci", "unspecified"],
    "transformer": ["dry_type", "pad_mounted", "step_down", "step_up", "unspecified"],
    "motor": ["single_phase", "three_phase", "fractional_hp", "motor_connection", "unspecified"],
    "generator": ["standby", "emergency", "transfer_switch", "unspecified"],
    "sensor": [
        "occupancy_sensor", "vacancy_sensor", "daylight_sensor", "photocell",
        "motion_detector", "flow_switch", "pressure_switch", "unspecified",
    ],
    "control_relay": ["lighting_relay", "contactor", "shunt_trip", "time_clock", "unspecified"],
    "electrical_meter": ["kwh_meter", "submeter", "ct_cabinet", "unspecified"],
    "grounding_symbol": ["ground_rod", "ground_bus", "bonding_jumper", "unspecified"],
    "conduit_fitting": ["stub_up", "stub_down", "homerun", "flexible_connection", "unspecified"],
    # Mechanical / HVAC / Plumbing / Fire Protection / Architectural
    "diffuser": ["supply", "return", "exhaust", "linear_slot", "transfer", "unspecified"],
    "grille": ["return", "exhaust", "supply", "transfer", "louver", "unspecified"],
    "air_terminal": ["vav_box", "cav_box", "fan_powered", "unspecified"],
    "damper": ["volume", "motorized", "backdraft", "fire_smoke", "unspecified"],
    "fire_damper": ["fire", "smoke", "combination_fire_smoke", "unspecified"],
    "fan": ["exhaust", "supply", "ceiling_fan", "wall_fan", "inline", "unspecified"],
    "thermostat": ["wall_thermostat", "programmable", "humidistat", "unspecified"],
    "valve": ["gate", "ball", "butterfly", "check", "balancing", "control", "prv", "unspecified"],
    "pump": ["centrifugal", "circulator", "sump", "booster", "condensate", "unspecified"],
    "sprinkler_head": ["pendent", "upright", "sidewall", "concealed", "dry_pendent", "unspecified"],
    "alarm_device": ["horn_strobe", "bell", "waterflow", "tamper", "pull_station", "unspecified"],
    "fire_protection_device": ["fire_extinguisher", "hose_cabinet", "fdc", "standpipe", "unspecified"],
    "plumbing_fixture": ["water_closet", "lavatory", "sink", "urinal", "shower", "drinking_fountain", "unspecified"],
    "drain": ["floor_drain", "roof_drain", "trench_drain", "cleanout", "unspecified"],
    "door": ["single_swing", "double_swing", "sliding", "revolving", "overhead", "unspecified"],
    "window": ["fixed", "casement", "sliding", "storefront", "unspecified"],
}


def discipline_for(family: str) -> str:
    return FAMILY_DISCIPLINES.get(family, "unmapped")


@dataclass
class RotatedBox:
    """Oriented bounding box: centre, size, in-plane angle (degrees, CCW)."""
    cx: float
    cy: float
    w: float
    h: float
    angle_deg: float = 0.0

    @classmethod
    def from_xyxy(cls, box: list[float]) -> "RotatedBox":
        x0, y0, x1, y1 = (float(v) for v in box)
        return cls((x0 + x1) / 2.0, (y0 + y1) / 2.0, x1 - x0, y1 - y0, 0.0)

    def corners(self) -> list[tuple[float, float]]:
        """Four corners in image coords (y down). Angle rotates about the centre."""
        a = math.radians(self.angle_deg)
        ca, sa = math.cos(a), math.sin(a)
        hw, hh = self.w / 2.0, self.h / 2.0
        local = [(-hw, -hh), (hw, -hh), (hw, hh), (-hw, hh)]
        out = []
        for lx, ly in local:
            out.append((self.cx + lx * ca - ly * sa, self.cy + lx * sa + ly * ca))
        return out

    def aabb(self) -> list[float]:
        """Tight axis-aligned hull of the rotated corners."""
        pts = self.corners()
        xs = [p[0] for p in pts]
        ys = [p[1] for p in pts]
        return [min(xs), min(ys), max(xs), max(ys)]

    def to_dict(self) -> dict[str, Any]:
        return {"cx": round(self.cx, 3), "cy": round(self.cy, 3),
                "w": round(self.w, 3), "h": round(self.h, 3),
                "angle_deg": round(self.angle_deg, 3)}


@dataclass
class SnapVector:
    """Extra vector (origin + direction) that biases/anchors the wall-snap search."""
    origin: tuple[float, float]
    direction: tuple[float, float]  # unoriented direction, image xy

    def unit(self) -> tuple[float, float]:
        n = math.hypot(*self.direction)
        if n < 1e-9:
            raise ValueError("snap vector direction has zero length")
        return (self.direction[0] / n, self.direction[1] / n)

    def to_dict(self) -> dict[str, Any]:
        return {"origin": [round(self.origin[0], 3), round(self.origin[1], 3)],
                "direction": [round(self.direction[0], 4), round(self.direction[1], 4)]}


@dataclass
class Instance:
    instance_id: str
    symbol_family: str = "unknown_symbol"
    symbol_variant: str = "unspecified"
    box: RotatedBox = field(default_factory=lambda: RotatedBox(0, 0, 1, 1, 0))
    anchors: dict[str, dict] = field(default_factory=dict)
    snap: dict | None = None
    snap_vector: SnapVector | None = None
    attributes_from_text: list = field(default_factory=list)
    device_attributes: dict[str, Any] = field(default_factory=dict)
    is_truncated: bool = False
    is_occluded: bool = False
    uncertain: bool = False
    extra: dict[str, Any] = field(default_factory=dict)

    @property
    def discipline(self) -> str:
        return discipline_for(self.symbol_family)

    def to_dict(self) -> dict[str, Any]:
        d: dict[str, Any] = {
            "instance_id": self.instance_id,
            "symbol_family": self.symbol_family,
            "symbol_variant": self.symbol_variant,
            "discipline": self.discipline,
            "device_type": self.symbol_family,
            "bbox_xyxy": [round(v, 3) for v in self.box.aabb()],
            "attributes_from_text": list(self.attributes_from_text),
            "rotation_degrees": round(self.box.angle_deg, 3),
            "is_truncated": self.is_truncated,
            "is_occluded": self.is_occluded,
        }
        if self.uncertain:
            d["uncertain"] = True
        if abs(self.box.angle_deg) > 1e-6:
            d["bbox_rotated"] = self.box.to_dict()
        if self.anchors:
            d["anchors"] = self.anchors
        if self.snap is not None:
            d["snap"] = self.snap
        if self.snap_vector is not None:
            d["snap_vector"] = self.snap_vector.to_dict()
        if self.device_attributes:
            d["device_attributes"] = dict(self.device_attributes)
        d.update(self.extra)
        return d

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> "Instance":
        known = {
            "instance_id", "symbol_family", "symbol_variant", "discipline",
            "device_type", "bbox_xyxy", "bbox_rotated", "attributes_from_text",
            "device_attributes", "rotation_degrees", "is_truncated", "is_occluded",
            "uncertain", "anchors", "snap", "snap_vector",
        }
        extra = {k: v for k, v in d.items() if k not in known}
        if "bbox_rotated" in d and d["bbox_rotated"] is not None:
            rb = d["bbox_rotated"]
            box = RotatedBox(float(rb["cx"]), float(rb["cy"]), float(rb["w"]),
                             float(rb["h"]), float(rb.get("angle_deg", 0.0)))
        else:
            box = RotatedBox.from_xyxy(d["bbox_xyxy"])
        sv = None
        if d.get("snap_vector"):
            s = d["snap_vector"]
            sv = SnapVector((float(s["origin"][0]), float(s["origin"][1])),
                            (float(s["direction"][0]), float(s["direction"][1])))
        return cls(
            instance_id=str(d["instance_id"]),
            symbol_family=str(d.get("symbol_family") or d.get("device_type") or "unknown_symbol"),
            symbol_variant=str(d.get("symbol_variant", "unspecified")),
            box=box,
            anchors=dict(d.get("anchors", {})),
            snap=d.get("snap"),
            snap_vector=sv,
            attributes_from_text=list(d.get("attributes_from_text", [])),
            device_attributes=dict(d.get("device_attributes", {})),
            is_truncated=bool(d.get("is_truncated", False)),
            is_occluded=bool(d.get("is_occluded", False)),
            uncertain=bool(d.get("uncertain", False)),
            extra=extra,
        )


@dataclass
class Sheet:
    """One annotated page. Mirrors the existing dataset JSON top level."""
    source_id: str
    document_id: str
    page_index: int
    image_path: str
    image_width: int
    image_height: int
    project_family_id: str = ""
    source_license: str = "user-owned"
    source_type: str = "cad_raster"
    image_sha256: str = ""
    instances: list[Instance] = field(default_factory=list)
    coordinate_system: str = "original_page_pixels_top_left_y_down"
    extra: dict[str, Any] = field(default_factory=dict)

    @classmethod
    def load(cls, path) -> "Sheet":
        from pathlib import Path
        d = json.loads(Path(path).read_text(encoding="utf-8"))
        known = {"format_version", "source_id", "source_license", "project_family_id",
                 "document_id", "page_index", "source_type", "image_path",
                 "image_sha256", "image_width", "image_height", "coordinate_system",
                 "instances"}
        extra = {k: v for k, v in d.items() if k not in known}
        return cls(
            source_id=str(d.get("source_id", "")),
            document_id=str(d.get("document_id", "")),
            page_index=int(d.get("page_index", 0)),
            image_path=str(d.get("image_path", "")),
            image_width=int(d["image_width"]),
            image_height=int(d["image_height"]),
            project_family_id=str(d.get("project_family_id", "")),
            source_license=str(d.get("source_license", "user-owned")),
            source_type=str(d.get("source_type", "cad_raster")),
            image_sha256=str(d.get("image_sha256", "")),
            instances=[Instance.from_dict(i) for i in d.get("instances", [])],
            coordinate_system=str(d.get("coordinate_system", "original_page_pixels_top_left_y_down")),
            extra=extra,
        )

    def to_dict(self) -> dict[str, Any]:
        d: dict[str, Any] = {
            "format_version": 1,
            "source_id": self.source_id,
            "source_license": self.source_license,
            "project_family_id": self.project_family_id,
            "document_id": self.document_id,
            "page_index": self.page_index,
            "source_type": self.source_type,
            "image_path": self.image_path,
            "image_sha256": self.image_sha256,
            "image_width": self.image_width,
            "image_height": self.image_height,
            "coordinate_system": self.coordinate_system,
            "instances": [i.to_dict() for i in self.instances],
        }
        d.update(self.extra)
        return d

    def save(self, path) -> None:
        from pathlib import Path
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        encoded = (json.dumps(self.to_dict(), ensure_ascii=False, indent=2) + "\n").encode("utf-8")
        fd, temp = tempfile.mkstemp(prefix="." + path.name + ".", suffix=".tmp", dir=path.parent)
        try:
            with os.fdopen(fd, "wb") as stream:
                stream.write(encoded)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temp, path)
        finally:
            if os.path.exists(temp):
                os.unlink(temp)
