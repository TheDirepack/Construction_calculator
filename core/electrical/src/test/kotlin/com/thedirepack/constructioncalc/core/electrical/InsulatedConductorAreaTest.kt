package com.thedirepack.constructioncalc.core.electrical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/**
 * Spot checks against NEC 2023 Chapter 9, Table 5 (printed pp. 70-720 to 70-723) and Table 8 (p. 70-725), as printed.
 * Every area and diameter in the tables was read from the book.
 */
class InsulatedConductorAreaTest {
    private fun area(t: InsulationType, s: ConductorSize) = InsulatedConductorArea.area(t, s)!!

    @Test fun thhnMatchesTable5() {
        assertEquals(0.0097, area(InsulationType.THHN, ConductorSize.AWG_14), 1e-9)
        assertEquals(0.0133, area(InsulationType.THHN, ConductorSize.AWG_12), 1e-9)
        assertEquals(0.0211, area(InsulationType.THHN, ConductorSize.AWG_10), 1e-9)
        assertEquals(0.0507, area(InsulationType.THHN, ConductorSize.AWG_6), 1e-9)
        assertEquals(0.1855, area(InsulationType.THHN, ConductorSize.AWG_1_0), 1e-9)
        assertEquals(0.3237, area(InsulationType.THHN, ConductorSize.AWG_4_0), 1e-9)
        assertEquals(0.7073, area(InsulationType.THHN, ConductorSize.KCMIL_500), 1e-9)
        assertEquals(1.3478, area(InsulationType.THHN, ConductorSize.KCMIL_1000), 1e-9)
        assertEquals(0.324, InsulatedConductorArea.diameter(InsulationType.THHN, ConductorSize.AWG_4)!!, 1e-9)
        // THHN is not listed above 1000 kcmil or below 14 AWG.
        assertNull(InsulatedConductorArea.area(InsulationType.THHN, ConductorSize.KCMIL_1250))
        assertNull(InsulatedConductorArea.area(InsulationType.THHN, ConductorSize.AWG_16))
        assertEquals(ConductorSize.AWG_14, InsulatedConductorArea.sizes(InsulationType.THHN).first())
        assertEquals(ConductorSize.KCMIL_1000, InsulatedConductorArea.sizes(InsulationType.THHN).last())
    }

    @Test fun xhhwMatchesTable5() {
        assertEquals(0.0139, area(InsulationType.XHHW, ConductorSize.AWG_14), 1e-9)
        assertEquals(0.0243, area(InsulationType.XHHW, ConductorSize.AWG_10), 1e-9)
        assertEquals(0.0814, area(InsulationType.XHHW, ConductorSize.AWG_4), 1e-9)
        assertEquals(0.3197, area(InsulationType.XHHW, ConductorSize.AWG_4_0), 1e-9)
        assertEquals(1.3519, area(InsulationType.XHHW, ConductorSize.KCMIL_1000), 1e-9)
        assertEquals(1.7180, area(InsulationType.XHHW, ConductorSize.KCMIL_1250), 1e-9)
        assertEquals(2.6073, area(InsulationType.XHHW, ConductorSize.KCMIL_2000), 1e-9)
        assertEquals(1.822, InsulatedConductorArea.diameter(InsulationType.XHHW, ConductorSize.KCMIL_2000)!!, 1e-9)
    }

    @Test fun thwTwAndRhh() {
        assertEquals(0.0139, area(InsulationType.THW, ConductorSize.AWG_14), 1e-9)
        assertEquals(0.0726, area(InsulationType.THW, ConductorSize.AWG_6), 1e-9)
        assertEquals(0.3718, area(InsulationType.THW, ConductorSize.AWG_4_0), 1e-9)
        assertEquals(2.7818, area(InsulationType.THW, ConductorSize.KCMIL_2000), 1e-9)
        for (s in InsulatedConductorArea.sizes(InsulationType.TW)) {
            assertEquals(area(InsulationType.THW, s), area(InsulationType.TW, s), 0.0)
        }
        // RHH/RHW/RHW-2 with outer covering is the large set; without it, small sizes differ and 6 AWG up equals THW.
        assertEquals(0.0293, area(InsulationType.RHH_COVERED, ConductorSize.AWG_14), 1e-9)
        assertEquals(0.3039, area(InsulationType.RHH_COVERED, ConductorSize.AWG_1_0), 1e-9)
        assertEquals(3.3719, area(InsulationType.RHH_COVERED, ConductorSize.KCMIL_2000), 1e-9)
        assertEquals(0.0209, area(InsulationType.RHH_NO_COVERING, ConductorSize.AWG_14), 1e-9)
        assertEquals(0.0260, area(InsulationType.RHH_NO_COVERING, ConductorSize.AWG_12), 1e-9)
        assertEquals(0.0333, area(InsulationType.RHH_NO_COVERING, ConductorSize.AWG_10), 1e-9)
        assertEquals(0.0556, area(InsulationType.RHH_NO_COVERING, ConductorSize.AWG_8), 1e-9)
        assertEquals(0.0726, area(InsulationType.RHH_NO_COVERING, ConductorSize.AWG_6), 1e-9)
        assertEquals(0.3117, area(InsulationType.RHH_NO_COVERING, ConductorSize.AWG_3_0), 1e-9)
    }

    @Test fun diametersMatchTable5() {
        // Approximate Diameter column, in.
        fun d(t: InsulationType, s: ConductorSize) = InsulatedConductorArea.diameter(t, s)!!
        assertEquals(0.111, d(InsulationType.THHN, ConductorSize.AWG_14), 1e-9)
        assertEquals(0.164, d(InsulationType.THHN, ConductorSize.AWG_10), 1e-9)
        assertEquals(0.446, d(InsulationType.THHN, ConductorSize.AWG_1), 1e-9)
        assertEquals(0.642, d(InsulationType.THHN, ConductorSize.AWG_4_0), 1e-9)
        assertEquals(1.310, d(InsulationType.THHN, ConductorSize.KCMIL_1000), 1e-9)
        assertEquals(0.304, d(InsulationType.THW, ConductorSize.AWG_6), 1e-9)
        assertEquals(1.882, d(InsulationType.THW, ConductorSize.KCMIL_2000), 1e-9)
        assertEquals(0.193, d(InsulationType.RHH_COVERED, ConductorSize.AWG_14), 1e-9)
        assertEquals(2.072, d(InsulationType.RHH_COVERED, ConductorSize.KCMIL_2000), 1e-9)
        assertEquals(0.163, d(InsulationType.RHH_NO_COVERING, ConductorSize.AWG_14), 1e-9)
        assertEquals(0.266, d(InsulationType.RHH_NO_COVERING, ConductorSize.AWG_8), 1e-9)
        assertEquals(0.084, d(InsulationType.TFN, ConductorSize.AWG_18), 1e-9)
    }

    @Test fun everyFamilyHasTheRowsTheBookPrints() {
        // Row counts: THHN/THWN 14 AWG to 1000 kcmil; the others 14 AWG to 2000 kcmil; RHH* (no covering) 14 to 8 AWG
        // with its own values, then the THW rows from 6 AWG up.
        assertEquals(24, InsulatedConductorArea.sizes(InsulationType.THHN).size)
        assertEquals(28, InsulatedConductorArea.sizes(InsulationType.XHHW).size)
        // XHHW-2 shares the XHHW row of Table 5.
        assertEquals(InsulatedConductorArea.sizes(InsulationType.XHHW), InsulatedConductorArea.sizes(InsulationType.XHHW_2))
        assertEquals(area(InsulationType.XHHW, ConductorSize.AWG_4_0), area(InsulationType.XHHW_2, ConductorSize.AWG_4_0), 0.0)
        assertEquals(28, InsulatedConductorArea.sizes(InsulationType.THW).size)
        assertEquals(28, InsulatedConductorArea.sizes(InsulationType.TW).size)
        assertEquals(28, InsulatedConductorArea.sizes(InsulationType.RHH_COVERED).size)
        assertEquals(28, InsulatedConductorArea.sizes(InsulationType.RHH_NO_COVERING).size)
        // RHH covered (the larger rows): 6 to 1 AWG.
        assertEquals(0.1041, area(InsulationType.RHH_COVERED, ConductorSize.AWG_6), 1e-9)
        assertEquals(0.2660, area(InsulationType.RHH_COVERED, ConductorSize.AWG_1), 1e-9)
        assertEquals(0.0437, area(InsulationType.RHH_COVERED, ConductorSize.AWG_10), 1e-9)
        assertEquals(0.0835, area(InsulationType.RHH_COVERED, ConductorSize.AWG_8), 1e-9)
    }

    @Test fun tfnSmallSizes() {
        assertEquals(0.0055, area(InsulationType.TFN, ConductorSize.AWG_18), 1e-9)
        assertEquals(0.0072, area(InsulationType.TFN, ConductorSize.AWG_16), 1e-9)
        assertEquals(listOf(ConductorSize.AWG_18, ConductorSize.AWG_16), InsulatedConductorArea.sizes(InsulationType.TFN))
    }

    @Test fun insulatedIsAlwaysLargerThanBareCopper() {
        for (type in InsulationType.entries) {
            for (size in InsulatedConductorArea.sizes(type)) {
                val insulated = InsulatedConductorArea.area(type, size)!!
                // Table 8 copper area in in^2 from circular mils is the metal alone; insulation adds to it.
                val metalSqIn = size.circularMils * PI / 4 * 1e-6
                assertTrue("$type $size", insulated > metalSqIn)
            }
        }
    }

    @Test fun areaMatchesDiameter() {
        // Table 5 areas are pi/4 x diameter^2 within the rounding of the printed diameter.
        for (type in InsulationType.entries) {
            for (size in InsulatedConductorArea.sizes(type)) {
                val d = InsulatedConductorArea.dimensions(type, size)!!
                val computed = PI / 4 * d.diameterIn * d.diameterIn
                assertEquals("$type $size", computed, d.areaSqIn, computed * 0.03)
            }
        }
    }

    @Test fun tablesAreOrderedAndHaveNoGaps() {
        for (type in InsulationType.entries) {
            val sizes = InsulatedConductorArea.sizes(type)
            assertEquals(sizes.sortedBy { it.circularMils }, sizes)
            val areas = sizes.map { area(type, it) }
            // Areas never shrink with size within a family (RHH uncovered 8 AWG up to 6 AWG is the NEC's own step).
            if (type != InsulationType.RHH_NO_COVERING) assertEquals("$type monotonic", areas.sorted(), areas)
            assertTrue(sizes.isNotEmpty())
        }
    }

    @Test fun nearestSize() {
        assertEquals(ConductorSize.AWG_14, InsulatedConductorArea.nearestSize(InsulationType.THHN, ConductorSize.AWG_16))
        assertEquals(ConductorSize.KCMIL_1000, InsulatedConductorArea.nearestSize(InsulationType.THHN, ConductorSize.KCMIL_2000))
        assertEquals(ConductorSize.AWG_16, InsulatedConductorArea.nearestSize(InsulationType.TFN, ConductorSize.AWG_12))
        assertEquals(ConductorSize.AWG_12, InsulatedConductorArea.nearestSize(InsulationType.THHN, ConductorSize.AWG_12))
    }

    @Test fun bareConductorsMatchTable8() {
        fun bare(s: ConductorSize, solid: Boolean = false) = BareConductorArea.area(s, solid)!!
        assertEquals(0.002, bare(ConductorSize.AWG_18), 1e-9)
        assertEquals(0.004, bare(ConductorSize.AWG_14), 1e-9)
        assertEquals(0.006, bare(ConductorSize.AWG_12), 1e-9)
        assertEquals(0.011, bare(ConductorSize.AWG_10), 1e-9)
        assertEquals(0.017, bare(ConductorSize.AWG_8), 1e-9)
        assertEquals(0.027, bare(ConductorSize.AWG_6), 1e-9)
        assertEquals(0.087, bare(ConductorSize.AWG_1), 1e-9)
        assertEquals(0.219, bare(ConductorSize.AWG_4_0), 1e-9)
        assertEquals(0.260, bare(ConductorSize.KCMIL_250), 1e-9)
        assertEquals(1.042, bare(ConductorSize.KCMIL_1000), 1e-9)
        assertEquals(2.092, bare(ConductorSize.KCMIL_2000), 1e-9)
        // Solid is listed through 8 AWG only.
        assertEquals(0.005, bare(ConductorSize.AWG_12, solid = true), 1e-9)
        assertEquals(0.013, bare(ConductorSize.AWG_8, solid = true), 1e-9)
        assertNull(BareConductorArea.area(ConductorSize.AWG_6, solid = true))
        assertEquals(0.092, BareConductorArea.diameter(ConductorSize.AWG_12)!!, 1e-9)
        assertEquals(0.528, BareConductorArea.diameter(ConductorSize.AWG_4_0)!!, 1e-9)
        assertEquals(30, BareConductorArea.sizes.size)
        // The rest of Table 8's "Overall" columns, spot checked.
        assertEquals(0.046, BareConductorArea.diameter(ConductorSize.AWG_18)!!, 1e-9)
        assertEquals(0.040, BareConductorArea.diameter(ConductorSize.AWG_18, solid = true)!!, 1e-9)
        assertEquals(0.128, BareConductorArea.diameter(ConductorSize.AWG_8, solid = true)!!, 1e-9)
        assertEquals(0.184, BareConductorArea.diameter(ConductorSize.AWG_6)!!, 1e-9)
        assertEquals(0.575, BareConductorArea.diameter(ConductorSize.KCMIL_250)!!, 1e-9)
        assertEquals(1.289, BareConductorArea.diameter(ConductorSize.KCMIL_1250)!!, 1e-9)
        assertEquals(1.632, BareConductorArea.diameter(ConductorSize.KCMIL_2000)!!, 1e-9)
        assertEquals(0.782, BareConductorArea.area(ConductorSize.KCMIL_750)!!, 1e-9)
        assertEquals(1.566, BareConductorArea.area(ConductorSize.KCMIL_1500)!!, 1e-9)
    }

    @Test fun bareAreaFollowsDiameterForLargerSizes() {
        for (size in ConductorSize.entries.filter { it.circularMils >= ConductorSize.AWG_6.circularMils }) {
            val d = BareConductorArea.dimensions(size)!!
            val computed = PI / 4 * d.diameterIn * d.diameterIn
            assertEquals("$size", computed, d.areaSqIn, computed * 0.03)
        }
        assertFalse(BareConductorArea.sizes.isEmpty())
    }

    @Test fun insulationCodesRoundTrip() {
        for (t in InsulationType.entries) assertEquals(t, InsulationType.fromCode(t.code))
        assertNull(InsulationType.fromCode("nope"))
    }
}
