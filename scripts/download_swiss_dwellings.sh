#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
target=blueprints_and_libraries/5_datasets/msd_public_releases/swiss-dwellings-v2.0.0.zip
for attempt in {1..10}; do
  if curl -L --fail --retry 3 --connect-timeout 30 -C - -o "$target.part" 'https://zenodo.org/records/7215005/files/swiss-dwellings-v2.0.0.zip?download=1'; then break; fi
  if (( attempt == 10 )); then exit 1; fi
done
printf '%s  %s\n' d899f7d2aa73f221f465ce1dcfe72039 "$target.part" | md5sum -c -
mv "$target.part" "$target"
python3 scripts/inventory_blueprints.py
