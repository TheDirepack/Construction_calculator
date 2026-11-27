#!/usr/bin/env bash
# Export the best checkpoint (best.pt -> best.onnx + best.onnx.json) on the
# remote GPU box on CPU, copy best.pt / best.onnx / best.onnx.json into
# ml/symbol-detector/work/models/<run_name>/, and point work/models/latest at it.
#
# Usage:
#   bash remote/pull_best_model.sh [run_name]
#   bash remote/pull_best_model.sh phase2_v8_init
set -euo pipefail

REMOTE="${REMOTE:-max@192.168.1.30}"
REMOTE_DIR="${REMOTE_DIR:-Projects/blueprint-symbols}"
RUN_NAME="${1:-phase2_v8_init}"

if [[ ! "$RUN_NAME" =~ ^[A-Za-z0-9_.-]+$ ]]; then
  echo "Invalid run name: $RUN_NAME" >&2
  exit 2
fi

HERE="$(cd "$(dirname "$0")/.." && pwd)"
MODELS_DIR="$HERE/work/models"
DEST="$MODELS_DIR/$RUN_NAME"

ssh "$REMOTE" 'bash -s' -- "$REMOTE_DIR" "$RUN_NAME" <<'EOF'
set -euo pipefail
RDIR="$1"
RUN="$2"
BASE="$HOME/$RDIR"
PT="$BASE/work/$RUN/runs/$RUN/weights/best.pt"
ONNX="$BASE/work/$RUN/runs/$RUN/weights/best.onnx"
META="$ONNX.json"
PY="$BASE/.venv/bin/python"

if [[ ! -s "$PT" ]]; then
  echo "Missing remote checkpoint: $PT" >&2
  exit 2
fi

NEEDS_EXPORT=$("$PY" - "$PT" "$ONNX" "$META" <<'PY'
import hashlib, json, sys
from pathlib import Path

pt, onnx, meta = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
if not onnx.is_file() or not meta.is_file():
    print(1)
    raise SystemExit(0)
try:
    info = json.loads(meta.read_text(encoding="utf-8"))
    h = hashlib.sha256()
    with pt.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    print(0 if info.get("source_pt_sha256") == h.hexdigest() else 1)
except Exception:
    print(1)
PY
)

if [[ "$NEEDS_EXPORT" == "1" ]]; then
  echo "Exporting $PT -> $ONNX (CPU)..."
  CUDA_VISIBLE_DEVICES="" PYTHONPATH="$BASE/code" "$PY" \
    "$BASE/code/scripts_export_onnx_detector.py" \
    --weights "$PT" --output "$ONNX"
else
  echo "Remote ONNX is already up to date with $PT"
fi
EOF

mkdir -p "$DEST"
REMOTE_WEIGHTS="$REMOTE_DIR/work/$RUN_NAME/runs/$RUN_NAME/weights"
rsync -av --progress \
  "$REMOTE:$REMOTE_WEIGHTS/best.pt" \
  "$REMOTE:$REMOTE_WEIGHTS/best.onnx" \
  "$REMOTE:$REMOTE_WEIGHTS/best.onnx.json" \
  "$DEST/"

ln -sfn "$RUN_NAME" "$MODELS_DIR/latest"
echo "Updated $MODELS_DIR/latest -> $RUN_NAME"
python3 - "$DEST/best.onnx.json" "$DEST" <<'PY'
import json, sys
from pathlib import Path
meta = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
dest = Path(sys.argv[2])
print(json.dumps({
    "model_dir": str(dest),
    "best_pt": str(dest / "best.pt"),
    "best_onnx": str(dest / "best.onnx"),
    "best_onnx_json": str(dest / "best.onnx.json"),
    "epoch": meta.get("epoch"),
    "best_fitness": meta.get("best_fitness"),
    "classes": len(meta.get("names") or {}),
    "output_layout": meta.get("output_layout"),
    "parity_max_abs_diff": meta.get("parity_max_abs_diff"),
}, indent=2))
PY
