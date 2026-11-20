package com.thedirepack.constructioncalc.core.electrical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/** Spot checks against NEC 2023 Chapter 9, Table 4 as printed (pp. 70-716 to 70-720; every row was read from the book). */
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
        assertRow(ConduitType.EMT, TradeSize.S_2_1_2, 2.731, 5.858)
        assertRow(ConduitType.EMT, TradeSize.S_3, 3.356, 8.846)
        assertRow(ConduitType.EMT, TradeSize.S_3_1_2, 3.834, 11.545)
        // The 2023 Table 4 (printed p. 70-716) also prints EMT 5" and 6", but as copies of the RMC rows that
        // contradict Annex C.1, so they are deliberately not offered.
        assertNull(ConduitTable.find(ConduitType.EMT, TradeSize.S_5))
        assertEquals(10, ConduitTable.sizes(ConduitType.EMT).size)
    }

    @Test fun otherMetalAndFlexibleTypes() {
        assertRow(ConduitType.RMC, TradeSize.S_3, 3.090, 7.499)
        assertRow(ConduitType.RMC, TradeSize.S_6, 6.093, 29.158)
        assertRow(ConduitType.IMC, TradeSize.S_2_1_2, 2.557, 5.135)
        assertRow(ConduitType.IMC, TradeSize.S_4, 4.166, 13.631)
        // IMC 5" and 6" (2023 Table 4, printed p. 70-717).
        assertRow(ConduitType.IMC, TradeSize.S_1, 1.105, 0.959)
        assertRow(ConduitType.IMC, TradeSize.S_5, 5.210, 21.32)
        assertRow(ConduitType.IMC, TradeSize.S_6, 6.258, 30.76)
        assertEquals(12, ConduitTable.sizes(ConduitType.IMC).size)
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

    @Test fun percentColumnsReproducePrintedValuesExactly() {
        // 40 / 60 / 53 / 31 percent columns, in square inches, as printed in the 2023 Table 4.
        fun check(type: ConduitType, size: TradeSize, p40: Double, p60: Double, p53: Double, p31: Double, tol: Double = 1e-12) {
            val r = row(type, size)
            assertEquals("$type $size 40%", p40, r.allowedAreaSqIn(40), tol)
            assertEquals("$type $size 60%", p60, r.allowedAreaSqIn(60), tol)
            assertEquals("$type $size 53%", p53, r.allowedAreaSqIn(53), tol)
            assertEquals("$type $size 31%", p31, r.allowedAreaSqIn(31), tol)
        }
        // EMT 1/2" to 4" (printed p. 70-716).
        check(ConduitType.EMT, TradeSize.S_1_2, 0.122, 0.182, 0.161, 0.094)
        check(ConduitType.EMT, TradeSize.S_3_4, 0.213, 0.320, 0.283, 0.165)
        check(ConduitType.EMT, TradeSize.S_1, 0.346, 0.519, 0.458, 0.268)
        check(ConduitType.EMT, TradeSize.S_1_1_4, 0.598, 0.897, 0.793, 0.464)
        check(ConduitType.EMT, TradeSize.S_1_1_2, 0.814, 1.221, 1.079, 0.631)
        check(ConduitType.EMT, TradeSize.S_2, 1.342, 2.013, 1.778, 1.040)
        check(ConduitType.EMT, TradeSize.S_2_1_2, 2.343, 3.515, 3.105, 1.816)
        check(ConduitType.EMT, TradeSize.S_3, 3.538, 5.307, 4.688, 2.742)
        check(ConduitType.EMT, TradeSize.S_3_1_2, 4.618, 6.927, 6.119, 3.579)
        check(ConduitType.EMT, TradeSize.S_4, 5.901, 8.852, 7.819, 4.573)
        // IMC, the whole table (printed p. 70-717).
        check(ConduitType.IMC, TradeSize.S_1_2, 0.137, 0.205, 0.181, 0.106)
        check(ConduitType.IMC, TradeSize.S_3_4, 0.235, 0.352, 0.311, 0.182)
        check(ConduitType.IMC, TradeSize.S_1, 0.384, 0.575, 0.508, 0.297)
        check(ConduitType.IMC, TradeSize.S_1_1_4, 0.659, 0.988, 0.873, 0.510)
        check(ConduitType.IMC, TradeSize.S_1_1_2, 0.890, 1.335, 1.179, 0.690)
        check(ConduitType.IMC, TradeSize.S_2, 1.452, 2.178, 1.924, 1.125)
        check(ConduitType.IMC, TradeSize.S_2_1_2, 2.054, 3.081, 2.722, 1.592)
        check(ConduitType.IMC, TradeSize.S_3, 3.169, 4.753, 4.199, 2.456)
        check(ConduitType.IMC, TradeSize.S_3_1_2, 4.234, 6.351, 5.610, 3.281)
        check(ConduitType.IMC, TradeSize.S_4, 5.452, 8.179, 7.224, 4.226)
        // IMC 5" and 6" are the rows whose percent columns the book computed from the rounded printed area,
        // not the diameter (e.g. 5" at 60% prints 12.792, the diameter gives 12.791): allow 0.001.
        check(ConduitType.IMC, TradeSize.S_5, 8.528, 12.792, 11.300, 6.610, tol = 0.0011)
        check(ConduitType.IMC, TradeSize.S_6, 12.304, 18.456, 16.302, 9.536, tol = 0.0011)
        // A few rows of other types (printed pp. 70-717 to 70-719).
        check(ConduitType.FMC, TradeSize.S_1_1_4, 0.511, 0.766, 0.677, 0.396)
        // RMC 3" at 53% prints 3.974 (from the rounded 7.499 area); the diameter gives 3.975.
        check(ConduitType.RMC, TradeSize.S_3, 3.000, 4.499, 3.974, 2.325, tol = 0.0011)
        check(ConduitType.RMC, TradeSize.S_4, 5.153, 7.729, 6.828, 3.994)
        check(ConduitType.PVC_40, TradeSize.S_2, 1.316, 1.975, 1.744, 1.020)
        check(ConduitType.PVC_80, TradeSize.S_2, 1.150, 1.725, 1.523, 0.891)
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
        assertEquals(TradeSize.S_6, ConduitTable.next(row(ConduitType.RMC, TradeSize.S_5))!!.tradeSize)
        // EB has no 1": nearest is the next larger available (2").
        assertEquals(TradeSize.S_2, ConduitTable.nearest(ConduitType.PVC_EB, TradeSize.S_1).tradeSize)
        // PVC Type A has no 5" or 6" row: the largest it has is the answer.
        assertEquals(TradeSize.S_4, ConduitTable.nearest(ConduitType.PVC_A, TradeSize.S_6).tradeSize)
        assertEquals(TradeSize.S_4, ConduitTable.nearest(ConduitType.EMT, TradeSize.S_6).tradeSize)
        assertEquals(ConduitType.LFNC_B, ConduitType.fromCode("lfnc-b"))
        assertNotNull(ConduitType.fromCode("PVC-80"))
    }
}
