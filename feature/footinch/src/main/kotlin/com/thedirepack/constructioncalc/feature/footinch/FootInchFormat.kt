package com.thedirepack.constructioncalc.feature.footinch

import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.measure.ExpressionText
import com.thedirepack.constructioncalc.core.measure.MeasureFormat
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.measure.Token
import com.thedirepack.constructioncalc.core.ui.isMetric
import com.thedirepack.constructioncalc.core.ui.lengthFormat

/** Pure text builders for Foot-Inch, in the user's unit system (so they test in both). */
internal object FootInchFormat {
    fun expression(tokens: List<Token>, settings: AppSettings, preferInches: Boolean?): String =
        ExpressionText.format(tokens, settings.lengthFormat(preferInches))

    fun primary(q: Quantity, settings: AppSettings, preferInches: Boolean?): String =
        MeasureFormat.primary(q, settings.lengthFormat(preferInches))

    fun conversions(q: Quantity, settings: AppSettings, preferInches: Boolean?): List<MeasureFormat.Conversion> =
        MeasureFormat.conversions(q, settings.lengthFormat(preferInches))

    /** -1/0/1 for a rounded fraction; metric lengths are shown at the chosen resolution, so no hint. */
    fun roundingDirection(q: Quantity, settings: AppSettings): Int =
        if (settings.isMetric) 0 else MeasureFormat.roundingDirection(q, settings.lengthFormat())

    fun hint(
        settings: AppSettings,
        showFractionKeys: Boolean,
        keypadLayout: KeypadLayout = KeypadLayout.CLASSIC,
    ): String = when {
        settings.isMetric -> "Try  1 m 20 cm  for  1.2 m"
        keypadLayout == KeypadLayout.DUAL_SPLIT -> "Tap Feet, Inches, and Fractions to enter a length"
        showFractionKeys -> "Try  5  '  3  1/2  for  5' 3 1/2\""
        else -> "Try  5 ' 3 \" 1 / 2  for  5' 3 1/2\""
    }
}
