package com.thedirepack.constructioncalc.core.data

import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.Run

/** Where "Save to project" goes: the active project and run. */
data class ActiveTarget(val project: Project, val run: Run?)

/**
 * The active project and run as every screen should show them: archived projects are never a save
 * target, and a missing or stale run falls back to the project's last run. One rule for the project
 * switcher, the drawer, the save sheet and the value picker.
 */
fun AppSettings.activeTarget(projects: List<Project>): ActiveTarget? {
    val project = projects.firstOrNull { it.id == activeProjectId && !it.archived } ?: return null
    return ActiveTarget(project, project.run(activeRunId ?: "") ?: project.runs.lastOrNull())
}
