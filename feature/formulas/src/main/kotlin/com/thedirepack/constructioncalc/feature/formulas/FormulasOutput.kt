package com.thedirepack.constructioncalc.feature.formulas

import com.thedirepack.constructioncalc.core.electrical.formula.Formula
import com.thedirepack.constructioncalc.core.electrical.formula.FormulaQuantity
import com.thedirepack.constructioncalc.core.electrical.formula.FormulaSolution
import com.thedirepack.constructioncalc.core.electrical.formula.FormulaSolver
import com.thedirepack.constructioncalc.core.electrical.formula.Variable
import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.measure.Decimals
import com.thedirepack.constructioncalc.core.model.ResultValue
import com.thedirepack.constructioncalc.core.model.ValueKind
import com.thedirepack.constructioncalc.core.ui.components.Notice
import com.thedirepack.constructioncalc.core.ui.components.NoticeLevel
import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs

/** Everything the screen shows for a set of inputs. Pure, so it's tested without Android. */
data class FormulasOutput(
    val solution: FormulaSolution,
    /** Computed values first (what the person asked for), then the entered ones. */
    val results: List<ResultValue>,
    /** Each computed value and the equation that gave it: "I = P ÷ V → 10 A". */
    val steps: List<String>,
    val notices: List<Notice>,
    /** Variable id → computed value as shown in that variable's unit, for the empty fields. */
    val computedText: Map<String, String>,
)

object FormulasText {
    /** 4 significant figures (1 decimal place from 1000 up), no grouping: `0.1234`, `12.35`, `83138.4`. */
    fun number(value: Double): String {
        if (value == 0.0) return "0"
        if (abs(value) >= 1000) return Decimals.trim(value, 1)
        val text = BigDecimal(value).round(MathContext(4)).stripTrailingZeros().toPlainString()
        return if (text == "-0") "0" else text
    }

    /** [base] (in the variable's base unit) in the unit at [unitIndex], with its label: `83.14 kVA`. */
    fun withUnit(variable: Variable, base: Double, unitIndex: Int): String {
        val unit = variable.shownUnits[unitIndex]
        val shown = number(base / unit.factor)
        return if (unit.label.isEmpty()) shown else if (unit.label.startsWith("$/") || unit.label.startsWith("/")) "$shown ${unit.label}"
        else if (unit.label == "$") "$$shown" else "$shown ${unit.label}"
    }
}

/** How a variable's value is shared with other tools, and which results its field accepts. */
internal fun valueKind(q: FormulaQuantity): ValueKind = when (q) {
    FormulaQuantity.VOLTAGE -> ValueKind.VOLTAGE
    FormulaQuantity.CURRENT -> ValueKind.CURRENT
    FormulaQuantity.POWER -> ValueKind.POWER
    FormulaQuantity.APPARENT_POWER -> ValueKind.APPARENT_POWER
    FormulaQuantity.RESISTANCE -> ValueKind.RESISTANCE
    FormulaQuantity.PERCENT -> ValueKind.PERCENT
    FormulaQuantity.LENGTH -> ValueKind.LENGTH
    else -> ValueKind.NUMBER
}

/** Base-unit value → the exact value published (lengths are published in inches, formulas use feet). */
internal fun publishedValue(q: FormulaQuantity, base: Double): Double = if (q == FormulaQuantity.LENGTH) base * 12 else base

/** A picked result's exact value → this variable's base unit. */
internal fun fromPublished(q: FormulaQuantity, exact: Double): Double = if (q == FormulaQuantity.LENGTH) exact / 12 else exact

fun formulasOutput(inputs: FormulaInputs, settings: AppSettings = AppSettings()): FormulasOutput {
    val system = settings.unitSystem
    val formula = inputs.formula
    val solution = FormulaSolver.solve(formula, inputs.given(system))
    fun text(id: String, base: Double) = FormulasText.withUnit(formula.variable(id), base, inputs.unitIndex(id, system))

    val ordered = solution.steps.map { it.variableId }.distinct() + formula.variables.map { it.id }.filter { it !in solution.computed }
    val results = ordered.mapNotNull { id ->
        val base = solution.values[id] ?: return@mapNotNull null
        val v = formula.variable(id)
        val kind = valueKind(v.quantity)
        val shown = text(id, base)
        val label = "${v.name} (${v.symbol})"
        if (kind == ValueKind.NUMBER) {
            ResultValue.number("${formula.id}.${v.id}", label, kind, base / v.shownUnits[inputs.unitIndex(id, system)].factor, shown)
        } else {
            ResultValue.number("${formula.id}.${v.id}", label, kind, publishedValue(v.quantity, base), shown)
        }
    }
    val steps = solution.steps.map { "${it.expression}  →  ${formula.variable(it.variableId).symbol} = ${text(it.variableId, it.value)}" }

    val notices = buildList {
        inputs.unreadable().forEach { add(Notice("$it isn't a number.", NoticeLevel.ERROR)) }
        solution.problems.forEach { add(Notice(it, NoticeLevel.ERROR)) }
        if (solution.conflicts.isNotEmpty()) {
            add(Notice("The entered values don't agree with ${solution.conflicts.joinToString(", ")}. Clear one of them to solve for it.", NoticeLevel.WARNING))
        }
        if (solution.steps.isEmpty() && solution.unknown.isNotEmpty()) {
            add(Notice(needMore(formula, inputs, system), NoticeLevel.INFO))
        } else if (solution.unknown.isNotEmpty()) {
            add(Notice("Not enough to find ${solution.unknown.joinToString(", ") { formula.variable(it).name.lowercase() }}.", NoticeLevel.INFO))
        }
        formula.notes.forEach { add(Notice(it)) }
    }
    val computed = solution.computed.associateWith { text(it, solution.values.getValue(it)) }
    return FormulasOutput(solution, results, steps, notices, computed)
}

private fun needMore(formula: Formula, inputs: FormulaInputs, system: com.thedirepack.constructioncalc.core.measure.UnitSystem): String =
    if (inputs.given(system).isEmpty()) "Enter the values you know; the empty ones are solved for." else "Enter one more value to solve."
