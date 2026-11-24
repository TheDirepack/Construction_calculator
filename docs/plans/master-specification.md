# Master Design & Implementation Specification

This document is the authoritative design reference resulting from the comprehensive product, architecture, and user-experience review. It defines what constitutes **"DONE"** for the Construction Calculator toolkit, establishing exact UX layouts, physical and electrical constraints, progressive disclosure rules, and implementation requirements across all tools.

---

## 1. Product Philosophy & Definition of "Done"

- **Finish What Exists:** Future work focuses strictly on completing, solidifying, and perfecting the current 13 tools and active integration loops. Unstarted specialty domains are deferred.
- **Field Ergonomics over Clutter:** Eliminate unnecessary vertical scrolling. The primary screen must show only the essential 3–4 inputs needed for immediate answers. Engineering knobs, secondary calculations, and advanced overrides collapse into progressive disclosure panels ("Advanced Options").
- **Lock Code & Mathematical Constants:** Things fixed by the National Electrical Code (NEC) or physical mathematics must never be open to accidental tampering or confusing manual entry.
- **Realistic Jobsite Assumptions:** Tools must reflect real trade practice (e.g., conduit bending models multiple bends on a 10-foot stick with 3D orientation, rather than assuming isolated single bends).
- **Core Software Durability:** Complete, offline, self-contained data durability takes priority over external hardware integrations. Bluetooth laser distance meters are explicitly cut; backup and project file integrity is bulletproof.

---

## 2. Tool-by-Tool Specifications

### 2.1 Conduit Bending: Multi-Bend Stick Layout & 3D Preview

#### Problem Solved
The legacy tool assumed only one bend per stick. In real field practice, an electrician bends a 10-foot stick of EMT or Rigid conduit with multiple bends (e.g., Stub 90 + Offset + Saddle).

#### UX & Functional Architecture
1. **Pinned Stick Summary & 3D Preview (Top of Screen):**
   - Interactive 3D wireframe preview of the pipe in space.
   - Summary statistics: Total Developed Length, Cut Length, Total Bend Degrees (flagged if > 360° per NEC 358.26 / 344.26), Remaining Straight Stick, and standard 10-foot stick cutoff alerts.
2. **Accordion Bend Cards (No Infinite Scroll):**
   - Bends appear as compact summary cards: `1. 90° Stub (14" stub, 0° roll) [Edit]`.
   - Only the active bend being configured expands to show inputs.
   - Tapping `+ Add Bend` inserts the next bend in sequence.
3. **Continuous Tape Layout from End A (Marking Guide):**
   - Electricians hook their tape measure once at End A.
   - The tool outputs cumulative marks from End A: Mark 1, Mark 2, Mark 3, etc.
   - Specifies bender hook direction (Hook toward End A vs Hook toward End B) and which shoe mark to align (Arrow, Star, Center notch).
4. **Full 0–360° Axial Rotation (Dogleg Prevention):**
   - Each bend specifies an axial rotation angle (default 0° same plane, 90°, 180°, 270°, or arbitrary roll angle) with live visual feedback in the 3D preview.
5. **Physical Shoe Clearance Checks:**
   - Warns if straight spacing between adjacent bends is shorter than bender shoe minimum clearance.

---

### 2.2 Voltage Drop: Progressive Disclosure & Sizing

#### Problem Solved
Legacy screen presented massive scrolling with engineering inputs (AC Table 9 vs DC Table 8, Power Factor, Stranding, Conduit Type, Parallel Sets) exposed for simple 15A/20A branch circuits.

#### UX & Functional Architecture
1. **Quick Input (Top Card):**
   - **4 Core Inputs:** Conductor Material (Cu / Al toggle), System & Voltage (e.g. 120V 1φ, 208V 3φ, 277V, 480V, or Custom), Load Current (Amps), and One-Way Length.
2. **Instant Auto-Sized Output:**
   - Immediately displays the minimum code-compliant wire size that stays ≤ 3% drop (or code percent drop), the actual % drop, and voltage delivered at the load.
3. **Locked Code Rules:**
   - **Parallel Sets:** Hidden unless wire size is ≥ 1/0 AWG (strictly enforcing NEC 310.10(G) prohibition of conductors smaller than 1/0 AWG in parallel).
   - **Default Power Factor:** Defaults to 0.90 / 0.85 per standard industry convention without requiring user interaction.
4. **Collapsed "Advanced Options":**
   - Expandable section containing: Power Factor modification, Target % Drop override (defaults to code 3%), Calculation Method (AC Table 9 vs DC Table 8), Conduit Material (Steel, Aluminum, PVC), and Manual Wire Size override.

---

### 2.3 Conduit Fill & Multi-Conductor Derating

#### Problem Solved
Legacy screen mixed single-wire Annex-C lookups ("How many fit") and derating into one long page, while lacking clear handling of multiple circuits sharing a conduit.

#### UX & Functional Architecture
1. **Conduit Contents & Presets:**
   - 1-tap quick buttons to add typical conductor sets: `3-Phase 4-Wire + Gnd`, `Single-Phase 120V Branch (2W+G)`, or manual wire count.
   - Pre-configured voltage levels (120V, 277V, medium voltage / custom).
   - Add and remove individual conductors seamlessly.
2. **Automated Neutral & CCC Determination:**
   - Automatically determines required neutrals (shared multiwire branch circuits per NEC 210.4 vs dedicated neutrals).
   - Automatically identifies Current-Carrying Conductors (CCC) for Table 310.15(C)(1) derating (grounds never count; neutrals count only when carrying harmonic/unbalanced currents per NEC 310.15(E)).
3. **Advanced Thermal Configuration:**
   - Collapsed/advance menu for thermals: Ambient Temperature (defaults to 30°C / 86°F), Rooftop Sunlight Condition (+60°F / +33°C adder), and Terminal Temperature Rating (60°C, 75°C default, 90°C).
4. **Annex C "How Many Fit" as Dedicated Sheet:**
   - Removed from the main scroll flow. Accessible via a clean 1-tap action sheet / tab ("How Many Fit of a Single Size").

---

### 2.4 Wire Pull Planner: Quick Check vs. Detailed Route

#### Problem Solved
Every route segment required four buttons (`Edit`, `Move up`, `Move down`, `Remove`), creating walls of buttons and high cognitive friction for quick pull estimates.

#### UX & Functional Architecture
1. **Top-Level Mode Toggle:**
   - **Quick Pull Check:** Enter Total Length, Total 90° Bends, and Cable Type. Instantly returns peak tension, maximum sidewall bearing pressure (SWBP), and required tugger capacity (e.g. 4,000 lb vs 10,000 lb).
   - **Detailed Segment Route:** Full segment-by-segment modeling for complex industrial pulls.
2. **Verdict-First Display:**
   - Prominently displays: `Recommended: Pull from End B → End A (Peak Tension 1,840 lb, Max SWBP 320 lb/ft — PASS)`.
   - Flip button to compare reverse pull direction immediately.
3. **Collapsed Rigging:**
   - Pull equipment ratings, rope limits, and friction coefficients (defaulting to 0.35 lubricated) collapse under an "Advanced Rigging" header.

---

### 2.5 Foot-Inch Calculator & Dimension Engine

#### Problem Solved
Insufficient configurability for different trades, lack of split keypad options, and missing persistent running tape.

#### UX & Functional Architecture
1. **Top Scrollable Calculation Tape:**
   - Running tape directly above display showing each entry and running total.
   - Tap any past line to recall into the current expression; long-press to copy or label ("Living Room Wall").
2. **Configurable Keypad Layout (Settings Option):**
   - **Classic Single Pad:** Standard keypad with `'` and `"` unit keys and top fraction bar.
   - **Split Dual-Pad (ArchCalc style):** Left pad for Feet (blue), Right pad for Inches (green), Right column for Fractions.
3. **Deep Footage & Fraction Customization:**
   - Display modes: Auto, Feet-Inches (`7' 11-7/8"`), Inches-only (`95-7/8"`), Decimal Feet (`7.99 ft`), Architectural (`7-11-14`), and Metric (mm / m).
   - Selectable rounding precision (1/2" down to 1/64").
   - Configurable fraction bar keys (8ths only, 16ths, 32nds).
4. **Tools Sheet:**
   - Quick-access drawer for Right-Triangle / Square-up (Rise, Run, Diagonal, Pitch), Stair Stringers, and Stud / Baluster layout spacing marks.

---

### 2.6 Panels & Circuits: Physical Bus Geometry & 1-Tap Entry

#### Problem Solved
Circuits required filling out dozens of fields; panel creation allowed nonsensical slot counts that do not exist physically in electrical panels.

#### UX & Functional Architecture
1. **Bus-Locked Geometry:**
   - **3-Phase Panels:** Strictly constrained to multiples of 6 spaces (e.g., 12, 18, 24, 30, 36, 42, 54, 72, 84). Guarantees equal stab slot counts across phases A, B, and C.
   - **Residential 1-Phase Panels:** Strictly constrained to multiples of 4 spaces (e.g., 8, 12, 16, 20, 24, 30, 32, 40, 42) for balanced L1 and L2 stabs.
2. **Panel Templates:**
   - Residential (120/240V 1φ), Commercial (208Y/120V 3φ), and Commercial Industrial (480Y/277V 3φ).
3. **1-Tap Quick Circuit Entry:**
   - Tapping an empty slot opens a compact sheet asking only for **Description** and **Breaker Amps** (defaulting to 20A 1-pole).
   - Wire size (#12 Cu), neutral, and ground auto-fill from code rules.
   - Phase balancing (kVA per phase and % imbalance) updates live.
4. **Visual Voltage Drop Schedule Badges:**
   - If a circuit's run length causes > 3% voltage drop, a warning badge appears directly on the panel schedule cell with a 1-tap `[Upsize Wire]` action.

---

### 2.7 Drawings & Plan Takeoff: Tablet Responsive & Configurable Tool Pill

#### Problem Solved
Small viewports cluttered by extensive toolbars; risk of accidentally changing plan scale in the field; rasterization dropping searchable text and vector lines.

#### UX & Functional Architecture
1. **Responsive Viewport:**
   - Responsive layout adapting smoothly between phone portrait viewports and full-screen tablet / landscape viewports.
2. **Configurable Floating Tool Pill:**
   - Minimalist collapsible pill: Super-common tools right on the pill (Measure/Distance, Count/Symbol, Cloud/Markup, Select).
   - Less common tools inside 1-tap expandable menu.
   - Fully configurable in user settings so trades can pin their own frequent tools.
3. **Scale Lock:**
   - Explicit scale lock toggle once calibration is verified, preventing accidental recalibration during field takeoff.
4. **Route to Run Integration:**
   - 1-tap "Send to Run" pushes polyline lengths directly to active project runs, feeding Voltage Drop, Bending, and Pull Planning.
5. **Vector Linework PDF Export (IMP-01):**
   - Sheet and crop exports preserve native vector lines and searchable text, eliminating silent rasterization.

---

### 2.8 Projects, Data Durability & Sharing

#### Problem Solved
Potential for partial backups, unhandled write interruptions, and ambiguous file collisions.

#### UX & Functional Architecture
1. **Atomic File Storage:**
   - Project writes standardize on `AtomicFile` / temporary file swap with disk sync (`fsync`), preventing zero-byte files on crash or battery death.
2. **Durable ZIP Backups:**
   - Backup ZIP contains project JSON, PDFs, photos, and an integrity manifest with metadata hashes, source app details, and creation timestamps.
   - Complete backup restore completely restores all projects, settings, and media.
   - Single-project import appends as a new revision without deleting existing data. Prompts with a collision dialog: `[Overwrite]`, `[Create New Revision]`, or `[Merge Changes]`.
3. **SMS Compact Sharing:**
   - For non-PDF projects and calculations, produces a compact, URL-safe / SMS-friendly text payload that can be shared over messaging apps without cloud dependencies.
4. **Hardware Scope Cut:**
   - Bluetooth laser distance meters are explicitly cut to prioritize core software stability and manual calibration accuracy.

---

### 2.9 Electrical Formulas, Reference & Regional NEC Engine

#### Problem Solved
Code editions differ across jurisdictions, and formula discovery was buried.

#### UX & Functional Architecture
1. **Multi-Edition & Regional Code Overrides:**
   - Global code edition setting (NEC 2023 base, 2026 once finalized).
   - Tool-level and project-level edition overrides.
   - Architectural support for regional and state-specific code amendments.
2. **Unified Quick Search:**
   - Persistent search bar across Reference and Formulas: instant indexing of rule numbers (e.g. `310.16`, `250.122`), topics (`motor`, `box fill`), and formulas.
   - 1-tap favorites for fast jobsite lookup.
3. **Bidirectional Constraint Solver:**
   - 18 Ugly's-style formulas solving for any unknown with step-by-step mathematical explanations.

---

## 3. Prioritized Implementation Roadmap

| Milestone | Scope | Deliverables |
|---|---|---|
| **M1: Core Durability & Backlog** | Quality & File Durability | BUG-01..07 fixes, AtomicFile writes, backup ZIP manifest, IMP-01 vector PDF export. |
| **M2: Conduit Bending 3D Stick** | Ergonomics & Layout | 10' stick multi-bend model, 3D pipe preview, 0-360° axial roll, continuous tape marking table, shoe clearance checks. |
| **M3: Progressive Disclosure** | UI De-cluttering | Voltage Drop 4-field quick input + auto-size; Conduit Fill presets + thermal config; Wire Pull Quick Check toggle. |
| **M4: Panels & Foot-Inch Polish** | Trades Centerpiece | Bus slot multiples of 6/4, 1-tap circuit entry, voltage drop schedule badges; Foot-Inch dual-pad option & scrollable tape. |
| **M5: Drawings & Reference** | Tablet UI & Codes | Responsive tablet UI, configurable floating pill, scale lock, multi-edition/regional NEC overrides. |
