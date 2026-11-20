package com.thedirepack.constructioncalc.feature.voltagedrop

import com.thedirepack.constructioncalc.core.ui.tool.ToolIds
import androidx.compose.runtime.Composable
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.tool.ToolCategory
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost
import com.thedirepack.constructioncalc.core.ui.tool.ToolModule

object VoltageDropTool : ToolModule {
    const val ID = ToolIds.VOLTAGE_DROP

    override val id = ID
    override val title = "Voltage Drop"
    override val summary = "Drop, % drop and minimum wire size for a run"
    override val category = ToolCategory.ELECTRICAL
    override val icon = R.drawable.ic_bolt

    @Composable
    override fun Content(host: ToolHost) = VoltageDropScreen(host)
}
