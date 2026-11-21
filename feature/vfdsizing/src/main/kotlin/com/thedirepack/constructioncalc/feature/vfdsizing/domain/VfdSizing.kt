package com.thedirepack.constructioncalc.feature.vfdsizing.domain

import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.electrical.Ampacity
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.MotorProtection
import com.thedirepack.constructioncalc.core.electrical.MotorProtectiveDevice
import com.thedirepack.constructioncalc.core.electrical.MotorType
import com.thedirepack.constructioncalc.core.electrical.MotorFullLoadCurrent
import com.thedirepack.constructioncalc.core.electrical.AcPhases
import com.thedirepack.constructioncalc.core.electrical.StandardOcpd
import com.thedirepack.constructioncalc.core.electrical.TemperatureRating
import kotlin.math.abs

/** Whether the motor rating is entered in horsepower or kilowatts. */
enum class RatingUnit(val label: String) { HP("HP"), KW("kW") }

/**
 * Which drive current rating applies. Drives are often rated twice: a higher continuous current for
 * variable-torque (normal-duty) loads that need ~110 % overload for a minute, and a lower one for
 * constant-torque (heavy-duty) loads that need ~150 %.
 */
enum class LoadType(val label: String, val dutyLabel: String) {
    VARIABLE_TORQUE("Variable torque (fans, pumps)", "Normal duty"),
    CONSTANT_TORQUE("Constant torque (conveyors, compressors)", "Heavy duty"),
}

/** What the drive's minimum output current is based on. */
enum class OutputBasis { NAMEPLATE_FLA, TABLE_FLC }

/** One input to [VfdSizing.calculate]. */
data class VfdRequest(
    val hp: Double,
    val volts: Int,
    val supplyPhase: AcPhases = AcPhases.THREE,
    /** Motor nameplate full-load amps. Optional. */
    val nameplateFla: Double? = null,
    /** The drive's rated input current from its nameplate or manual. Optional. */
    val driveInputAmps: Double? = null,
    val loadType: LoadType = LoadType.VARIABLE_TORQUE,
    val material: ConductorMaterial = ConductorMaterial.COPPER,
    val termination: TemperatureRating = TemperatureRating.C75,
)

/** A conductor selection: [sets] identical conductors per phase, each of [size]. */
data class ConductorChoice(val size: ConductorSize, val sets: Int, val ampacityEach: Int) {
    val totalAmpacity: Int get() = sets * ampacityEach

    fun text(material: ConductorMaterial): String {
        val metal = material.shortLabel // "Cu", "Cu coated", "Al", "CCA"
        return if (sets == 1) "${size.label} $metal" else "$sets × ${size.label} $metal per phase"
    }
}

data class VfdResult(
    /** Table 430.250 full-load current for the motor's HP and voltage. */
    val tableFlc: Double,
    /** The motor's rated current the drive must be able to deliver continuously. */
    val minDriveOutputAmps: Double,
    val outputBasis: OutputBasis,
    /** Rule-of-thumb drive output rating when fed from single-phase; null for three-phase supply. */
    val singlePhaseDriveGuidanceAmps: Double?,
    /** Nameplate FLA × 125 %, the usual maximum overload-device setting (430.32(A)(1)). */
    val overloadMaxAmps: Double?,
    val nameplatePercentOfTable: Double?,
    /** The input current the input conductors are sized from. */
    val inputBasisAmps: Double,
    /** True if [inputBasisAmps] is the 125 %-of-FLC approximation rather than the drive's rating. */
    val inputBasisIsEstimate: Boolean,
    val inputAmpacityRequired: Double,
    /** Null if no single size or parallel arrangement in Table 310.16 is large enough. */
    val inputConductor: ConductorChoice?,
    val outputAmpacityRequired: Double,
    val outputConductor: ConductorChoice?,
    /** Disconnect rating, minimum: 115 % of the drive's input current (430.128). */
    val disconnectMinAmps: Double,
    /** 250 % of FLC, before rounding. */
    val breakerMaxCalcAmps: Double,
    val breakerMax: Int?,
    /** 175 % of FLC, before rounding. */
    val fuseMaxCalcAmps: Double,
    val fuseMax: Int?,
)

/** NEC 2023 Article 430 rules for the branch circuit of a motor fed by a variable frequency drive. */
object VfdSizing {
    /** Motor voltages offered: three-phase table columns up to 600 V (Part X). 2300 V is Part XI and out of scope. */
    val MOTOR_VOLTAGES: List<Int> = listOf(200, 208, 230, 460, 575)

    /** 430.122 and 430.22 conductor factor. */
    const val CONDUCTOR_FACTOR = 1.25
    /** 430.128 disconnecting means factor. */
    const val DISCONNECT_FACTOR = 1.15
    /** Table 430.52(C)(1), inverse-time circuit breaker, three-phase induction motor. */
    val BREAKER_FACTOR = MotorProtection.factor(MotorType.POLYPHASE_AC, MotorProtectiveDevice.INVERSE_TIME_BREAKER)
    /** Table 430.52(C)(1), time-delay (dual-element) fuse, three-phase induction motor. */
    val DUAL_ELEMENT_FUSE_FACTOR = MotorProtection.factor(MotorType.POLYPHASE_AC, MotorProtectiveDevice.DUAL_ELEMENT_FUSE)
    /** Overload device maximum, motors with service factor 1.15 or more or ≤ 40 °C rise (430.32(A)(1)). */
    const val OVERLOAD_FACTOR = 1.25
    /** Rule of thumb only, not a code requirement. */
    const val SINGLE_PHASE_DRIVE_MULTIPLIER = 2.0
    /**
     * With no drive input rating, a single-phase supply's input current is estimated at this many
     * times the motor's three-phase FLC: √3 from the power balance, rounded up to 2 for the
     * rectifier's poor input power factor. Conservative; the drive's rating replaces it.
     */
    const val SINGLE_PHASE_INPUT_ESTIMATE_FACTOR = 2.0
    /** Parallel sets considered when one conductor isn't enough. */
    const val MAX_SETS = 6
    /** 310.10(G): parallel conductors are 1/0 AWG or larger. */
    private val MIN_PARALLEL_SIZE = ConductorSize.AWG_1_0
    private const val KW_PER_HP = 0.7457

    /** Standard horsepower ratings with a Table 430.250 value at [volts]. */
    fun horsepowers(volts: Int): List<Double> = MotorFullLoadCurrent.horsepowers(AcPhases.THREE, volts)

    /**
     * The standard horsepower for a motor rated [kw]: the smallest table rating at least the converted
     * horsepower, allowing 2 % under (a "7.5 kW" motor is 10.06 HP and counts as 10 HP). Null if
     * [kw] is not positive or exceeds the largest rating at [volts].
     */
    fun horsepowerForKw(kw: Double, volts: Int): Double? {
        if (!(kw > 0) || !kw.isFinite()) return null
        val hp = kw / KW_PER_HP
        return horsepowers(volts).firstOrNull { it >= hp * 0.98 }
    }

    /** Why [request] can't be calculated, or empty. */
    fun problems(request: VfdRequest): List<String> = buildList {
        if (request.volts !in MOTOR_VOLTAGES) add("Voltage ${request.volts} V isn't supported.")
        else if (MotorFullLoadCurrent.threePhase(request.hp, request.volts) == null) {
            add("${CodeRule.MOTOR_FLC_THREE_PHASE.cite} has no ${MotorFullLoadCurrent.label(request.hp)} HP value at ${request.volts} V.")
        }
        request.nameplateFla?.let { if (!(it > 0)) add("Nameplate FLA must be more than zero.") }
        request.driveInputAmps?.let { if (!(it > 0)) add("Drive input current must be more than zero.") }
    }

    fun calculate(request: VfdRequest): VfdResult? {
        if (problems(request).isNotEmpty()) return null
        val flc = MotorFullLoadCurrent.threePhase(request.hp, request.volts) ?: return null
        val fla = request.nameplateFla
        val minOutput = fla ?: flc
        val inputEstimate = request.driveInputAmps == null
        val inputBasis = request.driveInputAmps
            ?: if (request.supplyPhase == AcPhases.SINGLE) flc * SINGLE_PHASE_INPUT_ESTIMATE_FACTOR else flc
        val inputRequired = inputBasis * CONDUCTOR_FACTOR
        val outputRequired = flc * CONDUCTOR_FACTOR
        val breakerCalc = flc * BREAKER_FACTOR
        val fuseCalc = flc * DUAL_ELEMENT_FUSE_FACTOR
        return VfdResult(
            tableFlc = flc,
            minDriveOutputAmps = minOutput,
            outputBasis = if (fla != null) OutputBasis.NAMEPLATE_FLA else OutputBasis.TABLE_FLC,
            singlePhaseDriveGuidanceAmps = if (request.supplyPhase == AcPhases.SINGLE) minOutput * SINGLE_PHASE_DRIVE_MULTIPLIER else null,
            overloadMaxAmps = fla?.let { it * OVERLOAD_FACTOR },
            nameplatePercentOfTable = fla?.let { 100.0 * it / flc },
            inputBasisAmps = inputBasis,
            inputBasisIsEstimate = inputEstimate,
            inputAmpacityRequired = inputRequired,
            inputConductor = conductorFor(inputRequired, request.material, request.termination),
            outputAmpacityRequired = outputRequired,
            outputConductor = conductorFor(outputRequired, request.material, request.termination),
            disconnectMinAmps = inputBasis * DISCONNECT_FACTOR,
            breakerMaxCalcAmps = breakerCalc,
            breakerMax = StandardOcpd.nextUp(breakerCalc),
            fuseMaxCalcAmps = fuseCalc,
            fuseMax = StandardOcpd.nextUp(fuseCalc, fuse = true),
        )
    }

    /**
     * The smallest Table 310.16 conductor in the given column with ampacity of at least [requiredAmps]. When
     * no single conductor is large enough, the fewest parallel sets (1/0 AWG or larger each, sharing the
     * current equally) that works. Null if even [MAX_SETS] sets of 2000 kcmil are not enough.
     */
    fun conductorFor(requiredAmps: Double, material: ConductorMaterial, rating: TemperatureRating): ConductorChoice? {
        Ampacity.smallestSize(requiredAmps, material, rating)?.let {
            return ConductorChoice(it, 1, Ampacity.of(it, material, rating)!!)
        }
        for (sets in 2..MAX_SETS) {
            val perSet = requiredAmps / sets
            val size = Ampacity.sizes(material, rating)
                .firstOrNull { it.circularMils >= MIN_PARALLEL_SIZE.circularMils && (Ampacity.of(it, material, rating) ?: 0) >= perSet - 1e-9 }
            if (size != null) return ConductorChoice(size, sets, Ampacity.of(size, material, rating)!!)
        }
        return null
    }

    /** The nearest listed horsepower to [hp] at [volts], for when a voltage change makes a rating unavailable. */
    fun nearestHorsepower(hp: Double, volts: Int): Double {
        val list = horsepowers(volts)
        return list.firstOrNull { abs(it - hp) < 1e-6 } ?: list.firstOrNull { it >= hp } ?: list.last()
    }
}
