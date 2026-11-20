package com.thedirepack.constructioncalc.feature.reellength

import androidx.compose.runtime.Composable
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.tool.ToolCategory
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost
import com.thedirepack.constructioncalc.core.ui.tool.ToolModule

object ReelLengthTool : ToolModule {
    const val ID = "reel-length"

    override val id = ID
    override val title = "Reel Length"
    override val summary = "Cable left on a partial reel, and reel capacity"
    override val category = ToolCategory.ELECTRICAL
    override val icon = R.drawable.ic_tool_reel

    @Composable
    override fun Content(host: ToolHost) = ReelLengthScreen(host)
}
