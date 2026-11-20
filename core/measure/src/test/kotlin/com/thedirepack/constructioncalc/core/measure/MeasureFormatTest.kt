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
}
