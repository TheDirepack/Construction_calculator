package com.thedirepack.constructioncalc.core.measure

import com.thedirepack.constructioncalc.core.math.Rational
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EvaluatorTest {

    /** Splits e.g. `5'3 + 2'8` into tokens. */
    private fun tokens(expr: String): List<Token> = expr.split(' ').filter { it.isNotEmpty() }.map { part ->
        when (part) {
            "(" -> Token.OpenParen
            ")" -> Token.CloseParen
            "√" -> Token.Sqrt
            "²" -> Token.Square
            else -> Operator.entries.firstOrNull { it.symbol == part }?.let { Token.Op(it) } ?: Token.Literal(part)
        }
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

    @Test fun mixedFractionsCarryAcrossAWholeFootBeforeFormatting() {
        val result = eval("5'11\"63/64 + 1/64\"")
        assertEquals(Quantity.inches(Rational.of(72)), result)
        assertEquals("6' 0\"", fmt("5'11\"63/64 + 1/64\""))
        assertEquals(0, MeasureFormat.roundingDirection(result, Precision.SIXTEENTH))
    }

    @Test fun roundingAtTheHalfStepCarriesIntoTheNextFootInBothDirections() {
        val positive = eval("11\"31/32")
        val negative = eval("− 11\"31/32")
        assertEquals(Quantity.inches(Rational.of(383, 32)), positive)
        assertEquals(Quantity.inches(Rational.of(-383, 32)), negative)
        assertEquals("1' 0\"", fmt("11\"31/32"))
        assertEquals("−1' 0\"", fmt("− 11\"31/32"))
        assertEquals(1, MeasureFormat.roundingDirection(positive, Precision.SIXTEENTH))
        assertEquals(-1, MeasureFormat.roundingDirection(negative, Precision.SIXTEENTH))
    }

    @Test fun mixedUnitVolumeDividedByLengthCanBeAddedToAnArea() {
        // (18 in × 7.5 in × 28 in) / 15 in − 144 sq in = 108 sq in
        val result = eval("1'6 × 7\"1/2 × 2'4 ÷ 15\" − 1' ²")
        assertEquals(Quantity(Rational.of(108), Dimension.AREA), result)
        assertEquals("0.75 sq ft", fmt("1'6 × 7\"1/2 × 2'4 ÷ 15\" − 1' ²"))
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

    @Test fun parenthesesOverridePrecedence() {
        assertEquals("14' 0\"", fmt("( 2' + 5' ) × 2"))
        assertEquals("2' 6\"", fmt("( 10' − ( 3' + 2' ) ) ÷ 2"))
        assertEquals(Quantity.number(Rational.of(9)), eval("( − 3 ) ²"))
        assertEquals(Quantity.number(Rational.of(-9)), eval("− 3 ²"))
        // Preview auto-closes open parens
        assertEquals(Quantity.inches(Rational.of(84)), Evaluator.preview(tokens("( 2' + 5'")))
        assertThrows<CalcException.Incomplete> { eval("( 2' + 5'") }
        assertThrows<CalcException.Syntax> { eval("2' + 5' )") }
        assertThrows<CalcException.Syntax> { eval("( )") }
    }

    @Test fun squareAndSquareRootExactAndDimensions() {
        // Exact rational squares and roots
        assertEquals(Quantity.number(Rational.of(25)), eval("5 ²"))
        assertEquals(Quantity.number(Rational.of(9, 16)), eval("3/4 ²"))
        assertEquals(Quantity.number(Rational.of(3, 4)), eval("√ 9/16"))
        assertEquals(Quantity.number(Rational.of(0)), eval("√ 0"))
        // Length squared -> Area; Area square root -> Length (exact 3-4-5 right triangle!)
        val area = eval("3' ²")
        assertEquals(Dimension.AREA, area.dimension)
        assertEquals("9 sq ft", MeasureFormat.primary(area, Precision.SIXTEENTH, LengthStyle.FEET_INCHES))
        val diag = eval("√ ( 3' ² + 4' ² )")
        assertEquals(Quantity.feet(Rational.of(5)), diag)
        // Irrational root falls back cleanly
        val root2 = eval("√ 2")
        assertEquals(Dimension.NUMBER, root2.dimension)
        assertEquals(1.41421356, root2.value.toDouble(), 1e-6)
        // Dimension & domain errors
        assertThrows<CalcException.UnitMismatch> { eval("( 3' × 4' ) ²") } // AREA² rejected
        assertThrows<CalcException.UnitMismatch> { eval("( 1' × 1' × 1' ) ²") } // VOLUME² rejected
        assertThrows<CalcException.UnitMismatch> { eval("√ 5'") } // √LENGTH rejected
        assertThrows<CalcException.UnitMismatch> { eval("√ ( 1' × 1' × 1' )") } // √VOLUME rejected
        assertThrows<CalcException.Syntax> { eval("√ − 4") } // negative rejected
    }

    @Test fun mixedMetricAndImperialArithmetic() {
        // 25.4 mm == 1" exact
        assertEquals(Quantity.inches(Rational.of(1)), eval("25.4mm"))
        assertEquals(Quantity.inches(Rational.of(10)), eval("25.4cm"))
        assertEquals(Quantity.inches(Rational.of(13)), eval("1' + 25.4mm"))
        assertEquals("1' 1\"", fmt("1' + 25.4mm"))
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
