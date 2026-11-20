package com.thedirepack.constructioncalc.core.electrical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/** Spot checks against NEC Chapter 9, Table 4 as printed (2017 text, cross-checked with the 2020 tables). */
class ConduitTableTest {
    private fun row(type: ConduitType, size: TradeSize) = ConduitTable.find(type, size)!!

    private fun assertRow(type: ConduitType, size: TradeSize, id: Double, area: Double) {
        val r = row(type, size)
        assertEquals("$type $size ID", id, r.internalDiameterIn, 1e-9)
        assertEquals("$type $size area", area, r.area100SqIn, 1e-9)
    }

    @Test fun emtMatchesTable4() {
        assertRow(ConduitType.EMT, TradeSize.S_1_2, 0.622, 0.304)
        assertRow(ConduitType.EMT, TradeSize.S_3_4, 0.824, 0.533)
        assertRow(ConduitType.EMT, TradeSize.S_1, 1.049, 0.864)
        assertRow(ConduitType.EMT, TradeSize.S_1_1_4, 1.380, 1.496)
        assertRow(ConduitType.EMT, TradeSize.S_2, 2.067, 3.356)
        assertRow(ConduitType.EMT, TradeSize.S_4, 4.334, 14.753)
        assertNull(ConduitTable.find(ConduitType.EMT, TradeSize.S_5))
    }

    @Test fun otherMetalAndFlexibleTypes() {
        assertRow(ConduitType.RMC, TradeSize.S_3, 3.090, 7.499)
        assertRow(ConduitType.RMC, TradeSize.S_6, 6.093, 29.158)
        assertRow(ConduitType.IMC, TradeSize.S_2_1_2, 2.557, 5.135)
        assertRow(ConduitType.IMC, TradeSize.S_4, 4.166, 13.631)
        assertRow(ConduitType.FMC, TradeSize.S_3_8, 0.384, 0.116)
        assertRow(ConduitType.FMC, TradeSize.S_4, 4.000, 12.566)
        assertRow(ConduitType.LFMC, TradeSize.S_1_2, 0.632, 0.314)
        assertRow(ConduitType.LFMC, TradeSize.S_4, 4.020, 12.692)
        assertRow(ConduitType.LFNC_B, TradeSize.S_2, 2.033, 3.246)
        assertRow(ConduitType.ENT, TradeSize.S_1, 1.029, 0.832)
        assertRow(ConduitType.ENT, TradeSize.S_2, 2.047, 3.291)
    }

    @Test fun plasticTypes() {
        assertRow(ConduitType.PVC_40, TradeSize.S_1_2, 0.602, 0.285)
        assertRow(ConduitType.PVC_40, TradeSize.S_3, 3.042, 7.268)
        assertRow(ConduitType.PVC_40, TradeSize.S_6, 6.031, 28.567)
        assertRow(ConduitType.PVC_80, TradeSize.S_1_2, 0.526, 0.217)
        assertRow(ConduitType.PVC_80, TradeSize.S_2, 1.913, 2.874)
        assertRow(ConduitType.PVC_80, TradeSize.S_6, 5.709, 25.598)
        assertRow(ConduitType.PVC_A, TradeSize.S_1, 1.175, 1.084)
        assertRow(ConduitType.PVC_A, TradeSize.S_3_1_2, 3.690, 10.694)
        assertRow(ConduitType.PVC_A, TradeSize.S_4, 4.180, 13.723)
        assertRow(ConduitType.PVC_EB, TradeSize.S_2, 2.221, 3.874)
        assertRow(ConduitType.PVC_EB, TradeSize.S_6, 6.336, 31.530)
        // The NEC prints no Type EB rows below 2".
        assertNull(ConduitTable.find(ConduitType.PVC_EB, TradeSize.S_1_2))
        assertEquals(listOf(2, 3, 3, 4, 5, 6).size, ConduitTable.sizes(ConduitType.PVC_EB).size)
    }

    @Test fun publishedPercentColumns() {
        // 40 / 31 / 53 percent columns for EMT as printed (within 0.001 in² of the printed value).
        fun check(size: TradeSize, p40: Double, p31: Double, p53: Double) {
            val r = row(ConduitType.EMT, size)
            assertEquals(p40, r.allowedAreaSqIn(40), 0.0011)
            assertEquals(p31, r.allowedAreaSqIn(31), 0.0011)
            assertEquals(p53, r.allowedAreaSqIn(53), 0.0011)
        }
        check(TradeSize.S_1_2, 0.122, 0.094, 0.161)
        check(TradeSize.S_3_4, 0.213, 0.165, 0.283)
        check(TradeSize.S_1_1_4, 0.598, 0.464, 0.793)
        check(TradeSize.S_4, 5.901, 4.573, 7.819)
        assertEquals(0.122, row(ConduitType.EMT, TradeSize.S_1_2).allowedAreaSqIn(40), 1e-12)
    }

    @Test fun metricDesignators() {
        assertEquals(16, TradeSize.S_1_2.metricDesignator)
        assertEquals(35, TradeSize.S_1_1_4.metricDesignator)
        assertEquals(103, TradeSize.S_4.metricDesignator)
        assertEquals(155, TradeSize.S_6.metricDesignator)
        assertEquals(TradeSize.S_2, TradeSize.fromMetric(53))
        assertEquals(TradeSize.S_1_1_2, TradeSize.fromLabel("1-1/2\""))
        assertEquals(TradeSize.S_1, TradeSize.fromLabel(" 1 "))
        assertNull(TradeSize.fromLabel("7"))
    }

    @Test fun everyRowIsInternallyConsistent() {
        for (type in ConduitType.entries) {
            val rows = ConduitTable.sizes(type)
            assertTrue("$type has rows", rows.isNotEmpty())
            // Ascending by size, ID and area.
            assertEquals("$type sorted", rows.sortedBy { it.tradeSize.inches }, rows)
            assertEquals("$type ids ascending", rows.sortedBy { it.internalDiameterIn }, rows)
            assertEquals("$type unique", rows.size, rows.map { it.tradeSize }.toSet().size)
            for (r in rows) {
                // Table 4 total area is pi/4 x ID^2 (both printed rounded).
                val computed = PI / 4 * r.internalDiameterIn * r.internalDiameterIn
                assertEquals("${r.displayName} area vs ID", computed, r.area100SqIn, computed * 0.005)
                assertEquals(type, r.type)
            }
        }
    }

    @Test fun lookupsAndDisplay() {
        val emt = row(ConduitType.EMT, TradeSize.S_1_1_4)
        assertEquals("1-1/4\" EMT", emt.displayName)
        assertEquals("2\" PVC Schedule 40", row(ConduitType.PVC_40, TradeSize.S_2).displayName)
        assertEquals(TradeSize.S_1_1_2, ConduitTable.next(emt)!!.tradeSize)
        assertNull(ConduitTable.next(row(ConduitType.EMT, TradeSize.S_4)))
        // EB has no 1": nearest is the next larger available (2").
        assertEquals(TradeSize.S_2, ConduitTable.nearest(ConduitType.PVC_EB, TradeSize.S_1).tradeSize)
        assertEquals(TradeSize.S_4, ConduitTable.nearest(ConduitType.EMT, TradeSize.S_6).tradeSize)
        assertEquals(ConduitType.LFNC_B, ConduitType.fromCode("lfnc-b"))
        assertNotNull(ConduitType.fromCode("PVC-80"))
    }
}
