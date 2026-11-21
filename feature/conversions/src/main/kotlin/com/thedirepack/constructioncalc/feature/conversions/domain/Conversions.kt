package com.thedirepack.constructioncalc.feature.conversions.domain

import com.thedirepack.constructioncalc.core.math.Rational

/** A value expressed in one unit. */
data class UnitValue(val unit: UnitDef, val value: Rational)

/** Converts between the units of the [UnitTable]. All arithmetic is exact. */
object Conversions {
    /** [value] of [from] expressed in [to]. */
    fun convert(value: Rational, from: UnitDef, to: UnitDef): Rational = to.fromBase(from.toBase(value))

    /** [value] of [from], in the category's base unit (inches, sq in, cu in, lb, °C, psi, W, gpm…). */
    fun toBase(value: Rational, from: UnitDef): Rational = from.toBase(value)

    /** [value] of [from] in every unit of [category], in table order (including [from] itself). */
    fun convertAll(category: ConversionCategory, value: Rational, from: UnitDef): List<UnitValue> {
        val base = from.toBase(value)
        return UnitTable.unitsFor(category).map { UnitValue(it, it.fromBase(base)) }
    }

    /** True if [baseCelsius] (temperature's base) is below absolute zero (−273.15 °C). */
    fun belowAbsoluteZero(baseCelsius: Rational): Boolean = baseCelsius < Rational.parse("-27315/100")
}
