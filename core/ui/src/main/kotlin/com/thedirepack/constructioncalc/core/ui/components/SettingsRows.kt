package com.thedirepack.constructioncalc.core.ui.components

import com.thedirepack.constructioncalc.core.measure.MetricResolution

import com.thedirepack.constructioncalc.core.measure.MetricDisplay

import com.thedirepack.constructioncalc.core.measure.UnitSystem
import com.thedirepack.constructioncalc.core.measure.FeetInchStyle

import androidx.compose.ui.semantics.Role

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.RadioButton
import com.thedirepack.constructioncalc.core.electrical.code.CodeEdition
import com.thedirepack.constructioncalc.core.electrical.code.CodeRegions
import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.CodeProfile
import com.thedirepack.constructioncalc.core.electrical.code.CodeResolution
import com.thedirepack.constructioncalc.core.measure.FractionRounding

import androidx.compose.foundation.layout.heightIn

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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

/** A labelled switch whose whole row toggles it (48 dp tall, read as a switch). The app's one switch row. */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    detail: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp))
    }
}

/** [ToggleRow] laid out for the Settings screen. */
@Composable
fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, subtitle: String? = null) =
    ToggleRow(title, checked, onCheckedChange, subtitle, Modifier.padding(horizontal = 24.dp, vertical = 4.dp))

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
        val planned = com.thedirepack.constructioncalc.core.data.Trade.entries.filterNot { it.isAvailable }
        ChoiceRow(
            "Trade",
            com.thedirepack.constructioncalc.core.data.Trade.entries.filter { it.isAvailable },
            settings.primaryTrade,
            repo::setPrimaryTrade,
            { it.label },
            subtitle = if (planned.isEmpty()) null else "${planned.joinToString(" and ") { it.label }}: coming later.",
        )
        ChoiceRow("Theme", ThemeMode.entries, settings.themeMode, repo::setThemeMode, { it.label })
        SwitchRow(
            "Animations",
            checked = settings.animations,
            onCheckedChange = repo::setAnimations,
            subtitle = "Turn off to make sheets and transitions instant.",
        )
    }
}

/** Company details printed on shared projects, panel schedules and other exports. */
@Composable
fun CompanySettings() {
    val repo = LocalAppServices.current.settings
    val company = rememberAppSettings().company
    SettingsGroup("Company") {
        Text(
            "Shown at the top of shared projects and panel schedules. Leave blank to omit.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        TextSettingRow("Company name", company.name) { repo.setCompany(company.copy(name = it)) }
        TextSettingRow("Prepared by", company.preparedBy) { repo.setCompany(company.copy(preparedBy = it)) }
        TextSettingRow("License #", company.license) { repo.setCompany(company.copy(license = it)) }
        TextSettingRow("Phone", company.phone, KeyboardType.Phone) { repo.setCompany(company.copy(phone = it)) }
        TextSettingRow("Email", company.email, KeyboardType.Email) { repo.setCompany(company.copy(email = it)) }
        TextSettingRow("Address", company.address, singleLine = false) { repo.setCompany(company.copy(address = it)) }
    }
}

/** A text setting, saved as you type. */
@Composable
fun TextSettingRow(
    label: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
    )
}

/** How lengths look in every tool. */
@Composable
fun MeasurementSettings() {
    val repo = LocalAppServices.current.settings
    val settings = rememberAppSettings()
    SettingsGroup("Measurements") {
        ChoiceRow(
            "Units",
            UnitSystem.entries,
            settings.unitSystem,
            repo::setUnitSystem,
            { it.label },
            subtitle = "Every tool shows and asks for lengths, temperatures, weights and forces in this system. Saved values are never changed, so you can switch back at any time.",
        )
        if (settings.unitSystem == UnitSystem.METRIC) {
            ChoiceRow(
                "Show lengths in",
                MetricDisplay.entries,
                settings.metricDisplay,
                repo::setMetricDisplay,
                { it.label },
                subtitle = "Auto shows millimeters under 1 m and meters from there.",
            )
            ChoiceRow(
                "Round lengths to",
                MetricResolution.entries,
                settings.metricResolution,
                repo::setMetricResolution,
                { it.label },
            )
        } else {
            ChoiceRow(
                "Show lengths as",
                LengthDisplay.entries,
                settings.lengthDisplay,
                repo::setLengthDisplay,
                { it.label },
                subtitle = "Feet & inches, inches only, or Auto: inches when you entered inches. Applies to every tool.",
            )
            ChoiceRow(
                "Feet-inch style",
                FeetInchStyle.entries,
                settings.feetInchStyle,
                repo::setFeetInchStyle,
                { it.label },
                subtitle = "Space, hyphenated drawing style, or architectural feet-inches-sixteenths (10-04-03).",
            )
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Fraction precision", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Step size fractional inches are rounded to.",
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
            ChoiceRow(
                "Fraction rounding",
                FractionRounding.entries,
                settings.fractionRounding,
                repo::setFractionRounding,
                {
                    when (it) {
                        FractionRounding.NEAREST -> "Nearest"
                        FractionRounding.UP -> "Round up"
                        FractionRounding.DOWN -> "Round down"
                    }
                },
                subtitle = "Round fractional inches to nearest step, always up, or always down.",
            )
        }
    }
}

/**
 * The electrical code the app follows: a jurisdiction (which picks its adopted NEC edition) and the
 * edition itself, with a plain line saying where values come from.
 */
@Composable
fun CodeSettings() {
    SettingsGroup("Electrical code") {
        Text(
            "Pick where you work. The app uses that area's NEC edition and any local amendments entered for it. " +
                "Projects can use a different code.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        CodeJurisdictionChoices(Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    }
}

/** Jurisdiction picker, edition radio list and the resolved source; shared by Settings and quick setup. */
@Composable
fun CodeJurisdictionChoices(modifier: Modifier = Modifier) {
    val repo = LocalAppServices.current.settings
    val settings = rememberAppSettings()
    val edition = CodeEdition.parse(settings.codeEdition) ?: CodeEdition.NEC_2023
    val region = CodeRegions.getOrDefault(settings.codeRegion)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoiceField(
            label = "Where you work",
            options = CodeRegions.entries,
            selected = region,
            onSelect = { repo.setCodeRegion(it.id) },
            optionLabel = { it.name },
            supportingText = if (region.isNational) {
                region.description
            } else {
                "${region.description} Adopted: ${region.baseEdition.label}. Confirm with your inspector."
            },
        )
        Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("NEC edition", style = MaterialTheme.typography.bodyLarge)
            CodeEdition.entries.forEach { option ->
                val selected = option == edition
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { repo.setCodeEdition(option.name) })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Column(Modifier.weight(1f)) {
                        val adopted = if (option == region.baseEdition && !region.isNational) " (adopted here)" else ""
                        Text("${option.label}$adopted", style = MaterialTheme.typography.bodyMedium)
                        Text(option.statusNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        CodeSourceNotice(Code.resolution(CodeProfile(edition, region.id)))
    }
}

/**
 * Which edition and jurisdiction values come from, and any warning (an edition not in the app,
 * amendments applied). Show it wherever code-based results are shown.
 */
@Composable
fun CodeSourceNotice(resolution: CodeResolution, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "Code: ${resolution.summary}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        resolution.notices.forEach { notice ->
            Text(notice, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * Quick switch for the app-wide length display (also in Settings): Auto / ft-in / in, or in
 * metric Auto / mm / cm / m.
 */
@Composable
fun LengthDisplayToggle(modifier: Modifier = Modifier) {
    val repo = LocalAppServices.current.settings
    val settings = rememberAppSettings()
    if (settings.unitSystem == UnitSystem.METRIC) {
        SegmentedChoice(
            options = MetricDisplay.entries,
            selected = settings.metricDisplay,
            onSelect = repo::setMetricDisplay,
            optionLabel = { if (it == MetricDisplay.AUTO) "Auto" else it.label },
            modifier = modifier,
        )
        return
    }
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
