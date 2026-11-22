package com.thedirepack.constructioncalc.core.electrical

import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.electrical.code.StandardMetalBox

/**
 * One group of conductors counted in a box fill calculation ([CodeRule.BOX_FILL]).
 *
 * @property count number of conductors of [size] (conductors terminating/spliced count as 1 each;
 *                 an unbroken conductor looped/coiled ≥ 12 in. counts as 2, entered as 2 here)
 */
data class BoxConductorEntry(
    val size: ConductorSize,
    val count: Int,
) {
    init {
        require(count >= 0) { "Conductor count cannot be negative" }
    }
}

/**
 * A device yoke/strap (switch, receptacle, etc.) in a box.
 * Per NEC 314.16(B)(4), each yoke counts as 2× the volume of the largest conductor connected to it.
 */
data class BoxDeviceEntry(
    val count: Int,
    val conductorSize: ConductorSize,
) {
    init {
        require(count >= 0) { "Device count cannot be negative" }
    }
}

/**
 * Inputs for a [CodeRule.BOX_FILL] (NEC 314.16) box fill calculation.
 *
 * @property conductors current-carrying and neutral conductors (18–6 AWG)
 * @property hasInternalClamps true if the box contains one or more internal cable clamps (1× largest conductor)
 * @property supportFittings number of luminaire studs or hickeys (1× largest conductor each)
 * @property devices device yokes/straps (2× largest conductor connected to each yoke)
 * @property egcCount total number of equipment grounding conductors entering the box
 * @property largestEgcSize size of the largest EGC (defaults to largest conductor if null)
 */
data class BoxFillRequest(
    val conductors: List<BoxConductorEntry>,
    val hasInternalClamps: Boolean = false,
    val supportFittings: Int = 0,
    val devices: List<BoxDeviceEntry> = emptyList(),
    val egcCount: Int = 0,
    val largestEgcSize: ConductorSize? = null,
) {
    init {
        require(supportFittings >= 0) { "Support fittings cannot be negative" }
        require(egcCount >= 0) { "EGC count cannot be negative" }
    }
}

/** Breakdown of a [CodeRule.BOX_FILL] calculation, all volumes in cubic inches. */
data class BoxFillResult(
    val conductorVolumeCuIn: Double,
    val clampVolumeCuIn: Double,
    val supportVolumeCuIn: Double,
    val deviceVolumeCuIn: Double,
    val egcVolumeCuIn: Double,
    val totalVolumeCuIn: Double,
    val largestConductor: ConductorSize?,
    /** Standard metal boxes from [CodeRule.METAL_BOX_VOLUMES] large enough for [totalVolumeCuIn], smallest first. */
    val suitableBoxes: List<StandardMetalBox>,
) {
    val smallestBox: StandardMetalBox? get() = suitableBoxes.firstOrNull()
}

/**
 * Box fill calculations per [CodeRule.BOX_FILL] (NEC 314.16), reading Table 314.16(A) and
 * Table 314.16(B) from [Code.book].
 *
 * NEC 2023 allowances:
 * - Conductors (314.16(B)(1)): 1× volume per conductor of its AWG size (18–6 AWG).
 * - Internal clamps (314.16(B)(2)): 1× volume of the largest conductor in the box (for 1 or more clamps).
 * - Support fittings (314.16(B)(3)): 1× volume of the largest conductor per stud or hickey.
 * - Devices (314.16(B)(4)): 2× volume of the largest conductor connected to each yoke/strap.
 * - Equipment grounding conductors (314.16(B)(5)): 1× volume of the largest EGC for up to 4 EGCs,
 *   plus 0.25× volume of the largest EGC for each additional EGC beyond 4.
 */
object BoxFill {
    /** Sizes covered by [CodeRule.BOX_VOLUME_ALLOWANCE] (18 AWG through 6 AWG). */
    val supportedSizes: List<ConductorSize> get() = Code.book.boxVolumeAllowances.keys.toList()

    /** Standard metal boxes from [CodeRule.METAL_BOX_VOLUMES]. */
    val standardMetalBoxes: List<StandardMetalBox> get() = Code.book.metalBoxVolumes

    /** Volume allowance (in³) for one conductor of [size], or null if outside 18–6 AWG. */
    fun volumeAllowanceCuIn(size: ConductorSize): Double? = Code.book.boxVolumeAllowances[size]

    /** Computes box fill volume and suitable standard metal boxes. Returns null if any size is outside 18–6 AWG. */
    fun calculate(request: BoxFillRequest): BoxFillResult? {
        val activeConductors = request.conductors.filter { it.count > 0 }
        val activeDevices = request.devices.filter { it.count > 0 }

        // Validate every referenced size is in Table 314.16(B) (18–6 AWG).
        val allSizes = buildList {
            activeConductors.forEach { add(it.size) }
            activeDevices.forEach { add(it.conductorSize) }
            if (request.egcCount > 0 && request.largestEgcSize != null) add(request.largestEgcSize)
        }
        if (allSizes.any { volumeAllowanceCuIn(it) == null }) return null

        val largestConductor = allSizes.maxByOrNull { it.circularMils }
        val largestAllowance = largestConductor?.let { volumeAllowanceCuIn(it) } ?: 0.0

        val conductorVol = activeConductors.sumOf { it.count * volumeAllowanceCuIn(it.size)!! }
        val clampVol = if (request.hasInternalClamps && largestConductor != null) largestAllowance else 0.0
        val supportVol = if (request.supportFittings > 0 && largestConductor != null) request.supportFittings * largestAllowance else 0.0
        val values = Code.book.values
        val deviceVol = activeDevices.sumOf { it.count * values.boxFillDeviceYokeAllowances * volumeAllowanceCuIn(it.conductorSize)!! }
        val egcVol = if (request.egcCount > 0) {
            val egcSize = request.largestEgcSize ?: largestConductor
            val egcAllowance = egcSize?.let { volumeAllowanceCuIn(it) } ?: 0.0
            val extraEgcs = (request.egcCount - values.boxFillEgcCountedAsOne).coerceAtLeast(0)
            egcAllowance * (1.0 + values.boxFillExtraEgcFraction * extraEgcs)
        } else 0.0

        val total = conductorVol + clampVol + supportVol + deviceVol + egcVol
        val suitable = standardMetalBoxes
            .filter { it.volumeCuIn >= total - 1e-9 }
            .sortedBy { it.volumeCuIn }

        return BoxFillResult(
            conductorVolumeCuIn = conductorVol,
            clampVolumeCuIn = clampVol,
            supportVolumeCuIn = supportVol,
            deviceVolumeCuIn = deviceVol,
            egcVolumeCuIn = egcVol,
            totalVolumeCuIn = total,
            largestConductor = largestConductor,
            suitableBoxes = suitable,
        )
    }

    /** Checks whether a box of [boxVolumeCuIn] is large enough for [requiredVolumeCuIn]. */
    fun fits(boxVolumeCuIn: Double, requiredVolumeCuIn: Double): Boolean =
        boxVolumeCuIn + 1e-9 >= requiredVolumeCuIn
}
