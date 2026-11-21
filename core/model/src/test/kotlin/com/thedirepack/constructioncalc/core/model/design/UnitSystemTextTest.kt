package com.thedirepack.constructioncalc.core.model.design

import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.InsulationType
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.measure.UnitSystem
import com.thedirepack.constructioncalc.core.model.Circuit
import com.thedirepack.constructioncalc.core.model.Panel
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The design engine's text in both unit systems; the numbers underneath are the same. */
class UnitSystemTextTest {
    private val imperial = UnitSystem.IMPERIAL
    private val metric = UnitSystem.METRIC

    @Test fun forceAndPressureText() {
        assertEquals("1,000 lb", PullAnalysis.lb(1000.0, imperial))
        assertEquals("4,448 N", PullAnalysis.lb(1000.0, metric))
        assertEquals("320 lb/ft", PullAnalysis.pressure(320.0, imperial))
        assertEquals("4.67 kN/m", PullAnalysis.pressure(320.0, metric))
        assertEquals("0.5 in.", PullAnalysis.smallLength(0.5, imperial, 2))
        assertEquals("12.7 mm", PullAnalysis.smallLength(0.5, metric, 2))
    }

    private fun pullProject(): Project {
        val ft = { n: Long -> Quantity.feet(Rational.of(n)).stored() }
        val run = Run(
            name = "Feeder", raceway = Raceway(ConduitType.EMT, TradeSize.S_2),
            pull = PullSetup(
                frictionOverride = 0.35, incomingTension = listOf(50.0), cablesFromCircuits = false,
                cables = listOf(PullCable(3, ConductorSize.AWG_4_0, InsulationType.THHN, weightLbPerFt = 0.7, diameterIn = 0.6)),
            ),
        )
        return Project(name = "P", createdAt = 0, runs = listOf(run))
            .withRunPath(run.id, listOf(PathSegment.Straight(ft(100)), PathSegment.Bend(90.0, Quantity.inches(Rational.of(24L)).stored()), PathSegment.Straight(ft(20))), 1)
    }

    @Test fun pullSummaryFollowsTheSystemAndKeepsTheNumbers() {
        val p = pullProject()
        val run = p.runs.single()
        val i = PullAnalysis.analyze(run, null, imperial)
        val m = PullAnalysis.analyze(run, null, metric)
        assertEquals(i.maxTension, m.maxTension)
        val imperialSummary = PullText.summary(i, imperial)!!
        val metricSummary = PullText.summary(m, metric)!!
        assertTrue(imperialSummary, imperialSummary.startsWith("Pull ") && imperialSummary.contains(" lb") && imperialSummary.contains("lb/ft"))
        assertTrue(metricSummary, metricSummary.contains(PullAnalysis.lb(m.maxTension!!, metric)) && metricSummary.contains("kN/m"))
        assertFalse(metricSummary, metricSummary.contains(" lb"))
        assertTrue(metricSummary.contains("SWBP ") && metricSummary.contains(" of 14.59 kN/m"))
    }

    @Test fun pullNotesFollowTheSystem() {
        val run = pullProject().runs.single().copy(pull = pullProject().runs.single().pull!!.copy(incomingTension = listOf(0.0)))
        assertTrue(PullAnalysis.analyze(run, null, imperial).notes.any { it.text.startsWith("Incoming tension is 0 lb.") })
        assertTrue(PullAnalysis.analyze(run, null, metric).notes.any { it.text.startsWith("Incoming tension is 0 N.") })
    }

    @Test fun designNotesShowTemperaturesInTheSystem() {
        val run = Run(name = "Roof", raceway = Raceway(ConduitType.EMT, ambientC = 35.0, rooftop = true))
        val circuits = (0 until 4).map { Circuit(slot = 1 + 2 * it, breakerAmps = 20, loadVa = 1200.0, runIds = listOf(run.id)) }
        val project = Project(name = "P", createdAt = 0, runs = listOf(run), panels = listOf(Panel(name = "LP", circuits = circuits)))
        val c = circuits.first().id
        val ni = ProjectAnalysis.analyze(project, imperial).circuits.getValue(c).notes.map { it.text }
        val nm = ProjectAnalysis.analyze(project, metric).circuits.getValue(c).notes.map { it.text }
        assertTrue(ni.toString(), ni.any { "+60 °F adder" in it })
        assertTrue(nm.toString(), nm.any { "+33 °C adder" in it })
        val ri = ProjectAnalysis.analyze(project, imperial).runs.getValue(run.id).notes.map { it.text }
        val rm = ProjectAnalysis.analyze(project, metric).runs.getValue(run.id).notes.map { it.text }
        assertTrue(ri.toString(), ri.any { it.startsWith("154.4 °F: ampacity") })
        assertTrue(rm.toString(), rm.any { it.startsWith("68 °C: ampacity") })
    }

    @Test fun takeoffAndProjectTextUseTheSystemsLengths() {
        val takeoff = MaterialTakeoff(
            wire = listOf(WireTakeoff(ConductorSize.AWG_12, InsulationType.THHN, com.thedirepack.constructioncalc.core.electrical.ConductorMaterial.COPPER, 300.0, emptyList())),
            conduit = listOf(ConduitTakeoff(ConduitType.EMT, TradeSize.S_3_4, 100.0, emptyList())),
            notes = emptyList(),
        )
        val i = ProjectText.takeoffText(takeoff, imperial)
        val m = ProjectText.takeoffText(takeoff, metric)
        assertTrue(i, i.contains("Wire 12 AWG THHN Cu: 300 ft") && i.contains("Conduit 3/4\" EMT: 100 ft"))
        assertTrue(m, m.contains("Wire 12 AWG THHN Cu: 91 m") && m.contains("Conduit 3/4\" (21) EMT: 30 m"))
    }

    @Test fun projectTextCarriesThePullInTheSystem() {
        val p = pullProject()
        val i = ProjectText.render(p, units = imperial)
        val m = ProjectText.render(p, units = metric)
        assertTrue(i, Regex("Pull: Pull [\\d,]+ lb").containsMatchIn(i))
        assertTrue(m, Regex("Pull: Pull [\\d,]+ N").containsMatchIn(m))
    }

    @Test fun publishedMaxTensionFollowsTheSystemButKeepsPounds() {
        val p = pullProject()
        val design = ProjectAnalysis.analyze(p)
        val rd = design.runs.getValue(p.runs.single().id)
        val i = PublishedValues.run(rd, imperial) { it.toString() }.first { it.key.endsWith("max_tension") }
        val m = PublishedValues.run(rd, metric) { it.toString() }.first { it.key.endsWith("max_tension") }
        assertEquals(i.exact, m.exact)
        assertTrue(i.text.endsWith(" lb"))
        assertTrue(m.text.endsWith(" N"))
    }
}
