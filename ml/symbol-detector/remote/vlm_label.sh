#!/usr/bin/env bash
# Run annotator.agent_cli on the training machine from the laptop.
#
# Batch labeling agents run here, but the PDFs and the label store live on the
# remote box. This wrapper forwards one agent_cli command over ssh. Page and
# batch paths are REMOTE paths (as printed by `next` and `doc`).
#
#   remote/vlm_label.sh next    work/labels/batches/batch_01.txt
#   remote/vlm_label.sh doc     work/labels/batches/batch_01.txt --doc 1
#   remote/vlm_label.sh context work/labels/<doc>/<name>_p0012.json
#   remote/vlm_label.sh view    <page.json> --out work/vlm_views/b01/full.png [--region x0,y0,x1,y1]
#   remote/vlm_label.sh act     <page.json> --actions local_actions.json --agent nemotron
#   remote/vlm_label.sh status  work/labels/batches/batch_01.txt
#
# Relative remote paths resolve against $REMOTE_DIR (default
# ~/Projects/blueprint-symbols). `view --out` and `act --actions` are LOCAL
# paths; the wrapper copies them across.
set -euo pipefail

REMOTE="${REMOTE:-max@192.168.1.30}"
REMOTE_DIR="${REMOTE_DIR:-Projects/blueprint-symbols}"

if [[ $# -lt 1 ]]; then
  sed -n '2,16p' "$0"
  exit 64
fi

command="$1"; shift
args=()
local_out=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --out)
      local_out="$2"; args+=(--out -); shift 2 ;;
    --out=*)
      local_out="${1#--out=}"; args+=(--out -); shift ;;
    --actions)
      if [[ "$2" == "-" ]]; then payload="$(cat)"; else payload="$(cat -- "$2")"; fi
      args+=(--actions-b64 "$(printf '%s' "$payload" | base64 -w0)"); shift 2 ;;
    --actions=*)
      payload="$(cat -- "${1#--actions=}")"
      args+=(--actions-b64 "$(printf '%s' "$payload" | base64 -w0)"); shift ;;
    *)
      args+=("$1"); shift ;;
  esac
done

if [[ "$command" == "act" ]] && ! printf '%s\n' "${args[@]}" | grep -q -- '--actions-b64'; then
  # Actions on stdin.
  args+=(--actions-b64 "$(base64 -w0)")
fi

# Build a bash command line and ship it base64-encoded: the remote login shell
# is fish, whose quoting differs from bash.
script="cd ~/${REMOTE_DIR} && PYTHONWARNINGS=ignore PYTHONPATH=code exec .venv/bin/python -m annotator.agent_cli $(printf '%q ' "$command" "${args[@]}")"
encoded="$(printf '%s' "$script" | base64 -w0)"

if [[ -n "$local_out" ]]; then
  mkdir -p "$(dirname -- "$local_out")"
  tmp="${local_out}.part"
  meta="${local_out}.meta"
  if ssh -o BatchMode=yes "$REMOTE" "echo $encoded | base64 -d | bash" > "$tmp" 2> "$meta"; then
    if head -c 8 "$tmp" | grep -q PNG; then
      mv -f "$tmp" "$local_out"
      extra="$(grep '^{' "$meta" | tail -n 1)"
      rm -f "$meta"
      python3 - "$local_out" "$(stat -c %s "$local_out")" "$extra" <<'PY'
import json, sys
out = {"written": sys.argv[1], "bytes": int(sys.argv[2])}
if sys.argv[3]:
    out.update(json.loads(sys.argv[3]))
print(json.dumps(out, indent=1))
PY
      exit 0
    fi
  fi
  cat "$tmp" "$meta" 2>/dev/null; rm -f "$tmp" "$meta"
  exit 2
fi

exec ssh -o BatchMode=yes "$REMOTE" "echo $encoded | base64 -d | bash"
