package com.thedirepack.constructioncalc.core.electrical

import kotlin.math.abs

/**
 * Insulation families that share a row in NEC Chapter 9 Table 5 "Dimensions of Insulated Conductors
 * and Fixture Wires". [code] is stable and safe to store in saved files.
 *
 * Table 5 has no row for USE or USE-2 on their own. USE-2 is nearly always also listed as XHHW-2 (or
 * RHW-2); use that family's row when the cable is marked that way.
 */
enum class InsulationType(val code: String, val label: String, val types: String) {
    THHN("THHN", "THHN / THWN / THWN-2", "THHN, THWN, THWN-2"),
    XHHW("XHHW", "XHHW / XHH / ZW", "XHH, XHHW, ZW"),

    /**
     * XHHW-2: the same Table 5 row as [XHHW], kept separate because only XHHW-2 is exempt from the
     * rooftop temperature adder (310.15(B)(2) Exception) and is 90 °C wet as well as dry.
     */
    XHHW_2("XHHW-2", "XHHW-2", "XHHW-2"),
    THW("THW", "THW / THW-2 / THHW", "THHW, THW, THW-2"),
    TW("TW", "TW", "TW"),

    /** RHH, RHW and RHW-2 as manufactured with an outer covering (the larger Table 5 rows). */
    RHH_COVERED("RHH", "RHH / RHW / RHW-2 (outer covering)", "RHH, RHW, RHW-2 with outer covering"),

    /** The asterisked rows of Table 5: RHH, RHW and RHW-2 without outer covering. */
    RHH_NO_COVERING("RHH-NC", "RHH / RHW / RHW-2 (no covering)", "RHH*, RHW*, RHW-2* without outer covering"),
    TFN("TFN", "TFN / TFFN", "TFN, TFFN");

    companion object {
        fun fromCode(code: String): InsulationType? = entries.firstOrNull { it.code.equals(code.trim(), ignoreCase = true) }
    }
}

/** Approximate area and overall diameter of one conductor, from Table 5 (insulated) or Table 8 (bare). */
data class ConductorDimensions(val areaSqIn: Double, val diameterIn: Double)

/**
 * Insulated conductor dimensions: NEC (NFPA 70) Chapter 9, Table 5, columns "Approximate Area, in.²"
 * and "Approximate Diameter, in.".
 *
 * Values are the Table 5 figures as printed in the NEC 2023 (printed pages 70-720 to 70-723); every
 * area and diameter here was checked against the printed table. Table 5 values are worst-case for
 * round concentric-lay-stranded conductors and group solid with stranded. Compact conductors
 * (Table 5A), fixture wires, and the TFE/FEP/PFA/Z families are not included.
 */
object InsulatedConductorArea {
    /** Sizes that have a Table 5 row for [type], smallest first. */
    fun sizes(type: InsulationType): List<ConductorSize> = tables.getValue(type).keys.toList()

    fun dimensions(type: InsulationType, size: ConductorSize): ConductorDimensions? = tables.getValue(type)[size]

    /** Approximate area in square inches, or null when Table 5 has no row for this size and type. */
    fun area(type: InsulationType, size: ConductorSize): Double? = dimensions(type, size)?.areaSqIn

    /** Approximate overall diameter in inches, or null when there is no row. */
    fun diameter(type: InsulationType, size: ConductorSize): Double? = dimensions(type, size)?.diameterIn

    /** The available size nearest to [size] (the next larger if equally near), for when the type changes. */
    fun nearestSize(type: InsulationType, size: ConductorSize): ConductorSize {
        val available = sizes(type)
        if (size in available) return size
        return available.minWithOrNull(
            compareBy<ConductorSize> { abs(it.circularMils - size.circularMils) }.thenByDescending { it.circularMils },
        ) ?: available.first()
    }

    // Built from consecutive sizes starting at [first], so a table is two aligned arrays.
    private fun table(first: ConductorSize, areas: DoubleArray, diameters: DoubleArray): Map<ConductorSize, ConductorDimensions> {
        require(areas.size == diameters.size) { "Table 5 arrays must be the same length" }
        val sizes = ConductorSize.entries.drop(first.ordinal).take(areas.size)
        require(sizes.size == areas.size) { "Table 5 table runs past the largest size" }
        return sizes.indices.associate { sizes[it] to ConductorDimensions(areas[it], diameters[it]) }
    }

    // 14 AWG to 2000 kcmil is 28 rows: 14 12 10 8 6 4 3 2 1 1/0 2/0 3/0 4/0 250 300 350 400 500 600 700 750 800 900 1000 1250 1500 1750 2000

    private val thhn = table(
        ConductorSize.AWG_14,
        doubleArrayOf(
            0.0097, 0.0133, 0.0211, 0.0366, 0.0507, 0.0824, 0.0973, 0.1158, 0.1562, 0.1855, 0.2223, 0.2679, 0.3237,
            0.3970, 0.4608, 0.5242, 0.5863, 0.7073, 0.8676, 0.9887, 1.0496, 1.1085, 1.2311, 1.3478,
        ),
        doubleArrayOf(
            0.111, 0.130, 0.164, 0.216, 0.254, 0.324, 0.352, 0.384, 0.446, 0.486, 0.532, 0.584, 0.642,
            0.711, 0.766, 0.817, 0.864, 0.949, 1.051, 1.122, 1.156, 1.188, 1.252, 1.310,
        ),
    )

    private val tfn = table(
        ConductorSize.AWG_18,
        doubleArrayOf(0.0055, 0.0072),
        doubleArrayOf(0.084, 0.096),
    )

    private val xhhw = table(
        ConductorSize.AWG_14,
        doubleArrayOf(
            0.0139, 0.0181, 0.0243, 0.0437, 0.0590, 0.0814, 0.0962, 0.1146, 0.1534, 0.1825, 0.2190, 0.2642, 0.3197,
            0.3904, 0.4536, 0.5166, 0.5782, 0.6984, 0.8709, 0.9923, 1.0532, 1.1122, 1.2351, 1.3519,
            1.7180, 2.0156, 2.3127, 2.6073,
        ),
        doubleArrayOf(
            0.133, 0.152, 0.176, 0.236, 0.274, 0.322, 0.350, 0.382, 0.442, 0.482, 0.528, 0.580, 0.638,
            0.705, 0.760, 0.811, 0.858, 0.943, 1.053, 1.124, 1.158, 1.190, 1.254, 1.312,
            1.479, 1.602, 1.716, 1.822,
        ),
    )

    /** THW, THW-2, THHW and TW share one set of rows from 14 AWG up; 6 AWG and up also serve RHH*, RHW*, RHW-2*. */
    private val thwAreas = doubleArrayOf(
        0.0139, 0.0181, 0.0243, 0.0437, 0.0726, 0.0973, 0.1134, 0.1333, 0.1901, 0.2223, 0.2624, 0.3117, 0.3718,
        0.4596, 0.5281, 0.5958, 0.6619, 0.7901, 0.9729, 1.1010, 1.1652, 1.2272, 1.3561, 1.4784,
        1.8602, 2.1695, 2.4773, 2.7818,
    )
    private val thwDiameters = doubleArrayOf(
        0.133, 0.152, 0.176, 0.236, 0.304, 0.352, 0.380, 0.412, 0.492, 0.532, 0.578, 0.630, 0.688,
        0.765, 0.820, 0.871, 0.918, 1.003, 1.113, 1.184, 1.218, 1.250, 1.314, 1.372,
        1.539, 1.662, 1.776, 1.882,
    )
    private val thw = table(ConductorSize.AWG_14, thwAreas, thwDiameters)

    private val rhhCovered = table(
        ConductorSize.AWG_14,
        doubleArrayOf(
            0.0293, 0.0353, 0.0437, 0.0835, 0.1041, 0.1333, 0.1521, 0.1750, 0.2660, 0.3039, 0.3505, 0.4072, 0.4754,
            0.6291, 0.7088, 0.7870, 0.8626, 1.0082, 1.2135, 1.3561, 1.4272, 1.4957, 1.6377, 1.7719,
            2.3479, 2.6938, 3.0357, 3.3719,
        ),
        doubleArrayOf(
            0.193, 0.212, 0.236, 0.326, 0.364, 0.412, 0.440, 0.472, 0.582, 0.622, 0.668, 0.720, 0.778,
            0.895, 0.950, 1.001, 1.048, 1.133, 1.243, 1.314, 1.348, 1.380, 1.444, 1.502,
            1.729, 1.852, 1.966, 2.072,
        ),
    )

    // Asterisked rows: 14 to 8 AWG have their own values; 6 AWG and larger equal THW.
    private val rhhNoCovering: Map<ConductorSize, ConductorDimensions> = buildMap {
        put(ConductorSize.AWG_14, ConductorDimensions(0.0209, 0.163))
        put(ConductorSize.AWG_12, ConductorDimensions(0.0260, 0.182))
        put(ConductorSize.AWG_10, ConductorDimensions(0.0333, 0.206))
        put(ConductorSize.AWG_8, ConductorDimensions(0.0556, 0.266))
        thw.forEach { (size, dims) -> if (size.ordinal >= ConductorSize.AWG_6.ordinal) put(size, dims) }
    }

    private val tables: Map<InsulationType, Map<ConductorSize, ConductorDimensions>> = mapOf(
        InsulationType.THHN to thhn,
        InsulationType.XHHW to xhhw,
        InsulationType.XHHW_2 to xhhw,
        InsulationType.THW to thw,
        InsulationType.TW to thw,
        InsulationType.RHH_COVERED to rhhCovered,
        InsulationType.RHH_NO_COVERING to rhhNoCovering,
        InsulationType.TFN to tfn,
    )
}

/**
 * Bare conductor dimensions: NEC (NFPA 70) 2023 Chapter 9, Table 8 "Conductor Properties" (printed
 * page 70-725), columns "Overall" Diameter and Area. Every value was checked against the book. Table 1 Note 8 permits these for bare conductors (for example an
 * equipment grounding conductor installed without insulation).
 *
 * The NEC prints areas to three decimals, so small sizes are coarse (12 AWG stranded is 0.006 in²).
 * That is what the Code table says and what inspectors use. Stranded is the default because it is
 * the larger value and the only one the NEC lists above 8 AWG.
 */
object BareConductorArea {
    private val strandedAreas = doubleArrayOf(
        0.002, 0.003, 0.004, 0.006, 0.011, 0.017, 0.027, 0.042, 0.053, 0.067, 0.087, 0.109, 0.137, 0.173, 0.219,
        0.260, 0.312, 0.364, 0.416, 0.519, 0.626, 0.730, 0.782, 0.834, 0.940, 1.042, 1.305, 1.566, 1.829, 2.092,
    )
    private val strandedDiameters = doubleArrayOf(
        0.046, 0.058, 0.073, 0.092, 0.116, 0.146, 0.184, 0.232, 0.260, 0.292, 0.332, 0.372, 0.418, 0.470, 0.528,
        0.575, 0.630, 0.681, 0.728, 0.813, 0.893, 0.964, 0.998, 1.030, 1.094, 1.152, 1.289, 1.412, 1.526, 1.632,
    )

    // Solid is listed for 18 to 8 AWG only.
    private val solidAreas = doubleArrayOf(0.001, 0.002, 0.003, 0.005, 0.008, 0.013)
    private val solidDiameters = doubleArrayOf(0.040, 0.051, 0.064, 0.081, 0.102, 0.128)

    private val stranded: Map<ConductorSize, ConductorDimensions> =
        ConductorSize.entries.associateWith { ConductorDimensions(strandedAreas[it.ordinal], strandedDiameters[it.ordinal]) }

    private val solid: Map<ConductorSize, ConductorDimensions> =
        ConductorSize.entries.take(solidAreas.size).associateWith {
            ConductorDimensions(solidAreas[it.ordinal], solidDiameters[it.ordinal])
        }

    init {
        require(strandedAreas.size == ConductorSize.entries.size && strandedDiameters.size == ConductorSize.entries.size) {
            "Table 8 arrays must cover every ConductorSize"
        }
    }

    /** Every size, 18 AWG to 2000 kcmil. */
    val sizes: List<ConductorSize> get() = ConductorSize.entries

    fun dimensions(size: ConductorSize, solid: Boolean = false): ConductorDimensions? =
        if (solid) this.solid[size] else stranded[size]

    /** Overall area in square inches (Table 8, "Overall", Area in.²). Null for a solid size the NEC doesn't list. */
    fun area(size: ConductorSize, solid: Boolean = false): Double? = dimensions(size, solid)?.areaSqIn

    fun diameter(size: ConductorSize, solid: Boolean = false): Double? = dimensions(size, solid)?.diameterIn
}
