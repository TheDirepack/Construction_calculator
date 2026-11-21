package com.thedirepack.constructioncalc.core.model.design

import com.thedirepack.constructioncalc.core.electrical.CableConfiguration
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.InsulationType
import com.thedirepack.constructioncalc.core.electrical.PullRatings
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.BendPlane
import com.thedirepack.constructioncalc.core.model.Circuit
import com.thedirepack.constructioncalc.core.model.EquipmentRating
import com.thedirepack.constructioncalc.core.model.Panel
import com.thedirepack.constructioncalc.core.model.PanelSystem
import com.thedirepack.constructioncalc.core.model.PathSegment
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.ProjectText
import com.thedirepack.constructioncalc.core.model.PullCable
import com.thedirepack.constructioncalc.core.model.PullSetup
import com.thedirepack.constructioncalc.core.model.Raceway
import com.thedirepack.constructioncalc.core.model.Run
import com.thedirepack.constructioncalc.core.model.stored
import com.thedirepack.constructioncalc.core.model.withRunPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class PullAnalysisTest {
    private fun ft(n: Int) = Quantity.feet(Rational.of(n.toLong())).stored()
    private fun inches(n: Double) = Quantity.inches(Rational.of(n)).stored()

    /**
     * Original workflow fixture (not from any reference): 2" EMT (Table 4 ID 2.067 in.), three
     * single conductors of 0.6 in. OD at a catalog 0.7 lb/ft each, μ = 0.35, 50 lb into the first
     * section and 100 lb into the second.
     * Path: 100 ft level · 90° horizontal at 24 in. · 40 ft climbing 30° · pull box ·
     * 45° horizontal (Table 2 radius, 9-1/2 in.) · 60 ft level · 90° sag at 36 in. up into a riser · 10 ft up.
     * D/d = 3.445 → cradled, W = 1 + (4/3)(0.6/1.467)² = 1.22304. Expected values were computed
     * independently (a separate script from the published equations), not by this code.
     */
    private val fixturePath = listOf(
        PathSegment.Straight(ft(100)),
        PathSegment.Bend(90.0, inches(24.0)),
        PathSegment.Straight(ft(40), 30.0),
        PathSegment.PullPoint("PB-1"),
        PathSegment.Bend(45.0),
        PathSegment.Straight(ft(60)),
        PathSegment.Bend(90.0, inches(36.0), BendPlane.VERTICAL_UP),
        PathSegment.Straight(ft(10), 90.0),
    )
    private val fixtureSetup = PullSetup(
        frictionOverride = 0.35,
        incomingTension = listOf(50.0, 100.0),
        cablesFromCircuits = false,
        cables = listOf(PullCable(3, ConductorSize.AWG_4_0, InsulationType.THHN, weightLbPerFt = 0.7, diameterIn = 0.6)),
    )
    private fun fixtureRun() = Run(name = "Feeder", raceway = Raceway(ConduitType.EMT, TradeSize.S_2), pull = fixtureSetup)

    @Test fun workflowFixtureBothDirections() {
        val p0 = Project(name = "P", createdAt = 0, runs = listOf(fixtureRun()))
        val runId = p0.runs.first().id
        val p = p0.withRunPath(runId, fixturePath, 1)
        val r = PullAnalysis.analyze(p, runId)!!
        assertEquals(CableConfiguration.CRADLED, r.configuration)
        assertEquals(1.223039, r.weightCorrection!!, 1e-6)
        assertEquals(2.1, r.weightLbPerFt, 1e-12)

        val fwd = r.forward!!.steps
        val expectedFwd = listOf(139.893374, 274.087652, 347.227630, 139.966686, 193.902711, 383.803332, 404.803332)
        expectedFwd.forEachIndexed { i, e -> assertEquals("forward step $i", e, fwd[i].tensionOut, 1e-5) }
        assertEquals(listOf(0, 1, 2, 4, 5, 6, 7), fwd.map { it.pathIndex })
        assertEquals(76.247407, fwd[1].pressure!!, 1e-5)
        assertEquals(98.366661, fwd[3].pressure!!, 1e-5)
        assertEquals(71.179198, fwd[5].pressure!!, 1e-5)
        assertEquals(listOf(347.227630, 404.803332), r.forward!!.sectionTensions.map { Math.round(it * 1e6) / 1e6 })

        val rev = r.reverse!!.steps
        val expectedRev = listOf(79.0, 141.477621, 195.413645, 273.507176, 39.139978, 76.835400, 166.728774)
        expectedRev.forEachIndexed { i, e -> assertEquals("reverse step $i", e, rev[i].tensionOut, 1e-5) }
        assertEquals(listOf(7, 6, 5, 4, 2, 1, 0), rev.map { it.pathIndex })
        assertEquals(192.217079, rev[3].pressure!!, 1e-5)

        // Reverse is a different pull: lower peak tension but higher sidewall pressure.
        assertEquals(404.803332, r.forward!!.peakTension, 1e-5)
        assertEquals(273.507176, r.reverse!!.peakTension, 1e-5)
        // Against 5,078 lb (3 × 4/0 Cu) and 1,000 lb/ft (building wire): forward is 9.8% of its SWBP limit, reverse 19.2%.
        assertEquals(PullDirection.FORWARD, r.recommended)
        assertEquals(5078.4, r.tensionLimit!!.value, 1e-9)
        assertEquals(1000.0, r.pressureLimit!!.value, 0.0)

        assertEquals(listOf(90.0, 135.0), r.sectionDegrees)
        assertEquals(210 * 12 + 24 * PI / 2 + 9.5 * PI / 4 + 36 * PI / 2, r.routedLengthIn!!, 1e-9)
        assertEquals(2621.709062, p.run(runId)!!.raceway!!.length!!.toQuantity().value.toDouble(), 1e-5)
    }

    @Test fun slackAddsToCableNeeded() {
        val run = fixtureRun().let { it.copy(pull = it.pull!!.copy(slackPerEnd = ft(3))) }
        val p0 = Project(name = "P", createdAt = 0, runs = listOf(run))
        val p = p0.withRunPath(run.id, fixturePath, 1)
        val r = PullAnalysis.analyze(p, run.id)!!
        assertEquals(r.routedLengthIn!! + 72.0, r.cableLengthIn!!, 1e-9)
        assertEquals(3 * r.cableLengthIn!! / 12, r.conductorFeet!!, 1e-9)
    }

    @Test fun overLimitsProduceWarnings() {
        val run = fixtureRun().let {
            it.copy(pull = it.pull!!.copy(equipment = listOf(EquipmentRating("Rope", 300.0), EquipmentRating("Puller")), pressureLimit = 80.0))
        }
        val p = Project(name = "P", createdAt = 0, runs = listOf(run)).withRunPath(run.id, fixturePath, 1)
        val r = PullAnalysis.analyze(p, run.id)!!
        assertEquals("Rope", r.tensionLimit!!.label)
        assertEquals(listOf("Puller"), r.missingRatings)
        val warnings = r.notes.filter { it.isWarning }.map { it.text }
        assertTrue(warnings.toString(), warnings.any { "over the 300 lb limit (Rope)" in it && it.startsWith("Toward the end") })
        assertTrue(warnings.toString(), warnings.any { "sidewall pressure" in it && "80 lb/ft" in it })
        assertTrue(r.notes.any { "No rating entered for: Puller" in it.text })
        // With the rope limiting tension and SWBP over in both, the lower combined ratio wins.
        assertNotNull(r.recommended)
        assertTrue(r.overTension || r.overPressure)
    }

    @Test fun conductorsComeFromTheRunsCircuits() {
        val run = Run(name = "RTU", raceway = Raceway(ConduitType.EMT))
        val circuit = Circuit(slot = 1, poles = 3, breakerAmps = 100, neutral = false, loadVa = 30_000.0, runIds = listOf(run.id))
        val panel = Panel(name = "MDP", system = PanelSystem.WYE_480_277, circuits = listOf(circuit))
        val p0 = Project(name = "P", createdAt = 0, runs = listOf(run), panels = listOf(panel))
        val p = p0.withRunPath(run.id, listOf(PathSegment.Straight(ft(80)), PathSegment.Bend(90.0), PathSegment.Straight(ft(20))), 1)
        val design = ProjectAnalysis.analyze(p)
        val size = design.circuits.getValue(circuit.id).size!!
        val r = PullAnalysis.analyze(p, run.id, design)!!
        val hots = r.conductors.single { !it.ground }
        assertEquals(3, hots.count)
        assertEquals(size, hots.size)
        assertTrue(r.conductors.single { it.ground }.let { !it.bearsTension && it.fromCircuits })
        assertEquals(4, r.cableCount)
        // Tension limit from the three hots only (the EGC is left out).
        assertEquals(PullRatings.conductorTension(hots.material, size, 3), r.tensionLimit!!.value, 1e-9)
        assertNotNull(r.forward)
        // Bend radius defaults to Table 2 for the size the design picked.
        val conduitSize = design.runs.getValue(run.id).conduit!!.tradeSize
        assertEquals(
            com.thedirepack.constructioncalc.core.electrical.RacewayBends.minimumRadiusInches(ConduitType.EMT, conduitSize)!! / 12,
            r.forward!!.steps[1].radiusFt!!, 1e-12,
        )
        // Voltage drop uses the routed length.
        assertEquals(r.routedLengthIn!!, design.circuits.getValue(circuit.id).lengthInches!!, 1e-6)
        // Adding a hand cable keeps the circuits' ones.
        val more = p.updateRun(run.id, 2) { it.copy(pull = PullSetup(cables = listOf(PullCable(2, ConductorSize.AWG_12)))) }
        assertEquals(6, PullAnalysis.analyze(more, run.id)!!.cableCount)
    }

    @Test fun reverseDiffersWhenBendsAreAtOneEnd() {
        val run = fixtureRun().copy(pull = fixtureSetup.copy(incomingTension = emptyList()))
        val path = listOf(PathSegment.Straight(ft(200)), PathSegment.Bend(90.0, inches(24.0)), PathSegment.Bend(90.0, inches(24.0)))
        val p = Project(name = "P", createdAt = 0, runs = listOf(run)).withRunPath(run.id, path, 1)
        val r = PullAnalysis.analyze(p, run.id)!!
        assertTrue(r.forward!!.peakTension > 2 * r.reverse!!.peakTension)
        assertEquals(PullDirection.REVERSE, r.recommended)
        assertTrue(r.notes.any { "Incoming tension is 0 lb" in it.text })
    }

    @Test fun noPathOrNoConduitMeansNoTension() {
        val run = Run(name = "R", pull = fixtureSetup)
        val r = PullAnalysis.analyze(run, null)
        assertNull(r.forward)
        assertTrue(r.notes.any { it.isWarning && "conduit" in it.text })
    }

    @Test fun jamWarningForThreeCables() {
        // 1-1/4" EMT (1.380) with three 0.5 in. cables: 1.05 × 2.76 = 2.898.
        val run = Run(
            name = "R", raceway = Raceway(ConduitType.EMT, TradeSize.S_1_1_4),
            pull = PullSetup(cablesFromCircuits = false, cables = listOf(PullCable(3, ConductorSize.AWG_2, diameterIn = 0.5))),
        )
        val r = PullAnalysis.analyze(run, null)
        assertTrue(r.jamWarning)
        assertTrue(r.notes.any { it.isWarning && "Jam ratio" in it.text })
    }

    @Test fun projectTextAndTakeoffIncludeThePull() {
        val p0 = Project(name = "P", createdAt = 0, runs = listOf(fixtureRun()))
        val runId = p0.runs.first().id
        val p = p0.withRunPath(runId, fixturePath, 1)
        val text = ProjectText.render(p)
        assertTrue(text, "Pull: Pull 405 lb of 5,078 lb" in text)
        assertTrue(text, "bends 90°, 135°" in text)
        assertTrue(text, "MATERIAL TAKEOFF" in text)
        val takeoff = MaterialTakeoffs.of(p)
        val wire = takeoff.wire.single()
        assertEquals(3 * 2621.709062 / 12, wire.feet, 1e-4)
        assertEquals(2621.709062 / 12, takeoff.conduit.single().feet, 1e-4)
        assertEquals(TradeSize.S_2, takeoff.conduit.single().size)
    }
}
