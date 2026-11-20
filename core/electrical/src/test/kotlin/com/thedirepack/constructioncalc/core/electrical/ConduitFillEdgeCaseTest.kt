package com.thedirepack.constructioncalc.core.electrical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConduitFillEdgeCaseTest {

    private fun thhn(size: ConductorSize) = FillConductor.Insulated(InsulationType.THHN, size)
    private fun bare(size: ConductorSize) = FillConductor.Bare(size)

    @Test
    fun limitPercentsFollowTable1() {
        assertEquals(53, ConduitFill.limitPercent(1))
        assertEquals(31, ConduitFill.limitPercent(2))
        assertEquals(40, ConduitFill.limitPercent(3))
        assertEquals(40, ConduitFill.limitPercent(4))
        assertEquals(40, ConduitFill.limitPercent(10))

        // Nipple override is always 60% regardless of conductor count
        assertEquals(60, ConduitFill.limitPercent(1, nipple = true))
        assertEquals(60, ConduitFill.limitPercent(2, nipple = true))
        assertEquals(60, ConduitFill.limitPercent(3, nipple = true))
    }

    @Test
    fun note7RoundingFollowsNEC() {
        // Less than 0.8 truncates
        assertEquals(0, ConduitFill.note7Round(0.79))
        assertEquals(1, ConduitFill.note7Round(1.79))
        assertEquals(44, ConduitFill.note7Round(44.79))

        // 0.8 or greater rounds up to next whole number
        assertEquals(1, ConduitFill.note7Round(0.80))
        assertEquals(1, ConduitFill.note7Round(0.85))
        assertEquals(2, ConduitFill.note7Round(1.80))
        assertEquals(45, ConduitFill.note7Round(44.80))
        assertEquals(45, ConduitFill.note7Round(44.96))

        // Negative or zero
        assertEquals(0, ConduitFill.note7Round(0.0))
        assertEquals(0, ConduitFill.note7Round(-0.5))
    }

    @Test
    fun annexCReferenceCountsMatchNEC() {
        // EMT 1/2" with THHN:
        // Table 4: EMT 1/2" total area = 0.304 in²; 1 wire (53%) = 0.161; 2 wires (31%) = 0.094; over 2 (40%) = 0.122
        // Table 5: 14 AWG THHN area = 0.0097 in² -> 0.122 / 0.0097 = 12.57 -> 12
        // Table 5: 12 AWG THHN area = 0.0133 in² -> 0.122 / 0.0133 = 9.17 -> 9
        // Table 5: 10 AWG THHN area = 0.0211 in² -> 0.122 / 0.0211 = 5.78 -> 5
        val emtHalf = ConduitTable.find(ConduitType.EMT, TradeSize.S_1_2)!!
        assertEquals(12, ConduitFill.maxCount(emtHalf, thhn(ConductorSize.AWG_14).dimensions!!.areaSqIn))
        assertEquals(9, ConduitFill.maxCount(emtHalf, thhn(ConductorSize.AWG_12).dimensions!!.areaSqIn))
        assertEquals(5, ConduitFill.maxCount(emtHalf, thhn(ConductorSize.AWG_10).dimensions!!.areaSqIn))

        // 12 AWG THHN in 1-1/4" EMT: 0.598 / 0.0133 = 44.96 -> rounds up to 45 by Note 7
        val emtOneAndQuarter = ConduitTable.find(ConduitType.EMT, TradeSize.S_1_1_4)!!
        assertEquals(45, ConduitFill.maxCount(emtOneAndQuarter, thhn(ConductorSize.AWG_12).dimensions!!.areaSqIn))

        // EMT 3/4" with 10 AWG THHN: 40% area = 0.213 in² -> 0.213 / 0.0211 = 10.09 -> 10
        val emtThreeQuarter = ConduitTable.find(ConduitType.EMT, TradeSize.S_3_4)!!
        assertEquals(10, ConduitFill.maxCount(emtThreeQuarter, thhn(ConductorSize.AWG_10).dimensions!!.areaSqIn))
    }

    @Test
    fun analysisHandlesSingleConductorTwoConductorsAndMixed() {
        val emtHalf = ConduitTable.find(ConduitType.EMT, TradeSize.S_1_2)!!

        // 1 conductor uses 53%
        val single = ConduitFill.analyze(emtHalf, listOf(ConductorGroup(1, thhn(ConductorSize.AWG_12))))
        assertEquals(53, single.limitPercent)
        assertTrue(single.withinLimit)
        assertEquals(1, single.conductorCount)

        // 2 conductors use 31%
        val two = ConduitFill.analyze(emtHalf, listOf(ConductorGroup(2, thhn(ConductorSize.AWG_12))))
        assertEquals(31, two.limitPercent)
        assertTrue(two.withinLimit)
        assertEquals(2, two.conductorCount)

        // 3 conductors use 40%
        val three = ConduitFill.analyze(emtHalf, listOf(ConductorGroup(3, thhn(ConductorSize.AWG_12))))
        assertEquals(40, three.limitPercent)
        assertTrue(three.withinLimit)
        assertEquals(3, three.conductorCount)

        // Mixed conductors: 3x 12 AWG THHN + 1x 12 AWG Bare
        val mixed = ConduitFill.analyze(
            emtHalf,
            listOf(
                ConductorGroup(3, thhn(ConductorSize.AWG_12)),
                ConductorGroup(1, bare(ConductorSize.AWG_12)),
            )
        )
        assertEquals(4, mixed.conductorCount)
        assertEquals(40, mixed.limitPercent)
        // 3 * 0.0133 + 1 * 0.0050 = 0.0399 + 0.0050 = 0.0449 in² <= 0.122 in² (allowed)
        assertTrue(mixed.withinLimit)
        assertFalse(mixed.permittedByNote7) // Note 7 only applies when same size
    }

    @Test
    fun minimumConduitFindsSmallestTradeSize() {
        // 3x 500 kcmil THHN in PVC Schedule 40
        // 500 kcmil THHN area = 0.7073 in². 3 of them = 2.1219 in².
        // At 40% fill, needs allowed area >= 2.1219 in².
        // 2-1/2" PVC 40: 40% area = 1.878 in² (too small)
        // 3" PVC 40: 40% area = 2.907 in² (fits!)
        val groups = listOf(ConductorGroup(3, thhn(ConductorSize.KCMIL_500)))
        val min = ConduitFill.minimumConduit(ConduitType.PVC_40, groups)
        assertNotNull(min)
        assertEquals(TradeSize.S_3, min!!.tradeSize)
    }

    @Test
    fun jamCheckCalculatesRatioAndIdentifiesJamRange() {
        // Exactly 3 conductors checked
        val emtHalf = ConduitTable.find(ConduitType.EMT, TradeSize.S_1_2)!! // ID = 0.622 in
        // 3x 10 AWG THHN (OD = 0.164 in) -> ratio = 0.622 / 0.164 = 3.79 (outside 2.8 - 3.2)
        val jamOut = ConduitFill.jamCheck(emtHalf, listOf(ConductorGroup(3, thhn(ConductorSize.AWG_10))))
        assertNotNull(jamOut)
        assertFalse(jamOut!!.inJamRange)

        // Null when conductor count is not 3
        assertNull(ConduitFill.jamCheck(emtHalf, listOf(ConductorGroup(2, thhn(ConductorSize.AWG_10)))))
        assertNull(ConduitFill.jamCheck(emtHalf, listOf(ConductorGroup(4, thhn(ConductorSize.AWG_10)))))
    }
}
