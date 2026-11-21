package com.thedirepack.constructioncalc.feature.pullplanner

import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.PathSegment
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.PullCable
import com.thedirepack.constructioncalc.core.model.PullSetup
import com.thedirepack.constructioncalc.core.model.Run
import com.thedirepack.constructioncalc.core.model.ToolInputs
import com.thedirepack.constructioncalc.core.model.design.PullAnalysis
import com.thedirepack.constructioncalc.core.model.stored
import com.thedirepack.constructioncalc.feature.pullplanner.domain.PullReport
import com.thedirepack.constructioncalc.feature.pullplanner.domain.RunEdits
import com.thedirepack.constructioncalc.feature.pullplanner.domain.STANDALONE_RACEWAY
import org.junit.Assert.assertEquals
import org.junit.Test

class PullPlannerFlowTest {
    @Test fun pathEditUpdatesTheRunAndTheSavedPullCanBeReopened() {
        val run = Run(name = "Feeder", raceway = STANDALONE_RACEWAY)
        val project = Project(name = "Job", createdAt = 0, runs = listOf(run))
        val setup = PullSetup(
            incomingTension = listOf(10.0),
            slackPerEnd = feet(2).stored(),
            cablesFromCircuits = false,
            cables = listOf(PullCable(weightLbPerFt = 1.0, diameterIn = 0.25)),
        )
        val edited = RunEdits.apply(project, run.id, 1, setup) {
            it.copy(path = listOf(PathSegment.Straight(feet(30).stored())))
        }
        val savedRun = edited.runs.single()
        val raceway = requireNotNull(savedRun.raceway)
        assertEquals(feet(30), raceway.length!!.toQuantity())

        val snapshot = PullPlannerInputs(edited.id, run.id, raceway, savedRun.path, savedRun.pull)
        val restored = ToolInputs.decode<PullPlannerInputs>(ToolInputs.encode(snapshot))!!
        assertEquals(snapshot, restored)
        val analysis = PullAnalysis.analyze(
            Run(name = "Reopened", raceway = restored.raceway, path = restored.path, pull = restored.pull),
            runDesign = null,
        )
        assertEquals(20.5, analysis.forward!!.peakTension, 1e-12)
        val results = PullReport.results(analysis) { it.value.toString() }
        assertEquals(feet(34), results.single { it.key == PullReport.KEY_CABLE_NEEDED }.toQuantity())
    }

    private fun feet(value: Long) = Quantity.feet(Rational.of(value))
}
