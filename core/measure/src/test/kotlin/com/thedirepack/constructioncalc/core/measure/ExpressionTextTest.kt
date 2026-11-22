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

    @Test fun formatsParensSqrtAndSquare() {
        val add = Token.Op(Operator.ADD)
        val minus = Token.Op(Operator.SUBTRACT)
        val times = Token.Op(Operator.MULTIPLY)
        assertEquals(
            "√(3'² + 4'²)",
            fmt(Token.Sqrt, Token.OpenParen, Token.Literal("3'"), Token.Square, add, Token.Literal("4'"), Token.Square, Token.CloseParen),
        )
        assertEquals(
            "(5' + 6) × 2",
            fmt(Token.OpenParen, Token.Literal("5'"), add, Token.Literal("6"), Token.CloseParen, times, Token.Literal("2")),
        )
        assertEquals(
            "(−3)²",
            fmt(Token.OpenParen, minus, Token.Literal("3"), Token.CloseParen, Token.Square),
        )
        assertEquals(
            "√25 + 1",
            fmt(Token.Sqrt, Token.Literal("25"), add, Token.Literal("1")),
        )
    }
}
