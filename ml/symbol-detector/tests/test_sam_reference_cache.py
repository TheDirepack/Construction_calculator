"""CPU tests for SAM local reference quality and safe prompt caching."""
from collections import Counter
from dataclasses import dataclass

import numpy as np
import pytest
import torch

from blueprint_symbols.reference_quality import reference_quality_reason
from blueprint_symbols.neca_prompt_catalog import SamNecaPrompt, load_neca_sam_prompts
from blueprint_symbols.preencoded_prompt_cache import (
    prompt_feature_cache_path, save_prompt_features, load_prompt_features,
)
from blueprint_symbols.sam_precision_profile import (
    load_family_thresholds, threshold_for_family, validate_family_thresholds,
)


def test_reference_quality_rejects_blank_and_filled():
    assert reference_quality_reason(np.zeros((30, 30), np.uint8)) == 'too_sparse'
    assert reference_quality_reason(np.full((30, 30), 255, np.uint8)) == 'mostly_solid'
    circle = np.zeros((40, 40), np.uint8)
    yy, xx = np.ogrid[:40, :40]
    circle[(xx - 20) ** 2 + (yy - 20) ** 2 < 11 ** 2] = 255
    assert reference_quality_reason(circle) is None


def test_reference_render_cache_reuses_svg_without_rendering(tmp_path):
    svg = tmp_path / 'symbol.svg'
    svg.write_text(
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">'
        '<circle cx="50" cy="50" r="25" fill="none" stroke="#000" stroke-width="6"/>'
        '</svg>'
    )
    ref = SamNecaPrompt('ref0', '', 'duplex receptacle', 'Outlets and Receptacles',
                        'power_outlet', 'receptacle', 'duplex', 'power_outlet',
                        'duplex', 'numbered_row', True, 'symbol.svg')
    first_stats, second_stats = Counter(), Counter()
    first = load_neca_sam_prompts(
        [ref], tmp_path, cache_dir=tmp_path / 'cache', reference_stats=first_stats,
    )
    second = load_neca_sam_prompts(
        [ref], tmp_path, cache_dir=tmp_path / 'cache', reference_stats=second_stats,
    )
    assert len(first) == len(second) == 1
    assert first_stats['rasterized'] == 1
    assert second_stats['raster_cache_hits'] == 1
    np.testing.assert_array_equal(first[0].alpha, second[0].alpha)


def test_safe_encoded_prompt_cache_roundtrip_and_invalidates_changed_alpha(tmp_path):
    @dataclass(frozen=True)
    class Ref:
        symbol_id: str
        alpha: np.ndarray

    glyph = Ref('idA', np.ones((16, 16), dtype=np.uint8))
    kwargs = dict(
        model_id='local/sam3.1', model_revision='commit-abc',
        model_config='{"image_size":1008}', dtype=torch.float32, target_px=44,
    )
    path = prompt_feature_cache_path(tmp_path, [glyph], **kwargs)
    other = Ref('idA', np.zeros((16, 16), dtype=np.uint8))
    assert path != prompt_feature_cache_path(tmp_path, [other], **kwargs)
    assert load_prompt_features(path, glyph_count=1, device='cpu',
                                dtype=torch.float32) is None
    tensors = (
        torch.randn(1, 3, 8), torch.ones(1, 3, dtype=torch.bool),
        torch.randn(1, 4, 8), torch.ones(1, 4, dtype=torch.bool),
    )
    save_prompt_features(path, tensors)
    loaded = load_prompt_features(path, glyph_count=1, device='cpu',
                                  dtype=torch.float32)
    assert loaded is not None
    for actual, expected in zip(loaded, tensors):
        torch.testing.assert_close(actual, expected)
    assert load_prompt_features(path, glyph_count=3, device='cpu',
                                dtype=torch.float32) is None


def test_independent_precision_profiles_are_validated(tmp_path):
    settings = {'switch': {'coarse': 0.62, 'fine': 0.87},
                'receptacle': {'fine': 0.83}}
    f = tmp_path / 'thresholds.json'
    import json
    f.write_text(json.dumps(settings))
    result = load_family_thresholds(f, {'switch', 'receptacle'})
    assert threshold_for_family('switch', 'fine', 0.72, result) == 0.87
    assert threshold_for_family('switch', 'coarse', 0.5, result) == 0.62
    assert threshold_for_family('receptacle', 'coarse', 0.5, result) == 0.5
    for bad in [{'switch': {'fine': 0}}, {'unknown': {'fine': .85}},
                {'switch': {'other': .8}}, {'switch': {'fine': True}}]:
        with pytest.raises(ValueError):
            validate_family_thresholds(bad, {'switch'})



def test_exterior_circuit_label_trim_preserves_core_and_internal_letter():
    from blueprint_symbols.reference_quality import trim_external_reference_annotations
    # External title/schedule text above; circle outline + internal letter below.
    import cv2
    alpha = np.zeros((70, 130), dtype=np.uint8)
    cv2.circle(alpha, (82, 46), 18, 255, 2)
    cv2.putText(alpha, 'S', (75, 52), cv2.FONT_HERSHEY_SIMPLEX, .5, 255, 1)
    cv2.putText(alpha, 'EP-2 CKT.1', (2, 15), cv2.FONT_HERSHEY_SIMPLEX, .42, 255, 1)
    cropped, changed = trim_external_reference_annotations(
        alpha, 'receptacle.wall_duplex_emergency_filled')
    assert changed is True
    assert cropped.shape[1] < alpha.shape[1]
    assert cropped.shape[0] < alpha.shape[0]
    assert np.count_nonzero(cropped) >= 50

    untrimmed, changed = trim_external_reference_annotations(
        alpha, 'luminaire.track')
    assert changed is False
    np.testing.assert_array_equal(untrimmed, alpha)
