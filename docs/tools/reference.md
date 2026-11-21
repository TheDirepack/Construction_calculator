# Reference

A searchable, offline handbook: NEC tables, the formula sheet and field references.

## What's in it

- **Code tables** (`content/CodeTables.kt`): generated from the `core:electrical` objects the
  calculators use, so a table and a result can't disagree. Ampacity, ambient correction,
  conductor-count adjustment, termination temperature, Tables 5, 8 and 9, standard OCPD sizes,
  small-conductor limits, EGC sizes, fill limits, Table 4, wires-in-conduit (computed like
  Annex C and checked against it), bend radius and the 360° rule, motor FLC (single- and
  three-phase), Table 430.52(C)(1) and working space.
- **Formula sheet** (`content/FormulaSheet.kt`): a page per formula in `FormulaCatalog`, plus a
  one-page DC / single-phase / three-phase power table. Each page has "Solve in Electrical
  Formulas".
- **Field references** (`content/FieldReference.kt`): offset multipliers and shrink, saddles,
  knockouts, NEMA configurations, conductor colors, voltage systems, abbreviations, fractions.

## How it connects

- Every tool with entries gets a book icon in its top bar (`ToolHost.hasReference`,
  `ToolHost.openReference()`), which opens the Reference filtered to that tool.
- Entries link back into tools (`RefBlock.ToolLink`), optionally at a topic, e.g. a formula.
- Links use `ToolTarget.OfTopic(id)`: an entry id, a formula id, or `tool:<toolId>`.
- Favorites and recently opened entries are saved in the Reference's module settings, so full
  backups include them.

## Adding an entry

Add a `RefEntry` to the right source list with a new, stable `id` (favorites store it). Generate
code tables from `core:electrical` instead of typing values; cite with `CodeRule` so the page
names the edition. `ReferenceCatalogTest` builds every entry, expands every choice and fails on
empty tables.

Code-edition updates: see [../CODE_UPDATES.md](../CODE_UPDATES.md).
