package com.thedirepack.constructioncalc.core.ui.components

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thedirepack.constructioncalc.core.data.ProjectSharing
import com.thedirepack.constructioncalc.core.data.activeTarget
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices
import com.thedirepack.constructioncalc.core.ui.tool.LocalTools
import com.thedirepack.constructioncalc.core.ui.tool.titleOf

/**
 * The app-wide project picker shown at the top of every tool: the active project (or "No
 * project") with a dropdown to switch projects, pick the run new results are saved to, export
 * any project, create one, or manage them. The active project is shared by every tool: it's where
 * Save goes and whose results the value picker offers.
 *
 * @param onOpenProject opens a project's page, or the project list when null.
 */
@Composable
fun ProjectSwitcher(onOpenProject: (String?) -> Unit, onMessage: (String) -> Unit, modifier: Modifier = Modifier) {
    val services = LocalAppServices.current
    val tools = LocalTools.current
    val context = LocalContext.current
    val settings = rememberAppSettings()
    val all by services.projects.projects.collectAsStateWithLifecycle()
    val projects = all.filterNot { it.archived } // archived projects are read-only: never a save target
    val target = settings.activeTarget(all)
    val active = target?.project
    val activeRun = target?.run
    var open by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }

    Box(modifier) {
        Surface(
            onClick = { open = true },
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.semantics {
                contentDescription = "Project: ${active?.name ?: "No project"}. Change project."
            },
        ) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_folder), contentDescription = null, modifier = Modifier.size(14.dp))
                Text(
                    active?.let { p -> p.name + (activeRun?.takeIf { p.runs.size > 1 }?.let { " › ${it.name}" } ?: "") }
                        ?: "No project",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp).widthIn(max = 220.dp),
                )
                Text(" ▾", style = MaterialTheme.typography.labelMedium)
            }
        }

        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("No project", fontWeight = if (active == null) FontWeight.Bold else null) },
                leadingIcon = { Check(active == null) },
                onClick = {
                    services.settings.setActiveTarget(null, null)
                    open = false
                },
            )
            projects.forEach { project ->
                val isActive = project.id == active?.id
                DropdownMenuItem(
                    text = {
                        Text(
                            project.name,
                            fontWeight = if (isActive) FontWeight.Bold else null,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 200.dp),
                        )
                    },
                    leadingIcon = { Check(isActive) },
                    trailingIcon = {
                        IconButton(onClick = {
                            open = false
                            try {
                                context.startActivity(ProjectSharing.shareIntent(context, project, services.clock(), tools::titleOf, services.settings.settings.value))
                            } catch (_: ActivityNotFoundException) {
                                onMessage("No app available to share with")
                            }
                        }) {
                            Icon(painterResource(R.drawable.ic_share), contentDescription = "Export ${project.name}")
                        }
                    },
                    onClick = {
                        services.settings.setActiveTarget(project.id, project.runs.lastOrNull()?.id)
                        open = false
                    },
                )
                // Runs of the active project: choose where new results are saved.
                if (isActive && project.runs.size > 1) {
                    project.runs.forEach { run ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "› ${run.name}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (run.id == activeRun?.id) FontWeight.Bold else null,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingIcon = { Box(Modifier.size(24.dp)) },
                            onClick = {
                                services.settings.setActiveTarget(project.id, run.id)
                                open = false
                            },
                        )
                    }
                }
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("New project…") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                onClick = {
                    open = false
                    creating = true
                },
            )
            if (active != null) {
                DropdownMenuItem(
                    text = { Text("Open ${active.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_folder), contentDescription = null) },
                    onClick = {
                        open = false
                        onOpenProject(active.id)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Manage projects") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_edit), contentDescription = null) },
                onClick = {
                    open = false
                    onOpenProject(null)
                },
            )
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
                onMessage("Now saving to ${project.name}")
            },
            onDismiss = { creating = false },
        )
    }
}

@Composable
private fun Check(on: Boolean) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        if (on) Text("✓", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}
