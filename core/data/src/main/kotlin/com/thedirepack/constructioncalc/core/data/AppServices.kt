package com.thedirepack.constructioncalc.core.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The app's shared state, created once per process (in the Application class) and handed to
 * every tool through `LocalAppServices` in :core:ui.
 */
class AppServices(
    val settings: SettingsRepository,
    val projects: ProjectRepository,
    val recents: RecentResultsRepository,
    val backup: BackupService,
    val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        /** [toolBackups]: each tool's [ToolBackup], keyed by tool id (from `ToolModule.backup`). */
        fun create(context: Context, toolBackups: Map<String, ToolBackup> = emptyMap()): AppServices {
            val app = context.applicationContext
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val clock: () -> Long = System::currentTimeMillis
            val settings = SettingsRepository(app)
            val projects = ProjectRepository(ProjectStore.files(app), scope, clock)
            val recents = RecentResultsRepository(app)
            return AppServices(
                settings = settings,
                projects = projects,
                recents = recents,
                backup = BackupService(settings, projects, recents, toolBackups, clock),
                clock = clock,
            )
        }
    }
}
