#!/usr/bin/env bash
# Phase 2 v2: Clean real *DEVICES.json (text proxies filtered) + wall-touching &
# free-space composited electrical symbols onto real architectural sheets,
# trained with YOLO26n-P2 MuSGD using our own freshly trained SSL v3 backbone.
set -euo pipefail

BASE="$HOME/Projects/blueprint-symbols"
CODE="$BASE/code"
DATA="$BASE/data/blueprints_and_libraries"
POOL="$BASE/work/pool"
WORK="$BASE/work/phase2_v3"
PRETRAIN_WEIGHTS="${PRETRAIN_WEIGHTS:-$BASE/work/pretrain_v3/best_yolo26n_p2_backbone.pt}"
PY="$BASE/.venv/bin/python"

export PYTHONPATH="$CODE"
export PYTHONUNBUFFERED=1
cd "$CODE"
mkdir -p "$WORK"

echo "=== [1/5] IMPORT CLEAN REAL *DEVICES.json (TEXT PROXIES REMOVED) ==="
if [[ ! -d "$WORK/normalized_real" ]]; then
  $PY adapters/import_standardized_devices.py "$DATA" "$WORK/normalized_real" --dpi 200
fi

echo "=== [2/5] COMPOSITE NECA 100-2013 VECTOR SVG DEVICES (ZERO WALL CLIPPING, 350 ARCH SHEETS) ==="
if [[ ! -d "$WORK/composited_neca" ]]; then
  $PY scripts_composite_neca_svg.py \
    --neca-dir "$DATA/2_component_libraries/neca_100_2013_svgs/NECA_100_Vector_Library" \
    --backgrounds \
      "$POOL/background/*new_downloads_202611*.json" \
      "$POOL/background/*rinker_hall*.json" \
      "$POOL/background/*njatc*.json" \
      "$POOL/background/*freefarmhouse*.json" \
    --output "$WORK/composited_neca" \
    --copies 1 \
    --per-page 24 \
    --wall-fraction 0.60 \
    --min-px 20 \
    --max-px 64 \
    --limit 350 \
    --preview 5 \
    --seed 2026
fi

echo "=== [3/5] PREPARE BUILDING-ISOLATED YOLO DATASET (REAL + NECA COMPOSITED) ==="
# *DEVICES.json is POSITIVE-ONLY, not exhaustively annotated. A supervised
# detector can learn missed symbols as negatives. Fail closed by default;
# allow incomplete annotations for an explicitly exploratory run only.
COMPLETENESS_ARGS=()
if [[ "${PHASE2_ALLOW_UNVERIFIED:-0}" == "1" ]]; then
  echo "WARNING: EXPLORATORY ONLY: missing real device labels may become false negatives" >&2
  COMPLETENESS_ARGS=(--allow-unverified-completeness)
fi
if [[ ! -f "$WORK/prepared/yolo/data.yaml" ]]; then
  rm -rf "$WORK/prepared"
  $PY scripts_prepare_training_data.py \
    --annotations "$WORK/normalized_real/*.json" "$WORK/composited_neca/*.json" \
    --output "$WORK/prepared" \
    --train-tiles-per-page 18 \
    --train-variants 3 \
    --val-fraction 0.14 \
    --test-fraction 0.14 \
    --eval-scales 1,2,4,8,16 \
    --eval-overlap 256 \
    "${COMPLETENESS_ARGS[@]}"
fi

echo "=== [4/5] VERIFY EXPORT & PREFLIGHT ==="
if [[ ! -f "$WORK/prepared/verified.ok" ]]; then
  $PY scripts_verify_yolo_export.py "$WORK/prepared/yolo" | tail -20
  $PY scripts_preflight_training.py "$WORK/prepared/source_manifest.json" | tail -20
  touch "$WORK/prepared/verified.ok"
fi

echo "=== [5/5] VERIFY SSL BACKBONE TRANSFER, THEN TRAIN YOLO26N-P2 ==="
if [[ ! -f "$PRETRAIN_WEIGHTS" ]]; then
  echo "Missing fresh SSL checkpoint: $PRETRAIN_WEIGHTS" >&2
  exit 1
fi
$PY scripts_verify_ssl_transfer.py "$PRETRAIN_WEIGHTS" --classes 6
exec $PY scripts_train_ultralytics.py "$WORK/prepared/yolo/data.yaml" \
  --weights "$PRETRAIN_WEIGHTS" \
  --imgsz 512 \
  --batch 12 \
  --epochs 200 \
  --optimizer MuSGD \
  --lr0 0.005 \
  --fliplr 0.5 \
  --flipud 0.5 \
  --scale 0.2 \
  --translate 0.05 \
  --workers 2 \
  --project "$WORK/runs" \
  --name phase2_mixed_ssl_v3 \
  --exist-ok \
  --run
