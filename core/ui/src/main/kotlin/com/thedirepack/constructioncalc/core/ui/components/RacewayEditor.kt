package com.thedirepack.constructioncalc.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.electrical.ConduitTable
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.model.Raceway
import com.thedirepack.constructioncalc.core.model.stored
import com.thedirepack.constructioncalc.core.ui.format
import com.thedirepack.constructioncalc.core.ui.formatDecimal
import com.thedirepack.constructioncalc.core.ui.toDecimalOrNull

/**
 * A sheet to edit a run's [Raceway]: conduit type, trade size (or Auto: the smallest that fits),
 * length, ambient temperature, rooftop and nipple. Shared by the Panels tool and the project page.
 *
 * @param initial the run's current raceway, or null to start from defaults (EMT, auto size)
 * @param onRemove when given, shows "Remove conduit" (turns the run back into a plain run)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RacewayEditorSheet(
    title: String,
    initial: Raceway?,
    onSave: (Raceway) -> Unit,
    onDismiss: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    var draft by remember { mutableStateOf(initial ?: Raceway()) }
    var ambientText by remember { mutableStateOf(initial?.ambientC?.let { formatDecimal(it, 1) }.orEmpty()) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            ChoiceField(
                label = "Conduit type",
                options = ConduitType.entries,
                selected = draft.conduitType,
                onSelect = { type ->
                    val sizes = ConduitTable.tradeSizes(type)
                    draft = draft.copy(conduitType = type, tradeSize = draft.tradeSize?.takeIf { it in sizes })
                },
                optionLabel = { it.displayName },
                supportingText = draft.conduitType.fullName,
            )
            ChoiceField<TradeSize?>(
                label = "Trade size",
                options = listOf<TradeSize?>(null) + ConduitTable.tradeSizes(draft.conduitType),
                selected = draft.tradeSize,
                onSelect = { draft = draft.copy(tradeSize = it) },
                optionLabel = { it?.display ?: "Auto (smallest that fits)" },
            )
            MeasureField(
                label = "Length of run",
                value = draft.length?.toQuantity(),
                onValueChange = { draft = draft.copy(length = it?.stored()) },
                supportingText = "Used for voltage drop. Tap for the calculator or a value from another tool.",
            )
            NumberField(
                label = "Ambient temperature",
                value = ambientText,
                onValueChange = { ambientText = it },
                unit = "°C",
                supportingText = "Leave blank to use the project default.",
                allowNegative = true,
            )
            SwitchRow(
                title = "Rooftop",
                detail = "In sunlight less than 3/4\" above a roof (adds a temperature adder, NEC 310.15(B)(2)).",
                checked = draft.rooftop,
                onChange = { draft = draft.copy(rooftop = it) },
            )
            SwitchRow(
                title = "Nipple (24\" or shorter)",
                detail = "No conductor-count adjustment, and 60% conduit fill.",
                checked = draft.nipple,
                onChange = { draft = draft.copy(nipple = it) },
            )
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onRemove != null) {
                    TextButton(onClick = onRemove) { Text("Remove conduit", color = MaterialTheme.colorScheme.error) }
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = { onSave(draft.copy(ambientC = ambientText.toDecimalOrNull())) }) { Text("Save") }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp))
    }
}

/**
 * One line describing a run's conduit, e.g. `1" EMT · 150' · 6 circuits`.
 *
 * @param circuits circuits in the run, or null to leave that part out
 * @param autoSize the size the app picked when the raceway's own size is Auto
 */
fun racewaySummary(raceway: Raceway, settings: AppSettings, circuits: Int? = null, autoSize: TradeSize? = null): String {
    val size = raceway.tradeSize ?: autoSize
    val conduit = when {
        size == null -> "${raceway.conduitType.displayName} (auto size)"
        raceway.tradeSize == null -> "${size.display} ${raceway.conduitType.displayName} (auto)"
        else -> "${size.display} ${raceway.conduitType.displayName}"
    }
    return listOfNotNull(
        conduit,
        raceway.length?.let { settings.format(it.toQuantity()) },
        circuits?.let { if (it == 0) "no circuits" else if (it == 1) "1 circuit" else "$it circuits" },
    ).joinToString(" · ")
}
