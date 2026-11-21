package com.thedirepack.constructioncalc.core.electrical

import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule

/** Conductor construction. Table 8 lists both for 18 through 8 AWG; larger sizes are stranded only. */
enum class Stranding(val label: String) { STRANDED("Stranded"), SOLID("Solid") }

/**
 * DC resistance of conductors, ohms per 1000 ft: [CodeRule.CONDUCTOR_PROPERTIES] (Chapter 9 Table 8
 * "Conductor Properties" in NEC 2023, at 75 °C), the columns for uncoated copper, coated copper and
 * aluminum, from [Code.book].
 *
 * The table lists the smallest sizes twice, solid and stranded; larger sizes are stranded only.
 * Stranded is the default because most building wire in raceways is stranded, and it's the more
 * conservative value.
 *
 * Copper-clad aluminum has no Table 8 column; [ConductorMaterial.tableBasis] maps it to aluminum.
 * The values are valid only at the table's temperature
 * ([com.thedirepack.constructioncalc.core.electrical.code.CodeValues.resistanceReferenceC]); the table's
 * notes give R2 = R1[1 + α(T2 − T1)] with the coefficients in
 * [com.thedirepack.constructioncalc.core.electrical.code.CodeValues].
 */
object ConductorResistance {
    private val rows get() = Code.book.conductorResistance

    /** True if Table 8 lists [size] both solid and stranded. */
    fun hasSolidOption(size: ConductorSize): Boolean = rows[size]?.uncoatedSolid != null

    /**
     * Ω per 1000 ft at the table temperature. [stranding] only matters for sizes listed solid; larger
     * sizes are always stranded. Null only if [size] isn't in Table 8 (every [ConductorSize] is).
     */
    fun ohmsPerKft(
        size: ConductorSize,
        material: ConductorMaterial,
        stranding: Stranding = Stranding.STRANDED,
    ): Double? {
        val row = rows[size] ?: return null
        val solid = stranding == Stranding.SOLID
        return when (material) {
            ConductorMaterial.COPPER -> if (solid) row.uncoatedSolid ?: row.uncoated else row.uncoated
            ConductorMaterial.COATED_COPPER -> if (solid) row.coatedSolid ?: row.coated else row.coated
            ConductorMaterial.ALUMINUM, ConductorMaterial.COPPER_CLAD_ALUMINUM ->
                if (solid) row.aluminumSolid ?: row.aluminum else row.aluminum
        }
    }

    /** True if [material] has its own Table 8 column (copper-clad aluminum uses aluminum's). */
    fun hasOwnColumn(material: ConductorMaterial): Boolean = material != ConductorMaterial.COPPER_CLAD_ALUMINUM

    /** Sizes Table 8 covers for [material], smallest first (all sizes, for every material). */
    @Suppress("UNUSED_PARAMETER")
    fun sizes(material: ConductorMaterial): List<ConductorSize> = rows.keys.sorted()
}
