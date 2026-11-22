package com.thedirepack.constructioncalc.core.model

import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Dimension
import com.thedirepack.constructioncalc.core.measure.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.sqrt

class PlanPathTest {
    private val a = point(0, 0)
    private val b = point(4, 0)
    private val c = point(4, 3)
    private val d = point(0, 3)
    private val drawing = PlanDrawing(
        name = "Plan.pdf",
        sha256 = "a".repeat(64),
        calibration = PlanCalibrationLayer(preset = scale(2)),
    )

    @Test fun legacyDrawingDefaultsToNoPaths() {
        val restored = ProjectCodec.json.decodeFromString<PlanDrawing>(
            """{"name":"Plan.pdf","sha256":"${"a".repeat(64)}"}""",
        )
        assertEquals(emptyList<PlanPath>(), restored.paths)
    }

    @Test fun measuresEditableOpenAndClosedPathsAfterProjectRoundTrip() {
        val open = path(a, b, c)
        val closed = path(a, b, c, d, a, closed = true)
        val project = Project.create("Job", 1L).copy(drawings = listOf(drawing.copy(paths = listOf(open, closed))))
        val restored = ProjectCodec.decode(ProjectCodec.encode(project)).drawings.single()
        assertEquals(listOf(open, closed), restored.paths)
        val (openResult, closedResult) = restored.paths.map { restored.measured(it) }
        assertEquals(14.0, openResult.length.value.toDouble(), 1e-9)
        assertNull(openResult.area)
        assertEquals(28.0, closedResult.length.value.toDouble(), 1e-9)
        assertEquals(Dimension.AREA, closedResult.area!!.dimension)
        assertEquals(48.0, closedResult.area.value.toDouble(), 1e-9)
    }

    @Test fun recalibratesConcavePolygonUsingAffineAreaScale() {
        val refs = listOf(
            PlanReference(a, point(1, 0), scale(2)),
            PlanReference(a, point(0, 1), Quantity.inches(Rational.of(sqrt(10.0))).stored()),
            PlanReference(a, point(1, 1), Quantity.inches(Rational.of(sqrt(18.0))).stored()),
        )
        val points = listOf(a, b, c, point(2, 1), d)
        val calibration = PlanCalibrationLayer(references = refs, mode = PlanScaleMode.AFFINE)
        for (winding in listOf(points, points.reversed())) {
            val result = drawing.copy(calibration = calibration).measured(path(*winding.toTypedArray(), closed = true))
            assertEquals(48.0, result.area!!.value.toDouble(), 1e-9)
        }
    }

    @Test fun distortedModeIntegratesTheAreaOfAConstantField() {
        val calibration = PlanCalibrationLayer(
            references = listOf(PlanReference(a, b, scale(8)), PlanReference(a, d, scale(9))),
            mode = PlanScaleMode.DISTORTED,
        )
        val result = drawing.copy(calibration = calibration).measured(path(a, b, c, d, closed = true))
        assertEquals(72.0, result.area!!.value.toDouble(), 1e-9)
    }

    @Test fun resolvesZoneContainmentAndRejectsIntersectionsAndEnclosedZones() {
        val zone = PlanZone(name = "Detail", page = 0, first = point(1, 1), second = point(3, 2), preset = scale(5))
        val inside = path(point(1, 1), point(3, 1), point(3, 2), point(1, 2), closed = true)
        val adjacent = PlanZone(name = "Adjacent", page = 0, first = point(3, 1), second = point(5, 2), preset = scale(9))
        val zoned = drawing.copy(zones = listOf(zone, adjacent))
        val measured = zoned.measured(inside)
        assertEquals(zone.id, measured.zoneId)
        assertEquals(30.0, measured.length.value.toDouble(), 1e-9)
        assertEquals(50.0, measured.area!!.value.toDouble(), 1e-9)
        assertEquals(PlanPathMeasurement.CrossesZones, zoned.measurePath(path(a, c)))
        assertEquals(PlanPathMeasurement.CrossesZones, zoned.measurePath(path(a, b, c, d, closed = true)))
        assertEquals(PlanPathMeasurement.CrossesZones, zoned.measurePath(path(point(3, 1), point(3, 2))))
        assertEquals(8.0, zoned.measured(path(a, b)).length.value.toDouble(), 1e-9)
        assertEquals(8.0, zoned.measured(path(a, b).copy(page = 1)).length.value.toDouble(), 1e-9)
        assertEquals(PlanPathMeasurement.Uncalibrated, drawing.copy(zones = listOf(zone.copy(preset = null))).measurePath(inside))
        assertEquals(8.0, drawing.measured(inside).area!!.value.toDouble(), 1e-9)
    }

    @Test fun retainsIdentityAndPreventsInvalidVertexEdits() {
        val triangle = path(a, b, c, closed = true)
        assertNull(triangle.removeVertex(0))
        assertNull(triangle.moveVertex(1, c))
        assertNull(PlanPath.fromTrace(0, listOf(a, c, b, d), closed = true))
        assertNull(PlanPath.fromTrace(0, listOf(a, b, point(3, 0), c), closed = true))
        val square = triangle.insertVertex(2, d)!!
        assertEquals(listOf(a, b, c, d), square.points)
        assertEquals(triangle, square.removeVertex(3))
        val moved = square.moveVertex(3, point(0, 4))!!
        assertEquals(square.id, moved.id)
        assertEquals(56.0, drawing.measured(moved).area!!.value.toDouble(), 1e-9)
        assertNull(path(a, b).removeVertex(1))
    }

    private fun PlanDrawing.measured(path: PlanPath) = measurePath(path) as PlanPathMeasurement.Measured

    private fun path(vararg points: PlanPoint, closed: Boolean = false) =
        PlanPath.fromTrace(0, points.toList(), closed)!!

    private fun point(x: Int, y: Int) = PlanPoint(x.toDouble(), y.toDouble())

    private fun scale(inches: Int) = Quantity.inches(Rational.of(inches)).stored()
}
