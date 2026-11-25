#!/usr/bin/env bash
# Flag a finished SSL pretraining run's best backbone as THE pretrained model
# that post-training (run_phase2_train.sh) starts from by default.
#
#   remote/promote_pretrain.sh ~/Projects/blueprint-symbols/work/pretrain_v5
#
# Copies <run>/best_yolo26n_p2_backbone.pt to work/best_pretrain/backbone.pt
# with info.json (run, epoch, validation loss, fingerprint, sha256). The model
# flagged before is kept under work/best_pretrain/previous/<time>/ for rollback.
set -euo pipefail

RUN="$(cd "${1:?usage: promote_pretrain.sh <pretrain run dir>}" && pwd)"
BASE="${BASE:-$HOME/Projects/blueprint-symbols}"
FLAG="$BASE/work/best_pretrain"
SRC="$RUN/best_yolo26n_p2_backbone.pt"
PY="${PY:-$BASE/.venv/bin/python}"

if [[ ! -f "$SRC" ]]; then
  echo "no best backbone in $RUN" >&2
  exit 1
fi
mkdir -p "$FLAG"
if [[ -f "$FLAG/backbone.pt" ]]; then
  keep="$FLAG/previous/$(date +%Y%m%d_%H%M%S)"
  mkdir -p "$keep"
  mv "$FLAG/backbone.pt" "$FLAG/info.json" "$keep/" 2>/dev/null || true
  echo "previous flagged backbone kept in $keep"
fi
cp "$SRC" "$FLAG/backbone.pt.tmp"
mv "$FLAG/backbone.pt.tmp" "$FLAG/backbone.pt"
"$PY" - "$RUN" "$FLAG" <<'PY'
import hashlib
import json
import sys
import time
from pathlib import Path

run, flag = Path(sys.argv[1]), Path(sys.argv[2])
rows = [json.loads(l) for l in (run / "metrics.jsonl").read_text().splitlines() if l.strip()] \
    if (run / "metrics.jsonl").exists() else []
best = [r for r in rows if r.get("is_best")]
best = best[-1] if best else {}
fp = run / "run_fingerprint.txt"
info = {
    "flagged_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
    "run": str(run),
    "source_checkpoint": str(run / "best_yolo26n_p2_backbone.pt"),
    "best_epoch": best.get("epoch"),
    "val_loss": best.get("val_loss"),
    "epochs_logged": len(rows),
    "run_fingerprint": fp.read_text().strip() if fp.exists() else None,
    "sha256": hashlib.sha256((flag / "backbone.pt").read_bytes()).hexdigest(),
    "note": "Post-training (run_phase2_train.sh) starts from this backbone unless PRETRAIN_WEIGHTS is set.",
}
(flag / "info.json").write_text(json.dumps(info, indent=2) + "\n")
print(json.dumps(info, indent=2))
PY
