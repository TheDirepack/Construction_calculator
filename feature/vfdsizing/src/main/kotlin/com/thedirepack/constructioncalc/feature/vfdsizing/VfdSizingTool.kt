package com.thedirepack.constructioncalc.feature.vfdsizing

import androidx.compose.runtime.Composable
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.tool.ToolCategory
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost
import com.thedirepack.constructioncalc.core.ui.tool.ToolIds
import com.thedirepack.constructioncalc.core.ui.tool.ToolModule

object VfdSizingTool : ToolModule {
    const val ID = ToolIds.VFD_SIZING

    override val id = ID
    override val title = "VFD Sizing"
    override val summary = "Drive, conductor and protection sizing for a motor"
    override val category = ToolCategory.ELECTRICAL
    override val icon = R.drawable.ic_tool_vfd

    @Composable
    override fun Content(host: ToolHost) = VfdSizingScreen(host)
}
