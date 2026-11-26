#!/usr/bin/env bash
# Push the pipeline code (and optionally the downloaded source data) to the
# training machine. Safe to re-run: rsync only sends what changed, and it never
# deletes anything on the remote data side.
#
#   ml/symbol-detector/remote/sync.sh               # code only
#   ml/symbol-detector/remote/sync.sh --blueprints  # code + mirror the training/evaluation PDFs
#   ml/symbol-detector/remote/sync.sh --data        # code + all of blueprints_and_libraries/
#
# The drawing sets are curated on this machine, so they are MIRRORED: a PDF
# removed here (1_blueprints/*.pdf, 1_blueprints/evaluation/, the devices/
# folders) is removed on the remote too. Everything else in the data tree is
# only ever added to, never deleted: the remote holds datasets downloaded or
# recovered there that this machine never had.
#
# Label folders inside the devices/ folders (devices/<stem>_<sha12>/ and
# devices/batches/) are never mirrored: agents and model pre-labeling write
# them on the remote, and remote/push_labels.sh sends this machine's labels.
#
# Override the target with REMOTE=user@host and REMOTE_DIR=path.
set -euo pipefail

REMOTE="${REMOTE:-max@192.168.1.30}"
REMOTE_DIR="${REMOTE_DIR:-Projects/blueprint-symbols}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"

ssh "$REMOTE" "mkdir -p $REMOTE_DIR/code $REMOTE_DIR/data $REMOTE_DIR/work"

rsync -a --delete \
  --exclude '__pycache__' --exclude '.pytest_cache' --exclude 'work/' \
  "$HERE/" "$REMOTE:$REMOTE_DIR/code/"
echo "code -> $REMOTE:$REMOTE_DIR/code"

if [[ "${1:-}" == "--data" || "${1:-}" == "--blueprints" ]]; then
  # Root PDFs, evaluation/ and devices/ folders only; other subfolders untouched.
  # Excluded label folders are neither sent nor deleted on the remote.
  rsync -a --delete --info=stats1 \
    --exclude='/devices/*/' --exclude='/evaluation/devices/*/' --exclude='/devices/scanned_pdfs.json' \
    --include='/*.pdf' --include='/*.PDF' --include='/devices/***' --include='/evaluation/***' \
    --exclude='*' \
    "$REPO/blueprints_and_libraries/1_blueprints/" "$REMOTE:$REMOTE_DIR/data/blueprints_and_libraries/1_blueprints/"
  echo "training/evaluation drawings mirrored -> $REMOTE:$REMOTE_DIR/data/blueprints_and_libraries/1_blueprints"
fi

if [[ "${1:-}" == "--data" ]]; then
  # Raw sources only. Zips that were already extracted next to themselves are
  # skipped; partial downloads (*.part, *.tmp, *.incomplete) are skipped too.
  rsync -a --info=progress2 --partial \
    --exclude '*.part' --exclude '*.tmp' --exclude '*.incomplete' \
    --exclude '.ipynb_checkpoints' \
    "$REPO/blueprints_and_libraries/" "$REMOTE:$REMOTE_DIR/data/blueprints_and_libraries/"
  echo "data -> $REMOTE:$REMOTE_DIR/data/blueprints_and_libraries"
fi
