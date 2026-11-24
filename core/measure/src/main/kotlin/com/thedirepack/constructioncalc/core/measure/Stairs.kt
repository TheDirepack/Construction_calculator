package com.thedirepack.constructioncalc.core.measure

import com.thedirepack.constructioncalc.core.math.Rational
import java.math.BigInteger
import kotlin.math.atan2

/** How the top of the stair stringer connects to the upper landing or deck. */
enum class StairTopMount(val label: String) {
    /** Top tread sits flush with the upper deck/landing (`treadCount == riserCount`). */
    FLUSH("Flush top step"),

    /** Top tread sits one riser below the upper landing (`treadCount == riserCount - 1`). */
    DROPPED("Dropped top step"),
}

/**
 * Complete stair-stringer layout solution with exact [Rational] lengths and comfort checks.
 */
data class StairResult(
    val totalRise: Quantity,
    val riserCount: Int,
    val riserHeight: Quantity,
    val treadCount: Int,
    val treadDepth: Quantity,
    val totalRun: Quantity,
    val stringerLength: Quantity,
    val stepHypotenuse: Quantity,
    val angleDegrees: Double,
    val risePerFoot: Double,
    val twoRPlusTInches: Double,
    /** Cumulative step marks along the stringer edge, spaced by [stepHypotenuse]. */
    val layoutMarks: List<Quantity>,
    /** Informational comfort and typical residential guidance notices. */
    val notices: List<String>,
)

/**
 * Pure stair-stringer calculator using exact [Rational] arithmetic for riser heights, total run,
 * stringer length, and cumulative layout marks.
 */
object Stairs {

    const val MAX_RISERS = 100

    private val DEFAULT_TARGET_RISER = Rational.of(15, 2) // 7.5"
    private val DEFAULT_TREAD_DEPTH = Rational.of(10)     // 10"
    private val MAX_TYPICAL_RISER = Rational.of(31, 4)    // 7.75"
    private val MIN_TYPICAL_TREAD = Rational.of(10)       // 10"
    private val MIN_COMFORT_2R_PLUS_T = Rational.of(24)   // 24"
    private val MAX_COMFORT_2R_PLUS_T = Rational.of(25)   // 25"

    /**
     * Computes stair geometry for [totalRise]. Returns null if any length is non-positive or not
     * [Dimension.LENGTH], or if the riser count (auto or [riserCountOverride]) is outside `1..100`.
     */
    fun calculate(
        totalRise: Quantity,
        targetRiser: Quantity = Quantity.inches(DEFAULT_TARGET_RISER),
        treadDepth: Quantity = Quantity.inches(DEFAULT_TREAD_DEPTH),
        riserCountOverride: Int? = null,
        mount: StairTopMount = StairTopMount.FLUSH,
    ): StairResult? {
        if (!isPositiveLength(totalRise)) return null
        if (!isPositiveLength(targetRiser)) return null
        if (!isPositiveLength(treadDepth)) return null
        if (riserCountOverride != null && riserCountOverride !in 1..MAX_RISERS) return null

        val riserCount = riserCountOverride ?: run {
            val rounded = (totalRise.value / targetRiser.value).roundToSteps(1)
                .coerceAtLeast(BigInteger.ONE)
            if (rounded > BigInteger.valueOf(MAX_RISERS.toLong())) return null
            rounded.toInt()
        }

        val riserVal = totalRise.value / Rational.of(riserCount.toLong())
        val treadCount = when (mount) {
            StairTopMount.FLUSH -> riserCount
            StairTopMount.DROPPED -> (riserCount - 1).coerceAtLeast(0)
        }
        val totalRunVal = treadDepth.value * Rational.of(treadCount.toLong())
        val stepHypVal = Evaluator.sqrtRational(riserVal * riserVal + treadDepth.value * treadDepth.value)
        val stringerLenVal = Evaluator.sqrtRational(
            totalRise.value * totalRise.value + totalRunVal * totalRunVal,
        )

        val riserD = riserVal.toDouble()
        val treadD = treadDepth.value.toDouble()
        val angleDegrees = Math.toDegrees(atan2(riserD, treadD))
        val risePerFoot = (riserD / treadD) * 12.0
        val twoRPlusTVal = riserVal * Rational.of(2) + treadDepth.value
        val twoRPlusTInches = twoRPlusTVal.toDouble()

        val layoutMarks = List(treadCount) { idx ->
            Quantity.inches(stepHypVal * Rational.of((idx + 1).toLong()))
        }

        val notices = buildList {
            if (riserVal > MAX_TYPICAL_RISER) {
                add(
                    "Riser height (${Decimals.trim(riserD, 2)}\") exceeds typical residential maximum of 7 3/4\" (IRC guidance); verify local code or add a riser.",
                )
            }
            if (treadDepth.value < MIN_TYPICAL_TREAD) {
                add(
                    "Tread depth (${Decimals.trim(treadD, 2)}\") is under typical residential minimum of 10\" (IRC guidance); verify local code.",
                )
            }
            if (twoRPlusTVal < MIN_COMFORT_2R_PLUS_T || twoRPlusTVal > MAX_COMFORT_2R_PLUS_T) {
                add(
                    "2R + T comfort check is ${Decimals.trim(twoRPlusTInches, 2)}\"; typical comfortable residential range is 24\"–25\".",
                )
            }
        }

        return StairResult(
            totalRise = totalRise,
            riserCount = riserCount,
            riserHeight = Quantity.inches(riserVal),
            treadCount = treadCount,
            treadDepth = treadDepth,
            totalRun = Quantity.inches(totalRunVal),
            stringerLength = Quantity.inches(stringerLenVal),
            stepHypotenuse = Quantity.inches(stepHypVal),
            angleDegrees = angleDegrees,
            risePerFoot = risePerFoot,
            twoRPlusTInches = twoRPlusTInches,
            layoutMarks = layoutMarks,
            notices = notices,
        )
    }

    private fun isPositiveLength(q: Quantity): Boolean =
        q.dimension == Dimension.LENGTH && q.value.signum > 0
}
