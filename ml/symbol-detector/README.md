# Blueprint symbol detection — data preparation and audit v8

**Status:** Implements the dataset pipeline and a scratch-initialized YOLO26n-P2 **training launcher**. It has **not trained a model**. Only the rough-location detector is in scope: no OCR, keypoint model, or snapping-refinement training.

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
          → lazy, paired clean/degraded image views for blueprint-only SSL pretraining
Optional: specialist YOLO teacher → untrusted candidate labels for REVIEW ONLY
```

**Source images are never resized wholesale to 512px.** Tiles use continuous source-image coordinates. Their *effective symbol size* is deliberately varied, especially 16–32px, with some harder 10–16px examples. Input pages can be any size or aspect ratio. Crops are resampled to the detector's fixed tensor shape because the mobile NPU needs a predictable graph. Lossy simulated downsampling is applied *after* coordinate-preserving extraction. True low-DPI SVG/PDF re-renders are also supported when geometry/raster alignment is verified.

### Data and split safeguards

- **Group before augmentation.** All clean/downscaled/filtered re-renders of one original plan remain in a single train/validation/test partition. Validate source families manually; an arbitrary per-file ID is not proof of independence.
- **Protect rare classes and locked evaluations.** Reserve enough source groups for training to contain every mapped class. If impossible while retaining val/test groups, fail rather than producing unlearnable classes. For later training expansion pass `--locked-manifest` to preserve prior held-out groups, existing class IDs and unchanged source snapshots; newly added groups go to training. Warnings report missing class coverage in evaluation groups.
- **Verify image labels.** Validate image dimensions, finite in-bounds boxes, optional unique instance IDs, class IDs, split provenance, exported tile geometry, and files not listed in the manifest. Source **annotation and image SHA256** hashes are rechecked before export and full-page evaluation, so edited labels cannot silently change after splitting. Exact duplicate image bytes across different project IDs are rejected; duplicated annotations of identical rasters inside one group also fail until merged. Perceptual near-duplicates from differently encoded/rendered versions are **not** guaranteed to be caught; group them by original source.
- **No silent background labels.** A dataset can label valves thoroughly while not labeling receptacles. Standard YOLO detection loss has no per-image class-exhaustiveness mask: treating those missing receptacles as negatives is a defect. Consequently the normal exporter requires `exhaustive_families` covering **all active model classes** on each source. Unmapped, uncertain, or clipped symbol regions cause crops to be skipped. `--allow-unverified-completeness` is **exploratory only** and can create false negatives.
- **Held-out coverage gate.** A validation/test export fails if its usable tiles omit an otherwise labeled source instance. Overlapping tiles still duplicate some objects; for proper recall and localization evaluation, use fused **full-page** predictions and `scripts_score_full_pages.py`.
- **No pseudo-label promotion.** Teacher output remains `uncertain`, with `review_required: true`, until quality checks/verified completion are established. Teacher agreement or confidence alone is not verified truth.

## Installation

```bash
# CPU-side data preparation and source import:
python -m pip install -r requirements.txt
# Run all regression tests, including optional SVG / DXF / PDF adapters:
python -m pip install -r requirements-test.txt
python -m pytest -q tests
```

Install `ultralytics` separately on your 6 GB NVIDIA training machine. Ensure its release has `yolo26n-p2.yaml`; the P2 model is official, with stride-4 small-object detection. Install a CUDA-enabled PyTorch build for that GPU. The package does **not** download generic pretrained weights. See [Ultralytics P2 model YAML](https://github.com/ultralytics/ultralytics/blob/main/ultralytics/cfg/models/26/yolo26-p2.yaml).

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

## 4. Optional: use the huge **unlabeled training** pool

The package includes a lazy data generator for eventual self-supervised *blueprint-only* pretraining. It samples train-source drawings **and** separately verified unlabeled projects while excluding the validation/test groups and exact duplicate held-out raster hashes. It generates independent clean and damaged **pairs on demand**: no need to store many replicated megapixel images.

```bash
python scripts_prepare_ssl_manifest.py prepared/blueprints/source_manifest.json \
    prepared/ssl_sources.json --unlabeled verified_unlabeled.json \
    --preview-dir prepared/ssl_preview
```

`verified_unlabeled.json` is an array of objects containing `image_path`, `project_family_id`, and `source_group_verified: true`. Image paths may be relative to that JSON. The dataset class is `blueprint_symbols.ssl_data.SSLViewDataset`; call `set_epoch()` if/when implementing your own SSL trainer. The SSL objective, decoder, and pretrained backbone transfer are **not implemented**; do not claim two-stage training is ready to run just because paired inputs exist.

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

- 65 tests passed in v8 before final packaging; covers source import fixtures, rarity-safe source splits, image and label preflight, drop detection in held-out tiles, paired degradation, annotation completeness attestation, and teacher mapping. Tests are **synthetic fixtures**, not measured drawing-set accuracy.
- Existing older experimental precision/keypoint scripts remain in this prototype but are **not part of the v1 training path**. Do not run them for this task.
- Not tested: actual FloorPlanCAD/ArchCAD/CubiCasa dataset releases at scale, model training, trained-detector inference, teacher checkpoint weights, 6 GB GPU memory, mobile conversion, NPU latency, real-world symbol recall, and subpixel snapping precision.
- **A 20px symbol may not contain enough information to resolve its exact class in severe scans.** Report accuracy by observed symbol size, source collection, and damage level. Preserve failure cases rather than treating unknowns as correctly recognized.
