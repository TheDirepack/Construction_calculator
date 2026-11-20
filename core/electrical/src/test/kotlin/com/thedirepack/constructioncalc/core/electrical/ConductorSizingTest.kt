package com.thedirepack.constructioncalc.core.electrical

import com.thedirepack.constructioncalc.core.electrical.ConductorSize.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hand-checked against NEC 2023 Tables 310.16, 310.15(B)(1)(1), 310.15(C)(1), 240.4(D), 250.122. */
class ConductorSizingTest {
    private fun size(req: WireSizingRequest) = ConductorSizing.minimumSize(req).size

    @Test fun basicBranchCircuitsFollowSmallConductorRule() {
        assertEquals(AWG_14, size(WireSizingRequest(ocpdAmps = 15)))
        assertEquals(AWG_12, size(WireSizingRequest(ocpdAmps = 20)))
        assertEquals(AWG_10, size(WireSizingRequest(ocpdAmps = 30)))
        assertTrue(ConductorSizing.minimumSize(WireSizingRequest(ocpdAmps = 20)).governing.contains("240.4(D)"))
    }

    @Test fun conductorCountDerating() {
        // 12 AWG THHN 90 °C = 30 A: ×0.8 = 24, ×0.7 = 21 still protect 20 A; ×0.5 = 15 doesn't.
        assertEquals(AWG_12, size(WireSizingRequest(ocpdAmps = 20, currentCarrying = 6)))
        assertEquals(AWG_12, size(WireSizingRequest(ocpdAmps = 20, currentCarrying = 9)))
        // 15 A is a standard rating, so no next-size-up; 10 AWG: 40 × 0.5 = 20.
        assertEquals(AWG_10, size(WireSizingRequest(ocpdAmps = 20, currentCarrying = 10)))
    }

    @Test fun rooftopAdderAndXhhw2Exception() {
        // 35 + 33 = 68 °C → 90 °C factor 0.58: 12 AWG = 17.4 A, next standard up = 20 A (240.4(B)).
        val roof = WireSizingRequest(ocpdAmps = 20, ambientC = 35.0, rooftopUnder3_4In = true)
        val r = ConductorSizing.minimumSize(roof)
        assertEquals(68.0, r.effectiveAmbientC, 0.0)
        assertEquals(0.58, r.ambientFactor!!, 0.0)
        assertEquals(AWG_12, r.size)
        // Not allowed with multiple cord-and-plug receptacles: 10 AWG (40 × 0.58 = 23.2).
        assertEquals(AWG_10, size(roof.copy(multipleCordReceptacles = true)))
        // XHHW-2 isn't subject to the adder: 35 °C → 0.96.
        assertEquals(0.96, ConductorSizing.minimumSize(roof.copy(xhhw2 = true)).ambientFactor!!, 0.0)
    }

    @Test fun continuousLoadAtTerminals() {
        // 80 A continuous on 100 A: 125% = 100 A at 75 °C terminals → 3 AWG Cu (100 A).
        val r = ConductorSizing.minimumSize(WireSizingRequest(ocpdAmps = 100, loadAmps = 80.0, continuousAmps = 80.0))
        assertEquals(AWG_3, r.size)
        assertEquals(AWG_4_0, size(WireSizingRequest(ocpdAmps = 225)))
    }

    @Test fun aluminum() {
        assertEquals(AWG_10, size(WireSizingRequest(ocpdAmps = 20, material = ConductorMaterial.ALUMINUM)))
        assertEquals(AWG_10, size(WireSizingRequest(ocpdAmps = 20, material = ConductorMaterial.COPPER_CLAD_ALUMINUM)))
    }

    @Test fun tooHotForInsulation() {
        assertNull(size(WireSizingRequest(ocpdAmps = 20, ambientC = 60.0, insulation = TemperatureRating.C60)))
    }

    @Test fun derationTables() {
        assertEquals(0.91, AmbientCorrection.factor(40.0, TemperatureRating.C90)!!, 0.0)
        assertEquals(0.91, AmbientCorrection.factor(35.4, TemperatureRating.C90)!!, 0.0) // rounds into the 36–40 row
        assertEquals(1.20, AmbientCorrection.factor(5.0, TemperatureRating.C75)!!, 0.0)
        assertNull(AmbientCorrection.factor(86.0, TemperatureRating.C90))
        assertEquals(1.0, ConductorCountAdjustment.factor(3), 0.0)
        assertEquals(0.45, ConductorCountAdjustment.factor(21), 0.0)
        assertEquals(0.35, ConductorCountAdjustment.factor(41), 0.0)
    }

    @Test fun equipmentGroundingConductor() {
        assertEquals(AWG_12, EquipmentGroundingConductor.size(20, ConductorMaterial.COPPER))
        assertEquals(AWG_8, EquipmentGroundingConductor.size(100, ConductorMaterial.COPPER))
        assertEquals(AWG_1, EquipmentGroundingConductor.size(400, ConductorMaterial.ALUMINUM))
        assertEquals(AWG_3, EquipmentGroundingConductor.size(400, ConductorMaterial.COPPER))
        // 250.122(B): 12 → 10 AWG for voltage drop (×1.59 cmil) bumps a 12 AWG EGC to 10 AWG.
        assertEquals(AWG_10, EquipmentGroundingConductor.sizeWithUpsize(20, ConductorMaterial.COPPER, AWG_12, AWG_10))
    }

    @Test fun currentCarryingConductorCounting() {
        val c = CurrentCarryingConductors
        assertEquals(2, c.count(1, hasNeutral = true, system = SupplySystem.WYE_3PH_4W))
        assertEquals(2, c.count(2, hasNeutral = true, system = SupplySystem.SINGLE_PHASE_3W))
        assertEquals(3, c.count(2, hasNeutral = true, system = SupplySystem.WYE_3PH_4W)) // 310.15(E)(2)
        assertEquals(3, c.count(3, hasNeutral = true, system = SupplySystem.WYE_3PH_4W))
        assertEquals(4, c.count(3, hasNeutral = true, system = SupplySystem.WYE_3PH_4W, nonlinear = true))
        assertEquals(3, c.count(3, hasNeutral = false, system = SupplySystem.DELTA_3PH_3W))
    }
}
