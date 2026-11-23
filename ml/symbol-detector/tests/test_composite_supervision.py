"""Compositing must not turn an unlabeled real PDF into false negatives."""
from __future__ import annotations

import json
import sys

import pytest

from blueprint_symbols.composite_supervision import composite_supervision


FAMILIES = ("receptacle", "switch_sensor", "luminaire")
INSERTED = {"instance_id": "synthetic_1", "symbol_family": "receptacle",
            "bbox_xyxy": [10, 10, 40, 40], "uncertain": False}


def test_unverified_background_is_never_certified_empty():
    base = {"instances": [], "exhaustive_families": []}
    no_insertions = composite_supervision(base, [], generated_families=FAMILIES)
    assert no_insertions["instances"] == []
    assert no_insertions["verified_no_symbols"] is False
    assert no_insertions["exhaustive_families"] == []
    assert no_insertions["annotation_scope"] == "positive_only"

    with_insertions = composite_supervision(base, [INSERTED], generated_families=FAMILIES)
    assert len(with_insertions["instances"]) == 1
    assert with_insertions["verified_no_symbols"] is False
    assert with_insertions["source_background_verified_no_symbols"] is False
    assert with_insertions["exhaustive_families"] == []
    assert with_insertions["annotation_completeness_verified"] is False


def test_verified_empty_source_can_produce_both_training_modes():
    base = {"instances": [], "exhaustive_families": [],
            "verified_no_symbols": True}
    negative = composite_supervision(base, [], generated_families=FAMILIES)
    assert negative["verified_no_symbols"] is True
    assert negative["source_background_verified_no_symbols"] is True
    assert negative["exhaustive_families"] == sorted(FAMILIES)
    assert negative["instances"] == []

    positive = composite_supervision(base, [INSERTED], generated_families=FAMILIES)
    assert positive["verified_no_symbols"] is False
    assert positive["source_background_verified_no_symbols"] is True
    assert positive["annotation_completeness_verified"] is True
    assert positive["exhaustive_families"] == sorted(FAMILIES)
    assert positive["instances"] == [INSERTED]


def test_existing_exhaustiveness_and_boxes_are_preserved():
    original = {"instance_id": "verified_1", "symbol_family": "switch_sensor",
                "bbox_xyxy": [50, 50, 70, 70]}
    base = {"instances": [original], "exhaustive_families": ["switch_sensor"]}
    result = composite_supervision(base, [INSERTED], generated_families=FAMILIES)
    assert result["exhaustive_families"] == ["switch_sensor"]
    assert result["instances"] == [original, INSERTED]
    assert base["instances"] == [original]
    assert base["exhaustive_families"] == ["switch_sensor"]


@pytest.mark.parametrize("source", [
    {"verified_no_symbols": True, "instances": [INSERTED]},
    {"verified_no_symbols": "true", "instances": []},
    {"verified_no_symbols": 1, "instances": []},
    {"exhaustive_families": "receptacle", "instances": []},
])
def test_invalid_source_coverage_fails_closed(source):
    with pytest.raises(ValueError):
        composite_supervision(source, [INSERTED], generated_families=FAMILIES)


def test_generated_family_validation():
    with pytest.raises(ValueError, match="outside generated_families"):
        composite_supervision({"instances": []}, [{"symbol_family": "unknown"}],
                              generated_families=FAMILIES)


def test_negative_generation_refuses_unverified_source(tmp_path, monkeypatch):
    from scripts_composite_electrical import main

    source = tmp_path / "unreviewed.json"
    source.write_text(json.dumps({
        "project_family_id": "independent-project",
        "source_group_verified": True,
        "image_path": "placeholder.png",
        "instances": [],
        "exhaustive_families": [],
        "verified_no_symbols": False,
    }))
    monkeypatch.setattr(sys, "argv", [
        "scripts_composite_electrical.py", str(source),
        str(tmp_path / "generated"), "--negative",
    ])
    with pytest.raises(SystemExit, match="requires verified_no_symbols"):
        main()

def test_neca_writer_keeps_unreviewed_background_positive_only(tmp_path, monkeypatch):
    import cv2
    import numpy as np
    import scripts_composite_neca_svg as script

    image = tmp_path / "plan.png"
    assert cv2.imwrite(str(image), np.full((256, 256, 3), 255, dtype=np.uint8))
    annotation = tmp_path / "plan.json"
    annotation.write_text(json.dumps({
        "image_path": image.name, "project_family_id": "source-project",
        "source_group_verified": True, "instances": [],
        "exhaustive_families": [], "verified_no_symbols": False,
    }))

    class DummyBank:
        def __init__(self, root):
            pass

        def summary(self):
            return {}

    glyph = {
        "instance_id": "svg_1", "symbol_family": "receptacle",
        "bbox_xyxy": [80, 80, 100, 100], "uncertain": False,
        "on_wall": False, "wall_connection_point": [90, 90],
    }

    monkeypatch.setattr(script, "NecaSymbolBank", DummyBank)
    monkeypatch.setattr(script, "composite_neca_page",
                        lambda doc, image, bank, rng, **kw: (image.copy(), [glyph]))
    monkeypatch.setattr(sys, "argv", [
        "scripts_composite_neca_svg.py", "--neca-dir", str(tmp_path),
        "--backgrounds", str(annotation), "--output", str(tmp_path / "neca"),
        "--preview", "0",
    ])
    script.main()
    output = json.loads(next((tmp_path / "neca").glob("*.json")).read_text())
    assert output["instances"] == [glyph]
    assert output["verified_no_symbols"] is False
    assert output["exhaustive_families"] == []
    assert output["annotation_scope"] == "positive_only"
    assert output["source_background_verified_no_symbols"] is False


def test_procedural_writer_cannot_promote_unknown_background(tmp_path, monkeypatch):
    import cv2
    import numpy as np
    import scripts_composite_electrical as script

    image = tmp_path / "plan.png"
    assert cv2.imwrite(str(image), np.full((256, 256, 3), 255, dtype=np.uint8))
    annotation = tmp_path / "plan.json"
    annotation.write_text(json.dumps({
        "image_path": image.name, "project_family_id": "source-project",
        "source_group_verified": True, "instances": [],
        "exhaustive_families": [], "verified_no_symbols": False,
    }))
    glyph = {
        "instance_id": "proc_1", "symbol_family": "receptacle",
        "bbox_xyxy": [80, 80, 100, 100], "uncertain": False,
        "on_wall": False,
    }
    monkeypatch.setattr(script, "composite_page",
                        lambda doc, img, rng, **kw: (img.copy(), [glyph]))
    monkeypatch.setattr(sys, "argv", [
        "scripts_composite_electrical.py", str(annotation), str(tmp_path / "proc"),
        "--per-page", "1",
    ])
    script.main()
    output = json.loads(next((tmp_path / "proc").glob("*.json")).read_text())
    assert output["instances"] == [glyph]
    assert output["verified_no_symbols"] is False
    assert output["exhaustive_families"] == []
    assert output["annotation_completeness_verified"] is False
    assert output["annotation_scope"] == "positive_only"


def _same_named_source_pages(tmp_path):
    import cv2
    import numpy as np

    sources = []
    for ix, name in enumerate(('site_one', 'site_two')):
        folder = tmp_path / name
        folder.mkdir()
        image = folder / 'sheet.png'
        assert cv2.imwrite(str(image), np.full((256, 256, 3), 210 + ix * 20, np.uint8))
        annotation = folder / 'sheet.json'
        annotation.write_text(json.dumps({
            'image_path': image.name, 'image_width': 256, 'image_height': 256,
            'project_family_id': name, 'source_group_verified': True,
            'instances': [], 'exhaustive_families': [],
            'verified_no_symbols': False,
        }))
        sources.append(annotation)
    return sources


def test_composite_output_identity_is_source_aware(tmp_path):
    from blueprint_symbols.composite_output import source_stem, source_seed

    sources = _same_named_source_pages(tmp_path)
    a, b = sources
    assert a.name == b.name
    assert source_stem(a, 'el', 0) != source_stem(b, 'el', 0)
    assert source_stem(a, 'el', 0) == source_stem(a.resolve(), 'el', 0)
    assert source_stem(a, 'el', 0) != source_stem(a, 'neca', 0)
    assert source_stem(a, 'el', 0) != source_stem(a, 'el', 1)
    assert source_seed(a, namespace='electrical', seed=7, copy=0) != source_seed(
        b, namespace='electrical', seed=7, copy=0
    )
    assert source_seed(a, namespace='electrical', seed=7, copy=0) == source_seed(
        a.resolve(), namespace='electrical', seed=7, copy=0
    )


def test_procedural_writer_keeps_same_named_source_pages_distinct(tmp_path, monkeypatch):
    import cv2
    import numpy as np
    import scripts_composite_electrical as script

    sources = _same_named_source_pages(tmp_path)
    glyph = {
        'instance_id': 'inserted_1', 'symbol_family': 'receptacle',
        'bbox_xyxy': [80, 80, 100, 100], 'uncertain': False,
        'on_wall': False,
    }
    monkeypatch.setattr(script, 'composite_page',
                        lambda doc, image, rng, **kw: (image.copy(), [glyph]))
    output = tmp_path / 'generated'
    monkeypatch.setattr(sys, 'argv', [
        'scripts_composite_electrical.py', *map(str, sources), str(output),
        '--copies', '2', '--per-page', '1',
    ])
    script.main()
    annotations = sorted(output.glob('*.json'))
    assert len(annotations) == 4
    loaded = [json.loads(f.read_text()) for f in annotations]
    assert {doc['project_family_id'] for doc in loaded} == {'site_one', 'site_two'}
    assert len({doc['document_id'] for doc in loaded}) == 4
    assert len({doc['image_path'] for doc in loaded}) == 4
    assert all((output / doc['image_path']).is_file() for doc in loaded)
    assert len({doc['composite']['seed'] for doc in loaded}) == 4
    assert {doc['composite']['background_annotation'] for doc in loaded} == {
        str(source.resolve()) for source in sources
    }
    before = {f.name: f.read_bytes() for f in output.iterdir() if f.is_file()}
    with pytest.raises(FileExistsError):
        script.main()
    assert before == {f.name: f.read_bytes() for f in output.iterdir() if f.is_file()}


def test_neca_writer_keeps_same_named_source_pages_distinct(tmp_path, monkeypatch):
    import cv2
    import numpy as np
    import scripts_composite_neca_svg as script

    sources = _same_named_source_pages(tmp_path)
    class DummyBank:
        def __init__(self, root):
            pass
        def summary(self):
            return {}
    glyph = {
        'instance_id': 'svg_1', 'symbol_family': 'receptacle',
        'bbox_xyxy': [80, 80, 100, 100], 'uncertain': False,
        'on_wall': False, 'wall_connection_point': [90, 90],
    }
    monkeypatch.setattr(script, 'NecaSymbolBank', DummyBank)
    monkeypatch.setattr(script, 'composite_neca_page',
                        lambda doc, image, bank, rng, **kw: (image.copy(), [glyph]))
    output = tmp_path / 'generated_neca'
    monkeypatch.setattr(sys, 'argv', [
        'scripts_composite_neca_svg.py', '--neca-dir', str(tmp_path),
        '--backgrounds', *map(str, sources), '--output', str(output),
        '--copies', '2', '--preview', '0',
    ])
    script.main()
    annotations = sorted(output.glob('*.json'))
    assert len(annotations) == 4
    loaded = [json.loads(f.read_text()) for f in annotations]
    assert {doc['project_family_id'] for doc in loaded} == {'site_one', 'site_two'}
    assert len({doc['document_id'] for doc in loaded}) == 4
    assert len({doc['image_path'] for doc in loaded}) == 4
    assert all((output / doc['image_path']).is_file() for doc in loaded)
    assert len({doc['composite']['seed'] for doc in loaded}) == 4
    before = {f.name: f.read_bytes() for f in output.iterdir() if f.is_file()}
    with pytest.raises(FileExistsError):
        script.main()
    assert before == {f.name: f.read_bytes() for f in output.iterdir() if f.is_file()}
