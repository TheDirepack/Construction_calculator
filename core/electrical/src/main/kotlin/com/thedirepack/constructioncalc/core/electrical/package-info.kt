/**
 * Shared electrical reference data and formulas (pure Kotlin), used by several tools:
 * conductor sizes and properties (NEC Chapter 9 Tables 5, 8, 9), conduit dimensions
 * (Chapter 9 Table 4), ampacity, motor full-load currents, etc.
 *
 * Every table must cite its source (code edition, table number) in a KDoc comment and be
 * covered by spot-check unit tests against published values.
 */
package com.thedirepack.constructioncalc.core.electrical
