package com.thedirepack.constructioncalc.core.electrical

import com.thedirepack.constructioncalc.core.electrical.MotorProtectiveDevice.*
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

/** Values from the printed NEC 2023 Table 430.52(C)(1) (70-350) and Table 110.26(A)(1) (70-68). */
class MotorProtectionTest {
    @Test
    fun workingSpaceIncludesTheUpperVoltageOfEachBand() {
        assertEquals(36, WorkingSpace.depthInches(150.0, WorkingSpaceCondition.THREE))
        assertEquals(48, WorkingSpace.depthInches(150.01, WorkingSpaceCondition.THREE))
        assertEquals(48, WorkingSpace.depthInches(600.0, WorkingSpaceCondition.THREE))
        assertEquals(60, WorkingSpace.depthInches(600.01, WorkingSpaceCondition.THREE))
    }

    @Test
    fun polyphaseInductionMotor() {
        assertEquals(300, MotorProtection.percent(MotorType.POLYPHASE_AC, NONTIME_DELAY_FUSE))
        assertEquals(175, MotorProtection.percent(MotorType.POLYPHASE_AC, DUAL_ELEMENT_FUSE))
        assertEquals(800, MotorProtection.percent(MotorType.POLYPHASE_AC, INSTANTANEOUS_TRIP_BREAKER))
        assertEquals(250, MotorProtection.percent(MotorType.POLYPHASE_AC, INVERSE_TIME_BREAKER))
        assertEquals(2.5, MotorProtection.factor(MotorType.POLYPHASE_AC, INVERSE_TIME_BREAKER), 0.0)
    }

    @Test
    fun designBEfficientAllowsMoreInstantaneousTrip() {
        assertEquals(1100, MotorProtection.percent(MotorType.DESIGN_B_EFFICIENT, INSTANTANEOUS_TRIP_BREAKER))
    }

    @Test
    fun woundRotorAndDc() {
        assertEquals(listOf(150, 150, 800, 150), MotorProtectiveDevice.entries.map { MotorProtection.percent(MotorType.WOUND_ROTOR, it) })
        assertEquals(listOf(150, 150, 250, 150), MotorProtectiveDevice.entries.map { MotorProtection.percent(MotorType.DC_CONSTANT_VOLTAGE, it) })
    }

    @Test
    fun workingSpaceDepths() {
        assertEquals(36, WorkingSpace.depthInches(120.0, WorkingSpaceCondition.THREE))
        assertEquals(42, WorkingSpace.depthInches(277.0, WorkingSpaceCondition.TWO))
        assertEquals(48, WorkingSpace.depthInches(277.0, WorkingSpaceCondition.THREE))
        assertEquals(60, WorkingSpace.depthInches(1000.0, WorkingSpaceCondition.THREE))
        assertNull(WorkingSpace.depthInches(1001.0, WorkingSpaceCondition.ONE))
    }
}
