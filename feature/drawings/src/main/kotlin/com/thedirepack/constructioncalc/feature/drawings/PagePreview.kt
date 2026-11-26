package com.thedirepack.constructioncalc.feature.drawings

import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Thumbnails rendered for a PDF, shared by every grid showing that file in this session. */
private val thumbnailCache = object : LruCache<String, ImageBitmap>(8 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = (value.width * value.height * 4 / 1024).coerceAtLeast(1)
}

@Composable
internal fun PageThumbnail(file: File, page: Int, modifier: Modifier = Modifier) {
    val key = "${file.absolutePath}#$page"
    val image by produceState(thumbnailCache.get(key), key) {
        if (value != null) return@produceState
        value = try {
            PdfWork.render { loadPage(file, page, 220f).image.asImageBitmap() }.also { thumbnailCache.put(key, it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
    Box(modifier.background(Color.White), contentAlignment = Alignment.Center) {
        image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.matchParentSize()) }
            ?: Text("${page + 1}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
    }
}

/**
 * A grid of page thumbnails. Tapping a page calls [onOpen]; when [selected] is given, each page
 * also has a checkbox to pick it (for recognizing several pages).
 */
@Composable
internal fun PageThumbnailGrid(
    file: File,
    pageCount: Int,
    current: Int?,
    labels: Map<Int, SheetLabel>,
    modifier: Modifier = Modifier,
    selected: Set<Int>? = null,
    onToggle: (Int) -> Unit = {},
    onOpen: ((Int) -> Unit)? = null,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp),
        modifier = modifier.heightIn(max = 420.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items((0 until pageCount).toList(), key = { it }) { page ->
            val isCurrent = page == current
            val shape = RoundedCornerShape(4.dp)
            Column(
                Modifier.clickable { if (onOpen != null) onOpen(page) else onToggle(page) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    PageThumbnail(
                        file, page,
                        Modifier.fillMaxWidth().aspectRatio(0.77f).border(
                            if (isCurrent) 2.dp else 1.dp,
                            if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            shape,
                        ),
                    )
                    if (selected != null) {
                        Checkbox(checked = page in selected, onCheckedChange = { onToggle(page) }, modifier = Modifier.align(Alignment.TopEnd))
                    }
                }
                Text(
                    labels[page]?.number?.takeIf(String::isNotBlank) ?: "Page ${page + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * Shown after a PDF is picked and before it is added to the project: its pages, so a wrong file
 * can be cancelled. Tapping a page imports and opens on it.
 */
@Composable
internal fun PdfImportPreviewDialog(
    file: File,
    name: String,
    onImport: (openPage: Int) -> Unit,
    onCancel: () -> Unit,
) {
    val pageCount by produceState<Int?>(null, file) {
        value = try {
            withContext(Dispatchers.IO) { pdfPageCount(file) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            0
        }
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Import $name?", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (val count = pageCount) {
                    null -> Text("Reading pages…")
                    0 -> Text("This PDF can't be opened.", color = MaterialTheme.colorScheme.error)
                    else -> {
                        Text(if (count == 1) "1 page. Tap a page to import and open it." else "$count pages. Tap a page to import and open it.", style = MaterialTheme.typography.bodySmall)
                        PageThumbnailGrid(file, count, current = null, labels = emptyMap(), onOpen = onImport)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onImport(0) }, enabled = (pageCount ?: 0) > 0) { Text("Import") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** Remembers a page selection across recompositions for a page picker. */
@Composable
internal fun rememberPageSelection(initial: Set<Int>): Pair<Set<Int>, (Int) -> Unit> {
    var selected by rememberSaveable { mutableStateOf(initial.toList()) }
    val set = remember(selected) { selected.toSet() }
    return set to { page -> selected = if (page in set) selected - page else selected + page }
}
