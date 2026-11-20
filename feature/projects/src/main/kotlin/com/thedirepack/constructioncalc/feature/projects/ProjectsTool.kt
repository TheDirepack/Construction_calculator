package com.thedirepack.constructioncalc.feature.projects

import androidx.compose.runtime.Composable
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.tool.ToolCategory
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost
import com.thedirepack.constructioncalc.core.ui.tool.ToolModule
import kotlinx.coroutines.flow.MutableStateFlow

/** Projects → runs → saved items from every tool. Shared and imported as one JSON file. */
object ProjectsTool : ToolModule {
    const val ID = "projects"

    override val id = ID
    override val title = "Projects"
    override val summary = "Jobs, runs and saved results. Share as one file."
    override val category = ToolCategory.PROJECTS
    override val icon = R.drawable.ic_folder

    /** Set by the app shell (e.g. after importing a file) to open a project's page. */
    internal val openRequest = MutableStateFlow<String?>(null)

    fun requestOpen(projectId: String) {
        openRequest.value = projectId
    }

    /** Shows the project list (closing any open project page). */
    fun requestList() {
        openRequest.value = ""
    }

    @Composable
    override fun Content(host: ToolHost) = ProjectsScreen(host)
}
