package com.thedirepack.constructioncalc.core.electrical

import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import kotlin.math.PI
import kotlin.math.round

/**
 * Raceway trade sizes used by NEC Chapter 9 Table 4, with the metric designator that goes with each.
 * [label] is the inch designation as electricians write it ("1-1/4"); [inches] is the same value as a
 * number, for sorting and "nearest size" lookups only (a trade size is a name, not a dimension).
 */
enum class TradeSize(val label: String, val metricDesignator: Int, val inches: Double) {
    S_3_8("3/8", 12, 0.375),
    S_1_2("1/2", 16, 0.5),
    S_3_4("3/4", 21, 0.75),
    S_1("1", 27, 1.0),
    S_1_1_4("1-1/4", 35, 1.25),
    S_1_1_2("1-1/2", 41, 1.5),
    S_2("2", 53, 2.0),
    S_2_1_2("2-1/2", 63, 2.5),
    S_3("3", 78, 3.0),
    S_3_1_2("3-1/2", 91, 3.5),
    S_4("4", 103, 4.0),
    S_5("5", 129, 5.0),
    S_6("6", 155, 6.0);

    /** `1-1/4"` */
    val display: String get() = "$label\""

    companion object {
        fun fromLabel(text: String): TradeSize? {
            val t = text.trim().removeSuffix("\"").trim().replace(' ', '-')
            return entries.firstOrNull { it.label == t }
        }

        fun fromMetric(designator: Int): TradeSize? = entries.firstOrNull { it.metricDesignator == designator }
    }
}

/**
 * The conduit and tubing types that have a row in NEC Chapter 9 Table 4.
 *
 * @property code short, stable identifier (safe to store in saved files)
 * @property displayName what a person calls it, e.g. "PVC Schedule 40"
 * @property fullName spelled-out name
 */
enum class ConduitType(
    val code: String,
    val displayName: String,
    val fullName: String,
) {
    EMT("EMT", "EMT", "Electrical metallic tubing"),
    RMC("RMC", "RMC", "Rigid metal conduit"),
    IMC("IMC", "IMC", "Intermediate metal conduit"),
    PVC_40("PVC-40", "PVC Schedule 40", "Rigid PVC conduit, Schedule 40 (also HDPE conduit)"),
    PVC_80("PVC-80", "PVC Schedule 80", "Rigid PVC conduit, Schedule 80"),
    FMC("FMC", "FMC", "Flexible metal conduit"),
    LFMC("LFMC", "LFMC", "Liquidtight flexible metal conduit"),
    LFNC_B("LFNC-B", "LFNC-B", "Liquidtight flexible nonmetallic conduit, type B"),
    ENT("ENT", "ENT", "Electrical nonmetallic tubing"),
    PVC_A("PVC-A", "PVC Type A", "Rigid PVC conduit, Type A"),
    PVC_EB("PVC-EB", "PVC Type EB", "Rigid PVC conduit, Type EB (concrete-encased burial)");

    /** The code article that covers this raceway, e.g. "Article 358", from [Code.book]. */
    val article: String get() = Code.book.raceways.getValue(this).article

    companion object {
        fun fromCode(code: String): ConduitType? = entries.firstOrNull { it.code.equals(code.trim(), ignoreCase = true) }
    }
}

/**
 * One row of NEC Chapter 9 Table 4: a conduit type in one trade size.
 *
 * @property internalDiameterIn nominal internal diameter, inches
 * @property area100SqIn "Total Area 100%", square inches
 */
data class ConduitSize(
    val type: ConduitType,
    val tradeSize: TradeSize,
    val internalDiameterIn: Double,
    val area100SqIn: Double,
) {
    /** `1-1/4" EMT` */
    val displayName: String get() = "${tradeSize.display} ${type.displayName}"

    /**
     * Usable area at [percent] of the cross-section, in square inches, rounded to three decimals
     * like the published columns of Table 4 (40%, 31%, 53%, 60%). Annex C and the usual hand
     * calculation compare against these printed values, so this reproduces them.
     *
     * The book computes them from the unrounded cross-section (pi/4 x diameter squared), not from the
     * printed 100% area: for 1" EMT the 60% column is 0.519, where 0.864 x 0.6 would round to 0.518.
     * A few cells of the book itself were rounded the other way (IMC 5" and 6", RMC 3"), so a value
     * can differ from the printed column by 0.001 in² there.
     */
    fun allowedAreaSqIn(percent: Int): Double {
        val exact = PI / 4.0 * internalDiameterIn * internalDiameterIn
        return round(exact * percent * 10.0) / 1000.0
    }
}


/**
 * Conduit and tubing dimensions: [CodeRule.CONDUIT_DIMENSIONS] (Chapter 9 Table 4 "Dimensions and
 * Percent Area of Conduit and Tubing" in NEC 2023), columns Nominal Internal Diameter and Total Area
 * 100%, from [Code.book].
 *
 * Each type lists only the trade sizes the code table gives for it (PVC Type EB from 2" only, and so
 * on).
 *
 * Shared by Conduit Fill, the Wire Pull Planner and Panel Design; the internal diameter is what a pull needs.
 */
object ConduitTable {
    /** Ascending by size. Never empty: every [ConduitType] has rows. */
    fun sizes(type: ConduitType): List<ConduitSize> = Code.book.conduitDimensions.getValue(type)

    fun tradeSizes(type: ConduitType): List<TradeSize> = sizes(type).map { it.tradeSize }

    fun find(type: ConduitType, tradeSize: TradeSize): ConduitSize? =
        sizes(type).firstOrNull { it.tradeSize == tradeSize }

    /** The row for [tradeSize], or if the type has none, the next larger size (else the largest). */
    fun nearest(type: ConduitType, tradeSize: TradeSize): ConduitSize {
        val rows = sizes(type)
        return rows.firstOrNull { it.tradeSize.inches >= tradeSize.inches } ?: rows.last()
    }

    /** The next larger size of the same type, or null for the largest. */
    fun next(size: ConduitSize): ConduitSize? {
        val rows = sizes(size.type)
        return rows.getOrNull(rows.indexOf(size) + 1)
    }
}
