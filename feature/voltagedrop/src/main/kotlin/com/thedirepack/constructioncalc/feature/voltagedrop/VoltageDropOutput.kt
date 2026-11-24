package com.thedirepack.constructioncalc.feature.voltagedrop

import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.electrical.AcPhases
import com.thedirepack.constructioncalc.core.electrical.ConductorImpedance
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.ConductorResistance
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.electrical.code.codePercentValue
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Decimals
import com.thedirepack.constructioncalc.core.measure.Measure
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.measure.Units
import com.thedirepack.constructioncalc.core.model.ResultValue
import com.thedirepack.constructioncalc.core.model.ValueKind
import com.thedirepack.constructioncalc.core.ui.components.Notice
import com.thedirepack.constructioncalc.core.ui.components.NoticeLevel
import com.thedirepack.constructioncalc.core.ui.conductorSizeLabel
import com.thedirepack.constructioncalc.core.ui.format
import com.thedirepack.constructioncalc.core.ui.formatDecimal
import com.thedirepack.constructioncalc.core.ui.isMetric
import com.thedirepack.constructioncalc.core.ui.show
import com.thedirepack.constructioncalc.feature.voltagedrop.domain.AmpacityCheckRequest
import com.thedirepack.constructioncalc.feature.voltagedrop.domain.AmpacitySizingResult
import com.thedirepack.constructioncalc.feature.voltagedrop.domain.GoverningConstraint
import com.thedirepack.constructioncalc.feature.voltagedrop.domain.Method
import com.thedirepack.constructioncalc.feature.voltagedrop.domain.VoltageDrop
import com.thedirepack.constructioncalc.feature.voltagedrop.domain.VoltageDropRequest
import com.thedirepack.constructioncalc.feature.voltagedrop.domain.VoltageDropResult
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/** Everything the screen shows for one set of inputs. */
internal data class VoltageDropOutput(
    val results: List<ResultValue>,
    val notices: List<Notice>,
    /** One-line description for a saved item; blank when there are no results. */
    val summary: String,
    /** Auto-sized or manually selected conductor size, or null when inputs are incomplete/invalid. */
    val activeSize: ConductorSize? = null,
    /** True when [activeSize] meets NEC 310.10(G) minimum for parallel sets (≥ 1/0 AWG). */
    val canParallel: Boolean = false,
    /** True when parallel sets > 1 is active on a conductor smaller than 1/0 AWG (NEC 310.10(G)). */
    val hasParallelViolation: Boolean = false,
    /** True when auto-sizing mode is active (no manual wire size override). */
    val isAutoSized: Boolean = true,
)

/** A table temperature in the user's system; imperial keeps the NEC's °C beside it: `167 °F (75 °C)`. */
internal fun tempBoth(celsius: Double, settings: AppSettings) =
    if (settings.isMetric) settings.show(Measure.TEMPERATURE, celsius, 0) else Measure.TEMPERATURE.formatBoth(celsius, settings.unitSystem, 0)

internal fun materialShort(material: ConductorMaterial) = material.shortLabel

internal fun sizeText(request: VoltageDropRequest, size: ConductorSize, settings: AppSettings) =
    buildString {
        append(settings.conductorSizeLabel(size)).append(' ').append(materialShort(request.material))
        if (request.parallelSets > 1) append(" × ").append(request.parallelSets).append(" sets")
    }

/** One-line collapsed summary for the Advanced options panel. */
internal fun advancedSummary(inputs: VoltageDropInputs): String = buildList {
    val codeTarget = Code.book.values.voltageDropBranchPercent
    val parsedTarget = Decimals.parse(inputs.targetPercent)
    if (parsedTarget != null && abs(parsedTarget - codeTarget) < 1e-6) {
        add("${codePercentValue(codeTarget, spaced = false)} code")
    } else {
        val shown = parsedTarget?.let { Decimals.trim(it, 2) } ?: inputs.targetPercent.trim().ifEmpty { "—" }
        add("$shown% target")
    }
    if (inputs.method == Method.AC_TABLE_9) {
        val pfShown = Decimals.parse(inputs.powerFactor)?.let { Decimals.trim(it, 3) }
            ?: inputs.powerFactor.trim().ifEmpty { "—" }
        add("PF $pfShown")
    }
    add(inputs.method.shortLabel)
    if (inputs.method == Method.AC_TABLE_9) {
        add(inputs.conduit.label)
    } else {
        add(inputs.stranding.label)
    }
    inputs.manualConductorSize?.let { add("${it.label} manual") }
    if (inputs.parallelSets > 1) add("${inputs.parallelSets}× parallel")
    if (inputs.conductorMaterial != ConductorMaterial.COPPER && inputs.conductorMaterial != ConductorMaterial.ALUMINUM) {
        add(inputs.conductorMaterial.shortLabel)
    }
    if (inputs.checkAmpacity) {
        val ocpd = inputs.ocpdAmps.trim().toIntOrNull()
        add(if (ocpd != null) "Ampacity (${ocpd} A)" else "Ampacity on")
    }
}.joinToString(" · ")

internal fun voltageDropOutput(inputs: VoltageDropInputs, settings: AppSettings): VoltageDropOutput {
    val parsed = inputs.parse()
    val request = parsed.request
    val errors = parsed.problems.map { Notice(it, NoticeLevel.ERROR) }
    if (request == null) return VoltageDropOutput(emptyList(), errors, "", isAutoSized = parsed.isAutoSized)
    val result = VoltageDrop.calculate(request)
        ?: return VoltageDropOutput(
            emptyList(),
            listOf(Notice("This circuit can't be calculated.", NoticeLevel.ERROR)),
            "",
            isAutoSized = parsed.isAutoSized,
        )

    val smallest = parsed.smallestSize
    val activeSize = request.size
    val canParallel = VoltageDrop.allowsParallel(activeSize)
    val parallelViolation = VoltageDrop.violatesParallelMinimum(activeSize, request.parallelSets)
    val largestLabel = VoltageDrop.supportedSizes(request.material, request.method).last().label
    val minSizeText = smallest?.let { sizeText(request, it, settings) } ?: "None up to $largestLabel"
    val targetLabel = formatDecimal(request.targetPercent, 2)
    val ampacityResult = parsed.ampacity?.let {
        VoltageDrop.checkAmpacity(request, it, smallest, settings.unitSystem)
    }

    val results = buildList {
        if (parsed.isAutoSized) {
            if (ampacityResult != null) {
                add(ResultValue.text("governing_size", "Sized wire", formatGoverningText(request, ampacityResult, settings)))
            } else {
                add(ResultValue.text("min_size", "Sized wire ($targetLabel %)", minSizeText))
            }
            add(decimalResult("percent_drop", "Voltage drop %", ValueKind.PERCENT, result.percent, 2, " %"))
            add(decimalResult("voltage_drop", "Voltage drop", ValueKind.VOLTAGE, result.dropVolts, 2, " V"))
            add(decimalResult("voltage_at_load", "Voltage at load", ValueKind.VOLTAGE, result.voltsAtLoad, 2, " V"))
            if (ampacityResult != null) {
                add(ResultValue.text("min_size", "Smallest size for $targetLabel %", minSizeText))
                add(ResultValue.text("ampacity_size", "Minimum size (ampacity)", formatAmpacityText(request, parsed.ampacity, ampacityResult, largestLabel, settings)))
            }
            add(maxLengthResult(result, settings))
        } else {
            add(decimalResult("voltage_drop", "Voltage drop", ValueKind.VOLTAGE, result.dropVolts, 2, " V"))
            add(decimalResult("percent_drop", "Voltage drop %", ValueKind.PERCENT, result.percent, 2, " %"))
            add(decimalResult("voltage_at_load", "Voltage at load", ValueKind.VOLTAGE, result.voltsAtLoad, 2, " V"))
            add(ResultValue.text("wire_size", "Selected wire (manual)", sizeText(request, activeSize, settings)))
            add(ResultValue.text("min_size", "Smallest size for $targetLabel %", minSizeText))
            if (ampacityResult != null) {
                add(ResultValue.text("ampacity_size", "Minimum size (ampacity)", formatAmpacityText(request, parsed.ampacity, ampacityResult, largestLabel, settings)))
                add(ResultValue.text("governing_size", "Recommended size", formatGoverningText(request, ampacityResult, settings)))
            }
            add(maxLengthResult(result, settings))
        }
    }

    val notices = buildList {
        addAll(errors)
        if (parallelViolation) {
            add(
                Notice(
                    "NEC ${CodeRule.PARALLEL_CONDUCTORS.cite} requires conductors in parallel to be " +
                        "${VoltageDrop.minParallelSize.label} or larger (${sizeText(request, activeSize, settings)} is under ${VoltageDrop.minParallelSize.label}).",
                    NoticeLevel.WARNING,
                ),
            )
        }
        if (!result.meetsTarget) {
            val prefix = if (parsed.isAutoSized && smallest == null) {
                "Even ${sizeText(request, activeSize, settings)} drops ${formatDecimal(result.percent, 2)} %, above your $targetLabel % target."
            } else {
                "Drop is ${formatDecimal(result.percent, 2)} %, above your $targetLabel % target."
            }
            add(
                Notice(
                    prefix + when {
                        smallest != null -> " ${sizeText(request, smallest, settings)} would meet it."
                        request.parallelSets < VoltageDrop.MAX_SETS -> " No single size is large enough; try more parallel sets."
                        else -> " No listed size is large enough."
                    },
                    NoticeLevel.WARNING,
                ),
            )
        }
        if (ampacityResult != null && !ampacityResult.selectedMeetsAmpacity) {
            val selectedLabel = sizeText(request, activeSize, settings)
            val reason = ampacityResult.selectedFailure ?: "Selected size does not meet NEC ampacity requirements."
            val suggestion = when {
                ampacityResult.minimumSize != null ->
                    " ${sizeText(request, ampacityResult.minimumSize, settings)} is the minimum size for ampacity."
                ampacityResult.ambientFactor == null -> ""
                request.parallelSets < VoltageDrop.MAX_SETS ->
                    " No single size is large enough; try more parallel sets."
                else -> " No listed size is large enough."
            }
            add(Notice("$selectedLabel fails ampacity check: $reason$suggestion", NoticeLevel.WARNING))
        }
        if (request.parallelSets > 1 && !parallelViolation) {
            add(
                Notice(
                    "Parallel sets assume equal current sharing (${CodeRule.PARALLEL_CONDUCTORS.cite}: ${VoltageDrop.minParallelSize.label} or larger).",
                    NoticeLevel.INFO,
                ),
            )
        }
        add(Notice("Assumes conductors at ${tempBoth(VoltageDrop.CONDUCTOR_TEMPERATURE_C.toDouble(), settings)} and a balanced load.", NoticeLevel.INFO))
        add(
            Notice(
                when (request.method) {
                    Method.AC_TABLE_9 ->
                        "Impedance from NEC ${CodeRule.AC_IMPEDANCE.cite} (${request.conduit.label} conduit, ${request.material.label.lowercase()}), " +
                            "effective Z at power factor ${formatDecimal(request.powerFactor, 3)}."
                    Method.DC_TABLE_8 ->
                        "Resistance from NEC ${CodeRule.CONDUCTOR_PROPERTIES.cite}, DC at ${tempBoth(75.0, settings)}, ${request.stranding.label.lowercase()} " +
                            "${request.material.label.lowercase()}. Ignores reactance."
                },
                NoticeLevel.INFO,
            ),
        )
        val substituted = when (request.method) {
            Method.AC_TABLE_9 -> !ConductorImpedance.hasOwnColumn(request.material)
            Method.DC_TABLE_8 -> !ConductorResistance.hasOwnColumn(request.material)
        }
        if (substituted) {
            val table = if (request.method == Method.AC_TABLE_9) CodeRule.AC_IMPEDANCE.cite else CodeRule.CONDUCTOR_PROPERTIES.cite
            add(
                Notice(
                    "$table has no ${request.material.label.lowercase()} column; " +
                        "${request.material.tableBasis.label.lowercase()} values are used.",
                    NoticeLevel.INFO,
                ),
            )
        }
        if (request.system == AcPhases.THREE) {
            add(Notice("Three-phase: voltage is line-to-line.", NoticeLevel.INFO))
        }
        if (ampacityResult != null) {
            ampacityResult.notes.forEach { add(Notice(it, NoticeLevel.INFO)) }
        } else {
            add(Notice("Ampacity, terminal temperature, derating and overcurrent protection are not checked.", NoticeLevel.INFO))
        }
    }

    val summary = buildString {
        append(formatDecimal(request.volts, 1)).append(" V ")
        append(if (request.system == AcPhases.SINGLE) "1-phase" else "3-phase").append(", ")
        append(formatDecimal(request.amps, 2)).append(" A, ")
        append(settings.format(Quantity.feet(Rational.of(request.lengthFeet)))).append(" of ")
        append(sizeText(request, activeSize, settings)).append(": ")
        append(formatDecimal(result.dropVolts, 2)).append(" V (").append(formatDecimal(result.percent, 2)).append(" %)")
        if (ampacityResult != null) {
            append(" · ")
            append(
                ampacityResult.governingSize?.let { "${sizeText(request, it, settings)} (${ampacityResult.governing.label})" }
                    ?: ampacityResult.governing.label,
            )
        }
    }
    return VoltageDropOutput(
        results = results,
        notices = notices,
        summary = summary,
        activeSize = activeSize,
        canParallel = canParallel,
        hasParallelViolation = parallelViolation,
        isAutoSized = parsed.isAutoSized,
    )
}

private fun formatAmpacityText(
    request: VoltageDropRequest,
    ampacity: AmpacityCheckRequest,
    result: AmpacitySizingResult,
    largestLabel: String,
    settings: AppSettings,
): String = result.minimumSize?.let { min ->
    val usable = result.minimumUsableAmpacity?.let { formatDecimal(it, 1) } ?: "—"
    "${sizeText(request, min, settings)} ($usable A usable)"
} ?: if (result.ambientFactor == null) {
    "None (exceeds ${ampacity.insulation.label} ambient)"
} else {
    "None up to $largestLabel"
}

private fun formatGoverningText(
    request: VoltageDropRequest,
    result: AmpacitySizingResult,
    settings: AppSettings,
): String = when (result.governing) {
    GoverningConstraint.VOLTAGE_DROP ->
        "${sizeText(request, result.governingSize!!, settings)} · ${GoverningConstraint.VOLTAGE_DROP.label} governs"
    GoverningConstraint.AMPACITY ->
        "${sizeText(request, result.governingSize!!, settings)} · ${GoverningConstraint.AMPACITY.label} governs"
    GoverningConstraint.BOTH ->
        "${sizeText(request, result.governingSize!!, settings)} · Drop & ampacity match"
    GoverningConstraint.UNSOLVED -> GoverningConstraint.UNSOLVED.label
}

private fun decimalResult(key: String, label: String, kind: ValueKind, value: Double, decimals: Int, unit: String) =
    ResultValue(
        key, label, kind,
        exact = Rational.of(BigDecimal(value).setScale(4, RoundingMode.HALF_UP)).toString(),
        text = formatDecimal(value, decimals) + unit,
    )

private fun maxLengthResult(result: VoltageDropResult, settings: AppSettings): ResultValue {
    val label = "Max one-way length"
    if (!result.maxLengthFeet.isFinite()) return ResultValue.text("max_length", label, "—")
    val inches = Rational.of(result.maxLengthFeet) * Rational.of(12)
    val stored = Quantity.inches(floorToIncrement(inches, Rational.of(1, 64)))
    val displayIncrement = if (settings.isMetric) {
        Rational.of(settings.metricResolution.tenthsOfMm.toLong(), 10) / Units.MM_PER_INCH
    } else {
        Rational.of(1, settings.precision.denominator.toLong())
    }
    val display = Quantity.inches(floorToIncrement(inches, displayIncrement))
    return ResultValue.of("max_length", label, stored, settings.format(display))
}

private fun floorToIncrement(nonnegative: Rational, increment: Rational): Rational {
    val steps = nonnegative / increment
    return Rational.of(steps.numerator / steps.denominator) * increment
}
