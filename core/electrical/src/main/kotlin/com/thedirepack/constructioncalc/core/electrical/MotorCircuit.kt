package com.thedirepack.constructioncalc.core.electrical

import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule

/**
 * Inputs for sizing an across-the-line single-motor branch circuit per NEC Article 430.
 *
 * @property phases single- or three-phase AC
 * @property hp motor horsepower (must exist in Table 430.248 / 430.250 for [volts])
 * @property volts motor table voltage column (e.g. 115, 230, 460, 575)
 * @property motorType motor type row in Table 430.52(C)(1)
 * @property protectiveDevice short-circuit and ground-fault protective device column in Table 430.52(C)(1)
 * @property material conductor metal (copper, aluminum, etc.)
 * @property insulationRating conductor insulation temperature rating (default 75 °C)
 * @property terminalRating termination temperature rating per 110.14(C) (default 75 °C)
 * @property nameplateFla optional motor nameplate FLA for overload sizing (430.6(A)(2)); defaults to table FLC when null
 * @property serviceFactor motor service factor (≥ 1.15 allows 125% overload per 430.32(A)(1))
 * @property tempRiseC motor marked temperature rise in °C (≤ 40 °C allows 125% overload per 430.32(A)(1))
 */
data class MotorCircuitRequest(
    val phases: AcPhases,
    val hp: Double,
    val volts: Int,
    val motorType: MotorType = if (phases == AcPhases.SINGLE) MotorType.SINGLE_PHASE else MotorType.DESIGN_B_EFFICIENT,
    val protectiveDevice: MotorProtectiveDevice = MotorProtectiveDevice.INVERSE_TIME_BREAKER,
    val material: ConductorMaterial = ConductorMaterial.COPPER,
    val insulationRating: TemperatureRating = TemperatureRating.C75,
    val terminalRating: TemperatureRating = TemperatureRating.C75,
    val nameplateFla: Double? = null,
    val serviceFactor: Double = 1.15,
    val tempRiseC: Double = 40.0,
)

/**
 * Complete NEC Article 430 across-the-line single-motor branch-circuit sizing result.
 */
data class MotorCircuitResult(
    /** Table 430.248 / 430.250 full-load current, A (430.6(A)(1)). */
    val flcAmps: Double,
    /** Minimum branch-circuit conductor ampacity = 125% × FLC, A (430.22). */
    val minConductorAmpacity: Double,
    /** Governing Table 310.16 temperature column (lower of insulation and terminal rating). */
    val ampacityColumn: TemperatureRating,
    /** Smallest conductor meeting [minConductorAmpacity] in [ampacityColumn] (respecting 310.3(A) min size). */
    val conductorSize: ConductorSize?,
    /** Table 430.52(C)(1) maximum percentage of FLC for the chosen device. */
    val maxOcpdPercent: Int,
    /** Exact calculated max OCPD = FLC × [maxOcpdPercent] / 100, A. */
    val calculatedMaxOcpdAmps: Double,
    /** Maximum standard OCPD rating (next standard size up per 430.52(C)(1)(a) for fuses and inverse-time breakers). */
    val maxStandardOcpdAmps: Int?,
    /** Current used to size overloads: nameplate FLA if provided, otherwise table FLC. */
    val overloadBasisAmps: Double,
    /** True when [overloadBasisAmps] came from the motor nameplate rather than falling back to table FLC. */
    val usedNameplateForOverload: Boolean,
    /** Overload multiplier (1.25 or 1.15 per 430.32(A)(1)). */
    val overloadFactor: Double,
    /** Maximum running overload protection setting, A (430.32(A)(1)). */
    val maxOverloadAmps: Double,
    /** Minimum disconnecting means ampere rating = 115% × FLC, A (430.110(A)). */
    val minDisconnectAmps: Double,
    /** Minimum equipment grounding conductor per Table 250.122 based on [maxStandardOcpdAmps]. */
    val egcSize: ConductorSize?,
)

/**
 * Across-the-line single-motor branch-circuit sizing per NEC Article 430, reusing
 * [MotorFullLoadCurrent], [MotorProtection], [Ampacity], [StandardOcpd], and
 * [EquipmentGroundingConductor], with all factors read from [Code.book].
 */
object MotorCircuit {
    /** Sizes the motor branch circuit, or returns null if the HP/voltage pair isn't in Tables 430.248 / 430.250. */
    fun size(request: MotorCircuitRequest): MotorCircuitResult? {
        val flc = MotorFullLoadCurrent.amps(request.phases, request.hp, request.volts) ?: return null
        val values = Code.book.values

        val minConductorAmpacity = flc * values.motorConductorFactor
        val column = if (request.insulationRating.celsius <= request.terminalRating.celsius) {
            request.insulationRating
        } else {
            request.terminalRating
        }
        val minAllowedSize = if (request.material.isCopper) values.minimumSizeCopper else values.minimumSizeAluminum
        val conductorSize = Ampacity.sizes(request.material, column)
            .firstOrNull { size ->
                size.circularMils >= minAllowedSize.circularMils &&
                    (Ampacity.of(size, request.material, column) ?: 0) >= minConductorAmpacity - 1e-9
            }

        val maxOcpdPercent = MotorProtection.percent(request.motorType, request.protectiveDevice)
        val calcMaxOcpd = flc * maxOcpdPercent / 100.0
        val isFuse = request.protectiveDevice == MotorProtectiveDevice.NONTIME_DELAY_FUSE ||
            request.protectiveDevice == MotorProtectiveDevice.DUAL_ELEMENT_FUSE
        // 430.52(C)(1)(a): next higher standard rating allowed for fuses and inverse-time breakers;
        // instantaneous-trip breakers are adjustable and don't use the 240.6(A) next-size-up rule.
        val maxStandardOcpd = if (request.protectiveDevice == MotorProtectiveDevice.INSTANTANEOUS_TRIP_BREAKER) {
            kotlin.math.ceil(calcMaxOcpd).toInt()
        } else {
            StandardOcpd.nextUp(calcMaxOcpd, fuse = isFuse)
        }

        val nameplate = request.nameplateFla?.takeIf { it.isFinite() && it > 0.0 }
        val overloadBasis = nameplate ?: flc
        val highOverload = request.serviceFactor >= values.motorOverloadServiceFactor - 1e-9 ||
            request.tempRiseC <= values.motorOverloadMaxRiseC + 1e-9
        val overloadFactor = if (highOverload) values.motorOverloadFactor else values.motorOverloadFactorOther
        val maxOverload = overloadBasis * overloadFactor

        val minDisconnect = flc * values.motorDisconnectFactor
        val egc = maxStandardOcpd?.let { EquipmentGroundingConductor.size(it, request.material) }

        return MotorCircuitResult(
            flcAmps = flc,
            minConductorAmpacity = minConductorAmpacity,
            ampacityColumn = column,
            conductorSize = conductorSize,
            maxOcpdPercent = maxOcpdPercent,
            calculatedMaxOcpdAmps = calcMaxOcpd,
            maxStandardOcpdAmps = maxStandardOcpd,
            overloadBasisAmps = overloadBasis,
            usedNameplateForOverload = nameplate != null,
            overloadFactor = overloadFactor,
            maxOverloadAmps = maxOverload,
            minDisconnectAmps = minDisconnect,
            egcSize = egc,
        )
    }
}
