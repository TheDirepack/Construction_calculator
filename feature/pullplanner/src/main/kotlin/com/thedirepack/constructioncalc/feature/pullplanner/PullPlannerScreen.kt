package com.thedirepack.constructioncalc.feature.pullplanner

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.data.ProjectSharing
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.FrictionPreset
import com.thedirepack.constructioncalc.core.electrical.NamedCable
import com.thedirepack.constructioncalc.core.electrical.RacewayBends
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Decimals
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.measure.UnitSystem
import com.thedirepack.constructioncalc.core.model.PathSegment
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.ProjectCodec
import com.thedirepack.constructioncalc.core.model.PullCable
import com.thedirepack.constructioncalc.core.model.PullSetup
import com.thedirepack.constructioncalc.core.model.Raceway
import com.thedirepack.constructioncalc.core.model.ResultValue
import com.thedirepack.constructioncalc.core.model.Run
import com.thedirepack.constructioncalc.core.model.RunPath
import com.thedirepack.constructioncalc.core.model.ToolInputs
import com.thedirepack.constructioncalc.core.model.ToolTarget
import com.thedirepack.constructioncalc.core.model.addBendsToRunPath
import com.thedirepack.constructioncalc.core.model.design.ProjectAnalysis
import com.thedirepack.constructioncalc.core.model.design.PullAnalysis
import com.thedirepack.constructioncalc.core.model.design.PullDirection
import com.thedirepack.constructioncalc.core.model.design.PullResult
import com.thedirepack.constructioncalc.core.model.stored
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.components.AdvancedSection
import com.thedirepack.constructioncalc.core.ui.components.ChoiceField
import com.thedirepack.constructioncalc.core.ui.components.ConfirmDialog
import com.thedirepack.constructioncalc.core.ui.components.FormSection
import com.thedirepack.constructioncalc.core.ui.components.MeasureField
import com.thedirepack.constructioncalc.core.ui.components.NamedCableActions
import com.thedirepack.constructioncalc.core.ui.components.Notice
import com.thedirepack.constructioncalc.core.ui.components.NoticeLevel
import com.thedirepack.constructioncalc.core.ui.components.NumberField
import com.thedirepack.constructioncalc.core.ui.components.RacewayEditorSheet
import com.thedirepack.constructioncalc.core.ui.components.ResultCard
import com.thedirepack.constructioncalc.core.ui.components.SegmentedChoice
import com.thedirepack.constructioncalc.core.ui.components.ToggleRow
import com.thedirepack.constructioncalc.core.ui.components.ToolScaffold
import com.thedirepack.constructioncalc.core.ui.components.racewaySummary
import com.thedirepack.constructioncalc.core.ui.format
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.core.ui.tool.ItemDraft
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices
import com.thedirepack.constructioncalc.core.ui.tool.LocalTools
import com.thedirepack.constructioncalc.core.ui.tool.RunRoute
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost
import com.thedirepack.constructioncalc.core.ui.tool.ToolIds
import com.thedirepack.constructioncalc.core.ui.tool.find
import com.thedirepack.constructioncalc.core.ui.tool.toolViewModel
import com.thedirepack.constructioncalc.feature.pullplanner.domain.NamedCables
import com.thedirepack.constructioncalc.feature.pullplanner.domain.PullCheckStatus
import com.thedirepack.constructioncalc.feature.pullplanner.domain.PullPdfReport
import com.thedirepack.constructioncalc.feature.pullplanner.domain.PullPlannerMode
import com.thedirepack.constructioncalc.feature.pullplanner.domain.PullRefs
import com.thedirepack.constructioncalc.feature.pullplanner.domain.PullReport
import com.thedirepack.constructioncalc.feature.pullplanner.domain.PullReportModel
import com.thedirepack.constructioncalc.feature.pullplanner.domain.PullVerdict
import com.thedirepack.constructioncalc.feature.pullplanner.domain.QuickPullEstimate
import com.thedirepack.constructioncalc.feature.pullplanner.domain.RunEdits
import com.thedirepack.constructioncalc.feature.pullplanner.domain.STANDALONE_RACEWAY
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
        if (start != null) {
            val startRun = projects.firstOrNull { it.id == start.projectId }?.run(start.runId)
            vm.load(
                PullPlannerInputs(
                    projectId = start.projectId,
                    runId = start.runId,
                    quickLength = startRun?.raceway?.length,
                ),
            )
        }
    }

    val setupDefaults = defaults.setup(fromCircuits = linkedRun != null)
    val run = remember(linkedRun, inputs, setupDefaults) {
        val current = linkedRun ?: Run(name = "Standalone pull", raceway = inputs.raceway, path = inputs.path, pull = inputs.pull)
        current.copy(pull = current.pull ?: setupDefaults)
    }
    val setup = requireNotNull(run.pull)
    val activeMode = inputs.resolvedMode(hasDetailedPath = run.path.isNotEmpty())

    val runDesign = remember(project, linkedRun, settings.unitSystem) {
        if (linkedRun != null && project != null) {
            ProjectAnalysis.analyze(project, settings.unitSystem).runs[linkedRun.id]
        } else null
    }

    val quickEvaluation = remember(
        inputs.quickLength,
        inputs.quickBends90,
        inputs.quickBends45,
        inputs.quickExtraBendDegrees,
        run.raceway,
        setup,
        runDesign,
        run.name,
        settings.unitSystem,
    ) {
        QuickPullEstimate.evaluate(
            totalLength = inputs.quickLength?.toQuantity(),
            bends90 = inputs.quickBends90,
            bends45 = inputs.quickBends45,
            extraBendDegrees = inputs.quickExtraBendDegrees,
            raceway = run.raceway,
            setup = setup,
            runDesign = runDesign,
            units = settings.unitSystem,
            runName = run.name,
        )
    }

    val detailedAnalysis = remember(run, runDesign, settings.unitSystem) {
        PullAnalysis.analyze(run, runDesign, settings.unitSystem)
    }

    val analysis = if (activeMode == PullPlannerMode.QUICK_CHECK) quickEvaluation.analysis else detailedAnalysis
    val effectivePath = if (activeMode == PullPlannerMode.QUICK_CHECK) quickEvaluation.segments else run.path

    var inspectedDirection by remember(selected, activeMode) { mutableStateOf<PullDirection?>(null) }
    LaunchedEffect(analysis.forward, analysis.reverse) {
        val current = inspectedDirection
        if (current != null && analysis.pass(current) == null) inspectedDirection = null
    }
    val verdict = remember(analysis, inspectedDirection) {
        PullVerdict.of(analysis, inspectedDirection)
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
    var confirmingReplaceRoute by remember(selected) { mutableStateOf(false) }

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
                    staged.parentFile?.mkdirs()
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

    var extraBendText by remember(selected) {
        mutableStateOf(inputs.quickExtraBendDegrees.takeIf { it > 0.0 }?.let { Decimals.trim(it, 1) }.orEmpty())
    }
    LaunchedEffect(inputs.quickExtraBendDegrees) {
        val parsed = Decimals.parse(extraBendText) ?: 0.0
        if (kotlin.math.abs(parsed - inputs.quickExtraBendDegrees) > 1e-6) {
            extraBendText = inputs.quickExtraBendDegrees.takeIf { it > 0.0 }?.let { Decimals.trim(it, 1) }.orEmpty()
        }
    }

    fun update(change: (Run) -> Run) {
        if (!canEdit) return
        if (linkedRun != null && project != null) {
            repository.edit(project.id) { current, now -> RunEdits.apply(current, linkedRun.id, now, setupDefaults, change) }
        } else {
            val standalone = Project(name = run.name, createdAt = 0, runs = listOf(run))
            val changed = RunEdits.apply(standalone, run.id, 0, setupDefaults, change).runs.single()
            vm.update {
                it.copy(
                    raceway = changed.raceway ?: STANDALONE_RACEWAY,
                    path = changed.path,
                    pull = changed.pull,
                )
            }
        }
    }

    fun switchToMode(targetMode: PullPlannerMode) {
        if (targetMode == activeMode) return
        if (targetMode == PullPlannerMode.QUICK_CHECK &&
            inputs.quickLength == null &&
            inputs.quickBends90 == 0 &&
            inputs.quickBends45 == 0 &&
            inputs.quickExtraBendDegrees == 0.0
        ) {
            if (run.path.isNotEmpty()) {
                val quickSummary = QuickPullEstimate.summarizePath(
                    run.path,
                    run.raceway?.conduitType,
                    analysis.conduit?.tradeSize,
                )
                vm.update {
                    it.copy(
                        mode = targetMode,
                        quickLength = quickSummary.totalLength ?: run.raceway?.length,
                        quickBends90 = quickSummary.bends90.coerceAtMost(PullPlannerInputs.MAX_QUICK_BENDS),
                        quickBends45 = quickSummary.bends45.coerceAtMost(PullPlannerInputs.MAX_QUICK_BENDS),
                        quickExtraBendDegrees = quickSummary.extraBendDegrees.coerceIn(0.0, 3600.0),
                    )
                }
                return
            } else if (run.raceway?.length != null) {
                vm.update { it.copy(mode = targetMode, quickLength = run.raceway?.length) }
                return
            }
        }
        vm.update { it.copy(mode = targetMode) }
    }

    fun openInDetailedRoute() {
        val synthesized = quickEvaluation.segments
        if (synthesized.isEmpty()) return
        update { it.copy(path = synthesized) }
        vm.update { it.copy(mode = PullPlannerMode.DETAILED_ROUTE) }
    }

    LaunchedEffect(results, summary) {
        if (results.isNotEmpty()) host.publish(results, summary)
    }

    ToolScaffold(
        title = PullPlannerTool.title,
        host = host,
        onSave = if (results.isEmpty()) null else ({
            val snapshot = inputs.copy(
                mode = activeMode,
                raceway = run.raceway ?: STANDALONE_RACEWAY,
                path = run.path,
                pull = setup,
            )
            host.saveToProject(
                ItemDraft(
                    toolId = PullPlannerTool.ID,
                    title = "Wire pull: ${run.name}",
                    inputs = ToolInputs.encode(snapshot),
                    results = results,
                    summary = summary,
                    route = if (linkedRun == null && effectivePath.isNotEmpty()) RunRoute(effectivePath, setup, run.raceway) else null,
                ),
            )
        }),
        actions = {
            IconButton(onClick = host::openSettings) {
                Icon(painterResource(R.drawable.ic_tune), contentDescription = "Pull planner settings")
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (choices.isNotEmpty()) {
                ChoiceField("Run", listOf(null) + choices, selected, onSelect = { choice ->
                    val pickedRun = choice?.let { c -> projects.firstOrNull { it.id == c.projectId }?.run(c.runId) }
                    vm.load(
                        if (choice == null) PullPlannerInputs()
                        else PullPlannerInputs(
                            projectId = choice.projectId,
                            runId = choice.runId,
                            quickLength = pickedRun?.raceway?.length,
                        ),
                    )
                }, optionLabel = { it?.label ?: "Standalone pull" })
            }
            if (inputs.linked && loaded && linkedRun == null) {
                Text("The original run is unavailable. The saved inputs are available as a standalone pull.")
            }
            if (!canEdit) Text("This run is in an archived project.")

            SegmentedChoice(
                options = PullPlannerMode.entries,
                selected = activeMode,
                onSelect = ::switchToMode,
                optionLabel = { it.label },
            )

            PullVerdictCard(
                verdict = verdict,
                analysis = analysis,
                units = settings.unitSystem,
                lengthText = lengthText,
                emptyMessage = if (activeMode == PullPlannerMode.QUICK_CHECK) {
                    "Enter raceway, cables, total length, and 90° bends for an instant verdict."
                } else {
                    "Add segments and cables below to calculate pull tension and limits."
                },
                onFlipDirection = {
                    val activeDir = verdict?.inspectedDirection ?: analysis.recommended
                    if (activeDir != null) {
                        val flipped = QuickPullEstimate.opposite(activeDir)
                        if (analysis.pass(flipped) != null) inspectedDirection = flipped
                    }
                },
                onUseRecommended = { inspectedDirection = null },
            )

            when (activeMode) {
                PullPlannerMode.QUICK_CHECK -> {
                    FormSection("Quick pull inputs") {
                        RacewayCompactCard(
                            raceway = run.raceway,
                            autoSize = analysis.conduit?.tradeSize,
                            settings = settings,
                            canEdit = canEdit,
                            onClick = { editingRaceway = true },
                        )

                        CablesSection(
                            linkedRun = linkedRun,
                            setup = setup,
                            analysis = analysis,
                            canEdit = canEdit,
                            onToggleCircuits = { include ->
                                update { r -> r.copy(pull = setup.copy(cablesFromCircuits = include)) }
                            },
                            onEditCable = { index -> cableIndex = index },
                            onAddCable = { cableIndex = setup.cables.size },
                            onPickNamed = { named ->
                                update { it.copy(pull = NamedCables.apply(it.pull ?: setup, named)) }
                            },
                        )

                        MeasureField(
                            label = "Total length",
                            value = inputs.quickLength?.toQuantity(),
                            onValueChange = { value ->
                                if (value != null && value.value.signum < 0) {
                                    host.showMessage("Length must be zero or more.")
                                } else {
                                    vm.update { it.copy(quickLength = value?.stored()) }
                                }
                            },
                            supportingText = "End-to-end routed length including bend arcs.",
                        )

                        QuickBends90Control(
                            count = inputs.quickBends90,
                            canEdit = canEdit,
                            onCountChange = { next ->
                                val clamped = next.coerceIn(0, PullPlannerInputs.MAX_QUICK_BENDS)
                                vm.update { it.copy(quickBends90 = clamped) }
                            },
                        )

                        if (canEdit) {
                            FilledTonalButton(
                                onClick = {
                                    if (run.path.isNotEmpty()) confirmingReplaceRoute = true
                                    else openInDetailedRoute()
                                },
                                enabled = quickEvaluation.segments.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Open in Detailed Route")
                            }
                        }
                    }
                }

                PullPlannerMode.DETAILED_ROUTE -> {
                    FormSection("Raceway and route") {
                        RacewayCompactCard(
                            raceway = run.raceway,
                            autoSize = analysis.conduit?.tradeSize,
                            settings = settings,
                            canEdit = canEdit,
                            onClick = { editingRaceway = true },
                        )

                        PathDiagram(
                            run.path,
                            run.raceway?.conduitType,
                            analysis.conduit?.tradeSize,
                            description = "Side view of the route: ${run.path.size} segments, " +
                                "${PullReport.num(RunPath.totalDegrees(run.path))}° of bends, " +
                                "${run.path.count { it is PathSegment.PullPoint }} pull points",
                        )

                        if (run.path.isEmpty()) {
                            Text(
                                "No segments yet. Add a straight, bend, or pull point below.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                run.path.forEachIndexed { index, segment ->
                                    SegmentCard(
                                        index = index,
                                        segment = segment,
                                        conduitType = run.raceway?.conduitType,
                                        tradeSize = analysis.conduit?.tradeSize,
                                        analysis = analysis,
                                        inspectedDirection = verdict?.inspectedDirection ?: analysis.recommended,
                                        units = settings.unitSystem,
                                        lengthText = lengthText,
                                        canEdit = canEdit,
                                        onClick = { segmentEdit = SegmentEdit(index, SegmentKind.of(segment)) },
                                    )
                                }
                            }
                        }

                        // Bends saved from Conduit Bending in this run that aren't in the path yet (or need updating).
                        if (canEdit && linkedRun != null && project != null && bendSources.isNotEmpty()) {
                            TextButton(onClick = {
                                repository.edit(project.id) { current, now ->
                                    bendSources.fold(current) { acc, (item, bends) ->
                                        acc.addBendsToRunPath(linkedRun.id, item.id, bends, now)
                                    }
                                }
                                host.showMessage("Added bends from ${bendSources.size} Bending item${if (bendSources.size == 1) "" else "s"}")
                            }) { Text("Import bends from Bending (${bendSources.size})") }
                        }

                        if (canEdit && run.path.size < RunPath.MAX_SEGMENTS) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SegmentKind.entries.forEach { kind ->
                                    OutlinedButton(onClick = { segmentEdit = SegmentEdit(run.path.size, kind) }) {
                                        Text("+ ${kind.label}")
                                    }
                                }
                            }
                        }
                    }

                    FormSection("Cables") {
                        CablesSection(
                            linkedRun = linkedRun,
                            setup = setup,
                            analysis = analysis,
                            canEdit = canEdit,
                            onToggleCircuits = { include ->
                                update { r -> r.copy(pull = setup.copy(cablesFromCircuits = include)) }
                            },
                            onEditCable = { index -> cableIndex = index },
                            onAddCable = { cableIndex = setup.cables.size },
                            onPickNamed = { named ->
                                update { it.copy(pull = NamedCables.apply(it.pull ?: setup, named)) }
                            },
                        )
                    }
                }
            }

            if (canEdit) {
                val riggingSummary = remember(setup, settings, inputs.quickBends45, inputs.quickExtraBendDegrees, activeMode) {
                    buildRiggingSummary(
                        setup = setup,
                        units = settings.unitSystem,
                        formatQuantity = lengthText,
                        extra45 = if (activeMode == PullPlannerMode.QUICK_CHECK) inputs.quickBends45 else 0,
                        extraDeg = if (activeMode == PullPlannerMode.QUICK_CHECK) inputs.quickExtraBendDegrees else 0.0,
                    )
                }
                AdvancedSection(
                    title = "Advanced rigging & limits",
                    summary = riggingSummary,
                ) {
                    ChoiceField(
                        label = "Friction preset",
                        options = FrictionPreset.entries,
                        selected = setup.friction,
                        onSelect = { preset ->
                            update { it.copy(pull = setup.copy(friction = preset, frictionOverride = null)) }
                        },
                        optionLabel = { "${it.label} (${Decimals.trim(it.coefficient, 2)})" },
                        supportingText = setup.frictionOverride?.let {
                            "Custom override μ = ${Decimals.trim(it, 2)} active. Picking a preset resets the override."
                        },
                    )
                    MeasureField(
                        label = "Slack per end",
                        value = setup.slackPerEnd?.toQuantity(),
                        onValueChange = { value ->
                            if (value != null && value.value.signum < 0) host.showMessage("Slack must be zero or more.")
                            else update { it.copy(pull = setup.copy(slackPerEnd = value?.stored())) }
                        },
                    )
                    if (activeMode == PullPlannerMode.QUICK_CHECK) {
                        BendCountStepperRow(
                            label = "Optional 45° bends",
                            count = inputs.quickBends45,
                            canEdit = canEdit,
                            onCountChange = { next ->
                                val clamped = next.coerceIn(0, PullPlannerInputs.MAX_QUICK_BENDS)
                                vm.update { it.copy(quickBends45 = clamped) }
                            },
                        )
                        NumberField(
                            label = "Extra bend degrees (offsets/kicks)",
                            value = extraBendText,
                            onValueChange = { typed ->
                                extraBendText = typed
                                val parsed = if (typed.isBlank()) 0.0 else Decimals.parse(typed)
                                if (parsed != null && parsed in 0.0..3600.0) {
                                    vm.update { it.copy(quickExtraBendDegrees = parsed) }
                                }
                            },
                            unit = "°",
                            supportingText = "Optional additional bend degrees beyond 90° and 45° bends.",
                        )
                    }
                    OutlinedButton(
                        onClick = { editingRatings = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        val ratedCount = setup.equipment.count { it.pounds != null }
                        Text(
                            if (ratedCount > 0) "Equipment & tension ratings ($ratedCount rated)…"
                            else "Equipment & tension ratings…",
                        )
                    }
                }
            }

            FormSection("Details & notices") {
                ResultCard(
                    results = results,
                    notices = analysis.notes.map { Notice(it.text, if (it.isWarning) NoticeLevel.WARNING else NoticeLevel.INFO, PullRefs.forNote(it.text)) },
                    emptyText = if (activeMode == PullPlannerMode.QUICK_CHECK) {
                        "Enter total length and cables to view detailed pull metrics."
                    } else {
                        "Add the path and cables to calculate the pull."
                    },
                    onOpenReference = { host.openReference(it) },
                )
            }

            val needed = analysis.cableLengthIn
            if (needed != null || results.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (needed != null) {
                        OutlinedButton(onClick = {
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
                        OutlinedButton(enabled = !pdfBusy, onClick = ::sharePdf) {
                            Text("Share PDF")
                        }
                        OutlinedButton(enabled = !pdfBusy, onClick = ::savePdf) {
                            Text("Save PDF")
                        }
                        TextButton(onClick = { host.shareText("Wire pull: ${run.name}", summary) }) {
                            Text("Share text")
                        }
                    }
                }
            }
        }
    }

    if (editingRaceway) {
        RacewayEditorSheet(
            title = "Raceway",
            initial = run.raceway,
            onSave = { raceway ->
                update { it.copy(raceway = raceway) }
                if (activeMode == PullPlannerMode.QUICK_CHECK && raceway.length != null && raceway.length != run.raceway?.length) {
                    vm.update { it.copy(quickLength = raceway.length) }
                }
                editingRaceway = false
            },
            onDismiss = { editingRaceway = false },
            routed = activeMode == PullPlannerMode.DETAILED_ROUTE && run.path.isNotEmpty(),
        )
    }

    segmentEdit?.let { editor ->
        val current = run.path.getOrNull(editor.index)
        PullSegmentDialog(
            kind = editor.kind,
            initial = current,
            positionLabel = if (current != null) "Segment ${editor.index + 1} of ${run.path.size}" else null,
            canMoveUp = current != null && editor.index > 0,
            canMoveDown = current != null && editor.index < run.path.lastIndex,
            onSave = { segment ->
                update { it.copy(path = RunEdits.replace(it.path, editor.index, segment)) }
                segmentEdit = null
            },
            onDismiss = { segmentEdit = null },
            onMoveUp = if (current != null) ({
                if (editor.index > 0) {
                    update { it.copy(path = RunEdits.move(it.path, editor.index, editor.index - 1)) }
                    segmentEdit = editor.copy(index = editor.index - 1)
                }
            }) else null,
            onMoveDown = if (current != null) ({
                if (editor.index < run.path.lastIndex) {
                    update { it.copy(path = RunEdits.move(it.path, editor.index, editor.index + 1)) }
                    segmentEdit = editor.copy(index = editor.index + 1)
                }
            }) else null,
            onRemove = if (current != null) ({
                update { it.copy(path = RunEdits.remove(it.path, editor.index)) }
                segmentEdit = null
            }) else null,
        )
    }

    cableIndex?.let { index ->
        val existing = setup.cables.getOrNull(index)
        PullCableDialog(
            initial = existing ?: PullCable(),
            onSave = { cable ->
                val cables = setup.cables.toMutableList()
                if (index in cables.indices) cables[index] = cable else cables.add(cable)
                update { it.copy(pull = setup.copy(cables = cables)) }
                cableIndex = null
            },
            onDismiss = { cableIndex = null },
            onRemove = if (existing != null && canEdit) ({
                update { it.copy(pull = setup.copy(cables = setup.cables.filterIndexed { i, _ -> i != index })) }
                cableIndex = null
            }) else null,
        )
    }

    if (editingRatings) {
        PullRatingsDialog(
            setup = setup,
            sectionCount = RunPath.sections(effectivePath).size.coerceAtLeast(1),
            onSave = { rated ->
                update { it.copy(pull = rated) }
                editingRatings = false
            },
            onDismiss = { editingRatings = false },
        )
    }

    if (confirmingReplaceRoute) {
        ConfirmDialog(
            title = "Replace detailed route?",
            message = "This run already has ${run.path.size} segments. Replace them with the ${quickEvaluation.segments.size}-segment Quick Check route?",
            confirmLabel = "Replace",
            onConfirm = {
                confirmingReplaceRoute = false
                openInDetailedRoute()
            },
            onDismiss = { confirmingReplaceRoute = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PullVerdictCard(
    verdict: PullVerdict?,
    analysis: PullResult,
    units: UnitSystem,
    lengthText: (Quantity) -> String,
    emptyMessage: String,
    onFlipDirection: () -> Unit,
    onUseRecommended: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = if (verdict?.status == PullCheckStatus.FAIL) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
        } else null,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (verdict == null) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "Pull verdict",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            emptyMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (analysis.totalDegrees > 0.0) {
                        StatusBadge(
                            text = "${PullReport.num(analysis.totalDegrees)}° / ${PullReport.num(RacewayBends.MAX_DEGREES_BETWEEN_PULL_POINTS, 0)}°",
                            isError = analysis.sectionDegrees.any { it > RacewayBends.MAX_DEGREES_BETWEEN_PULL_POINTS + 1e-9 },
                        )
                    }
                }
                analysis.notes.firstOrNull { it.isWarning }?.let { warning ->
                    Text(
                        "⚠ ${warning.text}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                return@Card
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        if (verdict.isComparingReverse) "Comparing reverse pull" else "Recommended pull direction",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        QuickPullEstimate.directionLabel(verdict.inspectedDirection),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (verdict.isComparingReverse) {
                        Text(
                            "Recommended: ${QuickPullEstimate.directionLabel(verdict.recommendedDirection)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                StatusBadge(
                    text = verdict.status.label,
                    isError = verdict.status == PullCheckStatus.FAIL,
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                VerdictMetric(
                    label = "Peak Tension vs Limit",
                    value = buildString {
                        append(PullReport.lb(verdict.peakTensionLb, units))
                        verdict.tensionLimitLb?.let { append(" / ${PullReport.lb(it, units)}") }
                    },
                    detail = when {
                        verdict.overTension -> "Exceeds ${verdict.tensionLimitLabel ?: "limit"}"
                        verdict.tensionLimitLabel != null -> verdict.tensionLimitLabel
                        else -> "No tension limit set"
                    },
                    isError = verdict.overTension,
                    modifier = Modifier.weight(1f),
                )
                VerdictMetric(
                    label = "Max SWBP vs Limit",
                    value = verdict.peakSwbpLbPerFt?.let { swbp ->
                        buildString {
                            append(PullAnalysis.pressure(swbp, units))
                            verdict.swbpLimitLbPerFt?.let { append(" / ${PullAnalysis.pressure(it, units)}") }
                        }
                    } ?: "—",
                    detail = when {
                        verdict.overSwbp -> "Exceeds ${verdict.swbpLimitLabel ?: "SWBP limit"}"
                        verdict.peakSwbpLbPerFt == null -> "No bends in route"
                        verdict.swbpLimitLabel != null -> verdict.swbpLimitLabel
                        else -> "No SWBP limit set"
                    },
                    isError = verdict.overSwbp,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                VerdictMetric(
                    label = "Total Bend Degrees / 360°",
                    value = "${PullReport.num(verdict.totalBendDegrees)}° / ${PullReport.num(verdict.bendLimitDegrees, 0)}°",
                    detail = when {
                        verdict.overBendDegrees && analysis.sectionDegrees.size > 1 ->
                            "Section max ${PullReport.num(verdict.maxSectionDegrees)}° (over 360°)"
                        verdict.overBendDegrees -> "Over 360° — add pull point"
                        analysis.sectionDegrees.size > 1 ->
                            "${analysis.sectionDegrees.size} sections · max ${PullReport.num(verdict.maxSectionDegrees)}°"
                        else -> "Within NEC 360° limit"
                    },
                    isError = verdict.overBendDegrees,
                    modifier = Modifier.weight(1f),
                )
                VerdictMetric(
                    label = "Tugger & Cable",
                    value = verdict.suggestedTuggerLb?.let { "≥ ${PullReport.lb(it.toDouble(), units)}" }
                        ?: if (verdict.peakTensionLb > 0.0) "> ${PullReport.lb(20000.0, units)}" else "—",
                    detail = analysis.cableLengthIn?.let { "Cable: ${lengthText(Quantity.inches(Rational.of(it)))}" } ?: "—",
                    isError = false,
                    modifier = Modifier.weight(1f),
                )
            }

            val alerts = buildList {
                if (verdict.overFill) add("Fill over NEC limit")
                if (verdict.lowClearance) add("Clearance below minimum")
                if (verdict.jamWarning) add("Jam risk (3 cables)")
            }
            if (alerts.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    alerts.forEach { alert ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                        ) {
                            Text(
                                "⚠ $alert",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }

            if (analysis.forward != null && analysis.reverse != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onFlipDirection) {
                        val nextDir = QuickPullEstimate.opposite(verdict.inspectedDirection)
                        Text("Compare ${QuickPullEstimate.directionShortLabel(nextDir)}")
                    }
                    if (verdict.isComparingReverse) {
                        TextButton(onClick = onUseRecommended) {
                            Text("Show recommended")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String, isError: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun VerdictMetric(
    label: String,
    value: String,
    detail: String,
    isError: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        if (detail.isNotBlank()) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RacewayCompactCard(
    raceway: Raceway?,
    autoSize: TradeSize?,
    settings: AppSettings,
    canEdit: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (canEdit) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Raceway", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    raceway?.let { racewaySummary(it, settings, autoSize = autoSize) } ?: "Tap to choose raceway",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (canEdit) {
                Icon(
                    painterResource(R.drawable.ic_edit),
                    contentDescription = "Edit raceway",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CablesSection(
    linkedRun: Run?,
    setup: PullSetup,
    analysis: PullResult,
    canEdit: Boolean,
    onToggleCircuits: (Boolean) -> Unit,
    onEditCable: (Int) -> Unit,
    onAddCable: () -> Unit,
    onPickNamed: (NamedCable) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Cables", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (canEdit && linkedRun != null) {
            ToggleRow("Include assigned circuit conductors", setup.cablesFromCircuits, onToggleCircuits)
        }
        analysis.conductors.filter { it.fromCircuits }.forEach { conductor ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
            ) {
                Text(
                    "${conductor.text} (from circuits)",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        setup.cables.forEachIndexed { index, cable ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (canEdit) Modifier.clickable(role = Role.Button) { onEditCable(index) } else Modifier),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        NamedCables.describe(cable),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    if (canEdit) {
                        Icon(
                            painterResource(R.drawable.ic_edit),
                            contentDescription = "Edit cable",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        if (setup.cables.isEmpty() && analysis.conductors.none { it.fromCircuits }) {
            Text(
                "No cables added yet. Add a conductor group or saved named cable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (canEdit && setup.cables.size < 200) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onAddCable, modifier = Modifier.heightIn(min = 48.dp)) { Text("+ Add cable") }
                NamedCableActions(
                    onPick = onPickNamed,
                    pickLabel = "Add named cable…",
                    current = NamedCables.fromSetup(setup),
                )
            }
        }
    }
}

@Composable
private fun QuickBends90Control(
    count: Int,
    canEdit: Boolean,
    onCountChange: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BendCountStepperRow(
            label = "Number of 90° bends",
            count = count,
            canEdit = canEdit,
            onCountChange = onCountChange,
            subtitle = "${count * 90}° total from 90° bends",
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(0, 1, 2, 3, 4).forEach { preset ->
                FilterChip(
                    selected = count == preset,
                    onClick = { if (canEdit) onCountChange(preset) },
                    enabled = canEdit,
                    label = { Text("$preset × 90°") },
                )
            }
        }
    }
}

@Composable
private fun BendCountStepperRow(
    label: String,
    count: Int,
    canEdit: Boolean,
    onCountChange: (Int) -> Unit,
    subtitle: String? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FilledTonalIconButton(
                onClick = { onCountChange(count - 1) },
                enabled = canEdit && count > 0,
            ) {
                Text("−", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                count.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            FilledTonalIconButton(
                onClick = { onCountChange(count + 1) },
                enabled = canEdit && count < PullPlannerInputs.MAX_QUICK_BENDS,
            ) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = "Add bend")
            }
        }
    }
}

@Composable
private fun SegmentCard(
    index: Int,
    segment: PathSegment,
    conduitType: ConduitType?,
    tradeSize: TradeSize?,
    analysis: PullResult,
    inspectedDirection: PullDirection?,
    units: UnitSystem,
    lengthText: (Quantity) -> String,
    canEdit: Boolean,
    onClick: () -> Unit,
) {
    val activeDir = inspectedDirection ?: PullDirection.FORWARD
    val activeStep = PullReport.tensionAt(analysis, activeDir, index)
    val otherDir = QuickPullEstimate.opposite(activeDir)
    val otherStep = PullReport.tensionAt(analysis, otherDir, index)
    val overTension = activeStep != null && analysis.tensionLimit != null && activeStep.tensionOut > analysis.tensionLimit!!.value
    val overPressure = activeStep?.pressure != null && analysis.pressureLimit != null && activeStep.pressure!! > analysis.pressureLimit!!.value
    val hasIssue = overTension || overPressure

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (canEdit) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (hasIssue) BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)) else null,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "${index + 1}. ${PullReport.describe(segment, conduitType, tradeSize, lengthText)}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (activeStep != null) {
                    val activePressure = activeStep.pressure?.let { " · ${PullAnalysis.pressure(it, units)}" }.orEmpty()
                    val activeText = "${QuickPullEstimate.directionShortLabel(activeDir)}: ${PullReport.lb(activeStep.tensionOut, units)}$activePressure"
                    val otherText = otherStep?.let { "  |  ${QuickPullEstimate.directionShortLabel(otherDir)}: ${PullReport.lb(it.tensionOut, units)}" }.orEmpty()
                    Text(
                        activeText + otherText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasIssue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (canEdit) {
                Icon(
                    painterResource(R.drawable.ic_edit),
                    contentDescription = "Edit segment ${index + 1}",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun buildRiggingSummary(
    setup: PullSetup,
    units: UnitSystem,
    formatQuantity: (Quantity) -> String,
    extra45: Int,
    extraDeg: Double,
): String = buildList {
    val muText = Decimals.trim(setup.coefficient, 2)
    val muKind = if (setup.frictionOverride != null) "custom" else setup.friction.label.substringBefore(' ').lowercase()
    add("μ $muText ($muKind)")
    val slackQty = setup.slackPerEnd?.toQuantity()
    add(if (slackQty != null && slackQty.value.signum > 0) "Slack ${formatQuantity(slackQty)}/end" else "No slack")
    val ratedCount = setup.equipment.count { it.pounds != null }
    if (ratedCount > 0) add("$ratedCount equipment limit${if (ratedCount == 1) "" else "s"}")
    setup.pressureLimit?.let { add("SWBP ${PullAnalysis.pressure(it, units)}") }
    if (extra45 > 0) add("+$extra45×45°")
    if (extraDeg > 0.0) add("+${PullReport.num(extraDeg)}° extra")
}.joinToString(" · ")
