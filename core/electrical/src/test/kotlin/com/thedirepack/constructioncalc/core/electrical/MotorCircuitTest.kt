package com.thedirepack.constructioncalc.core.electrical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotorCircuitTest {

    @Test
    fun threePhase25Hp460VMotorCircuit() {
        // NEC Table 430.250: 25 HP, 460 V 3-phase = 34 A FLC
        // Conductors (430.22): 1.25 × 34 = 42.5 A → 8 AWG Cu @ 75 °C (50 A)
        // Inverse-time breaker (Table 430.52): 250% × 34 = 85 A → next standard size up = 90 A
        // Disconnect (430.110(A)): 1.15 × 34 = 39.1 A
        // Overload (430.32(A)(1), SF 1.15, nameplate 32 A): 1.25 × 32 = 40.0 A
        // EGC (Table 250.122 for 90 A): 8 AWG Cu
        val res = MotorCircuit.size(
            MotorCircuitRequest(
                phases = AcPhases.THREE,
                hp = 25.0,
                volts = 460,
                motorType = MotorType.DESIGN_B_EFFICIENT,
                protectiveDevice = MotorProtectiveDevice.INVERSE_TIME_BREAKER,
                material = ConductorMaterial.COPPER,
                nameplateFla = 32.0,
                serviceFactor = 1.15,
            ),
        )!!
        assertEquals(34.0, res.flcAmps, 1e-9)
        assertEquals(42.5, res.minConductorAmpacity, 1e-9)
        assertEquals(ConductorSize.AWG_8, res.conductorSize)
        assertEquals(250, res.maxOcpdPercent)
        assertEquals(85.0, res.calculatedMaxOcpdAmps, 1e-9)
        assertEquals(90, res.maxStandardOcpdAmps)
        assertTrue(res.usedNameplateForOverload)
        assertEquals(40.0, res.maxOverloadAmps, 1e-9)
        assertEquals(39.1, res.minDisconnectAmps, 1e-9)
        assertEquals(ConductorSize.AWG_8, res.egcSize)
    }

    @Test
    fun singlePhaseMotorWithLowServiceFactorUses115PercentOverload() {
        // Table 430.248: 1 HP, 115 V 1-phase = 16 A FLC
        val res = MotorCircuit.size(
            MotorCircuitRequest(
                phases = AcPhases.SINGLE,
                hp = 1.0,
                volts = 115,
                motorType = MotorType.SINGLE_PHASE,
                protectiveDevice = MotorProtectiveDevice.DUAL_ELEMENT_FUSE,
                serviceFactor = 1.0,
                tempRiseC = 50.0,
            ),
        )!!
        assertEquals(16.0, res.flcAmps, 1e-9)
        assertEquals(20.0, res.minConductorAmpacity, 1e-9)
        assertEquals(ConductorSize.AWG_14, res.conductorSize) // 14 AWG Cu @ 75 °C = 20 A (430.22 allows 14 AWG for motors)
        assertEquals(175, res.maxOcpdPercent)
        assertEquals(30, res.maxStandardOcpdAmps) // 16 × 1.75 = 28 A → 30 A
        assertFalse(res.usedNameplateForOverload)
        assertEquals(1.15, res.overloadFactor, 1e-9)
        assertEquals(18.4, res.maxOverloadAmps, 1e-9)
    }

    @Test
    fun invalidHpOrVoltageReturnsNull() {
        assertNull(MotorCircuit.size(MotorCircuitRequest(AcPhases.THREE, 999.0, 460)))
        assertNull(MotorCircuit.size(MotorCircuitRequest(AcPhases.SINGLE, 5.0, 460)))
    }
}
