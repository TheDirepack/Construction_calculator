package com.thedirepack.constructioncalc.feature.conversions.domain

import com.thedirepack.constructioncalc.core.electrical.ConductorSize

/** A wire area in every common form, plus the standard size that matches it. */
data class WireSizeResult(
    val circularMils: Double,
    val squareMillimeters: Double,
    /** The standard size: the entered one, or the smallest size at or above the entered area. Null above 2000 kcmil. */
    val size: ConductorSize?,
    /** True when the input was a standard size, false when [size] is only the nearest one at or above. */
    val isStandard: Boolean,
)

/**
 * AWG / kcmil ↔ circular mils ↔ mm². Sizes and circular mils come from [ConductorSize]
 * (NEC 2023 Chapter 9 Table 8); 1 circular mil = π/4 × (0.0254 mm)² = 0.000506707… mm².
 */
object WireSizes {
    const val MM2_PER_CMIL: Double = Math.PI / 4 * 0.0254 * 0.0254

    /**
     * Areas this close below a standard size still count as that size, so a rounded catalog
     * figure (3.31 mm² for 12 AWG's 3.309) doesn't jump a size. 0.1 % of area, far inside wire tolerances.
     */
    const val TOLERANCE = 0.001

    fun cmilToMm2(circularMils: Double) = circularMils * MM2_PER_CMIL

    fun mm2ToCmil(squareMillimeters: Double) = squareMillimeters / MM2_PER_CMIL

    /** The smallest standard size whose area is at least [circularMils] (within [TOLERANCE]); null if larger than 2000 kcmil. */
    fun nearestAtOrAbove(circularMils: Double): ConductorSize? =
        ConductorSize.entries.firstOrNull { it.circularMils >= circularMils * (1 - TOLERANCE) }

    fun fromSize(size: ConductorSize) =
        WireSizeResult(size.circularMils.toDouble(), size.squareMillimeters, size, isStandard = true)

    fun fromCircularMils(circularMils: Double): WireSizeResult {
        val size = nearestAtOrAbove(circularMils)
        return WireSizeResult(circularMils, cmilToMm2(circularMils), size, isStandard = size?.circularMils?.toDouble() == circularMils)
    }

    fun fromSquareMillimeters(squareMillimeters: Double): WireSizeResult {
        val cmil = mm2ToCmil(squareMillimeters)
        val size = nearestAtOrAbove(cmil)
        return WireSizeResult(cmil, squareMillimeters, size, isStandard = false)
    }
}
