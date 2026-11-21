package com.thedirepack.constructioncalc.feature.reference

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.model.ToolTarget
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.components.ChoiceField
import com.thedirepack.constructioncalc.core.ui.components.SegmentedChoice
import com.thedirepack.constructioncalc.core.ui.components.ToolScaffold
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices
import com.thedirepack.constructioncalc.core.ui.tool.LocalTools
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost
import com.thedirepack.constructioncalc.core.ui.tool.titleOf
import com.thedirepack.constructioncalc.feature.reference.content.RefBlock
import com.thedirepack.constructioncalc.feature.reference.content.RefEntry
import com.thedirepack.constructioncalc.feature.reference.content.RefSection
import com.thedirepack.constructioncalc.feature.reference.content.RefSource
import com.thedirepack.constructioncalc.feature.reference.content.ReferenceCatalog
import com.thedirepack.constructioncalc.feature.reference.content.Topics

@Composable
fun ReferenceScreen(host: ToolHost) {
    val topic = (host.target as? ToolTarget.OfTopic)?.topicId
    val toolFilter = topic?.let(Topics::toolOf)
    val directEntry = topic?.takeIf { toolFilter == null && ReferenceCatalog.find(it) != null }
    var openId by rememberSaveable { mutableStateOf(directEntry) }
    val settings = LocalAppServices.current.settings.module(ReferenceTool.ID)
    val store = remember(settings) { ReferenceStore(settings) }
    val version by settings.changes.collectAsStateWithLifecycle()

    val entry = openId?.let(ReferenceCatalog::find)
    if (entry != null) {
        // Opened straight at this entry from another tool: back returns there. Otherwise back returns to the list.
        val closeToList = if (entry.id == directEntry) null else ({ openId = null })
        if (closeToList != null) BackHandler(onBack = closeToList)
        LaunchedEffect(entry.id) { store.opened(entry.id) }
        val favorite = remember(version, entry.id) { store.isFavorite(entry.id) }
        EntryScreen(entry, host, favorite, onToggleFavorite = { store.toggleFavorite(entry.id) }, onBack = closeToList)
    } else {
        val favorites = remember(version) { store.favorites().mapNotNull(ReferenceCatalog::find) }
        val recents = remember(version) { store.recents().mapNotNull(ReferenceCatalog::find) }
        ListScreen(host, toolFilter, favorites, recents, onOpen = { openId = it.id })
    }
}

@Composable
private fun ListScreen(
    host: ToolHost,
    toolFilter: String?,
    favorites: List<RefEntry>,
    recents: List<RefEntry>,
    onOpen: (RefEntry) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var showAll by rememberSaveable { mutableStateOf(false) }
    val tools = LocalTools.current
    val results = remember(query) { ReferenceCatalog.search(query) }

    ToolScaffold(title = ReferenceTool.title, host = host) { padding ->
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.padding(padding)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search tables, formulas, topics") },
                        singleLine = true,
                        trailingIcon = if (query.isEmpty()) null else ({
                            IconButton(onClick = { query = "" }) { Icon(painterResource(R.drawable.ic_close), contentDescription = "Clear search") }
                        }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Code tables follow ${Code.label}. Check which edition your jurisdiction has adopted, and any local amendments.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when {
                query.isNotBlank() -> {
                    if (results.isEmpty()) item { Text("Nothing found for \"$query\".", Modifier.padding(16.dp)) }
                    items(results, key = { "r:" + it.id }) { EntryRow(it, onOpen, showSection = true) }
                }
                toolFilter != null && !showAll -> {
                    item { SectionHeader("For ${tools.titleOf(toolFilter)}") }
                    items(ReferenceCatalog.forTool(toolFilter), key = { "t:" + it.id }) { EntryRow(it, onOpen, showSection = true) }
                    item { TextButton(onClick = { showAll = true }, Modifier.padding(horizontal = 8.dp)) { Text("Browse everything") } }
                }
                else -> {
                    if (favorites.isNotEmpty()) {
                        item { SectionHeader("Favorites") }
                        items(favorites, key = { "f:" + it.id }) { EntryRow(it, onOpen, showSection = true) }
                    }
                    if (recents.isNotEmpty()) {
                        item { SectionHeader("Recently opened") }
                        items(recents, key = { "h:" + it.id }) { EntryRow(it, onOpen, showSection = true) }
                    }
                    RefSection.entries.forEach { section ->
                        item(key = "s:" + section.name) { SectionHeader(section.label) }
                        items(ReferenceCatalog.section(section), key = { "e:" + it.id }) { EntryRow(it, onOpen) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun EntryRow(entry: RefEntry, onOpen: (RefEntry) -> Unit, showSection: Boolean = false) {
    ListItem(
        headlineContent = { Text(entry.title) },
        supportingContent = { Text(entry.summary, maxLines = 2) },
        overlineContent = when {
            showSection -> ({ Text(entry.section.label) })
            entry.source is RefSource.CodeRef -> ({ Text(entry.source.label, maxLines = 1) })
            else -> null
        },
        modifier = Modifier.clickable(onClickLabel = "Open") { onOpen(entry) },
    )
}

@Composable
private fun EntryScreen(entry: RefEntry, host: ToolHost, favorite: Boolean, onToggleFavorite: () -> Unit, onBack: (() -> Unit)?) {
    ToolScaffold(
        title = entry.title,
        host = host,
        onBack = onBack,
        subtitle = entry.source.label,
        actions = {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    painterResource(if (favorite) R.drawable.ic_star else R.drawable.ic_star_border),
                    contentDescription = if (favorite) "Remove from favorites" else "Add to favorites",
                )
            }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(padding),
        ) {
            item { Text(entry.summary, style = MaterialTheme.typography.bodyLarge) }
            items(entry.blocks.size) { i -> Block(entry.blocks[i], host, key = "${entry.id}:$i") }
        }
    }
}

@Composable
private fun Block(block: RefBlock, host: ToolHost, key: String) {
    when (block) {
        is RefBlock.Paragraph -> Text(block.text, style = MaterialTheme.typography.bodyMedium)
        is RefBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            block.items.forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium) }
        }
        is RefBlock.Equation -> Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Text(block.text, Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Serif)
        }
        is RefBlock.Table -> TableView(block)
        is RefBlock.Choice -> ChoiceView(block, host, key)
        is RefBlock.ToolLink -> OutlinedButton(onClick = { host.openTool(block.toolId, target = block.topic?.let { ToolTarget.OfTopic(it) }) }) {
            Text(block.label)
        }
    }
}

@Composable
private fun ChoiceView(block: RefBlock.Choice, host: ToolHost, key: String) {
    var picked by rememberSaveable(key) { mutableIntStateOf(0) }
    val content = remember(block, picked) { block.content(picked.coerceIn(block.options.indices)) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (block.options.size <= 3 && block.options.all { it.length <= 16 }) {
            SegmentedChoice(block.options.indices.toList(), picked, { picked = it }, label = block.label, optionLabel = { block.options[it] })
        } else {
            ChoiceField(block.label, block.options.indices.toList(), picked, { picked = it }, optionLabel = { block.options[it] })
        }
        content.forEachIndexed { i, b -> Block(b, host, "$key/$picked/$i") }
    }
}

/** A table that scrolls sideways when it's wider than the screen; the header row is bold. */
@Composable
private fun TableView(table: RefBlock.Table) {
    val widths = remember(table) {
        table.columns.indices.map { c ->
            val longest = (table.rows.map { it[c] } + table.columns[c]).maxOf { it.length }
            (longest * 8 + 20).coerceIn(56, 220).dp
        }
    }
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row(Modifier.background(colors.secondaryContainer)) {
                table.columns.forEachIndexed { c, h ->
                    Text(h, Modifier.width(widths[c]).padding(horizontal = 8.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = colors.onSecondaryContainer)
                }
            }
            table.rows.forEachIndexed { r, row ->
                Row(Modifier.background(if (r % 2 == 1) colors.surfaceContainerLow else colors.surface)) {
                    row.forEachIndexed { c, cell ->
                        Text(
                            cell,
                            Modifier.width(widths[c]).padding(horizontal = 8.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (c == 0) FontWeight.Medium else FontWeight.Normal,
                        )
                    }
                }
                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.4f))
            }
        }
        table.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
    }
}
