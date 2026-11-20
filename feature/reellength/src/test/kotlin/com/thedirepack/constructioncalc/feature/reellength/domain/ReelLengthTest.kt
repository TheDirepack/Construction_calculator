package com.thedirepack.constructioncalc.feature.reellength.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class ReelLengthTest {
    /** F=30, D=12, W=18, X=3, d=1: H = (30 − 6 − 12)/2 = 6. */
    private val reel = ReelRequest(30.0, 12.0, 18.0, 1.0, 3.0)

    @Test fun partialReelByHand() {
        val r = ReelLength.calculate(reel)!!
        assertEquals(6.0, r.woundDepth, 1e-12)
        // π · (12 + 6) · 6 · 18 / 12 = π · 162 = 508.938 ft
        assertEquals(PI * 162, r.lengthFeet, 1e-9)
        assertEquals(508.938, r.lengthFeet, 1e-3)
        assertEquals(PI * 162 * 12, r.lengthInches, 1e-9)
        assertEquals(6.0, r.layers, 1e-12)
        assertEquals(18.0, r.turnsPerLayer, 1e-12)
    }

    @Test fun capacityUsesClearanceTheSameWay() {
        // 2" clearance: H = (30 − 4 − 12)/2 = 7; π · 19 · 7 · 18 / 12 = π · 199.5 = 626.7 ft
        val r = ReelLength.calculate(reel.copy(topClearance = 2.0))!!
        assertEquals(7.0, r.woundDepth, 1e-12)
        assertEquals(PI * 199.5, r.lengthFeet, 1e-9)
        assertEquals(626.7, r.lengthFeet, 0.05)
        assertEquals(7.0, r.layers, 1e-12)
    }

    @Test fun smallerCableHoldsMoreAndLengthScalesWithInverseSquare() {
        val thick = ReelLength.calculate(reel.copy(cableDiameter = 1.0))!!
        val thin = ReelLength.calculate(reel.copy(cableDiameter = 0.5))!!
        assertEquals(4.0, thin.lengthFeet / thick.lengthFeet, 1e-12)
        assertEquals(12.0, thin.layers, 1e-12)
        assertEquals(36.0, thin.turnsPerLayer, 1e-12)
    }

    @Test fun packingFactorScalesTheResult() {
        val r = ReelLength.calculate(reel.copy(packingFactor = 0.9))!!
        assertEquals(PI * 162 * 0.9, r.lengthFeet, 1e-9)
        // Layers and turns describe the geometry only
        assertEquals(6.0, r.layers, 1e-12)
    }

    @Test fun fullReelToTheFlangeEdge() {
        // X = 0: H = (30 − 12)/2 = 9; π · 21 · 9 · 18 / 12 = π · 283.5 ≈ 890.6 ft
        val r = ReelLength.calculate(reel.copy(topClearance = 0.0))!!
        assertEquals(PI * 283.5, r.lengthFeet, 1e-9)
    }

    @Test fun cableDepthIsExactForFractionalInches() {
        // F = 36", D = 14.5", X = 4.25": H = (36 − 8.5 − 14.5)/2 = 6.5
        val h = ReelLength.woundDepth(ReelRequest(36.0, 14.5, 20.0, 0.75, 4.25))
        assertEquals(6.5, h, 1e-12)
    }

    @Test fun validRequestHasNoProblems() {
        assertTrue(ReelLength.problems(reel).isEmpty())
    }

    @Test fun distanceTooLarge() {
        // X = 9 leaves F − 2X = 12 = D: no room. X = 10 is past the drum.
        for (x in listOf(9.0, 10.0)) {
            val bad = reel.copy(topClearance = x)
            assertTrue(ReelLength.problems(bad).single().contains("too large"))
            assertNull(ReelLength.calculate(bad))
        }
        // Just under works
        assertNotNull(ReelLength.calculate(reel.copy(topClearance = 8.9)))
    }

    @Test fun flangeNotLargerThanDrum() {
        for (f in listOf(12.0, 10.0)) {
            val bad = reel.copy(flangeDiameter = f, topClearance = 0.0)
            assertTrue(ReelLength.problems(bad).any { it.contains("Flange diameter") })
            assertNull(ReelLength.calculate(bad))
        }
    }

    @Test fun cableDiameterMustBePositive() {
        for (d in listOf(0.0, -1.0)) {
            val bad = reel.copy(cableDiameter = d)
            assertTrue(ReelLength.problems(bad).any { it.contains("Cable diameter") })
            assertNull(ReelLength.calculate(bad))
        }
    }

    @Test fun cableWiderThanTraverse() {
        val bad = reel.copy(cableDiameter = 20.0)
        assertTrue(ReelLength.problems(bad).any { it.contains("wider than the traverse") })
        assertNull(ReelLength.calculate(bad))
        // exactly the traverse is one turn per layer: allowed
        assertNotNull(ReelLength.calculate(reel.copy(cableDiameter = 18.0)))
    }

    @Test fun otherInvalidInputs() {
        assertTrue(ReelLength.problems(reel.copy(topClearance = -1.0)).isNotEmpty())
        assertTrue(ReelLength.problems(reel.copy(traverse = 0.0)).isNotEmpty())
        assertTrue(ReelLength.problems(reel.copy(drumDiameter = 0.0)).isNotEmpty())
        assertTrue(ReelLength.problems(reel.copy(packingFactor = 0.0)).isNotEmpty())
        assertTrue(ReelLength.problems(reel.copy(flangeDiameter = Double.NaN)).isNotEmpty())
        assertNull(ReelLength.calculate(reel.copy(traverse = Double.POSITIVE_INFINITY)))
    }
}
