# Roadmap

The app is a single multi-mode toolbox for field electricians and builders. Each mode is a
**tool module** (`:feature:<name>`) registered in `:app` → `ToolRegistry`; see
[ARCHITECTURE.md](ARCHITECTURE.md) for how tools share the calculator engine, values, projects and
settings.

See [Requirements and acceptance checklist](REQUIREMENTS.md) for the original scope and current implementation status.

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

## 1. Foot-Inch Calculator (done: v0.1)

- Exact fraction math (no floating-point drift), with feet `'` and inches `"` keys and one-tap fraction keys laid out like tape marks (eighths, then odd sixteenths)
- Results rounded to 1/2 through 1/64, with an "≈" marker and a note saying which way it rounded
- Auto display: inches in gives inches out, feet in gives feet-inches out (the most common complaint about ArchCalc)
- Dimensions are tracked: ft × ft → sq ft, × ft → cu ft, ft ÷ ft → a plain count
- Live preview while typing, history (saved), tap to copy, a feet-and-inches or inches-only display
- Conversions: total inches, decimal feet and inches, mm, m, sq in, sq yd, m², cu yd, gallons, m³

Next for this tool:
- [ ] Memory keys (M+, M−, MR) and an "Ans" key
- [ ] Parentheses
- [ ] Metric entry (mm, cm, m) alongside imperial
- [ ] √ and x² (for diagonals and squaring up), plus a right-triangle helper (rise, run, diagonal, angle)
- [ ] Stair, rafter and pitch helpers
- [ ] Circle helpers (circumference and area from a diameter)
- [ ] Edit an earlier part of the expression, not only the end
- [ ] Landscape and tablet layout
- [ ] Full calculation history / **tape** showing everything calculated with each step's running total, where you can tap any line to edit or recall it, swipe to delete it, and long-press to name it (ArchCalc's best history feature)
- [ ] **Series / layout marks**: multiples of the answer plus an offset, for spacing studs, balusters and joists
- [ ] Saved values / favorites, plus a picker to drop a saved, copied or clipboard value into any input
- [ ] **Material outputs**: show an area or volume as sheets, bags or yards of concrete, using material units the user defines
- [ ] Separate input precision (which fraction keys appear) from output precision (rounding)
- [ ] PDF/text export of a calculation (this becomes part of Projects & Runs)
- [ ] Architectural notation (`2-03-08`) as an optional output
- [ ] Optional key sounds and colour-blind-safe key shapes

## 2. Conduit Bending

Bending math for EMT, IMC and rigid conduit with hand benders and benders such as the 555 and Chicago.
- Offsets: a chosen angle (10°, 22.5°, 30°, 45°, 60°) and offset height give the mark spacing (multiplier, i.e. cosecant), the shrink, and where to put the first mark
- Rolling offsets (the true offset from rise and roll), plus parallel/concentric offsets
- 3-point and 4-point saddles: center mark, outer marks and shrink
- 90° stub-ups and back-to-back 90s with the bender's take-up/deduct for each conduit size
- Kicks, and offsets into boxes
- A bender profile: the user enters their own bender's take-ups, gains and the location of its star or arrow mark
- Diagrams of each bend showing where every mark goes, measured from the end of the stick
- Uses the Foot-Inch engine (`core/measure`) for all lengths and fractions

Saved bends can be added to the run's path ("Add to run path"), which the
[Wire Pull Planner](tools/pull-planner.md) uses. Next: plan several bend groups on one conduit
piece and several pieces in a project run, and show each piece's marks and developed length.

## 3. Conduit Fill

- NEC Chapter 9 Table 1 fill limits (53% for one wire, 31% for two, 40% for more)
- Conduit types (EMT, IMC, RMC, PVC 40/80, FMC, LFMC, ENT) and their trade sizes (Table 4)
- Wire types (THHN/THWN-2, XHHW-2, RHH, USE…) and their areas (Table 5), mixed sizes, bare grounds (Table 8)
- Output: % fill, the smallest conduit that works, and how many wires of one size fit (Annex C style)
- Optional: jam ratio and nipple fill (60% for 24" or less)
- Next: accept a measured or catalog cable outside diameter, save it as a named cable, and reuse
  that cable in Wire Pull Planner and Reel Length. Keep raceway sizes and fill limits sourced
  from the app's NEC tables. [Southwire Conduit Fill research](research/conduit.md)

## 4. Voltage Drop

- Single-phase or three-phase, copper or aluminum, a choice of conduit type (PVC, aluminum or steel when using AC impedance tables)
- Inputs: voltage, load amps, one-way length, wire size, number of parallel sets, power factor
- Outputs: volts dropped, % drop, voltage at the load, and the smallest wire size that keeps drop under X%
- Methods: the simple K-factor formula (2·K·I·L / CM, or √3 for three-phase) and NEC Chapter 9 Table 9 impedance
- Max distance for a given wire, load and % drop

## 5. Wire Pull Planner (done: v1)

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
- **Drawings:** open project PDFs, calibrate scale zones (including with a Bluetooth laser distance meter for exact known-dimension calibration), snap to lines, save measured lines, and add ink, vector marks, text, and imported SVG symbols. Export an annotated PDF or editable drawing JSON, or ship the originals with the project ZIP. [PDF plan measuring proposal](plans/pdf-plan-measuring.md)
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
- [ ] **Flexible partial-detail inputs**: let circuits work without requiring a full conduit run — enter just a length measurement, or a conduit tag + size, or a full linked run, and compute what's possible from the details available
- [ ] **Automatic recomputation**: automatically recompute Voltage Drop and Conduit Fill whenever upstream circuit or run inputs change, surfacing a warning immediately if out of spec (Wire Pull Planner stays on-demand / flagged)
- [ ] **Article 220 feeder and service load calculations** with demand factors, plus Cu/Al and parallel feeder comparisons
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
- [ ] Box fill (314.16) and pull box sizing (314.28) entries, with calculators
- [ ] Burial depth (Table 300.5), support spacing, GEC (Table 250.66) entries
- [ ] Editable local conventions (phase colors) and user notes on entries
- [ ] Deep links from individual notices (tap a citation to open its entry)

## Next improvements (to-do)

- **Core electrical & calculators**:
  - [ ] Box fill (NEC 314.16) and pull/junction box sizing (NEC 314.28), paired with Pull Planner pull points
  - [ ] GEC & bonding jumpers (Table 250.66), across-the-line motor circuits (Art. 430), burial depth (Table 300.5), support spacing, and PVC thermal expansion
  - [ ] Named, reusable cable definitions feeding Conduit Fill, Wire Pull Planner, and Reel Length
  - [ ] Cu/Al and parallel-feeder what-if comparisons
  - [ ] Foot-Inch full calculation history/tape, memory (`M+`, `M−`, `MR`, `Ans`), parentheses, metric entry, `√`/`x²` right-triangle helper, and spacing/layout marks
- **Panels & automatic updates**:
  - [ ] Flexible partial-detail inputs on panels/circuits (just a measurement, or conduit number + size, without requiring a full Run)
  - [ ] Automatic recomputation of Voltage Drop and Conduit Fill on upstream edits, with out-of-spec warnings (Pull Planner flagged / on-demand)
  - [ ] Printable panel schedule export: export directly from the panel schedule creator as a print-ready PDF or image (PNG) formatted to print and post inside a panel door or job binder
  - [ ] Focused on-device OCR for pre-filling circuit labels from existing panel schedule photos (standardized A/B/C and L1/L2 phase mapping)
- **Drawings UX & takeoff**:
  - [ ] Crisp high-zoom region tiling in `PlanCanvas` (reusing `PlanRendering.renderRegion`), avoiding per-frame allocations and recycling page bitmaps
  - [ ] Loupe / magnifier overlay while snapping endpoints on plans
  - [ ] Multi-segment / polyline measurement on plans (summing chained legs + bend count straight into a Project Run / Wire Pull Planner)
  - [ ] Undo/redo stack for drawing markups and measurements
  - [ ] Rename and delete imported drawings in the drawing list, plus garbage-collecting unreferenced PDFs
  - [ ] Include drawing measurement summaries in `ProjectText` exports
  - [ ] Bluetooth laser distance meter integration for length fields and exact two-point PDF plan calibration
- **Projects, backups & sharing**:
  - [ ] Full `.backup.zip` export/restore carrying everything (all projects, original PDFs, imported SVG symbols, all settings, tool data, and recents) for complete device-to-device migration
  - [ ] Compact SMS-friendly share format for non-PDF projects and calculations; ZIP over email/share sheet when PDFs are included (no cloud relay)
  - [ ] Add a `Panels · N` entry point directly on `ProjectDetailScreen`
  - [ ] CSV / spreadsheet export for material takeoffs and panel schedules
  - [ ] Search and filter on `ProjectsScreen` (by name, note, panel, or circuit)
  - [ ] Optional on-app-open nearby-project suggestion using Android's native `LocationManager` and coarse location only (`ACCESS_COARSE_LOCATION`, no precise GPS, no background geofencing, no Play Services)
- **Platform polish**:
  - [ ] Store lightweight `(projectId, itemId)` in `DestinationCodec` instead of full `ProjectItem` JSON in `rememberSaveable`
  - [ ] Standardize `FileProjectStore` writes on `AtomicFile` / atomic moves
  - [ ] Show clipboard copy feedback snackbar on Android API < 33

## Idea backlog

[plans/feature-ideas.md](plans/feature-ideas.md) holds the pruned future-work summary and explicit out-of-scope decisions. `docs/ROADMAP.md` is the authoritative plan.
