package com.thedirepack.constructioncalc.feature.drawings

import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.PlanCalibrationLayer
import com.thedirepack.constructioncalc.core.model.PlanDrawing
import com.thedirepack.constructioncalc.core.model.PlanMarkKind
import com.thedirepack.constructioncalc.core.model.PlanMeasurement
import com.thedirepack.constructioncalc.core.model.PlanPoint
import com.thedirepack.constructioncalc.core.model.PlanReference
import com.thedirepack.constructioncalc.core.model.PlanZone
import com.thedirepack.constructioncalc.core.model.stored
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorActionsTest {
    private val sha = "a".repeat(64)
    private fun p(x: Double, y: Double) = PlanPoint(x, y)
    private fun inches(n: Long) = Quantity.inches(Rational.of(n)).stored()

    /** 1 pt = 1 in on the document; a detail zone at 1 pt = 2 in. */
    private val zone = PlanZone(id = "z", name = "Detail", page = 0, first = p(200.0, 200.0), second = p(300.0, 300.0), preset = inches(2))
    private val drawing = PlanDrawing(
        name = "E1.pdf",
        sha256 = sha,
        calibration = PlanCalibrationLayer(preset = inches(1)),
        zones = listOf(zone),
    )

    @Test fun pageAndToolChangesResetTheOperationAndDialogInOnePlace() {
        val busy = EditorSession(page = 0, mode = DrawingMode.Route, op = EditOp.Tracing(listOf(p(1.0, 1.0))), dialog = EditorDialog.Takeoff, selectedZoneId = "z")
        val nextPage = busy.goToPage(1)
        assertEquals(EditorSession(page = 1, mode = DrawingMode.Route), nextPage)
        assertEquals(busy, busy.goToPage(0))

        val nextTool = busy.withMode(DrawingMode.Measure)
        assertEquals(EditOp.Idle, nextTool.op)
        assertNull(nextTool.dialog)
        assertEquals("z", nextTool.selectedZoneId)
        assertEquals(busy, busy.withMode(DrawingMode.Route))
    }

    @Test fun twoTapsMeasureAndPublishTheExactLength() {
        val start = EditorSession(mode = DrawingMode.Measure)
        val first = EditorActions.tap(start, drawing, p(0.0, 0.0), 8.0)
        assertEquals(EditOp.FirstPoint(p(0.0, 0.0)), first.session.op)
        assertNull(first.change)

        val second = EditorActions.tap(first.session, drawing, p(30.0, 40.0), 8.0)
        assertEquals(EditOp.Idle, second.session.op)
        val updated = second.change!!(drawing)
        assertEquals(1, updated.measurements.size)
        val (published, length) = second.published!!
        assertEquals(updated.measurements.single(), published)
        assertEquals(Rational.of(50), length.value)
    }

    @Test fun measuringRespectsZonesAndMissingCalibration() {
        val session = EditorSession(mode = DrawingMode.Measure)
        val inside = EditorActions.span(session, drawing, p(210.0, 210.0), p(260.0, 210.0))
        assertEquals("z", inside.change!!(drawing).measurements.single().zoneId)
        assertEquals(Rational.of(100), inside.published!!.second.value)
        assertEquals("z", inside.session.selectedZoneId)

        val crossing = EditorActions.span(session, drawing, p(100.0, 250.0), p(250.0, 250.0))
        assertNull(crossing.change)
        assertEquals("Split measurements at override zone boundaries.", crossing.message)

        val uncalibrated = EditorActions.span(session, drawing.copy(calibration = PlanCalibrationLayer()), p(0.0, 0.0), p(10.0, 0.0))
        assertNull(uncalibrated.change)
        assertTrue(uncalibrated.message!!.startsWith("Calibrate the sheet"))
    }

    @Test fun calibrationAndZoneSpansOpenTheirDialogs() {
        val ref = EditorActions.span(EditorSession(mode = DrawingMode.Reference), drawing, p(210.0, 210.0), p(250.0, 210.0))
        assertEquals(EditorDialog.Reference("z", p(210.0, 210.0), p(250.0, 210.0)), ref.session.dialog)

        val docRef = EditorActions.span(EditorSession(mode = DrawingMode.Reference), drawing, p(0.0, 0.0), p(50.0, 0.0))
        assertEquals(EditorDialog.Reference(null, p(0.0, 0.0), p(50.0, 0.0)), docRef.session.dialog)

        val overlap = EditorActions.span(EditorSession(mode = DrawingMode.Zone), drawing, p(250.0, 250.0), p(400.0, 400.0))
        assertNull(overlap.session.dialog)
        assertEquals("Override scale zones cannot overlap.", overlap.message)

        val flat = EditorActions.span(EditorSession(mode = DrawingMode.Zone), drawing, p(0.0, 0.0), p(50.0, 0.0))
        assertEquals("An override zone needs a width and height.", flat.message)

        val ok = EditorActions.span(EditorSession(mode = DrawingMode.Zone), drawing, p(0.0, 0.0), p(50.0, 60.0))
        assertEquals(EditorDialog.NewZone(p(0.0, 0.0), p(50.0, 60.0)), ok.session.dialog)
    }

    @Test fun markupSpansAddMarksButNotFlatRectangles() {
        val line = EditorActions.span(EditorSession(mode = DrawingMode.Arrow), drawing, p(0.0, 0.0), p(5.0, 0.0))
        assertEquals(PlanMarkKind.ARROW, line.change!!(drawing).marks.single().kind)
        val flat = EditorActions.span(EditorSession(mode = DrawingMode.Rectangle), drawing, p(0.0, 0.0), p(5.0, 0.0))
        assertNull(flat.change)
    }

    @Test fun routeTapsBuildATraceAndStopAtZoneEdges() {
        var session = EditorSession(mode = DrawingMode.Route)
        session = EditorActions.tap(session, drawing, p(0.0, 0.0), 8.0).session
        session = EditorActions.tap(session, drawing, p(100.0, 0.0), 8.0).session
        assertEquals(listOf(p(0.0, 0.0), p(100.0, 0.0)), session.routePoints)
        // The same point twice adds nothing.
        assertEquals(session, EditorActions.tap(session, drawing, p(100.0, 0.0), 8.0).session)

        val intoZone = EditorActions.tap(session, drawing, p(250.0, 250.0), 8.0)
        assertEquals(session, intoZone.session)
        assertEquals("Split routes at override zone boundaries.", intoZone.message)
    }

    @Test fun textAndSymbolTapsOpenTheirDialogs() {
        assertEquals(EditorDialog.PlaceText(p(3.0, 4.0)), EditorActions.tap(EditorSession(mode = DrawingMode.Text), drawing, p(3.0, 4.0), 8.0).session.dialog)
        assertEquals(EditorDialog.PickSymbol, EditorActions.tap(EditorSession(mode = DrawingMode.Symbol), drawing, p(3.0, 4.0), 8.0).session.dialog)
    }

    @Test fun selectFindsTheNearestMeasurementWithinTouchRadius() {
        val m = PlanMeasurement(id = "m", page = 0, first = p(0.0, 0.0), second = p(100.0, 0.0))
        assertEquals(m, EditorActions.measurementAt(listOf(m), p(50.0, 5.0), 8.0))
        assertNull(EditorActions.measurementAt(listOf(m), p(50.0, 20.0), 8.0))
    }

    @Test fun movingAMeasurementEndRezonesItAndRejectsCrossingAZone() {
        val m = PlanMeasurement(id = "m", page = 0, first = p(210.0, 210.0), second = p(250.0, 210.0), zoneId = "z")
        val d = drawing.copy(measurements = listOf(m))
        val session = EditorSession(mode = DrawingMode.Adjust)
        val handles = EditorActions.handles(d, 0, includeReferences = false)
        assertEquals(2, handles.size)
        val second = EditorActions.handleAt(handles, p(249.0, 211.0), 4.0)!!
        assertEquals(1, second.end)

        val crossing = EditorActions.moveEndpoint(session, d, second, p(400.0, 210.0))
        assertNull(crossing.change)
        assertNotNull(crossing.message)

        // Both ends moved out of the zone: it's measured on the document scale again.
        val first = handles.first { it.end == 0 }
        val movedFirst = EditorActions.moveEndpoint(session, d, first, p(10.0, 10.0))
        assertNull(movedFirst.change) // one end still inside the zone → crosses
        val outside = d.copy(measurements = listOf(m.copy(first = p(10.0, 10.0), second = p(20.0, 10.0), zoneId = null)))
        val h = EditorActions.handles(outside, 0, includeReferences = false).first { it.end == 1 }
        val moved = EditorActions.moveEndpoint(session, outside, h, p(40.0, 10.0)).change!!(outside)
        assertEquals(p(40.0, 10.0), moved.measurements.single().second)
        assertEquals(Rational.of(30), moved.length(moved.measurements.single())!!.value)
    }

    @Test fun movingAZoneDimensionMustStayInsideTheZone() {
        val ref = PlanReference(p(210.0, 210.0), p(250.0, 210.0), inches(80))
        val d = drawing.copy(zones = listOf(zone.copy(references = listOf(ref))))
        val handles = EditorActions.handles(d, 0, includeReferences = true)
        assertEquals(2, handles.size)
        val end = handles.first { it.end == 1 }
        val session = EditorSession(mode = DrawingMode.Adjust)
        assertEquals("Keep the dimension inside “Detail”.", EditorActions.moveEndpoint(session, d, end, p(350.0, 210.0)).message)
        val moved = EditorActions.moveEndpoint(session, d, end, p(290.0, 210.0)).change!!(d)
        assertEquals(p(290.0, 210.0), moved.zones.single().references.single().second)
        // References are hidden: no handles for them.
        assertTrue(EditorActions.handles(d, 0, includeReferences = false).isEmpty())
    }

    @Test fun movingADocumentDimensionCantEnterAZone() {
        val ref = PlanReference(p(0.0, 0.0), p(100.0, 0.0), inches(100))
        val d = drawing.copy(calibration = drawing.calibration.copy(references = listOf(ref)))
        val end = EditorActions.handles(d, 0, includeReferences = true).first { it.end == 1 }
        val session = EditorSession(mode = DrawingMode.Adjust)
        assertNotNull(EditorActions.moveEndpoint(session, d, end, p(250.0, 250.0)).message)
        val moved = EditorActions.moveEndpoint(session, d, end, p(120.0, 0.0)).change!!(d)
        assertEquals(p(120.0, 0.0), moved.calibration.references.single().second)
    }

    @Test fun segmentDistanceClampsToTheEnds() {
        assertEquals(5.0, EditorActions.segmentDistance(p(50.0, 5.0), p(0.0, 0.0), p(100.0, 0.0)), 1e-9)
        assertEquals(5.0, EditorActions.segmentDistance(p(-3.0, 4.0), p(0.0, 0.0), p(100.0, 0.0)), 1e-9)
    }
}
