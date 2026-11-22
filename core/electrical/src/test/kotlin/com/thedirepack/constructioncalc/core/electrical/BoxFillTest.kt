package com.thedirepack.constructioncalc.core.electrical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxFillTest {

    @Test
    fun table31416BVolumesMatchNec2023() {
        assertEquals(1.50, BoxFill.volumeAllowanceCuIn(ConductorSize.AWG_18)!!, 1e-9)
        assertEquals(1.75, BoxFill.volumeAllowanceCuIn(ConductorSize.AWG_16)!!, 1e-9)
        assertEquals(2.00, BoxFill.volumeAllowanceCuIn(ConductorSize.AWG_14)!!, 1e-9)
        assertEquals(2.25, BoxFill.volumeAllowanceCuIn(ConductorSize.AWG_12)!!, 1e-9)
        assertEquals(2.50, BoxFill.volumeAllowanceCuIn(ConductorSize.AWG_10)!!, 1e-9)
        assertEquals(3.00, BoxFill.volumeAllowanceCuIn(ConductorSize.AWG_8)!!, 1e-9)
        assertEquals(5.00, BoxFill.volumeAllowanceCuIn(ConductorSize.AWG_6)!!, 1e-9)
        assertNull(BoxFill.volumeAllowanceCuIn(ConductorSize.AWG_4))
    }

    @Test
    fun classicDuplexReceptacleIn12AwgBox() {
        // 4 × 12 AWG conductors (9.00) + internal clamps (2.25) + 1 device yoke on 12 AWG (4.50) + 2 EGCs (2.25) = 18.00 in³
        val res = BoxFill.calculate(
            BoxFillRequest(
                conductors = listOf(BoxConductorEntry(ConductorSize.AWG_12, 4)),
                hasInternalClamps = true,
                devices = listOf(BoxDeviceEntry(1, ConductorSize.AWG_12)),
                egcCount = 2,
                largestEgcSize = ConductorSize.AWG_12,
            ),
        )!!
        assertEquals(9.00, res.conductorVolumeCuIn, 1e-9)
        assertEquals(2.25, res.clampVolumeCuIn, 1e-9)
        assertEquals(0.0, res.supportVolumeCuIn, 1e-9)
        assertEquals(4.50, res.deviceVolumeCuIn, 1e-9)
        assertEquals(2.25, res.egcVolumeCuIn, 1e-9)
        assertEquals(18.00, res.totalVolumeCuIn, 1e-9)
        assertEquals(ConductorSize.AWG_12, res.largestConductor)
        assertNotNull(res.smallestBox)
        assertEquals(18.0, res.smallestBox!!.volumeCuIn, 1e-9)
    }

    @Test
    fun nec2023EgcRuleCountsExtraGroundsOverFourAtQuarterVolume() {
        // 6 × 12 AWG EGCs: first 4 = 1× (2.25), 2 extra = 2 × 0.25 × 2.25 = 1.125 → total EGC = 3.375 in³
        val res = BoxFill.calculate(
            BoxFillRequest(
                conductors = listOf(BoxConductorEntry(ConductorSize.AWG_12, 2)),
                egcCount = 6,
                largestEgcSize = ConductorSize.AWG_12,
            ),
        )!!
        assertEquals(3.375, res.egcVolumeCuIn, 1e-9)
        assertEquals(4.50 + 3.375, res.totalVolumeCuIn, 1e-9)
    }

    @Test
    fun mixedSizesUseLargestForClampsAndFittings() {
        // 2 × 14 AWG (4.00) + 2 × 10 AWG (5.00) + clamp (2.50) + 1 stud (2.50) = 14.00 in³
        val res = BoxFill.calculate(
            BoxFillRequest(
                conductors = listOf(
                    BoxConductorEntry(ConductorSize.AWG_14, 2),
                    BoxConductorEntry(ConductorSize.AWG_10, 2),
                ),
                hasInternalClamps = true,
                supportFittings = 1,
            ),
        )!!
        assertEquals(14.00, res.totalVolumeCuIn, 1e-9)
        assertEquals(ConductorSize.AWG_10, res.largestConductor)
        assertTrue(BoxFill.fits(14.0, res.totalVolumeCuIn))
        assertFalse(BoxFill.fits(12.5, res.totalVolumeCuIn))
    }

    @Test
    fun rejectsConductorLargerThan6Awg() {
        assertNull(
            BoxFill.calculate(BoxFillRequest(conductors = listOf(BoxConductorEntry(ConductorSize.AWG_4, 2)))),
        )
    }
}
