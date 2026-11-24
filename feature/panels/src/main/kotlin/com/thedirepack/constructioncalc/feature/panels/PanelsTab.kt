package com.thedirepack.constructioncalc.feature.panels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.electrical.StandardOcpd
import com.thedirepack.constructioncalc.core.model.CircuitKind
import com.thedirepack.constructioncalc.core.model.Panel
import com.thedirepack.constructioncalc.core.model.PanelSystem
import com.thedirepack.constructioncalc.core.model.design.Wire
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.components.ChoiceField
import com.thedirepack.constructioncalc.core.ui.components.ConfirmDialog
import com.thedirepack.constructioncalc.core.ui.components.SegmentedChoice
import com.thedirepack.constructioncalc.core.ui.components.TextInputDialog
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices
import com.thedirepack.constructioncalc.feature.panels.domain.BuiltInPanelTemplates
import com.thedirepack.constructioncalc.feature.panels.domain.PanelExportLayout
import com.thedirepack.constructioncalc.feature.panels.domain.PanelFacts
import com.thedirepack.constructioncalc.feature.panels.domain.PanelTemplate
import com.thedirepack.constructioncalc.feature.panels.domain.PanelTemplates
import com.thedirepack.constructioncalc.feature.panels.domain.SavedPanelTemplate
import com.thedirepack.constructioncalc.feature.panels.domain.duplicatePanel
import com.thedirepack.constructioncalc.feature.panels.domain.suggestedPanelName

private sealed interface PanelPrompt {
    data object Add : PanelPrompt
    data class Rename(val panel: Panel) : PanelPrompt
    data class SaveTemplate(val panel: Panel) : PanelPrompt
    data class Delete(val panel: Panel) : PanelPrompt
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PanelsTab(ctx: PanelsContext, export: ExportActions, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    var prompt by remember { mutableStateOf<PanelPrompt?>(null) }
    val services = LocalAppServices.current
    val templateStore = remember(services) { services.settings.module(PanelsTool.ID) }
    val customTemplates = rememberCustomPanelTemplates()
    val defaultLayout = rememberDefaultExportLayout()
    var selectedLayout by remember(defaultLayout) { mutableStateOf(defaultLayout) }

    val allWarnings = remember(ctx.design) {
        buildList {
            ctx.design.panels.forEach { pd ->
                pd.conflicts.forEach { add("${pd.panel.name}: $it") }
                pd.notes.filter { it.isWarning }.forEach { add("${pd.panel.name}: ${it.text}") }
            }
            ctx.design.circuits.values.forEach { cd ->
                cd.notes.filter { it.isWarning }.forEach { note ->
                    add("${cd.panel.name} #${cd.circuit.slots.joinToString(",")}: ${note.text}")
                }
            }
        }
    }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (allWarnings.isNotEmpty()) {
            item(key = "warnings") {
                OutOfSpecBanner(
                    title = "${plural(allWarnings.size, "out-of-spec warning")} across panels",
                    warnings = allWarnings,
                )
            }
        }
        if (!ctx.readOnly) {
            item(key = "add") { Button(onClick = { prompt = PanelPrompt.Add }) { Text("Add panel") } }
        }
        if (ctx.project.panels.isEmpty()) {
            item(key = "empty") {
                Text(
                    "No panels yet. Add one to lay out its circuits, then link circuits to conduit runs and the app sizes the wire.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(ctx.project.panels, key = { it.id }) { panel ->
            PanelCard(
                ctx,
                panel,
                onOpen = { onOpen(panel.id) },
                onSharePng = { export.png(panel.id, selectedLayout) },
                onSaveTemplate = { prompt = PanelPrompt.SaveTemplate(panel) },
                onRename = { prompt = PanelPrompt.Rename(panel) },
                onDelete = { prompt = PanelPrompt.Delete(panel) },
            )
        }
        if (ctx.project.panels.isNotEmpty()) {
            item(key = "export") {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Export", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Text(
                            "Print-ready PDF or PNG for panel doors and binders, RFC-4180 CSV for spreadsheets, or plain text.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        ChoiceField(
                            label = "Schedule layout",
                            options = PanelExportLayout.entries,
                            selected = selectedLayout,
                            onSelect = { layout ->
                                selectedLayout = layout
                                PanelTemplateStore.setDefaultLayout(templateStore, layout)
                            },
                            optionLabel = { it.label },
                            supportingText = selectedLayout.summary,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(onClick = { export.pdf(null, selectedLayout) }) { Text("Share PDF") }
                            OutlinedButton(onClick = { export.png(null, selectedLayout) }) { Text("Share printable PNG") }
                            OutlinedButton(onClick = { export.scheduleCsv(null) }) { Text("Export schedule (CSV)") }
                            OutlinedButton(onClick = { export.takeoffCsv() }) { Text("Export takeoff (CSV)") }
                            OutlinedButton(onClick = { export.text(null) }) { Text("Share as text") }
                        }
                    }
                }
            }
        }
    }

    when (val p = prompt) {
        null -> Unit
        PanelPrompt.Add -> CreatePanelDialog(
            suggestedName = ctx.project.suggestedPanelName(),
            customTemplates = customTemplates,
            onCreate = { panel ->
                ctx.edit { project, now -> project.addPanel(panel, now) }
                prompt = null
                onOpen(panel.id)
            },
            onDismiss = { prompt = null },
        )
        is PanelPrompt.Rename -> TextInputDialog("Rename panel", p.panel.name, "Rename", { name ->
            ctx.edit { project, now -> project.updatePanel(p.panel.id, now) { it.copy(name = name) } }
            prompt = null
        }, { prompt = null })
        is PanelPrompt.SaveTemplate -> TextInputDialog(
            title = "Save as template",
            initial = p.panel.name,
            confirmLabel = "Save",
            onConfirm = { templateName ->
                val saved = PanelTemplateStore.saveFromPanel(templateStore, p.panel, templateName)
                prompt = null
                ctx.host.showMessage("Saved template “${saved.name}”")
            },
            onDismiss = { prompt = null },
            label = "Template name",
        )
        is PanelPrompt.Delete -> ConfirmDialog(
            "Delete ${p.panel.name}?",
            "This removes the panel and its ${plural(p.panel.circuits.size, "circuit")}. Panels it fed lose their \"fed from\". Conduit runs stay.",
            "Delete",
            onConfirm = {
                ctx.edit { project, now -> project.removePanel(p.panel.id, now) }
                prompt = null
            },
            onDismiss = { prompt = null },
        )
    }
}

@Composable
private fun PanelCard(
    ctx: PanelsContext,
    panel: Panel,
    onOpen: () -> Unit,
    onSharePng: () -> Unit,
    onSaveTemplate: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val pd = ctx.design.panels.firstOrNull { it.panel.id == panel.id }
    val circuitWarnings = panel.circuits.flatMap { c ->
        ctx.design.circuits[c.id]?.notes.orEmpty()
            .filter { it.isWarning }
            .map { "#${c.slots.joinToString(",")}: ${it.text}" }
    }
    val warnings = (pd?.conflicts.orEmpty() + pd?.notes.orEmpty().filter { it.isWarning }.map { it.text } + circuitWarnings)
    val used = panel.circuits.sumOf { if (it.kind == CircuitKind.SPACE) 0 else it.poles }
    Card(
        Modifier.fillMaxWidth().clickable(onClickLabel = "Open ${panel.name}", onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 12.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(panel.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (warnings.isNotEmpty()) {
                    WarningBadge(plural(warnings.size, "warning"))
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "Options for ${panel.name}" }) {
                        Icon(painterResource(R.drawable.ic_more_vert), contentDescription = null)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; onOpen() })
                        DropdownMenuItem(text = { Text("Share printable PNG") }, onClick = { menu = false; onSharePng() })
                        DropdownMenuItem(text = { Text("Save as template") }, onClick = { menu = false; onSaveTemplate() })
                        if (!ctx.readOnly) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                            DropdownMenuItem(text = { Text("Duplicate") }, onClick = {
                                menu = false
                                ctx.edit { project, now -> project.duplicatePanel(panel.id, now) }
                            })
                            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                        }
                    }
                }
            }
            Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(PanelFacts.summary(panel), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (panel.location.isNotBlank()) Text(panel.location, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "${plural(panel.circuits.count { it.kind != CircuitKind.SPACE }, "circuit")} · $used of ${panel.spaces} positions used · " +
                        (pd?.totalVa?.let { Wire.va(it) } ?: "0 VA"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                PanelFacts.fedFromText(ctx.project, panel)?.let {
                    Text("Fed from $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (warnings.isNotEmpty()) {
                    WarningText(warnings.first() + if (warnings.size > 1) " (+${warnings.size - 1} more)" else "")
                }
            }
        }
    }
}

private enum class PanelMainMode(val label: String) {
    MLO("MLO"),
    MAIN("Main breaker"),
}

private sealed interface TemplateChoice {
    val key: String
    val title: String
    val system: PanelSystem
    val defaultSpaces: Int
    val defaultBusAmps: Int?
    val defaultMainAmps: Int?
    val circuitCount: Int

    data class Bus(val template: PanelTemplate) : TemplateChoice {
        override val key: String get() = "bus:${template.id}"
        override val title: String get() = template.label
        override val system: PanelSystem get() = template.system
        override val defaultSpaces: Int get() = template.defaultSpaces
        override val defaultBusAmps: Int get() = template.defaultBusAmps
        override val defaultMainAmps: Int? get() = template.defaultMainAmps
        override val circuitCount: Int get() = 0
    }

    data class Saved(val template: SavedPanelTemplate) : TemplateChoice {
        override val key: String get() = "saved:${template.id}"
        override val title: String get() = template.name
        override val system: PanelSystem get() = template.system
        override val defaultSpaces: Int get() = template.snappedSpaces
        override val defaultBusAmps: Int? get() = template.busAmps
        override val defaultMainAmps: Int? get() = template.mainAmps
        override val circuitCount: Int get() = template.circuits.size
    }
}

@Composable
private fun CreatePanelDialog(
    suggestedName: String,
    customTemplates: List<SavedPanelTemplate>,
    onCreate: (Panel) -> Unit,
    onDismiss: () -> Unit,
) {
    val sections = remember(customTemplates) {
        buildList {
            if (customTemplates.isNotEmpty()) {
                add("Saved templates" to customTemplates.map(TemplateChoice::Saved))
            }
            add("Standard bus (blank)" to PanelTemplates.all.map(TemplateChoice::Bus))
            add("Starter schedules" to BuiltInPanelTemplates.schedules.map(TemplateChoice::Saved))
        }
    }
    val allOptions = remember(sections) { sections.flatMap { it.second } }
    val defaultKey = remember { TemplateChoice.Bus(PanelTemplates.default).key }

    var selectedKey by rememberSaveable { mutableStateOf(defaultKey) }
    val selected = allOptions.firstOrNull { it.key == selectedKey } ?: TemplateChoice.Bus(PanelTemplates.default)

    var name by rememberSaveable(suggestedName) { mutableStateOf(suggestedName) }
    var spaces by rememberSaveable(selected.key) { mutableIntStateOf(selected.defaultSpaces) }
    var mainMode by rememberSaveable(selected.key) {
        mutableStateOf(if (selected.defaultMainAmps != null) PanelMainMode.MAIN else PanelMainMode.MLO)
    }
    var mainAmps by rememberSaveable(selected.key) {
        mutableIntStateOf(selected.defaultMainAmps ?: selected.defaultBusAmps ?: 225)
    }

    val validSpaces = remember(selected.system.phases) { Panel.validSpaceCounts(selected.system.phases) }
    val snappedSpaces = Panel.snapSpacesToBus(spaces, selected.system.phases)
    val step = if (selected.system.phases >= 3) 6 else 4
    val clippedCount = remember(selected, snappedSpaces) {
        when (selected) {
            is TemplateChoice.Bus -> 0
            is TemplateChoice.Saved -> selected.template.clippedCircuitCount(snappedSpaces)
        }
    }
    val spacesSupportText = buildString {
        append("Multiples of $step for ${if (selected.system.phases >= 3) "3φ" else "1φ"} bus.")
        if (clippedCount > 0) {
            append(" ${plural(clippedCount, "circuit")} past $snappedSpaces spaces will be omitted.")
        }
    }

    val ocpdOptions = remember { StandardOcpd.ratings.filter { it in 15..6000 } }
    val mainBreakerOptions = remember(mainAmps, ocpdOptions) {
        if (mainAmps in ocpdOptions) ocpdOptions else (listOf(mainAmps) + ocpdOptions).sorted()
    }
    val canCreate = name.isNotBlank()

    fun submit() {
        val effectiveMain = if (mainMode == PanelMainMode.MAIN) mainAmps else null
        val effectiveBus = maxOf(selected.defaultBusAmps ?: 0, effectiveMain ?: 0).takeIf { it > 0 }
        val panel = when (selected) {
            is TemplateChoice.Bus -> selected.template.createPanel(
                name = name,
                spaces = snappedSpaces,
                busAmps = effectiveBus,
                mainAmps = effectiveMain,
            )
            is TemplateChoice.Saved -> selected.template.instantiate(
                panelName = name,
                spaces = snappedSpaces,
                busAmps = effectiveBus,
                mainAmps = effectiveMain,
            )
        }
        onCreate(panel)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New panel") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TemplatePickerField(
                    selected = selected,
                    sections = sections,
                    onSelect = { next -> selectedKey = next.key },
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Panel name, e.g. LP-1") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (canCreate) submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                ChoiceField(
                    label = "Spaces (bus-locked)",
                    options = validSpaces,
                    selected = snappedSpaces,
                    onSelect = { spaces = it },
                    optionLabel = { "$it spaces" },
                    supportingText = spacesSupportText,
                )
                SegmentedChoice(
                    options = PanelMainMode.entries,
                    selected = mainMode,
                    onSelect = { mainMode = it },
                    label = "Main / MLO",
                    optionLabel = { it.label },
                )
                if (mainMode == PanelMainMode.MAIN) {
                    ChoiceField(
                        label = "Main breaker",
                        options = mainBreakerOptions,
                        selected = mainAmps,
                        onSelect = { mainAmps = it },
                        optionLabel = { "$it A" },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canCreate, onClick = { submit() }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TemplatePickerField(
    selected: TemplateChoice,
    sections: List<Pair<String, List<TemplateChoice>>>,
    onSelect: (TemplateChoice) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val openPicker by rememberUpdatedState { expanded = true }
    LaunchedEffect(interaction) {
        interaction.interactions.collect { if (it is PressInteraction.Release) openPicker() }
    }
    val supporting = buildList {
        add(selected.system.label)
        selected.defaultBusAmps?.let { add("$it A bus") }
        if (selected.circuitCount > 0) add(plural(selected.circuitCount, "circuit"))
    }.joinToString(" · ")

    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = selected.title,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("Template") },
            trailingIcon = { Text("▾", style = MaterialTheme.typography.titleMedium) },
            supportingText = { Text(supporting) },
            interactionSource = interaction,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    role = Role.DropdownList
                    contentDescription = "Template: ${selected.title}"
                    onClick { openPicker(); true }
                },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            sections.forEachIndexed { sectionIndex, (header, items) ->
                if (sectionIndex > 0) HorizontalDivider()
                Text(
                    header,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
                items.forEach { option ->
                    val meta = buildList {
                        if (option is TemplateChoice.Saved) add(option.template.category.label)
                        add(option.system.label)
                        add("${option.defaultSpaces} spaces")
                        if (option.circuitCount > 0) add(plural(option.circuitCount, "circuit"))
                    }.joinToString(" · ")
                    DropdownMenuItem(
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(
                                    option.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (option.key == selected.key) FontWeight.SemiBold else FontWeight.Normal,
                                )
                                Text(
                                    meta,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
