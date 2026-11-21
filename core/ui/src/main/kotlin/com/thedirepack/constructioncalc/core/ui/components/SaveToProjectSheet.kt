package com.thedirepack.constructioncalc.core.ui.components

import com.thedirepack.constructioncalc.core.model.Raceway
import com.thedirepack.constructioncalc.core.model.RunPath
import com.thedirepack.constructioncalc.core.model.addBendsToRunPath
import com.thedirepack.constructioncalc.core.model.syncRunLength
import com.thedirepack.constructioncalc.core.model.withRunPath
import com.thedirepack.constructioncalc.core.ui.formatDecimal
import com.thedirepack.constructioncalc.core.model.ToolTarget
import com.thedirepack.constructioncalc.core.data.activeTarget
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thedirepack.constructioncalc.core.model.ProjectItem
import com.thedirepack.constructioncalc.core.ui.fractionText
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.core.ui.tool.ItemDraft
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices

private const val NEW = "\u0000new"

/**
 * Saves a calculation into a project run. Remembers the last project/run used, can create both
 * inline, and when [editing] is set can update that saved item instead of adding a new one.
 *
 * @param onSaved called with a confirmation message, e.g. "Saved to Smith Residence › Panel A".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveToProjectSheet(
    draft: ItemDraft,
    editing: ProjectItem?,
    onSaved: (String) -> Unit,
    onDismiss: () -> Unit,
    /** The project, circuit or run the tool was opened for: saving defaults to it. */
    target: ToolTarget? = null,
) {
    val services = LocalAppServices.current
    val settings = rememberAppSettings()
    val allProjects by services.projects.projects.collectAsStateWithLifecycle()
    // Archived projects are read-only: they can't receive items or have items updated.
    val projects = allProjects.filterNot { it.archived }

    val editingProject = remember(editing, projects) { editing?.let { e -> projects.firstOrNull { it.item(e.id) != null } } }
    var updateExisting by remember { mutableStateOf(editingProject != null) }

    var title by remember { mutableStateOf(editing?.title ?: draft.title) }
    var note by remember { mutableStateOf(editing?.note.orEmpty()) }
    var projectId by remember {
        mutableStateOf(
            editingProject?.id
                ?: target?.projectId?.takeIf { id -> projects.any { it.id == id } }
                ?: settings.activeProjectId?.takeIf { id -> projects.any { it.id == id } }
                ?: projects.firstOrNull()?.id
                ?: NEW,
        )
    }
    var newProjectName by remember { mutableStateOf("") }
    val project = projects.firstOrNull { it.id == projectId }
    var runId by remember(projectId) {
        mutableStateOf(
            (target as? ToolTarget.OfRun)?.runId?.takeIf { id -> project?.run(id) != null }
                ?: settings.activeTarget(projects)?.takeIf { it.project.id == project?.id }?.run?.id
                ?: project?.runs?.lastOrNull()?.id
                ?: NEW,
        )
    }
    var newRunName by remember { mutableStateOf("") }
    // A calculation done for a conduit (a bend) can set the run's conduit, by default when it has none.
    val conduit = draft.conduit
    var useConduit by remember(projectId, runId) { mutableStateOf(conduit != null && project?.run(runId)?.raceway == null) }
    // A bend can add its bends to the run's path (on by default when the run already has a path).
    val bends = draft.pathBends
    var addBends by remember(projectId, runId) { mutableStateOf(bends.isNotEmpty() && project?.run(runId)?.path?.isNotEmpty() == true) }
    // A whole route (Pull Planner without a project) can become the run's path and pull settings.
    val route = draft.route
    var useRoute by remember(projectId, runId) { mutableStateOf(route != null && project?.run(runId)?.path.isNullOrEmpty()) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Save to project", style = MaterialTheme.typography.titleLarge)
            if (draft.summary.isNotBlank()) {
                Text(
                    fractionText(draft.summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (editingProject != null) {
                SegmentedChoice(
                    options = listOf(true, false),
                    selected = updateExisting,
                    onSelect = { updateExisting = it },
                    optionLabel = { if (it) "Update saved item" else "Save as new" },
                )
            }

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (!updateExisting) {
                ChoiceField(
                    label = "Project",
                    options = projects.map { it.id } + NEW,
                    selected = projectId,
                    onSelect = { projectId = it },
                    optionLabel = { id -> if (id == NEW) "+ New project" else projects.firstOrNull { it.id == id }?.name ?: "" },
                )
                if (projectId == NEW) {
                    OutlinedTextField(
                        value = newProjectName,
                        onValueChange = { newProjectName = it },
                        label = { Text("New project name") },
                        placeholder = { Text("e.g. Smith Residence") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Text("Run", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    project?.runs?.forEach { run ->
                        FilterChip(selected = run.id == runId, onClick = { runId = run.id }, label = { Text(run.name) })
                    }
                    FilterChip(selected = runId == NEW, onClick = { runId = NEW }, label = { Text("+ New run") })
                }
                if (runId == NEW) {
                    OutlinedTextField(
                        value = newRunName,
                        onValueChange = { newRunName = it },
                        label = { Text("New run name") },
                        placeholder = { Text(if (projectId == NEW) "General" else "e.g. Panel A → RTU-3") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (conduit != null && !updateExisting) {
                val label = listOfNotNull(conduit.tradeSize?.display, conduit.conduitType.displayName).joinToString(" ")
                ToggleRow(
                    title = "Use $label as this run's conduit",
                    checked = useConduit,
                    onCheckedChange = { useConduit = it },
                    detail = project?.run(runId)?.raceway?.let { r ->
                        "It now has ${listOfNotNull(r.tradeSize?.display, r.conduitType.displayName).joinToString(" ")}. Length, temperature and other settings are kept."
                    } ?: "Then assign circuits to the run in Panels to size their wire.",
                )
            }

            if (bends.isNotEmpty() && !updateExisting) {
                ToggleRow(
                    title = "Add to run path",
                    checked = addBends,
                    onCheckedChange = { addBends = it },
                    detail = "Adds its ${bends.size} bend${if (bends.size == 1) "" else "s"} (${bends.joinToString(", ") { formatDecimal(it.angleDegrees, 1) + "°" }}) to the run's route for the 360° check and the pull planner.",
                )
            }
            if (route != null && !updateExisting) {
                ToggleRow(
                    title = "Use this path for the run",
                    checked = useRoute,
                    onCheckedChange = { useRoute = it },
                    detail = if (project?.run(runId)?.path.isNullOrEmpty()) {
                        "Saves the route and pull settings with the run, so Panels and other tools see them."
                    } else {
                        "Replaces the run's current path and pull settings."
                    },
                )
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note (optional)") },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    onClick = {
                        val now = services.clock()
                        val itemTitle = title.trim().ifEmpty { draft.title }
                        if (updateExisting && editing != null && editingProject != null) {
                            services.projects.edit(editingProject.id) { p, t ->
                                val updated = p.updateItem(editing.id, t) {
                                    it.copy(title = itemTitle, note = note.trim(), inputs = draft.inputs, results = draft.results, summary = draft.summary)
                                }
                                // Bends this item put in its run's path follow the edit.
                                val owner = updated.item(editing.id)?.first
                                if (owner != null && bends.isNotEmpty() && RunPath.hasSource(owner.path, editing.id)) {
                                    updated.addBendsToRunPath(owner.id, editing.id, bends, t)
                                } else {
                                    updated
                                }
                            }
                            onSaved("Updated “$itemTitle”")
                            return@Button
                        }
                        val targetProject = if (projectId == NEW) {
                            services.projects.create(newProjectName.ifBlank { "Untitled project" })
                        } else {
                            project ?: return@Button
                        }
                        val targetRun = when {
                            runId != NEW -> targetProject.run(runId)
                            projectId == NEW && newRunName.isBlank() -> targetProject.runs.firstOrNull()
                            else -> services.projects.addRun(targetProject.id, newRunName.ifBlank { "Run ${targetProject.runs.size + 1}" })
                        } ?: return@Button
                        val item = ProjectItem(
                            toolId = draft.toolId,
                            title = itemTitle,
                            note = note.trim(),
                            createdAt = now,
                            inputs = draft.inputs,
                            results = draft.results,
                            summary = draft.summary,
                        )
                        services.projects.addItem(targetProject.id, targetRun.id, item)
                        if (addBends && bends.isNotEmpty()) {
                            services.projects.edit(targetProject.id) { p, t -> p.addBendsToRunPath(targetRun.id, item.id, bends, t) }
                        }
                        if (useRoute && route != null) {
                            services.projects.edit(targetProject.id) { p, t ->
                                p.updateRun(targetRun.id, t) { r -> r.copy(pull = route.pull ?: r.pull, raceway = route.raceway ?: r.raceway) }
                                    .withRunPath(targetRun.id, route.path, t)
                            }
                        }
                        if (useConduit && conduit != null) {
                            services.projects.edit(targetProject.id) { p, t ->
                                p.updateRun(targetRun.id, t) { r ->
                                    r.copy(raceway = (r.raceway ?: Raceway()).copy(conduitType = conduit.conduitType, tradeSize = conduit.tradeSize))
                                }.syncRunLength(targetRun.id, t) // bend radii follow the new size
                            }
                        }
                        services.settings.setActiveTarget(targetProject.id, targetRun.id)
                        onSaved("Saved to ${targetProject.name} › ${targetRun.name}")
                    },
                ) { Text(if (updateExisting) "Update" else "Save") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
