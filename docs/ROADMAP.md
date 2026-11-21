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
- Projects → runs → items from any tool; saved as one readable JSON file per project (`Name.project.json`); full backups as `….backup.json`;
  share as attachment + text (email/messages), save via file picker, open/import from other apps;
  no permissions
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
| Wire Pull Planner | planned in detail: [plans/wire-pull-planner.md](plans/wire-pull-planner.md) | [Southwire Cable Pull](https://www.southwire-cablepull.com/) and SIMpull workbook |
| Partial Reel Length | **done** (v1) | Southwire Partial Reel (`com.southwire.partialReelCalculator`) |
| Unit Conversions | **done** (v1) | Southwire Conversion (`com.southwire.conversion`) |
| VFD Sizing | **done** (v1, tables checked vs NEC 2023) | Southwire VFD Sizing (`com.southwire.VFDSizingCalculator`) |
| Well & Pump | last (after everything else) | Well Drilling (`com.welldrilling`) |
| Panels & Circuits | **done** (v1: schedules, feeders, design engine, PDF/text, links to the other tools) | Original: panel schedules linked to runs, wire sizing |
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
- [ ] Calculation **tape** showing each step's running total, where you can tap any line to edit it, swipe to delete it, and long-press to name it (ArchCalc's best history feature)
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

Next: plan several bend groups on one conduit piece and several pieces in a project run. Show
each piece's marks and developed length, then pass the route geometry and pull points to the
[Wire Pull Planner](plans/wire-pull-planner.md).

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

## 5. Wire Pull Planner

- Build a pull path from straight runs and bends (angle, radius) with elevation changes
- Pulling tension for each segment and sidewall bearing pressure in each bend, with the governing
  limit and its source shown in the results
- Inputs: conductors (size, count, weight per foot), conduit size and type, coefficient of friction (lubricant), and the cable's max tension
- Pull from the other end to compare; set the tugger capacity; warnings when a limit is exceeded
- Jam ratio and clearance checks
- Exports a pull report (see Projects & Runs)

## 6. Partial Reel Length

- Estimate the cable left on a reel from the flange diameter, drum (core) diameter, traverse width, the height of the cable layers (or their distance below the flange edge), and the cable OD
- Also work backwards: how big a reel a given length needs
- Presets for common reel sizes

## 7. Unit Conversions

- Length, area, volume, weight, temperature, pressure, power (HP ↔ kW), energy, speed
- Electrical: kcmil ↔ mm², AWG ↔ mm² ↔ circular mils, amps ↔ kW/kVA (single- or three-phase, PF), Ω/kft ↔ Ω/km
- Uses the same exact-fraction engine where it makes sense

Further examples from the [Southwire calculator catalog](https://www.southwire.com/calculators):

- **Building wire selection.** A standalone sizing workflow that can also start from a panel
  circuit, using the load, wiring method and installation conditions already in the project.
  [Building Wire Selector](https://www.southwire.com/calculators/re3%E2%84%A2-building-wire-selector-calculator)
- **Copper and aluminum comparison.** Compare candidate conductors for the same circuit and
  show the effects on ampacity, voltage drop and conduit fill.
  [Conductor Conversion Calculator](https://www.southwire.com/calculators/re3-conversion-calculator)
- **DC power conversion.** Extend the current and power calculator's single- and three-phase
  AC modes with a DC mode for voltage, current and power.
  [Power Conversion Calculator](https://www.southwire.com/calculators/power-conversion-calculator)

## 8. VFD Sizing

- Pick a drive from the motor's HP/kW, voltage, phase and FLA (NEC Table 430.248/430.250)
- Constant- or variable-torque loads, single-phase input derating, altitude and temperature derating
- Outputs: the drive's current rating needed, input and output conductors, OCPD guidance

## 9. Well & Pump

To be scoped once the reference research for `com.welldrilling` is done. That app's actual function
still needs confirming; it likely covers well volume, grout and gravel-pack volume, pump sizing and
total dynamic head.

## 10. Projects & Runs (linking everything together)

The main thing this app adds over single-purpose calculators:
- A **Project** holds **Runs**, for example "Panel A → RTU-3".
- A run collects linked calculations: feet-inch takeoffs → total length → conduit bends along the run → conduit fill → voltage drop for the run length → pull tension along the bends → wire from the reel.
- Values flow between tools: a length worked out in Foot-Inch can feed Voltage Drop and Pull Planner without retyping it.
- Each tool's screen gets an "Add to run" action, and a run shows every step and its result.
- Sharing: export a run or project as a readable summary (text or PDF) and as a file that someone else's copy of the app can import. Later: a share link.
- Storage: one plain JSON file per project (`core:data` `FileProjectStore`, unreadable files set aside in `projects/damaged/`), the same format that is shared.

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

Next: feeder and service load calculations (Article 220), demand factors, available fault current,
series ratings, and selective coordination notes.
