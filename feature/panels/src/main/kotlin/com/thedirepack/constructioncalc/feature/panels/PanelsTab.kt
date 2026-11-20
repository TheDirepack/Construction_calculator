package com.thedirepack.constructioncalc.feature.panels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.model.CircuitKind
import com.thedirepack.constructioncalc.core.model.Panel
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.components.ConfirmDialog
import com.thedirepack.constructioncalc.core.ui.components.TextInputDialog
import com.thedirepack.constructioncalc.feature.panels.domain.PanelFacts
import com.thedirepack.constructioncalc.core.model.design.Wire
import com.thedirepack.constructioncalc.feature.panels.domain.duplicatePanel
import com.thedirepack.constructioncalc.feature.panels.domain.newPanel
import com.thedirepack.constructioncalc.feature.panels.domain.suggestedPanelName

private sealed interface PanelPrompt {
    data object Add : PanelPrompt
    data class Rename(val panel: Panel) : PanelPrompt
    data class Delete(val panel: Panel) : PanelPrompt
}

@Composable
internal fun PanelsTab(ctx: PanelsContext, export: ExportActions, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    var prompt by remember { mutableStateOf<PanelPrompt?>(null) }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
            PanelCard(ctx, panel, onOpen = { onOpen(panel.id) }, onRename = { prompt = PanelPrompt.Rename(panel) }, onDelete = { prompt = PanelPrompt.Delete(panel) })
        }
        if (ctx.project.panels.isNotEmpty()) {
            item(key = "export") {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Export", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Text(
                            "A PDF with one panel per page (company header, schedule, wire sizes, totals), or plain text.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { export.pdf(null) }) { Text("Share panel schedule (PDF)") }
                        }
                        OutlinedButton(onClick = { export.text(null) }) { Text("Share as text") }
                    }
                }
            }
        }
    }

    when (val p = prompt) {
        null -> Unit
        PanelPrompt.Add -> TextInputDialog("New panel", ctx.project.suggestedPanelName(), "Add", { name ->
            val panel = newPanel(name)
            ctx.edit { project, now -> project.addPanel(panel, now) }
            prompt = null
            onOpen(panel.id)
        }, { prompt = null }, label = "Panel name, e.g. LP-1")
        is PanelPrompt.Rename -> TextInputDialog("Rename panel", p.panel.name, "Rename", { name ->
            ctx.edit { project, now -> project.updatePanel(p.panel.id, now) { it.copy(name = name) } }
            prompt = null
        }, { prompt = null })
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
private fun PanelCard(ctx: PanelsContext, panel: Panel, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val pd = ctx.design.panels.firstOrNull { it.panel.id == panel.id }
    val warnings = (pd?.conflicts.orEmpty() + pd?.notes.orEmpty().map { it.text })
    val used = panel.circuits.sumOf { if (it.kind == CircuitKind.SPACE) 0 else it.poles }
    Card(
        Modifier.fillMaxWidth().clickable(onClickLabel = "Open ${panel.name}", onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 12.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(panel.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "Options for ${panel.name}" }) {
                        Icon(painterResource(R.drawable.ic_more_vert), contentDescription = null)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; onOpen() })
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
