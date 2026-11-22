package com.thedirepack.constructioncalc.core.electrical

import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

/**
 * How the cables lie in the conduit, which sets the weight correction factor W and the sidewall
 * bearing pressure in a bend.
 *
 * - [SINGLE]: one cable, including a multiconductor cable under a common jacket.
 * - [TRIANGULAR]: three cables, one riding on two (also used for two cables, see [PullFormulas.weightCorrection]).
 * - [CRADLED]: three cables side by side, the middle one lowest.
 * - [COMPLEX]: four or more cables (diamond or a random lay).
 */
enum class CableConfiguration(val label: String) {
    SINGLE("Single cable"),
    TRIANGULAR("Triangular"),
    CRADLED("Cradled"),
    COMPLEX("Four or more (diamond)"),
}

/**
 * Cable-pulling equations. Pure; units are pounds, feet and radians unless a name says otherwise.
 *
 * Sources (each formula below says which it uses):
 * - IEEE Std 1185 (Recommended Practice for Cable Installation in Generating Stations and
 *   Industrial Facilities), pulling-tension annex: straight, inclined and bend equations, the
 *   weight correction factor, jam ratio and clearance.
 * - Southwire, "Maximum Sidewall Pressure for Electrical Conductors" (CableTech flyer, 2021), p. 1:
 *   the three sidewall pressure equations, with T the tension coming out of the bend in pounds and
 *   R the bend radius in feet.
 * - Southwire, "Maximum Pulling Tension for Electrical Conductors" (CableTech flyer, Edition 2,
 *   2024), pp. 1-3: T = S × cmil × n (× 0.8 when n > 3).
 *
 * The vertical-bend equations are derived here from the same friction model as the horizontal ones
 * (see [sagBend] and [humpBend]) and are checked numerically in the tests.
 */
object PullFormulas {
    /**
     * Weight correction factor W for [configuration], from the conduit inside diameter [conduitId]
     * and the cable outside diameter [cableOd] (same units), with x = d / (D − d) (IEEE 1185):
     * - single cable: W = 1
     * - triangular: W = 1 / √(1 − x²)
     * - cradled: W = 1 + (4/3) x²
     * - four or more (diamond / complex): W = 1 + 2 x², but never below [COMPLEX_MIN_W] (1.4). In roomy
     *   conduit the formula drops toward 1, while Southwire's SIMpull workbook uses a fixed 1.4 for
     *   four or more cables; the larger value is used so tension isn't understated.
     *
     * Two cables are treated as triangular (the pair bears on the wall like the lower two of a
     * triangle): an assumption, stated in the pull report. Null when the cables can't fit (D ≤ d, or
     * a triangle wouldn't fit, x ≥ 1).
     */
    fun weightCorrection(configuration: CableConfiguration, conduitId: Double, cableOd: Double): Double? {
        if (configuration == CableConfiguration.SINGLE) return 1.0
        if (conduitId <= cableOd || cableOd <= 0.0) return null
        val x = cableOd / (conduitId - cableOd)
        return when (configuration) {
            CableConfiguration.SINGLE -> 1.0
            CableConfiguration.TRIANGULAR -> if (x >= 1.0) null else 1.0 / sqrt(1.0 - x * x)
            CableConfiguration.CRADLED -> 1.0 + 4.0 / 3.0 * x * x
            CableConfiguration.COMPLEX -> maxOf(1.0 + 2.0 * x * x, COMPLEX_MIN_W)
        }
    }

    /**
     * The configuration to assume for [count] equal cables of [cableOd] in [conduitId]:
     * one cable is [CableConfiguration.SINGLE], four or more [CableConfiguration.COMPLEX]. For two or
     * three, a ratio D/d below 2.5 leaves no room for three side by side, so they ride
     * triangular; from 2.5 up they can lie cradled, which gives the larger W and is assumed
     * (IEEE 1185 guidance on configuration versus D/d).
     */
    fun configuration(count: Int, conduitId: Double, cableOd: Double): CableConfiguration = when {
        count <= 1 -> CableConfiguration.SINGLE
        count >= 4 -> CableConfiguration.COMPLEX
        count == 2 -> CableConfiguration.TRIANGULAR
        cableOd > 0 && conduitId / cableOd < CRADLED_MIN_RATIO -> CableConfiguration.TRIANGULAR
        else -> CableConfiguration.CRADLED
    }

    /** Minimum W for four or more cables: the fixed value Southwire's SIMpull workbook uses. */
    const val COMPLEX_MIN_W = 1.4

    /** D/d below which three cables can't lie cradled (IEEE 1185). */
    const val CRADLED_MIN_RATIO = 2.5

    /**
     * Tension after a straight section of [lengthFt], entering at [tensionIn] (IEEE 1185):
     * level: T₂ = T₁ + W μ w L; on an incline of [slopeRad] (positive pulling uphill):
     * T₂ = T₁ + w L (sin θ + W μ cos θ). The level case is the incline with θ = 0. Pulling downhill
     * can lower the tension; it never goes below zero (the cable would run on its own).
     *
     * @param weightLbPerFt total weight of all the cables, lb/ft.
     */
    fun straight(tensionIn: Double, weightLbPerFt: Double, lengthFt: Double, friction: Double, w: Double, slopeRad: Double = 0.0): Double {
        val out = tensionIn + weightLbPerFt * lengthFt * (sin(slopeRad) + w * friction * cos(slopeRad))
        return out.coerceAtLeast(0.0)
    }

    /**
     * Tension leaving a horizontal bend of [angleRad] and radius [radiusFt] (IEEE 1185, exact form,
     * cable weight included): T₂ = T₁ cosh(Wμθ) + √(T₁² + (wR)²) sinh(Wμθ).
     */
    fun horizontalBend(tensionIn: Double, weightLbPerFt: Double, radiusFt: Double, angleRad: Double, friction: Double, w: Double): Double {
        val k = w * friction * angleRad
        val wr = weightLbPerFt * radiusFt
        return tensionIn * cosh(k) + sqrt(tensionIn * tensionIn + wr * wr) * sinh(k)
    }

    /** The common simplified bend equation T₂ = T₁ e^(Wμθ), which ignores cable weight (shown for reference). */
    fun bendSimplified(tensionIn: Double, angleRad: Double, friction: Double, w: Double): Double =
        tensionIn * exp(w * friction * angleRad)

    /**
     * Tension leaving a vertical bend that is concave up (a sag): the path's slope rises from
     * [entrySlopeRad] to [entrySlopeRad] + [angleRad] along the pull.
     *
     * Derived from dT/dφ = k(T − wR cos α) + wR sin α with k = Wμ and α = α₁ + φ: the cable is
     * pulled against the inner (upper) wall, so the wall force per foot is T/R − w cos α. Solving
     * the linear equation gives, with α₂ = α₁ + θ:
     *
     * T₂ = T₁e^(kθ) − wR/(1+k²) · [2k sin α₂ + (1−k²) cos α₂ − e^(kθ)(2k sin α₁ + (1−k²) cos α₁)]
     *
     * the concave-up form in IEEE 1185 when α₁ = 0 (level into a riser) or α₂ = 0 (a drop into level).
     * Valid while the tension holds the cable off the floor of the bend (T > wR cos α), which is the
     * usual case in a pull.
     */
    fun sagBend(tensionIn: Double, weightLbPerFt: Double, radiusFt: Double, angleRad: Double, friction: Double, w: Double, entrySlopeRad: Double): Double {
        val k = w * friction
        val wr = weightLbPerFt * radiusFt
        val a1 = entrySlopeRad
        val a2 = entrySlopeRad + angleRad
        val e = exp(k * angleRad)
        val out = tensionIn * e - wr / (1 + k * k) * (2 * k * sin(a2) + (1 - k * k) * cos(a2) - e * (2 * k * sin(a1) + (1 - k * k) * cos(a1)))
        return out.coerceAtLeast(0.0)
    }

    /**
     * Tension leaving a vertical bend that is concave down (a hump): the slope falls from
     * [entrySlopeRad] to [entrySlopeRad] − [angleRad]. Tension and weight both press the cable on
     * the inner (lower) wall: dT/dφ = k(T + wR cos α) + wR sin α with α = α₁ − φ, which gives
     *
     * T₂ = T₁e^(kθ) + wR/(1+k²) · [(1−k²) cos α₂ − 2k sin α₂ − e^(kθ)((1−k²) cos α₁ − 2k sin α₁)]
     *
     * (the concave-down form in IEEE 1185 for α₁ = 0 or α₂ = 0).
     */
    fun humpBend(tensionIn: Double, weightLbPerFt: Double, radiusFt: Double, angleRad: Double, friction: Double, w: Double, entrySlopeRad: Double): Double {
        val k = w * friction
        val wr = weightLbPerFt * radiusFt
        val a1 = entrySlopeRad
        val a2 = entrySlopeRad - angleRad
        val e = exp(k * angleRad)
        val out = tensionIn * e + wr / (1 + k * k) * ((1 - k * k) * cos(a2) - 2 * k * sin(a2) - e * ((1 - k * k) * cos(a1) - 2 * k * sin(a1)))
        return out.coerceAtLeast(0.0)
    }

    /**
     * Sidewall bearing pressure in a bend, lb/ft, from the tension leaving the bend [tensionOut] (lb)
     * and the radius [radiusFt] (feet), Southwire sidewall pressure flyer (2021) p. 1:
     * - one cable (or a multiconductor cable under a common jacket): SP = T / R
     * - three cables, triangular: SP = W T / (2R)
     * - three cables, cradled: SP = (3W − 2) T / (3R)
     *
     * Two cables use the triangular form (they share the wall like the lower pair of a triangle) and
     * four or more the cradled form (the most heavily loaded cable is nested between two others):
     * assumptions stated in the pull report.
     */
    fun sidewallPressure(configuration: CableConfiguration, w: Double, tensionOut: Double, radiusFt: Double): Double = when (configuration) {
        CableConfiguration.SINGLE -> tensionOut / radiusFt
        CableConfiguration.TRIANGULAR -> w * tensionOut / (2 * radiusFt)
        CableConfiguration.CRADLED, CableConfiguration.COMPLEX -> (3 * w - 2) * tensionOut / (3 * radiusFt)
    }

    /** Conduit ovality allowance in bends for the jam ratio (IEEE 1185): 1.05 × D / d. */
    const val OVALITY = 1.05

    /** How the jam ratio is written wherever it is shown, so every tool labels it the same way. */
    const val OVALITY_LABEL = "1.05 × D/d"

    /** Jam ratio 1.05 D/d (IEEE 1185); jamming of three cables is possible from 2.8 to 3.2. */
    fun jamRatio(conduitId: Double, cableOd: Double): Double = OVALITY * conduitId / cableOd

    fun inJamRange(ratio: Double): Boolean = ratio >= ConduitFill.JAM_LOW - 1e-9 && ratio <= ConduitFill.JAM_HIGH + 1e-9

    /**
     * Clearance between the top of the cables and the top of the conduit, inches (IEEE 1185):
     * - one cable: D − d
     * - two cables side by side: D/2 − d/2 + (D − d)/2 · √(1 − (d/(D−d))²)
     * - three or more, triangular: D/2 − 1.366 d + (D − d)/2 · √(1 − (d/(D−d))²) (one cable riding on two)
     *
     * The three-cable form is used for any three or more cables: one cable riding in the cradle of
     * two is the arrangement with the least room above it, so it's the one to check. Recommend at
     * least 1/2 in., never less than 1/4 in. ([CLEARANCE_RECOMMENDED_IN], [CLEARANCE_MINIMUM_IN]).
     * Null when the cables don't fit.
     */
    fun clearance(count: Int, conduitId: Double, cableOd: Double): Double? {
        if (cableOd <= 0 || conduitId <= cableOd) return null
        if (count <= 1) return conduitId - cableOd
        val x = cableOd / (conduitId - cableOd)
        if (x >= 1.0) return null
        val root = (conduitId - cableOd) / 2 * sqrt(1 - x * x)
        return if (count == 2) conduitId / 2 - cableOd / 2 + root else conduitId / 2 - 1.366 * cableOd + root
    }

    const val CLEARANCE_RECOMMENDED_IN = 0.5
    const val CLEARANCE_MINIMUM_IN = 0.25
}
