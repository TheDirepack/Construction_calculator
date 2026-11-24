package com.thedirepack.constructioncalc.core.measure

import com.thedirepack.constructioncalc.core.math.Rational
import java.math.BigInteger
import kotlin.math.atan2

private val DEFAULT_TARGET_RISER_IN = Rational.of(15, 2) // 7.5"
private val DEFAULT_TREAD_RUN_IN = Rational.of(10)       // 10"
private val IRC_MAX_RISER_IN = Rational.of(31, 4)        // 7.75"
private val IRC_MIN_TREAD_IN = Rational.of(10)           // 10"

private data class StairCoreGeometry(
    val riserCount: Int,
    val riserVal: Rational,
    val treadCount: Int,
    val totalRun: Quantity,
    val stringerLength: Quantity,
    val stepDiagonal: Quantity,
    val angleDegrees: Double,
)

private fun isPositiveLength(q: Quantity): Boolean =
    q.dimension == Dimension.LENGTH && q.value.signum > 0

private fun computeStairGeometry(
    totalRise: Quantity,
    targetRiser: Quantity,
    treadDepth: Quantity,
    riserCountOverride: Int?,
    maxRisers: Int,
    flushTopStep: Boolean,
): StairCoreGeometry? {
    if (!isPositiveLength(totalRise)) return null
    if (!isPositiveLength(targetRiser)) return null
    if (!isPositiveLength(treadDepth)) return null
    if (riserCountOverride != null && riserCountOverride !in 1..maxRisers) return null

    val riserCount = riserCountOverride ?: run {
        val steps = (totalRise.value / targetRiser.value).roundToSteps(1)
        if (steps > BigInteger.valueOf(maxRisers.toLong())) return null
        steps.toInt().coerceAtLeast(1)
    }

    val riserVal = totalRise.value / Rational.of(riserCount.toLong())
    val treadCount = if (flushTopStep) riserCount else (riserCount - 1).coerceAtLeast(0)
    val totalRunVal = treadDepth.value * Rational.of(treadCount.toLong())
    val totalRun = Quantity.inches(totalRunVal)
    val stepDiagonal = Quantity.inches(
        Evaluator.sqrtRational(riserVal * riserVal + treadDepth.value * treadDepth.value),
    )
    val stringerLength = if (treadCount == 0) {
        totalRise
    } else {
        Quantity.inches(
            Evaluator.sqrtRational(totalRise.value * totalRise.value + totalRunVal * totalRunVal),
        )
    }
    val angleDegrees = Math.toDegrees(atan2(riserVal.toDouble(), treadDepth.value.toDouble()))

    return StairCoreGeometry(
        riserCount = riserCount,
        riserVal = riserVal,
        treadCount = treadCount,
        totalRun = totalRun,
        stringerLength = stringerLength,
        stepDiagonal = stepDiagonal,
        angleDegrees = angleDegrees,
    )
}

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

    private val MIN_COMFORT_2R_PLUS_T = Rational.of(24) // 24"
    private val MAX_COMFORT_2R_PLUS_T = Rational.of(25) // 25"

    /**
     * Computes stair geometry for [totalRise]. Returns null if any length is non-positive or not
     * [Dimension.LENGTH], or if the riser count (auto or [riserCountOverride]) is outside `1..100`.
     */
    fun calculate(
        totalRise: Quantity,
        targetRiser: Quantity = Quantity.inches(DEFAULT_TARGET_RISER_IN),
        treadDepth: Quantity = Quantity.inches(DEFAULT_TREAD_RUN_IN),
        riserCountOverride: Int? = null,
        mount: StairTopMount = StairTopMount.FLUSH,
    ): StairResult? {
        val geom = computeStairGeometry(
            totalRise = totalRise,
            targetRiser = targetRiser,
            treadDepth = treadDepth,
            riserCountOverride = riserCountOverride,
            maxRisers = MAX_RISERS,
            flushTopStep = mount == StairTopMount.FLUSH,
        ) ?: return null

        val riserD = geom.riserVal.toDouble()
        val treadD = treadDepth.value.toDouble()
        val risePerFoot = (riserD / treadD) * 12.0
        val twoRPlusTVal = geom.riserVal * Rational.of(2) + treadDepth.value
        val twoRPlusTInches = twoRPlusTVal.toDouble()

        val layoutMarks = List(geom.treadCount) { idx ->
            Quantity.inches(geom.stepDiagonal.value * Rational.of((idx + 1).toLong()))
        }

        val notices = buildList {
            if (geom.riserVal > IRC_MAX_RISER_IN) {
                add(
                    "Riser height (${Decimals.trim(riserD, 2)}\") exceeds typical residential maximum of 7 3/4\" (IRC guidance); verify local code or add a riser.",
                )
            }
            if (treadDepth.value < IRC_MIN_TREAD_IN) {
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
            riserCount = geom.riserCount,
            riserHeight = Quantity.inches(geom.riserVal),
            treadCount = geom.treadCount,
            treadDepth = treadDepth,
            totalRun = geom.totalRun,
            stringerLength = geom.stringerLength,
            stepHypotenuse = geom.stepDiagonal,
            angleDegrees = geom.angleDegrees,
            risePerFoot = risePerFoot,
            twoRPlusTInches = twoRPlusTInches,
            layoutMarks = layoutMarks,
            notices = notices,
        )
    }
}

/**
 * Pure-Kotlin stair stringer and layout calculator.
 *
 * Computes riser count, exact riser height as a [Rational]-backed [Quantity], tread count
 * (`risers - 1`), total run, stringer diagonal length, stair pitch angle, and residential IRC
 * guidance notes (IRC R311.7.5: max 7-3/4" riser, min 10" tread).
 */
object StairCalculator {

    /** Default target riser height: 7-1/2" (7.5"). */
    val DEFAULT_TARGET_RISER: Quantity = Quantity.inches(DEFAULT_TARGET_RISER_IN)

    /** Default tread run: 10". */
    val DEFAULT_TREAD_RUN: Quantity = Quantity.inches(DEFAULT_TREAD_RUN_IN)

    /** IRC R311.7.5.1 maximum residential riser height: 7-3/4" (7.75"). */
    val IRC_MAX_RISER: Quantity = Quantity.inches(IRC_MAX_RISER_IN)

    /** IRC R311.7.5.2 minimum residential tread depth/run: 10". */
    val IRC_MIN_TREAD: Quantity = Quantity.inches(IRC_MIN_TREAD_IN)

    /** Safety cap on riser count to prevent overflow on extreme spans. */
    const val MAX_RISERS = 10_000

    data class StairLayout(
        val totalRise: Quantity,
        val targetRiserHeight: Quantity,
        val riserCount: Int,
        /** Exact riser height (`totalRise / riserCount`) computed in exact [Rational] arithmetic. */
        val riserHeight: Quantity,
        /** Number of treads (`riserCount - 1`). */
        val treadCount: Int,
        val treadRun: Quantity,
        val totalRun: Quantity,
        /** Stringer diagonal span `√(totalRise² + totalRun²)`. */
        val stringerLength: Quantity,
        /** Diagonal length of a single step triangle `√(riserHeight² + treadRun²)`. */
        val stepDiagonal: Quantity,
        /** Stair pitch angle in degrees (`atan2(riserHeight, treadRun)`). */
        val angleDegrees: Double,
        val riserWithinIrcMax: Boolean,
        val treadWithinIrcMin: Boolean,
        val ircNotes: List<String>,
    ) {
        /** Exact riser height in inches as a [Rational]. */
        val riserHeightInches: Rational get() = riserHeight.value

        /** True when both riser height (≤ 7-3/4") and tread run (≥ 10") meet IRC guidance. */
        val isIrcCompliant: Boolean get() = riserWithinIrcMax && treadWithinIrcMin
    }

    /**
     * Calculates stair geometry from [totalRise], [targetRiserHeight] (default 7.5"), and
     * [treadRun] (default 10"). Optionally accepts [riserCountOverride] to evaluate an explicit
     * riser count.
     *
     * Returns null when any length input is not [Dimension.LENGTH], non-positive, or the
     * resulting riser count falls outside `1..MAX_RISERS`.
     */
    fun calculate(
        totalRise: Quantity,
        targetRiserHeight: Quantity = DEFAULT_TARGET_RISER,
        treadRun: Quantity = DEFAULT_TREAD_RUN,
        riserCountOverride: Int? = null,
    ): StairLayout? {
        val geom = computeStairGeometry(
            totalRise = totalRise,
            targetRiser = targetRiserHeight,
            treadDepth = treadRun,
            riserCountOverride = riserCountOverride,
            maxRisers = MAX_RISERS,
            flushTopStep = false,
        ) ?: return null

        val riserOk = geom.riserVal <= IRC_MAX_RISER_IN
        val treadOk = treadRun.value >= IRC_MIN_TREAD_IN
        val ircNotes = buildIrcNotes(geom.riserVal, treadRun.value, riserOk, treadOk)

        return StairLayout(
            totalRise = totalRise,
            targetRiserHeight = targetRiserHeight,
            riserCount = geom.riserCount,
            riserHeight = Quantity.inches(geom.riserVal),
            treadCount = geom.treadCount,
            treadRun = treadRun,
            totalRun = geom.totalRun,
            stringerLength = geom.stringerLength,
            stepDiagonal = geom.stepDiagonal,
            angleDegrees = geom.angleDegrees,
            riserWithinIrcMax = riserOk,
            treadWithinIrcMin = treadOk,
            ircNotes = ircNotes,
        )
    }

    private fun buildIrcNotes(
        riserIn: Rational,
        treadIn: Rational,
        riserOk: Boolean,
        treadOk: Boolean,
    ): List<String> = buildList {
        if (!riserOk) {
            add(
                "Riser height (${MeasureFormat.decimal(riserIn, 3)}\") exceeds IRC 7-3/4\" maximum; " +
                    "consider adding a riser.",
            )
        }
        if (!treadOk) {
            add(
                "Tread run (${MeasureFormat.decimal(treadIn, 3)}\") is below IRC 10\" minimum.",
            )
        }
        if (riserOk && treadOk) {
            add("Meets IRC residential guidance (max 7-3/4\" riser, min 10\" tread).")
        }
    }
}
