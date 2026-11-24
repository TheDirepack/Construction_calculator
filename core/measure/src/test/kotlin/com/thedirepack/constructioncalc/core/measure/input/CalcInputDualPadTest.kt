package com.thedirepack.constructioncalc.core.measure.input

import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.MeasureLiteral
import com.thedirepack.constructioncalc.core.measure.Operator
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.measure.Token
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalcInputDualPadTest {

    private fun reduceAll(vararg keys: Key, start: CalcState = CalcState()): CalcState =
        keys.fold(start) { s, k -> CalcInput.reduce(s, k).state }

    private fun feet(seq: String): List<Key> = seq.map { Key.FeetDigit(it) }
    private fun inches(seq: String): List<Key> = seq.map { Key.InchDigit(it) }

    @Test
    fun threeTapMixedEntryFiveFootThreeAndHalfInches() {
        val state = reduceAll(Key.FeetDigit('5'), Key.InchDigit('3'), Key.Fraction(1, 2))
        assertEquals(listOf(Token.Literal("5'3\"1/2\"")), state.tokens)
        assertEquals(Quantity.inches(Rational.of(127, 2)), state.result)
        assertEquals("5' 3 1/2\"", MeasureLiteral.display("5'3\"1/2\""))
        assertEquals(false, CalcInput.prefersInches(state.tokens))
    }

    @Test
    fun orderIndependentInchAndFractionBeforeFeet() {
        val expectedQty = Quantity.inches(Rational.of(127, 2))

        val inchThenFeetThenFrac = reduceAll(Key.InchDigit('3'), Key.FeetDigit('5'), Key.Fraction(1, 2))
        val inchThenFracThenFeet = reduceAll(Key.InchDigit('3'), Key.Fraction(1, 2), Key.FeetDigit('5'))
        val fracThenInchThenFeet = reduceAll(Key.Fraction(1, 2), Key.InchDigit('3'), Key.FeetDigit('5'))

        listOf(inchThenFeetThenFrac, inchThenFracThenFeet, fracThenInchThenFeet).forEach { s ->
            assertEquals(listOf(Token.Literal("5'3\"1/2\"")), s.tokens)
            assertEquals(expectedQty, s.result)
        }

        val fracThenFeet = reduceAll(Key.Fraction(1, 2), Key.FeetDigit('5'))
        assertEquals(listOf(Token.Literal("5'1/2\"")), fracThenFeet.tokens)
        assertEquals(Quantity.inches(Rational.of(121, 2)), fracThenFeet.result)
    }

    @Test
    fun multiDigitFeetAndInches() {
        val keys = feet("12") + inches("11") + Key.Fraction(3, 8)
        val state = keys.fold(CalcState()) { s, k -> CalcInput.reduce(s, k).state }
        assertEquals(listOf(Token.Literal("12'11\"3/8\"")), state.tokens)
        assertEquals(Quantity.inches(Rational.of(1243, 8)), state.result) // 155 3/8"
    }

    @Test
    fun decimalEntriesOnFeetAndInches() {
        val decFeet = feet("2.5").fold(CalcState()) { s, k -> CalcInput.reduce(s, k).state }
        assertEquals(listOf(Token.Literal("2.5'")), decFeet.tokens)
        assertEquals(Quantity.inches(Rational.of(30)), decFeet.result)

        val leadingDotFeet = feet(".5").fold(CalcState()) { s, k -> CalcInput.reduce(s, k).state }
        assertEquals(listOf(Token.Literal("0.5'")), leadingDotFeet.tokens)
        assertEquals(Quantity.inches(Rational.of(6)), leadingDotFeet.result)

        val decInches = inches("3.5").fold(CalcState()) { s, k -> CalcInput.reduce(s, k).state }
        assertEquals(listOf(Token.Literal("3.5\"")), decInches.tokens)
        assertEquals(Quantity.inches(Rational.of(7, 2)), decInches.result)

        val leadingDotInches = inches(".25").fold(CalcState()) { s, k -> CalcInput.reduce(s, k).state }
        assertEquals(listOf(Token.Literal("0.25\"")), leadingDotInches.tokens)
        assertEquals(Quantity.inches(Rational.of(1, 4)), leadingDotInches.result)

        val wholeFeetDecimalInches = (feet("5") + inches("3.5")).fold(CalcState()) { s, k -> CalcInput.reduce(s, k).state }
        assertEquals(listOf(Token.Literal("5'3.5\"")), wholeFeetDecimalInches.tokens)
        assertEquals(Quantity.inches(Rational.of(127, 2)), wholeFeetDecimalInches.result)
    }

    @Test
    fun clearFractionRemovesFractionPartCleanly() {
        val fromMixed = reduceAll(Key.FeetDigit('5'), Key.InchDigit('3'), Key.Fraction(1, 2), Key.ClearFraction)
        assertEquals(listOf(Token.Literal("5'3\"")), fromMixed.tokens)
        assertEquals(Quantity.inches(Rational.of(63)), fromMixed.result)

        val fromFeetAndFrac = reduceAll(Key.FeetDigit('5'), Key.Fraction(1, 2), Key.ClearFraction)
        assertEquals(listOf(Token.Literal("5'")), fromFeetAndFrac.tokens)
        assertEquals(Quantity.inches(Rational.of(60)), fromFeetAndFrac.result)

        val fromInchesAndFrac = reduceAll(Key.InchDigit('3'), Key.Fraction(1, 2), Key.ClearFraction)
        assertEquals(listOf(Token.Literal("3\"")), fromInchesAndFrac.tokens)
        assertEquals(Quantity.inches(Rational.of(3)), fromInchesAndFrac.result)

        val fromFracOnly = reduceAll(Key.Fraction(1, 2), Key.ClearFraction)
        assertEquals(emptyList<Token>(), fromFracOnly.tokens)

        val afterOpFracOnly = reduceAll(Key.FeetDigit('5'), Key.Op(Operator.ADD), Key.Fraction(1, 2), Key.ClearFraction)
        assertEquals(listOf(Token.Literal("5'"), Token.Op(Operator.ADD)), afterOpFracOnly.tokens)

        // Rejected when there is no fraction or after Equals
        val noFrac = reduceAll(Key.FeetDigit('5'), Key.InchDigit('3'))
        val rejectedNoFrac = CalcInput.reduce(noFrac, Key.ClearFraction)
        assertFalse(rejectedNoFrac.accepted)
        assertEquals(noFrac.tokens, rejectedNoFrac.state.tokens)

        assertFalse(CalcInput.reduce(CalcState(), Key.ClearFraction).accepted)

        val answered = reduceAll(Key.FeetDigit('5'), Key.Fraction(1, 2), Key.Equals)
        val rejectedAfterEquals = CalcInput.reduce(answered, Key.ClearFraction)
        assertFalse(rejectedAfterEquals.accepted)
        assertEquals(answered.answer, rejectedAfterEquals.state.answer)
    }

    @Test
    fun rejectsInvalidInputs() {
        assertFalse(CalcInput.reduce(CalcState(), Key.FeetDigit('a')).accepted)
        assertFalse(CalcInput.reduce(CalcState(), Key.FeetDigit('\'')).accepted)
        assertFalse(CalcInput.reduce(CalcState(), Key.InchDigit('/')).accepted)
        assertFalse(CalcInput.reduce(CalcState(), Key.InchDigit('"')).accepted)

        // Double decimal points
        val decFeet = feet("2.5").fold(CalcState()) { s, k -> CalcInput.reduce(s, k).state }
        assertFalse(CalcInput.reduce(decFeet, Key.FeetDigit('.')).accepted)
        assertFalse(CalcInput.reduce(decFeet, Key.InchDigit('3')).accepted)

        val decInches = inches("3.5").fold(CalcState()) { s, k -> CalcInput.reduce(s, k).state }
        assertFalse(CalcInput.reduce(decInches, Key.InchDigit('.')).accepted)
        assertFalse(CalcInput.reduce(decInches, Key.FeetDigit('.')).accepted)

        // Decimal inch when fraction is already present
        val withFrac = reduceAll(Key.InchDigit('3'), Key.Fraction(1, 2))
        assertFalse(CalcInput.reduce(withFrac, Key.InchDigit('.')).accepted)
        val onlyFrac = reduceAll(Key.Fraction(1, 2))
        assertFalse(CalcInput.reduce(onlyFrac, Key.InchDigit('.')).accepted)

        // Metric literals cannot be edited by FeetDigit / InchDigit / ClearFraction
        val metric = reduceAll(Key.Char('2'), Key.Char('0'), Key.Text("mm"))
        assertFalse(CalcInput.reduce(metric, Key.FeetDigit('1')).accepted)
        assertFalse(CalcInput.reduce(metric, Key.InchDigit('1')).accepted)
        assertFalse(CalcInput.reduce(metric, Key.ClearFraction).accepted)

        val partialMetric = reduceAll(Key.Char('2'), Key.Char('0'), Key.Char('c'))
        assertFalse(CalcInput.reduce(partialMetric, Key.InchDigit('1')).accepted)
    }

    @Test
    fun arithmeticChainingGroupingAndValueReplacement() {
        // 5' 3 1/2" + 2' 8 1/2" = 8' 0" (96")
        val sumOut = CalcInput.reduce(
            reduceAll(
                Key.FeetDigit('5'), Key.InchDigit('3'), Key.Fraction(1, 2),
                Key.Op(Operator.ADD),
                Key.FeetDigit('2'), Key.InchDigit('8'), Key.Fraction(1, 2),
            ),
            Key.Equals,
        )
        assertTrue(sumOut.accepted)
        assertEquals(Quantity.inches(Rational.of(96)), sumOut.completed!!.result)

        // Continue from answer with * 2 = -> 16'
        val doubled = reduceAll(Key.Op(Operator.MULTIPLY), Key.Char('2'), Key.Equals, start = sumOut.state)
        assertEquals(Quantity.inches(Rational.of(192)), doubled.answer)

        // Typing a FeetDigit after '=' starts fresh
        val fresh = CalcInput.reduce(doubled, Key.FeetDigit('3')).state
        assertNull(fresh.answer)
        assertEquals(listOf(Token.Literal("3'")), fresh.tokens)

        // Typing over recalled Value replaces it
        val recalled = CalcState(tokens = listOf(Token.Value(Quantity.inches(Rational.of(24)))))
        assertEquals(listOf(Token.Literal("7\"")), CalcInput.reduce(recalled, Key.InchDigit('7')).state.tokens)

        // Implicit multiplication after CloseParen and Square
        val parens = reduceAll(
            Key.OpenParen, Key.FeetDigit('2'), Key.Op(Operator.ADD), Key.FeetDigit('1'), Key.CloseParen,
            Key.FeetDigit('2'), Key.Equals,
        )
        assertEquals(
            listOf(
                Token.OpenParen, Token.Literal("2'"), Token.Op(Operator.ADD), Token.Literal("1'"),
                Token.CloseParen, Token.Op(Operator.MULTIPLY), Token.Literal("2'"),
            ),
            parens.tokens,
        )
        // 3' * 2' = 6 sq ft = 864 sq in
        assertEquals(Quantity(Rational.of(864), com.thedirepack.constructioncalc.core.measure.Dimension.AREA), parens.answer)
    }

    @Test
    fun prefersInchesMatchesEnteredUnits() {
        val inchesOnly = reduceAll(
            Key.InchDigit('9'), Key.InchDigit('6'),
            Key.Op(Operator.SUBTRACT),
            Key.InchDigit('5'), Key.InchDigit('5'),
        )
        assertEquals(true, CalcInput.prefersInches(inchesOnly.tokens))

        val mixed = reduceAll(
            Key.FeetDigit('5'), Key.InchDigit('3'),
            Key.Op(Operator.ADD),
            Key.InchDigit('6'),
        )
        assertEquals(false, CalcInput.prefersInches(mixed.tokens))

        val fracOnly = reduceAll(Key.Fraction(7, 16))
        assertEquals(true, CalcInput.prefersInches(fracOnly.tokens))

        val feetAndFrac = reduceAll(Key.FeetDigit('5'), Key.Fraction(1, 2))
        assertEquals(false, CalcInput.prefersInches(feetAndFrac.tokens))
    }
}
