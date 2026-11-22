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


## SAM 3.1 hierarchical device proposals (2026-11-22)

SAM 3/3.1 processes **multiple category prompts as independent batched
queries** against a single cached image embedding. This is not a native
joint multiclass decoder; the practical speed win is skipping unneeded
fine queries after discovering which families occur on a tile.

The new default runs two stages on each nonblank blueprint tile:

1. **Coarse**: one/two representative visual exemplars per device family
   (switch, receptacle, luminaire, data outlet, fire alarm, etc.). Output
   has `subtype=unspecified`, even though a prototype was used.
2. **Fine**: only families proposed by stage 1 get detailed NECA subtype
   queries. Both stages share the *same* cached SAM image encoding. Fine
   predictions must overlap the corresponding coarse-family proposal.

A periodic full fine scan recovers candidates that coarse prompts might
have missed. It is not a complete recall guarantee; the real-data
benchmark must compare this pipeline against the original flat baseline.

The source NECA 100 ZIP has 466 entries; the curated bank retains 284
eligible exemplars across 16 source categories and 254 distinct
category/subcategory pairs. The prompt metadata preserves all fine
categories, including those not selected for a particular query.

```bash
# Catalog preflight without model inference:
python scripts_run_sam3_pdfs.py --catalog-only \
    --neca-dir ../data/blueprints_and_libraries/2_component_libraries/neca_100_2013_svgs/NECA_100_Vector_Library \
    --data-root ../data/blueprints_and_libraries --out ../work/sam3_proposals

# Hierarchical default: all curated subtypes available, 2 initial
# exemplar prompts per family, gated fine prompts and 1/12 tile fallback:
python scripts_run_sam3_pdfs.py \
    --neca-dir ../data/blueprints_and_libraries/2_component_libraries/neca_100_2013_svgs/NECA_100_Vector_Library \
    --data-root ../data/blueprints_and_libraries --out ../work/sam3_proposals

# Key tuning:
# --scan-mode hierarchical --coarse-per-family 2
# --coarse-score-thr 0.30 --score-thr 0.55
# --fine-per-family 0         # 0 = all available variants within active family
# --fallback-every-tiles 12   # 0 disables, 1 = full fine search on each tile
# --scan-mode flat --max-prompts 96 --prompts-per-category 12
# --scan-mode flat --max-prompts 0 --prompts-per-category 0
# --native-only               # native vectors only; excludes some Annex A
# --prompt-allowlist reviewed_ids.json
```

**Performance:** Flat mode queries all configured prototypes on every tile.
Hierarchical mode queries only the coarse family prototypes and variants
from observed families. The output JSON reports `query_stats`: encoded tile
count, coarse/fine prompt calls, fallback count, and coarse/fine proposal
counts. Compare actual GPU time, proposal recall and accepted-label precision
on an exhaustively reviewed *small* held-out electrical set before treating
the hierarchical setting as more accurate/faster. The old low-level cached
SAM geometry path still needs an installed-model compatibility test.

**Avoid combinatorial label explosion:** Device family and fine symbol
form are separate from electrical attributes. A switch may be
`pole_configuration=three_way` with
`control_features=[dimmer,low_voltage]`. A receptacle may be duplex,
GFCI-protected, emergency-fed and floor-mounted. Voltage, phase, amperage,
NEMA configuration and circuit associations are optional *evidence-backed*
properties read from the actual project's legend, adjacent notes or
schedules; they are not inferred from stock NECA examples.

The VLM review batch contains NECA `taxonomy`,
`category_taxonomy` and `device_attribute_schema`. Model reviewers must
explicitly accept/reject/mark uncertain. Only accepted, valid
geometry/type/attribute proposals are exported as *provisional* devices.
Document-specific automatic proposals never imply exhaustive annotations.
Output names are fingerprinted by model, source, prompt selections and
tuning so old results are not silently reused.

### Partially annotated real drawings

Import real device annotations as `annotation_completeness=partial` with
`exhaustive_families=[]`. Individually verified positive boxes are useful,
but missing objects must not become negative targets. The existing strict YOLO
export will intentionally refuse incomplete pages unless its exploratory
override is enabled. Do **not** use that override for final training:
prepare manually verified complete crops/regions, or implement a masked-loss
objective that ignores unverified pixels. Whole-page evaluation still needs
a smaller *fully reviewed* held-out set.
