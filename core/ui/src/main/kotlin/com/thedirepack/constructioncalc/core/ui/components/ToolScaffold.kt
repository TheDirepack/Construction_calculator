package com.thedirepack.constructioncalc.core.ui.components

import com.thedirepack.constructioncalc.core.model.ToolTarget
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.design.PanelLayout
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost

/**
 * The standard frame for a tool: menu (or back, when editing a saved item or when [onBack] is
 * given), title, the app-wide [ProjectSwitcher] under it (unless a [subtitle] replaces it), a
 * Save-to-project action when [onSave] is given, and any extra [actions].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolScaffold(
    title: String,
    host: ToolHost,
    onSave: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val editing = host.openedItem
    // Opened for a circuit or run (from Panels or a project): say which, and offer the way back.
    val linked = host.target?.takeIf { it !is ToolTarget.OfProject }
    val projects by LocalAppServices.current.projects.projects.collectAsStateWithLifecycle()
    val back = onBack ?: if (editing != null || linked != null) host::back else null
    val subtitleText = subtitle ?: editing?.let { "Editing saved item: ${it.title}" } ?: linked?.let { targetLabel(it, projects) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (subtitleText != null) {
                            Text(
                                subtitleText,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            ProjectSwitcher(onOpenProject = host::openProject, onMessage = host::showMessage)
                        }
                    }
                },
                navigationIcon = {
                    if (back != null) {
                        IconButton(onClick = back) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                        }
                    } else {
                        IconButton(onClick = host::openDrawer) {
                            Icon(painterResource(R.drawable.ic_menu), contentDescription = "Open tools menu")
                        }
                    }
                },
                actions = {
                    actions()
                    if (onSave != null) {
                        IconButton(onClick = onSave) {
                            Icon(painterResource(R.drawable.ic_save), contentDescription = "Save to project")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        floatingActionButton = floatingActionButton,
        content = content,
    )
}

/** "For LP-1 #5 Kitchen" or "For run Home run", from the project's current state. */
fun targetLabel(target: ToolTarget, projects: List<Project>): String? {
    val project = projects.firstOrNull { it.id == target.projectId } ?: return null
    return when (target) {
        is ToolTarget.OfCircuit -> project.panels.firstNotNullOfOrNull { p ->
            p.circuits.firstOrNull { it.id == target.circuitId }?.let { c ->
                "For ${p.name} ${PanelLayout.label(c)}" + c.description.takeIf { it.isNotBlank() }?.let { " · ${it.trim()}" }.orEmpty()
            }
        }
        is ToolTarget.OfRun -> project.run(target.runId)?.let { "For run ${it.name} · ${project.name}" }
        is ToolTarget.OfProject -> project.name
    }
}
