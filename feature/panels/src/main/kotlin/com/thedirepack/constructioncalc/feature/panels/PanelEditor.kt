package com.thedirepack.constructioncalc.feature.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.electrical.StandardOcpd
import com.thedirepack.constructioncalc.core.model.Circuit
import com.thedirepack.constructioncalc.core.model.CircuitKind
import com.thedirepack.constructioncalc.core.model.CircuitRef
import com.thedirepack.constructioncalc.core.model.Panel
import com.thedirepack.constructioncalc.core.model.PanelSystem
import com.thedirepack.constructioncalc.core.model.design.PanelDesign
import com.thedirepack.constructioncalc.core.ui.components.ChoiceField
import com.thedirepack.constructioncalc.core.ui.components.FormSection
import com.thedirepack.constructioncalc.core.ui.components.SegmentedChoice
import com.thedirepack.constructioncalc.core.ui.components.TextInputDialog
import com.thedirepack.constructioncalc.feature.panels.domain.CircuitAt
import com.thedirepack.constructioncalc.feature.panels.domain.LoadMath
import com.thedirepack.constructioncalc.feature.panels.domain.PanelFacts
import com.thedirepack.constructioncalc.feature.panels.domain.ScheduleLayout
import com.thedirepack.constructioncalc.core.model.design.Wire
import com.thedirepack.constructioncalc.feature.panels.domain.changePanelSystem
import com.thedirepack.constructioncalc.feature.panels.domain.circuitLabel
import com.thedirepack.constructioncalc.feature.panels.domain.feedCandidates
import com.thedirepack.constructioncalc.feature.panels.domain.findCircuit
import com.thedirepack.constructioncalc.feature.panels.domain.newCircuit

private enum class EditorSection(val label: String) { SCHEDULE("Schedule"), SETTINGS("Settings") }

private data class SheetTarget(val circuit: Circuit, val isNew: Boolean)

private val COMMON_SPACES = listOf(12, 18, 20, 24, 30, 42, 54, 60, 84)
private const val CUSTOM_SPACES = -1

/** One panel: its schedule with totals, and its settings. */
@Composable
internal fun PanelEditor(ctx: PanelsContext, panel: Panel, modifier: Modifier = Modifier) {
    var section by rememberSaveable { mutableStateOf(EditorSection.SCHEDULE) }
    var sheet by remember { mutableStateOf<SheetTarget?>(null) }
    val pd = ctx.design.panels.firstOrNull { it.panel.id == panel.id }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.padding(horizontal = 4.dp)) {
            Text(panel.name, style = MaterialTheme.typography.titleLarge)
            Text(PanelFacts.summary(panel), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (panel.location.isNotBlank()) {
                Text(panel.location, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SegmentedChoice(EditorSection.entries, section, { section = it }, optionLabel = { it.label })
        when (section) {
            EditorSection.SCHEDULE -> ScheduleSection(ctx, panel, pd, onAddAt = { slot ->
                val c = newCircuit(panel, 1, slot)
                if (c == null) ctx.host.showMessage("${panel.name} has no free positions") else sheet = SheetTarget(c, true)
            }, onOpen = { sheet = SheetTarget(it, false) })
            EditorSection.SETTINGS -> PanelSettings(ctx, panel)
        }
    }

    sheet?.let { target ->
        CircuitSheet(ctx, panel.id, target.circuit, target.isNew, onDismiss = { sheet = null })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleSection(
    ctx: PanelsContext,
    panel: Panel,
    pd: PanelDesign?,
    onAddAt: (Int) -> Unit,
    onOpen: (Circuit) -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    val activeFilter = filter?.takeIf { id -> ctx.project.groups.any { it.id == id } }

    if (!ctx.readOnly) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onAddAt(-1) }) { Text("Add circuit") }
            Text(
                "Tap a position to add or edit.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (ctx.project.groups.isNotEmpty()) {
        FormSection("Filter by group") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = activeFilter == null, onClick = { filter = null }, label = { Text("All") })
                ctx.project.groups.forEach { g ->
                    FilterChip(
                        selected = activeFilter == g.id,
                        onClick = { filter = if (activeFilter == g.id) null else g.id },
                        label = { Text(g.name) },
                        leadingIcon = { GroupTag(g) },
                    )
                }
            }
            if (activeFilter != null) {
                val n = panel.circuits.count { activeFilter in it.groupIds }
                Text("${plural(n, "circuit")} in this group here. Others are dimmed.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    ScheduleGrid(ctx, panel, activeFilter, onAddAt = onAddAt, onOpen = onOpen)

    val unplaced = remember(panel) { ScheduleLayout.build(panel).unplaced }
    if (unplaced.isNotEmpty()) {
        FormSection("Not on the schedule") {
            Text(
                "These breakers overlap another one or sit past position ${panel.spaces}. Open one to give it a position.",
                style = MaterialTheme.typography.bodySmall,
            )
            unplaced.forEach { c ->
                OutlinedButton(onClick = { onOpen(c) }, modifier = Modifier.fillMaxWidth()) {
                    Text(circuitLabel(panel, c) + " · " + ScheduleLayout.breakerText(c))
                }
            }
        }
    }
    if (pd != null) Totals(pd, panel)
}

@Composable
private fun Totals(pd: PanelDesign, panel: Panel) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Totals", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            pd.phaseVa.forEach { (phase, va) ->
                Row(Modifier.fillMaxWidth()) {
                    Text("Phase ${phase.name}", Modifier.weight(1f))
                    Text("${Wire.va(va)} · ${Wire.amps(pd.phaseAmps.getValue(phase))}")
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Text("Connected total", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(Wire.va(pd.totalVa), style = MaterialTheme.typography.titleMedium)
            }
            val imbalance = LoadMath.imbalancePercent(pd.phaseVa.values)
            Text(
                (if (imbalance != null) "Imbalance ${Wire.percent(imbalance)} (largest phase vs. average). " else "") +
                    "${panel.circuits.sumOf { if (it.kind == CircuitKind.SPACE) 0 else it.poles }} of ${panel.spaces} positions used.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            (pd.conflicts + pd.notes.map { it.text }).forEach { WarningText(it) }
        }
    }
}

@Composable
private fun PanelSettings(ctx: PanelsContext, panel: Panel) {
    var customSpaces by remember { mutableStateOf(false) }
    fun update(change: (Panel) -> Panel) = ctx.edit { p, now -> p.updatePanel(panel.id, now, change) }
    val locked = ctx.readOnly

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FormSection("Panel") {
            OutlinedTextField(
                value = panel.name,
                onValueChange = { v -> update { it.copy(name = v) } },
                label = { Text("Name") },
                singleLine = true,
                readOnly = locked,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = panel.location,
                onValueChange = { v -> update { it.copy(location = v) } },
                label = { Text("Location") },
                singleLine = true,
                readOnly = locked,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        FormSection("Supply") {
            ChoiceField(
                label = "System",
                options = PanelSystem.entries,
                selected = panel.system,
                onSelect = { s -> if (!locked) ctx.edit { p, now -> p.changePanelSystem(panel.id, s, now) } },
                optionLabel = { it.label },
                supportingText = if (panel.system.phases == 1) "Single-phase panels hold 1- and 2-pole breakers." else null,
            )
            ChoiceField(
                label = "Spaces",
                options = (COMMON_SPACES + panel.spaces).distinct().sorted() + CUSTOM_SPACES,
                selected = panel.spaces,
                onSelect = { n -> if (n == CUSTOM_SPACES) customSpaces = !locked else update { it.copy(spaces = n) } },
                optionLabel = { if (it == CUSTOM_SPACES) "Custom…" else "$it spaces" },
            )
            ChoiceField<Int?>(
                label = "Bus rating",
                options = listOf<Int?>(null) + StandardOcpd.ratings.filter { it in 30..6000 },
                selected = panel.busAmps,
                onSelect = { a -> update { it.copy(busAmps = a) } },
                optionLabel = { it?.let { a -> "$a A" } ?: "Not set" },
            )
            ChoiceField<Int?>(
                label = "Main breaker",
                options = listOf<Int?>(null) + StandardOcpd.ratings.filter { it in 15..6000 },
                selected = panel.mainAmps,
                onSelect = { a -> update { it.copy(mainAmps = a) } },
                optionLabel = { it?.let { a -> "$a A main breaker" } ?: "Main lugs only (MLO)" },
            )
            val candidates = remember(ctx.project, panel.id) { ctx.project.feedCandidates(panel.id) }
            val current = panel.fedFrom?.let { ctx.project.findCircuit(it.circuitId) }
            val options: List<CircuitAt?> = listOf<CircuitAt?>(null) + candidates + listOfNotNull(current?.takeIf { c -> candidates.none { it.circuit.id == c.circuit.id } })
            ChoiceField(
                label = "Fed from",
                options = options,
                selected = current,
                onSelect = { at -> update { it.copy(fedFrom = at?.let { a -> CircuitRef(a.panel.id, a.circuit.id) }) } },
                optionLabel = { it?.let { a -> circuitLabel(a.panel, a.circuit) } ?: "Not set (service or utility)" },
                supportingText = if (candidates.isEmpty()) "Add a circuit in another panel to feed this one." else null,
            )
        }
        FormSection("Note") {
            OutlinedTextField(
                value = panel.note,
                onValueChange = { v -> update { it.copy(note = v) } },
                label = { Text("Note") },
                readOnly = locked,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (customSpaces) {
        TextInputDialog("Number of spaces", panel.spaces.toString(), "Set", { text ->
            text.toIntOrNull()?.takeIf { it in 2..Panel.MAX_SPACES }?.let { n -> update { it.copy(spaces = n) } }
                ?: ctx.host.showMessage("Enter a number from 2 to ${Panel.MAX_SPACES}")
            customSpaces = false
        }, { customSpaces = false }, label = "Spaces (2–${Panel.MAX_SPACES})")
    }
}
