package com.thedirepack.constructioncalc.core.electrical

import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
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
 * Insulated conductor dimensions: [CodeRule.CONDUCTOR_DIMENSIONS] (Chapter 9 Table 5 in NEC 2023),
 * columns "Approximate Area, in.²" and "Approximate Diameter, in.", from [Code.book]. Table 5 values are
 * worst-case for round concentric-lay-stranded conductors and group solid with stranded. Compact
 * conductors (Table 5A), most fixture wires, and the TFE/FEP/PFA/Z families are not included.
 */
object InsulatedConductorArea {
    private fun table(type: InsulationType) = Code.book.insulatedConductorDimensions.getValue(type)

    /** Sizes that have a Table 5 row for [type], smallest first. */
    fun sizes(type: InsulationType): List<ConductorSize> = table(type).keys.sorted()

    fun dimensions(type: InsulationType, size: ConductorSize): ConductorDimensions? = table(type)[size]

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
}

/**
 * Bare conductor dimensions: [CodeRule.CONDUCTOR_PROPERTIES] (Chapter 9 Table 8 "Conductor Properties"
 * in NEC 2023), columns "Overall" Diameter and Area, from [Code.book]. Table 1 Note 8 permits these for
 * bare conductors (for example an equipment grounding conductor installed without insulation).
 *
 * The NEC prints areas to three decimals, so small sizes are coarse (12 AWG stranded is 0.006 in²).
 * That is what the Code table says and what inspectors use. Stranded is the default because it is
 * the larger value and the only one the NEC lists above 8 AWG.
 */
object BareConductorArea {
    /** Every size, 18 AWG to 2000 kcmil. */
    val sizes: List<ConductorSize> get() = ConductorSize.entries

    fun dimensions(size: ConductorSize, solid: Boolean = false): ConductorDimensions? =
        Code.book.bareConductorDimensions[if (solid) Stranding.SOLID else Stranding.STRANDED]?.get(size)

    /** Overall area in square inches (Table 8, "Overall", Area in.²). Null for a solid size the table doesn't list. */
    fun area(size: ConductorSize, solid: Boolean = false): Double? = dimensions(size, solid)?.areaSqIn

    fun diameter(size: ConductorSize, solid: Boolean = false): Double? = dimensions(size, solid)?.diameterIn
}
