package com.thedirepack.constructioncalc.core.electrical

import com.thedirepack.constructioncalc.core.electrical.code.BurialDepthRow
import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.electrical.code.PvcExpansionRow
import com.thedirepack.constructioncalc.core.electrical.code.RacewaySupportRule

/** The 5 wiring-method columns of [CodeRule.BURIAL_DEPTH] ([CodeRule.BURIAL_DEPTH]). */
enum class BurialWiringMethod(val label: String, val shortLabel: String, val columnIndex: Int) {
    DIRECT_BURIAL("Direct burial cables or conductors (UF, USE)", "Direct burial", 0),
    RMC_IMC("Rigid metal conduit (RMC) or IMC", "RMC / IMC", 1),
    PVC_NONMETALLIC("EMT, or nonmetallic raceway listed for direct burial (PVC, RTRC)", "EMT / PVC", 2),
    RESIDENTIAL_120V_GFCI("Residential 120 V max, GFCI, ≤ 20 A OCPD", "Res. 120V GFCI", 3),
    IRRIGATION_LANDSCAPE_30V("Irrigation / landscape lighting ≤ 30 V", "≤ 30 V control", 4),
}

/** Result of calculating PVC conduit thermal expansion ([CodeRule.PVC_EXPANSION], NEC Table 352.44). */
data class PvcExpansionResult(
    val runLengthFt: Double,
    val tempChangeF: Double,
    val tempChangeC: Double,
    /** Total expected length change over the run, inches. */
    val expansionIn: Double,
    /** Total expected length change over the run, millimeters. */
    val expansionMm: Double,
    /** True when [expansionIn] meets or exceeds the NEC 352.44 expansion-fitting threshold (0.25 in. / 6 mm). */
    val expansionFittingRequired: Boolean,
)

/**
 * Raceway installation rules from [Code.book]:
 * - Underground burial cover ([CodeRule.BURIAL_DEPTH], [CodeRule.BURIAL_DEPTH])
 * - Securing and supporting intervals ([CodeRule.RACEWAY_SUPPORT], Articles 342–362)
 * - PVC thermal expansion ([CodeRule.PVC_EXPANSION], NEC Table 352.44)
 */
object RacewayInstall {
    /** Rows of [CodeRule.BURIAL_DEPTH] ([CodeRule.BURIAL_DEPTH]). */
    val burialDepths: List<BurialDepthRow> get() = Code.book.burialDepths

    /** Minimum cover in inches for [locationIndex] and [method], or null if index is out of range. */
    fun minimumCoverInches(locationIndex: Int, method: BurialWiringMethod): Int? =
        burialDepths.getOrNull(locationIndex)?.coverInches?.getOrNull(method.columnIndex)

    /** Securing and supporting rule for [conduitType]. */
    fun supportRule(conduitType: ConduitType): RacewaySupportRule? = Code.book.racewaySupport[conduitType]

    /**
     * Maximum distance between supports (feet) for [conduitType] at [tradeSize].
     * For RMC/IMC, [threadedCouplings] = true returns the extended straight-run threaded-coupling
     * interval (Table 344.30(B)(2)); otherwise the general 10 ft interval is returned.
     */
    fun maxSupportSpacingFt(
        conduitType: ConduitType,
        tradeSize: TradeSize,
        threadedCouplings: Boolean = false,
    ): Double? {
        val rule = supportRule(conduitType) ?: return null
        return when (conduitType) {
            ConduitType.PVC_40, ConduitType.PVC_80 -> rule.byTradeSizeFt[tradeSize] ?: rule.defaultIntervalFt
            ConduitType.RMC, ConduitType.IMC ->
                if (threadedCouplings) rule.byTradeSizeFt[tradeSize] ?: rule.defaultIntervalFt else rule.defaultIntervalFt
            else -> rule.defaultIntervalFt
        }
    }

    /** Rows of [CodeRule.PVC_EXPANSION] (NEC Table 352.44). */
    val pvcExpansionTable: List<PvcExpansionRow> get() = Code.book.pvcThermalExpansion

    /**
     * Calculates thermal expansion of rigid PVC conduit ([CodeRule.PVC_EXPANSION]) over [runLengthFt]
     * for a temperature swing of [tempChangeF] (°F). Returns null if inputs are negative or non-finite.
     */
    fun pvcExpansionFahrenheit(runLengthFt: Double, tempChangeF: Double): PvcExpansionResult? {
        if (!runLengthFt.isFinite() || !tempChangeF.isFinite() || runLengthFt < 0.0 || tempChangeF < 0.0) return null
        val rate = Code.book.values.pvcExpansionInPer100FtPer100F
        val expansionIn = (runLengthFt / 100.0) * rate * (tempChangeF / 100.0)
        val threshold = Code.book.values.pvcExpansionFittingThresholdIn
        return PvcExpansionResult(
            runLengthFt = runLengthFt,
            tempChangeF = tempChangeF,
            tempChangeC = tempChangeF * 5.0 / 9.0,
            expansionIn = expansionIn,
            expansionMm = expansionIn * 25.4,
            expansionFittingRequired = expansionIn >= threshold - 1e-9,
        )
    }

    /** Same as [pvcExpansionFahrenheit], taking temperature swing in °C. */
    fun pvcExpansionCelsius(runLengthFt: Double, tempChangeC: Double): PvcExpansionResult? =
        pvcExpansionFahrenheit(runLengthFt, tempChangeC * 9.0 / 5.0)
}
