package com.thedirepack.constructioncalc.core.electrical

import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule

/** The two radius columns of NEC Chapter 9 Table 2. */
enum class BendRadiusColumn(val label: String) {
    ONE_SHOT("One shot and full shoe benders"),
    OTHER("Other bends"),
}

/**
 * Bend rules for raceways, from [Code.book]:
 * - minimum bend radius, [CodeRule.BEND_RADIUS] (Chapter 9 Table 2 in NEC 2023), centerline radius in
 *   inches, in the column each raceway's article points to;
 * - the most degrees of bends between pull points, and the section of each raceway's article that
 *   says so ("Bends — (B) Number in One Run", xxx.24(B) in NEC 2023).
 *
 * The edition data (`code/nec2023/Nec2023Raceways.kt`) records which column and section each article
 * names, with the printed pages.
 */
object RacewayBends {
    /** Most degrees of bends between pull points (360° in NEC 2023). */
    val MAX_DEGREES_BETWEEN_PULL_POINTS: Double get() = Code.book.values.maxBendDegreesBetweenPullPoints

    /** The Table 2 column the raceway article for [type] refers to. */
    fun column(type: ConduitType): BendRadiusColumn = Code.book.raceways.getValue(type).bendColumn

    /** Table 2 minimum centerline radius in inches, or null for a size the table doesn't list (3/8). */
    fun minimumRadiusInches(size: TradeSize, column: BendRadiusColumn): Double? {
        val row = Code.book.bendRadius[size] ?: return null
        return if (column == BendRadiusColumn.ONE_SHOT) row.oneShotIn else row.otherIn
    }

    /** Table 2 minimum radius for [type] in [size], using the column its article names. */
    fun minimumRadiusInches(type: ConduitType, size: TradeSize): Double? = minimumRadiusInches(size, column(type))

    /**
     * The section that limits bends between pull points for [type], e.g. "358.24(B)". PVC Schedule 40
     * rows also cover HDPE (Article 353), which has the same rule.
     */
    fun section(type: ConduitType): String = Code.book.raceways.getValue(type).bendLimitSection
}
