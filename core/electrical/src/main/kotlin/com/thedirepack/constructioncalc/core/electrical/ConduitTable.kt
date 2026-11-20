package com.thedirepack.constructioncalc.core.electrical

import com.thedirepack.constructioncalc.core.electrical.TradeSize.*
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
 * @property article the NEC article that covers it
 */
enum class ConduitType(
    val code: String,
    val displayName: String,
    val fullName: String,
    val article: String,
) {
    EMT("EMT", "EMT", "Electrical metallic tubing", "Article 358"),
    RMC("RMC", "RMC", "Rigid metal conduit", "Article 344"),
    IMC("IMC", "IMC", "Intermediate metal conduit", "Article 342"),
    PVC_40("PVC-40", "PVC Schedule 40", "Rigid PVC conduit, Schedule 40 (also HDPE conduit)", "Articles 352 and 353"),
    PVC_80("PVC-80", "PVC Schedule 80", "Rigid PVC conduit, Schedule 80", "Article 352"),
    FMC("FMC", "FMC", "Flexible metal conduit", "Article 348"),
    LFMC("LFMC", "LFMC", "Liquidtight flexible metal conduit", "Article 350"),
    LFNC_B("LFNC-B", "LFNC-B", "Liquidtight flexible nonmetallic conduit, type B", "Article 356"),
    ENT("ENT", "ENT", "Electrical nonmetallic tubing", "Article 362"),
    PVC_A("PVC-A", "PVC Type A", "Rigid PVC conduit, Type A", "Article 352"),
    PVC_EB("PVC-EB", "PVC Type EB", "Rigid PVC conduit, Type EB (concrete-encased burial)", "Article 352");

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
     * calculation compare against these printed values, so this does the same.
     */
    fun allowedAreaSqIn(percent: Int): Double = round(area100SqIn * percent * 10.0) / 1000.0
}

/**
 * Conduit and tubing dimensions: NEC (NFPA 70) Chapter 9, Table 4 "Dimensions and Percent Area of
 * Conduit and Tubing", columns Nominal Internal Diameter and Total Area 100%.
 *
 * Values are the NEC 2020 Table 4 figures, cross-checked against the printed 2017 table (they agree
 * for every row here). Each type lists only the trade sizes the NEC table gives for it. Not
 * included: LFNC-A and LFNC-C, and the 3/8" sizes where the NEC shows no entry.
 *
 * Shared by Conduit Fill and the Wire Pull Planner; the internal diameter is what a pull needs.
 */
object ConduitTable {
    /** Ascending by size. Empty never: every [ConduitType] has rows. */
    fun sizes(type: ConduitType): List<ConduitSize> = byType.getValue(type)

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

    private fun rows(type: ConduitType, vararg data: Triple<TradeSize, Double, Double>): List<ConduitSize> =
        data.map { (trade, id, area) -> ConduitSize(type, trade, id, area) }

    private fun row(trade: TradeSize, id: Double, area: Double) = Triple(trade, id, area)

    private val byType: Map<ConduitType, List<ConduitSize>> = run {
        mapOf(
            // Article 358
            ConduitType.EMT to rows(
                ConduitType.EMT,
                row(S_1_2, 0.622, 0.304), row(S_3_4, 0.824, 0.533), row(S_1, 1.049, 0.864),
                row(S_1_1_4, 1.380, 1.496), row(S_1_1_2, 1.610, 2.036), row(S_2, 2.067, 3.356),
                row(S_2_1_2, 2.731, 5.858), row(S_3, 3.356, 8.846), row(S_3_1_2, 3.834, 11.545),
                row(S_4, 4.334, 14.753),
            ),
            // Article 344
            ConduitType.RMC to rows(
                ConduitType.RMC,
                row(S_1_2, 0.632, 0.314), row(S_3_4, 0.836, 0.549), row(S_1, 1.063, 0.887),
                row(S_1_1_4, 1.394, 1.526), row(S_1_1_2, 1.624, 2.071), row(S_2, 2.083, 3.408),
                row(S_2_1_2, 2.489, 4.866), row(S_3, 3.090, 7.499), row(S_3_1_2, 3.570, 10.010),
                row(S_4, 4.050, 12.882), row(S_5, 5.073, 20.212), row(S_6, 6.093, 29.158),
            ),
            // Article 342
            ConduitType.IMC to rows(
                ConduitType.IMC,
                row(S_1_2, 0.660, 0.342), row(S_3_4, 0.864, 0.586), row(S_1, 1.105, 0.959),
                row(S_1_1_4, 1.448, 1.647), row(S_1_1_2, 1.683, 2.225), row(S_2, 2.150, 3.630),
                row(S_2_1_2, 2.557, 5.135), row(S_3, 3.176, 7.922), row(S_3_1_2, 3.671, 10.584),
                row(S_4, 4.166, 13.631),
            ),
            // Articles 352 and 353 (the NEC prints HDPE with Schedule 40)
            ConduitType.PVC_40 to rows(
                ConduitType.PVC_40,
                row(S_1_2, 0.602, 0.285), row(S_3_4, 0.804, 0.508), row(S_1, 1.029, 0.832),
                row(S_1_1_4, 1.360, 1.453), row(S_1_1_2, 1.590, 1.986), row(S_2, 2.047, 3.291),
                row(S_2_1_2, 2.445, 4.695), row(S_3, 3.042, 7.268), row(S_3_1_2, 3.521, 9.737),
                row(S_4, 3.998, 12.554), row(S_5, 5.016, 19.761), row(S_6, 6.031, 28.567),
            ),
            // Article 352
            ConduitType.PVC_80 to rows(
                ConduitType.PVC_80,
                row(S_1_2, 0.526, 0.217), row(S_3_4, 0.722, 0.409), row(S_1, 0.936, 0.688),
                row(S_1_1_4, 1.255, 1.237), row(S_1_1_2, 1.476, 1.711), row(S_2, 1.913, 2.874),
                row(S_2_1_2, 2.290, 4.119), row(S_3, 2.864, 6.442), row(S_3_1_2, 3.326, 8.688),
                row(S_4, 3.786, 11.258), row(S_5, 4.768, 17.855), row(S_6, 5.709, 25.598),
            ),
            // Article 348
            ConduitType.FMC to rows(
                ConduitType.FMC,
                row(S_3_8, 0.384, 0.116), row(S_1_2, 0.635, 0.317), row(S_3_4, 0.824, 0.533),
                row(S_1, 1.020, 0.817), row(S_1_1_4, 1.275, 1.277), row(S_1_1_2, 1.538, 1.858),
                row(S_2, 2.040, 3.269), row(S_2_1_2, 2.500, 4.909), row(S_3, 3.000, 7.069),
                row(S_3_1_2, 3.500, 9.621), row(S_4, 4.000, 12.566),
            ),
            // Article 350
            ConduitType.LFMC to rows(
                ConduitType.LFMC,
                row(S_3_8, 0.494, 0.192), row(S_1_2, 0.632, 0.314), row(S_3_4, 0.830, 0.541),
                row(S_1, 1.054, 0.873), row(S_1_1_4, 1.395, 1.528), row(S_1_1_2, 1.588, 1.981),
                row(S_2, 2.033, 3.246), row(S_2_1_2, 2.493, 4.881), row(S_3, 3.085, 7.475),
                row(S_3_1_2, 3.520, 9.731), row(S_4, 4.020, 12.692),
            ),
            // Article 356 (type B; the NEC lists the same dimensions as LFMC through 2")
            ConduitType.LFNC_B to rows(
                ConduitType.LFNC_B,
                row(S_3_8, 0.494, 0.192), row(S_1_2, 0.632, 0.314), row(S_3_4, 0.830, 0.541),
                row(S_1, 1.054, 0.873), row(S_1_1_4, 1.395, 1.528), row(S_1_1_2, 1.588, 1.981),
                row(S_2, 2.033, 3.246),
            ),
            // Article 362
            ConduitType.ENT to rows(
                ConduitType.ENT,
                row(S_1_2, 0.602, 0.285), row(S_3_4, 0.804, 0.508), row(S_1, 1.029, 0.832),
                row(S_1_1_4, 1.360, 1.453), row(S_1_1_2, 1.590, 1.986), row(S_2, 2.047, 3.291),
            ),
            // Article 352, Type A
            ConduitType.PVC_A to rows(
                ConduitType.PVC_A,
                row(S_1_2, 0.700, 0.385), row(S_3_4, 0.910, 0.650), row(S_1, 1.175, 1.084),
                row(S_1_1_4, 1.500, 1.767), row(S_1_1_2, 1.720, 2.324), row(S_2, 2.155, 3.647),
                row(S_2_1_2, 2.635, 5.453), row(S_3, 3.230, 8.194), row(S_3_1_2, 3.690, 10.694),
                row(S_4, 4.180, 13.723),
            ),
            // Article 352, Type EB (the NEC has no 1/2" to 1-1/2" or 2-1/2" rows)
            ConduitType.PVC_EB to rows(
                ConduitType.PVC_EB,
                row(S_2, 2.221, 3.874), row(S_3, 3.330, 8.709), row(S_3_1_2, 3.804, 11.365),
                row(S_4, 4.289, 14.448), row(S_5, 5.316, 22.195), row(S_6, 6.336, 31.530),
            ),
        )
    }
}
