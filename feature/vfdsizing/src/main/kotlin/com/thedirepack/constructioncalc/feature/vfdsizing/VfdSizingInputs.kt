package com.thedirepack.constructioncalc.feature.vfdsizing

import com.thedirepack.constructioncalc.core.electrical.AcPhases
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.TemperatureRating
import com.thedirepack.constructioncalc.feature.vfdsizing.domain.LoadType
import com.thedirepack.constructioncalc.feature.vfdsizing.domain.RatingUnit
import com.thedirepack.constructioncalc.feature.vfdsizing.domain.VfdRequest
import com.thedirepack.constructioncalc.feature.vfdsizing.domain.VfdSizing
import kotlinx.serialization.Serializable

/** What a saved VFD Sizing item stores in `ProjectItem.inputs`. Never rename a field. */
@Serializable
data class VfdSizingInputs(
    val unit: RatingUnit = RatingUnit.HP,
    /** Standard horsepower from Table 430.250 (used when [unit] is HP). */
    val hp: Double = 5.0,
    /** Motor kilowatts as typed (used when [unit] is KW). */
    val kw: String = "",
    /** Rated motor voltage, one of [VfdSizing.MOTOR_VOLTAGES]. */
    val volts: Int = 460,
    val supplyPhase: AcPhases = AcPhases.THREE,
    /** Motor nameplate full-load amps. Optional. */
    val nameplateFla: String = "",
    /** Drive rated input amps. Optional. */
    val driveInputAmps: String = "",
    val loadType: LoadType = LoadType.VARIABLE_TORQUE,
    /** [ConductorMaterial] name: "COPPER" or "ALUMINUM". */
    val material: String = ConductorMaterial.COPPER.name,
    /** Termination temperature column in °C: 60 or 75. */
    val termination: Int = 75,
) {
    val conductorMaterial: ConductorMaterial
        get() = ConductorMaterial.entries.firstOrNull { it.name == material } ?: ConductorMaterial.COPPER

    val terminationRating: TemperatureRating
        get() = if (termination == 60) TemperatureRating.C60 else TemperatureRating.C75
}

/** Outcome of reading the form. */
data class ParsedInputs(
    val request: VfdRequest?,
    val missing: List<String>,
    val problems: List<String>,
    /** The horsepower a kW entry was converted to, when [VfdSizingInputs.unit] is kW. */
    val convertedHp: Double? = null,
)

fun VfdSizingInputs.parse(): ParsedInputs {
    val missing = mutableListOf<String>()
    val problems = mutableListOf<String>()

    fun optional(text: String, name: String): Double? {
        if (text.isBlank()) return null
        return parseNumber(text) ?: run { problems += "$name isn't a number."; null }
    }

    var convertedHp: Double? = null
    val horsepower: Double? = when (unit) {
        RatingUnit.HP -> hp
        RatingUnit.KW -> {
            if (kw.isBlank()) { missing += "Motor kW"; null } else {
                val value = parseNumber(kw)
                when {
                    value == null || value <= 0 -> { problems += "Motor kW must be a number above zero."; null }
                    else -> VfdSizing.horsepowerForKw(value, volts)
                        ?.also { convertedHp = it }
                        ?: run { problems += "No Table 430.250 rating covers $kw kW at $volts V."; null }
                }
            }
        }
    }
    val fla = optional(nameplateFla, "Nameplate FLA")
    val input = optional(driveInputAmps, "Drive input current")
    if (horsepower == null || missing.isNotEmpty() || problems.isNotEmpty()) {
        return ParsedInputs(null, missing, problems, convertedHp)
    }
    val request = VfdRequest(
        hp = horsepower,
        volts = volts,
        supplyPhase = supplyPhase,
        nameplateFla = fla,
        driveInputAmps = input,
        loadType = loadType,
        material = conductorMaterial,
        termination = terminationRating,
    )
    val domainProblems = VfdSizing.problems(request)
    return ParsedInputs(if (domainProblems.isEmpty()) request else null, emptyList(), domainProblems, convertedHp)
}

/** Accepts what people type; see [com.thedirepack.constructioncalc.core.measure.Decimals.parse]. */
internal fun parseNumber(text: String): Double? = com.thedirepack.constructioncalc.core.measure.Decimals.parse(text)
