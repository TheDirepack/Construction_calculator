# Southwire VFD Cable Sizing Calc: feature inventory

Feature-inventory notes only; paraphrased.

| Item | Detail |
|---|---|
| App | VFD Cable Sizing Calculator (Play title) / VFD Cable Sizing Calc |
| Play id | `com.southwire.VFDSizingCalculator` |
| Developer | Southwire Company; free |
| Date | 2026-11-20 |

**Sources actually read**
- https://play.google.com/store/apps/details?id=com.southwire.VFDSizingCalculator : fetched but truncated; only the title was readable.
- https://www.southwire.com/apps : vendor page.
- https://apps.apple.com/us/app/vfd-cable-sizing-calc/id6452802915 : iOS listing, which is itself sparse (the store text does not list the required inputs). Android parity **not verified**; it appears under the same developer name.

## Feature inventory
- Selects a cable size for the run from a VFD to a motor, from installation data the user enters. The specific inputs and outputs are not described in any public text I could read, so I list nothing beyond:
- Horsepower picker with an "Other" choice to type a custom full-load amps value (added Nov 2025).
- Uses NEC reference values (references corrected in 2024).
- First release Nov 2023; no share/export or unit options mentioned.
- Too few ratings for a review summary.

## Gap vs. our app
Ours: `feature/vfdsizing`, `docs/tools/vfd-sizing.md`. HP or kW entry (kW converted to the next standard HP), 200-575 V from Table 430.250, three-phase or single-phase supply, optional nameplate FLA and drive input amps, normal vs heavy duty, copper/aluminum, 60/75 C column, outputs: minimum drive output, input and output conductors with parallel sets, short-circuit protection, disconnect, overload, with NEC citations and flagged approximations.

**They have, we lack**
- Nothing verifiable. The only specific item, a custom FLA for an "Other" motor rating, we already cover via the optional nameplate FLA field (although our HP list is limited to table ratings, so a non-table HP cannot be chosen; their custom-FLA path suggests allowing a motor not in Table 430.250).
- Their wider scope is unknown (cable type, distance, or shielded VFD cable selection may exist but is unverified).

**We have, they lack (as far as verified)**
- Everything in the outputs list above, kW entry, single-phase supply.

**Suggested follow-up**: allow "Other HP" with a user-entered FLA.
