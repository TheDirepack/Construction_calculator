package com.thedirepack.constructioncalc.feature.pullplanner

import androidx.compose.runtime.Composable
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.components.PlaceholderToolScreen
import com.thedirepack.constructioncalc.core.ui.tool.ToolCategory
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost
import com.thedirepack.constructioncalc.core.ui.tool.ToolModule

object PullPlannerTool : ToolModule {
    const val ID = "pull-planner"

    override val id = ID
    override val title = "Wire Pull Planner"
    override val summary = "Pulling tension and sidewall pressure along a run"
    override val category = ToolCategory.ELECTRICAL
    override val icon = R.drawable.ic_tool_pull

    @Composable
    override fun Content(host: ToolHost) = PlaceholderToolScreen(host, title, summary)
}
