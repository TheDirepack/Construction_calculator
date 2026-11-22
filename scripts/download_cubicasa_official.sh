#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
target=blueprints_and_libraries/5_datasets/cubicasa5k_official.zip
curl -L --fail --retry 10 --retry-delay 10 --connect-timeout 30 --speed-time 120 --speed-limit 1024 -C - -o "$target.part" 'https://zenodo.org/records/2613548/files/cubicasa5k.zip?download=1'
printf '%s  %s\n' 0ce0b203d1e3c125b51087b219bd23b9 "$target.part" | md5sum -c -
mv "$target.part" "$target"
python3 scripts/inventory_blueprints.py
