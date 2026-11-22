package com.thedirepack.constructioncalc.feature.drawings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
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
import com.thedirepack.constructioncalc.core.model.PlanPoint
import com.thedirepack.constructioncalc.core.model.PlanReference
import com.thedirepack.constructioncalc.core.model.PlanZone
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
internal data class TracedRoute(val page: Int, val points: List<PlanPoint>, val horizontalInches: Double)

/** Saves the page and tool across configuration changes; a half-done operation is dropped. */
private val SessionSaver = Saver<EditorSession, List<Any>>(
    save = { listOf(it.page, it.mode.name) },
    restore = { saved ->
        EditorSession(
            page = (saved[0] as Int).coerceAtLeast(0),
            mode = DrawingMode.entries.firstOrNull { it.name == saved[1] } ?: DrawingMode.Navigate,
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
    var session by rememberSaveable(drawing.id, focusRouteId, stateSaver = SessionSaver) {
        mutableStateOf(
            if (focusRoute != null) EditorSession(page = focusRoute.page, focusedRouteId = focusRoute.id)
            else EditorSession(),
        )
    }
    var history by remember(drawing.id) { mutableStateOf(DrawingHistory<PlanDrawing>()) }
    var pageSize by remember(drawing.id, session.page) { mutableStateOf<PageSize?>(null) }
    var busy by remember { mutableStateOf(false) }
    val page = session.page
    val mode = if (editable) session.mode else DrawingMode.Navigate

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
    val displayedLines = remember(fittedMeasurements, calibrationLines, settings) {
        fittedMeasurements.map { (measurement, length) ->
            val value = length?.let { "≈ " + settings.format(it) } ?: "Uncalibrated"
            CanvasLine(measurement.first, measurement.second, if (measurement.label.isBlank()) value else "${measurement.label}\n$value")
        } + calibrationLines
    }
    val savedRoutes = remember(drawing.routes, project.runs, page, session.focusedRouteId) {
        drawing.routes.filter { it.page == page }.map { route ->
            CanvasRoute(
                points = route.points,
                label = project.runs.firstOrNull { it.id == route.runId }?.name ?: "Run (deleted)",
                focused = route.id == session.focusedRouteId,
            )
        }
    }
    val handles = remember(drawing, page, mode, prefs.showCalibrationLayer) {
        if (mode == DrawingMode.Adjust) EditorActions.handles(drawing, page, includeReferences = prefs.showCalibrationLayer)
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
        EditorToolbar(
            state = ToolbarState(
                page = page,
                pageCount = count,
                editable = editable,
                canUndo = history.canUndo,
                canRedo = history.canRedo,
                mode = mode,
                scaleStatus = docSummary.status,
                snapEnabled = if (mode.snaps) snapEnabled else null,
                snapReady = lineMap != null,
                snapTarget = snapTarget,
                showCalibrationLayer = prefs.showCalibrationLayer,
                busy = busy,
            ),
            actions = ToolbarActions(
                onPage = { session = session.goToPage(it.coerceIn(0, count - 1)) },
                onUndo = ::undo,
                onRedo = ::redo,
                onMode = { session = session.withMode(it) },
                onScale = { session = session.open(EditorDialog.Calibration) },
                onToggleSnap = {
                    if (mode == DrawingMode.Reference) DrawingsSettings.setSnapCalibration(prefsStore, !prefs.snapCalibration)
                    else DrawingsSettings.setSnapMeasurements(prefsStore, !prefs.snapMeasurements)
                },
                onToggleCalibrationLayer = { DrawingsSettings.setShowCalibrationLayer(prefsStore, !prefs.showCalibrationLayer) },
                onTakeoff = { session = session.open(EditorDialog.Takeoff) },
                onPageItems = { session = session.open(EditorDialog.PageItems) },
                onExportPdf = { pdf.launch("$baseName annotated.pdf") },
                onExportJson = { json.launch("$baseName.drawing.json") },
                onSettings = host::openSettings,
            ),
        )
        Text(
            when {
                session.firstPoint != null -> "Tap the second point."
                session.focusedRouteId != null && mode == DrawingMode.Navigate -> "Highlighted: the route this run was traced from."
                else -> mode.hint
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
        PlanCanvas(
            file = file,
            pageIndex = page,
            mode = mode,
            zones = pageZones,
            selectedZone = zone?.id,
            lines = displayedLines,
            marks = marks,
            pending = session.firstPoint,
            lineMap = lineMap,
            snapEnabled = snapEnabled,
            snapTarget = snapTarget,
            onPageSize = { pageSize = it },
            onPlace = { point, radius -> apply(EditorActions.tap(session, drawing, point, radius)) },
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
            handles = handles,
            onMoveEndpoint = { handle, point -> apply(EditorActions.moveEndpoint(session, drawing, handle, point)) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        BottomBar(
            routeSummary = routeSummary,
            routePoints = routePoints,
            lastMeasurement = fittedMeasurements.lastOrNull { it.second != null }?.let { it.first to it.second!! },
            editable = editable,
            onUndoPoint = { session = session.withRoute(routePoints.dropLast(1)) },
            onClearRoute = { session = session.withRoute(emptyList()) },
            onSaveLegs = { summary ->
                val legs = summary.legs.map { leg -> PlanMeasurement(page = page, zoneId = leg.zoneId, first = leg.from, second = leg.to) }
                editDrawing { it.copy(measurements = it.measurements + legs) }
                host.showMessage("Saved ${legs.size} leg(s) as measurements.")
            },
            onSendRoute = { session = session.open(EditorDialog.SendRoute) },
            publish = publish,
        )
    }

    EditorDialogs(
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
        onSession = { session = it },
        editDrawing = ::editDrawing,
        addMark = ::addMark,
        publish = publish,
        onSendRouteToRun = onSendRouteToRun,
        host = host,
    )
}

@Composable
private fun BottomBar(
    routeSummary: RouteSummary?,
    routePoints: List<PlanPoint>,
    lastMeasurement: Pair<PlanMeasurement, Quantity>?,
    editable: Boolean,
    onUndoPoint: () -> Unit,
    onClearRoute: () -> Unit,
    onSaveLegs: (RouteSummary) -> Unit,
    onSendRoute: () -> Unit,
    publish: (PlanMeasurement, Quantity) -> Unit,
) {
    val settings = rememberAppSettings()
    if (routePoints.isNotEmpty()) {
        Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text(
                    routeSummary?.let { summary ->
                        val bends = if (summary.corners.isEmpty()) "no bends"
                        else "${summary.corners.size} bend(s), ${summary.totalBendDegrees.roundToInt()}°"
                        "Route ≈ ${settings.format(summary.totalHorizontalQuantity)} · ${summary.legs.size} leg(s) · $bends"
                    } ?: "Route: 1 point placed · tap the next point",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (routeSummary != null && editable) {
                        Button(onClick = onSendRoute) { Text("Send to run") }
                        TextButton(onClick = { onSaveLegs(routeSummary) }) { Text("Save legs") }
                    }
                    TextButton(onClick = onUndoPoint) { Text("Undo point") }
                    TextButton(onClick = onClearRoute) { Text("Clear") }
                }
            }
        }
    } else if (lastMeasurement != null) {
        val (measurement, length) = lastMeasurement
        TextButton(onClick = { publish(measurement, length) }, modifier = Modifier.padding(horizontal = 4.dp)) {
            Text("≈ ${settings.format(length)} · Use measurement")
        }
    }
}

@Composable
private fun EditorDialogs(
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
    publish: (PlanMeasurement, Quantity) -> Unit,
    onSendRouteToRun: (runId: String?, newRunName: String?, segments: List<PathSegment>, route: TracedRoute) -> Unit,
    host: ToolHost,
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
        is EditorDialog.PlaceSymbol -> SymbolDialog(onDismiss = close, openSettings = {
            close()
            host.openSettings()
        }) { symbol ->
            // The name travels with the mark so the takeoff can count symbols by name.
            addMark(PlanMark(page = page, kind = PlanMarkKind.SYMBOL, points = listOf(dialog.point), svg = symbol.svg, text = symbol.name.take(2_000), width = 36.0))
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
                onSession(session.goToPage(route.page).copy(focusedRouteId = route.id))
            },
            onDismiss = close,
        )
        EditorDialog.SendRoute -> if (routeSummary == null) close() else SendRouteToRunDialog(
            project = project,
            summary = routeSummary,
            onDismiss = close,
            onSend = { runId, newRunName, segments ->
                onSendRouteToRun(runId, newRunName, segments, TracedRoute(page, routeSummary.points, routeSummary.totalHorizontalInches))
                onSession(session.closeDialog().withRoute(emptyList()))
            },
        )
    }
}

private const val LINE_MAP_DELAY_MS = 400L
