#!/usr/bin/env bash
# Copy this machine's human-labelled pages to the training machine's label
# store, so post-training sees them. Both stores are the 1_blueprints folder:
# each drawing's labels are one <prefix>_labels.json in the devices/ folder
# beside it (annotator.document_labels).
#
#   ml/symbol-detector/remote/push_labels.sh [local_store]
#
# Sends every page training can use (a verified box, "every symbol on this
# page is labeled", or "verified no target symbols") with its page image. The
# remote merges them page by page into its own labels file: a pushed page
# replaces that page there, other pages (agents, model) are kept. Safe to re-run. Override the target with
# REMOTE=user@host and REMOTE_STORE=path. The remote needs this code synced
# (remote/sync.sh) first.
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
STORE="$(cd "${1:-${BPS_LABELS_DIR:-$REPO/blueprints_and_libraries/1_blueprints}}" && pwd)"
REMOTE="${REMOTE:-max@192.168.1.30}"
REMOTE_DIR="${REMOTE_DIR:-Projects/blueprint-symbols}"
REMOTE_STORE="${REMOTE_STORE:-$REMOTE_DIR/data/blueprints_and_libraries/1_blueprints}"

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
PYTHONPATH="$HERE" python3 - "$STORE" "$STAGE" <<'PY'
import sys
from pathlib import Path

from annotator import label_store

staged, images, pages = label_store.stage_push(Path(sys.argv[1]), Path(sys.argv[2]))
(Path(sys.argv[2]) / "staged.txt").write_text("".join(f"{p}\n" for p in staged))
(Path(sys.argv[2]) / "images.txt").write_text("".join(f"{p}\n" for p in images))
print(f"{pages} labelled pages in {len(staged)} documents to push", file=sys.stderr)
PY
rsync -a --files-from="$STAGE/images.txt" "$STORE/" "$REMOTE:$REMOTE_STORE/"
rsync -a --files-from="$STAGE/staged.txt" "$STAGE/" "$REMOTE:$REMOTE_STORE/"
echo "labels -> $REMOTE:$REMOTE_STORE"

# Merge on the remote (label_store.merge_pushed). Paths are relative to the
# remote home directory, as rsync uses them.
ssh -o BatchMode=yes "$REMOTE" "bash -lc 'cd \$HOME && PYTHONPATH=$REMOTE_DIR/code PYTHONWARNINGS=ignore $REMOTE_DIR/.venv/bin/python - $REMOTE_STORE $REMOTE_DIR/data/blueprints_and_libraries'" <<'PY'
import json
import sys
from pathlib import Path

from annotator import label_store

result = label_store.merge_pushed(Path(sys.argv[1]), Path(sys.argv[2]))
print(json.dumps(result))
if result["missing_pdf"]:
    print(f"no matching PDF on the remote for: {', '.join(result['missing_pdf'][:10])}", file=sys.stderr)
PY
