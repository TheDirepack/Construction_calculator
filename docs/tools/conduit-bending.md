# Conduit Bending

Field bending math for hand benders. Code: `feature/conduitbending` (math in `domain/`, pure Kotlin,
unit-tested). Tool id `conduit-bending`.

All lengths are exact inches (`Rational`); trig results are rounded to 10 decimals first (so
1/sin 30° is exactly 2) and then shown with the app's precision and ft-in/in setting.

## Formulas

| Bend | Outputs |
|---|---|
| Offset (depth d, angle θ) | spacing = d·csc θ; shrink = d·tan(θ/2) (exact); field shrink = d × printed constant; first mark = distance + shrink; second mark = first + spacing |
| Rolling offset (rise, roll) | true offset = √(rise²+roll²), then the offset outputs on it |
| 3-point saddle (height H, center 45° or 60°) | outer marks = H × 2.5 (45°/22.5°) or H × 2 (60°/30°) from the center; shrink = H × the outer angle's offset constant (3/16"/in at 22.5°, 1/4"/in at 30°), which is what the conduit loses up to the center bend (geometry: H·tan(outer/2)); center mark = distance + shrink; the whole saddle's shrink, 2·H·tan(outer/2), is shown too |
| 4-point saddle (H, width W, θ) | marks 1, 2 = 1 + spacing, 3 = 2 + W, 4 = 3 + spacing; total shrink = 2 × offset shrink; first mark = distance + offset shrink |
| 90° stub-up | mark = stub height − take-up (error if shorter than the take-up) |
| Back-to-back 90s | second mark = distance between backs, measured from the back of the first bend; cut length = stub1 + between + stub2 − 2 × gain |
| Kick (h, θ) | travel = h·csc θ; run = h·cot θ; shrink = travel − run; bend mark = distance − run |

Printed field constants (offset multiplier / shrink per inch): 10° 6 / 1/16; 22.5° 2.6 / 3/16;
30° 2 / 1/4; 45° 1.4 / 3/8; 60° 1.2 / 1/2. These are the numbers hand-bending charts commonly
print; the tool computes from trigonometry and shows the field shrink beside it.

## Bender defaults (edit in the tool's Settings page)

Take-up (inches): EMT 1/2 → 5, 3/4 → 6, 1 → 8, 1-1/4 → 11; IMC and rigid 1/2 → 6, 3/4 → 8, 1 → 9.
These are the commonly published hand-bender deduct figures; benders differ by maker and wear, so
**check them against your own bender**.

Gain is derived, not looked up: gain = 2T − (π/2)(T − OD/2), rounded to 1/8", with T the take-up and
OD the conduit outside diameter (EMT 0.706/0.922/1.163/1.510; IMC 0.815/1.029/1.290; rigid
0.840/1.050/1.315 in). Defaults: EMT 2-3/4, 3-1/4, 4-3/8, 5-7/8; IMC 3-1/4, 4-1/4, 4-7/8;
rigid 3-1/4, 4-1/4, 4-7/8. Gain only affects the back-to-back cut length.

## Conduit and runs

The conduit type and trade size are the app's own (`ConduitType`, `TradeSize`: the ones a project
run uses); `Bendable` lists those with hand-bender values (EMT 1/2–1-1/4, IMC and RMC 1/2–1). Every
result includes *Total bend* (a 30° offset is 60°, a back-to-back is 180°). Opened for a run, the
tool starts with the run's conduit; saving a bend to a run offers its conduit as the run's conduit,
and the run adds up its saved bends and warns past 360° between pull points.

## Limitations

- The distance convention for offsets (first mark = distance + shrink) fits a distance measured on
  the finished run past the offset. If your distance already lands on the first bend, ignore the shrink.
- The 3-point saddle's field shrink (3/16"/in at 45°, 1/4"/in at 60°) is the shrink up to the center
  bend and matches the geometry to within 1/32" per inch; the whole saddle shrinks about twice that.
  (Before 2026-11-20 the center mark wrongly added only half the field shrink.)
- Rolling offsets give the true offset and marks only (no rotation angle for the bender).
- Bender star/arrow positions, parallel/concentric offsets and offsets into boxes are not covered.
- Nothing here accounts for conduit springback, bender wear or bending radius limits (NEC 358/344).
