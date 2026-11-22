package com.thedirepack.constructioncalc.core.model

import com.thedirepack.constructioncalc.core.electrical.ConductorGroup
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.ConduitFill
import com.thedirepack.constructioncalc.core.electrical.ConduitTable
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.FillConductor
import com.thedirepack.constructioncalc.core.electrical.InsulationType
import com.thedirepack.constructioncalc.core.electrical.PullFormulas
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.design.PullAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit proof tests documenting defects and edge cases found in :core:model and :core:electrical.
 */
class AuditDefectsTest {

    @Test
    fun pullAnalysisEmitsFalseZeroIncomingTensionNoteWhenIncomingTensionListIsEmpty() {
        // Default PullSetup has incomingTension = emptyList(), which uses DEFAULT_INCOMING_LB (25.0 lb).
        val setup = PullSetup(
            cablesFromCircuits = false,
            cables = listOf(PullCable(3, ConductorSize.AWG_12, InsulationType.THHN)),
        )
        val run = Run(
            name = "Branch",
            raceway = Raceway(ConduitType.EMT, TradeSize.S_3_4),
            path = listOf(PathSegment.Straight(Quantity.feet(Rational.of(100)).stored())),
            pull = setup,
        )
        val result = PullAnalysis.analyze(run, runDesign = null)
        assertNotNull(result.forward)
        // Actual incoming tension used in step 0 is 25.0 lb, NOT 0 lb.
        assertEquals(DEFAULT_INCOMING_LB, result.forward!!.steps.first().tensionIn, 1e-9)
        // Bug: emptyList().all { it == 0.0 } is vacuously true, so the misleading note is emitted.
        assertTrue(
            result.notes.any { it.text.startsWith("Incoming tension is 0") },
        )
    }

    @Test
    fun designModelsCurrentlyAcceptNegativeAndZeroInvalidValuesThroughJsonDecode() {
        val invalid = Project.create("Damaged", now = 1L).copy(
            panels = listOf(
                Panel(
                    name = "P1",
                    busAmps = -100,
                    mainAmps = 0,
                    circuits = listOf(
                        Circuit(slot = 1, breakerAmps = -20, loadVa = -500.0),
                    ),
                ),
            ),
            design = DesignDefaults(voltageDropTargetPercent = -3.0),
        )
        val decoded = ProjectCodec.decode(ProjectCodec.encode(invalid))
        assertEquals(-100, decoded.panels.single().busAmps)
        assertEquals(-20, decoded.panels.single().circuits.single().breakerAmps)
        assertEquals(-500.0, decoded.panels.single().circuits.single().loadVa!!, 0.0)
        assertEquals(-3.0, decoded.design.voltageDropTargetPercent, 0.0)
    }

    @Test
    fun conduitFillAndPullFormulasDisagreeOnJamRatioDueToOvalityFactor() {
        // 3 × 2 AWG THHN (OD 0.384 in.) in 1" EMT (ID 1.049 in.):
        // D/d = 1.049 / 0.384 = 2.7318 (outside 2.8..3.2)
        // 1.05 * D/d = 2.8684 (inside 2.8..3.2)
        val conduit = ConduitTable.find(ConduitType.EMT, TradeSize.S_1)!!
        val conductor = FillConductor.Insulated(InsulationType.THHN, ConductorSize.AWG_2)
        val fillJam = ConduitFill.jamCheck(conduit, listOf(ConductorGroup(3, conductor)))!!
        val pullJam = PullFormulas.jamRatio(conduit.internalDiameterIn, conductor.dimensions!!.diameterIn)

        assertFalse("ConduitFill uses D/d without 1.05 ovality", fillJam.inJamRange)
        assertTrue("PullFormulas uses 1.05 * D/d ovality", PullFormulas.inJamRange(pullJam))
    }
}
