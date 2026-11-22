package com.thedirepack.constructioncalc.core.measure

import com.thedirepack.constructioncalc.core.math.Rational
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GeometryTest {

    private fun ft(n: Long) = Quantity.feet(Rational.of(n))
    private fun inch(n: Long, d: Long = 1) = Quantity.inches(Rational.of(n, d))

    @Test
    fun solves345TriangleExactlyFromRiseAndRun() {
        val tri = Geometry.solveRightTriangle(rise = ft(3), run = ft(4))
        assertNotNull(tri)
        assertEquals(ft(3), tri!!.rise)
        assertEquals(ft(4), tri.run)
        assertEquals(ft(5), tri.diagonal)
        assertEquals(36.8699, tri.angleDegrees, 1e-3)
        assertEquals(53.1301, tri.complementaryDegrees, 1e-3)
        assertEquals(9.0, tri.risePerFoot, 1e-6) // 3/4 * 12 = 9:12
        assertEquals(75.0, tri.slopePercent, 1e-6)
    }

    @Test
    fun solvesFromRiseAndDiagonal() {
        val tri = Geometry.solveRightTriangle(rise = inch(5), diagonal = inch(13))
        assertNotNull(tri)
        assertEquals(inch(12), tri!!.run)
        assertEquals(5.0, tri.risePerFoot, 1e-6)
    }

    @Test
    fun solvesFromRunAndDiagonal() {
        val tri = Geometry.solveRightTriangle(run = ft(8), diagonal = ft(10))
        assertNotNull(tri)
        assertEquals(ft(6), tri!!.rise)
    }

    @Test
    fun solvesFromLegOrDiagonalAndAngle() {
        // 45° right triangle: rise == run, diagonal == run * √2
        val fromRun = Geometry.solveRightTriangle(run = ft(10), angleDegrees = 45.0)
        assertNotNull(fromRun)
        assertEquals(120.0, fromRun!!.rise.value.toDouble(), 1e-6)
        assertEquals(120.0 * kotlin.math.sqrt(2.0), fromRun.diagonal.value.toDouble(), 1e-5)
        assertEquals(12.0, fromRun.risePerFoot, 1e-6)
        assertEquals(100.0, fromRun.slopePercent, 1e-6)

        // 30° right triangle with diagonal = 10' -> rise = 5'
        val fromDiag = Geometry.solveRightTriangle(diagonal = ft(10), angleDegrees = 30.0)
        assertNotNull(fromDiag)
        assertEquals(60.0, fromDiag!!.rise.value.toDouble(), 1e-6)
        assertEquals(120.0 * kotlin.math.cos(Math.toRadians(30.0)), fromDiag.run.value.toDouble(), 1e-6)

        // Rise + 30° -> diagonal = 2 * rise
        val fromRise = Geometry.solveRightTriangle(rise = inch(6), angleDegrees = 30.0)
        assertNotNull(fromRise)
        assertEquals(12.0, fromRise!!.diagonal.value.toDouble(), 1e-6)
    }

    @Test
    fun rejectsInvalidInputs() {
        // Fewer or more than 2 inputs
        assertNull(Geometry.solveRightTriangle(rise = ft(3)))
        assertNull(Geometry.solveRightTriangle(rise = ft(3), run = ft(4), diagonal = ft(5)))
        // Diagonal <= leg
        assertNull(Geometry.solveRightTriangle(rise = ft(5), diagonal = ft(5)))
        assertNull(Geometry.solveRightTriangle(run = ft(6), diagonal = ft(5)))
        // Bad angle
        assertNull(Geometry.solveRightTriangle(run = ft(4), angleDegrees = 0.0))
        assertNull(Geometry.solveRightTriangle(run = ft(4), angleDegrees = 90.0))
        assertNull(Geometry.solveRightTriangle(run = ft(4), angleDegrees = -15.0))
        // Non-positive or non-length
        assertNull(Geometry.solveRightTriangle(rise = inch(0), run = ft(4)))
        assertNull(Geometry.solveRightTriangle(rise = Quantity.number(Rational.of(3)), run = ft(4)))
    }
}
