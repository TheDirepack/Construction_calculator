package com.thedirepack.constructioncalc.core.electrical

import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule

/** Conductor insulation temperature column in Table 310.16 (or a terminal's temperature rating). */
enum class TemperatureRating(val celsius: Int) {
    C60(60), C75(75), C90(90);

    val label: String get() = "$celsius °C"

    companion object {
        fun fromCelsius(value: Int): TemperatureRating? = entries.firstOrNull { it.celsius == value }
    }
}

/**
 * Allowable ampacity of insulated conductors, in amperes: [CodeRule.AMPACITY_TABLE] (Table 310.16 in
 * NEC 2023), read from [Code.book]. Copper, and aluminum or copper-clad aluminum; 18 AWG to 2000 kcmil.
 * Blank cells are absent.
 *
 * Ambient-temperature and more-than-three-conductor corrections (310.15) and the 240.4(D) small-conductor
 * limits are NOT applied. The 60 and 75 °C columns are what termination temperature limits (110.14(C))
 * normally select.
 */
object Ampacity {
    // Coated Cu → copper; CCA → aluminum (310.16 heading).
    private fun table(material: ConductorMaterial) = Code.book.ampacity.getValue(material.tableBasis)

    /** Ampacity in amperes, or null if the table has no value for this size, material and column. */
    fun of(size: ConductorSize, material: ConductorMaterial, rating: TemperatureRating): Int? =
        table(material)[size]?.of(rating)?.takeIf { it > 0 }

    /** Sizes that have a value in this column, smallest first. */
    fun sizes(material: ConductorMaterial, rating: TemperatureRating): List<ConductorSize> =
        ConductorSize.entries.filter { of(it, material, rating) != null }

    /** The smallest size whose ampacity is at least [requiredAmps], or null if even 2000 kcmil is too small. */
    fun smallestSize(requiredAmps: Double, material: ConductorMaterial, rating: TemperatureRating): ConductorSize? =
        sizes(material, rating).firstOrNull { (of(it, material, rating) ?: 0) >= requiredAmps - 1e-9 }
}
