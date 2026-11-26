package com.thedirepack.constructioncalc.feature.drawings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.model.PlanDrawing
import com.thedirepack.constructioncalc.core.model.PlanMark
import com.thedirepack.constructioncalc.core.model.PlanMarkKind
import com.thedirepack.constructioncalc.core.model.PlanReviewState
import com.thedirepack.constructioncalc.core.model.PlanSymbolCounts
import com.thedirepack.constructioncalc.feature.drawings.recognition.DetectorStatus
import com.thedirepack.constructioncalc.feature.drawings.recognition.RecognitionProgress
import com.thedirepack.constructioncalc.feature.drawings.recognition.RecognitionStats
import java.io.File

/** Pure review edits on a drawing's symbol marks. [page] null means every page. */
internal object SymbolReview {
    private fun PlanMark.isSymbolOn(page: Int?) = kind == PlanMarkKind.SYMBOL && (page == null || this.page == page)

    fun setState(drawing: PlanDrawing, ids: Set<String>, state: PlanReviewState): PlanDrawing =
        drawing.copy(marks = drawing.marks.map { mark ->
            val symbol = mark.symbol
            if (mark.id in ids && symbol != null) mark.copy(symbol = symbol.copy(reviewState = state)) else mark
        })

    /** Pending detections grouped by device, largest group first. */
    fun pendingGroups(drawing: PlanDrawing, page: Int?): List<Pair<String, List<PlanMark>>> =
        drawing.marks.filter { it.isSymbolOn(page) && it.symbol?.reviewState == PlanReviewState.PENDING }
            .groupBy { it.symbol!!.deviceId }
            .toList()
            .sortedWith(compareByDescending<Pair<String, List<PlanMark>>> { it.second.size }.thenBy { it.first })

    /** Accepts (or rejects) every pending detection of [deviceId], or of every class when null. */
    fun decideClass(drawing: PlanDrawing, page: Int?, deviceId: String?, state: PlanReviewState): PlanDrawing {
        val ids = drawing.marks.filter {
            it.isSymbolOn(page) && it.symbol?.reviewState == PlanReviewState.PENDING && (deviceId == null || it.symbol?.deviceId == deviceId)
        }.mapTo(HashSet()) { it.id }
        return setState(drawing, ids, state)
    }

    /** Moves every pending detection of [fromDeviceId] to [choice]; they stay pending. */
    fun reclassifyClass(drawing: PlanDrawing, page: Int?, fromDeviceId: String, choice: SymbolChoice): PlanDrawing {
        val targets = drawing.marks.filter {
            it.isSymbolOn(page) && it.symbol?.reviewState == PlanReviewState.PENDING && it.symbol?.deviceId == fromDeviceId
        }
        return targets.fold(drawing) { d, mark -> reclassifySymbol(d, mark, choice) }
    }

    fun clearRejected(drawing: PlanDrawing, page: Int?): PlanDrawing =
        drawing.copy(marks = drawing.marks.filterNot { it.isSymbolOn(page) && it.symbol?.reviewState == PlanReviewState.REJECTED })

    /** The next pending detection on [page] after [currentId], reading order (top to bottom, left to right). */
    fun nextPending(drawing: PlanDrawing, page: Int, currentId: String?): PlanMark? {
        val pending = drawing.marks.filter { it.isSymbolOn(page) && it.symbol?.reviewState == PlanReviewState.PENDING }
            .sortedWith(compareBy({ it.points.first().y }, { it.points.first().x }))
        if (pending.isEmpty()) return null
        val index = pending.indexOfFirst { it.id == currentId }
        return pending[(index + 1) % pending.size]
    }
}

internal fun recognitionSummary(stats: RecognitionStats, cancelled: Boolean): String = buildString {
    append(if (cancelled) "Recognition stopped. " else "")
    append(
        when (stats.proposed) {
            0 -> "No new symbols found"
            1 -> "Found 1 symbol to review"
            else -> "Found ${stats.proposed} symbols to review"
        },
    )
    val notes = buildList {
        if (stats.alreadyMarked > 0) add("${stats.alreadyMarked} already marked")
        if (stats.unknownClassDropped > 0) add("${stats.unknownClassDropped} not in the device catalog skipped")
    }
    if (notes.isNotEmpty()) append(" (").append(notes.joinToString(", ")).append(")")
    append('.')
}

internal sealed interface RecognitionRequest {
    data object ThisPage : RecognitionRequest
    data class Pages(val pages: List<Int>) : RecognitionRequest
    data object AllPages : RecognitionRequest
    data object AllDrawings : RecognitionRequest
}

private enum class RecognizeChoice(val label: String) {
    PAGE("This page"), PICK("Choose pages…"), ALL_PAGES("All pages of this drawing"), ALL_DRAWINGS("All drawings in the project"),
}

/** Where to look for symbols. Says plainly when no recognition model is installed. */
@Composable
internal fun RecognizeDialog(
    status: DetectorStatus,
    file: File,
    page: Int,
    pageCount: Int,
    drawingCount: Int,
    labels: Map<Int, SheetLabel>,
    onRun: (RecognitionRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    val ready = status is DetectorStatus.Ready
    var choice by rememberSaveable { mutableStateOf(RecognizeChoice.PAGE) }
    val (picked, toggle) = rememberPageSelection(setOf(page))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Recognize symbols") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!ready) {
                    Text(
                        "Symbol recognition isn't installed on this device yet. When the recognition model is " +
                            "available, this finds every catalog device on a page and adds it for you to review.",
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text("Place symbols by hand with the Symbol tool in the meantime.", style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                } else {
                    Text(
                        "Found symbols are added as pending marks. Only the ones you accept are counted.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                RecognizeChoice.entries.filter { it != RecognizeChoice.ALL_DRAWINGS || drawingCount > 1 }.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = choice == option, enabled = ready, onClick = { choice = option }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = choice == option, onClick = null, enabled = ready)
                        Text(
                            when (option) {
                                RecognizeChoice.PAGE -> "${option.label} (${labels[page]?.number?.takeIf(String::isNotBlank) ?: "page ${page + 1}"})"
                                RecognizeChoice.ALL_PAGES -> "${option.label} ($pageCount)"
                                RecognizeChoice.ALL_DRAWINGS -> "${option.label} ($drawingCount)"
                                RecognizeChoice.PICK -> if (choice == option) "${option.label} (${picked.size} selected)" else option.label
                            },
                            Modifier.padding(start = 4.dp),
                        )
                    }
                }
                if (ready && choice == RecognizeChoice.PICK) {
                    PageThumbnailGrid(file, pageCount, current = page, labels = labels, selected = picked, onToggle = toggle)
                }
            }
        },
        confirmButton = {
            if (ready) {
                TextButton(
                    enabled = choice != RecognizeChoice.PICK || picked.isNotEmpty(),
                    onClick = {
                        onRun(
                            when (choice) {
                                RecognizeChoice.PAGE -> RecognitionRequest.ThisPage
                                RecognizeChoice.PICK -> RecognitionRequest.Pages(picked.sorted())
                                RecognizeChoice.ALL_PAGES -> RecognitionRequest.AllPages
                                RecognizeChoice.ALL_DRAWINGS -> RecognitionRequest.AllDrawings
                            },
                        )
                    },
                ) { Text("Recognize") }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = if (ready) ({ TextButton(onClick = onDismiss) { Text("Cancel") } }) else null,
    )
}

/** Progress of a recognition run; it runs off the main thread and Stop keeps what was found. */
@Composable
internal fun RecognitionProgressDialog(progress: RecognitionProgress?, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Recognizing symbols") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (progress == null || progress.page < 0) {
                    Text("Starting…")
                    LinearProgressIndicator(progress = { 0f }, modifier = Modifier.fillMaxWidth())
                } else {
                    Text(
                        "${progress.drawingName} · page ${progress.page + 1} (${progress.pagesDone + 1} of ${progress.pagesTotal})",
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text("Stop") } },
    )
}

/** Pending detections on this page by device: accept, reject or change a whole class at once. */
@Composable
internal fun SymbolReviewDialog(
    drawing: PlanDrawing,
    page: Int,
    editable: Boolean,
    onEdit: ((PlanDrawing) -> PlanDrawing) -> Unit,
    onChangeClass: (fromDeviceId: String) -> Unit,
    onNext: () -> Unit,
    onDismiss: () -> Unit,
) {
    val groups = remember(drawing.marks, page) { SymbolReview.pendingGroups(drawing, page) }
    val elsewhere = remember(drawing.marks, page) { PlanSymbolCounts.pending(drawing).count { it.page != page } }
    val rejected = remember(drawing.marks, page) {
        drawing.marks.count { it.page == page && it.symbol?.reviewState == PlanReviewState.REJECTED }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review symbols") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (groups.isEmpty()) Text("Nothing to review on this page.")
                else Text("Orange marks are recognized symbols. They count once accepted.", style = MaterialTheme.typography.bodySmall)
                if (elsewhere > 0) Text("$elsewhere more pending on other pages.", style = MaterialTheme.typography.bodySmall)
                groups.forEach { (deviceId, marks) ->
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DevicePreview(deviceId, drawing.customSymbols)
                        Column(Modifier.weight(1f)) {
                            Text(PlanSymbolCounts.name(drawing, deviceId), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val confidences = marks.mapNotNull { it.symbol?.confidence }
                            val detail = if (confidences.isEmpty()) "${marks.size} pending"
                            else "${marks.size} pending · ${(confidences.min() * 100).toInt()}–${(confidences.max() * 100).toInt()}% sure"
                            Text(detail, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (editable) {
                        Row {
                            TextButton(onClick = { onEdit { SymbolReview.decideClass(it, page, deviceId, PlanReviewState.ACCEPTED) } }) {
                                Text("Accept all ${marks.size}")
                            }
                            TextButton(onClick = { onEdit { SymbolReview.decideClass(it, page, deviceId, PlanReviewState.REJECTED) } }) {
                                Text("Reject all")
                            }
                            TextButton(onClick = { onChangeClass(deviceId) }) { Text("Change class") }
                        }
                    }
                }
                if (editable && rejected > 0) {
                    HorizontalDivider()
                    TextButton(onClick = { onEdit { SymbolReview.clearRejected(it, page) } }) { Text("Remove $rejected rejected mark(s)") }
                }
            }
        },
        confirmButton = {
            Row {
                if (groups.isNotEmpty()) TextButton(onClick = onNext) { Text("Review one by one") }
                if (editable && groups.size > 1) {
                    TextButton(onClick = { onEdit { SymbolReview.decideClass(it, page, null, PlanReviewState.ACCEPTED) } }) { Text("Accept page") }
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}
