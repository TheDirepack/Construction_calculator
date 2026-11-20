package com.thedirepack.constructioncalc.core.math

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger

class RationalTest {
    @Test fun reducesAndNormalizesSign() {
        assertEquals(Rational.of(1, 2), Rational.of(-3, -6))
        assertEquals("-1/2", Rational.of(3, -6).toString())
    }

    @Test fun arithmeticIsExact() {
        val third = Rational.of(1, 3)
        assertEquals(Rational.ONE, third + third + third)
        assertEquals(Rational.of(3, 16), Rational.of(1, 8) + Rational.of(1, 16))
    }

    @Test fun parsesDecimals() {
        assertEquals(Rational.of(99, 8), Rational.parseDecimal("12.375"))
        assertEquals(Rational.of(1, 2), Rational.parseDecimal(".5"))
        assertEquals(Rational.of(5), Rational.parseDecimal("5."))
    }

    @Test fun roundsHalfAwayFromZero() {
        assertEquals(BigInteger.valueOf(1), Rational.of(1, 32).roundToSteps(16))
        assertEquals(BigInteger.valueOf(-1), Rational.of(-1, 32).roundToSteps(16))
        assertEquals(BigInteger.ZERO, Rational.of(1, 33).roundToSteps(16))
        assertEquals(BigInteger.valueOf(53), Rational.of(10, 3).roundToSteps(16)) // 3.3333 * 16 = 53.33
    }
}
