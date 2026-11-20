package com.thedirepack.constructioncalc.feature.panels

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.thedirepack.constructioncalc.core.model.CircuitGroup
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.components.ConfirmDialog
import com.thedirepack.constructioncalc.core.ui.components.TextInputDialog
import com.thedirepack.constructioncalc.feature.panels.domain.GroupColors
import com.thedirepack.constructioncalc.feature.panels.domain.Wire
import com.thedirepack.constructioncalc.feature.panels.domain.addGroup
import com.thedirepack.constructioncalc.feature.panels.domain.circuitLabel
import com.thedirepack.constructioncalc.feature.panels.domain.circuitsInGroup
import com.thedirepack.constructioncalc.feature.panels.domain.deleteGroup
import com.thedirepack.constructioncalc.feature.panels.domain.newGroup
import com.thedirepack.constructioncalc.feature.panels.domain.updateGroup

private sealed interface GroupPrompt {
    data object New : GroupPrompt
    data class Rename(val group: CircuitGroup) : GroupPrompt
    data class Recolor(val group: CircuitGroup) : GroupPrompt
    data class Delete(val group: CircuitGroup) : GroupPrompt
}

/** Circuit groups ("Kitchen", "RTU-3"): tags that cut across panels. */
@Composable
internal fun GroupsTab(ctx: PanelsContext, modifier: Modifier = Modifier) {
    var prompt by remember { mutableStateOf<GroupPrompt?>(null) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!ctx.readOnly) {
            item(key = "new") { Button(onClick = { prompt = GroupPrompt.New }) { Text("New group") } }
        }
        if (ctx.project.groups.isEmpty()) {
            item(key = "empty") {
                Text(
                    "Groups tag circuits across panels, like all the kitchen circuits or everything on one rooftop unit. " +
                        "Tag circuits when you edit them, then filter a panel's schedule by group.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(ctx.project.groups, key = { it.id }) { group ->
            GroupCard(
                ctx, group,
                onRename = { prompt = GroupPrompt.Rename(group) },
                onRecolor = { prompt = GroupPrompt.Recolor(group) },
                onDelete = { prompt = GroupPrompt.Delete(group) },
            )
        }
    }

    when (val p = prompt) {
        null -> Unit
        GroupPrompt.New -> TextInputDialog("New group", "", "Create", { name ->
            val group = ctx.project.newGroup(name)
            ctx.edit { project, now -> project.addGroup(group, now) }
            prompt = null
        }, { prompt = null }, label = "e.g. Kitchen")
        is GroupPrompt.Rename -> TextInputDialog("Rename group", p.group.name, "Rename", { name ->
            ctx.edit { project, now -> project.updateGroup(p.group.id, now) { it.copy(name = name) } }
            prompt = null
        }, { prompt = null })
        is GroupPrompt.Recolor -> ColorDialog(p.group, onPick = { argb ->
            ctx.edit { project, now -> project.updateGroup(p.group.id, now) { it.copy(color = argb) } }
            prompt = null
        }, onDismiss = { prompt = null })
        is GroupPrompt.Delete -> ConfirmDialog(
            "Delete ${p.group.name}?",
            "The group is removed from its ${plural(ctx.project.circuitsInGroup(p.group.id).size, "circuit")}. The circuits stay.",
            "Delete",
            onConfirm = {
                ctx.edit { project, now -> project.deleteGroup(p.group.id, now) }
                prompt = null
            },
            onDismiss = { prompt = null },
        )
    }
}

@Composable
private fun GroupCard(ctx: PanelsContext, group: CircuitGroup, onRename: () -> Unit, onRecolor: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val members = ctx.project.circuitsInGroup(group.id)
    val totalVa = members.sumOf { it.circuit.loadVa ?: 0.0 }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 12.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GroupTag(group, full = true)
                Text("  ${GroupColors.of(group.color).name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (!ctx.readOnly) {
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "Options for ${group.name}" }) {
                            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = null)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                            DropdownMenuItem(text = { Text("Change colour") }, onClick = { menu = false; onRecolor() })
                            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                        }
                    }
                }
            }
            Column(Modifier.padding(end = 12.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${plural(members.size, "circuit")} · ${Wire.va(totalVa)}", style = MaterialTheme.typography.titleSmall)
                if (members.isEmpty()) Text("No circuits yet.", style = MaterialTheme.typography.bodySmall)
                members.forEach { (panel, c) ->
                    Text(
                        "• " + circuitLabel(panel, c) + (c.loadVa?.let { " · ${Wire.va(it)}" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorDialog(group: CircuitGroup, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Colour for ${group.name}") },
        text = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GroupColors.palette.forEach { c ->
                    FilterChip(
                        selected = c.argb == group.color,
                        onClick = { onPick(c.argb) },
                        label = { Text(c.name) },
                        leadingIcon = { GroupTag(group.copy(color = c.argb), full = false) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
