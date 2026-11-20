package com.thedirepack.constructioncalc.core.measure

import com.thedirepack.constructioncalc.core.math.Rational
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasureLiteralTest {
    private fun inches(n: Long, d: Long = 1) = Quantity.inches(Rational.of(n, d))

    @Test fun parsesFeetInchFractionForms() {
        assertEquals(inches(60), MeasureLiteral.parse("5'"))
        assertEquals(inches(63), MeasureLiteral.parse("5'3"))
        assertEquals(inches(63), MeasureLiteral.parse("5'3\""))
        assertEquals(inches(127, 2), MeasureLiteral.parse("5'3\"1/2"))
        assertEquals(inches(127, 2), MeasureLiteral.parse("5'3\"1/2\""))
        assertEquals(inches(121, 2), MeasureLiteral.parse("5'1/2"))
        assertEquals(inches(7, 2), MeasureLiteral.parse("3\"1/2"))
        assertEquals(inches(7, 16), MeasureLiteral.parse("7/16\""))
        assertEquals(inches(30), MeasureLiteral.parse("2.5'"))
        assertEquals(inches(6), MeasureLiteral.parse("1/2'"))
    }

    @Test fun bareNumbersAreUnitless() {
        assertEquals(Quantity.number(Rational.of(12)), MeasureLiteral.parse("12"))
        assertEquals(Quantity.number(Rational.of(3, 4)), MeasureLiteral.parse("3/4"))
    }

    @Test fun prefixValidation() {
        listOf("5", "5'", "5'3", "5'3\"", "5'3\"1", "5'3\"1/", "5'3\"1/2", ".", "3/", "1/2\"").forEach {
            assertTrue(it, MeasureLiteral.isValidPrefix(it))
        }
        listOf("'", "\"", "/", "5''", "5'3'", "5\"'", "1/2/", "1..", "5'3\"1/2\"4", "1/2\"3").forEach {
            assertFalse(it, MeasureLiteral.isValidPrefix(it))
        }
    }

    @Test fun displaysTypedText() {
        assertEquals("5' 3 1/2\"", MeasureLiteral.display("5'3\"1/2"))
        assertEquals("5'", MeasureLiteral.display("5'"))
        assertEquals("5' 1/2\"", MeasureLiteral.display("5'1/2"))
        assertEquals("12", MeasureLiteral.display("12"))
        assertEquals("5' 3\"1/", MeasureLiteral.display("5'3\"1/"))
    }
}
