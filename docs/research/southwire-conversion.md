# Southwire Conversion Calc: feature inventory

Feature-inventory notes only; paraphrased.

| Item | Detail |
|---|---|
| App | Southwire Conversion Calc (despite the name, a wire-product conversion tool, not a general unit converter) |
| Play id | `com.southwire.conversion` |
| Developer | Southwire Company; free |
| Date | 2026-11-20 |

**Sources actually read**
- https://play.google.com/store/apps/details?id=com.southwire.conversion : fetched but truncated; only the title was readable.
- https://www.southwire.com/apps : vendor page (also mentions a separate Re3 copper/aluminum conversion app).
- https://apps.apple.com/us/app/-/id545756420 : iOS listing and history. Android parity **not verified**; the iOS history notes the copper-to-aluminum conversion was replaced in 2025 by a link to a new app.

## Feature inventory
- Converts between copper and aluminum conductors in either direction (now delegated to a separate newer app).
- Handles the product pricing unit of measure, a conversion factor, and weight per product length.
- A dedicated conversion-factor screen.
- Ships with many cables preloaded for the calculations.
- Decimal entry in the conversion field (added 2018).
- "More" section: contact, company info, privacy and license pages.
- No share/export mentioned.

## Gap vs. our app
Ours: `feature/conversions`. A general converter: length, area, volume, weight, temperature, pressure, power, energy, speed, flow, angle, resistance per length, plus wire size (AWG / cmil / mm2) and current-power (A, kW, kVA by phase, voltage, PF).

**They have, we lack**
- Copper to aluminum equivalent-conductor conversion (size matching by ampacity/resistance).
- Product-oriented conversions: pricing unit (per 1000 ft, per ft), weight per length for cable products. We have no cable weight data.

**We have, they lack**
- Essentially the whole general-purpose unit table, wire-area conversion and A/kW/kVA.

**Suggested follow-up**: a Cu to Al size equivalence helper built from our own ConductorResistance/Ampacity tables (no vendor data needed). Cable weight/price units need product data we do not have.
