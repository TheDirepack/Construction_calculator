# Training machine setup (llmserver, 192.168.1.30)

Symbol detection for the PDF plans feature: a small detector that finds and
labels plan symbols (receptacles first) so the Drawings tool can offer them
as takeoff candidates. Everything here runs on the remote GPU machine. The
app side comes later, as an exported LiteRT model.

## Machine

| | |
| --- | --- |
| GPU | RTX 2060, 6 GB, sm_75 (Turing) |
| RAM | 7.7 GB + 7.7 GB zram swap |
| OS | CachyOS, system Python 3.14 (not used) |
| `/tmp` | 3.9 GB tmpfs, too small for pip's torch wheel; installs use `TMPDIR` on disk |

## Layout on the remote

```
~/Projects/blueprint-symbols/
  .venv/        Python 3.12 (uv-managed CPython), torch 2.11+cu128, ultralytics 8.4.216
  code/         this folder (ml/symbol-detector), pushed with scp or remote/sync.sh
  data/blueprints_and_libraries/   raw sources, same layout as the repo's copy
  work/<RUN>/   normalized/ composited/ prepared/ runs/   (all generated)
```

Recreate the venv:

```bash
cd ~/Projects/blueprint-symbols
~/.local/share/uv/python/cpython-3.12.10-linux-x86_64-gnu/bin/python3.12 -m venv .venv
mkdir -p .piptmp && export TMPDIR=$PWD/.piptmp
.venv/bin/pip install torch torchvision --index-url https://download.pytorch.org/whl/cu128
.venv/bin/pip install ultralytics ezdxf cairosvg pymupdf numpy opencv-python-headless pytest svgpathtools
rm -rf .piptmp
```

Use the real interpreter path above. The `~/.local/bin/python3.12` symlink
gives a broken venv (`No module named 'encodings'`).

## Moving data

The link from the laptop to the remote is Wi-Fi and slow (about 0.2 MB/s
measured), while the remote downloads from the internet at about 28 MB/s.
Where a source is public, downloading it directly on the remote is far
faster than copying it over.

## Running

```bash
cd ~/Projects/blueprint-symbols/code
remote/run_pipeline.sh            # import -> composite -> prepare -> verify
remote/run_pipeline.sh smoke      # + 2-epoch GPU run
RUN=v1 BATCH=8 remote/run_pipeline.sh train   # full run; use tmux or nohup
```

Each stage skips itself if its output exists; delete `work/<RUN>/<stage>` to redo it.

## Where the labels come from

| Source | Labels | Used for |
| --- | --- | --- |
| FloorPlanCAD (Voxel51 export, 1000 pages) | doors, windows, stairs, elevators, plumbing fixtures (panoptic, so complete) | architectural classes and real backgrounds |
| `scripts_composite_electrical.py` | procedurally drawn receptacles, switches, luminaires, J-boxes, data, fire alarm, panels with exact boxes | electrical classes (no downloaded set labels these) |
| ArchCAD (gated on Hugging Face) | primitive-level CAD labels | not yet: needs an approved HF account token |
| Rinker Hall electrical sheets, field photos | none | the real-world test set, after hand labeling |

The composited electrical symbols are a bootstrap. Validation numbers on them
only show the loop works; real accuracy needs a hand-labeled set of real
electrical sheets (Rinker Hall `electrical/` and the field photos), kept
out of training.
