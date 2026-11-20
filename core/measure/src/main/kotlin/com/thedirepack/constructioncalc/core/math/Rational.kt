package com.thedirepack.constructioncalc.core.math

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * An exact fraction. All measurement math runs on these so that values like 3/16" never pick up
 * floating-point error; rounding only happens when a value is formatted for display.
 */
class Rational private constructor(val numerator: BigInteger, val denominator: BigInteger) :
    Comparable<Rational> {

    val signum: Int get() = numerator.signum()
    val isZero: Boolean get() = numerator.signum() == 0
    val isInteger: Boolean get() = denominator == BigInteger.ONE

    operator fun plus(other: Rational) = of(
        numerator * other.denominator + other.numerator * denominator,
        denominator * other.denominator,
    )

    operator fun minus(other: Rational) = this + (-other)

    operator fun times(other: Rational) =
        of(numerator * other.numerator, denominator * other.denominator)

    operator fun div(other: Rational): Rational {
        if (other.isZero) throw ArithmeticException("Division by zero")
        return of(numerator * other.denominator, denominator * other.numerator)
    }

    operator fun unaryMinus() = Rational(numerator.negate(), denominator)

    fun abs() = if (signum < 0) -this else this

    /**
     * Rounds `this * step` to the nearest integer, halves rounding away from zero.
     * `roundToSteps(16)` gives the number of sixteenths closest to this value.
     */
    fun roundToSteps(step: Long): BigInteger {
        val scaled = numerator * BigInteger.valueOf(step)
        val twice = scaled.shiftLeft(1) + denominator * BigInteger.valueOf(scaled.signum().toLong())
        return twice.divide(denominator.shiftLeft(1)) // BigInteger.divide truncates toward zero
    }

    fun toBigDecimal(scale: Int): BigDecimal =
        BigDecimal(numerator).divide(BigDecimal(denominator), scale, RoundingMode.HALF_UP)

    fun toDouble(): Double = toBigDecimal(17).toDouble()

    override fun compareTo(other: Rational): Int =
        (numerator * other.denominator).compareTo(other.numerator * denominator)

    override fun equals(other: Any?) =
        other is Rational && numerator == other.numerator && denominator == other.denominator

    override fun hashCode() = 31 * numerator.hashCode() + denominator.hashCode()

    override fun toString() = if (isInteger) "$numerator" else "$numerator/$denominator"

    companion object {
        val ZERO = Rational(BigInteger.ZERO, BigInteger.ONE)
        val ONE = Rational(BigInteger.ONE, BigInteger.ONE)

        fun of(numerator: BigInteger, denominator: BigInteger = BigInteger.ONE): Rational {
            if (denominator.signum() == 0) throw ArithmeticException("Division by zero")
            if (numerator.signum() == 0) return ZERO
            val gcd = numerator.gcd(denominator)
            var n = numerator / gcd
            var d = denominator / gcd
            if (d.signum() < 0) {
                n = n.negate()
                d = d.negate()
            }
            return Rational(n, d)
        }

        fun of(numerator: Long, denominator: Long = 1) =
            of(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator))

        /** Exact value of a decimal. */
        fun of(value: BigDecimal): Rational = if (value.scale() <= 0) {
            of(value.toBigIntegerExact())
        } else {
            of(value.unscaledValue(), BigInteger.TEN.pow(value.scale()))
        }

        /**
         * The decimal a [Double] prints as (0.1 → 1/10, not the binary approximation). Use for
         * results of formulas that need floating point (roots, logs, trig).
         */
        fun of(value: Double): Rational {
            require(value.isFinite()) { "Not a finite number: $value" }
            return of(BigDecimal.valueOf(value))
        }

        /** Parses [toString] output: "12", "-3", "7/16", "-25/2". Also accepts decimals like "2.5". */
        fun parse(text: String): Rational {
            val slash = text.indexOf('/')
            if (slash < 0) return of(BigDecimal(text))
            return of(BigInteger(text.substring(0, slash)), BigInteger(text.substring(slash + 1)))
        }

        /** Parses an unsigned decimal such as "12", "12.375", ".5" or "5." exactly. */
        fun parseDecimal(text: String): Rational {
            require(text.isNotEmpty() && text != "." && text.count { it == '.' } <= 1 &&
                text.all { it.isDigit() || it == '.' }) { "Not a number: $text" }
            return of(BigDecimal(if (text.endsWith('.')) text.dropLast(1) else text))
        }
    }
}
