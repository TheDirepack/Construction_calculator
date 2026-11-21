# Updating to a new code edition

The app follows **NFPA 70 (NEC) 2023**. This page is the checklist for moving to a later edition
(2026, 2029…). Two things change between editions: **where** a rule is (renumbering) and **what**
it says (values). They live in different places on purpose.

## 1. Numbers: one file

Every citation the app shows goes through a stable id:

- `core/electrical/.../code/CodeRule.kt`: the ids (`AMPACITY_TABLE`, `SMALL_CONDUCTORS`…), each
  with a short description in the app's own words.
- `core/electrical/.../code/Nec2023.kt`: id → "Table 310.16", "240.4(D)"…
- `Code.book` picks the edition; `Code.label` ("NEC 2023") is used in notes and reports.

Tools write `"… (${CodeRule.AMPACITY_TABLE.cite})"`, never a literal number. To renumber:

1. Copy `Nec2023.kt` to `Nec2026.kt`, check every line against the new book, change what moved.
2. Point `Code.book` at the new object.
3. `CodeBookTest` fails if any rule has no number, if two rules share one, or if a table's
   citation doesn't say "Table".

Not yet behind ids (check them by hand): raceway article numbers in `ConduitType.article`
and `RacewayBends.section()` (e.g. "358.24(B)"; the 2020 edition numbered these xxx.26).

## 2. Values: the table files

Each table is one object in `core/electrical`, with the printed page it was checked against in
its KDoc. The calculators, the design engine and the Reference all read these same objects, so
a corrected value shows up everywhere at once.

| Rule | File | Tests |
| --- | --- | --- |
| Table 310.16 ampacity | `Ampacity.kt` | `AmpacityTest` |
| Table 310.15(B)(1)(1), rooftop adder | `Derating.kt` (`AmbientCorrection`) | `ConductorSizingTest` |
| Table 310.15(C)(1) | `Derating.kt` (`ConductorCountAdjustment`) | `ConductorSizingTest` |
| 240.4(D) | `Derating.kt` (`SmallConductorLimit`) | `ConductorSizingTest` |
| Table 250.122 | `Derating.kt` (`EquipmentGroundingConductor`) | `ConductorSizingTest` |
| 240.6(A) | `StandardOcpd.kt` | `StandardOcpdTest` |
| Ch. 9 Table 1, notes | `ConduitFill.kt` | `ConduitFillTest` |
| Ch. 9 Table 2 | `RacewayBends.kt` | `RacewayBendsTest` |
| Ch. 9 Table 4 | `ConduitTable.kt` | `ConduitTableTest` |
| Ch. 9 Table 5, Table 8 areas | `InsulatedConductorArea.kt` | `InsulatedConductorAreaTest` |
| Ch. 9 Table 8 resistance | `ConductorResistance.kt` | `ConductorTablesTest` |
| Ch. 9 Table 9 | `ConductorImpedance.kt` | `ConductorTablesTest` |
| Tables 430.248, 430.250 | `MotorFullLoadCurrent.kt` | `MotorFullLoadCurrentTest` |
| Table 430.52(C)(1) | `MotorProtection.kt` | `MotorProtectionTest` |
| Table 110.26(A)(1) | `WorkingSpace.kt` | `MotorProtectionTest` |

For each: compare every cell with the new book, update the values and the KDoc page reference,
and update the test's expected values **from the book** (never from the code).

The Reference's NEC pages (`feature/reference/.../content/CodeTables.kt`) are generated from
these objects and need no edits for value changes. Their notes are written in the app's own words;
reread them for rule changes. `ReferenceCatalogTest` checks that every table rule has a page.

## 3. Rules in code, not tables

Some rules are logic, not lookup: 125% continuous load (210.19), next size up (240.4(B)), EGC
increase (250.122(B)), neutral counting (310.15(E)), the 360° bend limit, VFD sizing (430.122).
Search for the rule's `CodeRule` id to find every place it's applied, and reread each against the
new text.

## 4. Never

- Copy the code's text or a PDF into the repository. Numbers and values only, and notes in our
  own words.
- Change a value without checking the printed table.

## Later: more than one edition at once

Projects in different jurisdictions may need different editions at the same time. When that's
needed: store the edition on the project, give `CodeBook` the table objects (an
`AmpacityTable` interface per table, the current objects becoming the 2023 implementations), and
pass the project's book into the design engine instead of reading `Code.book`.
