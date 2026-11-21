package com.thedirepack.constructioncalc.feature.conversions.domain

import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElectricalTest {
    @Test fun twelveAwg() {
        val r = WireSizes.fromSize(ConductorSize.AWG_12)
        assertEquals(6530.0, r.circularMils, 0.0)
        assertEquals(3.31, r.squareMillimeters, 0.005)
        assertTrue(r.isStandard)
    }

    @Test fun fourZeroAndKcmil() {
        assertEquals(107.2, WireSizes.fromSize(ConductorSize.AWG_4_0).squareMillimeters, 0.1)
        assertEquals(126.7, WireSizes.fromSize(ConductorSize.KCMIL_250).squareMillimeters, 0.1)
        assertEquals(506.7, WireSizes.fromSize(ConductorSize.KCMIL_1000).squareMillimeters, 0.1)
    }

    @Test fun nearestSizeForTenSquareMillimeters() {
        val r = WireSizes.fromSquareMillimeters(10.0)
        assertEquals(19_735.25, r.circularMils, 0.01)
        assertEquals(ConductorSize.AWG_6, r.size)
        assertEquals(13.3, r.size!!.squareMillimeters, 0.05)
        assertFalse(r.isStandard)
    }

    @Test fun nearestSizeFromCircularMils() {
        assertEquals(ConductorSize.AWG_12, WireSizes.fromCircularMils(6530.0).size)
        assertTrue(WireSizes.fromCircularMils(6530.0).isStandard)
        assertEquals(ConductorSize.AWG_10, WireSizes.fromCircularMils(6600.0).size)
        assertEquals(ConductorSize.AWG_18, WireSizes.fromCircularMils(100.0).size)
        assertEquals(ConductorSize.KCMIL_2000, WireSizes.fromCircularMils(2_000_000.0).size)
        assertNull(WireSizes.fromCircularMils(2_500_000.0).size)
    }

    @Test fun catalogRoundingDoesNotJumpASize() {
        assertEquals(ConductorSize.AWG_12, WireSizes.fromSquareMillimeters(3.31).size)
        assertEquals(ConductorSize.AWG_10, WireSizes.fromSquareMillimeters(3.4).size)
    }

    @Test fun everySizeMapsToItself() {
        ConductorSize.entries.forEach {
            assertEquals(it, WireSizes.fromSquareMillimeters(it.squareMillimeters).size)
            assertEquals(it, WireSizes.fromCircularMils(it.circularMils.toDouble()).size)
            assertEquals(it.circularMils.toDouble(), WireSizes.mm2ToCmil(it.squareMillimeters), 1e-6)
        }
    }
}
