package com.thedirepack.constructioncalc.core.electrical

import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.electrical.code.GecRow

/** Type of grounding electrode connection (determines NEC 250.66(A)–(C) maximum required GEC caps). */
enum class GroundingElectrodeType(val label: String) {
    /** Water pipe, structural metal, or multiple electrodes bonded together (full Table 250.66). */
    FULL_TABLE("Water pipe, building steel or other electrode"),
    /** Sole connection to rod, pipe, or plate electrode(s): capped at 6 AWG Cu (250.66(A)). */
    ROD_PIPE_PLATE("Only rod, pipe or plate electrodes"),
    /** Sole connection to concrete-encased electrode (Ufer): capped at 4 AWG Cu (250.66(B)). */
    CONCRETE_ENCASED("Only a concrete-encased electrode (Ufer)"),
}

/**
 * Result of sizing a Grounding Electrode Conductor ([CodeRule.GEC_SIZE]) and Supply-Side / Main
 * Bonding Jumper ([CodeRule.SUPPLY_SIDE_BONDING]).
 *
 * @property totalServiceCmil combined circular mils of the largest ungrounded service-entrance conductor set
 * @property gecSize minimum GEC size (after any electrode cap in 250.66(A)/(B))
 * @property gecCapped true when an electrode cap reduced [gecSize] below the full Table 250.66 value
 * @property bondingJumperSize minimum main / supply-side bonding jumper size per Table 250.102(C)(1)
 * @property bondingJumperCmilRequired required circular mils when over 1100 kcmil Cu / 1750 kcmil Al (12.5% rule), or null when read directly from the table
 */
data class GecBondingResult(
    val totalServiceCmil: Long,
    val gecSize: ConductorSize,
    val gecCapped: Boolean,
    val bondingJumperSize: ConductorSize?,
    val bondingJumperCmilRequired: Long? = null,
)

/**
 * Grounding Electrode Conductor ([CodeRule.GEC_SIZE], NEC Table 250.66) and Main / Supply-Side
 * Bonding Jumper ([CodeRule.SUPPLY_SIDE_BONDING], NEC Table 250.102(C)(1)) sizing from [Code.book].
 */
object GecBonding {
    val table: List<GecRow> get() = Code.book.groundingElectrodeConductors

    /**
     * Sizes the GEC and supply-side/main bonding jumper for [parallelSets] parallel conductors of
     * [serviceConductorSize] ([serviceMaterial]), using [gecMaterial] for the grounding/bonding conductor.
     */
    fun size(
        serviceConductorSize: ConductorSize,
        serviceMaterial: ConductorMaterial = ConductorMaterial.COPPER,
        gecMaterial: ConductorMaterial = ConductorMaterial.COPPER,
        parallelSets: Int = 1,
        electrode: GroundingElectrodeType = GroundingElectrodeType.FULL_TABLE,
    ): GecBondingResult {
        require(parallelSets >= 1) { "Parallel sets must be at least 1" }
        val totalCmil = serviceConductorSize.circularMils.toLong() * parallelSets
        val serviceCu = serviceMaterial.isCopper
        val gecCu = gecMaterial.isCopper

        val row = table.firstOrNull { r ->
            val limit = if (serviceCu) r.maxCopperCmil else r.maxAluminumCmil
            limit == null || totalCmil <= limit
        } ?: table.last()

        val tableGec = if (gecCu) row.gecCopper else row.gecAluminum

        // Sole-connection limits ([CodeRule.GEC_GROUND_ROD_MAX], [CodeRule.GEC_CONCRETE_ENCASED_MAX]).
        // The concrete-encased limit is stated for copper only, so an aluminum GEC keeps its table size.
        val values = Code.book.values
        val cap: ConductorSize? = when (electrode) {
            GroundingElectrodeType.FULL_TABLE -> null
            GroundingElectrodeType.ROD_PIPE_PLATE -> if (gecCu) values.gecRodMaxCopper else values.gecRodMaxAluminum
            GroundingElectrodeType.CONCRETE_ENCASED -> if (gecCu) values.gecConcreteEncasedMaxCopper else null
        }
        val cappedGec = if (cap != null && tableGec.circularMils > cap.circularMils) cap else tableGec
        val wasCapped = cappedGec != tableGec

        // Table 250.102(C)(1): same as Table 250.66 through 1100 kcmil Cu / 1750 kcmil Al;
        // over that, 12.5% of the circular-mil area of the largest ungrounded service conductor set.
        val overTableMax = (if (serviceCu) row.maxCopperCmil else row.maxAluminumCmil) == null
        val bondingCmilRequired: Long?
        val bondingSize: ConductorSize?
        if (overTableMax) {
            val req = kotlin.math.ceil(totalCmil * Code.book.values.bondingJumperLargeAreaFraction).toLong()
            bondingCmilRequired = req
            bondingSize = ConductorSize.entries.firstOrNull { it.circularMils >= req }
        } else {
            bondingCmilRequired = null
            bondingSize = tableGec
        }

        return GecBondingResult(
            totalServiceCmil = totalCmil,
            gecSize = cappedGec,
            gecCapped = wasCapped,
            bondingJumperSize = bondingSize,
            bondingJumperCmilRequired = bondingCmilRequired,
        )
    }
}
