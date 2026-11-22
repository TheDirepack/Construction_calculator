package com.thedirepack.constructioncalc.core.electrical

import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import kotlin.math.hypot

/** Type of pull through a pull or junction box ([CodeRule.PULL_BOX_STRAIGHT], [CodeRule.PULL_BOX_ANGLE]). */
enum class PullBoxType(val label: String) {
    STRAIGHT("Straight pull"),
    ANGLE("Angle pull (90°)"),
    U_PULL("U-pull"),
}

/**
 * Result of sizing a pull or junction box per NEC 314.28(A) for conductors 4 AWG and larger.
 *
 * @property pullType straight, angle, or U-pull
 * @property largestTradeSizeIn trade size of the largest raceway in the governing row, inches
 * @property minimumLengthIn minimum distance to the opposite wall (314.28(A)(1) or (A)(2)), inches
 * @property minimumWidthIn for an angle pull with a second perpendicular row, minimum width to its opposite wall, inches (null for straight/U-pull)
 * @property minimumDiagonalBetweenSameConductorsIn minimum distance between raceway entries enclosing the same conductor (6× largest trade size), inches (null for straight pulls)
 */
data class PullBoxResult(
    val pullType: PullBoxType,
    val largestTradeSizeIn: Double,
    val minimumLengthIn: Double,
    val minimumWidthIn: Double? = null,
    val minimumDiagonalBetweenSameConductorsIn: Double? = null,
)

/**
 * Pull and junction box sizing for conductors 4 AWG and larger per NEC 314.28(A), reading
 * multipliers from [Code.book]:
 *
 * - Straight pull ([CodeRule.PULL_BOX_STRAIGHT]): length ≥ `8 × largest trade size`.
 * - Angle or U-pull ([CodeRule.PULL_BOX_ANGLE]): distance from each raceway entry row to the
 *   opposite wall ≥ `6 × largest trade size in that row + sum of trade sizes of all other raceways in that row`.
 * - Distance between raceway entries enclosing the same conductor (angle/U-pull): ≥ `6 × largest trade size`
 *   of the two entries.
 */
object PullBoxSizing {
    /** Trade size nominal diameter in inches (e.g. [TradeSize.S_2] → 2.0, [TradeSize.S_1_1_4] → 1.25). */
    fun tradeSizeInches(size: TradeSize): Double = when (size) {
        TradeSize.S_3_8 -> 0.375
        TradeSize.S_1_2 -> 0.5
        TradeSize.S_3_4 -> 0.75
        TradeSize.S_1 -> 1.0
        TradeSize.S_1_1_4 -> 1.25
        TradeSize.S_1_1_2 -> 1.5
        TradeSize.S_2 -> 2.0
        TradeSize.S_2_1_2 -> 2.5
        TradeSize.S_3 -> 3.0
        TradeSize.S_3_1_2 -> 3.5
        TradeSize.S_4 -> 4.0
        TradeSize.S_5 -> 5.0
        TradeSize.S_6 -> 6.0
    }

    /**
     * Straight pull ([CodeRule.PULL_BOX_STRAIGHT]): minimum box length = `8 × largest trade size`.
     * Returns null if [raceways] is empty.
     */
    fun straightPull(raceways: List<TradeSize>): PullBoxResult? {
        if (raceways.isEmpty()) return null
        val largest = raceways.maxOf(::tradeSizeInches)
        val mult = Code.book.values.pullBoxStraightMultiplier
        return PullBoxResult(
            pullType = PullBoxType.STRAIGHT,
            largestTradeSizeIn = largest,
            minimumLengthIn = mult * largest,
        )
    }

    /**
     * Angle pull ([CodeRule.PULL_BOX_ANGLE]):
     * - Wall A (primary row): `6 × largest(wallA) + sum(other raceways in wallA)`
     * - Wall B (perpendicular row, defaults to [wallA] if omitted): `6 × largest(wallB) + sum(other raceways in wallB)`
     * - Minimum diagonal distance between entries enclosing the same conductor: `6 × max(wallA + wallB)`.
     */
    fun anglePull(wallA: List<TradeSize>, wallB: List<TradeSize> = wallA): PullBoxResult? {
        if (wallA.isEmpty()) return null
        val rowA = angleOrURowDistance(wallA)
        val rowB = if (wallB.isNotEmpty()) angleOrURowDistance(wallB) else rowA
        val largestOverall = (wallA + wallB).maxOf(::tradeSizeInches)
        val mult = Code.book.values.pullBoxAngleMultiplier
        return PullBoxResult(
            pullType = PullBoxType.ANGLE,
            largestTradeSizeIn = largestOverall,
            minimumLengthIn = rowA,
            minimumWidthIn = rowB,
            minimumDiagonalBetweenSameConductorsIn = mult * largestOverall,
        )
    }

    /**
     * U-pull ([CodeRule.PULL_BOX_ANGLE]): all entries enter and leave through the same wall.
     * Minimum distance to the opposite wall = `6 × largest + sum(all other raceways in the row)`.
     * Note: for a U-pull with a single circuit, both the entering and leaving raceways are in the
     * same wall, so pass both entries in [raceways] (e.g. `listOf(S_2, S_2)` → `6×2 + 2 = 14 in.`).
     */
    fun uPull(raceways: List<TradeSize>): PullBoxResult? {
        if (raceways.isEmpty()) return null
        val largest = raceways.maxOf(::tradeSizeInches)
        val mult = Code.book.values.pullBoxAngleMultiplier
        return PullBoxResult(
            pullType = PullBoxType.U_PULL,
            largestTradeSizeIn = largest,
            minimumLengthIn = angleOrURowDistance(raceways),
            minimumDiagonalBetweenSameConductorsIn = mult * largest,
        )
    }

    /**
     * Distance between two raceway entries enclosing the same conductor in an angle pull, given
     * their center offsets [xIn] and [yIn] from the box corner, and whether it meets the `6 × largest`
     * minimum of [CodeRule.PULL_BOX_ANGLE].
     */
    fun diagonalDistanceIn(xIn: Double, yIn: Double): Double = hypot(xIn, yIn)

    fun meetsSameConductorSpacing(entryA: TradeSize, entryB: TradeSize, actualDistanceIn: Double): Boolean {
        val largest = maxOf(tradeSizeInches(entryA), tradeSizeInches(entryB))
        val required = Code.book.values.pullBoxAngleMultiplier * largest
        return actualDistanceIn + 1e-9 >= required
    }

    private fun angleOrURowDistance(raceways: List<TradeSize>): Double {
        val sizes = raceways.map(::tradeSizeInches)
        val largest = sizes.max()
        val sumOthers = sizes.sum() - largest
        return Code.book.values.pullBoxAngleMultiplier * largest + sumOthers
    }
}
