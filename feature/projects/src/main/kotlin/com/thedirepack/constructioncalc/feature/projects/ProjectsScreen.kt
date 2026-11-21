package com.thedirepack.constructioncalc.feature.projects

import com.thedirepack.constructioncalc.core.model.ToolTarget
import androidx.compose.foundation.layout.PaddingValues
import com.thedirepack.constructioncalc.core.data.AppServices
import com.thedirepack.constructioncalc.core.ui.components.rememberProjectSharer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thedirepack.constructioncalc.core.data.BackupSections
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.components.TextInputDialog
import com.thedirepack.constructioncalc.core.ui.components.rememberBackupExporter
import com.thedirepack.constructioncalc.core.ui.components.ToolScaffold
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ProjectsScreen(host: ToolHost) {
    val services = LocalAppServices.current
    val projects by services.projects.projects.collectAsStateWithLifecycle()
    // Opened for a project (from the switcher, an import…): show its page; otherwise the list.
    var openId by rememberSaveable(host.target) { mutableStateOf((host.target as? ToolTarget.OfProject)?.projectId) }

    val open = projects.firstOrNull { it.id == openId }
    if (open != null) {
        BackHandler { openId = null }
        ProjectDetailScreen(open, host, onBack = { openId = null })
    } else {
        ProjectListScreen(projects, host, onOpen = { openId = it })
    }
}

@Composable
private fun ProjectListScreen(projects: List<Project>, host: ToolHost, onOpen: (String) -> Unit) {
    val services = LocalAppServices.current
    val settings = rememberAppSettings()
    var creating by remember { mutableStateOf(false) }

    var menu by remember { mutableStateOf(false) }
    val openFile = host::openFile
    val exporter = rememberBackupExporter(host::showMessage)
    var showArchive by rememberSaveable { mutableStateOf(false) }
    val share = rememberProjectSharer(host::showMessage)
    val setArchived: (Project, Boolean) -> Unit = { project, archive ->
        archiveProject(services, project, archive)
        host.showMessage(if (archive) "Archived ${project.name}. It's read-only until you unarchive it." else "Unarchived ${project.name}")
    }

    ToolScaffold(
        title = "Projects",
        host = host,
        actions = {
            IconButton(onClick = openFile) {
                Icon(painterResource(R.drawable.ic_file_open), contentDescription = "Import a project or backup file")
            }
            if (projects.isNotEmpty()) {
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "More")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Share all projects") }, onClick = {
                            menu = false
                            exporter.share(BackupSections.PROJECTS_ONLY, "Construction Calc projects")
                        })
                        DropdownMenuItem(text = { Text("Save all projects to a file") }, onClick = {
                            menu = false
                            exporter.saveToFile(BackupSections.PROJECTS_ONLY, "Construction Calc projects")
                        })
                    }
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                text = { Text("New project") },
            )
        },
    ) { padding ->
        if (projects.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No projects yet", style = MaterialTheme.typography.titleLarge)
                Text(
                    "A project holds runs, and each run holds results saved from any tool: lengths, bends, " +
                        "voltage drop, pulls… Share a whole project as one file by email or text, and open " +
                        "files others send you.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { creating = true }) { Text("New project") }
                OutlinedButton(onClick = openFile) { Text("Open a project or backup file") }
            }
        } else {
            val (archived, current) = projects.partition { it.archived }
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
                if (current.isEmpty()) {
                    item {
                        Text(
                            "No open projects. Create one, or unarchive one below.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
                items(current, key = { it.id }) { project ->
                    ProjectRow(
                        project,
                        active = project.id == settings.activeProjectId,
                        onClick = { onOpen(project.id) },
                        onShare = { share(project) },
                        onArchiveChange = { setArchived(project, it) },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
                if (archived.isNotEmpty()) {
                    item(key = "archive-header") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { showArchive = !showArchive }
                                .padding(horizontal = 24.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Archive (${archived.size})",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            Text(if (showArchive) "Hide ▴" else "Show ▾", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    if (showArchive) {
                        items(archived, key = { it.id }) { project ->
                            ProjectRow(
                                project,
                                active = false,
                                onClick = { onOpen(project.id) },
                                onShare = { share(project) },
                                onArchiveChange = { setArchived(project, it) },
                            )
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        TextInputDialog(
            title = "New project",
            initial = "",
            confirmLabel = "Create",
            onConfirm = { name ->
                val project = services.projects.create(name)
                services.settings.setActiveTarget(project.id, project.runs.firstOrNull()?.id)
                creating = false
                onOpen(project.id)
            },
            onDismiss = { creating = false },
        )
    }
}

@Composable
private fun ProjectRow(
    project: Project,
    active: Boolean,
    onClick: () -> Unit,
    onShare: () -> Unit,
    onArchiveChange: (Boolean) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 24.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                project.name,
                style = MaterialTheme.typography.titleMedium,
                color = if (project.archived) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                if (project.archived) {
                    "Archived ${project.archivedAt?.let(::formatDate).orEmpty()} · ${plural(project.itemCount, "item")} · read-only"
                } else {
                    "${plural(project.runs.size, "run")} · ${plural(project.itemCount, "item")} · ${formatDate(project.updatedAt)}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (active) {
            Text("Active", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        IconButton(onClick = onShare) {
            Icon(painterResource(R.drawable.ic_share), contentDescription = "Export ${project.name}")
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "${project.name} options")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(if (project.archived) "Unarchive" else "Archive") },
                    onClick = {
                        menu = false
                        onArchiveChange(!project.archived)
                    },
                )
            }
        }
    }
}

/** Archives or unarchives; an archived project can't stay the active save target. */
internal fun archiveProject(services: AppServices, project: Project, archive: Boolean) {
    services.projects.setArchived(project.id, archive)
    if (archive && services.settings.settings.value.activeProjectId == project.id) {
        services.settings.setActiveTarget(null, null)
    }
}

internal fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

internal fun formatDate(epochMillis: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMillis))
