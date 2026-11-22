package com.thedirepack.constructioncalc.core.electrical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConductorComparisonTest {

    @Test fun comparesCopperAndAluminumAcrossParallelSets() {
        val result = ConductorComparison.compare(
            ComparisonRequest(
                ocpdAmps = 400,
                loadAmps = 350.0,
                voltage = 480,
                phases = AcPhases.THREE,
                lengthFeet = 200.0,
                conduitType = ConduitType.EMT,
                maxSets = 4,
            ),
        )
        assertEquals(8, result.options.size) // 2 materials × 4 sets

        val cu1 = result.options.first { it.material == ConductorMaterial.COPPER && it.sets == 1 }
        val cu2 = result.options.first { it.material == ConductorMaterial.COPPER && it.sets == 2 }
        val al1 = result.options.first { it.material == ConductorMaterial.ALUMINUM && it.sets == 1 }
        val al2 = result.options.first { it.material == ConductorMaterial.ALUMINUM && it.sets == 2 }

        // 400 A at 75 °C: 1 set Cu -> 500 kcmil (380 A -> next size up 400 A), 2 sets Cu -> 3/0 AWG (200 A × 2 = 400 A)
        assertEquals(ConductorSize.KCMIL_500, cu1.size)
        assertEquals(ConductorSize.AWG_3_0, cu2.size)
        assertEquals(ConductorSize.AWG_3, cu1.egcSize) // Table 250.122 for 400 A Cu = 3 AWG
        assertEquals(ConductorSize.AWG_3, cu2.egcSize) // 250.122(F): full-size EGC per parallel raceway

        // Aluminum needs larger conductor than copper for the same 400 A load
        assertNotNull(al1.size)
        assertNotNull(al2.size)
        assertTrue(al1.size!!.circularMils > cu1.size!!.circularMils)
        assertTrue(al2.size!!.circularMils > cu2.size!!.circularMils)

        // All valid options have voltage drop, minimum conduit, and fill %
        listOf(cu1, cu2, al1, al2).forEach { opt ->
            assertNotNull(opt.voltageDropPercent)
            assertNotNull(opt.minConduit)
            assertNotNull(opt.conduitFillPercent)
            assertTrue(opt.conduitFillPercent!! <= 40.0 + 1e-6)
        }
    }

    @Test fun parallelSetsEnforce1_0MinimumPerNec310_10_G() {
        val result = ConductorComparison.compare(
            ComparisonRequest(
                ocpdAmps = 200,
                loadAmps = 160.0,
                voltage = 208,
                phases = AcPhases.THREE,
                lengthFeet = 100.0,
                maxSets = 2,
            ),
        )
        val cu2 = result.options.first { it.material == ConductorMaterial.COPPER && it.sets == 2 }
        val al2 = result.options.first { it.material == ConductorMaterial.ALUMINUM && it.sets == 2 }
        assertTrue(cu2.size!! >= ConductorSize.AWG_1_0)
        assertTrue(al2.size!! >= ConductorSize.AWG_1_0)
    }

    @Test fun flagsVoltageDropOverTargetOnLongRun() {
        val result = ConductorComparison.compare(
            ComparisonRequest(
                ocpdAmps = 100,
                loadAmps = 80.0,
                voltage = 208,
                phases = AcPhases.SINGLE,
                lengthFeet = 400.0,
                voltageDropTargetPercent = 3.0,
                maxSets = 1,
            ),
        )
        val cu1 = result.options.first { it.material == ConductorMaterial.COPPER && it.sets == 1 }
        assertTrue(cu1.voltageDropPercent!! > 3.0)
        assertTrue(!cu1.meetsVoltageDropTarget)
    }
}
