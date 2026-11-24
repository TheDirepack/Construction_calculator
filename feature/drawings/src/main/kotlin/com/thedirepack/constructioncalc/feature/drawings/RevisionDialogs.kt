package com.thedirepack.constructioncalc.feature.drawings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.thedirepack.constructioncalc.core.data.PlanExportCrop
import com.thedirepack.constructioncalc.core.data.PlanFiles
import com.thedirepack.constructioncalc.core.model.IssuedSheet
import com.thedirepack.constructioncalc.core.model.PlanSheetSource
import com.thedirepack.constructioncalc.core.model.SheetRevision
import com.thedirepack.constructioncalc.core.model.SheetRevisionStatus
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException

@Composable
internal fun SheetRevisionDialog(
    fallbackId: String,
    current: SheetRevision?,
    status: SheetRevisionStatus?,
    editable: Boolean,
    candidates: (String) -> List<IssuedSheet>,
    onSave: (SheetRevision) -> Unit,
    onCompare: (IssuedSheet) -> Unit,
    onDismiss: () -> Unit,
) {
    var sheetId by remember { mutableStateOf(current?.sheetId ?: fallbackId) }
    var revision by remember { mutableStateOf(current?.revision.orEmpty()) }
    var order by remember { mutableStateOf(current?.issueOrder?.toString().orEmpty()) }
    val comparable = remember(current, candidates) { current?.let { candidates(it.sheetId) }.orEmpty() }
    val issue = order.toIntOrNull()
    val conflict = issue != null && candidates(sheetId).any {
        it.identity.issueOrder == issue || it.identity.revision.equals(revision.trim(), ignoreCase = true)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sheet revision") },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(status?.label ?: "Revision unassigned")
                if (editable) {
                    OutlinedTextField(sheetId, { sheetId = it.take(80) }, label = { Text("Sheet ID") }, singleLine = true)
                    OutlinedTextField(revision, { revision = it.take(40) }, label = { Text("Revision") }, singleLine = true)
                    OutlinedTextField(order, { order = it.filter { char -> char in '0'..'9' }.take(10) },
                        label = { Text("Issue order") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText = { Text("Use a higher number for a newer issue of this sheet.") })
                    if (conflict) Text("This sheet already has that revision or issue order.")
                } else if (current != null) {
                    Text("${current.sheetId} · Rev ${current.revision}")
                }
                if (comparable.isNotEmpty()) {
                    var choosing by remember { mutableStateOf(false) }
                    TextButton(onClick = { choosing = true }) { Text("Compare with another revision") }
                    DropdownMenu(choosing, onDismissRequest = { choosing = false }) {
                        comparable.forEach { sheet ->
                            DropdownMenuItem(
                                text = { Text("Rev ${sheet.identity.revision} · ${sheet.drawing.name} · Page ${sheet.page + 1}") },
                                onClick = { choosing = false; onCompare(sheet) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (editable) {
                TextButton(
                    enabled = !conflict && issue != null && issue > 0 && sheetId.isNotBlank() && revision.isNotBlank() &&
                        sheetId.none(Char::isISOControl) && revision.none(Char::isISOControl),
                    onClick = { onSave(SheetRevision(sheetId.trim(), revision.trim(), checkNotNull(issue))) },
                ) { Text("Save") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private val grayscale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

private val comparisonTint = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
    .70f, 0f, 0f, 0f, 76f,
    0f, .35f, 0f, 0f, 165f,
    0f, 0f, .08f, 0f, 234f,
    0f, 0f, 0f, 1f, 0f,
)))

@Composable
internal fun RevisionOverlayDialog(
    file: File,
    page: Int,
    currentLabel: String,
    comparison: IssuedSheet,
    files: PlanFiles,
    onDismiss: () -> Unit,
) {
    val other = files.file(comparison.drawing.sha256)
    val preview by key(file, page, other, comparison.page) {
        produceState<Result<Pair<PageBitmap, PageBitmap>>?>(null) {
            value = try {
                Result.success(PdfWork.render { loadPage(file, page) to loadPage(other, comparison.page) })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
        }
    }
    var position by remember { mutableStateOf(Offset.Zero) }
    var scale by remember { mutableStateOf(1f) }
    var angle by remember { mutableStateOf(0f) }
    var opacity by remember { mutableStateOf(.75f) }
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var panning by remember { mutableStateOf(false) }
    val previewResult = preview
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(10.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 4.dp,
        ) {
            Column(
                Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Revision overlay",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (previewResult?.isSuccess == true) {
                        TextButton(onClick = {
                            position = Offset.Zero
                            scale = 1f
                            angle = 0f
                            opacity = .75f
                            zoom = 1f
                            pan = Offset.Zero
                            panning = false
                        }) { Text("Reset") }
                    }
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
                Text(
                    "$currentLabel (black)  •  ${comparison.source.label} (blue)",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                when {
                    previewResult == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    previewResult.isFailure -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("A PDF page could not be loaded for comparison.")
                    }
                    else -> {
                        val (base, layer) = previewResult.getOrThrow()
                        val baseImage = remember(base) { base.image.asImageBitmap() }
                        val layerImage = remember(layer) { layer.image.asImageBitmap() }
                        Canvas(
                            Modifier.weight(1f).fillMaxWidth().clipToBounds().background(Color.White)
                                .pointerInput(base, panning) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        var lastPos = down.position
                                        var multi = false
                                        do {
                                            val event = awaitPointerEvent()
                                            if (event.changes.count { it.pressed } > 1) {
                                                multi = true
                                                val z = event.calculateZoom()
                                                val nextZoom = (zoom * z).coerceIn(1f, 5f)
                                                zoom = nextZoom
                                                pan = if (nextZoom <= 1.01f) Offset.Zero else pan + event.calculatePan()
                                                event.changes.forEach { it.consume() }
                                            } else if (!multi) {
                                                val change = event.changes.firstOrNull { it.id == down.id }
                                                if (change != null) {
                                                    val delta = change.position - lastPos
                                                    lastPos = change.position
                                                    if (panning) {
                                                        pan += delta
                                                    } else {
                                                        val fit = minOf(size.width / base.width, size.height / base.height)
                                                        position += delta / (fit * zoom)
                                                    }
                                                    change.consume()
                                                }
                                            }
                                        } while (event.changes.any { it.pressed })
                                    }
                                },
                        ) {
                            val fit = zoom * minOf(size.width / base.width, size.height / base.height)
                            val origin = center + pan
                            fun imageSize(sheet: PageBitmap) =
                                IntSize(
                                    (sheet.width * fit).roundToInt().coerceAtLeast(1),
                                    (sheet.height * fit).roundToInt().coerceAtLeast(1),
                                )
                            val baseSize = imageSize(base)
                            val layerSize = imageSize(layer)
                            clipRect {
                                withTransform({ translate(origin.x - baseSize.width / 2f, origin.y - baseSize.height / 2f) }) {
                                    drawImage(baseImage, dstSize = baseSize, colorFilter = grayscale)
                                }
                                withTransform({
                                    translate(origin.x + position.x * fit, origin.y + position.y * fit)
                                    rotate(angle, Offset.Zero)
                                    scale(scale, scale, Offset.Zero)
                                    translate(-layerSize.width / 2f, -layerSize.height / 2f)
                                }) {
                                    drawImage(
                                        layerImage,
                                        dstSize = layerSize,
                                        alpha = opacity,
                                        colorFilter = comparisonTint,
                                        blendMode = BlendMode.Darken,
                                    )
                                }
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FilterChip(selected = !panning, onClick = { panning = false }, label = { Text("Align blue") })
                            FilterChip(selected = panning, onClick = { panning = true }, label = { Text("Pan preview") })
                            Spacer(Modifier.weight(1f))
                            Text("Zoom ${(zoom * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                        }
                        Column(
                            Modifier.fillMaxWidth().heightIn(max = 175.dp).verticalScroll(rememberScrollState()),
                        ) {
                            Text("Opacity ${(opacity * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall)
                            Slider(opacity, { opacity = it })
                            Text("Scale ${(scale * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall)
                            Slider(scale, { scale = it }, valueRange = .5f..2f)
                            Text("Rotation ${angle.roundToInt()}°", style = MaterialTheme.typography.labelSmall)
                            Slider(angle, { angle = it }, valueRange = -180f..180f)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SheetExportDialog(
    source: PlanSheetSource,
    crop: PlanExportCrop?,
    onExport: (region: PlanExportCrop?, vector: Boolean, share: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var vector by rememberSaveable { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(source.label) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("PDF format", style = MaterialTheme.typography.labelLarge)
                Column(Modifier.selectableGroup()) {
                    ExportFormatRow(
                        title = "Vector PDF (preserves text & crisp lines)",
                        subtitle = "Default — keeps native CAD vectors and searchable text.",
                        selected = vector,
                        onSelect = { vector = true },
                    )
                    ExportFormatRow(
                        title = "Flattened raster snapshot (PDF)",
                        subtitle = "Renders the sheet and markups into a flattened image.",
                        selected = !vector,
                        onSelect = { vector = false },
                    )
                }
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = { onExport(null, vector, false) }) {
                        Text("Save annotated sheet")
                    }
                    TextButton(onClick = { onExport(null, vector, true) }) {
                        Text("Share annotated sheet")
                    }
                    if (crop != null) {
                        TextButton(onClick = { onExport(crop, vector, false) }) {
                            Text("Save current crop")
                        }
                        TextButton(onClick = { onExport(crop, vector, true) }) {
                            Text("Share current crop")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ExportFormatRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
