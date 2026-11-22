package com.thedirepack.constructioncalc.feature.drawings

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import android.util.LruCache
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class SheetLabel(val number: String, val title: String = "") {
    fun matches(query: String) = "$number $title".contains(query, ignoreCase = true)
}

private val sheetNumber = Regex("""[A-Z]{1,4}[- .]?\d{1,3}(?:[.-]\d{1,2})?[A-Z]?""")

internal fun titleBlockLabel(text: String): SheetLabel? {
    val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    val index = lines.indexOfLast(sheetNumber::matches)
    if (index < 0) return null
    val above = lines.take(index)
    val titleStart = above.indexOfLast { it.startsWith("SHEET TITLE:", ignoreCase = true) }
    val titleLines = if (titleStart >= 0) {
        listOf(above[titleStart].substringAfter(':').trim()) + above.drop(titleStart + 1)
    } else above.takeLast(4)
    val title = titleLines.takeWhile { !it.startsWith("SHEET NUMBER:", ignoreCase = true) }
        .filter { line ->
            line.count(Char::isLetter) >= 3 && ':' !in line
        }
        .takeLast(3).joinToString(" ")
    return SheetLabel(lines[index], title.take(120))
}

internal suspend fun indexSheets(context: Context, file: File, update: (Int, SheetLabel) -> Unit) =
    withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context)
        PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)).use { pdf ->
            val labels = pdf.documentCatalog.pageLabels?.labelsByPageIndices
            val stripper = object : PDFTextStripper() {
                override fun processTextPosition(text: TextPosition) {
                    // Prefer text in the title block. Scans and sheets with a different layout
                    // can still be opened by thumbnail or page number.
                    if (text.xDirAdj > text.pageWidth * .85f && text.yDirAdj > text.pageHeight * .80f) {
                        super.processTextPosition(text)
                    }
                }
            }.apply { sortByPosition = true }
            for (index in 0 until pdf.numberOfPages) {
                currentCoroutineContext().ensureActive()
                stripper.startPage = index + 1
                stripper.endPage = index + 1
                val explicit = labels?.getOrNull(index)?.takeIf { it.isNotBlank() && it != (index + 1).toString() }
                val inferred = titleBlockLabel(stripper.getText(pdf))
                val label = if (explicit == null) inferred else SheetLabel(explicit.take(80), inferred?.title.orEmpty())
                if (label != null) withContext(Dispatchers.Main) { update(index, label) }
            }
        }
    }

@Composable
internal fun SheetBrowser(
    file: File,
    drawingName: String,
    projectName: String,
    page: Int,
    count: Int,
    labels: Map<Int, SheetLabel>,
    indexing: Boolean,
    onDismiss: () -> Unit,
    onPage: (Int) -> Unit,
) {
    var search by remember { mutableStateOf("") }
    val query = search.trim()
    val pages = (0 until count).filter {
        query.isEmpty() || (it + 1).toString() == query || labels[it]?.matches(query) == true
    }
    val listState = rememberLazyListState(page)
    val previews = remember(file) { LruCache<Int, ImageBitmap>(24) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sheets") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(projectName, style = MaterialTheme.typography.labelMedium)
                Text(drawingName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it; listState.requestScrollToItem(0) },
                    label = { Text("Sheet number or title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (pages.isEmpty()) Text(if (indexing) "Looking for sheet labels…" else "No matching sheets")
                LazyColumn(state = listState, modifier = Modifier.heightIn(max = 380.dp)) {
                    items(pages, key = { it }) { index ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPage(index) }.padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            SheetThumbnail(file, index, previews)
                            Column {
                                Text(labels[index]?.number ?: "Page ${index + 1}", style = MaterialTheme.typography.titleSmall)
                                labels[index]?.title?.takeIf(String::isNotBlank)?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                                if (labels[index] != null || index == page) {
                                    Text("Page ${index + 1}" + if (index == page) " · Current" else "", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun SheetThumbnail(file: File, page: Int, previews: LruCache<Int, ImageBitmap>) {
    val image by produceState(previews.get(page), file, page) {
        if (value != null) return@produceState
        value = try {
            PdfWork.render { loadPage(file, page, 144f).image.asImageBitmap() }
                .also { previews.put(page, it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
    androidx.compose.foundation.layout.Box(Modifier.size(width = 88.dp, height = 62.dp)) {
        image?.let { Image(it, null, modifier = Modifier.matchParentSize()) }
    }
}
