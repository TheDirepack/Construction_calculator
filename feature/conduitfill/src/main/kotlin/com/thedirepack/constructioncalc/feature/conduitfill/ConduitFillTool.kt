package com.thedirepack.constructioncalc.feature.conduitfill

import com.thedirepack.constructioncalc.core.ui.tool.ToolIds
import androidx.compose.runtime.Composable
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.tool.ToolCategory
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost
import com.thedirepack.constructioncalc.core.ui.tool.ToolModule

object ConduitFillTool : ToolModule {
    const val ID = ToolIds.CONDUIT_FILL

    override val id = ID
    override val title = "Conduit Fill"
    override val summary = "NEC Chapter 9 fill for mixed wires and conduit types"
    override val category = ToolCategory.ELECTRICAL
    override val icon = R.drawable.ic_tool_fill

    @Composable
    override fun Content(host: ToolHost) = ConduitFillScreen(host)
}
