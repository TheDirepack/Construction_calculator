package com.thedirepack.constructioncalc.core.electrical

import com.thedirepack.constructioncalc.core.electrical.ConductorSize.*
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
 * AC resistance and reactance of 600 V cables, three single conductors in conduit, 3-phase,
 * 60 Hz, 75 °C, ohms per 1000 ft.
 *
 * Source: NEC (NFPA 70) 2017/2020/2023 Chapter 9, Table 9, columns "XL (reactance) for all wires"
 * and "Alternating-current resistance for uncoated copper wires / aluminum wires". The table's own
 * "Effective Z at 0.85 PF" columns are not stored: [ImpedanceRow.effectiveZ] computes them for any
 * power factor. Copper covers 14 AWG to 1000 kcmil; aluminum 12 AWG to 1000 kcmil (no 700, 800 or
 * 900 kcmil rows). Values checked against the printed NEC 2023 Table 9 (page 70-726).
 */
object ConductorImpedance {
    private fun row(rp: Double, ra: Double, rs: Double, xn: Double, xs: Double) = ImpedanceRow(rp, ra, rs, xn, xs)

    private val copper: Map<ConductorSize, ImpedanceRow> = mapOf(
        AWG_14 to row(3.1, 3.1, 3.1, 0.058, 0.073),
        AWG_12 to row(2.0, 2.0, 2.0, 0.054, 0.068),
        AWG_10 to row(1.2, 1.2, 1.2, 0.050, 0.063),
        AWG_8 to row(0.78, 0.78, 0.78, 0.052, 0.065),
        AWG_6 to row(0.49, 0.49, 0.49, 0.051, 0.064),
        AWG_4 to row(0.31, 0.31, 0.31, 0.048, 0.060),
        AWG_3 to row(0.25, 0.25, 0.25, 0.047, 0.059),
        AWG_2 to row(0.19, 0.20, 0.20, 0.045, 0.057),
        AWG_1 to row(0.15, 0.16, 0.16, 0.046, 0.057),
        AWG_1_0 to row(0.12, 0.13, 0.12, 0.044, 0.055),
        AWG_2_0 to row(0.10, 0.10, 0.10, 0.043, 0.054),
        AWG_3_0 to row(0.077, 0.082, 0.079, 0.042, 0.052),
        AWG_4_0 to row(0.062, 0.067, 0.063, 0.041, 0.051),
        KCMIL_250 to row(0.052, 0.057, 0.054, 0.041, 0.052),
        KCMIL_300 to row(0.044, 0.049, 0.045, 0.041, 0.051),
        KCMIL_350 to row(0.038, 0.043, 0.039, 0.040, 0.050),
        KCMIL_400 to row(0.033, 0.038, 0.035, 0.040, 0.049),
        KCMIL_500 to row(0.027, 0.032, 0.029, 0.039, 0.048),
        KCMIL_600 to row(0.023, 0.028, 0.025, 0.039, 0.048),
        KCMIL_750 to row(0.019, 0.024, 0.021, 0.038, 0.048),
        KCMIL_1000 to row(0.015, 0.019, 0.018, 0.037, 0.046),
    )

    private val aluminum: Map<ConductorSize, ImpedanceRow> = mapOf(
        AWG_12 to row(3.2, 3.2, 3.2, 0.054, 0.068),
        AWG_10 to row(2.0, 2.0, 2.0, 0.050, 0.063),
        AWG_8 to row(1.3, 1.3, 1.3, 0.052, 0.065),
        AWG_6 to row(0.81, 0.81, 0.81, 0.051, 0.064),
        AWG_4 to row(0.51, 0.51, 0.51, 0.048, 0.060),
        AWG_3 to row(0.40, 0.41, 0.40, 0.047, 0.059),
        AWG_2 to row(0.32, 0.32, 0.32, 0.045, 0.057),
        AWG_1 to row(0.25, 0.26, 0.25, 0.046, 0.057),
        AWG_1_0 to row(0.20, 0.21, 0.20, 0.044, 0.055),
        AWG_2_0 to row(0.16, 0.16, 0.16, 0.043, 0.054),
        AWG_3_0 to row(0.13, 0.13, 0.13, 0.042, 0.052),
        AWG_4_0 to row(0.10, 0.11, 0.10, 0.041, 0.051),
        KCMIL_250 to row(0.085, 0.090, 0.086, 0.041, 0.052),
        KCMIL_300 to row(0.071, 0.076, 0.072, 0.041, 0.051),
        KCMIL_350 to row(0.061, 0.066, 0.063, 0.040, 0.050),
        KCMIL_400 to row(0.054, 0.059, 0.055, 0.040, 0.049),
        KCMIL_500 to row(0.043, 0.048, 0.045, 0.039, 0.048),
        KCMIL_600 to row(0.036, 0.041, 0.038, 0.039, 0.048),
        KCMIL_750 to row(0.029, 0.034, 0.031, 0.038, 0.048),
        KCMIL_1000 to row(0.023, 0.027, 0.025, 0.037, 0.046),
    )

    /** The Table 9 row, or null if Table 9 has no entry (e.g. 18/16 AWG, above 1000 kcmil). */
    fun row(size: ConductorSize, material: ConductorMaterial): ImpedanceRow? = table(material)[size]

    /** Sizes Table 9 covers for [material], smallest first. */
    fun sizes(material: ConductorMaterial): List<ConductorSize> = table(material).keys.toList()

    /** Table 9 has copper and aluminum columns only; coated copper and copper-clad aluminum use [ConductorMaterial.tableBasis]. */
    fun hasOwnColumn(material: ConductorMaterial): Boolean = material.tableBasis == material

    private fun table(material: ConductorMaterial) = when (material.tableBasis) {
        ConductorMaterial.COPPER -> copper
        else -> aluminum
    }
}
