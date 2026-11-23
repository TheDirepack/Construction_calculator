"""CPU smoke: actual randomly initialized YOLO26n-P2 SSL forward/backward.

This covers model feature taps, decoder size, weighted reconstruction loss,
and learning signal into the *backbone* rather than just the decoder.
No dataset, GPU or external checkpoint is required.
"""
from __future__ import annotations

import pytest

torch = pytest.importorskip("torch")
pytest.importorskip("ultralytics")
from blueprint_symbols.ssl_pretrain import Yolo26P2SSLModel, BlueprintSSLLoss


def test_real_yolo26p2_ssl_forward_backward_cpu():
    torch.manual_seed(122)
    previous_threads = torch.get_num_threads()
    torch.set_num_threads(min(previous_threads, 2))
    try:
        model = Yolo26P2SSLModel("yolo26n-p2.yaml", imgsz=128).train()
        criterion = BlueprintSSLLoss()
        degraded = torch.rand((1, 3, 128, 128), dtype=torch.float32)
        target = torch.rand((1, 3, 128, 128), dtype=torch.float32)
        mask = torch.zeros((1, 1, 128, 128), dtype=torch.float32)
        mask[:, :, 32:64, 32:64] = 1.0

        predicted = model(degraded)
        assert predicted.shape == target.shape
        assert torch.isfinite(predicted).all()
        assert torch.all((0 <= predicted) & (predicted <= 1))
        loss, stats = criterion(predicted, target, mask)
        assert torch.isfinite(loss)
        assert 0 < stats["loss"] < 20
        loss.backward()

        grad_backbone = model.det_model.model[0].conv.weight.grad
        grad_decoder = model.head.weight.grad
        assert grad_backbone is not None and torch.isfinite(grad_backbone).all()
        assert grad_decoder is not None and torch.isfinite(grad_decoder).all()
        assert grad_backbone.abs().sum().item() > 0
        assert grad_decoder.abs().sum().item() > 0
    finally:
        torch.set_num_threads(previous_threads)
