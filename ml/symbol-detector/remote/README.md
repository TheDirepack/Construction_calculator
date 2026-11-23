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
audit classifies each source entry as: **250 base glyphs, 34 switch
base-plus-suffix composites, 35 standalone/additive modifiers and 147
excluded/non-device** (including routing symbols). The inspected base
artwork is grouped into **166 visual groups**. These are reference grouping
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
  boxes, and other repeated shell shapes share a base group. Wide HVAC
  HOA selectors and rectangular CCTV/control boxes remain separate from
  square versions. Internal letters and external functional marks still
  require subsequent VLM or OCR verification.
- All uncommon standalone artwork remains in its own group by default;
  grouping never silently drops an unfamiliar shape solely due to a
  semantic name mismatch.

### Production pipeline

`--scan-mode grouped` is the default and uses **same-image visual
exemplars**, the supported SAM 3 prompting arrangement. A compact strip
of quality-approved NECA SVGs is inserted at the right of **each tile**.
The blueprint crop occupies the left; positive prompt boxes point only to
reference glyphs in the strip. The model vision encoder processes the
combined frame once, and each glyph reuses this frame's cached features.
Predicted boxes are accepted only within the blueprint area; any box
touching the reference strip is rejected. There is **no off-image geometry
embedding transfer** on the default path.

At the standard `1008×1008` resolution, 166 grouped references fit in
six 36px-wide columns (24px maximum glyph size), leaving a `792×1008`
blueprint crop. This uses more horizontal tiles than a full-page 1008px
crop but avoids 166 separate vision encodes per tile. The reference strip
and small glyph sizes may affect detection performance and remain a
**required weighted-GPU evaluation item**. Any layout overflow fails
preflight instead of dropping categories.

It queries one base reference per visually distinct group. An optional
backup SVG is rasterized if the first fails quality checks. Suffix
composites are not SAM queries. Only *base device* boxes are emitted.
Detections are filtered for routing lines, blank regions, tile-overlap
duplicates, nested boxes and detection floods. Overlaps are merged
**before** per-tile quotas so multiple references matching the same
symbol don't crowd out distinct devices.

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

# Grouped settings: one visual prompt per group, score 0.72
#   --scan-mode grouped --visual-prototypes-per-group 1
#   --shared-reference-px 24 --shared-slot-pitch 36
#   --switch-suffix-score 0.92 --switch-suffix-margin 0.06
#   --no-switch-suffixes         disables local modifier matching
#   --no-core-crop              disables conservative cropping of outside
#                               circuit/schedule labels from reference icons
#   --max-detections-per-tile 64   hard ceiling on post-gated boxes
#   --fine-family-limit 24     ceiling on boxes per device family
#   --no-prompt-cache           disables local SVG/suffix raster caches
#   --family-thresholds FILE    per-family confidence overrides
#   --prompt-allowlist FILE     explicit NECA references
#   --prompt-denylist FILE      manually reviewed noisy reference IDs

# Old UNVALIDATED off-image transfer modes, for research only:
#   --scan-mode hierarchical --fine-per-family 12
#   --scan-mode flat --max-prompts 96 --prompts-per-category 12
```

The grouped defaults are **engineering settings**, not benchmarked optima.
The number of prompts per tile can be higher than a severely limited old
hierarchical coarse pass because every visually distinct group is now
represented; total runtime improvement is not guaranteed. However,
compound switch variants no longer each trigger a costly model query,
and modifiers do not generate extra bounding boxes.

NECA SVG rasterization and suffix templates are cached locally across
runs. The grouped default computes geometry tokens **per tile from that
tile's own vision features**; there is no cross-image feature cache.
Experimental hierarchical/flat modes retain the legacy separately encoded
SVG tokens for comparison, but such cross-image transfer is not officially
supported by SAM 3 and must not be used as evidence of production quality.
GPU geometry/DETR query batches shrink automatically on out-of-memory
errors. The processor image resolution must match `--tile-size`.

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

After reviewing and updating each `*_batch_NNN.json`, measure **the actual
false-box burden** from those review decisions:

```bash
python scripts_sam_precision_report.py \
  --review-dir "$SAM_OUTPUT/review" \
  --proposals-dir "$SAM_OUTPUT" \
  --out "$SAM_OUTPUT/review_precision.json"
```

The report separates human acceptance/rejection from VLM-only or unattributed
decisions, includes human-review coverage and per-family, per-visual-group and
score-band precision proxies, and counts high-score false proposals. It
rejects duplicate candidate IDs so duplicate exports cannot inflate
precision. With `--proposals-dir`, it reconciles every candidate against
the original SAM JSON and reports candidate boxes per scanned page. A
**complete human-only review** is required before publishing an actual
false-boxes-per-scanned-page rate; partial reviews yield only a lower bound.
**It never reports recall:** missing devices are absent from
the candidate list and require a separate exhaustive human audit. A small or
selectively reviewed sample should not be presented as general model
precision. Review crop rendering now refuses ambiguous PDF filename matches
and, for newly produced SAM outputs, mismatched **full-file PDF SHA256**.
This catches timestamp-preserving edits and also permits identical PDFs
copied to a different location. SHA256 requires one bounded-memory sequential
read per source PDF before scanning and when preparing review crops.
If a project PDF was replaced, regenerate detections before reviewing.

**Still unverified:** actual pretrained SAM3.1 checkpoint inference,
accuracy, VRAM use and throughput on electrical drawings. The default uses
same-image visual exemplars, as required by SAM3. CI runs the complete
low-level geometry/DETR layers **and** a tiny randomly initialized vision
model through the actual shared-canvas scanner; neither predicts meaningful
electrical-device labels without pretrained weights. Validate detected true
devices, false-positive counts, modifier attachment, and missed devices on
manually audited real PDF pages before merging.

### Merge readiness / self-hosted acceptance

**Verified by GitHub CI (2026-11-22):** complete detector Python
suite, 148 tests passing at the last fully verified shared-canvas revision.
Includes grouping, template tags, real randomly initialized SAM3 vision,
geometry, DETR and scoring components, page inference, OOM retries,
annotation conversion and review gates. The runner also imported
Transformers 5.20.1 and CPU PyTorch 2.6.0 / torchvision 0.21.0
(including `torchvision.ops.roi_align`). This does not load the
SAM 3.1 weights or establish actual visual precision.

Install on the **self-hosted** GPU host, using a
CUDA-enabled, *version-matched* torch and torchvision from the official
PyTorch selector, **before** installing detector dependencies:

```bash
cd ml/symbol-detector
# Install torch and torchvision together for this GPU's CUDA/driver first.
python -m pip install -r requirements-sam3.txt
python -m pytest -q tests

# Substitute local directories actually mounted on the host:
python scripts_run_sam3_pdfs.py --catalog-only \
  --neca-dir "$NECA_ROOT" --data-root "$BLUEPRINT_ROOT" \
  --out "$SAM_OUTPUT" \
  --visual-audit-out "$SAM_OUTPUT/neca_visual_groups.json"

# Smoke: up to TWO eligible electrical pages from one real PDF;
# the output is marked partial and has a distinct resume fingerprint.
python scripts_run_sam3_pdfs.py --scan-mode grouped \
  --neca-dir "$NECA_ROOT" --data-root "$BLUEPRINT_ROOT" \
  --subdirs "$DRAWING_SUBDIR" --out "$SAM_OUTPUT" \
  --limit-pdfs 1 --max-pages-per-pdf 2

# Inspect actual proposal boxes and VLM contact sheet:
python scripts_circuitnet_review_sheets.py render \
  --proposals-dir "$SAM_OUTPUT" --data-root "$BLUEPRINT_ROOT" \
  --out "$SAM_OUTPUT/review" --limit-pdfs 1

# AFTER humans confirm/reject each review item:
python scripts_sam_precision_report.py \
  --review-dir "$SAM_OUTPUT/review" \
  --proposals-dir "$SAM_OUTPUT" \
  --out "$SAM_OUTPUT/review_precision.json"
```

**Merge only after** a weighted same-image SAM 3.1 run finishes
without an unsupported internal API, missing reference group, CUDA OOM,
or invalid PDF coordinates. Inspect grouped contact sheets for actual
devices, false positives, and modifier attachment. For a valid
performance comparison use the **same-image grouped path** with different
quality/score settings and `--no-switch-suffixes`; the old
`hierarchical` and `flat` modes rely on unsupported cross-image
feature transfer and are not an authoritative performance baseline.
A partially scanned PDF does not establish full-document recall.
The `--allow-missing-visual-groups` override is for diagnosis only:
the default requires every selected coarse silhouette to survive
reference screening. If there are no valid devices in the test PDF,
that is not sufficient evidence of recall.

### Partially annotated real drawings

Import real device annotations as `annotation_completeness=partial` with
`exhaustive_families=[]`. Individually verified positive boxes are useful,
but missing objects must not become negative targets. The existing strict YOLO
export will intentionally refuse incomplete pages unless its exploratory
override is enabled. Do **not** use that override for final training:
prepare manually verified complete crops/regions, or implement a masked-loss
objective that ignores unverified pixels. Whole-page evaluation still needs
a smaller *fully reviewed* held-out set.
