package com.thedirepack.constructioncalc.core.measure

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * Plain decimal numbers as people type and read them, shared by every tool so they all accept and
 * show numbers the same way. (Exact lengths and fractions go through [MeasureFormat] instead.)
 */
object Decimals {
    /**
     * What a user typed in a number field: `,` or `.` as the decimal mark, a leading `-` or `−`.
     * Null for blanks, a lone sign or point, exponents (`1e5`), and anything not finite.
     */
    fun parse(text: String): Double? {
        val clean = text.trim().replace(',', '.').replace('−', '-')
        if (clean.isEmpty() || clean == "-" || clean == "." || clean.any { it == 'e' || it == 'E' }) return null
        return clean.toBigDecimalOrNull()?.toDouble()?.takeIf { it.isFinite() }
    }

    /** At most [maxDecimals] places, trailing zeros removed, `.` as the mark: `23.5`, `30`, `0.96`. */
    fun trim(value: Double, maxDecimals: Int = 2): String {
        if (!value.isFinite()) return value.toString()
        val text = BigDecimal.valueOf(value).setScale(maxDecimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        return if (text == "-0") "0" else text
    }

    /** Exactly [decimals] places, `.` as the mark: `32.0`, `0.3460`. */
    fun fixed(value: Double, decimals: Int): String = String.format(Locale.US, "%.${decimals}f", value)
}
