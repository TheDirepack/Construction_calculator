package com.thedirepack.constructioncalc.feature.drawings

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.material3.CircularProgressIndicator
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
import com.thedirepack.constructioncalc.core.data.PlanExports
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.PathSegment
import com.thedirepack.constructioncalc.core.model.PlanDrawing
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
    publish: (PlanMeasurement, Quantity) -> Unit,
    host: ToolHost,
    modifier: Modifier = Modifier,
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
    var editCircuit by remember(drawing.id) { mutableStateOf(false) }
    var editMarkCircuit by remember(drawing.id) { mutableStateOf(false) }
    var settingsOpen by rememberSaveable(drawing.id) { mutableStateOf(false) }
    var sheetsOpen by rememberSaveable(drawing.id) { mutableStateOf(false) }
    val sheetBrowserState = rememberSaveableStateHolder()
    var routeFinished by remember(drawing.id) { mutableStateOf(false) }
    var highlightsVisible by rememberSaveable(drawing.id) { mutableStateOf(true) }
    var editText by remember(drawing.id) { mutableStateOf(false) }
    val sheetStore = remember(views, drawing.id, drawing.sha256) {
        SheetNavigationStore(views, "${drawing.id}/${drawing.sha256}")
    }
    var visits by remember(sheetStore) { mutableStateOf(sheetStore.visits()) }
    var customLabels by remember(sheetStore) { mutableStateOf(sheetStore.labels()) }
    val labels = remember(file) { mutableStateMapOf<Int, SheetLabel>() }
    var indexing by remember(file) { mutableStateOf(true) }
    LaunchedEffect(file) {
        try {
            indexSheets(context.applicationContext, file) { index, label -> labels[index] = label }
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
    }

    fun switchMode(next: DrawingMode) {
        if (next != mode) {
            clearSelection()
            routeFinished = false
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
    val snapTarget = when (mode) {
        DrawingMode.Reference -> prefs.calibrationTarget
        DrawingMode.Measure, DrawingMode.Route, DrawingMode.Adjust -> prefs.measurementTarget
        else -> SnapTarget.CENTER
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
    val docSummary = remember(drawing.calibration, page) { CalibrationDiagnostics.evaluate(drawing.calibration, page) }
    val fittedMeasurements = remember(drawing, page) {
        drawing.measurements.filter { it.page == page }.map { it to drawing.length(it) }
    }
    val calibrationLines = remember(drawing.calibration, pageZones, page, prefs.showCalibrationLayer, settings) {
        if (!prefs.showCalibrationLayer) emptyList()
        else {
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
    val handles = remember(drawing, page, mode, prefs.showCalibrationLayer, selectedMeasurementId, selectedMark, selectedPath) {
        if (selectedPath != null) {
            val path = selectedPath.path
            val target = EndpointTarget.Path(path.id)
            path.points.mapIndexed { i, point -> EndpointHandle(target, i, point) }
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

    // Routes sent to runs aren't part of undo: they mirror runs, which undo doesn't touch.
    fun undo() {
        val (restored, next) = history.undo(drawing) ?: return
        history = next
        onChange { current -> restored.copy(routes = current.routes) }
    }

    fun redo() {
        val (restored, next) = history.redo(drawing) ?: return
        history = next
        onChange { current -> restored.copy(routes = current.routes) }
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
                markAt(strokes, point, radius * .55)?.let(::deleteMark)
            }
            DrawingMode.Symbol -> {
                val current = symbol
                if (current == null) session = session.open(EditorDialog.PickSymbol)
                else addMark(PlanMark(page = page, kind = PlanMarkKind.SYMBOL, points = listOf(point),
                    svg = current.svg, text = current.name.take(2_000), width = 36.0, circuitTag = circuitTag))
            }
            DrawingMode.Route -> if (!routeFinished) apply(EditorActions.tap(session, drawing, point, radius))
            else -> apply(EditorActions.tap(session, drawing, point, radius))
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
        EditorHeader(
            sheet = (customLabels[page] ?: labels[page])?.number?.takeIf(String::isNotBlank)
                ?.let { "$it · ${page + 1}/$count" } ?: "${page + 1} / $count",
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
                onMode = ::switchMode,
                onScale = { session = session.open(EditorDialog.Calibration) },
                onRotate = { rotation = (rotation + 1) % 4 },
                onToggleCalibration = { DrawingsSettings.setShowCalibrationLayer(prefsStore, !prefs.showCalibrationLayer) },
                onTakeoff = { session = session.open(EditorDialog.Takeoff) },
                onPageItems = { session = session.open(EditorDialog.PageItems) },
                onExportPdf = { pdf.launch("$baseName annotated.pdf") },
                onExportJson = { json.launch("$baseName.drawing.json") },
                highlightsVisible = highlightsVisible,
                onToggleHighlights = { highlightsVisible = !highlightsVisible; clearSelection() },
            )
        }
        if (sheetsOpen) {
            sheetBrowserState.SaveableStateProvider("${drawing.id}/${drawing.sha256}") {
                SheetBrowser(
                    file = file,
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
                    onPage = ::goToPage,
                )
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val landscape = maxWidth > maxHeight && maxWidth >= 560.dp
            val strip: @Composable () -> Unit = {
                if (editable) {
                    EditorTools(mode, landscape, ::switchMode)
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
                        onPlace = ::place,
                        onLine = { first, second -> apply(EditorActions.span(session, drawing, first, second)) },
                        onInk = { points ->
                            addMark(
                                PlanMark(
                                    page = page,
                                    kind = if (mode == DrawingMode.Highlight) PlanMarkKind.HIGHLIGHT else PlanMarkKind.INK,
                                    points = points.map { it.point },
                                    pressures = points.map { it.pressure },
                                    width = if (mode == DrawingMode.Highlight) 11.0 else 1.8,
                                ),
                            )
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
                        onMoveMark = ::replaceMark,
                        onMoveEndpoint = { handle, point ->
                            if (handle.target is EndpointTarget.Path) {
                                selectedVertex = handle.end
                                selectedEdge = null
                            }
                            apply(EditorActions.moveEndpoint(session, drawing, handle, point))
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    val selectedMeasurement = fittedMeasurements.firstOrNull { it.first.id == selectedMeasurementId }
                    val latest = fittedMeasurements.lastOrNull().takeIf { mode == DrawingMode.Measure }
                    val result = selectedMeasurement ?: latest
                    val hasActions = selectedMark != null || selectedPath != null || result != null || routePoints.isNotEmpty() || session.firstPoint != null
                    if (hasActions || mode == DrawingMode.Route || mode == DrawingMode.Symbol && symbol != null) {
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
                                        if (selectedMark.kind in listOf(PlanMarkKind.TEXT, PlanMarkKind.CALLOUT)) {
                                            TextButton(onClick = { editText = true }) { Text("Edit text") }
                                        }
                                        if (selectedMark.kind == PlanMarkKind.SYMBOL) {
                                            TextButton(onClick = { editMarkCircuit = true }) {
                                                Text(selectedMark.circuitTag.ifBlank { "Circuit" })
                                            }
                                        }
                                        TextButton(onClick = {
                                            val duplicate = selectedMark.copy(id = newId()).move(12.0, 12.0)
                                            addMark(duplicate)
                                            selectedMarkId = duplicate.id
                                        }) { Text("Duplicate") }
                                        TextButton(onClick = { deleteMark(selectedMark) }) { Text("Delete") }
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
                                        val total = marks.count {
                                            it.kind == PlanMarkKind.SYMBOL && it.text == active.name && it.circuitTag == circuitTag
                                        }
                                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                                            TextButton(onClick = { session = session.open(EditorDialog.PickSymbol) }) {
                                                Text("${active.name} · $total  ▾")
                                            }
                                            TextButton(onClick = { editCircuit = true }) { Text(circuitTag.ifBlank { "Circuit" }) }
                                            TextButton(onClick = { session = session.open(EditorDialog.Takeoff) }) { Text("Totals") }
                                        }
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
                                        onSavePath = ::saveTrace,
                                        vertexCount = routePoints.size,
                                        onSendRoute = { session = session.open(EditorDialog.SendRoute) },
                                    )
                                    session.firstPoint != null -> Text(
                                        if (mode == DrawingMode.Callout) "Tap where the note goes." else "Tap the second point.",
                                        Modifier.padding(8.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                    result != null -> {
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
                                    }
                                }
                            }
                        }
                    }
                    if (selectedMark != null && editText) {
                        TextMarkupDialog(onDismiss = { editText = false }, initial = selectedMark.text) { text ->
                            replaceMark(selectedMark.copy(text = text))
                            editText = false
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

    if (editMarkCircuit && selectedMark != null) {
        CircuitTagDialog(selectedMark.circuitTag, onDismiss = { editMarkCircuit = false }) { tag ->
            replaceMark(selectedMark.copy(circuitTag = tag))
            editMarkCircuit = false
        }
    }

    if (editCircuit) {
        CircuitTagDialog(circuitTag, onDismiss = { editCircuit = false }) { tag ->
            circuitTag = tag
            views.edit().putString("${drawing.id}/circuit", tag).apply()
            editCircuit = false
        }
    }

    EditorDialogs(
        circuitTag = circuitTag,
        session = session,
        project = project,
        drawing = drawing,
        page = page,
        pageSize = pageSize,
        pageZones = pageZones,
        zone = zone,
        fittedMeasurements = fittedMeasurements,
        marks = marks,
        routeSummary = routeSummary,
        editable = editable,
        onSession = {
            if (it.page != page) clearSelection()
            if (it.op == EditOp.Idle) routeFinished = false
            session = it
        },
        editDrawing = ::editDrawing,
        addMark = ::addMark,
        onPickSymbol = {
            symbol = it
            views.edit().putString("${drawing.id}/symbol", it.id).apply()
        },
        onOpenSettings = { settingsOpen = true },
        publish = publish,
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
    page: Int,
    pageSize: PageSize?,
    pageZones: List<PlanZone>,
    zone: PlanZone?,
    fittedMeasurements: List<Pair<PlanMeasurement, Quantity?>>,
    marks: List<PlanMark>,
    routeSummary: RouteSummary?,
    editable: Boolean,
    onSession: (EditorSession) -> Unit,
    editDrawing: ((PlanDrawing) -> PlanDrawing) -> Unit,
    addMark: (PlanMark) -> Unit,
    onPickSymbol: (PlanSymbol) -> Unit,
    onOpenSettings: () -> Unit,
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
            val target = drawing.zones.firstOrNull { it.id == dialog.zoneId }
            val title = target?.name ?: "Document calibration (page ${page + 1})"
            val hasFit = target?.let { it.fit() != null } ?: (drawing.calibration.fit(page) != null)
            ReferenceDialog(title, hasFit = hasFit, onDismiss = close) { length, check ->
                val ref = PlanReference(dialog.first, dialog.second, length, check, page = page)
                if (target != null) {
                    editDrawing { d -> d.copy(zones = d.zones.map { if (it.id == target.id) it.copy(references = it.references + ref) else it }) }
                } else {
                    editDrawing { d -> d.copy(calibration = d.calibration.copy(references = d.calibration.references + ref)) }
                }
                close()
            }
        }
        is EditorDialog.PlaceText -> TextMarkupDialog(onDismiss = close) { text ->
            addMark(PlanMark(page = page, kind = PlanMarkKind.TEXT, points = listOf(dialog.point), text = text, width = 13.0))
            close()
        }
        EditorDialog.PickSymbol -> SymbolDialog(onDismiss = close, openSettings = {
            close()
            onOpenSettings()
        }) { symbol ->
            onPickSymbol(symbol)
            close()
        }
        is EditorDialog.PlaceCallout -> TextMarkupDialog(onDismiss = close, title = "Add leader note") { text ->
            addMark(PlanMark(page = page, kind = PlanMarkKind.CALLOUT, points = listOf(dialog.first, dialog.second),
                text = text, width = 13.0))
            close()
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
            onZoneChange = ::updateZone,
            onZoneDelete = ::deleteZone,
            onDismiss = close,
        )
        EditorDialog.PageItems -> PageItemsDialog(
            zone = zone,
            measurements = fittedMeasurements,
            marks = marks,
            editable = editable,
            publish = publish,
            onZoneChange = ::updateZone,
            onZoneDelete = ::deleteZone,
            onMeasureEdit = { id -> onSession(session.open(EditorDialog.EditMeasurement(id))) },
            onMeasureDelete = { id -> editDrawing { it.copy(measurements = it.measurements.filterNot { m -> m.id == id }) } },
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
