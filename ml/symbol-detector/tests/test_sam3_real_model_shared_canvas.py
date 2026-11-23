"""Tiny randomly initialized SAM3 full-vision smoke on a SAME-image NECA tile.

Adapted from Transformers' own SAM3ModelTester configuration. No weights,
network, real detection claims, or GPU required. The purpose is to execute
the production scanner's real vision/geometry/DETR stack once end to end.
"""
from __future__ import annotations

import pytest
pytest.importorskip("transformers", reason="SAM-only API tests need optional Transformers runtime")

from collections import Counter
from types import SimpleNamespace

import cv2
import numpy as np
import torch
from PIL import Image

from transformers import Sam3Config, Sam3Model
from transformers.models.sam3.configuration_sam3 import (
    Sam3ViTConfig, Sam3VisionConfig, Sam3GeometryEncoderConfig,
    Sam3DETREncoderConfig, Sam3DETRDecoderConfig, Sam3MaskDecoderConfig,
)

from blueprint_symbols.sam_shared_canvas import build_shared_canvas


class SmallProcessor:
    def __call__(self, *, images, return_tensors):
        assert return_tensors == 'pt'
        assert isinstance(images, Image.Image) and images.size == (224, 224)
        pixels = np.asarray(images, dtype=np.float32).copy() / 255.
        return {'pixel_values': torch.from_numpy(pixels).permute(2, 0, 1).unsqueeze(0)}


def _model():
    backbone = Sam3ViTConfig(
        hidden_size=32, num_hidden_layers=2, num_attention_heads=4,
        intermediate_size=64, num_channels=3, image_size=224,
        patch_size=14, window_size=8, global_attn_indexes=[0,1],
    )
    return Sam3Model(Sam3Config(
        vision_config=Sam3VisionConfig(
            backbone_config=backbone, fpn_hidden_size=32,
            scale_factors=[2.0, 1.0],
        ),
        text_config={
            'vocab_size': 1000, 'hidden_size': 32,
            'intermediate_size': 64, 'projection_dim': 32,
            'num_hidden_layers': 2, 'num_attention_heads': 4,
            'max_position_embeddings': 32, 'hidden_act':'gelu',
        },
        geometry_encoder_config=Sam3GeometryEncoderConfig(
            hidden_size=32, num_layers=1, num_attention_heads=4,
            intermediate_size=64, roi_size=3,
        ),
        detr_encoder_config=Sam3DETREncoderConfig(
            hidden_size=32, num_layers=1, num_attention_heads=4,
            intermediate_size=64,
        ),
        detr_decoder_config=Sam3DETRDecoderConfig(
            hidden_size=32, num_layers=1, num_queries=5,
            num_attention_heads=4, intermediate_size=64,
        ),
        mask_decoder_config=Sam3MaskDecoderConfig(
            hidden_size=32, num_upsampling_stages=2,
        ),
    )).eval()


def test_real_sam3_vision_and_shared_image_geometry_complete_one_blueprint_tile():
    from scripts_run_sam3_pdfs import detect_page_sam3

    torch.manual_seed(123)
    glyphs = []
    for i, family in enumerate(['switch','receptacle']):
        alpha = np.zeros((20,20),dtype=np.uint8)
        cv2.circle(alpha,(10,10),7,255,2)
        glyphs.append(SimpleNamespace(
            symbol_id=str(i), alpha=alpha,
            device_type=family, subtype='unspecified',
            visual_group=family, canonical_class=family,
            code=str(i), name=family, category=family,
            subcategory='unspecified', encoded_attributes={},
        ))
    canvas = build_shared_canvas(
        glyphs, tile_size=224, slot_pitch=16, reference_px=10,
        min_target_width=96,
    )
    tile = np.full((224, 200, 3),255,dtype=np.uint8)
    cv2.circle(tile,(95,105),10,(0,0,0),2)
    model = _model()
    with torch.inference_mode():
        text = model.get_text_features(
            input_ids=torch.ones(1,16,dtype=torch.long),
            attention_mask=torch.ones(1,16,dtype=torch.long),
            return_dict=True,
        ).pooler_output
        stats = Counter()
        # Threshold 1.0 ensures randomized weights produce no accepted
        # detections; the full real SAM3 vision/geometry/DETR path still runs.
        result = detect_page_sam3(
            model, SmallProcessor(), tile, glyphs,
            torch.empty(0,2,32), torch.empty(0,2,dtype=torch.bool),
            text, torch.ones(1,16,dtype=torch.bool),
            shared_canvas=canvas,
            prompt_plan={'mode':'grouped'},
            svg_batch=2, score_thr=1.0,
            tile_size=224, overlap=32, min_box_px=2,
            device='cpu', dtype=torch.float32,
            query_stats=stats,
        )
    assert result == []
    assert stats['vision_encoded_tiles'] == 1
    assert stats['visual_base_prompts'] == len(glyphs)
