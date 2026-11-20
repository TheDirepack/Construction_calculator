# Southwire Voltage Drop Calc: feature inventory

Feature-inventory notes only. Everything is paraphrased; no text, art, code or layouts are reused.

| Item | Detail |
|---|---|
| App | Southwire Voltage Drop Calc (Play listing title "Southwire Voltage Drop") |
| Play id | `com.southwire.voltageDrop` |
| Developer | Southwire Company; free |
| Date | 2026-11-20 |

**Sources actually read**
- https://play.google.com/store/apps/details?id=com.southwire.voltageDrop : fetched but truncated; only the app name was readable. Nothing below is taken from it.
- https://www.southwire.com/apps : vendor product page (short description only).
- https://apps.apple.com/us/app/southwire-voltage-drop-calc/id440338420 : the iOS listing of the same product (description, version history, review themes). The Android feature set is assumed to match it but that is **not verified**.
- Web search result summarising a Play description: it says the app is for AWG and kcmil sizes only.

## Feature inventory
- Purpose: pick a wire size that keeps drop within a limit, or compute the drop on a known run.
- Three calculation types (per the iOS listing's history and reviews): minimum conductor size, maximum circuit distance, and plain voltage drop for a given size and length.
- Sizing is limited by both voltage drop and NEC ampacity (so the answer is never an undersized wire that merely meets the drop).
- Two use modes: commercial and residential. Residential no longer suggests parallel conductors (v3.1.0).
- Parallel conductors supported.
- Inputs: system voltage (a drop-down of preset voltages), load current (decimals allowed), power factor, a DC option, and the target drop as a decimal percent. The default limit is 3 %.
- Sizes: AWG and kcmil only (no metric mm2). A reviewer asked for 16 AWG.
- Used for AC mains and also DC systems (reviewers mention 12 V, 48 V and 400 V DC).
- A pop-up warns when a chosen size triggers a condition; an "extended results" view explains more.
- Home-screen widget showing the last result (iOS, v3.1.3).
- Share/export: none mentioned. Unit options: none mentioned (feet only implied).
- Review themes: fast and accurate; one reviewer found the wording confusing and wanted a simpler ampacity readout and a table of how ampacity falls with distance.
- Recent updates are cosmetic; no calculation features were added in 2024-2025.

## Gap vs. our app
Ours: `feature/voltagedrop`, `docs/tools/voltage-drop.md`. We have single- and three-phase, copper/aluminum, preset plus custom voltage, parallel sets (1 to max), DC (Table 8) and AC (Table 9 with conduit type, PF) methods, target percent, drop / voltage at load / smallest size / max length, stranded vs uncoated, saved project items.

**They have, we lack**
- Ampacity check in sizing (we explicitly do not check ampacity; sizing is drop-only).
- Commercial vs residential mode with different parallel-conductor behaviour.
- Warning pop-up plus an extended-results explanation view.
- Last-result home-screen widget.

**We have, they lack (as far as the listings show)**
- Max length output, conduit-type reactance, Table 8/9 method choice, aluminum, saved items/backup, documented limits.

**Suggested follow-ups**: optional ampacity cross-check using core/electrical Ampacity; a short "why this size" explanation.
