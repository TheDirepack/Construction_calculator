# Updating to a new code edition

The app follows **NFPA 70 (NEC) 2023**. This page is the checklist for moving to a later edition
(2026, 2029…).

## The rule: one source

Every value the code defines, and every section or table number the app shows, lives in **one
edition source**: an implementation of `ElectricalCode` in `core/electrical/.../code/`. The
calculations contain only math and read their numbers from `Code.book`. Between editions the math
stays the same; the values and the numbering are what change, and they change in one place.

```
core/electrical/.../code/
  CodeRule.kt         stable rule ids (AMPACITY_TABLE, NEXT_SIZE_UP…), CodeEdition, Code.book
  ElectricalCode.kt   the interface: cite(rule), values, and every table, as typed rows
  nec2023/            the 2023 edition, data only (no logic)
    Nec2023.kt            object Nec2023 : ElectricalCode, the citation map, assembles the files below
    Nec2023Values.kt      single values (CodeValues): 1.25 continuous, 800 A, 33 °C, 53/31/40/60 %…
    Nec2023Conductors.kt  Tables 310.16, 310.15(B)(1)(1), 310.15(C)(1), 310.4(1) ratings; Ch. 9 Tables 5, 8, 9
    Nec2023Protection.kt  240.6(A), 240.4(D), Table 250.122
    Nec2023Raceways.kt    Ch. 9 Tables 2 and 4; each raceway's article, bend-radius column and xxx.24(B) section
    Nec2023Motors.kt      Tables 430.248, 430.250, 430.52(C)(1)
    Nec2023General.kt     Table 110.26(A)(1)
```

Each table carries the printed page it was checked against in its KDoc. Each single value in
`CodeValues` names its `CodeRule` in its KDoc, and `Nec2023Values` gives the section beside it.

The math objects (`Ampacity`, `AmbientCorrection`, `ConductorCountAdjustment`, `SmallConductorLimit`,
`EquipmentGroundingConductor`, `StandardOcpd`, `ConductorProtection`, `ConduitFill`, `ConduitTable`,
`InsulatedConductorArea`, `BareConductorArea`, `ConductorResistance`, `ConductorImpedance`,
`RacewayBends`, `MotorFullLoadCurrent`, `MotorProtection`, `WorkingSpace`, `ConductorSizing`,
`FillDerating`) read `Code.book` on every call. The calculators, the design engine and the Reference
all go through them, so a new edition shows up everywhere at once.

Citations in user-facing text go through the rule id, never a literal number:
`"… (${CodeRule.AMPACITY_TABLE.cite})"`. `Code.label` ("NEC 2023") is used in notes and reports.

## Adopting a new edition

1. Copy `code/nec2023/` to `code/nec2026/` and rename the objects (`Nec2026`, `Nec2026Values`…). Add
   `NEC_2026` to `CodeEdition`.
2. Check **every value and every citation** against the new printed book: each table cell, each
   single value, each section number (renumbered rules, the raceway articles, the xxx.24(B)
   sections). Update the KDoc page references.
3. Point `Code.book` at `Nec2026`.
4. Update the tests' expected values **from the book** (never from the code): the per-table tests
   (below) and `ElectricalCodeTest.singleValuesMatchThe2023Book` (copy it for the new edition).
5. Reread the rule descriptions in `CodeRule` and the Reference's notes for rules whose wording
   changed.

The math needs no change unless a rule's **method** changed (for example a new way to count
neutrals, or a new kind of row). Then change the math and the `ElectricalCode` shape together, and
give the 2023 edition the same shape.

| Data | Edition file | Read by | Tests |
| --- | --- | --- | --- |
| Single values (`CodeValues`) | `Nec2023Values.kt` | everything below | `ElectricalCodeTest` |
| Table 310.16 | `Nec2023Conductors.kt` | `Ampacity` | `AmpacityTest` |
| Table 310.15(B)(1)(1), rooftop adder | `Nec2023Conductors.kt`, values | `AmbientCorrection` | `ConductorSizingTest` |
| Table 310.15(C)(1) | `Nec2023Conductors.kt` | `ConductorCountAdjustment` | `ConductorSizingTest` |
| Table 310.4(1) temperature ratings | `Nec2023Conductors.kt` | `FillDerating.insulationRating` | `ConduitFillTest` |
| Ch. 9 Table 5, Table 8 areas | `Nec2023Conductors.kt` | `InsulatedConductorArea`, `BareConductorArea` | `InsulatedConductorAreaTest` |
| Ch. 9 Table 8 resistance, note 2 coefficients | `Nec2023Conductors.kt`, values | `ConductorResistance`, `FormulaCatalog` | `ConductorTablesTest` |
| Ch. 9 Table 9 | `Nec2023Conductors.kt` | `ConductorImpedance` | `ConductorTablesTest` |
| 240.6(A) | `Nec2023Protection.kt` | `StandardOcpd` | `StandardOcpdTest` |
| 240.4(B) (800 A), 240.4(D) | values, `Nec2023Protection.kt` | `ConductorProtection`, `SmallConductorLimit` | `ConductorSizingTest` |
| Table 250.122 | `Nec2023Protection.kt` | `EquipmentGroundingConductor` | `ConductorSizingTest` |
| Ch. 9 Table 1, Notes 4 and 7, jam range | values | `ConduitFill` | `ConduitFillTest` |
| Ch. 9 Table 2, xxx.24(A)/(B), 360° | `Nec2023Raceways.kt`, values | `RacewayBends` | `RacewayBendsTest` |
| Ch. 9 Table 4, raceway articles | `Nec2023Raceways.kt` | `ConduitTable`, `ConduitType.article` | `ConduitTableTest` |
| Tables 430.248, 430.250 | `Nec2023Motors.kt` | `MotorFullLoadCurrent` | `MotorFullLoadCurrentTest` |
| Table 430.52(C)(1) | `Nec2023Motors.kt` | `MotorProtection` | `MotorProtectionTest` |
| Table 110.26(A)(1), (A)(2), (A)(3) | `Nec2023General.kt`, values | `WorkingSpace` | `MotorProtectionTest` |
| 210.19(A)(1) 125 %, 310.3(A) minimum size | values | `ConductorSizing` | `ConductorSizingTest` |

The Reference's NEC pages (`feature/reference/.../content/CodeTables.kt`) are generated from these
objects. Their notes are written in the app's own words; reread them for rule changes.
`ReferenceCatalogTest` checks that every table rule has a page.

## Guards

- `CodeBookTest`: every rule has a citation, citations are unique, tables are cited as tables.
- `ElectricalCodeTest`: `Code.book` is the expected edition, every table has rows and covers the
  app's vocabulary (every conduit type, insulation, motor type…), and the single values are the
  printed ones.
- `NoCodeTablesOutsideEditionTest`: fails if a file in `core/electrical/src/main` outside `code/`
  has a collection literal with four or more numbers (a pasted table). `PullFormulas`, `PullRatings`,
  `AcPhases` and `formula/` are allowlisted: they hold physics and manufacturer figures, not code
  values. It can't see a single hard-coded factor, so review still applies: never write a code
  number in a tool; add it to `CodeValues` and read it.

## Not code values

These stay out of the edition on purpose:

- **Vocabulary**: `ConductorSize` (with its circular mils), `ConductorMaterial`, `TemperatureRating`,
  `InsulationType`, `ConduitType`, `TradeSize` (inch and metric designators are trade naming),
  `MotorType`, `MotorProtectiveDevice`, `WorkingSpaceCondition`. They are the same in every edition
  and key the tables.
- **Pulling**: `PullFormulas` (IEEE 1185 tension, sidewall pressure and weight-correction formulas)
  and `PullRatings` (Southwire tension and sidewall-pressure guidance, friction values). They are
  engineering and manufacturer guidance, labelled as such in the UI. The jam range they check is
  the code's (`ConduitFill.JAM_LOW`/`JAM_HIGH`).
- **Physics and formulas**: `AcPhases` (√3, 2), `formula/` (Ohm's law and friends). The formula
  catalog's Table 8 coefficients are read from the edition.

## Text and tools

Tools and notes build every code number into their text from the edition (`Code.book.values`,
`AmbientCorrection.rooftopAdderText`, `codeLength`/`codePercent` in `code/CodeText.kt`), in the
user's unit system. A new tool follows the same rule: no literal code number in logic or text.

## Never

- Copy the code's text or a PDF into the repository. Numbers and values only, and notes in our
  own words.
- Change a value without checking the printed table.

## Later: more than one edition at once

Projects in different jurisdictions may need different editions at the same time. The math already
reads everything through one `ElectricalCode`, so this needs: store the edition on the project,
and pass the project's book into the design engine and the math objects (a `code: ElectricalCode =
Code.book` parameter) instead of reading `Code.book`.
