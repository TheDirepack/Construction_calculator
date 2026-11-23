"""Run real, randomly initialized HF SAM3 geometry + DETR components on CPU.

Unlike the deterministic page-scanner mock, this test executes the exact
Transformers 5.20.1 layers and tensor shapes used by self-hosted inference.
It tests API compatibility, NOT semantic zero-shot detection quality.
"""
import pytest
pytest.importorskip("transformers", reason="SAM-only API tests need optional Transformers runtime")

import torch

from transformers import Sam3Config
from transformers.models.sam3.configuration_sam3 import (
    Sam3GeometryEncoderConfig,
    Sam3DETREncoderConfig,
    Sam3DETRDecoderConfig,
)
from transformers.models.sam3.modeling_sam3 import (
    Sam3GeometryEncoder,
    Sam3DetrEncoder,
    Sam3DetrDecoder,
    Sam3DotProductScoring,
    box_cxcywh_to_xyxy,
    inverse_sigmoid,
)


def test_real_sam3_components_batch_geometry_and_produce_valid_boxes():
    torch.manual_seed(42)
    hidden = 32
    geometry_cfg = Sam3GeometryEncoderConfig(
        hidden_size=hidden, num_layers=1, num_attention_heads=4,
        intermediate_size=64, roi_size=3, dropout=0.0,
    )
    encoder_cfg = Sam3DETREncoderConfig(
        hidden_size=hidden, num_layers=1, num_attention_heads=4,
        intermediate_size=64, dropout=0.0,
    )
    decoder_cfg = Sam3DETRDecoderConfig(
        hidden_size=hidden, num_layers=1, num_queries=3,
        num_attention_heads=4, intermediate_size=64, dropout=0.0,
    )
    root_cfg = Sam3Config(
        geometry_encoder_config=geometry_cfg,
        detr_encoder_config=encoder_cfg,
        detr_decoder_config=decoder_cfg,
    )
    geometry = Sam3GeometryEncoder(root_cfg.geometry_encoder_config).eval()
    encoder = Sam3DetrEncoder(root_cfg.detr_encoder_config).eval()
    decoder = Sam3DetrDecoder(root_cfg.detr_decoder_config).eval()
    scorer = Sam3DotProductScoring(root_cfg).eval()

    # The two exemplars share reference image features, as in the scanner.
    features = torch.randn(1, hidden, 8, 8)
    positions = torch.randn_like(features)
    boxes = torch.tensor([[[.28, .35, .14, .12]],
                          [[.70, .63, .10, .15]]])
    labels = torch.ones(2, 1, dtype=torch.long)
    masks = torch.ones(2, 1, dtype=torch.bool)
    with torch.inference_mode():
        batch_geometry = geometry(
            box_embeddings=boxes, box_mask=masks, box_labels=labels,
            img_feats=[features.expand(2,-1,-1,-1)],
            img_pos_embeds=[positions.expand(2,-1,-1,-1)],
        )
        assert batch_geometry.last_hidden_state.shape == (2, 2, hidden)
        assert batch_geometry.attention_mask.shape == (2, 2)
        # Batching reference boxes must agree with sequential preencoding.
        for i in range(2):
            sequential = geometry(
                box_embeddings=boxes[i:i+1],
                box_mask=masks[i:i+1],
                box_labels=labels[i:i+1],
                img_feats=[features],
                img_pos_embeds=[positions],
            )
            torch.testing.assert_close(
                batch_geometry.last_hidden_state[i:i+1],
                sequential.last_hidden_state, atol=1e-5, rtol=1e-5,
            )
        text_features = torch.randn(2, 3, hidden)
        prompt_features = torch.cat([text_features,
                                     batch_geometry.last_hidden_state], dim=1)
        prompt_mask = torch.ones(2, 5, dtype=torch.bool)
        encoded = encoder(
            vision_features=[features.expand(2,-1,-1,-1)],
            text_features=prompt_features,
            vision_pos_embeds=[positions.expand(2,-1,-1,-1)],
            text_mask=prompt_mask,
        )
        decoded = decoder(
            vision_features=encoded.last_hidden_state,
            text_features=encoded.text_features,
            vision_pos_encoding=encoded.pos_embeds_flattened,
            text_mask=prompt_mask,
            spatial_shapes=encoded.spatial_shapes,
        )
        logits = scorer(
            decoder_hidden_states=decoded.intermediate_hidden_states,
            text_features=encoded.text_features,
            text_mask=prompt_mask,
        )[-1].squeeze(-1)
        presence = decoded.presence_logits[-1].squeeze(-1)
        if presence.ndim == 1:
            presence = presence.unsqueeze(-1)
        scores = logits.sigmoid() * presence.sigmoid()
        offsets = decoder.box_head(decoded.intermediate_hidden_states)[-1]
        refs = decoded.reference_boxes[-1]
        boxes_xyxy = box_cxcywh_to_xyxy((inverse_sigmoid(refs) + offsets).sigmoid())
        assert scores.shape == (2, 3)
        assert boxes_xyxy.shape == (2, 3, 4)
        assert torch.isfinite(scores).all()
        assert torch.isfinite(boxes_xyxy).all()
        assert (boxes_xyxy[..., 2:] >= boxes_xyxy[..., :2]).all()
