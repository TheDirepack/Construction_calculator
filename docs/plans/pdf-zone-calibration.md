# PDF zone calibration implementation plan

## Define and calibrate a zone

1. Open a sheet, choose **Add scale zone**, and draw a rectangle around the floor plan or detail. Start with rectangles and keep the boundary in page coordinates so it survives zoom and rotation. Name the zone. A sheet can have several zones, including one explicitly drawn over the whole sheet. Outside a zone, allow markups and counts and show distances as uncalibrated.
2. Choose a preset, such as ¼″ = 1′ or 1:50, or tap both ends of a known dimension and enter the real length. Use the existing `MeasureField` and `MeasureInputSheet` so the same keypad accepts feet, inches, fractions, and metric inputs. A reference can use a typed value, a calculator expression, or a saved project value.
3. Add references at different positions and along different directions. Let the user choose which references fit the correction and which independently check it. Show the measured-versus-entered difference on each line, and mark the zone as preset, fitted, verified, or needing review. Tell the user which references conflict and let them move points, correct an input, or split the area into zones.
4. Measure lengths, angles, and areas against the active zone's correction. Display the zone name while tracing. Start with explicit splits at zone boundaries for route legs, and require each area to fit in one zone until the app can combine areas reliably. Reject ambiguous overlaps and show the user which zone needs editing.

Existing plan tools support independently calibrated regions and separate X and Y scales. [Bluebeam viewport calibration](https://support.bluebeam.com/user-manual/menus/window/create-manage-viewports.html)

## Fit the correction to the reference geometry

| Correction | Input and use |
|---|---|
| **Uniform scale** | A preset or one known span sets the same scale in every direction. Additional spans can fit one common scale and report residuals. A separate check span can verify it. |
| **Separate horizontal and vertical scales** | Use known spans along both declared axes of the zone. Fit each scale from the relevant references and check diagonals or references in other parts of the zone. Apply the correction to both components of every measured leg before calculating the length. |
| **Shear or skew** | When enough independent directions are available, fit a single affine distance correction. In zone coordinates, a known span supplies `length² = gxx·dx² + 2·gxy·dx·dy + gyy·dy²`. Require at least three linearly independent constraints, such as horizontal, vertical and diagonal, and a positive-definite result. Check the fit with extra references. A length-only fit determines distances and angles. Placement onto another sheet also needs orientation and an anchor. |
| **Distortion that changes across the zone** | Start by flagging the mismatch and allowing smaller independent zones. For a continuous correction, prototype a shared control grid with matched page and real-world points. Use landmarks from a known rectangle or a dimensioned grid, then interpolate within well-supported cells. Check coverage, seams, and reversed or collapsed cells before applying it. Distances alone from a few disconnected spots cannot determine a unique local warp. |

Use the simplest correction the selected references support. Several horizontal references can improve or check the horizontal scale. A vertical reference adds information about stretch in that direction. If a single correction fits one end of the zone and misses the other, show where it misses before offering a split or more control points.

Keep the original PDF as the visual backdrop. Store the mapping separately. Convert tapped points into corrected coordinates for lengths, angles and area. Use the inverse mapping for fixed-size sketches and route placements drawn on the original sheet. A later rectified preview can use the same mapping. For a piecewise correction, split measurement segments at cell boundaries before accumulating their lengths.

## Preserve measurement precision

- The existing calculator already uses `Rational` for fractions, decimal inputs, unit conversion and arithmetic. Keep calibration inputs, entered elevations, presets and calculator results as exact `Quantity` or `StoredQuantity` values. Keep the existing exact conversion of 25.4 mm per inch. Round only when formatting a value for the screen, a visible PDF label or a printed report. [Exact arithmetic](../../core/measure/src/main/kotlin/com/thedirepack/constructioncalc/core/math/Rational.kt) · [Measurement input](../../core/ui/src/main/kotlin/com/thedirepack/constructioncalc/core/ui/components/MeasureInputSheet.kt)
- PDF-derived geometry takes its precision from the source and the tapped points. A diagonal can also require an irrational square root. Preserve its source points, fit parameters, and full computed precision. Carry that value through the picker and the run review without snapping it to a displayed fraction or metric increment. Mark it as measured and keep that provenance distinct from an exact typed value.
- Preserve exact coefficients and squared distances where practical. Delay roots and numerical conversions until the operation needs them. Audit the run update when a PDF route is accepted. `RunPath.lengthInches` currently uses `Double`; sum typed straights as rationals where applicable so the handoff does not unnecessarily convert exact lengths. Curves, projected lengths and fitted sums need numerical evaluation. Keep that computation separate from display precision. [Stored quantities and published values](../../core/model/src/main/kotlin/com/thedirepack/constructioncalc/core/model/ResultValue.kt) · [Run path math](../../core/model/src/main/kotlin/com/thedirepack/constructioncalc/core/model/RunPath.kt)

## Build order

1. **Shared calibration model.** Define drawing revision, page, zone boundary, reference endpoints, exact reference length, fit/check role, correction type, residuals and calibration revision. Keep the math in pure Kotlin and use versioned project data. Store raw geometry and references so a recalibration can recompute measurements without converting them back from labels.
2. **First usable editor.** Import and render a PDF, draw multiple zones, choose a preset or add a reference using the measurement keypad, and place a measured line. Support a second check span from the start. Save the line with its zone revision and publish its value through the existing picker.
3. **Multi-reference correction.** Support uniform fits with multiple spans, then two-axis correction with independent references. Add diagnostics for insufficient directions, incompatible references and out-of-zone points. Prototype affine fitting on a known skewed sheet. Add a spatially varying correction only after control-point fixtures pass.
4. **Route integration and exports.** Use corrected geometry for route drafts, placement and duplication. When a fit changes, refresh live drawing measurements and show the proposed difference on any accepted run. Keep the accepted run available until its update is reviewed. Round-trip references, correction parameters, source points and revision links through standalone JSON and the project ZIP. Produce annotated PDFs with visible labels.

## Acceptance checks

- Put two details at different scales on one page. Each zone resolves its own measurements after zoom, rotation, or reopening. An out-of-zone trace asks for calibration, and a cross-zone trace asks to split.
- Enter `1/3″`, use it in arithmetic, save and recall it, and switch the display between imperial and metric. Three copies still sum to exactly `1″`, even when the readout rounds to a different displayed fraction. Verify that a typed `25.4 mm` and `1″` store the same length.
- Simulate a sheet stretched by different amounts on each axis. Fit horizontal and vertical references and recover the independently checked diagonal and area. For a sheared sheet, include a diagonal fit reference and a separate check. A uniform fit should show the residuals.
- Use inconsistent or nearly parallel references to test rejection of an underdetermined fit. For spatial correction, check an independent dimension inside each supported cell and verify that a trace crossing a cell boundary maintains continuity.
- Recalibrate a source zone, compare the new trace length with the accepted run, and verify that the run changes only after review. Export and reimport with a different unit and precision setting and compare the saved values and correction parameters.
