# Feature ideas (backlog)

A long list of possible additions, supplied 2026-11-21. Most of it isn't meant to be built now.
It's here so good ideas aren't lost. Nothing in this file is committed work. `docs/ROADMAP.md`
stays the authoritative plan: when an idea is picked up, move it there with its acceptance tests.

## Triage

Judged against what exists today, and the app's rules: offline, as few permissions as possible,
one integrated system, one settings screen.

**Next: small to medium, building directly on existing engines, no new permissions**
- **Electrical boxes:** device box fill (D1, NEC 314.16) and pull/junction box sizing (D2,
  314.28). D2 pairs naturally with the Pull Planner's pull points.
- **Grounding and motors:** GEC and bonding jumpers (D3, Table 250.66). Across-the-line motor
  circuits (D5), reusing VFD Sizing's Article 430 tables.
- **Quick math:** an electrical formula sheet (D6): done as Electrical Formulas + the Reference formula sheet.
- **Loads:** Article 220 feeder and service loads with demand factors (F1, already planned).
  Parallel-feeder and Cu/Al what-if comparisons (F2, F3 / L6).
- **Materials:** a project-wide bill of materials and CSV export (I1, I7), extending the existing
  material takeoff.
- **Cables and pulls:** named, reusable cable definitions (G1), feeding fill, pulls and reels.
  Pull diagram turn direction and plan view (G3).
- **Calculator:** the Foot-Inch bundle (C1: memory, parentheses, metric entry), field geometry (C2)
  and spacing/layout marks (C3), all already in the roadmap.
- **Raceway rules:** support spacing (E4), PVC thermal expansion (E7), burial depth (E8, 300.5),
  stock-length cut lists (E11).
- **Results:** flag saved results and exports as stale when their inputs change (L2), and give every
  result a structured record of its rule, edition and inputs (L3). The live design engine already
  recalculates; saved items and exports don't yet.

**Later: valuable, but each is a large new subsystem or needs a permission**
- Reference hub (A1, A4–A8): v1 done, see ROADMAP "Reference and formulas". A10–A11 remain.
- Code-edition versioning (A2): citations done (`CodeRule`, docs/CODE_UPDATES.md); per-project
  edition selection remains.
- PDF viewing, plan calibration, takeoff and markups (B).
- Camera capture and offline OCR (M): needs the camera permission, requested only when used.
- Field records and commissioning (J1–J5, J9).
- Single-line diagrams and fault current (F4–F7, B7).
- Specialty calculators (H1–H7).
- Controls reference (K).
- Assemblies and catalogs (I2–I6).
- Advanced bending, tray and racks (E1–E3, E5, E6, E9, E10).
- Reel allocation and pull records (G4–G7).
- Platform extras (L4–L9).

**Optional modules (off by default, switched on in Settings)**

The user's rule (2026-11-21): these are optional extras, never needed for the core app to work.
Each would be a module that's hidden until enabled in Settings and may need a permission or
Google Play services only when it's on.
- AR room measurement (C9): needs Google Play services.
- Location features and geofencing (N3, N4).
- Bluetooth laser meters (C7), unless a specific model is chosen.
- Arc-flash beyond storing documented studies (J7).
- PV and power-quality calculators (H8, H9).
- Link-based sharing (L11).
- PDF page assembly (B14).

---

# Proposal as received

# Android Electrical & Construction Toolkit — Modular Feature Roadmap

**Status:** Proposed post-v1 capabilities; not an implementation report  
**Prepared:** 2026-11-20; **updated:** 2026-11-21 (offline camera/OCR/PDF, optional site context and AR-assisted room measurement)  
**Baseline:** Supplied codebase inventory: 10 tools implemented, Well & Pump placeholder, shared `:core:measure`, `:core:model`, `:core:electrical`, `:core:data`, `:core:ui`, `ToolModule` / `ToolHost`.  
**Purpose:** One consolidated, actionable list of features worth implementing, organized into coherent systems rather than dozens of disconnected apps.

## 0. Product direction and implementation rules

**Product:** An offline-first electrical construction/design/installation toolkit with a strong feet-inch calculator, field reference library, project-linked electrical calculations, drawings, material planning, and installation records. It is **not** a replacement for CAD/BIM, licensed engineering studies, accounting software, or a general construction-management platform.

**Priorities:** `P0` foundation/acceptance; `P1` high-return daily-use features; `P2` useful substantial extensions; `P3` specialized/advanced work once prerequisites exist. `P3` means accepted for the roadmap, not required immediately. `Existing planned` means included in the supplied roadmap already, so do not create a duplicate backlog item. Effort is relative: `S` small, `M` medium, `L` large, `XL` major subsystem.

**Non-negotiable design choices**

- Keep the existing shared `ToolModule` / `ToolHost` navigation. A *system* below is an ownership boundary, not necessarily a new drawer item or Gradle module. Only split physical Gradle modules when reusable implementation and dependency direction justify it.
- Prefer one authoritative model for project, run, circuit, panel, device, equipment, cable, material, measurement, and source. No duplicate calculators for the same rule under different names.
- Every calculation should distinguish exact arithmetic, entered/observed measurements, assumptions, catalog values, and code-derived limits. Show inputs, governing rule, source/edition, intermediate results where material, and out-of-scope conditions.
- Recalculate dependent outputs or mark them **stale** when their upstream inputs change. Never silently leave a saved pull, fill, voltage-drop, BOM, or safety result looking current after its inputs changed.
- Keep offline local projects authoritative. **Core camera capture, image correction, PDF handling, OCR, project attachment, and importing photographed schedules must work without Google Play services, network access, or accounts wherever technically feasible. **Optional ARCore room measurement is a GMS-dependent enhancement, never a prerequisite for the core app or its manual, laser, photo and PDF measurement workflows.**** External catalogs, hardware, vendor prices, and cloud/share links are optional; failures must not block calculations or reading project files.
- Make code compliance *edition and jurisdiction sensitive*. 2026 NEC publication does **not** mean 2026 is adopted on every project. Do not transplant 2023 section numbers into other editions without verification.
- User-supplied drawings, scans, photos, test results, and supplier data are preserved with attribution and revision history; no silent overwrites. Treat OCR as *unverified source extraction*: users review proposed structured records before any changes to panel/circuit/project data.
- Prefer Android framework/Jetpack APIs and a small tested set of maintained permissively licensed libraries. Do not add native engines or Play Services requirements merely because a demonstration combines them; retain capability detection and fallback paths.

## 1. Proposed system map

| System | User-facing responsibility | Suggested ownership / reuse |
|---|---|---|
| **A. Documentation & References** | Searchable field handbook, code references, diagrams, sources, procedures | `:feature:reference`, shared rule metadata in `:core:electrical` or `:core:rules` |
| **B. PDF & Drawings** | PDF reading/assembly, plan calibration, measurements, markups, diagrams, export | `:feature:drawings`, `:core:geometry`, shared report/export adapter |
| **C. Measurement & Layout** | Calculator expansion, geometry, layout, inclinometer, laser input, optional AR-assisted room survey | Extend `:core:measure`, shared measurement input adapters; AR adapter is optional |
| **D. Electrical Code Calculators** | Box, grounding, motor, transformer, protection and related rules | Extend `:core:electrical`; grouped tools rather than separate engines |
| **E. Raceway, Supports & Fabrication** | Bends, conduit systems, tray, trapeze, burial, expansion, fabrication | Reuse `RacewayBends`, `CableWeight`, `RunPath`, material catalog |
| **F. Distribution & Power Design** | Panels, feeders, fault current, one-line data, comparisons | Extend `:core:model` design engine and `:core:electrical` |
| **G. Cable, Pulling & Reels** | Cable properties, pull planning, reels, allocations, installation | Reuse `PullFormulas`, `PullRatings`, `CableWeight`, project paths |
| **H. Lighting, Energy & Low Voltage** | Lighting, PF, EV, PV, DC, PoE, battery, generator, fire alarm | Specialized calculators on shared electrical/measure engines |
| **I. Materials, Takeoff & Estimating** | BOM, assemblies, waste, cut lists, pricing comparisons | Shared `:core:catalog` and derived project takeoff service |
| **J. Field Records & Commissioning** | Photos, as-builts, inspections, measurements, test logs, labels | Project attachments and versioned structured records |
| **K. Controls & Ladder Reference** | Motor controls, diagrams, terminals, control transformer calculations | Visual reference + simple state/logic engine |
| **L. Project Platform & UX** | Persistence, backup, dependencies, import/export, search, settings | Extend existing `:core:model`, `:core:data`, `:core:ui` |
| **M. Camera, Scanning & Document Intelligence** | Photograph plans, straighten pages, OCR text, import panel schedules, capture site reference images | `:feature:capture`, `:core:vision`/OCR adapters, `:core:model` import proposals |
| **N. Site Context & Location (optional)** | User-selected project locations, opt-in photo geotags, nearby-project suggestions | `:feature:site-context` (only if justified), Android location/EXIF adapter |

**Shared library boundaries (not independent user tools):**

- **Rules & provenance:** versioned code tables, applicability predicates, citations, tests, jurisdiction override records, manufacturer-specific restrictions.
- **Catalog:** common typed specs for conductors/cables, conduits, trays, boxes, fittings, protection devices, motors, transformers, luminaires, supports and stock products. Custom entries always allowed; record source and revision.
- **Geometry:** 2D points, polylines, arcs, dimensions, page-space transforms, elevation/path segments and orientation. Geometry can be generated by tools, measured from PDF or imported from a room survey; no competing geometry models. Preserve the measurement source, coordinate frame, scale, tracking quality and manual corrections for AR-derived geometry.
- **Project dependency graph:** input IDs, calculated values, provenance, version/fingerprint, stale status, recomputation, alternate scenarios.
- **Attachments & documents:** immutable originals, editable annotations as layers, references to project objects, export adapters; avoid embedding massive binary PDF/photo payloads in every project JSON record.
- **Capture/OCR:** one on-device capture pipeline for scanned plans, photographed panels/schedules, equipment labels, and general jobsite photos. Image enhancement, OCR, spatial text regions and structured extraction are adapters; they do not own project or electrical design records.
- **Project import proposals:** OCR/extracted text becomes a typed, reviewable draft with source image/page/crop/coordinates, uncertainty, and validation errors. Only an explicit user confirmation writes to `:core:model`.
- **Optional location context:** project site coordinates and photo GPS metadata are separate from project identity. No automatic active-project changes or background tracking required by the core app.

## A. Documentation & References — `P0/P1`, then `P2`

**Goal:** A usable offline field handbook and authoritative explanation layer for every tool—not just a pile of links.

- [ ] **A1 · P0 / M — Searchable offline reference hub.** Browse by subject (raceway, conductors, panels, motors, grounding, safety), search terms/tables/symbols, favorites, recently opened, and deep-link from each calculation to its relevant entry.
- [ ] **A2 · P0 / L — Versioned electrical rules.** 2020/2023/2026 NEC support *only for rules actually verified and tested*; store per-project edition, locality/AHJ amendments and adoption notes; each result carries the exact rule/table version, assumptions and caveats. Preserve prior-edition results when projects are opened later.
- [ ] **A3 · P0 / M — Explainable calculations.** Inputs → formula/table lookup → adjustment factors → conclusion; identify the controlling rule and alternative paths; accessible explanation from the result without copying rule text into every UI screen.
- [ ] **A4 · P1 / S — Electrical symbols, abbreviations and terminology.** Searchable symbols for power, controls, diagrams, receptacles, motors and equipment; include manufacturer variations when relevant.
- [ ] **A5 · P1 / S — NEMA plug/receptacle visual reference.** Configuration, nominal rating, grounding and conductor arrangements, straight/locking types; do not imply every configuration fits every installation.
- [ ] **A6 · P1 / S — Knockout / hole saw / conduit trade-size reference.** Separate trade size, actual nominal knockout hole dimensions, selected punch/cutter and manufacturer tolerances; link to enclosure layout.
- [ ] **A7 · P1 / S — Phase colors / circuit numbers.** Use panel phase rotation and odd/even circuit map already modeled; offer *editable local color conventions*, not a universal mandated three-color rule.
- [ ] **A8 · P1 / M — Installation requirements quick reference.** Raceway support/termination, burial/cover, equipment working clearances, enclosure requirements, conductor bending space and inspection prompts; linked to the relevant calculators.
- [ ] **A9 · P2 / M — Manufacturer and user document catalog.** Attach product manuals, approved instructions, spec sheets, cut sheets, captured pages and installation diagrams to equipment/material definitions; index native PDF text and *verified* OCR text for offline search, with source image/page, revision/date and source URL when known.
- [ ] **A10 · P2 / S — Procedure/checklist library.** Editable templates for installation verification, pull preparation, panel startup, inspection and commissioning; checklists execute within Field Records (System J), not duplicated in the reference database.
- [ ] **A11 · P2 / M — Source freshness and conflict handling.** Identify user-entered vs manufacturer-listed vs standards-derived values; warn on missing or mismatched editions and stale catalog documentation.

**Guardrails:** Official codes and technical standards may be copyrighted/licensed. Prefer licensed access, permitted tables, references and original explanatory text—not scraping or shipping a full NEC/NFPA/NETA book. Distinguish legal requirements, installation recommendations, examples, and local convention.

## B. PDF & Drawings — major standalone system (`P1`→`P2`, `XL` collectively)

**Goal:** One drawing/document pipeline that supports import, calibrated takeoff, route tracing, annotation, visual diagrams and export. **Keep it separate from the electrical calculation engines.**

The [PDF plan measuring proposal](pdf-plan-measuring.md) describes the first field workflow, how it connects to existing tools, and the order to build it.

- [ ] **B1 · P1 / M — Robust PDF viewer and file handling.** Multipage PDFs, thumbnails, zoom/pan, page rotation, page selection, page labels, large file handling, Android file picker/open-with, offline access; render embedded imagery without corrupting originals. Prefer Jetpack PDF where runtime/device compatibility is verified, with an offline fallback for unsupported devices.
- [ ] **B2 · P1 / L — Scale zones.** Draw one or more zones on a sheet. Give each zone a preset scale or calibrate it with known distances entered on the shared measurement keypad. Use multiple references to check the fit and correct stretch or skew when the geometry supports it. [Zone calibration implementation plan](pdf-zone-calibration.md)
- [ ] **B3 · P1 / L — Drawing measurement and quantity tools.** Length, continuous polyline, perimeter, angle, area, counts, elevations entered separately, and named takeoff categories. Measurements use `:core:measure` display/precision and carry drawing/page/revision provenance.
- [ ] **B4 · P1 / L — Tap-to-route conduit takeoff.** Trace a route on a plan, add vertical drops or rises, pull points, bends and equipment connections, and create or update a `RunPath`. Show measured and entered lengths and unresolved elevations. Generate a schematic or plan overlay from a run and preview a duplicate placement before creating a new run. [Route workflows](../research/pdf-editor-workflows.md)
- [ ] **B5 · P1 / M — Markups and linked annotations.** Text, arrows, clouds, dimensions, highlights, basic shapes, equipment markers, layer visibility, undo/redo, object links; support project comments without modifying original PDF bytes.
- [ ] **B6 · P1 / M — Reusable PDF report generator.** Single report engine for calculation worksheets, panels, run summaries, pulling plans, BOMs, fabrication/cut sheets, inspection/test forms and signed closeout records; common template styles and company profile. Reuse the existing project PDF export where possible; do not add a second PDF generator without a demonstrated gap.
- [ ] **B7 · P2 / L — Auto-generated single-line diagram.** Layout vector SLD from modeled service/panel/feeder/equipment relations; represent actual hierarchy, wire/feeder sizes, breaker/main ratings, transformers, labels and source links; support manual positioning/notes. Flag missing topology rather than inventing connections.
- [ ] **B8 · P2 / M — Printable panel schedules and labels.** Existing panel PDF expanded with print-safe legends, wire/circuit/equipment identifiers, box/run tags, optional QR codes, configurable label-sheet formats and no-server fallback (QR payload with durable local ID or readable text).
- [ ] **B9 · P2 / M — Photos as markup canvases.** Dimension arrows/notes on field photos; photo link to circuit, conduit, panel or run; clear distinction between annotated estimate and a verified measurement.
- [ ] **B10 · P2 / M — Revision comparison.** Track drawings by version, highlight annotations tied to an earlier revision, manually re-anchor measurements/objects, and flag stale route/takeoff data when source sheets change. Pixel/vector diff is an optional enhancement, not a required baseline.
- [ ] **B11 · P2 / M — Vector export of app-generated diagrams.** High-quality PDF/SVG output for SLDs, bend diagrams, tray/rack layouts, control schematics, and selected drawings; share/export text and CSV through normal app exports.
- [ ] **B12 · P2 / M — Lightweight plan sketching.** Simple dimensioned rooms, walls, openings and equipment points for sites without usable drawings; accept manually entered, laser-measured, or optionally AR-surveyed room outlines from C9. Permit editing and dimension constraints after import; no full CAD editor or assumption that AR points form a validated drawing.
- [ ] **B13 · P1 / M — PDF text/content extraction and search.** Extract native selectable text, text locations, links, pages and form data *before* using OCR. Provide an explicit OCR fallback for image-only/scanned regions via System M; preserve page provenance and never assume native text has a usable table structure.
- [ ] **B14 · P2 / M — PDF document assembly and safe editing.** Merge, split, reorder, rotate/crop pages, insert scanned pages and organize a closeout package; support forms/ink annotations where APIs allow. Preserve originals; export a new document; do not promise full page-tree or arbitrary object editing from Jetpack PDF alone.
- [ ] **B15 · P1 / M — Searchable PDF scans.** Convert straightened camera/scanner pages into a multipage PDF, optionally embed an invisible Unicode text layer aligned to corrected page coordinates, and retain OCR confidence/provenance outside the PDF. Verify search/copy/highlight and text alignment across viewers and rotated pages.
- [ ] **B16 · P1 / M — PDF processing safety and compatibility.** Capability-gated rendering/editing by SDK extension/API/runtime; isolated parsing when supported; bound file size/page count/image resolution and memory use; safe timeouts/cancellation; representative tests for malformed, encrypted, very large and image-only PDFs. No assumption that all Android devices have the same Mainline PDF extensions.

### Plan and project exports

A project can contain multiple PDFs. Each drawing revision has a stable ID, and its measurements and markups reference pages within that revision. Keep the original PDF bytes in project storage. Save calibrations, measurements, markups and project links as editable data.

| Export | Contents and import behavior |
|---|---|
| **Annotated PDF** | Export one drawing with visible measurements and markups for viewing or printing. Preserve the source PDF in the project. |
| **Standalone JSON** | Export editable drawing data in `drawing.json`, including the drawing revision, page references, calibration, measurement geometry, markups and linked object IDs. Standalone project JSON is also available and includes references to the drawings. The app can relink separately supplied PDFs after checking that they match the referenced revisions. |
| **Complete project** | Export a project containing one or more PDFs as a ZIP with `project.json` and every original PDF under `drawings/`. The JSON contains editable drawing data and maps revision IDs to archive paths. Generate paths from IDs so same-named PDFs can coexist. Import restores the project, its drawings and links together. Projects without PDFs can continue to use the existing JSON export. |

Version the JSON formats and keep measurements in page coordinates with their calibration and original precision. Check source identity before applying standalone drawing data to a PDF. On ZIP import, validate schema, paths, attachment integrity and size limits before saving the project. Keep older project JSON importable.

Verify that a project with two same-named PDFs and different page calibrations survives export and import with its run links intact. Verify that the annotated PDF shows measurements in a regular PDF viewer. Confirm that standalone drawing JSON restores editable measurements when paired with its source PDF.

**Recommended PDF implementation stack (selection, not a commitment to ship every library):**

| Concern | Recommended default | Conditional alternative / limitation |
|---|---|---|
| Viewing and headless text/search/forms | `androidx.pdf:pdf-core` + `androidx.pdf:pdf-document-service` / `SandboxedPdfLoader`; `pdf-viewer` or `pdf-viewer-fragment` only when useful | Jetpack PDF is modular, but actual APIs/operations vary by version, device and SDK extension. Some processing routes still rely on Android's PDF engine/Mainline; test on AOSP and older devices. |
| Drawn PDF ink and form interaction | `androidx.pdf:pdf-ink` and `androidx.ink` when supported | App-owned geometry/markup layers remain the portable source of truth; don't make annotations depend on one platform extension. |
| Page assembly, structural edits and searchable PDF composition | `PdfBox-Android` (`com.tom-roush:pdfbox-android`) only for required operations absent from platform APIs | Additional Java/native-library load, memory/time and security review; shared `InputStream`/`Uri` must be copied to local seekable cache if a library requires a `File`. Preserve existing file restrictions and permissions. |
| Deterministic cross-device PDFium features | Consider `PdfiumAndroid` only if device matrix proves Jetpack/framework insufficient | Do not ship a second heavy PDF engine preemptively. |
| PDF generation/stamping | Reuse existing exporter, Android `PdfDocument`, vector/graphics exporter or PdfBox first | `OpenPDF` is an *alternative to evaluate*, not the default: its current Java requirements and Android compatibility must be tested. `QPDF` JNI is deferred unless concrete repair/structural needs justify it. |
| OCR on scanned PDF pages | Shared System M on-device OCR adapter after bitmap rendering | `pdf-ocr-play-services` is a possible GMS-dependent integration, **not** a core requirement; do not assume Jetpack provides offline OCR on every device. |

**Build sequence.** Start with safe PDF import, viewing, and calibrated measurements. Add takeoff tools, markups, and portable exports, then connect reviewed traces to project runs. Text extraction, searchable scans, structural assembly, and visual revision comparison can follow. Calibrated plan measurements are estimates subject to drawing accuracy, perspective, lens distortion, resizing and verified scale.

## C. Measurement & Layout — extend the existing calculator

- [ ] **C1 · P1 / M — Calculator usability bundle (existing planned).** Memory (`M+`, `M−`, `MR`, `Ans`), parentheses, editable expression cursor, metric entry, separate input-key precision and result rounding, saved values, clipboard/result picker, and improved landscape/tablet layout.
- [ ] **C2 · P1 / M — Field geometry.** Right triangles, diagonals, slope/pitch/rise/run, angles, squaring layout, circle diameter/circumference/area; reuse measured/typed `Quantity` values and exact-fraction formatting where possible. Include stair/rafter helpers when their formulas are documented.
- [ ] **C3 · P1 / S — Repeating spacing/layout marks.** Equal spacing with start/end offsets and exclusion zones; studs, joists, rack supports, devices; export a numbered field mark list.
- [ ] **C4 · P1 / M — Calculation tape (existing planned).** Editable named steps, saved sequences, delete/reorder, input history and named favorites; export to projects/reports.
- [ ] **C5 · P2 / M — Material-unit conversion from measured quantities.** Area/volume → sheets, bags, concrete yards or other user-defined units and waste allowance, without hiding assumptions.
- [ ] **C6 · P1 / M — Inclinometer with zero/tare.** Phone sensors for slope and conduit bend angles, selectable orientation, target alert through haptic/audio, optional user-recorded springback; sensor confidence/calibration indicator and no assertion of certified precision.
- [ ] **C7 · P2 / L — Bluetooth laser distance meter adapter.** A common measurement source that fills any supported length field; first implement a small tested device set and manual fallback; store the device/model and measurement source. Do not promise compatibility with arbitrary BLE LDMs.
- [ ] **C8 · P2 / S — Display/accessibility additions (existing planned).** Optional architectural notation, sounds, color-blind-safe shapes and focused one-handed keypad behavior; avoid cluttering the default keypad.
- [ ] **C9 · P3 / L — Optional AR-assisted room measurement and 2D floor-plan capture.** On ARCore-supported Android devices with **Google Play Services for AR**, offer user-guided corner tapping/hit-testing on detected horizontal/vertical planes; optionally use Depth where supported. Convert tracked 3D point/anchor poses into measured wall lengths, diagonals, heights and a constrained local room outline, then send editable 2D room geometry (and optionally a lightweight 3D wireframe) to B12 and `:core:geometry`. A floor-plane hit does **not** by itself identify the true wall/floor intersection: let users adjust corners, fit intersecting wall/floor planes only when reliable, snap to orthogonal/parallel constraints by choice, and verify at least one known/laser-measured distance. Track confidence, lost tracking, scale/drift, occlusions and cross-session limitations; label output **approximate** until checked. No heavyweight 3D engine, cloud anchors, geospatial API or automatic full-room scan required. Feature-detect via ARCore API, use `AR Optional` integration, and retain **fully functional manual/BLE laser/PDF measurement paths without Play services**. ARCore cannot be advertised as a no-GMS technique.

## D. Electrical Code Calculators — common rules engine

- [ ] **D1 · P1 / M — Junction/device box fill.** NEC 314.16 modeled with conductor counts/sizes, EGC grouping, device/yoke allowances, internal clamps, support fittings, box type, standard vs field-marked volume; version-specific treatment; attach box to a run or equipment record.
- [ ] **D2 · P1 / M — Pull/junction box sizing.** NEC 314.28 straight, angle and U-pulls and multiple entries; interactive side/entry layout; report minimum dimensions and usable candidates. Keep distinct from *device box fill* while presenting both under Electrical Boxes.
- [ ] **D3 · P1 / M — Grounding and bonding.** Extend existing EGC Table 250.122 with GEC Table 250.66, main/supply-side bonding jumpers, parallel services, electrode/copper/aluminum exceptions and rule-specific minimums.
- [ ] **D4 · P1 / M — Transformer sizing and protection.** 1φ/3φ, kVA, input/output currents, impedance and approximate transformer-limited short-circuit current, OCPD guidance, conductor sizing and rule-aware secondary conductor/tap checks; clearly separate estimated fault levels from a full system study.
- [ ] **D5 · P1 / M — Across-the-line motor circuits.** Branch conductor sizing, overload protection, motor short-circuit/ground-fault protection, starter information, starting/LRA checks; share motor tables and nominal data with existing VFD Sizing.
- [ ] **D6 · P1 / S — Electrical formula sheet/calculator.** Ohm's law, W/VA/VAR, efficiency, PF, series/parallel R, three-phase conversions, DC calculations; expose as compact quick tools using shared math rather than duplicate Unit Conversions.
- [ ] **D7 · P2 / M — Protective device selection guidance.** Compare ratings, application limits and known manufacturer constraints; make series ratings and coordination **verification workflows** when manufacturer data is absent—not a generic safety promise.
- [ ] **D8 · P2 / M — Installation-conditions calculator.** Grouping/bundling, conductor insulation and terminations, ambient/rooftop/underground conditions, existing derating/ampacity checks; one rule pipeline reused by panels, feeders, and standalone screens.
- [ ] **D9 · P2 / M — Equipment working clearance / enclosure fit.** Coordinate enclosure dimensions, working space and conductor bending space with the Drawing/Geometry and box modules; no generic assumptions about all equipment types.
- [ ] **D10 · P2 / S — Basic enclosure environmental-rating lookup.** NEMA/IP descriptions, environmental selection questions and links to actual manufacturer documents; no false equivalence between NEMA and IP ratings.

## E. Raceway, Supports & Fabrication

- [ ] **E1 · P1 / M — Multi-bend conduit piece planner (existing planned).** Multiple bend groups on one stick, piece length, accumulated shrink/gain/take-up, exact mark-from-end positions, multi-piece run integration and 360°/pull-point warnings. Reconcile duplicate claims about offset/parallel/star-arrow support by testing existing behavior first.
- [ ] **E2 · P1 / M — Segmented/compound bends.** Large-radius segmented bends and compound obstructions; diagrams, mark order, target angles, bend-specific geometry and user bender profiles.
- [ ] **E3 · P1 / S — Bender calibration.** Measured take-up/gain/star/arrow and springback, with named bender profiles and correction history; display measured vs theoretical values.
- [ ] **E4 · P1 / M — Raceway support placement.** Edition/raceway/size-specific fastening distance and maximum interval rules; auto-generate approximate support stations on routed paths and note exceptions/installation constraints. **Do not use a universal 3-ft terminal rule** for all raceways.
- [ ] **E5 · P1 / M — Conduit/trapeze rack dead-load estimator.** Multiple conduits + conductor/cable weights + fittings, per-span distributed weight, support spacing and approximate reaction loads; channel and rod pass/fail **only with selected manufacturer's rated tables, installation geometry, connection and attachment capacities**. Structural design escalation when missing.
- [ ] **E6 · P2 / L — Cable tray sizing and loading.** NEC Article 392 fill method selected by cable category, tray type and cable arrangement; fill/spare capacity and suggested tray dimensions; separately validate tray/rung load class, support spans, deflection and product ratings from manufacturer/NEMA data. Do not collapse tray design to a universal sum-of-diameters rule.
- [ ] **E7 · P1 / M — Raceway thermal expansion.** Material, run length, operating min/max temperatures, expected motion, expansion fitting travel and placement; PVC rule checks, metallic coefficient estimates and manufacturer-specific fitting capabilities. Thermal movement is not identical to rooftop ampacity correction.
- [ ] **E8 · P1 / M — Underground burial/trench calculator.** NEC 300.5 edition-specific minimum cover scenarios, voltage, wiring method, GFCI, surface conditions; cross-section, excavation/bedding/backfill/concrete quantities. Flag special applications and local civil requirements.
- [ ] **E9 · P2 / M — Knockout/enclosure entry layout.** Conduit hole sizes, multiple entries, hubs, separation, equipment/access dimensions and actual fitting clearances; coordinate pull-box geometry rather than infer minimum hub spacing from trade size alone.
- [ ] **E10 · P2 / M — Conduit rack/bundle layout.** Parallel conduit spacing, bends and changes of elevation, order, interference checks and material quantities; generate a simple 2D diagram and fabrication sheet.
- [ ] **E11 · P1 / M — Fabrication and stock-length cut lists.** Conduit sticks, strut, threaded rod, lengths/angles/mark order; scrap-aware cut optimization with standard stock lengths; export worksheet/PDF.
- [ ] **E12 · P2 / S — Raceway compatibility reference.** Couplings, bends, corrosion/environment limits, special installation conditions, manufacturer notes; shared with documentation/catalog, not hard-coded in every calculator.

## F. Distribution, Feeders & Power Design

- [ ] **F1 · P1 / L — Article 220 and feeder/service loads (existing planned).** Edition-specific occupancy, load categories, demand factors, continuous loads and assumptions; sync back to existing Panel/Circuit design rather than maintain a second load database.
- [ ] **F2 · P1 / M — Parallel feeder what-if optimizer.** Compare permitted single/multiple parallel conductor sets for ampacity, temperature derating, VD, fill, sets, EGC, conductor/cable weight, available lugs, number of conduits, installed length and cost; use existing pulling tension only when the modeled cable assembly/path supports it.
- [ ] **F3 · P1 / M — Cu/Al and wiring-method comparison (existing planned).** Alternate scenarios with user-defined prices and selected catalog products; do not optimize on price alone when installation constraints differ.
- [ ] **F4 · P2 / L — Available fault-current calculation (existing planned).** Explicit source, transformer impedance, feeder impedance, X/R assumptions and protective devices; distinguish screening estimates from a validated engineering study and record provenance.
- [ ] **F5 · P2 / M — Series ratings / selective-coordination checks (existing planned).** Require listed manufacturer combinations or verified device curves and settings. Show unknown/unsupported where those are missing.
- [ ] **F6 · P1 / M — Single-line *data model* and topology validation.** Capture source → switchgear/panels → feeders/transformers → equipment; identify missing feeds, loops, invalid connections and mismatched ratings. Drawing renderer is owned by System B (B7).
- [ ] **F7 · P2 / M — Generator and transfer equipment quick sizing.** Running loads, starting impact, phases and basic transfer arrangements; explicitly exclude detailed transient or protective engineering where not modeled.
- [ ] **F8 · P2 / S — Panel labeling and phase balancing suggestions.** Existing phase load calculations plus candidate rearrangements and user acceptance; preserve multi-pole/group constraints.
- [ ] **F9 · P2 / M — Energy use and demand summary.** Estimated kWh/peak load/cost from entered profiles and tariffs; separate estimated demand from code calculated load.

## G. Cable, Pulling & Reels

- [ ] **G1 · P1 / M — Named reusable cable definitions (existing planned).** Measured/catalog OD, geometry, weight, insulation/jacket type, pulling tension limits, SWBP, min bend radius, conductor composition, source and confidence. Feed Conduit Fill, Wire Pull Planner, Reels and BOM.
- [ ] **G2 · P1 / M — Sourced friction/pressure presets.** Conduit × cable jacket × lubricant/installation method, with test provenance and user overrides; never claim a generic value as universally safe.
- [ ] **G3 · P1 / S — Pull diagram improvements.** Turn direction for horizontal bends, plan view, ordered segment IDs, rated limit annotations; preserve forward/reverse comparison.
- [ ] **G4 · P1 / M — Wire reel allocation optimizer.** Assign cable needs across available reels by OD/spec/length; include cut length, head/tail/slack/waste, minimum useful remainder, available stock; do not auto-combine dissimilar cable.
- [ ] **G5 · P2 / M — Pull equipment/setup record.** Enter puller/sheave/roller ratings, direction, lubricant, crew notes and pre-pull checklist; do not turn an estimated pulling force into approval of unspecified equipment.
- [ ] **G6 · P1 / M — Cross-validation of pull calculations.** Matched-settings test against available Re³/SIMpull references and worked examples; include uncertainty, representative edge cases and regression fixtures before presenting results as trusted.
- [ ] **G7 · P2 / S — Cable color, tag and installation tracking.** Conductor/phase identifiers, reel usage, installed cable quantities, remaining-to-pull and printable tags, attached to each run.

## H. Lighting, Energy & Low Voltage

- [ ] **H1 · P2 / M — Interior lighting lumen-method layout.** Room dimensions, height, target illuminance, luminaire lumens, utilization/maintenance factors, fixture count, grid spacing, wall offsets, LPD and calculated circuit load; explicitly mark preliminary results, not photometric/UGR/glare/egress compliance.
- [ ] **H2 · P1 / S — Power factor correction.** kVAR required for a target PF, expected current reduction, capacitor comparison and screening of demand effects; no promise of freed electrical *code capacity* without a valid load/thermal/protection assessment.
- [ ] **H3 · P2 / M — Low-voltage/DC power.** 12/24/48 V distribution, cable voltage drop, power/current/resistance, source/load limits and wire selection. DC conversion functions extend existing units rather than duplicating them.
- [ ] **H4 · P2 / M — PoE budgeting.** IEEE 802.3af/at/bt power classes, source/switch budgets, cable length/drop and installation considerations; use verified cable resistance/temperature data, not Ethernet performance promises based only on voltage drop.
- [ ] **H5 · P2 / S — Fiber optical link budget.** Tx power, Rx threshold, length-dependent attenuation, connector/splice loss, safety margins and basic multimode/single-mode compatibility reminders.
- [ ] **H6 · P2 / M — Battery/UPS runtime and fire-alarm battery sizing.** Standby vs alarm currents/durations, efficiency, derating, available Ah and manufacturer constraints; identify applicable fire alarm standard/edition when using code acceptance logic.
- [ ] **H7 · P2 / M — EVSE load and energy-management quick checks.** Continuous loads, panel/service headroom and user-entered load management rules; no default assumption that simple spare-breaker-space means spare capacity.
- [ ] **H8 · P3 / M — PV string and basic conductor checks.** Cold-temperature Voc correction from verified location/design temperature, max inverter voltage, current and conductor checks. Requires model/inverter rated data; not a complete PV design package.
- [ ] **H9 · P3 / M — Power quality quick reference/calculations.** Basic voltage imbalance, neutral loading estimates and harmonic quantities when measured inputs are available; flag when full metering and analysis are required.

## I. Materials, Takeoff & Estimating

- [ ] **I1 · P1 / L — Project-wide BOM.** Consolidate all per-run takeoffs and modeled panels/equipment into quantity-by-spec lines: conduit lengths, conductors grouped by size/type/color, grounding, boxes, fittings, supports, breakers and equipment. Show calculation/source IDs, manual overrides and missing quantities.
- [ ] **I2 · P1 / M — Reusable installation assemblies.** User-maintained templates for receptacles, drops, devices, bends, supports, racks and common equipment; material derivation + user-editable allowances, not magical fixed counts.
- [ ] **I3 · P1 / M — Material catalog.** Named product definitions (including custom cable), supplier/manufacturer SKUs, specs, pack sizes, units, standard stock lengths, cost source/time and substitutability limits.
- [ ] **I4 · P1 / M — Cut/waste and stock optimization.** Reuse fabrication system E11; compare stock lengths, minimum remnant, kerf/cut allowances and quantity plans for conduit/strut/rod. One algorithm used by per-piece fabrication and the BOM.
- [ ] **I5 · P1 / M — Material and labor scenarios.** Compare materials on unit cost, quantities, waste and user-defined labor units; do not build accounting/payroll/invoicing. Distinguish quote vs actual vs estimate prices.
- [ ] **I6 · P2 / M — Jobsite stock and reel inventory.** Simple on-hand, consumed, allocated, available, shortage and reserved quantities; no real-time multi-warehouse ERP.
- [ ] **I7 · P1 / S — CSV quotes and purchase lists.** Export normalized CSV with item, description, spec, unit, quantity, vendor/SKU and notes; import supplier price columns without overriding source item identity.
- [ ] **I8 · P2 / M — Change impact report.** Compare design revisions: material delta, estimated cost and labor delta, affected runs and prior quantities. Reuse project dependency/revision engine.
- [ ] **I9 · P2 / S — Material quantity helpers.** Sheet/bag/concrete/pieces quantities from user-defined coverage, volume and waste; accessible from construction measuring and assembly flows.

## J. Field Records, Safety & Commissioning

- [ ] **J1 · P1 / M — Field photos and location-linked notes.** Attach captured or imported images to project/panel/circuit/run/box/equipment, annotate in System B, search optional extracted text via System A, and retain date/user/source metadata. Link to a *site/location* when explicitly chosen; GPS is optional. Keep originals unchanged.
- [ ] **J2 · P1 / M — Planned vs installed vs tested.** Distinct record states, change reasons and measurement provenance. Recompute affected calculations when verified as-built length, size or route differs from design; don't silently edit the original design.
- [ ] **J3 · P1 / M — Structured inspection and punch checklists.** Reusable templates, result status, deficiencies, corrective action, evidence photos, completion status and signatures; limited workflow, not a general project-management platform.
- [ ] **J4 · P1 / M — Field test and commissioning records.** Phase-phase/phase-ground voltage, current, continuity, polarity, phase rotation, insulation resistance (megger), test voltage/duration, ambient conditions, equipment and instruments, date, technician and result status.
- [ ] **J5 · P1 / M — Torque verification.** Manufacturer-defined target torque, location, measured/confirmed torque, tool ID/calibration and sign-off; NEC 110.14(D) linkage. **Never infer lug torque solely from conductor size.**
- [ ] **J6 · P2 / M — Acceptance-test templates.** For applicable equipment, user-licensed/verified NETA or manufacturer procedures, recorded thresholds and test histories; no embedded copyrighted test tables without rights, no one-size-fits-all pass/fail thresholds.
- [ ] **J7 · P2 / M — Arc-flash documentation and controlled lookup.** Store qualified assessment, study date, approach boundaries, calculated incident energy **or** correctly eligible NFPA 70E table-method PPE category, operating conditions, author and equipment labels. Built-in method selection only with verified edition-specific prerequisites; **do not calculate a PPE category from arbitrary incident energy** or label equipment safe from crude fault-current estimates.
- [ ] **J8 · P2 / M — Printable warning/equipment and QR labels.** Shared label generator B8; include validated study details only, source/review date and local record ID. Scanning should open the local project when available and still provide useful offline readable information.
- [ ] **J9 · P2 / M — Closeout packet.** Generate PDF documentation with approved panels/SLD, as-builts, photos, pull data, material quantities, test logs, exceptions and signoffs from existing models; every included record has date and provenance.
- [ ] **J10 · P3 / S — Simple equipment history.** Test/service snapshots and follow-up reminders linked to installed equipment, not a full maintenance management system.

## K. Controls & Ladder Reference

- [ ] **K1 · P2 / M — Motor-control schematic library.** 2-wire, 3-wire seal-in, Hand-Off-Auto, forward/reverse interlocks, starters, control relays, timers and common control transformer arrangements; show contact/coil relationships and explanatory states.
- [ ] **K2 · P2 / M — Interactive ladder logic viewer.** Tap an input/relay contact and visualize coil state and control path for a **limited, explicitly modeled** set of sample circuits; supports correct normally-open/closed and timed-contact representations. Reference/training tool, not PLC/industrial control verification.
- [ ] **K3 · P2 / S — Terminal numbering and wire-reference mapping.** Labels, cross-references, auxiliary contacts, and printable simple wiring schedule; custom definitions allowed.
- [ ] **K4 · P2 / S — Control transformer VA sizing.** Inrush/sealed VA demand for known contactors/relays, load summation, voltage and manufacturer selection warnings.
- [ ] **K5 · P3 / M — Project-linked control diagrams.** Attach a selected standard circuit or small custom schematic to equipment, export vector PDF/SVG through System B; no full PLC programming IDE.

## L. Project Platform, Data Integrity & UX

- [ ] **L1 · P0 / L — Complete backup/restore acceptance (existing open).** Backup *all* projects, attachments (original photos/PDFs, scans, OCR drafts/text, markups), settings, tool data and recents; restore into an existing installation with conflict choices, verification and non-destructive rollback. Preserve damaged files for diagnosis; test migrations and roundtrip fidelity.
- [ ] **L2 · P0 / M — Dependency/stale-result system.** When loads, cable definitions, path dimensions, code edition or catalog specs change, recompute safe deterministic values or mark downstream reports/BOM/pulls/panels as stale pending review.
- [ ] **L3 · P0 / M — Structured result provenance.** Inputs, units, numeric basis, precision, source/edition, manufacturer data ID, method version, timestamp and applicability flags; printable explanation audit trail.
- [ ] **L4 · P1 / M — Reusable project/equipment templates.** Common circuits, distribution hierarchies, equipment specs, run profiles, bend presets and installation assemblies with clear override behavior.
- [ ] **L5 · P1 / S — Project/global search and favorites.** Find a circuit, value, device, run, calculation, drawing or document; pin frequent tools and saved calculations.
- [ ] **L6 · P1 / M — Scenario/alternative comparisons.** Side-by-side choices for copper vs aluminum, parallel sets, conduit sizes, pulling directions and material cost; variants do not silently mutate selected design.
- [ ] **L7 · P1 / M — Batch editing and validation.** Apply selected settings or selected circuit properties consistently across targets; show impacted entities and validate before committing.
- [ ] **L8 · P1 / M — Import/export interoperability.** Project JSON, complete backup, text, CSV, PDF/SVG and native Android share/open-with; make schemas versioned and verify saved/restored attachments.
- [ ] **L9 · P1 / M — Useful calculation state.** Recent results, named favorites, per-choice notes, reversible edits, undo/redo where supported, and conflict explanations.
- [ ] **L10 · P0 / S — Motion/accessibility acceptance review (existing open).** Inspect animation-on/off states, reduced-motion preference, high-contrast/color-independent controls, touch targets, screen-reader labels, large text, tablet/landscape.
- [ ] **L11 · P2 / M — Optional link-based sharing.** Only if a secure, privacy-aware relay/sync mechanism is justified; local file share remains the default. Do not require accounts/cloud access for normal use.
- [ ] **L12 · P1 / M — Technical regression fixtures.** Reference worked examples for NEC editions, real installations, geometry, PDFs, electrical boundary conditions, roundtrip imports and hardware devices; add scanned/photographed panel schedules, skewed/folded plans, OCR errors, image/PDF metadata privacy, Play-less/AOSP devices and source-to-project validation; automated results check and UI smoke tests.

## M. Camera, Scanning & Document Intelligence — separate shared system

**Goal:** One camera and image-processing workflow serving **three distinct uses**: (1) capture, straighten and measure photographed plans; (2) read panel schedules, labels and other printed information into *reviewed* structured project records; and (3) preserve field/site reference photos with markups. No Google Play services or network dependency for core flows.

- [ ] **M1 · P1 / M — Unified camera capture and image import.** CameraX `Preview`, `ImageCapture` and optional `ImageAnalysis` for sharp full-resolution photos, crop/retake, flash/torch, gallery/system picker import, batch capture, multi-page ordering and orientation correction. User chooses **Plan / Read text / Panel schedule / Field reference** intent but can change it later.
- [ ] **M2 · P1 / L — Offline document detection and perspective correction.** Detect page/plan corners, rotate, crop, deskew/rectify perspective, improve contrast, handle shadows and optionally dewarp; always allow manual four-corner adjustment and compare original vs processed image. OpenCV image-processing primitives are a candidate, not a mandatory dependency. Don't imply a perspective-corrected photograph has a calibrated engineering scale.
- [ ] **M3 · P1 / M — On-device OCR with bounding boxes.** Extract text from photos, drawings and rendered PDF pages, preserving words/lines, coordinates, orientation, language and confidence where supported. Primary candidate: **bundled** `com.google.mlkit:text-recognition` (model shipped with app; not the GMS-downloaded variant). Evaluate `Tesseract4Android` as a fully open-source/offline engine or alternative for additional scripts/configuration. Choose based on real fixtures, AOSP behavior, footprint and maintenance.
- [ ] **M4 · P1 / M — Text capture actions.** Select/copy recognized text, search in project's own reference library, save as searchable project notes, insert verified measurements/identifiers into active inputs, or attach recognized labels to equipment. Show image crop next to extracted text; corrections remain editable and attributable.
- [ ] **M5 · P1 / L — Panel-schedule photo → structured import.** Detect panel header (name, voltage, phases, spaces, bus/main where legible), odd/even breaker rows, pole grouping, labels, breaker ratings and loads. Produce **an editable import preview**, indicate unclear text, show source crops/row mapping, identify duplicate/contradictory circuit numbers, compare against an existing panel and *only then* create/update confirmed `Panel`/`Circuit` records. Do not infer conductor sizes, load calculations or compliance from unreadable cells.
- [ ] **M6 · P2 / M — Equipment and electrical label extraction.** Read nameplates, model/serial, voltage, current, HP/kW, phase, kVA, ratings and manufacturer markings; link to user-selected equipment or catalog entries only after verification. Source image and units are retained; don't interpret a nameplate OCR result as an authoritative product rating without review.
- [ ] **M7 · P1 / M — Photograph plan → drawing.** Import captured floor plans, crop/rectify, optionally OCR callouts/room labels, save as image-backed drawing or PDF page in System B; require explicit known-dimension/two-point calibration and a check dimension before any route lengths enter `RunPath`. Preserve original and transformation history.
- [ ] **M8 · P1 / S — Field reference photo workflow.** Quick capture → pick current project/run/panel/box/equipment or create an unassigned inbox item → add notes, measurement arrows, tags and optional OCR → later locate from project search and reports. No need to choose a location or run a scanner for ordinary photographs.
- [ ] **M9 · P2 / M — Multi-page document scanning.** Batch capture, image ordering and retakes, rotate/crop, export original photos and corrected scans to multipage PDF via System B, with optional OCR/searchable text and project attachment; all steps usable offline.
- [ ] **M10 · P2 / M — Structured table/diagram extraction.** OCR spatial grouping for rows/columns/labels, especially messy panel schedules and printed electrical tables. Prefer deterministic geometry/layout rules plus user correction; evaluate heavier on-device ONNX/PaddleOCR/RapidOCR only if tested fixture accuracy justifies model size and runtime cost. Never assume OCR provides reliable column identity by itself.
- [ ] **M11 · P1 / M — Secure capture provenance and review.** Original immutable image, corrected derivative, crop/homography, extracted tokens, source coordinates/confidence, user's corrections, draft→confirmed status and associated record ID. Display actionable warnings on low confidence/ambiguous columns; prevent unreviewed OCR writes into engineered calculations.
- [ ] **M12 · P1 / M — Real-device offline camera/OCR QA.** Exercise blurred, skewed, rotated, shadowed and low-light forms; multi-column schedules; handwriting (not assumed supported); dense plan text and small labels; landscape and portrait; AOSP device without Play services; memory-limited devices; duplicate imports and app restart/recovery.

**Recommended no-Play-services implementation layering:**

| Layer | Default / fallback | Notes |
|---|---|---|
| Capture | CameraX (`camera-core`, `camera-camera2`, `camera-lifecycle`, optional `camera-view`), Android photo/file pickers | CameraX does **not** require Google Play services. Use full-resolution still capture for document OCR; use live `ImageAnalysis` only when it improves user experience. |
| Crop and rectify | Android graphics for simple operations; evaluate OpenCV for robust document-edge detection + `warpPerspective` | Correct rotation before applying OCR coordinate transforms; lens distortion/curved pages require additional correction or manual review. |
| Primary offline OCR | **Bundled** ML Kit Text Recognition v2 (`com.google.mlkit:text-recognition`) | Google-provided proprietary library, but model packaged inside the app; unlike `com.google.android.gms:play-services-mlkit-text-recognition`, no Play Services model download. Verify device behavior and keep replaceable. |
| Open-source OCR option | `Tesseract4Android` (`cz.adaptech.tesseract4android:tesseract4android`) and packaged `.traineddata` | No Play services; more engine/language assets and tuning. Evaluate accuracy/speed with actual panel schedules before selecting it as primary/fallback. |
| Advanced recognition | ONNX Runtime + suitable local OCR/layout models *only if needed* | Optional; high implementation, size and battery costs. Never make basic OCR depend on a cloud or a vision LLM. |
| Domain extraction | Kotlin parser/geometry grouping → typed `ImportProposal` → user review → `:core:model` | Preserve unknowns and conflicting values, don't guess measurements, phase assignments or incomplete electrical ratings. |
| Searchable scan export | System B PDF writer with Unicode-capable embedded fonts and correctly transformed text positions | Simple Helvetica/ASCII-only text overlays are insufficient for robust multilingual OCR, positioning or selectable text; test rotated/scaled pages. |

**Not selected as defaults:** Play Services ML Kit Document Scanner (automatic scanner depends on GMS), dynamically downloaded OCR models, Jetpack PDF's `pdf-ocr-play-services` OCR provider, remote OCR/API calls. All may be opt-in alternatives later, but no core user task should require them.

## N. Site Context & Location — optional, privacy-first

**Goal:** Make jobsite context convenient without requiring location permissions or sacrificing the offline-first project model. **Do not make geofencing an app foundation.**

- [ ] **N1 · P2 / S — Manual site details.** Project address/name, optional approximate map point/site area and user-set radius; support adjacent buildings/floors or multiple sites with the same address. Manual project switching remains the default.
- [ ] **N2 · P2 / S — Optional geotagged attachments.** On user request, obtain current coordinates via Android `LocationManager`/`LocationManagerCompat`, store project association and GPS accuracy/time in local metadata; optionally write GPS EXIF (`androidx.exifinterface`) or PDF XMP/custom metadata on *export copies*. Do not attach precise GPS to every photo/PDF by default.
- [ ] **N3 · P2 / M — Nearby-project suggestion while app is open.** With foreground location permission, compare current **accurate enough** fix against saved site regions; present candidates (including overlapping sites) for user confirmation, rather than silently replacing an active project or clearing it on a transient EXIT. If location unavailable or ambiguous, stay on current/manual selection.
- [ ] **N4 · P3 / M — Optional background arrival notifications.** **Defer unless clearly useful.** Android's standard `GeofencingClient` is **Google Play services-dependent**; there is no equally reliable, low-power, universally available non-GMS drop-in. If shipped as optional adapter, require separate opt-in, appropriate foreground/background permissions, notification policy and reboot/error recovery. Do not continuously poll GPS as an assumed equivalent.
- [ ] **N5 · P2 / S — Location privacy, sharing and persistence.** Show whether an attachment contains GPS data; allow removing or excluding it before share/export and preserving private project association separately; restore user-selected site records in backup. Do not auto-upload site locations, and never treat approximate location as proof someone is in a specific room/floor.

**Geofence detail (only for N4 if ever approved):** Respect the Android/Play Services geofence limit and device accuracy; use dwell/debounce as appropriate, re-register after reboot and relevant errors, and rotate a limited set of closest sites only on reliable location updates. WorkManager is not a continuous real-time location service and cannot guarantee switching when a user crosses into a previously *unregistered* distant region. Background location access is regulated by Android/Play policy; this feature must be optional and not required for capture, OCR, or projects.

## 2. Existing planned features retained, not duplicated

The following remain in the original roadmap and are incorporated above or left in their existing owning tool:

- Foot-Inch: memory/Ans, parentheses, metric entry, √/square, geometric/stair/rafter/circle helpers, expression editing, landscape/tablet, tape, series marks, favorites/input picker, material units, input vs output precision, exports, notation and accessibility.
- Bending: multiple bend groups/pieces, developed lengths and bender-specific calibration gaps; validate which parallel/concentric/star/arrow/box-offset functions are *actually* complete before creating work items.
- Conduit Fill: named cable OD and cross-tool reuse.
- Wire Pull: Re³ fixture comparison, cable catalogs, friction sources and horizontal bend orientation.
- Panels: Article 220 load/demand calculations, fault current, series ratings, selective coordination.
- Projects: full-application backup/restore, optional share links; minimal animation acceptance.
- Existing VFD, Voltage Drop, Partial Reel, Unit Conversions and Panels tools remain maintained; use shared electrical inputs instead of adding duplicate UI workflows.
- Well & Pump is **already a placeholder**. Keep it deferred until the reference app's exact scope and audience are established; if implemented, add well volume, grout/gravel pack, pump TDH and pump sizing as its own bounded tool using `:core:measure`.

## 3. Implementation order and gates

| Wave | Scope | Reason / minimum acceptance gate |
|---|---|---|
| **0 — Reliability and rules** | L1–L3, L10, L12, A2–A3 | Full-app restore demonstrably works; project results can identify edition, method and stale dependencies; regression tests established. |
| **1 — Small high-use field tools** | D1–D6, A1/A4/A6/A7/A8, C2/C3, E3/E4, H2 | Accurate, independently testable daily tasks; do not add redundant standalone engines. |
| **2 — Raceway, pulling and materials** | E1/E2/E5/E7/E8/E11, G1–G4/G6, I1–I4/I7 | Reuses existing paths, conductor rules, weights and material takeoff; BOM reconciles against runs. |
| **3 — Documentation, PDF and field workflow** | B1–B6, B13/B15/B16, M1–M5/M7/M8/M11/M12, J1–J5, J9, L4–L9 | Camera plan → rectify → verify scale → route; schedule photo → OCR → reviewed panel import; reference photo → linked notes; searchable scans/backups all work **offline without Play services**. |
| **4 — Distribution and advanced fabrication** | F1–F6, B7/B8, E6/E9/E10, I5/I6/I8 | Code edition and catalog data mature; SLD reflects actual topology; supports/loads use manufacturer ratings. |
| **5 — Specialty field systems** | C6/C7/C9, H1/H3–H9, J6–J8, K1–K5, B9–B12/B14, M6/M9/M10, N1–N5 | Specialized document capture, advanced layout extraction and site context remain optional/additive; safety and device integrations require verified procedures and fixtures. |

**Parallel work is possible:** User-facing reference UI and calculator improvements can be built while the PDF pipeline is in development. Do **not** let the PDF feature block ordinary text/CSV exports or basic drawings generated by the current tools.

## 4. End-to-end acceptance scenarios

1. **Design-to-install:** Create panel/circuits → size conductor/raceway → draw/measure run → add bends/pull sections → inspect fill/tension → BOM/cut list → mark installed → closeout report.
2. **Change propagation:** Upsize conductor or change cable catalog OD → flag and recalculate fill, voltage drop, pulling, material quantities, labels and saved exports; preserve design revision.
3. **Electrical boxes:** Enter conductor counts/devices → correct box fill per selected edition → add multiple conduit entries → pull-box size → geometry diagram and BOM line; results show sources.
4. **Parallel feeder alternatives:** Enter heavy feeder → compare legal configurations with installation/ampacity/VD/fill/weight/cost constraints → save chosen scenario without corrupting others.
5. **Trapeze/cable tray:** Multiple raceways/cables → actual distributed load → support span and catalog rating check → report incomplete where hardware ratings/attachments are unknown.
6. **PDF route:** Import scanned or vector plan → calibrate per page → measure known check dimension → route with vertical segments → generate RunPath and work plan → reopen drawing on another device after complete backup/restore.
7. **Commissioning:** Attach original photo and markup → verify equipment manufacturer torque → record test settings/readings → sign off → print report with revision/date → no invented passing threshold.
8. **Arc-flash boundary:** Without eligible table prerequisites or a documented study, system returns **insufficient data / qualified assessment required**, not an arbitrary PPE category.
9. **Offline/recovery:** Launch without network or Google Play services, complete calculations and reports, export/restore complete data into an existing device, preserve attachments and handle a deliberately damaged import without deleting it.
10. **Photographed plan:** Capture paper plan without Play services → detect corners/deskew with manual correction → retain raw photo → calibrate against known dimensions → verify a second reference → trace a run and export a searchable PDF.
11. **Photographed panel schedule:** Photograph crooked/partially shaded panel directory → OCR → table reconstruction with source-crop review → detect uncertain/missing circuits and multiple-pole spans → preview differences against an existing panel → confirm selected fields only → preserve evidence and undoable import transaction.
12. **Field photo and text:** Capture a site condition photo → attach to run/box/panel or inbox → annotate → optionally extract text for search/notes → include in closeout PDF; no GPS/camera-location permission required.
13. **AOSP and privacy:** Run CameraX, rectification, OCR, image PDF export, PDF opening and panel import on a supported no-GMS test device; location off by default; exported files optionally strip geotags; unavailable SDK PDF features fall back without losing the original document.
14. **Optional AR room capture:** On a supported device with Google Play Services for AR, tap and revise visible room corners → inspect plane/depth hits and tracking state → compare a wall length/diagonal against a known or laser-measured reference → export editable 2D outline into B12 and save measurement provenance. On unsupported/no-GMS devices, AR is absent or disabled without breaking manual layout, CameraX, plan photography or PDF measuring. Check drift, occlusion, wrong-plane hits, incomplete rooms and session restarts; never silently claim survey-grade accuracy.

## 5. Deliberately excluded or constrained

**Not recommended as product modules:** full AutoCAD/BIM/CAD editor, automatic full-building AR scanning/point-cloud reconstruction; complete building photometric simulator; certified structural engineering solver; full arc-flash / protection-coordination study engine; PLC programming IDE; contractor ERP/payroll/invoicing; team time tracking, workforce scheduling, messaging and general task management; cloud-only project architecture; enormous manufacturer catalog mirror; arbitrary BLE compatibility promises.

**Constrained but included:**

- Arc-flash feature = documented assessments and verified method-constrained lookup/labeling, not unsafe instant PPE determination.
- Trapeze and tray checks = load takeoff + **manufacturer-rated** support/component evaluation, not unsupported structural approval.
- Lighting = preliminary lumen-method count/spacing, not certified illuminance uniformity, photometric/egress/glare design.
- Fault current = assumptions/provenance and screening model until protective device/system data supports a full verified result.
- Controls = bounded, explainable ladder references and training visualization, not an industrial safety controller.
- Estimating = material/labor scenario support, not bookkeeping/accounting.
- OCR = human-reviewed data capture and document search; **never silently trust photographed panel schedules or nameplates as engineered data**.
- Location = optional convenience, not a requirement for projects, scanning, attachments or automatic project selection; defer Play-Services background geofencing.
- AR-assisted room measurement = **optional ARCore/GMS adapter** only; produces approximate dimensions and editable geometry, not a guaranteed precision survey or a requirement for AOSP devices.
- Do **not** bundle PdfBox, OpenPDF, Pdfium, QPDF, Tesseract and ONNX all at once. Begin with a proven small stack; add only what user-facing fixture tests demonstrate is missing.

## 6. References and research leads

These are research anchors and potential validation sources, **not authorization to reproduce copyrighted standard text**. Verify edition-specific rules in the adopted standard and manufacturer data before release.

- NFPA: [NFPA 70 (NEC), including the 2026 edition](https://www.nfpa.org/product/nfpa-70-standard/p0070code); [NFPA NEC development / revision](https://www.nfpa.org/codes-and-standards/nfpa-70-standard-for-electrical-wiring/70).
- NFPA: [Correct use of incident-energy and PPE-category methods](https://www.nfpa.org/news-blogs-and-articles/blogs/2022/01/14/a-better-understanding-of-nfpa-70e-correctly-using-the-incident-energy-analysis-and-arc-flash-ppe).
- OSHA: [Electrical arc-flash hazard resources](https://www.osha.gov/electrical/flash-hazards).
- Eaton: [Cable tray design considerations (manufacturer)](https://www.eaton.com/content/dam/eaton/products/support-systems/cable-management/b-line-cable-tray-design-considerations.pdf); [NEMA metallic cable tray products](https://www.eaton.com/us/en-us/products/support-systems/b-line-series-cospec-specifier-center/cable-tray-systems-and-supports-cable-management/metallic-cable-tray-systems-nema.html).
- InterNational Electrical Testing Association: [ANSI/NETA ATS-2025](https://www.netaworld.org/standards/ansi-neta-ats); [Commissioning standard / scope](https://www.netaworld.org/standards/ansi-neta-ecs).
- Bluebeam: [Scale calibration, measurements and takeoffs](https://support.bluebeam.com/user-manual/menus/tools/takeoffs-measurements.html).
- Leica: [DISTO Plan / Bluetooth laser measurement workflows](https://shop.leica-geosystems.com/measurement-tools/disto/leica-disto-plan-app).
- Android: [Jetpack PDF releases / modular artifacts](https://developer.android.com/jetpack/androidx/releases/pdf); [PDF `SandboxedPdfLoader` / core APIs](https://developer.android.com/reference/androidx/pdf/package-summary); [PDF viewer platform requirements](https://developer.android.com/develop/ui/views/layout/pdf/implement-pdf-viewer).
- Android: [CameraX still capture](https://developer.android.com/media/camera/camerax/take-photo); [CameraX image analysis](https://developer.android.com/media/camera/camerax/analyze); [platform LocationManager](https://developer.android.com/reference/android/location/LocationManager).
- Google: [ML Kit Text Recognition v2, bundled vs unbundled](https://developers.google.com/ml-kit/vision/text-recognition/v2/android); [model install paths](https://developers.google.com/ml-kit/tips/installation-paths).
- Open source: [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android); [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android); [OpenCV Android build](https://github.com/opencv/opencv/wiki/Custom-OpenCV-Android-SDK-and-AAR-package-build); [OpenPDF / Android and Java requirements](https://github.com/LibrePDF/OpenPDF).
- Android: [Geofencing requires Play Services and has reboot/error constraints](https://developer.android.com/develop/sensors-and-location/location/geofencing); [background location restrictions](https://developer.android.com/develop/sensors-and-location/location/background); [EXIF/media location permissions](https://developer.android.com/training/data-storage/shared/media).
- Google ARCore: [AR Required versus AR Optional and Play Services for AR dependency](https://developers.google.com/ar/develop/java/enable-arcore); [hit-testing against planes/depth](https://developers.google.com/ar/develop/java/hit-test/developer-guide); [Depth availability](https://developers.google.com/ar/develop/java/depth/developer-guide); [tracking drift and anchor behavior](https://developers.google.com/ar/develop/anchors).

---

### Roadmap maintenance

Treat this file as a **proposal** until reconciled with the live `docs/ROADMAP.md` and code/tests. The PDF/OCR/library options are verified research directions, **not tested integrations in the Android app**; recheck exact artifact releases, signatures, API/extension gates, app minSdk, licenses and device support during implementation. For an implementation task, record the acceptance fixture and expected owner, then move it into the authoritative roadmap. Avoid a second permanent source of truth. Priorities and module names are architectural proposals, not claims about current code or completed testing.
