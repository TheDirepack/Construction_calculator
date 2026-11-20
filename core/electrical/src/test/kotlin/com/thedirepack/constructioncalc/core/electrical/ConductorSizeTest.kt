package com.thedirepack.constructioncalc.core.electrical

import org.junit.Assert.assertEquals
import org.junit.Test

class ConductorSizeTest {
    @Test fun areasMatchPublishedValues() {
        assertEquals(3.31, ConductorSize.AWG_12.squareMillimeters, 0.01)   // NEC Table 8: 3.309 mm²
        assertEquals(107.2, ConductorSize.AWG_4_0.squareMillimeters, 0.1)  // NEC Table 8: 107.2 mm²
        assertEquals(253.0, ConductorSize.KCMIL_500.squareMillimeters, 0.5) // NEC Table 8: 253 mm²
    }

    @Test fun sortedAndLookups() {
        assertEquals(ConductorSize.entries.sortedBy { it.circularMils }, ConductorSize.entries)
        assertEquals(ConductorSize.AWG_1_0, ConductorSize.fromLabel("1/0"))
        assertEquals(ConductorSize.KCMIL_250, ConductorSize.fromLabel("250 kcmil"))
    }
}
