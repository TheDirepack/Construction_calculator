package com.thedirepack.constructioncalc.feature.conduitbending

import androidx.compose.runtime.Composable
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.tool.ToolCategory
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost
import com.thedirepack.constructioncalc.core.ui.tool.ToolModule

object ConduitBendingTool : ToolModule {
    const val ID = "conduit-bending"

    override val id = ID
    override val title = "Conduit Bending"
    override val summary = "Offsets, saddles, kicks and 90s with bender marks"
    override val category = ToolCategory.ELECTRICAL
    override val icon = R.drawable.ic_tool_bend
    override val hasSettings = true

    @Composable
    override fun Content(host: ToolHost) = ConduitBendingScreen(host)

    @Composable
    override fun SettingsContent() = BenderSettingsPage()
}
