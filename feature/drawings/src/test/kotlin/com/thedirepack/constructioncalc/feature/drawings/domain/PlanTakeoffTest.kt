package com.thedirepack.constructioncalc.feature.drawings.domain

import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.PlanCalibrationLayer
import com.thedirepack.constructioncalc.core.model.PlanDrawing
import com.thedirepack.constructioncalc.core.model.PlanMark
import com.thedirepack.constructioncalc.core.model.PlanMarkKind
import com.thedirepack.constructioncalc.core.model.PlanMeasurement
import com.thedirepack.constructioncalc.core.model.PlanPoint
import com.thedirepack.constructioncalc.core.model.PlanRunRoute
import com.thedirepack.constructioncalc.core.model.PlanZone
import com.thedirepack.constructioncalc.core.model.stored
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanTakeoffTest {
    private fun p(x: Double, y: Double) = PlanPoint(x, y)
    private fun inches(n: Long) = Quantity.inches(Rational.of(n)).stored()
    private fun line(page: Int, length: Double, label: String = "") =
        PlanMeasurement(page = page, first = p(0.0, 10.0 * page), second = p(length, 10.0 * page), label = label)

    private val base = PlanDrawing(
        name = "E1.pdf",
        sha256 = "a".repeat(64),
        calibration = PlanCalibrationLayer(preset = inches(1)),
    )

    @Test fun totalsByPageAndLabel() {
        val drawing = base.copy(
            measurements = listOf(
                line(0, 100.0, "Corridor"),
                line(0, 50.0),
                line(1, 25.0, "Corridor"),
                line(1, 10.0, " corridor "),
            ),
        )
        val takeoff = PlanTakeoff.summarize(drawing)
        assertEquals(4, takeoff.measuredCount)
        assertEquals(Rational.of(185), takeoff.measuredTotal.value)
        assertEquals(listOf(0, 1), takeoff.byPage.map { it.first })
        assertEquals(Rational.of(150), takeoff.byPage[0].second.total.value)
        assertEquals(Rational.of(35), takeoff.byPage[1].second.total.value)
        // Labels group ignoring case and surrounding spaces, named as first typed.
        val corridor = takeoff.byLabel.single()
        assertEquals("Corridor", corridor.name)
        assertEquals(3, corridor.count)
        assertEquals(Rational.of(135), corridor.total.value)
    }

    @Test fun uncalibratedMeasurementsAreCountedButNotTotaled() {
        val zone = PlanZone(id = "z", name = "Detail", page = 0, first = p(500.0, 500.0), second = p(600.0, 600.0))
        val drawing = base.copy(
            zones = listOf(zone),
            measurements = listOf(line(0, 10.0), PlanMeasurement(page = 0, zoneId = "z", first = p(510.0, 510.0), second = p(520.0, 510.0))),
        )
        val takeoff = PlanTakeoff.summarize(drawing)
        assertEquals(1, takeoff.measuredCount)
        assertEquals(1, takeoff.uncalibratedCount)
        assertEquals(Rational.of(10), takeoff.measuredTotal.value)
    }

    @Test fun countsSymbolsByName() {
        fun symbol(name: String) = PlanMark(page = 0, kind = PlanMarkKind.SYMBOL, points = listOf(p(1.0, 1.0)), svg = "<svg/>", text = name)
        val drawing = base.copy(
            marks = listOf(
                symbol("Duplex"), symbol("Duplex"), symbol("2x4 troffer"), symbol(""),
                PlanMark(page = 0, kind = PlanMarkKind.TEXT, points = listOf(p(1.0, 1.0)), text = "5R4-B-36"),
                PlanMark(page = 0, kind = PlanMarkKind.LINE, points = listOf(p(1.0, 1.0), p(2.0, 2.0))),
            ),
        )
        val takeoff = PlanTakeoff.summarize(drawing)
        assertEquals(listOf("2x4 troffer" to 1, "Duplex" to 2, "Symbol" to 1), takeoff.symbols.map { it.name to it.count })
        assertEquals(1, takeoff.textNotes)
        assertFalse(takeoff.isEmpty)
        assertTrue(PlanTakeoff.summarize(base).isEmpty)
    }

    @Test fun circuitCountsPreserveOverallDeviceTotals() {
        val mark = PlanMark(page = 0, kind = PlanMarkKind.SYMBOL, points = listOf(p(1.0, 1.0)), text = "Duplex")
        val takeoff = PlanTakeoff.summarize(base.copy(marks = listOf(
            mark, mark.copy(circuitTag = "E-36"), mark.copy(circuitTag = "e-36"), mark.copy(circuitTag = "E-38"),
        )))
        assertEquals(listOf(SymbolCount("Duplex", 4)), takeoff.symbols)
        assertEquals(listOf(SymbolCount("Duplex", 2, "E-36"), SymbolCount("Duplex", 1, "E-38")), takeoff.symbolsByCircuit)
    }

    @Test fun flagsRoutesWhosePlanLengthMovedSinceTheyWereSent() {
        val route = PlanRunRoute(runId = "r", page = 0, points = listOf(p(0.0, 0.0), p(120.0, 0.0), p(120.0, 60.0)), sentInches = 180.0)
        val same = PlanTakeoff.summarize(base.copy(routes = listOf(route))).routes.single()
        assertEquals(Rational.of(180), same.current!!.value)
        assertFalse(same.changed)

        // Recalibrated to 1 pt = 2 in: the plan now says 360".
        val rescaled = base.copy(calibration = PlanCalibrationLayer(preset = inches(2)), routes = listOf(route))
        val moved = PlanTakeoff.summarize(rescaled).routes.single()
        assertEquals(Rational.of(360), moved.current!!.value)
        assertTrue(moved.changed)

        // Scale removed: can't be measured.
        val lost = PlanTakeoff.summarize(base.copy(calibration = PlanCalibrationLayer(), routes = listOf(route))).routes.single()
        assertNull(lost.current)
        assertTrue(lost.changed)
    }
}
