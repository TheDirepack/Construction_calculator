# Roadmap

The app is a single multi-mode toolbox for field electricians and builders. Each mode is a
**tool module** (`:feature:<name>`) registered in `:app` → `ToolRegistry`; see
[ARCHITECTURE.md](ARCHITECTURE.md) for how tools share the calculator engine, values, projects and
settings.

See [Requirements and acceptance checklist](REQUIREMENTS.md) for the original scope and current implementation status.
See [Master Design Specification](plans/master-specification.md) for the definitive product and UX specifications established during the comprehensive product alignment review.

## Foundations (v0.2, done)

- Multi-module build with convention plugins; pure-Kotlin engine (`:core:measure`), model
  (`:core:model`) and electrical tables (`:core:electrical`)
- Tool API (`ToolModule` / `ToolHost`): one drawer, one look, one back stack
- Projects → runs → items from any tool; saved as one readable JSON file per project (`Name.project.json`, or `.zip` with PDFs); full backups as a complete `.backup.zip` for device-to-device migration;
  share as compact text (SMS-friendly for non-PDF work) or attachment + text (email/messages), save via file picker, open/import from other apps;
  no permissions required for core workflows
- Values flow between tools: every finished result is published; every length field is the
  feet-inch calculator and offers recent results and active-project results by kind
- One Settings screen: General (theme, animations on/off), Measurements (Auto/ft-in/in, precision),
  and a page per tool that has options (opened directly from inside that tool)

## Direction: one app for the trades, electrical first

The app is an all-in-one toolbox for electrical work (industrial, commercial and residential;
planning, installing and documenting), usable by an average person without guessing. Electrical is
the only trade built. Plumbing and HVAC come later, each with its own calculators, formulas and code
book. `core/data/Trade.kt` is the trade registry (Electrical available; Plumbing and HVAC shown as
"coming later"); a new trade adds its entry there, its tools as `:feature:` modules, and its own
code source next to `core/electrical/.../code/`.

### Code jurisdictions (done)

- A **jurisdiction** (`CodeRegion`) names the NEC edition it adopted (`baseEdition`) plus its local
  **amendments**, keyed by the edition they amend. An amendment lists only what differs (citations,
  single values, Article 220 values); every lookup answers from the amendment first and falls
  through to the base edition (`AmendedElectricalCode`, `Code.resolution`).
- Only NEC 2023 data exists. NEC 2020 and 2026 can be picked; they resolve to NEC 2023 values and
  every place that names the code says so ("NEC 2023 values (NEC 2026 not in app)" plus a notice).
  Never silent.
- No amendment contents are entered yet (California, Massachusetts, Washington, New York State).
  They show "NEC 2023, no local amendments entered". Amendments are added only from the published
  text, in `core/electrical/.../code/`, like edition data.
- `CodeResolution` gives the source of every value: `label` for notes and reports ("NEC 2023 · MA
  amended"), `summary` for Settings, `citeWithSource(rule)` marking amended citations, `notices`
  for warnings.
- Next: enter verified amendments for the listed states; confirm each state's adopted edition with
  its published adoption; a per-project jurisdiction picker that uses the same model.

### First-launch quick setup (done)

Three short, skippable steps on first launch: trade, where you work (jurisdiction + NEC edition),
and units + Foot-Inch keypad (Classic or Dual-Split, and ' / " badges so feet and inches keys aren't
told apart by color alone). Every choice is saved as it's made and is also in the one Settings
screen, which can run the setup again ("Run quick setup again").

### Symbol recognition on drawings (planned)

- The model runs **on the device**; drawings never leave the phone.
- Inputs are mainly **vector PDFs and scanned PDFs**, not camera photos.
- The user opens a page and taps **Recognize all symbols**, or runs it over all pages or all
  drawings in a project.
- Every detection snaps to a device in the **full device catalog** (the catalog JSON is the one
  source of classes) and is **reviewed by the user before it's counted** in takeoffs.
- Training and data live in `ml/symbol-detector/`, outside the Android build, until a model is ready.

Feature references are in [`docs/research/`](research/): ArchCalc, Southwire Voltage Drop, Conduit
Fill (`com.conduit`), Partial Reel, Conversion and VFD Sizing (each with a "gap vs. our app"
section). They are written from public store listings and vendor pages (the Play listings could
not be read in full; see each file's sources). We use them only as an
inventory of features, never as a source of code, art or text.

## Status

| Tool | Status | Reference app(s) |
| --- | --- | --- |
| Foot-Inch Calculator | **done** (on shared system) | ArchCalc (`evanwinograd.archcalc`) |
| Conduit Bending | **done** (v1) | [Bending tool notes](tools/conduit-bending.md) |
| Conduit Fill | **done** (v1: Table 1 limits, Note 7, jam ratio, derating; the same check the design engine uses) | [Southwire Conduit Fill](https://www.southwire.com/calculator-conduit) |
| Voltage Drop | **done** (v1, tables checked vs NEC 2023) | Southwire Voltage Drop (`com.southwire.voltageDrop`) |
| Wire Pull Planner | **done** (v1: run paths, both directions, SWBP, limits, takeoff; [tool notes](tools/pull-planner.md)) | [Southwire Cable Pull](https://www.southwire-cablepull.com/) and SIMpull workbook |
| Partial Reel Length | **done** (v1) | Southwire Partial Reel (`com.southwire.partialReelCalculator`) |
| Unit Conversions | **done** (v1) | Southwire Conversion (`com.southwire.conversion`) |
| VFD Sizing | **done** (v1, tables checked vs NEC 2023) | Southwire VFD Sizing (`com.southwire.VFDSizingCalculator`) |
| Panels & Circuits | **done** (v1: schedules, feeders, design engine, PDF/text, links to the other tools) | Original: panel schedules linked to runs, wire sizing |
| Electrical Formulas | **done** (v1: 18 formulas, solve for any blank; [tool notes](tools/formulas.md)) | Ugly's-style formula pages, Southwire Power Conversion |
| Reference | **done** (v1: NEC tables generated from the engine data, formula sheet, field references; [tool notes](tools/reference.md)) | Original; edition-aware via `CodeRule` ([CODE_UPDATES.md](CODE_UPDATES.md)) |
| Projects & Runs | **done** (circuits and runs open Voltage Drop, Conduit Fill, Bending and Panels; shared text includes the design) | Original to this app: links all of the above |
| Drawings | **done** (v1: offline PDF takeoff, snap/loupe, routes, markups, stylus, revisions, exports; [plan](plans/pdf-plan-measuring.md)) | Bluebeam, Fieldwire, Autodesk Build |

## 1. Foot-Inch Calculator (done: v0.1)

- Exact fraction math (no floating-point drift), with feet `'` and inches `"` keys and one-tap fraction keys laid out like tape marks (eighths, then odd sixteenths)
- Results rounded to 1/2 through 1/64, with an "≈" marker and a note saying which way it rounded
- Auto display: inches in gives inches out, feet in gives feet-inches out (the most common complaint about ArchCalc)
- Dimensions are tracked: ft × ft → sq ft, × ft → cu ft, ft ÷ ft → a plain count
- Live preview while typing, history (saved), tap to copy, a feet-and-inches or inches-only display
- Conversions: total inches, decimal feet and inches, mm, m, sq in, sq yd, m², cu yd, gallons, m³

Next for this tool:
- [x] Top scrollable calculation tape with inline recall (tap to recall, swipe to delete, long-press to name/copy)
- [x] Configurable keypad layout: Split Dual-Pad (ArchCalc style: blue feet, green inches, fraction column) vs Classic Single Pad
- [x] Extensive footage formatting & customization (Auto, Ft-In, In, Dec Ft, Architectural, Metric; selectable rounding 1/2" to 1/64"; configurable fraction keys)
- [x] Quick-access Tools Sheet for right-triangle (rise, run, diagonal, pitch), stairs/stringers, and stud/baluster layout spacing marks
- [ ] Memory keys (M+, M−, MR) and an "Ans" key
- [ ] Parentheses
- [ ] Metric entry (mm, cm, m) alongside imperial
- [ ] Circle helpers (circumference and area from a diameter)
- [ ] Saved values / favorites picker
- [ ] Material outputs (sheets, bags, yards of concrete)
- [ ] PDF/text export of calculations

## 2. Conduit Bending

Bending math for EMT, IMC and rigid conduit with hand benders and electric/hydraulic benders.
- **Multi-bend stick layout (10' standard or cut stick):** model multiple sequential bends on a single stick of conduit.
- **3D pipe preview:** interactive 3D visualization showing physical orientation in space and eliminating doglegs.
- **Continuous tape marking guide:** cumulative marks from End A (hook tape once), bender hook direction (Hook toward End A vs End B), and alignment marks (Arrow, Star, Center notch).
- **0–360° axial rotation:** full rotational freedom per bend to match real field paths.
- **Pinned stick summary + accordion cards:** stats (Total Developed Length, Cut Length, Total Degrees / 360°, Remaining straight, shoe clearance warnings) pinned at top; bends listed as compact accordion cards to eliminate scrolling.
- **Quick Single Bend:** simple 1-bend calculation seamlessly supported within the stick architecture.

## 3. Conduit Fill & Derating

- **Progressive disclosure:** essential inputs at top with 1-tap presets (`3-Phase 4-Wire + Gnd`, `120V Branch 2W+G`) and manual add/remove wires. Set of voltages (120, 277, medium voltage / custom).
- **Automated neutral count & CCC determination:** determines required neutrals (shared vs dedicated) and counts Current-Carrying Conductors per NEC 310.15(E)/(F) for Table 310.15(C)(1) derating.
- **Advance thermal configuration:** ambient temperature (30°C default), rooftop sunlight condition (+60°F / +33°C), and terminal temperature rating (60°/75°/90°).
- **Dedicated Annex C sheet:** 'How many fit' moved out of the main screen into a 1-tap sheet.
- Output: % fill, the smallest conduit that works, Table 1 limits (53%/31%/40%/60% nipple).

## 4. Voltage Drop

- **4-Field quick input + auto-sizing:** Material (Cu/Al), System/Voltage (120, 208, 277, 480, Custom), Load Amps, and One-Way Length.
- **Instant output:** Smallest wire size for ≤3% drop (or code percent drop) and actual % drop at load.
- **Locked code constraints:** Parallel sets hidden unless wire size is ≥ 1/0 AWG (enforcing NEC 310.10(G)) or in advanced menu; power factor defaulted to 0.90 / 0.85.
- **Collapsed Advanced Options:** Custom target % drop, power factor override, AC Table 9 vs DC Table 8 method, conduit material, and manual wire size override.

## 5. Wire Pull Planner (done: v1)

- **Top-level mode toggle:** 'Quick Pull Check' (total length, 90° bends count, cable) vs 'Detailed Segment Route'.
- **Verdict-first display:** Recommended pull direction (End A vs End B) with peak tension and max SWBP against equipment and cable limits.
- **Collapsed rigging:** Puller ratings, rope limits, and friction coefficients (0.35 lubricated default) collapsed under Advanced Rigging.

See [tool notes](tools/pull-planner.md) and the [plan](plans/wire-pull-planner.md).
- The path (straights with slope, bends with radius and plane, pull points) lives on the project
  run, shared with Bending and Panels; the run's length is the routed length
- Tension per segment in both directions, SWBP per bend, the governing limit and its source,
  recommended direction, jam ratio, clearance, degrees per pull section (NEC 2023 xxx.24(B))
- Cables from the run's circuits plus hand-added ones; equipment ratings, a cable pressure rating,
  friction, incoming tension per section, slack
- Pull report saved to the run; pulls and the material takeoff in the project text and the panel
  schedule export; Reel Length checks a run's cable needed

Next:
- [ ] Compare the workflow fixture with the Re³ web calculator using matched settings
- [ ] Cable presets from catalog data (OD, weight, pressure rating) with their sources
- [ ] Friction values by conduit and jacket from a citable source
- [ ] Turn direction for horizontal bends, so the diagram can show a plan view

## 6. Partial Reel Length

- Estimate the cable left on a reel from the flange diameter, drum (core) diameter, traverse width, the height of the cable layers (or their distance below the flange edge), and the cable OD
- Also work backwards: how big a reel a given length needs
- Presets for common reel sizes

## 7. Unit Conversions

- Length, area, volume, weight, temperature, pressure, power (HP ↔ kW), energy, speed
- Electrical: kcmil ↔ mm², AWG ↔ mm² ↔ circular mils, Ω/kft ↔ Ω/km. Amps ↔ kW/kVA moved to Electrical Formulas (one calculator per rule).
- Uses the same exact-fraction engine where it makes sense

Further examples from the [Southwire calculator catalog](https://www.southwire.com/calculators):

- **Building wire selection.** A standalone sizing workflow that can also start from a panel
  circuit, using the load, wiring method and installation conditions already in the project.
  [Building Wire Selector](https://www.southwire.com/calculators/re3%E2%84%A2-building-wire-selector-calculator)
- **Copper and aluminum comparison.** Compare candidate conductors for the same circuit and
  show the effects on ampacity, voltage drop and conduit fill.
  [Conductor Conversion Calculator](https://www.southwire.com/calculators/re3-conversion-calculator)
- **DC power conversion.** Done in Electrical Formulas (Ohm's law and power covers DC).
  [Power Conversion Calculator](https://www.southwire.com/calculators/power-conversion-calculator)

## 8. VFD Sizing

- Pick a drive from the motor's HP/kW, voltage, phase and FLA (NEC Table 430.248/430.250)
- Constant- or variable-torque loads, single-phase input derating, altitude and temperature derating
- Outputs: the drive's current rating needed, input and output conductors, OCPD guidance

## 9. Projects & Runs (linking everything together)

The main thing this app adds over single-purpose calculators:
- A **Project** holds **Runs**, for example "Panel A → RTU-3".
- A run collects linked calculations: feet-inch takeoffs → total length → conduit bends along the run → conduit fill → voltage drop for the run length → pull tension along the bends → wire from the reel.
- Values flow between tools: a length worked out in Foot-Inch can feed Voltage Drop and Pull Planner without retyping it.
- Each tool's screen gets an "Add to run" action, and a run shows every step and its result.
- **Sharing:** export a run or project as a readable summary (text or PDF) and as a file that someone else's copy of the app can import. For non-PDF projects/calculations, support a compact share format small enough to send over a text message; projects with PDFs share as a ZIP over email/share sheet (no cloud relay or accounts).
- **Full device-to-device backups:** export everything (all projects, original PDFs, imported SVG symbols, app & tool settings, tool data, and recents) in one `.backup.zip` so you can move from one device to another and restore completely.
- **Drawings:** open project PDFs, calibrate scale zones, snap to lines, save measured lines, and add ink, vector marks, text, and imported SVG symbols. Export an annotated PDF or editable drawing JSON, or ship the originals with the project ZIP. [PDF plan measuring proposal](plans/pdf-plan-measuring.md)
- **Storage:** one plain JSON file per project (`core:data` `FileProjectStore`, unreadable files set aside in `projects/damaged/`), the same format that is shared.

## Architecture notes

See [ARCHITECTURE.md](ARCHITECTURE.md): module map, the tool API, how tools link to project circuits
and runs, and the rules every tool follows.

## Panels & Circuits (integrated electrical design)

Panels, circuits and conduit runs live in the project and are linked:
- **Panels:** spaces, voltage system (120/240 1φ, 208Y/120, 480Y/277, 240/480 Δ), bus, main or MLO,
  fed-from circuit. Standard odd/even numbering with phase rotation; multi-pole breakers; spares and
  spaces; per-phase load, imbalance and bus/main checks.
- **Circuits:** breaker, poles, label, load (VA or A), continuous, neutral, nonlinear, groups
  ("Kitchen", "RTU-3") across panels, and the **runs** they pass through.
- **Runs** are conduit runs: conduit type and size (or auto), length (from Bending, Foot-Inch or any
  result), ambient temperature, rooftop exposure, nipple. Bends, fill and pull results saved to a run
  belong to it.
- **Design engine** (`core/model/design/ProjectAnalysis`, NEC 2023, tables checked against the
  book):
  - counts current-carrying conductors per run (310.15(E)/(F)) and derates (Table 310.15(C)(1))
  - corrects for ambient temperature (Table 310.15(B)(1)(1)) with the rooftop adder (310.15(B)(2))
  - sizes each circuit for its breaker and continuous load: 210.19(A), 110.14(C) terminations,
    240.4(B)/(D)
  - checks voltage drop over the run length (Table 9) and upsizes to the target
  - sizes the EGC (Table 250.122, upsized per 250.122(B))
  - computes conduit fill and the smallest conduit (Chapter 9 Tables 1, 4, 5)
- **Exports:** panel schedule PDF and text, headed with the company profile.

Next:
- [x] **Bus-locked panel geometry & templates:** 3-phase panels strictly constrained to multiples of 6 spaces (equal A/B/C stab slots); 1-phase residential in multiples of 4 (equal L1/L2 stabs). Pre-populated templates for Residential 120/240V 1φ, Commercial 208/120V 3φ, and Commercial 480/277V 3φ.
- [x] **1-Tap quick circuit entry:** Description and Breaker Amps only; wire size, neutral, and ground auto-fill from code rules. Live phase balancing (kVA and % imbalance).
- [x] **Visual voltage-drop schedule flags:** Warning badge on schedule cells when a circuit's run length exceeds 3% voltage drop, with 1-tap `[Upsize Wire]` action.
- [x] **Flexible partial-detail inputs**: let circuits work without requiring a full conduit run — enter just a length measurement, or a conduit tag + size, or a full linked run, and compute what's possible from the details available
- [x] **Automatic recomputation**: automatically recompute Voltage Drop and Conduit Fill whenever upstream circuit or run inputs change, surfacing a warning immediately if out of spec (Wire Pull Planner stays on-demand / flagged)
- [x] **Printable panel schedules:** print-ready PDF or PNG formatted for panel doors and job binders.
- [ ] **Focused panel-schedule label OCR**: on-device OCR to pre-fill circuit labels from a photo of an existing panel schedule (with review); standardized phase rotation (A/B/C for 3φ, L1/L2 for 1φ) fills automatically, leaving only breaker amperage to confirm
- [ ] Available fault current, series ratings, and selective coordination notes

## Reference and formulas (done: v1)

- [x] Code-edition registry: every NEC citation in the app goes through `CodeRule` / `Nec2023`
- [x] Reference hub: search, sections, favorites, recently opened, a Reference action in every
  tool's top bar for its own entries, links from entries into tools
- [x] Code tables generated from `core:electrical` (ampacity, correction, adjustment, EGC, small
  conductors, standard OCPD, fill, Tables 4, 5, 8, 9, 2, wires-in-conduit, motors, 430.52, working space)
- [x] Formula sheet and Electrical Formulas tool from one catalog
- [x] Field references: offsets and saddles, NEMA configurations, conductor colors, knockouts,
  voltage systems, abbreviations, fractions
- [x] Box fill (314.16) and pull box sizing (314.28) entries, with calculators
- [x] Burial depth (Table 300.5), support spacing, GEC (Table 250.66) entries
- [ ] Editable local conventions (phase colors) and user notes on entries
- [x] Deep links from individual notices (tap a citation to open its entry)

## Next improvements (prioritized to-do)

Ordered by priority and reasonableness of implementation — start at Tier 1 and work down.

### Tier 1 — Quick Wins & High-Impact Polish *(small effort, no new dependencies)*
- [x] Add a `Panels · N` entry point directly on `ProjectDetailScreen`
- [x] Rename and delete imported drawings **and scale zones**, plus garbage-collecting unreferenced PDFs
- [x] Unify jam ratio between Conduit Fill (`D/d`) and Wire Pull Planner / `ProjectAnalysis` (`1.05 × D/d`), or explicitly label the 1.05 ovality allowance in both
- [x] One-tap "Check on reel" from Material Takeoff / Pull Planner (prefills Reel Length with wire OD and needed footage)
- [x] Include drawing measurement summaries in `ProjectText` exports
- [x] Show clipboard copy feedback snackbar on Android API < 33
- [x] Move `PlanSymbols.list()` / `remove()` off the main thread, normalize PDF rotation in `PlanExports.overlayMatrix`, and include extra pull/takeoff pages in `PanelPdf` page numbering
- [x] Search and filter on `ProjectsScreen` (by name, note, panel, or circuit)
- [x] CSV / spreadsheet export for material takeoffs and panel schedules
- [x] Deep links from individual NEC notices into their Reference entries
- [x] Clean up `scripts/download_*.py`: restore TLS verification (remove `CERT_NONE`), guard `extractall` against Zip Slip, and replace hardcoded machine paths

### Tier 2 — Data Integrity, Sharing & Everyday Workflow *(medium effort, high daily utility)*
- [x] Full `.backup.zip` export/restore carrying everything (all projects, original PDFs, imported SVG symbols, all settings, tool data, and recents) for complete device-to-device migration
- [x] Flexible partial-detail inputs on panels/circuits (just a measurement, or conduit number + size, without requiring a full `Run`)
- [x] Automatic recomputation of Voltage Drop and Conduit Fill on upstream edits, with out-of-spec warnings (Wire Pull Planner stays on-demand / flagged)
- [x] Compact SMS-friendly share format for non-PDF projects and calculations; ZIP over email/share sheet when PDFs are included (no cloud relay)
- [x] Printable panel schedule export: export directly from the panel schedule creator as a print-ready PDF or image (PNG) formatted for panel doors or job binders
- [x] Store lightweight `(projectId, itemId)` in `DestinationCodec` instead of full `ProjectItem` JSON in `rememberSaveable`, and standardize `FileProjectStore` writes on `AtomicFile` / atomic moves
- [x] Add unit test coverage for under-tested areas (`:feature:drawings`, `:feature:projects`, `BackupService`, `PlanSymbols`, `PlanFiles`)

### Tier 3 — Core Electrical & Calculator Expansions *(medium effort, reuses existing engines)*
- [x] Foot-Inch full calculation history/tape, memory (`M+`, `M−`, `MR`, `Ans`), parentheses, metric entry, `√`/`x²` right-triangle helper, and spacing/layout marks
- [x] **Resistance → Wire Length**: enter a measured resistance (Ω / mΩ), conductor material (Cu / Al / CCA), wire size, and one-way or loop measurement to calculate wire length from NEC Table 8
- [x] Box fill (NEC 314.16) and pull/junction box sizing (NEC 314.28), paired with Pull Planner pull points
- [x] Named, reusable cable definitions feeding Conduit Fill, Wire Pull Planner, and Reel Length
- [x] GEC & bonding jumpers (Table 250.66), across-the-line motor circuits (Art. 430), burial depth (Table 300.5), support spacing, and PVC thermal expansion
- [x] Article 220 feeder and service load calculations with demand factors, plus Cu/Al and parallel-feeder what-if comparisons

### Tier 4 — Drawings Takeoff Polish *(done)*
- [x] Crisp high-zoom region tiling in `PlanCanvas` (reusing `PlanRendering.renderRegion`), avoiding per-frame allocations and byte-bounded LRU page-bitmap cache
- [x] Undo/redo stack for drawing markups and measurements
- [x] Loupe / magnifier overlay while snapping endpoints on plans (Center / Edge / Off + visual snap target labels)
- [x] Multi-segment / polyline measurement on plans (summing chained legs + bend count straight into a Project Run) and saved editable open polylines / closed polygons with calibrated length, perimeter & area
- [x] "View on plan" link back from a run to the route it came from (the drawing keeps each sent route; Takeoff totals flag routes whose plan length moved after recalibration and roll up devices, circuits, areas & paths)
- [x] Symbol-first collapsible drawing toolbar, fixed status bar (`Revision`, `Scale`, `Snap`, `Pencil`), typed editor state, Adjust tool (move/resize handles, partial stroke & whole-stroke eraser), full architectural/engineering/metric presets, and PDF export covering marks, paths, routes, measurements & revisions
- [x] Sheet navigation (title-block extraction, disk-cached indexing & full-text search, repeated-label recovery, favorites, recents, discipline filters, manual labels)
- [x] Markup styles & electrical annotations (color palette, width/text size, opacity, dash patterns, arrowheads, fills, per-tool defaults, starter + custom symbol sets/favorites/recents, dimensions, revision clouds/callouts, offline photo pins, cross-sheet detail links)
- [x] Revision identity & comparison (sheet revision tracking with Latest/Superseded provenance into Value Picker/Takeoff, full-screen pinch/pan revision overlay compare, single-sheet/crop/whole-set exports)
- [x] Smooth zoom/convenience gestures (0.85×–24× pinch-to-zoom, fling inertia, double-tap zoom & link jump, fit-to-page & `< page/total >` pills, scroll-wheel zoom, 2/3-finger tap undo/redo) and Android stylus support (Pencil mode finger-pan, palm rejection, pressure smoothing, barrel button actions, tail eraser, hover preview)

### Future Additions & Polish (Drawings & Platform)
- [ ] Persist manual sheet labels (`SheetLabel`) on `PlanDrawing` for cross-device `.drawing.json` / `.project.zip` portability
- [ ] Persist revision overlay alignment per sheet pair & export flattened revision-diff PDF/PNG
- [ ] Batch / bulk revision assignment across imported addendum sets
- [ ] On-device offline OCR for scanned PDFs (title-block auto-naming & searchable scan text, no Play Services)
- [ ] Resize handles / bounding-box transform on `TEXT`, `SYMBOL`, `INK`, and `HIGHLIGHT` markups
- [ ] Multipolygon / polygon-with-cutout (hole) area takeoff support
- [ ] Sheet-size-aware default markup text/callout/stroke scaling (ARCH D/E vs Letter)
- [ ] Shape snap / hold-to-straighten on freehand pen strokes
- [ ] Markup layer/type/circuit/area visibility filters
- [ ] Standalone shareable custom symbol pack import/export
- [ ] Physical-tablet stylus QA & tuning (S Pen / USI 2.0 / Wacom EMR pressure curves, tilt, stylus quick-draw option)
- [ ] Keyboard shortcuts for tablet/Chromebook workflows (`Ctrl+Z`/`Ctrl+Shift+Z`, zoom/fit, page step, tool hotkeys)
- [ ] Instrumented Compose UI test suite (`androidTest`) for `DrawingEditor` live interactions + snap reference benchmark fixtures for `:core:plans` (`LineSnapReferenceTest`, `PlanLinesReferenceTest`)

### Tier 5 — Vision & Context Integrations *(optional, off by default)*
- [ ] Optional on-app-open nearby-project suggestion using Android's native `LocationManager` and coarse location only (`ACCESS_COARSE_LOCATION`, no precise GPS, no background geofencing, no Play Services)
- [ ] Focused on-device OCR for pre-filling circuit labels from existing panel schedule photos (standardized A/B/C and L1/L2 phase mapping; no Play Services)
- [ ] Blueprint symbol detector (`ml/symbol-detector/`): keep isolated outside the Android build until a trained model is ready

## Idea backlog

[plans/feature-ideas.md](plans/feature-ideas.md) holds the pruned future-work summary and explicit out-of-scope decisions. `docs/ROADMAP.md` is the authoritative plan.
