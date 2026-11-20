package com.thedirepack.constructioncalc.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.BuildConfig
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.data.BackupSections
import com.thedirepack.constructioncalc.core.model.AppFile
import com.thedirepack.constructioncalc.core.ui.components.ActionRow
import com.thedirepack.constructioncalc.core.ui.components.rememberAppFileOpener
import com.thedirepack.constructioncalc.core.ui.components.rememberBackupExporter
import com.thedirepack.constructioncalc.core.ui.components.GeneralSettings
import com.thedirepack.constructioncalc.core.ui.components.MeasurementSettings
import com.thedirepack.constructioncalc.core.ui.components.SettingsGroup

/**
 * The app's only settings screen. The main page has General and Measurements; each tool with
 * options ([com.thedirepack.constructioncalc.core.ui.tool.ToolModule.hasSettings]) gets its own
 * page, reached from here or straight from that tool.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    toolId: String?,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenToolPage: (String) -> Unit,
    onImport: (AppFile) -> Unit,
    onMessage: (String) -> Unit,
) {
    val tool = toolId?.let { id -> ToolRegistry.all.firstOrNull { it.id == id && it.hasSettings } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tool?.let { "${it.title} settings" } ?: "Settings") },
                navigationIcon = {
                    if (canGoBack) {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                        }
                    } else {
                        IconButton(onClick = onOpenDrawer) {
                            Icon(painterResource(R.drawable.ic_menu), contentDescription = "Open tools menu")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            if (tool != null) {
                tool.SettingsContent()
                SettingsGroup("App") {
                    ActionRow("All settings", onClick = { onOpenToolPage("") }, subtitle = "Theme, animations, measurement display")
                }
            } else {
                GeneralSettings()
                MeasurementSettings()
                val toolsWithSettings = ToolRegistry.all.filter { it.hasSettings }
                if (toolsWithSettings.isNotEmpty()) {
                    SettingsGroup("Tools") {
                        toolsWithSettings.forEach { t -> ActionRow(t.title, onClick = { onOpenToolPage(t.id) }, subtitle = t.summary) }
                    }
                }
                DataSettings(onImport, onMessage)
                SettingsGroup("About") {
                    Text(
                        "Construction Calc ${BuildConfig.VERSION_NAME}\nNo permissions required. Projects stay on this device " +
                            "unless you share or save them.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
        }
    }
}

/** Save/load everything: backups (projects, settings, tool data) by share sheet or file, and import. */
@Composable
private fun DataSettings(onImport: (AppFile) -> Unit, onMessage: (String) -> Unit) {
    val exporter = rememberBackupExporter(onMessage)
    val open = rememberAppFileOpener { result ->
        result.onSuccess(onImport).onFailure { onMessage(it.message ?: "Couldn't open that file") }
    }
    SettingsGroup("Data") {
        ActionRow(
            "Share full backup",
            onClick = { exporter.share(BackupSections.ALL, "Construction Calc backup") },
            subtitle = "Every project, all settings and tool data in one file, by email, messages, Drive…",
        )
        ActionRow(
            "Save full backup to a file",
            onClick = { exporter.saveToFile(BackupSections.ALL, "Construction Calc backup") },
        )
        ActionRow(
            "Share all projects",
            onClick = { exporter.share(BackupSections.PROJECTS_ONLY, "Construction Calc projects") },
            subtitle = "Projects only, without your settings.",
        )
        ActionRow(
            "Import or restore from a file",
            onClick = open,
            subtitle = "Opens a project file or a backup. You choose what to restore.",
        )
    }
}
