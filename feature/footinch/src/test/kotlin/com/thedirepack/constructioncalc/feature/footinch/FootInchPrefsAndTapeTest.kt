package com.thedirepack.constructioncalc.feature.footinch

import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.FeetInchStyle
import com.thedirepack.constructioncalc.core.measure.FractionRounding
import com.thedirepack.constructioncalc.core.measure.MeasureFormat
import com.thedirepack.constructioncalc.core.measure.Operator
import com.thedirepack.constructioncalc.core.measure.Precision
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.measure.input.CalcInput
import com.thedirepack.constructioncalc.core.measure.input.CalcState
import com.thedirepack.constructioncalc.core.measure.input.Key
import com.thedirepack.constructioncalc.core.model.stored
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
    fun inlineTapeRecallRenameDeleteAndClipboardRoundTrip() {
        val sixFeet = Quantity.inches(Rational.of(72))
        val sixInches = Quantity.inches(Rational.of(6))

        var tape = emptyList<HistoryEntry>()
        tape = HistoryTapeOps.add(tape, HistoryEntry("4' + 2'", sixFeet.stored(), preferInches = false))
        tape = HistoryTapeOps.add(tape, HistoryEntry("2\" + 4\"", sixInches.stored(), preferInches = true))
        assertEquals(2, tape.size)

        // Tap row recalls its stored quantity and preferInches into an active expression
        val firstRow = tape[0]
        var calcState = CalcInput.reduce(CalcState(), Key.Recall(firstRow.result.toQuantity(), firstRow.preferInches)).state
        calcState = CalcInput.reduce(calcState, Key.Op(Operator.ADD)).state
        val secondRow = tape[1]
        calcState = CalcInput.reduce(calcState, Key.Recall(secondRow.result.toQuantity(), secondRow.preferInches)).state
        calcState = CalcInput.reduce(calcState, Key.Equals).state
        assertEquals(Quantity.inches(Rational.of(78)), calcState.answer)

        // Copy formats cleanly for clipboard
        val copied = MeasureFormat.forClipboard(FootInchFormat.primary(secondRow.result.toQuantity(), AppSettings(), secondRow.preferInches))
        assertEquals("6' 0\"", copied)

        // Rename & delete preserve remaining entries
        tape = HistoryTapeOps.setLabel(tape, firstRow.id, "Blocking")
        assertEquals("Blocking", tape.first { it.id == firstRow.id }.label)
        tape = HistoryTapeOps.remove(tape, firstRow.id)
        assertEquals(1, tape.size)
        assertEquals(secondRow.id, tape.single().id)
    }
}
