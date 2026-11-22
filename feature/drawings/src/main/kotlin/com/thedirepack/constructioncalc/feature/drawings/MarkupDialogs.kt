package com.thedirepack.constructioncalc.feature.drawings

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.data.PlanFiles
import com.thedirepack.constructioncalc.core.data.PlanPhotos
import com.thedirepack.constructioncalc.core.model.PlanArrowStyle
import com.thedirepack.constructioncalc.core.model.PlanDashStyle
import com.thedirepack.constructioncalc.core.model.PlanMarkKind
import com.thedirepack.constructioncalc.core.model.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

private fun dashLabel(style: PlanDashStyle): String = when (style) {
    PlanDashStyle.SOLID -> "Solid"
    PlanDashStyle.DASHED -> "Dashed"
    PlanDashStyle.DOTTED -> "Dotted"
}

private fun arrowLabel(style: PlanArrowStyle): String = when (style) {
    PlanArrowStyle.NONE -> "None"
    PlanArrowStyle.END -> "End"
    PlanArrowStyle.BOTH -> "Both"
    PlanArrowStyle.FILLED_END -> "Filled end"
    PlanArrowStyle.FILLED_BOTH -> "Filled both"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MarkupStyleDialog(
    title: String,
    kind: PlanMarkKind,
    initial: MarkupToolStyle,
    onDismiss: () -> Unit,
    onConfirm: (MarkupToolStyle) -> Unit,
) {
    var style by remember { mutableStateOf(initial) }
    val widthPresets = when {
        kind.isTextSized() -> listOf(9.0, 11.0, 13.0, 16.0, 20.0, 26.0)
        kind == PlanMarkKind.HIGHLIGHT -> listOf(6.0, 10.0, 12.0, 18.0, 24.0)
        kind == PlanMarkKind.PHOTO || kind == PlanMarkKind.DETAIL_LINK -> listOf(18.0, 24.0, 32.0, 42.0)
        else -> listOf(1.0, 2.0, 3.5, 5.0, 8.0)
    }
    val widthRange = when {
        kind.isTextSized() -> 8f..32f
        kind == PlanMarkKind.HIGHLIGHT -> 4f..32f
        kind == PlanMarkKind.PHOTO || kind == PlanMarkKind.DETAIL_LINK -> 14f..48f
        else -> 0.8f..12f
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Color", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (option in markupColorPalette) {
                        val selected = (style.color and 0xFFFFFFL) == (option.argb and 0xFFFFFFL)
                        val ringColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(option.argb.toInt()))
                                .border(if (selected) 3.dp else 1.dp, ringColor, CircleShape)
                                .clickable { style = style.copy(color = option.argb) }
                                .semantics { contentDescription = option.name },
                        )
                    }
                }

                val sizeLabel = when {
                    kind.isTextSized() -> "Text size · ${style.width.roundToInt()} pt"
                    kind == PlanMarkKind.HIGHLIGHT -> "Highlight width · ${style.width.roundToInt()} pt"
                    kind == PlanMarkKind.PHOTO || kind == PlanMarkKind.DETAIL_LINK -> "Badge size · ${style.width.roundToInt()} pt"
                    else -> "Line width · ${String.format(Locale.US, "%.1f", style.width)} pt"
                }
                Text(sizeLabel, style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (preset in widthPresets) {
                        val selected = kotlin.math.abs(style.width - preset) < 0.2
                        FilterChip(
                            selected = selected,
                            onClick = { style = style.copy(width = preset) },
                            label = { Text(if (preset == preset.toInt().toDouble()) "${preset.toInt()}" else "$preset") },
                        )
                    }
                }
                Slider(
                    value = style.width.toFloat().coerceIn(widthRange.start, widthRange.endInclusive),
                    onValueChange = { style = style.copy(width = it.toDouble()) },
                    valueRange = widthRange,
                )

                Text("Opacity · ${(style.opacity * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (pct in listOf(0.25f, 0.40f, 0.70f, 1.0f)) {
                        FilterChip(
                            selected = kotlin.math.abs(style.opacity - pct) < 0.04f,
                            onClick = { style = style.copy(opacity = pct) },
                            label = { Text("${(pct * 100).roundToInt()}%") },
                        )
                    }
                }
                Slider(
                    value = style.opacity.coerceIn(0.10f, 1f),
                    onValueChange = { style = style.copy(opacity = it) },
                    valueRange = 0.10f..1f,
                )

                if (kind.supportsDash()) {
                    Text("Line pattern", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (dash in PlanDashStyle.entries) {
                            FilterChip(
                                selected = style.dash == dash,
                                onClick = { style = style.copy(dash = dash) },
                                label = { Text(dashLabel(dash)) },
                            )
                        }
                    }
                }

                if (kind.supportsArrowStyle()) {
                    Text("Arrowheads", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val currentArrow = style.arrowStyle ?: if (kind == PlanMarkKind.LINE) PlanArrowStyle.NONE else PlanArrowStyle.END
                        for (arrow in PlanArrowStyle.entries) {
                            FilterChip(
                                selected = currentArrow == arrow,
                                onClick = { style = style.copy(arrowStyle = arrow) },
                                label = { Text(arrowLabel(arrow)) },
                            )
                        }
                    }
                }

                if (kind.supportsFill()) {
                    HorizontalDivider()
                    Text("Background / fill", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val hasFill = style.fillColor != null && style.fillOpacity > 0f
                        FilterChip(
                            selected = !hasFill,
                            onClick = { style = style.copy(fillColor = null, fillOpacity = 0f) },
                            label = { Text("None") },
                        )
                        val fillChoices = listOf(MarkupColorOption("White", 0xFFFFFFFFL)) + markupColorPalette
                        for (option in fillChoices) {
                            val selected = hasFill && (style.fillColor?.and(0xFFFFFFL) == (option.argb and 0xFFFFFFL))
                            val ringColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                            Box(
                                Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(Color(option.argb.toInt()))
                                    .border(if (selected) 3.dp else 1.dp, ringColor, CircleShape)
                                    .clickable {
                                        val defaultFillOpacity = if (kind == PlanMarkKind.CALLOUT || kind == PlanMarkKind.CLOUD_CALLOUT || kind == PlanMarkKind.TEXT) 0.90f else 0.25f
                                        style = style.copy(
                                            fillColor = option.argb,
                                            fillOpacity = if (style.fillOpacity > 0f) style.fillOpacity else defaultFillOpacity,
                                        )
                                    }
                                    .semantics { contentDescription = "Fill ${option.name}" },
                            )
                        }
                    }
                    if (style.fillColor != null && style.fillOpacity > 0f) {
                        Text("Fill opacity · ${(style.fillOpacity * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (pct in listOf(0.15f, 0.35f, 0.70f, 0.95f)) {
                                FilterChip(
                                    selected = kotlin.math.abs(style.fillOpacity - pct) < 0.05f,
                                    onClick = { style = style.copy(fillOpacity = pct) },
                                    label = { Text("${(pct * 100).roundToInt()}%") },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(style) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun AreaTagDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var tag by rememberSaveable(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Area / room tag") },
        text = {
            OutlinedTextField(
                value = tag,
                onValueChange = { tag = it.take(80) },
                singleLine = true,
                label = { Text("Area or room (e.g. Kitchen, Mech 102)") },
            )
        },
        confirmButton = { TextButton(onClick = { onSave(tag.trim().take(80)) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DetailLinkDialog(
    project: Project,
    currentDrawingId: String,
    currentPage: Int,
    currentPageCount: Int,
    currentLabels: Map<Int, SheetLabel>,
    initialTargetDrawingId: String? = null,
    initialTargetPage: Int? = null,
    initialLabel: String = "",
    onDismiss: () -> Unit,
    onSave: (targetDrawingId: String?, targetPage: Int, label: String) -> Unit,
) {
    val context = LocalContext.current
    val files = remember(context) { PlanFiles(context.applicationContext) }
    var targetDrawingId by rememberSaveable { mutableStateOf(initialTargetDrawingId ?: currentDrawingId) }
    val targetDrawing = project.drawings.firstOrNull { it.id == targetDrawingId } ?: project.drawings.firstOrNull { it.id == currentDrawingId }
    val resolvedDrawingId = targetDrawing?.id ?: currentDrawingId
    val resolvedPageCount by produceState(initialValue = if (resolvedDrawingId == currentDrawingId) currentPageCount else 1, resolvedDrawingId) {
        value = if (resolvedDrawingId == currentDrawingId || targetDrawing == null) {
            currentPageCount.coerceAtLeast(1)
        } else {
            try {
                withContext(Dispatchers.IO) { pdfPageCount(files.file(targetDrawing.sha256)).coerceAtLeast(1) }
            } catch (_: Exception) {
                1
            }
        }
    }
    var targetPage by rememberSaveable { mutableStateOf((initialTargetPage ?: currentPage).coerceIn(0, (resolvedPageCount - 1).coerceAtLeast(0))) }
    if (targetPage >= resolvedPageCount) targetPage = (resolvedPageCount - 1).coerceAtLeast(0)
    var pageInput by rememberSaveable(resolvedDrawingId, targetPage) { mutableStateOf((targetPage + 1).toString()) }
    val targetRevLabel = targetDrawing?.sheetRevisions?.get(targetPage)?.sheetId
        ?: if (resolvedDrawingId == currentDrawingId) currentLabels[targetPage]?.number else null
    val defaultCaption = targetRevLabel?.takeIf { it.isNotBlank() } ?: "Page ${targetPage + 1}"
    var label by rememberSaveable { mutableStateOf(initialLabel.ifBlank { "" }) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link to sheet or detail") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (project.drawings.size > 1) {
                    Text("Target PDF", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (d in project.drawings) {
                            FilterChip(
                                selected = d.id == resolvedDrawingId,
                                onClick = {
                                    targetDrawingId = d.id
                                    targetPage = 0
                                },
                                label = { Text(if (d.id == currentDrawingId) "${d.name} (this)" else d.name) },
                            )
                        }
                    }
                }
                Text("Target page (1–$resolvedPageCount)", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = targetPage > 0, onClick = { targetPage -= 1 }) { Text("−") }
                    OutlinedTextField(
                        value = pageInput,
                        onValueChange = { raw ->
                            val digits = raw.filter { it in '0'..'9' }.take(4)
                            pageInput = digits
                            digits.toIntOrNull()?.let { n ->
                                if (n in 1..resolvedPageCount) targetPage = n - 1
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        label = { Text("Page") },
                    )
                    TextButton(enabled = targetPage + 1 < resolvedPageCount, onClick = { targetPage += 1 }) { Text("+") }
                }
                val sheetChoices = buildList {
                    if (resolvedDrawingId == currentDrawingId) {
                        currentLabels.toSortedMap().entries.take(24).forEach { (idx, sl) ->
                            add(idx to listOfNotNull(sl.number.takeIf(String::isNotBlank), "P${idx + 1}").joinToString(" · "))
                        }
                    } else if (targetDrawing != null) {
                        targetDrawing.sheetRevisions.toSortedMap().entries.take(24).forEach { (idx, rev) ->
                            add(idx to "${rev.sheetId} · P${idx + 1}")
                        }
                    }
                }
                if (sheetChoices.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        sheetChoices.forEach { (idx, text) ->
                            FilterChip(
                                selected = targetPage == idx,
                                onClick = { targetPage = idx },
                                label = { Text(text) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(120) },
                    label = { Text("Callout label") },
                    placeholder = { Text(defaultCaption) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val saveDrawingId = if (resolvedDrawingId == currentDrawingId && initialTargetDrawingId == null) null else resolvedDrawingId
                onSave(saveDrawingId, targetPage, label.trim().ifBlank { defaultCaption })
            }) { Text("Save link") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun PhotoPinDialog(
    onDismiss: () -> Unit,
    onSave: (photoSha256: String, caption: String) -> Unit,
) {
    val context = LocalContext.current
    val photos = remember(context) { PlanPhotos(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var sha256 by rememberSaveable { mutableStateOf<String?>(null) }
    var caption by rememberSaveable { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true
            error = null
            scope.launch {
                try {
                    sha256 = photos.import(uri)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    error = e.message ?: "Could not import this photo."
                } finally {
                    importing = false
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pin field photo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { picker.launch(arrayOf("image/*")) }, enabled = !importing) {
                    Text(
                        when {
                            importing -> "Importing photo…"
                            sha256 != null -> "Choose a different photo"
                            else -> "Choose photo"
                        },
                    )
                }
                if (sha256 != null) {
                    Text("Photo stored offline (${sha256!!.take(8)}…).", style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it.take(160) },
                    label = { Text("Caption (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = sha256 != null && !importing,
                onClick = { sha256?.let { onSave(it, caption.trim()) } },
            ) { Text("Pin photo") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun PhotoViewerDialog(
    photoSha256: String?,
    initialCaption: String,
    editable: Boolean,
    onReplacePhoto: (String) -> Unit,
    onUpdateCaption: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val photos = remember(context) { PlanPhotos(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var caption by rememberSaveable(photoSha256, initialCaption) { mutableStateOf(initialCaption) }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val bitmap by produceState<Bitmap?>(null, photoSha256) {
        value = if (photoSha256 == null) null else try {
            withContext(Dispatchers.IO) {
                val file = photos.file(photoSha256)
                if (!file.isFile) null else BitmapFactory.decodeFile(file.absolutePath)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
    DisposableEffect(bitmap) {
        val current = bitmap
        onDispose {
            if (current != null && !current.isRecycled) current.recycle()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true
            error = null
            scope.launch {
                try {
                    val newSha = photos.import(uri)
                    onReplacePhoto(newSha)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    error = e.message ?: "Could not import this photo."
                } finally {
                    importing = false
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pinned photo") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val loaded = bitmap
                if (loaded != null && !loaded.isRecycled) {
                    var photoZoom by remember(photoSha256) { mutableStateOf(1f) }
                    var photoOffset by remember(photoSha256) { mutableStateOf(Offset.Zero) }
                    val doubleTapDist = with(LocalDensity.current) { 48.dp.toPx() }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .clipToBounds()
                            .pointerInput(photoSha256) {
                                var lastTapMs = 0L
                                var lastTapPos = Offset.Zero
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    var lastPos = down.position
                                    var moved = false
                                    var multi = false
                                    do {
                                        val event = awaitPointerEvent()
                                        if (event.changes.count { it.pressed } > 1) {
                                            multi = true
                                            val nextZoom = (photoZoom * event.calculateZoom()).coerceIn(1f, 5f)
                                            photoZoom = nextZoom
                                            photoOffset = if (nextZoom <= 1.02f) Offset.Zero else photoOffset + event.calculatePan()
                                            event.changes.forEach { it.consume() }
                                        } else if (!multi && photoZoom > 1.02f) {
                                            val change = event.changes.firstOrNull { it.id == down.id }
                                            if (change != null) {
                                                val delta = change.position - lastPos
                                                moved = moved || (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                                                lastPos = change.position
                                                if (moved) {
                                                    photoOffset += delta
                                                    change.consume()
                                                }
                                            }
                                        } else if (!multi) {
                                            val change = event.changes.firstOrNull { it.id == down.id }
                                            if (change != null) {
                                                moved = moved || (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                                            }
                                        }
                                    } while (event.changes.any { it.pressed })
                                    if (!multi && !moved) {
                                        if (isDoubleTap(lastTapMs, lastTapPos, down.uptimeMillis, down.position, doubleTapDist)) {
                                            lastTapMs = 0L
                                            if (photoZoom > 1.25f) {
                                                photoZoom = 1f
                                                photoOffset = Offset.Zero
                                            } else {
                                                photoZoom = 2.5f
                                            }
                                        } else {
                                            lastTapMs = down.uptimeMillis
                                            lastTapPos = down.position
                                        }
                                    }
                                }
                            },
                    ) {
                        Image(
                            bitmap = loaded.asImageBitmap(),
                            contentDescription = caption.ifBlank { "Field photo" },
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp)
                                .graphicsLayer {
                                    scaleX = photoZoom
                                    scaleY = photoZoom
                                    translationX = photoOffset.x
                                    translationY = photoOffset.y
                                },
                        )
                    }
                    if (photoZoom > 1.05f) {
                        FilterChip(
                            selected = true,
                            onClick = {
                                photoZoom = 1f
                                photoOffset = Offset.Zero
                            },
                            label = { Text("Reset zoom (${(photoZoom * 100).roundToInt()}%)") },
                        )
                    }
                } else {
                    Text(
                        if (photoSha256 == null) "No photo attached."
                        else "This photo file isn't on this device yet.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (editable) {
                    OutlinedTextField(
                        value = caption,
                        onValueChange = { caption = it.take(160) },
                        label = { Text("Caption") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { picker.launch(arrayOf("image/*")) }, enabled = !importing) {
                        Text(if (importing) "Replacing photo…" else "Replace photo")
                    }
                    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                } else if (caption.isNotBlank()) {
                    Text(caption)
                }
            }
        },
        confirmButton = {
            if (editable) {
                TextButton(onClick = {
                    if (caption.trim() != initialCaption) onUpdateCaption(caption.trim())
                    onDismiss()
                }) { Text("Save") }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            if (editable) TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
