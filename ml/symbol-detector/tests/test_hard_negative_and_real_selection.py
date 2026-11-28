"""Hard-negative-focused blank/complete weighting and real-score checkpoint selection."""
from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

from scripts_train_ultralytics import build_real_selection_callbacks, plan


# --------------------------------------------------------------------------
# B1: loss weighting (torch; skipped where torch/ultralytics are absent)
# --------------------------------------------------------------------------

def _bce(torch, **overrides):
    pytest.importorskip("ultralytics")
    from blueprint_symbols.partial_yolo import PositiveAwareBCE
    from blueprint_symbols.partial_supervision import DEFAULT_PHASE2_LOSS_CFG

    cfg = dict(DEFAULT_PHASE2_LOSS_CFG)
    cfg.update(overrides)
    return PositiveAwareBCE(**cfg), cfg


def _weights(torch, bce, logits, scores, coverage, roles, near=None):
    bce.verified_class_mask = coverage
    bce.sample_role = roles
    bce.near_gt_mask = near
    loss = bce(logits, scores)
    base = torch.nn.functional.binary_cross_entropy_with_logits(logits.detach(), scores, reduction="none")
    return loss, loss.detach() / base


def test_hard_negative_weighting_is_not_uniform():
    torch = pytest.importorskip("torch")
    from blueprint_symbols.partial_yolo import ROLE_PARTIAL, ROLE_REAL_BLANK, ROLE_REAL_COMPLETE

    topk = 4
    bce, cfg = _bce(torch, hard_neg_topk=float(topk), hard_neg_tile_cap=0.0)
    B, A, C = 3, 60, 2
    logits = torch.full((B, A, C), -6.0, dtype=torch.float64)
    # Blank tile: a few "dashed-line" anchors score high for class 0.
    hot = [3, 11, 17, 29, 41, 50]
    logits[0, hot, 0] = torch.tensor([0.5, 0.0, -0.36, -1.0, -2.0, -3.0], dtype=torch.float64)
    logits[0, 7, 1] = 1.0
    # Partial tile: unverified background must stay zero even when hot.
    logits[1, :, :] = 2.0
    # Real complete tile: GT at anchor 0 (class 0); anchors 1-2 are near GT.
    logits[2, 0:3, :] = 2.0
    logits[2, 30, 1] = 0.0
    logits.requires_grad_(True)
    scores = torch.zeros((B, A, C), dtype=torch.float64)
    scores[1, 5, 1] = 1.0  # partial known positive
    scores[2, 0, 0] = 1.0  # complete GT
    coverage = torch.tensor([[True, True], [False, False], [True, True]])
    roles = torch.tensor([ROLE_REAL_BLANK, ROLE_PARTIAL, ROLE_REAL_COMPLETE])
    near = torch.zeros((B, A), dtype=torch.bool)
    near[2, 0:3] = True

    loss, w = _weights(torch, bce, logits, scores, coverage, roles, near)
    p = torch.sigmoid(logits.detach())
    fp = 1.0 + cfg["fp_boost"] * p

    # Blank: exactly top-k anchors per class are boosted, all others ordinary weight.
    boosted = w[0] > cfg["hard_neg_base_weight"] + 1e-9
    assert boosted.sum(dim=0).tolist() == [topk, topk]
    assert set(torch.nonzero(boosted[:, 0]).flatten().tolist()) == set(hot[:topk])
    assert torch.allclose(w[0][~boosted], torch.ones_like(w[0][~boosted]))
    expect = cfg["hard_neg_base_weight"] + cfg["real_blank_neg_weight"] * fp[0][boosted]
    assert torch.allclose(w[0][boosted], expect)
    # The worst far negative gets far more than the old uniform-everywhere floor.
    assert w[0, 3, 0] > 10 * w[0, 59, 0]

    # Partial: unverified background is zero-weight, zero-gradient; positive and
    # fg-anchor wrong class unchanged.
    partial_bg = torch.ones((A, C), dtype=torch.bool)
    partial_bg[5, :] = False
    assert torch.all(loss[1][partial_bg] == 0)
    assert w[1, 5, 1].item() == pytest.approx(1.0)
    assert w[1, 5, 0].item() == pytest.approx(cfg["partial_wrong_cls_weight"] * fp[1, 5, 0].item())

    # Real complete: near-GT background never mined; legacy near/wrong/pos weights.
    half = 1.0 + 0.5 * cfg["fp_boost"] * p
    assert w[2, 1, 0].item() == pytest.approx(cfg["real_complete_near_neg_weight"] * half[2, 1, 0].item())
    assert w[2, 0, 1].item() == pytest.approx(cfg["real_complete_wrong_cls_weight"] * fp[2, 0, 1].item())
    assert w[2, 0, 0].item() == pytest.approx(cfg["real_complete_pos_weight"])
    far_boost = (w[2] > cfg["hard_neg_base_weight"] + 1e-9) & ~near[2][:, None]
    assert far_boost.sum(dim=0).tolist() == [topk, topk]
    assert far_boost[30, 1]

    loss.sum().backward()
    assert torch.all(logits.grad[1][partial_bg] == 0)

    # The legacy switch restores uniform weighting on every blank entry.
    legacy, _ = _bce(torch, hard_neg_mining=0.0)
    _, w_old = _weights(torch, legacy, logits, scores, coverage, roles, near)
    assert torch.allclose(w_old[0], cfg["real_blank_neg_weight"] * fp[0])
    assert torch.all(w_old[0] >= cfg["real_blank_neg_weight"])
    # Partial, near, wrong-class and positive entries are identical in both modes.
    assert torch.equal(w_old[1], w[1])
    for idx in [(2, 1, 0), (2, 0, 1), (2, 0, 0)]:
        assert w_old[idx].item() == pytest.approx(w[idx].item())


def test_hard_negative_per_tile_normalization():
    torch = pytest.importorskip("torch")
    from blueprint_symbols.partial_yolo import ROLE_REAL_BLANK

    topk, cap = 8, 2.0
    bce, cfg = _bce(torch, hard_neg_topk=float(topk), hard_neg_tile_cap=cap)
    W = cfg["real_blank_neg_weight"]
    base_w = cfg["hard_neg_base_weight"]

    def extra_mass(num_anchors, hot_logit):
        logits = torch.full((1, num_anchors, 3), -7.0, dtype=torch.float64)
        logits[0, : num_anchors // 2, :] = hot_logit
        scores = torch.zeros_like(logits)
        _, w = _weights(torch, bce, logits, scores, torch.ones((1, 3), dtype=torch.bool),
                        torch.tensor([ROLE_REAL_BLANK]))
        return (w - base_w).clamp(min=0).sum().item(), (w > base_w + 1e-9).sum().item(), w

    # Tile size does not change the number of boosted entries or the extra mass.
    small, n_small, _ = extra_mass(100, -7.0)
    large, n_large, _ = extra_mass(4000, -7.0)
    assert n_small == n_large == topk * 3
    assert small == pytest.approx(large)

    # A hot tile (mean modulation above the cap) is scaled down to the cap.
    hot, n_hot, w_hot = extra_mass(400, 3.0)
    assert n_hot == topk * 3
    assert hot == pytest.approx(cap * W * n_hot)
    p = torch.sigmoid(torch.tensor(3.0, dtype=torch.float64)).item()
    assert hot < W * (1 + cfg["fp_boost"] * p) * n_hot
    # A cold tile (mean modulation below the cap) is untouched by the cap.
    cold_p = torch.sigmoid(torch.tensor(-7.0, dtype=torch.float64)).item()
    assert small == pytest.approx(W * (1 + cfg["fp_boost"] * cold_p) * n_small)


# --------------------------------------------------------------------------
# B2: real-score patience and best.pt selection (torch-free)
# --------------------------------------------------------------------------

def _fake_trainer(tmp_path):
    wdir = tmp_path / "weights"
    wdir.mkdir()
    trainer = SimpleNamespace(save_dir=tmp_path, last=wdir / "last.pt", best=wdir / "best.pt",
                              epoch=0, stop=False, real_eval_metrics=None, saves=[])

    def save_model():
        trainer.last.write_text(f"epoch{trainer.epoch}")
        trainer.best.write_text(f"tilemap{trainer.epoch}")  # Ultralytics' mAP best
        trainer.saves.append(Path(trainer.best).name)
        return True

    trainer.save_model = save_model
    return trainer


def _run_epoch(trainer, callbacks, epoch, score=None):
    trainer.epoch = epoch
    trainer.save_model()
    if score is not None:
        trainer.real_eval_metrics = {"real_score": score, "epoch": epoch + 1}
    callbacks["on_fit_epoch_end"](trainer)


def test_real_patience_counts_evaluations_and_stops(tmp_path):
    trainer = _fake_trainer(tmp_path)
    cbs = build_real_selection_callbacks(patience=2)
    cbs["on_train_start"](trainer)
    wdir = tmp_path / "weights"

    _run_epoch(trainer, cbs, 0, score=0.30)
    assert (wdir / "best.pt").read_text() == "epoch0"
    _run_epoch(trainer, cbs, 1, score=0.50)
    assert (wdir / "best.pt").read_text() == "epoch1"
    # After the first real score, Ultralytics' mAP best goes to best_tile_map.pt.
    assert trainer.saves[0] == "best.pt" and trainer.saves[1] == "best_tile_map.pt"
    assert (wdir / "best_tile_map.pt").read_text() == "tilemap1"
    assert trainer.best == wdir / "best.pt"

    # Epochs without a fresh eval leave the stale dict in place: not counted.
    for epoch in (2, 3, 4):
        _run_epoch(trainer, cbs, epoch)
    assert cbs["state"]["evals"] == 2 and not trainer.stop
    _run_epoch(trainer, cbs, 5, score=0.40)
    assert not trainer.stop and cbs["state"]["since_best"] == 1
    _run_epoch(trainer, cbs, 6, score=0.50)  # tie is not an improvement
    assert trainer.stop is True
    assert (wdir / "best.pt").read_text() == "epoch1"

    # Ultralytics final_eval re-runs epoch-end callbacks: ignored.
    trainer.real_eval_metrics = {"real_score": 0.99, "epoch": 8}
    cbs["on_fit_epoch_end"](trainer)
    assert cbs["state"]["best_score"] == 0.50
    saved = json.loads((tmp_path / "real_selection.json").read_text())
    assert saved["best_epoch"] == 2 and saved["stopped_by_real_patience"] is True


def test_real_patience_zero_never_stops_and_absent_metrics_keep_ultralytics_best(tmp_path):
    trainer = _fake_trainer(tmp_path)
    cbs = build_real_selection_callbacks(patience=0)
    cbs["on_train_start"](trainer)
    _run_epoch(trainer, cbs, 0)  # no real eval yet: Ultralytics best.pt untouched
    assert (tmp_path / "weights" / "best.pt").read_text() == "tilemap0"
    _run_epoch(trainer, cbs, 1, score=0.2)
    for epoch, score in enumerate([0.1] * 20, start=2):
        _run_epoch(trainer, cbs, epoch, score=score)
    assert not trainer.stop
    assert (tmp_path / "weights" / "best.pt").read_text() == "epoch1"


# --------------------------------------------------------------------------
# B2/B4: plan metadata (torch-free)
# --------------------------------------------------------------------------

def test_plan_real_eval_disables_map_patience_and_records_init(tmp_path):
    data = tmp_path / "data.yaml"
    data.write_text("names:\n  0: receptacle.duplex\n")
    manifest = tmp_path / "source_manifest.json"
    manifest.write_text("{}")
    weights = tmp_path / "best_pretrain.pt"
    weights.write_bytes(b"not a real checkpoint")

    conf = plan(data, real_eval_every=5, real_eval_manifest=manifest, real_patience=6,
                init_weights=weights)
    assert conf["patience"] == 0
    assert conf["real_eval"]["active"] is True and conf["real_eval"]["patience_evals"] == 6
    assert conf["initialization"] == "blueprint_checkpoint_verified_transfer"
    assert conf["init_checkpoint"]["sha256"] == hashlib.sha256(weights.read_bytes()).hexdigest()
    assert conf["init_checkpoint"]["path"] == str(weights.resolve())
    notes = " ".join(conf["notes"])
    assert "Random-initialized" not in notes
    assert "locked test set is only reported" in notes
    assert "excluded from checkpoint selection" not in notes

    # No manifest: real eval inactive, Ultralytics patience stays.
    conf = plan(data, real_eval_every=5, real_eval_manifest=None)
    assert conf["patience"] == 40 and conf["real_eval"]["active"] is False
    assert conf["initialization"] == "random"


def test_main_plan_only_with_weights_and_hard_neg_flags_without_torch(tmp_path):
    data = tmp_path / "data.yaml"
    data.write_text("names:\n  0: receptacle.duplex\n")
    (tmp_path / "source_manifest.json").write_text("{}")
    weights = tmp_path / "init.pt"
    weights.write_bytes(b"abc")
    repo_root = str(Path(__file__).resolve().parents[1])
    env = dict(os.environ)
    env["PYTHONPATH"] = f"{repo_root}{os.pathsep}{env['PYTHONPATH']}" if env.get("PYTHONPATH") else repo_root
    code = (
        "import sys, scripts_train_ultralytics as train_cli; "
        "sys.argv = ['scripts_train_ultralytics.py', sys.argv[1], '--weights', sys.argv[2], "
        "'--real-eval-every', '5', '--real-eval-manifest', sys.argv[3], '--real-patience', '3', "
        "'--hard-neg-topk', '16']; "
        "train_cli.main(); "
        "assert 'torch' not in sys.modules, 'torch must not be imported in plan-only mode'"
    )
    proc = subprocess.run(
        [sys.executable, "-c", code, str(data), str(weights), str(tmp_path / "source_manifest.json")],
        env=env, capture_output=True, text=True, check=True,
    )
    out = proc.stdout
    assert hashlib.sha256(b"abc").hexdigest() in out
    assert '"patience_evals": 3' in out and '"patience": 0' in out
    assert '"hard_neg_topk": 16.0' in out and '"hard_neg_mining": 1.0' in out
    assert "PLAN ONLY" in out
