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


## Precision-first, self-hosted SAM 3.1 symbol proposals (2026-11-22)

The GPU scanner is **local** and produces provisional device candidates. Its
primary goal is to avoid flooding the downstream VLM with false boxes, **not**
to label every device on every drawing. A small, fully reviewed evaluation set
is still required before claiming real-world precision gains.

### Detection path

1. Preflight the NECA glyphs: reject routing/legend modifiers, parent rows
   with separately extracted alternatives, implausible sparsity/solid fills,
   and fragmented visual references. Keep the original symbol IDs.
2. Rasterize SVGs and encode reference geometry using persistent **local**
   caches in `<out>/_sam_prompt_cache`. Group geometric reference encodings
   in bounded batches; on 6GB GPUs reduce `--reference-geometry-batch` to
   `1` or `2` if the default of `4` is too large.
3. Encode each 1008px blueprint tile **once**. Query broad device families in
   GPU batches, then query the 12 most representative subtype exemplars only
   within active families (all variants are available with
   `--fine-per-family 0`).
4. Apply precision gates: **coarse-only boxes are not exported**; confirmed
   fine hits require overlap with a coarse-family candidate. Reject boxes
   from blank/fill-only areas, highly elongated routing strokes, duplicate
   overlap tiles, and overactive families. De-duplicate nested, variable-sized
   boxes after combining all tiles. Each reject reason is counted.
5. Review candidate crops as a VLM/human. A completed review explicitly sets
   `review_status` and `review_source`; unreviewed candidates never pass.
   Approved outputs remain **provisional**, never exhaustive ground truth.

The NECA 100 source ZIP has 466 items; the catalog contains 284 eligible
visual exemplars across 16 drawing-system categories and 254
category/subcategory pairs before reference quality filtering. VLM subtype
choices include the complete eligible taxonomy, not just queried exemplars.

```bash
# CPU category/index preflight (model still needs to be installed on the
# GPU machine for the normal scan):
python scripts_run_sam3_pdfs.py --catalog-only \
    --neca-dir ../data/blueprints_and_libraries/2_component_libraries/neca_100_2013_svgs/NECA_100_Vector_Library \
    --data-root ../data/blueprints_and_libraries \
    --out ../work/sam3_proposals

# Precision-first local GPU run:
python scripts_run_sam3_pdfs.py \
    --neca-dir ../data/blueprints_and_libraries/2_component_libraries/neca_100_2013_svgs/NECA_100_Vector_Library \
    --data-root ../data/blueprints_and_libraries \
    --out ../work/sam3_proposals

# Effective defaults:
#   --scan-mode hierarchical --coarse-per-family 2 --fine-per-family 12
#   --coarse-score-thr 0.50 --score-thr 0.72
#   --emit-generic OFF --fallback-every-tiles 0
#   --max-box-aspect 6 --min-ink-fraction 0.018 --max-ink-fraction 0.85
#   --coarse-family-limit 50 --fine-family-limit 80
#   --reference-render-height 96 --reference-geometry-batch 4
#
# High-recall control comparison:
#   --fine-per-family 0 --fallback-every-tiles 1 --score-thr 0.55
# Flat reference comparison (same source and model):
#   --scan-mode flat --max-prompts 96 --prompts-per-category 12
#   --score-thr 0.55
#
# Cached geometry is versioned by resolved model revision, model
# configuration, image glyph content and render parameters.
#   --no-prompt-cache             bypass raster and geometry caches
#   --no-reference-quality       bypass quality rejection for A/B tests
#   --native-only                exclude raster-traced Annex A exemplars
#   --prompt-allowlist PATH      explicitly approved NECA IDs
#   --prompt-denylist PATH       locally reviewed false-positive NECA IDs
```

**Family thresholds:** Create a local JSON file, e.g.:

```json
{
  "switch": {"coarse": 0.55, "fine": 0.80},
  "receptacle": {"coarse": 0.53, "fine": 0.77},
  "fire_alarm_detector": {"fine": 0.82}
}
```

Pass `--family-thresholds my_thresholds.json`. These numbers are
**illustrative only**, not empirically calibrated. The code validates
family names and applies the family-specific thresholds on GPU before
copying detection boxes back to the CPU.

### Reference and prompt best practices

- Favor a **small number of clean, distinct positives** (common base shapes
  first) over many near-identical or text-heavy legend entries. No printed
  definitions, annotations, table borders, circuit-routing arrows, or
  full legend rows in an exemplar.
- Inspect cropped reference images at the same approximate effective
  pixel scale as plan symbols. Avoid ambiguous fragments; use a tight
  bounding rectangle with a little surrounding paper. Include varied
  drafting styles where justified by real positive samples.
- Use a short **object-concept text prompt** (`electrical wall switch`,
  `duplex receptacle`, etc.) for a controlled experiment. Compare
  **text-only, exemplar-only, and combined text+exemplar** predictions.
  Meta/Hugging Face support positive and negative exemplar boxes, but
  **this runner's custom low-level off-image SVG embedding path does not
  implement native negative prompt handling**. Do not mix unsupported
  negative-box semantics into the cached geometry encoder.
- Collect hard negatives specifically from text callouts, circuit routing,
  grid bubbles, junction-box lookalikes, and symbol legends. Only
  **human-verified** false-positive crops should drive automatic prompt
  removal; the source page need not be completely annotated for this.
- The NECA graphics are standardized symbols, not guaranteed matches for
  each architect/engineer's custom legend. Confirm symbol meaning against
  the **actual drawing's own legend** when a variant is unclear.

To identify noisy NECA exemplars from existing contact-sheet reviews:

```bash
python scripts_sam_prompt_feedback.py \
    --review-dir ../work/circuitnet_review_sheets \
    --out ../work/sam_prompt_feedback.json \
    --suggested-denylist ../work/sam_noisy_prompts_review.json
# After inspecting the suggestions:
# --prompt-denylist ../work/sam_noisy_prompts_review.json
```

The tool ranks human and VLM review results separately. It only proposes a
denylist entry after at least 10 human-reviewed rejections spanning two
source PDFs and no human-confirmed positive examples. It never applies the
suggestions automatically.

### Verification and expected tradeoffs

For the **same** PDFs and model revision, compare `query_stats`, PDF run
time, total emitted candidates, VLM/human rejection fraction, missed devices
on a small exhaustively annotated test set, and class-by-class recall.
Lower candidate count alone does **not** prove higher precision. The
default thresholds and image-quality limits are engineering starting
points, **not benchmarked optima**. The low-level SAM geometry/DETR encoder
path, including the new multi-exemplar geometry batches, needs an
end-to-end test on the installed Transformers/SAM 3.1 revision.

The default `--dpi 150 --max-pixels 20000000` can downscale large sheets
below the intended resolution. Inspect actual `render_size_px` to ensure
small text-like device symbols remain legible. Raising render resolution
can improve detection but increases page RAM, tile count and runtime.

Electrical shape labels should not multiply into every possible voltage/
phase combination. Switch pole/dimmer features, receptacle configuration,
GFCI protection, mounting, emergency service, voltage, phase and NEMA
configurations are independent VLM attributes supported only when the
drawing/legend/schedule provides evidence.

### Partially annotated real drawings

Import real device annotations as `annotation_completeness=partial` with
`exhaustive_families=[]`. Individually verified positive boxes are useful,
but missing objects must not become negative targets. The existing strict YOLO
export will intentionally refuse incomplete pages unless its exploratory
override is enabled. Do **not** use that override for final training:
prepare manually verified complete crops/regions, or implement a masked-loss
objective that ignores unverified pixels. Whole-page evaluation still needs
a smaller *fully reviewed* held-out set.
