#!/usr/bin/env bash
# Export the best checkpoint (.pt -> best.onnx + best.onnx.json) on the
# remote GPU box on CPU, copy best.pt / best.onnx / best.onnx.json into
# ml/symbol-detector/work/models/<run_name>/, and point work/models/latest at it.
#
# Usage:
#   bash remote/pull_best_model.sh [run_name] [work_dir_name] [weight_filename]
#   bash remote/pull_best_model.sh phase2_v8_r4b phase2_v8_r4 best_real.pt
set -euo pipefail

REMOTE="${REMOTE:-max@192.168.1.30}"
REMOTE_DIR="${REMOTE_DIR:-Projects/blueprint-symbols}"
RUN_NAME="${1:-phase2_v8_init}"
WORK_NAME="${2:-$RUN_NAME}"
WEIGHT_FILE="${3:-}"

if [[ ! "$RUN_NAME" =~ ^[A-Za-z0-9_.-]+$ ]]; then
  echo "Invalid run name: $RUN_NAME" >&2
  exit 2
fi
if [[ ! "$WORK_NAME" =~ ^[A-Za-z0-9_.-]+$ ]]; then
  echo "Invalid work dir name: $WORK_NAME" >&2
  exit 2
fi
if [[ -n "$WEIGHT_FILE" && ! "$WEIGHT_FILE" =~ ^[A-Za-z0-9_.-]+$ ]]; then
  echo "Invalid weight filename: $WEIGHT_FILE" >&2
  exit 2
fi

HERE="$(cd "$(dirname "$0")/.." && pwd)"
MODELS_DIR="$HERE/work/models"
DEST="$MODELS_DIR/$RUN_NAME"

CHOSEN_WEIGHT=$(ssh "$REMOTE" 'bash -s' -- "$REMOTE_DIR" "$RUN_NAME" "$WORK_NAME" "$WEIGHT_FILE" <<'EOF'
set -euo pipefail
RDIR="$1"
RUN="$2"
WORK="$3"
REQ_WEIGHT="$4"
BASE="$HOME/$RDIR"
WDIR="$BASE/work/$WORK/runs/$RUN/weights"
if [[ -n "$REQ_WEIGHT" ]]; then
  CHOSEN="$REQ_WEIGHT"
elif [[ -s "$WDIR/best_real.pt" ]]; then
  CHOSEN="best_real.pt"
else
  CHOSEN="best.pt"
fi
PT="$WDIR/$CHOSEN"
ONNX="$WDIR/best.onnx"
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
  echo "Exporting $PT -> $ONNX (CPU)..." >&2
  CUDA_VISIBLE_DEVICES="" PYTHONPATH="$BASE/code" "$PY" \
    "$BASE/code/scripts_export_onnx_detector.py" \
    --weights "$PT" --output "$ONNX" >&2
else
  echo "Remote ONNX is already up to date with $PT" >&2
fi
echo "$CHOSEN"
EOF
)

mkdir -p "$DEST"
REMOTE_WEIGHTS="$REMOTE_DIR/work/$WORK_NAME/runs/$RUN_NAME/weights"
rsync -av --progress \
  "$REMOTE:$REMOTE_WEIGHTS/$CHOSEN_WEIGHT" \
  "$DEST/best.pt"
rsync -av --progress \
  "$REMOTE:$REMOTE_WEIGHTS/best.onnx" \
  "$REMOTE:$REMOTE_WEIGHTS/best.onnx.json" \
  "$DEST/"

ln -sfn "$RUN_NAME" "$MODELS_DIR/latest"
echo "Updated $MODELS_DIR/latest -> $RUN_NAME (from $CHOSEN_WEIGHT)"
python3 - "$DEST/best.onnx.json" "$DEST" "$CHOSEN_WEIGHT" <<'PY'
import json, sys
from pathlib import Path
meta = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
dest = Path(sys.argv[2])
chosen = sys.argv[3]
print(json.dumps({
    "model_dir": str(dest),
    "checkpoint_source": chosen,
    "best_pt": str(dest / "best.pt"),
    "best_onnx": str(dest / "best.onnx"),
    "best_onnx_json": str(dest / "best.onnx.json"),
    "epoch": meta.get("epoch"),
    "best_fitness": meta.get("best_fitness"),
    "recommended_confidence": meta.get("recommended_confidence"),
    "recommended_confidence_source": meta.get("recommended_confidence_source"),
    "recommended_class_base_confidence": meta.get("recommended_class_base_confidence"),
    "recommended_class_confidence": meta.get("recommended_class_confidence"),
    "classes": len(meta.get("names") or {}),
    "output_layout": meta.get("output_layout"),
    "parity_max_abs_diff": meta.get("parity_max_abs_diff"),
}, indent=2))

# Real-page numbers: only SELECTION chose the checkpoint and cutoffs; TEST and
# CROSS-DOCUMENT were scored at those cutoffs and are the honest estimate.
summary = meta.get("real_eval_summary") or {}
def line(name):
    rep = summary.get(name) or {}
    if not rep.get("evaluated"):
        return f"{name:>14}: not scored ({rep.get('reason', 'not evaluated for this checkpoint')})"
    p = rep.get("at_chosen") or {}
    u = p.get("uncertainty") or {}
    return (f"{name:>14}: {rep.get('documents')} drawings, {rep.get('blank_pages')} blank / "
            f"{rep.get('complete_pages')} labelled pages, {rep.get('truth_symbols')} symbols | "
            f"@{p.get('threshold')}: blank FP {p.get('blank_fp')} (<= {u.get('blank_fp_per_page_upper95')}/page, 95%), "
            f"found {p.get('found')}/{p.get('gt_total')}, labelled FP {p.get('labelled_fp')}, "
            f"recall 95% CI {u.get('recall_ci95')}")
if "scores_used_for_selection" in summary:
    print("Real-page evaluation (selection = used to choose; others = reported only):")
    for name in ("selection", "test", "cross_document"):
        print(line(name))
    stress = summary.get("stress") or {}
    if stress:
        print(f"{'stress':>14}: worst blank FP at chosen cutoff {stress.get('worst_blank_fp_at_chosen')} "
              f"({stress.get('worst_variant')}), worst blank score {stress.get('worst_blank_max_conf')}")
    print(f"highest selection blank-page score {summary.get('blank_max_conf')}, "
          f"cutoff gap {summary.get('blank_margin_gap')} (margin {summary.get('margin')})")
    if not summary.get("margin_ok"):
        print("WARNING: the recommended cutoff does not clear the highest blank-page score by the margin")
    if summary.get("val_test_independent") is False:
        print("WARNING: val_test_independent = false (one evaluation project: val and test are the same drawings)")
    if summary.get("page_pool") == "training_pages_fallback":
        print("WARNING: no held-out pages; real-page numbers come from TRAINING pages (optimistic)")
else:
    print("WARNING: checkpoint predates the selection/test split; its real-page numbers chose it (optimistic) "
          "and recommended_confidence has no blank-page margin")
PY
