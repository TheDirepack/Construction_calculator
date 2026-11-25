#!/usr/bin/env bash
# Add new PDFs to the label store and assign them to agent batches.
#
#   remote/update_label_store.sh            # on the training machine
#
# Training PDFs (1_blueprints/*.pdf) and evaluation PDFs (1_blueprints/evaluation)
# that are not in the store yet are triaged from their
# text (pages mentioning electrical work become pages to label); PDFs already
# checked are skipped, so this is cheap when nothing changed. New documents are
# appended to the smallest batches; agents keep their place in existing ones.
set -euo pipefail

BASE="$HOME/Projects/blueprint-symbols"
BATCHES="${BATCHES:-10}"
export PYTHONPATH="$BASE/code" PYTHONUNBUFFERED=1 PYTHONWARNINGS=ignore
cd "$BASE"
nice -n 15 .venv/bin/python code/scripts_build_label_store.py \
  --store work/labels \
  --data-root data/blueprints_and_libraries \
  --scan data/blueprints_and_libraries/1_blueprints
.venv/bin/python -m annotator.agent_cli batches --store work/labels --count "$BATCHES"
