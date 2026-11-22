package com.thedirepack.constructioncalc.feature.drawings

import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.ui.R as CoreR
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
internal fun SheetBrowser(
    file: File,
    drawingSha256: String,
    drawingName: String,
    projectName: String,
    page: Int,
    count: Int,
    labels: Map<Int, SheetLabel>,
    customLabels: Map<Int, SheetLabel>,
    indexing: Boolean,
    visits: SheetVisits,
    onFavorite: (Int) -> Unit,
    onLabel: (Int, SheetLabel?) -> Unit,
    onDismiss: () -> Unit,
    onPage: (Int) -> Unit,
) {
    var search by rememberSaveable { mutableStateOf("") }
    var includePdfText by rememberSaveable { mutableStateOf(false) }
    var scope by rememberSaveable { mutableStateOf(SheetScope.ALL) }
    var discipline by rememberSaveable { mutableStateOf<SheetDiscipline?>(null) }
    var disciplinesOpen by remember { mutableStateOf(false) }
    var labelPage by rememberSaveable { mutableStateOf<Int?>(null) }
    val query = normalizeSheetText(search)
    val context = LocalContext.current
    var textMatches by remember(file, query, includePdfText) { mutableStateOf<PdfTextMatches?>(null) }
    LaunchedEffect(file, drawingSha256, query, includePdfText) {
        if (!includePdfText || query.isEmpty()) return@LaunchedEffect
        delay(300)
        textMatches = try {
            searchPdfText(context.applicationContext, drawingSha256, file, query)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PdfTextMatches(status = "PDF text could not be read.")
        }
    }
    val sheetLabels = remember(labels, customLabels) { labels + customLabels }
    val disciplines = remember(sheetLabels) { sheetLabels.mapValues { sheetDiscipline(it.value) } }
    val pages = remember(count, scope, visits, disciplines, discipline, query, sheetLabels, textMatches) {
        visits.pages(count, scope).filter { index ->
            (discipline == null || (disciplines[index] ?: SheetDiscipline.UNCLASSIFIED) == discipline) &&
                (query.isEmpty() || (index + 1).toString() == query || sheetLabels[index]?.matches(query) == true ||
                    textMatches?.pages?.contains(index) == true)
        }.toList()
    }
    val listState = rememberLazyListState(page)
    val previews = remember(file) { LruCache<Int, ImageBitmap>(24) }
    val searching = includePdfText && query.isNotEmpty() && textMatches == null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sheets") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(projectName, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(drawingName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                OutlinedTextField(
                    value = search,
                    onValueChange = {
                        search = it
                        listState.requestScrollToItem(0)
                    },
                    label = { Text(if (includePdfText) "Sheets and PDF text" else "Sheet number or title") },
                    trailingIcon = if (search.isNotEmpty()) {
                        {
                            IconButton(onClick = {
                                search = ""
                                listState.requestScrollToItem(0)
                            }) {
                                Icon(painterResource(CoreR.drawable.ic_close), contentDescription = "Clear search")
                            }
                        }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SheetScope.entries.forEach { option ->
                        FilterChip(selected = scope == option, onClick = {
                            scope = option
                            listState.requestScrollToItem(0)
                        }, label = { Text(option.label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.weight(1f, fill = false)) {
                        FilterChip(selected = discipline != null, onClick = { disciplinesOpen = true }, label = {
                            Text(discipline?.label ?: "Discipline", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        })
                        DropdownMenu(expanded = disciplinesOpen, onDismissRequest = { disciplinesOpen = false }) {
                            val available = SheetDiscipline.entries.filter {
                                it in disciplines.values || it == discipline ||
                                    it == SheetDiscipline.UNCLASSIFIED && disciplines.size < count
                            }
                            (listOf(null) + available).forEach { option ->
                                DropdownMenuItem(text = { Text(option?.label ?: "All disciplines") }, onClick = {
                                    discipline = option
                                    disciplinesOpen = false
                                    listState.requestScrollToItem(0)
                                })
                            }
                        }
                    }
                    FilterChip(selected = includePdfText, onClick = {
                        includePdfText = !includePdfText
                        listState.requestScrollToItem(0)
                    }, label = { Text("PDF text") })
                }
                if (searching) Text("Searching PDF text…", style = MaterialTheme.typography.labelSmall)
                textMatches?.status?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                if (pages.isEmpty() && !searching) {
                    Text(when {
                        indexing && (query.isNotEmpty() || discipline != null) -> "Looking for sheet labels…"
                        scope == SheetScope.FAVORITES && visits.favorites.isEmpty() -> "Star sheets to find them here."
                        else -> "No matching sheets"
                    })
                }
                LazyColumn(state = listState, modifier = Modifier.heightIn(max = 380.dp)) {
                    items(pages, key = { it }) { index ->
                        SheetRow(
                            file = file,
                            index = index,
                            label = sheetLabels[index],
                            current = index == page,
                            favorite = index in visits.favorites,
                            previews = previews,
                            onOpen = { onPage(index) },
                            onFavorite = { onFavorite(index) },
                        )
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = { labelPage = page }) { Text("Label current sheet") } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
    labelPage?.let { index ->
        SheetLabelDialog(index, sheetLabels[index], index in customLabels, onDismiss = { labelPage = null }) {
            onLabel(index, it)
            labelPage = null
        }
    }
}

@Composable
private fun SheetRow(
    file: File,
    index: Int,
    label: SheetLabel?,
    current: Boolean,
    favorite: Boolean,
    previews: LruCache<Int, ImageBitmap>,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
) {
    val page = "Page ${index + 1}"
    val sheetNumber = label?.number?.takeIf(String::isNotBlank)
    val name = sheetNumber ?: page
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).clickable(onClick = onOpen).padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SheetThumbnail(file, index, previews)
            Column {
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                label?.title?.takeIf(String::isNotBlank)?.let {
                    Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                val detail = listOfNotNull(page.takeIf { sheetNumber != null }, "Current".takeIf { current })
                if (detail.isNotEmpty()) {
                    Text(detail.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        IconButton(onClick = onFavorite) {
            Icon(
                painterResource(if (favorite) R.drawable.ic_plan_star else R.drawable.ic_plan_star_outline),
                contentDescription = if (favorite) "Remove $name from favorites" else "Favorite $name",
            )
        }
    }
}

@Composable
private fun SheetLabelDialog(
    page: Int,
    label: SheetLabel?,
    hasOverride: Boolean,
    onDismiss: () -> Unit,
    onSave: (SheetLabel?) -> Unit,
) {
    var number by rememberSaveable(page) { mutableStateOf(label?.number.orEmpty()) }
    var title by rememberSaveable(page) { mutableStateOf(label?.title.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Label page ${page + 1}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(number, { number = it.take(80) }, label = { Text("Sheet number") }, singleLine = true)
                OutlinedTextField(title, { title = it.take(120) }, label = { Text("Sheet title") }, singleLine = true)
                if (hasOverride) TextButton(onClick = { onSave(null) }) { Text("Clear custom label") }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(SheetLabel(normalizeSheetText(number), normalizeSheetText(title))) },
                enabled = number.isNotBlank() || title.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
    Box(Modifier.size(width = 88.dp, height = 62.dp)) {
        image?.let { Image(it, null, modifier = Modifier.matchParentSize()) }
    }
}
