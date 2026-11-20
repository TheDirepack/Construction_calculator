package com.thedirepack.constructioncalc.core.electrical

import kotlin.math.abs
import kotlin.math.floor

/** One kind of conductor: an insulated wire from NEC Table 5, or a bare one from Table 8. */
sealed interface FillConductor {
    val size: ConductorSize

    /** Table 5 or Table 8 area and diameter, or null if the NEC has no row for this combination. */
    val dimensions: ConductorDimensions?

    /** "12 AWG THHN / THWN / THWN-2" or "6 AWG bare". */
    val label: String

    /** "12 AWG THHN" or "6 AWG bare": for tight spaces such as a result label. */
    val shortLabel: String

    data class Insulated(val type: InsulationType, override val size: ConductorSize) : FillConductor {
        override val dimensions get() = InsulatedConductorArea.dimensions(type, size)
        override val label get() = "${size.label} ${type.label}"
        override val shortLabel get() = "${size.label} ${type.code}"
    }

    data class Bare(override val size: ConductorSize, val solid: Boolean = false) : FillConductor {
        override val dimensions get() = BareConductorArea.dimensions(size, solid)
        override val label get() = "${size.label} bare"
        override val shortLabel get() = label
    }
}

/** [count] identical conductors. */
data class ConductorGroup(val count: Int, val spec: FillConductor)

/** Result of checking a set of conductors against one conduit. */
data class FillAnalysis(
    val conduit: ConduitSize,
    val conductorCount: Int,
    val totalAreaSqIn: Double,
    /** Table 1 limit that applies, percent. */
    val limitPercent: Int,
    /** The limit as an area, from the printed Table 4 value (see [ConduitSize.allowedAreaSqIn]). */
    val allowedAreaSqIn: Double,
    /** Total conductor area as a percent of the conduit's 100% area. */
    val percentFill: Double,
    /** True when the fill is permitted, including by Note 7 rounding. */
    val withinLimit: Boolean,
    /** True when [withinLimit] only because of the Note 7 round-up (all conductors the same size). */
    val permittedByNote7: Boolean,
)

/** The 3-conductor jamming check from the Informational Note to Table 1. */
data class JamCheck(
    val ratio: Double,
    /** True when [ratio] is from 2.8 to 3.2, where jamming can occur. */
    val inJamRange: Boolean,
    /** True if the three conductors differ in size; [ratio] then uses the largest diameter. */
    val mixedSizes: Boolean,
)

/**
 * Conduit fill by NEC (NFPA 70) Chapter 9: Table 1 percent limits, Table 4 conduit areas, Table 5
 * and Table 8 conductor areas, and the "how many fit" counts of Annex C, computed from the tables.
 *
 * ### Table 1 limits
 * 1 conductor 53%, 2 conductors 31%, more than 2 conductors 40% of the conduit's cross-section.
 * Conduit or tubing *nipples* no longer than 24 in. (600 mm) between boxes or similar enclosures
 * may be filled to 60% (Table 1 Note 4), for any number of conductors.
 *
 * ### Note 7 rounding
 * When working out the maximum number of conductors that are **all the same size** (same total
 * cross-sectional area including insulation), the quotient `allowed area / conductor area` is
 * rounded **up** to the next whole number if its decimal part is **0.8 or more**, and is otherwise
 * truncated. Example: 12 AWG THHN in 1-1/4" EMT is 0.598 / 0.0133 = 44.96, which counts as 45.
 * Note 7 also says a single conductor is permitted when `allowed / area` is 0.8 or more. The rule
 * applies only to sets of one size; a mix of sizes is compared exactly with no rounding. Annex C is
 * built this way, so the counts here reproduce it.
 *
 * The count for a given conduit uses the limit that matches the count: more than 2 conductors
 * are tried first at 40%, then 2 at 31%, then 1 at 53%.
 */
object ConduitFill {
    const val NIPPLE_PERCENT = 60
    const val JAM_LOW = 2.8
    const val JAM_HIGH = 3.2

    private const val EPS = 1e-9

    /** Table 1 percent for a conduit holding [conductorCount] conductors (nipple: 60%). */
    fun limitPercent(conductorCount: Int, nipple: Boolean = false): Int = when {
        nipple -> NIPPLE_PERCENT
        conductorCount == 1 -> 53
        conductorCount == 2 -> 31
        else -> 40
    }

    /** The Note 7 whole-number rule: round up when the decimal part is 0.8 or more, else truncate. */
    fun note7Round(quotient: Double): Int {
        if (quotient <= 0.0) return 0
        val whole = floor(quotient + EPS)
        val decimal = quotient - whole
        return (if (decimal >= 0.8 - EPS && decimal < 1.0 - EPS) whole + 1 else whole).toInt()
    }

    /**
     * Largest number of identical conductors of [areaEachSqIn] that Table 1 and Note 7 allow in
     * [conduit] (what Annex C tabulates). 0 if not even one fits.
     */
    fun maxCount(conduit: ConduitSize, areaEachSqIn: Double, nipple: Boolean = false): Int {
        require(areaEachSqIn > 0.0) { "Conductor area must be positive" }
        fun fits(percent: Int) = note7Round(conduit.allowedAreaSqIn(percent) / areaEachSqIn)
        if (nipple) return fits(NIPPLE_PERCENT)
        val overTwo = fits(40)
        if (overTwo >= 3) return overTwo
        if (fits(31) >= 2) return 2
        return if (fits(53) >= 1) 1 else 0
    }

    /** Total area of every conductor in [groups], square inches. Groups the NEC has no row for add nothing. */
    fun totalArea(groups: List<ConductorGroup>): Double =
        groups.sumOf { it.count * (it.spec.dimensions?.areaSqIn ?: 0.0) }

    fun conductorCount(groups: List<ConductorGroup>): Int = groups.sumOf { it.count }

    /** The checked fill of [groups] in [conduit]. Empty or zero-count groups give zero fill. */
    fun analyze(conduit: ConduitSize, groups: List<ConductorGroup>, nipple: Boolean = false): FillAnalysis {
        val active = groups.filter { it.count > 0 }
        val count = conductorCount(active)
        val total = totalArea(active)
        val limit = limitPercent(count, nipple)
        val allowed = conduit.allowedAreaSqIn(limit)
        val exactlyWithin = total <= allowed + EPS
        val sameSize = count > 0 && allAreSameArea(active)
        val note7 = !exactlyWithin && sameSize &&
            count <= maxCount(conduit, active.first().spec.dimensions!!.areaSqIn, nipple)
        return FillAnalysis(
            conduit = conduit,
            conductorCount = count,
            totalAreaSqIn = total,
            limitPercent = limit,
            allowedAreaSqIn = allowed,
            percentFill = total / conduit.area100SqIn * 100.0,
            withinLimit = exactlyWithin || note7,
            permittedByNote7 = note7,
        )
    }

    /** Smallest trade size of [type] that holds [groups], or null if none does (or there are no conductors). */
    fun minimumConduit(type: ConduitType, groups: List<ConductorGroup>, nipple: Boolean = false): ConduitSize? {
        if (conductorCount(groups.filter { it.count > 0 }) == 0) return null
        return ConduitTable.sizes(type).firstOrNull { analyze(it, groups, nipple).withinLimit }
    }

    /**
     * Jam ratio (conduit inside diameter / conductor outside diameter) when exactly three
     * conductors are installed, else null. With three different sizes the largest is used.
     */
    fun jamCheck(conduit: ConduitSize, groups: List<ConductorGroup>): JamCheck? {
        val active = groups.filter { it.count > 0 }
        if (conductorCount(active) != 3) return null
        val diameters = active.map { it.spec.dimensions?.diameterIn ?: return null }
        val largest = diameters.max()
        val ratio = conduit.internalDiameterIn / largest
        return JamCheck(
            ratio = ratio,
            inJamRange = ratio >= JAM_LOW - EPS && ratio <= JAM_HIGH + EPS,
            mixedSizes = diameters.any { abs(it - largest) > 1e-9 },
        )
    }

    private fun allAreSameArea(groups: List<ConductorGroup>): Boolean {
        val first = groups.first().spec.dimensions?.areaSqIn ?: return false
        return groups.all { g -> g.spec.dimensions?.areaSqIn?.let { abs(it - first) < 1e-12 } == true }
    }
}
