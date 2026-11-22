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
- **Electrical boxes:** device box fill (NEC 314.16) and pull/junction box sizing (NEC 314.28, paired with Pull Planner pull points).
- **Grounding & motors:** GEC and bonding jumpers (Table 250.66) and across-the-line motor circuits (Article 430, reusing VFD Sizing tables).
- **Loads & comparisons:** Article 220 feeder/service loads with demand factors; Cu/Al and parallel-feeder what-if comparisons.
- **Raceway rules:** support spacing, PVC thermal expansion, burial depth (Table 300.5), and stock-length cut lists.
- **Named reusable cables:** measured/catalog OD, weight, and pull ratings shared across Conduit Fill, Wire Pull Planner, and Reel Length.
- **Foot-Inch calculator:** full calculation history/tape (browse, edit, recall), memory (`M+`, `M−`, `MR`, `Ans`), parentheses, metric entry, `√`/`x²` right-triangle helper, and spacing/layout marks.

### Panels, Runs & Automatic Recomputation
- **Flexible partial-detail inputs:** allow circuits to take just a length measurement, or a conduit number + size, or a full linked `Run` — computing whatever is possible from the details available.
- **Automatic recomputation:** automatically recompute Voltage Drop and Conduit Fill whenever upstream circuit/run inputs change and warn immediately if out of spec. Wire Pull Planner stays on-demand / flagged rather than auto-recomputing on every edit.
- **Printable panel schedules:** print-ready PDF or PNG formatted for panel doors and job binders.
- **Focused panel-schedule label OCR:** on-device OCR (no Play Services) to pre-fill circuit labels from a photo of an existing panel schedule, with user review. Standardized phase rotation (`A/B/C` for 3φ, `L1/L2` for 1φ) fills automatically from slot order, leaving only breaker amperage to confirm.

### Drawings & Field Measurement
- **PDF plan takeoff:** line snapping (pre-computed vector/raster line maps), crisp high-zoom region tiling, loupe/magnifier, polyline measurement, "Send route to Run", undo/redo, rename/delete drawings and scale zones, and unreferenced PDF garbage collection.
- **Bluetooth laser distance meters:** feed any `MeasureField` length input and provide exact two-point real-world measurements for PDF plan calibration ("tap two points + fire laser").
- **Blueprint symbol detector (`ml/symbol-detector/`):** kept in its own isolated folder outside the Android build for future work once a trained model is ready.

### Projects, Backups, Sharing & Optional Context
- **Full device-to-device backup (`.backup.zip`):** one ZIP containing all projects, original PDFs, imported SVG symbols, app & tool settings, tool data (Foot-Inch history, Reference favorites/recents), and recent values — allowing complete transfer from one device to another.
- **Compact sharing (no cloud relay):** for non-PDF projects and calculations, produce a compact payload small enough to share over a text message (SMS); for projects with PDFs, share as a ZIP over email/share sheet.
- **CSV export** for material takeoffs and panel schedules, plus search/filter on Projects and a `Panels · N` link on `ProjectDetailScreen`.
- **Optional nearby-project check on app open:** uses Android's native `LocationManager` with **`ACCESS_COARSE_LOCATION` only** (rough location, no precise GPS, no background geofencing, no Google Play Services) to suggest switching when opening the app near a saved jobsite.

---

## Explicitly Out of Scope (Cut / Not Planned)

- **Well & Pump** (`:feature:wellpump` removed — outside core electrical/construction scope).
- **ARCore 3D room scanning** (Play Services dependency, jobsite drift, low field trust).
- **Background geofencing / continuous location tracking** (Play Services dependency, battery/permission overhead).
- **Field commissioning, torque verification logs, punch lists, closeout ERP, and arc-flash calculators** (business/compliance/liability scope outside a field calculator).
- **Off-core specialty calculators:** interior lumen lighting, PoE budgeting, fiber optical link budgets, PV string sizing, power-quality harmonics, and interactive ladder-logic/PLC simulation.
- **Cloud-relay link sharing, accounts, or sync servers.**
- **Full CAD/BIM editing or piecewise non-linear PDF warp meshes.**
