# Wire Pull Planner: plan

Status: **planned**. Implementation starts after the current bug-fix and refactor pass lands on
`main`. Well & Pump stays last.

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
- **The two conduit enums are merged into one.** Bending's local conduit enum is being merged into core `ConduitType`/`TradeSize` in the current refactor; the pull planner relies on that.

## Engineering (core:electrical, pure, unit-tested)

Sources: IEEE Std 1185 (cable installation), manufacturers' published pulling guides (e.g.
Southwire, Polywater). Every formula and default is cited in KDoc. Values that are manufacturer
guidance, not code, are labelled that way in the UI.

| Item | Method |
|---|---|
| **Cable weight per foot** | Estimated from Table 8 bare area × metal density (Cu 0.321 lb/in³, Al 0.0975) plus insulation area (Table 5 − Table 8) × compound density. Editable per conductor (catalog value), marked as an estimate. |
| **Weight correction factor W** | 1 cable: 1.<br>3 cables: triangular W = 1 / √(1 − (d/(D−d))²), cradled W = 1 + (4/3)(d/(D−d))².<br>4 or more cables: diamond/complex, 1 + 2(d/(D−d))².<br>Configuration is picked from the jam ratio D/d. |
| **Straight section** | Horizontal: T₂ = T₁ + W·μ·w·L.<br>Inclined: T₂ = T₁ + w·L(sin θ ± W·μ·cos θ). |
| **Bend** | Horizontal, exact: T₂ = T₁·cosh(Wμθ) + √(T₁² + (wR)²)·sinh(Wμθ).<br>Vertical: concave-up and concave-down forms.<br>Simplified e^(Wμθ) shown for reference. |
| **Sidewall bearing pressure** | 1 cable: T/R.<br>3 triangular: W·T/(2R).<br>3 cradled: (3W − 2)·T/(3R).<br>R in feet → lb/ft. |
| **Maximum tension** | Pulling eye: 0.008 lb/cmil copper, 0.006 lb/cmil aluminum, per conductor, × conductors (derated if more than 3).<br>Basket grip: lesser of that and 1000 lb.<br>User override. |
| **Maximum SWBP** | Default by cable type (e.g. 1000 lb/ft building wire), user override, flagged as manufacturer guidance. |
| **Jam ratio** | 1.05·D/d between 2.8 and 3.2 → jam warning; the 1.05 accounts for conduit ovality at bends. |
| **Clearance** | Cradled-configuration clearance formula. Recommend ≥ 0.5 in (0.25 in minimum). |
| **Coefficient of friction μ** | Presets by conduit (EMT, RMC, PVC…) and lubricant (none, wax, polymer), default 0.35 lubricated. User override. |
| **Bends between pull points** | ≤ 360° per pull section: NEC 2023 "Bends — (B) Number in One Run" in each raceway article (358.24(B) for EMT checked on the printed page, 70-245; the same rule in the IMC, RMC, FMC, LFMC, PVC, LFNC and ENT articles, pp. 70-232 to 70-248). Map each ConduitType to its section number, checked page by page against the local PDF. |
| **Bend radius default** | Chapter 9 Table 2 minimum radius (one-shot and full-shoe benders, and other bends), by trade size, as the default when a bend has no radius. Verify against the printed table. |
| **Fill** | Reuses the shared fill check (the same core implementation as Conduit Fill). |

**Both directions.** Each pull section is computed both ways. The tool recommends the lower-tension direction, or the one that keeps every limit, and notes when the end with the tight bend should be the pulling end.

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
- **Settings:** μ preset, pulling device (eye or basket grip), max tension and SWBP overrides, slack per end. Tool defaults go on the tool's page in the one Settings screen; per-run values are saved with the run.
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
