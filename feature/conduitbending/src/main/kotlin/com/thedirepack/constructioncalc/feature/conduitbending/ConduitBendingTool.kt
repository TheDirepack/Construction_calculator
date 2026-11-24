package com.thedirepack.constructioncalc.feature.conduitbending

import com.thedirepack.constructioncalc.core.ui.tool.ToolIds
import androidx.compose.runtime.Composable
import com.thedirepack.constructioncalc.core.model.PathSegment
import com.thedirepack.constructioncalc.core.model.ProjectItem
import com.thedirepack.constructioncalc.core.model.ToolInputs
import com.thedirepack.constructioncalc.feature.conduitbending.domain.BenderTable
import com.thedirepack.constructioncalc.feature.conduitbending.domain.PathBends
import com.thedirepack.constructioncalc.feature.conduitbending.domain.StickPlanner
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.tool.ToolCategory
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost
import com.thedirepack.constructioncalc.core.ui.tool.ToolModule

object ConduitBendingTool : ToolModule {
    const val ID = ToolIds.CONDUIT_BENDING

    override val id = ID
    override val title = "Conduit Bending"
    override val summary = "Offsets, saddles, kicks and 90s with bender marks"
    override val category = ToolCategory.ELECTRICAL
    override val icon = R.drawable.ic_tool_bend
    override val hasSettings = true

    @Composable
    override fun Content(host: ToolHost) = ConduitBendingScreen(host)

    /** A saved bend stick's individual bends for its run's path (see [PathBends]). */
    override fun pathBends(item: ProjectItem): List<PathSegment.Bend> {
        val inputs = ToolInputs.decode<ConduitBendingInputs>(item.inputs) ?: return emptyList()
        val plan = StickPlanner.plan(inputs.toStick(BenderTable.DEFAULT))
        return PathBends.of(plan)
    }

    @Composable
    override fun SettingsContent() = BenderSettingsPage()
}
