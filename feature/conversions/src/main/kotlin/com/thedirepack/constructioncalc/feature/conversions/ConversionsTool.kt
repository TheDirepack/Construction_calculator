package com.thedirepack.constructioncalc.feature.conversions

import androidx.compose.runtime.Composable
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.components.PlaceholderToolScreen
import com.thedirepack.constructioncalc.core.ui.tool.ToolCategory
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost
import com.thedirepack.constructioncalc.core.ui.tool.ToolModule

object ConversionsTool : ToolModule {
    const val ID = "conversions"

    override val id = ID
    override val title = "Unit Conversions"
    override val summary = "Length, area, volume, electrical and more"
    override val category = ToolCategory.REFERENCE
    override val icon = R.drawable.ic_tool_convert

    @Composable
    override fun Content(host: ToolHost) = PlaceholderToolScreen(host, title, summary)
}
