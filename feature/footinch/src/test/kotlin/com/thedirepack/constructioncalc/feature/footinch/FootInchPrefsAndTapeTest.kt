package com.thedirepack.constructioncalc.feature.footinch

import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.FeetInchStyle
import com.thedirepack.constructioncalc.core.measure.FractionRounding
import com.thedirepack.constructioncalc.core.measure.MeasureFormat
import com.thedirepack.constructioncalc.core.measure.Operator
import com.thedirepack.constructioncalc.core.measure.Precision
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.measure.Token
import com.thedirepack.constructioncalc.core.measure.input.CalcInput
import com.thedirepack.constructioncalc.core.measure.input.CalcState
import com.thedirepack.constructioncalc.core.measure.input.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FootInchPrefsAndTapeTest {

    @Test
    fun footInchPrefKeysAreDefinedAndDistinct() {
        val keys = listOf(
            FootInchPrefs.SHOW_FRACTION_KEYS,
            FootInchPrefs.KEYPAD_LAYOUT,
            FootInchPrefs.SHOW_INLINE_TAPE,
            FootInchPrefs.INPUT_FRACTION_PRECISION,
            FootInchPrefs.SHOW_FUNCTION_STRIP,
            FootInchPrefs.COLOR_BLIND_SHAPES,
        )
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all { it.isNotBlank() })
    }

    @Test
    fun keypadLayoutAndInputFractionPrecisionParseWithSafeDefaults() {
        assertEquals(KeypadLayout.CLASSIC, KeypadLayout.parse(null))
        assertEquals(KeypadLayout.CLASSIC, KeypadLayout.parse("UNKNOWN"))
        assertEquals(KeypadLayout.DUAL_SPLIT, KeypadLayout.parse(KeypadLayout.DUAL_SPLIT.name))

        assertEquals(InputFractionPrecision.MATCH_OUTPUT, InputFractionPrecision.parse(null))
        assertEquals(InputFractionPrecision.MATCH_OUTPUT, InputFractionPrecision.parse("BOGUS"))
        assertEquals(InputFractionPrecision.EIGHTHS, InputFractionPrecision.parse(InputFractionPrecision.EIGHTHS.name))
        assertEquals(InputFractionPrecision.SIXTEENTHS, InputFractionPrecision.parse(InputFractionPrecision.SIXTEENTHS.name))
        assertEquals(InputFractionPrecision.THIRTY_SECONDS, InputFractionPrecision.parse(InputFractionPrecision.THIRTY_SECONDS.name))

        assertEquals(Precision.SIXTY_FOURTH, InputFractionPrecision.MATCH_OUTPUT.resolve(Precision.SIXTY_FOURTH))
        assertEquals(Precision.EIGHTH, InputFractionPrecision.EIGHTHS.resolve(Precision.THIRTY_SECOND))
        assertEquals(Precision.SIXTEENTH, InputFractionPrecision.SIXTEENTHS.resolve(Precision.EIGHTH))
        assertEquals(Precision.THIRTY_SECOND, InputFractionPrecision.THIRTY_SECONDS.resolve(Precision.SIXTEENTH))
    }

    @Test
    fun footInchSettingsDefaultsMatchSpecification() {
        val defaults = FootInchSettings()
        assertEquals(KeypadLayout.CLASSIC, defaults.keypadLayout)
        assertTrue(defaults.showFractionKeys)
        assertTrue(defaults.showInlineTape)
        assertEquals(InputFractionPrecision.MATCH_OUTPUT, defaults.inputFractionPrecision)
        assertTrue(defaults.showFunctionStrip)
        assertFalse(defaults.colorBlindShapes)
        assertEquals(Precision.THIRTY_SECOND, defaults.effectiveFractionPrecision(Precision.THIRTY_SECOND))
    }

    @Test
    fun formatRespectsArchitecturalStyleFractionRoundingAndDualSplitHint() {
        val sample = Quantity.inches(Rational.of(127, 2)) // 5' 3 1/2"
        val arch = AppSettings(feetInchStyle = FeetInchStyle.ARCHITECTURAL)
        assertEquals("5-03-08", FootInchFormat.primary(sample, arch, preferInches = false))

        // 1/3" with 1/16" precision: 5/16 = 0.3125, 6/16 = 0.375
        val third = Quantity.inches(Rational.of(1, 3))
        assertEquals(-1, FootInchFormat.roundingDirection(third, AppSettings(precision = Precision.SIXTEENTH, fractionRounding = FractionRounding.DOWN)))
        assertEquals(1, FootInchFormat.roundingDirection(third, AppSettings(precision = Precision.SIXTEENTH, fractionRounding = FractionRounding.UP)))
        assertEquals("3/8\"", FootInchFormat.primary(third, AppSettings(precision = Precision.SIXTEENTH, fractionRounding = FractionRounding.UP), true))
        assertEquals("5/16\"", FootInchFormat.primary(third, AppSettings(precision = Precision.SIXTEENTH, fractionRounding = FractionRounding.DOWN), true))

        val dualHint = FootInchFormat.hint(AppSettings(), showFractionKeys = true, keypadLayout = KeypadLayout.DUAL_SPLIT)
        assertTrue(dualHint.contains("Feet") && dualHint.contains("Inches"))
    }

    @Test
    fun inlineTapeRecallLabelDeleteAndClipboardRoundTrip() {
        var groups = CalculationTapeOps.startNewGroup(emptyList(), listOf(Token.Literal("4'"), Token.Op(Operator.ADD), Token.Literal("2'"))).first
        groups = CalculationTapeOps.startNewGroup(groups, listOf(Token.Literal("2\""), Token.Op(Operator.ADD), Token.Literal("4\""))).first
        assertEquals(2, groups.size)

        // Tapping a line recalls its exact result into an active expression
        val first = groups[1].finalStep!!
        val second = groups[0].finalStep!!
        var calcState = CalcInput.reduce(CalcState(), Key.Recall(first.result.toQuantity(), first.preferInches)).state
        calcState = CalcInput.reduce(calcState, Key.Op(Operator.ADD)).state
        calcState = CalcInput.reduce(calcState, Key.Recall(second.result.toQuantity(), second.preferInches)).state
        calcState = CalcInput.reduce(calcState, Key.Equals).state
        assertEquals(Quantity.inches(Rational.of(78)), calcState.answer)

        // Copy formats cleanly for the clipboard
        val copied = MeasureFormat.forClipboard(FootInchFormat.primary(first.result.toQuantity(), AppSettings(), first.preferInches))
        assertEquals("6' 0\"", copied)

        // Notes and deletes touch only their own group
        val groupId = groups[1].id
        groups = CalculationTapeOps.setStepLabel(groups, groupId, first.id, "Blocking")
        assertEquals("Blocking", groups.first { it.id == groupId }.finalStep!!.label)
        groups = CalculationTapeOps.removeGroup(groups, groupId)
        assertEquals(1, groups.size)
        assertFalse(groups.any { it.id == groupId })
    }
}
