package com.thedirepack.constructioncalc.feature.voltagedrop.domain

import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.electrical.AcPhases
import com.thedirepack.constructioncalc.core.electrical.ConductorImpedance
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.ConductorResistance
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.Stranding
import com.thedirepack.constructioncalc.core.electrical.Table9Conduit
import kotlinx.serialization.Serializable
import kotlin.math.floor
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
    val powerFactor: Double = 0.85,
    val targetPercent: Double = 3.0,
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
        if (!(request.volts > 0)) add("Voltage must be greater than 0.")
        if (!(request.amps > 0)) add("Load current must be greater than 0.")
        if (!(request.lengthFeet > 0)) add("Length must be greater than 0.")
        if (request.parallelSets !in 1..MAX_SETS) add("Parallel sets must be 1 to $MAX_SETS.")
        if (!(request.targetPercent > 0 && request.targetPercent < 100)) add("Target drop must be between 0 and 100%.")
        if (request.method == Method.AC_TABLE_9 && !(request.powerFactor > 0 && request.powerFactor <= 1)) {
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

    /** Rounds down to a 1/64 inch step, so a stated maximum never exceeds the true limit. */
    fun floorToSixtyFourths(inches: Double): Long = floor(inches * 64 + 1e-9).toLong()
}
