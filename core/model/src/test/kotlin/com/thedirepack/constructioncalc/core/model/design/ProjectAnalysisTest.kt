package com.thedirepack.constructioncalc.core.model.design

import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.Circuit
import com.thedirepack.constructioncalc.core.model.Panel
import com.thedirepack.constructioncalc.core.model.PanelSystem
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.Raceway
import com.thedirepack.constructioncalc.core.model.Run
import com.thedirepack.constructioncalc.core.model.stored
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectAnalysisTest {

    @Test fun phasesAndPlacement() {
        assertEquals(listOf(Phase.A, Phase.A, Phase.B, Phase.B, Phase.C, Phase.C, Phase.A), (1..7).map { PanelLayout.phaseOf(it, 3) })
        assertEquals(listOf(Phase.A, Phase.B, Phase.A), listOf(1, 3, 5).map { PanelLayout.phaseOf(it, 1) })
        val panel = Panel(name = "LP", spaces = 42, circuits = listOf(Circuit(slot = 1, poles = 2)))
        assertFalse(PanelLayout.canPlace(panel, 3, 1))
        assertTrue(PanelLayout.canPlace(panel, 2, 1))
        assertFalse(PanelLayout.canPlace(panel, 41, 3)) // 41, 43, 45: past 42
        assertEquals(2, PanelLayout.firstFree(panel, 1))
        val clash = panel.copy(circuits = panel.circuits + Circuit(slot = 3))
        assertTrue(PanelLayout.conflicts(clash).single().contains("overlaps"))
    }

    @Test fun sharedRunDeratesEveryCircuitAndSizesTheConduit() {
        val run = Run(name = "Home run", raceway = Raceway(ConduitType.EMT))
        // Six 1-pole 20 A circuits with neutrals: 12 current-carrying conductors → 50%.
        val circuits = (0 until 6).map { Circuit(slot = 1 + 2 * it, breakerAmps = 20, runIds = listOf(run.id)) }
        val project = Project(name = "P", createdAt = 0, runs = listOf(run), panels = listOf(Panel(name = "LP", circuits = circuits)))
        val design = ProjectAnalysis.analyze(project)
        circuits.forEach { c ->
            val d = design.circuits.getValue(c.id)
            assertEquals(12, d.currentCarrying)
            assertEquals(ConductorSize.AWG_10, d.size) // 12 AWG: 30 × 0.5 = 15 A < 20 A
            assertEquals(ConductorSize.AWG_12, d.groundSize)
        }
        val r = design.runs.getValue(run.id)
        assertEquals(13, r.totalConductors) // 12 + one shared EGC
        assertEquals(12 * 0.0211 + 0.0133, r.totalAreaSqIn, 1e-6)
        assertEquals(TradeSize.S_1, r.smallestFitting!!.tradeSize) // 3/4" EMT 40% = 0.213, 1" = 0.346
        assertEquals(0.5, r.adjustmentFactor, 0.0)
        assertEquals(FillStatus.OK, r.fillStatus) // auto-sized
        // A chosen 3/4" EMT is over the 40% limit.
        val tight = project.copy(runs = listOf(run.copy(raceway = Raceway(ConduitType.EMT, tradeSize = TradeSize.S_3_4))))
        assertEquals(FillStatus.OVER, ProjectAnalysis.analyze(tight).runs.getValue(run.id).fillStatus)
        // Rooftop run in the sun: 35 + 33 = 68 °C → 0.58 on the 90 °C column.
        val roof = project.copy(runs = listOf(run.copy(raceway = Raceway(ConduitType.EMT, ambientC = 35.0, rooftop = true))))
        val rr = ProjectAnalysis.analyze(roof).runs.getValue(run.id)
        assertEquals(68.0, rr.ambientC, 0.0)
        assertEquals(0.58, rr.ambientFactor!!, 0.0)
    }

    @Test fun voltageDropUpsizesWireAndGround() {
        val run = Run(name = "To RTU", raceway = Raceway(ConduitType.EMT, length = Quantity.feet(Rational.of(150)).stored()))
        val c = Circuit(slot = 1, breakerAmps = 20, loadVa = 1440.0, runIds = listOf(run.id)) // 12 A at 120 V
        val project = Project(name = "P", createdAt = 0, runs = listOf(run), panels = listOf(Panel(name = "LP", circuits = listOf(c))))
        val d = ProjectAnalysis.analyze(project).circuits.getValue(c.id)
        assertEquals(12.0, d.amps!!, 1e-9)
        assertEquals(ConductorSize.AWG_12, d.minimumSize)
        // Steel conduit Z at 0.85 PF: 12 AWG 1.736 → 5.2%; 10 AWG 1.053 → 3.2%; 8 AWG 0.697 → 2.1%.
        assertEquals(ConductorSize.AWG_8, d.size)
        assertEquals(2.09, d.voltageDropPercent!!, 0.01)
        assertEquals(ConductorSize.AWG_8, d.groundSize) // 250.122(B): 12 × (16510/6530 cmil) → 8 AWG
    }

    @Test fun panelPhaseBalance() {
        val panel = Panel(
            name = "LP",
            system = PanelSystem.WYE_208_120,
            circuits = listOf(Circuit(slot = 1, loadVa = 1200.0), Circuit(slot = 2, poles = 3, breakerAmps = 30, loadVa = 9000.0)),
        )
        val p = ProjectAnalysis.analyze(Project(name = "P", createdAt = 0, panels = listOf(panel))).panels.single()
        assertEquals(4200.0, p.phaseVa.getValue(Phase.A), 1e-9)
        assertEquals(3000.0, p.phaseVa.getValue(Phase.B), 1e-9)
        assertEquals(35.0, p.phaseAmps.getValue(Phase.A), 1e-9)
        assertEquals(10200.0, p.totalVa, 1e-9)
    }

    @Test fun removingARunUnassignsItFromCircuits() {
        val run = Run(name = "R")
        val c = Circuit(slot = 1, runIds = listOf(run.id))
        val p = Project(name = "P", createdAt = 0, runs = listOf(run), panels = listOf(Panel(name = "LP", circuits = listOf(c))))
        val after = p.removeRun(run.id, 1)
        assertTrue(after.panels.single().circuits.single().runIds.isEmpty())
    }
}
