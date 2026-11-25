#!/usr/bin/env bash
# Fresh, blueprint-only SSL pretraining (random initialization, no generic weights).
# Samples raw/real drawings, verified device-centered crops, and overlays NECA
# vector symbols, symbols cut from legend sheets and CAD block libraries,
# crops of verified labels, and procedural CAD-style symbols. Runtime overlay count: uniform 0..16.
set -euo pipefail

BASE="$HOME/Projects/blueprint-symbols"
CODE="$BASE/code"
DATA="$BASE/data/blueprints_and_libraries"
WORK="$BASE/work"
OUT="${SSL_OUT:-$WORK/pretrain_v5}"
# Effective optimizer batch. By default the whole batch is held on the GPU
# (no gradient accumulation): a physical batch of 14 at 512px measured
# ~4.6GB allocated / 4.9GB reserved on the 6GB RTX 2060 (2026-11-24).
# Set SSL_MICROBATCH lower to trade speed for memory.
SSL_BATCH="${SSL_BATCH:-14}"
SSL_MICROBATCH="${SSL_MICROBATCH:-$SSL_BATCH}"
SSL_EPOCHS="${SSL_EPOCHS:-60}"
SSL_ITEMS_PER_EPOCH="${SSL_ITEMS_PER_EPOCH:-8190}"
SSL_VAL_ITEMS="${SSL_VAL_ITEMS:-512}"
SSL_WORKERS="${SSL_WORKERS:-2}"
SSL_MAX_MP="${SSL_MAX_MP:-8}"
RESUME_ARGS=()
if [[ "${SSL_RESUME:-0}" == "1" ]]; then
  RESUME_ARGS=(--resume)
fi
PY="$BASE/.venv/bin/python"
NECA="$DATA/2_component_libraries/neca_100_2013_svgs/NECA_100_Vector_Library"
LEGEND_CROPS="$DATA/2_component_libraries/legend_symbol_crops"
HARVESTED="$DATA/2_component_libraries/harvested_symbols"
LABEL_STORE="${BPS_LABELS_DIR:-$WORK/labels}"
LABELS="$WORK/ssl_real_device_labels"

export PYTHONPATH="$CODE"
export PYTHONUNBUFFERED=1
cd "$CODE"

if [[ ! -f "$NECA/symbols.json" ]]; then
  echo "Missing NECA vector symbol catalog: $NECA/symbols.json" >&2
  exit 1
fi

# Training drawings = the PDFs directly in $DATA/1_blueprints, evaluation
# drawings = $DATA/1_blueprints/evaluation. The pretraining script lists them
# from the folders when it starts (no index, no held-out manifest); evaluation
# drawings never enter pretraining.
TRAIN_PDFS=$(find "$DATA/1_blueprints" -maxdepth 1 -type f -iname '*.pdf' | wc -l)
EVAL_PDFS=$(find "$DATA/1_blueprints/evaluation" -maxdepth 1 -type f -iname '*.pdf' 2>/dev/null | wc -l)
echo "training PDFs: $TRAIN_PDFS   evaluation PDFs (never used here): $EVAL_PDFS"

echo "=== INCREMENTAL NON-PDF BLUEPRINT SOURCE INGEST ==="
"$PY" scripts_build_page_pool.py "$DATA" "$WORK/pool" \
  --roles class_maps/page_roles.json --workers 5 --skip-pdfs --max-mp "$SSL_MAX_MP"
"$PY" scripts_curate_pool.py "$WORK/pool"

echo "=== TRAIN-SPLIT CAD/DRAWING LAYOUT BACKGROUNDS ==="
LAYOUT_DIR="$WORK/pool/background_layouts"
if [[ -d "$DATA/5_datasets" ]]; then
  # Renderer skips existing pages by ID. Rerunning also completes a partially
  # rendered dataset after a crash (existence of one JSON is not completion).
  mkdir -p "$LAYOUT_DIR"
  "$PY" adapters/render_layouts.py "$DATA/5_datasets" "$LAYOUT_DIR" \
    --train-only --limit 1500 --seed 11
fi

# Reuse only known FloorPlanCAD training rasters. Do not infer a train/test
# split from an unverified arbitrary FloorPlanCAD folder.
FPCAD_ARGS=()
FPCAD_TRAIN_DIR="${FPCAD_TRAIN_DIR:-$WORK/pretrain_v1/floorplancad_train_pngs}"
if [[ -d "$FPCAD_TRAIN_DIR" ]]; then
  FPCAD_ARGS=(--fpcad-train-dir "$FPCAD_TRAIN_DIR")
else
  echo "No verified FloorPlanCAD train directory; skipping optional FloorPlanCAD bucket"
fi

echo "=== BUILD DEVICE-POSITIVE CROP REFERENCES (NOT EXHAUSTIVE LABELS) ==="
# Refresh on every new run. The previous .complete marker could silently
# freeze the positive-crop pool even after more devices had been annotated.
# Existing import output is regenerated; source PDFs/DEVICES.json are unchanged.
# Rebuilt from scratch: a PDF moved to evaluation/ must not leave crops behind.
rm -rf "$LABELS"
"$PY" adapters/import_standardized_devices.py "$DATA" "$LABELS" --dpi 200

echo "=== SYMBOL DRAWINGS FROM CAD BLOCK LIBRARIES (INCREMENTAL) ==="
# Every block / single-symbol file / symbol sheet in 2_component_libraries;
# sources harvested before are skipped, so this is quick after the first run.
"$PY" scripts_harvest_symbols.py --sources "$DATA/2_component_libraries" --out "$HARVESTED" \
  harvest --workers "${SSL_HARVEST_WORKERS:-8}"

LIBRARY_ARGS=()
for lib in "$LEGEND_CROPS" "$HARVESTED"; do
  if [[ -f "$lib/crops.json" ]]; then LIBRARY_ARGS+=(--symbol-library "$lib"); fi
done
if [[ -d "$LABEL_STORE" ]]; then LIBRARY_ARGS+=(--label-store "$LABEL_STORE"); fi

echo "=== FRESH BLUEPRINT-ONLY SSL TRAINING ==="
mkdir -p "$OUT"
exec "$PY" scripts_pretrain_ssl.py \
  --data-root "$DATA" \
  --work-root "$WORK" \
  "${FPCAD_ARGS[@]}" \
  --labeled-annotations "$LABELS/*.json" \
  --neca-dir "$NECA" \
  "${LIBRARY_ARGS[@]}" \
  --library-symbol-share "${SSL_LIBRARY_SYMBOL_SHARE:-0.5}" \
  --out "$OUT" \
  --imgsz 512 \
  --batch "$SSL_BATCH" \
  --microbatch "$SSL_MICROBATCH" \
  --epochs "$SSL_EPOCHS" \
  --items-per-epoch "$SSL_ITEMS_PER_EPOCH" \
  --crop-reuse-views "${SSL_CROP_REUSE_VIEWS:-4}" \
  --crop-bank-size "${SSL_CROP_BANK_SIZE:-32}" \
  --fresh-crop-probability "${SSL_FRESH_CROP_PROBABILITY:-0.20}" \
  --val-items "$SSL_VAL_ITEMS" \
  --optimizer MuSGD \
  --lr 5e-3 \
  --max-symbols 16 \
  --symbol-min-px 12 \
  --symbol-max-px 80 \
  --symbol-augment-probability 1.00 \
  --procedural-fraction 0.25 \
  --symbol-chaos-probability "${SSL_SYMBOL_CHAOS_PROBABILITY:-0.88}" \
  --symbol-chaos-strength "${SSL_SYMBOL_CHAOS_STRENGTH:-1.0}" \
  --placement-chaos "${SSL_PLACEMENT_CHAOS:-0.85}" \
  --page-chaos-probability "${SSL_PAGE_CHAOS_PROBABILITY:-0.70}" \
  --page-chaos-strength "${SSL_PAGE_CHAOS_STRENGTH:-1.0}" \
  --clean-fraction 0.20 \
  --labeled-focus-probability 0.65 \
  --workers "$SSL_WORKERS" \
  "${RESUME_ARGS[@]}" \
  --run
