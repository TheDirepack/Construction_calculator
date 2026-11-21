package com.thedirepack.constructioncalc.core.electrical

import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import kotlin.math.acos
import kotlin.math.sin

/** Raceway material column of NEC Table 9. PVC and aluminum conduit share one reactance column. */
enum class Table9Conduit(val label: String) { PVC("PVC"), ALUMINUM("Aluminum"), STEEL("Steel") }

/** One row of NEC Table 9 for one conductor material: AC resistance per conduit type, and reactance. */
data class ImpedanceRow(
    val rPvc: Double,
    val rAluminum: Double,
    val rSteel: Double,
    /** Reactance in PVC and aluminum conduit. */
    val xNonMagnetic: Double,
    /** Reactance in steel conduit. */
    val xSteel: Double,
) {
    fun resistance(conduit: Table9Conduit) = when (conduit) {
        Table9Conduit.PVC -> rPvc
        Table9Conduit.ALUMINUM -> rAluminum
        Table9Conduit.STEEL -> rSteel
    }

    fun reactance(conduit: Table9Conduit) = if (conduit == Table9Conduit.STEEL) xSteel else xNonMagnetic

    /** Effective impedance `Z = R·PF + X·sin(acos PF)`, Ω per 1000 ft (NEC Ch. 9 Table 9, note 2). */
    fun effectiveZ(conduit: Table9Conduit, powerFactor: Double): Double =
        resistance(conduit) * powerFactor + reactance(conduit) * sin(acos(powerFactor))
}


/**
 * AC resistance and reactance of 600 V cables, three single conductors in conduit, 3-phase, 60 Hz,
 * 75 °C, ohms per 1000 ft: [CodeRule.AC_IMPEDANCE] (Chapter 9 Table 9 in NEC 2023), from [Code.book].
 * The table's own "Effective Z" columns are not stored: [ImpedanceRow.effectiveZ] computes them for any
 * power factor. Coated copper and copper-clad aluminum use [ConductorMaterial.tableBasis].
 */
object ConductorImpedance {
    /** The Table 9 row, or null if Table 9 has no entry (e.g. 18/16 AWG, above 1000 kcmil). */
    fun row(size: ConductorSize, material: ConductorMaterial): ImpedanceRow? = table(material)[size]

    /** Sizes Table 9 covers for [material], smallest first. */
    fun sizes(material: ConductorMaterial): List<ConductorSize> = table(material).keys.sorted()

    /** Table 9 has copper and aluminum columns only; coated copper and copper-clad aluminum use [ConductorMaterial.tableBasis]. */
    fun hasOwnColumn(material: ConductorMaterial): Boolean = material.tableBasis == material

    private fun table(material: ConductorMaterial) = Code.book.acImpedance.getValue(material.tableBasis)
}
