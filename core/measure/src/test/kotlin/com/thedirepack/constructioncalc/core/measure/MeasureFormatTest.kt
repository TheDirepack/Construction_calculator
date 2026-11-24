package com.thedirepack.constructioncalc.core.measure

import com.thedirepack.constructioncalc.core.math.Rational
import org.junit.Assert.assertEquals
import org.junit.Test

class MeasureFormatTest {
    private fun len(n: Long, d: Long = 1, p: Precision = Precision.SIXTEENTH, s: LengthStyle = LengthStyle.FEET_INCHES) =
        MeasureFormat.length(Rational.of(n, d), p, s)

    @Test fun formatsFeetInches() {
        assertEquals("10' 4 3/16\"", len(124 * 16 + 3, 16))
        assertEquals("7/16\"", len(7, 16))
        assertEquals("0\"", len(0))
        assertEquals("2' 0\"", len(24))
        assertEquals("−1' 0 1/2\"", len(-25, 2))
    }

    @Test fun inchesStyle() {
        assertEquals("124 3/16\"", len(124 * 16 + 3, 16, s = LengthStyle.INCHES))
    }

    @Test fun reducesAndRoundsToPrecision() {
        assertEquals("1/2\"", len(8, 16))
        assertEquals("1/4\"", len(5, 16, Precision.QUARTER)) // 0.3125 → 0.25
        assertEquals("3/8\"", len(6, 16, Precision.EIGHTH))
        assertEquals("1' 0\"", len(1535, 128)) // 11.992" rounds up to a whole foot
    }

    @Test fun conversions() {
        val c = MeasureFormat.conversions(Quantity.inches(Rational.of(12)), Precision.SIXTEENTH, LengthStyle.FEET_INCHES)
            .associate { it.label to it.value }
        assertEquals("12\"", c["Inches"])
        assertEquals("1 ft", c["Decimal ft"])
        assertEquals("304.8 mm", c["Millimeters"])
        assertEquals("0.3048 m", c["Meters"])
    }

    @Test fun numberFractionConversion() {
        val c = MeasureFormat.conversions(Quantity.number(Rational.of(10, 3)), Precision.SIXTEENTH, LengthStyle.FEET_INCHES)
        assertEquals("≈ 3 5/16", c.single().value)
        assertEquals("3.333333", MeasureFormat.primary(Quantity.number(Rational.of(10, 3)), Precision.SIXTEENTH, LengthStyle.FEET_INCHES))
    }

    @Test fun clipboardText() {
        assertEquals("-10' 4-3/16\"", MeasureFormat.forClipboard("−10' 4 3/16\""))
        assertEquals("7/16\"", MeasureFormat.forClipboard("7/16\""))
    }

    @Test fun roundingDirection() {
        val third = Quantity.inches(Rational.of(121, 3)) // 40.333" → shown as 40 5/16" (down)
        assertEquals(-1, MeasureFormat.roundingDirection(third, Precision.SIXTEENTH))
        val twoThirds = Quantity.inches(Rational.of(2, 3)) // 0.667" → 11/16" (up)
        assertEquals(1, MeasureFormat.roundingDirection(twoThirds, Precision.SIXTEENTH))
        assertEquals(0, MeasureFormat.roundingDirection(Quantity.inches(Rational.of(1, 2)), Precision.SIXTEENTH))
    }

    @Test fun fractionRoundingModes() {
        val fortyAndThird = Rational.of(121, 3) // 40.3333" (between 40 5/16" and 40 3/8")
        assertEquals("3' 4 5/16\"", MeasureFormat.length(fortyAndThird, Precision.SIXTEENTH, LengthStyle.FEET_INCHES, rounding = FractionRounding.NEAREST))
        assertEquals("3' 4 5/16\"", MeasureFormat.length(fortyAndThird, Precision.SIXTEENTH, LengthStyle.FEET_INCHES, rounding = FractionRounding.DOWN))
        assertEquals("3' 4 3/8\"", MeasureFormat.length(fortyAndThird, Precision.SIXTEENTH, LengthStyle.FEET_INCHES, rounding = FractionRounding.UP))

        assertEquals(-1, MeasureFormat.roundingDirection(Quantity.inches(fortyAndThird), Precision.SIXTEENTH, FractionRounding.DOWN))
        assertEquals(1, MeasureFormat.roundingDirection(Quantity.inches(fortyAndThird), Precision.SIXTEENTH, FractionRounding.UP))

        val twoThirds = Rational.of(2, 3) // 0.6667" (between 5/8" and 11/16")
        assertEquals("11/16\"", MeasureFormat.length(twoThirds, Precision.SIXTEENTH, LengthStyle.INCHES, rounding = FractionRounding.NEAREST))
        assertEquals("11/16\"", MeasureFormat.length(twoThirds, Precision.SIXTEENTH, LengthStyle.INCHES, rounding = FractionRounding.UP))
        assertEquals("5/8\"", MeasureFormat.length(twoThirds, Precision.SIXTEENTH, LengthStyle.INCHES, rounding = FractionRounding.DOWN))
        assertEquals(-1, MeasureFormat.roundingDirection(Quantity.inches(twoThirds), Precision.SIXTEENTH, FractionRounding.DOWN))

        // Exact fractions are unaffected by rounding mode
        val exactHalf = Rational.of(1, 2)
        FractionRounding.entries.forEach { mode ->
            assertEquals("1/2\"", MeasureFormat.length(exactHalf, Precision.SIXTEENTH, LengthStyle.INCHES, rounding = mode))
            assertEquals(0, MeasureFormat.roundingDirection(Quantity.inches(exactHalf), Precision.SIXTEENTH, mode))
        }

        // Negative values: UP moves toward +∞ (shown >= actual), DOWN moves toward -∞ (shown <= actual)
        val negThird = Rational.of(-1, 3) // -0.3333" (between -3/8" and -5/16")
        assertEquals("−5/16\"", MeasureFormat.length(negThird, Precision.SIXTEENTH, LengthStyle.INCHES, rounding = FractionRounding.NEAREST))
        assertEquals("−5/16\"", MeasureFormat.length(negThird, Precision.SIXTEENTH, LengthStyle.INCHES, rounding = FractionRounding.UP))
        assertEquals("−3/8\"", MeasureFormat.length(negThird, Precision.SIXTEENTH, LengthStyle.INCHES, rounding = FractionRounding.DOWN))
        assertEquals(1, MeasureFormat.roundingDirection(Quantity.inches(negThird), Precision.SIXTEENTH, FractionRounding.UP))
        assertEquals(-1, MeasureFormat.roundingDirection(Quantity.inches(negThird), Precision.SIXTEENTH, FractionRounding.DOWN))
    }

    @Test fun architecturalFeetInchStyle() {
        assertEquals("5-03-08", MeasureFormat.length(Rational.of(63) + Rational.of(1, 2), Precision.SIXTEENTH, LengthStyle.FEET_INCHES, FeetInchStyle.ARCHITECTURAL))
        assertEquals("7-11-14", MeasureFormat.length(Rational.of(95) + Rational.of(7, 8), Precision.SIXTEENTH, LengthStyle.FEET_INCHES, FeetInchStyle.ARCHITECTURAL))
        assertEquals("10-04-03", MeasureFormat.length(Rational.of(124) + Rational.of(3, 16), Precision.SIXTEENTH, LengthStyle.FEET_INCHES, FeetInchStyle.ARCHITECTURAL))
        assertEquals("2-00-00", MeasureFormat.length(Rational.of(24), Precision.SIXTEENTH, LengthStyle.FEET_INCHES, FeetInchStyle.ARCHITECTURAL))
        assertEquals("0-00-00", MeasureFormat.length(Rational.ZERO, Precision.SIXTEENTH, LengthStyle.FEET_INCHES, FeetInchStyle.ARCHITECTURAL))
        assertEquals("0-00-07", MeasureFormat.length(Rational.of(7, 16), Precision.SIXTEENTH, LengthStyle.FEET_INCHES, FeetInchStyle.ARCHITECTURAL))
        assertEquals("−1-00-08", MeasureFormat.length(Rational.of(-25, 2), Precision.SIXTEENTH, LengthStyle.FEET_INCHES, FeetInchStyle.ARCHITECTURAL))
        assertEquals("5-03-1/32", MeasureFormat.length(Rational.of(63) + Rational.of(1, 32), Precision.THIRTY_SECOND, LengthStyle.FEET_INCHES, FeetInchStyle.ARCHITECTURAL))
    }
}
