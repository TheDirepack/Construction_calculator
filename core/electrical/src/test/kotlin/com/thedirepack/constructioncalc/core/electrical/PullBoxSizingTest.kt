package com.thedirepack.constructioncalc.core.electrical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PullBoxSizingTest {

    @Test
    fun straightPullIsEightTimesLargestTradeSize() {
        // 2" and 3" conduits → 8 × 3" = 24"
        val res = PullBoxSizing.straightPull(listOf(TradeSize.S_2, TradeSize.S_3))!!
        assertEquals(PullBoxType.STRAIGHT, res.pullType)
        assertEquals(3.0, res.largestTradeSizeIn, 1e-9)
        assertEquals(24.0, res.minimumLengthIn, 1e-9)
        assertNull(res.minimumWidthIn)
        assertNull(res.minimumDiagonalBetweenSameConductorsIn)
    }

    @Test
    fun anglePullUsesSixTimesLargestPlusSumOfOthers() {
        // Wall A: one 3" and two 2" → 6 × 3 + 2 + 2 = 22"
        // Wall B: two 2-1/2" → 6 × 2.5 + 2.5 = 17.5"
        // Diagonal between same-conductor entries: 6 × 3 = 18"
        val res = PullBoxSizing.anglePull(
            wallA = listOf(TradeSize.S_3, TradeSize.S_2, TradeSize.S_2),
            wallB = listOf(TradeSize.S_2_1_2, TradeSize.S_2_1_2),
        )!!
        assertEquals(22.0, res.minimumLengthIn, 1e-9)
        assertEquals(17.5, res.minimumWidthIn!!, 1e-9)
        assertEquals(18.0, res.minimumDiagonalBetweenSameConductorsIn!!, 1e-9)
    }

    @Test
    fun uPullIncludesBothEnteringAndLeavingRacewaysInSameRow() {
        // One 2" entering and one 2" leaving same wall → 6 × 2 + 2 = 14", diagonal = 12"
        val res = PullBoxSizing.uPull(listOf(TradeSize.S_2, TradeSize.S_2))!!
        assertEquals(14.0, res.minimumLengthIn, 1e-9)
        assertEquals(12.0, res.minimumDiagonalBetweenSameConductorsIn!!, 1e-9)
    }

    @Test
    fun diagonalDistanceCheck() {
        val d = PullBoxSizing.diagonalDistanceIn(9.0, 12.0)
        assertEquals(15.0, d, 1e-9)
        assertTrue(PullBoxSizing.meetsSameConductorSpacing(TradeSize.S_2, TradeSize.S_2, d)) // needs 12"
        assertFalse(PullBoxSizing.meetsSameConductorSpacing(TradeSize.S_3, TradeSize.S_2, d)) // needs 18"
    }

    @Test
    fun emptyRacewaysReturnNull() {
        assertNull(PullBoxSizing.straightPull(emptyList()))
        assertNull(PullBoxSizing.anglePull(emptyList()))
        assertNull(PullBoxSizing.uPull(emptyList()))
    }
}
