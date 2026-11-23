#!/usr/bin/env bash
# Fresh, blueprint-only SSL pretraining (random initialization, no generic weights).
# Samples raw/real drawings, verified device-centered crops, NECA vector symbols
# and procedural CAD-style symbols. Runtime overlay count: uniform 0..16.
set -euo pipefail

BASE="$HOME/Projects/blueprint-symbols"
CODE="$BASE/code"
DATA="$BASE/data/blueprints_and_libraries"
WORK="$BASE/work"
OUT="$WORK/pretrain_v3"
PY="$BASE/.venv/bin/python"
NECA="$DATA/2_component_libraries/neca_100_2013_svgs/NECA_100_Vector_Library"
LABELS="$WORK/ssl_real_device_labels"

export PYTHONPATH="$CODE"
export PYTHONUNBUFFERED=1
cd "$CODE"

if [[ ! -f "$NECA/symbols.json" ]]; then
  echo "Missing NECA vector symbol catalog: $NECA/symbols.json" >&2
  exit 1
fi

echo "=== INCREMENTAL BLUEPRINT SOURCE INGEST ==="
"$PY" scripts_build_page_pool.py "$DATA" "$WORK/pool" \
  --roles class_maps/page_roles.json --workers 5
"$PY" scripts_curate_pool.py "$WORK/pool"

echo "=== BUILD DEVICE-POSITIVE CROP REFERENCES (NOT EXHAUSTIVE LABELS) ==="
if [[ ! -f "$LABELS/.complete" ]]; then
  "$PY" adapters/import_standardized_devices.py "$DATA" "$LABELS" --dpi 200
  touch "$LABELS/.complete"
fi

echo "=== FRESH BLUEPRINT-ONLY SSL TRAINING ==="
mkdir -p "$OUT"
exec "$PY" scripts_pretrain_ssl.py \
  --data-root "$DATA" \
  --work-root "$WORK" \
  --labeled-annotations "$LABELS/*.json" \
  --neca-dir "$NECA" \
  --out "$OUT" \
  --imgsz 512 \
  --batch 4 \
  --epochs 60 \
  --items-per-epoch 8192 \
  --val-items 512 \
  --optimizer MuSGD \
  --lr 5e-3 \
  --max-symbols 16 \
  --symbol-min-px 12 \
  --symbol-max-px 80 \
  --symbol-augment-probability 1.00 \
  --procedural-fraction 0.25 \
  --clean-fraction 0.20 \
  --labeled-focus-probability 0.65 \
  --workers 2 \
  --run
