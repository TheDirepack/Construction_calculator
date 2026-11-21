package com.thedirepack.constructioncalc.feature.reellength

import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Dimension
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.StoredQuantity
import com.thedirepack.constructioncalc.feature.reellength.domain.ReelLength
import com.thedirepack.constructioncalc.feature.reellength.domain.ReelRequest
import kotlinx.serialization.Serializable
import kotlin.math.PI

/** What the tool is working out. */
@Serializable
enum class ReelMode(val label: String) {
    REMAINING("Cable on reel"),
    CAPACITY("Reel capacity"),
}

@Serializable
enum class DrumMeasurement(val label: String) {
    DIAMETER("Diameter"),
    CIRCUMFERENCE("Circumference"),
    FLANGE_TO_CORE("Depth from flange rim"),
}

/** What a saved Reel Length item stores in `ProjectItem.inputs`. Never rename a field. */
@Serializable
data class ReelLengthInputs(
    val mode: ReelMode = ReelMode.REMAINING,
    /** Flange diameter, F. */
    val flange: StoredQuantity? = null,
    /** Drum (core) diameter, D. */
    val drum: StoredQuantity? = null,
    /** Traverse, W. */
    val traverse: StoredQuantity? = null,
    /** Remaining mode: distance X from the flange edge down to the top of the cable. */
    val distance: StoredQuantity? = null,
    /** Capacity mode: clearance kept below the flange edge. */
    val clearance: StoredQuantity? = DEFAULT_CLEARANCE,
    /** Cable outside diameter, d. */
    val cableDiameter: StoredQuantity? = null,
    val packingFactor: String = "1",
    val drumMeasurement: DrumMeasurement = DrumMeasurement.DIAMETER,
    val drumCircumference: StoredQuantity? = null,
    val flangeToCore: StoredQuantity? = null,
) {
    companion object {
        val DEFAULT_CLEARANCE: StoredQuantity = StoredQuantity.of(Quantity.inches(Rational.of(2)))
    }
}

/** Outcome of reading the form: a request ready to calculate, or what is missing or wrong. */
data class ParsedReel(
    val request: ReelRequest?,
    /** Required fields not filled in yet (not an error: the form is just incomplete). */
    val missing: List<String>,
    /** Values that can't be used. */
    val problems: List<String>,
)

fun ReelLengthInputs.parse(): ParsedReel {
    val missing = mutableListOf<String>()

    fun inches(q: StoredQuantity?, name: String): Double? {
        val value = q?.takeIf { it.dimension == Dimension.LENGTH }?.toQuantity()?.value?.toDouble()
        if (value == null) missing += name
        return value
    }

    val f = inches(flange, "Flange diameter")
    val depth = if (drumMeasurement == DrumMeasurement.FLANGE_TO_CORE) {
        inches(flangeToCore, "Depth from flange rim to bare core")
    } else null
    val d = when (drumMeasurement) {
        DrumMeasurement.DIAMETER -> inches(drum, "Drum diameter")
        DrumMeasurement.CIRCUMFERENCE -> inches(drumCircumference, "Drum circumference")?.div(PI)
        DrumMeasurement.FLANGE_TO_CORE -> if (f != null && depth != null) f - 2 * depth else null
    }
    val w = inches(traverse, "Traverse")
    val x = if (mode == ReelMode.REMAINING) inches(distance, "Distance from flange edge") else inches(clearance, "Clearance")
    val od = inches(cableDiameter, "Cable diameter")
    val packing = if (packingFactor.isBlank()) 1.0 else parseNumber(packingFactor)

    if (packing == null) return ParsedReel(null, missing, listOf("Packing factor isn't a number."))
    if (depth != null && (depth <= 0 || (f != null && depth >= f / 2))) {
        return ParsedReel(null, missing, listOf("The depth to the bare core must be above zero and less than the flange radius."))
    }
    if (missing.isNotEmpty()) return ParsedReel(null, missing, emptyList())
    val request = ReelRequest(f!!, d!!, w!!, od!!, x!!, packing)
    val problems = ReelLength.problems(request)
    return ParsedReel(if (problems.isEmpty()) request else null, emptyList(), problems)
}

/** Accepts what people type; see [com.thedirepack.constructioncalc.core.measure.Decimals.parse]. */
internal fun parseNumber(text: String): Double? = com.thedirepack.constructioncalc.core.measure.Decimals.parse(text)
