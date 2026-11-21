package com.thedirepack.constructioncalc.core.model

import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class PlanDrawingTest {
    private val origin = PlanPoint(0.0, 0.0)

    private fun zone() = PlanZone(name = "Main", page = 0, first = origin, second = PlanPoint(100.0, 100.0))
    private fun reference(x: Double, y: Double, inches: Rational, check: Boolean = false) =
        PlanReference(origin, PlanPoint(x, y), Quantity.inches(inches).stored(), check)

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
}
