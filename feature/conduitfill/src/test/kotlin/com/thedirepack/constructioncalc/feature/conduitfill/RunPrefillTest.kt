package com.thedirepack.constructioncalc.feature.conduitfill

import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.model.Circuit
import com.thedirepack.constructioncalc.core.model.Panel
import com.thedirepack.constructioncalc.core.model.PanelSystem
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.Raceway
import com.thedirepack.constructioncalc.core.model.Run
import com.thedirepack.constructioncalc.core.model.design.ProjectAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunPrefillTest {
    @Test fun aRunsConductorsLandInTheFillTool() {
        val run = Run(name = "Home run", raceway = Raceway(ConduitType.PVC_40, ambientC = 40.0))
        val a = Circuit(slot = 1, poles = 2, breakerAmps = 30, neutral = true, runIds = listOf(run.id)) // 2 hots + neutral (not counted)
        val b = Circuit(slot = 2, breakerAmps = 20, runIds = listOf(run.id)) // hot + neutral, both counted
        val panel = Panel(name = "LP", system = PanelSystem.SINGLE_120_240, circuits = listOf(a, b))
        val project = Project(name = "P", createdAt = 0, runs = listOf(run), panels = listOf(panel))
        val inputs = conduitFillInputsForRun(project, run.id)!!
        assertEquals(ConduitType.PVC_40.code, inputs.conduitType)
        val auto = ProjectAnalysis.analyze(project).runs.getValue(run.id).conduit!!.tradeSize
        assertEquals(auto.label, inputs.tradeSize)
        assertEquals("40", inputs.ambientC)
        // Counts add up to the run's conductors, and the tool counts the same current-carrying ones.
        assertEquals(6, inputs.groups.sumOf { it.count.toInt() })
        assertEquals(4, inputs.derating()!!.currentCarrying)
        val rd = ProjectAnalysis.analyze(project).runs.getValue(run.id)
        assertEquals(rd.totalAreaSqIn, inputs.toRequest().request!!.groups.sumOf { it.count * it.spec.dimensions!!.areaSqIn }, 1e-9)
    }

    @Test fun aChosenSizeWins() {
        val run = Run(name = "R", raceway = Raceway(ConduitType.EMT, tradeSize = TradeSize.S_1))
        val project = Project(name = "P", createdAt = 0, runs = listOf(run))
        assertEquals("1", conduitFillInputsForRun(project, run.id)!!.tradeSize)
    }

    @Test fun noConduitNoPrefill() {
        val run = Run(name = "Plain")
        assertNull(conduitFillInputsForRun(Project(name = "P", createdAt = 0, runs = listOf(run)), run.id))
    }
}
