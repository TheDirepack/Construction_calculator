#!/usr/bin/env bash
# Incremental pool build for newly synced blueprint PDFs, then launch
# high-value-weighted SSL pre-training v2 (warm-started from v1).
set -euo pipefail

BASE="$HOME/Projects/blueprint-symbols"
CODE="$BASE/code"
DATA="$BASE/data/blueprints_and_libraries"
WORK="$BASE/work"
PY="$BASE/.venv/bin/python"

export PYTHONPATH="$CODE"
export PYTHONUNBUFFERED=1
cd "$CODE"

echo "=== [1/2] INCREMENTAL PAGE POOL BUILD (new blueprint PDFs + archives) ==="
$PY scripts_build_page_pool.py "$DATA" "$WORK/pool" \
  --roles class_maps/page_roles.json \
  --workers 5

echo "=== POOL COUNTS AFTER INGEST ==="
for d in "$WORK/pool/background" "$WORK/pool/label_queue" "$WORK/pool/negative"; do
  echo "  $(basename "$d"): $(find "$d" -maxdepth 1 -name '*.json' | wc -l) pages"
done
df -h ~

echo "=== [2/2] STARTING SSL PRE-TRAINING V2 (55% real construction drawings) ==="
mkdir -p "$WORK/pretrain_v2"
exec $PY scripts_pretrain_ssl.py \
  --data-root "$DATA" \
  --work-root "$WORK" \
  --fpcad-train-dir "$WORK/pretrain_v1/floorplancad_train_pngs" \
  --out "$WORK/pretrain_v2" \
  --init-weights "$WORK/pretrain_v1/last_pretrain.pt" \
  --imgsz 512 \
  --batch 12 \
  --epochs 60 \
  --items-per-epoch 8192 \
  --val-items 512 \
  --lr 6e-4 \
  --workers 2 \
  --run
