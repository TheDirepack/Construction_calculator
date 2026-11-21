/**
 * Shared electrical rules and formulas (pure Kotlin), used by several tools: ampacity, corrections,
 * conductor sizing, conduit fill, conductor properties (NEC Chapter 9 Tables 5, 8, 9), conduit
 * dimensions (Chapter 9 Table 4), motor full-load currents, etc.
 *
 * The code's values and section numbers live only in the edition source, `code/` (an
 * [com.thedirepack.constructioncalc.core.electrical.code.ElectricalCode], read through
 * `Code.book`); the objects here hold the math. Every table there cites its source (code edition,
 * table number, printed page) in a KDoc comment and is covered by spot-check unit tests against
 * the published values.
 */
package com.thedirepack.constructioncalc.core.electrical
