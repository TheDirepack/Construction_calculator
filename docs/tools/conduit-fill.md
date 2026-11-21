# Conduit Fill

Module: `feature/conduitfill` (results and notices in `domain/ConduitFillReport.kt`). The fill math
(`ConduitFill.kt`) and derating (`FillDerating.kt`) live in `core/electrical` with the tables
(`ConduitTable.kt`, `InsulatedConductorArea.kt`), so the Panels design engine checks a run's fill
with exactly the same code, Note 7 included.

## What it does

Pick a conduit type and trade size, add groups of conductors (count × insulation × size, plus
bare or insulated equipment grounds), and it shows:

- total conductor area, allowed area and percent fill against NEC Chapter 9 Table 1
- the smallest trade size of the chosen type that holds the conductors
- "how many fit": the most of one wire that fit alone in the chosen conduit (Annex C style)
- a notice when exactly three conductors land in the 2.8 to 3.2 jam-ratio range

Results are published as `ResultValue`s: `fill_percent` and `fill_limit` (`PERCENT`),
`conductor_area` and `allowed_area` (`AREA`, square inches), `min_conduit` (`TEXT`) and `max_count`
(`NUMBER`). Saved items store the inputs (`ConduitFillInputs`) and the results.

## Rules

```
Table 1:   1 conductor 53 %,  2 conductors 31 %,  more than 2 conductors 40 %
           nipple <= 24 in. between boxes: 60 %, any count (Note 4)
fill %   = total conductor area / conduit 100 % area
allowed  = Table 4's printed column for the limit (area from the unrounded diameter, 3 decimals)
```

**Note 7 rounding.** For conductors that are all the same size, the maximum count is
`allowed / area` with the decimal part rounded **up** when it is 0.8 or more, else truncated
(12 AWG THHN in 1-1/4" EMT: 0.598 / 0.0133 = 44.96, counted as 45). It also lets one conductor in when
the quotient is 0.8 or more (1/0 AWG THHN in 1/2" EMT: 0.161 / 0.1855 = 0.87). A mix of sizes is compared
exactly with no rounding. The count tries more than two conductors at 40 % first, then two at 31 %, then
one at 53 %.

**Jam ratio** (Table 1 Informational Note 2): conduit inside diameter / conductor outside diameter for
exactly three conductors (the largest, when sizes differ). From 2.8 to 3.2 is a warning.

## Data sources (NEC 2023, checked against the printed book)

| Data | NEC | Printed page |
| --- | --- | --- |
| Percent limits, Notes 3, 4, 7, 8, 9 | Chapter 9 Table 1 | 70-715 |
| Conduit ID and area, 11 types | Chapter 9 Table 4 | 70-716 to 70-720 |
| Insulated conductor area and diameter | Chapter 9 Table 5 | 70-720 to 70-723 |
| Bare conductor area and diameter | Chapter 9 Table 8 | 70-725 |
| Published counts used as tests | Annex C Tables C.1, C.4, C.9 | 70-774, 70-775 and the IMC and RMC tables |

Every conduit row, every Table 5 area and diameter in the seven insulation families, and every Table 8
"Overall" value was read from the book and matches the code. Differences from the book:

- **EMT 5" and 6" are not offered.** The 2023 Table 4 prints EMT rows for them, but they are identical to
  the RMC 5" and 6" rows, and Annex C.1's EMT 5" and 6" columns need areas of roughly 22.2 and 31.3 sq in
  (RMC's Annex C columns match RMC's rows exactly). The book contradicts itself, so those sizes stay out
  until the true EMT dimensions are confirmed.
- **IMC 5" and 6"** (21.32 and 30.76 sq in) are included: they were missing and the book prints them.
- The book computes its 40/60/53/31 % columns from the unrounded cross-section, so `allowedAreaSqIn`
  does too (1" EMT at 60 % is 0.519, where 0.864 × 0.6 would give 0.518). Three cells in the book
  itself (IMC 5" and 6", RMC 3") were rounded from the printed area instead and can differ by 0.001 in².
- Annex C's counts were worked in square millimetres, so where the quotient lands within about 0.01 of
  Note 7's 0.8 the book can differ by one from a square-inch calculation (for example 14 AWG RHH in 1"
  EMT: 222 / 18.90 = 11.75 in the book, 0.346 / 0.0293 = 11.81 here, so 12 versus 11). These cells are
  listed in `ConduitFillTest`.

## Limits

- Not included: LFNC-A and LFNC-C, EMT 5" and 6", compact conductors (Table 5A), fixture wires and the
  TFE, FEP, PFA and Z insulations. Use actual dimensions for those (Table 1 Notes 5, 9, 10).
- Bare conductors use stranded Table 8 areas.
- Multiconductor cables count as one conductor with their actual (elliptical-major) diameter: not
  modelled, enter an equivalent insulated size instead.
- Derating (310.15(B) ambient with the rooftop adder, XHHW-2 exempt; 310.15(C)(1) count; 110.14(C)
  terminals; the 240.4 largest OCPD) is shown per conductor group. Not checked: pull length, bends,
  box fill, or whether a conduit type suits the location.
- Opened for a project run (Panels › Runs or the project page › run menu › *Check fill*), it starts
  with every conductor the design puts in that run, split into current-carrying or not.
