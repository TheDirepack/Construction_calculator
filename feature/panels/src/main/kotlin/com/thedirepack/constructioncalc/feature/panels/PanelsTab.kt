package com.thedirepack.constructioncalc.feature.panels

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.model.CircuitKind
import com.thedirepack.constructioncalc.core.model.Panel
import com.thedirepack.constructioncalc.core.model.design.Wire
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.components.ChoiceField
import com.thedirepack.constructioncalc.core.ui.components.ConfirmDialog
import com.thedirepack.constructioncalc.core.ui.components.TextInputDialog
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices
import com.thedirepack.constructioncalc.feature.panels.domain.BuiltInPanelTemplates
import com.thedirepack.constructioncalc.feature.panels.domain.PanelExportLayout
import com.thedirepack.constructioncalc.feature.panels.domain.PanelFacts
import com.thedirepack.constructioncalc.feature.panels.domain.PanelTemplate
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
    val templates = rememberPanelTemplates()
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
        PanelPrompt.Add -> AddPanelDialog(
            suggestedName = ctx.project.suggestedPanelName(),
            templates = templates,
            onConfirm = { name, template ->
                val panel = template.instantiate(name)
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
private fun AddPanelDialog(
    suggestedName: String,
    templates: List<PanelTemplate>,
    onConfirm: (String, PanelTemplate) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(suggestedName) { mutableStateOf(suggestedName) }
    var selectedId by rememberSaveable { mutableStateOf(BuiltInPanelTemplates.BLANK_208Y.id) }
    val selectedTemplate = templates.firstOrNull { it.id == selectedId }
        ?: templates.firstOrNull()
        ?: BuiltInPanelTemplates.BLANK_208Y
    val canConfirm = name.isNotBlank()

    val savedTemplates = templates.filter { !it.builtIn }
    val builtInTemplates = templates.filter { it.builtIn }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New panel") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Panel name, e.g. LP-1") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (canConfirm) onConfirm(name.trim(), selectedTemplate) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Template",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState())
                        .selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (savedTemplates.isNotEmpty()) {
                        Text(
                            "Saved templates",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        savedTemplates.forEach { template ->
                            TemplateChoiceRow(
                                template = template,
                                selected = template.id == selectedTemplate.id,
                                onSelect = { selectedId = template.id },
                            )
                        }
                        Text(
                            "Starter templates",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    builtInTemplates.forEach { template ->
                        TemplateChoiceRow(
                            template = template,
                            selected = template.id == selectedTemplate.id,
                            onSelect = { selectedId = template.id },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim(), selectedTemplate) }, enabled = canConfirm) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun TemplateChoiceRow(
    template: PanelTemplate,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val summary = buildList {
        add(template.category.label)
        add(template.system.label)
        add("${template.spaces} spaces")
        if (template.circuits.isNotEmpty()) add(plural(template.circuits.size, "circuit"))
    }.joinToString(" · ")

    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(template.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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
