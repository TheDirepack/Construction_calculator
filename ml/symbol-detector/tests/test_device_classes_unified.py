"""One class name per device everywhere: the annotator's device list (the
catalog) is the set of classes; every source's names map onto it."""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from blueprint_symbols import label_schema
from blueprint_symbols.label_schema import (CATALOG_DEVICES, CATALOG_FAMILIES, canonical_device,
                                            canonical_instance, instance_class)

CATALOG = set(CATALOG_DEVICES)
HERE = Path(__file__).resolve().parents[1]


def _allowed(device) -> bool:
    """A UI class, or a UI family whose exact device is not picked yet."""
    return device is None or device in CATALOG or (
        device[1] == "unspecified" and (device[0] in CATALOG_FAMILIES or device[0] == "unknown_symbol"))


def _v7_taxonomy() -> dict[str, list[str]]:
    from scripts_circuitnet_review_sheets import DEVICE_TAXONOMY_V7
    taxonomy = {k: list(v) for k, v in DEVICE_TAXONOMY_V7.items()}
    conventions = HERE.parents[1] / "blueprints_and_libraries" / "1_blueprints" / "devices" / \
        "device_naming_conventions_v7.json"
    if conventions.is_file():
        for k, v in json.loads(conventions.read_text())["taxonomy"].items():
            taxonomy.setdefault(k, [])
            taxonomy[k] += [s for s in v if s not in taxonomy[k]]
    return taxonomy


def test_ui_device_list_is_the_catalog():
    from annotator.web import _load_neca_subtypes, load_electrical_device_catalog
    ui = {(d["symbol_family"], d["symbol_variant"]) for d in load_electrical_device_catalog()["devices"]}
    assert ui == CATALOG and len(ui) == 182
    variants = _load_neca_subtypes(None)
    for family in CATALOG_FAMILIES:
        assert set(variants[family]) == {v for f, v in CATALOG if f == family} | {"unspecified"}


def test_every_v7_and_extractor_name_maps_to_one_ui_class():
    taxonomy = _v7_taxonomy()
    names = [(t, s) for t, subs in taxonomy.items() for s in subs] + [(t, None) for t in taxonomy]
    names += [(t, None) for t in label_schema.RAW_DEVICE_TYPES]
    for raw_type, subtype in names:
        device = canonical_device(raw_type, subtype)
        assert _allowed(device), (raw_type, subtype, device)
    # The exact v7 devices the catalog has are not left unspecified.
    exact = {("receptacle", "gfci_duplex"): ("receptacle", "duplex_gfci"),
             ("fire_alarm", "pull_station"): ("fire_alarm_device", "manual_pull_station"),
             ("light_fixture", "2x4"): ("luminaire", "troffer_2x4"),
             ("switch", "timer"): ("switch", "time_switch"),
             ("data_outlet", "data"): ("data_outlet", "data_wall"),
             ("security_device", "security_camera"): ("cctv_device", "security_camera")}
    for name, device in exact.items():
        assert canonical_device(*name) == device
        # ... also when a box already carries the catalog family with a v7 subtype
        assert canonical_device(device[0], name[1]) == device


def test_one_alias_never_names_two_devices():
    seen: dict[tuple[str, str], tuple[str, str]] = {}
    catalog = json.loads(label_schema.CATALOG_PATH.read_text())
    for d in catalog["devices"]:
        for kind in ("neca_v7", "devices_json"):
            for alias in (d.get("aliases") or {}).get(kind, []):
                key = (kind, alias)
                assert key not in seen, f"{alias} names {seen[key]} and {d['symbol_family']}.{d['symbol_variant']}"
                seen[key] = (d["symbol_family"], d["symbol_variant"])


@pytest.mark.parametrize("raw_type,subtype,expected", [
    ("receptacle", "switched_duplex", ("receptacle", "unspecified")),   # not merged into duplex
    ("speaker", "speaker", ("audio_visual_device", "unspecified")),    # not a speaker/strobe
    ("homerun_arrow", None, None), ("hvac_device", "vav", None), ("ceiling_fan", "ceiling_fan", None),
    ("variable_air_volume_box", None, None),
    ("downlight", None, ("luminaire", "downlight")), ("switch_3way", None, ("switch", "three_way")),
    ("anything_else", "whatever", ("unknown_symbol", "unspecified")),
])
def test_names_without_an_exact_device_are_never_guessed(raw_type, subtype, expected):
    assert canonical_device(raw_type, subtype) == expected


def test_a_legend_description_picks_the_device_within_its_family():
    assert canonical_device("receptacle", None, description="GFCI DUPLEX RECEPTACLE") == \
        ("receptacle", "duplex_gfci")
    # A description naming another family does not move the box to it.
    assert canonical_device("switch", None, description="DUPLEX RECEPTACLE") == ("switch", "unspecified")


def test_boxes_are_renamed_once_and_keep_their_old_names():
    agent = {"symbol_family": "fire_alarm", "symbol_variant": "pull_station", "review_source": "vlm_agent",
             "verified_ground_truth": True, "review_status": "accepted"}
    out = canonical_instance(agent)
    assert (out["symbol_family"], out["symbol_variant"]) == ("fire_alarm_device", "manual_pull_station")
    assert (out["raw_device_type"], out["raw_subtype"]) == ("fire_alarm", "pull_station")
    assert out["verified_ground_truth"] is True                       # same device: still verified
    vague = canonical_instance(dict(agent, symbol_family="receptacle", symbol_variant="weatherproof"))
    assert (vague["symbol_family"], vague["symbol_variant"]) == ("receptacle", "unspecified")
    assert vague["verified_ground_truth"] is False                    # pick the exact device again
    assert canonical_instance(canonical_instance(agent)) == out       # idempotent
    for family, variant in CATALOG:
        item = {"symbol_family": family, "symbol_variant": variant}
        assert canonical_instance(item) is item
    # A person's own name for a non-catalog thing is left for them to resolve.
    mine = {"symbol_family": "fan", "symbol_variant": "ceiling_fan", "review_source": "human"}
    assert canonical_instance(mine) is mine


def test_training_classes_are_exactly_the_ui_classes():
    classes = {instance_class({"symbol_family": f, "symbol_variant": v}) for f, v in CATALOG}
    assert classes == {f"{f}.{v}" for f, v in CATALOG}
    for raw_type, subtype in [("fire_alarm", "pull_station"), ("receptacle", "switched_duplex"),
                              ("light_fixture", "other"), ("downlight", None)]:
        device = canonical_device(raw_type, subtype)
        cls = instance_class({"symbol_family": device[0], "symbol_variant": device[1]})
        assert cls in {f"{f}.{v}" for f, v in CATALOG} or cls == label_schema.UNMAPPED


# ------------------------------------------------------------------ through the annotator

fitz = pytest.importorskip("fitz")


def _pdf(path: Path) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    document = fitz.open()
    document.new_page(width=400, height=300)
    document.save(path)
    document.close()
    return path.resolve()


def test_every_source_name_becomes_a_ui_class_in_the_labels_file(tmp_path, monkeypatch):
    from annotator import document_labels
    from annotator.web import LabelingSession
    monkeypatch.delenv("BPS_LABELS_DIR", raising=False)
    bp = tmp_path / "blueprints_and_libraries" / "1_blueprints"
    pdf = _pdf(bp / "mix.pdf")
    (bp / "devices").mkdir(parents=True, exist_ok=True)
    names = [("fire_alarm", "pull_station"), ("light_fixture", "2x4"), ("receptacle", "switched_duplex"),
             ("receptacle", "gfci_duplex"), ("homerun_arrow", None), ("hvac_device", "vav"),
             ("speaker", "speaker"), ("security_device", "door_lock"), ("switch", "timer")]
    devices = [{"pdf_page": 1, "device_type": t, "subtype": s,
                "box_px_tl": [20 + 60 * n, 20, 50 + 60 * n, 50]} for n, (t, s) in enumerate(names)]
    (bp / "devices" / "mix_DEVICES.json").write_text(json.dumps({
        "source_file": "mix.pdf", "devices": devices,
        "legend_types": [{"pdf_page": 1, "device_type": "receptacle", "subtype": "gfci_duplex",
                          "description": "GFCI DUPLEX RECEPTACLE"}]}))
    session = LabelingSession(pdf=pdf, blueprints_dir=bp)
    boxes = session.page_state(0)["document"]["instances"]
    classes = [(b["symbol_family"], b["symbol_variant"]) for b in boxes]
    assert all(_allowed(c) for c in classes)
    assert classes == [("fire_alarm_device", "manual_pull_station"), ("luminaire", "troffer_2x4"),
                       ("receptacle", "unspecified"), ("receptacle", "duplex_gfci"),
                       ("audio_visual_device", "unspecified"), ("security_device", "unspecified"),
                       ("switch", "time_switch")]
    assert boxes[2]["raw_subtype"] == "switched_duplex"

    # Saving v7 names (a script, an agent's vocabulary) stores the same classes.
    agent = [dict(boxes[0], symbol_family="fire_alarm", symbol_variant="pull_station",
                  review_status="accepted", review_source="human", uncertain=False,
                  verified_ground_truth=True)]
    state = session.page_state(0)
    session.save(0, revision=state["revision"], instances=agent, rejected=[])
    [saved] = document_labels.pages(session.out_dir, "mix")[1]["instances"]
    assert (saved["symbol_family"], saved["symbol_variant"]) == ("fire_alarm_device", "manual_pull_station")

    session.merge_pages({})
    record = json.loads(document_labels.labels_path(session.out_dir, "mix").read_text())
    [legend] = record["extracted"]["mix_DEVICES.json"]["legend_types"]
    assert legend["catalog_class"] == "receptacle.duplex_gfci"
    assert record["sources"][0]["devices_left_out"] == {"not_an_electrical_device": 2}
