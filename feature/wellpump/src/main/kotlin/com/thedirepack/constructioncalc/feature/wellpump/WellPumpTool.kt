package com.thedirepack.constructioncalc.feature.wellpump

import androidx.compose.runtime.Composable
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.components.PlaceholderToolScreen
import com.thedirepack.constructioncalc.core.ui.tool.ToolCategory
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost
import com.thedirepack.constructioncalc.core.ui.tool.ToolModule

object WellPumpTool : ToolModule {
    const val ID = "well-pump"

    override val id = ID
    override val title = "Well & Pump"
    override val summary = "Well volume, pump head and related calculations"
    override val category = ToolCategory.WATER
    override val icon = R.drawable.ic_tool_well

    @Composable
    override fun Content(host: ToolHost) = PlaceholderToolScreen(host, title, summary)
}
