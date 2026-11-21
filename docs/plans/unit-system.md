# Imperial / Metric everywhere

Requested 2026-11-21: one setting, Imperial or Metric, that every tool follows, converting
automatically (FieldScale was given as the example for measurement).

## Principles

- **One setting** in Settings → Measurements: **Units: Imperial | Metric**. Metric adds its own
  options (length unit, resolution); Imperial keeps ft-in / in and fraction precision.
- **Storage never changes.** Engines and files keep their base units (lengths as exact inches,
  temperatures °C as the NEC tables use, pull lengths in feet, weights in lb…). Only what's shown
  and typed is converted. Switching systems is lossless and can be flipped back and forth; saved
  projects and backups don't care which system was on.
- **Exchange stays in base units.** `ResultValue.exact` keeps its `ValueKind` base unit; only
  `text` is formatted in the current system.
- **Exact where possible.** 1 in = 25.4 mm exactly, so metric lengths are exact `Rational`s.
- **Either system can be typed in both.** In metric, `5'` still works; in imperial, `300mm` still
  works. Only the defaults (keys shown, unit for a bare number) follow the setting.

## What converts

| Measure | Base (stored) | Imperial | Metric |
| --- | --- | --- | --- |
| Length (calculator, fields) | exact inches | ft-in / in, fractions | mm / cm / m (Auto: mm under 1 m) |
| Run length (pulls, reels, VD) | feet | ft | m |
| Small length (diameters, radii) | inches | in | mm |
| Area (fill) | in² | in² | mm² |
| Area (calculator) | in² | sq ft | m² |
| Volume (calculator) | in³ | cu ft | m³ (L under 0.1 m³) |
| Temperature | °C | °F | °C |
| Weight | lb | lb | kg |
| Cable weight | lb/ft | lb/ft | kg/m |
| Tension | lbf | lb | N |
| Sidewall pressure | lb/ft | lb/ft | kN/m |
| Pressure | psi | psi | kPa |
| Flow | gpm | gpm | L/min |

**Not converted** (they're names or electrical units, the same everywhere): AWG/kcmil sizes
(metric shows mm² beside them), conduit trade sizes (metric shows the metric designator beside
them, e.g. 3/4 (21)), V, A, W, VA, Ω, %, degrees of bend. NEC tables in the Reference stay as
printed.

## Input

- Length fields and the Foot-Inch calculator: in metric the keypad shows **m · cm · mm** instead
  of ' and ", the fraction pad is hidden, and a bare number in a length field is millimeters.
- Other unit fields (`UnitField`): show the value in the current system, convert typed values
  to the base unit; the field's text is rebuilt when the system changes.

## Building blocks

- `core:measure`: `UnitSystem`, metric literal parsing (`mm`, `cm`, `m`), `LengthFormat`
  (system + imperial and metric options), metric formatting, `Measure` (each non-length quantity
  with its base, imperial and metric unit and the conversion).
- `core:data`: `AppSettings.unitSystem`, metric display and resolution; backed up like the rest.
- `core:ui`: `AppSettings.format(q)` follows the system; `AppSettings.show(measure, base)`;
  `UnitField`; metric keypad keys; `TradeSize`/`ConductorSize` labels with the metric extra.
- Tools: each tool's inputs use `MeasureField` / `UnitField`, outputs format through the
  settings' `UnitFormatter` (pure, so output functions stay testable for both systems).

## Order

1. Core (measure, data, ui, Settings rows), with tests.
2. Tools, one module each: Foot-Inch, Conduit Bending, Conduit Fill, Voltage Drop, Pull Planner,
   Reel Length, Panels and Projects text/PDF, VFD Sizing, Electrical Formulas, Conversions defaults.
3. Docs: ARCHITECTURE rule "show units through the formatter, never a literal unit string for a
   convertible measure".
