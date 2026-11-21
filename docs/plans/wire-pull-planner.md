# Wire Pull Planner: plan

Status: **implemented** (v1, November 2026). See [the tool notes](../tools/pull-planner.md) for
what shipped, the reference checks and where it differs from this plan. Well & Pump stays last.

### As built: differences from this plan

- **Bends from Bending** reach the path through `ToolModule.pathBends(item)` (features never
  import each other), from the save sheet ("Add to run path"), the project page and the planner's
  "Import bends". Imported bends have no radius (Table 2) and start horizontal.
- **Configuration** is picked from the cable count and D/d (cradled from D/d 2.5 with three
  cables), with a per-run override. Two cables use the triangular forms; four or more use
  1 + 2x² and the cradled SWBP form. These are stated assumptions in every report.
- **Clearance** uses the one-on-two (triangular) geometry, D/2 − 1.366d + (D−d)/2 √(1 − x²): that
  arrangement leaves the least room, so it's the one checked. The cradled arrangement
  always has more clearance.
- **Vertical bends** use concave-up and concave-down forms derived here from the same friction
  model (checked numerically against the differential equation), not copied from a table.
- **Friction presets** are by lubricant only (lubricated 0.35, none 0.50, pre-lubricated 0.15),
  labelled guidance. No conduit-specific values were found in a source we could cite.
- **Workflow fixture:** the original two-section fixture is checked against an independent script.
  The comparison with the Re³ web calculator is still to do (it needs interactive entry).
- **Project report** means the Panels schedule export (text and PDF) and the shared project text;
  both now end with each run's pull and the material takeoff.

## Goal

Plan a cable pull through a project's conduit run. You get pulling tension along the path,
sidewall bearing pressure in each bend, jam and clearance checks, and the better pull direction.
It isn't a separate calculator: it uses the same run, conduit, circuits and bends the rest of the
project already holds.

```
Bending items ──► Run.path (straights, bends, pull points) ◄── typed / measured lengths
                        │
Panels circuits ──► Run conductors (sizes, insulation, count)  ◄── ConduitTable / Table 5 / Table 8
                        │
                        ▼
              PullAnalysis (core:model/design, pure)
   tension per segment, both directions · SWBP per bend · jam ratio · clearance ·
   bend degrees between pull points · cable length per conductor
                        │
     ┌──────────────────┼───────────────────────┬──────────────────────┐
     ▼                  ▼                       ▼                      ▼
 Pull Planner UI    Panels › Runs tab      Reel Length            Material takeoff
 (feature/pullplanner) pull summary + warnings  "cable needed" value    wire footage per size
```

## Data model (core:model)

Add the path to the shared `Run`, so every tool sees the same geometry:

```kotlin
@Serializable
sealed interface PathSegment {
    @Serializable @SerialName("straight")
    data class Straight(val length: StoredQuantity, val slopeDegrees: Double = 0.0) : PathSegment  // + up, − down
    @Serializable @SerialName("bend")
    data class Bend(
        val angleDegrees: Double,
        val radius: StoredQuantity?,          // null = Chapter 9 Table 2 minimum radius for the conduit size
        val plane: BendPlane = BendPlane.HORIZONTAL, // HORIZONTAL, VERTICAL_UP (concave up), VERTICAL_DOWN
        val sourceItemId: String? = null,     // Bending item that produced it
    ) : PathSegment
    @Serializable @SerialName("pull_point")
    data class PullPoint(val label: String = "") : PathSegment  // pull box / conduit body: splits the pull
}

data class Run(..., val raceway: Raceway?, val path: List<PathSegment> = emptyList())
```

- **Length:** when a path exists, `Raceway.length` is derived from it (straights plus bend arc lengths), so the design engine's voltage drop uses the real routed length. Editing either one keeps them consistent.
- **Bends from Bending:** each Bending result saved to a run can add its bends to the run's path ("Add to run path"):
  - offset → two bends of θ
  - 3-point saddle → θ/2, θ, θ/2 (22.5°, 45°, 22.5° by default)
  - 4-point saddle → four bends
  - kick → one bend of θ
  - 90° stub → one 90° bend

  The bends keep `sourceItemId`, so later edits to that item can update them.
- **The two conduit enums are merged into one.** Bending uses the core `ConduitType`/`TradeSize`; the pull planner relies on that.

## Engineering (core:electrical, pure, unit-tested)

Use IEEE Std 1185 and the engineering references below to verify the formulas before
implementation. Cite formulas and defaults in KDoc. Label manufacturer limits with their
source and applicability, and keep NEC raceway rules identified by edition and section.

### Southwire references

- **Maximum Sidewall Pressure for Electrical Conductors (2021), p. 1.** Verify all three
  pressure equations, including use of bend exit tension and radius in feet. The flyer lists
  500 lb/ft for its 14, 12 and 10 AWG single-conductor building wire and 1,000 lb/ft for its
  8 AWG and larger building wire. It also lists limits for specified industrial and utility cable
  families. [Sidewall pressure flyer](https://www.southwire.com/medias/SW-1003229-Maximum-Sidewall-Pressure-Flyer-LO.pdf?context=bWFzdGVyfHJvb3R8MjQwODY2fGFwcGxpY2F0aW9uL3BkZnxoMjkvaDg5Lzk2NzEwNjY4Nzc5ODIvU1ctMTAwMzIyOS1NYXhpbXVtLVNpZGV3YWxsLVByZXNzdXJlLUZseWVyLUxPLnBkZnw3OTk3MGEzMjgwODc0MmI5NWNiODY1MjZkODViZDE1Y2Q1MWI3N2U3MjNhNDU4MGYxOTQ3YTVmYmUyMjU4ZTYy)
  [Supplied PDF](../research/southwire/SW-1003229-Maximum-Sidewall-Pressure-Flyer-LO.pdf)
- **Maximum Pulling Tension for Electrical Conductors (2024), pp. 1–3.** Use the conductor
  stress calculation and the copper and aluminum tables for worked checks. The flyer uses a
  0.8 multiplier above three load-bearing conductors and covers cables rated up to 46 kV.
  Take device ratings from their manufacturers.
  [Pulling tension flyer](https://www.southwire.com/medias/2405-Maximum-Pulling-Tension-WEB.pdf?context=bWFzdGVyfHBkZnN8MTA2NDg2MnxhcHBsaWNhdGlvbi9wZGZ8aDhiL2hiNS85MjI1NjYzNTEyNjA2LzI0MDVfTWF4aW11bV9QdWxsaW5nX1RlbnNpb25fV0VCLnBkZnw0NmU3ZDRhNmY4NDliMmRhMzc2MmI4MDNiMzYxYzM1ZTI2MzhkYzUyOTRjOWI5NjNmZDhlNTU3NGUwN2ZlMmY4)
  [Supplied PDF](../research/southwire/2405-Maximum-Pulling-Tension-WEB.pdf)
- **SIMpull cable pull workbook.** The supplied file `SIMpull-Cable-Pull-Calculator-9.18.2020.xls`
  has separate `Calc for 600V` and `Calc for Power Cable` worksheets. Use them as examples for
  grouping conductors, setting the incoming tension and friction, starting another pull section,
  and reviewing tension and pressure by segment. The 600 V sheet also shows how fill and reel data
  can accompany a pull. Its dated tables and product assumptions need independent verification.
  [Supplied workbook](../research/southwire/SIMpull-Cable-Pull-Calculator-9.18.2020.xls)
- **Current web calculator.** Review the workflow for setting cable OD and weight, selecting which
  cables carry tension, reversing pull direction, and reporting straight and bend lengths. Use
  example outputs as comparisons only when the inputs, units and assumptions match.
  [Re³ Cable Pull Calculator](https://www.southwire-cablepull.com/)

### Workbook implementation map

| Worksheets | Planned use |
|---|---|
| `Calc for 600V`, `Calc for Power Cable` | Identify the inputs, their units and the results that belong in a saved pull. |
| `Tension Calc`, `Power Cable Tension Calc` | Trace how incoming tension carries through each straight and bend. Check geometry branches, inch-to-foot conversion, bend exit tension and sidewall pressure against published equations. |
| `Reverse` and the two comparison sheets | Develop reference cases for reverse direction, pull-section resets and configuration changes. Record any differences between the workbook's two calculation paths before choosing expected values. |
| `Conduit`, `Conductor`, `Reels` | Identify the required lookup fields. Take code data from the app's referenced NEC tables and equipment or cable ratings from the applicable product data. |

The workbook contains the formula branches and data flow needed to start the Kotlin engine.
Build independently derived tests for individual equations first, then compare segment-by-segment
results with the workbook using the same inputs and assumptions. Review older product defaults
and unsupported cable configurations before adding presets.

| Item | Method |
|---|---|
| **Cable weight per foot** | Estimated from Table 8 bare area × metal density (Cu 0.321 lb/in³, Al 0.0975) plus insulation area (Table 5 − Table 8) × compound density. Editable per conductor (catalog value), marked as an estimate. |
| **Weight correction factor W** | 1 cable: 1.<br>3 cables: triangular W = 1 / √(1 − (d/(D−d))²), cradled W = 1 + (4/3)(d/(D−d))².<br>4 or more cables: diamond/complex, 1 + 2(d/(D−d))².<br>Configuration is picked from the jam ratio D/d. |
| **Straight section** | Horizontal: T₂ = T₁ + W·μ·w·L.<br>Inclined: T₂ = T₁ + w·L(sin θ ± W·μ·cos θ). |
| **Bend** | Horizontal, exact: T₂ = T₁·cosh(Wμθ) + √(T₁² + (wR)²)·sinh(Wμθ).<br>Vertical: concave-up and concave-down forms.<br>Simplified e^(Wμθ) shown for reference. |
| **Sidewall bearing pressure** | One cable, including a multiconductor cable with a common jacket: T/R.<br>3 triangular: W·T/(2R).<br>3 cradled: (3W − 2)·T/(3R).<br>T is tension at the bend exit. R in feet gives lb/ft. |
| **Maximum tension** | For the applicable Southwire pulling-eye calculation, use 0.008 lb/cmil for copper or 0.006 lb/cmil for the aluminum constructions covered by the flyer. For equal-size conductors carrying the pull, multiply by the number of conductors and apply 0.8 for counts above three. Compare against separately supplied ratings for the grip or eye, rope, swivel, puller and other limiting equipment. Show the lowest known rating and flag incomplete limits. |
| **Maximum SWBP** | Use the applicable product rating or an explicit user value. A manufacturer preset needs a cable family, size range, unit and source. Keep pressure limits in lb/ft separate from tension limits in lb. |
| **Jam ratio** | 1.05·D/d between 2.8 and 3.2 → jam warning; the 1.05 accounts for conduit ovality at bends. |
| **Clearance** | Cradled-configuration clearance formula. Recommend ≥ 0.5 in (0.25 in minimum). |
| **Coefficient of friction μ** | Presets by conduit (EMT, RMC, PVC…) and lubricant (none, wax, polymer), default 0.35 lubricated. User override. |
| **Bends between pull points** | ≤ 360° per pull section: NEC 2023 "Bends — (B) Number in One Run". Checked page by page: IMC 342.24(B) p. 70-232, RMC 344.24(B) 70-233, FMC 348.24(B) 70-235, LFMC 350.24(B) 70-236, PVC 352.24(B) 70-238, HDPE 353.24(B) 70-240, LFNC 356.24(B) 70-244, EMT 358.24(B) 70-245, ENT 362.24(B) 70-248 (`RacewayBends.section`). |
| **Bend radius default** | Chapter 9 Table 2 (printed p. 70-716, verified): one-shot column for EMT (358.24(A) names it), IMC, RMC and PVC; "Other Bends" for FMC, LFMC, LFNC and ENT (their (A) sections name it). |
| **Fill** | Reuses the shared fill check (the same core implementation as Conduit Fill). |

**Both directions.** Recalculate each section with its geometry and initial tension for each direction. Compare peak tension and peak SWBP against their own limits and identify the governing segment.

## Tool (feature/pullplanner)

- **Target:** a run of the active project, picked from a list or opened from Panels › Runs ("Plan pull"). With no project it works standalone on a temporary run, with "Save to project…" to keep it.
- **Path editor:** a list of segments.
  - Add straight: length from MeasureField, which offers Foot-Inch and other results.
  - Add bend: angle, radius or standard radius, plane.
  - Add pull point.
  - Import bends from the run's Bending items.
  - Reorder and delete.
  - A simple, static Canvas diagram of the route with no animation.
- **Cables:** prefilled from the circuits in the run (ProjectAnalysis gives sizes and insulation), editable. You can also add cables without circuits: count × size × insulation, weight override.
- **Settings:** μ preset, incoming tension per pull section, pulling device and equipment ratings,
  cable pressure rating, and slack per end. Save per-run values with the run and show any
  manufacturer presets and overrides in the pull report.
- **Results:**
  - tension at each point, both directions
  - peak SWBP per bend
  - jam ratio and clearance
  - degrees per pull section
  - recommended direction
  - cable length per conductor (path + slack)
  - every assumption as a notice
- **Publish and save:**
  - Publishes: max tension (FORCE), max SWBP (NUMBER, lb/ft), cable needed (LENGTH), total bend degrees (ANGLE).
  - Saves as a ProjectItem in the run.
  - The path itself lives on the Run, so Bending, Panels and Pull share it.

## Integration with the rest of the app

1. **Panels › Runs tab:** a pull summary per run (max tension vs limit, worst SWBP, degrees, jam warning) and a "Plan pull" action.
2. **Design engine:** voltage drop uses the path length; the run fill check shows jam and clearance warnings.
3. **Bending:** "Add to run path" on saved items; the run shows total bend degrees between pull points.
4. **Reel Length:**
   - "Cable needed" from a run is offered as a value, and the tool says whether a reel has enough.
   - The reel's cable OD can come from Table 5 (size + insulation) instead of being typed.
5. **Material takeoff (new, shared):** wire footage per size, insulation and material from all runs (conductors × path length + slack), plus conduit footage per type and size. Part of the project report and export.
6. **Opening tools with a target:** reuse whatever "open this circuit or run" mechanism the current refactor adds (a typed target passed through `ToolHost.openTool`), so Voltage Drop, Conduit Fill, Bending and Pull all accept a run or circuit target the same way.
7. **Exports:** the pull summary is included in project text, the project report and backups automatically (it's project data).

## Tests

- **Formula unit tests** against worked examples from IEEE 1185 and manufacturer guides: straight, inclined, horizontal bend exact versus exponential, SWBP for 1 and 3 cables, W for the triangular and cradled configurations.
- **Southwire reference checks:** the copper table on p. 2 of the tension flyer rounds 12 AWG
  to 157 lb for three conductors and 167 lb for four. For the pressure formulas, use an exit
  tension of 600 lb and a 2 ft radius. Expect 300 lb/ft for one cable, 195 lb/ft for three
  triangular cables and 190 lb/ft for three cradled cables with W = 1.3 in both three-cable
  cases. Check the 10-to-8 AWG building-wire preset boundary and cases where the device rating,
  cable tension rating or bend pressure rating limits the pull.
- **Workflow fixture:** build an original multi-segment example with a pull point, a nonzero
  starting tension, an inclined straight, and bends of different radii. Compare both directions
  with matched settings in the reference calculator, and preserve the assumptions with the fixture.
- **Path math:** arc lengths, derived run length, splitting at pull points, degrees per section.
- **Mapping Bending items to path bends.**
- **Integration:** a project with circuits in a run → pull analysis uses their conductors; reverse direction gives a different tension; over-limit cases produce notices.
- **Fuzz:** a malformed path in an imported file can't crash the app.

## Order of work

1. Path model on `Run` and path math in core, with tests.
2. Pulling formulas in core:electrical (weights, W, tension, SWBP, limits, μ presets), with tests.
3. `PullAnalysis` in core:model/design, wired to circuits, runs and fill.
4. Bending → path integration, and Panels › Runs pull summary.
5. Pull Planner UI.
6. Reel Length and material takeoff integration.
7. Verify the bend-limit sections (xxx.24(B)), Chapter 9 Table 2 and any other NEC-derived values against the local NEC 2023 PDF; label manufacturer values.
