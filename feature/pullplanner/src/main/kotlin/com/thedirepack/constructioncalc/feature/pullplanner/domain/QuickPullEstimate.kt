package com.thedirepack.constructioncalc.feature.pullplanner.domain

import com.thedirepack.constructioncalc.core.electrical.PullFormulas
import com.thedirepack.constructioncalc.core.electrical.RacewayBends
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Dimension
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.measure.UnitSystem
import com.thedirepack.constructioncalc.core.model.PathSegment
import com.thedirepack.constructioncalc.core.model.PullSetup
import com.thedirepack.constructioncalc.core.model.Raceway
import com.thedirepack.constructioncalc.core.model.Run
import com.thedirepack.constructioncalc.core.model.RunPath
import com.thedirepack.constructioncalc.core.model.design.PullAnalysis
import com.thedirepack.constructioncalc.core.model.design.PullDirection
import com.thedirepack.constructioncalc.core.model.design.PullResult
import com.thedirepack.constructioncalc.core.model.design.RunDesign
import com.thedirepack.constructioncalc.core.model.stored
import kotlinx.serialization.Serializable

/** Top-level workflow mode in the Wire Pull Planner. */
@Serializable
enum class PullPlannerMode(val label: String) {
    QUICK_CHECK("Quick Check"),
    DETAILED_ROUTE("Detailed Route"),
}

/** Overall PASS/FAIL verdict for a pull analysis. */
enum class PullCheckStatus(val label: String) {
    PASS("PASS"),
    FAIL("FAIL"),
}

/** Verdict-first summary for either Quick Check or Detailed Route. */
data class PullVerdict(
    val status: PullCheckStatus,
    val recommendedDirection: PullDirection,
    val inspectedDirection: PullDirection,
    val peakTensionLb: Double,
    val tensionLimitLb: Double?,
    val tensionLimitLabel: String?,
    val peakSwbpLbPerFt: Double?,
    val swbpLimitLbPerFt: Double?,
    val swbpLimitLabel: String?,
    val totalBendDegrees: Double,
    val maxSectionDegrees: Double,
    val bendLimitDegrees: Double,
    val overTension: Boolean,
    val overSwbp: Boolean,
    val overBendDegrees: Boolean,
    val overFill: Boolean,
    val lowClearance: Boolean,
    val jamWarning: Boolean,
    val suggestedTuggerLb: Int?,
) {
    val isComparingReverse: Boolean get() = inspectedDirection != recommendedDirection

    companion object {
        fun of(result: PullResult, inspectedDirection: PullDirection? = null): PullVerdict? {
            val recommended = result.recommended ?: return null
            val inspected = inspectedDirection?.takeIf { result.pass(it) != null } ?: recommended
            val pass = result.pass(inspected) ?: return null

            val peakTension = pass.peakTension
            val tensionLimit = result.tensionLimit
            val peakPressure = pass.peakPressure
            val pressureLimit = result.pressureLimit
            val bendLimit = RacewayBends.MAX_DEGREES_BETWEEN_PULL_POINTS
            val maxSection = result.sectionDegrees.maxOrNull() ?: 0.0

            val overTension = tensionLimit != null && peakTension > tensionLimit.value
            val overSwbp = pressureLimit != null && peakPressure != null && peakPressure > pressureLimit.value
            val overBends = result.sectionDegrees.any { it > bendLimit + 1e-9 }
            val overFill = result.notes.any { it.isWarning && it.text.startsWith("Fill ") }
            val lowClearance = result.clearanceIn != null && result.clearanceIn!! < PullFormulas.CLEARANCE_MINIMUM_IN
            val cantFit = result.weightCorrection == null && result.cableCount > 0 && result.conduit != null

            val status = if (overTension || overSwbp || overBends || overFill || lowClearance || cantFit) {
                PullCheckStatus.FAIL
            } else {
                PullCheckStatus.PASS
            }

            return PullVerdict(
                status = status,
                recommendedDirection = recommended,
                inspectedDirection = inspected,
                peakTensionLb = peakTension,
                tensionLimitLb = tensionLimit?.value,
                tensionLimitLabel = tensionLimit?.label,
                peakSwbpLbPerFt = peakPressure,
                swbpLimitLbPerFt = pressureLimit?.value,
                swbpLimitLabel = pressureLimit?.label,
                totalBendDegrees = result.totalDegrees,
                maxSectionDegrees = maxSection,
                bendLimitDegrees = bendLimit,
                overTension = overTension,
                overSwbp = overSwbp,
                overBendDegrees = overBends,
                overFill = overFill,
                lowClearance = lowClearance,
                jamWarning = result.jamWarning,
                suggestedTuggerLb = QuickPullEstimate.suggestedTuggerCapacityLb(peakTension),
            )
        }
    }
}

/** Combined output of a Quick Pull Check evaluation, exposing the synthesized path and full [PullResult]. */
data class QuickPullResult(
    val segments: List<PathSegment>,
    val analysis: PullResult,
    val verdict: PullVerdict?,
)

/**
 * Pure-Kotlin Quick Pull Check engine.
 *
 * Synthesizes an equivalent `List<PathSegment>` (horizontally level straights evenly distributed
 * around 90°, 45°, and optional extra bends, deducting Chapter 9 Table 2 bend arc lengths so total
 * routed length matches the entered run length) and delegates to [PullAnalysis.analyze] so Quick
 * Check and Detailed Route produce identical physics, cable footage, notes, and limits.
 */
object QuickPullEstimate {
    val TUGGER_CLASSES_LB = listOf(1000, 2000, 4000, 6000, 8000, 10000, 15000, 20000)

    fun directionLabel(direction: PullDirection): String = when (direction) {
        PullDirection.FORWARD -> "End A → End B"
        PullDirection.REVERSE -> "End B → End A"
    }

    fun directionShortLabel(direction: PullDirection): String = when (direction) {
        PullDirection.FORWARD -> "End A → B"
        PullDirection.REVERSE -> "End B → A"
    }

    fun opposite(direction: PullDirection): PullDirection = when (direction) {
        PullDirection.FORWARD -> PullDirection.REVERSE
        PullDirection.REVERSE -> PullDirection.FORWARD
    }

    /** Smallest standard jobsite tugger capacity class (lb) at or above [peakTensionLb], or null if above max class or <= 0. */
    fun suggestedTuggerCapacityLb(peakTensionLb: Double): Int? {
        if (!peakTensionLb.isFinite() || peakTensionLb <= 0.0) return null
        return TUGGER_CLASSES_LB.firstOrNull { it >= peakTensionLb }
    }

    /** Splits positive extra bend angles into valid `PathSegment.Bend` angles in `(0, 180]`. */
    fun normalizeExtraBends(extraBends: List<Double> = emptyList(), extraBendDegrees: Double = 0.0): List<Double> = buildList {
        fun addSingleOrSplit(angle: Double, maxSingleDeg: Double) {
            if (!angle.isFinite() || angle <= 0.0) return
            var rem = angle
            while (rem > maxSingleDeg + 1e-9) {
                add(90.0)
                rem -= 90.0
            }
            if (rem > 1e-9) add(rem.coerceAtMost(180.0))
        }
        extraBends.forEach { addSingleOrSplit(it, 180.0) }
        addSingleOrSplit(extraBendDegrees, 90.0)
    }

    /**
     * Synthesizes an equivalent [List]<[PathSegment]> for [totalLength] and the given bend counts.
     *
     * Returns an empty list if [totalLength] is null or non-positive.
     */
    fun toDetailedSegments(
        totalLength: Quantity?,
        bends90: Int = 0,
        bends45: Int = 0,
        extraBends: List<Double> = emptyList(),
        extraBendDegrees: Double = 0.0,
        raceway: Raceway? = STANDALONE_RACEWAY,
        runDesign: RunDesign? = null,
    ): List<PathSegment> {
        val length = totalLength?.takeIf { it.dimension == Dimension.LENGTH } ?: return emptyList()
        val totalInches = length.value.toDouble()
        if (!totalInches.isFinite() || totalInches <= 0.0) return emptyList()
        return buildSegments(
            exactTotalInches = length.value,
            totalLengthIn = totalInches,
            bends90 = bends90,
            bends45 = bends45,
            extraBends = extraBends,
            extraBendDegrees = extraBendDegrees,
            raceway = raceway,
            runDesign = runDesign,
        )
    }

    /** Overload taking [totalLengthIn] in inches directly. */
    fun toDetailedSegments(
        totalLengthIn: Double,
        bends90: Int = 0,
        bends45: Int = 0,
        extraBends: List<Double> = emptyList(),
        extraBendDegrees: Double = 0.0,
        raceway: Raceway? = STANDALONE_RACEWAY,
        runDesign: RunDesign? = null,
    ): List<PathSegment> {
        if (!totalLengthIn.isFinite() || totalLengthIn <= 0.0) return emptyList()
        return buildSegments(
            exactTotalInches = null,
            totalLengthIn = totalLengthIn,
            bends90 = bends90,
            bends45 = bends45,
            extraBends = extraBends,
            extraBendDegrees = extraBendDegrees,
            raceway = raceway,
            runDesign = runDesign,
        )
    }

    /** Synthesizes the route and runs [PullAnalysis.analyze] on it. */
    fun evaluate(
        totalLength: Quantity?,
        bends90: Int = 0,
        bends45: Int = 0,
        extraBends: List<Double> = emptyList(),
        extraBendDegrees: Double = 0.0,
        raceway: Raceway? = STANDALONE_RACEWAY,
        setup: PullSetup = PullSetup(),
        runDesign: RunDesign? = null,
        units: UnitSystem = UnitSystem.IMPERIAL,
        runName: String = "Quick check",
        inspectedDirection: PullDirection? = null,
    ): QuickPullResult {
        val segments = toDetailedSegments(
            totalLength = totalLength,
            bends90 = bends90,
            bends45 = bends45,
            extraBends = extraBends,
            extraBendDegrees = extraBendDegrees,
            raceway = raceway,
            runDesign = runDesign,
        )
        val effectiveSize = raceway?.tradeSize ?: runDesign?.conduit?.tradeSize
        val effectiveRaceway = if (segments.isEmpty()) {
            // Do not fall back to a stale run.raceway.length when quick length is cleared.
            raceway?.copy(length = null)
        } else {
            val routedIn = RunPath.lengthInches(segments, raceway?.conduitType, effectiveSize)
            raceway?.copy(length = Quantity.inches(Rational.of(routedIn)).stored())
        }
        val run = Run(
            name = runName,
            raceway = effectiveRaceway,
            path = segments,
            pull = setup,
        )
        val analysis = PullAnalysis.analyze(run, runDesign, units)
        return QuickPullResult(segments = segments, analysis = analysis, verdict = PullVerdict.of(analysis, inspectedDirection))
    }

    /** Convenience returning just [PullResult]. */
    fun analyze(
        totalLength: Quantity?,
        bends90: Int = 0,
        bends45: Int = 0,
        extraBends: List<Double> = emptyList(),
        extraBendDegrees: Double = 0.0,
        raceway: Raceway? = STANDALONE_RACEWAY,
        setup: PullSetup = PullSetup(),
        runDesign: RunDesign? = null,
        units: UnitSystem = UnitSystem.IMPERIAL,
        runName: String = "Quick check",
    ): PullResult = evaluate(
        totalLength = totalLength,
        bends90 = bends90,
        bends45 = bends45,
        extraBends = extraBends,
        extraBendDegrees = extraBendDegrees,
        raceway = raceway,
        setup = setup,
        runDesign = runDesign,
        units = units,
        runName = runName,
    ).analysis

    /** Convenience overload accepting [totalLengthIn] in inches directly. */
    fun analyze(
        totalLengthIn: Double,
        bends90: Int = 0,
        bends45: Int = 0,
        extraBends: List<Double> = emptyList(),
        extraBendDegrees: Double = 0.0,
        raceway: Raceway? = STANDALONE_RACEWAY,
        setup: PullSetup = PullSetup(),
        runDesign: RunDesign? = null,
        units: UnitSystem = UnitSystem.IMPERIAL,
        runName: String = "Quick check",
    ): PullResult = analyze(
        totalLength = if (totalLengthIn.isFinite() && totalLengthIn > 0.0) Quantity.inches(Rational.of(totalLengthIn)) else null,
        bends90 = bends90,
        bends45 = bends45,
        extraBends = extraBends,
        extraBendDegrees = extraBendDegrees,
        raceway = raceway,
        setup = setup,
        runDesign = runDesign,
        units = units,
        runName = runName,
    )

    /** Extracts Quick Check bend counts and routed length from an existing detailed path. */
    fun summarizePath(path: List<PathSegment>, type: com.thedirepack.constructioncalc.core.electrical.ConduitType?, size: com.thedirepack.constructioncalc.core.electrical.TradeSize?): QuickPathSummary {
        val bends = path.filterIsInstance<PathSegment.Bend>()
        var count90 = 0
        var count45 = 0
        var extraDeg = 0.0
        bends.forEach { b ->
            when {
                kotlin.math.abs(b.angleDegrees - 90.0) < 1e-6 -> count90++
                kotlin.math.abs(b.angleDegrees - 45.0) < 1e-6 -> count45++
                else -> extraDeg += b.angleDegrees
            }
        }
        val routedIn = RunPath.lengthInches(path, type, size)
        val length = routedIn.takeIf { it > 0.0 }?.let { Quantity.inches(Rational.of(it)).stored() }
        return QuickPathSummary(
            totalLength = length,
            bends90 = count90,
            bends45 = count45,
            extraBendDegrees = extraDeg,
        )
    }

    private fun buildSegments(
        exactTotalInches: Rational?,
        totalLengthIn: Double,
        bends90: Int,
        bends45: Int,
        extraBends: List<Double>,
        extraBendDegrees: Double,
        raceway: Raceway?,
        runDesign: RunDesign?,
    ): List<PathSegment> {
        val bends = buildList {
            repeat(bends90.coerceAtLeast(0)) { add(PathSegment.Bend(90.0)) }
            repeat(bends45.coerceAtLeast(0)) { add(PathSegment.Bend(45.0)) }
            normalizeExtraBends(extraBends, extraBendDegrees).forEach { add(PathSegment.Bend(it)) }
        }.take(RunPath.MAX_SEGMENTS)

        if (bends.isEmpty()) {
            val qty = exactTotalInches?.let { Quantity.inches(it) } ?: Quantity.inches(Rational.of(totalLengthIn))
            return listOf(PathSegment.Straight(qty.stored()))
        }

        val effectiveType = raceway?.conduitType
        val effectiveSize = raceway?.tradeSize ?: runDesign?.conduit?.tradeSize
        val totalBendArcIn = RunPath.lengthInches(bends, effectiveType, effectiveSize)
        val remainingStraightIn = (totalLengthIn - totalBendArcIn).coerceAtLeast(0.0)

        if (remainingStraightIn <= 1e-9) {
            return bends
        }

        val interleavedCount = 2 * bends.size + 1
        if (interleavedCount > RunPath.MAX_SEGMENTS) {
            val cappedBends = bends.take(RunPath.MAX_SEGMENTS - 1)
            val straight = PathSegment.Straight(Quantity.inches(Rational.of(remainingStraightIn)).stored())
            return listOf(straight) + cappedBends
        }

        val intervalCount = bends.size + 1
        val eachIn = remainingStraightIn / intervalCount
        val straightSegment = PathSegment.Straight(Quantity.inches(Rational.of(eachIn)).stored())

        return buildList(interleavedCount) {
            add(straightSegment)
            bends.forEach { bend ->
                add(bend)
                add(straightSegment)
            }
        }
    }
}

/** Quick-check summary extracted from an existing [Run.path]. */
data class QuickPathSummary(
    val totalLength: com.thedirepack.constructioncalc.core.model.StoredQuantity?,
    val bends90: Int,
    val bends45: Int,
    val extraBendDegrees: Double,
)
