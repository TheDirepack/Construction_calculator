package com.thedirepack.constructioncalc.feature.conversions.domain

import com.thedirepack.constructioncalc.core.math.Rational
import java.math.MathContext
import java.math.RoundingMode

/** Formats converted values compactly: a fixed number of significant digits, no grouping. */
object NumberText {
    /**
     * [value] rounded to [digits] significant digits with trailing zeros removed, e.g.
     * `63360`, `0.0393701`, `6.213712E-7`. Negatives use the `−` sign the rest of the app uses
     * (the clipboard copy turns it back into `-`).
     */
    fun significant(value: Rational, digits: Int = 7): String {
        if (value.isZero) return "0"
        val rounded = value.toBigDecimal(60).round(MathContext(digits, RoundingMode.HALF_UP)).stripTrailingZeros()
        if (rounded.signum() == 0) return "0"
        val leadingDigitExponent = rounded.precision() - rounded.scale() - 1
        val text = if (leadingDigitExponent in -5..11) rounded.toPlainString() else rounded.toString()
        return if (text.startsWith("-")) "−" + text.substring(1) else text
    }
}
