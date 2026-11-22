package com.thedirepack.constructioncalc.core.measure

import com.thedirepack.constructioncalc.core.math.Rational
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Pure right-triangle solver for field layout (squaring corners, stairs, rafters, conduit offsets).
 *
 * All lengths are exact [Quantity] values with [Dimension.LENGTH] (stored in inches).
 * [RightTriangle.angleDegrees] is the angle between [RightTriangle.run] and [RightTriangle.diagonal].
 */
object Geometry {

    data class RightTriangle(
        val rise: Quantity,
        val run: Quantity,
        val diagonal: Quantity,
        /** Angle opposite [rise], in degrees (0 < angle < 90). */
        val angleDegrees: Double,
        /** Roof/stair pitch: inches of rise per 12 inches of run (e.g. 4.0 means 4:12). */
        val risePerFoot: Double,
        /** Grade/slope percentage: (rise / run) * 100. */
        val slopePercent: Double,
    ) {
        /** Complementary angle opposite [run], in degrees. */
        val complementaryDegrees: Double get() = 90.0 - angleDegrees
    }

    /**
     * Solves a right triangle given any 2 positive inputs among [rise], [run], [diagonal], and
     * [angleDegrees]. Returns null when the input count is not 2 or the values cannot form a valid
     * right triangle (non-positive, non-LENGTH, diagonal <= leg, or angle outside (0, 90)).
     */
    fun solveRightTriangle(
        rise: Quantity? = null,
        run: Quantity? = null,
        diagonal: Quantity? = null,
        angleDegrees: Double? = null,
    ): RightTriangle? {
        val provided = listOfNotNull(rise, run, diagonal, angleDegrees).size
        if (provided != 2) return null

        if (rise != null && !isPositiveLength(rise)) return null
        if (run != null && !isPositiveLength(run)) return null
        if (diagonal != null && !isPositiveLength(diagonal)) return null
        if (angleDegrees != null && (!angleDegrees.isFinite() || angleDegrees <= 0.0 || angleDegrees >= 90.0)) {
            return null
        }

        val solvedRise: Quantity
        val solvedRun: Quantity
        val solvedDiag: Quantity
        val solvedAngle: Double

        when {
            rise != null && run != null -> {
                solvedRise = rise
                solvedRun = run
                val diagSq = rise.value * rise.value + run.value * run.value
                solvedDiag = Quantity.inches(Evaluator.sqrtRational(diagSq))
                solvedAngle = Math.toDegrees(atan2(rise.value.toDouble(), run.value.toDouble()))
            }
            rise != null && diagonal != null -> {
                if (diagonal.value <= rise.value) return null
                solvedRise = rise
                solvedDiag = diagonal
                val runSq = diagonal.value * diagonal.value - rise.value * rise.value
                solvedRun = Quantity.inches(Evaluator.sqrtRational(runSq))
                solvedAngle = Math.toDegrees(asin((rise.value / diagonal.value).toDouble().coerceIn(-1.0, 1.0)))
            }
            run != null && diagonal != null -> {
                if (diagonal.value <= run.value) return null
                solvedRun = run
                solvedDiag = diagonal
                val riseSq = diagonal.value * diagonal.value - run.value * run.value
                solvedRise = Quantity.inches(Evaluator.sqrtRational(riseSq))
                solvedAngle = Math.toDegrees(acos((run.value / diagonal.value).toDouble().coerceIn(-1.0, 1.0)))
            }
            rise != null && angleDegrees != null -> {
                val rad = Math.toRadians(angleDegrees)
                val riseD = rise.value.toDouble()
                solvedRise = rise
                solvedRun = Quantity.inches(Rational.of(riseD / tan(rad)))
                solvedDiag = Quantity.inches(Rational.of(riseD / sin(rad)))
                solvedAngle = angleDegrees
            }
            run != null && angleDegrees != null -> {
                val rad = Math.toRadians(angleDegrees)
                val runD = run.value.toDouble()
                solvedRun = run
                solvedRise = Quantity.inches(Rational.of(runD * tan(rad)))
                solvedDiag = Quantity.inches(Rational.of(runD / cos(rad)))
                solvedAngle = angleDegrees
            }
            diagonal != null && angleDegrees != null -> {
                val rad = Math.toRadians(angleDegrees)
                val diagD = diagonal.value.toDouble()
                solvedDiag = diagonal
                solvedRise = Quantity.inches(Rational.of(diagD * sin(rad)))
                solvedRun = Quantity.inches(Rational.of(diagD * cos(rad)))
                solvedAngle = angleDegrees
            }
            else -> return null
        }

        val ratio = (solvedRise.value / solvedRun.value).toDouble()
        return RightTriangle(
            rise = solvedRise,
            run = solvedRun,
            diagonal = solvedDiag,
            angleDegrees = solvedAngle,
            risePerFoot = ratio * 12.0,
            slopePercent = ratio * 100.0,
        )
    }

    private fun isPositiveLength(q: Quantity): Boolean =
        q.dimension == Dimension.LENGTH && q.value.signum > 0
}
