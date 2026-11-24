package com.thedirepack.constructioncalc.feature.conduitfill.domain

import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.InsulationType
import com.thedirepack.constructioncalc.core.electrical.NamedCable
import com.thedirepack.constructioncalc.feature.conduitfill.BARE_CODE
import com.thedirepack.constructioncalc.feature.conduitfill.ConductorGroupInput
import com.thedirepack.constructioncalc.feature.conduitfill.ConduitFillInputs
import com.thedirepack.constructioncalc.feature.conduitfill.ConduitFillViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CircuitPresetsTest {

    @Test
    fun allSevenVoltageSystemsExistAndResolveByNameOrLabel() {
        assertEquals(7, VoltageSystem.entries.size)
        assertEquals(
            listOf("120V", "240V", "208Y/120V", "277V", "480Y/277V", "Medium Voltage", "Custom"),
            VoltageSystem.entries.map { it.label },
        )
        VoltageSystem.entries.forEach { preset ->
            assertEquals(preset, VoltageSystem.fromKey(preset.name))
            assertEquals(preset, VoltageSystem.fromKey(preset.label))
        }
        assertEquals(VoltageSystem.V120, VoltageSystem.fromKey("unknown-voltage"))
    }

    @Test
    fun singlePhaseBranchPresetAddsHotNeutralAndOneSharedGround() {
        val cleared = ConduitFillInputs(
            groups = listOf(ConductorGroupInput(count = "0")),
            circuitCount = 0,
        )
        val afterFirst = CircuitPresets.apply(cleared, CircuitPreset.SINGLE_PHASE_BRANCH_2W_G)
        assertEquals(1, afterFirst.circuitCount)
        assertFalse(afterFirst.threePhaseCircuits)
        assertEquals(3, afterFirst.groups.size)

        val hot = afterFirst.groups[0]
        val neutral = afterFirst.groups[1]
        val ground = afterFirst.groups[2]
        assertEquals("1", hot.count)
        assertTrue(hot.currentCarrying)
        assertEquals(ConductorRole.UNGROUNDED, hot.role)
        assertEquals("1", neutral.count)
        assertTrue(neutral.currentCarrying)
        assertEquals(ConductorRole.NEUTRAL, neutral.role)
        assertEquals("1", ground.count)
        assertEquals(BARE_CODE, ground.insulation)
        assertFalse(ground.currentCarrying)
        assertEquals(ConductorRole.GROUND, ground.role)

        // Adding a second 1φ branch merges hots and neutrals and reuses the single shared EGC (NEC 250.122(C)).
        val afterSecond = CircuitPresets.apply(afterFirst, CircuitPreset.SINGLE_PHASE_BRANCH_2W_G)
        assertEquals(2, afterSecond.circuitCount)
        assertEquals("2", afterSecond.groups[0].count)
        assertEquals("2", afterSecond.groups[1].count)
        assertEquals("1", afterSecond.groups[2].count)
        assertEquals(4, afterSecond.derating()!!.currentCarrying)
    }

    @Test
    fun threePhaseCircuitPresetTracksLinearVsHarmonicNeutralCcc() {
        val emptyLinear = ConduitFillInputs(
            groups = listOf(ConductorGroupInput(count = "0")),
            circuitCount = 0,
            nonlinearHarmonic = false,
        )
        val linear = CircuitPresets.apply(emptyLinear, CircuitPreset.THREE_PHASE_CIRCUIT_4W_G)
        assertEquals(1, linear.circuitCount)
        assertTrue(linear.threePhaseCircuits)
        assertEquals("3", linear.groups[0].count)
        assertTrue(linear.groups[0].currentCarrying)
        assertEquals("1", linear.groups[1].count)
        assertEquals(ConductorRole.NEUTRAL, linear.groups[1].role)
        assertFalse(linear.groups[1].currentCarrying)
        assertEquals(3, linear.derating()!!.currentCarrying)

        val harmonic = CircuitPresets.apply(
            emptyLinear.copy(nonlinearHarmonic = true),
            CircuitPreset.THREE_PHASE_CIRCUIT_4W_G,
        )
        assertTrue(harmonic.groups[1].currentCarrying)
        assertEquals(4, harmonic.derating()!!.currentCarrying)
    }

    @Test
    fun groundAndCustomWireAndNamedCablePresetsWork() {
        val base = ConduitFillInputs(groups = listOf(ConductorGroupInput(count = "0")), circuitCount = 0)
        val withGround = CircuitPresets.apply(base, CircuitPreset.GROUND)
        assertEquals(1, withGround.groups.size)
        assertEquals(BARE_CODE, withGround.groups[0].insulation)
        assertEquals("1", withGround.groups[0].count)

        val withTwoGrounds = CircuitPresets.apply(withGround, CircuitPreset.GROUND)
        assertEquals(1, withTwoGrounds.groups.size)
        assertEquals("2", withTwoGrounds.groups[0].count)

        val withCustom = CircuitPresets.apply(withTwoGrounds, CircuitPreset.CUSTOM_WIRE)
        assertEquals(2, withCustom.groups.size)
        assertEquals(InsulationType.THHN.code, withCustom.groups[1].insulation)
        assertEquals(ConductorSize.AWG_12.label, withCustom.groups[1].size)
        assertTrue(withCustom.groups[1].currentCarrying)

        val cable = NamedCable("c1", "3/C #10 MC", diameterIn = 0.62)
        val withCable = CircuitPresets.addNamedCable(base, cable)
        assertEquals(1, withCable.groups.size)
        assertTrue(withCable.groups[0].isCable)
        assertEquals("3/C #10 MC", withCable.groups[0].cableName)
    }

    @Test
    fun viewModelSteppersAndHarmonicSyncBehaveCleanly() {
        val vm = ConduitFillViewModel()
        vm.clearGroups()
        assertEquals(1, vm.inputs.groups.size)
        assertEquals("0", vm.inputs.groups[0].count)
        assertEquals(0, vm.inputs.circuitCount)

        vm.setVoltageSystem(VoltageSystem.WYE_208_120)
        vm.applyPreset(CircuitPreset.THREE_PHASE_CIRCUIT_4W_G)
        assertEquals(3, vm.inputs.derating()!!.currentCarrying)

        // Toggling harmonic load flips the 3φ neutral to CCC
        vm.setNonlinearHarmonic(true)
        assertEquals(4, vm.inputs.derating()!!.currentCarrying)
        vm.setNonlinearHarmonic(false)
        assertEquals(3, vm.inputs.derating()!!.currentCarrying)

        // Single-wire steppers on group 0 (3 -> 4 -> 3)
        vm.incrementCount(0)
        assertEquals("4", vm.inputs.groups[0].count)
        vm.decrementCount(0)
        assertEquals("3", vm.inputs.groups[0].count)

        // Decrementing a 1-count non-last group removes that row
        val beforeGroups = vm.inputs.groups.size
        vm.decrementCount(1) // neutral count is 1
        assertEquals(beforeGroups - 1, vm.inputs.groups.size)

        // Fahrenheit high-precision string is not truncated mid-typing
        vm.setAmbient("37.777778")
        assertEquals("37.777778", vm.inputs.ambientC)
    }
}
