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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.CodeEdition
import com.thedirepack.constructioncalc.core.electrical.code.CodeProfile
import com.thedirepack.constructioncalc.core.electrical.code.CodeRegions
import com.thedirepack.constructioncalc.core.model.ToolTarget
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.components.ChoiceField
import com.thedirepack.constructioncalc.core.ui.components.SegmentedChoice
import com.thedirepack.constructioncalc.core.ui.components.ToolScaffold
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices
import com.thedirepack.constructioncalc.core.ui.tool.LocalTools
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost
import com.thedirepack.constructioncalc.core.ui.tool.titleOf
import com.thedirepack.constructioncalc.feature.reference.content.RefBlock
import com.thedirepack.constructioncalc.feature.reference.content.RefContext
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
    var showCodeDialog by rememberSaveable { mutableStateOf(false) }

    val appSettings = rememberAppSettings()
    val settings = LocalAppServices.current.settings.module(ReferenceTool.ID)
    val store = remember(settings) { ReferenceStore(settings) }
    val version by settings.changes.collectAsStateWithLifecycle()

    val override = remember(version) { store.codeOverride() }
    val appProfile = remember(appSettings.codeEdition, appSettings.codeRegion) {
        val global = Code.globalProfile
        val edition = CodeEdition.parse(appSettings.codeEdition)
            ?: CodeEdition.NEC_2023
        val regionId = CodeRegions.getOrDefault(appSettings.codeRegion).id
        if (global.edition == edition && global.regionId == regionId) global else CodeProfile(edition, regionId)
    }
    val overrideProfile = remember(override, appProfile) {
        ReferenceCodeProfiles.resolveOverrideOrNull(override, appProfile)
    }
    val activeProfile = overrideProfile ?: appProfile
    val chipLabel = remember(activeProfile, override) {
        ReferenceCodeProfiles.chipLabel(activeProfile, isOverride = !override.isInherited)
    }

    val entry = openId?.let(ReferenceCatalog::find)
    if (entry != null) {
        // Opened straight at this entry from another tool: back returns there. Otherwise back returns to the list.
        val closeToList = if (entry.id == directEntry) null else ({ openId = null })
        if (closeToList != null) BackHandler(onBack = closeToList)
        LaunchedEffect(entry.id) { store.opened(entry.id) }
        val favorite = remember(version, entry.id) { store.isFavorite(entry.id) }
        EntryScreen(
            entry = entry,
            host = host,
            favorite = favorite,
            overrideProfile = overrideProfile,
            activeProfile = activeProfile,
            chipLabel = chipLabel,
            isCodeOverridden = !override.isInherited,
            onToggleFavorite = { store.toggleFavorite(entry.id) },
            onOpenCodeDialog = { showCodeDialog = true },
            onBack = closeToList,
        )
    } else {
        val favoriteIds = remember(version) { store.favorites().toSet() }
        val favorites = remember(version) { store.favorites().mapNotNull(ReferenceCatalog::find) }
        val recents = remember(version) { store.recents().mapNotNull(ReferenceCatalog::find) }
        ListScreen(
            host = host,
            toolFilter = toolFilter,
            favorites = favorites,
            recents = recents,
            favoriteIds = favoriteIds,
            chipLabel = chipLabel,
            isCodeOverridden = !override.isInherited,
            overrideProfile = overrideProfile,
            activeProfile = activeProfile,
            onOpen = { openId = it.id },
            onToggleFavorite = { store.toggleFavorite(it) },
            onOpenCodeDialog = { showCodeDialog = true },
        )
    }

    if (showCodeDialog) {
        ReferenceCodeOverrideDialog(
            appProfile = appProfile,
            currentOverride = override,
            onInheritApp = {
                store.clearCodeOverride()
                showCodeDialog = false
            },
            onApplyOverride = { edition, region ->
                store.setCodeOverride(edition.name, region.id)
                showCodeDialog = false
            },
            onDismiss = { showCodeDialog = false },
        )
    }
}

@Composable
private fun ListScreen(
    host: ToolHost,
    toolFilter: String?,
    favorites: List<RefEntry>,
    recents: List<RefEntry>,
    favoriteIds: Set<String>,
    chipLabel: String,
    isCodeOverridden: Boolean,
    overrideProfile: CodeProfile?,
    activeProfile: CodeProfile,
    onOpen: (RefEntry) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onOpenCodeDialog: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var showAll by rememberSaveable { mutableStateOf(false) }
    val tools = LocalTools.current
    val results = remember(query, overrideProfile, activeProfile) {
        withOptionalCodeProfile(overrideProfile) { ReferenceCatalog.search(query) }
    }
    val codeLabel = remember(overrideProfile, activeProfile) {
        withOptionalCodeProfile(overrideProfile) { Code.label }
    }
    val sourceLabels = remember(overrideProfile, activeProfile) {
        withOptionalCodeProfile(overrideProfile) {
            ReferenceCatalog.all.associate { it.id to it.source.label }
        }
    }

    ToolScaffold(title = ReferenceTool.title, host = host) { padding ->
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.padding(padding)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = isCodeOverridden,
                            onClick = onOpenCodeDialog,
                            label = { Text(chipLabel) },
                        )
                        Text(
                            if (isCodeOverridden) "Per-tool override · Tap to change" else "App setting · Tap to override",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "Code tables follow $codeLabel. Check which edition your jurisdiction has adopted, and any local amendments.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when {
                query.isNotBlank() -> {
                    if (results.isEmpty()) item { Text("Nothing found for \"$query\".", Modifier.padding(16.dp)) }
                    items(results, key = { "r:" + it.id }) { entry ->
                        EntryRow(
                            entry = entry,
                            favorite = entry.id in favoriteIds,
                            sourceLabel = sourceLabels[entry.id] ?: entry.source.label,
                            onOpen = onOpen,
                            onToggleFavorite = { onToggleFavorite(entry.id) },
                            showSection = true,
                        )
                    }
                }
                toolFilter != null && !showAll -> {
                    item { SectionHeader("For ${tools.titleOf(toolFilter)}") }
                    items(ReferenceCatalog.forTool(toolFilter), key = { "t:" + it.id }) { entry ->
                        EntryRow(
                            entry = entry,
                            favorite = entry.id in favoriteIds,
                            sourceLabel = sourceLabels[entry.id] ?: entry.source.label,
                            onOpen = onOpen,
                            onToggleFavorite = { onToggleFavorite(entry.id) },
                            showSection = true,
                        )
                    }
                    item { TextButton(onClick = { showAll = true }, Modifier.padding(horizontal = 8.dp)) { Text("Browse everything") } }
                }
                else -> {
                    if (favorites.isNotEmpty()) {
                        item { SectionHeader("Favorites") }
                        items(favorites, key = { "f:" + it.id }) { entry ->
                            EntryRow(
                                entry = entry,
                                favorite = true,
                                sourceLabel = sourceLabels[entry.id] ?: entry.source.label,
                                onOpen = onOpen,
                                onToggleFavorite = { onToggleFavorite(entry.id) },
                                showSection = true,
                            )
                        }
                    }
                    if (recents.isNotEmpty()) {
                        item { SectionHeader("Recently opened") }
                        items(recents, key = { "h:" + it.id }) { entry ->
                            EntryRow(
                                entry = entry,
                                favorite = entry.id in favoriteIds,
                                sourceLabel = sourceLabels[entry.id] ?: entry.source.label,
                                onOpen = onOpen,
                                onToggleFavorite = { onToggleFavorite(entry.id) },
                                showSection = true,
                            )
                        }
                    }
                    RefSection.entries.forEach { section ->
                        item(key = "s:" + section.name) { SectionHeader(section.label) }
                        items(ReferenceCatalog.section(section), key = { "e:" + it.id }) { entry ->
                            EntryRow(
                                entry = entry,
                                favorite = entry.id in favoriteIds,
                                sourceLabel = sourceLabels[entry.id] ?: entry.source.label,
                                onOpen = onOpen,
                                onToggleFavorite = { onToggleFavorite(entry.id) },
                            )
                        }
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
private fun EntryRow(
    entry: RefEntry,
    favorite: Boolean,
    sourceLabel: String,
    onOpen: (RefEntry) -> Unit,
    onToggleFavorite: () -> Unit,
    showSection: Boolean = false,
) {
    ListItem(
        headlineContent = { Text(entry.title) },
        supportingContent = { Text(entry.summary, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        overlineContent = when {
            showSection && entry.source is RefSource.CodeRef -> ({
                Text("${entry.section.label} · $sourceLabel", maxLines = 1, overflow = TextOverflow.Ellipsis)
            })
            showSection -> ({ Text(entry.section.label, maxLines = 1, overflow = TextOverflow.Ellipsis) })
            entry.source is RefSource.CodeRef -> ({ Text(sourceLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) })
            else -> null
        },
        trailingContent = {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    painterResource(if (favorite) R.drawable.ic_star else R.drawable.ic_star_border),
                    contentDescription = if (favorite) "Remove ${entry.title} from favorites" else "Add ${entry.title} to favorites",
                    tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        modifier = Modifier.clickable(onClickLabel = "Open") { onOpen(entry) },
    )
}

@Composable
private fun EntryScreen(
    entry: RefEntry,
    host: ToolHost,
    favorite: Boolean,
    overrideProfile: CodeProfile?,
    activeProfile: CodeProfile,
    chipLabel: String,
    isCodeOverridden: Boolean,
    onToggleFavorite: () -> Unit,
    onOpenCodeDialog: () -> Unit,
    onBack: (() -> Unit)?,
) {
    val sourceLabel = remember(entry, overrideProfile, activeProfile) {
        withOptionalCodeProfile(overrideProfile) { entry.source.label }
    }
    ToolScaffold(
        title = entry.title,
        host = host,
        onBack = onBack,
        subtitle = sourceLabel,
        actions = {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    painterResource(if (favorite) R.drawable.ic_star else R.drawable.ic_star_border),
                    contentDescription = if (favorite) "Remove from favorites" else "Add to favorites",
                )
            }
        },
    ) { padding ->
        val context = RefContext(rememberAppSettings().unitSystem)
        val blocks = remember(entry, context, overrideProfile, activeProfile) {
            withOptionalCodeProfile(overrideProfile) { entry.blocks(context) }
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(padding),
        ) {
            if (entry.source is RefSource.CodeRef || isCodeOverridden) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = isCodeOverridden,
                            onClick = onOpenCodeDialog,
                            label = { Text(chipLabel) },
                        )
                        Text(
                            if (isCodeOverridden) "Reference override" else "App code profile",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item { Text(entry.summary, style = MaterialTheme.typography.bodyLarge) }
            items(blocks.size) { i ->
                Block(blocks[i], host, overrideProfile, key = "${entry.id}:${context.system}:${activeProfile.edition.name}:${activeProfile.regionId}:$i")
            }
        }
    }
}

@Composable
private fun Block(block: RefBlock, host: ToolHost, overrideProfile: CodeProfile?, key: String) {
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
        is RefBlock.Choice -> ChoiceView(block, host, overrideProfile, key)
        is RefBlock.Calculator -> QuickCalculator(block.kind, overrideProfile)
        is RefBlock.ToolLink -> OutlinedButton(onClick = { host.openTool(block.toolId, target = block.topic?.let { ToolTarget.OfTopic(it) }) }) {
            Text(block.label)
        }
    }
}

@Composable
private fun ChoiceView(block: RefBlock.Choice, host: ToolHost, overrideProfile: CodeProfile?, key: String) {
    var picked by rememberSaveable(key) { mutableIntStateOf(0) }
    val content = remember(block, picked, overrideProfile) {
        withOptionalCodeProfile(overrideProfile) {
            block.content(picked.coerceIn(block.options.indices))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (block.options.size <= 3 && block.options.all { it.length <= 16 }) {
            SegmentedChoice(block.options.indices.toList(), picked, { picked = it }, label = block.label, optionLabel = { block.options[it] })
        } else {
            ChoiceField(block.label, block.options.indices.toList(), picked, { picked = it }, optionLabel = { block.options[it] })
        }
        content.forEachIndexed { i, b -> Block(b, host, overrideProfile, "$key/$picked/$i") }
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

@Composable
private fun ReferenceCodeOverrideDialog(
    appProfile: CodeProfile,
    currentOverride: ReferenceCodeOverride,
    onInheritApp: () -> Unit,
    onApplyOverride: (CodeEdition, com.thedirepack.constructioncalc.core.electrical.code.CodeRegion) -> Unit,
    onDismiss: () -> Unit,
) {
    val resolvedCurrent = remember(currentOverride, appProfile) {
        ReferenceCodeProfiles.resolve(currentOverride, appProfile)
    }
    var inheritApp by remember(currentOverride) { mutableStateOf(currentOverride.isInherited) }
    var selectedEdition by remember(resolvedCurrent) { mutableStateOf(resolvedCurrent.edition) }
    var selectedRegionId by remember(resolvedCurrent) { mutableStateOf(resolvedCurrent.regionId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("NEC edition & region") },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = inheritApp, role = Role.RadioButton, onClick = { inheritApp = true })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(selected = inheritApp, onClick = null)
                    Column(Modifier.weight(1f)) {
                        Text(ReferenceCodeProfiles.INHERIT_LABEL, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Follows app setting (${ReferenceCodeProfiles.chipLabel(appProfile)})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                HorizontalDivider()

                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Code edition", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    CodeEdition.entries.forEach { edition ->
                        val enabled = true
                        val selected = !inheritApp && enabled && selectedEdition == edition
                        val label = edition.label
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selected,
                                    enabled = enabled,
                                    role = Role.RadioButton,
                                    onClick = {
                                        if (enabled) {
                                            inheritApp = false
                                            selectedEdition = edition
                                        }
                                    },
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RadioButton(selected = selected, onClick = null, enabled = enabled)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                )
                                Text(
                                    edition.statusNote,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                )
                            }
                        }
                    }
                }

                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Jurisdiction", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    CodeRegions.entries.forEach { region ->
                        val selected = !inheritApp && selectedRegionId == region.id
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = {
                                        inheritApp = false
                                        selectedRegionId = region.id
                                    },
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Column(Modifier.weight(1f)) {
                                Text(region.name, style = MaterialTheme.typography.bodyMedium)
                                val note = buildString {
                                    append(region.description)
                                    if (!region.hasOverrides && region.id != CodeRegions.STANDARD_ID) {
                                        append(" No local amendments entered; uses ${selectedEdition.label} as published.")
                                    }
                                }
                                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (inheritApp) onInheritApp()
                    else onApplyOverride(selectedEdition, CodeRegions.getOrDefault(selectedRegionId))
                },
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}