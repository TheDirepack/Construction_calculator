#!/usr/bin/env bash
# Fresh, blueprint-only SSL pretraining (random initialization, no generic weights).
# Samples raw/real drawings, verified device-centered crops, NECA vector symbols
# and procedural CAD-style symbols. Runtime overlay count: uniform 0..16.
set -euo pipefail

BASE="$HOME/Projects/blueprint-symbols"
CODE="$BASE/code"
DATA="$BASE/data/blueprints_and_libraries"
WORK="$BASE/work"
OUT="${SSL_OUT:-$WORK/pretrain_v3}"
# Effective optimizer batch, not necessarily the number held on the GPU.
# The 512px SSL decoder reconstructs full-resolution RGB and can exhaust
# 6GB VRAM with a physical batch of 14. Default to 7 x 2 accumulation.
SSL_BATCH="${SSL_BATCH:-14}"
SSL_MICROBATCH="${SSL_MICROBATCH:-$(( SSL_BATCH < 2 ? SSL_BATCH : 2 ))}"
SSL_EPOCHS="${SSL_EPOCHS:-60}"
SSL_ITEMS_PER_EPOCH="${SSL_ITEMS_PER_EPOCH:-8190}"
SSL_VAL_ITEMS="${SSL_VAL_ITEMS:-512}"
SSL_WORKERS="${SSL_WORKERS:-2}"
RESUME_ARGS=()
if [[ "${SSL_RESUME:-0}" == "1" ]]; then
  RESUME_ARGS=(--resume)
fi
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

echo "=== INDEX ALL REAL PDF PAGES WITHOUT FULL-PAGE RASTERIZATION ==="
RESERVED_ARGS=()
if [[ -f "$WORK/heldout_real_pdfs.json" ]]; then
  RESERVED_ARGS=(--reserved-manifest "$WORK/heldout_real_pdfs.json")
elif [[ "${SSL_RESERVED_PDFS_OUTSIDE_DATA:-0}" == "1" ]]; then
  echo "Reserved evaluation PDFs confirmed outside $DATA by operator." >&2
else
  echo "Refusing to index training PDFs without proof of the ~100 reserved evaluation PDFs' exclusion." >&2
  echo "Create $WORK/heldout_real_pdfs.json with source_pdfs/project_family_ids," >&2
  echo "or set SSL_RESERVED_PDFS_OUTSIDE_DATA=1 if they physically live outside $DATA." >&2
  exit 2
fi
"$PY" scripts_index_real_pdfs.py "$DATA" "$WORK/pdf_page_index/index.json" \
  --roles class_maps/page_roles.json "${RESERVED_ARGS[@]}"

echo "=== INCREMENTAL NON-PDF BLUEPRINT SOURCE INGEST ==="
"$PY" scripts_build_page_pool.py "$DATA" "$WORK/pool" \
  --roles class_maps/page_roles.json --workers 5 --skip-pdfs
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
"$PY" adapters/import_standardized_devices.py "$DATA" "$LABELS" --dpi 200

echo "=== FRESH BLUEPRINT-ONLY SSL TRAINING ==="
mkdir -p "$OUT"
exec "$PY" scripts_pretrain_ssl.py \
  --data-root "$DATA" \
  --work-root "$WORK" \
  "${FPCAD_ARGS[@]}" \
  --labeled-annotations "$LABELS/*.json" \
  --neca-dir "$NECA" \
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
