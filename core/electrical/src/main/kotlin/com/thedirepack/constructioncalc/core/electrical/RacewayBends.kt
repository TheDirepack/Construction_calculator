package com.thedirepack.constructioncalc.core.electrical

/** The two radius columns of NEC Chapter 9 Table 2. */
enum class BendRadiusColumn(val label: String) {
    ONE_SHOT("One shot and full shoe benders"),
    OTHER("Other bends"),
}

/**
 * Bend rules for raceways, NEC (NFPA 70) 2023.
 *
 * ### Minimum bend radius: Chapter 9 Table 2 "Radius of Conduit and Tubing Bends"
 * Printed page 70-716 (PDF page 719 of the local copy); every value was read from the book. The
 * radius is to the centerline of the conduit, in inches. Table 2 has no row for trade size 3/8.
 *
 * Which column a raceway article points to (each checked on its page):
 * - EMT 358.24(A): "Table 2, Chapter 9 for one-shot and full shoe benders".
 * - IMC 342.24(A), RMC 344.24(A), PVC 352.24(A): "Table 2, Chapter 9" without naming a column.
 *   These are usually bent with one-shot or full-shoe benders (or are factory elbows), so the
 *   one-shot column is used. It is the smaller radius, so it is also the conservative choice for
 *   sidewall pressure.
 * - FMC 348.24(A), LFMC 350.24(A), LFNC 356.24(A), ENT 362.24(A): "the column 'Other Bends'".
 *
 * ### Degrees between pull points: "Bends — (B) Number in One Run"
 * "The total degrees of bends in a conduit run shall not exceed 360 degrees between pull points."
 * Section numbers and printed pages in the 2023 edition, each checked against the local PDF:
 * IMC 342.24(B) p. 70-232, RMC 344.24(B) p. 70-233, FMC 348.24(B) p. 70-235, LFMC 350.24(B)
 * p. 70-236, PVC 352.24(B) p. 70-238, HDPE 353.24(B) p. 70-240, LFNC 356.24(B) p. 70-244,
 * EMT 358.24(B) p. 70-245, ENT 362.24(B) p. 70-248. (Earlier editions numbered these xxx.26.)
 */
object RacewayBends {
    /** "shall not exceed 360 degrees between pull points" (xxx.24(B), NEC 2023). */
    const val MAX_DEGREES_BETWEEN_PULL_POINTS = 360.0

    // Table 2, inches, by trade size 1/2 .. 6.
    private val sizes = listOf(
        TradeSize.S_1_2, TradeSize.S_3_4, TradeSize.S_1, TradeSize.S_1_1_4, TradeSize.S_1_1_2, TradeSize.S_2,
        TradeSize.S_2_1_2, TradeSize.S_3, TradeSize.S_3_1_2, TradeSize.S_4, TradeSize.S_5, TradeSize.S_6,
    )
    private val oneShot = doubleArrayOf(4.0, 4.5, 5.75, 7.25, 8.25, 9.5, 10.5, 13.0, 15.0, 16.0, 24.0, 30.0)
    private val other = doubleArrayOf(4.0, 5.0, 6.0, 8.0, 10.0, 12.0, 15.0, 18.0, 21.0, 24.0, 30.0, 36.0)

    /** The Table 2 column the raceway article for [type] refers to. */
    fun column(type: ConduitType): BendRadiusColumn = when (type) {
        ConduitType.FMC, ConduitType.LFMC, ConduitType.LFNC_B, ConduitType.ENT -> BendRadiusColumn.OTHER
        else -> BendRadiusColumn.ONE_SHOT
    }

    /** Table 2 minimum centerline radius in inches, or null for a size the table doesn't list (3/8). */
    fun minimumRadiusInches(size: TradeSize, column: BendRadiusColumn): Double? {
        val i = sizes.indexOf(size)
        if (i < 0) return null
        return if (column == BendRadiusColumn.ONE_SHOT) oneShot[i] else other[i]
    }

    /** Table 2 minimum radius for [type] in [size], using the column its article names. */
    fun minimumRadiusInches(type: ConduitType, size: TradeSize): Double? = minimumRadiusInches(size, column(type))

    /**
     * The section that limits bends between pull points for [type], e.g. "358.24(B)". PVC Schedule 40
     * rows also cover HDPE (Article 353, 353.24(B)), which has the same rule.
     */
    fun section(type: ConduitType): String = when (type) {
        ConduitType.EMT -> "358.24(B)"
        ConduitType.RMC -> "344.24(B)"
        ConduitType.IMC -> "342.24(B)"
        ConduitType.PVC_40, ConduitType.PVC_80, ConduitType.PVC_A, ConduitType.PVC_EB -> "352.24(B)"
        ConduitType.FMC -> "348.24(B)"
        ConduitType.LFMC -> "350.24(B)"
        ConduitType.LFNC_B -> "356.24(B)"
        ConduitType.ENT -> "362.24(B)"
    }
}
