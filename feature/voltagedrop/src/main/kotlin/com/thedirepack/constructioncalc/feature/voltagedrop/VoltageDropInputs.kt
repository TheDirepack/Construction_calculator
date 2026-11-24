package com.thedirepack.constructioncalc.feature.voltagedrop

import com.thedirepack.constructioncalc.core.measure.Decimals
import com.thedirepack.constructioncalc.core.electrical.code.codeNumber
import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.Table9Conduit
import com.thedirepack.constructioncalc.core.electrical.AcPhases
import com.thedirepack.constructioncalc.core.electrical.Stranding
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.TemperatureRating
import com.thedirepack.constructioncalc.core.measure.Dimension
import com.thedirepack.constructioncalc.core.model.StoredQuantity
import com.thedirepack.constructioncalc.feature.voltagedrop.domain.AmpacityCheckRequest
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
    val powerFactor: String = codeNumber(Code.book.values.acImpedanceTablePowerFactor),
    val targetPercent: String = codeNumber(Code.book.values.voltageDropBranchPercent),
    val stranding: Stranding = Stranding.STRANDED,
    val checkAmpacity: Boolean = false,
    val insulation: TemperatureRating = TemperatureRating.C75,
    val termination: TemperatureRating = TemperatureRating.C75,
    val continuousLoad: Boolean = false,
    val ambientC: String = codeNumber(Code.book.values.ambientBasisC),
    val currentCarrying: String = "3",
    val ocpdAmps: String = "",
) {
    val conductorMaterial: ConductorMaterial
        get() = ConductorMaterial.entries.firstOrNull { it.name == material } ?: ConductorMaterial.COPPER

    val conductorSize: ConductorSize get() = ConductorSize.fromLabel(size) ?: ConductorSize.AWG_12

    /** The voltage as typed or chosen; null while a custom voltage is empty or unreadable. */
    val volts: Double? get() = if (voltagePreset in VOLTAGE_PRESETS) voltagePreset.toDouble() else Decimals.parse(customVoltage)

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
    val ampacity: AmpacityCheckRequest? = null,
)

fun VoltageDropInputs.parse(): ParsedInputs {
    val missing = mutableListOf<String>()
    val problems = mutableListOf<String>()

    fun number(text: String, name: String): Double? {
        if (text.isBlank()) { missing += name; return null }
        return Decimals.parse(text) ?: run { problems += "$name isn't a number."; null }
    }

    val volts = if (voltagePreset in VoltageDropInputs.VOLTAGE_PRESETS) voltagePreset.toDouble() else number(customVoltage, "Voltage")
    val currentAmps = number(amps, "Load current")
    val lengthFeet = length?.takeIf { it.dimension == Dimension.LENGTH }?.toQuantity()?.value?.toDouble()?.div(12.0)
    if (lengthFeet == null) missing += "Length"
    val pf = if (method == Method.AC_TABLE_9) number(powerFactor, "Power factor") else Code.book.values.acImpedanceTablePowerFactor
    val target = number(targetPercent, "Target drop")

    var parsedAmbient: Double? = null
    var parsedCcc: Int? = null
    var parsedOcpd: Int? = null
    if (checkAmpacity) {
        parsedAmbient = number(ambientC, "Ambient temperature")
        parsedCcc = if (currentCarrying.isBlank()) {
            missing += "Current-carrying conductors"
            null
        } else {
            currentCarrying.trim().toIntOrNull()?.takeIf { it >= 1 } ?: run {
                problems += "Current-carrying conductors must be a whole number of 1 or more."
                null
            }
        }
        parsedOcpd = if (ocpdAmps.isBlank()) {
            null
        } else {
            ocpdAmps.trim().toIntOrNull()?.takeIf { it >= 1 } ?: run {
                problems += "Breaker / fuse rating must be a positive whole number."
                null
            }
        }
    }

    if (missing.isNotEmpty() || problems.isNotEmpty()) return ParsedInputs(null, missing, problems, null)
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
    if (domainProblems.isNotEmpty()) return ParsedInputs(null, emptyList(), domainProblems, null)
    val ampacityRequest = if (checkAmpacity) {
        AmpacityCheckRequest(
            insulation = insulation,
            termination = termination,
            ambientC = parsedAmbient!!,
            currentCarrying = parsedCcc!!,
            continuousLoad = continuousLoad,
            ocpdAmps = parsedOcpd,
        )
    } else {
        null
    }
    return ParsedInputs(request, emptyList(), emptyList(), ampacityRequest)
}
