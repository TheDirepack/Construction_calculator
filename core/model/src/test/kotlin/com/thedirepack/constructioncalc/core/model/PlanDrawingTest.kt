package com.thedirepack.constructioncalc.core.model

import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

class PlanDrawingTest {
    private val origin = PlanPoint(0.0, 0.0)

    private fun zone() = PlanZone(name = "Main", page = 0, first = origin, second = PlanPoint(100.0, 100.0))
    private fun reference(x: Double, y: Double, inches: Rational, check: Boolean = false, page: Int = 0) =
        PlanReference(origin, PlanPoint(x, y), Quantity.inches(inches).stored(), check, page)

    @Test fun retainsExactPresetAndReferenceThroughProjectJson() {
        val third = Rational.of(1, 3)
        val zone = zone().copy(
            preset = Quantity.inches(third).stored(),
            references = listOf(reference(3.0, 0.0, Rational.ONE, check = true)),
        )
        val measurement = PlanMeasurement(page = 0, zoneId = zone.id, first = origin, second = PlanPoint(6.0, 0.0))
        val drawing = PlanDrawing(name = "Plan.pdf", sha256 = "1".repeat(64), zones = listOf(zone), measurements = listOf(measurement))
        val project = Project.create("Job", now = 1L).copy(drawings = listOf(drawing))
        val restored = ProjectCodec.decode(ProjectCodec.encode(project)).drawings.single()
        assertEquals(third, restored.zones.single().preset!!.toQuantity().value)
        assertEquals(2.0, restored.length(measurement)!!.value.toDouble(), 1e-12)
        assertEquals(0.0, restored.zones.single().fit()!!.residuals.single(), 1e-12)
    }

    @Test fun recoversUnequalAxisStretchAndSeparatesCheckReferences() {
        val zone = zone().copy(mode = PlanScaleMode.AXES, references = listOf(
            reference(3.0, 0.0, Rational.of(6)),
            reference(0.0, 4.0, Rational.of(8), check = true),
            reference(0.0, 2.0, Rational.of(6)),
            reference(6.0, 0.0, Rational.of(12)),
        ))
        val fit = zone.fit()!!
        assertEquals(2 * sqrt(13.0), fit.distance(origin, PlanPoint(2.0, 2.0)).value.toDouble(), 1e-12)
        assertEquals(4.0, fit.residuals[1], 1e-12)
    }

    @Test fun affineFitRejectsDependentSamplesAndRecoversAShearedDistance() {
        val x = reference(1.0, 0.0, Rational.of(2))
        val y = reference(0.0, 1.0, Rational.of(sqrt(10.0)))
        val diagonal = reference(1.0, 1.0, Rational.of(sqrt(18.0)))
        val affine = zone().copy(mode = PlanScaleMode.AFFINE, references = listOf(x, y, diagonal))
        assertEquals(10.0, affine.fit()!!.distance(origin, PlanPoint(3.0, 2.0)).value.toDouble(), 1e-12)
        assertNull(affine.copy(references = listOf(x, y, reference(2.0, 0.0, Rational.of(4)))).fit())
        assertNull(affine.copy(references = listOf(x, y, reference(1.0, 1.0, Rational.of(10)))).fit())
    }

    @Test fun adjacentZonesCanTouchWithoutOverlapping() {
        val adjacent = zone().copy(first = PlanPoint(100.0, 0.0), second = PlanPoint(200.0, 100.0))
        assertFalse(zone().overlaps(adjacent))
        assertTrue(zone().overlaps(adjacent.copy(first = PlanPoint(99.0, 0.0))))
        assertFalse(zone().overlaps(zone().copy(page = 1)))
    }

    @Test fun documentCalibrationAppliesAcrossPagesAndZonesOverrideWithoutPollutingIt() {
        // Calibrate document once on page 0 (1 pt = 2 in) and measure on page 2.
        val calibration = PlanCalibrationLayer(
            references = listOf(PlanReference(PlanPoint(10.0, 10.0), PlanPoint(60.0, 10.0), Quantity.inches(Rational.of(100)).stored(), page = 0)),
        )
        // Override detail zone on page 2 with a 10x different scale (1 pt = 20 in).
        val detailZone = PlanZone(
            name = "Enlarged detail",
            page = 2,
            first = PlanPoint(200.0, 200.0),
            second = PlanPoint(400.0, 400.0),
            references = listOf(PlanReference(PlanPoint(210.0, 210.0), PlanPoint(260.0, 210.0), Quantity.inches(Rational.of(1000)).stored(), page = 2)),
        )
        val drawing = PlanDrawing(
            name = "Set.pdf",
            sha256 = "2".repeat(64),
            calibration = calibration,
            zones = listOf(detailZone),
        )
        // Outside detail zone on page 2 -> uses document-wide scale (2 in/pt).
        val docMeasure = PlanMeasurement(page = 2, first = PlanPoint(10.0, 10.0), second = PlanPoint(30.0, 10.0))
        assertEquals(40.0, drawing.length(docMeasure)!!.value.toDouble(), 1e-9)

        // Inside detail zone on page 2 -> uses zone scale (20 in/pt).
        val zoneMeasure = PlanMeasurement(page = 2, first = PlanPoint(210.0, 250.0), second = PlanPoint(230.0, 250.0))
        assertEquals(400.0, drawing.length(zoneMeasure)!!.value.toDouble(), 1e-9)

        // Straddling zone boundary -> rejected until split.
        val crossing = PlanMeasurement(page = 2, first = PlanPoint(150.0, 250.0), second = PlanPoint(250.0, 250.0))
        assertTrue(drawing.crossesZoneBoundary(2, crossing.first, crossing.second))
        assertNull(drawing.length(crossing))

        // Full serialization round-trip preserves document calibration layer, pageModes, and zone-free measurements.
        val withWarp = drawing.copy(
            calibration = calibration.copy(pageModes = mapOf(2 to PlanScaleMode.DISTORTED)),
            measurements = listOf(docMeasure, zoneMeasure),
        )
        val restored = ProjectCodec.decode(ProjectCodec.encode(Project.create("Set", 1L).copy(drawings = listOf(withWarp)))).drawings.single()
        assertEquals(PlanScaleMode.DISTORTED, restored.calibration.modeFor(2))
        assertEquals(40.0, restored.length(docMeasure)!!.value.toDouble(), 1e-9)
        assertEquals(400.0, restored.length(zoneMeasure)!!.value.toDouble(), 1e-9)
    }

    @Test fun unlimitedReferencesAreAccepted() {
        val refs = (1..150).map { i ->
            PlanReference(PlanPoint(0.0, i.toDouble()), PlanPoint(10.0, i.toDouble()), Quantity.inches(Rational.of(20)).stored(), page = 0)
        }
        val layer = PlanCalibrationLayer(references = refs)
        val fit = layer.fit(0)
        assertNotNull(fit)
        assertEquals(150, fit!!.residuals.size)
        assertEquals(40.0, fit.distance(PlanPoint(0.0, 0.0), PlanPoint(20.0, 0.0)).value.toDouble(), 1e-9)
    }

    @Test fun distortedFieldRecoversPerspectiveAndPaperCurlAcrossSheet() {
        // Simulate a real 600" x 400" floor plan photographed in 3D perspective + paper curl:
        // maps real world (X, Y) in inches -> observed page point (x, y).
        fun project(wx: Double, wy: Double): PlanPoint {
            val u = (wx - 300.0) / 300.0
            val v = (wy - 200.0) / 200.0
            // 3D perspective depth varies across the sheet (~18% scale change top-to-bottom & left-to-right).
            val z = 1.0 + 0.12 * v + 0.07 * u
            val px = 400.0 + (wx - 300.0) * 0.5 / z
            val py = 300.0 + (wy - 200.0) * 0.5 / z + 6.0 * sin(u * 1.4)
            return PlanPoint(px, py)
        }

        // Known dimensions spread over the sheet (horizontal, vertical, and diagonals).
        val spans = listOf(
            (0.0 to 0.0) to (250.0 to 0.0),
            (350.0 to 0.0) to (600.0 to 0.0),
            (0.0 to 200.0) to (300.0 to 200.0),
            (300.0 to 200.0) to (600.0 to 200.0),
            (0.0 to 400.0) to (250.0 to 400.0),
            (350.0 to 400.0) to (600.0 to 400.0),
            (0.0 to 0.0) to (0.0 to 200.0),
            (0.0 to 200.0) to (0.0 to 400.0),
            (300.0 to 0.0) to (300.0 to 400.0),
            (600.0 to 0.0) to (600.0 to 200.0),
            (600.0 to 200.0) to (600.0 to 400.0),
            (50.0 to 50.0) to (250.0 to 250.0),
            (350.0 to 150.0) to (550.0 to 350.0),
        )
        val refs = spans.map { (a, b) ->
            val trueLen = hypot(b.first - a.first, b.second - a.second)
            PlanReference(project(a.first, a.second), project(b.first, b.second), Quantity.inches(Rational.of(trueLen)).stored(), page = 1)
        }
        val uniform = PlanCalibrationLayer(references = refs, mode = PlanScaleMode.UNIFORM).fit(1)!!
        val distorted = PlanCalibrationLayer(references = refs, pageModes = mapOf(1 to PlanScaleMode.DISTORTED)).fit(1)!!

        // Held-out long diagonal spanning from near side to far side (scale varies along its length).
        val testA = 80.0 to 40.0
        val testB = 520.0 to 360.0
        val trueDiag = hypot(testB.first - testA.first, testB.second - testA.second)
        val distortedDiag = distorted.distance(project(testA.first, testA.second), project(testB.first, testB.second)).value.toDouble()
        assertEquals(trueDiag, distortedDiag, trueDiag * 0.015)

        // Held-out spans near opposite corners should beat uniform fit dramatically.
        val nearSpan = (40.0 to 20.0) to (240.0 to 20.0) // true 200"
        val farSpan = (360.0 to 380.0) to (560.0 to 380.0) // true 200"
        val uNearErr = kotlin.math.abs(uniform.distance(project(nearSpan.first.first, nearSpan.first.second), project(nearSpan.second.first, nearSpan.second.second)).value.toDouble() - 200.0)
        val dNearErr = kotlin.math.abs(distorted.distance(project(nearSpan.first.first, nearSpan.first.second), project(nearSpan.second.first, nearSpan.second.second)).value.toDouble() - 200.0)
        val uFarErr = kotlin.math.abs(uniform.distance(project(farSpan.first.first, farSpan.first.second), project(farSpan.second.first, farSpan.second.second)).value.toDouble() - 200.0)
        val dFarErr = kotlin.math.abs(distorted.distance(project(farSpan.first.first, farSpan.first.second), project(farSpan.second.first, farSpan.second.second)).value.toDouble() - 200.0)
        assertTrue("distorted near error $dNearErr should beat uniform $uNearErr", dNearErr < uNearErr * 0.25)
        assertTrue("distorted far error $dFarErr should beat uniform $uFarErr", dFarErr < uFarErr * 0.25)
    }
}
