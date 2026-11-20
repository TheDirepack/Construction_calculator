package com.thedirepack.constructioncalc.feature.voltagedrop

import com.thedirepack.constructioncalc.core.electrical.Table9Conduit
import com.thedirepack.constructioncalc.core.electrical.AcPhases
import com.thedirepack.constructioncalc.core.electrical.Stranding
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.measure.Dimension
import com.thedirepack.constructioncalc.core.model.StoredQuantity
import com.thedirepack.constructioncalc.feature.voltagedrop.domain.Method
import com.thedirepack.constructioncalc.feature.voltagedrop.domain.VoltageDrop
import com.thedirepack.constructioncalc.feature.voltagedrop.domain.VoltageDropRequest
import kotlinx.serialization.Serializable

/** What a saved Voltage Drop item stores in `ProjectItem.inputs`. Never rename a field. */
@Serializable
data class VoltageDropInputs(
    val system: AcPhases = AcPhases.SINGLE,
    /** One of [VOLTAGE_PRESETS], or 0 to use [customVoltage]. */
    val voltagePreset: Int = 120,
    val customVoltage: String = "",
    /** [ConductorMaterial] name: "COPPER" or "ALUMINUM". */
    val material: String = ConductorMaterial.COPPER.name,
    val amps: String = "",
    /** One-way length. */
    val length: StoredQuantity? = null,
    /** [ConductorSize.label], e.g. "12 AWG". */
    val size: String = ConductorSize.AWG_12.label,
    val parallelSets: Int = 1,
    val method: Method = Method.AC_TABLE_9,
    val conduit: Table9Conduit = Table9Conduit.PVC,
    val powerFactor: String = "0.85",
    val targetPercent: String = "3",
    val stranding: Stranding = Stranding.STRANDED,
) {
    val conductorMaterial: ConductorMaterial
        get() = ConductorMaterial.entries.firstOrNull { it.name == material } ?: ConductorMaterial.COPPER

    val conductorSize: ConductorSize get() = ConductorSize.fromLabel(size) ?: ConductorSize.AWG_12

    /** The voltage as typed or chosen; null while a custom voltage is empty or unreadable. */
    val volts: Double? get() = if (voltagePreset in VOLTAGE_PRESETS) voltagePreset.toDouble() else parseNumber(customVoltage)

    companion object {
        val VOLTAGE_PRESETS = listOf(120, 208, 240, 277, 480)
    }
}

/** Outcome of reading the form: a request ready to calculate, or what is missing or wrong. */
data class ParsedInputs(
    val request: VoltageDropRequest?,
    /** Required fields not filled in yet (not an error: the form is just incomplete). */
    val missing: List<String>,
    /** Fields with values that can't be used. */
    val problems: List<String>,
)

fun VoltageDropInputs.parse(): ParsedInputs {
    val missing = mutableListOf<String>()
    val problems = mutableListOf<String>()

    fun number(text: String, name: String): Double? {
        if (text.isBlank()) { missing += name; return null }
        return parseNumber(text) ?: run { problems += "$name isn't a number."; null }
    }

    val volts = if (voltagePreset in VoltageDropInputs.VOLTAGE_PRESETS) voltagePreset.toDouble() else number(customVoltage, "Voltage")
    val currentAmps = number(amps, "Load current")
    val lengthFeet = length?.takeIf { it.dimension == Dimension.LENGTH }?.toQuantity()?.value?.toDouble()?.div(12.0)
    if (lengthFeet == null) missing += "Length"
    val pf = if (method == Method.AC_TABLE_9) number(powerFactor, "Power factor") else 0.85
    val target = number(targetPercent, "Target drop")

    if (missing.isNotEmpty() || problems.isNotEmpty()) return ParsedInputs(null, missing, problems)
    val request = VoltageDropRequest(
        system = system,
        volts = volts!!,
        material = conductorMaterial,
        amps = currentAmps!!,
        lengthFeet = lengthFeet!!,
        size = conductorSize,
        parallelSets = parallelSets,
        method = method,
        conduit = conduit,
        powerFactor = pf!!,
        targetPercent = target!!,
        stranding = stranding,
    )
    val domainProblems = VoltageDrop.problems(request)
    return ParsedInputs(if (domainProblems.isEmpty()) request else null, emptyList(), domainProblems)
}

/** Accepts what people type; see [com.thedirepack.constructioncalc.core.measure.Decimals.parse]. */
internal fun parseNumber(text: String): Double? = com.thedirepack.constructioncalc.core.measure.Decimals.parse(text)
