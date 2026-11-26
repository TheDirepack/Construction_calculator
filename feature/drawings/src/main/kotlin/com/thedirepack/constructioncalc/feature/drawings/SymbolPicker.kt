package com.thedirepack.constructioncalc.feature.drawings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.data.PlanSymbol
import com.thedirepack.constructioncalc.core.data.PlanSymbols
import com.thedirepack.constructioncalc.core.data.SymbolLibraryMeta
import com.thedirepack.constructioncalc.core.model.DeviceCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class SymbolLibrary(val catalog: DeviceCatalog?, val imported: List<PlanSymbol>, val meta: SymbolLibraryMeta)

/**
 * Browse and search every catalog device (and SVGs imported on this device). Used to pick the
 * Symbol tool's device and to change a placed or detected symbol's class.
 */
@Composable
internal fun SymbolDialog(
    onDismiss: () -> Unit,
    title: String = "Choose a symbol",
    currentDeviceId: String? = null,
    onSelect: (SymbolChoice) -> Unit,
) {
    val context = LocalContext.current
    val library = remember(context) { PlanSymbols(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var reloadKey by remember { mutableStateOf(0) }
    val loaded by produceState(SymbolLibrary(null, emptyList(), SymbolLibraryMeta()), context, reloadKey) {
        value = withContext(Dispatchers.IO) { SymbolLibrary(DeviceCatalog.bundled, library.list(), library.readMeta()) }
    }
    var meta by remember(loaded) { mutableStateOf(loaded.meta) }
    var query by rememberSaveable { mutableStateOf("") }
    var filterKey by rememberSaveable { mutableStateOf("All") }
    var creatingSet by remember { mutableStateOf(false) }
    var assigning by remember { mutableStateOf<SymbolChoice?>(null) }
    var importing by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val catalog = loaded.catalog

    fun saveMeta(updated: SymbolLibraryMeta) {
        meta = updated
        scope.launch(Dispatchers.IO) { runCatching { library.writeMeta(updated) } }
    }

    fun choose(choice: SymbolChoice) {
        saveMeta(meta.copy(recents = (listOf(choice.id) + meta.recents.filterNot { it == choice.id }).take(24)))
        onSelect(choice)
    }

    // Import straight from the picker and place the new symbol, instead of detouring through settings.
    val importSvg = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            try {
                val symbol = library.import(uri)
                problem = null
                choose(SymbolChoice.Imported(symbol))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                problem = error.message ?: "Could not import this SVG."
                reloadKey++
            } finally {
                importing = false
            }
        }
    }

    val filters = remember(catalog, meta.sets, loaded.imported) {
        buildList {
            add(SymbolFilter.All)
            add(SymbolFilter.Favorites)
            add(SymbolFilter.Recent)
            if (loaded.imported.isNotEmpty()) add(SymbolFilter.Imported)
            catalog?.categories?.forEach { add(SymbolFilter.Category(it)) }
            meta.sets.keys.sorted().forEach { add(SymbolFilter.UserSet(it)) }
        }
    }
    val filter = filters.firstOrNull { it.label == filterKey } ?: SymbolFilter.All
    val visible = remember(catalog, loaded.imported, meta, filter, query) {
        if (catalog == null) emptyList() else filterSymbolChoices(catalog, loaded.imported, meta, filter, query)
    }
    val listState = rememberLazyListState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(80); listState.requestScrollToItem(0) },
                    label = { Text("Search devices") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (option in filters) {
                        FilterChip(
                            selected = option == filter,
                            onClick = { filterKey = option.label; listState.requestScrollToItem(0) },
                            label = { Text(option.label, maxLines = 1) },
                        )
                    }
                    TextButton(onClick = { creatingSet = true }) { Text("+ Set") }
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                when {
                    catalog == null -> Text("Loading devices…", style = MaterialTheme.typography.bodySmall)
                    visible.isEmpty() -> Text(
                        when {
                            query.isNotBlank() -> "No device matches “${query.trim()}”."
                            filter == SymbolFilter.Favorites -> "Star symbols to add them to Favorites."
                            filter == SymbolFilter.Recent -> "Picked symbols will appear here."
                            else -> "No symbols in this set yet."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                LazyColumn(state = listState, modifier = Modifier.heightIn(max = 380.dp)) {
                    items(visible, key = { it.id }) { choice ->
                        val favorite = choice.id in meta.favorites
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Row(
                                Modifier.weight(1f).clickable { choose(choice) }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                SymbolPreview(choice)
                                Column(Modifier.weight(1f)) {
                                    val current = choice.deviceId == currentDeviceId
                                    Text(
                                        if (current) "${choice.name} (current)" else choice.name,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val detail = when (choice) {
                                        is SymbolChoice.Device -> choice.device.category.takeIf { filter !is SymbolFilter.Category }
                                        is SymbolChoice.Imported -> "Imported SVG"
                                    }
                                    detail?.let {
                                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            if (meta.sets.isNotEmpty()) TextButton(onClick = { assigning = choice }) { Text("Set") }
                            IconButton(onClick = {
                                saveMeta(meta.copy(favorites = if (favorite) meta.favorites - choice.id else meta.favorites + choice.id))
                            }) {
                                Icon(
                                    painterResource(if (favorite) R.drawable.ic_plan_star else R.drawable.ic_plan_star_outline),
                                    contentDescription = if (favorite) "Remove ${choice.name} from favorites" else "Favorite ${choice.name}",
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { importSvg.launch(arrayOf("image/svg+xml", "application/xml", "text/xml")) },
                enabled = !importing,
            ) { Text(if (importing) "Importing…" else "Import SVG") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (creatingSet) {
        RenameDialog(
            title = "New symbol set",
            label = "Set name",
            initialName = "",
            maxLength = 80,
            onDismiss = { creatingSet = false },
            onConfirm = { name ->
                val clean = name.trim()
                if (clean.isNotBlank() && filters.none { it.label.equals(clean, ignoreCase = true) && it !is SymbolFilter.UserSet }) {
                    saveMeta(meta.copy(sets = meta.sets + (clean to meta.sets[clean].orEmpty())))
                    filterKey = clean
                }
                creatingSet = false
            },
        )
    }

    assigning?.let { target ->
        AlertDialog(
            onDismissRequest = { assigning = null },
            title = { Text("Sets for “${target.name}”") },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    meta.sets.keys.sorted().forEach { setName ->
                        val member = target.id in meta.sets[setName].orEmpty()
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = member, onCheckedChange = { checked ->
                                val existing = meta.sets[setName].orEmpty()
                                val updated = if (checked) (existing + target.id).distinct() else existing.filterNot { it == target.id }
                                saveMeta(meta.copy(sets = meta.sets + (setName to updated)))
                            })
                            Text(setName, Modifier.weight(1f))
                            TextButton(onClick = {
                                saveMeta(meta.copy(sets = meta.sets - setName))
                                if (filterKey == setName) filterKey = "All"
                            }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { assigning = null }) { Text("Done") } },
        )
    }
}
