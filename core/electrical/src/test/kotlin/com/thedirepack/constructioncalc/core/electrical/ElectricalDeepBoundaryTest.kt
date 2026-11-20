package com.thedirepack.constructioncalc.core.electrical

import com.thedirepack.constructioncalc.core.electrical.ConductorSize.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deep testing of NEC Article 310, 240, 250, and 210/215 continuous load rules and extreme limits.
 */
class ElectricalDeepBoundaryTest {

    @Test
    fun mixedContinuousAndNonContinuousLoadsFollowArticle210_19() {
        // Continuous load = 40 A, non-continuous = 20 A, OCPD = 80 A
        // Terminal requirement (110.14(C)): 20 A + 1.25 * 40 A = 70 A in 75 °C column.
        // Table 310.16 75 °C Copper: 4 AWG = 85 A (fits 70 A). 6 AWG = 65 A (too small).
        val req = WireSizingRequest(
            ocpdAmps = 80,
            material = ConductorMaterial.COPPER,
            loadAmps = 60.0,
            continuousAmps = 40.0,
            insulation = TemperatureRating.C90,
            termination = TemperatureRating.C75,
        )
        val result = ConductorSizing.minimumSize(req)
        assertNotNull(result.size)
        // 4 AWG Cu handles 85 A at 75 °C terminals (exceeds 70 A required terminal load)
        assertEquals(AWG_4, result.size)
        assertTrue(result.governing.contains("210.19") || result.governing.contains("terminals"))
    }

    @Test
    fun extremeAmbientTemperatureCutoffsHandledCleanly() {
        // > 85 °C: NEC Table 310.15(B)(1)(1) has no correction factor even for 90 °C insulation
        val extremeHeatReq = WireSizingRequest(
            ocpdAmps = 20,
            ambientC = 86.0,
            insulation = TemperatureRating.C90,
        )
        val result = ConductorSizing.minimumSize(extremeHeatReq)
        assertNull("Should return null for ambient temperatures beyond code tables", result.size)
        assertEquals("Too hot", result.governing)
        assertTrue(result.notes.any { it.contains("No 90 °C correction factor") })

        // 60 °C column cuts off above 55 °C
        val cutoff60C = WireSizingRequest(
            ocpdAmps = 20,
            ambientC = 56.0,
            insulation = TemperatureRating.C60,
        )
        assertNull(ConductorSizing.minimumSize(cutoff60C).size)
    }

    @Test
    fun equipmentGroundingConductorProportionalUpsizeComprehensive() {
        // Table 250.122 minimum:
        // 15 A -> 14 Cu
        // 20 A -> 12 Cu
        // 30 A -> 10 Cu
        // 40/60 A -> 10 Cu
        // 100 A -> 8 Cu
        // 200 A -> 6 Cu
        // 400 A -> 3 Cu
        assertEquals(AWG_14, EquipmentGroundingConductor.size(15, ConductorMaterial.COPPER))
        assertEquals(AWG_10, EquipmentGroundingConductor.size(30, ConductorMaterial.COPPER))
        assertEquals(AWG_10, EquipmentGroundingConductor.size(60, ConductorMaterial.COPPER))
        assertEquals(AWG_8, EquipmentGroundingConductor.size(100, ConductorMaterial.COPPER))
        assertEquals(AWG_6, EquipmentGroundingConductor.size(200, ConductorMaterial.COPPER))
        assertEquals(AWG_3, EquipmentGroundingConductor.size(400, ConductorMaterial.COPPER))

        // 250.122(B) proportional upsizing for voltage drop:
        // If ungrounded conductor is upsized from 12 AWG (6530 cmil) to 8 AWG (16510 cmil) -> ratio = 2.528
        // Base EGC on 20 A is 12 AWG (6530 cmil). 6530 * 2.528 = 16510 cmil -> requires 8 AWG EGC.
        val upsizedEgc = EquipmentGroundingConductor.sizeWithUpsize(
            ocpdAmps = 20,
            material = ConductorMaterial.COPPER,
            minimumUngrounded = AWG_12,
            actualUngrounded = AWG_8,
        )
        assertEquals(AWG_8, upsizedEgc)

        // When standard wire equals upsized wire, EGC remains standard
        val noUpsize = EquipmentGroundingConductor.sizeWithUpsize(
            ocpdAmps = 20,
            material = ConductorMaterial.COPPER,
            minimumUngrounded = AWG_12,
            actualUngrounded = AWG_12,
        )
        assertEquals(AWG_12, noUpsize)
    }

    @Test
    fun massiveBundlingDeratingScalesConductorsSafely() {
        // 41+ current-carrying conductors -> 35% adjustment factor
        // 100 A circuit: with 4/0 AWG (90 °C = 260 A), 260 * 0.35 = 91 A usable.
        // Under 240.4(B), 91 A allows next standard size up of 100 A OCPD.
        val req = WireSizingRequest(
            ocpdAmps = 100,
            loadAmps = 80.0,
            currentCarrying = 45,
            insulation = TemperatureRating.C90,
            termination = TemperatureRating.C75,
        )
        val result = ConductorSizing.minimumSize(req)
        assertNotNull(result.size)
        assertTrue(result.size!!.circularMils >= AWG_4_0.circularMils)
        assertEquals(0.35, result.adjustmentFactor, 1e-9)
    }
}
