package com.thedirepack.constructioncalc.feature.voltagedrop.domain

import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.electrical.code.codePercent
import com.thedirepack.constructioncalc.core.electrical.AcPhases
import com.thedirepack.constructioncalc.core.electrical.AmbientCorrection
import com.thedirepack.constructioncalc.core.electrical.Ampacity
import com.thedirepack.constructioncalc.core.electrical.ConductorCountAdjustment
import com.thedirepack.constructioncalc.core.electrical.ConductorImpedance
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.ConductorProtection
import com.thedirepack.constructioncalc.core.electrical.ConductorResistance
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.SmallConductorLimit
import com.thedirepack.constructioncalc.core.electrical.Stranding
import com.thedirepack.constructioncalc.core.electrical.Table9Conduit
import com.thedirepack.constructioncalc.core.electrical.TemperatureRating
import com.thedirepack.constructioncalc.core.measure.Decimals
import com.thedirepack.constructioncalc.core.measure.Measure
import com.thedirepack.constructioncalc.core.measure.UnitSystem
import kotlinx.serialization.Serializable
import kotlin.math.sqrt

/** How the conductor impedance per 1000 ft is found. */
@Serializable
enum class Method(val label: String) {
    /** NEC Chapter 9 Table 9: effective impedance `Z = R·PF + X·sin(acos PF)`. */
    AC_TABLE_9("NEC ${CodeRule.AC_IMPEDANCE.cite} (AC impedance)"),

    /** NEC Chapter 9 Table 8: DC resistance at 75 °C, the same thing a K-factor formula approximates. */
    DC_TABLE_8("DC resistance (${CodeRule.CONDUCTOR_PROPERTIES.cite} / K-factor)"),
}

/** Everything the calculation needs, in plain units (volts, amps, feet). */
data class VoltageDropRequest(
    val system: AcPhases,
    val volts: Double,
    val material: ConductorMaterial,
    val amps: Double,
    /** One-way length of the run, in feet. */
    val lengthFeet: Double,
    val size: ConductorSize,
    /** Identical conductors in parallel per phase. */
    val parallelSets: Int = 1,
    val method: Method = Method.AC_TABLE_9,
    val conduit: Table9Conduit = Table9Conduit.PVC,
    val powerFactor: Double = Code.book.values.acImpedanceTablePowerFactor,
    val targetPercent: Double = Code.book.values.voltageDropBranchPercent,
    /** Solid or stranded, for the DC method's Table 8 rows (18–8 AWG differ). */
    val stranding: Stranding = Stranding.STRANDED,
)

data class VoltageDropResult(
    val dropVolts: Double,
    val percent: Double,
    val voltsAtLoad: Double,
    /** The Z used, Ω per 1000 ft of one conductor. */
    val ohmsPerKft: Double,
    /** Longest one-way run (feet) of the selected size that stays within the target. */
    val maxLengthFeet: Double,
    val meetsTarget: Boolean,
)

/** Conditions for evaluating NEC ampacity alongside a voltage drop request. */
data class AmpacityCheckRequest(
    val insulation: TemperatureRating = TemperatureRating.C75,
    val termination: TemperatureRating = TemperatureRating.C75,
    val ambientC: Double = Code.book.values.ambientBasisC,
    val currentCarrying: Int = 3,
    val continuousLoad: Boolean = false,
    val ocpdAmps: Int? = null,
)

/** Which sizing constraint determines the minimum required conductor size. */
enum class GoverningConstraint(val label: String) {
    VOLTAGE_DROP("Voltage drop"),
    AMPACITY("Ampacity"),
    BOTH("Voltage drop & ampacity"),
    UNSOLVED("Exceeds table"),
}

/** Combined NEC ampacity evaluation and comparison against the voltage-drop minimum size. */
data class AmpacitySizingResult(
    val minimumSize: ConductorSize?,
    val minimumUsableAmpacity: Double?,
    val selectedUsableAmpacity: Double?,
    val selectedMeetsAmpacity: Boolean,
    val selectedFailure: String?,
    val requiredLoadAmps: Double,
    val requiredTerminalAmps: Double,
    val ambientFactor: Double?,
    val adjustmentFactor: Double,
    val governingSize: ConductorSize?,
    val governing: GoverningConstraint,
    val notes: List<String>,
)

/**
 * Voltage-drop math.
 *
 * ```
 * single-phase:  VD = 2  · I · L · Z / 1000 / sets
 * three-phase:   VD = √3 · I · L · Z / 1000 / sets
 * % drop = 100 · VD / V          voltage at load = V − VD
 * ```
 * with `I` in amps, `L` the one-way length in feet, `V` the source voltage (line-to-line for
 * three-phase) and `Z` in Ω per 1000 ft of one conductor:
 *
 * - [Method.DC_TABLE_8]: `Z = R`, NEC Chapter 9 Table 8, DC resistance at 75 °C, **uncoated**
 *   copper or aluminum (the "Uncoated Copper" and "Aluminum" columns, not "Coated Copper"),
 *   stranded by default; solid rows for 18–8 AWG when [VoltageDropRequest.stranding] says so.
 * - [Method.AC_TABLE_9]: `Z = R·PF + X·sin(acos PF)`, using Table 9's AC resistance `R` for the
 *   conduit type and its reactance `X` (PVC and aluminum conduit share one X column, steel has
 *   its own). At PF 0.85 this reproduces Table 9's printed "effective Z" column. Table 9 is for
 *   three single conductors in conduit at 75 °C; the same Z is used for single-phase.
 *
 * Parallel sets split the current equally, so the drop is divided by the number of sets.
 * Maximum length is the same formula solved for `L` at the target percent.
 * Ampacity, terminal temperature limits and overcurrent protection are not checked.
 */
object VoltageDrop {
    const val MAX_SETS = 6
    const val CONDUCTOR_TEMPERATURE_C = 75

    /** Sizes the chosen method's table covers for [material], smallest first. */
    fun supportedSizes(material: ConductorMaterial, method: Method): List<ConductorSize> = when (method) {
        Method.AC_TABLE_9 -> ConductorImpedance.sizes(material)
        Method.DC_TABLE_8 -> ConductorResistance.sizes(material)
    }

    /** Z in Ω per 1000 ft, or null when the table has no entry for this size. */
    fun impedancePerKft(
        size: ConductorSize,
        material: ConductorMaterial,
        method: Method,
        conduit: Table9Conduit,
        powerFactor: Double,
        stranding: Stranding = Stranding.STRANDED,
    ): Double? = when (method) {
        Method.DC_TABLE_8 -> ConductorResistance.ohmsPerKft(size, material, stranding)
        Method.AC_TABLE_9 -> ConductorImpedance.row(size, material)?.effectiveZ(conduit, powerFactor)
    }

    /** Human-readable problems that stop a calculation. Empty when [request] can be calculated. */
    fun problems(request: VoltageDropRequest): List<String> = buildList {
        if (!(request.volts.isFinite() && request.volts > 0)) add("Voltage must be greater than 0.")
        if (!(request.amps.isFinite() && request.amps > 0)) add("Load current must be greater than 0.")
        if (!(request.lengthFeet.isFinite() && request.lengthFeet > 0)) add("Length must be greater than 0.")
        if (request.parallelSets !in 1..MAX_SETS) add("Parallel sets must be 1 to $MAX_SETS.")
        if (!(request.targetPercent.isFinite() && request.targetPercent > 0 && request.targetPercent < 100)) add("Target drop must be between 0 and 100%.")
        if (request.method == Method.AC_TABLE_9 && !(request.powerFactor.isFinite() && request.powerFactor > 0 && request.powerFactor <= 1)) {
            add("Power factor must be above 0 and at most 1.")
        }
        if (impedancePerKft(request.size, request.material, request.method, request.conduit, request.powerFactor.coerceIn(0.01, 1.0), request.stranding) == null) {
            val table = if (request.method == Method.AC_TABLE_9) "NEC ${CodeRule.AC_IMPEDANCE.cite}" else "NEC ${CodeRule.CONDUCTOR_PROPERTIES.cite}"
            add("$table has no ${request.size.label} ${request.material.label.lowercase()} entry. Pick another size or method.")
        }
    }

    /** Null when [problems] is not empty. */
    fun calculate(request: VoltageDropRequest): VoltageDropResult? {
        if (problems(request).isNotEmpty()) return null
        val z = impedancePerKft(request.size, request.material, request.method, request.conduit, request.powerFactor, request.stranding)!!
        val drop = dropVolts(request.system, request.amps, request.lengthFeet, z, request.parallelSets)
        val percent = 100 * drop / request.volts
        return VoltageDropResult(
            dropVolts = drop,
            percent = percent,
            voltsAtLoad = request.volts - drop,
            ohmsPerKft = z,
            maxLengthFeet = maxLengthFeet(request.system, request.volts, request.amps, z, request.parallelSets, request.targetPercent),
            meetsTarget = percent <= request.targetPercent + 1e-9,
        )
    }

    fun dropVolts(system: AcPhases, amps: Double, lengthFeet: Double, zPerKft: Double, sets: Int): Double =
        system.dropFactor * amps * lengthFeet * zPerKft / 1000.0 / sets

    /** One-way feet at which the drop equals [targetPercent] of [volts]. */
    fun maxLengthFeet(system: AcPhases, volts: Double, amps: Double, zPerKft: Double, sets: Int, targetPercent: Double): Double =
        targetPercent / 100.0 * volts * 1000.0 * sets / (system.dropFactor * amps * zPerKft)

    /**
     * The smallest size in the method's table that meets the target with every other input
     * unchanged, or null if even the largest listed size does not.
     */
    fun smallestSize(request: VoltageDropRequest): ConductorSize? {
        if (problemsIgnoringSize(request).isNotEmpty()) return null
        return supportedSizes(request.material, request.method).firstOrNull { size ->
            calculate(request.copy(size = size))?.meetsTarget == true
        }
    }

    private fun problemsIgnoringSize(request: VoltageDropRequest): List<String> {
        val anySize = supportedSizes(request.material, request.method).first()
        return problems(request.copy(size = anySize))
    }

    private data class AmpacityEvaluation(
        val usableTotal: Double?,
        val failure: String?,
    )

    /**
     * Evaluates NEC ampacity, ambient and conductor-count derating, termination rating, continuous
     * load, and optional overcurrent protection for [request], and compares the ampacity minimum
     * size against [dropMinimum] to identify the governing constraint.
     */
    fun checkAmpacity(
        request: VoltageDropRequest,
        ampacity: AmpacityCheckRequest,
        dropMinimum: ConductorSize?,
        units: UnitSystem = UnitSystem.IMPERIAL,
    ): AmpacitySizingResult {
        val values = Code.book.values
        val continuousFactor = values.continuousLoadFactor
        val requiredAdjustedAmps = request.amps
        val requiredTerminalAmps = if (ampacity.continuousLoad) request.amps * continuousFactor else request.amps
        val ambientFactor = AmbientCorrection.factor(ampacity.ambientC, ampacity.insulation)
        val adjustment = ConductorCountAdjustment.factor(ampacity.currentCarrying)

        if (ambientFactor == null) {
            val ambientText = if (ampacity.ambientC.isFinite()) {
                Measure.TEMPERATURE.format(ampacity.ambientC, units, 0)
            } else {
                ampacity.ambientC.toString()
            }
            val note = "No ${ampacity.insulation.label} ambient correction factor at $ambientText (${CodeRule.AMBIENT_CORRECTION.cite})."
            return AmpacitySizingResult(
                minimumSize = null,
                minimumUsableAmpacity = null,
                selectedUsableAmpacity = null,
                selectedMeetsAmpacity = false,
                selectedFailure = note,
                requiredLoadAmps = requiredAdjustedAmps,
                requiredTerminalAmps = requiredTerminalAmps,
                ambientFactor = null,
                adjustmentFactor = adjustment,
                governingSize = null,
                governing = GoverningConstraint.UNSOLVED,
                notes = listOf(note),
            )
        }

        val terminationCol = if (ampacity.termination.celsius < ampacity.insulation.celsius) {
            ampacity.termination
        } else {
            ampacity.insulation
        }
        val sets = request.parallelSets
        val minCodeSize = if (request.material.isCopper) values.minimumSizeCopper else values.minimumSizeAluminum
        val candidates = supportedSizes(request.material, request.method)

        fun evaluate(size: ConductorSize): AmpacityEvaluation {
            if (sets > 1 && size.circularMils < values.parallelMinimumSize.circularMils) {
                return AmpacityEvaluation(
                    null,
                    "Parallel conductors must be at least ${values.parallelMinimumSize.label} (${CodeRule.PARALLEL_CONDUCTORS.cite}).",
                )
            }
            if (size.circularMils < minCodeSize.circularMils) {
                return AmpacityEvaluation(
                    null,
                    "Minimum conductor size for ${request.material.label.lowercase()} is ${minCodeSize.label} (${CodeRule.MINIMUM_CONDUCTOR_SIZE.cite}).",
                )
            }
            val table = Ampacity.of(size, request.material, ampacity.insulation)?.toDouble()
            val term = Ampacity.of(size, request.material, terminationCol)?.toDouble()
            if (table == null || term == null) {
                return AmpacityEvaluation(
                    null,
                    "No ${CodeRule.AMPACITY_TABLE.cite} entry for ${size.label} ${request.material.label.lowercase()} at ${ampacity.insulation.label} / ${terminationCol.label}.",
                )
            }
            val adjustedTotal = table * ambientFactor * adjustment * sets
            val termTotal = term * sets
            val usableTotal = minOf(adjustedTotal, termTotal)

            if (termTotal < requiredTerminalAmps - 1e-9) {
                val msg = if (ampacity.continuousLoad) {
                    "Terminal ampacity ${Decimals.trim(termTotal, 1)} A at ${terminationCol.label} is below ${Decimals.trim(requiredTerminalAmps, 1)} A (${codePercent(continuousFactor, spaced = false)} continuous load: ${CodeRule.BRANCH_CONDUCTOR_CONTINUOUS.cite}, ${CodeRule.TERMINATION_TEMPERATURE.cite})."
                } else {
                    "Terminal ampacity ${Decimals.trim(termTotal, 1)} A at ${terminationCol.label} is below ${Decimals.trim(requiredTerminalAmps, 1)} A (${CodeRule.TERMINATION_TEMPERATURE.cite})."
                }
                return AmpacityEvaluation(usableTotal, msg)
            }
            if (adjustedTotal < requiredAdjustedAmps - 1e-9) {
                return AmpacityEvaluation(
                    usableTotal,
                    "Derated ampacity ${Decimals.trim(adjustedTotal, 1)} A is below ${Decimals.trim(requiredAdjustedAmps, 1)} A (${CodeRule.AMPACITY.cite}).",
                )
            }
            if (ampacity.ocpdAmps != null) {
                if (sets == 1) {
                    val smallMax = SmallConductorLimit.maxOcpd(size, request.material)
                    if (smallMax != null && ampacity.ocpdAmps > smallMax) {
                        return AmpacityEvaluation(
                            usableTotal,
                            "OCPD ${ampacity.ocpdAmps} A exceeds ${smallMax} A small-conductor limit for ${size.label} (${CodeRule.SMALL_CONDUCTORS.cite}).",
                        )
                    }
                }
                val maxOcpd = ConductorProtection.maxStandardOcpd(usableTotal, multipleCordReceptacles = false) ?: 0
                if (ampacity.ocpdAmps > maxOcpd) {
                    return AmpacityEvaluation(
                        usableTotal,
                        "Usable ampacity ${Decimals.trim(usableTotal, 1)} A is inadequate for a ${ampacity.ocpdAmps} A OCPD (max ${maxOcpd} A per ${CodeRule.CONDUCTOR_PROTECTION.cite}).",
                    )
                }
            }
            return AmpacityEvaluation(usableTotal, null)
        }

        var minimumSize: ConductorSize? = null
        var minimumUsable: Double? = null
        for (size in candidates) {
            val eval = evaluate(size)
            if (eval.failure == null) {
                minimumSize = size
                minimumUsable = eval.usableTotal
                break
            }
        }

        val selectedEval = evaluate(request.size)

        val governingSize: ConductorSize?
        val governing: GoverningConstraint
        when {
            dropMinimum == null || minimumSize == null -> {
                governingSize = null
                governing = GoverningConstraint.UNSOLVED
            }
            dropMinimum.circularMils > minimumSize.circularMils -> {
                governingSize = dropMinimum
                governing = GoverningConstraint.VOLTAGE_DROP
            }
            minimumSize.circularMils > dropMinimum.circularMils -> {
                governingSize = minimumSize
                governing = GoverningConstraint.AMPACITY
            }
            else -> {
                governingSize = dropMinimum
                governing = GoverningConstraint.BOTH
            }
        }

        val notes = buildList {
            add(
                "Ampacity basis (${ampacity.insulation.label} insulation, ${terminationCol.label} terminals): " +
                    "ambient ${Measure.TEMPERATURE.format(ampacity.ambientC, units, 0)} (×${Decimals.trim(ambientFactor, 2)}, ${CodeRule.AMBIENT_CORRECTION.cite}), " +
                    "${ampacity.currentCarrying} current-carrying conductors (×${Decimals.trim(adjustment, 2)}, ${CodeRule.CONDUCTOR_COUNT_ADJUSTMENT.cite}).",
            )
            if (ampacity.continuousLoad) {
                add(
                    "Continuous load (${codePercent(continuousFactor, spaced = false)}): " +
                        "${Decimals.trim(requiredTerminalAmps, 1)} A required at ${terminationCol.label} terminals " +
                        "(${CodeRule.BRANCH_CONDUCTOR_CONTINUOUS.cite}, ${CodeRule.TERMINATION_TEMPERATURE.cite}).",
                )
            } else {
                add(
                    "Noncontinuous load (${codePercent(1.0, spaced = false)}): " +
                        "${Decimals.trim(requiredTerminalAmps, 1)} A required at terminals (${CodeRule.TERMINATION_TEMPERATURE.cite}).",
                )
            }
            if (ampacity.ocpdAmps != null) {
                add("Checked against ${ampacity.ocpdAmps} A OCPD (${CodeRule.CONDUCTOR_PROTECTION.cite}, ${CodeRule.SMALL_CONDUCTORS.cite}).")
                if (minimumSize != null && minimumUsable != null && minimumUsable < ampacity.ocpdAmps - 1e-9) {
                    add("Usable ampacity ${Decimals.trim(minimumUsable, 1)} A is below ${ampacity.ocpdAmps} A; permitted by ${CodeRule.NEXT_SIZE_UP.cite} next size up.")
                }
            } else {
                add("Overcurrent protection (OCPD) not checked — enter a breaker or fuse rating to verify ${CodeRule.CONDUCTOR_PROTECTION.cite} and ${CodeRule.SMALL_CONDUCTORS.cite}.")
            }
            if (sets > 1) {
                add("Parallel conductors ($sets sets): minimum ${values.parallelMinimumSize.label} (${CodeRule.PARALLEL_CONDUCTORS.cite}).")
            }
            if (minimumSize == null) {
                add("No listed size up to ${candidates.last().label} meets NEC ampacity requirements (${CodeRule.AMPACITY_TABLE.cite}).")
            }
        }

        return AmpacitySizingResult(
            minimumSize = minimumSize,
            minimumUsableAmpacity = minimumUsable,
            selectedUsableAmpacity = selectedEval.usableTotal,
            selectedMeetsAmpacity = selectedEval.failure == null,
            selectedFailure = selectedEval.failure,
            requiredLoadAmps = requiredAdjustedAmps,
            requiredTerminalAmps = requiredTerminalAmps,
            ambientFactor = ambientFactor,
            adjustmentFactor = adjustment,
            governingSize = governingSize,
            governing = governing,
            notes = notes,
        )
    }
}
