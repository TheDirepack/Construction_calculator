package com.thedirepack.constructioncalc.core.electrical

import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.measure.Decimals


/** A conductor group as the derating needs it. [insulation] null means bare (never current-carrying). */
data class DeratingGroup(
    val count: Int,
    val size: ConductorSize,
    val insulation: InsulationType?,
    val currentCarrying: Boolean,
)

/** One group's derated ampacity. Null values mean the tables have no entry (e.g. too hot). */
data class DeratingRow(
    val group: DeratingGroup,
    val insulationRating: TemperatureRating?,
    val tableAmpacity: Int?,
    val ambientC: Double,
    val ambientFactor: Double?,
    /** Table ampacity × ambient × count adjustment. */
    val adjusted: Double?,
    /** [adjusted] limited to the termination column (110.14(C)). */
    val usable: Double?,
    /** Largest standard OCPD these conductors may be protected by (240.4(B), 240.4(D)). */
    val maxOcpd: Int?,
)

data class DeratingResult(
    val currentCarrying: Int,
    val adjustmentFactor: Double,
    val rows: List<DeratingRow>,
    val notes: List<String>,
)

/**
 * Ampacity derating for the conductors in one conduit (NEC 2023):
 * - count current-carrying conductors (grounds and bare conductors never count, 310.15(F));
 * - Table 310.15(C)(1) adjustment, not applied to nipples 24 in. or shorter (310.15(C)(1)(b));
 * - Table 310.15(B)(1)(1) ambient correction on the insulation's column, with the rooftop
 *   adder (310.15(B)(2), XHHW-2 exempt);
 * - the result may not exceed the termination column (110.14(C));
 * - the largest OCPD it may have: 240.4(B) next size up (≤ 800 A) and 240.4(D) limits.
 */
object FillDerating {

    /** Temperature column of each insulation family in Table 310.16 (dry/damp rating for THHN/XHHW-2/RHH). */
    fun insulationRating(type: InsulationType): TemperatureRating? = when (type) {
        InsulationType.TW -> TemperatureRating.C60
        InsulationType.THW -> TemperatureRating.C75
        InsulationType.THHN, InsulationType.XHHW, InsulationType.XHHW_2, InsulationType.RHH_COVERED, InsulationType.RHH_NO_COVERING -> TemperatureRating.C90
        InsulationType.TFN -> null // fixture wire: Table 402.5, not 310.16
    }

    fun compute(
        groups: List<DeratingGroup>,
        ambientC: Double,
        rooftop: Boolean,
        material: ConductorMaterial,
        termination: TemperatureRating,
        nipple: Boolean,
    ): DeratingResult {
        val ccc = groups.filter { it.currentCarrying && it.insulation != null }.sumOf { it.count }
        val adjustment = if (nipple) 1.0 else ConductorCountAdjustment.factor(ccc)
        val notes = mutableListOf<String>()
        notes += when {
            nipple -> "Nipple 24 in. or shorter: no adjustment for conductor count (${CodeRule.SHORT_RACEWAY_ADJUSTMENT.cite})."
            adjustment < 1.0 -> "$ccc current-carrying conductors: ×$adjustment (${CodeRule.CONDUCTOR_COUNT_ADJUSTMENT.cite})."
            else -> "$ccc current-carrying conductors: no count adjustment (3 or fewer)."
        }
        if (rooftop) notes += "Rooftop in sunlight under 3/4 in.: +33 °C (${CodeRule.ROOFTOP_ADDER.cite}); XHHW-2 is exempt."

        val rows = groups.filter { it.insulation != null && it.currentCarrying }.map { g ->
            val rating = insulationRating(g.insulation!!)
            val ambient = AmbientCorrection.effectiveAmbient(ambientC, rooftop, g.insulation == InsulationType.XHHW_2)
            val factor = rating?.let { AmbientCorrection.factor(ambient, it) }
            val table = rating?.let { Ampacity.of(g.size, material, it) }
            val adjusted = if (table != null && factor != null) table * factor * adjustment else null
            val termColumn = rating?.let { if (termination.celsius < it.celsius) termination else it }
            val termAmps = termColumn?.let { Ampacity.of(g.size, material, it) }
            val usable = if (adjusted != null && termAmps != null) minOf(adjusted, termAmps.toDouble()) else adjusted
            DeratingRow(g, rating, table, ambient, factor, adjusted, usable, usable?.let { maxOcpd(it, g.size, material) })
        }
        if (rows.any { it.insulationRating == null }) notes += "Fixture wire (TFN) isn't rated in ${CodeRule.AMPACITY_TABLE.cite}."
        if (rows.any { it.insulationRating != null && it.ambientFactor == null }) notes += "Too hot for some insulation: no correction factor in ${CodeRule.AMBIENT_CORRECTION.cite}."
        notes += "Ampacities from ${CodeRule.AMPACITY_TABLE.cite} (30 °C basis), ${material.label.lowercase()}; terminals limited to ${termination.label} (${CodeRule.TERMINATION_TEMPERATURE.cite})."
        return DeratingResult(ccc, adjustment, rows, notes)
    }

    /** Largest standard OCPD for [ampacity]: itself if standard, else the next standard up (240.4(B)), capped by 240.4(D). */
    fun maxOcpd(ampacity: Double, size: ConductorSize, material: ConductorMaterial): Int? =
        ConductorProtection.maxOcpd(ampacity, size, material)
}

/** One line per group showing the arithmetic, e.g. `12 AWG THHN: 30 A × 0.96 (36 °C) × 0.8 = 23 A`. */
fun DeratingResult.explanations(adjustmentLabel: Boolean = true): List<String> = rows.map { row ->
    val g = row.group
    val name = "${g.size.label} ${g.insulation?.code.orEmpty()}"
    when {
        row.insulationRating == null -> "$name: not rated in ${CodeRule.AMPACITY_TABLE.cite}."
        row.tableAmpacity == null -> "$name: no ${row.insulationRating.label} ampacity in ${CodeRule.AMPACITY_TABLE.cite}."
        row.ambientFactor == null -> "$name: too hot at ${Decimals.trim(row.ambientC)} °C for ${row.insulationRating.label} insulation."
        else -> buildString {
            append("$name: ${row.tableAmpacity} A (${row.insulationRating.label}) × ${Decimals.trim(row.ambientFactor)} (${Decimals.trim(row.ambientC)} °C)")
            if (adjustmentLabel) append(" × ${Decimals.trim(adjustmentFactor)}")
            append(" = ${Decimals.trim(row.adjusted!!)} A")
            if (row.usable != null && row.usable < row.adjusted - 1e-9) append(", limited to ${Decimals.trim(row.usable)} A by terminals")
        }
    }
}
