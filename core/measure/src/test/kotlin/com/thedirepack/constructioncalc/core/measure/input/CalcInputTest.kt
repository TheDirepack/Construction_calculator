package com.thedirepack.constructioncalc.core.measure.input

import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Operator
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.measure.Token
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalcInputTest {

    private fun keys(seq: String): List<Key> = seq.map {
        when (it) {
            '+' -> Key.Op(Operator.ADD)
            '-' -> Key.Op(Operator.SUBTRACT)
            '*' -> Key.Op(Operator.MULTIPLY)
            '÷' -> Key.Op(Operator.DIVIDE)
            '=' -> Key.Equals
            '<' -> Key.Backspace
            'C' -> Key.Clear
            else -> Key.Char(it)
        }
    }

    private fun press(seq: String, start: CalcState = CalcState()) =
        keys(seq).fold(start) { s, k -> CalcInput.reduce(s, k).state }

    @Test fun buildsTokens() {
        val s = press("5'3\"1/2+2'8")
        assertEquals(
            listOf(Token.Literal("5'3\"1/2"), Token.Op(Operator.ADD), Token.Literal("2'8")),
            s.tokens,
        )
        assertEquals(Quantity.inches(Rational.of(191, 2)), s.result)
    }

    @Test fun equalsProducesAnswerAndCalculation() {
        var s = press("5'+6")
        val out = CalcInput.reduce(s, Key.Equals)
        assertNotNull(out.completed)
        s = out.state
        assertEquals(Quantity.inches(Rational.of(66)), s.answer)
        // Operator continues from the answer.
        s = press("*2=", s)
        assertEquals(Quantity.inches(Rational.of(132)), s.answer)
        // A digit starts fresh.
        s = press("3", s)
        assertNull(s.answer)
        assertEquals(listOf(Token.Literal("3")), s.tokens)
    }

    @Test fun rejectsInvalidKeys() {
        assertEquals(emptyList<Token>(), press("'").tokens)
        assertEquals(listOf(Token.Literal("5'")), press("5''").tokens)
        assertFalse(CalcInput.reduce(CalcState(), Key.Op(Operator.ADD)).accepted)
    }

    @Test fun operatorReplacementAndUnaryMinus() {
        assertEquals(listOf(Token.Literal("5"), Token.Op(Operator.MULTIPLY)), press("5+*").tokens)
        assertEquals(
            listOf(Token.Literal("5"), Token.Op(Operator.MULTIPLY), Token.Op(Operator.SUBTRACT)),
            press("5*-").tokens,
        )
        assertEquals(listOf(Token.Literal("5"), Token.Op(Operator.ADD)), press("5*-+").tokens)
        assertEquals(listOf(Token.Op(Operator.SUBTRACT), Token.Literal("2")), press("-2").tokens)
    }

    @Test fun backspace() {
        assertEquals(listOf(Token.Literal("5'")), press("5'3<").tokens)
        assertEquals(listOf(Token.Literal("5")), press("5+<").tokens)
        assertEquals(emptyList<Token>(), press("5<").tokens)
        val s = press("5+1=<")
        assertNull(s.answer)
        assertEquals(3, s.tokens.size) // "5 + 1" kept for editing
    }

    @Test fun errorOnEqualsKeepsInput() {
        val s = press("5÷0=")
        assertEquals("Can't divide by zero", s.error)
        assertEquals(3, s.tokens.size)
        assertNull(press("5÷0=<").error)
    }

    @Test fun recallReplacesOperandOrAppends() {
        val q = Quantity.inches(Rational.of(10))
        assertEquals(listOf(Token.Value(q)), CalcInput.reduce(press("5"), Key.Recall(q)).state.tokens)
        assertEquals(
            listOf(Token.Literal("5"), Token.Op(Operator.ADD), Token.Value(q)),
            CalcInput.reduce(press("5+"), Key.Recall(q)).state.tokens,
        )
    }

    private fun frac(n: Int, d: Int, start: CalcState) = CalcInput.reduce(start, Key.Fraction(n, d)).state

    @Test fun fractionKeyBuildsMeasurements() {
        assertEquals(listOf(Token.Literal("5'3\"1/2\"")), frac(1, 2, press("5'3")).tokens)
        assertEquals(listOf(Token.Literal("5'1/2\"")), frac(1, 2, press("5'")).tokens)
        assertEquals(listOf(Token.Literal("3\"7/16\"")), frac(7, 16, press("3")).tokens)
        assertEquals(listOf(Token.Literal("7/16\"")), frac(7, 16, CalcState()).tokens)
        assertEquals(
            listOf(Token.Literal("5'"), Token.Op(Operator.ADD), Token.Literal("3/4\"")),
            frac(3, 4, press("5'+")).tokens,
        )
        assertEquals(Quantity.inches(Rational.of(127, 2)), frac(1, 2, press("5'3")).result)
    }

    @Test fun fractionKeyReplacesExistingFraction() {
        val once = frac(1, 4, press("5'3"))
        assertEquals(listOf(Token.Literal("5'3\"3/8\"")), frac(3, 8, once).tokens)
        assertEquals(listOf(Token.Literal("1/2\"")), frac(1, 2, frac(7, 16, CalcState())).tokens)
    }

    @Test fun baseForFraction() {
        assertEquals("5'3\"", CalcInput.baseForFraction("5'3\"1/"))
        assertEquals("", CalcInput.baseForFraction("3/4"))
        assertEquals("5'", CalcInput.baseForFraction("5'1/2"))
    }

    @Test fun prefersInches() {
        assertEquals(true, CalcInput.prefersInches(press("96\"-55\"").tokens))
        assertEquals(false, CalcInput.prefersInches(press("96\"-5'").tokens))
        assertEquals(null, CalcInput.prefersInches(press("96-55").tokens))
        // The hint survives continuing from an answer.
        val chained = press("96\"-55\"=*2")
        assertEquals(true, (chained.tokens.first() as Token.Value).preferInches)
        assertEquals(true, CalcInput.prefersInches(chained.tokens))
    }

    @Test fun typingOverARecalledValueReplacesIt() {
        // A length field opens with its current value; typing a new number must replace it.
        val q = Quantity.inches(Rational.of(30))
        val start = CalcState(tokens = listOf(Token.Value(q)))
        val typed = CalcInput.reduce(start, Key.Char('7'))
        assertTrue(typed.accepted)
        assertEquals(listOf(Token.Literal("7")), typed.state.tokens)
        val fraction = CalcInput.reduce(start, Key.Fraction(1, 2))
        assertEquals(listOf(Token.Literal("1/2\"")), fraction.state.tokens)
        // Operators still continue from the value.
        val plus = CalcInput.reduce(start, Key.Op(Operator.ADD))
        assertEquals(listOf(Token.Value(q), Token.Op(Operator.ADD)), plus.state.tokens)
    }

    @Test fun parenthesesSqrtSquareAndMetricKeys() {
        // √(3'² + 4'²) = 5'
        val keys = listOf(
            Key.Sqrt,
            Key.OpenParen,
            Key.Char('3'), Key.Char('\''), Key.Square,
            Key.Op(Operator.ADD),
            Key.Char('4'), Key.Char('\''), Key.Square,
            Key.CloseParen,
            Key.Equals,
        )
        val finalState = keys.fold(CalcState()) { s, k -> CalcInput.reduce(s, k).state }
        assertEquals(Quantity.feet(Rational.of(5)), finalState.answer)

        // Implicit multiplication: 2(3+4) = 14
        val mulState = listOf(
            Key.Char('2'), Key.OpenParen, Key.Char('3'), Key.Op(Operator.ADD), Key.Char('4'), Key.CloseParen, Key.Equals,
        ).fold(CalcState()) { s, k -> CalcInput.reduce(s, k).state }
        assertEquals(Quantity.number(Rational.of(14)), mulState.answer)

        // Unmatched close paren is rejected
        assertFalse(CalcInput.reduce(press("5"), Key.CloseParen).accepted)

        // Applying x² or √ directly to an existing answer
        val ansState = press("5=")
        val sqAns = CalcInput.reduce(ansState, Key.Square)
        assertTrue(sqAns.accepted)
        assertEquals(Quantity.number(Rational.of(25)), sqAns.state.result)
        val sqrtAfterSq = CalcInput.reduce(CalcInput.reduce(sqAns.state, Key.Equals).state, Key.Sqrt)
        assertTrue(sqrtAfterSq.accepted)
        assertEquals(Quantity.number(Rational.of(5)), sqrtAfterSq.state.result)

        // Metric entry alongside imperial via Key.Text
        val metricPlusImperial = listOf(
            Key.Char('1'), Key.Char('\''),
            Key.Op(Operator.ADD),
            Key.Char('2'), Key.Char('5'), Key.Char('.'), Key.Char('4'), Key.Text("mm"),
            Key.Equals,
        ).fold(CalcState()) { s, k -> CalcInput.reduce(s, k).state }
        assertEquals(Quantity.inches(Rational.of(13)), metricPlusImperial.answer)
    }
}
