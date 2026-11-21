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

@Composable
fun rememberBackupExporter(onMessage: (String) -> Unit): BackupExporter {
    val context = LocalContext.current
    val services = LocalAppServices.current
    val tools = LocalTools.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<String?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ProjectCodec.MIME_TYPE)) { uri ->
        val text = pending
        pending = null
        if (uri == null || text == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { ProjectSharing.writeText(context, uri, text) } }
                .onSuccess { onMessage("Saved") }
                .onFailure { onMessage(it.message ?: "Couldn't save the file") }
        }
    }
    return remember(services, tools) {
        BackupExporter(
            share = { sections, label ->
                val backup = services.backup.create(sections)
                val name = AppFileCodec.backupFileName(services.clock(), label)
                try {
                    context.startActivity(ProjectSharing.shareBackupIntent(context, backup, name, tools::titleOf))
                } catch (_: ActivityNotFoundException) {
                    onMessage("No app available to share with")
                }
            },
            saveToFile = { sections, label ->
                pending = AppFileCodec.encodeBackup(services.backup.create(sections))
                saver.launch(AppFileCodec.backupFileName(services.clock(), label))
            },
        )
    }
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
    val services = LocalAppServices.current
    val tools = LocalTools.current
    var projects by remember { mutableStateOf(backup.projects.isNotEmpty()) }
    var settings by remember { mutableStateOf(backup.hasSettings) }
    var toolData by remember { mutableStateOf(backup.toolData.isNotEmpty()) }
    var recents by remember { mutableStateOf(backup.recents.isNotEmpty()) }
    var mode by remember { mutableStateOf(ProjectMergeMode.MERGE) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore backup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                backup.exportedAt?.let { Text("Made ${formatDate(it)}", style = MaterialTheme.typography.bodyMedium) }
                if (backup.projects.isNotEmpty()) {
                    CheckRow("${backup.projects.size} projects (${backup.projects.sumOf { it.itemCount }} items)", projects) { projects = it }
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
                        ProjectMergeMode.REPLACE -> "Deletes the projects (and chosen data) on this device first, then restores the backup."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (mode == ProjectMergeMode.REPLACE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = projects || settings || toolData || recents,
                onClick = {
                    runCatching {
                        services.backup.restore(backup, BackupSections(projects, settings, toolData, recents), mode)
                    }.onSuccess { onFinished(it.toString(), null) }
                        .onFailure { error = it.message ?: "The backup could not be restored." }
                },
            ) { Text("Restore") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
