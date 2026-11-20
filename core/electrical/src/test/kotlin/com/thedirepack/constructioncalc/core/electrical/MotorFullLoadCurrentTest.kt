package com.thedirepack.constructioncalc.core.electrical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotorFullLoadCurrentTest {
    private fun three(hp: Double, v: Int) = MotorFullLoadCurrent.threePhase(hp, v)

    @Test fun table430_250_spotChecks() {
        assertEquals(14.0, three(10.0, 460)!!, 0.0)
        assertEquals(34.0, three(25.0, 460)!!, 0.0)
        assertEquals(28.0, three(10.0, 230)!!, 0.0)
        assertEquals(1.1, three(0.5, 460)!!, 0.0)
        assertEquals(552.0, three(200.0, 200)!!, 0.0)
        assertEquals(590.0, three(500.0, 460)!!, 0.0)
        assertEquals(472.0, three(500.0, 575)!!, 0.0)
        assertEquals(118.0, three(500.0, 2300)!!, 0.0)
        assertEquals(16.0, three(60.0, 2300)!!, 0.0)
        assertEquals(4.4, three(0.5, 115)!!, 0.0)
        assertEquals(11.0, three(3.0, 200)!!, 0.0)
        assertEquals(22.0, three(7.5, 230)!!, 0.0)
        assertEquals(15.2, three(5.0, 230)!!, 0.0)
    }

    @Test fun table430_250_blankCellsAreNull() {
        assertNull(three(3.0, 115))
        assertNull(three(250.0, 230))
        assertNull(three(50.0, 2300))
        assertNull(three(12.0, 460))
    }

    @Test fun table430_248_spotChecks() {
        assertEquals(100.0, MotorFullLoadCurrent.singlePhase(10.0, 115)!!, 0.0)
        assertEquals(50.0, MotorFullLoadCurrent.singlePhase(10.0, 230)!!, 0.0)
        assertEquals(4.4, MotorFullLoadCurrent.singlePhase(1.0 / 6, 115)!!, 0.0)
        assertEquals(2.2, MotorFullLoadCurrent.singlePhase(1.0 / 6, 230)!!, 0.0)
        assertEquals(19.6, MotorFullLoadCurrent.singlePhase(3.0, 200)!!, 0.0)
        assertEquals(30.8, MotorFullLoadCurrent.singlePhase(5.0, 208)!!, 0.0)
        assertEquals(10.0, MotorFullLoadCurrent.singlePhase(1.5, 230)!!, 0.0)
        assertNull(MotorFullLoadCurrent.singlePhase(15.0, 230))
        assertNull(MotorFullLoadCurrent.singlePhase(5.0, 460))
    }

    @Test fun horsepowerListsFollowTheTable() {
        val at230 = MotorFullLoadCurrent.horsepowers(AcPhases.THREE, 230)
        assertEquals(0.5, at230.first(), 0.0)
        assertEquals(200.0, at230.last(), 0.0)
        val at460 = MotorFullLoadCurrent.horsepowers(AcPhases.THREE, 460)
        assertEquals(500.0, at460.last(), 0.0)
        assertEquals(27, at460.size)
        assertEquals(12, MotorFullLoadCurrent.horsepowers(AcPhases.SINGLE, 230).size)
        assertTrue(at460.zipWithNext().all { (a, b) -> a < b })
    }

    @Test fun labels() {
        assertEquals("1/6", MotorFullLoadCurrent.label(1.0 / 6))
        assertEquals("7-1/2", MotorFullLoadCurrent.label(7.5))
        assertEquals("250", MotorFullLoadCurrent.label(250.0))
    }

    @Test fun currentsRiseWithHorsepowerAtEachVoltage() {
        for (phase in AcPhases.entries) for (v in MotorFullLoadCurrent.voltages(phase)) {
            val values = MotorFullLoadCurrent.horsepowers(phase, v).map { MotorFullLoadCurrent.amps(phase, it, v)!! }
            assertTrue("$phase $v V", values.zipWithNext().all { (a, b) -> a < b })
        }
    }
}
