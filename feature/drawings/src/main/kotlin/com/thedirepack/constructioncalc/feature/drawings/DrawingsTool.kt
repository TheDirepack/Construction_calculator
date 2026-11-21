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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thedirepack.constructioncalc.core.data.PlanExports
import com.thedirepack.constructioncalc.core.data.PlanFiles
import com.thedirepack.constructioncalc.core.data.activeTarget
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.PlanDrawing
import com.thedirepack.constructioncalc.core.model.PlanMark
import com.thedirepack.constructioncalc.core.model.PlanMarkKind
import com.thedirepack.constructioncalc.core.model.PlanMeasurement
import com.thedirepack.constructioncalc.core.model.PlanPoint
import com.thedirepack.constructioncalc.core.model.PlanZone
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.ResultValue
import com.thedirepack.constructioncalc.core.model.newId
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.components.ToolScaffold
import com.thedirepack.constructioncalc.core.ui.format
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices
import com.thedirepack.constructioncalc.core.ui.tool.ToolCategory
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost
import com.thedirepack.constructioncalc.core.ui.tool.ToolModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object DrawingsTool : ToolModule {
    const val ID = "drawings"
    override val id = ID
    override val title = "Drawings"
    override val summary = "PDF plans, measurements and markups"
    override val category = ToolCategory.PROJECTS
    override val icon = R.drawable.ic_straighten
    override val hasSettings = true

    @Composable
    override fun Content(host: ToolHost) = DrawingsScreen(host)

    @Composable
    override fun SettingsContent() = SymbolSettings()
}

@Composable
private fun DrawingsScreen(host: ToolHost) {
    val context = LocalContext.current
    val files = remember(context) { PlanFiles(context.applicationContext) }
    val services = LocalAppServices.current
    val projects by services.projects.projects.collectAsStateWithLifecycle()
    val appSettings = rememberAppSettings()
    val project = host.target?.projectId?.let { id -> projects.firstOrNull { it.id == id } }
        ?: appSettings.activeTarget(projects)?.project
    var activeDrawingId by rememberSaveable(project?.id) { mutableStateOf<String?>(null) }
    val drawing = project?.drawings?.firstOrNull { it.id == activeDrawingId }
    val file = drawing?.let { files.file(it.sha256) }
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    fun importDrawing(uri: Uri?, asJson: Boolean) {
        val projectId = project?.id
        if (uri != null && projectId != null) {
            importing = true
            scope.launch {
                try {
                    val source = if (asJson) files.importDrawingJson(uri) else files.import(uri)
                    val attached = if (services.projects.get(projectId)?.drawings?.any { it.id == source.id } == true) {
                        source.copy(id = newId())
                    } else source
                    val result = services.projects.edit(projectId) { current, now ->
                        current.copy(drawings = current.drawings + attached, updatedAt = now)
                    }
                    if (result?.drawings?.any { it.id == attached.id } == true) activeDrawingId = attached.id
                    else host.showMessage("The project is unavailable for editing.")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    host.showMessage(error.message ?: "Could not import this drawing.")
                } finally {
                    importing = false
                }
            }
        }
    }
    val importPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { importDrawing(it, false) }
    val importJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { importDrawing(it, true) }

    fun updateDrawing(change: (PlanDrawing) -> PlanDrawing) {
        if (project == null || drawing == null) return
        val result = services.projects.edit(project.id) { current, now ->
            current.copy(drawings = current.drawings.map { if (it.id == drawing.id) change(it) else it }, updatedAt = now)
        }
        if (result == null || result.archived) host.showMessage("The project is unavailable for editing.")
    }

    fun publish(measurement: PlanMeasurement, quantity: Quantity, name: String) {
        host.publish(
            listOf(ResultValue.of(measurement.id, "Plan distance", quantity, appSettings.format(quantity))),
            context = name,
            immediate = true,
        )
    }

    ToolScaffold(
        title = if (drawing == null) "Drawings" else drawing.name,
        host = host,
        onBack = when {
            drawing != null -> { { activeDrawingId = null } }
            host.target != null -> host::back
            else -> null
        },
        subtitle = if (host.target != null || drawing != null) project?.name else null,
        actions = {
            if (drawing != null || project == null) {
                TextButton(onClick = host::openSettings) { Text("Symbols") }
            }
        },
    ) { padding ->
        when {
            project == null -> Column(Modifier.padding(padding).padding(20.dp)) {
                Text("Choose a project to store your drawings.")
                TextButton(onClick = { host.openProject(null) }) { Text("Open projects") }
            }
            drawing == null || file == null -> DrawingList(
                project, importing,
                onImport = { importPdf.launch(arrayOf("application/pdf")) },
                onImportJson = { importJson.launch(arrayOf("application/json", "application/octet-stream")) },
                onDrawing = { activeDrawingId = it },
                modifier = Modifier.padding(padding),
            )
            else -> DrawingEditor(
                drawing = drawing,
                file = file,
                editable = !project.archived,
                onChange = ::updateDrawing,
                publish = { measurement, value -> publish(measurement, value, drawing.name) },
                host = host,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun DrawingList(
    project: Project,
    importing: Boolean,
    onImport: () -> Unit,
    onImportJson: () -> Unit,
    onDrawing: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = onImport, enabled = !project.archived && !importing) {
            Text(if (importing) "Importing drawing…" else "Import PDF")
        }
        TextButton(onClick = onImportJson, enabled = !project.archived && !importing) { Text("Import drawing JSON") }
        if (project.drawings.isEmpty()) Text("No PDFs in this project.")
        if (project.archived) Text("Archived project")
        for (drawing in project.drawings) {
            TextButton(onClick = { onDrawing(drawing.id) }, modifier = Modifier.fillMaxWidth()) {
                Text(drawing.name)
            }
        }
    }
}

private data class Span(val first: PlanPoint, val second: PlanPoint)

@Composable
private fun DrawingEditor(
    drawing: PlanDrawing,
    file: File,
    editable: Boolean,
    onChange: ((PlanDrawing) -> PlanDrawing) -> Unit,
    publish: (PlanMeasurement, Quantity) -> Unit,
    host: ToolHost,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings = rememberAppSettings()
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
    var page by rememberSaveable(drawing.id) { mutableStateOf(0) }
    var mode by remember(drawing.id) { mutableStateOf(DrawingMode.Navigate) }
    var selectedZoneId by remember(drawing.id, page) { mutableStateOf<String?>(null) }
    var pending by remember(drawing.id, page, mode) { mutableStateOf<PlanPoint?>(null) }
    var newZone by remember(drawing.id, page) { mutableStateOf<Span?>(null) }
    var reference by remember(drawing.id, page) { mutableStateOf<Pair<PlanZone, Span>?>(null) }
    var labelPoint by remember(drawing.id, page) { mutableStateOf<PlanPoint?>(null) }
    var symbolPoint by remember(drawing.id, page) { mutableStateOf<PlanPoint?>(null) }
    var showOptions by remember { mutableStateOf(false) }
    var showCalibration by remember { mutableStateOf(false) }
    var showItems by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val pageZones = remember(drawing.zones, page) { drawing.zones.filter { it.page == page } }
    val zone = pageZones.firstOrNull { it.id == selectedZoneId } ?: pageZones.firstOrNull()
    val marks = remember(drawing.marks, page) { drawing.marks.filter { it.page == page } }
    val measurements = remember(drawing.measurements, page) { drawing.measurements.filter { it.page == page } }
    val fittedMeasurements = remember(pageZones, measurements) {
        val fits = pageZones.associate { it.id to (it to it.fit()) }
        measurements.map { measurement ->
            val match = fits[measurement.zoneId]
            val length = match?.takeIf { (zone, _) -> zone.contains(measurement.first) && zone.contains(measurement.second) }
                ?.second?.distance(measurement.first, measurement.second)
            measurement to length
        }
    }
    val planLines = fittedMeasurements.map { (measurement, length) ->
        PlanLine(measurement.first, measurement.second, length?.let { settings.format(it) } ?: "Uncalibrated")
    } + zone?.references.orEmpty().map { ref ->
        PlanLine(ref.first, ref.second, settings.format(ref.length.toQuantity()), isReference = true)
    }

    fun addMark(mark: PlanMark) {
        onChange { it.copy(marks = it.marks + mark) }
    }

    fun applySpan(first: PlanPoint, second: PlanPoint) {
        pending = null
        if (first == second) return
        when (mode) {
            DrawingMode.Zone -> {
                if (first.x == second.x || first.y == second.y) {
                    host.showMessage("A scale zone needs a width and height.")
                    return
                }
                val temporary = PlanZone(name = "Scale zone", page = page, first = first, second = second)
                if (pageZones.any { it.overlaps(temporary) }) {
                    host.showMessage("Scale zones cannot overlap.")
                } else newZone = Span(first, second)
            }
            DrawingMode.Reference -> {
                val containing = pageZones.singleOrNull { it.contains(first) && it.contains(second) }
                if (containing == null) host.showMessage("Put both reference points inside one scale zone.")
                else {
                    selectedZoneId = containing.id
                    reference = containing to Span(first, second)
                }
            }
            DrawingMode.Measure -> {
                val containing = pageZones.singleOrNull { it.contains(first) && it.contains(second) }
                if (containing == null) {
                    host.showMessage("Put both endpoints inside one scale zone. Split measurements at zone boundaries.")
                    return
                }
                val fit = containing.fit()
                if (fit == null) {
                    host.showMessage("Calibrate this scale zone before measuring.")
                    return
                }
                val saved = PlanMeasurement(page = page, zoneId = containing.id, first = first, second = second)
                onChange { it.copy(measurements = it.measurements + saved) }
                selectedZoneId = containing.id
                publish(saved, fit.distance(first, second))
            }
            DrawingMode.Arrow, DrawingMode.Line, DrawingMode.Rectangle -> {
                val kind = when (mode) {
                    DrawingMode.Arrow -> PlanMarkKind.ARROW
                    DrawingMode.Rectangle -> PlanMarkKind.RECTANGLE
                    else -> PlanMarkKind.LINE
                }
                if (kind != PlanMarkKind.RECTANGLE || first.x != second.x && first.y != second.y) {
                    addMark(PlanMark(page = page, kind = kind, points = listOf(first, second)))
                }
            }
            else -> Unit
        }
    }

    fun tap(point: PlanPoint) {
        when (mode) {
            DrawingMode.Text -> labelPoint = point
            DrawingMode.Symbol -> symbolPoint = point
            else -> {
                val first = pending
                if (first == null) pending = point else applySpan(first, point)
            }
        }
    }

    fun writeExport(uri: Uri?, pdf: Boolean) {
        if (uri == null) return
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        if (pdf) PlanExports.writePdf(context, drawing, stream, { quantity -> settings.format(quantity) })
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
    val count = pageCount
    Column(modifier.fillMaxSize()) {
        if (count == null) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(12.dp))
            return@Column
        }
        if (count <= 0) {
            Text("This drawing could not be opened. Check that its source PDF is available.", Modifier.padding(16.dp))
            return@Column
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { page-- }, enabled = page > 0) { Text("Previous") }
            Text("${page + 1} / $count")
            TextButton(onClick = { page++ }, enabled = page + 1 < count) { Text("Next") }
            TextButton(onClick = { showCalibration = true }, enabled = zone != null) { Text(zone?.name ?: "No scale zone") }
            DropdownMenu(expanded = showCalibration, onDismissRequest = { showCalibration = false }) {
                for (choice in pageZones) {
                    DropdownMenuItem(text = { Text(choice.name) }, onClick = {
                        showCalibration = false
                        selectedZoneId = choice.id
                        showItems = true
                    })
                }
            }
            TextButton(onClick = { showOptions = true }, enabled = !busy) { Text(if (busy) "Saving…" else "More") }
            DropdownMenu(expanded = showOptions, onDismissRequest = { showOptions = false }) {
                DropdownMenuItem(text = { Text("Export annotated PDF") }, onClick = {
                    showOptions = false
                    pdf.launch("$baseName annotated.pdf")
                })
                DropdownMenuItem(text = { Text("Export drawing JSON") }, onClick = {
                    showOptions = false
                    json.launch("$baseName.drawing.json")
                })
                DropdownMenuItem(text = { Text("Measurements and markups") }, onClick = {
                    showOptions = false
                    showItems = true
                })
                DropdownMenuItem(text = { Text("SVG symbol settings") }, onClick = {
                    showOptions = false
                    host.openSettings()
                })
            }
        }
        if (editable) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DrawingMode.entries.forEach { tool ->
                    FilterChip(mode == tool, onClick = { mode = tool }, label = { Text(tool.title) })
                }
            }
        }
        Text(
            if (pending == null) mode.hint else "Tap the second point.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
        PlanCanvas(
            file = file,
            pageIndex = page,
            mode = if (editable) mode else DrawingMode.Navigate,
            zones = pageZones,
            selectedZone = zone?.id,
            lines = planLines,
            marks = marks,
            pending = pending,
            onPlace = ::tap,
            onLine = ::applySpan,
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
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        fittedMeasurements.lastOrNull { it.second != null }?.let { (measurement, value) ->
            val length = requireNotNull(value)
            TextButton(onClick = { publish(measurement, length) }) {
                Text("${settings.format(length)} · Use measurement")
            }
        }
    }
    newZone?.let { span ->
        ZoneDialog(defaultName = "Zone ${drawing.zones.size + 1}", onDismiss = { newZone = null }) { name, preset ->
            val saved = PlanZone(name = name, page = page, first = span.first, second = span.second, preset = preset)
            onChange { it.copy(zones = it.zones + saved) }
            selectedZoneId = saved.id
            newZone = null
            mode = if (preset == null) DrawingMode.Reference else DrawingMode.Measure
        }
    }
    reference?.let { (target, span) ->
        ReferenceDialog(target, onDismiss = { reference = null }) { length, check ->
            if (target.references.size >= 100) {
                host.showMessage("This zone already has 100 reference measurements.")
                return@ReferenceDialog
            }
            onChange { current ->
                current.copy(zones = current.zones.map {
                    if (it.id == target.id) it.copy(references = it.references + com.thedirepack.constructioncalc.core.model.PlanReference(span.first, span.second, length, check)) else it
                })
            }
            reference = null
        }
    }
    labelPoint?.let { point ->
        TextMarkupDialog(onDismiss = { labelPoint = null }) { text ->
            addMark(PlanMark(page = page, kind = PlanMarkKind.TEXT, points = listOf(point), text = text, width = 13.0))
            labelPoint = null
        }
    }
    symbolPoint?.let { point ->
        SymbolDialog(onDismiss = { symbolPoint = null }, openSettings = {
            symbolPoint = null
            host.openSettings()
        }) { symbol ->
            addMark(PlanMark(page = page, kind = PlanMarkKind.SYMBOL, points = listOf(point), svg = symbol.svg, width = 36.0))
            symbolPoint = null
        }
    }
    if (showItems) {
        PageItemsDialog(
            zone = zone,
            measurements = fittedMeasurements,
            marks = marks,
            editable = editable,
            publish = publish,
            onZoneChange = { updated -> onChange { current -> current.copy(zones = current.zones.map { if (it.id == updated.id) updated else it }) } },
            onMeasureDelete = { id -> onChange { it.copy(measurements = it.measurements.filterNot { m -> m.id == id }) } },
            onMarkDelete = { id -> onChange { it.copy(marks = it.marks.filterNot { m -> m.id == id }) } },
            onDismiss = { showItems = false },
        )
    }
}
