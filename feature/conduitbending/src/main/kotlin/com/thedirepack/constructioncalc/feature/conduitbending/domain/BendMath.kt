package com.thedirepack.constructioncalc.feature.conduitbending.domain

import com.thedirepack.constructioncalc.core.math.Rational
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Trig helpers for bend angles (degrees) and conversion of the results to exact [Rational]s. */
object BendMath {
    /** Angles a hand bender is normally used at (degrees). */
    val STANDARD_ANGLES: List<Double> = listOf(10.0, 22.5, 30.0, 45.0, 60.0)

    /** True for a bend angle these formulas support (strictly between 0° and 90°). */
    fun validAngle(deg: Double) = deg.isFinite() && deg > 0.0 && deg < 90.0

    /** Cosecant: the offset "multiplier" (distance between marks per unit of depth). */
    fun csc(deg: Double) = 1.0 / sin(Math.toRadians(deg))

    /** Cotangent: the run an angled leg covers per unit of rise. */
    fun cot(deg: Double) = 1.0 / tan(Math.toRadians(deg))

    /** tan(θ/2): the shrink per unit of depth for an offset bent at θ. Exact, not a field constant. */
    fun tanHalf(deg: Double) = tan(Math.toRadians(deg / 2.0))

    /**
     * A floating-point result as a [Rational], rounded to 10 decimals first so that values like
     * 1/sin 30° come out as exactly 2 rather than 2.0000000000000004.
     */
    fun tidy(x: Double): Rational {
        require(x.isFinite()) { "Not a finite number: $x" }
        return Rational.of(BigDecimal.valueOf(x).setScale(10, RoundingMode.HALF_UP))
    }

    /** Square root, tidied to 10 decimals (so a 3-4-5 triangle gives exactly 5). */
    fun sqrt(x: Rational): Rational {
        require(x.signum >= 0) { "Square root of a negative number" }
        return tidy(sqrt(x.toDouble()))
    }

    /** True when two angles match to within a hundredth of a degree. */
    fun sameAngle(a: Double, b: Double) = kotlin.math.abs(a - b) < 0.01
}
