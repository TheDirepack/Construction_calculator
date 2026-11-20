package com.thedirepack.constructioncalc.core.measure

import com.thedirepack.constructioncalc.core.math.Rational
import org.junit.Assert.assertEquals
import org.junit.Test

class ExpressionTextTest {
    private fun fmt(vararg tokens: Token) =
        ExpressionText.format(tokens.toList(), Precision.SIXTEENTH, LengthStyle.FEET_INCHES)

    @Test fun formatsBinaryAndUnaryOperators() {
        val add = Token.Op(Operator.ADD)
        val minus = Token.Op(Operator.SUBTRACT)
        val times = Token.Op(Operator.MULTIPLY)
        assertEquals("5' 3 1/2\" + 2' 8\"", fmt(Token.Literal("5'3\"1/2"), add, Token.Literal("2'8")))
        assertEquals("−2' + 6", fmt(minus, Token.Literal("2'"), add, Token.Literal("6")))
        assertEquals("5' × −2", fmt(Token.Literal("5'"), times, minus, Token.Literal("2")))
        assertEquals("5' − 2\"", fmt(Token.Literal("5'"), minus, Token.Literal("2\"")))
        assertEquals("5' 6\" ×", fmt(Token.Value(Quantity.inches(Rational.of(66))), times))
    }
}
