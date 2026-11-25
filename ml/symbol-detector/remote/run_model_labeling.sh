#!/usr/bin/env bash
# Pre-label the label store (pages rendered from the PDFs) with a fine-tuned
# detector; boxes are written as unreviewed proposals.
#
#   remote/run_model_labeling.sh WEIGHTS [extra scripts_model_prelabel.py args]
#
# Run as a systemd user unit so it survives disconnects, e.g.:
#   systemd-run --user --unit=bps-model-labeling /usr/bin/bash \
#     ~/Projects/blueprint-symbols/code/remote/run_model_labeling.sh \
#     ~/Projects/blueprint-symbols/work/phase2_v3/runs/phase2_mixed_ssl_v3/weights/best.pt
#
# Refuses to start while less than MIN_FREE_GPU_MB of VRAM is free (another
# training job is running); set DEVICE=cpu to run on the CPU instead.
set -euo pipefail

BASE="$HOME/Projects/blueprint-symbols"
WEIGHTS="${1:?usage: run_model_labeling.sh WEIGHTS [args...]}"; shift
DEVICE="${DEVICE:-0}"
MIN_FREE_GPU_MB="${MIN_FREE_GPU_MB:-1500}"

if [[ "$DEVICE" != "cpu" ]]; then
  free_mb="$(nvidia-smi --query-gpu=memory.free --format=csv,noheader,nounits | head -n1 | tr -d ' ')"
  if (( free_mb < MIN_FREE_GPU_MB )); then
    echo "Only ${free_mb} MB of GPU memory free (need ${MIN_FREE_GPU_MB}); is training still running?" >&2
    echo "Wait for it, or set DEVICE=cpu." >&2
    exit 3
  fi
fi

export PYTHONPATH="$BASE/code" PYTHONUNBUFFERED=1 PYTHONWARNINGS=ignore
cd "$BASE"
exec .venv/bin/python code/scripts_model_prelabel.py \
  --weights "$WEIGHTS" \
  --store work/labels \
  --out work/model_proposals \
  --device "$DEVICE" \
  "$@"
