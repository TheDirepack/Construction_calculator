# Measuring PDF plans in Construction Calc

*Proposed feature*

Say we need to run conduit from Panel A to an RTU. We have an electrical plan with the panel location, a roof plan with the unit, and a dimension we can use to check the scale. Today we can enter a length and use it across the app. With PDF plan measuring, we'll be able to trace the route on those sheets, add the rise to the roof, and save the result to the same run we use for voltage drop and the pull plan. From the run, we'll be able to open the sheet and see where that length came from.

## Bring the plans into the job

Each project will have a Drawings section that can hold several PDFs, each with its own pages and revisions. Pick a file from Android's file picker or open it into Construction Calc, then choose the project. The app will keep a local copy so the plans work offline. Open a page from the thumbnail list, zoom in, and rotate the view to work on it.

Draw a scale zone around a floor plan or detail. Choose a preset or tap the ends of a known dimension and enter its actual length with the same feet-inch or metric keypad used in the calculators. Add references in different directions and places to check the scale and correct stretch or skew within the zone when the references support it. If a trace crosses a zone boundary, split it into separately calibrated sections. Outside a calibrated zone you can still add notes and count fixtures. [Zone calibration implementation plan](pdf-zone-calibration.md)

The takeoff tools will cover straight lengths, connected runs of line segments, perimeters, enclosed areas, and counts grouped by item. Basic text, arrows, and labels will let you explain what you measured. For example, you could count junction boxes on an electrical sheet, save a conduit length to a run, and mark the spot where the conduit goes up a wall.

## Take a measurement into the existing tools

A saved length will be available in the app's value picker. The drawing will retain the points, scale, and source sheet. The picker will receive a length through the existing `ResultValue` interface, with the same unit formatting as other tools. A run or panel can also link back to a drawing location, such as a panel tag on the floor plan.

Saving a traced route will open a review step. It will list the lengths measured on each sheet, plus the rises, drops, bends, and pull points you enter. A corner on a plan gives us a change of direction. The person laying out the run supplies the installed bend details and confirms how sections on different sheets connect. The review shows missing details and previews the resulting length before you create a run or update an existing one. An incomplete trace can stay saved as a draft. [Current run and tool integration](../ARCHITECTURE.md#how-the-pieces-connect)

Once accepted, the route goes through the existing `Project.withRunPath` update so the stored raceway length stays in sync. Voltage Drop uses that length. The Wire Pull Planner uses the accepted straights, bends, and pull points. Conduit Bending can still add field bends, Conduit Fill still uses the run's raceway and conductors, and material takeoffs use the routed footage. When a drawing revision or scale changes, the app will flag linked results for review and let you compare and apply a new route. The installed route remains usable while you review the change. [Run path model](../../core/model/src/main/kotlin/com/thedirepack/constructioncalc/core/model/RunPath.kt)

## Share a drawing or send the whole job

An annotated PDF will show the measurements and notes in an ordinary PDF viewer. To share the editable points, scales, and markups, export `drawing.json` separately. Construction Calc can pair that file with the matching original PDF.

For a project with one or more PDFs, the complete export will be a ZIP containing `project.json` and the original drawings. The JSON will carry the measurements, markup layers, and run links. When someone imports the ZIP, they'll get the whole job and can pick up where you left off. Each PDF will have its own archive path, so two files named `Electrical.pdf` can travel in the same project. Project JSON will also be available on its own and will reference any drawings that need to be supplied separately. [Export behavior](feature-ideas.md#plan-and-project-exports)

## How we'll build it

The [PDF tooling research](../research/pdf-tooling.md) lists built-ins, writer candidates, the desktop round-trip result, and the Android prototype checks.

The [PDF editor workflow research](../research/pdf-editor-workflows.md) covers tracing a run from a plan, generating diagrams and plan overlays from an existing run, copying routes, and implementation options for the wider editor.

- **Viewer and editor.** A new Drawings feature will use the app's active project and navigation. A PDF adapter will handle page metadata and rendering, with a Compose layer for selecting points and drawing overlays. Render the visible page region at an appropriate resolution and cache thumbnails within a memory limit. Prototype that layer against Android's `PdfRenderer`, which takes a seekable descriptor. Test it on the oldest supported Android version and on a device without Play services before choosing the final viewer. [Android PdfRenderer](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer)
- **Geometry and project model.** Keep calibration and geometry math in pure Kotlin. Save points in a defined, unrotated page coordinate frame. The viewer converts between that frame and the screen as you pan, zoom, or rotate. Store page geometry, scale references, and the source revision so reopening a view can reproduce the measurement. Use the app's existing base quantities and format rounding for display. Add drawing records and source links to the project model with empty defaults for older projects.
- **Files and adapters.** Store PDF bytes in app-managed files keyed by drawing revision, with file hashes for matching standalone JSON to the right source. Keep project metadata and drawing edits in versioned JSON. Reuse the app's picker, share sheet, and import conflict flow. Stage archive imports, check file paths, sizes and hashes, then make the project visible once its referenced files are in place. Use that same attachment storage for a full backup and restore. For annotated exports, test Jetpack's PDF save path on supported devices and a writer that can cover the app's API 26 minimum. Check that the exported copy preserves the original page content and existing annotations.
- **Project handoff.** Use stable links from saved measurements and accepted routes back to their sheet, calibration and revision. Convert reviewed geometry into the existing run path model. Keep the calculation engines reading the same project data they already use. Recalibration and revision updates can propose a replacement while showing any field edits that would be affected.

## Decisions from the user (2026-11-21)

These came out of the FieldScale comparison ([research](../research/fieldscale.md)) and override
anything above that disagrees.

- **Measured values are approximate and say so.** A length or area taken off a plan carries its
  full, unrounded value (like typed values, rounding is display-only) and is shown with ≈ wherever
  it's reused: the value picker, the calculator, runs, takeoffs.
- **Snapping.** CAD-exported PDFs snap to their vector lines. Scanned or raster plans snap by
  contrast: find the two edges of the drawn line across the tap and snap to the **center** of the
  line between them (also along the line's direction when tracing).
- **Data inside the PDF too.** Besides the annotated layer, write the drawing's JSON *into* the
  exported PDF (an embedded file), so one PDF carries its own editable measurements and the app can
  read them back. Plain `drawing.json` export stays available.
- **Projects.** A project can hold several PDFs. Exporting a project that has PDFs produces a ZIP
  (project JSON plus the original PDFs); a project without PDFs stays a single JSON file.
- **Scale.** Presets (architectural, engineering, metric ratios) and two-point calibration with a
  "measure a second known dimension" check, offline only; no AI or internet calibration. Preset
  scales are exact ratios; two-point scales are measured values.

## What we'll build first

1. **Open a plan and save a measurement.** Attach and reopen several PDFs offline. Draw a scale zone, calibrate it using the existing keypad or a preset, measure a line, and find that value in the existing picker. Check that points and lengths survive page rotation, zooming, and reopening the app.
2. **Finish the takeoff and share it.** Add multi-reference zone correction, polylines, perimeter, area, counts, and basic markups. Verify that an annotated PDF shows the markups in another reader. Round-trip editable measurements through standalone JSON paired with its source PDF and through a project ZIP with two same-named PDFs.
3. **Use the plan to build a run.** Trace a route across sheets, enter a vertical segment and pull points, and confirm the route. Check the length in Voltage Drop and the route in the Wire Pull Planner. Import a revised sheet and verify that it flags the source change without silently replacing the accepted route.

Text search, OCR for scans, page assembly, and visual revision comparison can build on these same drawing records once the core workflow works. [Drawing feature backlog](feature-ideas.md)
