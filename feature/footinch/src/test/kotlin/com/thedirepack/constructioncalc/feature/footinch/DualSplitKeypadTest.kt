package com.thedirepack.constructioncalc.feature.footinch

import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Dimension
import com.thedirepack.constructioncalc.core.measure.Operator
import com.thedirepack.constructioncalc.core.measure.Precision
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.measure.Token
import com.thedirepack.constructioncalc.core.measure.input.CalcInput
import com.thedirepack.constructioncalc.core.measure.input.CalcState
import com.thedirepack.constructioncalc.core.measure.input.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DualSplitKeypadTest {

    private fun runActions(
        vararg actions: DualSplitAction,
        initial: CalcState = CalcState(),
    ): CalcInput.Outcome {
        var outcome = CalcInput.Outcome(initial)
        for (action in actions) {
            outcome = DualSplitKeyMapper.apply(outcome.state, action)
        }
        return outcome
    }

    @Test
    fun feetInchesAndFractionsComposeInAnyOrder() {
        // Feet -> Inches -> Fraction: 5' 3 1/2" = 63.5"
        val standard = runActions(
            DualSplitAction.Digit(DualPadZone.FEET, '5'),
            DualSplitAction.Digit(DualPadZone.INCHES, '3'),
            DualSplitAction.Fraction(1, 2),
        )
        assertTrue(standard.accepted)
        assertEquals(Quantity.inches(Rational.of(127, 2)), standard.state.result)
        assertEquals(false, CalcInput.prefersInches(standard.state.tokens))

        // Multi-digit feet & inches: 12' 11 3/16" = 155 3/16" = 2483/16"
        val multiDigit = runActions(
            DualSplitAction.Digit(DualPadZone.FEET, '1'),
            DualSplitAction.Digit(DualPadZone.FEET, '2'),
            DualSplitAction.Digit(DualPadZone.INCHES, '1'),
            DualSplitAction.Digit(DualPadZone.INCHES, '1'),
            DualSplitAction.Fraction(3, 16),
        )
        assertTrue(multiDigit.accepted)
        assertEquals(Quantity.inches(Rational.of(2483, 16)), multiDigit.state.result)

        // Inches -> Feet -> Fraction: 8" then 2' then 3/4" = 2' 8 3/4" = 131/4"
        val inchesFirst = runActions(
            DualSplitAction.Digit(DualPadZone.INCHES, '8'),
            DualSplitAction.Digit(DualPadZone.FEET, '2'),
            DualSplitAction.Fraction(3, 4),
        )
        assertTrue(inchesFirst.accepted)
        assertEquals(Quantity.inches(Rational.of(131, 4)), inchesFirst.state.result)

        // Fraction -> Inches -> Feet: 5/8" then 4" then 7' = 7' 4 5/8" = 709/8"
        val fractionFirst = runActions(
            DualSplitAction.Fraction(5, 8),
            DualSplitAction.Digit(DualPadZone.INCHES, '4'),
            DualSplitAction.Digit(DualPadZone.FEET, '7'),
        )
        assertTrue(fractionFirst.accepted)
        assertEquals(Quantity.inches(Rational.of(709, 8)), fractionFirst.state.result)

        // Inches + Fraction only keeps prefersInches = true
        val inchesOnly = runActions(
            DualSplitAction.Digit(DualPadZone.INCHES, '9'),
            DualSplitAction.Fraction(7, 8),
        )
        assertEquals(Quantity.inches(Rational.of(79, 8)), inchesOnly.state.result)
        assertEquals(true, CalcInput.prefersInches(inchesOnly.state.tokens))
    }

    @Test
    fun targetedBackspacesEditOnlyTheirOwnZone() {
        val fiveThreeHalf = runActions(
            DualSplitAction.Digit(DualPadZone.FEET, '5'),
            DualSplitAction.Digit(DualPadZone.INCHES, '3'),
            DualSplitAction.Fraction(1, 2),
        ).state

        // Removing feet leaves 3 1/2"
        val withoutFeet = DualSplitKeyMapper.apply(fiveThreeHalf, DualSplitAction.Backspace(DualPadZone.FEET))
        assertTrue(withoutFeet.accepted)
        assertEquals(Quantity.inches(Rational.of(7, 2)), withoutFeet.state.result)
        assertEquals(true, CalcInput.prefersInches(withoutFeet.state.tokens))

        // Removing fraction leaves 5' 3"
        val withoutFrac = DualSplitKeyMapper.apply(fiveThreeHalf, DualSplitAction.FractionBackspace)
        assertTrue(withoutFrac.accepted)
        assertEquals(Quantity.inches(Rational.of(63)), withoutFrac.state.result)

        // Removing one inch digit from 15' 12 3/4" leaves 15' 1 3/4"
        val fifteenTwelve = runActions(
            DualSplitAction.Digit(DualPadZone.FEET, '1'),
            DualSplitAction.Digit(DualPadZone.FEET, '5'),
            DualSplitAction.Digit(DualPadZone.INCHES, '1'),
            DualSplitAction.Digit(DualPadZone.INCHES, '2'),
            DualSplitAction.Fraction(3, 4),
        ).state
        val editedInches = DualSplitKeyMapper.apply(fifteenTwelve, DualSplitAction.Backspace(DualPadZone.INCHES))
        assertTrue(editedInches.accepted)
        assertEquals(Quantity.inches(Rational.of(181 * 4 + 3, 4)), editedInches.state.result)

        // Backspace on an empty zone is rejected without touching other parts
        val feetOnly = runActions(DualSplitAction.Digit(DualPadZone.FEET, '5')).state
        val rejectedInchBack = DualSplitKeyMapper.apply(feetOnly, DualSplitAction.Backspace(DualPadZone.INCHES))
        assertFalse(rejectedInchBack.accepted)
        assertEquals(feetOnly, rejectedInchBack.state)

        val rejectedFracBack = DualSplitKeyMapper.apply(feetOnly, DualSplitAction.FractionBackspace)
        assertFalse(rejectedFracBack.accepted)
        assertEquals(feetOnly, rejectedFracBack.state)
    }

    @Test
    fun decimalFeetAndInchesNormalizeLeadingDotAndRejectSecondDot() {
        val leadingDotFeet = runActions(
            DualSplitAction.Dot(DualPadZone.FEET),
            DualSplitAction.Digit(DualPadZone.FEET, '5'),
        )
        assertTrue(leadingDotFeet.accepted)
        assertEquals(Quantity.inches(Rational.of(6)), leadingDotFeet.state.result)

        val duplicateDot = DualSplitKeyMapper.apply(leadingDotFeet.state, DualSplitAction.Dot(DualPadZone.FEET))
        assertFalse(duplicateDot.accepted)
        assertEquals(leadingDotFeet.state, duplicateDot.state)

        val decimalInches = runActions(
            DualSplitAction.Dot(DualPadZone.INCHES),
            DualSplitAction.Digit(DualPadZone.INCHES, '7'),
            DualSplitAction.Digit(DualPadZone.INCHES, '5'),
        )
        assertTrue(decimalInches.accepted)
        assertEquals(Quantity.inches(Rational.of(3, 4)), decimalInches.state.result)
    }

    @Test
    fun plainNumberZoneSupportsScalarMultiplyAndDivide() {
        // 5' × 3 = 15' (length, not area)
        val scaledUp = runActions(
            DualSplitAction.Digit(DualPadZone.FEET, '5'),
            DualSplitAction.Op(Operator.MULTIPLY),
            DualSplitAction.Digit(DualPadZone.NUMBER, '3'),
            DualSplitAction.Equals,
        )
        assertTrue(scaledUp.accepted)
        assertNotNull(scaledUp.completed)
        assertEquals(Dimension.LENGTH, scaledUp.completed!!.result.dimension)
        assertEquals(Quantity.feet(Rational.of(15)), scaledUp.completed!!.result)

        // 10' ÷ 4 = 2' 6" (30")
        val divided = runActions(
            DualSplitAction.Digit(DualPadZone.FEET, '1'),
            DualSplitAction.Digit(DualPadZone.FEET, '0'),
            DualSplitAction.Op(Operator.DIVIDE),
            DualSplitAction.Digit(DualPadZone.NUMBER, '4'),
            DualSplitAction.Equals,
        )
        assertEquals(Quantity.inches(Rational.of(30)), divided.completed!!.result)

        // Leading dot in number zone: .5 × 12' = 6'
        val halfOfTwelve = runActions(
            DualSplitAction.Dot(DualPadZone.NUMBER),
            DualSplitAction.Digit(DualPadZone.NUMBER, '5'),
            DualSplitAction.Op(Operator.MULTIPLY),
            DualSplitAction.Digit(DualPadZone.FEET, '1'),
            DualSplitAction.Digit(DualPadZone.FEET, '2'),
            DualSplitAction.Equals,
        )
        assertEquals(Quantity.feet(Rational.of(6)), halfOfTwelve.completed!!.result)

        // Duplicate dot and invalid backspace in number mode are rejected
        val numState = runActions(
            DualSplitAction.Digit(DualPadZone.NUMBER, '2'),
            DualSplitAction.Dot(DualPadZone.NUMBER),
        ).state
        assertFalse(DualSplitKeyMapper.apply(numState, DualSplitAction.Dot(DualPadZone.NUMBER)).accepted)
        val trimmed = DualSplitKeyMapper.apply(numState, DualSplitAction.Backspace(DualPadZone.NUMBER))
        assertTrue(trimmed.accepted)
        assertEquals(listOf(Token.Literal("2")), trimmed.state.tokens)
    }

    @Test
    fun chainsAfterEqualsAndStartsFreshOnNewDigit() {
        // 5' 3 1/2" + 2' 8 1/2" = 8' 0" (96")
        val first = runActions(
            DualSplitAction.Digit(DualPadZone.FEET, '5'),
            DualSplitAction.Digit(DualPadZone.INCHES, '3'),
            DualSplitAction.Fraction(1, 2),
            DualSplitAction.Op(Operator.ADD),
            DualSplitAction.Digit(DualPadZone.FEET, '2'),
            DualSplitAction.Digit(DualPadZone.INCHES, '8'),
            DualSplitAction.Fraction(1, 2),
            DualSplitAction.Equals,
        )
        assertEquals(Quantity.inches(Rational.of(96)), first.state.answer)

        // Operator continues from answer: + 6" = 8' 6" (102")
        val chained = runActions(
            DualSplitAction.Op(Operator.ADD),
            DualSplitAction.Digit(DualPadZone.INCHES, '6'),
            DualSplitAction.Equals,
            initial = first.state,
        )
        assertEquals(Quantity.inches(Rational.of(102)), chained.state.answer)

        // Tapping a feet digit right after `=` starts a fresh expression
        val fresh = DualSplitKeyMapper.apply(chained.state, DualSplitAction.Digit(DualPadZone.FEET, '4'))
        assertTrue(fresh.accepted)
        assertNull(fresh.state.answer)
        assertEquals(Quantity.feet(Rational.of(4)), fresh.state.result)
    }

    @Test
    fun fractionKeysForOrdersTiersFromCoarseToFine() {
        val eighths = DualSplitKeyMapper.fractionKeysFor(Precision.EIGHTH)
        assertEquals(listOf(1 to 8, 1 to 4, 3 to 8, 1 to 2, 5 to 8, 3 to 4, 7 to 8), eighths)

        val sixteenths = DualSplitKeyMapper.fractionKeysFor(Precision.SIXTEENTH)
        assertEquals(15, sixteenths.size)
        assertEquals(eighths, sixteenths.take(7))
        assertEquals(1 to 16, sixteenths[7])
        assertEquals(15 to 16, sixteenths.last())

        val thirtySeconds = DualSplitKeyMapper.fractionKeysFor(Precision.THIRTY_SECOND)
        assertEquals(31, thirtySeconds.size)
        assertEquals(eighths, thirtySeconds.take(7))
        assertEquals(1 to 16, thirtySeconds[7])
        assertEquals(15 to 16, thirtySeconds[14])
        assertEquals(1 to 32, thirtySeconds[15])
        assertEquals(31 to 32, thirtySeconds.last())
    }
}
