package com.thedirepack.constructioncalc.feature.drawings.domain

import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Dimension
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.PlanCalibrationLayer
import com.thedirepack.constructioncalc.core.model.PlanCustomSymbol
import com.thedirepack.constructioncalc.core.model.PlanReviewState
import com.thedirepack.constructioncalc.core.model.PlanSymbolPlacement
import com.thedirepack.constructioncalc.core.model.PlanDrawing
import com.thedirepack.constructioncalc.core.model.PlanMark
import com.thedirepack.constructioncalc.core.model.PlanMarkKind
import com.thedirepack.constructioncalc.core.model.PlanMeasurement
import com.thedirepack.constructioncalc.core.model.PlanPath
import com.thedirepack.constructioncalc.core.model.PlanPoint
import com.thedirepack.constructioncalc.core.model.PlanRunRoute
import com.thedirepack.constructioncalc.core.model.PlanZone
import com.thedirepack.constructioncalc.core.model.SheetRevision
import com.thedirepack.constructioncalc.core.model.sheetSource
import com.thedirepack.constructioncalc.core.model.stored
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DUPLEX = "receptacle.duplex"

class PlanTakeoffTest {
    private fun p(x: Double, y: Double) = PlanPoint(x, y)
    private fun inches(n: Long) = Quantity.inches(Rational.of(n)).stored()
    private fun line(page: Int, length: Double, label: String = "") =
        PlanMeasurement(page = page, first = p(0.0, 10.0 * page), second = p(length, 10.0 * page), label = label)

    private fun symbol(page: Int, at: PlanPoint, deviceId: String = DUPLEX, state: PlanReviewState = PlanReviewState.ACCEPTED, circuit: String = "", area: String = "") =
        PlanMark(page = page, kind = PlanMarkKind.SYMBOL, points = listOf(at), symbol = PlanSymbolPlacement(deviceId, reviewState = state), circuitTag = circuit, areaTag = area)

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

    @Test fun takeoffCarriesSourcesForBothMeasuredAndCountOnlySheets() {
        val drawing = base.copy(
            measurements = listOf(line(0, 25.0), line(0, 10.0)),
            marks = listOf(symbol(3, p(1.0, 2.0))),
            sheetRevisions = mapOf(0 to SheetRevision("E101", "C", 3), 3 to SheetRevision("E501", "A", 1)),
        )
        assertEquals(listOf(drawing.sheetSource(0), drawing.sheetSource(3)), PlanTakeoff.summarize(drawing).sources)
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

    @Test fun countsAcceptedSymbolsByCatalogDevice() {
        val custom = PlanCustomSymbol("custom:x", "Floor box", "<svg viewBox=\"0 0 1 1\"><rect width=\"1\" height=\"1\"/></svg>")
        val drawing = base.copy(
            customSymbols = listOf(custom),
            marks = listOf(
                symbol(0, p(1.0, 1.0)), symbol(0, p(2.0, 1.0)), symbol(0, p(3.0, 1.0), "luminaire.troffer_2x4"), symbol(0, p(4.0, 1.0), "custom:x"),
                // Detections waiting for review and rejected ones never count.
                symbol(0, p(5.0, 1.0), state = PlanReviewState.PENDING),
                symbol(0, p(6.0, 1.0), state = PlanReviewState.REJECTED),
                PlanMark(page = 0, kind = PlanMarkKind.TEXT, points = listOf(p(1.0, 1.0)), text = "5R4-B-36"),
                PlanMark(page = 0, kind = PlanMarkKind.LINE, points = listOf(p(1.0, 1.0), p(2.0, 2.0))),
            ),
        )
        val takeoff = PlanTakeoff.summarize(drawing)
        assertEquals(
            listOf("Floor box" to 1, "Light Fixture - Troffer 2x4" to 1, "Receptacle - Duplex" to 2),
            takeoff.symbols.map { it.name to it.count },
        )
        assertEquals(1, takeoff.pendingSymbols)
        assertEquals(1, takeoff.textNotes)
        assertFalse(takeoff.isEmpty)
        assertTrue(PlanTakeoff.summarize(base).isEmpty)
    }

    @Test fun circuitCountsPreserveOverallDeviceTotals() {
        val mark = symbol(0, p(1.0, 1.0))
        val takeoff = PlanTakeoff.summarize(base.copy(marks = listOf(
            mark, mark.copy(circuitTag = "E-36"), mark.copy(circuitTag = "e-36"), mark.copy(circuitTag = "E-38"),
        )))
        val name = "Receptacle - Duplex"
        assertEquals(listOf(SymbolCount(name, 4, deviceId = DUPLEX)), takeoff.symbols)
        assertEquals(listOf(SymbolCount(name, 2, "E-36", deviceId = DUPLEX), SymbolCount(name, 1, "E-38", deviceId = DUPLEX)), takeoff.symbolsByCircuit)
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

    @Test fun summarizesPathsAreasAreaTagsAndCombinedCircuitTakeoff() {
        val zone = PlanZone(id = "z", name = "Detail", page = 1, first = p(200.0, 200.0), second = p(300.0, 300.0), preset = inches(2))
        val openPath = PlanPath(page = 0, points = listOf(p(0.0, 0.0), p(30.0, 0.0), p(30.0, 40.0)), closed = false)
        val rectArea = PlanPath(page = 0, points = listOf(p(0.0, 0.0), p(10.0, 0.0), p(10.0, 20.0), p(0.0, 20.0)), closed = true)
        val crossingPath = PlanPath(page = 1, points = listOf(p(50.0, 250.0), p(250.0, 250.0)), closed = false)

        val takeoff = PlanTakeoff.summarize(
            base.copy(
                zones = listOf(zone),
                paths = listOf(openPath, rectArea, crossingPath),
                marks = listOf(
                    symbol(0, p(1.0, 1.0), circuit = "e-36", area = "Kitchen"),
                    symbol(0, p(2.0, 2.0), circuit = "E-36", area = "Kitchen"),
                    PlanMark(page = 2, kind = PlanMarkKind.CLOUD_CALLOUT, points = listOf(p(5.0, 5.0), p(25.0, 25.0), p(35.0, 5.0)), text = "Note"),
                ),
                routes = listOf(
                    PlanRunRoute(runId = "r1", page = 0, points = listOf(p(0.0, 0.0), p(100.0, 0.0)), sentInches = 100.0, circuitTag = "E-36"),
                ),
            ),
        )
        assertEquals(1, takeoff.pathPolylineCount)
        assertEquals(Rational.of(70), takeoff.pathPolylineTotal.value)
        assertEquals(1, takeoff.pathPolygonCount)
        assertEquals(Rational.of(60), takeoff.pathPolygonPerimeter.value)
        assertEquals(Dimension.AREA, takeoff.pathPolygonArea.dimension)
        assertEquals(Rational.of(200), takeoff.pathPolygonArea.value)
        assertEquals(1, takeoff.multiScalePathCount)
        assertEquals(0, takeoff.uncalibratedPathCount)
        assertEquals(1, takeoff.textNotes)
        assertEquals(listOf(SymbolCount("Receptacle - Duplex", 2, area = "Kitchen", deviceId = DUPLEX)), takeoff.symbolsByArea)
        val circuitGroup = takeoff.byCircuitCombined.single()
        assertEquals("E-36", circuitGroup.circuit)
        assertEquals(2, circuitGroup.symbols.single().count)
        assertEquals("r1", circuitGroup.routes.single().route.runId)
        assertEquals(listOf(0, 1, 2), takeoff.sources.map { it.page })
    }
}
