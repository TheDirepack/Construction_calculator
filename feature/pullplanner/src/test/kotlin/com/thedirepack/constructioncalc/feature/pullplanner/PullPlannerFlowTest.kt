package com.thedirepack.constructioncalc.feature.pullplanner

import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.PathSegment
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.PullCable
import com.thedirepack.constructioncalc.core.model.PullSetup
import com.thedirepack.constructioncalc.core.model.Raceway
import com.thedirepack.constructioncalc.core.model.Run
import com.thedirepack.constructioncalc.core.model.RunPath
import com.thedirepack.constructioncalc.core.model.ToolInputs
import com.thedirepack.constructioncalc.core.model.design.PullAnalysis
import com.thedirepack.constructioncalc.core.model.stored
import com.thedirepack.constructioncalc.feature.pullplanner.domain.PullPlannerMode
import com.thedirepack.constructioncalc.feature.pullplanner.domain.PullReport
import com.thedirepack.constructioncalc.feature.pullplanner.domain.QuickPullEstimate
import com.thedirepack.constructioncalc.feature.pullplanner.domain.RunEdits
import com.thedirepack.constructioncalc.feature.pullplanner.domain.STANDALONE_RACEWAY
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun legacyInputsDecodeWithDefaultsAndResolveModeFromPath() {
        val empty = ToolInputs.decode<PullPlannerInputs>(JsonObject(emptyMap()))!!
        assertNull(empty.mode)
        assertNull(empty.quickLength)
        assertEquals(0, empty.quickBends90)
        assertEquals(0, empty.quickBends45)
        assertEquals(0.0, empty.quickExtraBendDegrees, 0.0)
        assertEquals(PullPlannerMode.QUICK_CHECK, empty.resolvedMode(hasDetailedPath = false))
        assertEquals(PullPlannerMode.DETAILED_ROUTE, empty.resolvedMode(hasDetailedPath = true))

        val legacyWithPathJson = ToolInputs.json.parseToJsonElement(
            """
            {
              "projectId": "p-1",
              "runId": "r-1",
              "raceway": {"conduitType": "EMT", "tradeSize": "S_1"},
              "path": [{"type": "straight", "length": {"exact": "1200", "dimension": "LENGTH"}}],
              "pull": null
            }
            """.trimIndent(),
        ) as JsonObject
        val decodedLegacy = ToolInputs.decode<PullPlannerInputs>(legacyWithPathJson)!!
        assertEquals(1, decodedLegacy.path.size)
        assertNull(decodedLegacy.mode)
        assertEquals(PullPlannerMode.DETAILED_ROUTE, decodedLegacy.resolvedMode())
    }

    @Test fun quickInputsRoundTripAndUnknownModeCoercesSafely() {
        val quickInputs = PullPlannerInputs(
            raceway = Raceway(ConduitType.EMT, TradeSize.S_2),
            mode = PullPlannerMode.QUICK_CHECK,
            quickLength = feet(250).stored(),
            quickBends90 = 3,
            quickBends45 = 1,
            quickExtraBendDegrees = 30.0,
        )
        val encoded = ToolInputs.encode(quickInputs)
        val decoded = ToolInputs.decode<PullPlannerInputs>(encoded)!!
        assertEquals(quickInputs, decoded)
        assertEquals(PullPlannerMode.QUICK_CHECK, decoded.resolvedMode(hasDetailedPath = true))

        val futureModeJson = buildJsonObject {
            encoded.forEach { (k, v) -> if (k != "mode") put(k, v) }
            put("mode", JsonPrimitive("FUTURE_MODE"))
        }
        val coerced = ToolInputs.decode<PullPlannerInputs>(futureModeJson)!!
        assertNull(coerced.mode)
        assertEquals(250 * 12.0, coerced.quickLength!!.toQuantity().value.toDouble(), 1e-9)

        val invalidJson = buildJsonObject {
            put("quickBends90", -1)
        }
        assertNull(ToolInputs.decode<PullPlannerInputs>(invalidJson))
    }

    @Test fun openingQuickCheckInDetailedRoutePreservesLengthAndPhysics() {
        val raceway = Raceway(ConduitType.EMT, TradeSize.S_1_1_2)
        val setup = PullSetup(
            cablesFromCircuits = false,
            cables = listOf(PullCable(3, ConductorSize.AWG_1)),
        )
        val run = Run(name = "QuickToDetailed", raceway = raceway)
        val project = Project(name = "Job", createdAt = 0, runs = listOf(run))

        val quick = QuickPullEstimate.evaluate(
            totalLength = feet(120),
            bends90 = 2,
            raceway = raceway,
            setup = setup,
        )
        val updatedProject = RunEdits.apply(project, run.id, 1, setup) {
            it.copy(path = quick.segments)
        }
        val savedRun = updatedProject.run(run.id)!!
        assertEquals(5, savedRun.path.size)
        assertEquals(1440.0, RunPath.lengthInches(savedRun.path, raceway.conduitType, raceway.tradeSize), 1e-6)
        assertEquals(1440.0, savedRun.raceway!!.length!!.toQuantity().value.toDouble(), 1e-6)

        val detailed = PullAnalysis.analyze(savedRun, null)
        assertEquals(quick.analysis.forward!!.peakTension, detailed.forward!!.peakTension, 1e-9)
        assertTrue(savedRun.path.isNotEmpty())
    }

    private fun feet(value: Long) = Quantity.feet(Rational.of(value))
}
