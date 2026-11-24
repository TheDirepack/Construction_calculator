package com.thedirepack.constructioncalc.feature.drawings

import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.listSaver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thedirepack.constructioncalc.core.data.PlanFiles
import com.thedirepack.constructioncalc.core.data.PlanPhotos
import com.thedirepack.constructioncalc.core.data.PlanSymbols
import com.thedirepack.constructioncalc.core.data.ToolBackup
import com.thedirepack.constructioncalc.core.data.activeTarget
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.PathSegment
import com.thedirepack.constructioncalc.core.model.PlanDrawing
import com.thedirepack.constructioncalc.core.model.PlanMeasurement
import com.thedirepack.constructioncalc.core.model.PlanRunRoute
import com.thedirepack.constructioncalc.core.model.PlanSheetSource
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.ResultValue
import com.thedirepack.constructioncalc.core.model.Run
import com.thedirepack.constructioncalc.core.model.ToolTarget
import com.thedirepack.constructioncalc.core.model.newId
import com.thedirepack.constructioncalc.core.model.planRouteFor
import com.thedirepack.constructioncalc.core.model.withRunPath
import com.thedirepack.constructioncalc.core.model.withRunRoute
import com.thedirepack.constructioncalc.core.ui.R as CoreR
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
import java.util.Locale

object DrawingsTool : ToolModule {
    const val ID = "drawings"
    override val id = ID
    override val title = "Drawings"
    override val summary = "PDF plans, measurements and markups"
    override val category = ToolCategory.PROJECTS
    override val icon = CoreR.drawable.ic_straighten
    override val hasSettings = true

    override fun backup(context: Context): ToolBackup = PlanSymbols(context.applicationContext).backup

    @Composable
    override fun Content(host: ToolHost) = DrawingsScreen(host)

    @Composable
    override fun SettingsContent() = DrawingsSettingsScreen()
}

private data class DetailJumpTarget(val drawingId: String, val page: Int)

private val DetailJumpSaver = listSaver<DetailJumpTarget?, Any>(
    save = { target -> if (target == null) emptyList() else listOf(target.drawingId, target.page) },
    restore = { saved -> if (saved.size == 2) DetailJumpTarget(saved[0] as String, saved[1] as Int) else null },
)

@Composable
private fun DrawingsScreen(host: ToolHost) {
    val context = LocalContext.current
    val files = remember(context) { PlanFiles(context.applicationContext) }
    val photos = remember(context) { PlanPhotos(context.applicationContext) }
    val services = LocalAppServices.current
    val projects by services.projects.projects.collectAsStateWithLifecycle()
    val appSettings = rememberAppSettings()
    val project = host.target?.projectId?.let { id -> projects.firstOrNull { it.id == id } }
        ?: appSettings.activeTarget(projects)?.project
    // Opened from a run ("View on plan"): start on the drawing and route the run was traced from.
    val runTarget = host.target as? ToolTarget.OfRun
    val runRoute = remember(project?.id, runTarget) {
        runTarget?.let { target -> project?.takeIf { it.id == target.projectId }?.planRouteFor(target.runId) }
    }
    var activeDrawingId by rememberSaveable(project?.id) { mutableStateOf(runRoute?.first?.id) }
    var returnTarget by rememberSaveable(project?.id, stateSaver = DetailJumpSaver) { mutableStateOf<DetailJumpTarget?>(null) }
    var pendingJumpPage by rememberSaveable(project?.id) { mutableStateOf<Int?>(null) }
    var jumpSerial by rememberSaveable(project?.id) { mutableStateOf(0) }
    val drawing = project?.drawings?.firstOrNull { it.id == activeDrawingId }
    val focusRouteId = runRoute?.takeIf { it.first.id == drawing?.id }?.second?.id
    val file = drawing?.let { files.file(it.sha256) }
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var pendingJsonImport by remember { mutableStateOf<PlanDrawing?>(null) }

    fun attachImportedDrawing(source: PlanDrawing, projectId: String, chosenName: String? = null) {
        val resolvedName = (chosenName ?: source.name).trim().ifBlank { "Drawing" }.take(240)
        var savedId = source.id
        val result = services.projects.edit(projectId) { current, now ->
            val needsNewId = current.drawings.any { it.id == source.id }
            val attached = source.copy(
                id = if (needsNewId) newId() else source.id,
                name = resolvedName,
            )
            savedId = attached.id
            current.copy(drawings = current.drawings + attached, updatedAt = now)
        }
        if (result != null && !result.archived && result.drawings.any { it.id == savedId }) {
            activeDrawingId = savedId
        } else {
            host.showMessage("The project is unavailable for editing.")
        }
    }

    fun importDrawing(uri: Uri?, asJson: Boolean) {
        val projectId = project?.id
        if (uri != null && projectId != null) {
            importing = true
            scope.launch {
                try {
                    val source = if (asJson) files.importDrawingJson(uri) else files.import(uri)
                    val existing = services.projects.get(projectId)?.drawings.orEmpty()
                    // The same PDF again (same bytes): open the copy already in the project.
                    val duplicate = if (asJson) null else existing.firstOrNull { it.sha256 == source.sha256 }
                    if (duplicate != null) {
                        activeDrawingId = duplicate.id
                        host.showMessage("This PDF is already in the project as “${duplicate.name}”.")
                        return@launch
                    }
                    if (asJson && existing.any { it.id == source.id || it.name.equals(source.name, ignoreCase = true) }) {
                        pendingJsonImport = source
                        return@launch
                    }
                    attachImportedDrawing(source, projectId)
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

    fun renameDrawing(target: PlanDrawing, newName: String) {
        if (project == null || newName.isBlank()) return
        val result = services.projects.edit(project.id) { current, now ->
            current.copy(
                drawings = current.drawings.map { if (it.id == target.id) it.copy(name = newName) else it },
                updatedAt = now,
            )
        }
        if (result == null || result.archived) host.showMessage("The project is unavailable for editing.")
    }

    fun deleteDrawing(target: PlanDrawing) {
        if (project == null) return
        val result = services.projects.edit(project.id) { current, now ->
            current.copy(drawings = current.drawings.filterNot { it.id == target.id }, updatedAt = now)
        }
        if (result == null || result.archived) {
            host.showMessage("The project is unavailable for editing.")
            return
        }
        if (activeDrawingId == target.id) {
            activeDrawingId = null
            returnTarget = null
        }
        // Garbage-collect unreferenced PDFs and field photos across all projects.
        val remainingProjects = services.projects.projects.value
        val stillReferenced = remainingProjects.any { p ->
            p.drawings.any { it.sha256 == target.sha256 }
        }
        val referencedPhotos = remainingProjects.flatMap { p ->
            p.drawings.flatMap { it.referencedPhotoHashes() }
        }.toSet()
        scope.launch(Dispatchers.IO) {
            if (!stillReferenced) runCatching { files.file(target.sha256).delete() }
            runCatching { photos.garbageCollect(referencedPhotos) }
        }
        host.showMessage("Deleted “${target.name}”.")
    }

    fun sendRouteToRun(runId: String?, newRunName: String?, segments: List<PathSegment>, traced: TracedRoute) {
        if (project == null || drawing == null) return
        var targetRunName = ""
        val result = services.projects.edit(project.id) { current, now ->
            val (updated, targetId) = when {
                newRunName != null -> {
                    val created = Run(name = newRunName)
                    targetRunName = created.name
                    current.addRun(created, now).withRunPath(created.id, segments, now) to created.id
                }
                runId != null -> {
                    targetRunName = current.run(runId)?.name ?: "Run"
                    current.withRunPath(runId, segments, now) to runId
                }
                else -> return@edit current
            }
            // Keep the run's link back to this trace; a run has one plan source, so drop older ones.
            val route = PlanRunRoute(runId = targetId, page = traced.page, points = traced.points, sentInches = traced.horizontalInches, circuitTag = traced.circuitTag)
            updated.copy(
                drawings = updated.drawings.map { d ->
                    if (d.id == drawing.id) d.withRunRoute(route)
                    else d.copy(routes = d.routes.filterNot { it.runId == targetId })
                },
            )
        }
        if (result == null || result.archived) {
            host.showMessage("The project is unavailable for editing.")
        } else {
            host.showMessage("Route sent to “$targetRunName”.")
        }
    }

    fun publish(measurement: PlanMeasurement, quantity: Quantity, source: PlanSheetSource) {
        host.publish(
            listOf(ResultValue.of(measurement.id, "Plan distance", quantity, "≈ " + appSettings.format(quantity))
                .copy(planSource = source)),
            context = "${drawing?.name} · ${source.label}",
            immediate = true,
        )
    }

    if (project != null && drawing != null && file != null) {
        val returnLabel = returnTarget?.let { back ->
            val srcDrawing = project.drawings.firstOrNull { it.id == back.drawingId }
            val sheetName = srcDrawing?.sheetRevisions?.get(back.page)?.sheetId ?: "Page ${back.page + 1}"
            if (back.drawingId == drawing.id || srcDrawing == null) sheetName else "${srcDrawing.name} · $sheetName"
        }
        BackHandler {
            returnTarget = null
            if (focusRouteId != null) host.back() else activeDrawingId = null
        }
        DrawingEditor(
            project = project,
            drawing = drawing,
            file = file,
            editable = !project.archived,
            focusRouteId = focusRouteId,
            jumpPageRequest = pendingJumpPage?.let { it to jumpSerial },
            returnLabel = returnLabel,
            onReturnToSheet = returnTarget?.let { back ->
                {
                    returnTarget = null
                    activeDrawingId = back.drawingId
                    pendingJumpPage = back.page
                    jumpSerial++
                }
            },
            onJumpToSheet = { targetDrawingId, targetPage, fromPage ->
                returnTarget = DetailJumpTarget(drawing.id, fromPage)
                activeDrawingId = targetDrawingId
                pendingJumpPage = targetPage
                jumpSerial++
            },
            onChange = { updateDrawing(it) },
            onSendRouteToRun = { id, name, segs, route -> sendRouteToRun(id, name, segs, route) },
            onPublish = { m, q, s -> publish(m, q, s) },
            host = host,
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
        )
        return
    }

    ToolScaffold(
        title = "Drawings",
        host = host,
        onBack = if (host.target != null) host::back else null,
        subtitle = if (host.target != null) project?.name else null,
        actions = {
            IconButton(onClick = host::openSettings) {
                Icon(painterResource(R.drawable.ic_plan_settings), "Drawing settings")
            }
        },
    ) { padding ->
        when {
            project == null -> Column(Modifier.padding(padding).padding(20.dp)) {
                Text("Choose a project to store your drawings.")
                TextButton(onClick = { host.openProject(null) }) { Text("Open projects") }
            }
            else -> DrawingList(
                project = project,
                importing = importing,
                onImport = { importPdf.launch(arrayOf("application/pdf")) },
                onImportJson = { importJson.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) },
                onDrawing = { activeDrawingId = it },
                onRename = { d, name -> renameDrawing(d, name) },
                onDelete = { deleteDrawing(it) },
                modifier = Modifier.padding(padding),
            )

        }
    }

    pendingJsonImport?.let { source ->
        if (project == null) {
            pendingJsonImport = null
        } else {
            val suggestedName = remember(source, project.drawings) {
                uniqueImportedDrawingName(source.name, project.drawings.map { it.name })
            }
            RenameDialog(
                title = "Name imported drawing",
                label = "Drawing name",
                initialName = suggestedName,
                maxLength = 240,
                onDismiss = { pendingJsonImport = null },
                onConfirm = { chosenName ->
                    pendingJsonImport = null
                    attachImportedDrawing(source, project.id, chosenName)
                },
            )
        }
    }
}

internal fun uniqueImportedDrawingName(requested: String, existingNames: Collection<String>): String {
    val normalizedExisting = existingNames.mapTo(HashSet()) { it.trim().lowercase(Locale.ROOT) }
    val base = requested.trim().ifBlank { "Drawing" }.take(240)
    if (base.lowercase(Locale.ROOT) !in normalizedExisting) return base
    val hasPdfExt = base.endsWith(".pdf", ignoreCase = true) && base.length > 4
    val ext = if (hasPdfExt) base.takeLast(4) else ""
    val stem = if (hasPdfExt) base.dropLast(4) else base
    var copyIndex = 1
    while (true) {
        val suffix = if (copyIndex == 1) " (copy)" else " (copy $copyIndex)"
        val maxStem = (240 - suffix.length - ext.length).coerceAtLeast(1)
        val candidate = "${stem.take(maxStem)}$suffix$ext"
        if (candidate.lowercase(Locale.ROOT) !in normalizedExisting) return candidate
        copyIndex++
    }
}

internal fun drawingRowSubtitle(drawing: PlanDrawing): String {
    fun count(n: Int, singular: String, plural: String = "${singular}s"): String =
        if (n == 1) "1 $singular" else "$n $plural"

    val parts = buildList {
        if (drawing.measurements.isNotEmpty()) add(count(drawing.measurements.size, "measurement"))
        if (drawing.marks.isNotEmpty()) add(count(drawing.marks.size, "markup"))
        if (drawing.routes.isNotEmpty()) add(count(drawing.routes.size, "route"))
        if (drawing.paths.isNotEmpty()) add(count(drawing.paths.size, "path"))
        if (drawing.zones.isNotEmpty()) add(count(drawing.zones.size, "zone"))
        if (drawing.sheetRevisions.isNotEmpty()) add(count(drawing.sheetRevisions.size, "sheet rev"))
    }
    val details = if (parts.isEmpty()) "No markups or measurements" else parts.joinToString(" · ")
    val calibrated = drawing.calibration.preset != null || drawing.calibration.references.any { !it.check }
    val scale = if (calibrated) "Calibrated" else "Uncalibrated"
    return "$scale · $details"
}

@Composable
private fun DrawingList(
    project: Project,
    importing: Boolean,
    onImport: () -> Unit,
    onImportJson: () -> Unit,
    onDrawing: (String) -> Unit,
    onRename: (PlanDrawing, String) -> Unit,
    onDelete: (PlanDrawing) -> Unit,
    modifier: Modifier = Modifier,
) {
    var renaming by remember { mutableStateOf<PlanDrawing?>(null) }
    var deleting by remember { mutableStateOf<PlanDrawing?>(null) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onImport, enabled = !project.archived && !importing) {
                Text(if (importing) "Importing drawing…" else "Import PDF")
            }
            TextButton(onClick = onImportJson, enabled = !project.archived && !importing) { Text("Import drawing JSON") }
        }
        if (project.drawings.isEmpty()) Text("No PDFs in this project.")
        if (project.archived) Text("Archived project")
        for (drawing in project.drawings) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onDrawing(drawing.id) }, modifier = Modifier.weight(1f)) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(drawing.name)
                        Text(
                            drawingRowSubtitle(drawing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!project.archived) {
                    TextButton(onClick = { renaming = drawing }) { Text("Rename") }
                    TextButton(onClick = { deleting = drawing }) { Text("Delete") }
                }
            }
        }
    }

    renaming?.let { target ->
        RenameDialog(
            title = "Rename drawing",
            label = "Drawing name",
            initialName = target.name,
            maxLength = 240,
            onDismiss = { renaming = null },
            onConfirm = { updated ->
                onRename(target, updated)
                renaming = null
            },
        )
    }
    deleting?.let { target ->
        ConfirmDeleteDialog(
            title = "Delete drawing?",
            message = "Remove “${target.name}” and its measurements, zones, and markups from this project?",
            onDismiss = { deleting = null },
            onConfirm = {
                onDelete(target)
                deleting = null
            },
        )
    }
}
