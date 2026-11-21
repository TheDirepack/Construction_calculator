package com.thedirepack.constructioncalc.feature.panels

import com.thedirepack.constructioncalc.core.electrical.RacewayBends
import com.thedirepack.constructioncalc.core.ui.show
import com.thedirepack.constructioncalc.core.measure.UnitSystem
import com.thedirepack.constructioncalc.core.measure.Measure
import com.thedirepack.constructioncalc.core.model.withRaceway
import com.thedirepack.constructioncalc.core.model.ToolTarget
import com.thedirepack.constructioncalc.core.ui.tool.ToolIds
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.model.CircuitKind
import com.thedirepack.constructioncalc.core.model.Raceway
import com.thedirepack.constructioncalc.core.model.Run
import com.thedirepack.constructioncalc.core.model.design.FillStatus
import com.thedirepack.constructioncalc.core.model.design.RunDesign
import com.thedirepack.constructioncalc.core.model.design.PullAnalysis
import com.thedirepack.constructioncalc.core.model.design.PullText
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.components.ConfirmDialog
import com.thedirepack.constructioncalc.core.ui.components.RacewayEditorSheet
import com.thedirepack.constructioncalc.core.ui.components.TextInputDialog
import com.thedirepack.constructioncalc.core.ui.components.racewaySummary
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.feature.panels.domain.CircuitAt
import com.thedirepack.constructioncalc.core.model.design.Wire
import com.thedirepack.constructioncalc.feature.panels.domain.allCircuits
import com.thedirepack.constructioncalc.feature.panels.domain.assignRun
import com.thedirepack.constructioncalc.feature.panels.domain.circuitLabel
import com.thedirepack.constructioncalc.feature.panels.domain.deleteRunAndUnassign
import com.thedirepack.constructioncalc.feature.panels.domain.designRuns

private sealed interface RunPrompt {
    data object New : RunPrompt
    data class Rename(val run: Run) : RunPrompt
    data class Conduit(val run: Run) : RunPrompt
    data class Assign(val run: Run) : RunPrompt
    data class Delete(val run: Run) : RunPrompt
}

/** Conduit runs and what's in them: fill, conductor counts, and assigning circuits across panels. */
@Composable
internal fun RunsTab(ctx: PanelsContext, modifier: Modifier = Modifier) {
    var prompt by remember { mutableStateOf<RunPrompt?>(null) }
    val runs = remember(ctx.project) { ctx.project.designRuns() }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!ctx.readOnly) {
            item(key = "new") { Button(onClick = { prompt = RunPrompt.New }) { Text("New conduit run") } }
        }
        if (runs.isEmpty()) {
            item(key = "empty") {
                Text(
                    "No conduit runs yet. A run is a conduit that carries circuits (like a home run from a panel to a rooftop unit). " +
                        "Assign circuits from any panel to it and the app counts conductors, derates, checks fill and sizes the wire.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(runs, key = { it.id }) { run ->
            RunCard(
                ctx, run, ctx.design.runs[run.id],
                onRename = { prompt = RunPrompt.Rename(run) },
                onConduit = { prompt = RunPrompt.Conduit(run) },
                onAssign = { prompt = RunPrompt.Assign(run) },
                onDelete = { prompt = RunPrompt.Delete(run) },
            )
        }
    }

    when (val p = prompt) {
        null -> Unit
        RunPrompt.New -> TextInputDialog("New conduit run", "", "Create", { name ->
            val run = Run(name = name.ifBlank { "Conduit run" }, raceway = Raceway())
            ctx.edit { project, now -> project.addRun(run, now) }
            prompt = RunPrompt.Conduit(run)
        }, { prompt = null }, label = "e.g. LP-1 to RTU-3")
        is RunPrompt.Rename -> TextInputDialog("Rename run", p.run.name, "Rename", { name ->
            ctx.edit { project, now -> project.updateRun(p.run.id, now) { it.copy(name = name) } }
            prompt = null
        }, { prompt = null })
        is RunPrompt.Conduit -> RacewayEditorSheet(
            title = "Conduit for ${p.run.name}",
            initial = ctx.project.run(p.run.id)?.raceway,
            onSave = { raceway ->
                ctx.edit { project, now -> project.withRaceway(p.run.id, raceway, now) }
                prompt = null
            },
            onDismiss = { prompt = null },
            routed = ctx.project.run(p.run.id)?.path?.isNotEmpty() == true,
            onRemove = ctx.project.run(p.run.id)?.raceway?.let {
                {
                    ctx.edit { project, now -> project.updateRun(p.run.id, now) { it.copy(raceway = null) } }
                    prompt = null
                }
            },
        )
        is RunPrompt.Assign -> AssignCircuitsSheet(ctx, p.run, onDismiss = { prompt = null })
        is RunPrompt.Delete -> ConfirmDialog(
            "Delete ${p.run.name}?",
            "This removes the run from every circuit that passes through it" +
                if (p.run.items.isNotEmpty()) " and deletes its ${plural(p.run.items.size, "saved item")}." else ".",
            "Delete",
            onConfirm = {
                ctx.edit { project, now -> project.deleteRunAndUnassign(p.run.id, now) }
                prompt = null
            },
            onDismiss = { prompt = null },
        )
    }
}

@Composable
private fun RunCard(
    ctx: PanelsContext,
    run: Run,
    rd: RunDesign?,
    onRename: () -> Unit,
    onConduit: () -> Unit,
    onAssign: () -> Unit,
    onDelete: () -> Unit,
) {
    val settings = rememberAppSettings()
    var menu by remember { mutableStateOf(false) }
    val members = rd?.circuits.orEmpty()
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 12.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(run.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        run.raceway?.let { racewaySummary(it, settings, members.size, autoSize = rd?.conduit?.tradeSize) } ?: "No conduit set",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "Options for ${run.name}" }) {
                        Icon(painterResource(R.drawable.ic_more_vert), contentDescription = null)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (run.raceway != null) {
                            val target = ToolTarget.OfRun(ctx.project.id, run.id)
                            DropdownMenuItem(text = { Text("Check fill in Conduit Fill") }, onClick = {
                                menu = false
                                ctx.host.openTool(ToolIds.CONDUIT_FILL, target = target)
                            })
                            DropdownMenuItem(text = { Text("Bend this conduit") }, onClick = {
                                menu = false
                                ctx.host.openTool(ToolIds.CONDUIT_BENDING, target = target)
                            })
                        }
                        if (!ctx.readOnly) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                            DropdownMenuItem(text = { Text("Delete run") }, onClick = { menu = false; onDelete() })
                        }
                    }
                }
            }
            Column(Modifier.padding(end = 12.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (members.isEmpty()) {
                    Text("No circuits assigned.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    members.forEach { (panel, c) ->
                        Text("• " + circuitLabel(panel, c), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (rd != null && members.isNotEmpty()) {
                    val nipple = run.raceway?.nipple == true
                    Text(
                        "${rd.currentCarrying} current-carrying conductors" + if (nipple) " (nipple: no derating)" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val area = "${rd.totalConductors} conductors, ${settings.show(Measure.SMALL_AREA, rd.totalAreaSqIn, 3)}"
                    Text(
                        if (rd.fillStatus == FillStatus.INCOMPLETE) "Fill incomplete. Known conductors: $area" else "$area total",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val fill = rd.fillPercent
                    if (fill != null && rd.conduit != null) {
                        val over = fill > rd.fillLimitPercent + 1e-9
                        Text(
                            "Fill ${Wire.percent(fill)} of ${rd.fillLimitPercent}% limit in ${rd.conduit!!.displayName}" + if (over) ": over the limit" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    rd.smallestFitting?.let { Text("Smallest that fits: ${it.displayName}", style = MaterialTheme.typography.bodySmall) }
                    if (run.path.isEmpty()) {
                        rd.bendDegrees?.let { Text("Saved bends: ${Wire.number(it, 1)}° of ${Wire.number(RacewayBends.MAX_DEGREES_BETWEEN_PULL_POINTS, 0)}° between pull points", style = MaterialTheme.typography.bodySmall) }
                    }
                    rd.notes.forEach { note ->
                        if (note.isWarning) {
                            WarningText(note.text)
                        } else {
                            Text(note.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (run.raceway != null) PullSummary(run, rd, settings.unitSystem)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!ctx.readOnly) {
                    TextButton(onClick = onConduit) { Text(if (run.raceway == null) "Set conduit…" else "Edit conduit…") }
                    TextButton(onClick = onAssign) { Text("Circuits…") }
                }
                if (run.raceway != null) {
                    TextButton(onClick = { ctx.host.openTool(ToolIds.PULL_PLANNER, target = ToolTarget.OfRun(ctx.project.id, run.id)) }) { Text("Plan pull") }
                }
            }
        }
    }
}

/** Pick, from every panel, which circuits pass through [run]. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AssignCircuitsSheet(ctx: PanelsContext, run: Run, onDismiss: () -> Unit) {
    val all = remember(ctx.project) { ctx.project.allCircuits().filter { it.circuit.kind != CircuitKind.SPACE } }
    var selected by remember { mutableStateOf(all.filter { run.id in it.circuit.runIds }.map { it.circuit.id }.toSet()) }
    var groupFilter by remember { mutableStateOf<String?>(null) }
    val visible = all.filter { groupFilter == null || groupFilter in it.circuit.groupIds }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp)) {
            Text("Circuits in ${run.name}", style = MaterialTheme.typography.titleLarge)
            Text(
                "${selected.size} selected. Circuits from any panel can share a conduit.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ctx.project.groups.isNotEmpty()) {
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = groupFilter == null, onClick = { groupFilter = null }, label = { Text("All") })
                    ctx.project.groups.forEach { g ->
                        FilterChip(
                            selected = groupFilter == g.id,
                            onClick = { groupFilter = if (groupFilter == g.id) null else g.id },
                            label = { Text(g.name) },
                            leadingIcon = { GroupTag(g) },
                        )
                    }
                }
                if (groupFilter != null) {
                    Row {
                        TextButton(onClick = { selected = selected + visible.map { it.circuit.id } }) { Text("Select these") }
                        TextButton(onClick = { selected = selected - visible.map { it.circuit.id }.toSet() }) { Text("Clear these") }
                    }
                }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                if (all.isEmpty()) Text("Add circuits to a panel first.", Modifier.padding(vertical = 16.dp))
                visible.groupBy { it.panel.id }.forEach { (_, circuits) ->
                    val panel = circuits.first().panel
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(panel.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        val ids = circuits.map { it.circuit.id }
                        val allOn = ids.all { it in selected }
                        TextButton(onClick = { selected = if (allOn) selected - ids.toSet() else selected + ids }) { Text(if (allOn) "Clear" else "Select all") }
                    }
                    circuits.forEach { at: CircuitAt ->
                        val on = at.circuit.id in selected
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 0.dp)
                                .toggleable(value = on, role = Role.Checkbox, onValueChange = { selected = if (it) selected + at.circuit.id else selected - at.circuit.id })
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = on, onCheckedChange = null, modifier = Modifier.padding(end = 12.dp))
                            Column {
                                Text(circuitLabel(at.panel, at.circuit).removePrefix("${at.panel.name} "), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    listOfNotNull(
                                        "${at.circuit.breakerAmps ?: "—"} A / ${at.circuit.poles}P",
                                        at.circuit.runIds.filter { it != run.id }.mapNotNull(ctx.project::run).takeIf { it.isNotEmpty() }?.joinToString(prefix = "also in ") { it.name },
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = {
                    ctx.edit { project, now -> project.assignRun(run.id, selected, now) }
                    onDismiss()
                }, modifier = Modifier.padding(start = 8.dp)) { Text("Save") }
            }
        }
    }
}

/** The run's path and pull: max tension against its limit, worst sidewall pressure, degrees per section and warnings. */
@Composable
private fun PullSummary(run: Run, rd: RunDesign?, units: UnitSystem) {
    val result = remember(run, rd, units) { PullAnalysis.analyze(run, rd, units) }
    Column(Modifier.padding(end = 12.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (run.path.isEmpty()) {
            Text("No path yet. Plan the pull to add straights, bends and pull points.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        val sections = result.sectionDegrees
        Text(
            "Path: ${plural(run.path.size, "segment")} · bends " + sections.joinToString(" | ") { "${Wire.number(it, 1)}°" } +
                if (sections.size > 1) " (per pull section, ${Wire.number(RacewayBends.MAX_DEGREES_BETWEEN_PULL_POINTS, 0)}° max)" else " of ${Wire.number(RacewayBends.MAX_DEGREES_BETWEEN_PULL_POINTS, 0)}° max",
            style = MaterialTheme.typography.bodySmall,
        )
        val summary = PullText.summary(result, units)
        if (summary != null) {
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = if (result.overTension || result.overPressure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        } else {
            Text("Pull not calculated yet: open Plan pull for what's missing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val shown = rd?.notes.orEmpty().filter { it.isWarning }.map { it.text }.toSet()
        PullText.warnings(result).filterNot { it in shown }.forEach { WarningText(it) }
    }
}
