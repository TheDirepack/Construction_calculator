#!/usr/bin/env bash
# Phase 2: Bounding-box-only supervised detection training on real construction
# blueprint *DEVICES.json annotations, initialized from Phase 1 SSL v2 backbone.
set -euo pipefail

BASE="$HOME/Projects/blueprint-symbols"
CODE="$BASE/code"
DATA="$BASE/data/blueprints_and_libraries"
WORK="$BASE/work/phase2_v1"
PRETRAIN_WEIGHTS="$BASE/work/pretrain_v2/best_yolo26n_p2_backbone.pt"
PY="$BASE/.venv/bin/python"

export PYTHONPATH="$CODE"
export PYTHONUNBUFFERED=1
cd "$CODE"
mkdir -p "$WORK"

echo "=== [1/4] IMPORT REAL *DEVICES.json (BBOX-ONLY) ==="
if [[ ! -d "$WORK/normalized" ]]; then
  $PY adapters/import_standardized_devices.py "$DATA" "$WORK/normalized" --dpi 200
fi

echo "=== [2/4] PREPARE GROUP-ISOLATED YOLO DATASET (MULTI-SCALE + DEGRADATIONS) ==="
if [[ ! -f "$WORK/prepared/yolo/data.yaml" ]]; then
  rm -rf "$WORK/prepared"
  $PY scripts_prepare_training_data.py \
    --annotations "$WORK/normalized/*.json" \
    --output "$WORK/prepared" \
    --train-tiles-per-page 48 \
    --train-variants 4 \
    --val-fraction 0.17 \
    --test-fraction 0.17 \
    --eval-scales 1,2,4,8 \
    --eval-overlap 256 \
    --stress-val --stress-test \
    --allow-unverified-completeness
fi

echo "=== [3/4] VERIFY EXPORT & PREFLIGHT ==="
$PY scripts_verify_yolo_export.py "$WORK/prepared/yolo" | tail -20
$PY scripts_preflight_training.py "$WORK/prepared/source_manifest.json" | tail -20

echo "=== [4/4] TRAIN YOLO26N-P2 (WARM-STARTED FROM PHASE 1 SSL BACKBONE) ==="
exec $PY scripts_train_ultralytics.py "$WORK/prepared/yolo/data.yaml" \
  --weights "$PRETRAIN_WEIGHTS" \
  --imgsz 512 \
  --batch 12 \
  --epochs 200 \
  --optimizer AdamW \
  --workers 2 \
  --project "$WORK/runs" \
  --name phase2_bbox_v1 \
  --exist-ok \
  --run
