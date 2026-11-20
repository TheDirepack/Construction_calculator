package com.thedirepack.constructioncalc.feature.panels

import androidx.compose.runtime.Composable
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.tool.ToolCategory
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost
import com.thedirepack.constructioncalc.core.ui.tool.ToolModule

object PanelsTool : ToolModule {
    const val ID = "panels"

    override val id = ID
    override val title = "Panels & Circuits"
    override val summary = "Panel schedules, circuits, runs and wire sizing for the project"
    override val category = ToolCategory.ELECTRICAL
    override val icon = R.drawable.ic_tool_panel

    @Composable
    override fun Content(host: ToolHost) = PanelsScreen(host)
}
