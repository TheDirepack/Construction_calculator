#!/usr/bin/env bash
# Run the symbol-detector pipeline on the training machine, stage by stage.
# Each stage skips itself when its output already exists, so re-running after
# a crash or after new data lands only does the missing work. Delete a stage's
# output directory to redo it.
#
#   remote/run_pipeline.sh                 # import -> composite -> prepare -> verify (no training)
#   remote/run_pipeline.sh smoke           # ...then a 2-epoch GPU training run to prove the loop works
#   remote/run_pipeline.sh train           # ...then the full training run (300 epochs max, early stop)
#
# Settings (environment variables):
#   RUN=name          dataset/run name under work/            (default: v1)
#   PER_PAGE=18       electrical symbols composited per plan
#   COPIES=2          independent composites per plan (same split group)
#   TILES=32          training tiles per source page
#   VARIANTS=3        clean / low-res / damaged variants per tile
#   BATCH=8 EPOCHS=300
set -euo pipefail

MODE="${1:-prepare}"
BASE="$HOME/Projects/blueprint-symbols"
CODE="$BASE/code"
DATA="$BASE/data/blueprints_and_libraries"
RUN="${RUN:-v1}"
WORK="$BASE/work/$RUN"
PY="$BASE/.venv/bin/python"   # runs from $CODE; data dirs are only ever passed as arguments
export PYTHONPATH="$CODE"
cd "$CODE"
mkdir -p "$WORK"
log() { printf '\n== %s ==\n' "$*"; }

# 1. Import labeled sources into the normalized page format -----------------
FPCAD="$DATA/5_datasets/floorplancad_sample"
NORM="$WORK/normalized/floorplancad"
if [[ -f "$FPCAD/samples.json" && ! -d "$NORM" ]]; then
  log "import FloorPlanCAD"
  $PY adapters/import_floorplancad_fiftyone.py "$FPCAD" "$NORM" --class-map class_maps/floorplancad.json
fi
[[ -d "$NORM" ]] || { echo "no FloorPlanCAD data at $FPCAD yet" >&2; exit 1; }

# 2. Composite electrical symbols onto the architectural plans ---------------
COMP="$WORK/composited/floorplancad"
if [[ ! -d "$COMP" ]]; then
  log "composite electrical symbols"
  $PY scripts_composite_electrical.py "$NORM/*.json" "$COMP" \
      --per-page "${PER_PAGE:-18}" --copies "${COPIES:-2}" --seed 7
fi

# 3. Group split + tiles + degradations -> YOLO dataset ----------------------
PREP="$WORK/prepared"
if [[ ! -f "$PREP/yolo/data.yaml" ]]; then
  log "prepare training data"
  rm -rf "$PREP"
  $PY scripts_prepare_training_data.py --annotations "$COMP/*.json" --output "$PREP" \
      --train-tiles-per-page "${TILES:-32}" --train-variants "${VARIANTS:-3}" \
      --stress-val --stress-test
fi

# 4. Independent checks of the export ----------------------------------------
log "verify export"
$PY scripts_verify_yolo_export.py "$PREP/yolo" | tail -20
$PY scripts_preflight_training.py "$PREP/source_manifest.json" | tail -20

# 5. Train ----------------------------------------------------------------------
case "$MODE" in
  prepare) echo; echo "Prepared: $PREP/yolo/data.yaml (run with 'smoke' or 'train' to train)";;
  smoke)
    log "smoke training (2 epochs)"
    $PY scripts_train_ultralytics.py "$PREP/yolo/data.yaml" --run --epochs 2 \
        --batch "${BATCH:-8}" --project "$WORK/runs" --name smoke --exist-ok;;
  train)
    log "training"
    $PY scripts_train_ultralytics.py "$PREP/yolo/data.yaml" --run --epochs "${EPOCHS:-300}" \
        --batch "${BATCH:-8}" --project "$WORK/runs" --name train;;
  *) echo "unknown mode: $MODE" >&2; exit 2;;
esac
