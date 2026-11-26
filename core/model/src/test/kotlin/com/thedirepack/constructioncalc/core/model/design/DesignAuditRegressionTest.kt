package com.thedirepack.constructioncalc.core.model.design

import com.thedirepack.constructioncalc.core.electrical.ConductorImpedance
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.Circuit
import com.thedirepack.constructioncalc.core.model.DesignDefaults
import com.thedirepack.constructioncalc.core.model.Panel
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.ProjectCodec
import com.thedirepack.constructioncalc.core.model.Raceway
import com.thedirepack.constructioncalc.core.model.Run
import com.thedirepack.constructioncalc.core.model.stored
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignAuditRegressionTest {
    private fun feet(n: Long) = Quantity.feet(Rational.of(n)).stored()

    /** 3-pole + neutral, nonlinear, on 208Y/120: 4 current-carrying conductors of its own. */
    private fun fourCccCircuit(runId: String) = Circuit(
        slot = 1, poles = 3, breakerAmps = 30, loadVa = 6_000.0, neutral = true, nonlinear = true, runIds = listOf(runId),
    )

    @Test fun circuitOnlyInNipplesGetsNoCountAdjustmentLikeItsRun() {
        val nipple = Run(name = "N-1", raceway = Raceway(nipple = true, length = feet(2)))
        val c = fourCccCircuit(nipple.id)
        val project = Project(name = "Nipple", createdAt = 0, panels = listOf(Panel(name = "LP", circuits = listOf(c))), runs = listOf(nipple))
        val design = ProjectAnalysis.analyze(project)

        val run = design.runs.getValue(nipple.id)
        val circuit = design.circuits.getValue(c.id)
        assertEquals(1.0, run.adjustmentFactor, 0.0)
        assertEquals(run.adjustmentFactor, circuit.sizing!!.adjustmentFactor, 0.0)
        assertTrue(circuit.notes.any { "nipple" in it.text })
    }

    @Test fun circuitInARegularRunStillGetsCountAdjustment() {
        val run = Run(name = "R-1", raceway = Raceway(length = feet(50)))
        val c = fourCccCircuit(run.id)
        val project = Project(name = "Run", createdAt = 0, panels = listOf(Panel(name = "LP", circuits = listOf(c))), runs = listOf(run))
        val design = ProjectAnalysis.analyze(project)

        assertTrue(design.runs.getValue(run.id).adjustmentFactor < 1.0)
        assertEquals(design.runs.getValue(run.id).adjustmentFactor, design.circuits.getValue(c.id).sizing!!.adjustmentFactor, 0.0)
    }

    @Test fun sizesMissingFromTable9FlagTheDcResistanceFallback() {
        val big = Circuit(slot = 1, poles = 3, neutral = false, breakerAmps = 700, loadVa = 150_000.0, length = feet(200))
        val small = Circuit(slot = 7, breakerAmps = 20, loadVa = 1_000.0, length = feet(50))
        val project = Project(
            name = "Big", createdAt = 0,
            panels = listOf(Panel(name = "MDP", circuits = listOf(big, small))),
            design = DesignDefaults(upsizeForVoltageDrop = false),
        )
        val design = ProjectAnalysis.analyze(project)
        val bigDesign = design.circuits.getValue(big.id)
        val size = bigDesign.size!!
        assertNull("test needs a size Table 9 lacks", ConductorImpedance.row(size, ConductorMaterial.COPPER))
        assertNotNull(bigDesign.voltageDropPercent)
        assertTrue(bigDesign.voltageDropUsesDcResistance)
        assertTrue(bigDesign.notes.any { it.isWarning && "DC resistance" in it.text })

        val smallDesign = design.circuits.getValue(small.id)
        assertNotNull(smallDesign.voltageDropPercent)
        assertFalse(smallDesign.voltageDropUsesDcResistance)
        assertFalse(smallDesign.notes.any { "DC resistance" in it.text })
    }

    @Test fun perCircuitUpsizeOverrideAffectsOnlyThatCircuit() {
        // Long 20 A circuits: 12 AWG is well over a 3 % drop at 300 ft.
        val upsized = Circuit(slot = 1, breakerAmps = 20, loadVa = 1_800.0, length = feet(300), upsizeForVoltageDrop = true)
        val following = Circuit(slot = 3, breakerAmps = 20, loadVa = 1_800.0, length = feet(300))
        val optedOut = Circuit(slot = 5, breakerAmps = 20, loadVa = 1_800.0, length = feet(300), upsizeForVoltageDrop = false)

        fun analyze(projectWide: Boolean) = ProjectAnalysis.analyze(
            Project(
                name = "VD", createdAt = 0,
                panels = listOf(Panel(name = "LP", circuits = listOf(upsized, following, optedOut))),
                design = DesignDefaults(upsizeForVoltageDrop = projectWide),
            ),
        ).circuits

        val off = analyze(projectWide = false)
        assertTrue(off.getValue(upsized.id).size!! > off.getValue(upsized.id).minimumSize!!)
        assertEquals(off.getValue(following.id).minimumSize, off.getValue(following.id).size)
        assertEquals(off.getValue(optedOut.id).minimumSize, off.getValue(optedOut.id).size)

        val on = analyze(projectWide = true)
        assertTrue(on.getValue(upsized.id).size!! > on.getValue(upsized.id).minimumSize!!)
        assertTrue(on.getValue(following.id).size!! > on.getValue(following.id).minimumSize!!)
        assertEquals(on.getValue(optedOut.id).minimumSize, on.getValue(optedOut.id).size)
    }

    @Test fun perCircuitUpsizeOverrideSurvivesTheCodec() {
        val c = Circuit(slot = 1, upsizeForVoltageDrop = true)
        val project = Project(name = "Codec", createdAt = 0, panels = listOf(Panel(name = "LP", circuits = listOf(c))))
        val decoded = ProjectCodec.decode(ProjectCodec.encode(project))
        assertEquals(true, decoded.panels.single().circuits.single().upsizeForVoltageDrop)
        val plain = Project(name = "Codec", createdAt = 0, panels = listOf(Panel(name = "LP", circuits = listOf(Circuit(slot = 1)))))
        assertNull(ProjectCodec.decode(ProjectCodec.encode(plain)).panels.single().circuits.single().upsizeForVoltageDrop)
    }
}
