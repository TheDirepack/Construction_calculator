#!/usr/bin/env bash
# Phase 2: transfer blueprint SSL v3 backbone+neck, then train YOLO26n-P2
# with class-masked partial real labels, exact synthetic positive boxes and
# independently verified real-page validation/test. No unsafe label override.
set -euo pipefail

BASE="${BLUEPRINT_BASE:-$HOME/Projects/blueprint-symbols}"
CODE="$BASE/code"
DATA="$BASE/data/blueprints_and_libraries"
POOL="$BASE/work/pool"
# New directory: do NOT accidentally reuse phase2_v3 exports from unsafe scripts.
WORK="${PHASE2_WORK:-$BASE/work/phase2_v4}"
PRETRAIN_WEIGHTS="${PRETRAIN_WEIGHTS:-$BASE/work/pretrain_v3/best_yolo26n_p2_backbone.pt}"
PY="$BASE/.venv/bin/python"
# Review these independently of pretraining, preferably from the held-out PDF set.
VERIFIED_GLOB="${PHASE2_VERIFIED_ANNOTATIONS_GLOB:-$WORK/verified_real/*.json}"
PHASE2_BATCH="${PHASE2_BATCH:-12}"
PHASE2_EPOCHS="${PHASE2_EPOCHS:-200}"

export PYTHONPATH="$CODE"
export PYTHONUNBUFFERED=1
cd "$CODE"
mkdir -p "$WORK"

if [[ "${PHASE2_ALLOW_UNVERIFIED:-0}" != "0" ]]; then
  echo 'ERROR: PHASE2_ALLOW_UNVERIFIED is forbidden. Use class-masked --allow-partial-training.' >&2
  exit 2
fi
if [[ ! -s "$PRETRAIN_WEIGHTS" ]]; then
  echo "Missing trained SSL backbone+neck checkpoint: $PRETRAIN_WEIGHTS" >&2
  exit 2
fi
echo "=== [1/6] VERIFY ACTUAL SSL BACKBONE TRANSFER BEFORE EXPENSIVE DATA PREP ==="
"$PY" scripts_verify_ssl_transfer.py "$PRETRAIN_WEIGHTS" --classes 6

echo "=== [2/6] REQUIRE COMPLETE, HUMAN-REVIEWED REAL VALIDATION/TEST SOURCES ==="
if ! compgen -G "$VERIFIED_GLOB" >/dev/null; then
  echo "No reviewed evaluation annotation JSONs match: $VERIFIED_GLOB" >&2
  echo "Place at least 2 distinct, fully reviewed, labeled project groups in verified_real/." >&2
  echo "Unreviewed pages and pages with only provisional *DEVICES.json boxes cannot validate detection." >&2
  exit 2
fi
"$PY" scripts_phase2_readiness.py --annotations "$VERIFIED_GLOB"

echo "=== [3/6] IMPORT REAL DEVICE LABELS (PROVISIONAL BOXES REMAIN UNTRUSTED) ==="
if [[ ! -d "$WORK/normalized_real" ]]; then
  "$PY" adapters/import_standardized_devices.py "$DATA" "$WORK/normalized_real" --dpi 200
fi
if ! compgen -G "$WORK/normalized_real/*.json" >/dev/null; then
  echo "No normalized real-device annotations in $WORK/normalized_real" >&2
  exit 2
fi

echo "=== [4/6] COMPOSITE NECA SYMBOLS ON REAL DRAWINGS (PARTIAL LABELS STAY PARTIAL) ==="
if [[ ! -d "$WORK/composited_neca" ]]; then
  "$PY" scripts_composite_neca_svg.py \
    --neca-dir "$DATA/2_component_libraries/neca_100_2013_svgs/NECA_100_Vector_Library" \
    --backgrounds \
      "$POOL/background/*new_downloads_202611*.json" \
      "$POOL/background/*rinker_hall*.json" \
      "$POOL/background/*njatc*.json" \
      "$POOL/background/*freefarmhouse*.json" \
    --output "$WORK/composited_neca" \
    --copies 1 --per-page 24 --wall-fraction 0.60 \
    --min-px 20 --max-px 64 --limit 350 --preview 5 --seed 2026
fi
if ! compgen -G "$WORK/composited_neca/*.json" >/dev/null; then
  echo "No composited NECA symbol annotations in $WORK/composited_neca" >&2
  exit 2
fi

ANNOTATIONS=("$WORK/normalized_real/*.json" "$WORK/composited_neca/*.json" "$VERIFIED_GLOB")
# Optional reviewed zero-symbol sources: script verifies every target family,
# retaining truly empty negatives AND synthetic-inserted positive variants.
if [[ -n "${PHASE2_WALL_MANIFEST:-}" ]]; then
  if [[ ! -f "$PHASE2_WALL_MANIFEST" ]]; then
    echo "Verified wall manifest not found: $PHASE2_WALL_MANIFEST" >&2
    exit 2
  fi
  if [[ ! -d "$WORK/composited_verified_walls" ]]; then
    "$PY" scripts_generate_phase2_wall_only.py \
      "$PHASE2_WALL_MANIFEST" "$WORK/composited_verified_walls" \
      --neca-root "$DATA/2_component_libraries/neca_100_2013_svgs/NECA_100_Vector_Library" \
      --copies 8 --empty-fraction 0.30 --max-symbols 32
  fi
  ANNOTATIONS+=("$WORK/composited_verified_walls/*.json")
fi

echo "=== [5/6] PREPARE CLASS-MASKED, PROJECT-ISOLATED YOLO DATASET ==="
if [[ ! -f "$WORK/prepared/yolo/data.yaml" ]]; then
  if [[ -d "$WORK/prepared" ]] && [[ -n "$(ls -A "$WORK/prepared")" ]]; then
    echo "Incomplete previous export exists at $WORK/prepared; inspect/move it manually, never delete it implicitly." >&2
    exit 2
  fi
  "$PY" scripts_prepare_training_data.py \
    --annotations "${ANNOTATIONS[@]}" \
    --output "$WORK/prepared" \
    --allow-partial-training \
    --train-tiles-per-page 18 --train-variants 3 \
    --val-fraction 0.14 --test-fraction 0.14 \
    --eval-scales 1,2,4,8,16 --eval-overlap 256
fi

echo "=== [6/6] VERIFY EXPORT, CHECK LABEL MASKS, TRAIN YOLO26n-P2 / MuSGD ==="
# The group splitter must actually assign a reviewed REAL-positive project to
# each held-out split; merely having enough candidates is not sufficient.
"$PY" scripts_phase2_readiness.py --prepared-manifest "$WORK/prepared/source_manifest.json"
"$PY" scripts_verify_yolo_export.py "$WORK/prepared/yolo"
"$PY" scripts_preflight_training.py "$WORK/prepared/source_manifest.json"
# All exports must include a sidecar; otherwise ordinary YOLO would generate
# negative gradients on missing real labels.
"$PY" - "$WORK/prepared/yolo/data.yaml" <<'PY'
import json, sys
from pathlib import Path
from blueprint_symbols.partial_supervision import read_supervision
source = Path(sys.argv[1])
mask = read_supervision(source)
if mask is None or not mask["has_partial"]:
    raise SystemExit("Missing/incorrect class-masked partial-supervision sidecar")
print(json.dumps({"partial_training": mask["has_partial"], "class_ids": mask["class_ids"]}, indent=2))
PY

TRAIN_ARGS=(
  "$WORK/prepared/yolo/data.yaml"
  --weights "$PRETRAIN_WEIGHTS"
  --imgsz 512 --batch "$PHASE2_BATCH" --epochs "$PHASE2_EPOCHS"
  --optimizer MuSGD --lr0 0.005
  --fliplr 0.5 --flipud 0.5 --scale 0.2 --translate 0.05
  --workers 2 --project "$WORK/runs"
  --name "${PHASE2_RUN_NAME:-phase2_mixed_ssl_v4}"
)
if [[ "${PHASE2_DRY_RUN:-0}" == "1" ]]; then
  "$PY" scripts_train_ultralytics.py "${TRAIN_ARGS[@]}"
  echo "Preflight passed; PHASE2_DRY_RUN=1 did not train."
  exit 0
fi
exec "$PY" scripts_train_ultralytics.py "${TRAIN_ARGS[@]}" --run
