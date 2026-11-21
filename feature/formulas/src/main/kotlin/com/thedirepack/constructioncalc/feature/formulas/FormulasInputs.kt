package com.thedirepack.constructioncalc.feature.formulas

import com.thedirepack.constructioncalc.core.electrical.formula.Formula
import com.thedirepack.constructioncalc.core.electrical.formula.FormulaCatalog
import com.thedirepack.constructioncalc.core.measure.Decimals
import com.thedirepack.constructioncalc.core.ui.tool.ToolInputsViewModel
import kotlinx.serialization.Serializable

/**
 * What the person typed, saved with an item. Values are kept as typed (in the unit picked for each
 * variable); blank variables are the ones to solve for.
 *
 * @property values variable id → text as typed
 * @property units variable id → index into that variable's units, when not its default
 */
@Serializable
data class FormulaInputs(
    val formulaId: String = FormulaCatalog.default.id,
    val values: Map<String, String> = emptyMap(),
    val units: Map<String, Int> = emptyMap(),
) {
    val formula: Formula get() = FormulaCatalog.find(formulaId) ?: FormulaCatalog.default

    fun unitIndex(variableId: String): Int {
        val v = formula.variables.firstOrNull { it.id == variableId } ?: return 0
        return units[variableId]?.takeIf { it in v.units.indices } ?: v.defaultUnit
    }

    /** Entered values in base units; blank or unreadable entries are left out. */
    fun given(): Map<String, Double> = formula.variables.mapNotNull { v ->
        val shown = values[v.id]?.let(Decimals::parse) ?: return@mapNotNull null
        v.id to shown * v.units[unitIndex(v.id)].factor
    }.toMap()

    /** Entries that aren't blank but can't be read as a number. */
    fun unreadable(): List<String> = formula.variables.filter { v ->
        val text = values[v.id]?.trim().orEmpty()
        text.isNotEmpty() && Decimals.parse(text) == null
    }.map { it.name }

    fun withValue(id: String, text: String) = copy(values = values + (id to text))

    /** Changes a variable's unit, converting what was typed so the quantity stays the same. */
    fun withUnit(id: String, index: Int): FormulaInputs {
        val v = formula.variables.firstOrNull { it.id == id } ?: return this
        if (index !in v.units.indices) return this
        val typed = values[id]?.let(Decimals::parse)
        val converted = typed?.let { FormulasText.number(it * v.units[unitIndex(id)].factor / v.units[index].factor) }
        return copy(units = units + (id to index), values = if (converted != null) values + (id to converted) else values)
    }

    /** Switches formula, starting from its default values (e.g. 60 Hz). */
    fun withFormula(formula: Formula) = FormulaInputs(formula.id, defaults(formula))

    fun cleared() = FormulaInputs(formulaId, defaults(formula))

    companion object {
        fun defaults(formula: Formula): Map<String, String> =
            formula.variables.mapNotNull { v -> v.default?.let { v.id to Decimals.trim(it, 6) } }.toMap()

        fun start(formula: Formula = FormulaCatalog.default) = FormulaInputs(formula.id, defaults(formula))
    }
}

class FormulasViewModel : ToolInputsViewModel<FormulaInputs>(FormulaInputs.start(), FormulaInputs.serializer()) {
    override fun normalize(next: FormulaInputs): FormulaInputs =
        if (FormulaCatalog.find(next.formulaId) == null) FormulaInputs.start() else next
}
