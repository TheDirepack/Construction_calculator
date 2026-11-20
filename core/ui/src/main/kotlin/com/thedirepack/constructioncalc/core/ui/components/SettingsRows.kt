package com.thedirepack.constructioncalc.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thedirepack.constructioncalc.core.data.ThemeMode
import com.thedirepack.constructioncalc.core.measure.Precision
import com.thedirepack.constructioncalc.core.measure.input.LengthDisplay
import com.thedirepack.constructioncalc.core.ui.fractionText
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices

// Building blocks for the single Settings screen and for tools' settings pages.

@Composable
fun SettingsGroup(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        content()
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, subtitle: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** A setting with a few options, shown as a segmented row under its title. */
@Composable
fun <T> ChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    optionLabel: (T) -> String,
    subtitle: String? = null,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SegmentedChoice(options = options, selected = selected, onSelect = onSelect, optionLabel = optionLabel)
    }
}

@Composable
fun ActionRow(title: String, onClick: () -> Unit, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Theme and motion. */
@Composable
fun GeneralSettings() {
    val repo = LocalAppServices.current.settings
    val settings = rememberAppSettings()
    SettingsGroup("General") {
        ChoiceRow("Theme", ThemeMode.entries, settings.themeMode, repo::setThemeMode, { it.label })
        SwitchRow(
            "Animations",
            checked = settings.animations,
            onCheckedChange = repo::setAnimations,
            subtitle = "Turn off to make sheets and transitions instant.",
        )
    }
}

/** How lengths look in every tool. */
@Composable
fun MeasurementSettings() {
    val repo = LocalAppServices.current.settings
    val settings = rememberAppSettings()
    SettingsGroup("Measurements") {
        ChoiceRow(
            "Show lengths as",
            LengthDisplay.entries,
            settings.lengthDisplay,
            repo::setLengthDisplay,
            { it.label },
            subtitle = "Feet & inches, inches only, or Auto: inches when you entered inches. Applies to every tool.",
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Fraction precision", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Lengths are rounded to the nearest fraction of an inch.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Precision.entries.forEach { p ->
                    FilterChip(
                        selected = p == settings.precision,
                        onClick = { repo.setPrecision(p) },
                        label = { Text(fractionText(p.label + "\"")) },
                    )
                }
            }
        }
    }
}

/** Quick Auto / ft-in / in switch for the app-wide length display (also in Settings). */
@Composable
fun LengthDisplayToggle(modifier: Modifier = Modifier) {
    val repo = LocalAppServices.current.settings
    val settings = rememberAppSettings()
    SegmentedChoice(
        options = LengthDisplay.entries,
        selected = settings.lengthDisplay,
        onSelect = repo::setLengthDisplay,
        optionLabel = { it.label },
        modifier = modifier,
    )
}

/** A tool's Boolean setting as Compose state. */
@Composable
fun rememberModuleBoolean(toolId: String, key: String, default: Boolean): Boolean {
    val store = LocalAppServices.current.settings.module(toolId)
    val version by store.changes.collectAsStateWithLifecycle()
    return remember(version) { store.getBoolean(key, default) }
}
