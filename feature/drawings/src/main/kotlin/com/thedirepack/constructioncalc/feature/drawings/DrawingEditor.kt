package com.thedirepack.constructioncalc.feature.drawings

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.thedirepack.constructioncalc.core.ui.R as CoreR
import com.thedirepack.constructioncalc.core.data.PlanSymbols
import com.thedirepack.constructioncalc.core.data.PlanSymbol
import com.thedirepack.constructioncalc.core.model.newId
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.data.PlanExportCrop
import com.thedirepack.constructioncalc.core.data.PlanExports
import com.thedirepack.constructioncalc.core.data.PlanFiles
import com.thedirepack.constructioncalc.core.data.ProjectSharing
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.PathSegment
import com.thedirepack.constructioncalc.core.model.IssuedSheet
import com.thedirepack.constructioncalc.core.model.PlanDrawing
import com.thedirepack.constructioncalc.core.model.PlanSheetSource
import com.thedirepack.constructioncalc.core.model.issuedSheets
import com.thedirepack.constructioncalc.core.model.sheetRevisionStatus
import com.thedirepack.constructioncalc.core.model.sheetSource
import com.thedirepack.constructioncalc.core.model.PlanMark
import com.thedirepack.constructioncalc.core.model.PlanMarkKind
import com.thedirepack.constructioncalc.core.model.PlanMeasurement
import com.thedirepack.constructioncalc.core.model.PlanPath
import com.thedirepack.constructioncalc.core.model.PlanPoint
import com.thedirepack.constructioncalc.core.model.PlanReference
import com.thedirepack.constructioncalc.core.model.PlanZone
import com.thedirepack.constructioncalc.core.model.measurePath
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.plans.LineMap
import com.thedirepack.constructioncalc.core.plans.SnapTarget
import com.thedirepack.constructioncalc.core.ui.format
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost
import com.thedirepack.constructioncalc.feature.drawings.domain.CalibrationDiagnostics
import com.thedirepack.constructioncalc.feature.drawings.domain.DrawingHistory
import com.thedirepack.constructioncalc.feature.drawings.domain.PlanRoute
import com.thedirepack.constructioncalc.feature.drawings.domain.RouteSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** A route ready for a run: where it was traced and its plan length, for the run's link back. */
internal data class TracedRoute(val page: Int, val points: List<PlanPoint>, val horizontalInches: Double, val circuitTag: String)

/** Saves the page and tool across configuration changes; a half-done operation is dropped. */
private val SessionSaver = Saver<EditorSession, List<Any>>(
    save = { listOf(it.page, it.mode.name) },
    restore = { saved ->
        EditorSession(
            page = (saved[0] as Int).coerceAtLeast(0),
            mode = DrawingMode.entries.firstOrNull { it.name == saved[1] } ?: DrawingMode.Adjust,
        )
    },
)

@Composable
internal fun DrawingEditor(
    project: Project,
    drawing: PlanDrawing,
    file: File,
    editable: Boolean,
    /** A saved route to open on (from a run's "View on plan"). */
    focusRouteId: String?,
    onChange: ((PlanDrawing) -> PlanDrawing) -> Unit,
    onSendRouteToRun: (runId: String?, newRunName: String?, segments: List<PathSegment>, route: TracedRoute) -> Unit,
    onPublish: (PlanMeasurement, Quantity, PlanSheetSource) -> Unit,
    host: ToolHost,
    modifier: Modifier = Modifier,
    jumpPageRequest: Pair<Int, Int>? = null,
    returnLabel: String? = null,
    onReturnToSheet: (() -> Unit)? = null,
    onJumpToSheet: (targetDrawingId: String, targetPage: Int, fromPage: Int) -> Unit = { _, _, _ -> },
) {
    val context = LocalContext.current
    val settings = rememberAppSettings()
    val (prefs, prefsStore) = rememberDrawingsPrefs()
    val lineStore = remember(context) { PlanLineStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val pageCount by produceState<Int?>(null, file) {
        value = try {
            withContext(Dispatchers.IO) { pdfPageCount(file) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            0
        }
    }
    val focusRoute = drawing.routes.firstOrNull { it.id == focusRouteId }
    val views = remember(context) { context.getSharedPreferences("drawing-views", 0) }
    var rotation by rememberSaveable(drawing.id) { mutableStateOf(views.getInt("${drawing.id}/rotation", 0)) }
    var selectedMarkId by remember(drawing.id) { mutableStateOf<String?>(null) }
    var selectedPathId by remember(drawing.id) { mutableStateOf<String?>(null) }
    var selectedVertex by remember(drawing.id) { mutableStateOf<Int?>(null) }
    var selectedEdge by remember(drawing.id) { mutableStateOf<Int?>(null) }
    var selectedMeasurementId by remember(drawing.id) { mutableStateOf<String?>(null) }
    var symbol by remember(drawing.id) { mutableStateOf<PlanSymbol?>(null) }
    LaunchedEffect(drawing.id) {
        val id = views.getString("${drawing.id}/symbol", null) ?: return@LaunchedEffect
        val saved = starterSymbols.firstOrNull { it.id == id } ?: withContext(Dispatchers.IO) {
            PlanSymbols(context.applicationContext).list().firstOrNull { it.id == id }
        }
        if (symbol == null) symbol = saved
    }
    var circuitTag by rememberSaveable(drawing.id) { mutableStateOf(views.getString("${drawing.id}/circuit", "").orEmpty()) }
    var areaTag by rememberSaveable(drawing.id) { mutableStateOf(views.getString("${drawing.id}/area", "").orEmpty()) }
    var editCircuit by remember(drawing.id) { mutableStateOf(false) }
    var editArea by remember(drawing.id) { mutableStateOf(false) }
    var editMarkCircuit by remember(drawing.id) { mutableStateOf(false) }
    var editMarkArea by remember(drawing.id) { mutableStateOf(false) }
    var eraserMode by rememberSaveable(drawing.id) { mutableStateOf(EraserMode.PARTIAL) }
    var eraserSizeOpen by remember(drawing.id) { mutableStateOf(false) }
    var countGrouping by rememberSaveable(drawing.id) { mutableStateOf(CountGrouping.DEVICE) }
    val styleStore = remember(views) { MarkupStyleStore(views) }
    var styleVersion by remember { mutableStateOf(0) }
    var settingsOpen by rememberSaveable(drawing.id) { mutableStateOf(false) }
    var sheetsOpen by rememberSaveable(drawing.id) { mutableStateOf(false) }
    val sheetBrowserState = rememberSaveableStateHolder()
    var routeFinished by remember(drawing.id) { mutableStateOf(false) }
    var highlightsVisible by rememberSaveable(drawing.id) { mutableStateOf(true) }
    var editText by remember(drawing.id) { mutableStateOf(false) }
    var calibrationDraft by remember(drawing.id) { mutableStateOf<List<StagedCalibrationRef>?>(null) }
    val sheetStore = remember(views, drawing.id, drawing.sha256) {
        SheetNavigationStore(views, "${drawing.id}/${drawing.sha256}")
    }
    var visits by remember(sheetStore) { mutableStateOf(sheetStore.visits()) }
    var customLabels by remember(sheetStore) { mutableStateOf(sheetStore.labels()) }
    val labels = remember(file) { mutableStateMapOf<Int, SheetLabel>() }
    var indexing by remember(file) { mutableStateOf(true) }
    LaunchedEffect(file, drawing.sha256) {
        try {
            indexSheets(context.applicationContext, drawing.sha256, file) { index, label -> labels[index] = label }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Page numbers and thumbnails are available when PDF text extraction fails.
        } finally {
            indexing = false
        }
    }
    var session by rememberSaveable(drawing.id, focusRouteId, stateSaver = SessionSaver) {
        mutableStateOf(
            if (focusRoute != null) EditorSession(page = focusRoute.page, focusedRouteId = focusRoute.id)
            else EditorSession(page = views.getInt("${drawing.id}/page", 0).coerceAtLeast(0)),
        )
    }
    var history by remember(drawing.id) { mutableStateOf(DrawingHistory<PlanDrawing>()) }
    var pageSize by remember(drawing.id, session.page) { mutableStateOf<PageSize?>(null) }
    var busy by remember { mutableStateOf(false) }
    var revisionOpen by remember(drawing.id, session.page) { mutableStateOf(false) }
    var comparison by remember(drawing.id, session.page) { mutableStateOf<IssuedSheet?>(null) }
    var sheetExportOpen by remember(drawing.id, session.page) { mutableStateOf(false) }
    var cropProvider by remember(drawing.id) { mutableStateOf<(() -> PlanExportCrop?)?>(null) }
    var pendingExport by rememberSaveable(drawing.id, stateSaver = SheetExportSaver) {
        mutableStateOf<SheetExportRequest?>(null)
    }
    val files = remember(context) { PlanFiles(context.applicationContext) }

    fun publish(measurement: PlanMeasurement, quantity: Quantity) {
        val source = drawing.sheetSource(measurement.page, labels[measurement.page]?.number ?: "Page ${measurement.page + 1}")
        onPublish(measurement, quantity, source)
    }
    val page = session.page
    val mode = if (editable) session.mode else DrawingMode.Navigate
    LaunchedEffect(drawing.id, page, rotation, pageCount) {
        if (pageCount?.let { page < it } == true) {
            views.edit().putInt("${drawing.id}/page", page).putInt("${drawing.id}/rotation", rotation).apply()
        }
    }

    LaunchedEffect(sheetStore, page, pageCount) {
        if (pageCount?.let { page in 0 until it } == true && visits.recent.firstOrNull() != page) {
            visits = visits.visit(page)
            sheetStore.saveRecent(visits)
        }
    }

    fun clearSelection() {
        selectedMarkId = null
        selectedPathId = null
        selectedVertex = null
        selectedEdge = null
        selectedMeasurementId = null
        editText = false
    }

    var handledJumpSerial by rememberSaveable(drawing.id) { mutableStateOf(0) }
    LaunchedEffect(jumpPageRequest?.second, pageCount) {
        val total = pageCount ?: return@LaunchedEffect
        val req = jumpPageRequest ?: return@LaunchedEffect
        if (total > 0 && req.second > handledJumpSerial) {
            handledJumpSerial = req.second
            clearSelection()
            routeFinished = false
            session = session.goToPage(req.first.coerceIn(0, total - 1))
            sheetsOpen = false
        }
    }

    fun seedCalibrationDraft(): List<StagedCalibrationRef> = buildList {
        drawing.calibration.references.forEach { add(StagedCalibrationRef(zoneId = null, ref = it)) }
        drawing.zones.forEach { z -> z.references.forEach { add(StagedCalibrationRef(zoneId = z.id, ref = it)) } }
    }

    fun applyDraftToDrawing(draft: List<StagedCalibrationRef>): PlanDrawing {
        val docRefs = draft.filter { it.zoneId == null }.map { it.ref }
        return drawing.copy(
            calibration = drawing.calibration.copy(references = docRefs),
            zones = drawing.zones.map { z -> z.copy(references = draft.filter { it.zoneId == z.id }.map { it.ref }) },
        )
    }

    LaunchedEffect(mode) {
        if (mode == DrawingMode.Reference && calibrationDraft == null) {
            calibrationDraft = seedCalibrationDraft()
            DrawingsSettings.setShowCalibrationLayer(prefsStore, true)
        } else if (mode != DrawingMode.Reference && calibrationDraft != null) {
            calibrationDraft = null
        }
    }

    fun switchMode(next: DrawingMode) {
        if (next != mode) {
            clearSelection()
            routeFinished = false
            if (next == DrawingMode.Reference) {
                if (calibrationDraft == null) calibrationDraft = seedCalibrationDraft()
                DrawingsSettings.setShowCalibrationLayer(prefsStore, true)
            } else if (mode == DrawingMode.Reference) {
                calibrationDraft = null
            }
        }
        session = session.withMode(next)
        if (next == DrawingMode.Symbol && symbol == null) session = session.open(EditorDialog.PickSymbol)
    }

    BackHandler(enabled = session.dialog == null && !settingsOpen && !sheetsOpen &&
        (selectedMarkId != null || selectedPathId != null || selectedMeasurementId != null || session.op != EditOp.Idle || mode != DrawingMode.Adjust && editable)
    ) {
        when {
            selectedMarkId != null || selectedPathId != null || selectedMeasurementId != null -> clearSelection()
            session.op != EditOp.Idle -> {
                session = session.copy(op = EditOp.Idle)
                routeFinished = false
            }
            else -> switchMode(DrawingMode.Adjust)
        }
    }

    val snapEnabled = when {
        !mode.snaps -> false
        mode == DrawingMode.Reference -> prefs.snapCalibration
        else -> prefs.snapMeasurements
    }
    // Wait for page navigation to settle before starting the native line extraction.
    val lineMap by key(drawing.sha256, file, page, snapEnabled) {
        produceState<LineMap?>(null) {
            if (!snapEnabled) return@produceState
            delay(LINE_MAP_DELAY_MS)
            value = try {
                lineStore.getOrBuild(drawing.sha256, file, page)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }
    val activeToolStyle = remember(mode, styleVersion, styleStore) {
        if (mode.supportsStyle()) styleStore.read(mode) else null
    }
    val snapTarget = when (mode) {
        DrawingMode.Reference -> prefs.calibrationTarget
        DrawingMode.Measure, DrawingMode.Route, DrawingMode.Adjust, DrawingMode.Dimension -> prefs.measurementTarget
        else -> SnapTarget.CENTER
    }
    val formatDimension: (PlanMark) -> String? = remember(drawing, settings) {
        { mark ->
            val measured = drawing.dimensionLength(mark)?.let { "≈ ${settings.format(it)}" } ?: "Uncalibrated"
            if (mark.text.isBlank()) measured else "${mark.text}: $measured"
        }
    }

    val pageZones = remember(drawing.zones, page) { drawing.zones.filter { it.page == page } }
    val zone = pageZones.firstOrNull { it.id == session.selectedZoneId } ?: pageZones.firstOrNull()
    val marks = remember(drawing.marks, page) { drawing.marks.filter { it.page == page } }
    val visibleMarks = remember(marks, highlightsVisible) {
        if (highlightsVisible) marks else marks.filterNot { it.kind == PlanMarkKind.HIGHLIGHT }
    }
    val pagePaths = remember(drawing.paths, drawing.calibration, pageZones, page, settings) {
        drawing.paths.filter { it.page == page }.map { path ->
            CanvasPath(path, drawing.measurePath(path).label { settings.format(it) })
        }
    }
    val selectedPath = pagePaths.firstOrNull { it.path.id == selectedPathId }.takeIf { mode == DrawingMode.Adjust }
    val selectedMark = visibleMarks.firstOrNull { it.id == selectedMarkId }
    val activeCalibrationDraft = calibrationDraft.takeIf { mode == DrawingMode.Reference }
    val draftDrawing = remember(drawing, activeCalibrationDraft) {
        activeCalibrationDraft?.let(::applyDraftToDrawing) ?: drawing
    }
    val docSummary = remember(drawing.calibration, page) { CalibrationDiagnostics.evaluate(drawing.calibration, page) }
    val fittedMeasurements = remember(drawing, page) {
        drawing.measurements.filter { it.page == page }.map { it to drawing.length(it) }
    }
    val calibrationLines = remember(drawing.calibration, pageZones, page, prefs.showCalibrationLayer, activeCalibrationDraft, settings) {
        if (activeCalibrationDraft != null) {
            activeCalibrationDraft.mapIndexedNotNull { idx, item ->
                if (item.ref.page != page) null
                else {
                    val tag = if (item.ref.check) " (Check)" else ""
                    CanvasLine(item.ref.first, item.ref.second, "#${idx + 1} · ${settings.format(item.ref.length.toQuantity())}$tag", isReference = true)
                }
            }
        } else if (!prefs.showCalibrationLayer) {
            emptyList()
        } else {
            val refs = drawing.calibration.references.filter { it.page == page } + pageZones.flatMap { it.references }
            refs.map { ref -> CanvasLine(ref.first, ref.second, settings.format(ref.length.toQuantity()), isReference = true) }
        }
    }
    val zoneStatus = remember(pageZones) {
        pageZones.associateWith { CalibrationDiagnostics.evaluateZone(it).status }.mapKeys { it.key.id }
    }
    fun scaleFor(first: PlanPoint, second: PlanPoint): String {
        val region = drawing.zoneContaining(page, first, second)
        return if (region == null) "Sheet · ${docSummary.status.label}"
        else "${region.name} · ${zoneStatus[region.id]?.label}"
    }
    val displayedLines = remember(fittedMeasurements, calibrationLines, settings) {
        fittedMeasurements.map { (measurement, length) ->
            val value = length?.let { "≈ ${settings.format(it)}" } ?: "Uncalibrated"
            CanvasLine(measurement.first, measurement.second, if (measurement.label.isBlank()) value else "${measurement.label}\n$value")
        } + calibrationLines
    }
    val savedRoutes = remember(drawing.routes, project.runs, page, session.focusedRouteId) {
        drawing.routes.filter { it.page == page }.map { route ->
            val runName = project.runs.firstOrNull { it.id == route.runId }?.name ?: "Run (deleted)"
            CanvasRoute(
                points = route.points,
                label = if (route.circuitTag.isBlank()) runName else "$runName · ${route.circuitTag}",
                focused = route.id == session.focusedRouteId,
            )
        }
    }
    val handles = remember(drawing, page, mode, prefs.showCalibrationLayer, activeCalibrationDraft, selectedMeasurementId, selectedMark, selectedPath) {
        if (selectedPath != null) {
            val path = selectedPath.path
            val target = EndpointTarget.Path(path.id)
            path.points.mapIndexed { i, point -> EndpointHandle(target, i, point) }
        } else if (mode == DrawingMode.Reference && activeCalibrationDraft != null) {
            activeCalibrationDraft.filter { it.ref.page == page }.flatMap { item ->
                val target = EndpointTarget.StagedReference(item.id)
                listOf(
                    EndpointHandle(target, 0, item.ref.first),
                    EndpointHandle(target, 1, item.ref.second),
                )
            }
        } else if (mode == DrawingMode.Adjust && selectedMark == null) {
            EditorActions.handles(drawing, page, includeReferences = prefs.showCalibrationLayer).filter {
                when (val target = it.target) {
                    is EndpointTarget.Measurement -> target.id == selectedMeasurementId
                    else -> selectedMeasurementId == null
                }
            }
        }
        else emptyList()
    }

    val routePoints = session.routePoints
    val routeSummary = remember(drawing, page, routePoints) { PlanRoute.measure(drawing, page, routePoints) }
    val routeLegLabels = remember(routeSummary, settings) {
        routeSummary?.legs?.map { leg -> "≈ " + settings.format(Quantity.inches(Rational.of(leg.lengthInches))) }.orEmpty()
    }
    val routeCornerLabels = remember(routeSummary) {
        routeSummary?.corners?.map { corner -> corner.point to "${corner.bendDegrees.roundToInt()}°" }.orEmpty()
    }

    fun editDrawing(change: (PlanDrawing) -> PlanDrawing) {
        history = history.push(drawing)
        onChange(change)
    }

    // Markup undo preserves run links and sheet identity.
    fun undo() {
        val (restored, next) = history.undo(drawing) ?: return
        history = next
        onChange { current -> restored.copy(routes = current.routes, sheetRevisions = current.sheetRevisions) }
    }

    fun redo() {
        val (restored, next) = history.redo(drawing) ?: return
        history = next
        onChange { current -> restored.copy(routes = current.routes, sheetRevisions = current.sheetRevisions) }
    }

    fun apply(outcome: EditorOutcome) {
        session = outcome.session
        outcome.change?.let(::editDrawing)
        outcome.message?.let(host::showMessage)
        outcome.published?.let { (measurement, value) -> publish(measurement, value) }
    }

    fun addMark(mark: PlanMark) = editDrawing { it.copy(marks = it.marks + mark) }

    fun replaceMark(mark: PlanMark) = editDrawing { d -> d.copy(marks = d.marks.map { if (it.id == mark.id) mark else it }) }
    fun deleteMark(mark: PlanMark) {
        editDrawing { d -> d.copy(marks = d.marks.filterNot { it.id == mark.id }) }
        clearSelection()
    }

    fun saveTrace(closed: Boolean) {
        val path = PlanPath.fromTrace(page, routePoints, closed)
        if (path == null) {
            host.showMessage(if (closed) "Trace a simple polygon with 3 to ${PlanPath.MAX_VERTICES} vertices."
                else "Trace a path with 2 to ${PlanPath.MAX_VERTICES} vertices.")
            return
        }
        editDrawing { it.copy(paths = it.paths + path) }
        switchMode(DrawingMode.Adjust)
        selectedPathId = path.id
    }

    fun replacePath(path: PlanPath, replacement: PlanPath?) {
        if (replacement == null) {
            host.showMessage("That edit would make the path invalid.")
            return
        }
        editDrawing { d -> d.copy(paths = d.paths.map { if (it == path) replacement else it }) }
    }

    fun place(point: PlanPoint, radius: Double) {
        when (mode) {
            DrawingMode.Adjust -> {
                val tolerance = radius * .55
                val currentPath = selectedPath?.path
                val vertex = currentPath?.nearestVertex(point, tolerance)
                if (vertex != null) {
                    selectedVertex = vertex
                    selectedEdge = null
                    return
                }
                clearSelection()
                selectedMarkId = markAt(visibleMarks, point, tolerance)?.id
                if (selectedMarkId == null) {
                    for (item in pagePaths.asReversed()) {
                        val edge = item.path.nearestEdge(point, tolerance) ?: continue
                        selectedPathId = item.path.id
                        selectedVertex = item.path.nearestVertex(point, tolerance)
                        selectedEdge = edge.takeIf { selectedVertex == null }
                        return
                    }
                    selectedMeasurementId = EditorActions.measurementAt(fittedMeasurements.map { it.first }, point, tolerance)?.id
                }
            }
            DrawingMode.Eraser -> {
                val strokes = visibleMarks.filter { it.kind == PlanMarkKind.INK || it.kind == PlanMarkKind.HIGHLIGHT }
                markAt(strokes, point, radius)?.let(::deleteMark)
            }
            DrawingMode.Text -> {
                val editableTextMarks = visibleMarks.filter {
                    it.kind == PlanMarkKind.TEXT || it.kind == PlanMarkKind.CALLOUT ||
                        it.kind == PlanMarkKind.CLOUD || it.kind == PlanMarkKind.CLOUD_CALLOUT
                }
                val hit = markAt(editableTextMarks, point, radius * .65)
                if (hit != null) {
                    clearSelection()
                    selectedMarkId = hit.id
                    editText = true
                } else {
                    apply(EditorActions.tap(session, drawing, point, radius, activeToolStyle))
                }
            }
            DrawingMode.Photo -> {
                val hit = markAt(visibleMarks.filter { it.kind == PlanMarkKind.PHOTO }, point, radius * .85)
                if (hit != null) session = session.open(EditorDialog.ViewPhoto(hit.id))
                else apply(EditorActions.tap(session, drawing, point, radius, activeToolStyle))
            }
            DrawingMode.DetailLink -> {
                val hit = markAt(visibleMarks.filter { it.kind == PlanMarkKind.DETAIL_LINK }, point, radius * .85)
                if (hit != null) {
                    val targetDrawingId = hit.targetDrawingId ?: drawing.id
                    val targetPage = hit.targetPage ?: page
                    clearSelection()
                    onJumpToSheet(targetDrawingId, targetPage, page)
                } else {
                    apply(EditorActions.tap(session, drawing, point, radius, activeToolStyle))
                }
            }
            DrawingMode.Symbol -> {
                val current = symbol
                if (current == null) session = session.open(EditorDialog.PickSymbol)
                else addMark(
                    PlanMark(
                        page = page,
                        kind = PlanMarkKind.SYMBOL,
                        points = listOf(point),
                        svg = current.svg,
                        text = current.name.take(2_000),
                        width = 36.0,
                        circuitTag = circuitTag,
                        areaTag = areaTag,
                    ),
                )
            }
            DrawingMode.Route -> if (!routeFinished) apply(EditorActions.tap(session, drawing, point, radius, activeToolStyle))
            else -> apply(EditorActions.tap(session, drawing, point, radius, activeToolStyle))
        }
    }

    fun writeExport(uri: Uri?, pdf: Boolean) {
        if (uri == null) return
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                        if (pdf) {
                            PlanExports.writePdf(context, drawing, stream, { quantity -> settings.format(quantity) }) { runId ->
                                project.runs.firstOrNull { it.id == runId }?.name ?: "Run"
                            }
                        }
                        else PlanExports.writeJson(drawing, stream)
                    } ?: error("Could not open the export destination.")
                }
                host.showMessage(if (pdf) "Annotated PDF saved." else "Drawing JSON saved.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                host.showMessage(error.message ?: "Could not save the drawing.")
            } finally {
                busy = false
            }
        }
    }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { writeExport(it, true) }
    val json = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { writeExport(it, false) }
    val baseName = drawing.name.substringBeforeLast('.').ifBlank { "Drawing" }

    fun exportSheet(request: SheetExportRequest, uri: Uri? = null, share: Boolean = false) {
        busy = true
        scope.launch {
            try {
                val chooser = PdfWork.render {
                    val write: (java.io.OutputStream) -> Unit = { stream ->
                        PlanExports.writeSheetPdf(context, drawing, request.source, request.crop, stream,
                            { quantity -> settings.format(quantity) }) { runId ->
                            project.runs.firstOrNull { it.id == runId }?.name ?: "Run"
                        }
                    }
                    if (share) {
                        ProjectSharing.shareFileIntent(context, request.fileName, "application/pdf",
                            request.source.label, drawing.name, write)
                    } else {
                        context.contentResolver.openOutputStream(checkNotNull(uri), "wt")?.use(write)
                            ?: error("Could not open the export destination.")
                        null
                    }
                }
                if (chooser != null) context.startActivity(chooser)
                else host.showMessage("Annotated PDF saved.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                host.showMessage(error.message ?: "Could not export this sheet.")
            } finally {
                busy = false
            }
        }
    }
    val saveSheet = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val request = pendingExport
        pendingExport = null
        if (uri != null && request != null) exportSheet(request, uri)
    }

    Column(modifier.fillMaxSize()) {
        val count = pageCount
        if (count == null) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(12.dp))
            return@Column
        }
        if (count <= 0) {
            Text("This drawing could not be opened. Check that its source PDF is available.", Modifier.padding(16.dp))
            return@Column
        }
        if (page >= count) {
            // A revised PDF with fewer pages: fall back to the last one.
            LaunchedEffect(count) { session = session.goToPage(count - 1) }
            return@Column
        }
        fun goToPage(target: Int) {
            clearSelection()
            routeFinished = false
            session = session.goToPage(target.coerceIn(0, count - 1))
            sheetsOpen = false
        }
        val fallbackId = (customLabels[page] ?: labels[page])?.number?.takeIf(String::isNotBlank) ?: "Page ${page + 1}"
        val source = drawing.sheetSource(page, fallbackId)
        val identity = drawing.sheetRevisions[page]
        val revisionStatus = remember(project.drawings, drawing, page) { project.sheetRevisionStatus(drawing, page) }
        EditorHeader(
            sheetTitle = source.sheetId,
            pageCounter = "${page + 1}/$count",
            onSheets = { sheetsOpen = true },
            canUndo = editable && history.canUndo,
            canRedo = editable && history.canRedo,
            onUndo = { clearSelection(); undo() },
            onRedo = { clearSelection(); redo() },
            onSettings = { settingsOpen = true },
        ) {
            EditorOptions(
                editable = editable,
                busy = busy,
                showCalibration = prefs.showCalibrationLayer,
                scale = docSummary.status,
                onMode = { switchMode(it) },
                onScale = { session = session.open(EditorDialog.Calibration) },
                onRotate = { rotation = (rotation + 1) % 4 },
                onToggleCalibration = { DrawingsSettings.setShowCalibrationLayer(prefsStore, !prefs.showCalibrationLayer) },
                onTakeoff = { session = session.open(EditorDialog.Takeoff) },
                onPageItems = { session = session.open(EditorDialog.PageItems) },
                onExportSheet = { sheetExportOpen = true },
                onExportPdf = { pdf.launch("$baseName annotated.pdf") },
                onExportJson = { json.launch("$baseName.drawing.json") },
                highlightsVisible = highlightsVisible,
                onToggleHighlights = { highlightsVisible = !highlightsVisible; clearSelection() },
            )
        }
        val statusZone = zone?.takeIf { session.selectedZoneId == it.id }
        val activeScaleStatus = statusZone?.let { zoneStatus[it.id] } ?: docSummary.status
        val activeScaleLabel = if (statusZone != null) {
            "${statusZone.name} · ${activeScaleStatus.label}"
        } else {
            docSummary.status.label
        }
        EditorStatusBar(
            revisionLabel = identity?.let { "Rev ${it.revision} · ${revisionStatus?.label.orEmpty()}" } ?: "Rev unassigned",
            superseded = revisionStatus == com.thedirepack.constructioncalc.core.model.SheetRevisionStatus.SUPERSEDED,
            onRevision = { revisionOpen = true },
            scaleLabel = activeScaleLabel,
            scaleStatus = activeScaleStatus,
            onScale = { session = session.open(EditorDialog.Calibration) },
            snapEnabled = if (mode == DrawingMode.Reference) prefs.snapCalibration else prefs.snapMeasurements,
            snapTarget = if (mode == DrawingMode.Reference) prefs.calibrationTarget else prefs.measurementTarget,
            onSnapSelect = { enabled, target ->
                if (mode == DrawingMode.Reference) {
                    DrawingsSettings.setSnapCalibration(prefsStore, enabled)
                    DrawingsSettings.setCalibrationTarget(prefsStore, target)
                } else {
                    DrawingsSettings.setSnapMeasurements(prefsStore, enabled)
                    DrawingsSettings.setMeasurementTarget(prefsStore, target)
                }
            },
            stylusOnlyDraw = prefs.stylusOnlyDraw,
            stylusPressure = prefs.stylusPressure,
            stylusBarrelAction = prefs.stylusBarrelAction,
            twoFingerUndo = prefs.twoFingerUndo,
            onToggleStylusOnly = { DrawingsSettings.setStylusOnlyDraw(prefsStore, it) },
            onToggleStylusPressure = { DrawingsSettings.setStylusPressure(prefsStore, it) },
            onStylusBarrelAction = { DrawingsSettings.setStylusBarrelAction(prefsStore, it) },
            onToggleTwoFingerUndo = { DrawingsSettings.setTwoFingerUndo(prefsStore, it) },
            returnLabel = returnLabel,
            onReturn = onReturnToSheet,
        )
        if (revisionOpen) {
            SheetRevisionDialog(
                fallbackId = fallbackId,
                current = identity,
                status = revisionStatus,
                editable = editable,
                candidates = { id -> project.issuedSheets(id).filter { it.drawing.id != drawing.id || it.page != page } },
                onSave = { updated ->
                    onChange { current -> current.copy(sheetRevisions = current.sheetRevisions + (page to updated)) }
                    revisionOpen = false
                },
                onCompare = { selected -> revisionOpen = false; comparison = selected },
                onDismiss = { revisionOpen = false },
            )
        }
        comparison?.let { selected ->
            RevisionOverlayDialog(file, page, source.label, selected, files, onDismiss = { comparison = null })
        }
        if (sheetExportOpen) {
            val crop = remember { cropProvider?.invoke() }
            fun request(region: PlanExportCrop?, share: Boolean) {
                sheetExportOpen = false
                val request = SheetExportRequest(source, region)
                if (share) exportSheet(request, share = true)
                else {
                    pendingExport = request
                    saveSheet.launch(request.fileName)
                }
            }
            AlertDialog(
                onDismissRequest = { sheetExportOpen = false },
                title = { Text(source.label) },
                text = {
                    Column {
                        TextButton(onClick = { request(null, false) }) { Text("Save annotated sheet") }
                        TextButton(onClick = { request(null, true) }) { Text("Share annotated sheet") }
                        if (crop != null) {
                            TextButton(onClick = { request(crop, false) }) { Text("Save current crop") }
                            TextButton(onClick = { request(crop, true) }) { Text("Share current crop") }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { sheetExportOpen = false }) { Text("Close") } },
            )
        }
        if (sheetsOpen) {
            sheetBrowserState.SaveableStateProvider("${drawing.id}/${drawing.sha256}") {
                SheetBrowser(
                    file = file,
                    drawingSha256 = drawing.sha256,
                    drawingName = drawing.name,
                    projectName = project.name,
                    page = page,
                    count = count,
                    labels = labels.toMap(),
                    customLabels = customLabels,
                    indexing = indexing,
                    visits = visits,
                    onFavorite = { target ->
                        visits = visits.toggleFavorite(target)
                        sheetStore.saveFavorites(visits)
                    },
                    onLabel = { target, label ->
                        customLabels = if (label == null) customLabels - target else customLabels + (target to label)
                        sheetStore.saveLabel(target, label)
                    },
                    onDismiss = { sheetsOpen = false },
                    onPage = { goToPage(it) },
                )
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val landscape = maxWidth > maxHeight && maxWidth >= 560.dp
            val toolColors = remember(styleVersion, mode) {
                buildMap {
                    put(DrawingMode.Pen, styleStore.read(DrawingMode.Pen).color)
                    put(DrawingMode.Highlight, styleStore.read(DrawingMode.Highlight).color)
                    if (mode.supportsStyle()) put(mode, styleStore.read(mode).color)
                }
            }
            val strip: @Composable () -> Unit = {
                if (editable) {
                    EditorTools(mode, landscape, toolColors) { switchMode(it) }
                }
            }
            val canvas: @Composable (Modifier) -> Unit = { canvasModifier ->
                Box(canvasModifier) {
                    PlanCanvas(
                        file = file,
                        pageIndex = page,
                        drawingId = drawing.id,
                        quarterTurns = rotation,
                        mode = mode,
                        zones = pageZones,
                        selectedZone = zone?.id,
                        lines = displayedLines,
                        marks = visibleMarks,
                        pending = session.firstPoint,
                        lineMap = lineMap,
                        snapEnabled = snapEnabled,
                        snapTarget = snapTarget,
                        onPageSize = { pageSize = it },
                        onCropProvider = { cropProvider = it },
                        onPlace = { p, r -> place(p, r) },
                        onLine = { first, second -> apply(EditorActions.span(session, drawing, first, second, activeToolStyle)) },
                        onInk = { points ->
                            val raw = PlanMark(
                                page = page,
                                kind = if (mode == DrawingMode.Highlight) PlanMarkKind.HIGHLIGHT else PlanMarkKind.INK,
                                points = points.map { it.point },
                                pressures = points.map { it.pressure },
                            )
                            addMark(activeToolStyle?.applyTo(raw) ?: raw)
                        },
                        routePoints = routePoints,
                        routeLegLabels = routeLegLabels,
                        routeCornerLabels = routeCornerLabels,
                        savedRoutes = savedRoutes,
                        savedPaths = pagePaths,
                        selectedPathId = selectedPath?.path?.id,
                        selectedPathVertex = selectedVertex,
                        selectedPathEdge = selectedEdge,
                        handles = handles,
                        selectedMark = selectedMark,
                        onMoveMark = { replaceMark(it) },
                        onMoveEndpoint = { handle, point ->
                            val target = handle.target
                            if (target is EndpointTarget.StagedReference) {
                                val draft = calibrationDraft ?: return@PlanCanvas
                                val item = draft.firstOrNull { it.id == target.id } ?: return@PlanCanvas
                                val first = if (handle.end == 0) point else item.ref.first
                                val second = if (handle.end == 1) point else item.ref.second
                                if (first == second) return@PlanCanvas
                                val targetZone = item.zoneId?.let { id -> drawing.zones.firstOrNull { it.id == id } }
                                if (targetZone != null && (!targetZone.contains(first) || !targetZone.contains(second))) {
                                    host.showMessage("Keep the dimension inside “${targetZone.name}”.")
                                    return@PlanCanvas
                                }
                                if (targetZone == null && drawing.zones.any { it.page == page && (it.contains(first) || it.contains(second)) }) {
                                    host.showMessage("A document dimension can't reach into an override zone.")
                                    return@PlanCanvas
                                }
                                val updatedRef = item.ref.copy(first = first, second = second)
                                calibrationDraft = draft.map { if (it.id == target.id) it.copy(ref = updatedRef) else it }
                            } else {
                                if (target is EndpointTarget.Path) {
                                    selectedVertex = handle.end
                                    selectedEdge = null
                                }
                                apply(EditorActions.moveEndpoint(session, drawing, handle, point))
                            }
                        },
                        activeStyle = activeToolStyle,
                        eraserMode = eraserMode,
                        onPartialErase = { eraserPoints, radiusPt ->
                            val updated = drawing.marks.flatMap { m ->
                                if (m.page == page && (m.kind == PlanMarkKind.INK || m.kind == PlanMarkKind.HIGHLIGHT) &&
                                    (highlightsVisible || m.kind != PlanMarkKind.HIGHLIGHT)
                                ) {
                                    eraseStrokePartially(m, eraserPoints, radiusPt)
                                } else {
                                    listOf(m)
                                }
                            }
                            if (updated != drawing.marks) {
                                editDrawing { it.copy(marks = updated) }
                            }
                        },
                        dimensionLabel = formatDimension,
                        pageCount = count,
                        onPageStep = { goToPage(it) },
                        onOpenSheets = { sheetsOpen = true },
                        editable = editable,
                        stylusOnlyDraw = prefs.stylusOnlyDraw,
                        stylusPressure = prefs.stylusPressure,
                        stylusBarrelAction = prefs.stylusBarrelAction,
                        stylusHoverPreview = prefs.stylusHoverPreview,
                        twoFingerUndo = prefs.twoFingerUndo,
                        canUndo = editable && history.canUndo,
                        canRedo = editable && history.canRedo,
                        onUndo = { clearSelection(); undo() },
                        onRedo = { clearSelection(); redo() },
                        onWholeEraseTap = { point, radius ->
                            val strokes = visibleMarks.filter { it.kind == PlanMarkKind.INK || it.kind == PlanMarkKind.HIGHLIGHT }
                            markAt(strokes, point, radius)?.let(::deleteMark)
                        },
                        onDoubleTapMark = { mark ->
                            when (mark.kind) {
                                PlanMarkKind.DETAIL_LINK -> {
                                    val targetDrawingId = mark.targetDrawingId ?: drawing.id
                                    val targetPage = mark.targetPage ?: page
                                    clearSelection()
                                    onJumpToSheet(targetDrawingId, targetPage, page)
                                }
                                PlanMarkKind.PHOTO -> session = session.open(EditorDialog.ViewPhoto(mark.id))
                                PlanMarkKind.TEXT, PlanMarkKind.CALLOUT, PlanMarkKind.CLOUD, PlanMarkKind.CLOUD_CALLOUT, PlanMarkKind.DIMENSION -> {
                                    clearSelection()
                                    selectedMarkId = mark.id
                                    editText = true
                                }
                                else -> Unit
                            }
                        },
                        spanInputMode = prefs.spanInputModeFor(mode),
                        eraserSizeDp = prefs.eraserSizeDp,
                        liveMeasureLabel = { a, b ->
                            if (draftDrawing.crossesZoneBoundary(page, a, b)) null
                            else draftDrawing.calibrationFor(page, a, b)?.let { fit -> "≈ ${settings.format(fit.distance(a, b))}" }
                        },
                        onTapStagedRef = { stagedId -> session = session.open(EditorDialog.EditStagedReference(stagedId)) },
                        modifier = Modifier.fillMaxSize(),
                    )
                    val selectedMeasurement = fittedMeasurements.firstOrNull { it.first.id == selectedMeasurementId }
                    val latest = fittedMeasurements.lastOrNull().takeIf { mode == DrawingMode.Measure }
                    val result = selectedMeasurement ?: latest
                    val hasActions = selectedMark != null || selectedPath != null || result != null ||
                        routePoints.isNotEmpty() || session.firstPoint != null ||
                        mode == DrawingMode.Reference || mode == DrawingMode.Zone || mode == DrawingMode.Measure
                    if (hasActions || mode == DrawingMode.Route || mode == DrawingMode.Eraser || (mode == DrawingMode.Symbol && symbol != null) || activeToolStyle != null) {
                        Surface(
                            tonalElevation = 4.dp,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.align(Alignment.TopStart).padding(6.dp).sizeIn(maxWidth = 370.dp),
                        ) {
                            Column(Modifier.padding(horizontal = 6.dp)) {
                                when {
                                    selectedMark != null -> Row(
                                        Modifier.horizontalScroll(rememberScrollState()),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (selectedMark.kind == PlanMarkKind.DETAIL_LINK) {
                                            TextButton(
                                                onClick = {
                                                    val targetDrawingId = selectedMark.targetDrawingId ?: drawing.id
                                                    val targetPage = selectedMark.targetPage ?: page
                                                    clearSelection()
                                                    onJumpToSheet(targetDrawingId, targetPage, page)
                                                },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            ) {
                                                Icon(painterResource(R.drawable.ic_plan_open_sheet), contentDescription = null, Modifier.size(16.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("Open sheet")
                                            }
                                            ToolTooltipIconButton(
                                                label = "Edit link",
                                                iconRes = CoreR.drawable.ic_edit,
                                                onClick = { session = session.open(EditorDialog.EditDetailLink(selectedMark.id)) },
                                            )
                                        }
                                        if (selectedMark.kind == PlanMarkKind.PHOTO) {
                                            TextButton(
                                                onClick = { session = session.open(EditorDialog.ViewPhoto(selectedMark.id)) },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            ) {
                                                Icon(painterResource(R.drawable.ic_plan_tool_photo), contentDescription = null, Modifier.size(16.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("View photo")
                                            }
                                            ToolTooltipIconButton(
                                                label = "Edit caption",
                                                iconRes = CoreR.drawable.ic_edit,
                                                onClick = { editText = true },
                                            )
                                        }
                                        if (selectedMark.kind in listOf(PlanMarkKind.TEXT, PlanMarkKind.CALLOUT, PlanMarkKind.CLOUD, PlanMarkKind.CLOUD_CALLOUT, PlanMarkKind.DIMENSION)) {
                                            ToolTooltipIconButton(
                                                label = if (selectedMark.kind == PlanMarkKind.DIMENSION) "Edit label" else "Edit text",
                                                iconRes = CoreR.drawable.ic_edit,
                                                onClick = { editText = true },
                                            )
                                        }
                                        if (selectedMark.kind == PlanMarkKind.SYMBOL) {
                                            TextButton(onClick = { editMarkCircuit = true }) {
                                                Text(selectedMark.circuitTag.ifBlank { "Circuit" })
                                            }
                                            TextButton(onClick = { editMarkArea = true }) {
                                                Text(selectedMark.areaTag.ifBlank { "Area" })
                                            }
                                        }
                                        if (selectedMark.kind.supportsStyle()) {
                                            val markStyle = MarkupToolStyle.fromMark(selectedMark)
                                            ToolTooltipIconButton(
                                                label = "Markup style",
                                                iconRes = R.drawable.ic_plan_palette,
                                                badgeDotColor = markStyle.color,
                                                onClick = { session = session.open(EditorDialog.EditMarkStyle(selectedMark.id)) },
                                            )
                                        }
                                        ToolTooltipIconButton(
                                            label = "Duplicate mark",
                                            iconRes = R.drawable.ic_plan_duplicate,
                                            onClick = {
                                                val duplicate = selectedMark.copy(id = newId()).move(12.0, 12.0)
                                                addMark(duplicate)
                                                selectedMarkId = duplicate.id
                                            },
                                        )
                                        ToolTooltipIconButton(
                                            label = "Delete mark",
                                            iconRes = CoreR.drawable.ic_delete,
                                            tint = MaterialTheme.colorScheme.error,
                                            onClick = { deleteMark(selectedMark) },
                                        )
                                    }
                                    selectedPath != null -> {
                                        val path = selectedPath.path
                                        val edge = selectedEdge
                                        val vertex = selectedVertex
                                        Text(selectedPath.label, Modifier.padding(8.dp), style = MaterialTheme.typography.labelLarge)
                                        Text("Tap an edge or vertex to edit. Drag a vertex to move it.",
                                            Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelSmall)
                                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                                            TextButton(enabled = edge != null && path.points.size < PlanPath.MAX_VERTICES, onClick = {
                                                if (edge != null) {
                                                    val a = path.points[edge]
                                                    val b = path.edgeEnd(edge)
                                                    val next = path.insertVertex(edge, PlanPoint((a.x + b.x) / 2, (a.y + b.y) / 2))
                                                    replacePath(path, next)
                                                    if (next != null) {
                                                        selectedVertex = edge + 1
                                                        selectedEdge = null
                                                    }
                                                }
                                            }) { Text("Add vertex") }
                                            TextButton(enabled = vertex != null && path.points.size > (if (path.closed) 3 else 2), onClick = {
                                                if (vertex != null) {
                                                    val next = path.removeVertex(vertex)
                                                    replacePath(path, next)
                                                    if (next != null) selectedVertex = null
                                                }
                                            }) { Text("Remove vertex") }
                                            TextButton(onClick = {
                                                editDrawing { d -> d.copy(paths = d.paths.filterNot { it.id == path.id }) }
                                                clearSelection()
                                            }) { Text("Delete") }
                                        }
                                    }
                                    mode == DrawingMode.Symbol && symbol != null -> {
                                        val active = checkNotNull(symbol)
                                        val symbolMarks = marks.filter { it.kind == PlanMarkKind.SYMBOL && it.text == active.name }
                                        val groupTotal = when (countGrouping) {
                                            CountGrouping.DEVICE -> symbolMarks.size
                                            CountGrouping.CIRCUIT -> symbolMarks.count { it.circuitTag == circuitTag }
                                            CountGrouping.AREA -> symbolMarks.count { it.areaTag == areaTag }
                                        }
                                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                                            TextButton(onClick = { session = session.open(EditorDialog.PickSymbol) }) {
                                                Text("${active.name} · $groupTotal  ▾")
                                            }
                                            TextButton(onClick = {
                                                val nextIndex = (CountGrouping.entries.indexOf(countGrouping) + 1) % CountGrouping.entries.size
                                                countGrouping = CountGrouping.entries[nextIndex]
                                            }) { Text("By: ${countGrouping.label}") }
                                            TextButton(onClick = { editCircuit = true }) { Text(circuitTag.ifBlank { "Circuit" }) }
                                            TextButton(onClick = { editArea = true }) { Text(areaTag.ifBlank { "Area" }) }
                                            TextButton(onClick = { session = session.open(EditorDialog.Takeoff) }) { Text("Totals") }
                                        }
                                    }
                                    mode == DrawingMode.Eraser -> Row(
                                        Modifier.horizontalScroll(rememberScrollState()),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        for (option in EraserMode.entries) {
                                            TextButton(onClick = { eraserMode = option }) {
                                                Text(if (eraserMode == option) "• ${option.label}" else option.label)
                                            }
                                        }
                                        TextButton(onClick = { eraserSizeOpen = true }) {
                                            Text("Size ${prefs.eraserSizeDp}dp")
                                        }
                                        ToolTooltipIconButton(
                                            label = if (highlightsVisible) "Hide highlights" else "Show highlights",
                                            iconRes = if (highlightsVisible) R.drawable.ic_plan_visibility else R.drawable.ic_plan_visibility_off,
                                            selected = !highlightsVisible,
                                            onClick = { highlightsVisible = !highlightsVisible; clearSelection() },
                                        )
                                    }
                                    mode == DrawingMode.Reference -> {
                                        val draft = calibrationDraft.orEmpty()
                                        val previewSummary = remember(draftDrawing.calibration, page) {
                                            CalibrationDiagnostics.evaluate(draftDrawing.calibration, page)
                                        }
                                        val statusText = when {
                                            session.firstPoint != null -> "Calibrate · Point 2 (or drag handles)"
                                            draft.isEmpty() -> "Calibrate · Place a known dimension"
                                            else -> "Calibrate · ${draft.size} dim(s) · ${previewSummary.status.label}"
                                        }
                                        Text(
                                            statusText,
                                            Modifier.padding(start = 8.dp, top = 6.dp, end = 8.dp),
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                        Row(
                                            Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            if (session.firstPoint != null) {
                                                TextButton(onClick = { session = session.copy(op = EditOp.Idle) }) { Text("Clear pt") }
                                            }
                                            TextButton(
                                                onClick = { session = session.open(EditorDialog.StagedCalibrationList) },
                                                enabled = draft.isNotEmpty(),
                                            ) { Text("Dims (${draft.size})") }
                                            val refSpanMode = prefs.spanInputModeFor(DrawingMode.Reference)
                                            TextButton(onClick = {
                                                val next = if (refSpanMode == SpanInputMode.POINTS_ON_RELEASE) SpanInputMode.DRAG_ACROSS else SpanInputMode.POINTS_ON_RELEASE
                                                DrawingsSettings.setSpanInputMode(prefsStore, DrawingMode.Reference, next)
                                            }) { Text(refSpanMode.shortLabel) }
                                            FilledTonalButton(
                                                onClick = {
                                                    val committed = applyDraftToDrawing(draft)
                                                    editDrawing {
                                                        it.copy(calibration = committed.calibration, zones = committed.zones)
                                                    }
                                                    DrawingsSettings.setShowCalibrationLayer(prefsStore, true)
                                                    val finalStatus = CalibrationDiagnostics.evaluate(committed.calibration, page).status.label
                                                    host.showMessage("Calibration applied · $finalStatus (${draft.size} dim(s))")
                                                    calibrationDraft = null
                                                    switchMode(DrawingMode.Adjust)
                                                },
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                            ) { Text("Submit") }
                                            TextButton(onClick = {
                                                calibrationDraft = null
                                                switchMode(DrawingMode.Adjust)
                                            }) { Text("Cancel") }
                                        }
                                    }
                                    mode == DrawingMode.Zone -> Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            if (session.firstPoint != null) "Tap the opposite zone corner."
                                            else "Tap or drag opposite zone corners.",
                                            Modifier.padding(8.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                        TextButton(onClick = { switchMode(DrawingMode.Adjust) }) { Text("Cancel") }
                                    }
                                    mode == DrawingMode.Route && routePoints.isEmpty() -> {
                                        TextButton(onClick = { editCircuit = true }) { Text(circuitTag.ifBlank { "Circuit" }) }
                                    }
                                    routePoints.isNotEmpty() -> RouteControls(
                                        routeSummary = routeSummary,
                                        scale = routePoints.getOrNull(1)?.let { scaleFor(routePoints.first(), it) },
                                        finished = routeFinished,
                                        onUndoPoint = { session = session.withRoute(routePoints.dropLast(1)); routeFinished = false },
                                        onFinish = { routeFinished = true },
                                        onContinue = { routeFinished = false },
                                        onSaveLegs = { summary ->
                                            val legs = summary.legs.map { leg ->
                                                PlanMeasurement(page = page, zoneId = leg.zoneId, first = leg.from, second = leg.to)
                                            }
                                            editDrawing { it.copy(measurements = it.measurements + legs) }
                                            session = session.withRoute(emptyList())
                                            routeFinished = false
                                            host.showMessage("Saved ${legs.size} leg(s) as measurements.")
                                        },
                                        onSavePath = { saveTrace(it) },
                                        vertexCount = routePoints.size,
                                        onSendRoute = { session = session.open(EditorDialog.SendRoute) },
                                    )
                                    session.firstPoint != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                                        val isTwoPt = prefs.spanInputModeFor(mode) == SpanInputMode.POINTS_ON_RELEASE
                                        Text(
                                            when (mode) {
                                                DrawingMode.Callout -> "Place the text box."
                                                DrawingMode.CloudCallout -> "Tap the opposite cloud corner."
                                                else -> if (isTwoPt) "Aim & release point 2." else "Tap the second point."
                                            },
                                            Modifier.padding(8.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                        TextButton(onClick = { session = session.copy(op = EditOp.Idle) }) { Text("Clear") }
                                        if (activeToolStyle != null) {
                                            TextButton(onClick = { session = session.open(EditorDialog.EditMarkStyle(null)) }) {
                                                Text("Style")
                                            }
                                        }
                                    }
                                    result != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                                        val (measurement, length) = result
                                        TextButton(onClick = {
                                            if (selectedMeasurement != null || length == null) {
                                                session = session.open(EditorDialog.EditMeasurement(measurement.id))
                                            } else publish(measurement, length)
                                        }) {
                                            val value = length?.let { "≈ ${settings.format(it)}" } ?: "Uncalibrated"
                                            Column {
                                                val action = if (selectedMeasurement != null || length == null) "Edit" else "Use"
                                                Text("$value · $action", style = MaterialTheme.typography.labelLarge)
                                                if (length != null) Text(scaleFor(measurement.first, measurement.second), style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                        if (mode == DrawingMode.Measure) {
                                            val mSpan = prefs.spanInputModeFor(DrawingMode.Measure)
                                            TextButton(onClick = {
                                                val next = if (mSpan == SpanInputMode.POINTS_ON_RELEASE) SpanInputMode.DRAG_ACROSS else SpanInputMode.POINTS_ON_RELEASE
                                                DrawingsSettings.setSpanInputMode(prefsStore, DrawingMode.Measure, next)
                                            }) { Text(mSpan.shortLabel) }
                                        }
                                    }
                                    mode == DrawingMode.Measure -> Row(verticalAlignment = Alignment.CenterVertically) {
                                        val mSpan = prefs.spanInputModeFor(DrawingMode.Measure)
                                        TextButton(onClick = { session = session.open(EditorDialog.Calibration) }) {
                                            Text("Scale · ${docSummary.status.label}")
                                        }
                                        TextButton(onClick = {
                                            val next = if (mSpan == SpanInputMode.POINTS_ON_RELEASE) SpanInputMode.DRAG_ACROSS else SpanInputMode.POINTS_ON_RELEASE
                                            DrawingsSettings.setSpanInputMode(prefsStore, DrawingMode.Measure, next)
                                        }) { Text(mSpan.shortLabel) }
                                    }
                                    activeToolStyle != null -> Row(
                                        Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        val quickSwatches = if (mode == DrawingMode.Highlight) {
                                            listOf(
                                                MarkupColorOption("Yellow", 0xFFFFE43CL),
                                                MarkupColorOption("Green", 0xFF2E7D32L),
                                                MarkupColorOption("Blue", 0xFF155CBDL),
                                                MarkupColorOption("Red", 0xFFC62828L),
                                                MarkupColorOption("Orange", 0xFFE65100L),
                                            )
                                        } else {
                                            listOf(
                                                MarkupColorOption("Blue", 0xFF155CBDL),
                                                MarkupColorOption("Red", 0xFFC62828L),
                                                MarkupColorOption("Green", 0xFF2E7D32L),
                                                MarkupColorOption("Orange", 0xFFE65100L),
                                                MarkupColorOption("Black", 0xFF111111L),
                                            )
                                        }
                                        for (swatch in quickSwatches) {
                                            val isSelectedColor = (activeToolStyle.color and 0xFFFFFFL) == (swatch.argb and 0xFFFFFFL)
                                            val ringColor = if (isSelectedColor) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                            Box(
                                                Modifier
                                                    .size(26.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(swatch.argb.toInt()))
                                                    .border(if (isSelectedColor) 2.5.dp else 1.dp, ringColor, CircleShape)
                                                    .clickable {
                                                        styleStore.save(mode, activeToolStyle.copy(color = swatch.argb))
                                                        styleVersion++
                                                    }
                                                    .semantics { contentDescription = "Color ${swatch.name}" },
                                            )
                                        }
                                        TextButton(
                                            onClick = { session = session.open(EditorDialog.EditMarkStyle(null)) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        ) {
                                            Icon(painterResource(R.drawable.ic_plan_palette), contentDescription = "More style options", Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("${activeToolStyle.width.roundToInt()}pt")
                                        }
                                        if (mode == DrawingMode.Highlight) {
                                            ToolTooltipIconButton(
                                                label = if (highlightsVisible) "Hide highlights" else "Show highlights",
                                                iconRes = if (highlightsVisible) R.drawable.ic_plan_visibility else R.drawable.ic_plan_visibility_off,
                                                selected = !highlightsVisible,
                                                onClick = { highlightsVisible = !highlightsVisible; clearSelection() },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (selectedMark != null && editText) {
                        key(selectedMark.id) {
                            val isOptionalText = selectedMark.kind == PlanMarkKind.DIMENSION || selectedMark.kind == PlanMarkKind.PHOTO || selectedMark.kind == PlanMarkKind.CLOUD
                            val textDialogTitle = when (selectedMark.kind) {
                                PlanMarkKind.DIMENSION -> "Dimension label"
                                PlanMarkKind.PHOTO -> "Photo caption"
                                PlanMarkKind.CLOUD, PlanMarkKind.CLOUD_CALLOUT -> "Cloud note"
                                PlanMarkKind.CALLOUT -> "Arrow callout text"
                                else -> "Edit text"
                            }
                            TextMarkupDialog(
                                onDismiss = { editText = false },
                                initial = selectedMark.text,
                                title = textDialogTitle,
                                label = if (selectedMark.kind == PlanMarkKind.DIMENSION || selectedMark.kind == PlanMarkKind.PHOTO) "Label" else "Text",
                                helperText = if (selectedMark.kind == PlanMarkKind.DIMENSION) {
                                    "Measured: ${formatDimension(selectedMark)}. Enter an optional prefix label:"
                                } else null,
                                allowEmpty = isOptionalText,
                            ) { text ->
                                replaceMark(selectedMark.copy(text = text))
                                editText = false
                            }
                        }
                    }
                }
            }
            if (landscape) {
                Row(Modifier.fillMaxSize()) { canvas(Modifier.weight(1f)); strip() }
            } else {
                Column(Modifier.fillMaxSize()) { canvas(Modifier.weight(1f)); strip() }
            }
        }
    }

    if (settingsOpen) DrawingSettingsDialog { settingsOpen = false }
    if (eraserSizeOpen) {
        EraserSizeDialog(
            initialDp = prefs.eraserSizeDp,
            onDismiss = { eraserSizeOpen = false },
            onConfirm = { dp ->
                DrawingsSettings.setEraserSizeDp(prefsStore, dp)
                eraserSizeOpen = false
            },
        )
    }

    if (editMarkCircuit && selectedMark != null) {
        CircuitTagDialog(selectedMark.circuitTag, onDismiss = { editMarkCircuit = false }) { tag ->
            replaceMark(selectedMark.copy(circuitTag = tag))
            editMarkCircuit = false
        }
    }

    if (editMarkArea && selectedMark != null) {
        AreaTagDialog(selectedMark.areaTag, onDismiss = { editMarkArea = false }) { tag ->
            replaceMark(selectedMark.copy(areaTag = tag))
            editMarkArea = false
        }
    }

    if (editCircuit) {
        CircuitTagDialog(circuitTag, onDismiss = { editCircuit = false }) { tag ->
            circuitTag = tag
            views.edit().putString("${drawing.id}/circuit", tag).apply()
            editCircuit = false
        }
    }

    if (editArea) {
        AreaTagDialog(areaTag, onDismiss = { editArea = false }) { tag ->
            areaTag = tag
            views.edit().putString("${drawing.id}/area", tag).apply()
            editArea = false
        }
    }

    EditorDialogs(
        circuitTag = circuitTag,
        session = session,
        project = project,
        drawing = drawing,
        previewDrawing = draftDrawing,
        calibrationDraft = calibrationDraft,
        page = page,
        pageCount = pageCount ?: 1,
        labels = labels.toMap(),
        pageSize = pageSize,
        pageZones = pageZones,
        zone = zone,
        fittedMeasurements = fittedMeasurements,
        pagePaths = pagePaths,
        marks = marks,
        routeSummary = routeSummary,
        editable = editable,
        styleStore = styleStore,
        onStyleUpdated = { styleVersion++ },
        onSession = {
            if (it.page != page) clearSelection()
            if (it.op == EditOp.Idle) routeFinished = false
            session = it
        },
        onCalibrationDraftChange = { calibrationDraft = it },
        onStartCalibrating = { switchMode(DrawingMode.Reference) },
        onSubmitCalibrationDraft = { draft ->
            val committed = applyDraftToDrawing(draft)
            editDrawing { it.copy(calibration = committed.calibration, zones = committed.zones) }
            DrawingsSettings.setShowCalibrationLayer(prefsStore, true)
            val finalStatus = CalibrationDiagnostics.evaluate(committed.calibration, page).status.label
            host.showMessage("Calibration applied · $finalStatus (${draft.size} dim(s))")
            calibrationDraft = null
            switchMode(DrawingMode.Adjust)
        },
        editDrawing = { editDrawing(it) },
        addMark = { addMark(it) },
        replaceMark = { replaceMark(it) },
        deleteMark = { deleteMark(it) },
        onPickSymbol = {
            symbol = it
            views.edit().putString("${drawing.id}/symbol", it.id).apply()
        },
        onOpenSettings = { settingsOpen = true },
        onOpenMark = { mark ->
            when (mark.kind) {
                PlanMarkKind.PHOTO -> session = session.open(EditorDialog.ViewPhoto(mark.id))
                PlanMarkKind.DETAIL_LINK -> {
                    clearSelection()
                    onJumpToSheet(mark.targetDrawingId ?: drawing.id, mark.targetPage ?: page, page)
                }
                else -> Unit
            }
        },
        publish = { m, q -> publish(m, q) },
        onSendRouteToRun = onSendRouteToRun,
    )
}

@Composable
private fun RouteControls(
    routeSummary: RouteSummary?,
    scale: String?,
    finished: Boolean,
    onUndoPoint: () -> Unit,
    onFinish: () -> Unit,
    onContinue: () -> Unit,
    onSaveLegs: (RouteSummary) -> Unit,
    onSavePath: (closed: Boolean) -> Unit,
    vertexCount: Int,
    onSendRoute: () -> Unit,
) {
    val settings = rememberAppSettings()
    Text(
        routeSummary?.let { "≈ ${settings.format(it.totalHorizontalQuantity)} · ${it.corners.size} bends · ${it.totalBendDegrees.roundToInt()}°" }
            ?: "Tap the next route point",
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = 8.dp, top = 6.dp),
    )
    if (scale != null) Text(scale, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelSmall)
    if (finished) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { onSavePath(false) }) { Text("Save polyline") }
            TextButton(onClick = { onSavePath(true) }, enabled = vertexCount >= 3) { Text("Save polygon") }
        }
    }
    Row {
        if (finished && routeSummary != null) {
            TextButton(onClick = onSendRoute) { Text("Send to run") }
            TextButton(onClick = { onSaveLegs(routeSummary) }) { Text("Save legs") }
            TextButton(onClick = onContinue) { Text("Continue") }
        } else {
            TextButton(onClick = onUndoPoint) { Text("Undo point") }
            TextButton(onClick = onFinish, enabled = routeSummary != null) { Text("Finish") }
        }
    }
}

@Composable
private fun EditorDialogs(
    circuitTag: String,
    session: EditorSession,
    project: Project,
    drawing: PlanDrawing,
    previewDrawing: PlanDrawing,
    calibrationDraft: List<StagedCalibrationRef>?,
    page: Int,
    pageCount: Int,
    labels: Map<Int, SheetLabel>,
    pageSize: PageSize?,
    pageZones: List<PlanZone>,
    zone: PlanZone?,
    fittedMeasurements: List<Pair<PlanMeasurement, Quantity?>>,
    pagePaths: List<CanvasPath>,
    marks: List<PlanMark>,
    routeSummary: RouteSummary?,
    editable: Boolean,
    styleStore: MarkupStyleStore,
    onStyleUpdated: () -> Unit,
    onSession: (EditorSession) -> Unit,
    onCalibrationDraftChange: (List<StagedCalibrationRef>) -> Unit,
    onStartCalibrating: () -> Unit,
    onSubmitCalibrationDraft: (List<StagedCalibrationRef>) -> Unit,
    editDrawing: ((PlanDrawing) -> PlanDrawing) -> Unit,
    addMark: (PlanMark) -> Unit,
    replaceMark: (PlanMark) -> Unit,
    deleteMark: (PlanMark) -> Unit,
    onPickSymbol: (PlanSymbol) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMark: (PlanMark) -> Unit,
    publish: (PlanMeasurement, Quantity) -> Unit,
    onSendRouteToRun: (runId: String?, newRunName: String?, segments: List<PathSegment>, route: TracedRoute) -> Unit,
) {
    val close = { onSession(session.closeDialog()) }
    fun updateZone(updated: PlanZone) = editDrawing { d -> d.copy(zones = d.zones.map { if (it.id == updated.id) updated else it }) }
    fun deleteZone(id: String) = editDrawing { d -> d.copy(zones = d.zones.filterNot { it.id == id }) }

    when (val dialog = session.dialog) {
        null -> Unit
        is EditorDialog.NewZone -> ZoneDialog(defaultName = "Zone ${drawing.zones.size + 1}", pageSize = pageSize, onDismiss = close) { name, preset ->
            val saved = PlanZone(name = name, page = page, first = dialog.first, second = dialog.second, preset = preset)
            editDrawing { it.copy(zones = it.zones + saved) }
            onSession(
                session.withMode(if (preset == null) DrawingMode.Reference else DrawingMode.Measure)
                    .copy(selectedZoneId = saved.id),
            )
        }
        is EditorDialog.Reference -> {
            val target = previewDrawing.zones.firstOrNull { it.id == dialog.zoneId }
            val title = target?.name ?: "Document calibration (page ${page + 1})"
            val hasFit = target?.let { it.fit() != null } ?: (previewDrawing.calibration.fit(page) != null)
            ReferenceDialog(title, hasFit = hasFit, onDismiss = close) { length, check ->
                val ref = PlanReference(dialog.first, dialog.second, length, check, page = page)
                val currentDraft = calibrationDraft ?: buildList {
                    drawing.calibration.references.forEach { add(StagedCalibrationRef(zoneId = null, ref = it)) }
                    drawing.zones.forEach { z -> z.references.forEach { add(StagedCalibrationRef(zoneId = z.id, ref = it)) } }
                }
                onCalibrationDraftChange(currentDraft + StagedCalibrationRef(zoneId = dialog.zoneId, ref = ref))
                close()
            }
        }
        is EditorDialog.EditStagedReference -> {
            val currentDraft = calibrationDraft.orEmpty()
            val item = currentDraft.firstOrNull { it.id == dialog.stagedId }
            if (item == null) close()
            else {
                val target = item.zoneId?.let { id -> drawing.zones.firstOrNull { it.id == id } }
                val title = target?.name ?: "Document calibration (page ${item.ref.page + 1})"
                val hasFit = currentDraft.any { it.id != item.id && it.zoneId == item.zoneId && !it.ref.check } ||
                    (target?.preset != null || (item.zoneId == null && drawing.calibration.preset != null))
                ReferenceDialog(
                    targetName = title,
                    hasFit = hasFit,
                    initialLength = item.ref.length.toQuantity(),
                    initialCheck = item.ref.check,
                    title = "Edit calibration dimension",
                    confirmLabel = "Update",
                    onDelete = {
                        onCalibrationDraftChange(currentDraft.filterNot { it.id == item.id })
                        close()
                    },
                    onDismiss = close,
                ) { length, check ->
                    val updated = item.copy(ref = item.ref.copy(length = length, check = check))
                    onCalibrationDraftChange(currentDraft.map { if (it.id == item.id) updated else it })
                    close()
                }
            }
        }
        EditorDialog.StagedCalibrationList -> {
            val currentDraft = calibrationDraft.orEmpty()
            StagedCalibrationListDialog(
                previewDrawing = previewDrawing,
                draft = currentDraft,
                page = page,
                editable = editable,
                onEdit = { id -> onSession(session.open(EditorDialog.EditStagedReference(id))) },
                onDelete = { id -> onCalibrationDraftChange(currentDraft.filterNot { it.id == id }) },
                onSubmit = { onSubmitCalibrationDraft(currentDraft) },
                onDismiss = close,
            )
        }
        is EditorDialog.PlaceText -> TextMarkupDialog(onDismiss = close) { text ->
            val raw = PlanMark(page = page, kind = PlanMarkKind.TEXT, points = listOf(dialog.point), text = text)
            addMark(styleStore.read(DrawingMode.Text).applyTo(raw))
            close()
        }
        EditorDialog.PickSymbol -> SymbolDialog(onDismiss = close, openSettings = {
            close()
            onOpenSettings()
        }) { symbol ->
            onPickSymbol(symbol)
            close()
        }
        is EditorDialog.PlaceCallout -> TextMarkupDialog(onDismiss = close, title = "Arrow callout text") { text ->
            val raw = PlanMark(page = page, kind = PlanMarkKind.CALLOUT, points = listOf(dialog.first, dialog.second), text = text)
            addMark(styleStore.read(DrawingMode.Callout).applyTo(raw))
            close()
        }
        is EditorDialog.PlaceCloudCallout -> TextMarkupDialog(onDismiss = close, title = "Cloud note (inside cloud)") { text ->
            val raw = PlanMark(page = page, kind = PlanMarkKind.CLOUD_CALLOUT, points = listOf(dialog.first, dialog.second), text = text)
            addMark(styleStore.read(DrawingMode.CloudCallout).applyTo(raw))
            close()
        }
        is EditorDialog.PlacePhoto -> PhotoPinDialog(onDismiss = close) { sha, caption ->
            val raw = PlanMark(page = page, kind = PlanMarkKind.PHOTO, points = listOf(dialog.point), text = caption, photoSha256 = sha)
            addMark(styleStore.read(DrawingMode.Photo).applyTo(raw))
            close()
        }
        is EditorDialog.ViewPhoto -> {
            val mark = marks.firstOrNull { it.id == dialog.markId }
            if (mark == null) close()
            else PhotoViewerDialog(
                photoSha256 = mark.photoSha256,
                initialCaption = mark.text,
                editable = editable,
                onReplacePhoto = { newSha -> replaceMark(mark.copy(photoSha256 = newSha)) },
                onUpdateCaption = { caption -> replaceMark(mark.copy(text = caption)) },
                onDeletePhoto = if (editable) ({ deleteMark(mark); close() }) else null,
                onDismiss = close,
            )
        }
        is EditorDialog.PlaceDetailLink -> DetailLinkDialog(
            project = project,
            currentDrawingId = drawing.id,
            currentPage = page,
            currentPageCount = pageCount,
            currentLabels = labels,
            onDismiss = close,
        ) { targetDrawingId, targetPage, label ->
            val raw = PlanMark(
                page = page,
                kind = PlanMarkKind.DETAIL_LINK,
                points = listOf(dialog.point),
                text = label,
                targetPage = targetPage,
                targetDrawingId = targetDrawingId,
            )
            addMark(styleStore.read(DrawingMode.DetailLink).applyTo(raw))
            close()
        }
        is EditorDialog.EditDetailLink -> {
            val mark = marks.firstOrNull { it.id == dialog.markId }
            if (mark == null) close()
            else DetailLinkDialog(
                project = project,
                currentDrawingId = drawing.id,
                currentPage = page,
                currentPageCount = pageCount,
                currentLabels = labels,
                initialTargetDrawingId = mark.targetDrawingId,
                initialTargetPage = mark.targetPage,
                initialLabel = mark.text,
                onDismiss = close,
            ) { targetDrawingId, targetPage, label ->
                replaceMark(mark.copy(text = label, targetPage = targetPage, targetDrawingId = targetDrawingId))
                close()
            }
        }
        is EditorDialog.EditMarkStyle -> {
            val targetMark = dialog.markId?.let { id -> marks.firstOrNull { it.id == id } }
            if (dialog.markId != null && targetMark == null) {
                close()
            } else {
                val styleKind = targetMark?.kind ?: when (session.mode) {
                    DrawingMode.Pen -> PlanMarkKind.INK
                    DrawingMode.Highlight -> PlanMarkKind.HIGHLIGHT
                    DrawingMode.Arrow -> PlanMarkKind.ARROW
                    DrawingMode.Line -> PlanMarkKind.LINE
                    DrawingMode.Rectangle -> PlanMarkKind.RECTANGLE
                    DrawingMode.Text -> PlanMarkKind.TEXT
                    DrawingMode.Callout -> PlanMarkKind.CALLOUT
                    DrawingMode.Dimension -> PlanMarkKind.DIMENSION
                    DrawingMode.Cloud -> PlanMarkKind.CLOUD
                    DrawingMode.CloudCallout -> PlanMarkKind.CLOUD_CALLOUT
                    DrawingMode.Photo -> PlanMarkKind.PHOTO
                    DrawingMode.DetailLink -> PlanMarkKind.DETAIL_LINK
                    else -> PlanMarkKind.LINE
                }
                val initial = targetMark?.let { MarkupToolStyle.fromMark(it) } ?: styleStore.read(session.mode)
                val dialogTitle = if (targetMark != null) "Markup style" else "${session.mode.title} style"
                MarkupStyleDialog(
                    title = dialogTitle,
                    kind = styleKind,
                    initial = initial,
                    onDismiss = close,
                ) { updated ->
                    if (targetMark != null) {
                        replaceMark(updated.applyTo(targetMark))
                    } else {
                        styleStore.save(session.mode, updated)
                        onStyleUpdated()
                    }
                    close()
                }
            }
        }
        is EditorDialog.EditMeasurement -> {
            val entry = fittedMeasurements.firstOrNull { it.first.id == dialog.id }
            if (entry == null) close()
            else MeasurementDialog(
                measurement = entry.first,
                length = entry.second,
                editable = editable,
                onDismiss = close,
                onRename = { label ->
                    editDrawing { d -> d.copy(measurements = d.measurements.map { if (it.id == dialog.id) it.copy(label = label) else it }) }
                    close()
                },
                onDelete = {
                    editDrawing { d -> d.copy(measurements = d.measurements.filterNot { it.id == dialog.id }) }
                    close()
                },
                onUse = { value ->
                    publish(entry.first, value)
                    close()
                },
            )
        }
        EditorDialog.Calibration -> CalibrationDialog(
            calibration = drawing.calibration,
            page = page,
            pageSize = pageSize,
            pageZones = pageZones,
            editable = editable,
            onCalibrationChange = { updated -> editDrawing { it.copy(calibration = updated) } },
            onZoneChange = { updateZone(it) },
            onZoneDelete = { deleteZone(it) },
            onCalibrateFromLine = {
                close()
                onStartCalibrating()
            },
            onDrawScaleZone = {
                close()
                onSession(session.withMode(DrawingMode.Zone))
            },
            onDismiss = close,
        )
        EditorDialog.PageItems -> PageItemsDialog(
            zone = zone,
            measurements = fittedMeasurements,
            paths = pagePaths,
            marks = marks,
            editable = editable,
            publish = publish,
            onZoneChange = { updateZone(it) },
            onZoneDelete = { deleteZone(it) },
            onMeasureEdit = { id -> onSession(session.open(EditorDialog.EditMeasurement(id))) },
            onMeasureDelete = { id -> editDrawing { it.copy(measurements = it.measurements.filterNot { m -> m.id == id }) } },
            onPathDelete = { id -> editDrawing { it.copy(paths = it.paths.filterNot { p -> p.id == id }) } },
            onMarkOpen = onOpenMark,
            onMarkDelete = { id -> editDrawing { it.copy(marks = it.marks.filterNot { m -> m.id == id }) } },
            onDismiss = close,
        )
        EditorDialog.Takeoff -> TakeoffDialog(
            project = project,
            drawing = drawing,
            onShowRoute = { route ->
                onSession(session.goToPage(route.page).withMode(DrawingMode.Adjust).copy(dialog = null, focusedRouteId = route.id))
            },
            onDismiss = close,
        )
        EditorDialog.SendRoute -> if (routeSummary == null) close() else SendRouteToRunDialog(
            project = project,
            summary = routeSummary,
            onDismiss = close,
            onSend = { runId, newRunName, segments ->
                onSendRouteToRun(runId, newRunName, segments, TracedRoute(page, routeSummary.points, routeSummary.totalHorizontalInches, circuitTag))
                onSession(session.closeDialog().withRoute(emptyList()))
            },
        )
    }
}

private const val LINE_MAP_DELAY_MS = 400L
