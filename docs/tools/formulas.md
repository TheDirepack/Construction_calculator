# Electrical Formulas

Pick a formula, enter what you know, and every blank is solved. Each formula is a set of
equations (`Relation`s) over its variables; `FormulaSolver` repeatedly uses any equation with
exactly one unknown left, so any combination that determines the rest works ("enter any two" for
Ohm's law).

## Formulas (v1)

Ohm's law and power · voltage drop (K factor, single- and three-phase) · single- and three-phase
power (A, kW, kVA, kVAR, PF) · wye and delta · neutral current of an unbalanced wye · power factor
correction · motor current from horsepower (single- and three-phase) · transformer full-load and
fault current (single- and three-phase) · transformer ratio · reactance and impedance (R-L-C) ·
two resistances in series and parallel · conductor resistance at temperature · energy and cost ·
efficiency and losses.

## Behavior

- **Units:** each variable has its own unit choices (V/kV, W/kW/MW, µF/F…). Switching a unit
  converts what was typed. Solving is in base units.
- **Checks:** out-of-range entries (a negative voltage, a PF above 1) are rejected; a computed
  value out of range is flagged. Entering more values than needed is fine when they agree within
  0.5%; otherwise the equations that disagree are listed.
- **Steps:** "How it was solved" lists each equation used and the value it gave.
- **Sharing:** values are published like other tools' results (amps, volts, watts, VA, ohms,
  percent, lengths), so Voltage Drop, Panels and the rest can pick them, and fields here can pick
  theirs. Saved to a project like any calculation.
- **Formula sheet:** the Reference shows every formula from the same catalog.

## Adding a formula

Add it to `FormulaCatalog` (core/electrical) with a new stable id, a solver for each variable that
can be isolated, and a test in `FormulaCatalogTest` that solves it each way round. It appears in
the tool and on the formula sheet automatically.

Amps ↔ kW ↔ kVA used to be in Unit Conversions; it lives here now (one calculator per rule).
