package com.thedirepack.constructioncalc.feature.pullplanner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thedirepack.constructioncalc.core.electrical.FrictionPreset
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.PathSegment
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.PullCable
import com.thedirepack.constructioncalc.core.model.Run
import com.thedirepack.constructioncalc.core.model.RunPath
import com.thedirepack.constructioncalc.core.model.ToolInputs
import com.thedirepack.constructioncalc.core.model.ToolTarget
import com.thedirepack.constructioncalc.core.model.design.ProjectAnalysis
import com.thedirepack.constructioncalc.core.model.design.PullAnalysis
import com.thedirepack.constructioncalc.core.model.design.PullResult
import com.thedirepack.constructioncalc.core.model.stored
import com.thedirepack.constructioncalc.core.ui.components.ChoiceField
import com.thedirepack.constructioncalc.core.ui.components.FormSection
import com.thedirepack.constructioncalc.core.ui.components.MeasureField
import com.thedirepack.constructioncalc.core.ui.components.Notice
import com.thedirepack.constructioncalc.core.ui.components.NoticeLevel
import com.thedirepack.constructioncalc.core.ui.components.RacewayEditorSheet
import com.thedirepack.constructioncalc.core.ui.components.ResultCard
import com.thedirepack.constructioncalc.core.ui.components.ToolScaffold
import com.thedirepack.constructioncalc.core.ui.components.ToggleRow
import com.thedirepack.constructioncalc.core.ui.components.racewaySummary
import com.thedirepack.constructioncalc.core.ui.format
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.core.ui.tool.ItemDraft
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices
import com.thedirepack.constructioncalc.core.ui.tool.LocalTools
import com.thedirepack.constructioncalc.core.ui.tool.find
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.model.addBendsToRunPath
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.painterResource
import com.thedirepack.constructioncalc.core.ui.tool.RunRoute
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost
import com.thedirepack.constructioncalc.core.ui.tool.toolViewModel
import com.thedirepack.constructioncalc.feature.pullplanner.domain.PullReport
import com.thedirepack.constructioncalc.feature.pullplanner.domain.RunEdits
import com.thedirepack.constructioncalc.feature.pullplanner.domain.STANDALONE_RACEWAY

private data class RunChoice(val projectId: String, val runId: String, val label: String)
private data class SegmentEdit(val index: Int, val kind: SegmentKind)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PullPlannerScreen(host: ToolHost) {
    val vm: PullPlannerViewModel = toolViewModel(PullPlannerTool.ID, host)
    val repository = LocalAppServices.current.projects
    val projects by repository.projects.collectAsStateWithLifecycle()
    val loaded by repository.loaded.collectAsStateWithLifecycle()
    val settings = rememberAppSettings()
    val defaults = rememberPullDefaults()
    val inputs = vm.inputs
    val project = projects.firstOrNull { it.id == inputs.projectId }
    val linkedRun = inputs.runId?.let { project?.run(it) }
    val choices = remember(projects) {
        projects.flatMap { job -> job.runs.map { RunChoice(job.id, it.id, "${job.name} › ${it.name}") } }
    }
    val selected = choices.firstOrNull { it.projectId == inputs.projectId && it.runId == inputs.runId }
    val target = host.target as? ToolTarget.OfRun
    LaunchedEffect(loaded, target) {
        if (!loaded || host.openedItem != null || vm.autoLinked) return@LaunchedEffect
        vm.autoLinked = true
        val start = choices.firstOrNull {
            it.projectId == (target?.projectId ?: settings.activeProjectId) &&
                it.runId == (target?.runId ?: settings.activeRunId)
        }
        if (start != null) vm.load(PullPlannerInputs(projectId = start.projectId, runId = start.runId))
    }

    val setupDefaults = defaults.setup(fromCircuits = linkedRun != null)
    val run = remember(linkedRun, inputs, setupDefaults) {
        val current = linkedRun ?: Run(name = "Standalone pull", raceway = inputs.raceway, path = inputs.path, pull = inputs.pull)
        current.copy(pull = current.pull ?: setupDefaults)
    }
    val setup = requireNotNull(run.pull)
    val analysis = remember(project, linkedRun, run) {
        val design = if (linkedRun != null && project != null) ProjectAnalysis.analyze(project).runs[linkedRun.id] else null
        PullAnalysis.analyze(run, design)
    }
    val lengthText: (Quantity) -> String = { settings.format(it) }
    val results = remember(analysis, settings) { PullReport.results(analysis, lengthText) }
    val summary = remember(analysis) { PullReport.summary(analysis) }
    val canEdit = linkedRun == null || project?.archived != true
    val tools = LocalTools.current
    val bendSources = remember(linkedRun, tools) {
        linkedRun?.items.orEmpty().mapNotNull { item ->
            tools.find(item.toolId)?.pathBends(item)?.takeIf { it.isNotEmpty() }?.let { item to it }
        }
    }
    var segmentEdit by remember(selected) { mutableStateOf<SegmentEdit?>(null) }
    var cableIndex by remember(selected) { mutableStateOf<Int?>(null) }
    var editingRaceway by remember(selected) { mutableStateOf(false) }
    var editingRatings by remember(selected) { mutableStateOf(false) }

    fun update(change: (Run) -> Run) {
        if (!canEdit) return
        if (linkedRun != null && project != null) {
            repository.edit(project.id) { current, now -> RunEdits.apply(current, linkedRun.id, now, setupDefaults, change) }
        } else {
            val standalone = Project(name = run.name, createdAt = 0, runs = listOf(run))
            val changed = RunEdits.apply(standalone, run.id, 0, setupDefaults, change).runs.single()
            vm.load(PullPlannerInputs(raceway = changed.raceway ?: STANDALONE_RACEWAY, path = changed.path, pull = changed.pull))
        }
    }

    LaunchedEffect(results, summary) {
        if (results.isNotEmpty()) host.publish(results, summary)
    }
    ToolScaffold(
        title = PullPlannerTool.title,
        host = host,
        onSave = if (results.isEmpty()) null else ({
            val snapshot = inputs.copy(raceway = run.raceway ?: STANDALONE_RACEWAY, path = run.path, pull = setup)
            host.saveToProject(ItemDraft(
                toolId = PullPlannerTool.ID,
                title = "Wire pull: ${run.name}",
                inputs = ToolInputs.encode(snapshot),
                results = results,
                summary = summary,
                route = if (linkedRun == null) RunRoute(run.path, setup, run.raceway) else null,
            ))
        }),
        actions = {
            IconButton(onClick = host::openSettings) {
                Icon(painterResource(R.drawable.ic_tune), contentDescription = "Pull planner settings")
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (choices.isNotEmpty()) {
                ChoiceField("Run", listOf(null) + choices, selected, onSelect = { choice ->
                    vm.load(if (choice == null) PullPlannerInputs() else PullPlannerInputs(projectId = choice.projectId, runId = choice.runId))
                }, optionLabel = { it?.label ?: "Standalone pull" })
            }
            if (inputs.linked && loaded && linkedRun == null) {
                Text("The original run is unavailable. The saved inputs are available as a standalone pull.")
            }
            if (!canEdit) Text("This run is in an archived project.")
            FormSection("Raceway and path") {
                Text(run.raceway?.let { racewaySummary(it, settings, autoSize = analysis.conduit?.tradeSize) } ?: "Choose a raceway.")
                if (canEdit) TextButton(onClick = { editingRaceway = true }) { Text("Edit raceway") }
                PathDiagram(
                    run.path, run.raceway?.conduitType, analysis.conduit?.tradeSize,
                    description = "Side view of the route: ${run.path.size} segments, " +
                        "${PullReport.num(RunPath.totalDegrees(run.path))}° of bends, " +
                        "${run.path.count { it is PathSegment.PullPoint }} pull points",
                )
                run.path.forEachIndexed { index, segment ->
                    Text("${index + 1}. ${PullReport.describe(segment, run.raceway?.conduitType, analysis.conduit?.tradeSize, lengthText)}")
                    SegmentResults(analysis, index)
                    if (canEdit) {
                        FlowRow {
                            TextButton(onClick = { segmentEdit = SegmentEdit(index, SegmentKind.of(segment)) }) { Text("Edit") }
                            TextButton(enabled = index > 0, onClick = { update { it.copy(path = RunEdits.move(it.path, index, index - 1)) } }) { Text("Move up") }
                            TextButton(enabled = index < run.path.lastIndex, onClick = { update { it.copy(path = RunEdits.move(it.path, index, index + 1)) } }) { Text("Move down") }
                            TextButton(onClick = { update { it.copy(path = RunEdits.remove(it.path, index)) } }) { Text("Remove") }
                        }
                    }
                }
                // Bends saved from Conduit Bending in this run that aren't in the path yet (or need updating).
                if (canEdit && linkedRun != null && project != null && bendSources.isNotEmpty()) {
                    TextButton(onClick = {
                        repository.edit(project.id) { current, now ->
                            bendSources.fold(current) { acc, (item, bends) -> acc.addBendsToRunPath(linkedRun.id, item.id, bends, now) }
                        }
                        host.showMessage("Added bends from ${bendSources.size} Bending item${if (bendSources.size == 1) "" else "s"}")
                    }) { Text("Import bends from Bending (${bendSources.size})") }
                }
                if (canEdit && run.path.size < RunPath.MAX_SEGMENTS) {
                    FlowRow {
                        SegmentKind.entries.forEach { kind ->
                            TextButton(onClick = { segmentEdit = SegmentEdit(run.path.size, kind) }) { Text("Add ${kind.label.lowercase()}") }
                        }
                    }
                }
            }
            FormSection("Cables") {
                if (canEdit && linkedRun != null) {
                    ToggleRow("Include assigned circuit conductors", setup.cablesFromCircuits, {
                        update { run -> run.copy(pull = setup.copy(cablesFromCircuits = it)) }
                    })
                }
                analysis.conductors.filter { it.fromCircuits }.forEach { Text(it.text) }
                setup.cables.forEachIndexed { index, cable ->
                    Text("${cable.count} × ${cable.size.label} ${cable.insulation.code} ${cable.material.shortLabel}")
                    if (canEdit) {
                        FlowRow {
                            TextButton(onClick = { cableIndex = index }) { Text("Edit cable") }
                            TextButton(onClick = {
                                update { it.copy(pull = setup.copy(cables = setup.cables.filterIndexed { i, _ -> i != index })) }
                            }) { Text("Remove cable") }
                        }
                    }
                }
                if (canEdit && setup.cables.size < 200) TextButton(onClick = { cableIndex = setup.cables.size }) { Text("Add cable") }
            }
            if (canEdit) {
                FormSection("Pull settings") {
                    ChoiceField("Friction preset", FrictionPreset.entries, setup.friction, onSelect = { preset ->
                        update { it.copy(pull = setup.copy(friction = preset, frictionOverride = null)) }
                    }, optionLabel = { it.label })
                    MeasureField("Slack per end", setup.slackPerEnd?.toQuantity(), { value ->
                        if (value != null && value.value.signum < 0) host.showMessage("Slack must be zero or more.")
                        else update { it.copy(pull = setup.copy(slackPerEnd = value?.stored())) }
                    })
                    TextButton(onClick = { editingRatings = true }) { Text("Edit tensions and limits") }
                }
            }
            ResultCard(
                results,
                notices = analysis.notes.map { Notice(it.text, if (it.isWarning) NoticeLevel.WARNING else NoticeLevel.INFO) },
                emptyText = "Add the path and cables to calculate the pull.",
            )
            if (results.isNotEmpty()) TextButton(onClick = { host.shareText("Wire pull: ${run.name}", summary) }) { Text("Share pull report") }
        }
    }

    if (editingRaceway) RacewayEditorSheet("Raceway", run.raceway, onSave = { raceway ->
        update { it.copy(raceway = raceway) }
        editingRaceway = false
    }, onDismiss = { editingRaceway = false }, routed = run.path.isNotEmpty())
    segmentEdit?.let { editor ->
        PullSegmentDialog(editor.kind, run.path.getOrNull(editor.index), onSave = { segment ->
            update { it.copy(path = RunEdits.replace(it.path, editor.index, segment)) }
            segmentEdit = null
        }, onDismiss = { segmentEdit = null })
    }
    cableIndex?.let { index ->
        PullCableDialog(setup.cables.getOrNull(index) ?: PullCable(), onSave = { cable ->
            val cables = setup.cables.toMutableList()
            if (index in cables.indices) cables[index] = cable else cables.add(cable)
            update { it.copy(pull = setup.copy(cables = cables)) }
            cableIndex = null
        }, onDismiss = { cableIndex = null })
    }
    if (editingRatings) PullRatingsDialog(setup, RunPath.sections(run.path).size, onSave = { rated ->
        update { it.copy(pull = rated) }
        editingRatings = false
    }, onDismiss = { editingRatings = false })
}

@Composable
private fun SegmentResults(result: PullResult, index: Int) {
    listOfNotNull(result.forward, result.reverse).forEach { pass ->
        val step = PullReport.tensionAt(result, pass.direction, index) ?: return@forEach
        val pressure = step.pressure?.let { " · ${PullReport.num(it)} lb/ft" }.orEmpty()
        Text(
            "${pass.direction.label}: ${PullReport.lb(step.tensionOut)}$pressure",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
