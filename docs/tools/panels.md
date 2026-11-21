# Panels & Circuits

The electrician's centrepiece: design panels, make panel schedules, and link circuits to conduit
runs so the app sizes the wire. It works on the **active project** (`AppSettings.activeProjectId`);
everything is stored in the project file (`Project.panels`, `groups`, `design`, and runs with a
`Raceway`). Every edit goes through `ProjectRepository.edit` and is saved immediately. Archived
projects are shown read-only.

Code: `feature/panels` (screens), `feature/panels/.../domain` (pure logic + tests),
`core/model/.../design` (the engine: `PanelLayout`, `ProjectAnalysis`), and
`core/ui/.../RacewayEditor.kt` (the conduit editor sheet and `racewaySummary`, shared with Projects).

## Screens

- **No project / archived**: empty state with *New project* and *Choose project* (archived projects can be viewed read-only).
- **Panels** tab: cards with system, spaces, load and warnings; add, rename, duplicate, delete.
  Opening a panel gives **Schedule** and **Settings**.
  - *Schedule*: two columns (odd left, even right), each row labelled with its phase. A multi-pole breaker is one tall cell spanning its rows. Cells show breaker (`20/1`), load VA, description, wire (`2#12, 1#12G`), group tags and a voltage-drop warning. Tap a free position to add a circuit, a breaker to edit. A group filter dims other circuits. Totals: VA and amps per phase, % imbalance, conflicts and notes. Circuits that overlap or run past the last position are listed under *Not on the schedule*.
  - *Settings*: name, location, system, spaces (common sizes or custom), bus, main breaker or MLO, fed from (a circuit in another panel; loops are excluded), note.
- **Circuit sheet**: kind (load/spare/space), panel, poles, position (only positions that fit are offered, so moving is just picking another), breaker, description, load (VA or amps, converted with the panel voltage), continuous / neutral / nonlinear / multiple cord-and-plug, groups, conduit runs (create new inline), length override, conductor override. A live **Design result** shows the wire, minimum and final size, ground, current-carrying conductors, ambient used, adjustment factors, voltage drop, the governing rule and every note.
- **Runs** tab: runs with a conduit or circuits. Conduit and size (or auto), length, circuits with their panels, current-carrying count, total conductor area, fill vs limit and the smallest conduit that fits. *Edit conduit…* opens `RacewayEditorSheet`; *Circuits…* assigns circuits from every panel (with a group filter).
- **Groups** tab: create, rename, recolour (named colours), delete; each group lists its circuits across panels with total load.
- **Design** tab: the project's `DesignDefaults` (conductor material, insulation, temperature columns, ambient, voltage-drop target, upsize for voltage drop).

## Feeders and subpanels

A panel's *fed from* links it to a circuit in another panel. If that feeder circuit has no load of
its own, the design engine sizes it for the connected load of every panel it feeds (recursively,
continuous loads at 125 %, no demand factors), shows it on the schedule as "VA (fed)", and counts it
in the upstream panel's phase totals. Enter a load on the feeder to override it (e.g. after demand
factors). Duplicating a panel copies its circuits without their conduit runs.

## Links to the other tools

- Circuit sheet › *Voltage drop for this circuit…* opens Voltage Drop prefilled from the circuit
  (voltage, phases, load, length, sized wire, the run's Table 9 conduit, the project target).
- Runs tab › run menu › *Check fill in Conduit Fill* / *Bend this conduit* open those tools for
  the run. The project page's run menu has the same, plus *Open in Panels & Circuits*.
- Opened for a run, Panels starts on the Runs tab; for a circuit, on its panel.
- Design values (circuit currents and loads, lengths, panel loads, run lengths) appear in every
  tool's value picker under "From the design".
- The project's shared text includes every panel schedule and each run's conduit and fill.

## Temperature

Each conduit run's ambient (or the project default when blank) plus its own rooftop adder gives the
run's effective ambient; a circuit is sized for the hottest run it passes through.

## Export

*Share panel schedule (PDF)*: letter size, one panel per page, company header (`CompanyProfile.headerLines()`), project, panel facts, two-column schedule with wire sizes, totals and date, made with `android.graphics.pdf.PdfDocument` and shared with `ProjectSharing.shareFileIntent`. *Share as text*: a plain-text schedule through `host.shareText`. Both are in the share menu and on the Panels tab, for one panel or all.

## Published values (`host.publish`)

Open panel: phase A/B/C current (`CURRENT`), connected load (`APPARENT_POWER`, and `POWER` with VA taken as W). Open circuit: wire size (`TEXT`), length (`LENGTH`, inches), load current (`CURRENT`).

## Notes and limits

- Sizing follows NEC 2023 via `ProjectAnalysis`; it guides fieldwork and doesn't replace a code check.
- Insulation XHHW-2 is listed separately from XHHW/XHH/ZW: only XHHW-2 is exempt from the rooftop adder.
- Imbalance is the largest phase deviation from the average, over the average.
- The schedule uses fixed row heights; at very large font scales text is clipped (the circuit sheet shows everything).
