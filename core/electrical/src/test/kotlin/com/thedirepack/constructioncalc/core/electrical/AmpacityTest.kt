package com.thedirepack.constructioncalc.core.electrical

import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial.ALUMINUM
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial.COPPER
import com.thedirepack.constructioncalc.core.electrical.ConductorSize.*
import com.thedirepack.constructioncalc.core.electrical.TemperatureRating.C60
import com.thedirepack.constructioncalc.core.electrical.TemperatureRating.C75
import com.thedirepack.constructioncalc.core.electrical.TemperatureRating.C90
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmpacityTest {
    @Test fun table310_16Copper() {
        assertEquals(25, Ampacity.of(AWG_12, COPPER, C75))
        assertEquals(230, Ampacity.of(AWG_4_0, COPPER, C75))
        assertEquals(380, Ampacity.of(KCMIL_500, COPPER, C75))
        assertEquals(15, Ampacity.of(AWG_14, COPPER, C60))
        assertEquals(30, Ampacity.of(AWG_12, COPPER, C90))
        assertEquals(665, Ampacity.of(KCMIL_2000, COPPER, C75))
        assertEquals(14, Ampacity.of(AWG_18, COPPER, C90))
        assertEquals(555, Ampacity.of(KCMIL_2000, COPPER, C60))
    }

    @Test fun table310_16Aluminum() {
        assertEquals(20, Ampacity.of(AWG_12, ALUMINUM, C75))
        assertEquals(180, Ampacity.of(AWG_4_0, ALUMINUM, C75))
        assertEquals(310, Ampacity.of(KCMIL_500, ALUMINUM, C75))
        assertEquals(15, Ampacity.of(AWG_12, ALUMINUM, C60))
        assertEquals(35, Ampacity.of(AWG_8, ALUMINUM, C60))
        assertEquals(630, Ampacity.of(KCMIL_2000, ALUMINUM, C90))
    }

    @Test fun blankCellsAreNull() {
        assertNull(Ampacity.of(AWG_18, COPPER, C75))
        assertNull(Ampacity.of(AWG_16, COPPER, C60))
        assertNull(Ampacity.of(AWG_14, ALUMINUM, C75))
    }

    @Test fun ampacityNeverFallsAsSizeGrows() {
        for (m in ConductorMaterial.entries) for (t in TemperatureRating.entries) {
            val values = Ampacity.sizes(m, t).map { Ampacity.of(it, m, t)!! }
            assertTrue("$m $t", values.zipWithNext().all { (a, b) -> a < b })
        }
    }

    @Test fun smallestSize() {
        assertEquals(AWG_14, Ampacity.smallestSize(17.5, COPPER, C75))
        assertEquals(AWG_12, Ampacity.smallestSize(17.5, COPPER, C60))
        assertEquals(AWG_12, Ampacity.smallestSize(20.0, COPPER, C60))
        assertEquals(AWG_12, Ampacity.smallestSize(20.0, ALUMINUM, C75))
        assertEquals(KCMIL_2000, Ampacity.smallestSize(665.0, COPPER, C75))
        assertNull(Ampacity.smallestSize(665.1, COPPER, C75))
    }
}
