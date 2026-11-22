package com.thedirepack.constructioncalc.feature.voltagedrop

import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.electrical.AcPhases
import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.ConductorResistance
import com.thedirepack.constructioncalc.core.electrical.ConductorImpedance
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.measure.Units
import com.thedirepack.constructioncalc.core.model.ResultValue
import com.thedirepack.constructioncalc.core.model.ValueKind
import com.thedirepack.constructioncalc.core.ui.components.Notice
import com.thedirepack.constructioncalc.core.ui.components.NoticeLevel
import com.thedirepack.constructioncalc.core.ui.format
import com.thedirepack.constructioncalc.core.ui.conductorSizeLabel
import com.thedirepack.constructioncalc.core.ui.show
import com.thedirepack.constructioncalc.core.ui.isMetric
import com.thedirepack.constructioncalc.core.measure.Measure
import com.thedirepack.constructioncalc.core.ui.formatDecimal
import com.thedirepack.constructioncalc.feature.voltagedrop.domain.Method
import com.thedirepack.constructioncalc.feature.voltagedrop.domain.VoltageDrop
import com.thedirepack.constructioncalc.feature.voltagedrop.domain.VoltageDropRequest
import com.thedirepack.constructioncalc.feature.voltagedrop.domain.VoltageDropResult
import java.math.BigDecimal
import java.math.RoundingMode

/** Everything the screen shows for one set of inputs. */
internal data class VoltageDropOutput(
    val results: List<ResultValue>,
    val notices: List<Notice>,
    /** One-line description for a saved item; blank when there are no results. */
    val summary: String,
)

/** A table temperature in the user's system; imperial keeps the NEC's °C beside it: `167 °F (75 °C)`. */
internal fun tempBoth(celsius: Double, settings: AppSettings) =
    if (settings.isMetric) settings.show(Measure.TEMPERATURE, celsius, 0) else Measure.TEMPERATURE.formatBoth(celsius, settings.unitSystem, 0)

internal fun materialShort(material: ConductorMaterial) = material.shortLabel

internal fun sizeText(request: VoltageDropRequest, size: com.thedirepack.constructioncalc.core.electrical.ConductorSize, settings: AppSettings) =
    buildString {
        append(settings.conductorSizeLabel(size)).append(' ').append(materialShort(request.material))
        if (request.parallelSets > 1) append(" × ").append(request.parallelSets).append(" sets")
    }

internal fun voltageDropOutput(inputs: VoltageDropInputs, settings: AppSettings): VoltageDropOutput {
    val parsed = inputs.parse()
    val request = parsed.request
    val errors = parsed.problems.map { Notice(it, NoticeLevel.ERROR) }
    if (request == null) return VoltageDropOutput(emptyList(), errors, "")
    val result = VoltageDrop.calculate(request)
        ?: return VoltageDropOutput(emptyList(), listOf(Notice("This circuit can't be calculated.", NoticeLevel.ERROR)), "")

    val smallest = VoltageDrop.smallestSize(request)
    val results = buildList {
        add(decimalResult("voltage_drop", "Voltage drop", ValueKind.VOLTAGE, result.dropVolts, 2, " V"))
        add(decimalResult("percent_drop", "Voltage drop %", ValueKind.PERCENT, result.percent, 2, " %"))
        add(decimalResult("voltage_at_load", "Voltage at load", ValueKind.VOLTAGE, result.voltsAtLoad, 2, " V"))
        add(
            ResultValue.text(
                "min_size",
                "Smallest size for ${formatDecimal(request.targetPercent, 2)} %",
                smallest?.let { sizeText(request, it, settings) } ?: "None up to ${VoltageDrop.supportedSizes(request.material, request.method).last().label}",
            ),
        )
        add(maxLengthResult(result, settings))
    }

    val notices = buildList {
        addAll(errors)
        if (!result.meetsTarget) {
            add(
                Notice(
                    "Drop is ${formatDecimal(result.percent, 2)} %, above your ${formatDecimal(request.targetPercent, 2)} % target." +
                        when {
                            smallest != null -> " ${sizeText(request, smallest, settings)} would meet it."
                            request.parallelSets < VoltageDrop.MAX_SETS -> " No single size is large enough; try more parallel sets."
                            else -> " No listed size is large enough."
                        },
                    NoticeLevel.WARNING,
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
        add(Notice("Ampacity, terminal temperature, derating and overcurrent protection are not checked.", NoticeLevel.INFO))
    }

    val summary = buildString {
        append(formatDecimal(request.volts, 1)).append(" V ")
        append(if (request.system == AcPhases.SINGLE) "1-phase" else "3-phase").append(", ")
        append(formatDecimal(request.amps, 2)).append(" A, ")
        append(settings.format(Quantity.feet(Rational.of(request.lengthFeet)))).append(" of ")
        append(sizeText(request, request.size, settings)).append(": ")
        append(formatDecimal(result.dropVolts, 2)).append(" V (").append(formatDecimal(result.percent, 2)).append(" %)")
    }
    return VoltageDropOutput(results, notices, summary)
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
