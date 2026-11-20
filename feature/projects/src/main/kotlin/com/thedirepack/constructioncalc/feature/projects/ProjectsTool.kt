package com.thedirepack.constructioncalc.feature.projects

import com.thedirepack.constructioncalc.core.ui.tool.ToolIds
import androidx.compose.runtime.Composable
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.tool.ToolCategory
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost
import com.thedirepack.constructioncalc.core.ui.tool.ToolModule

/** Projects → runs → saved items from every tool. Shared and imported as one JSON file. */
object ProjectsTool : ToolModule {
    const val ID = ToolIds.PROJECTS

    override val id = ID
    override val title = "Projects"
    override val summary = "Jobs, runs and saved results. Share as one file."
    override val category = ToolCategory.PROJECTS
    override val icon = R.drawable.ic_folder

    @Composable
    override fun Content(host: ToolHost) = ProjectsScreen(host)
}
