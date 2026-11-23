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
from scripts_pretrain_ssl import build_musgd_optimizer


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

        # MuSGD is the actual optimizer used on the remote training machine.
        # Check parameter grouping and one real update, not only loss.backward.
        optimizer = build_musgd_optimizer(model, lr=0.005)
        before = model.det_model.model[0].conv.weight.detach().clone()
        optimizer.step()
        after = model.det_model.model[0].conv.weight.detach()
        assert torch.isfinite(after).all()
        assert not torch.equal(before, after)
    finally:
        torch.set_num_threads(previous_threads)


def test_ssl_feature_probe_does_not_modify_batchnorm_statistics(monkeypatch):
    """The channel discovery pass is not a training minibatch."""
    import torch.nn as nn

    original = Yolo26P2SSLModel.extract_neck_features
    seen = []

    def inspect(self, x):
        bn = next(m for m in self.det_model.modules()
                  if isinstance(m, nn.BatchNorm2d))
        before = (bn.running_mean.clone(), bn.running_var.clone(),
                  bn.num_batches_tracked.clone())
        feats = original(self, x)
        after = (bn.running_mean, bn.running_var, bn.num_batches_tracked)
        seen.append((self.det_model.training, [
            torch.equal(a, b) for a, b in zip(before, after)
        ]))
        return feats

    monkeypatch.setattr(Yolo26P2SSLModel, "extract_neck_features", inspect)
    model = Yolo26P2SSLModel("yolo26n-p2.yaml", imgsz=128)
    assert seen and seen[0] == (False, [True, True, True])
    assert model.det_model.training


def test_real_yolo26n_p2_effective_batch16_musgd_cpu():
    """Four real minibatches of 4 feed exactly one YOLO MuSGD update."""
    from scripts_pretrain_ssl import accumulated_training_update

    torch.manual_seed(97)
    old_threads = torch.get_num_threads()
    torch.set_num_threads(min(old_threads, 2))
    try:
        model = Yolo26P2SSLModel("yolo26n-p2.yaml", imgsz=128).train()
        model.det_model.model[model.detect_idx].requires_grad_(False)
        criterion = BlueprintSSLLoss()
        optimizer = build_musgd_optimizer(model, lr=.005)
        scaler = torch.amp.GradScaler("cpu", enabled=False)
        assert type(optimizer).__name__ == "MuSGD"
        assert all(not p.requires_grad
                   for p in model.det_model.model[model.detect_idx].parameters())

        first_conv = model.det_model.model[0].conv.weight
        before = first_conv.detach().clone()

        def make_batches():
            for _ in range(4):
                degraded = torch.rand(4, 3, 128, 128)
                target = torch.rand(4, 3, 128, 128)
                mask = torch.zeros(4, 1, 128, 128)
                mask[:, :, 35:76, 40:91] = 1.0
                yield degraded, target, mask

        stats = accumulated_training_update(
            model, criterion, optimizer, scaler, make_batches(),
            device=torch.device("cpu"), effective_batch=16,
        )
        assert stats["samples"] == 16
        assert torch.isfinite(torch.tensor(stats["loss"]))
        assert torch.isfinite(torch.tensor(stats["grad_norm"]))
        assert first_conv.grad is not None
        assert not torch.equal(before, first_conv.detach())
        assert torch.isfinite(first_conv).all()
    finally:
        torch.set_num_threads(old_threads)
