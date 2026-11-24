package com.thedirepack.constructioncalc.core.measure

import com.thedirepack.constructioncalc.core.math.Rational
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2

class StairsTest {

    private fun ft(n: Long) = Quantity.feet(Rational.of(n))
    private fun inch(n: Long, d: Long = 1) = Quantity.inches(Rational.of(n, d))

    @Test
    fun calculatesNineFootRiseFlushAndDropped() {
        val flush = Stairs.calculate(totalRise = ft(9))
        assertNotNull(flush)
        flush!!

        assertEquals(inch(108), flush.totalRise)
        assertEquals(14, flush.riserCount)
        assertEquals(inch(54, 7), flush.riserHeight) // 108 / 14 = 54/7"
        assertEquals(14, flush.treadCount)
        assertEquals(inch(10), flush.treadDepth)
        assertEquals(inch(140), flush.totalRun)

        val expectedAngle = Math.toDegrees(atan2(54.0 / 7.0, 10.0))
        assertEquals(expectedAngle, flush.angleDegrees, 1e-6)
        assertEquals((54.0 / 7.0 / 10.0) * 12.0, flush.risePerFoot, 1e-6)
        assertEquals(2.0 * (54.0 / 7.0) + 10.0, flush.twoRPlusTInches, 1e-6)
        assertEquals(14, flush.layoutMarks.size)
        assertEquals(flush.stepHypotenuse, flush.layoutMarks.first())
        assertEquals(
            flush.stepHypotenuse.value * Rational.of(14),
            flush.layoutMarks.last().value,
        )

        val dropped = Stairs.calculate(totalRise = ft(9), mount = StairTopMount.DROPPED)
        assertNotNull(dropped)
        dropped!!

        assertEquals(14, dropped.riserCount)
        assertEquals(inch(54, 7), dropped.riserHeight)
        assertEquals(13, dropped.treadCount)
        assertEquals(inch(130), dropped.totalRun)
        assertEquals(flush.stepHypotenuse, dropped.stepHypotenuse)
        assertEquals(flush.angleDegrees, dropped.angleDegrees, 1e-6)
        assertEquals(13, dropped.layoutMarks.size)

        val expectedDroppedStringer = Evaluator.sqrtRational(
            Rational.of(108L * 108L + 130L * 130L),
        )
        assertEquals(Quantity.inches(expectedDroppedStringer), dropped.stringerLength)
        assertTrue(dropped.stringerLength.value < flush.stringerLength.value)
    }

    @Test
    fun exactPythagoreanStringerLengthAndLayoutMarks() {
        // 90" rise with 7.5" target riser -> 12 exact risers of 7.5"; 10" tread -> exact 12.5" hypotenuse
        val res = Stairs.calculate(
            totalRise = inch(90),
            targetRiser = inch(15, 2),
            treadDepth = inch(10),
            mount = StairTopMount.FLUSH,
        )
        assertNotNull(res)
        res!!

        assertEquals(12, res.riserCount)
        assertEquals(inch(15, 2), res.riserHeight)
        assertEquals(12, res.treadCount)
        assertEquals(inch(120), res.totalRun)
        assertEquals(inch(25, 2), res.stepHypotenuse)
        assertEquals(inch(150), res.stringerLength) // sqrt(90^2 + 120^2) = 150"
        assertEquals(12, res.layoutMarks.size)
        assertEquals(inch(25, 2), res.layoutMarks[0])
        assertEquals(inch(25), res.layoutMarks[1])
        assertEquals(inch(150), res.layoutMarks.last())
        assertEquals(25.0, res.twoRPlusTInches, 1e-9)
        assertTrue(res.notices.isEmpty())
    }

    @Test
    fun riserOverrideAndSingleRiserDropped() {
        val override = Stairs.calculate(totalRise = ft(9), riserCountOverride = 15)
        assertNotNull(override)
        override!!
        assertEquals(15, override.riserCount)
        assertEquals(inch(36, 5), override.riserHeight) // 7.2"
        assertEquals(24.4, override.twoRPlusTInches, 1e-6)
        assertTrue(override.notices.isEmpty())

        val singleDropped = Stairs.calculate(
            totalRise = inch(7),
            riserCountOverride = 1,
            mount = StairTopMount.DROPPED,
        )
        assertNotNull(singleDropped)
        singleDropped!!
        assertEquals(1, singleDropped.riserCount)
        assertEquals(0, singleDropped.treadCount)
        assertEquals(inch(0), singleDropped.totalRun)
        assertEquals(inch(7), singleDropped.stringerLength)
        assertTrue(singleDropped.layoutMarks.isEmpty())
    }

    @Test
    fun emitsIrcComfortNoticesWhenOutsideGuidance() {
        // 108" / 13 = 8.31" (> 7.75" max riser and 2R+T = 26.62")
        val steep = Stairs.calculate(totalRise = ft(9), riserCountOverride = 13)
        assertNotNull(steep)
        assertTrue(steep!!.notices.any { "7 3/4" in it })
        assertTrue(steep.notices.any { "2R + T" in it })

        // Narrow tread < 10" (7.5" riser, 9" tread -> 2R + T = 24", only tread notice)
        val narrow = Stairs.calculate(totalRise = inch(90), treadDepth = inch(9))
        assertNotNull(narrow)
        assertEquals(1, narrow!!.notices.size)
        assertTrue(narrow.notices.single().contains("10\""))

        // Too small 2R + T (< 24"): 6" riser + 10" tread = 22"
        val shallow = Stairs.calculate(totalRise = inch(72), targetRiser = inch(6))
        assertNotNull(shallow)
        assertTrue(shallow!!.notices.any { "2R + T" in it })
    }

    @Test
    fun returnsNullOnInvalidInputs() {
        assertNull(Stairs.calculate(totalRise = inch(0)))
        assertNull(Stairs.calculate(totalRise = inch(-12)))
        assertNull(Stairs.calculate(totalRise = ft(9), targetRiser = inch(0)))
        assertNull(Stairs.calculate(totalRise = ft(9), treadDepth = inch(-5)))
        assertNull(Stairs.calculate(totalRise = Quantity.number(Rational.of(108))))
        assertNull(Stairs.calculate(totalRise = ft(9), targetRiser = Quantity(Rational.of(10), Dimension.AREA)))
        assertNull(Stairs.calculate(totalRise = ft(9), riserCountOverride = 0))
        assertNull(Stairs.calculate(totalRise = ft(9), riserCountOverride = -3))
        assertNull(Stairs.calculate(totalRise = ft(9), riserCountOverride = 101))
        assertNull(Stairs.calculate(totalRise = ft(200), targetRiser = inch(6))) // 400 risers > MAX_RISERS
    }
}
