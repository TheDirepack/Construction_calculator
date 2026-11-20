package com.thedirepack.constructioncalc.core.measure

import com.thedirepack.constructioncalc.core.math.Rational
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EvaluatorTest {

    /** Splits e.g. `5'3 + 2'8` into tokens. */
    private fun tokens(expr: String): List<Token> = expr.split(' ').filter { it.isNotEmpty() }.map { part ->
        Operator.entries.firstOrNull { it.symbol == part }?.let { Token.Op(it) } ?: Token.Literal(part)
    }

    private fun eval(expr: String) = Evaluator.evaluate(tokens(expr))
    private fun fmt(expr: String, p: Precision = Precision.SIXTEENTH) =
        MeasureFormat.primary(eval(expr), p, LengthStyle.FEET_INCHES)

    @Test fun addsFeetAndInches() {
        assertEquals("8' 0\"", fmt("5'3\"1/2 + 2'8\"1/2"))
        assertEquals("5' 6\"", fmt("5' + 6"))
        assertEquals("3 3/16\"", fmt("3\"1/8 + 1/16\""))
    }

    @Test fun precedenceAndUnaryMinus() {
        assertEquals("3' 0\"", fmt("2' + 3 × 4"))
        assertEquals("−1' 6\"", fmt("− 2' + 6"))
        assertEquals("−10' 0\"", fmt("5' × − 2"))
    }

    @Test fun dividesIntoEqualParts() {
        assertEquals("3' 4\"", fmt("10' ÷ 3"))
        assertEquals(false, MeasureFormat.isRounded(eval("10' ÷ 4"), Precision.SIXTEENTH))
        assertEquals(true, MeasureFormat.isRounded(eval("10'1 ÷ 3"), Precision.SIXTEENTH))
        assertEquals("3' 4 5/16\"", fmt("10'1 ÷ 3")) // 40.333" → 40 5/16"
    }

    @Test fun lengthOverLengthIsCount() {
        val q = eval("10' ÷ 16\"")
        assertEquals(Quantity.number(Rational.of(15, 2)), q)
    }

    @Test fun areaAndVolume() {
        assertEquals("50 sq ft", fmt("10' × 5'"))
        assertEquals("100 cu ft", fmt("10' × 5' × 2'"))
        assertEquals(Dimension.LENGTH, eval("10' × 5' ÷ 5'").dimension)
    }

    @Test fun errors() {
        assertThrows<CalcException.DivideByZero> { eval("5' ÷ 0") }
        assertThrows<CalcException.UnitMismatch> { eval("10' × 5' + 3'") }
        assertThrows<CalcException.UnitMismatch> { eval("5 ÷ 2'") }
        assertThrows<CalcException.UnitMismatch> { eval("1' × 1' × 1' × 1'") }
        assertThrows<CalcException.Incomplete> { eval("5'3\"1/") }
    }

    @Test fun previewIgnoresTrailingOperator() {
        assertEquals(Quantity.inches(Rational.of(60)), Evaluator.preview(tokens("5' +")))
        assertNull(Evaluator.preview(tokens("5' ÷ 0")))
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            if (t is T) return
            throw AssertionError("Expected ${T::class.simpleName} but got $t")
        }
        throw AssertionError("Expected ${T::class.simpleName}")
    }
}
