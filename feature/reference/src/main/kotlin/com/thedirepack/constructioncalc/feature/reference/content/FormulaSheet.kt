package com.thedirepack.constructioncalc.feature.reference.content

import com.thedirepack.constructioncalc.core.electrical.formula.Formula
import com.thedirepack.constructioncalc.core.electrical.formula.FormulaCatalog
import com.thedirepack.constructioncalc.core.ui.tool.ToolIds
import com.thedirepack.constructioncalc.feature.reference.content.RefBlock.Bullets
import com.thedirepack.constructioncalc.feature.reference.content.RefBlock.Equation
import com.thedirepack.constructioncalc.feature.reference.content.RefBlock.Paragraph
import com.thedirepack.constructioncalc.feature.reference.content.RefBlock.Table
import com.thedirepack.constructioncalc.feature.reference.content.RefBlock.ToolLink

/**
 * The formula sheet: a page per formula in [FormulaCatalog] (the same list the Electrical Formulas
 * tool solves, so the two can't drift), plus a one-page summary of the everyday power formulas.
 */
internal object FormulaSheet {
    /** Entry id for a formula; the Formulas tool links here with the same id. */
    fun entryId(formulaId: String) = "formula:$formulaId"

    val entries: List<RefEntry> get() = listOf(quickSheet()) + FormulaCatalog.all.sortedBy { it.group.ordinal }.map(::entry)

    private fun entry(f: Formula) = RefEntry(
        id = entryId(f.id),
        title = f.title,
        section = RefSection.FORMULAS,
        summary = f.summary,
        source = RefSource.Math,
        tools = listOfNotNull(ToolIds.FORMULAS, f.toolId),
        keywords = f.keywords + f.group.label + f.variables.map { it.name },
    ) {
        buildList {
            f.relations.forEach { add(Equation(it.expression)) }
            add(
                Table(
                    listOf("Symbol", "Quantity", "Units"),
                    f.variables.map { v -> listOf(v.symbol, v.name, v.units.joinToString(", ") { it.label.ifEmpty { "ratio" } }) },
                ),
            )
            if (f.notes.isNotEmpty()) add(Bullets(f.notes))
            add(ToolLink("Solve in Electrical Formulas", ToolIds.FORMULAS, f.id))
            f.toolId?.let { add(ToolLink("Open the full tool", it)) }
        }
    }

    private fun quickSheet() = RefEntry(
        id = "formula-quick-sheet",
        title = "Everyday power formulas",
        section = RefSection.FORMULAS,
        summary = "Amps, kW, kVA and horsepower for DC, single-phase and three-phase in one table.",
        source = RefSource.Math,
        tools = listOf(ToolIds.FORMULAS),
        keywords = listOf("amps", "kw", "kva", "hp", "horsepower", "cheat sheet", "power wheel", "ohm", "watts"),
    ) {
        listOf(
            Table(
                listOf("To find", "DC", "Single-phase", "Three-phase"),
                listOf(
                    listOf("Amps from kW", "kW × 1000 ÷ V", "kW × 1000 ÷ (V × PF)", "kW × 1000 ÷ (√3 × V × PF)"),
                    listOf("Amps from kVA", "—", "kVA × 1000 ÷ V", "kVA × 1000 ÷ (√3 × V)"),
                    listOf("Amps from HP", "HP × 746 ÷ (V × η)", "HP × 746 ÷ (V × η × PF)", "HP × 746 ÷ (√3 × V × η × PF)"),
                    listOf("kW", "V × I ÷ 1000", "V × I × PF ÷ 1000", "√3 × V × I × PF ÷ 1000"),
                    listOf("kVA", "—", "V × I ÷ 1000", "√3 × V × I ÷ 1000"),
                    listOf("HP output", "V × I × η ÷ 746", "V × I × η × PF ÷ 746", "√3 × V × I × η × PF ÷ 746"),
                ),
                "V is line-to-line for three-phase. η is efficiency as a decimal (0.9). √3 ≈ 1.732.",
            ),
            Paragraph("Ohm's law: V = I × R, I = V ÷ R, R = V ÷ I. Power: P = V × I = I² × R = V² ÷ R."),
            ToolLink("Solve any of these", ToolIds.FORMULAS, "power-three-phase"),
        )
    }
}
