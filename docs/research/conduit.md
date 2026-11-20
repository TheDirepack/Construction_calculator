# Southwire Conduit Fill Calc (Play id com.conduit): feature inventory

Feature-inventory notes only; paraphrased, nothing copied.

| Item | Detail |
|---|---|
| App | Southwire Conduit Fill Calc. Its Play title is a *fill* calculator; the Play id `com.conduit` was described to us as a bending/fill app, but no bending feature appears in any source read. |
| Play id | `com.conduit` |
| Developer | Southwire Company; free |
| Date | 2026-11-20 |

**Sources actually read**
- https://play.google.com/store/apps/details?id=com.conduit : fetched but truncated; only the title was readable.
- https://www.southwire.com/apps : vendor page (short description).
- https://apps.apple.com/us/app/id509204523 : iOS listing of the same product (description, history, review themes). Android parity is **not verified**.
- [Southwire Conduit Fill web calculator](https://www.southwire.com/calculator-conduit) shows
  raceway type and trade size, conductor groups, measured diameter entry, fill, and a jam-ratio
  explanation. The page links to a dedicated [Cable Pull Calculator](https://www.southwire-cablepull.com/).

## Feature inventory
- Pick conduit type and trade size, enter the conductors, get fill percent against NEC limits.
- Recommends a conduit size for a given set of conductors.
- Jam probability shown (the three-conductor case). The vendor page also lists pulling tension, sidewall pressure, voltage drop and ground sizing among its general claims for the calculators; I could not verify those are inside this particular app.
- Conductor entry: wire size (AWG), insulation type, stranded vs solid, via spin-wheel style selectors.
- User-defined outside diameters, which can be saved; custom wires can be given names and saved (v2.1.0).
- Warning when the same conductor is entered twice (v2.3).
- Results can be emailed with one tap; shared files get unique names.
- NEC data refreshed in 2.2 (Oct 2024).
- "More" section with contact and company info.
- Review complaint: no automatic max-wires-per-conduit figure, users must try counts by hand.
- No bending math was found in any source.

## Gap vs. our app
Ours: `feature/conduitfill` + `docs/tools/conduit-fill.md`; `feature/conduitbending` + `docs/tools/conduit-bending.md`. Fill: 11 conduit types, groups of count x insulation x size, grounds, Table 1 limits, nipple 60 %, Note 7 rounding, min trade size, "how many fit", jam-ratio notice, derating. Bending: offsets, rolling offsets, 3- and 4-point saddles, 90 stub, back-to-back, kicks, editable bender take-up, diagram.

**They have, we lack**
- User-defined conductor outside diameters, saved and named custom wires (we have no custom conductor entry; cables outside the tables are unmodelled).
- Duplicate-conductor warning.
- One-tap email share of the result (we have project sharing but no per-result email/share action noted).

**We have, they lack**
- "How many fit" and minimum size output, 60 % nipple rule, derating, all bending math, saved projects.

**Suggested follow-up**: custom conductor OD (also helps multiconductor cables, a stated limit of ours).

## Planned workflow

- Let a conductor group use either a table entry or a named cable with its measured or catalog
  outside diameter. Count a jacketed multiconductor cable as one item for fill and apply the
  relevant NEC area rule for its shape.
- Keep the conduit type and trade size tied to the app's NEC Chapter 9 Table 4 data. Show the
  entered cable area, the applicable fill limit, unused area, and the smallest supported size
  that fits.
- Hand the selected raceway and cable groups to [Wire Pull Planner](../plans/wire-pull-planner.md)
  and share the cable OD with Reel Length. Report a jam ratio only for a supported cable
  configuration and state the calculation's assumptions.
