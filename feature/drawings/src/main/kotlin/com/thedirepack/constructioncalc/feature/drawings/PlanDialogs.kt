package com.thedirepack.constructioncalc.feature.drawings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.data.PlanSymbol
import com.thedirepack.constructioncalc.core.data.PlanSymbols
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.PlanMark
import com.thedirepack.constructioncalc.core.model.PlanMeasurement
import com.thedirepack.constructioncalc.core.model.PlanScaleMode
import com.thedirepack.constructioncalc.core.model.PlanZone
import com.thedirepack.constructioncalc.core.model.StoredQuantity
import com.thedirepack.constructioncalc.core.model.stored
import com.thedirepack.constructioncalc.core.ui.components.MeasureField
import com.thedirepack.constructioncalc.core.ui.components.SettingsGroup
import com.thedirepack.constructioncalc.core.ui.format
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

private val zonePresets = listOf(
    "¼″ = 1′" to 48L,
    "⅛″ = 1′" to 96L,
    "½″ = 1′" to 24L,
    "1″ = 10′" to 120L,
    "1:20" to 20L,
    "1:50" to 50L,
    "1:100" to 100L,
    "1:200" to 200L,
)

@Composable
internal fun ZoneDialog(
    defaultName: String,
    onDismiss: () -> Unit,
    onConfirm: (String, StoredQuantity?) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(defaultName) }
    var preset by rememberSaveable { mutableStateOf<Long?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New scale zone") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80) },
                    label = { Text("Zone name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FilterChip(preset == null, onClick = { preset = null }, label = { Text("Calibrate from a known distance") })
                for ((label, ratio) in zonePresets) {
                    FilterChip(preset == ratio, onClick = { preset = ratio }, label = { Text(label) })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(name.trim(), preset?.let { Quantity.inches(Rational.of(it, 72)).stored() })
                },
                enabled = name.isNotBlank(),
            ) { Text("Add zone") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun ReferenceDialog(
    zone: PlanZone,
    onDismiss: () -> Unit,
    onConfirm: (StoredQuantity, Boolean) -> Unit,
) {
    var length by remember { mutableStateOf<Quantity?>(null) }
    var check by rememberSaveable { mutableStateOf(zone.fit() != null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Known distance") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(zone.name)
                MeasureField(
                    label = "Real-world distance",
                    value = length,
                    onValueChange = { length = it },
                    modifier = Modifier.fillMaxWidth(),
                    isError = length?.value?.signum?.let { it <= 0 } == true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = check, onCheckedChange = { check = it }, enabled = zone.fit() != null)
                    Column {
                        Text("Check only")
                        Text(
                            if (check) "Compare with the fit." else "Use this reference to fit the zone.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { length?.let { onConfirm(it.stored(), check) } },
                enabled = length?.value?.signum?.let { it > 0 } == true,
            ) { Text("Save reference") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun TextMarkupDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Place text") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(2_000) },
                label = { Text("Text") },
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim()) }, enabled = value.isNotBlank()) { Text("Place text") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun PageItemsDialog(
    zone: PlanZone?,
    measurements: List<Pair<PlanMeasurement, Quantity?>>,
    marks: List<PlanMark>,
    editable: Boolean,
    publish: (PlanMeasurement, Quantity) -> Unit,
    onZoneChange: (PlanZone) -> Unit,
    onMeasureDelete: (String) -> Unit,
    onMarkDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val settings = rememberAppSettings()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Page details") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (zone != null) {
                    val fit = remember(zone) { zone.fit() }
                    Text(zone.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            fit == null -> "Add references in different directions for this correction."
                            zone.preset != null && zone.references.none { !it.check } -> "Preset scale"
                            zone.references.none { it.check } -> "Fitted · add an independent check"
                            else -> "Review check differences below"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val modes = mapOf(PlanScaleMode.UNIFORM to "Uniform", PlanScaleMode.AXES to "X/Y", PlanScaleMode.AFFINE to "Skew")
                        for ((mode, label) in modes) {
                            FilterChip(
                                selected = zone.mode == mode,
                                onClick = { onZoneChange(zone.copy(mode = mode)) },
                                enabled = editable,
                                label = { Text(label) },
                            )
                        }
                    }
                    zone.references.forEachIndexed { index, ref ->
                        val residual = fit?.residuals?.getOrNull(index)
                        ItemRow(
                            title = "${if (ref.check) "Check" else "Fit"} · ${settings.format(ref.length.toQuantity())}",
                            detail = residual?.let { delta ->
                                val difference = settings.format(Quantity.inches(Rational.of(abs(delta))))
                                val percent = 100 * delta / ref.length.toQuantity().value.toDouble()
                                "Difference ${if (delta < 0) "−" else "+"}$difference (${String.format(Locale.getDefault(), "%+.2f", percent)}%)"
                            },
                            onDelete = if (editable) ({
                                onZoneChange(zone.copy(references = zone.references.filterIndexed { i, _ -> i != index }))
                            }) else null,
                        )
                    }
                    HorizontalDivider()
                }
                Text("Measurements", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                if (measurements.isEmpty()) Text("No measurements on this page.")
                for ((measurement, quantity) in measurements) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { if (quantity != null) publish(measurement, quantity); onDismiss() },
                            enabled = quantity != null,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(quantity?.let { "${settings.format(it)} · Use" } ?: "Uncalibrated")
                        }
                        if (editable) TextButton(onClick = { onMeasureDelete(measurement.id) }) { Text("Delete") }
                    }
                }
                HorizontalDivider()
                Text("Markups", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                if (marks.isEmpty()) Text("No markups on this page.")
                marks.forEach { mark ->
                    ItemRow(
                        title = mark.kind.name.lowercase().replaceFirstChar { it.titlecase() },
                        detail = mark.text.takeIf { it.isNotBlank() },
                        onDelete = if (editable) ({ onMarkDelete(mark.id) }) else null,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun ItemRow(title: String, detail: String?, onDelete: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete") }
    }
}

@Composable
internal fun SymbolSettings() {
    val context = LocalContext.current
    val library = remember(context) { PlanSymbols(context.applicationContext) }
    var entries by remember(library) { mutableStateOf(library.list()) }
    var problem by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true
            scope.launch {
                try {
                    library.import(uri)
                    entries = library.list()
                    problem = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    problem = error.message ?: "Could not import this SVG."
                } finally {
                    importing = false
                }
            }
        }
    }
    SettingsGroup("SVG symbols") {
        TextButton(onClick = { picker.launch(arrayOf("image/svg+xml", "application/xml", "text/xml")) }, enabled = !importing) {
            Text(if (importing) "Importing SVG…" else "Import SVG")
        }
        if (entries.isEmpty()) Text("Imported symbols appear in the Symbol tool.", Modifier.padding(horizontal = 16.dp))
        problem?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
        entries.forEach { symbol ->
            Column(Modifier.padding(horizontal = 16.dp)) {
                ItemRow(symbol.name, null) {
                    library.remove(symbol.id)
                    entries = library.list()
                }
            }
        }
    }
}

@Composable
internal fun SymbolDialog(
    onDismiss: () -> Unit,
    openSettings: () -> Unit,
    onSelect: (PlanSymbol) -> Unit,
) {
    val context = LocalContext.current
    val entries = remember(context) { PlanSymbols(context.applicationContext).list() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Place an SVG symbol") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                if (entries.isEmpty()) Text("Import an SVG in Settings to place it here.")
                entries.forEach { symbol ->
                    TextButton(onClick = { onSelect(symbol) }, modifier = Modifier.fillMaxWidth()) { Text(symbol.name) }
                }
            }
        },
        confirmButton = { TextButton(onClick = openSettings) { Text("Symbol settings") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
