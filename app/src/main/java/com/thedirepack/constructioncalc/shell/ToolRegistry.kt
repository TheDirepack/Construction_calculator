package com.thedirepack.constructioncalc.shell

import com.thedirepack.constructioncalc.core.ui.tool.ToolModule
import com.thedirepack.constructioncalc.feature.conduitbending.ConduitBendingTool
import com.thedirepack.constructioncalc.feature.conduitfill.ConduitFillTool
import com.thedirepack.constructioncalc.feature.conversions.ConversionsTool
import com.thedirepack.constructioncalc.feature.drawings.DrawingsTool
import com.thedirepack.constructioncalc.feature.footinch.FootInchTool
import com.thedirepack.constructioncalc.feature.formulas.FormulasTool
import com.thedirepack.constructioncalc.feature.panels.PanelsTool
import com.thedirepack.constructioncalc.feature.projects.ProjectsTool
import com.thedirepack.constructioncalc.feature.pullplanner.PullPlannerTool
import com.thedirepack.constructioncalc.feature.reellength.ReelLengthTool
import com.thedirepack.constructioncalc.feature.reference.ReferenceTool
import com.thedirepack.constructioncalc.feature.vfdsizing.VfdSizingTool
import com.thedirepack.constructioncalc.feature.voltagedrop.VoltageDropTool
import com.thedirepack.constructioncalc.feature.wellpump.WellPumpTool

/** Every tool in the app, in drawer order within each category. */
object ToolRegistry {
    val all: List<ToolModule> = listOf(
        ProjectsTool,
        DrawingsTool,
        FootInchTool,
        PanelsTool,
        ConduitBendingTool,
        ConduitFillTool,
        VoltageDropTool,
        PullPlannerTool,
        ReelLengthTool,
        VfdSizingTool,
        FormulasTool,
        ReferenceTool,
        ConversionsTool,
        WellPumpTool,
    )

    val default: ToolModule = FootInchTool

    init {
        val duplicates = all.groupBy { it.id }.filterValues { it.size > 1 }.keys
        check(duplicates.isEmpty()) { "Duplicate tool ids: $duplicates" }
    }
}
