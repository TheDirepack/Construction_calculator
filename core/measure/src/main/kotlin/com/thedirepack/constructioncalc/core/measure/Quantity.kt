package com.thedirepack.constructioncalc.core.measure

import com.thedirepack.constructioncalc.core.math.Rational

/** What kind of value a [Quantity] is. [power] is the exponent on inches. */
enum class Dimension(val power: Int) {
    NUMBER(0),
    LENGTH(1),
    AREA(2),
    VOLUME(3);

    companion object {
        fun ofPower(power: Int): Dimension? = entries.firstOrNull { it.power == power }
    }
}

/**
 * A value with a dimension. The value is always stored in inches raised to the dimension's power:
 * lengths in inches, areas in square inches, volumes in cubic inches, plain numbers as-is.
 */
data class Quantity(val value: Rational, val dimension: Dimension) {

    companion object {
        fun number(value: Rational) = Quantity(value, Dimension.NUMBER)
        fun inches(value: Rational) = Quantity(value, Dimension.LENGTH)
        fun feet(value: Rational) = Quantity(value * Units.INCHES_PER_FOOT, Dimension.LENGTH)
    }
}

object Units {
    val INCHES_PER_FOOT: Rational = Rational.of(12)
    val INCHES_PER_YARD: Rational = Rational.of(36)
    val MM_PER_INCH: Rational = Rational.of(254, 10) // exact by definition
    val CUBIC_INCHES_PER_US_GALLON: Rational = Rational.of(231)
}

/** Errors a calculation can produce. [message] is user-facing. */
sealed class CalcException(message: String) : Exception(message) {
    class Syntax(message: String = "Check the entry") : CalcException(message)
    class Incomplete : CalcException("Incomplete entry")
    class DivideByZero : CalcException("Can't divide by zero")
    class UnitMismatch(message: String) : CalcException(message)
}
