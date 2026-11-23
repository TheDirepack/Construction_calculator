"""Verify effective-batch gradient accumulation with the actual MuSGD optimizer.

This is a CPU numerical equivalence test for a model without BatchNorm.
BatchNorm running statistics are inherently dependent on physical microbatch
size; GPU profiling and full model convergence must still be evaluated.
"""
from __future__ import annotations

import argparse
from copy import deepcopy

import pytest

torch = pytest.importorskip("torch")

from scripts_pretrain_ssl import (
    accumulated_training_update,
    build_musgd_optimizer,
    configured_microbatch,
    resolve_batch_plan,
    run_fingerprint,
    validate_pretrain_settings,
)


class _MSELoss(torch.nn.Module):
    def forward(self, pred, target, _mask):
        loss = (pred - target).square().mean()
        value = float(loss.detach().item())
        return loss, {"loss": value, "l1": value, "edge": 0.0}


def _microbatches(inputs, targets, chunk):
    for i in range(0, len(inputs), chunk):
        size = len(inputs[i:i + chunk])
        yield inputs[i:i + chunk], targets[i:i + chunk], torch.zeros(size, 1)


@pytest.mark.parametrize("logical,physical", [(16, 4), (14, 2)])
def test_effective_musgd_batch_matches_full_batch_update(logical, physical):
    torch.manual_seed(1234)
    inputs = torch.randn(logical, 3)
    targets = torch.randn(logical, 2)
    model = torch.nn.Sequential(
        torch.nn.Linear(3, 6), torch.nn.SiLU(), torch.nn.Linear(6, 2)
    )
    reference = deepcopy(model)
    opt = build_musgd_optimizer(model, lr=.005, momentum=.9)
    ref_opt = build_musgd_optimizer(reference, lr=.005, momentum=.9)
    assert type(opt).__name__ == "MuSGD"
    assert any(g["use_muon"] for g in opt.param_groups)
    assert any(not g["use_muon"] for g in opt.param_groups)
    scaler = torch.amp.GradScaler("cpu", enabled=False)
    result = accumulated_training_update(
        model, _MSELoss(), opt, scaler,
        _microbatches(inputs, targets, chunk=physical),
        device=torch.device("cpu"), effective_batch=logical, grad_clip=1e6,
    )
    ref_opt.zero_grad(set_to_none=True)
    ref_loss = (reference(inputs) - targets).square().mean()
    ref_loss.backward()
    ref_opt.step()

    assert result["samples"] == float(logical)
    assert result["loss"] == pytest.approx(float(ref_loss.item()), abs=5e-7)
    for a, b in zip(model.parameters(), reference.parameters()):
        assert torch.allclose(a, b, atol=1e-5, rtol=1e-5)


def test_one_musgd_step_for_four_unequal_microbatches_totalling_sixteen():
    torch.manual_seed(41)
    model = torch.nn.Linear(3, 2)
    opt = build_musgd_optimizer(model, lr=.005)
    scaler = torch.amp.GradScaler("cpu", enabled=False)
    inputs = torch.randn(16, 3)
    targets = torch.randn(16, 2)
    chunks = [(1, 1), (2, 2), (5, 5), (8, 8)]
    offset = 0
    batches = []
    for ni, nt in chunks:
        assert ni == nt
        batches.append((
            inputs[offset:offset+ni], targets[offset:offset+nt],
            torch.zeros(ni, 1),
        ))
        offset += ni
    before = model.weight.detach().clone()
    report = accumulated_training_update(
        model, _MSELoss(), opt, scaler, batches,
        device=torch.device("cpu"), effective_batch=16,
    )
    assert report["samples"] == 16
    assert not torch.equal(before, model.weight.detach())
    assert torch.isfinite(model.weight).all()
    assert torch.isfinite(torch.tensor(report["grad_norm"]))


@pytest.mark.parametrize("effective,micro,steps", [
    (14, 2, 7), (14, 1, 14), (14, 7, 2), (14, 14, 1),
    (16, 4, 4), (16, 2, 8), (16, 1, 16), (16, 16, 1),
    (2, 2, 1), (8, 4, 2),
])
def test_microbatch_plans(effective, micro, steps):
    assert resolve_batch_plan(effective, micro) == (micro, steps)


@pytest.mark.parametrize("effective,micro", [
    (16, 3), (16, 0), (16, 17), (0, 1), (-1, 1)
])
def test_microbatch_rejects_invalid_plans(effective, micro):
    with pytest.raises(ValueError, match="microbatch"):
        resolve_batch_plan(effective, micro)


def test_configured_microbatch_defaults_for_small_smoke_runs():
    assert configured_microbatch(argparse.Namespace(batch=14)) == 2
    assert configured_microbatch(argparse.Namespace(batch=16)) == 2
    assert configured_microbatch(argparse.Namespace(batch=2)) == 2
    assert configured_microbatch(argparse.Namespace(batch=16, microbatch=2)) == 2


def test_changes_to_gpu_microbatch_invalidate_checkpoint_fingerprint():
    args = argparse.Namespace(
        imgsz=512, batch=16, microbatch=4, epochs=60,
        items_per_epoch=8192, val_items=512, optimizer="MuSGD", lr=.005,
        workers=2, pool_val_fraction=.1, seed=2026,
        max_symbols=16, symbol_min_px=12., symbol_max_px=80.,
        symbol_augment_probability=1., procedural_fraction=.25,
        clean_fraction=.20, labeled_focus_probability=.65,
        symbol_chaos_probability=.88, symbol_chaos_strength=1.,
        placement_chaos=.85, page_chaos_probability=.70,
        page_chaos_strength=1., neca_dir=None,
    )
    train = {"buckets": {"drawings": {"groups": {"project": ["img.png"]}}}}
    old = run_fingerprint(train, {"buckets": {}}, args)
    args.microbatch = 2
    assert run_fingerprint(train, {"buckets": {}}, args) != old


def test_pretraining_rejects_partial_optimizer_steps():
    from types import SimpleNamespace
    args = SimpleNamespace(
        imgsz=512, batch=16, microbatch=4, epochs=60,
        items_per_epoch=8191, val_items=512, workers=2,
        pool_val_fraction=.1, lr=.005,
        max_symbols=16, symbol_min_px=12., symbol_max_px=80.,
        symbol_augment_probability=1., procedural_fraction=.25,
        clean_fraction=.20, labeled_focus_probability=.65,
        symbol_chaos_probability=.88, symbol_chaos_strength=1.,
        placement_chaos=.85, page_chaos_probability=.70,
        page_chaos_strength=1.,
    )
    with pytest.raises(ValueError, match="divisible"):
        validate_pretrain_settings(args)


def test_no_optimizer_step_on_incomplete_accumulation():
    torch.manual_seed(4)
    model = torch.nn.Linear(3, 2)
    opt = build_musgd_optimizer(model, lr=.005)
    scaler = torch.amp.GradScaler("cpu", enabled=False)
    before = model.weight.detach().clone()
    with pytest.raises(ValueError, match="incomplete"):
        accumulated_training_update(
            model, _MSELoss(), opt, scaler,
            _microbatches(torch.randn(12, 3), torch.randn(12, 2), 4),
            device=torch.device("cpu"), effective_batch=16,
        )
    assert torch.equal(before, model.weight.detach())



def test_run_safety_refuses_to_overwrite_existing_manifests(tmp_path, monkeypatch):
    """Checkpoint mismatch must be detected before any files are rewritten."""
    import scripts_pretrain_ssl as script

    out = tmp_path / "run"
    out.mkdir()
    (out / "last_pretrain.pt").write_bytes(b"prior checkpoint")
    (out / "train_manifest.json").write_text("original manifest bytes")
    (out / "run_fingerprint.txt").write_text("old-data-fingerprint")

    monkeypatch.setattr(script, "build_pretrain_manifests",
                        lambda *a, **k: ({"buckets": {}}, {"buckets": {}}, {}))
    monkeypatch.setattr(script, "run_fingerprint",
                        lambda *a, **k: "different-fingerprint")
    args = argparse.Namespace(
        data_root=tmp_path, work_root=tmp_path, out=out,
        fpcad_train_dir=None, labeled_annotations=[], neca_dir=None,
        imgsz=512, batch=16, microbatch=4, epochs=60,
        items_per_epoch=8192, val_items=512, workers=2,
        pool_val_fraction=.1, lr=.005, optimizer="MuSGD", seed=99,
        max_symbols=16, symbol_min_px=12., symbol_max_px=80.,
        symbol_augment_probability=1., procedural_fraction=.25,
        clean_fraction=.20, labeled_focus_probability=.65,
        symbol_chaos_probability=.88, symbol_chaos_strength=1.,
        placement_chaos=.85, page_chaos_probability=.70,
        page_chaos_strength=1., init_weights=None, run=True, resume=False,
    )
    with pytest.raises(SystemExit, match="Existing SSL run"):
        script.train(args)
    assert (out / "train_manifest.json").read_text() == "original manifest bytes"

    args.resume = True
    with pytest.raises(ValueError, match="existing run files untouched"):
        script.train(args)
    assert (out / "train_manifest.json").read_text() == "original manifest bytes"
    assert (out / "run_fingerprint.txt").read_text() == "old-data-fingerprint"


def test_default_pretraining_batch14_uses_full_optimizer_steps():
    import scripts_pretrain_ssl as script
    opts = script.argparse.ArgumentParser()
    # Parser defaults are tested via the actual CLI entry point by inspecting
    # the module's declared option definitions below.
    from inspect import getsource
    src = getsource(script.main)
    assert "default=14" in src and "default=8190" in src
    assert script.resolve_batch_plan(14, script.configured_microbatch(
        argparse.Namespace(batch=14)
    )) == (2, 7)
    assert 8190 % 14 == 0
