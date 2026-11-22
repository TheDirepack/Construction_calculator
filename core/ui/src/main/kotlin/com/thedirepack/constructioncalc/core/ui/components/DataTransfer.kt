package com.thedirepack.constructioncalc.core.ui.components

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.data.BackupSections
import com.thedirepack.constructioncalc.core.data.ProjectSharing
import com.thedirepack.constructioncalc.core.model.AppFile
import com.thedirepack.constructioncalc.core.model.AppFileCodec
import com.thedirepack.constructioncalc.core.model.BackupDocument
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.ProjectCodec
import com.thedirepack.constructioncalc.core.model.ProjectMergeMode
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices
import com.thedirepack.constructioncalc.core.ui.tool.LocalTools
import com.thedirepack.constructioncalc.core.ui.tool.titleOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

// Save/load for everything: open any app file, export backups by share sheet or file picker.

/**
 * Returns a function that shows the system file picker and reads a project or backup file.
 * No storage permission is needed: the picker grants access to the one file chosen.
 */
@Composable
fun rememberAppFileOpener(onResult: (Result<AppFile>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            onResult(runCatching { withContext(Dispatchers.IO) { ProjectSharing.readAppFile(context, uri) } })
        }
    }
    return { launcher.launch(ProjectSharing.OPEN_MIME_TYPES) }
}

@Composable
fun rememberProjectSharer(onMessage: (String) -> Unit): (Project) -> Unit {
    val context = LocalContext.current
    val services = LocalAppServices.current
    val tools = LocalTools.current
    val scope = rememberCoroutineScope()
    return { project ->
        scope.launch {
            runCatching {
                val intent = withContext(Dispatchers.IO) {
                    ProjectSharing.shareIntent(context, project, services.clock(), tools::titleOf, services.settings.settings.value)
                }
                context.startActivity(intent)
            }.onFailure {
                onMessage(if (it is ActivityNotFoundException) "No app available to share with" else it.message ?: "Couldn't share the project.")
            }
        }
    }
}

/** Exports backups: share by email/messages/Drive, or save to a file the user picks. */
class BackupExporter internal constructor(
    val share: (sections: BackupSections, label: String) -> Unit,
    val saveToFile: (sections: BackupSections, label: String) -> Unit,
)

private data class BackupExportRequest(
    val backup: BackupDocument,
    val label: String,
    val share: Boolean,
)

private fun List<String>.backupMediaDetails(): String = buildString {
    take(5).forEach { appendLine("• $it") }
    if (size > 5) append("…and ${size - 5} more.")
}.trimEnd()

@Composable
fun rememberBackupExporter(onMessage: (String) -> Unit): BackupExporter {
    val context = LocalContext.current
    val services = LocalAppServices.current
    val tools = LocalTools.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<BackupDocument?>(null) }
    var incompleteRequest by remember { mutableStateOf<BackupExportRequest?>(null) }
    var missingMedia by remember { mutableStateOf<List<String>>(emptyList()) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ProjectSharing.ZIP_MIME_TYPE)) { uri ->
        val backup = pending
        pending = null
        if (uri == null || backup == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { ProjectSharing.writeBackup(context, uri, backup) } }
                .onSuccess { onMessage(if (backup.incompleteMedia) "Incomplete backup saved" else "Backup saved") }
                .onFailure { onMessage(it.message ?: "Couldn't save the file") }
        }
    }

    suspend fun export(request: BackupExportRequest) {
        val label = if (request.backup.incompleteMedia) "${request.label} (incomplete)" else request.label
        val name = ProjectSharing.backupFileName(services.clock(), label)
        if (request.share) {
            val intent = withContext(Dispatchers.IO) {
                ProjectSharing.shareBackupIntent(context, request.backup, name, tools::titleOf)
            }
            try {
                context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                onMessage("No app available to share with")
            }
        } else {
            pending = request.backup
            saver.launch(name)
        }
    }

    fun prepare(sections: BackupSections, label: String, share: Boolean) {
        scope.launch {
            try {
                val (backup, missing) = withContext(Dispatchers.IO) {
                    val backup = services.backup.create(sections)
                    backup to ProjectSharing.missingBackupMedia(context, backup)
                }
                val request = BackupExportRequest(backup, label, share)
                if (missing.isEmpty()) {
                    export(request)
                } else {
                    missingMedia = missing
                    incompleteRequest = request
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                onMessage(failure.message ?: "Couldn't create the backup")
            }
        }
    }

    incompleteRequest?.let { request ->
        AlertDialog(
            onDismissRequest = { incompleteRequest = null },
            title = { Text("Backup media is missing") },
            text = {
                Text("This backup will be marked incomplete. It can restore the available data. These files are missing:\n\n${missingMedia.backupMediaDetails()}")
            },
            confirmButton = {
                TextButton(onClick = {
                    incompleteRequest = null
                    scope.launch {
                        try {
                            export(request.copy(backup = request.backup.copy(incompleteMedia = true)))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            onMessage(failure.message ?: "Couldn't create the backup")
                        }
                    }
                }) { Text(if (request.share) "Share incomplete backup" else "Save incomplete backup") }
            },
            dismissButton = {
                TextButton(onClick = { incompleteRequest = null }) { Text("Cancel") }
            },
        )
    }
    return BackupExporter(
        share = { sections, label -> prepare(sections, label, share = true) },
        saveToFile = { sections, label -> prepare(sections, label, share = false) },
    )
}

/**
 * Confirms importing a file someone opened or picked: a single project (with "Replace mine" /
 * "Keep both" when it already exists), or a backup (choose sections; merge or replace).
 *
 * @param onFinished message for the user, and the project to open (single-project imports).
 */
@Composable
fun ImportFileDialog(file: AppFile, onFinished: (message: String, openProjectId: String?) -> Unit, onDismiss: () -> Unit) {
    // Opening a file can start the app; wait until saved projects are loaded so "already have it"
    // checks and merges see them (otherwise an import could silently overwrite a local project).
    val loaded by LocalAppServices.current.projects.loaded.collectAsStateWithLifecycle()
    if (!loaded) return
    when (file) {
        is AppFile.SingleProject -> ImportProjectDialog(file.project, onFinished, onDismiss)
        is AppFile.Backup -> RestoreBackupDialog(file.backup, onFinished, onDismiss)
    }
}

@Composable
private fun ImportProjectDialog(project: Project, onFinished: (String, String?) -> Unit, onDismiss: () -> Unit) {
    val services = LocalAppServices.current
    val all by services.projects.projects.collectAsStateWithLifecycle()
    val existing = all.firstOrNull { it.id == project.id }
    val info = "${project.runs.size} runs · ${project.itemCount} items · updated ${formatDate(project.updatedAt)}"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Import “${project.name}”?" else "You already have “${existing.name}”") },
        text = {
            Text(
                if (existing == null) {
                    info
                } else {
                    "This file: $info\nOn this device: updated ${formatDate(existing.updatedAt)}" +
                        (if (project.updatedAt < existing.updatedAt) "\nThe copy on this device is newer." else "") +
                        (if (existing.archived) "\nYours is archived (read-only), so this file can only be added as a copy." else "")
                },
            )
        },
        confirmButton = {
            TextButton(enabled = existing?.archived != true, onClick = {
                if (existing == null) {
                    val imported = services.projects.import(project)
                    onFinished("Imported ${imported.name}", imported.id)
                } else {
                    services.projects.replace(project)
                    onFinished("Replaced ${project.name}", project.id)
                }
            }) { Text(if (existing == null) "Import" else "Replace mine") }
        },
        dismissButton = {
            Row {
                if (existing != null) {
                    TextButton(onClick = {
                        val copy = services.projects.import(project) // existing id → saved as a copy
                        onFinished("Imported as ${copy.name}", copy.id)
                    }) { Text("Keep both") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun RestoreBackupDialog(backup: BackupDocument, onFinished: (String, String?) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val services = LocalAppServices.current
    val tools = LocalTools.current
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf(backup.projects.isNotEmpty()) }
    var settings by remember { mutableStateOf(backup.hasSettings) }
    var toolData by remember { mutableStateOf(backup.toolData.isNotEmpty()) }
    var recents by remember { mutableStateOf(backup.recents.isNotEmpty()) }
    var mode by remember { mutableStateOf(ProjectMergeMode.MERGE) }
    var error by remember { mutableStateOf<String?>(null) }
    var restoring by remember { mutableStateOf(false) }
    val missingMedia by produceState<List<String>?>(null, context, backup) {
        value = withContext(Dispatchers.IO) { ProjectSharing.missingBackupMedia(context, backup) }
    }

    AlertDialog(
        onDismissRequest = { if (!restoring) onDismiss() },
        title = { Text("Restore backup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                backup.exportedAt?.let { Text("Made ${formatDate(it)}", style = MaterialTheme.typography.bodyMedium) }
                if (backup.projects.isNotEmpty()) {
                    CheckRow("${backup.projects.size} projects (${backup.projects.sumOf { it.itemCount }} items)", projects) { projects = it }
                }
                if (projects) {
                    val missing = missingMedia.orEmpty()
                    if (backup.incompleteMedia || missing.isNotEmpty()) {
                        Text(
                            if (missing.isEmpty()) "This backup was exported with missing media."
                            else "These drawing files or photo pins are unavailable on this device:\n${missing.backupMediaDetails()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (backup.hasSettings) CheckRow("Settings (app and tools)", settings) { settings = it }
                if (backup.toolData.isNotEmpty()) {
                    CheckRow("Tool data: " + backup.toolData.keys.joinToString { tools.titleOf(it) }, toolData) { toolData = it }
                }
                if (backup.recents.isNotEmpty()) CheckRow("Recent values", recents) { recents = it }
                SegmentedChoice(
                    options = ProjectMergeMode.entries,
                    selected = mode,
                    onSelect = { mode = it },
                    optionLabel = { it.label },
                )
                Text(
                    when (mode) {
                        ProjectMergeMode.MERGE -> "Adds what's new and updates projects where the backup is newer. Nothing is deleted."
                        ProjectMergeMode.REPLACE -> "Replaces the projects and selected data on this device with the backup."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (mode == ProjectMergeMode.REPLACE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !restoring && (!projects || missingMedia != null) && (projects || settings || toolData || recents),
                onClick = {
                    val sections = BackupSections(projects, settings, toolData, recents)
                    val selectedMode = mode
                    restoring = true
                    error = null
                    scope.launch {
                        try {
                            val report = services.backup.restore(backup, sections, selectedMode)
                            onFinished(report.toString(), null)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = failure.message ?: "The backup could not be restored."
                        } finally {
                            restoring = false
                        }
                    }
                },
            ) { Text(if (restoring) "Restoring…" else "Restore") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !restoring) { Text("Cancel") } },
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatDate(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMillis))
