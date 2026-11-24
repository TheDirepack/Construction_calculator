package com.thedirepack.constructioncalc.core.measure

import com.thedirepack.constructioncalc.core.math.Rational
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.sqrt

class StairsTest {

    private fun ft(n: Long) = Quantity.feet(Rational.of(n))
    private fun inch(n: Long, d: Long = 1) = Quantity.inches(Rational.of(n, d))

    // --- Stairs (FLUSH / DROPPED stringer layout & comfort checks) ---

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

    // --- StairCalculator (IRC-checked dropped-top layout) ---

    @Test
    fun calculatesExactCleanStairLayout() {
        // 8' 9" (105") at default 7.5" target riser -> 14 risers, 7.5" each, 13 treads @ 10" = 130"
        val layout = StairCalculator.calculate(totalRise = inch(105))
        assertNotNull(layout)
        layout!!
        assertEquals(14, layout.riserCount)
        assertEquals(inch(15, 2), layout.riserHeight)
        assertEquals(Rational.of(15, 2), layout.riserHeightInches)
        assertEquals(13, layout.treadCount)
        assertEquals(inch(130), layout.totalRun)
        // Step triangle is 7.5" x 10" -> 3:4:5 scaled by 2.5 -> step diagonal 12.5"
        assertEquals(inch(25, 2), layout.stepDiagonal)
        assertEquals(36.8699, layout.angleDegrees, 1e-3)
        // Stringer diagonal = sqrt(105^2 + 130^2)
        assertEquals(sqrt(105.0 * 105.0 + 130.0 * 130.0), layout.stringerLength.value.toDouble(), 1e-6)
        assertTrue(layout.isIrcCompliant)
        assertTrue(layout.riserWithinIrcMax)
        assertTrue(layout.treadWithinIrcMin)
        assertTrue(layout.ircNotes.any { it.contains("7-3/4\"") && it.contains("10\"") })
    }

    @Test
    fun keepsExactRationalRiserHeightWithoutFloatingPointDrift() {
        // 9' 0" (108") / 7.5" = 14.4 -> rounds to 14 risers, exact riser = 108/14 = 54/7" (~7.714")
        val layout = StairCalculator.calculate(totalRise = ft(9))
        assertNotNull(layout)
        layout!!
        assertEquals(14, layout.riserCount)
        assertEquals(Rational.of(54, 7), layout.riserHeight.value)
        assertEquals(Rational.of(108), layout.riserHeight.value * Rational.of(layout.riserCount.toLong()))
        assertEquals(13, layout.treadCount)
        assertTrue(layout.isIrcCompliant) // 54/7 <= 31/4 (216/28 <= 217/28)
    }

    @Test
    fun flagsIrcViolationsForSteepRiserOrShallowTread() {
        // 8' 0" (96") with 8" target riser -> 12 risers @ 8" (> 7-3/4" max)
        val steep = StairCalculator.calculate(
            totalRise = inch(96),
            targetRiserHeight = inch(8),
            treadRun = inch(19, 2), // 9.5" (< 10" min)
        )
        assertNotNull(steep)
        steep!!
        assertEquals(12, steep.riserCount)
        assertEquals(inch(8), steep.riserHeight)
        assertFalse(steep.riserWithinIrcMax)
        assertFalse(steep.treadWithinIrcMin)
        assertFalse(steep.isIrcCompliant)
        assertEquals(2, steep.ircNotes.size)
        assertTrue(steep.ircNotes[0].contains("7-3/4\""))
        assertTrue(steep.ircNotes[1].contains("10\""))
    }

    @Test
    fun handlesSingleRiserAndOverride() {
        val single = StairCalculator.calculate(totalRise = inch(7))
        assertNotNull(single)
        single!!
        assertEquals(1, single.riserCount)
        assertEquals(0, single.treadCount)
        assertEquals(inch(0), single.totalRun)
        assertEquals(inch(7), single.stringerLength)
        assertEquals(inch(7), single.riserHeight)

        // Override 108" to 15 risers -> exact 7.2" (36/5")
        val overridden = StairCalculator.calculate(totalRise = inch(108), riserCountOverride = 15)
        assertNotNull(overridden)
        assertEquals(15, overridden!!.riserCount)
        assertEquals(14, overridden.treadCount)
        assertEquals(Rational.of(36, 5), overridden.riserHeight.value)
    }

    @Test
    fun rejectsInvalidInputs() {
        assertNull(StairCalculator.calculate(totalRise = inch(0)))
        assertNull(StairCalculator.calculate(totalRise = inch(-96)))
        assertNull(StairCalculator.calculate(totalRise = Quantity.number(Rational.of(96))))
        assertNull(StairCalculator.calculate(totalRise = inch(96), targetRiserHeight = inch(0)))
        assertNull(StairCalculator.calculate(totalRise = inch(96), treadRun = inch(0)))
        assertNull(StairCalculator.calculate(totalRise = inch(96), riserCountOverride = 0))
        assertNull(StairCalculator.calculate(totalRise = inch(96), riserCountOverride = StairCalculator.MAX_RISERS + 1))
    }
}
