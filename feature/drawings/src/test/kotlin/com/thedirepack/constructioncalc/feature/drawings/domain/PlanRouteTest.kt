package com.thedirepack.constructioncalc.feature.drawings.domain

import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.BendPlane
import com.thedirepack.constructioncalc.core.model.PathSegment
import com.thedirepack.constructioncalc.core.model.PlanCalibrationLayer
import com.thedirepack.constructioncalc.core.model.PlanDrawing
import com.thedirepack.constructioncalc.core.model.PlanPoint
import com.thedirepack.constructioncalc.core.model.PlanZone
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.RunPath
import com.thedirepack.constructioncalc.core.model.stored
import com.thedirepack.constructioncalc.core.model.withRunPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PlanRouteTest {
    private val sha = "a".repeat(64)

    /** 1 pt = 1 inch preset for simple exact geometry assertions. */
    private fun calibratedDrawing(zones: List<PlanZone> = emptyList()): PlanDrawing = PlanDrawing(
        name = "Floor 1.pdf",
        sha256 = sha,
        calibration = PlanCalibrationLayer(preset = Quantity.inches(Rational.of(1)).stored()),
        zones = zones,
    )

    @Test fun measuresLShapedRouteWith90DegreeCorner() {
        val drawing = calibratedDrawing()
        val points = listOf(
            PlanPoint(0.0, 0.0),
            PlanPoint(120.0, 0.0),
            PlanPoint(120.0, 60.0),
        )
        val summary = PlanRoute.measure(drawing, page = 0, points = points)
        assertNotNull(summary)
        assertEquals(2, summary!!.legs.size)
        assertEquals(180.0, summary.totalHorizontalInches, 1e-6)
        assertEquals(1, summary.corners.size)
        assertEquals(90.0, summary.corners.single().bendDegrees, 1e-6)
        assertEquals(90.0, summary.totalBendDegrees, 1e-6)
    }

    @Test fun ignoresCollinearPointsAsBendsAndMeasures45DegreeTurn() {
        val drawing = calibratedDrawing()
        val points = listOf(
            PlanPoint(0.0, 0.0),
            PlanPoint(50.0, 0.0),
            PlanPoint(100.0, 0.0), // straight ahead -> 0 deg, not a bend
            PlanPoint(150.0, 50.0), // 45 deg turn
        )
        val summary = PlanRoute.measure(drawing, page = 0, points = points)!!
        assertEquals(3, summary.legs.size)
        assertEquals(1, summary.corners.size)
        assertEquals(45.0, summary.corners.single().bendDegrees, 1e-6)
    }

    @Test fun returnsNullWhenUncalibratedOrCrossingZoneBoundary() {
        val uncalibrated = PlanDrawing(name = "Uncal.pdf", sha256 = sha)
        assertNull(PlanRoute.measure(uncalibrated, 0, listOf(PlanPoint(0.0, 0.0), PlanPoint(10.0, 0.0))))

        val zone = PlanZone(
            name = "Detail",
            page = 0,
            first = PlanPoint(0.0, 0.0),
            second = PlanPoint(50.0, 50.0),
            preset = Quantity.inches(Rational.of(2)).stored(),
        )
        val withZone = calibratedDrawing(zones = listOf(zone))
        assertNull(PlanRoute.measure(withZone, 0, listOf(PlanPoint(10.0, 10.0), PlanPoint(80.0, 10.0))))
    }

    @Test fun convertsRouteWithStartAndEndVerticalsToRunPath() {
        val drawing = calibratedDrawing()
        val summary = PlanRoute.measure(
            drawing,
            page = 0,
            points = listOf(PlanPoint(0.0, 0.0), PlanPoint(100.0, 0.0), PlanPoint(100.0, 50.0)),
        )!!

        val segments = PlanRoute.toRunPath(
            summary = summary,
            startVerticalInches = 96.0,
            startVerticalUp = true,
            endVerticalInches = 48.0,
            endVerticalUp = false,
        )

        // Expected: StartRise(96", +90°) -> Bend(90°, VERTICAL_DOWN) -> Straight(100") -> Bend(90°, HORIZONTAL)
        //           -> Straight(50") -> Bend(90°, VERTICAL_DOWN) -> EndDrop(48", -90°)
        assertEquals(7, segments.size)
        assertEquals(PathSegment.Straight(Quantity.inches(Rational.of(96)).stored(), 90.0), segments[0])
        assertEquals(BendPlane.VERTICAL_DOWN, (segments[1] as PathSegment.Bend).plane)
        assertEquals(90.0, (segments[1] as PathSegment.Bend).angleDegrees, 1e-6)
        assertEquals(100.0, (segments[2] as PathSegment.Straight).lengthInches, 1e-6)
        assertEquals(BendPlane.HORIZONTAL, (segments[3] as PathSegment.Bend).plane)
        assertEquals(90.0, (segments[3] as PathSegment.Bend).angleDegrees, 1e-6)
        assertEquals(50.0, (segments[4] as PathSegment.Straight).lengthInches, 1e-6)
        assertEquals(BendPlane.VERTICAL_DOWN, (segments[5] as PathSegment.Bend).plane)
        assertEquals(-90.0, (segments[6] as PathSegment.Straight).slopeDegrees, 1e-6)
        assertEquals(48.0, (segments[6] as PathSegment.Straight).lengthInches, 1e-6)
        assertEquals(270.0, RunPath.totalDegrees(segments), 1e-6)
        assertEquals(294.0, RunPath.straightInches(segments), 1e-6)

        // Verify clean integration with Project.withRunPath
        val project = Project.create("Job", now = 1000L)
        val runId = project.runs.single().id
        val updated = project.withRunPath(runId, segments, now = 2000L)
        val updatedRun = updated.run(runId)!!
        assertEquals(7, updatedRun.path.size)
        assertNotNull(updatedRun.raceway?.length)
    }
}
