package com.thedirepack.constructioncalc.feature.formulas

import com.thedirepack.constructioncalc.core.electrical.formula.Formula
import com.thedirepack.constructioncalc.core.electrical.formula.FormulaCatalog
import com.thedirepack.constructioncalc.core.electrical.formula.FormulaQuantity
import com.thedirepack.constructioncalc.core.electrical.formula.FormulaUnit
import com.thedirepack.constructioncalc.core.electrical.formula.Variable
import com.thedirepack.constructioncalc.core.measure.Decimals
import com.thedirepack.constructioncalc.core.measure.UnitSystem
import com.thedirepack.constructioncalc.core.ui.tool.ToolInputsViewModel
import kotlinx.serialization.Serializable

private val METERS = FormulaUnit("m", 1.0 / 0.3048)

/** The units a variable offers: lengths (feet in the catalog) also offer meters. */
val Variable.shownUnits: List<FormulaUnit>
    get() = if (quantity == FormulaQuantity.LENGTH && units.none { it.label == "m" }) units + METERS else units

/** The unit a variable starts on in [system]: meters for lengths in metric, otherwise the catalog's default. */
fun Variable.defaultUnitIn(system: UnitSystem): Int =
    if (quantity == FormulaQuantity.LENGTH && system == UnitSystem.METRIC) shownUnits.indexOfFirst { it.label == "m" } else defaultUnit

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

    /** The picked unit, or the variable's default in [system] when none was picked. */
    fun unitIndex(variableId: String, system: UnitSystem = UnitSystem.IMPERIAL): Int {
        val v = formula.variables.firstOrNull { it.id == variableId } ?: return 0
        return units[variableId]?.takeIf { it in v.shownUnits.indices } ?: v.defaultUnitIn(system)
    }

    /** Entered values in base units; blank or unreadable entries are left out. */
    fun given(system: UnitSystem = UnitSystem.IMPERIAL): Map<String, Double> = formula.variables.mapNotNull { v ->
        val shown = values[v.id]?.let(Decimals::parse) ?: return@mapNotNull null
        v.id to shown * v.shownUnits[unitIndex(v.id, system)].factor
    }.toMap()

    /** Entries that aren't blank but can't be read as a number. */
    fun unreadable(): List<String> = formula.variables.filter { v ->
        val text = values[v.id]?.trim().orEmpty()
        text.isNotEmpty() && Decimals.parse(text) == null
    }.map { it.name }

    /**
     * Sets a typed value and pins the unit it was typed in, so switching Imperial/Metric later
     * never changes what a saved value means (an unpinned value follows the system's default unit).
     */
    fun withValue(id: String, text: String, system: UnitSystem = UnitSystem.IMPERIAL): FormulaInputs {
        val pinned = if (id in units || text.isBlank()) units else units + (id to unitIndex(id, system))
        return copy(values = values + (id to text), units = pinned)
    }

    /** Changes a variable's unit, converting what was typed so the quantity stays the same. */
    fun withUnit(id: String, index: Int, system: UnitSystem = UnitSystem.IMPERIAL): FormulaInputs {
        val v = formula.variables.firstOrNull { it.id == id } ?: return this
        if (index !in v.shownUnits.indices) return this
        val typed = values[id]?.let(Decimals::parse)
        val converted = typed?.let { FormulasText.number(it * v.shownUnits[unitIndex(id, system)].factor / v.shownUnits[index].factor) }
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
