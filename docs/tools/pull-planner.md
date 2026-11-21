# Wire Pull Planner

Module: `feature/pullplanner` (screen, dialogs and the static diagram; pure helpers in
`domain/PullPlan.kt` and `domain/PathProfile.kt`). The engineering is shared:

- `core/electrical`: `PullFormulas` (tension, W, sidewall pressure, jam, clearance),
  `PullRatings` (conductor tension, building-wire pressure preset), `FrictionPreset`,
  `CableWeight`, `RacewayBends` (Chapter 9 Table 2 and the 360° sections).
- `core/model`: `Run.path` (`PathSegment`), `Run.pull` (`PullSetup`), `RunPath` (path math, length
  kept in step), and `design/PullAnalysis` (the pull for a run, both directions),
  `design/MaterialTakeoff`, `PullText`.

Plan and reasoning: [plans/wire-pull-planner.md](../plans/wire-pull-planner.md).

## What it does

Pick a run of a project (or work standalone), lay out its path, and it shows:

- tension leaving every segment in both directions, and sidewall bearing pressure (SWBP) in every bend
- the peak tension and SWBP against their limits, and which direction to pull from
- the weight correction factor and configuration, jam ratio and clearance
- bend degrees in each pull section against the 360° rule
- cable needed per conductor (routed length + slack at both ends)
- every assumption and limit, with its source, as a notice

**Path.** Straights (length from `MeasureField`, slope −90° to 90°), bends (angle, radius or the
Table 2 default, plane: horizontal, vertical sag, vertical hump) and pull points. Rows can be
edited, moved and removed; bends saved in the run from Conduit Bending import with one tap. The
diagram is a static side view (inclines and vertical bends drawn, horizontal bends and pull
points marked).

**Cables.** The conductors of the circuits assigned to the run, as the design engine sized them
(Table 5 diameters, estimated weights), plus cable groups added by hand (count × size ×
insulation × metal, with catalog OD and weight overrides and a "bears tension" switch).

**Shared with the project.** The path and pull settings live on the `Run`, so Bending, Panels ›
Runs, the design engine and exports all see the same route. The run's conduit length is the
path's routed length (straights plus bend arcs), so voltage drop uses it; editing the length
moves the last straight.

Published values: `max_tension` (`FORCE`, lb), `max_swbp` (`NUMBER`, lb/ft), `cable_needed`
(`LENGTH`), `total_bend_degrees` (`ANGLE`), plus `tension_limit`, `jam_ratio` and `clearance`. The
design also publishes each routed run's `run_<id>_cable_needed` and `run_<id>_max_tension`, which
Reel Length offers. Saved items store `PullPlannerInputs` (the run link and a snapshot) and a pull
report summary listing every limit and preset with its source.

## Formulas

W = weight correction, μ = friction, w = total cable weight (lb/ft), L in ft, R in ft, θ in radians.

```
Straight (IEEE 1185)        T2 = T1 + w L (sin α + W μ cos α)        α = incline, + uphill
Horizontal bend (exact)     T2 = T1 cosh(Wμθ) + √(T1² + (wR)²) sinh(Wμθ)
Vertical sag (concave up)   T2 = T1 e^(kθ) − wR/(1+k²) [2k sin α2 + (1−k²) cos α2 − e^(kθ)(2k sin α1 + (1−k²) cos α1)]
Vertical hump (concave dn)  T2 = T1 e^(kθ) + wR/(1+k²) [(1−k²) cos α2 − 2k sin α2 − e^(kθ)((1−k²) cos α1 − 2k sin α1)]
                            k = Wμ, α1/α2 = slope entering/leaving (derived; checked against the ODE)
Reference only              T2 = T1 e^(Wμθ)

W, x = d/(D−d)              1 cable: 1   triangular: 1/√(1−x²)   cradled: 1 + 4/3 x²   4+: 1 + 2x²
SWBP (Southwire 2021)       1 cable: T/R   triangular: W T/(2R)   cradled: (3W−2) T/(3R)   (T leaving the bend)
Jam ratio                   1.05 D/d, warning from 2.8 to 3.2 with three cables
Clearance                   1 cable: D − d;  3+: D/2 − 1.366 d + (D−d)/2 √(1−x²)   (≥ 1/2 in. recommended, 1/4 in. minimum)
Conductor tension (2024)    S × cmil × n, × 0.8 when n > 3;  S = 0.008 lb/cmil Cu, 0.006 Al
```

Each pull section starts from its incoming tension. Pulling the other way reverses the segments and
every incline; bend planes are geometry and don't change. The recommended direction has the lower
peak as a fraction of its limits (tension and SWBP each against their own).

## Limits and presets

- **Tension:** the conductor limit for the conductors carrying the pull (the EGC is left out), and
  every entered equipment rating (eye or grip, rope, swivel, puller). The lowest governs; equipment
  without a rating is listed as not covered. Mixed sizes use the smallest conductor for all
  (conservative).
- **SWBP:** the cable's rating if entered, else Southwire's building-wire guidance (500 lb/ft for
  14–10 AWG, 1,000 lb/ft for 8 AWG and larger), labelled as manufacturer guidance.
- **Friction:** presets are guidance: lubricated 0.35, no lubricant 0.50, pre-lubricated wire
  0.15 (use the maker's value). Each run can enter its own.
- **Configuration:** one cable single; two triangular; three triangular below D/d 2.5, else cradled;
  four or more complex. Each run can pick its own.
- **Bend radius:** Chapter 9 Table 2, the one-shot column for EMT, IMC, RMC and PVC, "other bends" for
  FMC, LFMC, LFNC and ENT (as each article says).
- **360°:** NEC 2023 342.24(B), 344.24(B), 348.24(B), 350.24(B), 352.24(B), 353.24(B), 356.24(B),
  358.24(B), 362.24(B) (pages 70-232 to 70-248, checked against the book).

Settings page (Settings › Wire Pull Planner): the friction preset, slack per end and incoming
tension new pulls start with.

## Checks against references

- Southwire tension flyer: 12 AWG copper is 52, 157 and 167 lb for 1, 3 and 4 conductors; 4/0 Cu,
  250 kcmil and 4/0 Al rows also match.
- Southwire pressure flyer: 600 lb leaving a 2 ft bend gives 300 (one cable), 195 (triangular,
  W 1.3) and 190 lb/ft (cradled, W 1.3).
- SIMpull workbook (9.18.2020), Illustration sheet: the nine-segment example chained with the
  workbook's own bend form (e^(Wμθ)) reproduces its μ = 0.35 column to the pound and its μ = 0.14
  column except two typed-in cells that disagree with the sheet's own chain.
- An original two-section fixture (pull point, 50 and 100 lb incoming, a 30° incline, a vertical sag,
  radii of 24 in., 36 in. and Table 2) is checked in both directions against an independent script.

## Differences from the SIMpull workbook

The workbook uses e^(Wμθ) for every bend, has no clearance check, no 1.05 in the jam ratio, a fixed
W = 1.4 and W·T/(2R) for four or more cables, and clamps a negative straight to 1 lb. The planner
uses the exact and derived bend forms, IEEE's 1.05 ovality factor, 1 + 2x² and the cradled SWBP
form for four or more (more conservative than 0.7 T/R), and clamps at 0 lb.

## Limits of the tool

Horizontal bends are treated as level (a bend on an incline uses the horizontal form). Vertical
bends assume the tension holds the cable against the inside of the bend. A side view can't show
turn direction. Friction values are guidance; cable weights without catalog values are estimates.
It does not size pulling equipment or cover cable-tray pulls.
