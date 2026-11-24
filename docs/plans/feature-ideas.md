# Future Work & Scope Decisions

This file summarizes future work beyond what is actively tracked in [docs/ROADMAP.md](../ROADMAP.md) and records what has been explicitly cut or parked so the app stays focused, offline-first, and easy to maintain.

`docs/ROADMAP.md` is the single authoritative plan.

---

## Product Principles

- **One integrated offline toolkit** for electricians and builders, built on shared engines (`:core:measure`, `:core:electrical`, `:core:model`, `:core:data`, `:core:ui`).
- **Zero permissions required for core workflows.** Any optional feature that needs a permission (camera for OCR, coarse location on app open, Bluetooth for a laser meter) stays off by default and requests access only when enabled/used.
- **No cloud servers, accounts, or Google Play Services dependencies.**
- **Practical field ergonomics over rigid data entry:** tools should work with partial information (e.g., entering just a length or conduit tag + size on a circuit without forcing a full modeled run).

---

## Active & Planned Work (Tracked in `docs/ROADMAP.md`)

### Core Electrical & Calculators
- **Conduit Bending multi-bend stick layout:** model standard 10' (or custom) sticks with sequential bends, 3D pipe preview for axial orientation / dogleg control (0-360°), continuous tape marking output from End A (hook tape once), and shoe clearance checks. Pinned 3D/stick summary with accordion bend cards to eliminate vertical scrolling.
- **Voltage Drop progressive disclosure:** 4-field quick input (Voltage, Amps, Length, Material), code-governed percent drop default, automatic wire sizing, and power factor default. Parallel sets hidden unless wire is ≥1/0 AWG (enforcing NEC 310.10(G)) or in advanced.
- **Conduit Fill & multiple conductors in conduit:** advance configuration of thermals (ambient, rooftop, terminal rating). Presets + manual add/remove wires. Set of voltages (120, 277, medium/custom). Automated neutral count calculation and current-carrying conductor (CCC) determination for multi-conductor runs.
- **Wire Pull Planner:** top-level toggle between "Quick Pull Check" (total length, 90° bends count, cable) and "Detailed Segment Route". Immediate pull direction verdict and tugger capacity checks.
- **Foot-Inch calculator:** top scrollable calculation tape with inline recall, configurable dual-button split layout (ArchCalc style) vs classic single pad, extensive footage/unit configuration, and tools sheet for stairs/diagonals/spacing.
- **Multi-edition & regional NEC code engine:** tool-level and project-level code edition overrides, supporting regional/state amendments alongside base NEC (NEC 2023 base, 2026 once finalized).
- **Electrical boxes:** device box fill (NEC 314.16) and pull/junction box sizing (NEC 314.28, paired with Pull Planner pull points).
- **Grounding & motors:** GEC and bonding jumpers (Table 250.66) and across-the-line motor circuits (Article 430, reusing VFD Sizing tables).
- **Loads & comparisons:** Article 220 feeder/service loads with demand factors; Cu/Al and parallel-feeder what-if comparisons.
- **Raceway rules:** support spacing, PVC thermal expansion, burial depth (Table 300.5), and stock-length cut lists.
- **Named reusable cables:** measured/catalog OD, weight, and pull ratings shared across Conduit Fill, Wire Pull Planner, and Reel Length.

### Panels, Runs & Automatic Recomputation
- **Bus-locked panel templates:** Panel creation templates (Residential 120/240V 1φ, Commercial 208/120V 3φ, Commercial 480/277V 3φ) strictly enforcing physical bus slot geometry: 3-phase panels in multiples of 6 (equal A/B/C stab slots) and residential in multiples of 4 (equal L1/L2 slots).
- **1-tap quick circuit entry:** Description and Breaker Amps only; wire size, neutral, and ground auto-fill from code rules. Phase balancing updates live. Visual schedule warning badges for >3% voltage drop with 1-tap upsize action.
- **Flexible partial-detail inputs:** allow circuits to take just a length measurement, or a conduit number + size, or a full linked `Run` — computing whatever is possible from the details available.
- **Automatic recomputation:** automatically recompute Voltage Drop and Conduit Fill whenever upstream circuit/run inputs change and warn immediately if out of spec. Wire Pull Planner stays on-demand / flagged rather than auto-recomputing on every edit.
- **Printable panel schedules:** print-ready PDF or PNG formatted for panel doors and job binders.
- **Focused panel-schedule label OCR:** on-device OCR (no Play Services) to pre-fill circuit labels from a photo of an existing panel schedule, with user review. Standardized phase rotation (`A/B/C` for 3φ, `L1/L2` for 1φ) fills automatically from slot order, leaving only breaker amperage to confirm.

### Drawings & Field Measurement
- **Tablet & mobile responsive UI with configurable tool pill:** Minimalist collapsible floating tool pill on mobile with super-common tools right there (Measure, Count, Cloud/Pen, Select), less common in 1-tap menu; fully configurable.
- **Scale lock:** Lock calibration once verified so scale cannot be accidentally changed during field takeoff.
- **Vector PDF export (IMP-01):** Preserve crisp vector linework and searchable text in sheet and crop exports.
- **PDF plan takeoff (v1 done):** line snapping (pre-computed vector/raster line maps, Center/Edge/Off), high-zoom region tiling + LRU page cache, loupe/magnifier, polyline & closed-polygon area measurement, "Send route to Run", undo/redo, symbol-first toolbar, markup styles & symbol sets, partial eraser, clouds, photo pins, detail links, revision tracking & full-screen overlay compare, stylus/Pencil mode & convenience gestures, rename/delete drawings and scale zones, and unreferenced PDF/photo garbage collection.
- **Next for Drawings:** portable manual sheet labels on `PlanDrawing`, saved overlay alignments & diff export, bulk addendum revision tagging, offline scan OCR, `TEXT`/`SYMBOL`/`INK` resize handles, cutout polygons, sheet-size-aware style scaling, shape snap, layer filters, shareable symbol packs, physical stylus tuning, keyboard shortcuts, and instrumented UI/snap benchmark tests (tracked in `docs/ROADMAP.md`).
- **Blueprint symbol detector (`ml/symbol-detector/`):** kept in its own isolated folder outside the Android build for future work once a trained model is ready.

### Projects, Backups, Sharing & Durability
- **Durable ZIP backup with hash metadata:** ZIP archive containing metadata hashes, source app details, and creation date. Complete backup restores everything. Single-project restore appends as a new revision without deleting existing data; prompts with collision dialog (Override vs New Revision vs Merge changes). Atomic file writes (`AtomicFile` / temp file swap) to prevent zero-byte corruptions.
- **Compact sharing (no cloud relay):** for non-PDF projects and calculations, produce a compact payload small enough to share over a text message (SMS); for projects with PDFs, share as a ZIP over email/share sheet.
- **CSV export** for material takeoffs and panel schedules, plus search/filter on Projects and a `Panels · N` link on `ProjectDetailScreen`.
- **Optional nearby-project check on app open:** uses Android's native `LocationManager` with **`ACCESS_COARSE_LOCATION` only** (rough location, no precise GPS, no background geofencing, no Google Play Services) to suggest switching when opening the app near a saved jobsite.

---

## Explicitly Out of Scope (Cut / Not Planned)

- **Bluetooth laser distance meters** (hardware integration cut; core software reliability, manual/calibrated takeoff, and offline file durability take complete priority).
- **Well & Pump** (`:feature:wellpump` removed — outside core electrical/construction scope).
- **ARCore 3D room scanning** (Play Services dependency, jobsite drift, low field trust).
- **Background geofencing / continuous location tracking** (Play Services dependency, battery/permission overhead).
- **Field commissioning, torque verification logs, punch lists, closeout ERP, and arc-flash calculators** (business/compliance/liability scope outside a field calculator).
- **Off-core specialty calculators:** interior lumen lighting, PoE budgeting, fiber optical link budgets, PV string sizing, power-quality harmonics, and interactive ladder-logic/PLC simulation.
- **Cloud-relay link sharing, accounts, or sync servers.**
- **Full CAD/BIM editing or piecewise non-linear PDF warp meshes.**
