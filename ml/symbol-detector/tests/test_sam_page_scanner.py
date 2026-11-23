"""Model-free CPU integration test for the actual SAM grouped page-scanner path.

Mocks the SAM *interface* with deterministic scores/boxes, not model weights.
Catches tiling, CPU/GPU tensor indexing, grouped subtype/provenance, and
stage-to-fusion integration bugs on every PR push.
"""
from __future__ import annotations

import importlib
import sys
import types
from collections import Counter
from types import SimpleNamespace

import cv2
import numpy as np
import torch


def _load_scanner_with_fake_transformers(monkeypatch):
    fake_t = types.ModuleType('transformers')
    fake_t.Sam3Model = type('Sam3Model', (), {})
    fake_t.Sam3Processor = type('Sam3Processor', (), {})
    fake_models = types.ModuleType('transformers.models')
    fake_sam3 = types.ModuleType('transformers.models.sam3')
    fake_modeling = types.ModuleType('transformers.models.sam3.modeling_sam3')

    def xyxy(boxes):
        cx, cy, w, h = boxes.unbind(-1)
        return torch.stack([cx-w/2, cy-h/2, cx+w/2, cy+h/2], -1)

    fake_modeling.box_cxcywh_to_xyxy = xyxy
    fake_modeling.inverse_sigmoid = lambda boxes: torch.logit(
        boxes.clamp(.00001, .99999)
    )

    for name, module in [
        ('transformers', fake_t),
        ('transformers.models', fake_models),
        ('transformers.models.sam3', fake_sam3),
        ('transformers.models.sam3.modeling_sam3', fake_modeling),
    ]:
        monkeypatch.setitem(sys.modules, name, module)
    # Remove a cached real module to ensure this test uses the deterministic
    # fake regardless of other tests' import order.
    monkeypatch.delitem(sys.modules, 'scripts_run_sam3_pdfs', raising=False)
    return importlib.import_module('scripts_run_sam3_pdfs')


class FakeSam3:
    def __init__(self):
        self.visions = 0
        self.query_batches = 0
        self.detr_decoder = self
        self.batch = 1
        self.fail_large_batches = False

    def get_vision_features(self, pixel_values):
        self.visions += 1
        t = torch.zeros(1, 4, 8, 8)
        return SimpleNamespace(fpn_hidden_states=(t, t, t),
                               fpn_position_encoding=(t, t, t))

    def detr_encoder(self, **kwargs):
        self.batch = kwargs['text_features'].shape[0]
        self.query_batches += 1
        if self.fail_large_batches and self.batch > 1:
            raise torch.cuda.OutOfMemoryError('simulated limited VRAM')
        t = torch.zeros(self.batch, 1, 4)
        return SimpleNamespace(last_hidden_state=t, text_features=kwargs['text_features'],
                               pos_embeds_flattened=t,
                               spatial_shapes=torch.tensor([[1, 1]]))

    def __call__(self, **kwargs):
        ref = torch.tensor([.50, .50, .06, .06]).reshape(1, 1, 1, 4)
        self.reference_boxes = ref.expand(1, self.batch, 1, 4)
        return SimpleNamespace(
            intermediate_hidden_states=torch.zeros(1, self.batch, 1, 4),
            presence_logits=torch.full((1, self.batch, 1), 12.0),
            reference_boxes=self.reference_boxes,
        )

    def box_head(self, features):
        return torch.zeros_like(self.reference_boxes)

    def dot_product_scoring(self, **kwargs):
        return torch.full((1, self.batch, 1, 1), 12.0)


class FakeProcessor:
    def __call__(self, **kwargs):
        return {'pixel_values': torch.zeros(1, 3, 8, 8)}


def _glyph(sym, family, group):
    return SimpleNamespace(
        symbol_id=sym, code=sym, name=sym, section='test',
        device_type=family, subtype='duplex', canonical_class=family,
        category='power_outlet', subcategory='duplex',
        visual_group=group, encoded_attributes={},
    )


def test_grouped_page_scanner_uses_minimal_tile_count_and_no_subtype_claim(monkeypatch):
    scanner = _load_scanner_with_fake_transformers(monkeypatch)
    image = np.full((1008, 1800, 3), 255, dtype=np.uint8)
    # 1800px page requires two 1008px tiles, not three. Fake model returns
    # a 60px centered box in each tile.
    for x in (504, 1384):
        cv2.circle(image, (x, 504), 24, (0, 0, 0), 3)
    glyphs = [
        _glyph('NECA_S', 'switch', 'switch.base_letter_s'),
        _glyph('NECA_R', 'receptacle', 'receptacle.wall_duplex_open'),
    ]
    model = FakeSam3()
    model.detr_decoder = model
    features = torch.zeros(2, 1, 4)
    masks = torch.ones(2, 1, dtype=torch.bool)
    text = torch.zeros(1, 1, 4)
    text_mask = torch.ones(1, 1, dtype=torch.bool)
    stats = Counter()
    dets = scanner.detect_page_sam3(
        model, FakeProcessor(), image, glyphs,
        features, masks, text, text_mask,
        prompt_plan={'mode': 'grouped', 'families': ['switch', 'receptacle']},
        svg_batch=2, query_stats=stats,
        device='cpu', dtype=torch.float32,
    )
    assert model.visions == 2
    assert model.query_batches == 2
    assert stats['visual_base_prompts'] == 4
    assert len(dets) == 2
    assert all(d['stage'] == 'visual_base' for d in dets)
    assert all(d['subtype'] == 'unspecified' for d in dets)
    assert all(d['family_only'] and d['needs_class_review'] for d in dets)
    assert all(not d['verified_ground_truth'] for d in dets)


def test_grouped_page_scanner_skips_blank_image_without_encoding(monkeypatch):
    scanner = _load_scanner_with_fake_transformers(monkeypatch)
    model = FakeSam3()
    model.detr_decoder = model
    img = np.full((1008, 1008, 3), 255, dtype=np.uint8)
    result = scanner.detect_page_sam3(
        model, FakeProcessor(), img,
        [_glyph('x', 'switch', 'switch.base_letter_s')],
        torch.zeros(1, 1, 4), torch.ones(1, 1, dtype=torch.bool),
        torch.zeros(1, 1, 4), torch.ones(1, 1, dtype=torch.bool),
        prompt_plan={'mode': 'grouped'}, device='cpu', dtype=torch.float32,
    )
    assert result == []
    assert model.visions == 0



def test_decoder_oom_retry_reuses_image_embedding_and_preserves_queries(monkeypatch):
    scanner = _load_scanner_with_fake_transformers(monkeypatch)
    image = np.full((1008, 1008, 3), 255, dtype=np.uint8)
    cv2.circle(image, (504, 504), 24, (0, 0, 0), 3)
    model = FakeSam3()
    model.fail_large_batches = True
    glyphs = [
        _glyph('NECA_S', 'switch', 'switch.base_letter_s'),
        _glyph('NECA_R', 'receptacle', 'receptacle.wall_duplex_open'),
    ]
    stats = Counter()
    dets = scanner.detect_page_sam3(
        model, FakeProcessor(), image, glyphs,
        torch.zeros(2, 1, 4), torch.ones(2, 1, dtype=torch.bool),
        torch.zeros(1, 1, 4), torch.ones(1, 1, dtype=torch.bool),
        prompt_plan={'mode': 'grouped'}, svg_batch=2,
        query_stats=stats, device='cpu', dtype=torch.float32,
    )
    assert model.visions == 1
    assert model.query_batches == 3  # one failed batch of 2, then 2x1
    assert stats['sam_inference_oom_retries'] == 1
    assert stats['visual_base_prompts'] == 2
    assert len(dets) == 1  # two label proposals fuse into one location
