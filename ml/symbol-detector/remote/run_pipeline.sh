#!/usr/bin/env bash
# Run the symbol-detector pipeline on the training machine, stage by stage.
# Each stage skips itself when its output already exists, so re-running after
# a crash or after new data lands only does the missing work. Delete a stage's
# output directory to redo it. Run it as a systemd user unit so a dropped SSH
# session cannot kill it:
#
#   systemd-run --user --unit=bps-train --collect -E RUN=v1 \
#       -p WorkingDirectory=$HOME/Projects/blueprint-symbols/code remote/run_pipeline.sh train
#
#   remote/run_pipeline.sh            # pool -> layouts -> import -> composite -> prepare -> verify
#   remote/run_pipeline.sh smoke      # ...then a 2-epoch GPU run to prove the loop works
#   remote/run_pipeline.sh train      # ...then the full run (300 epochs max, early stop)
#
# Settings (environment variables):
#   RUN=name            dataset/run name under work/                  (default: v1)
#   POOL_COPIES=3       composites per real background page (scans, photos, CAD renders)
#   LAYOUT_COPIES=1     composites per rendered vector layout
#   FPCAD_COPIES=2      composites per FloorPlanCAD page
#   LAYOUT_LIMIT=1500   rendered layouts per dataset (ResPlan, CubiCasa, HouseExpo)
#   MAX_PER_GROUP=25    background pages kept per source group (others -> pool/reserve)
#   TILES=16            training tiles per source page
#   VARIANTS=3          clean / low-res / damaged variants per tile
#   EVAL_SCALES=1,2     val/test tiling scales (2 covers 1024 source px per tile)
#   BATCH=16 EPOCHS=300 (batch 8 measured at 2.0 GB VRAM)
set -euo pipefail

MODE="${1:-prepare}"
BASE="$HOME/Projects/blueprint-symbols"
CODE="$BASE/code"
DATA="$BASE/data/blueprints_and_libraries"
RUN="${RUN:-v1}"
WORK="$BASE/work/$RUN"
POOL="$BASE/work/pool"            # shared by all runs; incremental
PY="$BASE/.venv/bin/python"       # runs from $CODE; data dirs are only ever passed as arguments
export PYTHONPATH="$CODE"
cd "$CODE"
mkdir -p "$WORK"
log() { printf '\n== %s ==\n' "$*"; }

# 1. Page pool: every PDF / scan / photo / DXF, with a role per page ---------
log "page pool (incremental)"
$PY scripts_build_page_pool.py "$DATA" "$POOL" --roles class_maps/page_roles.json --workers 5 | tail -15
if [[ ! -d "$POOL/background_layouts" ]]; then
  log "render vector layouts"
  $PY adapters/render_layouts.py "$DATA/5_datasets" "$POOL/background_layouts" --limit "${LAYOUT_LIMIT:-1500}"
fi

log "curate pool (scores, photo pages -> negative, per-group cap)"
$PY scripts_curate_pool.py "$POOL" --max-per-group "${MAX_PER_GROUP:-25}"

# 2. Labeled FloorPlanCAD -> normalized pages ----------------------------------
FPCAD="$DATA/5_datasets/floorplancad_sample"
NORM="$BASE/work/normalized/floorplancad"
if [[ -f "$FPCAD/samples.json" && ! -d "$NORM" ]]; then
  log "import FloorPlanCAD"
  $PY adapters/import_floorplancad_fiftyone.py "$FPCAD" "$NORM" --class-map class_maps/floorplancad.json
fi

# 3. Composite electrical symbols onto every background ------------------------
composite() {  # name, glob, copies, [extra args]
  local out="$WORK/composited/$1"
  if [[ ! -d "$out" ]] && compgen -G "$2" >/dev/null; then
    log "composite electrical symbols: $1"
    $PY scripts_composite_electrical.py "$2" "$out" --copies "$3" --seed 7 "${@:4}"
  fi
}
composite pool     "$POOL/background/*.json"         "${POOL_COPIES:-3}"
composite layouts  "$POOL/background_layouts/*.json" "${LAYOUT_COPIES:-1}"
[[ -d "$NORM" ]] && composite floorplancad "$NORM/*.json" "${FPCAD_COPIES:-2}"
composite negative "$POOL/negative/*.json" 1 --negative     # photos: verified symbol-free pages

# 4. Group split + tiles + degradations -> YOLO dataset ------------------------
PREP="$WORK/prepared"
if [[ ! -f "$PREP/yolo/data.yaml" ]]; then
  log "prepare training data"
  rm -rf "$PREP"
  $PY scripts_prepare_training_data.py --annotations "$WORK/composited/*/*.json" --output "$PREP" \
      --train-tiles-per-page "${TILES:-16}" --train-variants "${VARIANTS:-3}" \
      --eval-scales "${EVAL_SCALES:-1,2}" --eval-overlap "${EVAL_OVERLAP:-192}" \
      --stress-val --stress-test | tail -40
fi

# 5. Independent checks of the export --------------------------------------------
log "verify export"
$PY scripts_verify_yolo_export.py "$PREP/yolo" | tail -20
$PY scripts_preflight_training.py "$PREP/source_manifest.json" | tail -20

# 6. Train -----------------------------------------------------------------------
case "$MODE" in
  prepare) echo; echo "Prepared: $PREP/yolo/data.yaml (run with 'smoke' or 'train' to train)";;
  smoke)
    log "smoke training (2 epochs)"
    $PY scripts_train_ultralytics.py "$PREP/yolo/data.yaml" --run --epochs 2 \
        --batch "${BATCH:-16}" --project "$WORK/runs" --name smoke --exist-ok;;
  train)
    log "training"
    $PY scripts_train_ultralytics.py "$PREP/yolo/data.yaml" --run --epochs "${EPOCHS:-300}" \
        --batch "${BATCH:-16}" --project "$WORK/runs" --name train;;
  *) echo "unknown mode: $MODE" >&2; exit 2;;
esac
