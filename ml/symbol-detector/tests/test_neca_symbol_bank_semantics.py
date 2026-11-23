"""NECA source figure rows are not all standalone device glyphs."""
from __future__ import annotations

import json

import pytest

pytest.importorskip("cairosvg")

from blueprint_symbols.neca_svg_compositor import NecaSymbolBank


def test_neca_bank_skips_composite_parent_rows_and_annotation_modifiers(tmp_path):
    (tmp_path / "symbol.svg").write_text(
        '<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100">'
        '<circle cx="50" cy="50" r="30" stroke="black" fill="none" '
        'stroke-width="5"/></svg>'
    )
    def symbol(symbol_id, code, section, kind, name):
        return {"id": symbol_id, "code": code, "name": name,
                "section": section, "kind": kind, "file": "symbol.svg"}

    entries = [
        symbol("switch_parent", "4.001", "Switches and Sensors",
               "numbered_row", "Single pole switch; two printed alternatives"),
        symbol("switch_single", "4.001", "Switches and Sensors",
               "printed_alternative", "Single pole switch, letter S"),
        symbol("plain_receptacle", "3.002", "Outlets and Receptacles",
               "numbered_row", "Duplex convenience receptacle"),
        symbol("luminaire_orientation_arrow", "2.200",
               "Luminaires - Orientation Modifiers", "numbered_row",
               "Directional arrow"),
        symbol("mounting_multi", "2.100", "Luminaires - Mounting Modifiers",
               "numbered_row", "Multiple different fixture mountings"),
        symbol("mounting_subtype_multi", "2.100",
               "Luminaires - Mounting Modifiers", "numbered_row_subtype",
               "Several surface-mounted alternatives"),
        symbol("mounting_single", "2.100", "Luminaires - Mounting Modifiers",
               "printed_alternative", "Surface-mounted fixture - round"),
        symbol("raceway_pole", "1.202", "Raceways - Boxes and Busways",
               "numbered_row", "Power pole with receptacles"),
        symbol("pull_box", "1.205", "Raceways - Boxes and Busways",
               "numbered_row", "Pull box"),
    ]
    (tmp_path / "symbols.json").write_text(json.dumps({"symbols": entries}))
    bank = NecaSymbolBank(tmp_path, render_height=72)
    found = {item.symbol_id for group in bank.by_class.values() for item in group}
    assert found == {
        "switch_single", "plain_receptacle", "mounting_single", "pull_box"
    }
    assert bank.by_class["switch_sensor"][0].device_type == "switch"
    assert bank.by_class["receptacle"][0].subtype == "duplex"

    no_modifiers = NecaSymbolBank(
        tmp_path, render_height=72, include_modifiers=False
    )
    found_no_modifiers = {
        item.symbol_id for group in no_modifiers.by_class.values()
        for item in group
    }
    assert "mounting_single" not in found_no_modifiers
    assert found_no_modifiers == {
        "switch_single", "plain_receptacle", "pull_box"
    }

def test_neca_teledata_vs_av_equipment_mapping():
    from blueprint_symbols.neca_svg_compositor import map_neca_to_v7

    assert map_neca_to_v7("Communications - Teledata", "Data outlet") == (
        "data_outlet", "data"
    )
    assert map_neca_to_v7(
        "Communications - Teledata", "Telephone/data outlet"
    ) == ("data_outlet", "data_telephone")
    assert map_neca_to_v7(
        "Communications - Audio-Visual", "Speaker—wall mounted"
    ) == ("speaker", "speaker")
    for name in ("Volume control", "Master intercom and directory unit",
                 "Microphone outlet—wall mounted", "Cable antenna system outlet"):
        assert map_neca_to_v7("Communications - Audio-Visual", name) == (
            "other_device", "unspecified"
        )
    assert map_neca_to_v7(
        "Communications - Equipment", "Equipment rack—wall mounted"
    ) == ("other_device", "unspecified")
