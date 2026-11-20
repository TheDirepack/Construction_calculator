package com.thedirepack.constructioncalc.core.electrical

import com.thedirepack.constructioncalc.core.electrical.ConductorSize.*

/** Conductor construction. Table 8 lists both for 18 through 8 AWG; larger sizes are stranded only. */
enum class Stranding(val label: String) { STRANDED("Stranded"), SOLID("Solid") }

/**
 * DC resistance of conductors at 75 °C (167 °F), ohms per 1000 ft.
 *
 * Source: NEC (NFPA 70) 2023, Chapter 9, Table 8 "Conductor Properties", "Direct-Current
 * Resistance at 75°C (167°F)", the ohm/kFT columns for **uncoated copper**, **coated copper** and
 * **aluminum**, 18 AWG to 2000 kcmil, checked against the printed 2023 table.
 *
 * Table 8 lists 18 through 8 AWG twice: solid, and 7-strand Class B. Larger sizes are stranded
 * only. Stranded is the default because most building wire in raceways is stranded, and it's
 * the more conservative value.
 *
 * Copper-clad aluminum has no Table 8 column; [ConductorMaterial.tableBasis] maps it to aluminum.
 * Table 8 note 1: values are valid only for the stated parameters; other temperatures change
 * the resistance (note 2: R2 = R1[1 + α(T2 − 75)], α = 0.00323 copper, 0.00330 aluminum).
 */
object ConductorResistance {

    /** One Table 8 row: solid (where listed) and stranded values for each material column. */
    private class Row(
        val uncoated: Double,
        val coated: Double,
        val aluminum: Double,
        val uncoatedSolid: Double? = null,
        val coatedSolid: Double? = null,
        val aluminumSolid: Double? = null,
    )

    private val rows: Map<ConductorSize, Row> = linkedMapOf(
        AWG_18 to Row(7.95, 8.45, 13.1, 7.77, 8.08, 12.8),
        AWG_16 to Row(4.99, 5.29, 8.21, 4.89, 5.08, 8.05),
        AWG_14 to Row(3.14, 3.26, 5.17, 3.07, 3.19, 5.06),
        AWG_12 to Row(1.98, 2.05, 3.25, 1.93, 2.01, 3.18),
        AWG_10 to Row(1.24, 1.29, 2.04, 1.21, 1.26, 2.00),
        AWG_8 to Row(0.778, 0.809, 1.28, 0.764, 0.786, 1.26),
        AWG_6 to Row(0.491, 0.510, 0.808),
        AWG_4 to Row(0.308, 0.321, 0.508),
        AWG_3 to Row(0.245, 0.254, 0.403),
        AWG_2 to Row(0.194, 0.201, 0.319),
        AWG_1 to Row(0.154, 0.160, 0.253),
        AWG_1_0 to Row(0.122, 0.127, 0.201),
        AWG_2_0 to Row(0.0967, 0.101, 0.159),
        AWG_3_0 to Row(0.0766, 0.0797, 0.126),
        AWG_4_0 to Row(0.0608, 0.0626, 0.100),
        KCMIL_250 to Row(0.0515, 0.0535, 0.0847),
        KCMIL_300 to Row(0.0429, 0.0446, 0.0707),
        KCMIL_350 to Row(0.0367, 0.0382, 0.0605),
        KCMIL_400 to Row(0.0321, 0.0331, 0.0529),
        KCMIL_500 to Row(0.0258, 0.0265, 0.0424),
        KCMIL_600 to Row(0.0214, 0.0223, 0.0353),
        KCMIL_700 to Row(0.0184, 0.0189, 0.0303),
        KCMIL_750 to Row(0.0171, 0.0176, 0.0282),
        KCMIL_800 to Row(0.0161, 0.0166, 0.0265),
        KCMIL_900 to Row(0.0143, 0.0147, 0.0235),
        KCMIL_1000 to Row(0.0129, 0.0132, 0.0212),
        KCMIL_1250 to Row(0.0103, 0.0106, 0.0169),
        KCMIL_1500 to Row(0.00858, 0.00883, 0.0141),
        KCMIL_1750 to Row(0.00735, 0.00756, 0.0121),
        KCMIL_2000 to Row(0.00643, 0.00662, 0.0106),
    )

    /** True if Table 8 lists [size] both solid and stranded. */
    fun hasSolidOption(size: ConductorSize): Boolean = rows[size]?.uncoatedSolid != null

    /**
     * Ω per 1000 ft at 75 °C. [stranding] only matters for 18–8 AWG; larger sizes are always
     * stranded. Null only if [size] isn't in Table 8 (every [ConductorSize] is).
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
    fun sizes(material: ConductorMaterial): List<ConductorSize> = rows.keys.toList()
}
