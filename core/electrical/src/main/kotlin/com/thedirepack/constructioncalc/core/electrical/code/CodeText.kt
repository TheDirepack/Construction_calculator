package com.thedirepack.constructioncalc.core.electrical.code

import com.thedirepack.constructioncalc.core.measure.Decimals
import com.thedirepack.constructioncalc.core.measure.UnitSystem
import kotlin.math.roundToInt

/**
 * Text for code values, so notes and labels are built from [Code.book] instead of spelling a number:
 * `codePercent(values.continuousLoadFactor)` is "125 %".
 */

/** A multiplier as a percent: 1.25 is "125 %". */
fun codePercent(factor: Double, spaced: Boolean = true): String = "${(factor * 100).roundToInt()}${if (spaced) " " else ""}%"

/** A percent value as text: 60 is "60 %", 2.5 is "2.5 %". */
fun codePercentValue(percent: Number, spaced: Boolean = true): String = "${codeNumber(percent.toDouble())}${if (spaced) " " else ""}%"

/** A code number without a trailing ".0": 1.15 is "1.15", 24.0 is "24". */
fun codeNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

/**
 * A length the code prints in both systems, in the app's system: imperial `24 in.` or `6-1/2 ft`,
 * metric `600 mm` or `2 m`. Both values come from the edition (the code's metric figure is its own
 * rounding, not a conversion).
 */
fun codeLength(inches: Number, mm: Number, units: UnitSystem): String {
    if (units == UnitSystem.METRIC) {
        val v = mm.toDouble()
        return if (v >= 1000.0) "${Decimals.trim(v / 1000.0, 2)} m" else "${codeNumber(v)} mm"
    }
    val total = inches.toInt()
    if (total < 60) return "$total in."
    val feet = total / 12
    return when (val rem = total % 12) {
        0 -> "$feet ft"
        6 -> "$feet-1/2 ft"
        else -> "$feet ft $rem in."
    }
}
