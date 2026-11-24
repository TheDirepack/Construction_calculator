package com.thedirepack.constructioncalc.feature.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.components.ConfirmDialog
import com.thedirepack.constructioncalc.core.ui.components.SettingsGroup
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices
import com.thedirepack.constructioncalc.core.ui.tool.ToolCategory
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost
import com.thedirepack.constructioncalc.core.ui.tool.ToolIds
import com.thedirepack.constructioncalc.core.ui.tool.ToolModule
import com.thedirepack.constructioncalc.feature.panels.domain.PanelExportLayout
import com.thedirepack.constructioncalc.feature.panels.domain.SavedPanelTemplate

object PanelsTool : ToolModule {
    const val ID = ToolIds.PANELS

    override val id = ID
    override val title = "Panels & Circuits"
    override val summary = "Panel schedules, circuits, runs and wire sizing for the project"
    override val category = ToolCategory.ELECTRICAL
    override val icon = R.drawable.ic_tool_panel
    override val hasSettings = true

    @Composable
    override fun Content(host: ToolHost) = PanelsScreen(host)

    @Composable
    override fun SettingsContent() = PanelsSettingsPage()
}

@Composable
private fun PanelsSettingsPage() {
    val store = LocalAppServices.current.settings.module(PanelsTool.ID)
    val defaultLayout = rememberDefaultExportLayout()
    val customTemplates = rememberCustomPanelTemplates()
    var deleting by remember { mutableStateOf<SavedPanelTemplate?>(null) }

    SettingsGroup("Default export layout") {
        Column(Modifier.selectableGroup()) {
            PanelExportLayout.entries.forEach { layout ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(
                            selected = layout == defaultLayout,
                            role = Role.RadioButton,
                            onClick = { PanelTemplateStore.setDefaultLayout(store, layout) },
                        )
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = layout == defaultLayout, onClick = null)
                    Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(layout.label, style = MaterialTheme.typography.bodyLarge)
                        Text(layout.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    SettingsGroup("Saved panel templates") {
        if (customTemplates.isEmpty()) {
            Text(
                "No saved panel templates yet. Use ⋮ → Save as template on any panel to save one.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
        } else {
            customTemplates.forEach { template ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(template.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${template.system.label} · ${template.snappedSpaces} spaces · ${plural(template.circuits.size, "circuit")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { deleting = template }) {
                        Icon(painterResource(R.drawable.ic_delete), contentDescription = "Delete ${template.name}")
                    }
                }
            }
        }
    }

    deleting?.let { target ->
        ConfirmDialog(
            title = "Delete ${target.name}?",
            message = "Removes this saved panel template. Panels already created from it stay in their projects.",
            confirmLabel = "Delete",
            onConfirm = {
                PanelTemplateStore.deleteTemplate(store, target.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}
