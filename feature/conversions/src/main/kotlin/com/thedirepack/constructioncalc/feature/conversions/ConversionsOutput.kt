package com.thedirepack.constructioncalc.feature.conversions

import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Dimension
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.ResultValue
import com.thedirepack.constructioncalc.core.model.ValueKind
import com.thedirepack.constructioncalc.core.ui.components.Notice
import com.thedirepack.constructioncalc.core.ui.components.NoticeLevel
import com.thedirepack.constructioncalc.core.ui.format
import com.thedirepack.constructioncalc.feature.conversions.domain.ConversionCategory
import com.thedirepack.constructioncalc.feature.conversions.domain.Conversions
import com.thedirepack.constructioncalc.feature.conversions.domain.CurrentPower
import com.thedirepack.constructioncalc.feature.conversions.domain.NumberText
import com.thedirepack.constructioncalc.feature.conversions.domain.PowerTerm
import com.thedirepack.constructioncalc.feature.conversions.domain.UnitTable
import com.thedirepack.constructioncalc.feature.conversions.domain.WireSizeResult
import com.thedirepack.constructioncalc.feature.conversions.domain.WireSizes
import java.math.BigDecimal
import java.math.RoundingMode

/** Everything the screen shows for one set of inputs. */
internal data class ConversionsOutput(
    val results: List<ResultValue>,
    val notices: List<Notice>,
    /** One-line description for a saved item; blank when there are no results. */
    val summary: String,
)

private val SQ_IN_PER_MM2 = Rational.of(BigDecimal("645.16")).let { Rational.ONE / it }

/** A double as an exact Rational, rounded to 6 decimals so saved values stay short. */
private fun exact(value: Double): Rational = Rational.of(BigDecimal(value).setScale(6, RoundingMode.HALF_UP))

private fun empty(vararg notices: Notice) = ConversionsOutput(emptyList(), notices.toList(), "")

internal fun conversionsOutput(inputs: ConversionsInputs, settings: AppSettings): ConversionsOutput =
    when (inputs.category) {
        ConversionCategory.WIRE_SIZE -> wireSizeOutput(inputs)
        ConversionCategory.CURRENT_POWER -> currentPowerOutput(inputs)
        else -> tableOutput(inputs, settings)
    }

private fun tableNote(category: ConversionCategory): String? = when (category) {
    ConversionCategory.LENGTH -> "1 in = 25.4 mm exactly (NIST SP 811)."
    ConversionCategory.AREA -> "Acre is 43,560 sq ft (international foot); the US-survey acre differs by 2 parts per million."
    ConversionCategory.VOLUME -> "US gallon = 231 cu in = 3.785411784 L. Imperial gallons aren't included."
    ConversionCategory.WEIGHT -> "1 lb = 0.45359237 kg exactly. Weight here means mass in avoirdupois units."
    ConversionCategory.PRESSURE -> "Inches of water, feet of head and inches of mercury use 60 °F (NIST SP 811)."
    ConversionCategory.POWER -> "Horsepower is mechanical (550 ft·lbf/s ≈ 745.7 W); 1 ton of refrigeration = 12,000 BTU/h."
    ConversionCategory.ENERGY -> "BTU is the International Table BTU (1055.05585262 J); therm is the US therm."
    ConversionCategory.FLOW -> "Gallons are US gallons; cfm and cfs are cubic feet."
    ConversionCategory.ANGLE -> "Radians use 180/π to 30 digits."
    ConversionCategory.RESISTANCE_LENGTH -> "1 Ω/km = 0.3048 Ω/1000 ft."
    else -> null
}

private fun tableOutput(inputs: ConversionsInputs, settings: AppSettings): ConversionsOutput {
    val category = inputs.category
    val calculator = inputs.usesCalculator
    val from = if (calculator) UnitTable.unit(category, "in") else UnitTable.unit(category, inputs.fromUnit)
    val entered: Rational = if (calculator) {
        inputs.length?.takeIf { it.dimension == Dimension.LENGTH }?.toQuantity()?.value ?: return empty()
    } else {
        if (inputs.value.isBlank()) return empty()
        parseRational(inputs.value) ?: return empty(Notice("That isn't a number.", NoticeLevel.ERROR))
    }

    val base = from.toBase(entered)
    if (category == ConversionCategory.TEMPERATURE && Conversions.belowAbsoluteZero(base)) {
        return empty(Notice("That's below absolute zero.", NoticeLevel.ERROR))
    }

    val rows = Conversions.convertAll(category, entered, from)
    val numberKind = category.kind == ValueKind.NUMBER
    val results = buildList {
        if (category == ConversionCategory.LENGTH) {
            val q = Quantity.inches(base)
            add(ResultValue.of("length", "Length", q, settings.format(q)))
        }
        rows.forEach { (unit, value) ->
            add(
                ResultValue.number(
                    unit.id,
                    unit.name,
                    category.kind,
                    if (numberKind) value else base,
                    NumberText.significant(value) + " " + unit.symbol,
                ),
            )
        }
    }

    val enteredText = if (calculator) settings.format(Quantity.inches(entered)) else NumberText.significant(entered) + " " + from.symbol
    val others = rows.filter { it.unit.id != from.id }.take(2).joinToString(" = ") { NumberText.significant(it.value, 5) + " " + it.unit.symbol }
    val summary = "${category.label}: $enteredText = $others"
    val notices = listOfNotNull(tableNote(category)?.let { Notice(it) })
    return ConversionsOutput(results, notices, summary)
}

private fun wireSizeOutput(inputs: ConversionsInputs): ConversionsOutput {
    val result: WireSizeResult
    when (inputs.wireFrom) {
        WireFrom.SIZE -> result = WireSizes.fromSize(ConductorSize.fromLabel(inputs.wireSize) ?: ConductorSize.AWG_12)
        WireFrom.CMIL, WireFrom.MM2 -> {
            if (inputs.value.isBlank()) return empty()
            val number = parseRational(inputs.value)?.toDouble()
            if (number == null || number <= 0) return empty(Notice("Enter an area greater than zero.", NoticeLevel.ERROR))
            result = if (inputs.wireFrom == WireFrom.CMIL) WireSizes.fromCircularMils(number) else WireSizes.fromSquareMillimeters(number)
        }
    }

    fun areaRow(key: String, label: String, mm2: Double) = ResultValue.number(
        key, label, ValueKind.AREA, exact(mm2) * SQ_IN_PER_MM2, NumberText.significant(Rational.of(mm2), 4) + " mm²",
    )

    fun cmilRow(key: String, label: String, cmil: Double) = ResultValue.number(
        key, label, ValueKind.NUMBER, exact(cmil), NumberText.significant(Rational.of(cmil), 5) + " cmil",
    )

    val size = result.size
    val results = buildList {
        if (inputs.wireFrom == WireFrom.SIZE && size != null) {
            add(ResultValue.text("size", "Size", size.label))
            add(cmilRow("cmil", "Circular mils", result.circularMils))
            add(areaRow("mm2", "Area", result.squareMillimeters))
        } else {
            if (size != null) {
                add(ResultValue.text("size", "Nearest standard size at or above", size.label))
                add(cmilRow("size_cmil", "Size circular mils", size.circularMils.toDouble()))
                add(areaRow("size_mm2", "Size area", size.squareMillimeters))
            }
            add(cmilRow("entered_cmil", "Entered circular mils", result.circularMils))
            add(areaRow("entered_mm2", "Entered area", result.squareMillimeters))
        }
    }
    val notices = buildList {
        if (size == null) add(Notice("Larger than 2000 kcmil, the biggest size in the table.", NoticeLevel.WARNING))
        if (inputs.wireFrom != WireFrom.SIZE) {
            add(Notice("Nearest size is the smallest AWG/kcmil with at least the entered area (within 0.1%). Metric sizes like 10 mm² aren't AWG; ampacity isn't checked."))
        }
        add(Notice("Sizes and circular mils: NEC 2023 Chapter 9 Table 8. mm² = circular mils × π/4 × 0.0254²."))
    }
    val summary = if (size == null) "Wire area ${NumberText.significant(Rational.of(result.squareMillimeters), 4)} mm²"
    else "${size.label} = ${NumberText.significant(Rational.of(size.circularMils.toLong()), 6)} cmil = ${NumberText.significant(Rational.of(size.squareMillimeters), 4)} mm²"
    return ConversionsOutput(results, notices, summary)
}

private fun currentPowerOutput(inputs: ConversionsInputs): ConversionsOutput {
    val value = parseRational(inputs.value)?.toDouble()
    val volts = parseRational(inputs.voltage)?.toDouble()
    val pf = parseRational(inputs.powerFactor)?.toDouble()
    val missing = buildList {
        if (value == null) add(inputs.powerFrom.label)
        if (volts == null) add("Voltage")
        if (pf == null) add("Power factor")
    }
    if (value == null || volts == null || pf == null) {
        return empty(Notice("Still needed: " + missing.joinToString(", ") + "."))
    }
    val problems = CurrentPower.problems(value, volts, pf)
    if (problems.isNotEmpty()) return empty(*problems.map { Notice(it, NoticeLevel.ERROR) }.toTypedArray())

    val r = CurrentPower.calculate(inputs.powerFrom, value, inputs.phase, volts, pf)
    fun text(x: Double, unit: String) = NumberText.significant(Rational.of(x), 4) + " $unit"
    val results = listOf(
        ResultValue.number("amps", "Current", ValueKind.CURRENT, exact(r.amps), text(r.amps, "A")),
        ResultValue.number("kw", "Real power", ValueKind.POWER, exact(r.kw * 1000), text(r.kw, "kW")),
        ResultValue.number("kva", "Apparent power", ValueKind.APPARENT_POWER, exact(r.kva * 1000), text(r.kva, "kVA")),
    )
    val notices = listOf(
        Notice("Balanced ${inputs.phase.label.lowercase()} load" + (if (inputs.phase.factor > 1) ", line-to-line voltage" else "") + ". kVA = V × A" + (if (inputs.phase.factor > 1) " × √3" else "") + " ÷ 1000; kW = kVA × power factor."),
        Notice("Load power factor only: motor nameplate hp or output kW also depends on efficiency."),
    )
    val entered = text(value, inputs.powerFrom.unit)
    val summary = "${inputs.phase.label} ${NumberText.significant(Rational.of(volts), 4)} V, PF ${NumberText.significant(Rational.of(pf), 3)}: " +
        listOf(PowerTerm.AMPS to results[0], PowerTerm.KW to results[1], PowerTerm.KVA to results[2]).joinToString(" = ") { it.second.text }
    return ConversionsOutput(results, notices + Notice("Entered $entered."), summary)
}
