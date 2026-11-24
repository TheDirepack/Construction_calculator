package com.thedirepack.constructioncalc.feature.pullplanner

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thedirepack.constructioncalc.core.data.ProjectSharing
import com.thedirepack.constructioncalc.core.electrical.FrictionPreset
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.measure.UnitSystem
import com.thedirepack.constructioncalc.core.model.PathSegment
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.ProjectCodec
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
import com.thedirepack.constructioncalc.feature.pullplanner.domain.PullPdfReport
import com.thedirepack.constructioncalc.feature.pullplanner.domain.PullReport
import com.thedirepack.constructioncalc.feature.pullplanner.domain.PullReportModel
import com.thedirepack.constructioncalc.feature.pullplanner.domain.RunEdits
import com.thedirepack.constructioncalc.feature.pullplanner.domain.STANDALONE_RACEWAY
import com.thedirepack.constructioncalc.feature.pullplanner.domain.NamedCables
import com.thedirepack.constructioncalc.feature.pullplanner.domain.PullRefs
import com.thedirepack.constructioncalc.core.ui.components.NamedCableActions
import com.thedirepack.constructioncalc.core.ui.tool.ToolIds
import com.thedirepack.constructioncalc.core.model.ResultValue
import com.thedirepack.constructioncalc.core.math.Rational
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private data class RunChoice(val projectId: String, val runId: String, val label: String)
private data class SegmentEdit(val index: Int, val kind: SegmentKind)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PullPlannerScreen(host: ToolHost) {
    val vm: PullPlannerViewModel = toolViewModel(PullPlannerTool.ID, host)
    val services = LocalAppServices.current
    val repository = services.projects
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
    val analysis = remember(project, linkedRun, run, settings.unitSystem) {
        val design = if (linkedRun != null && project != null) ProjectAnalysis.analyze(project, settings.unitSystem).runs[linkedRun.id] else null
        PullAnalysis.analyze(run, design, settings.unitSystem)
    }
    val lengthText: (Quantity) -> String = { settings.format(it) }
    val results = remember(analysis, settings) { PullReport.results(analysis, settings.unitSystem, lengthText) }
    val summary = remember(analysis, settings.unitSystem) { PullReport.summary(analysis, settings.unitSystem) }
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
    var pdfBusy by remember { mutableStateOf(false) }
    var pendingPdfFile by rememberSaveable { mutableStateOf<String?>(null) }
    val savePdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        val stagedName = pendingPdfFile
        pendingPdfFile = null
        val stagedFile = stagedName?.let { File(context.cacheDir, it) } ?: return@rememberLauncherForActivityResult
        pdfBusy = true
        scope.launch {
            try {
                if (uri != null) {
                    withContext(Dispatchers.IO) {
                        stagedFile.inputStream().use { input ->
                            val output = context.contentResolver.openOutputStream(uri, "wt")
                                ?: throw IOException("Could not open the export destination.")
                            output.use { input.copyTo(it) }
                        }
                    }
                    host.showMessage("PDF report saved.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                host.showMessage(error.message ?: "Could not save the PDF report.")
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { stagedFile.delete() }
                pdfBusy = false
            }
        }
    }

    fun reportDate(): String = DateTimeFormatter.ofPattern("MMM d, yyyy")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(services.clock()))

    fun buildPdfReport(): PullPdfReport = PullReportModel.build(
        result = analysis,
        projectName = project?.name,
        companyHeader = settings.company.headerLines(),
        date = reportDate(),
        units = settings.unitSystem,
        lengthText = lengthText,
    )

    fun pdfFileName(): String {
        val base = listOfNotNull(
            project?.name?.trim()?.takeIf { it.isNotEmpty() },
            run.name.trim().takeIf { it.isNotEmpty() },
        ).joinToString(" - ")
        val suffix = if (base.lowercase().endsWith("pull")) "report.pdf" else "pull report.pdf"
        return "${ProjectCodec.safeFileName(base, "Wire pull")} $suffix"
    }

    fun sharePdf() {
        if (pdfBusy) return
        pdfBusy = true
        val report = buildPdfReport()
        val resultSnapshot = analysis
        val fileName = pdfFileName()
        val subject = "Wire pull: ${run.name}"
        val body = summary
        scope.launch {
            try {
                val intent = withContext(Dispatchers.IO) {
                    ProjectSharing.shareFileIntent(
                        context = context,
                        fileName = fileName,
                        mimeType = "application/pdf",
                        subject = subject,
                        body = body,
                    ) { out -> PullReportPdf.write(out, report, resultSnapshot) }
                }
                try {
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    host.showMessage("No app available to share with")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                host.showMessage(error.message ?: "Couldn't create the PDF report.")
            } finally {
                pdfBusy = false
            }
        }
    }

    fun savePdf() {
        if (pendingPdfFile != null || pdfBusy) return
        pdfBusy = true
        val report = buildPdfReport()
        val resultSnapshot = analysis
        val suggestedName = pdfFileName()
        val staged = File(context.cacheDir, "pull-report-${UUID.randomUUID()}.pdf")
        scope.launch {
            var launched = false
            try {
                withContext(Dispatchers.IO) {
                    staged.outputStream().use { out ->
                        PullReportPdf.write(out, report, resultSnapshot)
                    }
                }
                pendingPdfFile = staged.name
                savePdfLauncher.launch(suggestedName)
                launched = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                pendingPdfFile = null
                host.showMessage(error.message ?: "Couldn't create the PDF report.")
            } finally {
                if (!launched) {
                    withContext(NonCancellable + Dispatchers.IO) { staged.delete() }
                }
                pdfBusy = false
            }
        }
    }

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
                    SegmentResults(analysis, index, settings.unitSystem)
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
                    Text(NamedCables.describe(cable))
                    if (canEdit) {
                        FlowRow {
                            TextButton(onClick = { cableIndex = index }) { Text("Edit cable") }
                            TextButton(onClick = {
                                update { it.copy(pull = setup.copy(cables = setup.cables.filterIndexed { i, _ -> i != index })) }
                            }) { Text("Remove cable") }
                        }
                    }
                }
                if (canEdit && setup.cables.size < 200) {
                    TextButton(onClick = { cableIndex = setup.cables.size }) { Text("Add cable") }
                    NamedCableActions(
                        onPick = { named -> update { it.copy(pull = NamedCables.apply(it.pull ?: setup, named)) } },
                        pickLabel = "Add a named cable…",
                        current = NamedCables.fromSetup(setup),
                    )
                }
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
                notices = analysis.notes.map { Notice(it.text, if (it.isWarning) NoticeLevel.WARNING else NoticeLevel.INFO, PullRefs.forNote(it.text)) },
                emptyText = "Add the path and cables to calculate the pull.",
                onOpenReference = { host.openReference(it) },
            )
            val needed = analysis.cableLengthIn
            if (needed != null) {
                TextButton(onClick = {
                    // Offer the cable needed and the cable OD right away, then open Reel Length. A project
                    // run is opened as the target, so Reel Length fills both in from the same run.
                    val od = analysis.cableOdIn?.let { d ->
                        val q = Quantity.inches(Rational.of(d))
                        ResultValue.of(PullRefs.KEY_CABLE_OD, "Cable outside diameter", q, settings.format(q))
                    }
                    host.publish(results + listOfNotNull(od), summary, immediate = true)
                    if (linkedRun != null && project != null) {
                        host.openTool(ToolIds.REEL_LENGTH, target = ToolTarget.OfRun(project.id, linkedRun.id))
                    } else {
                        host.openTool(ToolIds.REEL_LENGTH)
                        host.showMessage("Pick the cable needed and its diameter from the Wire Pull Planner's values.")
                    }
                }) { Text("Check on reel") }
            }
            if (results.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { host.shareText("Wire pull: ${run.name}", summary) }) {
                        Text("Share pull report")
                    }
                    TextButton(enabled = !pdfBusy, onClick = { sharePdf() }) {
                        Text("Share PDF report")
                    }
                    TextButton(enabled = !pdfBusy, onClick = { savePdf() }) {
                        Text("Save PDF report")
                    }
                }
            }
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
private fun SegmentResults(result: PullResult, index: Int, units: UnitSystem) {
    listOfNotNull(result.forward, result.reverse).forEach { pass ->
        val step = PullReport.tensionAt(result, pass.direction, index) ?: return@forEach
        val pressure = step.pressure?.let { " · ${PullAnalysis.pressure(it, units)}" }.orEmpty()
        Text(
            "${pass.direction.label}: ${PullReport.lb(step.tensionOut, units)}$pressure",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
