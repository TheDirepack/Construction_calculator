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

    @Test fun threePhase480V100AAtPf09() {
        val r = CurrentPower.calculate(PowerTerm.AMPS, 100.0, Phase.THREE, 480.0, 0.9)
        assertEquals(74.8, r.kw, 0.05)
        assertEquals(83.1, r.kva, 0.05)
        assertEquals(100.0, r.amps, 0.0)
    }

    @Test fun reverseFromKwAndKva() {
        val forward = CurrentPower.calculate(PowerTerm.AMPS, 100.0, Phase.THREE, 480.0, 0.9)
        val fromKw = CurrentPower.calculate(PowerTerm.KW, forward.kw, Phase.THREE, 480.0, 0.9)
        assertEquals(100.0, fromKw.amps, 1e-9)
        assertEquals(forward.kva, fromKw.kva, 1e-9)
        val fromKva = CurrentPower.calculate(PowerTerm.KVA, forward.kva, Phase.THREE, 480.0, 0.9)
        assertEquals(100.0, fromKva.amps, 1e-9)
        assertEquals(forward.kw, fromKva.kw, 1e-9)
        assertEquals(100.0, CurrentPower.calculate(PowerTerm.KW, 74.8, Phase.THREE, 480.0, 0.9).amps, 0.1)
    }

    @Test fun singlePhase() {
        val r = CurrentPower.calculate(PowerTerm.AMPS, 20.0, Phase.SINGLE, 240.0, 1.0)
        assertEquals(4.8, r.kw, 1e-12)
        assertEquals(4.8, r.kva, 1e-12)
        assertEquals(20.0, CurrentPower.calculate(PowerTerm.KVA, 4.8, Phase.SINGLE, 240.0, 1.0).amps, 1e-12)
        assertEquals(41.67, CurrentPower.calculate(PowerTerm.KW, 5.0, Phase.SINGLE, 120.0, 1.0).amps, 0.005)
    }

    @Test fun invalidInputs() {
        assertTrue(CurrentPower.problems(10.0, 240.0, 1.0).isEmpty())
        assertEquals(1, CurrentPower.problems(10.0, 0.0, 1.0).size)
        assertEquals(1, CurrentPower.problems(10.0, 240.0, 1.2).size)
        assertEquals(1, CurrentPower.problems(10.0, 240.0, 0.0).size)
        assertEquals(1, CurrentPower.problems(-1.0, 240.0, 1.0).size)
    }
}
