package com.thedirepack.constructioncalc.core.electrical

import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.measure.Decimals

/**
 * Inputs for choosing the minimum conductor for one circuit.
 *
 * @property ocpdAmps the breaker or fuse protecting the conductors.
 * @property loadAmps total load current, if known (null: size to the OCPD only).
 * @property continuousAmps the part of [loadAmps] that is continuous (3 h or more), taken at the
 *   code's continuous-load factor (125%).
 * @property terminalLoadAmps the largest per-phase load including the continuous multiplier,
 *   when an unbalanced load has been calculated phase by phase.
 * @property insulation conductor insulation rating (e.g. 90 °C for THHN/THWN-2): the column
 *   correction and adjustment factors are applied to (310.15).
 * @property termination lowest terminal rating (usually 60 °C ≤ 100 A, 75 °C above, per 110.14(C)):
 *   the conductor's ampacity may not be used beyond this column.
 * @property currentCarrying conductors in the same raceway/cable counted per 310.15(E)/(F).
 * @property multipleCordReceptacles 240.4(B)(1): no next-size-up rule for branch circuits with
 *   more than one receptacle for cord-and-plug loads.
 */
data class WireSizingRequest(
    val ocpdAmps: Int,
    val material: ConductorMaterial = ConductorMaterial.COPPER,
    val loadAmps: Double? = null,
    val continuousAmps: Double = 0.0,
    val insulation: TemperatureRating = TemperatureRating.C90,
    val termination: TemperatureRating = TemperatureRating.C75,
    val ambientC: Double = Code.book.values.ambientBasisC,
    val rooftopUnder3_4In: Boolean = false,
    val xhhw2: Boolean = false,
    val currentCarrying: Int = 3,
    val multipleCordReceptacles: Boolean = false,
    val terminalLoadAmps: Double? = null,
)

/** The chosen size and every number that went into it, so the app can show its work. */
data class WireSizingResult(
    val size: ConductorSize?,
    val effectiveAmbientC: Double,
    val ambientFactor: Double?,
    val adjustmentFactor: Double,
    /** Table ampacity × factors, in the insulation column. */
    val adjustedAmpacity: Double?,
    /** Ampacity at the termination column (the most the conductor may be counted for at its ends). */
    val terminationAmpacity: Int?,
    /** min(adjusted, termination): what protects/serves the load. */
    val usableAmpacity: Double?,
    /** Why the next smaller size didn't work, i.e. the rule that decided the size. */
    val governing: String,
    val notes: List<String>,
)

/**
 * Minimum conductor size for a circuit (every factor and limit from [Code.book]; NEC 2023 sections shown):
 * 1. Load at the terminals: 125% of continuous + noncontinuous ≤ ampacity in the termination
 *    column, before adjustment (210.19(A)(1), 215.2(A)(1), 110.14(C)).
 * 2. Load after correction/adjustment: load ≤ insulation-column ampacity × ambient (310.15(B)) ×
 *    count adjustment (310.15(C)(1)) (210.19(A)(2)).
 * 3. Protection: usable ampacity ≥ OCPD, or the next standard rating above it for OCPD ≤ 800 A
 *    (240.4(B)); never above 240.4(D) small-conductor limits.
 *
 * Starts at the code's minimum size (310.3(A): 14 AWG copper / 12 AWG aluminum).
 * Doesn't cover motors (Art. 430), 310.12 dwelling services, or special occupancies.
 */
object ConductorSizing {

    fun minimumSize(request: WireSizingRequest): WireSizingResult {
        val r = request
        val notes = mutableListOf<String>()
        val ambient = AmbientCorrection.effectiveAmbient(r.ambientC, r.rooftopUnder3_4In, r.xhhw2)
        if (ambient != r.ambientC) notes += "Rooftop: +${AmbientCorrection.ROOFTOP_ADDER_C.toInt()} °C adder (${CodeRule.ROOFTOP_ADDER.cite})."
        val ambientFactor = AmbientCorrection.factor(ambient, r.insulation)
        val adjustment = ConductorCountAdjustment.factor(r.currentCarrying)
        val terminationColumn = if (r.termination.celsius < r.insulation.celsius) r.termination else r.insulation

        if (ambientFactor == null) {
            return WireSizingResult(
                null, ambient, null, adjustment, null, null, null,
                "Too hot",
                notes + "No ${r.insulation.label} correction factor at ${ambient.toInt()} °C (${CodeRule.AMBIENT_CORRECTION.cite}).",
            )
        }

        val load = r.loadAmps
        val continuous = r.continuousAmps.coerceIn(0.0, load ?: r.continuousAmps)
        val values = Code.book.values
        val terminalLoad = r.terminalLoadAmps ?: load?.let { (it - continuous) + values.continuousLoadFactor * continuous }
        val first = if (r.material.isCopper) values.minimumSizeCopper else values.minimumSizeAluminum

        /** Why [size] is too small, or null if it works. */
        fun failure(size: ConductorSize): String? {
            val table = Ampacity.of(size, r.material, r.insulation)?.toDouble() ?: return "Not in ${CodeRule.AMPACITY_TABLE.cite}"
            val termination = Ampacity.of(size, r.material, terminationColumn) ?: return "Not in ${CodeRule.AMPACITY_TABLE.cite}"
            val adjusted = table * ambientFactor * adjustment
            val usable = minOf(adjusted, termination.toDouble())
            if (terminalLoad != null && termination < terminalLoad - 1e-9) {
                return "Load at ${terminationColumn.label} terminals: ${Decimals.trim(Code.book.values.continuousLoadFactor * 100)}% continuous + noncontinuous (${CodeRule.BRANCH_CONDUCTOR_CONTINUOUS.cite}, ${CodeRule.TERMINATION_TEMPERATURE.cite})"
            }
            if (load != null && adjusted < load - 1e-9) {
                return "Load after temperature and conductor-count derating (${CodeRule.BRANCH_CONDUCTOR_ADJUSTED.cite}, ${CodeRule.AMPACITY.cite})"
            }
            val small = SmallConductorLimit.maxOcpd(size, r.material)
            if (small != null && r.ocpdAmps > small) return "Small-conductor protection limit (${CodeRule.SMALL_CONDUCTORS.cite})"
            val protects = r.ocpdAmps <= (ConductorProtection.maxStandardOcpd(usable, r.multipleCordReceptacles) ?: 0)
            return if (protects) null else "Overcurrent protection: ampacity vs ${r.ocpdAmps} A OCPD (${CodeRule.CONDUCTOR_PROTECTION.cite})"
        }

        var lastFailure = "Smallest size considered"
        for (size in ConductorSize.entries.filter { it >= first }) {
            val failed = failure(size)
            if (failed != null) {
                lastFailure = failed
                continue
            }
            val table = Ampacity.of(size, r.material, r.insulation)!!.toDouble()
            val termination = Ampacity.of(size, r.material, terminationColumn)!!
            val adjusted = table * ambientFactor * adjustment
            val usable = minOf(adjusted, termination.toDouble())
            if (adjustment < 1.0) notes += "${r.currentCarrying} current-carrying conductors: ×$adjustment (${CodeRule.CONDUCTOR_COUNT_ADJUSTMENT.cite})."
            if (ambientFactor != 1.0) notes += "${fmt(ambient)} °C ambient: ×$ambientFactor (${CodeRule.AMBIENT_CORRECTION.cite})."
            SmallConductorLimit.conditions(size, r.material)?.let(notes::add)
            if (usable < r.ocpdAmps) notes += "Ampacity ${fmt(usable)} A is below the ${r.ocpdAmps} A OCPD; allowed by ${CodeRule.NEXT_SIZE_UP.cite} next size up."
            return WireSizingResult(size, ambient, ambientFactor, adjustment, adjusted, termination, usable, lastFailure, notes)
        }
        return WireSizingResult(
            null, ambient, ambientFactor, adjustment, null, null, null,
            "Larger than ${ConductorSize.entries.last().label}",
            notes + "No single conductor is large enough; use parallel sets (${CodeRule.PARALLEL_CONDUCTORS.cite}).",
        )
    }

    private fun fmt(v: Double) = Decimals.trim(v, 1)
}
