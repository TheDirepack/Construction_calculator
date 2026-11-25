#!/usr/bin/env bash
# Run on THIS machine (where labelling happens): wait for a pretraining run on
# the training machine to finish, push the latest labels and code, flag the
# run's best backbone, then start post-training there.
#
#   systemd-run --user --unit bps-post-train-chain \
#     ml/symbol-detector/remote/post_train_after_pretrain.sh bps-pretrain-v5 pretrain_v5 phase2_v5
#
# Arguments: pretraining unit name, its run folder under work/, a NEW
# post-training work folder under work/. Stops without promoting if the
# pretraining unit did not finish successfully.
set -euo pipefail

UNIT="${1:?pretraining systemd unit}"
RUN="${2:?pretraining run folder under work/}"
PHASE2="${3:?new post-training work folder under work/}"
REMOTE="${REMOTE:-max@192.168.1.30}"
BASE_REMOTE='$HOME/Projects/blueprint-symbols'
HERE="$(cd "$(dirname "$0")" && pwd)"

echo "waiting for $UNIT on $REMOTE to finish"
while ssh -o BatchMode=yes "$REMOTE" "systemctl --user is-active -q $UNIT"; do
  sleep 300
done
result="$(ssh -o BatchMode=yes "$REMOTE" "systemctl --user show -p Result --value $UNIT" || echo unknown)"
echo "$UNIT finished: $result"
if [[ "$result" != "success" ]]; then
  echo "pretraining did not succeed; nothing promoted, post-training not started" >&2
  exit 1
fi

"$HERE/sync.sh" --blueprints   # code (push_labels uses it) + drawings mirrored, deletions included
"$HERE/push_labels.sh"

ssh -o BatchMode=yes "$REMOTE" "bash -lc '
  set -e
  B=$BASE_REMOTE
  \$B/code/remote/promote_pretrain.sh \$B/work/$RUN
  systemd-run --user --unit bps-post-train-$PHASE2 -E PHASE2_WORK=\$B/work/$PHASE2 \
    -E PHASE2_RUN_NAME=$PHASE2 bash \$B/code/remote/run_phase2_train.sh
'"
echo "post-training started on $REMOTE as bps-post-train-$PHASE2"
