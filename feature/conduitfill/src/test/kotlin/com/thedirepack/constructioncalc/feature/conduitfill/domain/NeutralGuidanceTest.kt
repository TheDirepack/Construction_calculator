package com.thedirepack.constructioncalc.feature.conduitfill.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeutralGuidanceTest {

    @Test
    fun singlePhase120VDedicatedNeutralsAlwaysCountAsCcc() {
        val oneCircuit = NeutralGuidance.evaluate(
            voltageSystem = VoltageSystem.V120,
            circuitCount = 1,
            sharedMwbc = false,
            nonlinearHarmonic = false,
        )
        assertEquals(1, oneCircuit.requiredNeutrals)
        assertEquals(1, oneCircuit.neutralCccCount)
        assertTrue(oneCircuit.anyNeutralCountsAsCcc)
        assertNull(oneCircuit.handleTieNote)

        val threeCircuits = NeutralGuidance.evaluate(
            voltageSystem = VoltageSystem.V120,
            circuitCount = 3,
            sharedMwbc = false,
            nonlinearHarmonic = false,
        )
        assertEquals(3, threeCircuits.requiredNeutrals)
        assertEquals(3, threeCircuits.neutralCccCount)

        // Single-leg 120V cannot share a neutral across circuits
        val invalidMwbc = NeutralGuidance.evaluate(
            voltageSystem = VoltageSystem.V120,
            circuitCount = 2,
            sharedMwbc = true,
        )
        assertEquals(2, invalidMwbc.requiredNeutrals)
        assertEquals(2, invalidMwbc.neutralCccCount)
        assertNotNull(invalidMwbc.handleTieNote)
        assertTrue(invalidMwbc.handleTieNote!!.contains("210.4"))
    }

    @Test
    fun splitPhase240VLineToLineNeedsZeroNeutralsAndMwbcNeedsHandleTie() {
        val pure240 = NeutralGuidance.evaluate(
            voltageSystem = VoltageSystem.V240,
            circuitCount = 2,
            sharedMwbc = false,
        )
        assertEquals(0, pure240.requiredNeutrals)
        assertEquals(0, pure240.neutralCccCount)
        assertFalse(pure240.anyNeutralCountsAsCcc)

        val mwbcPair = NeutralGuidance.evaluate(
            voltageSystem = VoltageSystem.V240,
            circuitCount = 2,
            sharedMwbc = true,
            nonlinearHarmonic = false,
        )
        assertEquals(1, mwbcPair.requiredNeutrals)
        assertEquals(0, mwbcPair.neutralCccCount)
        assertFalse(mwbcPair.anyNeutralCountsAsCcc)
        assertNotNull(mwbcPair.handleTieNote)
        assertTrue(mwbcPair.handleTieNote!!.contains("210.4(B)"))
        assertTrue(mwbcPair.handleTieNote.contains("simultaneous disconnecting means"))

        // 3 circuits on 120/240V MWBC = 1 shared pair (0 CCC) + 1 odd 1φ circuit (1 CCC) = 2 neutrals, 1 CCC
        val mwbcThree = NeutralGuidance.evaluate(
            voltageSystem = VoltageSystem.V240,
            circuitCount = 3,
            sharedMwbc = true,
            nonlinearHarmonic = false,
        )
        assertEquals(2, mwbcThree.requiredNeutrals)
        assertEquals(1, mwbcThree.neutralCccCount)
    }

    @Test
    fun threePhaseWyeMwbcHandlesFullGroupVsTwoPhaseVsNonlinear() {
        // 3 circuits on 208Y/120V shared MWBC, linear -> 1 neutral, 0 CCC, NEC 210.4 handle-tie note
        val balancedLinear = NeutralGuidance.evaluate(
            voltageSystem = VoltageSystem.WYE_208_120,
            circuitCount = 3,
            sharedMwbc = true,
            nonlinearHarmonic = false,
        )
        assertEquals(1, balancedLinear.requiredNeutrals)
        assertEquals(0, balancedLinear.neutralCccCount)
        assertFalse(balancedLinear.anyNeutralCountsAsCcc)
        assertNotNull(balancedLinear.handleTieNote)
        assertTrue(balancedLinear.handleTieNote!!.contains("210.4"))

        // 3 circuits on 208Y/120V shared MWBC, nonlinear -> 1 neutral, 1 CCC per NEC 310.15(E)(3)
        val balancedHarmonic = NeutralGuidance.evaluate(
            voltageSystem = VoltageSystem.WYE_208_120,
            circuitCount = 3,
            sharedMwbc = true,
            nonlinearHarmonic = true,
        )
        assertEquals(1, balancedHarmonic.requiredNeutrals)
        assertEquals(1, balancedHarmonic.neutralCccCount)
        assertTrue(balancedHarmonic.anyNeutralCountsAsCcc)
        assertTrue(balancedHarmonic.notes.any { it.contains("310.15(E)(3)") })

        // 2 circuits on 480Y/277V (or 277V) shared MWBC -> 2 phases + N of 3φ wye MUST count as CCC per 310.15(E)(2)
        val twoPhaseWye = NeutralGuidance.evaluate(
            voltageSystem = VoltageSystem.WYE_480_277,
            circuitCount = 2,
            sharedMwbc = true,
            nonlinearHarmonic = false,
        )
        assertEquals(1, twoPhaseWye.requiredNeutrals)
        assertEquals(1, twoPhaseWye.neutralCccCount)
        assertTrue(twoPhaseWye.notes.any { it.contains("310.15(E)(2)") })
        assertNotNull(twoPhaseWye.handleTieNote)
    }

    @Test
    fun wyeDedicatedCircuitsDifferentiateThreePhaseFourWireVsOnePhaseBranches() {
        // 3φ 4W linear circuit: 1 neutral, 0 CCC
        val threePhaseLinear = NeutralGuidance.evaluate(
            voltageSystem = VoltageSystem.WYE_480_277,
            circuitCount = 1,
            sharedMwbc = false,
            nonlinearHarmonic = false,
            threePhaseCircuits = true,
        )
        assertEquals(1, threePhaseLinear.requiredNeutrals)
        assertEquals(0, threePhaseLinear.neutralCccCount)

        // 3φ 4W nonlinear circuit: 1 neutral, 1 CCC (310.15(E)(3))
        val threePhaseHarmonic = NeutralGuidance.evaluate(
            voltageSystem = VoltageSystem.WYE_480_277,
            circuitCount = 2,
            sharedMwbc = false,
            nonlinearHarmonic = true,
            threePhaseCircuits = true,
        )
        assertEquals(2, threePhaseHarmonic.requiredNeutrals)
        assertEquals(2, threePhaseHarmonic.neutralCccCount)

        // Dedicated 277V 1φ L-N branches: 3 neutrals, all 3 CCC
        val dedicated277 = NeutralGuidance.evaluate(
            voltageSystem = VoltageSystem.V277,
            circuitCount = 3,
            sharedMwbc = false,
        )
        assertEquals(3, dedicated277.requiredNeutrals)
        assertEquals(3, dedicated277.neutralCccCount)

        // Shared 277V MWBC across 3 phases of 480Y/277V: 1 neutral, 0 CCC linear
        val mwbc277 = NeutralGuidance.evaluate(
            voltageSystem = VoltageSystem.V277,
            circuitCount = 3,
            sharedMwbc = true,
            nonlinearHarmonic = false,
        )
        assertEquals(1, mwbc277.requiredNeutrals)
        assertEquals(0, mwbc277.neutralCccCount)
        assertNotNull(mwbc277.handleTieNote)
    }

    @Test
    fun mediumVoltageAndCustomReturnExpectedGuidance() {
        val mv = NeutralGuidance.evaluate(VoltageSystem.MEDIUM_VOLTAGE, circuitCount = 1)
        assertEquals(0, mv.requiredNeutrals)
        assertEquals(0, mv.neutralCccCount)
        assertTrue(mv.notes.any { it.contains("311") })

        val custom = NeutralGuidance.evaluate(VoltageSystem.CUSTOM, circuitCount = 2, sharedMwbc = true, nonlinearHarmonic = true)
        assertNull(custom.requiredNeutrals)
        assertNull(custom.neutralCccCount)
        assertTrue(custom.anyNeutralCountsAsCcc)
        assertNotNull(custom.handleTieNote)
    }
}
