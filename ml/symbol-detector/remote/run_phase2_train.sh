#!/usr/bin/env bash
# Phase 2: real labeled pages from the unified label store (human-verified
# boxes and verified page attestations, from the annotator and agent review) +
# wall-touching & free-space composited NECA/legend/harvested symbols on real
# architectural, walls-only, and dataset sheets, trained with YOLO26n-P2 MuSGD
# on our own SSL backbone. The detector learns exact catalog devices
# (switch.three_way, receptacle.duplex_gfci, ...), with class-masked partial
# supervision and fail-closed verified-real evaluation.
set -euo pipefail

BASE="${BLUEPRINT_BASE:-$HOME/Projects/blueprint-symbols}"
CODE="$BASE/code"
DATA="$BASE/data/blueprints_and_libraries"
LABELS="${BPS_LABELS_DIR:-$DATA/1_blueprints}"   # labels: devices/ beside each PDF
# Read from the folders when this runs (no index or manifest): labelled pages of
# $DATA/1_blueprints/*.pdf train; fully labelled pages of
# $DATA/1_blueprints/evaluation/*.pdf are validation and test.
# A new PHASE2_WORK for every run: composites and the prepared dataset are
# cached there and are not rebuilt when it already exists.
WORK="${PHASE2_WORK:-$BASE/work/phase2_v4}"
# The flagged pretrained backbone (remote/promote_pretrain.sh) is reused by
# every post-training run; set PRETRAIN_WEIGHTS to try another one.
PRETRAIN_WEIGHTS="${PRETRAIN_WEIGHTS:-$BASE/work/best_pretrain/backbone.pt}"
RUN_NAME="${PHASE2_RUN_NAME:-phase2_mixed_ssl_v4}"
PHASE2_BATCH="${PHASE2_BATCH:-12}"
PHASE2_EPOCHS="${PHASE2_EPOCHS:-200}"
PY="$BASE/.venv/bin/python"

export PYTHONPATH="$CODE"
export PYTHONUNBUFFERED=1
cd "$CODE"
mkdir -p "$WORK"

if [[ "${PHASE2_ALLOW_UNVERIFIED:-0}" != "0" ]]; then
  echo 'ERROR: PHASE2_ALLOW_UNVERIFIED is forbidden. Use class-masked --allow-partial-training.' >&2
  exit 2
fi
if [[ ! -s "$PRETRAIN_WEIGHTS" ]]; then
  echo "Missing fresh SSL checkpoint: $PRETRAIN_WEIGHTS" >&2
  exit 2
fi

echo "=== [1/6] VERIFY SSL BACKBONE TRANSFER BEFORE EXPENSIVE DATA PREP ==="
"$PY" scripts_verify_ssl_transfer.py "$PRETRAIN_WEIGHTS" --classes 6

echo "=== [2/6] LABEL STORE: PAGES WITH VERIFIED LABELS ==="
PYTHONPATH="$CODE" "$PY" - "$LABELS" "$DATA" <<'PY'
import sys
from pathlib import Path
from annotator import label_store
from blueprint_symbols.label_schema import read_page

pages = label_store.labeled_pages(Path(sys.argv[1]), data_root=Path(sys.argv[2]))
complete = [p for p in pages["evaluation"]
            if read_page(p).get("annotation_completeness_verified") is True
            or read_page(p).get("verified_no_symbols") is True]
print(f"{len(pages['train'])} training pages (1_blueprints/*.pdf); "
      f"{len(pages['evaluation'])} evaluation pages (1_blueprints/evaluation), "
      f"{len(complete)} fully labelled -> validation/test")
if len(complete) == 0:
    raise SystemExit("No fully labelled evaluation pages in 1_blueprints/evaluation; label at least 2 independent projects before phase 2.")
PY

# Backgrounds: real training-PDF pages stay dominant (~62%). Walls-only pages
# (walls drawn from ArchCAD/FloorPlanCAD line labels, rendered wall layouts)
# are proven symbol-free, so their composites are completely labelled. Other
# dataset images may hold symbols: positive-only unless listed in
# $DATA/symbol_free_images.txt.
echo "=== [3/6] COMPOSITE DEVICES: NECA 100-2013 GLYPHS + LEGEND-SHEET AND CAD-LIBRARY CROPS + CROPS OF VERIFIED LABELS ON TRAINING-PDF, WALLS-ONLY AND DATASET PAGES ==="
if [[ ! -d "$WORK/composited_neca" ]]; then
  "$PY" scripts_composite_neca_svg.py \
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
if ! compgen -G "$WORK/composited_neca/*.json" >/dev/null; then
  echo "No composited NECA symbol annotations in $WORK/composited_neca" >&2
  exit 2
fi

ANNOTATIONS=("$WORK/composited_neca/*.json")
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

echo "=== [4/6] PREPARE CLASS-MASKED, BUILDING-ISOLATED YOLO DATASET (REAL + NECA COMPOSITED) ==="
# Pages not marked "every symbol labeled" are POSITIVE-ONLY and train with
# class-masked loss so unlabelled symbols are never taught as background.
if [[ ! -f "$WORK/prepared/yolo/data.yaml" ]]; then
  if [[ -d "$WORK/prepared" ]] && [[ -n "$(ls -A "$WORK/prepared")" ]]; then
    echo "Incomplete previous export exists at $WORK/prepared; inspect/move it manually, never delete it implicitly." >&2
    exit 2
  fi
  "$PY" scripts_prepare_training_data.py \
    --label-store "$LABELS" --data-root "$DATA" \
    --annotations "${ANNOTATIONS[@]}" \
    --output "$WORK/prepared" \
    --train-tiles-per-page 18 \
    --train-variants 3 \
    --val-fraction 0.14 \
    --test-fraction 0.14 \
    --eval-scales 1,2,4,8,16 \
    --eval-overlap 256 \
    --allow-partial-training
fi

echo "=== [5/6] VERIFY EXPORT, HELD-OUT READINESS, AND NEGATIVE-TILE MASKS ==="
"$PY" scripts_phase2_readiness.py --prepared-manifest "$WORK/prepared/source_manifest.json"
"$PY" scripts_verify_yolo_export.py "$WORK/prepared/yolo"
"$PY" scripts_preflight_training.py "$WORK/prepared/source_manifest.json"
"$PY" - "$WORK/prepared/yolo/data.yaml" <<'PY'
import json, sys
from pathlib import Path
from blueprint_symbols.partial_supervision import read_supervision
source = Path(sys.argv[1])
mask = read_supervision(source)
if mask is None or not mask["has_partial"]:
    raise SystemExit("Missing/incorrect class-masked partial-supervision sidecar")
classes = set(mask["class_ids"].values())
labels = source.parent / "labels" / "train"
complete_empty = [
    name for name, ids in mask["samples"].items()
    if classes.issubset(set(ids))
    and not (labels / (name + ".txt")).read_text().strip()
]
if not complete_empty:
    raise SystemExit(
        "No fully verified negative detector tiles in TRAIN. "
        "Supply reviewed negative pages, walls-only pages, and/or PHASE2_WALL_MANIFEST; "
        "partial/unlabeled pages may not be used as replacement negatives."
    )
print(json.dumps({
    "partial_training": mask["has_partial"],
    "num_classes": len(mask["class_ids"]),
    "verified_complete_empty_training_tiles": len(complete_empty),
}, indent=2))
PY

echo "=== [6/6] VERIFY DYNAMIC-CLASS SSL TRANSFER, THEN TRAIN YOLO26N-P2 ==="
# One class per catalog device that occurs in the training split.
NCLASSES=$("$PY" -c "import json,sys; print(len(json.load(open(sys.argv[1]))['class_ids']))" "$WORK/prepared/source_manifest.json")
echo "device classes: $NCLASSES"
"$PY" scripts_verify_ssl_transfer.py "$PRETRAIN_WEIGHTS" --classes "$NCLASSES"
TRAIN_ARGS=(
  "$WORK/prepared/yolo/data.yaml"
  --weights "$PRETRAIN_WEIGHTS"
  --imgsz 512 --batch "$PHASE2_BATCH" --epochs "$PHASE2_EPOCHS"
  --optimizer MuSGD --lr0 0.005
  --fliplr 0.5 --flipud 0.5 --scale 0.2 --translate 0.05
  --workers 2 --project "$WORK/runs"
  --name "$RUN_NAME"
  --exist-ok
)
if [[ "${PHASE2_DRY_RUN:-0}" == "1" ]]; then
  "$PY" scripts_train_ultralytics.py "${TRAIN_ARGS[@]}"
  echo "Preflight passed; PHASE2_DRY_RUN=1 did not train."
  exit 0
fi
exec "$PY" scripts_train_ultralytics.py "${TRAIN_ARGS[@]}" --run
