package com.thedirepack.constructioncalc.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thedirepack.constructioncalc.core.data.Trade
import com.thedirepack.constructioncalc.core.measure.UnitSystem
import com.thedirepack.constructioncalc.core.ui.components.CodeJurisdictionChoices
import com.thedirepack.constructioncalc.core.ui.components.SegmentedChoice
import com.thedirepack.constructioncalc.core.ui.components.ToggleRow
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices
import com.thedirepack.constructioncalc.feature.footinch.FootInchKeypadSetup
import com.thedirepack.constructioncalc.feature.footinch.FootInchTool
import com.thedirepack.constructioncalc.feature.footinch.KeypadLayout

/**
 * First-launch quick setup: trade, where you work (code), and units/keypad. Every choice is saved as
 * it's made and is also in Settings, where this setup can be run again. Skip keeps the defaults.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickSetupScreen(onDone: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val steps = 3
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Quick setup · ${step + 1} of $steps") },
                actions = { TextButton(onClick = onDone) { Text("Skip") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).navigationBarsPadding()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (step) {
                    0 -> TradeStep()
                    1 -> CodeStep()
                    else -> UnitsStep()
                }
                Text(
                    "You can change all of this later in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (step > 0) OutlinedButton(onClick = { step-- }) { Text("Back") }
                Spacer(Modifier.weight(1f))
                if (step < steps - 1) {
                    Button(onClick = { step++ }) { Text("Next") }
                } else {
                    Button(onClick = onDone) { Text("Done") }
                }
            }
        }
    }
}

@Composable
private fun StepTitle(title: String, detail: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall)
    Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun TradeStep() {
    val repo = LocalAppServices.current.settings
    val settings = rememberAppSettings()
    StepTitle("What's your trade?", "The app opens with the tools for this trade.")
    Column(Modifier.selectableGroup()) {
        Trade.entries.forEach { trade ->
            val selected = settings.primaryTrade == trade
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selected,
                        enabled = trade.isAvailable,
                        role = Role.RadioButton,
                        onClick = { repo.setPrimaryTrade(trade) },
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RadioButton(selected = selected, onClick = null, enabled = trade.isAvailable)
                Column(Modifier.weight(1f)) {
                    val color = if (trade.isAvailable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    Text(trade.label, style = MaterialTheme.typography.bodyLarge, color = color)
                    Text(trade.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun CodeStep() {
    StepTitle(
        "Where do you work?",
        "Electrical answers follow the NEC edition your area adopted, plus any local changes entered in the app.",
    )
    CodeJurisdictionChoices()
}

@Composable
private fun UnitsStep() {
    val services = LocalAppServices.current
    val repo = services.settings
    val settings = rememberAppSettings()
    val store = remember(services) { repo.module(FootInchTool.ID) }
    val version by store.changes.collectAsStateWithLifecycle()
    val layout = remember(version) { FootInchKeypadSetup.layout(store) }
    val badges = remember(version) { FootInchKeypadSetup.shapeBadges(store) }

    StepTitle("Units and keypad", "How lengths are shown and typed.")
    SegmentedChoice(
        options = UnitSystem.entries,
        selected = settings.unitSystem,
        onSelect = repo::setUnitSystem,
        label = "Units",
        optionLabel = { if (it == UnitSystem.IMPERIAL) "Imperial (ft-in)" else "Metric (mm, m)" },
    )
    if (settings.unitSystem == UnitSystem.IMPERIAL) {
        SegmentedChoice(
            options = KeypadLayout.entries,
            selected = layout,
            onSelect = { FootInchKeypadSetup.setLayout(store, it) },
            label = "Foot-Inch keypad",
            optionLabel = { it.label },
        )
        Text(
            when (layout) {
                KeypadLayout.CLASSIC -> "Classic: one number pad with Feet and Inches keys."
                KeypadLayout.DUAL_SPLIT -> "Dual-Split: a Feet pad and an Inches pad side by side."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (layout == KeypadLayout.DUAL_SPLIT) {
            ToggleRow(
                "Mark keys with ' and \"",
                checked = badges,
                onCheckedChange = { FootInchKeypadSetup.setShapeBadges(store, it) },
                detail = "Feet keys show ' and Inches keys show \", so the pads aren't told apart by color alone.",
            )
        }
    }
}
