#!/usr/bin/env bash
# Copy this machine's human-labelled pages to the training machine's label
# store, so post-training sees them.
#
#   ml/symbol-detector/remote/push_labels.sh [local_store]
#
# Sends every page training can use (a verified box, "every symbol on this
# page is labeled", or "verified no target symbols") with its page image.
# Files it would replace on the remote are kept as *.remote_backup. Safe to
# re-run: rsync only sends what changed. Override the target with REMOTE=user@host
# and REMOTE_STORE=path.
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
STORE="$(cd "${1:-${BPS_LABELS_DIR:-$HERE/work/labels}}" && pwd)"
REMOTE="${REMOTE:-max@192.168.1.30}"
REMOTE_STORE="${REMOTE_STORE:-Projects/blueprint-symbols/work/labels}"

LIST="$(mktemp)"
trap 'rm -f "$LIST"' EXIT
PYTHONPATH="$HERE" python3 - "$STORE" > "$LIST" <<'PY'
import json
import sys
from pathlib import Path

from blueprint_symbols.label_schema import training_usable

store = Path(sys.argv[1])
pages = 0
for page in sorted(store.glob("*/*_p[0-9]*.json")):
    data = json.loads(page.read_text(encoding="utf-8"))
    if not training_usable(data):
        continue
    pages += 1
    folder = page.parent
    print(page.relative_to(store))
    # document.json is not sent: each machine's copy names its own PDF path.
    if not (folder / "document.json").exists():
        print(f"warning: {folder.name} has no document.json here", file=sys.stderr)
    image = folder / data.get("image_path", "")
    if data.get("image_path") and image.is_file():
        print(image.relative_to(store))
print(f"{pages} labelled pages to push", file=sys.stderr)
PY
sort -u -o "$LIST" "$LIST"
rsync -a --files-from="$LIST" --backup --suffix=.remote_backup "$STORE/" "$REMOTE:$REMOTE_STORE/"
echo "labels -> $REMOTE:$REMOTE_STORE"

# A drawing the remote store never added (no document.json there) gets its
# document entry from the remote's own copy of the PDF: the same file name in
# 1_blueprints or 1_blueprints/evaluation, and the same bytes (folder hash).
ssh -o BatchMode=yes "$REMOTE" "bash -lc 'cd \$HOME/Projects/blueprint-symbols && PYTHONPATH=code PYTHONWARNINGS=ignore .venv/bin/python - $REMOTE_STORE data/blueprints_and_libraries'" <<'PY'
import json
import sys
from pathlib import Path

from annotator import label_store
from blueprint_symbols.blueprint_sets import blueprint_sets

store, data = Path(sys.argv[1]), Path(sys.argv[2])
sets = blueprint_sets(data)
added, missing = 0, []
for folder in sorted(p for p in store.iterdir() if p.is_dir()):
    if (folder / label_store.DOCUMENT_FILE).exists():
        continue
    pages = sorted(folder.glob("*_p[0-9]*.json"))
    if not pages:
        continue
    name = json.loads(pages[0].read_text()).get("document_id", "")
    pdf = sets.evaluation.get(name.casefold()) or sets.train.get(name.casefold())
    sha = (json.loads(pages[0].read_text()).get("source_pdf_sha256") or "")
    if pdf is None or not sha or label_store.doc_folder_name(pdf, sha) != folder.name:
        missing.append(folder.name)
        continue
    label_store.create_document(store, pdf, sha256=sha, meta={"origin": "pushed_labels"})
    added += 1
print(f"remote documents added for pushed labels: {added}")
if missing:
    print(f"no matching PDF on the remote for: {', '.join(missing[:10])}", file=sys.stderr)
PY
