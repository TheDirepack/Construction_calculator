#!/usr/bin/env bash
# Phase 2: real labeled pages from the label store (human-verified boxes and
# verified page attestations, from the annotator and agent review) + wall-touching &
# free-space composited NECA symbols on real architectural sheets, trained with
# YOLO26n-P2 MuSGD on our own SSL backbone. The detector learns exact catalog
# devices (switch.three_way, receptacle.duplex_gfci, ...), not categories. Extracted *DEVICES.json boxes enter
# the label store as proposals (remote/update_label_store.sh) and train only
# once a human verifies them.
set -euo pipefail

BASE="$HOME/Projects/blueprint-symbols"
CODE="$BASE/code"
DATA="$BASE/data/blueprints_and_libraries"
LABELS="${BPS_LABELS_DIR:-$DATA/1_blueprints}"   # labels: devices/ beside each PDF
# Read from the folders when this runs (no index or manifest): labelled pages of
# $DATA/1_blueprints/*.pdf train; fully labelled pages of
# $DATA/1_blueprints/evaluation/*.pdf are validation and test.
# A new PHASE2_WORK for every run: composites and the prepared dataset are
# cached there and are not rebuilt when it already exists.
WORK="${PHASE2_WORK:-$BASE/work/phase2_v3}"
# The flagged pretrained backbone (remote/promote_pretrain.sh) is reused by
# every post-training run; set PRETRAIN_WEIGHTS to try another one.
PRETRAIN_WEIGHTS="${PRETRAIN_WEIGHTS:-$BASE/work/best_pretrain/backbone.pt}"
RUN_NAME="${PHASE2_RUN_NAME:-phase2_mixed_ssl}"
PY="$BASE/.venv/bin/python"

export PYTHONPATH="$CODE"
export PYTHONUNBUFFERED=1
cd "$CODE"
mkdir -p "$WORK"

echo "=== [1/5] LABEL STORE: PAGES WITH VERIFIED LABELS ==="
PYTHONPATH="$CODE" $PY - "$LABELS" "$DATA" <<'PY'
import json
import sys
from pathlib import Path
from annotator import label_store
pages = label_store.labeled_pages(Path(sys.argv[1]), data_root=Path(sys.argv[2]))
complete = [p for p in pages["evaluation"]
            if json.loads(p.read_text()).get("annotation_completeness_verified") is True
            or json.loads(p.read_text()).get("verified_no_symbols") is True]
print(f"{len(pages['train'])} training pages (1_blueprints/*.pdf); "
      f"{len(pages['evaluation'])} evaluation pages (1_blueprints/evaluation), "
      f"{len(complete)} fully labelled -> validation/test")
PY

# Backgrounds: real training-PDF pages stay dominant (~62%). Walls-only pages
# (walls drawn from ArchCAD/FloorPlanCAD line labels, rendered wall layouts)
# are proven symbol-free, so their composites are completely labelled. Other
# dataset images may hold symbols: positive-only unless listed in
# $DATA/symbol_free_images.txt.
echo "=== [2/5] COMPOSITE DEVICES: NECA 100-2013 GLYPHS + LEGEND-SHEET AND CAD-LIBRARY CROPS + CROPS OF VERIFIED LABELS ON TRAINING-PDF, WALLS-ONLY AND DATASET PAGES ==="
if [[ ! -d "$WORK/composited_neca" ]]; then
  $PY scripts_composite_neca_svg.py \
    --neca-dir "$DATA/2_component_libraries/neca_100_2013_svgs/NECA_100_Vector_Library" \
    --label-store "$LABELS" --data-root "$DATA" \
    --symbol-library "$DATA/2_component_libraries/legend_symbol_crops" \
    --symbol-library "$DATA/2_component_libraries/harvested_symbols" \
    --background-pages "${PHASE2_BACKGROUND_PAGES:-350}" \
    --walls-only-pages "${PHASE2_WALLS_ONLY_PAGES:-150}" \
    --layouts-dir "$BASE/work/pool/background_layouts" \
    --dataset-pages "${PHASE2_DATASET_PAGES:-60}" \
    --page-hash-cache "$BASE/work/pdf_page_hashes.sqlite" \
    --output "$WORK/composited_neca" \
    --copies 1 \
    --per-page 24 \
    --wall-fraction 0.60 \
    --min-px 20 \
    --max-px 64 \
    --preview 5 \
    --seed 2026
fi

echo "=== [3/5] PREPARE BUILDING-ISOLATED YOLO DATASET (REAL + NECA COMPOSITED) ==="
# Pages not marked "every symbol labeled" are POSITIVE-ONLY. A supervised
# detector can learn missed symbols as negatives. Fail closed by default;
# allow incomplete annotations for an explicitly exploratory run only.
COMPLETENESS_ARGS=()
if [[ "${PHASE2_PARTIAL:-1}" == "1" && "${PHASE2_ALLOW_UNVERIFIED:-0}" != "1" ]]; then
  # Positive-only pages train with the class-masked loss: an unlabelled symbol
  # is never taught as background. Validation/test still need two projects
  # whose pages are marked "every symbol on this page is labeled".
  COMPLETENESS_ARGS=(--allow-partial-training)
fi
if [[ "${PHASE2_ALLOW_UNVERIFIED:-0}" == "1" ]]; then
  echo "WARNING: EXPLORATORY ONLY: missing real device labels may become false negatives" >&2
  COMPLETENESS_ARGS=(--allow-unverified-completeness)
fi
if [[ ! -f "$WORK/prepared/yolo/data.yaml" ]]; then
  rm -rf "$WORK/prepared"
  $PY scripts_prepare_training_data.py \
    --label-store "$LABELS" --data-root "$DATA" \
    --annotations "$WORK/composited_neca/*.json" \
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
# One class per catalog device that occurs in the training split.
NCLASSES=$($PY -c "import json,sys; print(len(json.load(open(sys.argv[1]))['class_ids']))" "$WORK/prepared/source_manifest.json")
echo "device classes: $NCLASSES"
$PY scripts_verify_ssl_transfer.py "$PRETRAIN_WEIGHTS" --classes "$NCLASSES"
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
  --name "$RUN_NAME" \
  --exist-ok \
  --run
