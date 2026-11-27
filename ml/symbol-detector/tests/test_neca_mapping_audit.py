"""Audit tests for NECA 100-2013 -> 180-device catalog symbol overrides."""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pytest

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from blueprint_symbols import label_schema, neca_svg_compositor  # noqa: E402
from blueprint_symbols.neca_svg_compositor import (  # noqa: E402
    DEFAULT_NECA_OVERRIDES_PATH,
    NecaSymbolBank,
    load_neca_overrides,
    resolve_neca_device,
)
from blueprint_symbols.placement_policy import wall_placement_probability  # noqa: E402


def test_overrides_json_targets_are_active_180_catalog_devices() -> None:
    assert DEFAULT_NECA_OVERRIDES_PATH.is_file(), f"Missing {DEFAULT_NECA_OVERRIDES_PATH}"
    catalog = json.loads(label_schema.CATALOG_PATH.read_text(encoding="utf-8"))
    active_devices = {
        f"{d['symbol_family']}.{d['symbol_variant']}" for d in catalog["devices"]
    }
    assert len(active_devices) == 180

    payload = json.loads(DEFAULT_NECA_OVERRIDES_PATH.read_text(encoding="utf-8"))
    overrides = payload["overrides"]
    assert isinstance(overrides, dict) and overrides

    for sym_id, target in overrides.items():
        assert sym_id.startswith(("NECA_", "NFPA170_")), sym_id
        if target is None:
            continue
        assert target in active_devices, f"{sym_id} -> {target!r} is not an active catalog device"
        fam, var = target.split(".", 1)
        assert label_schema.device_class(fam, var) == target

    parsed = load_neca_overrides()
    assert set(parsed) == set(overrides)


def test_invalid_override_target_raises_value_error(tmp_path: Path) -> None:
    bad = tmp_path / "bad_overrides.json"
    bad.write_text(
        json.dumps({"overrides": {"NECA_2_005": "luminaire.exit_sign_double_arrow"}}),
        encoding="utf-8",
    )
    with pytest.raises(ValueError, match="unknown or inactive"):
        load_neca_overrides(bad)


@pytest.mark.parametrize(
    "sym_id,section,name",
    [
        # Raceways, poles, busways, wireways, polluted PB OR []
        ("NECA_1_202", "Raceways - Boxes and Busways", "Power pole with devices."),
        ("NECA_1_203", "Raceways - Boxes and Busways", "Telecom pole with devices."),
        ("NECA_1_204", "Raceways - Boxes and Busways", "Telecom/Power pole with devices."),
        ("NECA_1_205", "Raceways - Boxes and Busways", "Pull box—size as indicated or required."),
        ("NECA_1_205__alternative_1", "Raceways - Boxes and Busways", "Pull box [figure 1/2]"),
        ("NECA_1_205__alternative_2", "Raceways - Boxes and Busways", "Pull box [figure 2/2]"),
        ("NECA_1_206", "Raceways - Boxes and Busways", "Cabletray size as indicated."),
        ("NECA_1_207", "Raceways - Boxes and Busways", "Cabletray size as indicated, concealed."),
        ("NECA_1_208", "Raceways - Boxes and Busways", "Busway with cable tap box."),
        ("NECA_1_209", "Raceways - Boxes and Busways", "Busway with plug-in device."),
        ("NECA_1_210", "Raceways - Boxes and Busways", "Busway feeding up."),
        ("NECA_1_211", "Raceways - Boxes and Busways", "Busway feeding down."),
        ("NECA_1_212", "Raceways - Boxes and Busways", "Busway expansion joint."),
        ("NECA_1_213", "Raceways - Boxes and Busways", "Wireway, size as indicated."),
        # Multi-symbol luminaire/exit charts & modifier charts
        ("NECA_2_001", "Luminaires", "Luminaire: (drawn to approximate shape)."),
        ("NECA_2_004", "Luminaires", "Fixture—double or single head spotlight."),
        ("NECA_2_004__alternative_1", "Luminaires", "Fixture spotlight [figure 1/2]"),
        ("NECA_2_004__alternative_2", "Luminaires", "Fixture spotlight [figure 2/2]"),
        ("NECA_2_005", "Luminaires", "Exit luminaire fixture."),
        ("NECA_2_100", "Luminaires - Mounting Modifiers", "Surface mounted fixture..."),
        ("NECA_2_100__suspended", "Luminaires - Mounting Modifiers", "Suspended/pendant fixture"),
        ("NECA_2_100__recessed", "Luminaires - Mounting Modifiers", "Recessed fixture"),
        ("NECA_2_100__wall_mounted", "Luminaires - Mounting Modifiers", "Wall-mounted fixture"),
        ("NECA_2_100__surface_mounted__round", "Luminaires - Mounting Modifiers", "Surface-mounted fixture - round"),
        ("NECA_2_200", "Luminaires - Orientation Modifiers", "Accent/directional arrow."),
        ("NECA_2_200__directional_arrow__rectangular", "Luminaires - Orientation Modifiers", "Accent/directional arrow - rectangular"),
        ("NECA_2_300", "Luminaires - Emergency Modifiers", "Luminaire providing emergency illumination."),
        ("NECA_2_300__alternative_1", "Luminaires - Emergency Modifiers", "Emergency illumination [figure 1/2]"),
        ("NECA_2_401", "Luminaires - Extended Modifiers", "Standard designations."),
        ("NECA_2_402", "Luminaires - Extended Modifiers", "Mounting height."),
        ("NECA_2_403", "Luminaires - Extended Modifiers", "Louvers."),
        # Polluted/non-catalog receptacles, switches, security, fire alarm, NFPA170
        ("NECA_3_003", "Outlets and Receptacles", "Duplex convenience receptacle on emergency/standby circuit."),
        ("NECA_3_005", "Outlets and Receptacles", "Single convenience receptacle on emergency/standby circuit."),
        ("NECA_3_007", "Outlets and Receptacles", "Double duplex convenience receptacle on emergency/standby circuit."),
        ("NECA_3_010", "Outlets and Receptacles", "Special receptacle."),
        ("NECA_3_010__alternative_1", "Outlets and Receptacles", "Special receptacle [figure 1/2]"),
        ("NECA_3_010__alternative_2", "Outlets and Receptacles", "Special receptacle [figure 2/2]"),
        ("NECA_3_012", "Outlets and Receptacles", "Flush mounted floor box with power and voice/data."),
        ("NECA_3_013", "Outlets and Receptacles", "Junction box AxBxC."),
        ("NECA_3_013__alternative_1", "Outlets and Receptacles", "Junction box AxBxC [figure 1/2]"),
        ("NECA_3_013__alternative_2", "Outlets and Receptacles", "Junction box AxBxC [figure 2/2]"),
        ("NECA_4_001", "Switches and Sensors", "Single pole switch."),
        ("NECA_4_001__dollar", "Switches and Sensors", "Single pole switch. [dollar notation]"),
        ("NECA_4_007__letter_s", "Switches and Sensors", "Single pole/double throw switch. [S notation]"),
        ("NECA_4_021__letter_s", "Switches and Sensors", "Motorized projection screen raise/lower switch."),
        ("NECA_6_002", "Security", "CCTV Coaxial cable outlet and power outlet."),
        ("NECA_6_003", "Security", "CCTV monitor outlet."),
        ("NECA_7_002", "Fire Alarm Communications and Panels", "Fire fighter's phone."),
        ("NECA_7_005", "Fire Alarm Communications and Panels", "Key repository (knox box)."),
        ("NECA_7_107", "Fire Alarm Indicators", "Bell and strobe."),
        ("NECA_7_111", "Fire Alarm Indicators", "LED pilot light."),
        ("NECA_7_215", "Fire Alarm Sensors", "Motor operated fire/smoke duct damper."),
        ("NECA_8_004", "Power Distribution Equipment", "Lighting or power panel on normal/generator feeder."),
        ("NECA_8_009", "Power Distribution Equipment", "Current transformer cabinet."),
        ("NFPA170_505_1__basic", "Annex A - Alternate Fire Safety", "Control panel - basic shape"),
        ("NFPA170_5_6_1", "Annex A - Alternate Fire Safety", "Emergency battery-powered light"),
        ("NFPA170_5_6_2", "Annex A - Alternate Fire Safety", "Illuminated exit - single face"),
        ("NFPA170_5_6_3", "Annex A - Alternate Fire Safety", "Illuminated exit - double face"),
        ("NFPA170_5_7_1_1__basic", "Annex A - Alternate Fire Safety", "Manual station - basic shape"),
        ("NFPA170_5_7_1_1__f", "Annex A - Alternate Fire Safety", "Pull station"),
        ("NFPA170_5_7_1_2__basic", "Annex A - Alternate Fire Safety", "Automatic detector - basic shape"),
        ("NFPA170_5_7_1_2_1__basic", "Annex A - Alternate Fire Safety", "Heat detector - basic shape"),
        ("NFPA170_5_7_1_2_2__a", "Annex A - Alternate Fire Safety", "Photoelectric smoke detector"),
        ("NFPA170_5_7_1_2_3", "Annex A - Alternate Fire Safety", "Smoke detector in duct"),
        ("NFPA170_5_7_2_4__a", "Annex A - Alternate Fire Safety", "Horn and separate light"),
        ("NFPA170_5_7_2_4__b", "Annex A - Alternate Fire Safety", "Horn with light assembly"),
        # Non-device sections
        ("NECA_1_001", "Raceways", "Conduit concealed in finished areas."),
        ("NECA_10_001", "Site Work", "Underground feeder."),
        ("NECA_11_001", "Schematic and One-Line Diagram Symbols", "Capacitor."),
        ("NECA_11_101", "Switchboard Meters", "Customer meter."),
        ("NECA_12_001", "Miscellaneous", "Ground bar."),
    ],
)
def test_non_device_charts_busways_and_dirty_svgs_resolve_to_none(
    sym_id: str, section: str, name: str
) -> None:
    assert resolve_neca_device(sym_id, section, name) is None


@pytest.mark.parametrize(
    "sym_id,section,name,expected",
    [
        ("NECA_1_201", "Raceways - Boxes and Busways", "Underfloor raceway system junction box, flush floor mounted.", ("junction_box", "junction_box")),
        ("NECA_2_005__alternative_1", "Luminaires", "Exit luminaire fixture [figure 1/2]", ("luminaire", "exit_sign_single_face_single_arrow")),
        ("NECA_2_005__alternative_2", "Luminaires", "Exit luminaire fixture [figure 2/2]", ("luminaire", "exit_sign_double_face_double_arrow")),
        ("NFPA170_5_6_4", "Annex A - Alternate Fire Safety", "Combined emergency luminaire and exit", ("luminaire", "exit_emergency_combo")),
        ("NECA_2_006", "Luminaires", "Light track.", ("luminaire", "track_light")),
        ("NECA_2_007", "Luminaires", "Emergency battery remote luminaire heads.", ("luminaire", "emergency_remote_head")),
        ("NECA_2_008", "Luminaires", "Emergency battery unit with luminaire heads.", ("luminaire", "emergency_battery_light")),
        ("NECA_2_009", "Luminaires", "Single luminaire pole mounted site luminaire fixture.", ("luminaire", "pole_luminaire")),
        ("NECA_2_010", "Luminaires", "Twin luminaire pole mounted site luminaire fixture.", ("luminaire", "pole_luminaire")),
        ("NECA_2_011", "Luminaires", "Roadway luminaire—cobra head.", ("luminaire", "pole_luminaire")),
        ("NECA_2_013", "Luminaires", "Outdoor wallpack.", ("luminaire", "wallpack")),
        ("NECA_2_100__recessed__round", "Luminaires - Mounting Modifiers", "Recessed fixture - round", ("luminaire", "recessed_downlight")),
        ("NECA_2_100__recessed__rectangular", "Luminaires - Mounting Modifiers", "Recessed fixture - rectangular", ("luminaire", "linear")),
        ("NECA_2_100__suspended__round", "Luminaires - Mounting Modifiers", "Suspended/pendant fixture - round", ("luminaire", "pendant")),
        ("NECA_2_100__suspended__rectangular", "Luminaires - Mounting Modifiers", "Suspended/pendant fixture - rectangular", ("luminaire", "pendant")),
        ("NECA_2_100__pole_arm__round", "Luminaires - Mounting Modifiers", "Pole-mounted with arm - round", ("luminaire", "pole_luminaire")),
        ("NECA_2_100__pole_top__round", "Luminaires - Mounting Modifiers", "Pole-mounted on top - round", ("luminaire", "pole_luminaire")),
        ("NECA_2_404", "Luminaires - Extended Modifiers", "Recessed, emergency fixture.", ("luminaire", "recessed_downlight_emergency")),
        ("NECA_3_001", "Outlets and Receptacles", "Floor duplex receptacle.", ("receptacle", "floor_mounted")),
        ("NECA_3_006", "Outlets and Receptacles", "Double duplex convenience receptacle.", ("receptacle", "quad")),
        ("NECA_3_011", "Outlets and Receptacles", "Clock hanger outlet.", ("audio_visual_device", "clock_outlet")),
        ("NECA_3_014", "Outlets and Receptacles", "Duples receptacle ceiling mounted 20A 125V.", ("receptacle", "ceiling_mounted")),
        ("NECA_3_015", "Outlets and Receptacles", "Double duplex receptacle—ceiling mounted.", ("receptacle", "quad")),
        ("NECA_4_020", "Switches and Sensors", "Occupancy sensor—ceiling mounted.", ("sensor", "occupancy_sensor_ceiling")),
        ("NECA_5_004", "Motor and HVAC Controls", "Magnetic contactor.", ("control_relay", "contactor")),
        ("NECA_5_005", "Motor and HVAC Controls", "Adjustable speed drive.", ("motor_control", "vfd")),
        ("NECA_5_006", "Motor and HVAC Controls", "Motor starter switch.", ("motor_control", "motor_starter")),
        ("NECA_5_012", "Motor and HVAC Controls", "Toggle disconnect switch.", ("disconnect", "motor_rated")),
        ("NECA_5_020", "Motor and HVAC Controls", "Humidistat.", ("sensor", "humidistat")),
        ("NECA_5_021", "Motor and HVAC Controls", "Line voltage thermostat.", ("sensor", "thermostat")),
        ("NECA_5_022", "Motor and HVAC Controls", "Low voltage thermostat.", ("sensor", "thermostat")),
        ("NECA_5_023", "Motor and HVAC Controls", "Thermostat.", ("sensor", "thermostat")),
        ("NECA_5_033", "Motor and HVAC Controls", "Photo cell or photo control.", ("sensor", "photocell")),
        ("NECA_6_008", "Security", "Electric door strike.", ("security_device", "electric_door_strike")),
        ("NECA_6_009", "Security", "Intercom unit—flush MTD.", ("audio_visual_device", "intercom")),
        ("NECA_6_010", "Security", "Master intercom and directory unit.", ("audio_visual_device", "intercom_master_station")),
        ("NECA_6_012", "Security", "Security door alarm magnetic lock.", ("security_device", "magnetic_door_lock")),
        ("NECA_6_014", "Security", "Security control panel.", ("security_device", "access_control_panel")),
        ("NECA_6_017", "Security", "Security keypad.", ("security_device", "security_keypad")),
        ("NECA_7_008", "Fire Alarm Communications and Panels", "Voice evacuation panel.", ("fire_alarm_device", "voice_evacuation_panel")),
        ("NECA_7_102", "Fire Alarm Indicators", "Door holder.", ("fire_alarm_device", "door_holder")),
        ("NECA_7_105", "Fire Alarm Indicators", "Horn unit only.", ("fire_alarm_device", "fire_alarm_horn")),
        ("NECA_7_201", "Fire Alarm Sensors", "Manual pull station.", ("fire_alarm_device", "manual_pull_station")),
        ("NECA_7_202", "Fire Alarm Sensors", "Smoke detector.", ("fire_alarm_device", "smoke_detector")),
        ("NECA_7_203", "Fire Alarm Sensors", "Duct smoke detector.", ("fire_alarm_device", "duct_smoke_detector")),
        ("NECA_7_206", "Fire Alarm Sensors", "Elevator recall with auxiliary contacts.", ("fire_alarm_device", "elevator_recall_detector")),
        ("NECA_7_210", "Fire Alarm Sensors", "Carbon monoxide detector.", ("fire_alarm_device", "carbon_monoxide_detector")),
        ("NECA_7_212", "Fire Alarm Sensors", "Automatic heat detector.", ("fire_alarm_device", "heat_detector")),
        ("NECA_7_216", "Fire Alarm Sensors", "Water flow switch.", ("fire_alarm_device", "waterflow_switch")),
        ("NECA_7_218", "Fire Alarm Sensors", "Tamper switch.", ("fire_alarm_device", "tamper_switch")),
        ("NECA_8_001", "Power Distribution Equipment", "Lighting or power panel, recessed.", ("panelboard", "panelboard")),
        ("NECA_8_008", "Power Distribution Equipment", "Transformer—pad mount.", ("transformer", "transformer")),
        ("NECA_9_002", "Communications - Teledata", "Data outlet floor type.", ("data_outlet", "data_floor")),
        ("NECA_9_101", "Communications - Audio-Visual", "Call in switch.", ("audio_visual_device", "intercom_substation")),
        ("NECA_9_102", "Communications - Audio-Visual", "Cable antenna system outlet. (CATV)", ("data_outlet", "catv_outlet")),
        ("NECA_9_111", "Communications - Audio-Visual", "Intercom unit—flush mounted.", ("audio_visual_device", "intercom")),
        ("NECA_9_112", "Communications - Audio-Visual", "Master intercom and directory unit.", ("audio_visual_device", "intercom_master_station")),
        ("NECA_7_113", "Fire Alarm Indicators", "Speaker—ceiling mounted.", ("audio_visual_device", "speaker")),
        ("NFPA170_5_7_2_1", "Annex A - Alternate Fire Safety", "Speaker/horn", ("fire_alarm_device", "fire_alarm_horn")),
        ("NFPA170_5_7_2_2", "Annex A - Alternate Fire Safety", "Bell/gong", ("fire_alarm_device", "fire_alarm_bell")),
        ("NFPA170_5_7_1_2_1__a", "Annex A - Alternate Fire Safety", "Rate-of-rise and fixed temp", ("fire_alarm_device", "heat_detector_rate_of_rise")),
        ("NFPA170_5_7_1_2_1__c", "Annex A - Alternate Fire Safety", "Fixed-temperature", ("fire_alarm_device", "heat_detector")),
    ],
)
def test_fixed_and_newly_mapped_neca_ids_resolve_accurately(
    sym_id: str, section: str, name: str, expected: tuple[str, str]
) -> None:
    assert resolve_neca_device(sym_id, section, name) == expected


def test_neca_symbol_bank_respects_overrides_without_cairosvg(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(
        neca_svg_compositor,
        "_rasterize_svg_alpha",
        lambda *_args, **_kwargs: np.full((24, 24), 255, dtype=np.uint8),
    )

    entries = [
        {"id": "NECA_1_201", "code": "1.201", "section": "Raceways - Boxes and Busways", "name": "Underfloor junction box.", "file": "a.svg", "kind": "numbered_row"},
        {"id": "NECA_1_208", "code": "1.208", "section": "Raceways - Boxes and Busways", "name": "Busway with cable tap box.", "file": "b.svg", "kind": "numbered_row"},
        {"id": "NECA_2_005", "code": "2.005", "section": "Luminaires", "name": "Exit luminaire fixture.", "file": "c.svg", "kind": "numbered_row"},
        {"id": "NECA_2_005__alternative_1", "code": "2.005", "section": "Luminaires", "name": "Exit luminaire fixture [1/2]", "file": "d.svg", "kind": "printed_alternative"},
        {"id": "NECA_2_005__alternative_2", "code": "2.005", "section": "Luminaires", "name": "Exit luminaire fixture [2/2]", "file": "e.svg", "kind": "printed_alternative"},
        {"id": "NECA_2_404", "code": "2.404", "section": "Luminaires - Extended Modifiers", "name": "Recessed, emergency fixture.", "file": "f.svg", "kind": "numbered_row"},
        {"id": "NECA_3_001", "code": "3.001", "section": "Outlets and Receptacles", "name": "Floor duplex receptacle.", "file": "g.svg", "kind": "numbered_row"},
        {"id": "NECA_5_020", "code": "5.020", "section": "Motor and HVAC Controls", "name": "Humidistat.", "file": "h.svg", "kind": "numbered_row"},
        {"id": "NFPA170_5_6_4", "code": "5-6.4", "section": "Annex A - Alternate Fire Safety", "name": "Combined emergency luminaire and exit", "file": "i.svg", "kind": "numbered_row"},
        {"id": "NFPA170_505_1__basic", "code": "505.1", "section": "Annex A - Alternate Fire Safety", "name": "Control panel - basic shape", "file": "j.svg", "kind": "printed_alternative"},
    ]
    for e in entries:
        (tmp_path / e["file"]).write_text("<svg/>", encoding="utf-8")
    (tmp_path / "symbols.json").write_text(json.dumps({"symbols": entries}), encoding="utf-8")

    bank = NecaSymbolBank(tmp_path, devices_only=True)
    by_id = {g.symbol_id: g for pool in bank.by_class.values() for g in pool}

    assert "NECA_1_208" not in by_id
    assert "NECA_2_005" not in by_id
    assert "NFPA170_505_1__basic" not in by_id

    assert set(by_id) == {
        "NECA_1_201",
        "NECA_2_005__alternative_1",
        "NECA_2_005__alternative_2",
        "NECA_2_404",
        "NECA_3_001",
        "NECA_5_020",
        "NFPA170_5_6_4",
    }
    assert (by_id["NECA_2_404"].family, by_id["NECA_2_404"].variant) == (
        "luminaire", "recessed_downlight_emergency"
    )
    assert by_id["NECA_5_020"].canonical_class == "switch_sensor"
    assert (by_id["NECA_5_020"].family, by_id["NECA_5_020"].variant) == ("sensor", "humidistat")
    assert by_id["NFPA170_5_6_4"].canonical_class == "luminaire"
    assert (by_id["NFPA170_5_6_4"].family, by_id["NFPA170_5_6_4"].variant) == (
        "luminaire", "exit_emergency_combo"
    )

    floor = by_id["NECA_3_001"]
    assert (floor.family, floor.variant) == ("receptacle", "floor_mounted")
    assert wall_placement_probability(floor.device_type, floor.subtype, 0.6) < 0.2

    # Even in pretraining mode (devices_only=False), explicit null exclusions stay excluded.
    bank_ssl = NecaSymbolBank(tmp_path, devices_only=False)
    ssl_ids = {g.symbol_id for pool in bank_ssl.by_class.values() for g in pool}
    assert "NECA_1_208" not in ssl_ids
    assert "NFPA170_505_1__basic" not in ssl_ids


def test_resolve_neca_device_custom_overrides_precedence() -> None:
    custom = {
        "NECA_3_002": None,
        "NECA_1_001": "junction_box.pull_box",
    }
    assert resolve_neca_device("NECA_3_002", "Outlets and Receptacles", "Duplex convenience receptacle.", overrides=custom) is None
    assert resolve_neca_device("NECA_1_001", "Raceways", "Conduit.", overrides=custom) == ("junction_box", "pull_box")
    assert resolve_neca_device("NECA_3_004", "Outlets and Receptacles", "Single convenience receptacle.", overrides=custom) == ("receptacle", "single")


def test_every_entry_in_neca_library_symbols_json_if_present(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    candidates = [
        ROOT.parents[1] / "blueprints_and_libraries" / "2_component_libraries" / "neca_100_2013_symbols" / "symbols.json",
        ROOT / "data" / "blueprints_and_libraries" / "2_component_libraries" / "neca_100_2013_symbols" / "symbols.json",
    ]
    symbols_path = next((p for p in candidates if p.is_file()), None)
    if symbols_path is None:
        pytest.skip("NECA symbols.json not present in local checkout")

    catalog_set = set(label_schema.CATALOG_DEVICES)
    overrides = load_neca_overrides()
    meta = json.loads(symbols_path.read_text(encoding="utf-8"))
    for entry in meta["symbols"]:
        resolved = resolve_neca_device(entry["id"], entry.get("section", ""), entry.get("name", ""), overrides=overrides)
        assert resolved is None or resolved in catalog_set, (entry["id"], resolved)

    monkeypatch.setattr(
        neca_svg_compositor,
        "_rasterize_svg_alpha",
        lambda *_args, **_kwargs: np.full((24, 24), 255, dtype=np.uint8),
    )
    bank = NecaSymbolBank(symbols_path.parent, devices_only=True)
    loaded_ids = {g.symbol_id for pool in bank.by_class.values() for g in pool}
    excluded_ids = {sid for sid, val in overrides.items() if val is None}
    assert loaded_ids.isdisjoint(excluded_ids)
    assert all((g.family, g.variant) in catalog_set for pool in bank.by_class.values() for g in pool)
