package com.thedirepack.constructioncalc.feature.pullplanner.domain

import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.BendPlane
import com.thedirepack.constructioncalc.core.model.EquipmentRating
import com.thedirepack.constructioncalc.core.model.PathSegment
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.PullCable
import com.thedirepack.constructioncalc.core.model.PullSetup
import com.thedirepack.constructioncalc.core.model.Raceway
import com.thedirepack.constructioncalc.core.model.Run
import com.thedirepack.constructioncalc.core.model.ValueKind
import com.thedirepack.constructioncalc.core.model.design.PullAnalysis
import com.thedirepack.constructioncalc.core.model.stored
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PullPlanTest {
    private fun ft(n: Long) = Quantity.feet(Rational.of(n)).stored()

    private val path = listOf(
        PathSegment.Straight(ft(100)),
        PathSegment.Bend(90.0),
        PathSegment.PullPoint("JB"),
        PathSegment.Straight(ft(20), 30.0),
        PathSegment.Bend(90.0, plane = BendPlane.VERTICAL_UP),
        PathSegment.Straight(ft(10), 90.0),
    )

    @Test fun profileClimbsWithInclinesAndSagsAndMarksTheRest() {
        val p = PathProfile.of(path, ConduitType.EMT, TradeSize.S_1)
        assertEquals(0.0, p.points.first().y, 0.0)
        assertTrue(p.maxY > 20.0) // 10 ft of 30° plus the riser
        assertEquals(listOf("90°", "JB"), p.markers.map { it.label })
        assertEquals(listOf(false, true), p.markers.map { it.pullPoint })
        assertEquals(listOf(1, 2), p.markers.map { it.pathIndex })
        // The riser ends straight up: last two points share x.
        val (a, b) = p.points.takeLast(2)
        assertEquals(a.x, b.x, 1e-9)
    }

    @Test fun shortStraightsStayVisible() {
        val p = PathProfile.of(listOf(PathSegment.Straight(ft(1000)), PathSegment.Straight(Quantity.inches(Rational.of(1L)).stored())), null, null)
        val last = p.points.last().x - p.points[1].x
        assertTrue(last >= 1000.0 / 25 - 1e-9)
    }

    @Test fun resultsArePublishedWithTheirKinds() {
        val run = Run(
            name = "Feeder", raceway = Raceway(ConduitType.EMT, TradeSize.S_2), path = path,
            pull = PullSetup(
                cablesFromCircuits = false, slackPerEnd = ft(2),
                cables = listOf(PullCable(3, ConductorSize.AWG_4_0, weightLbPerFt = 0.7, diameterIn = 0.6)),
                equipment = listOf(EquipmentRating("Rope", 2000.0)),
            ),
        )
        val r = PullAnalysis.analyze(run, null)
        val results = PullReport.results(r) { "${it.value}" }.associateBy { it.key }
        assertEquals(ValueKind.FORCE, results.getValue(PullReport.KEY_MAX_TENSION).kind)
        assertEquals(ValueKind.NUMBER, results.getValue(PullReport.KEY_MAX_PRESSURE).kind)
        assertEquals(ValueKind.LENGTH, results.getValue(PullReport.KEY_CABLE_NEEDED).kind)
        assertEquals(ValueKind.ANGLE, results.getValue(PullReport.KEY_BEND_DEGREES).kind)
        assertEquals(180.0, results.getValue(PullReport.KEY_BEND_DEGREES).rational!!.toDouble(), 1e-9)
        assertEquals(r.cableLengthIn!!, results.getValue(PullReport.KEY_CABLE_NEEDED).rational!!.toDouble(), 1e-6)
        assertEquals(PullReport.KEY_MAX_TENSION, PullReport.results(r) { "" }.first().key)
        // The report names every limit and the manufacturer preset as guidance.
        val summary = PullReport.summary(r)
        assertTrue(summary, "Tension limit: Rope 2,000 lb" in summary)
        assertTrue(summary, "Southwire" in summary && "guidance" in summary)
        assertTrue(summary, "Friction μ 0.35 (Lubricated pull, guidance)" in summary)
    }

    @Test fun describeSegments() {
        val text = { q: Quantity -> "${q.value}in" }
        assertEquals("Straight 1200in", PullReport.describe(path[0], ConduitType.EMT, TradeSize.S_1, text))
        assertEquals("Straight 240in, up 30°", PullReport.describe(path[3], ConduitType.EMT, TradeSize.S_1, text))
        assertEquals("Bend 90°, R 23/4in (Table 2)", PullReport.describe(path[1], ConduitType.EMT, TradeSize.S_1, text))
        assertTrue(PullReport.describe(path[4], ConduitType.EMT, null, text).contains("once the size is known"))
        assertEquals("Pull point JB", PullReport.describe(path[2], null, null, text))
    }

    @Test fun pathEditsMoveRemoveReplace() {
        val moved = RunEdits.move(path, 0, 1)
        assertEquals(path[1], moved[0])
        assertEquals(path, RunEdits.move(path, 0, 99))
        assertEquals(path.size - 1, RunEdits.remove(path, 2).size)
        val replaced = RunEdits.replace(path, 1, PathSegment.Bend(45.0))
        assertEquals(45.0, (replaced[1] as PathSegment.Bend).angleDegrees, 0.0)
        assertEquals(path.size + 1, RunEdits.replace(path, path.size, PathSegment.PullPoint()).size)
    }

    @Test fun editingALinkedRunStoresDefaultsAndKeepsLengthInStep() {
        val p0 = Project.create("Job", 0)
        val runId = p0.runs.first().id
        val defaults = PullDefaults(slackPerEndFeet = 3.0, incomingTension = 25.0).setup()
        val p1 = RunEdits.apply(p0, runId, 1, defaults) { it.copy(path = listOf(PathSegment.Straight(ft(40)))) }
        val run = p1.run(runId)!!
        assertEquals(defaults, run.pull)
        assertEquals(listOf(25.0), run.pull!!.incomingTension)
        assertEquals(480.0, run.raceway!!.length!!.toQuantity().value.toDouble(), 1e-9)
        val p2 = RunEdits.apply(p1, runId, 2, defaults) { it.copy(raceway = it.raceway!!.copy(tradeSize = TradeSize.S_3_4)) }
        assertEquals(TradeSize.S_3_4, p2.run(runId)!!.raceway!!.tradeSize)
        assertEquals(480.0, p2.run(runId)!!.raceway!!.length!!.toQuantity().value.toDouble(), 1e-9)
    }
}
