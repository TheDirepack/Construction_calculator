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

Copy from the laptop with scp over **Ethernet**; Wi-Fi measured about 0.2 MB/s.
The remote downloads from the internet at about 28 MB/s, so public sources can
also be fetched there directly. Run long jobs (copies, pool builds, training)
as systemd user units (`systemd-run --user --unit=NAME --collect ...`):
processes started from a T3 session die when the T3 server restarts.

## Running

```bash
cd ~/Projects/blueprint-symbols/code
remote/run_pipeline.sh            # pool -> layouts -> import -> composite -> prepare -> verify
remote/run_pipeline.sh smoke      # + 2-epoch GPU run
systemd-run --user --unit=bps-train --collect -E RUN=v1 \
    -p WorkingDirectory=$HOME/Projects/blueprint-symbols/code remote/run_pipeline.sh train
```

Each stage skips itself if its output exists; delete `work/<RUN>/<stage>` to
redo it. The page pool (`work/pool`) is shared and incremental: new files in
`data/` are rendered on the next run.

## Every source has a role

`class_maps/page_roles.json` assigns each raw file (or each page of a scanned
set) one role. The first matching rule wins:

| Role | What happens | Sources |
| --- | --- | --- |
| `background` | electrical glyphs are composited onto it with exact boxes | architectural, structural, civil, mechanical and plumbing sheets (Rinker, NJATC scan sheets 1-22, 24-25), HABS/HAER, UW, Olmsted, LoC, Wikimedia, pattern books, innerscene and open-source CAD renders, rendered ResPlan/CubiCasa/HouseExpo layouts, FloorPlanCAD |
| `label_queue` | real electrical symbols, no labels yet: model proposals + human review, then real training and test data | NJATC scan sheets 23 and 26-49, field album 1, Rinker `electrical/` and `fire_protection/`, USFS power/lighting DXFs, electrical pages of vector PDFs (found by text) |
| `asset` | symbol libraries to harvest glyph shapes from | innerscene `*symbol*`, NECA, EdrawMax, SimpleDiagrams, Draftsperson |
| `skip` | not plan symbols | panel photos (album 2), Google Open Buildings |

Unlabeled sheets that contain real electrical symbols never enter supervised
training as-is: the detector would learn that receptacles are background.

The model is **electrical-only** (receptacle, switch, luminaire, junction box,
data outlet, fire alarm device, panelboard). Most backgrounds have unlabeled
doors and windows, so architectural classes would get false negatives. Verified
FloorPlanCAD door/window labels are dropped to background in composites. A
separate door/window model can be trained from FloorPlanCAD and CubiCasa later.

The innerscene `.dxf` files are mixed: real DXF, plus DWG R10-R12, PSD, ZIP,
PDF and SVG under a `.dxf` name. The pool builder detects the real type from
the file contents. DWG files wait for LibreDWG (`yay -S libredwg`) and are
listed in `work/pool/dwg_unconverted.json`.

ArchCAD (gated on Hugging Face) needs an approved account token before it can
be downloaded.

## First smoke run (2026-11-21, FloorPlanCAD only, before the page pool)

`RUN=smoke COPIES=1 TILES=4 VARIANTS=2 remote/run_pipeline.sh smoke` completed end to end:

- 1000 FloorPlanCAD pages → 188 drawing groups split 132 / 28 / 28 (train / val / test)
- 18,000 composited electrical symbols; 12 classes (7 electrical, 5 architectural)
- 2,656 training tiles; val 785 tiles (7,611 symbols), test 810 tiles
- YOLO26n-P2, batch 8: 2.0 GB VRAM, about 40 s per epoch
- after 2 epochs: receptacle recall 0.70, mAP50 0.30. Everything else was still near zero, as expected this early.

Held-out tiling uses scales 1 and 2 (`EVAL_SCALES`). At scale 1 alone, the dense
1000 px pages left about 4,400 symbols clipped in every tile, and the export's
coverage gate refused to continue.

Full-run defaults (2 composites per page, 16 tiles, 3 variants) give roughly
65k training tiles, about 15 min per epoch (an estimate scaled from the smoke
run). Early stopping (patience 40) can end it before 300 epochs; that has not
been measured yet.


## Grouped-shape SAM 3.1 proposals (self-hosted)

Goal: detect real electrical devices without flooding Nemotron VLM review with
hundreds of overlapping or false bounding boxes. Missing rare objects is
acceptable; never mark a partially annotated page as exhaustive.

### Audited NECA reference hierarchy

The source vector library contains **466 SVG entries**. A full local visual
audit classifies each source entry as: **251 base glyphs, 34 switch
base-plus-suffix composites, 35 standalone/additive modifiers and 146
excluded/non-device** (including routing symbols). The inspected base
artwork is grouped into **165 visual groups**. These are reference grouping
counts, **not verified model detections**. Use `--visual-audit-out` to export
the full mapping of NECA ID → group, role, and known attributes.

- **Switches**: `S` and dollar-form switch are separate base prototypes.
  `S3`, `S4`, `SLV`, `SK`, `ST`, etc. reuse those bases;
  trailing 3/4/LV/K/T/... marks represent attributes, not new devices.
  Boxed D dimmers and diamond M occupancy sensors have genuinely
  different core artwork and retain independent coarse references.
- **Receptacles**: single, duplex, quad, emergency-filled, ceiling, floor,
  specialty, multioutlet and junction-box geometries are separate groups.
  Do not collapse them into one generic circle.
- **Luminaires**: round/rectangular/triangular fixtures, strips, tracks,
  spots, exit signs, emergency heads, pole and site luminaires preserve
  visibly different silhouettes. Mounting, aiming and emergency markers
  in modifier-only sections do not produce independent device boxes.
- **Detection/communications/control**: fire-alarm hexagons with changing
  internal letters, HVAC lettered circles/squares, security lettered
  boxes, and other repeated shell shapes share a base group.
  Internal letters still require subsequent VLM or OCR verification.
- All uncommon standalone artwork remains in its own group by default;
  grouping never silently drops an unfamiliar shape solely due to a
  semantic name mismatch.

### Production pipeline

`--scan-mode grouped` is now the default. It queries up to one clean
base reference per distinct visual group, including uncommon forms. An
optional backup SVG is rasterized if the preferred reference fails quality
checks. Suffix composites are not SAM queries. Only *base device* boxes
are emitted. Detections are filtered for overlong routing lines, blank
regions, image overlap duplicates, nested boxes and detection floods.

For switches, the code also extracts the trailing label from each NECA
switch SVG and tests those small templates in a bounded crop immediately
beside already detected `S` or dollar-form switches. The local matching
pass does **not run SAM again**; it attaches provisional marks to the
base device by geometry. Multiple nonoverlapping marks may attach to one
switch (e.g. three-way + low-voltage + dimmer). Ambiguous, conflicting or
distant marks are not treated as trusted attributes, nor as new device
boxes. Real drawing validation is mandatory, especially for custom
symbols outside NECA's conventions.

```bash
python scripts_run_sam3_pdfs.py \
  --neca-dir ../data/blueprints_and_libraries/2_component_libraries/neca_100_2013_svgs/NECA_100_Vector_Library \
  --data-root ../data/blueprints_and_libraries \
  --out ../work/sam3_proposals

# Export complete 466-entry visual taxonomy without loading the model:
python scripts_run_sam3_pdfs.py --catalog-only \
  --neca-dir ../data/blueprints_and_libraries/2_component_libraries/neca_100_2013_svgs/NECA_100_Vector_Library \
  --data-root ../data/blueprints_and_libraries \
  --out ../work/sam3_proposals \
  --visual-audit-out ../work/neca_visual_groups.json

# Grouped settings: default per visual group 1, score 0.72
#   --scan-mode grouped --visual-prototypes-per-group 1
#   --switch-suffix-score 0.92 --switch-suffix-margin 0.06
#   --no-switch-suffixes         disables local modifier matching
#   --no-prompt-cache           disables local SVG and feature caches
#   --family-thresholds FILE    per-family confidence overrides
#   --prompt-allowlist FILE     explicit NECA references
#   --prompt-denylist FILE      manually reviewed noisy reference IDs

# Compare original hierarchical/flat queries:
#   --scan-mode hierarchical --fine-per-family 12
#   --scan-mode flat --max-prompts 96 --prompts-per-category 12
```

The grouped defaults are **engineering settings**, not benchmarked optima.
The number of prompts per tile can be higher than a severely limited old
hierarchical coarse pass because every visually distinct group is now
represented; total runtime improvement is not guaranteed. However,
compound switch variants no longer each trigger a costly model query,
and modifiers do not generate extra bounding boxes.

Locally cached NECA rasters, modifier templates and SAM concept embeddings
avoid re-preparing unchanged SVGs. Prompt embeddings are keyed to source
content and model revision/configuration. GPU geometry-encoding batches
shrink automatically after out-of-memory errors.

### Precision, verification and review

The source PDF's symbol legend and schedule override any generic NECA
description. Numeric voltage/phase/amperage attributes must have explicit
drawing evidence. Proposed `analytic_attributes`, `modifier_evidence`
and `visual_group` appear in the VLM batch JSON but are **not** auto-approved.
The reviewer must visibly confirm every attribute before copying to the
accepted output. The review gate rejects unreviewed/uncertain items.

For false-positive exemplars, run:

```bash
python scripts_sam_prompt_feedback.py \
  --review-dir ../work/circuitnet_review_sheets \
  --out ../work/sam_prompt_feedback.json \
  --suggested-denylist ../work/sam_noisy_prompts_review.json
```

Manual denylist suggestions require at least 10 human rejections over two
PDFs, with no human-confirmed positive. Model-only decisions never
automatically eliminate references.

**Still unverified:** GPU inference/accuracy/throughput for the installed
SAM3.1 Transformers version and its custom cross-image concept-token path.
CPU checks on source NECA switch art found correct 3/4/LV/K/T suffix splits,
including a two-mark synthetic compound switch. This does not establish
real blueprint accuracy. Validate group coverage, reference quality losses,
candidate precision and missed true devices on a small fully audited held-out
drawing set before merging this draft PR.

### Partially annotated real drawings

Import real device annotations as `annotation_completeness=partial` with
`exhaustive_families=[]`. Individually verified positive boxes are useful,
but missing objects must not become negative targets. The existing strict YOLO
export will intentionally refuse incomplete pages unless its exploratory
override is enabled. Do **not** use that override for final training:
prepare manually verified complete crops/regions, or implement a masked-loss
objective that ignores unverified pixels. Whole-page evaluation still needs
a smaller *fully reviewed* held-out set.
