package com.thedirepack.constructioncalc.feature.drawings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.PlanDrawing
import com.thedirepack.constructioncalc.core.model.PlanMeasurement
import com.thedirepack.constructioncalc.core.model.PlanRunRoute
import com.thedirepack.constructioncalc.core.model.sheetSource
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.ui.format
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.feature.drawings.domain.PlanTakeoff

/** Totals for the whole drawing: lengths by page and label, symbol counts, and routes sent to runs. */
@Composable
internal fun TakeoffDialog(
    project: Project,
    drawing: PlanDrawing,
    onShowRoute: (PlanRunRoute) -> Unit,
    onDismiss: () -> Unit,
) {
    val settings = rememberAppSettings()
    val takeoff = remember(drawing) { PlanTakeoff.summarize(drawing) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Takeoff totals") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (takeoff.sources.isNotEmpty()) {
                    Text(drawing.name, style = MaterialTheme.typography.titleSmall)
                    Text(takeoff.sources.joinToString { it.label }, style = MaterialTheme.typography.bodySmall)
                }
                if (takeoff.isEmpty) Text("Nothing measured, counted or routed on this drawing yet.")
                if (takeoff.measuredCount > 0) {
                    Section("Measured lengths")
                    TotalRow("All pages · ${takeoff.measuredCount}", "≈ ${settings.format(takeoff.measuredTotal)}")
                    takeoff.byPage.forEach { (page, group) ->
                        val source = drawing.sheetSource(page)
                        TotalRow("${source.label} · ${group.count}", "≈ ${settings.format(group.total)}")
                    }
                }
                if (takeoff.uncalibratedCount > 0) {
                    Text(
                        "${takeoff.uncalibratedCount} measurement(s) have no scale and aren't in the totals.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (takeoff.pathPolylineCount > 0 || takeoff.pathPolygonCount > 0 || takeoff.uncalibratedPathCount > 0 || takeoff.multiScalePathCount > 0) {
                    Section("Saved paths & areas")
                    if (takeoff.pathPolylineCount > 0) {
                        TotalRow("Polylines · ${takeoff.pathPolylineCount}", "≈ ${settings.format(takeoff.pathPolylineTotal)}")
                    }
                    if (takeoff.pathPolygonCount > 0) {
                        TotalRow("Areas · ${takeoff.pathPolygonCount}", "≈ ${settings.format(takeoff.pathPolygonArea)} (perim. ${settings.format(takeoff.pathPolygonPerimeter)})")
                    }
                    if (takeoff.multiScalePathCount > 0) {
                        Text(
                            "${takeoff.multiScalePathCount} saved path(s) span multiple scale zones and aren't totaled.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (takeoff.uncalibratedPathCount > 0) {
                        Text(
                            "${takeoff.uncalibratedPathCount} saved path(s) have no scale and aren't totaled.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (takeoff.byLabel.isNotEmpty()) {
                    Section("By label")
                    takeoff.byLabel.forEach { TotalRow("${it.name} · ${it.count}", "≈ ${settings.format(it.total)}") }
                }
                if (takeoff.symbols.isNotEmpty() || takeoff.textNotes > 0) {
                    Section("Counts")
                    takeoff.symbols.forEach { TotalRow(it.name, it.count.toString()) }
                    if (takeoff.textNotes > 0) TotalRow("Text notes", takeoff.textNotes.toString())
                }
                if (takeoff.symbolsByArea.isNotEmpty()) {
                    Section("Counts by area")
                    takeoff.symbolsByArea.forEach { TotalRow("${it.area} · ${it.name}", it.count.toString()) }
                }
                if (takeoff.byCircuitCombined.isNotEmpty()) {
                    Section("By circuit (devices & routes)")
                    takeoff.byCircuitCombined.forEach { group ->
                        Text(group.circuit, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                        group.symbols.forEach { TotalRow("  ${it.name}", it.count.toString()) }
                        group.routes.forEach { entry ->
                            val runName = project.drawingRunName(entry.route.runId)
                            val lengthText = entry.current?.let { "≈ ${settings.format(it)}" } ?: "Uncalibrated"
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("  Route · $runName ($lengthText)", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = { onShowRoute(entry.route) }) { Text("Show") }
                            }
                        }
                    }
                } else if (takeoff.symbolsByCircuit.isNotEmpty()) {
                    Section("Counts by circuit")
                    takeoff.symbolsByCircuit.forEach { TotalRow("${it.circuit} · ${it.name}", it.count.toString()) }
                }
                if (takeoff.routes.isNotEmpty()) {
                    Section("Routes sent to runs")
                    takeoff.routes.forEach { entry ->
                        val runName = project.drawingRunName(entry.route.runId)
                        val source = drawing.sheetSource(entry.route.page)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(listOf(runName, entry.route.circuitTag, source.label).filter(String::isNotBlank).joinToString(" · "))
                                Text(
                                    when {
                                        entry.current == null -> "Can't be measured now: the scale was removed."
                                        entry.changed -> "Plan now ≈ ${settings.format(entry.current)}, sent ≈ ${settings.format(entry.sent)}. " +
                                            "Trace and send it again to update the run."
                                        else -> "≈ ${settings.format(entry.current)} horizontal"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (entry.changed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = {
                                onShowRoute(entry.route)
                            }) { Text("Show") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 4.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun TotalRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/** One measurement: its label, its length to reuse, and delete. */
@Composable
internal fun MeasurementDialog(
    measurement: PlanMeasurement,
    length: Quantity?,
    editable: Boolean,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onUse: (Quantity) -> Unit,
) {
    val settings = rememberAppSettings()
    var label by rememberSaveable(measurement.id) { mutableStateOf(measurement.label) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Measurement") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(length?.let { "≈ ${settings.format(it)}" } ?: "Uncalibrated", style = MaterialTheme.typography.titleMedium)
                if (editable) {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it.take(120) },
                        label = { Text("Label (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Measurements with the same label are totaled together.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (measurement.label.isNotBlank()) {
                    Text(measurement.label)
                }
                Row {
                    if (length != null) TextButton(onClick = { onUse(length) }) { Text("Use length") }
                    if (editable) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            if (editable) TextButton(onClick = { onRename(label.trim()) }) { Text("Save") }
            else TextButton(onClick = onDismiss) { Text("Done") }
        },
        dismissButton = { if (editable) TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
