package com.thedirepack.constructioncalc.core.electrical

import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import kotlin.math.max

/**
 * Inputs for comparing Copper vs. Aluminum conductors across 1 to 4 parallel sets.
 */
data class ComparisonRequest(
    val ocpdAmps: Int,
    val loadAmps: Double,
    val voltage: Int,
    val phases: AcPhases = AcPhases.THREE,
    val lengthFeet: Double,
    val continuous: Boolean = false,
    val includeNeutral: Boolean = false,
    val conduitType: ConduitType = ConduitType.EMT,
    val insulation: InsulationType = InsulationType.THHN,
    val insulationRating: TemperatureRating = TemperatureRating.C90,
    val terminationRating: TemperatureRating = TemperatureRating.C75,
    val ambientC: Double = Code.book.values.ambientBasisC,
    val voltageDropTargetPercent: Double = Code.book.values.voltageDropFeederPercent,
    val powerFactor: Double = Code.book.values.acImpedanceTablePowerFactor,
    val maxSets: Int = 4,
)

/** One evaluated conductor configuration (material + number of parallel sets). */
data class ConductorOption(
    val material: ConductorMaterial,
    val sets: Int,
    val size: ConductorSize?,
    /** Usable ampacity of one conductor (min of adjusted insulation ampacity and termination ampacity). */
    val ampacityPerConductor: Double?,
    /** Total usable ampacity across all parallel sets (`ampacityPerConductor * sets`). */
    val totalAmpacity: Double?,
    val voltageDropVolts: Double?,
    val voltageDropPercent: Double?,
    val meetsVoltageDropTarget: Boolean,
    /** Minimum equipment grounding conductor per raceway ([CodeRule.EGC_SIZE]). */
    val egcSize: ConductorSize?,
    /** Minimum conduit size per raceway (one set of phase/neutral conductors + one EGC). */
    val minConduit: ConduitSize?,
    /** Conduit fill percentage in [minConduit]. */
    val conduitFillPercent: Double?,
    val note: String? = null,
)

/** Full Copper vs. Aluminum and 1–4 parallel sets comparison result. */
data class ComparisonResult(
    val request: ComparisonRequest,
    val options: List<ConductorOption>,
)

/**
 * Compares Copper vs. Aluminum and 1–4 parallel conductor sets for a given load, OCPD, voltage,
 * length, and raceway type. Values come from [Code.book] (ampacity, parallel conductors, OCPD,
 * equipment grounding conductors, conduit fill and Chapter 9 impedance).
 */
object ConductorComparison {

    fun compare(request: ComparisonRequest): ComparisonResult {
        require(request.ocpdAmps > 0) { "OCPD rating must be positive" }
        require(request.loadAmps >= 0.0) { "Load current cannot be negative" }
        require(request.voltage > 0) { "Voltage must be positive" }
        require(request.lengthFeet >= 0.0) { "Length cannot be negative" }

        val setsRange = 1..request.maxSets.coerceIn(1, 4)
        val materials = listOf(ConductorMaterial.COPPER, ConductorMaterial.ALUMINUM)
        val options = materials.flatMap { material ->
            setsRange.map { sets -> evaluateOption(request, material, sets) }
        }
        return ComparisonResult(request, options)
    }

    private fun evaluateOption(
        r: ComparisonRequest,
        material: ConductorMaterial,
        sets: Int,
    ): ConductorOption {
        val values = Code.book.values
        val ambientFactor = AmbientCorrection.factor(r.ambientC, r.insulationRating)
            ?: return ConductorOption(
                material, sets, null, null, null, null, null, false, null, null, null,
                "Ambient too hot for ${r.insulationRating.label} (${CodeRule.AMBIENT_CORRECTION.cite}).",
            )

        // One parallel set per raceway: 3 hots (3φ) or 2 hots (1φ) + optional neutral.
        val hotsPerRaceway = if (r.phases == AcPhases.THREE) 3 else 2
        // A feeder neutral carrying only the unbalanced current isn't counted (NEUTRAL_COUNTING).
        val currentCarrying = hotsPerRaceway
        val adjustment = ConductorCountAdjustment.factor(currentCarrying)
        val terminationColumn = if (r.terminationRating.celsius < r.insulationRating.celsius) r.terminationRating else r.insulationRating

        val terminalLoadTotal = if (r.continuous) r.loadAmps * values.continuousLoadFactor else r.loadAmps
        val minSize = when {
            sets > 1 -> values.parallelMinimumSize
            material.isCopper -> values.minimumSizeCopper
            else -> values.minimumSizeAluminum
        }

        var chosen: ConductorSize? = null
        var chosenUsable: Double? = null
        for (size in ConductorSize.entries) {
            if (size < minSize) continue
            val table = Ampacity.of(size, material, r.insulationRating)?.toDouble() ?: continue
            val termination = Ampacity.of(size, material, terminationColumn) ?: continue
            val adjusted = table * ambientFactor * adjustment
            val usable = minOf(adjusted, termination.toDouble())
            val totalTerm = termination.toDouble() * sets
            val totalAdj = adjusted * sets
            val totalUsable = usable * sets

            if (totalTerm < terminalLoadTotal - 1e-9) continue
            if (totalAdj < r.loadAmps - 1e-9) continue
            if (sets == 1) {
                val smallLimit = SmallConductorLimit.maxOcpd(size, material)
                if (smallLimit != null && r.ocpdAmps > smallLimit) continue
            }
            val maxOcpd = ConductorProtection.maxStandardOcpd(totalUsable) ?: 0
            if (r.ocpdAmps > maxOcpd) continue

            chosen = size
            chosenUsable = usable
            break
        }

        if (chosen == null || chosenUsable == null) {
            return ConductorOption(
                material, sets, null, null, null, null, null, false, null, null, null,
                "No single conductor size up to ${ConductorSize.entries.last().label} fits $sets set${if (sets == 1) "" else "s"}.",
            )
        }

        val totalAmpacity = chosenUsable * sets
        val egc = EquipmentGroundingConductor.size(r.ocpdAmps, material)

        // Voltage drop using NEC Chapter 9 Table 9 effective impedance divided by parallel sets.
        val t9Conduit = table9Conduit(r.conduitType)
        val impedance = ConductorImpedance.row(chosen, material)?.effectiveZ(t9Conduit, r.powerFactor)
            ?: ConductorResistance.ohmsPerKft(chosen, material)
        val vdVolts = impedance?.let { z ->
            r.phases.dropFactor * r.loadAmps * (r.lengthFeet / 1_000.0 * z / sets)
        }
        val vdPercent = vdVolts?.let { 100.0 * it / r.voltage }
        val meetsVd = vdPercent != null && vdPercent <= r.voltageDropTargetPercent + 1e-9

        // Minimum conduit per raceway (one set of hots + optional neutral + one full-size EGC per 250.122(F)).
        val mainCount = hotsPerRaceway + if (r.includeNeutral) 1 else 0
        val groups = buildList {
            add(ConductorGroup(mainCount, FillConductor.Insulated(r.insulation, chosen)))
            if (egc != null) add(ConductorGroup(1, FillConductor.Insulated(r.insulation, egc)))
        }
        val minConduit = ConduitFill.minimumConduit(r.conduitType, groups)
        val fillPercent = minConduit?.let { ConduitFill.analyze(it, groups).percentFill }

        val note = when {
            vdPercent != null && !meetsVd -> "Exceeds ${r.voltageDropTargetPercent}% voltage drop target."
            sets > 1 -> "Parallel per ${CodeRule.PARALLEL_CONDUCTORS.cite} (min ${values.parallelMinimumSize.label}); full-size EGC in each raceway (${CodeRule.EGC_SIZE.cite})."
            else -> null
        }

        return ConductorOption(
            material = material,
            sets = sets,
            size = chosen,
            ampacityPerConductor = chosenUsable,
            totalAmpacity = totalAmpacity,
            voltageDropVolts = vdVolts,
            voltageDropPercent = vdPercent,
            meetsVoltageDropTarget = meetsVd,
            egcSize = egc,
            minConduit = minConduit,
            conduitFillPercent = fillPercent,
            note = note,
        )
    }

    private fun table9Conduit(type: ConduitType): Table9Conduit = when (type) {
        ConduitType.EMT, ConduitType.RMC, ConduitType.IMC, ConduitType.FMC, ConduitType.LFMC -> Table9Conduit.STEEL
        else -> Table9Conduit.PVC
    }
}
