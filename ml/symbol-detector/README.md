# Blueprint symbol detection — data preparation and audit v8

**Status:** Includes a scratch-initialized YOLO26n-P2 detector launcher, a documented two-epoch detector smoke test, and a runnable blueprint-only self-supervised pre-training trainer. The new multi-symbol SSL configuration has not been GPU-validated. Only the rough-location detector is in scope: no OCR, keypoint model, or snapping-refinement training.

This is **blueprint-only** training data. Generic COCO/Objects365/YOLO images and general-purpose pretrained model weights are *not* used. Public construction datasets may *provide COCO-format annotations*: that format is merely an import format. The final output uses YOLO detection labels to train your model.

The V8 resource document is a **list of potential sources, not the downloaded datasets**. The package has not been trained or validated against those full real collections. Source-dependent formats must be inspected and mapped before conversion.

## Practical architecture

```text
Original source collections (mixed page sizes, resolutions and aspect ratios)
  → inspect the actual class labels and geometry
  → import CAD/DXF, SVG, relevant COCO-style and primitive-JSON annotations
  → explicit mapping into a common electrical / mechanical / HVAC symbol vocabulary
  → independently verify complete labels for every active detection class
  → split whole original projects/drawing families: train / val / locked test

Training drawings → multiple 512px crops at varied physical scales
                  → clean + low-resolution-only + moderately/severely damaged variants
Validation/test drawings → full-coverage overlapping tiles and independent stress sets
                         → source-instance coverage gate and full-page evaluation
  → YOLO26n-P2, initialized randomly (only if you explicitly start training)
  → Android LiteRT / iOS Core ML export and NPU tests in a later phase

Optional: training + verified non-held-out unlabeled sources
          → lazy blueprint-only SSL pretraining on clean and randomly degraded
            views with 0..16 inserted vector/procedural symbols
Optional: specialist YOLO teacher → untrusted candidate labels for REVIEW ONLY
```

**Source images are never resized wholesale to 512px.** Tiles use continuous source-image coordinates. Their *effective symbol size* is deliberately varied, especially 16–32px, with some harder 10–16px examples. Input pages can be any size or aspect ratio. Crops are resampled to the detector's fixed tensor shape because the mobile NPU needs a predictable graph. Lossy simulated downsampling is applied *after* coordinate-preserving extraction. True low-DPI SVG/PDF re-renders are also supported when geometry/raster alignment is verified.

### Data and split safeguards

- **Group before augmentation.** All clean/downscaled/filtered re-renders of one original plan remain in a single train/validation/test partition. Validate source families manually; an arbitrary per-file ID is not proof of independence.
- **Protect rare classes and locked evaluations.** Reserve enough source groups for training to contain every mapped class. If impossible while retaining val/test groups, fail rather than producing unlearnable classes. For later training expansion pass `--locked-manifest` to preserve prior held-out groups, existing class IDs and unchanged source snapshots; newly added groups go to training. Warnings report missing class coverage in evaluation groups.
- **Verify image labels.** Validate image dimensions, finite in-bounds boxes, optional unique instance IDs, class IDs, split provenance, exported tile geometry, and files not listed in the manifest. Source **annotation and image SHA256** hashes are rechecked before export and full-page evaluation, so edited labels cannot silently change after splitting. Exact duplicate image bytes across different project IDs are rejected; duplicated annotations of identical rasters inside one group also fail until merged. Verified symbol-free pages are also deduplicated by image fingerprint (the same standard sheet rendered from different sets trains once); for labeled pages, perceptual near-duplicates from differently encoded/rendered versions are **not** guaranteed to be caught, so group them by original source.
- **No silent background labels.** A dataset can label valves thoroughly while not labeling receptacles. Standard YOLO detection loss has no per-image class-exhaustiveness mask: treating those missing receptacles as negatives is a defect. Consequently the normal exporter requires `exhaustive_families` covering **all active model classes** on each source. Unmapped, uncertain, or clipped symbol regions cause crops to be skipped. `--allow-unverified-completeness` is **exploratory only** and can create false negatives.
- **Held-out coverage gate.** A validation/test export fails if its usable tiles omit an otherwise labeled source instance. Overlapping tiles still duplicate some objects; for proper recall and localization evaluation, use fused **full-page** predictions and `scripts_score_full_pages.py`.
- **No pseudo-label promotion.** Teacher output remains `uncertain`, with `review_required: true`, until quality checks/verified completion are established. Teacher agreement or confidence alone is not verified truth.

## Installation

```bash
# CPU-side data preparation and source import (no PyTorch):
python -m pip install -r requirements.txt
# Run the regression tests, including optional SVG / DXF / PDF adapters:
python -m pip install -r requirements-test.txt
python -m pytest -q tests
# Training machine only: PyTorch for the model and training tests.
python -m pip install -r requirements-train.txt
```

The normal tests never need PyTorch. Model and training tests sit in `tests/test_torch_models.py`
(and two marked tests) and are skipped when it is not installed. Adapter tests skip when their
optional library (ezdxf, PyMuPDF, cairosvg) is missing.

Install `ultralytics` separately on your 6 GB NVIDIA training machine. Ensure its release has `yolo26n-p2.yaml`; the P2 model is official, with stride-4 small-object detection. Install a CUDA-enabled PyTorch build for that GPU. The package does **not** download generic pretrained weights. See [Ultralytics P2 model YAML](https://github.com/ultralytics/ultralytics/blob/main/ultralytics/cfg/models/26/yolo26-p2.yaml).

## Manual labeling / SAM proposal review (local browser UI)

The prior `annotator/tool.py` was a **command-line modifier**, not a
graphical editor. For manual training labels, use the browser editor instead.
It runs locally with only the existing CPU-side PyMuPDF dependencies and
never calls an external annotation service:

```bash
cd ml/symbol-detector
python -m pip install -r requirements-test.txt

# Label PDF pages directly. Pages go to the devices/ folder beside the PDF
# (1_blueprints/devices/<stem>_<sha12>/ or 1_blueprints/evaluation/devices/...),
# shared with the labeling agents and model pre-labeling; $BPS_LABELS_DIR or
# --store selects a flat store instead. Extracted devices/*_DEVICES.json boxes
# and SAM proposals appear as pending boxes when a page is first opened.
python -m annotator.web --pdf /path/to/electrical_plan.pdf --open-browser

# Open a single existing annotation JSON and its paired image:
python -m annotator.web --edit annotated/electrical_plan/electrical_plan_p0001.json \
  --open-browser
```

The editor supports zoom at cursor, pan, drag-to-draw, move, corner resize,
rotation, family/variant editing, spatial label list, pending/verified filter,
individual verify/reject with audit history, undo/redo, and page navigation.
Keyboard: `B` box, `V` select, hold Space to pan, `A` verify, `X` reject,
`N` next pending, Ctrl+Z/Y undo/redo, Ctrl+S save, `[`/`]` pages, `0` fit.
Long documents: type a page number in the page box (Enter), Home/End for the
first/last page, and `Shift+N` / **Review ›** for the next page with pending
boxes (saved pages and unopened pages with proposals). Page steps pressed while
a page is loading are queued, and the next two pages are pre-rendered. Unsaved
pages stay in memory until saved (Ctrl+S saves every changed page); the 12
most recent saved pages are kept as well. A kept page that was saved elsewhere
meanwhile is reloaded, or, if it has edits here, flagged as changed on disk.

One file per PDF: every label of a drawing is in
`1_blueprints/devices/<stem>_<sha12>/<stem>_labels.json` (evaluation drawings:
`1_blueprints/evaluation/devices/`): human, agent, model and imported boxes,
and the DEVICES/SAM/CircuitNet proposals as pending boxes. Editing the
drawing's `*_DEVICES.json` shows up on the next page load: pending machine
boxes are replaced by the file's current ones, while accepted, verified,
hand-drawn and rejected boxes stay. `scripts_unify_labels.py --apply` brings
everything into these files (format 2): every valid device of the DEVICES
(LLM extraction), VLM-reviewed, SAM and CircuitNet JSONs, with each box keeping
what its source said about it (`proposal_details`) and its centre / wall point
as anchors; older page files (`work/annotator_labels`, `work/labels`); and
labels pulled from the training machine (`work/remote_labels`). The file also
records each source (hash, model, how many devices were used and why others
were left out) and the DEVICES legend and page findings. Training reads these
files directly: a page is `<stem>_labels.json#p12`, and a glob of
`devices/*/*_labels.json` works wherever page JSONs were accepted.

One class name per device: the annotator's device list
(`annotator/electrical_device_catalog.json`, 182 devices) is the set of classes
everything uses. `label_schema.canonical_device` maps every source's names onto
it: v7 taxonomy pairs (`fire_alarm/pull_station` ->
`fire_alarm_device.manual_pull_station`), extractor types (`downlight`,
`switch_3way`), legend descriptions. A name with no exact device becomes
`<family>.unspecified` (pending until you pick the device); it is never turned
into a new class, and two different devices are never merged. Mechanical
equipment and non-devices (homerun arrows) are not boxes. Proposals, every save
(annotator, agents, model, imports) and the unify script all go through it;
the old names stay on the box as `raw_device_type`/`raw_subtype`. New source
names are added as catalog aliases, not code.

**One label format, one source of truth.** `annotator/electrical_device_catalog.json`
defines every device and what other sources call it (NECA 100 glyph names,
extracted `*_DEVICES.json` types, names that are not devices). The browser
annotator, the vision-model agents, model pre-labeling, the DEVICES.json
importer and the compositors all go through `blueprint_symbols/label_schema.py`,
which reads that file. A box names its exact device (`symbol_family` /
`symbol_variant`, e.g. `switch` / `three_way`) and the detector learns exactly
that device: class `switch.three_way`. There are no category classes. A box
without a specific catalog device (variant `unspecified`, an old coarse name)
becomes an ignore region in training. Every page carries its verified project
group (`class_maps/page_roles.json`), so train/val/test never split a project.
**Training and evaluation drawings are whatever is in the folders when a run
starts** (`blueprint_symbols/blueprint_sets.py`; no index, cache or held-out
manifest):

| Folder | Role |
|---|---|
| `1_blueprints/*.pdf` | training drawings |
| `1_blueprints/devices/*_DEVICES.json` | their extracted device boxes |
| `1_blueprints/evaluation/*.pdf` | evaluation drawings, never trained on |
| `1_blueprints/evaluation/devices/*_DEVICES.json` | their extracted device boxes |
| any other subfolder of `1_blueprints` | neither set |

* A file name in both folders counts as evaluation.
* Moving a PDF between the folders moves it, and its labelled pages, at the
  next run; label-store documents follow a moved PDF by name and hash (its
  label folder can stay in the old `devices/` folder).
* **Labels live beside the drawings**: `1_blueprints/devices/<stem>_<sha12>/`
  and `1_blueprints/evaluation/devices/<stem>_<sha12>/` (`annotator/label_store.py`);
  agent batches are in `1_blueprints/devices/batches/`. `remote/sync.sh` never
  mirrors these folders (the remote's agents write there); `remote/push_labels.sh`
  sends this machine's labels. `scripts_migrate_label_store.py` copies an old
  flat store (`work/labels`) into them.
* Labeling covers both sets: the store scan, the annotator, the agents and model
  pre-labeling all propose from the DEVICES file in the `devices/` folder
  beside each PDF.
* Post-training (`scripts_prepare_training_data.py --label-store
  <blueprints_and_libraries>/1_blueprints --data-root <blueprints_and_libraries>`)
  trains on labelled pages of training drawings.
* Validation and test are the **fully labelled** pages of evaluation drawings:
  pages marked "every symbol on this page is labeled" or "verified no target
  symbols". Their projects alternate between validation and test, and one
  project serves as both.
* Prep fails until at least one such page exists. Verified symbol-free pages
that repeat an earlier one (same image fingerprint, e.g. a DOT standard sheet
bound into many sets) are kept once. Phase-2 compositing places NECA glyphs,
symbols cut from published legend sheets (`--symbol-library`) and crops of your
verified labels (`--label-store`), sampling devices evenly.

**Legend-sheet symbols:** `scripts_extract_legend_symbols.py` builds
`2_component_libraries/legend_symbol_crops` from the downloaded legends in
`web_symbol_sources` (see its `manifest.json` for each file's licence; most
bid-set legends are internal training data only).
* `auto` cuts the drawing left of each legend line whose text names a catalog
  device. Matching uses the catalog's `aliases.legend` phrases and
  `naming.legend_synonyms`.
* Pass `--tessdata` to OCR scans and stroke-text sheets.
* Every crop needs an `accepted` entry in `review.json` before it is composited.
  `sheets` and `sheets --audit` draw crops in context for review. For charts
  that text matching can't read, `render`, `add` and `claim` let a reviewer
  (or vision agent) box symbols by hand.

**CAD library symbols:** `scripts_harvest_symbols.py harvest` builds
`2_component_libraries/harvested_symbols` (same crops/review format) from every
CAD file (DWG via libredwg `dwg2dxf`), single-symbol image and symbol sheet in
`2_component_libraries`:
* each named block definition is one symbol;
* a symbol sheet is split into separate drawings;
* dimension, centre-mark and annotation layers are dropped;
* identical shapes are kept once.

A device is proposed when the block name or caption names one, and `sheets`
plus `review` accept or reject it.

**Where the symbol libraries are used:**
* **Detector training** composites only crops that review accepted as a catalog
  device (`--symbol-library`).
* **SSL pretraining** (`remote/run_pretrain_v3.sh`, `--symbol-library`,
  `--label-store`) overlays every crop not rejected, labelled or not, plus
  crops of verified labels.
* Each inserted symbol comes from these libraries with probability
  `--library-symbol-share` (default 0.5), each library equally often;
  otherwise it comes from NECA as before.
* The library contents are part of the pretraining run fingerprint.

**Review and safety:** Machine proposals start `uncertain=true`,
`verified_ground_truth=false`, `review_status=unreviewed`. Only an explicit
human Verify promotes a box to a trusted positive, and Verify needs the exact
device (a variant other than `unspecified`). Rejected proposals move to
`rejected_proposals`. A page has three training states:

* default: **positives only**; unlabeled symbols are unknown, never negatives;
* **Every symbol on this page is labeled** (no pending boxes allowed): the page
  is exhaustive for every detector class and can serve validation/test;
* **Verified no target symbols** (no active boxes): a whole-page negative.

Images remain at the original rendered-page pixel coordinates; SAM PDF-point
boxes are converted from PDF bottom-left to image top-left coordinates.
The service listens on the LAN by default (`--host 127.0.0.1` for this machine
only), opens only PDFs directly in `1_blueprints/` or `1_blueprints/evaluation/`,
accepts only Host headers that are private IPs or LAN machine names (never a
public DNS name, so other websites cannot reach it by DNS rebinding), requires
a session token for edits,
writes JSON atomically, and refuses stale revisions, mismatched PDF/image
identities, invalid boxes or duplicate IDs. Rendered pages and JSON live in
the PDF's label-store folder (or `--out-dir`, which puts every PDF's pages in
one folder). Never expose it on a public interface; use an SSH local port
forward to reach a remote machine from outside the LAN.

### Conservative PDF page-presence scanner

For large mixed-discipline PDF sets, generate a **read-only** review queue:

```bash
python scripts_triage_pdf_symbol_pages.py /path/to/construction_pdfs \
  --output work/page_review_queue.json
python -m annotator.web --pdf /path/to/construction_pdfs/drawings.pdf \
  --page-presence work/page_review_queue.json --open-browser
```

Outputs: possible_symbols when electrical/symbol text is detected,
review_required for other content (including raster-only and
architectural/structural drawings), and blank_candidate **only** for a page
without a PDF content stream, embedded images or visible annotations.
These are **unverified suggestions**, not binary truth labels. A blank
candidate still needs human review. Source SHA256 is checked before displaying
suggestions. The scanner never changes normalized annotation JSON or sets
verified_no_symbols=true. A model's failure to detect symbols must not
be used as proof of absence. Reviewed pages can be marked manually using
the editor checkbox. The original source remains usable for SSL
pretraining and synthetic composites regardless of review category.

For supervised synthetic composites, a verified symbol-free **source**
can produce both empty negative and inserted-positive samples.
The generated page's verified_no_symbols is true only when there are
**zero** actual inserted devices; positive composites retain their exact
inserted boxes. The source's negative attestation does not override
the final composite's labels.

The older `scripts_composite_neca_svg.py` and
`scripts_composite_electrical.py` follow the same rule. Unreviewed/partially
annotated PDF backgrounds can still receive generated symbols, but those
insertions add **verified positives only**: the scripts preserve original
`exhaustive_families` without claiming the rest of the page is empty.
Export these derivatives with `--allow-partial-training` so unknown original
symbols are not trained as background. A background explicitly verified empty
may produce exhaustive synthetic-positive samples; `--negative` in the
procedural compositor refuses unverified sources entirely. Source
`verified_no_symbols` becomes false on every final image containing inserts,
while `source_background_verified_no_symbols` records its original state.

## 1. Import and normalize source annotations

Keep source images, annotations, and generated datasets in local storage. Use the
ignored directories in the examples below for inputs and outputs inside this
checkout. The repository includes scripts, test fixtures and format examples.

To reproduce the local snapping demo, run `python scripts_make_demo.py`, then
`python scripts_resolve_snaps.py --annotations examples/demo_annotations.json --walls examples/demo_verified_walls.json --output examples/demo_snaps.json`.

```bash
python scripts_inspect_raw_datasets.py 'downloads/**/*' --out raw_inventory.json

# The CLI adapters do not claim that a dataset contains complete labels.
python scripts_import_svg.py drawing.svg paired.png class_map.json \
    normalized/drawing.json --group ORIGINAL_PROJECT --aligned-verified
python scripts_import_primitives.py primitives.json paired.png class_map.json \
    normalized/cad.json --group ORIGINAL_PROJECT --aligned-verified
python scripts_autolabel_dxf.py drawing.dxf paired.png cad_mapping.json \
    normalized/dxf.json --group ORIGINAL_PROJECT --aligned-verified
python scripts_import_coco.py blueprint_annotations.json blueprint_images/ normalized/ \
    class_map.json --groups original_project_groups.json
```

These are **blueprint source annotations**, not generic YOLO-dataset imports. Mapping keys must match actual source labels. For DXF, supply a verified 3×3 CAD-to-image homography. The `--aligned-verified` flag is **your assertion**, not an automatic image-registration algorithm. COCO bbox labels alone do not provide snapping anchors; CAD circles may supply centers for later use.

For vector sources known to match a verified raster viewport:

```bash
python scripts_render_vector_variants.py normalized/drawing.json drawing.svg variants/ \
    --fractions .75,.5,.25 --aligned-verified
```

All variants preserve the same original project group and rescale boxes/anchors exactly. The renderer does not automatically prove viewport agreement. Review an alignment overlay with `scripts_preview_annotations.py`.

For **raster-only or scanned PDFs without annotations**, rasterize pages for the unlabeled pretraining pool (also suitable as future teacher inputs):

```bash
python scripts_render_pdf_sources.py drawings.pdf pdf_rasters/ \
  --project-group ORIGINAL_PROJECT --dpi 200 --max-pixels 50000000
```

`pdf_rasters/unlabeled_sources.json` retains one verified project ID for every PDF page. The exporter attempts to preserve the native detail of embedded scans without assuming vectors, but caps the rendering size to avoid excessive CPU RAM use. These PDF renders are **unlabeled**, not fabricated detection truth. They can be combined with other verified unlabeled sources before creating an SSL manifest.

## 2. Assert annotation completeness at the dataset level (when established)

Many source datasets contain valid positive boxes but **do not guarantee labels for every target class**. The converters deliberately do not invent an `exhaustive_families` claim.

After verifying that a particular annotation collection really labels **all** intended classes, write separate attested copies:

```bash
python scripts_attest_dataset_coverage.py 'normalized/**/*.json' \
  --families receptacle,light_fixture,hvac_diffuser \
  --output complete_labels/ --assert-exhaustive
```

This is a metadata attestation only; it does not verify the images or invent any missing boxes. It does **not edit** the original annotation JSON. Use matching families across combined datasets only if their labels are truly complete. If a dataset has only some classes labeled, either improve those labels or use it for pretraining; do not blindly declare all classes exhaustive.

## 3. Export train/val/test for rough symbol detection

```bash
python scripts_prepare_training_data.py \
  --annotations 'complete_labels/**/*.json' \
  --output prepared/blueprints \
  --train-tiles-per-page 32 --train-variants 4 \
  --stress-val --stress-test

python scripts_verify_yolo_export.py prepared/blueprints/yolo
python scripts_preflight_training.py prepared/blueprints/source_manifest.json
```

The exporter produces `source_manifest.json`, `preparation_report.json`, YOLO `data.yaml`, `images/{train,val,test}`, matching labels, per-tile provenance, `evaluation_configs/`, and `export_report.json`.

When incorporating *new* verified drawings or reviewed teacher labels after selecting a locked test set, build another export from **all previous annotations plus the new ones**, preserving the original partitions:

```bash
python scripts_prepare_training_data.py \
  --annotations 'all_labels/**/*.json' \
  --output prepared/expanded \
  --locked-manifest prepared/blueprints/source_manifest.json \
  --train-tiles-per-page 32 --train-variants 4 --stress-val --stress-test
```

A changed image or annotation requires a deliberate new dataset snapshot; v8 will not quietly reuse stale provenance hashes. Keep a stable original project ID for every new variant. Incomplete teacher pseudo-labels still require an explicit exploratory override and are **not** automatically promoted to gold labels.

The four training variants are: (1) original-resampled clean crop, (2) low-resolution-only crop, (3) randomly combined damage, and (4) optional contrast-cleaned crop. Change `--train-variants` to 3 for lower storage. Real scans/photos remain necessary: simulated damage alone is not real-world evaluation. Held-out stress includes downsample ratios 75%, 50%, 25%, independent blur/JPEG, stroke thinning/thickening, cleaned contrast, and compound degradation. Stroke morphology uses centered odd-size kernels to avoid intentional half-pixel shifts.

## 3a. Mixed real, synthetic and partially annotated phase-two training

The supervised dataset can combine normalized annotation JSON from:
- fully reviewed real pages with positive symbols (exhaustive_families covers
  every active class);
- completely reviewed real negative pages (instances=[]; verified exhaustive
  for every active class);
- synthetically inserted devices on independently verified symbol-free
  architectural backgrounds (only known placements are positives);
- verified symbol-free source crops without inserted devices;
- **partially annotated real pages**, where existing labeled positives are
  trusted, but unlabeled regions may contain more devices.

**Never infer label completeness from the absence of a bounding box.**
For a partially annotated source, exhaustive_families must list *only* target
classes proven to be exhaustively labeled over that source raster. An empty
list is valid: known boxes still train, but missing detections are not
punished as background. Uncertain/review-required candidate boxes are not
positive truth. Existing manual label tool output may be partial.

For example: one real page with manually confirmed receptacle boxes but
other unlabeled receptacles has exhaustive_families=[]. A distinct fully
reviewed symbol-free page may set `"verified_no_symbols": true` and
`"instances": []`; the manifest and tile exporter automatically expand its
negative coverage to the trained class map. Other verified negatives may still
use class-specific `exhaustive_families`.
Do not make completeness claims solely because a sheet is called
"architectural" or "negative" in its file name.

```bash
python scripts_prepare_training_data.py \
  --annotations 'normalized/real_verified/**/*.json' \
                'normalized/real_partials/**/*.json' \
                'generated/wall_phase2/wall_*.json' \
  --output prepared/phase2_mixed \
  --allow-partial-training \
  --train-tiles-per-page 32 --train-variants 4

python scripts_train_ultralytics.py prepared/phase2_mixed/yolo/data.yaml \
  --weights work/blueprint_ssl_backbone.pt --batch 2
# add --run only on the CUDA training machine after preflight
```

When --allow-partial-training is set:
1. Original projects with *any* partially annotated page are forced into
   train. Validation and locked test require independently verified fully
   annotated source projects, including verified empty real pages.
2. The exporter emits normal YOLO positive labels **plus**
   yolo/phase2_supervision.json, mapping every train tile to class IDs for
   which negative/background labels are independently verified.
3. scripts_train_ultralytics.py detects this sidecar and automatically uses
   the tested Ultralytics 8.4.216 YOLO26 class-masked loss (both one-to-many
   and one-to-one heads). Assigned verified positive scores always train.
   Unknown background anchors/classes have **zero negative gradient**.
4. Incomplete tiles containing neither a verified positive nor any
   exhaustive class are skipped. Real/synthetic negatives and confirmed
   positives continue to train normally.
5. YOLO geometric mosaic/mixup/cutmix are not permitted in this mode,
   because they would invalidate image-level supervision masks. Existing
   coordinate-preserving pre-export degradations remain enabled.

The per-class mask is *image-level*. Region-level verification inside an
incomplete class is not yet exploited as negative supervision; this
conservative choice protects unlabeled devices from false-negative gradients.
Fully annotated negative pages and synthetic exact-placement pages provide
the needed hard-negative signal.

The generated mixed dataset is still **not sufficient evidence** of detector
precision/recall. Evaluate on fully audited independent real projects and
full-page fused detections; keep incomplete real pages for training or
unlabeled/qualitative tests rather than numerical mAP.

## 3b. Phase-two supervised wall-only positives and hard negatives

Verified wall-only drawings provide separate *detector* supervision: some
views receive inserted symbols, while others intentionally remain empty.
**Only inserted glyphs receive detection boxes**; the original walls, doors,
text, and non-device geometry are not target instances.

Use scripts_generate_phase2_wall_only.py rather than SSL negative-control
buckets. An SSL negative tag only suppresses synthetic insertions during
pretraining; it is not proof that the underlying page has no device. Every
input source must attest absence of **all active target families** via
verified_negative_families, and must have verified original-project grouping.
Do not treat partially annotated electrical/MEP pages as verified negatives.

Example wall-only source manifest (paths relative to the manifest):

```json
{
  "sources": [
    {
      "image_path": "walls.png",
      "project_family_id": "original-building-A",
      "source_group_verified": true,
      "verified_negative_families": [
        "fire_alarm", "junction_box", "luminaire", "other_device",
        "receptacle", "switch_sensor"
      ]
    },
    {
      "source_pdf": "wall-only.pdf",
      "pdf_page_index": 0,
      "project_family_id": "original-building-B",
      "source_group_verified": true,
      "verified_negative_families": [
        "fire_alarm", "junction_box", "luminaire", "other_device",
        "receptacle", "switch_sensor"
      ]
    }
  ]
}
```

```bash
python scripts_generate_phase2_wall_only.py verified_wall_sources.json \
    generated/wall_phase2 --copies 8 --empty-fraction .30 \
    --max-symbols 32 --class-map detector_classes.json \
    --reserved ../work/heldout_real_pdfs.json \
    --neca-root /path/to/neca_svg_catalog

python scripts_prepare_training_data.py \
    --annotations 'verified_device_labels/**/*.json' 'generated/wall_phase2/wall_*.json' \
    --output prepared/phase2 --train-tiles-per-page 32 \
    --verified-wall-negative-fraction .65 --verified-wall-blank-fraction .12
```

The NECA SVG catalog is optional; procedural glyphs work without it. The
class map can be a JSON list or name-to-ID dictionary of active model
classes, including the six canonical generated classes. Every source's
negative-family attestation must cover the whole map. The reserved manifest
rejects listed evaluation project groups and PDFs, with its relative paths
resolved against the reserved manifest's own parent folder.

The generator independently selects positive/empty modes; with two or more
copies and nontrivial empty fraction, it preserves both modes per source.
Coordinate-preserving damage happens **after** compositing. Exact generated
boxes, classes, original source project, crop region, and actual PDF render
DPI are recorded. A failed symbol insertion is skipped, **not** silently
converted to an empty negative. generation_report.json records counts.
The detector exporter retains more verified nonblank wall-only negatives
than incidental empty crops but limits near-blank examples.

Keep original drawing/project groups together across train/val/test.
Do not count synthetic copies as independent real evaluation samples, and
never override completeness checks for unrelated partially annotated pages.

## 4. Optional: use the huge **unlabeled training** pool

The package includes a lazy data generator for eventual self-supervised *blueprint-only* pretraining. It samples train-source drawings **and** separately verified unlabeled projects while excluding the validation/test groups and exact duplicate held-out raster hashes. It generates independent clean and damaged **pairs on demand**: no need to store many replicated megapixel images.

```bash
python scripts_prepare_ssl_manifest.py prepared/blueprints/source_manifest.json \
    prepared/ssl_sources.json --unlabeled verified_unlabeled.json \
    --preview-dir prepared/ssl_preview
```

`verified_unlabeled.json` is an array of objects containing `image_path`, `project_family_id`, and `source_group_verified: true`. Image paths may be relative to that JSON. The dataset class is `blueprint_symbols.ssl_data.SSLViewDataset`; call `set_epoch()` if/when implementing your own SSL trainer. That command is a legacy **paired-input preview**, not a trainer. The active runnable SSL trainer is `scripts_pretrain_ssl.py` (below). Its actual GPU behavior and improvement over scratch-only detector training must be measured; a pre-training loss alone is not evidence of detection quality.

## 4a. Current mixed-source SSL pre-training (fresh random initialization)

The implementation in `blueprint_symbols/ssl_pretrain.py` wraps a randomly
initialized YOLO26n-P2 backbone and neck with a reconstruction decoder. It
uses real construction drawings, CAD-derived layouts, optionally verified
device-box annotations for crop targeting, NECA 100 vector SVG glyphs, and
procedural electrical glyphs. None requires COCO or ImageNet weights.

**Data sampling:** The training PDFs (`1_blueprints/*.pdf`) are listed page by
page when pretraining starts, about a minute for ~900 PDFs; nothing is indexed.
All other sources still train as before (pool images, scans, datasets, CAD
layouts); only pool renders of a training PDF (already read directly) or of an
evaluation drawing are skipped. The image datasets are also listed from their
folders at start: every clean PNG in `5_datasets/floorplancad_full/extracted`
(train and test archives; `coco_vis` never), every slice in
`5_datasets/archcad_400k/extracted/png` (ArchCAD, ~41k CAD plan slices), and
every floor (`F*_scaled.png`) of the CubiCasa5K train plans. Neither
FloorPlanCAD's nor ArchCAD's own splits matter here, since the detector is
evaluated on `1_blueprints/evaluation` only. A run stopped to add data can be
restarted warm with `SSL_INIT_WEIGHTS=<old run>/last_pretrain.pt`.

**What each dataset holds, and its flag** (reviewed 2026-11-25; none has
electrical labels, so none adds detector labels of its own):

| Source | Content | Flag |
|---|---|---|
| ResPlan, CubiCasa-HF, HouseExpo layouts (`work/pool/background_layouts`) | walls and openings only, drawn by `adapters/render_layouts.py` | **walls only, proven** |
| ArchCAD / FloorPlanCAD drawn from their line labels (`blueprint_symbols/vector_walls.py`) | only wall, column, beam, grid and window lines kept; ArchCAD piles and FloorPlanCAD IDs 3-6, 11, 12, 19, 35 left out | **walls only, proven** |
| ArchCAD, FloorPlanCAD, CubiCasa5K rasters | doors, fixtures, furniture, text and 40% unlabelled linework; ArchCAD parking bays carry EV-charger symbols | may contain symbols |
| Training PDFs and pool pages | real sheets, many electrical | may contain symbols |

FloorPlanCAD's class IDs in this release do not follow the published order
(1 = wall, 2 = curtain wall, 9 = window, 35 = parked cars); they were
identified by drawing each one. Walls-only buckets carry `"symbol_free": true`
in the manifest and get the heaviest mutation: never a clean view, a page warp
on every sample at 1.5x strength, twice the added symbols with
always-distorted shapes, a new drawing style per draw (scale, pen weight, ink,
paper) and `mutate_linework` (thicker or thinner lines, scan dropouts, pen
colour, ghosting, paper tint).

In post-training (`run_phase2_train.sh`) real training-PDF pages stay the
dominant composite background (350 pages). 150 walls-only pages give
completely labelled composites: every symbol on them is one that was placed,
so the rest is a true negative. 60 other dataset images stay positive-only
unless listed in `<data root>/symbol_free_images.txt` (one path per line,
relative to the data root), the hand-flag list. The SSL loader opens each source PDF lazily, caches at most
three document handles and 96 tiny density thumbnails per worker, and renders
only randomized crop rectangles at the selected training scale. It does **not**
first rasterize each sheet at 150 DPI and downsample a 24 MP page to 512px.
For each 512px training crop, randomly sample the **true PDF rasterization
resolution uniformly between 100 and 400 DPI**, then compute its physical
source clip size as `512 × 72 / DPI` PDF points. At 100 DPI the crop
covers about 5.12 inches on a sheet; at 400 DPI it covers 1.28 inches.
For smaller pages, restrict the lower bound just enough for the square to
fit; reject a page if it cannot fit even at 400 DPI. The selected rectangle
is rendered directly at that DPI; any final rounding to 512 pixels is at
most the pixel-grid alignment of the PDF rasterizer. Mixed density-guided
and uniform/stratified positions, fresh rotations, and degradations generate
many distinct views from each available page. Original image/scan
sources retain a bounded 96 MB LRU cache per worker. Source groups are
weighted by 45% uniform plus 55% sqrt(page count), allowing longer sets more
views without overwhelming small projects.

The remote SSL launcher now **skips full-page PDF rasterization** in the
generic pool builder; other programs retain its previous behavior. Earlier
pool PNGs from the same indexed PDF are skipped to prevent double weighting.
The dataset now deliberately **reuses the same physical source region**
across multiple independent SSL views (default 4, configurable 1–32).
Crucially, each PDF view chooses its **own randomized real rendering DPI**
within 100–400. The four default views use independently randomized DPI
values from different subranges to cover the full range, centered on the
same source location in PDF coordinates. On subsequent epochs, each source
region receives **new random DPI values**, not a permanently fixed set of
four. The visible physical extent changes with DPI; the background is not
a duplicated 512px raster. Inserted symbols
receive that view's exact DPI and are independently randomized along with
rotations, warps, masks, scan noise, and clean/degraded branches. Raster-only
scans without trustworthy source DPI continue to reuse pixels without
inventing a PDF scale. Each page/image has a stable spatially stratified crop
bank (default 32), reused on later epochs with fresh augmentations. About 20% of source bursts explore a new
crop site instead of a bank slot. Source pages continue to cycle without
replacement between bursts, and repeat visits cycle through spatial slots
rather than randomly resampling the same slot while others are unvisited.
Bank slots form a page-aspect-ratio-aware grid, with ink-guided selection
inside each region. The effective MuSGD batch remains 14. Per-worker
LRU caches hold at most 32 rendered PDF crops and 24 MiB of base raster
crops, with independent copies returned to augmentations. No full-page
pre-rendering or on-disk cache is required.

For visual validation on a real PDF, use the QA-only command:
```bash
python scripts_audit_real_pdf_crops.py /path/to/electrical.pdf \\
  --pages 33 34 --samples-per-page 4 --repeat-region-dpis 4 \\
  --max-symbols 8 --out work/real_pdf_multi_dpi_qa
```
The four side-by-side image pairs for each page share one physical PDF
anchor, but their source clips are independently rasterized at different
randomized 100–400 DPI scales. `--dpi-epoch 1` redraws new random scales
without changing the anchor. The JSON report stores each actual DPI, source
clip, source anchor and composited symbol DPI. QA runs are **not** training
labels and do not alter the reserved PDF evaluation collection.

Tuning: `SSL_CROP_REUSE_VIEWS`, `SSL_CROP_BANK_SIZE`, and
`SSL_FRESH_CROP_PROBABILITY` control those distributions; the corresponding
CLI flags are `--crop-reuse-views`, `--crop-bank-size`, and
`--fresh-crop-probability`. A larger repeat count trades unique pages per
epoch for more real DPI variants and randomized augmentations per sampled
location; it also increases the number of PDF renders per source burst. Preview
reports unique clean crops, repeated views, bank grid coordinates and cache hits. Any change
invalidates the run fingerprint.

Verified device-centered raster crops remain available. Existing receptacles,
labels and other symbols remain in the original image and are valid **SSL**
training content. New glyphs are additive and their generated boxes are
tracked only to protect augmentation and support future review. Existing
devices have **no inferred boxes or negative labels**. The later supervised
Detect-head phase must merge independently verified real annotations and
synthetic positives while enforcing completeness rules appropriate to
detection; these SSL crop metadata are not exhaustive ground truth.

Evaluation drawings (`1_blueprints/evaluation`) never enter pretraining, nor do
pool renders or labelled crops of them. The separate 10% group-hashed SSL
validation monitor is drawn from training drawings and is **not** the
evaluation set.

**Symbol augmentation:** When enabled, each selected crop receives a uniformly
sampled *requested* count of 0–16 new symbols (actual placements may be lower
in cluttered regions). SVG and procedural symbols are randomly scaled, rotated,
wall-adjacent or freestanding, with class-aware mounting preferences:
switches and ordinary receptacles favor wall alignment; ceiling fixtures
and floor receptacles prefer free space. They are inserted **before degradation** and
thus appear in both the uncorrupted target and degraded model input. Existing
symbols on original drawings are retained. The eligible NECA SVG bank contains
the verified device sections, not routing arrows or standalone modifiers.
The prior procedural glyph families supply additional variation.

**DPI and ink consistency:** For PDF-native crops, both the NECA vector
glyphs and procedural electrical symbols receive **the same sampled 100–400
DPI** as the crop. Their apparent physical sizes use a common 200 DPI
reference and scale by `sampled_dpi / 200`; they are clamped to fit at most
38% of the crop's shorter side, and the normal placement checks reject
wall overlaps, crowded regions, and off-page devices. This reference does
**not** assert that the source SVGs have dimensionally calibrated physical
device sizes. Each inserted symbol also samples nearby **original drafting
line color** (including tinted blue/grey ink) rather than defaulting to
black or using ink from previously composited symbols. Procedural ink
weight is DPI-scaled with the symbol; independent fractional line-width
randomization still applies. Raster images without known DPI do not
receive a fabricated DPI label. Preview JSON reports DPI bins, symbol
DPI mismatches, and distinct matched ink colors. These rendering changes
invalidate old SSL checkpoint fingerprints and require a new run.

**High-entropy symbol deformation (PR #9):** Each inserted device independently
samples 1–4 geometric/print perturbations, with a default 0.88 activation
probability. Effects include independent X/Y stretching, two-axis shear,
rotation and keystone, elastic control-point meshes, crossing horizontal and
vertical waves, folds, radial pinch/bulge with twist, stroke erosion or expansion, fractional **per-symbol line-width**
variation, dry-pen dropouts, ink misregistration, low-DPI print, fading and
defocus. Small/heavy/rare-extreme severity levels are sampled per symbol.
Bounds and ink-retention guards reject severe collapse, but **human-recognizable
class identity is not guaranteed** by a pixel-area test.

**Whole-page geometric chaos:** After placing symbols, the entire image
(background drawing **and** the inserted symbols) is independently warped with
70% probability by default. Supported modes: anisotropic page stretch/shear
and skew, quadrilateral perspective, smoothly elastic deformation, independent
horizontal/vertical waves, paper-edge curling, fold creases, barrel/pincushion
lens distortion, local wrinkles, and narrow scanner-feed slippage. Up to
three independent modes are composed. All stages preserve output dimensions;
extreme clipping/ink loss rejects a stage. Newly inserted symbol bounding boxes
are transformed with a matching instance-ID mask, so preview boxes remain
aligned with warped positions. Stage rejection checks cover box area,
**actual symbol ink**, and local Jacobian foldovers; preview reports include
rejection reasons and lowest measured retention. The warped drawing is the
SSL *clean target* for augmented views; input-only scan degradation happens
afterward. Whole-page effects do not create fake supervised labels.

**Placement chaos:** The wall-attached/free-space mix varies by crop. Free
placements can be uniform, near corners/edges, clustered near other devices,
near the drawing center or in horizontal/vertical bands, while the existing
wall clearance, ink density and collision checks still decide acceptance.
Original supervised compositor behavior is unchanged unless chaos is enabled.

**Pre-training objective:** Mask-aware line-art restoration using separately
normalized dark-ink and background losses, combined 2:1 by default, plus
a Sobel edge term. A 1%-ink drawing no longer gets a nearly all-white
reconstruction objective. This is a **learning-signal correction**, not
evidence that downstream object detection has improved.

**Degradation:** 20% end-to-end uncorrupted source-geometry views by default
(except optional sharp synthetic symbol insertion). The clean decision is
sampled before all warps: no scan noise, symbol deformation, geometric skew,
or page deformation applies to these examples. Other views independently
sample light, moderate, heavy, and rare extreme corruption. Categories include
illumination/paper tint and gradients, Gaussian/motion blur, sharpening,
median/box filtering, thresholding/CLAHE, downsample/upsample aliasing, toner
morphology, ink dropout/dust, scanner streaks, Gaussian noise, bit-depth
banding, JPEG/WebP artifacts and optional masked blocks. Extra whole-page
photocopy/scan degradation includes gamma drift, local shadows and glare,
fold creases, roller banding, moiré patterns, halftone dots, double-fed toner
ghosts, toner bleed, feed scratches, local smears and asymmetric X/Y DPI loss.
Bloom and contrast are now independently mixed: variable global contrast gain,
toe/shoulder curves, local exposure-dependent contrast, localized light-scatter
bloom and occasional dark-ink halation. These effects may co-occur with each
other and with existing gamma, glare, photocopy, blur, noise and compression.
Intensity and selection vary by corruption severity. They act only on the
degraded SSL input, not the clean target or the source document; hard cases
remain bounded by the existing original-line contrast rescue. This is a
realism/robustness hypothesis, not a demonstrated accuracy gain.
The input-only degradation stage retains a minimum line-contrast guard and
caps MIM-block masking at 60% of every **known inserted symbol box** (unknown
original devices cannot be protected this way). It does not reverse all
damage or guarantee symbol readability. Whole-page geometry
is applied to both target and input **before** image degradation.

**Hard-negative controls (text, walls, sparse, blank):** Source entries
may set `negative_control_kind` to `text_heavy`, `walls_only`,
`sparse_lines`, `blank`, or `non_device_geometry`. These must be
**fixed raster crops**, not a random PDF page/variable-DPI viewport:
the loader rejects false page-level negative labels. Controls explicitly
disable synthetic symbol insertion while preserving optional geometric and
scanner degradation. An arbitrary PDF page may use
`suppress_synthetic_symbols: true` to disable insertion without declaring
the page a reviewed negative.

To collect candidate controls from real construction PDFs, pass exact PDF
top-left viewport coordinates to `scripts_audit_negative_controls.py`:

```bash
python scripts_audit_negative_controls.py work/negative_regions.json \\
  --out work/negative_review --dpi 200 --size 512
```

These images are **visual QA candidates**, not certified detector negatives;
the manifest explicitly sets `detector_negative_verified: false`. Only an
independently exhaustively reviewed **exact raster** can be marked verified
for the later Detect-head phase. The 100-PDF locked evaluation collection
must remain separated from training. To measure unwanted device predictions
after a detector checkpoint exists, supply one predictions record for each
reviewed control to `scripts_score_negative_controls.py`. Unreviewed crops
cannot be scored as false-positive ground truth without
`--diagnostic-only`, which reports uncertainty. No detector false-positive
rate can be claimed from SSL augmentation alone.

**Faint drafting ink:** adaptive paper-relative line-contrast rescue now
covers pale gray/blue wall strokes when bloom or contrast curves otherwise
bleach them. This avoids the previous fixed `gray < 185` threshold that
could miss weak original linework.

For the remote 6 GB NVIDIA machine, start a **new** experiment (no prior
weights and no automatic resume):

```bash
bash remote/run_pretrain_v3.sh
```

For a **short CUDA smoke test**, use a separate output directory rather
than overwriting the full experiment:

```bash
SSL_OUT="$HOME/Projects/blueprint-symbols/work/pretrain_smoke_v3" \
SSL_EPOCHS=1 SSL_ITEMS_PER_EPOCH=28 SSL_VAL_ITEMS=14 \
SSL_BATCH=14 SSL_MICROBATCH=2 \
  bash remote/run_pretrain_v3.sh
```

An interrupted, otherwise unchanged run can be resumed with
`SSL_RESUME=1 bash remote/run_pretrain_v3.sh`. Different dataset
manifests, model objectives, and training options deliberately fail
resume fingerprint checks. The runner regenerates the device-positive
annotation index on each invocation so newly annotated pages are eligible.

This runner imports existing device annotations as *positive-only crop hints*,
loads the NECA SVG library, and generates 0..16 symbols per augmented crop.
CLI tuning flags: `--clean-fraction`, `--max-symbols`,
`--symbol-min-px`, `--symbol-max-px`,
`--symbol-augment-probability`, `--procedural-fraction`,
`--labeled-focus-probability`, `--labeled-annotations`, `--neca-dir`,
`--symbol-chaos-probability`, `--symbol-chaos-strength`,
`--placement-chaos`, `--page-chaos-probability`, `--page-chaos-strength`.
The remote runner forwards `SSL_SYMBOL_CHAOS_PROBABILITY`,
`SSL_SYMBOL_CHAOS_STRENGTH`, `SSL_PLACEMENT_CHAOS`,
`SSL_PAGE_CHAOS_PROBABILITY`, and `SSL_PAGE_CHAOS_STRENGTH`.
These settings enter the resume fingerprint; changing them requires a
new run directory.
**Pretraining settings (YOLO26n-P2):** after the reported batch-16
capacity limit, use **effective/optimizer batch 14**, **physical GPU microbatch
2**, and **seven accumulation passes** per MuSGD update. MuSGD remains the
default; the detection head stays frozen, while backbone/neck and decoder
train. Images remain 512×512, LR `0.005`, momentum `0.937`, weight decay
`0.0005`; 60 epochs × 8,190 samples = **35,100 optimizer updates**.
Three warmup epochs precede cosine decay. CUDA uses FP16 autocast and
`GradScaler`, unscales/clips gradients once per logical 14 samples.
Validation uses microbatch 2, preserving VRAM.

The reported inability to fit 16 is not sufficient to establish that
**14 physical images** fit the 6 GB RTX 2060 with this full-resolution SSL
decoder. The effective batch is 14; the actual allocation remains two
images per pass. Reduce `SSL_MICROBATCH` to 1 if required (still 14 optimizer
samples). BatchNorm behavior changes with physical microbatch size, so
accumulation is not numerically equivalent to a physical batch of 14.
MuSGD suitability, learning rate and loss schedule for this specific SSL
objective still require CUDA measurement and a downstream ablation.
Prior batch-16 checkpoint runs cannot be resumed under the new settings;
start a fresh output directory.

Before running a long GPU job, inspect what the generator actually creates.
After a manifest has been produced, run:

```bash
python scripts_preview_pretrain_ssl.py ../work/pretrain_v3/train_manifest.json \
  --neca-dir ../data/blueprints_and_libraries/2_component_libraries/neca_100_2013_svgs/NECA_100_Vector_Library \
  --out ../work/pretrain_v3/previews --samples 32
```

Each preview shows the clean target beside its degraded/masked input.
`preview_report.json` counts **requested versus actually placed** symbols,
including shortfalls on crowded pages, zero-symbol examples, symbols by
family/variant, eligible NECA vocabulary coverage, observed PDF source/page/crop counts,
PDF crop DPI range, generator p50/p95 latency, and degradation
severities/categories, accepted/rejected whole-page warp modes,
symbol-level warp categories and placement modes. A separate
`sample_NNNN_overlay.png` marks inserted symbols in their new warped
positions (red boxes for independently distorted symbols).
`sample_NNNN_geometry.png` separately shows the symbol-composited crop
before and after whole-page warping. Review these alongside the clean/degraded
scan pairs to spot illegible symbols and excessive page distortion.

Each pull-request CI run generates a deterministic **synthetic-only**
16-crop audit as the `blueprint-chaos-visual-audit` GitHub Actions artifact,
with montage images, detailed samples and JSON counts. It exercises the
real generator, but does not establish readability on the real-plan corpus.

For the second-stage supervised detector, **preflight actual weight transfer**
rather than trusting that `model.load_state_dict` before `model.train` worked.
Ultralytics recreates the training model. The launcher now loads SSL weights
in the subclassed `DetectionTrainer.get_model` and checks all backbone and
neck tensors. The six-class Phase 2 launcher defaults to `pretrain_v3` and
checks a real YOLO26n-P2 model without GPU training first:

```bash
python scripts_verify_ssl_transfer.py \
  ../work/pretrain_v3/best_yolo26n_p2_backbone.pt --classes 6
```

The clean/augmented SSL generator does not change supervised YOLO exports.
Random initial weights are used for SSL itself; only our own SSL checkpoint
is eligible for detector initialization. Actual GPU pretraining, downstream
detector accuracy improvement, and target-phone deployment still require
measurements.

## 5. Optional: specialist teachers, held-out scoring, and training

The local YOLO teacher proposer supports a class-name adapter for models with a different blueprint vocabulary (e.g. SkeySpot) without forcing its classes to equal ours:

```bash
python scripts_propose_labels.py --manifest prepared/blueprints/source_manifest.json \
  --unlabeled verified_unlabeled.json --weights /path/to/local_specialist.pt \
  --teacher-class-map teacher_to_our_class_names.json \
  --output-dir review_candidates
```

`teacher_to_our_class_names.json` maps teacher class names to our class names, e.g. `{ "TEACHER_RECEPTACLE": "receptacle" }`. A teacher's unmapped categories are **ignored**, not guessed. This path currently supports a YOLO-compatible checkpoint, not CircuitNet's custom ONNX head. It only produces untrusted review candidates, **not training-ready gold labels**.

Inspect the training configuration without executing it:

```bash
python scripts_train_ultralytics.py prepared/blueprints/yolo/data.yaml \
  --imgsz 512 --batch 2 --epochs 300 --optimizer MuSGD
```

**Only on your own NVIDIA 6 GB machine, after checking class completeness:**

```bash
python scripts_train_ultralytics.py prepared/blueprints/yolo/data.yaml --run
```

This creates `YOLO('yolo26n-p2.yaml')` with **random initialization** (`pretrained=False`), no COCO/Objects365 weights, and no generic dataset. Batch size 2 and 300 epoch maximum are **untested starting settings**, not established optimal run length, VRAM compatibility, or accuracy. Compare scratch-training and later blueprint-only-pretrained weights when you actually have results.

After training, run full-page tiled inference and scoring, using **validation** for tuning and the locked **test** only for the final measurement:

```bash
python scripts_predict_full_pages.py prepared/blueprints/source_manifest.json \
  path/to/your_best.pt val_predictions.json --split val --device 0
python scripts_score_full_pages.py prepared/blueprints/source_manifest.json \
  val_predictions.json --split val
```

This is a planned machine-side inference step, not device NPU benchmarking. The full-page evaluator reports per-class recall/precision and bounding-box center localization errors, but **bbox center is not the physical snapping anchor**. The precision model/line center fitting is intentionally deferred.

## Validation status and limitations

- v8 previously reported 65 passing tests for the original data prep. New SSL augmentation tests have been added but are **not yet executed** in this review. Existing tests use synthetic fixtures, not measured drawing-set accuracy.
- Existing older experimental precision/keypoint scripts remain in this prototype but are **not part of the v1 training path**. Do not run them for this task.
- Not tested: actual FloorPlanCAD/ArchCAD/CubiCasa dataset releases at scale, model training, trained-detector inference, teacher checkpoint weights, 6 GB GPU memory, mobile conversion, NPU latency, real-world symbol recall, and subpixel snapping precision.
- **A 20px symbol may not contain enough information to resolve its exact class in severe scans.** Report accuracy by observed symbol size, source collection, and damage level. Preserve failure cases rather than treating unknowns as correctly recognized.
