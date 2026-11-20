package com.thedirepack.constructioncalc.core.data

import com.thedirepack.constructioncalc.core.model.BackupDocument
import com.thedirepack.constructioncalc.core.model.ProjectMerge
import com.thedirepack.constructioncalc.core.model.ProjectMergeMode

/** Which sections go into, or come out of, a backup. */
data class BackupSections(
    val projects: Boolean = true,
    val settings: Boolean = true,
    val toolData: Boolean = true,
    val recents: Boolean = true,
) {
    companion object {
        val ALL = BackupSections()
        val PROJECTS_ONLY = BackupSections(projects = true, settings = false, toolData = false, recents = false)
    }
}

/**
 * Builds and restores [BackupDocument]s: the "save/load everything" system. A backup can hold
 * every project, app and tool settings, tools' own data (via [ToolBackup]) and recent values.
 */
class BackupService(
    private val settings: SettingsRepository,
    private val projects: ProjectRepository,
    private val recents: RecentResultsRepository,
    private val toolBackups: Map<String, ToolBackup>,
    private val clock: () -> Long,
) {
    /** A backup of [sections]; [projectIds] limits which projects are included (null = all). */
    fun create(sections: BackupSections = BackupSections.ALL, projectIds: Set<String>? = null): BackupDocument =
        BackupDocument(
            exportedAt = clock(),
            settings = if (sections.settings) settings.exportSettings() else null,
            toolSettings = if (sections.settings) settings.exportToolSettings() else emptyMap(),
            projects = if (sections.projects) {
                projects.projects.value.filter { projectIds == null || it.id in projectIds }
            } else {
                emptyList()
            },
            toolData = if (sections.toolData) {
                toolBackups.mapNotNull { (id, backup) -> runCatching { backup.export() }.getOrNull()?.let { id to it } }.toMap()
            } else {
                emptyMap()
            },
            recents = if (sections.recents) recents.recents.value else emptyList(),
        )

    data class Report(val lines: List<String>) {
        override fun toString() = lines.joinToString(" · ").ifEmpty { "Nothing to restore" }
    }

    /** Applies the chosen [sections] of [backup]. Projects merge or replace per [mode]. */
    fun restore(backup: BackupDocument, sections: BackupSections, mode: ProjectMergeMode): Report {
        if (sections.settings) SettingsRepository.validateBackup(backup.settings, backup.toolSettings)
        val lines = mutableListOf<String>()
        if (sections.projects && backup.projects.isNotEmpty()) {
            when (mode) {
                ProjectMergeMode.REPLACE -> {
                    projects.deleteAll()
                    projects.putAll(backup.projects)
                    lines += "${backup.projects.size} projects restored"
                }
                ProjectMergeMode.MERGE -> {
                    val plan = ProjectMerge.plan(projects.projects.value, backup.projects)
                    projects.putAll(plan.toWrite)
                    lines += buildList {
                        if (plan.added > 0) add("${plan.added} added")
                        if (plan.updated > 0) add("${plan.updated} updated")
                        if (plan.unchanged > 0) add("${plan.unchanged} already up to date")
                    }.joinToString(", ", prefix = "Projects: ")
                }
            }
        }
        val replace = mode == ProjectMergeMode.REPLACE
        if (sections.settings && backup.hasSettings) {
            backup.settings?.let(settings::importSettings)
            settings.importToolSettings(backup.toolSettings, replace)
            lines += "Settings restored"
        }
        if (sections.toolData && backup.toolData.isNotEmpty()) {
            var restored = 0
            backup.toolData.forEach { (id, data) ->
                val target = toolBackups[id] ?: return@forEach // a tool this app version doesn't have
                if (runCatching { target.import(data, replace) }.isSuccess) restored++
            }
            if (restored > 0) lines += "Tool data restored"
        }
        if (sections.recents && backup.recents.isNotEmpty()) {
            recents.importAll(backup.recents, replace)
            lines += "Recent values restored"
        }
        // Drop an active project that no longer exists.
        val active = settings.settings.value.activeProjectId
        if (active != null && projects.get(active) == null) settings.setActiveTarget(null, null)
        return Report(lines)
    }
}
