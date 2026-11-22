package com.thedirepack.constructioncalc.core.data

import com.thedirepack.constructioncalc.core.model.BackupDocument
import com.thedirepack.constructioncalc.core.model.ProjectMergeMode
import com.thedirepack.constructioncalc.core.model.RecentResult
import kotlinx.serialization.json.JsonObject

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

internal interface BackupSettingsPort {
    fun exportSettings(): JsonObject
    fun exportToolSettings(): Map<String, JsonObject>
    fun importSettings(json: JsonObject)
    fun importToolSettings(toolSettings: Map<String, JsonObject>, replace: Boolean)
    fun activeProjectId(): String?
    fun clearActiveTarget()
}

internal interface BackupRecentsPort {
    fun current(): List<RecentResult>
    fun importAll(results: List<RecentResult>, replace: Boolean)
}

/**
 * Builds and restores [BackupDocument]s: the "save/load everything" system. A backup can hold
 * every project, app and tool settings, tools' own data (via [ToolBackup]) and recent values.
 */
class BackupService internal constructor(
    private val settings: BackupSettingsPort,
    private val projects: ProjectRepository,
    private val recents: BackupRecentsPort,
    private val toolBackups: Map<String, ToolBackup>,
    private val clock: () -> Long,
) {
    constructor(
        settings: SettingsRepository,
        projects: ProjectRepository,
        recents: RecentResultsRepository,
        toolBackups: Map<String, ToolBackup>,
        clock: () -> Long,
    ) : this(
        settings = object : BackupSettingsPort {
            override fun exportSettings() = settings.exportSettings()
            override fun exportToolSettings() = settings.exportToolSettings()
            override fun importSettings(json: JsonObject) = settings.importSettings(json)
            override fun importToolSettings(toolSettings: Map<String, JsonObject>, replace: Boolean) =
                settings.importToolSettings(toolSettings, replace)
            override fun activeProjectId() = settings.settings.value.activeProjectId
            override fun clearActiveTarget() = settings.setActiveTarget(null, null)
        },
        projects = projects,
        recents = object : BackupRecentsPort {
            override fun current() = recents.recents.value
            override fun importAll(results: List<RecentResult>, replace: Boolean) = recents.importAll(results, replace)
        },
        toolBackups = toolBackups,
        clock = clock,
    )

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
            recents = if (sections.recents) recents.current() else emptyList(),
        )

    data class Report(val lines: List<String>) {
        override fun toString() = lines.joinToString(" · ").ifEmpty { "Nothing to restore" }
    }

    /** Applies the chosen [sections] of [backup]. Projects merge or replace per [mode]. */
    suspend fun restore(backup: BackupDocument, sections: BackupSections, mode: ProjectMergeMode): Report {
        if (sections.settings) SettingsRepository.validateBackup(backup.settings, backup.toolSettings)
        val lines = mutableListOf<String>()
        if (sections.projects && backup.projects.isNotEmpty()) {
            val plan = projects.restore(backup.projects, mode)
            when (mode) {
                ProjectMergeMode.REPLACE -> lines += "${backup.projects.size} projects restored"
                ProjectMergeMode.MERGE -> {
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
        val active = settings.activeProjectId()
        if (active != null && projects.get(active) == null) settings.clearActiveTarget()
        return Report(lines)
    }
}
