package com.thedirepack.constructioncalc.feature.footinch

import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.FeetInchStyle
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.measure.StairCalculator
import com.thedirepack.constructioncalc.core.measure.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StairHelperTest {

    private fun inch(n: Long, d: Long = 1) = Quantity.inches(Rational.of(n, d))

    @Test
    fun emptyAndInvalidInputsProduceClearStates() {
        assertEquals(StairHelperEval.Empty, StairHelperState.evaluate(StairHelperInput()))

        val zeroRise = StairHelperState.evaluate(StairHelperInput(totalRise = inch(0)))
        assertTrue(zeroRise is StairHelperEval.Invalid)

        val numberRise = StairHelperState.evaluate(StairHelperInput(totalRise = Quantity.number(Rational.of(100))))
        assertTrue(numberRise is StairHelperEval.Invalid)

        val zeroTread = StairHelperState.evaluate(StairHelperInput(totalRise = inch(96), treadRun = inch(0)))
        assertTrue(zeroTread is StairHelperEval.Invalid)

        val badOverrideText = StairHelperState.evaluate(StairHelperInput(totalRise = inch(96), riserCountOverrideText = "abc"))
        assertTrue(badOverrideText is StairHelperEval.Invalid)

        val zeroOverride = StairHelperState.evaluate(StairHelperInput(totalRise = inch(96), riserCountOverrideText = "0"))
        assertTrue(zeroOverride is StairHelperEval.Invalid)

        val overMaxOverride = StairHelperState.evaluate(
            StairHelperInput(totalRise = inch(96), riserCountOverrideText = (StairCalculator.MAX_RISERS + 1).toString()),
        )
        assertTrue(overMaxOverride is StairHelperEval.Invalid)
    }

    @Test
    fun solvesWithDefaultSevenAndHalfRiserAndTenInchTread() {
        val eval = StairHelperState.evaluate(StairHelperInput(totalRise = inch(105)))
        assertTrue(eval is StairHelperEval.Solved)
        val layout = (eval as StairHelperEval.Solved).layout

        assertEquals(14, layout.riserCount)
        assertEquals(inch(15, 2), layout.riserHeight)
        assertEquals(13, layout.treadCount)
        assertEquals(inch(130), layout.totalRun)
        assertEquals(inch(25, 2), layout.stepDiagonal)
        assertEquals(36.8699, layout.angleDegrees, 1e-3)
        assertTrue(layout.isIrcCompliant)
    }

    @Test
    fun preservesExactRationalRiserHeightAndRespectsOverride() {
        val autoEval = StairHelperState.evaluate(StairHelperInput(totalRise = Quantity.feet(Rational.of(9))))
        val autoLayout = (autoEval as StairHelperEval.Solved).layout
        assertEquals(14, autoLayout.riserCount)
        assertEquals(Rational.of(54, 7), autoLayout.riserHeightInches)
        assertTrue(autoLayout.isIrcCompliant)

        val overrideEval = StairHelperState.evaluate(
            StairHelperInput(totalRise = Quantity.feet(Rational.of(9)), riserCountOverrideText = "15"),
        )
        val overrideLayout = (overrideEval as StairHelperEval.Solved).layout
        assertEquals(15, overrideLayout.riserCount)
        assertEquals(14, overrideLayout.treadCount)
        assertEquals(Rational.of(36, 5), overrideLayout.riserHeightInches)
    }

    @Test
    fun surfacesIrcNotesWhenRiserOrTreadViolatesLimits() {
        val eval = StairHelperState.evaluate(
            StairHelperInput(
                totalRise = inch(96),
                targetRiser = inch(8),
                treadRun = inch(19, 2),
            ),
        )
        val layout = (eval as StairHelperEval.Solved).layout
        assertFalse(layout.isIrcCompliant)
        assertFalse(layout.riserWithinIrcMax)
        assertFalse(layout.treadWithinIrcMin)
        assertTrue(layout.ircNotes.any { it.contains("7-3/4\"") })
        assertTrue(layout.ircNotes.any { it.contains("10\"") })
    }

    @Test
    fun formatSummaryRespectsAppSettingsStyles() {
        val layout = (StairHelperState.evaluate(StairHelperInput(totalRise = inch(105))) as StairHelperEval.Solved).layout

        val imperial = StairHelperState.formatSummary(layout, AppSettings())
        assertTrue(imperial.contains("Riser count: 14"))
        assertTrue(imperial.contains("7 1/2\""))
        assertTrue(imperial.contains("10' 10\""))

        val arch = StairHelperState.formatSummary(layout, AppSettings(feetInchStyle = FeetInchStyle.ARCHITECTURAL))
        assertTrue(arch.contains("10-10-00"))

        val metric = StairHelperState.formatSummary(layout, AppSettings(unitSystem = UnitSystem.METRIC))
        assertTrue(metric.contains("191 mm") || metric.contains("190.5 mm"))
        assertTrue(metric.contains("3.302 m"))
    }
}
