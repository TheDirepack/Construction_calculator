package com.thedirepack.constructioncalc.core.ui.components

import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.ToolTarget
import com.thedirepack.constructioncalc.core.model.design.PanelLayout

/** "For LP-1 #5 Kitchen" or "For run Home run", from the project's current state. */
fun targetLabel(target: ToolTarget, projects: List<Project>): String? {
    val project = projects.firstOrNull { it.id == target.projectId } ?: return null
    return when (target) {
        is ToolTarget.OfCircuit -> project.panels.firstNotNullOfOrNull { p ->
            p.circuits.firstOrNull { it.id == target.circuitId }?.let { c ->
                "For ${p.name} ${PanelLayout.label(c)}" + c.description.takeIf { it.isNotBlank() }?.let { " · ${it.trim()}" }.orEmpty()
            }
        }
        is ToolTarget.OfRun -> project.run(target.runId)?.let { "For run ${it.name} · ${project.name}" }
        is ToolTarget.OfProject -> project.name
    }
}
