package com.thedirepack.constructioncalc.feature.conduitfill.domain

import com.thedirepack.constructioncalc.core.electrical.FillDerating
import com.thedirepack.constructioncalc.core.electrical.DeratingGroup
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.InsulationType
import com.thedirepack.constructioncalc.core.electrical.TemperatureRating
import com.thedirepack.constructioncalc.feature.conduitfill.ConduitFillInputs
import com.thedirepack.constructioncalc.core.electrical.AmbientCorrection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.thedirepack.constructioncalc.core.measure.UnitSystem
import com.thedirepack.constructioncalc.feature.conduitfill.ConductorGroupInput

/** Hand-checked against NEC 2023 Tables 310.16, 310.15(B)(1)(1), 310.15(C)(1) and 240.4(B)/(D). */
class FillDeratingTest {
    private val cu = ConductorMaterial.COPPER
    private fun thhn(count: Int, size: ConductorSize = ConductorSize.AWG_12, ccc: Boolean = true) =
        DeratingGroup(count, size, InsulationType.THHN, ccc)
    private val ground = DeratingGroup(1, ConductorSize.AWG_12, null, false)

    private fun compute(
        groups: List<DeratingGroup>,
        ambient: Double = 30.0,
        rooftop: Boolean = false,
        nipple: Boolean = false,
        termination: TemperatureRating = TemperatureRating.C75,
    ) = FillDerating.compute(groups, ambient, rooftop, cu, termination, nipple)

    @Test fun sixCurrentCarryingConductors() {
        val r = compute(listOf(thhn(6), ground))
        assertEquals(6, r.currentCarrying) // the bare ground never counts (310.15(F))
        assertEquals(0.8, r.adjustmentFactor, 0.0)
        val row = r.rows.single()
        assertEquals(30, row.tableAmpacity) // 12 AWG Cu, 90 °C column
        assertEquals(24.0, row.adjusted!!, 1e-9)
        assertEquals(24.0, row.usable!!, 1e-9) // under the 25 A 75 °C terminal limit
        assertEquals(20, row.maxOcpd) // 240.4(B) would allow 25, but 240.4(D) caps 12 AWG Cu at 20 A
        assertEquals(ThermalAdjustmentKind.DERATING, r.thermalStatus().kind)
        assertTrue(r.toResults()[1].label.contains("derated"))
    }

    @Test fun nippleHasNoCountAdjustment() {
        assertEquals(1.0, compute(listOf(thhn(9)), nipple = true).adjustmentFactor, 0.0)
    }

    @Test fun neutralCarryingOnlyUnbalancedCurrentDoesNotCount() {
        val r = compute(listOf(thhn(3), thhn(1, ccc = false)))
        assertEquals(3, r.currentCarrying)
        assertEquals(1.0, r.adjustmentFactor, 0.0)
        assertEquals(ThermalAdjustmentKind.NOMINAL, r.thermalStatus().kind)
    }

    @Test fun coldAmbientUnder30CAppliesRatingIncreaseAbove100Percent() {
        // 10 °C (50 °F): Table 310.15(B)(1)(1) 90 °C column factor = 1.15 (> 1.00)
        val r = compute(listOf(thhn(3)), ambient = 10.0)
        val row = r.rows.single()
        assertEquals(1.15, row.ambientFactor!!, 0.0)
        assertTrue(row.hasAmbientRatingIncrease())
        assertTrue(row.hasNetAmpacityIncrease())
        assertEquals(34.5, row.adjusted!!, 1e-9) // 30 A * 1.15 = 34.5 A (> 30 A table rating)
        assertEquals(25.0, row.usable!!, 1e-9)   // 75 °C terminal cap per 110.14(C)

        val status = r.thermalStatus(UnitSystem.IMPERIAL)
        assertEquals(ThermalAdjustmentKind.RATING_INCREASE, status.kind)
        assertTrue(status.hasAmbientIncrease)
        assertTrue(status.hasNetAmpacityIncrease)
        assertTrue(status.compactLabel.contains("rating increase"))

        val results = r.toResults()
        val wireResult = results[1]
        assertTrue(wireResult.label, wireResult.label.contains("increased"))
        assertFalse(wireResult.label.contains("derated"))
        assertTrue(wireResult.text, wireResult.text.contains("34.5 A"))
        assertTrue(wireResult.text, wireResult.text.contains("1.15"))

        val notices = r.noticeTexts(UnitSystem.METRIC)
        assertTrue(notices.any { it.contains("rating increase", ignoreCase = true) && it.contains("> 1.00") })
    }

    @Test fun coldAmbientOffsetsCountDeratingForMoreThanThreeConductors() {
        // 10 °C (×1.15) with 10 CCC (×0.50): 30 * 1.15 * 0.50 = 17.25 A (vs 15.0 A at 30 °C)
        val cold = compute(listOf(thhn(10)), ambient = 10.0)
        val nominal = compute(listOf(thhn(10)), ambient = 30.0)
        val coldRow = cold.rows.single()
        assertEquals(1.15, coldRow.ambientFactor!!, 0.0)
        assertEquals(17.25, coldRow.adjusted!!, 1e-9)
        assertEquals(17.25, coldRow.usable!!, 1e-9)
        assertTrue(coldRow.usable!! > nominal.rows.single().usable!!)

        val status = cold.thermalStatus()
        assertEquals(ThermalAdjustmentKind.MIXED, status.kind)
        assertTrue(status.hasAmbientIncrease)

        val wireResult = cold.toResults()[1]
        assertTrue(wireResult.label, wireResult.label.contains("adjusted"))
        assertFalse(wireResult.label.contains("derated"))
        assertTrue(wireResult.text, wireResult.text.contains("1.15"))
    }

    @Test fun warmAmbientOver30CDeratesAmpacity() {
        // 45 °C (113 °F): 90 °C column factor = 0.87 (< 1.00)
        val r = compute(listOf(thhn(3)), ambient = 45.0)
        val row = r.rows.single()
        assertEquals(0.87, row.ambientFactor!!, 0.0)
        assertFalse(row.hasAmbientRatingIncrease())
        assertEquals(26.1, row.adjusted!!, 1e-9) // 30 A * 0.87
        assertEquals(25.0, row.usable!!, 1e-9)
        assertEquals(ThermalAdjustmentKind.DERATING, r.thermalStatus().kind)
        assertTrue(r.toResults()[1].label.contains("derated"))
    }

    @Test fun hasNonDefaultThermalsFlagsNon30COrRooftopOrOver3Ccc() {
        val nominal = ConduitFillInputs()
        assertFalse(nominal.hasNonDefaultThermals(nominal.derating()))

        val cold = ConduitFillInputs(ambientC = "15")
        assertTrue(cold.hasNonDefaultThermals(cold.derating()))

        val hot = ConduitFillInputs(ambientC = "40")
        assertTrue(hot.hasNonDefaultThermals(hot.derating()))

        val roof = ConduitFillInputs(rooftop = true)
        assertTrue(roof.hasNonDefaultThermals(roof.derating()))

        val fourCcc = ConduitFillInputs(groups = listOf(ConductorGroupInput(count = "4")))
        assertTrue(fourCcc.hasNonDefaultThermals(fourCcc.derating()))
    }

    @Test fun rooftopAdderAndXhhw2Exemption() {
        val thhnRow = compute(listOf(thhn(3)), ambient = 35.0, rooftop = true).rows.single()
        assertEquals(68.0, thhnRow.ambientC, 0.0)
        assertEquals(0.58, thhnRow.ambientFactor!!, 0.0)
        assertEquals(17.4, thhnRow.usable!!, 1e-9)
        assertEquals(20, thhnRow.maxOcpd) // next standard size up from 17.4 A (240.4(B))
        val xhhw2 = DeratingGroup(3, ConductorSize.AWG_12, InsulationType.XHHW_2, true)
        val xRow = FillDerating.compute(listOf(xhhw2), 35.0, true, cu, TemperatureRating.C75, false).rows.single()
        assertEquals(35.0, xRow.ambientC, 0.0)
        assertEquals(0.96, xRow.ambientFactor!!, 0.0)
        // Only XHHW-2 is exempt (310.15(B)(2) Exception); plain XHHW gets the adder.
        val xhhw = DeratingGroup(3, ConductorSize.AWG_12, InsulationType.XHHW, true)
        assertEquals(68.0, FillDerating.compute(listOf(xhhw), 35.0, true, cu, TemperatureRating.C75, false).rows.single().ambientC, 0.0)
    }

    @Test fun maxOcpdUsesNextSizeUpAndSmallConductorLimits() {
        assertEquals(250, FillDerating.maxOcpd(230.0, ConductorSize.AWG_4_0, cu)) // 4/0 Cu 75 °C: 230 A → 250 A
        assertEquals(30, FillDerating.maxOcpd(35.0, ConductorSize.AWG_10, cu))   // capped by 240.4(D)
        assertEquals(225, FillDerating.maxOcpd(225.0, ConductorSize.AWG_4_0, cu))
    }

    @Test fun twUsesTheSixtyDegreeColumn() {
        val tw = DeratingGroup(3, ConductorSize.AWG_12, InsulationType.TW, true)
        assertEquals(20, compute(listOf(tw)).rows.single().tableAmpacity)
    }

    @Test fun defaultInputsTreatTheBareGroundAsNonCurrentCarrying() {
        val d = ConduitFillInputs().derating()!!
        assertEquals(3, d.currentCarrying)
        assertNull(ConduitFillInputs(ambientC = "hot").derating())
    }

    @Test fun temperatureTextFollowsTheSystem() {
        // The rooftop adder is a temperature difference: the code prints 33 °C (60 °F).
        assertEquals("+60 °F", AmbientCorrection.rooftopAdderText(UnitSystem.IMPERIAL))
        assertEquals("+33 °C", AmbientCorrection.rooftopAdderText(UnitSystem.METRIC))
    }
}
