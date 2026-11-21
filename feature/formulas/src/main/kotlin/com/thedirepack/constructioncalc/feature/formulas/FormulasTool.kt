package com.thedirepack.constructioncalc.feature.formulas

import androidx.compose.runtime.Composable
import com.thedirepack.constructioncalc.core.electrical.formula.FormulaCatalog
import com.thedirepack.constructioncalc.core.model.ProjectItem
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.tool.ToolCategory
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost
import com.thedirepack.constructioncalc.core.ui.tool.ToolIds
import com.thedirepack.constructioncalc.core.ui.tool.ToolModule

/** Solves any of the formulas in [FormulaCatalog] for whatever is left blank. */
object FormulasTool : ToolModule {
    const val ID = ToolIds.FORMULAS

    override val id = ID
    override val title = "Electrical Formulas"
    override val summary = "Ohm's law, power, motors, transformers: solve for any unknown"
    override val category = ToolCategory.ELECTRICAL
    override val icon = R.drawable.ic_function

    @Composable
    override fun Content(host: ToolHost) = FormulasScreen(host)

    override fun describe(item: ProjectItem): String? = item.results.take(3).joinToString(" · ") { "${it.label} ${it.text}" }.ifBlank { null }
}
