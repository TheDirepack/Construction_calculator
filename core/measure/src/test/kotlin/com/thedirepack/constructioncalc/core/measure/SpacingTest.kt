package com.thedirepack.constructioncalc.core.measure

import com.thedirepack.constructioncalc.core.math.Rational
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpacingTest {

    private fun ft(n: Long) = Quantity.feet(Rational.of(n))
    private fun inch(n: Long, d: Long = 1) = Quantity.inches(Rational.of(n, d))

    @Test
    fun bySpacingProducesExactCumulativeMarks() {
        // 8' (96") wall at 16" OC -> 0", 16", 32", 48", 64", 80", 96"
        val layout = Spacing.bySpacing(totalSpan = ft(8), onCenter = inch(16))
        assertNotNull(layout)
        assertEquals(7, layout!!.markCount)
        assertEquals(6, layout.spaceCount)
        assertEquals(inch(0), layout.marks.first())
        assertEquals(inch(48), layout.marks[3])
        assertEquals(inch(96), layout.marks.last())
        assertEquals(inch(0), layout.remainder)
        assertFalse(layout.truncated)
    }

    @Test
    fun bySpacingRespectsStartAndEndOffsetsAndRemainder() {
        // 10' (120") span, 6" start offset, 6" end offset -> usable 108", at 24" OC:
        // marks: 6", 30", 54", 78", 102" (remainder = 114 - 102 = 12")
        val layout = Spacing.bySpacing(
            totalSpan = ft(10),
            onCenter = inch(24),
            startOffset = inch(6),
            endOffset = inch(6),
        )
        assertNotNull(layout)
        assertEquals(inch(108), layout!!.usableSpan)
        assertEquals(
            listOf(inch(6), inch(30), inch(54), inch(78), inch(102)),
            layout.marks,
        )
        assertEquals(inch(12), layout.remainder)

        // With includeEnd = true, appends 114"
        val withEnd = Spacing.bySpacing(
            totalSpan = ft(10),
            onCenter = inch(24),
            startOffset = inch(6),
            endOffset = inch(6),
            includeEnd = true,
        )
        assertEquals(inch(114), withEnd!!.marks.last())
        assertEquals(6, withEnd.markCount)
    }

    @Test
    fun equalSpacesDividesExactlyWithoutDrift() {
        // 10' (120") into 7 equal spaces -> each space is 120/7 inches; last mark is EXACTLY 120"
        val layout = Spacing.equalSpaces(totalSpan = ft(10), spaces = 7)
        assertNotNull(layout)
        assertEquals(8, layout!!.markCount)
        assertEquals(7, layout.spaceCount)
        assertEquals(inch(120, 7), layout.spacing)
        assertEquals(inch(0), layout.marks.first())
        assertEquals(inch(120), layout.marks.last())
        assertEquals(inch(0), layout.remainder)
    }

    @Test
    fun equalCountHandlesSingleAndMultipleMarks() {
        val single = Spacing.equalCount(totalSpan = ft(10), count = 1, startOffset = inch(12), endOffset = inch(12))
        assertNotNull(single)
        assertEquals(listOf(inch(60)), single!!.marks)

        val five = Spacing.equalCount(totalSpan = ft(8), count = 5)
        assertNotNull(five)
        assertEquals(5, five!!.markCount)
        assertEquals(inch(24), five.spacing)
    }

    @Test
    fun capsAtMaxMarksAndRejectsInvalidInputs() {
        val huge = Spacing.bySpacing(totalSpan = ft(1000), onCenter = inch(1))
        assertNotNull(huge)
        assertEquals(Spacing.MAX_MARKS, huge!!.markCount)
        assertTrue(huge.truncated)

        assertNull(Spacing.bySpacing(totalSpan = inch(0), onCenter = inch(16)))
        assertNull(Spacing.bySpacing(totalSpan = ft(8), onCenter = inch(0)))
        assertNull(Spacing.bySpacing(totalSpan = ft(8), onCenter = inch(16), startOffset = ft(5), endOffset = ft(5)))
        assertNull(Spacing.equalSpaces(totalSpan = ft(8), spaces = 0))
        assertNull(Spacing.bySpacing(totalSpan = Quantity.number(Rational.of(96)), onCenter = inch(16)))
    }
}
