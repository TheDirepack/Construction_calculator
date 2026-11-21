package com.thedirepack.constructioncalc.feature.projects

import com.thedirepack.constructioncalc.core.model.RunPath
import com.thedirepack.constructioncalc.core.model.addBendsToRunPath
import com.thedirepack.constructioncalc.core.model.withRaceway
import com.thedirepack.constructioncalc.core.ui.tool.ToolIds
import com.thedirepack.constructioncalc.core.model.design.PanelLayout
import com.thedirepack.constructioncalc.core.model.ToolTarget
import com.thedirepack.constructioncalc.core.ui.format

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.data.ProjectSharing
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.ProjectCodec
import com.thedirepack.constructioncalc.core.model.ProjectItem
import com.thedirepack.constructioncalc.core.model.ProjectText
import com.thedirepack.constructioncalc.core.model.Run
import com.thedirepack.constructioncalc.core.model.design.ProjectAnalysis
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.components.ConfirmDialog
import com.thedirepack.constructioncalc.core.ui.components.RacewayEditorSheet
import com.thedirepack.constructioncalc.core.ui.components.ResultCard
import com.thedirepack.constructioncalc.core.ui.components.TextInputDialog
import com.thedirepack.constructioncalc.core.ui.components.ToolScaffold
import com.thedirepack.constructioncalc.core.ui.components.racewaySummary
import com.thedirepack.constructioncalc.core.ui.fractionText
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices
import com.thedirepack.constructioncalc.core.ui.tool.LocalTools
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost
import com.thedirepack.constructioncalc.core.ui.tool.find
import com.thedirepack.constructioncalc.core.ui.tool.titleOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which text dialog is open on the project page. */
private sealed interface Prompt {
    data object RenameProject : Prompt
    data object EditNote : Prompt
    data object AddRun : Prompt
    data class RenameRun(val run: Run) : Prompt
    data class EditConduit(val run: Run) : Prompt
    data object DeleteProject : Prompt
    data class DeleteRun(val run: Run) : Prompt
}

@Composable
internal fun ProjectDetailScreen(project: Project, host: ToolHost, onBack: () -> Unit) {
    val services = LocalAppServices.current
    val tools = LocalTools.current
    val settings = rememberAppSettings()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf<Prompt?>(null) }
    var menu by remember { mutableStateOf(false) }
    var openItem by remember { mutableStateOf<String?>(null) }
    val isActive = settings.activeProjectId == project.id
    val readOnly = project.archived
    // Conduit runs show their circuit count and (when the size is Auto) the size the design picked.
    val runDesigns = remember(project) {
        if (project.runs.any { it.raceway != null } || project.panels.isNotEmpty()) ProjectAnalysis.analyze(project).runs else emptyMap()
    }

    val toText = { ProjectText.render(project, tools::titleOf, header = settings.company.headerLines(), length = { settings.format(it) }) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ProjectCodec.MIME_TYPE)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { ProjectSharing.write(context, uri, project, services.clock()) } }
                .onSuccess { host.showMessage("Saved ${ProjectCodec.fileName(project)}") }
                .onFailure { host.showMessage(it.message ?: "Couldn't save the file") }
        }
    }

    ToolScaffold(
        title = project.name,
        host = host,
        onBack = onBack,
        subtitle = "${plural(project.runs.size, "run")} · ${plural(project.itemCount, "item")}" +
            when {
                readOnly -> " · Archived (read-only)"
                isActive -> " · Active"
                else -> ""
            },
        actions = {
            IconButton(onClick = {
                try {
                    context.startActivity(ProjectSharing.shareIntent(context, project, services.clock(), tools::titleOf, services.settings.settings.value.company.headerLines()))
                } catch (_: ActivityNotFoundException) {
                    host.showMessage("No app available to share with")
                }
            }) {
                Icon(painterResource(R.drawable.ic_share), contentDescription = "Share project")
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "More")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    val actions = buildList<Pair<String, () -> Unit>> {
                        if (readOnly) {
                            add("Unarchive" to {
                                archiveProject(services, project, false)
                                host.showMessage("Unarchived ${project.name}")
                            })
                        } else if (!isActive) {
                            add("Make active (save here)" to {
                                services.settings.setActiveTarget(project.id, project.runs.lastOrNull()?.id)
                            })
                        }
                        add("Save to file…" to { exporter.launch(ProjectCodec.fileName(project)) })
                        add("Share as text" to { host.shareText(project.name, toText()) })
                        add("Copy as text" to {
                            context.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText(project.name, toText()))
                        })
                        if (!readOnly) {
                            add("Rename" to { prompt = Prompt.RenameProject })
                            add((if (project.note.isBlank()) "Add note" else "Edit note") to { prompt = Prompt.EditNote })
                        }
                        add((if (readOnly) "Duplicate as editable copy" else "Duplicate") to {
                            val copy = project.duplicated("${project.name} (copy)", services.clock())
                            services.projects.import(copy)
                            host.showMessage("Duplicated as ${copy.name}")
                        })
                        if (!readOnly) {
                            add("Archive" to {
                                archiveProject(services, project, true)
                                host.showMessage("Archived ${project.name}. It's read-only until you unarchive it.")
                            })
                            add("Delete project" to { prompt = Prompt.DeleteProject })
                        }
                    }
                    actions.forEach { (label, block) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            menu = false
                            block()
                        })
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 32.dp)) {
            if (readOnly) {
                item(key = "archived-banner") {
                    Card(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Archived${project.archivedAt?.let { " " + formatDate(it) }.orEmpty()}. Everything is kept, " +
                                    "but it can't be changed until you unarchive it.",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                archiveProject(services, project, false)
                                host.showMessage("Unarchived ${project.name}")
                            }) { Text("Unarchive") }
                        }
                    }
                }
            }
            if (project.note.isNotBlank()) {
                item {
                    Text(
                        project.note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).clickable(enabled = !readOnly) { prompt = Prompt.EditNote },
                    )
                }
            }
            project.runs.forEach { run ->
                item(key = "run-${run.id}") {
                    RunHeader(
                        run = run,
                        readOnly = readOnly,
                        isTarget = isActive && settings.activeRunId == run.id,
                        conduitSummary = run.raceway?.let { raceway ->
                            val design = runDesigns[run.id]
                            racewaySummary(raceway, settings, circuits = design?.circuits?.size, autoSize = design?.conduit?.tradeSize)
                        },
                        onEditConduit = { prompt = Prompt.EditConduit(run) },
                        onRename = { prompt = Prompt.RenameRun(run) },
                        onDelete = { prompt = Prompt.DeleteRun(run) },
                        onMakeTarget = { services.settings.setActiveTarget(project.id, run.id) },
                        onShare = {
                            val text = run.items.joinToString("") { ProjectText.renderItem(it, tools::titleOf) }
                            host.shareText("${project.name} › ${run.name}", "${project.name} › ${run.name}\n\n$text")
                        },
                        circuits = runDesigns[run.id]?.circuits.orEmpty().map { (panel, c) -> "${panel.name} ${PanelLayout.label(c)}" },
                        onOpenTool = { toolId -> host.openTool(toolId, target = ToolTarget.OfRun(project.id, run.id)) },
                    )
                }
                if (run.items.isEmpty()) {
                    item(key = "empty-${run.id}") {
                        Text(
                            if (readOnly) "Empty." else "Nothing saved yet. Use the save button in any tool.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                        )
                    }
                }
                items(run.items, key = { it.id }) { item ->
                    ItemRow(item, toolTitle = tools.titleOf(item.toolId), onClick = { openItem = item.id })
                }
            }
            if (!readOnly) {
                item {
                    OutlinedButton(onClick = { prompt = Prompt.AddRun }, modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                        Text("Add run")
                    }
                }
            }
        }
    }

    openItem?.let { id ->
        val (run, item) = project.item(id) ?: return@let
        ItemSheet(
            project = project,
            run = run,
            item = item,
            host = host,
            onDismiss = { openItem = null },
        )
    }

    when (val p = prompt) {
        null -> Unit
        Prompt.RenameProject -> TextInputDialog("Rename project", project.name, "Rename", { name ->
            services.projects.edit(project.id) { pr, now -> pr.renamed(name, now) }
            prompt = null
        }, { prompt = null })
        Prompt.EditNote -> TextInputDialog("Project note", project.note, "Save", { note ->
            services.projects.edit(project.id) { pr, now -> pr.withNote(note, now) }
            prompt = null
        }, { prompt = null }, label = "Note", singleLine = false)
        Prompt.AddRun -> TextInputDialog("New run", "", "Add", { name ->
            val run = services.projects.addRun(project.id, name)
            if (run != null && isActive) services.settings.setActiveTarget(project.id, run.id)
            prompt = null
        }, { prompt = null }, label = "e.g. Panel A → RTU-3")
        is Prompt.RenameRun -> TextInputDialog("Rename run", p.run.name, "Rename", { name ->
            services.projects.edit(project.id) { pr, now -> pr.updateRun(p.run.id, now) { it.copy(name = name) } }
            prompt = null
        }, { prompt = null })
        is Prompt.EditConduit -> RacewayEditorSheet(
            title = "Conduit for ${p.run.name}",
            initial = p.run.raceway,
            onSave = { raceway ->
                services.projects.edit(project.id) { pr, now -> pr.withRaceway(p.run.id, raceway, now) }
                prompt = null
            },
            onDismiss = { prompt = null },
            routed = p.run.path.isNotEmpty(),
        )
        Prompt.DeleteProject -> ConfirmDialog(
            "Delete ${project.name}?",
            "This removes the project and everything saved in it from this device. Shared copies aren't affected.",
            "Delete",
            onConfirm = {
                services.projects.delete(project.id)
                if (isActive) services.settings.setActiveTarget(null, null)
                prompt = null
                onBack()
            },
            onDismiss = { prompt = null },
        )
        is Prompt.DeleteRun -> ConfirmDialog(
            "Delete ${p.run.name}?",
            "This removes the run and its ${plural(p.run.items.size, "item")}.",
            "Delete",
            onConfirm = {
                services.projects.edit(project.id) { pr, now -> pr.removeRun(p.run.id, now) }
                prompt = null
            },
            onDismiss = { prompt = null },
        )
    }
}

@Composable
private fun RunHeader(
    run: Run,
    readOnly: Boolean,
    isTarget: Boolean,
    conduitSummary: String?,
    onEditConduit: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMakeTarget: () -> Unit,
    onShare: () -> Unit,
    circuits: List<String>,
    onOpenTool: (String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(run.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            if (conduitSummary != null) {
                Text(fractionText(conduitSummary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (circuits.isNotEmpty()) {
                Text(
                    "Circuits: " + circuits.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isTarget) {
                Text("New saves go here", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "Run options")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (!isTarget && !readOnly) DropdownMenuItem(text = { Text("Save new results here") }, onClick = { menu = false; onMakeTarget() })
                DropdownMenuItem(text = { Text("Share run as text") }, onClick = { menu = false; onShare() })
                DropdownMenuItem(text = { Text("Open in Panels & Circuits") }, onClick = { menu = false; onOpenTool(ToolIds.PANELS) })
                if (conduitSummary != null) {
                    DropdownMenuItem(text = { Text("Check fill in Conduit Fill") }, onClick = { menu = false; onOpenTool(ToolIds.CONDUIT_FILL) })
                    DropdownMenuItem(text = { Text("Bend this conduit") }, onClick = { menu = false; onOpenTool(ToolIds.CONDUIT_BENDING) })
                    DropdownMenuItem(text = { Text("Plan pull") }, onClick = { menu = false; onOpenTool(ToolIds.PULL_PLANNER) })
                }
                if (!readOnly) {
                    DropdownMenuItem(
                        text = { Text(if (conduitSummary == null) "Add conduit…" else "Edit conduit…") },
                        onClick = { menu = false; onEditConduit() },
                    )
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Delete run") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun ItemRow(item: ProjectItem, toolTitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(toolTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item.results.firstOrNull()?.let {
            Text(fractionText(it.text), style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemSheet(project: Project, run: Run, item: ProjectItem, host: ToolHost, onDismiss: () -> Unit) {
    val services = LocalAppServices.current
    val tools = LocalTools.current
    val context = LocalContext.current
    val tool = tools.find(item.toolId)
    var confirmDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var moveMenu by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 24.dp).navigationBarsPadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(item.title, style = MaterialTheme.typography.titleLarge)
            Text(
                "${tools.titleOf(item.toolId)} · ${run.name} · ${formatDate(item.updatedAt)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ResultCard(item.results, emptyText = "No results saved.")
            val summary = item.summary.ifBlank { tool?.describe(item).orEmpty() }
            if (summary.isNotBlank()) Text(fractionText(summary), style = MaterialTheme.typography.bodyMedium)
            if (item.note.isNotBlank()) Text("Note: ${item.note}", style = MaterialTheme.typography.bodyMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (tool != null) {
                    Button(onClick = {
                        onDismiss()
                        host.openTool(item.toolId, item)
                    }) { Text("Open in ${tool.title}") }
                }
                val bends = remember(item) { tool?.pathBends(item).orEmpty() }
                if (bends.isNotEmpty() && !project.archived) {
                    val inPath = RunPath.hasSource(run.path, item.id)
                    AssistChip(onClick = {
                        services.projects.edit(project.id) { p, now -> p.addBendsToRunPath(run.id, item.id, bends, now) }
                        host.showMessage(if (inPath) "Updated its bends in ${run.name}'s path" else "Added ${bends.size} bend${if (bends.size == 1) "" else "s"} to ${run.name}'s path")
                    }, label = { Text(if (inPath) "Update run path" else "Add to run path") })
                }
                AssistChip(onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText(item.title, ProjectText.renderItem(item, tools::titleOf)))
                }, label = { Text("Copy") })
            }
            if (project.archived) {
                Text(
                    "This project is archived. Unarchive it to rename, move or delete items.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { renaming = true }) { Text("Rename") }
                if (project.runs.size > 1) {
                    Box {
                        TextButton(onClick = { moveMenu = true }) { Text("Move to…") }
                        DropdownMenu(expanded = moveMenu, onDismissRequest = { moveMenu = false }) {
                            project.runs.filter { it.id != run.id }.forEach { target ->
                                DropdownMenuItem(text = { Text(target.name) }, onClick = {
                                    services.projects.edit(project.id) { p, now -> p.moveItem(item.id, target.id, target.items.size, now) }
                                    moveMenu = false
                                    onDismiss()
                                })
                            }
                        }
                    }
                }
                TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (renaming) {
        TextInputDialog("Rename item", item.title, "Rename", { name ->
            services.projects.edit(project.id) { p, now -> p.updateItem(item.id, now) { it.copy(title = name) } }
            renaming = false
        }, { renaming = false })
    }
    if (confirmDelete) {
        ConfirmDialog("Delete ${item.title}?", "This removes the saved item from ${run.name}.", "Delete", onConfirm = {
            services.projects.edit(project.id) { p, now -> p.removeItem(item.id, now) }
            confirmDelete = false
            onDismiss()
        }, onDismiss = { confirmDelete = false })
    }
}
