package com.thedirepack.constructioncalc.core.electrical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spot checks against NEC 2023 Chapter 9 Table 2 (printed p. 70-716) and the xxx.24(B) sections. */
class RacewayBendsTest {
    @Test fun table2OneShotColumn() {
        assertEquals(4.0, RacewayBends.minimumRadiusInches(TradeSize.S_1_2, BendRadiusColumn.ONE_SHOT)!!, 0.0)
        assertEquals(4.5, RacewayBends.minimumRadiusInches(TradeSize.S_3_4, BendRadiusColumn.ONE_SHOT)!!, 0.0)
        assertEquals(5.75, RacewayBends.minimumRadiusInches(TradeSize.S_1, BendRadiusColumn.ONE_SHOT)!!, 0.0)
        assertEquals(7.25, RacewayBends.minimumRadiusInches(TradeSize.S_1_1_4, BendRadiusColumn.ONE_SHOT)!!, 0.0)
        assertEquals(8.25, RacewayBends.minimumRadiusInches(TradeSize.S_1_1_2, BendRadiusColumn.ONE_SHOT)!!, 0.0)
        assertEquals(9.5, RacewayBends.minimumRadiusInches(TradeSize.S_2, BendRadiusColumn.ONE_SHOT)!!, 0.0)
        assertEquals(10.5, RacewayBends.minimumRadiusInches(TradeSize.S_2_1_2, BendRadiusColumn.ONE_SHOT)!!, 0.0)
        assertEquals(13.0, RacewayBends.minimumRadiusInches(TradeSize.S_3, BendRadiusColumn.ONE_SHOT)!!, 0.0)
        assertEquals(16.0, RacewayBends.minimumRadiusInches(TradeSize.S_4, BendRadiusColumn.ONE_SHOT)!!, 0.0)
        assertEquals(30.0, RacewayBends.minimumRadiusInches(TradeSize.S_6, BendRadiusColumn.ONE_SHOT)!!, 0.0)
    }

    @Test fun table2OtherBendsColumn() {
        assertEquals(4.0, RacewayBends.minimumRadiusInches(TradeSize.S_1_2, BendRadiusColumn.OTHER)!!, 0.0)
        assertEquals(5.0, RacewayBends.minimumRadiusInches(TradeSize.S_3_4, BendRadiusColumn.OTHER)!!, 0.0)
        assertEquals(12.0, RacewayBends.minimumRadiusInches(TradeSize.S_2, BendRadiusColumn.OTHER)!!, 0.0)
        assertEquals(21.0, RacewayBends.minimumRadiusInches(TradeSize.S_3_1_2, BendRadiusColumn.OTHER)!!, 0.0)
        assertEquals(36.0, RacewayBends.minimumRadiusInches(TradeSize.S_6, BendRadiusColumn.OTHER)!!, 0.0)
        assertNull(RacewayBends.minimumRadiusInches(TradeSize.S_3_8, BendRadiusColumn.OTHER))
    }

    @Test fun articlesPickTheirColumn() {
        assertEquals(BendRadiusColumn.ONE_SHOT, RacewayBends.column(ConduitType.EMT))
        assertEquals(BendRadiusColumn.ONE_SHOT, RacewayBends.column(ConduitType.PVC_40))
        assertEquals(BendRadiusColumn.OTHER, RacewayBends.column(ConduitType.FMC))
        assertEquals(BendRadiusColumn.OTHER, RacewayBends.column(ConduitType.ENT))
        assertEquals(6.0, RacewayBends.minimumRadiusInches(ConduitType.LFMC, TradeSize.S_1)!!, 0.0)
        assertEquals(5.75, RacewayBends.minimumRadiusInches(ConduitType.RMC, TradeSize.S_1)!!, 0.0)
    }

    @Test fun bendSectionsAreThe2023Numbers() {
        assertEquals("358.24(B)", RacewayBends.section(ConduitType.EMT))
        assertEquals("344.24(B)", RacewayBends.section(ConduitType.RMC))
        assertEquals("342.24(B)", RacewayBends.section(ConduitType.IMC))
        assertEquals("352.24(B)", RacewayBends.section(ConduitType.PVC_80))
        assertEquals("348.24(B)", RacewayBends.section(ConduitType.FMC))
        assertEquals("350.24(B)", RacewayBends.section(ConduitType.LFMC))
        assertEquals("356.24(B)", RacewayBends.section(ConduitType.LFNC_B))
        assertEquals("362.24(B)", RacewayBends.section(ConduitType.ENT))
        ConduitType.entries.forEach { RacewayBends.section(it) } // every type mapped
    }
}
