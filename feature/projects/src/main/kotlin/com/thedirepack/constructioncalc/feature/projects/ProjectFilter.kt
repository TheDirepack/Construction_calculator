package com.thedirepack.constructioncalc.feature.projects

import com.thedirepack.constructioncalc.core.model.Project

/**
 * Filters [projects] by [query], matching case-insensitively against project name, note,
 * panel names, circuit descriptions, and run names. Multi-word queries require every word
 * to match at least one of those fields.
 */
internal fun filterProjects(projects: List<Project>, query: String): List<Project> {
    val tokens = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return projects
    return projects.filter { project -> tokens.all { token -> projectMatchesToken(project, token) } }
}

internal fun projectMatchesQuery(project: Project, query: String): Boolean {
    val tokens = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return tokens.all { token -> projectMatchesToken(project, token) }
}

private fun projectMatchesToken(project: Project, token: String): Boolean {
    if (project.name.contains(token, ignoreCase = true)) return true
    if (project.note.contains(token, ignoreCase = true)) return true
    if (project.runs.any { it.name.contains(token, ignoreCase = true) }) return true
    if (project.panels.any { panel ->
            panel.name.contains(token, ignoreCase = true) ||
                panel.circuits.any { it.description.contains(token, ignoreCase = true) }
        }
    ) return true
    return false
}
