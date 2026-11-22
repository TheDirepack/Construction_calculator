#!/usr/bin/env bash
# Push the pipeline code (and optionally the downloaded source data) to the
# training machine. Safe to re-run: rsync only sends what changed, and it never
# deletes anything on the remote data side.
#
#   ml/symbol-detector/remote/sync.sh            # code only
#   ml/symbol-detector/remote/sync.sh --data     # code + blueprints_and_libraries/
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

if [[ "${1:-}" == "--data" ]]; then
  # Raw sources only. Zips that were already extracted next to themselves are
  # skipped; partial downloads (*.part, *.tmp, *.incomplete) are skipped too.
  rsync -a --info=progress2 --partial \
    --exclude '*.part' --exclude '*.tmp' --exclude '*.incomplete' \
    --exclude '.ipynb_checkpoints' \
    "$REPO/blueprints_and_libraries/" "$REMOTE:$REMOTE_DIR/data/blueprints_and_libraries/"
  echo "data -> $REMOTE:$REMOTE_DIR/data/blueprints_and_libraries"
fi
