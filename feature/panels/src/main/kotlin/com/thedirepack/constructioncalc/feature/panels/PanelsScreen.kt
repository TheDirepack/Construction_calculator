package com.thedirepack.constructioncalc.feature.panels

import com.thedirepack.constructioncalc.core.model.ToolTarget
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.design.ProjectAnalysis
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.components.ConfirmDialog
import com.thedirepack.constructioncalc.core.ui.components.TextInputDialog
import com.thedirepack.constructioncalc.core.ui.components.ToolScaffold
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost
import com.thedirepack.constructioncalc.core.model.design.PublishedValues
import com.thedirepack.constructioncalc.feature.panels.domain.duplicatePanel

private val TABS = listOf("Panels", "Runs", "Groups", "Design")

/** The Panels & Circuits tool: works on the active project. */
@Composable
fun PanelsScreen(host: ToolHost) {
    val services = LocalAppServices.current
    val settings = rememberAppSettings()
    val projects by services.projects.projects.collectAsStateWithLifecycle()
    // Opened for a circuit or run of a project (from Projects or another tool): that project; else the active one.
    val project = projects.firstOrNull { it.id == (host.target?.projectId ?: settings.activeProjectId) }
    var viewArchived by rememberSaveable(project?.id) { mutableStateOf(false) }

    if (project == null || (project.archived && !viewArchived)) {
        NoProject(host, archived = project, onViewArchived = { viewArchived = true })
    } else {
        ProjectPanels(project, host)
    }
}

/** No active project, or an archived one: say so, and offer to start or choose one. */
@Composable
private fun NoProject(host: ToolHost, archived: Project?, onViewArchived: () -> Unit) {
    val services = LocalAppServices.current
    var creating by remember { mutableStateOf(false) }
    ToolScaffold(title = PanelsTool.title, host = host) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(painterResource(R.drawable.ic_tool_panel), contentDescription = null, modifier = Modifier.padding(bottom = 16.dp))
            Text(
                if (archived == null) "Pick a project to design panels in" else "${archived.name} is archived",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                if (archived == null) {
                    "Panels, circuits and conduit runs are saved in a project, so every tool can use them. Start a new project or choose one you already have."
                } else {
                    "Archived projects are read-only. You can look at its panels, or unarchive it from Projects to edit."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { creating = true }) { Text("New project") }
                OutlinedButton(onClick = { host.openProject(null) }) { Text("Choose project") }
            }
            if (archived != null) TextButton(onClick = onViewArchived) { Text("View read-only") }
        }
    }
    if (creating) {
        TextInputDialog("New project", "", "Create", { name ->
            val project = services.projects.create(name)
            services.settings.setActiveTarget(project.id, project.runs.firstOrNull()?.id)
            creating = false
        }, { creating = false })
    }
}

@Composable
private fun ProjectPanels(project: Project, host: ToolHost) {
    val services = LocalAppServices.current
    val design = remember(project) { ProjectAnalysis.analyze(project) }
    val ctx = PanelsContext(project, design, host) { change -> services.projects.edit(project.id, change) }
    val target = host.target
    var tab by rememberSaveable { mutableIntStateOf(if (target is ToolTarget.OfRun) 1 else 0) }
    var openPanelId by rememberSaveable {
        mutableStateOf((target as? ToolTarget.OfCircuit)?.let { t -> project.panels.firstOrNull { p -> p.circuits.any { it.id == t.circuitId } }?.id })
    }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val openPanel = openPanelId?.let(project::panel)
    val export = rememberExportActions(ctx)

    BackHandler(enabled = openPanel != null) { openPanelId = null }

    val openDesign = openPanel?.let { p -> design.panels.firstOrNull { it.panel.id == p.id } }
    LaunchedEffect(openDesign) {
        openDesign?.let { host.publish(PublishedValues.panel(it), context = "Panel ${it.panel.name}") }
    }

    ToolScaffold(
        title = PanelsTool.title,
        host = host,
        onBack = if (openPanel != null) ({ openPanelId = null }) else null,
        subtitle = null,
        actions = {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(painterResource(R.drawable.ic_share), contentDescription = "Share or export")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (openPanel != null) {
                        DropdownMenuItem(text = { Text("Share ${openPanel.name} as PDF") }, onClick = { menu = false; export.pdf(listOf(openPanel.id)) })
                        DropdownMenuItem(text = { Text("Share ${openPanel.name} as text") }, onClick = { menu = false; export.text(listOf(openPanel.id)) })
                    }
                    DropdownMenuItem(
                        text = { Text(if (openPanel != null) "Share all panels as PDF" else "Share panel schedule (PDF)") },
                        onClick = { menu = false; export.pdf(null) },
                    )
                    DropdownMenuItem(
                        text = { Text(if (openPanel != null) "Share all panels as text" else "Share as text") },
                        onClick = { menu = false; export.text(null) },
                    )
                    if (openPanel != null && !ctx.readOnly) {
                        DropdownMenuItem(text = { Text("Duplicate panel") }, onClick = {
                            menu = false
                            ctx.edit { p, now -> p.duplicatePanel(openPanel.id, now) }
                            openPanelId = null
                        })
                        DropdownMenuItem(text = { Text("Delete panel") }, onClick = { menu = false; confirmDelete = true })
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (project.archived) {
                Text(
                    "Archived project: read-only. Unarchive it in Projects to edit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (openPanel != null) {
                PanelEditor(ctx, openPanel, Modifier.weight(1f))
            } else {
                PrimaryTabRow(selectedTabIndex = tab) {
                    TABS.forEachIndexed { i, label ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label, maxLines = 1) })
                    }
                }
                when (tab) {
                    0 -> PanelsTab(ctx, export, onOpen = { openPanelId = it }, Modifier.weight(1f))
                    1 -> RunsTab(ctx, Modifier.weight(1f))
                    2 -> GroupsTab(ctx, Modifier.weight(1f))
                    else -> DesignTab(ctx, Modifier.weight(1f))
                }
            }
        }
    }

    if (confirmDelete && openPanel != null) {
        ConfirmDialog(
            "Delete ${openPanel.name}?",
            "This removes the panel and its ${plural(openPanel.circuits.size, "circuit")}. Panels it fed lose their \"fed from\". Conduit runs stay.",
            "Delete",
            onConfirm = {
                ctx.edit { p, now -> p.removePanel(openPanel.id, now) }
                confirmDelete = false
                openPanelId = null
            },
            onDismiss = { confirmDelete = false },
        )
    }
}
