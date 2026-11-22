package com.thedirepack.constructioncalc.feature.drawings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.painterResource
import com.thedirepack.constructioncalc.core.data.PlanSymbol
import com.thedirepack.constructioncalc.core.data.PlanSymbols
import com.thedirepack.constructioncalc.core.data.SymbolLibraryMeta
import com.thedirepack.constructioncalc.core.model.PlanPath
import kotlinx.coroutines.delay
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.PathSegment
import com.thedirepack.constructioncalc.core.model.PlanCalibrationLayer
import com.thedirepack.constructioncalc.core.model.PlanMark
import com.thedirepack.constructioncalc.core.model.PlanMeasurement
import com.thedirepack.constructioncalc.core.model.PlanScaleMode
import com.thedirepack.constructioncalc.core.model.PlanZone
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.RunPath
import com.thedirepack.constructioncalc.core.model.StoredQuantity
import com.thedirepack.constructioncalc.core.model.stored
import com.thedirepack.constructioncalc.core.ui.components.MeasureField
import com.thedirepack.constructioncalc.core.ui.components.SettingsGroup
import com.thedirepack.constructioncalc.core.ui.format
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.feature.drawings.domain.CalibrationDiagnostics
import com.thedirepack.constructioncalc.feature.drawings.domain.CalibrationStatus
import com.thedirepack.constructioncalc.feature.drawings.domain.PlanRoute
import com.thedirepack.constructioncalc.feature.drawings.domain.RouteSummary
import com.thedirepack.constructioncalc.feature.drawings.domain.ScaleFamily
import com.thedirepack.constructioncalc.feature.drawings.domain.ScalePreset
import com.thedirepack.constructioncalc.feature.drawings.domain.ScalePresets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val scaleModeLabels = mapOf(
    PlanScaleMode.UNIFORM to "Uniform",
    PlanScaleMode.AXES to "X/Y",
    PlanScaleMode.AFFINE to "Skew",
    PlanScaleMode.DISTORTED to "2D Warp",
)

private fun presetWarning(pageSize: PageSize?): String? =
    ScalePresets.warning(pageSize?.width?.toDouble(), pageSize?.height?.toDouble())

/**
 * Preset scales, one family at a time so the list fits a phone: Architectural, Engineering or
 * Metric chips, then that family's scales. [selected] is the exact stored inches per point.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PresetPicker(
    selected: Rational?,
    enabled: Boolean,
    noneLabel: String,
    onSelect: (ScalePreset?) -> Unit,
) {
    val current = ScalePresets.matching(selected)
    var family by rememberSaveable { mutableStateOf(current?.family ?: ScaleFamily.ARCHITECTURAL) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = selected == null, onClick = { onSelect(null) }, enabled = enabled, label = { Text(noneLabel) })
            if (selected != null && current == null) {
                FilterChip(selected = true, onClick = {}, enabled = enabled, label = { Text("Custom") })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (option in ScaleFamily.entries) {
                FilterChip(
                    selected = family == option,
                    onClick = { family = option },
                    label = { Text(option.label) },
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (preset in ScalePresets.all.filter { it.family == family }) {
                FilterChip(
                    selected = current == preset,
                    onClick = { onSelect(preset) },
                    enabled = enabled,
                    label = { Text(preset.label) },
                )
            }
        }
    }
}

@Composable
private fun statusColor(status: CalibrationStatus): Color = when (status) {
    CalibrationStatus.VERIFIED -> MaterialTheme.colorScheme.primary
    CalibrationStatus.FITTED, CalibrationStatus.PRESET -> MaterialTheme.colorScheme.secondary
    CalibrationStatus.NEEDS_REVIEW -> MaterialTheme.colorScheme.error
    CalibrationStatus.UNCALIBRATED -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
internal fun RenameDialog(
    title: String,
    label: String,
    initialName: String,
    maxLength: Int = 240,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(maxLength) },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun ConfirmDeleteDialog(
    title: String,
    message: String,
    confirmLabel: String = "Delete",
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun ZoneDialog(
    defaultName: String,
    pageSize: PageSize?,
    onDismiss: () -> Unit,
    onConfirm: (String, StoredQuantity?) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(defaultName) }
    var preset by remember { mutableStateOf<ScalePreset?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New override scale zone") },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Overrides the document scale inside this region and is left out of the document-wide fit.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80) },
                    label = { Text("Zone name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                PresetPicker(
                    selected = preset?.inchesPerPoint,
                    enabled = true,
                    noneLabel = "Calibrate from known dimensions",
                    onSelect = { preset = it },
                )
                if (preset != null) {
                    presetWarning(pageSize)?.let { warning ->
                        Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(name.trim(), preset?.stored())
                },
                enabled = name.isNotBlank(),
            ) { Text("Add zone") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun ReferenceDialog(
    targetName: String,
    hasFit: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (StoredQuantity, Boolean) -> Unit,
) {
    var length by remember { mutableStateOf<Quantity?>(null) }
    var check by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Known dimension") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(targetName)
                MeasureField(
                    label = "Real-world distance",
                    value = length,
                    onValueChange = { length = it },
                    modifier = Modifier.fillMaxWidth(),
                    isError = length?.value?.signum?.let { it <= 0 } == true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = check, onCheckedChange = { check = it }, enabled = hasFit)
                    Column {
                        Text("Check only")
                        Text(
                            if (check) "Compare with the fit without changing it." else "Use this dimension to fit the calibration.",
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
            ) { Text("Save dimension") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun CalibrationDialog(
    calibration: PlanCalibrationLayer,
    page: Int,
    pageSize: PageSize?,
    pageZones: List<PlanZone>,
    editable: Boolean,
    onCalibrationChange: (PlanCalibrationLayer) -> Unit,
    onZoneChange: (PlanZone) -> Unit,
    onZoneDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val settings = rememberAppSettings()
    val summary = remember(calibration, page) { CalibrationDiagnostics.evaluate(calibration, page) }
    val activePageMode = calibration.modeFor(page)
    val hasPageOverride = calibration.pageModes.containsKey(page)
    var renamingZone by remember { mutableStateOf<PlanZone?>(null) }
    var deletingZone by remember { mutableStateOf<PlanZone?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Calibration layer") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Document scale (all pages)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        summary.status.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = statusColor(summary.status),
                    )
                }
                Text(
                    summary.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (summary.status == CalibrationStatus.NEEDS_REVIEW) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Document preset", style = MaterialTheme.typography.labelLarge)
                PresetPicker(
                    selected = calibration.preset?.toQuantity()?.value,
                    enabled = editable,
                    noneLabel = "None",
                    onSelect = { onCalibrationChange(calibration.copy(preset = it?.stored())) },
                )
                if (calibration.preset != null) {
                    presetWarning(pageSize)?.let { warning ->
                        Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
                Text("Document correction", style = MaterialTheme.typography.labelLarge)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for ((mode, label) in scaleModeLabels) {
                        val supported = mode in summary.supportedModes
                        FilterChip(
                            selected = calibration.mode == mode,
                            onClick = { onCalibrationChange(calibration.copy(mode = mode)) },
                            enabled = editable,
                            label = { Text(if (supported) label else "$label ·") },
                        )
                    }
                }
                Text("Page ${page + 1} distortion override", style = MaterialTheme.typography.labelLarge)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = !hasPageOverride,
                        onClick = { onCalibrationChange(calibration.copy(pageModes = calibration.pageModes - page)) },
                        enabled = editable,
                        label = { Text("Follow document") },
                    )
                    for ((mode, label) in scaleModeLabels) {
                        FilterChip(
                            selected = hasPageOverride && activePageMode == mode,
                            onClick = { onCalibrationChange(calibration.copy(pageModes = calibration.pageModes + (page to mode))) },
                            enabled = editable,
                            label = { Text(label) },
                        )
                    }
                }
                HorizontalDivider()
                Text("Document dimensions (${calibration.references.size})", style = MaterialTheme.typography.titleMedium)
                if (calibration.references.isEmpty()) {
                    Text("No known dimensions recorded yet.", style = MaterialTheme.typography.bodySmall)
                }
                summary.residuals.forEach { entry ->
                    val ref = entry.reference
                    val detail = entry.deltaInches?.let { delta ->
                        val difference = settings.format(Quantity.inches(Rational.of(abs(delta))))
                        val percent = entry.percentError ?: 0.0
                        val flag = if (entry.exceedsTolerance) " · Review" else ""
                        "Diff ${if (delta < 0) "−" else "+"}$difference (${String.format(Locale.getDefault(), "%+.2f", percent)}%)$flag"
                    }
                    ItemRow(
                        title = "P${ref.page + 1} · ${if (ref.check) "Check" else "Fit"} · ${settings.format(ref.length.toQuantity())}",
                        detail = detail,
                        detailIsError = entry.exceedsTolerance,
                        onDelete = if (editable) ({
                            onCalibrationChange(calibration.copy(references = calibration.references.filterIndexed { i, _ -> i != entry.index }))
                        }) else null,
                    )
                }
                if (pageZones.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Override zones on Page ${page + 1}", style = MaterialTheme.typography.titleMedium)
                    for (zone in pageZones) {
                        val zoneSummary = remember(zone) { CalibrationDiagnostics.evaluateZone(zone) }
                        Row(
                            Modifier.fillMaxWidth().padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(zone.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${zoneSummary.status.label} · ${zone.references.size} dim(s)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = statusColor(zoneSummary.status),
                                )
                            }
                            if (editable) {
                                TextButton(onClick = { renamingZone = zone }) { Text("Rename") }
                                TextButton(onClick = { deletingZone = zone }) { Text("Delete") }
                            }
                        }
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            for ((mode, label) in scaleModeLabels) {
                                val supported = mode in zoneSummary.supportedModes
                                FilterChip(
                                    selected = zone.mode == mode,
                                    onClick = { onZoneChange(zone.copy(mode = mode)) },
                                    enabled = editable,
                                    label = { Text(if (supported) label else "$label ·") },
                                )
                            }
                        }
                        zoneSummary.residuals.forEach { entry ->
                            val ref = entry.reference
                            val detail = entry.deltaInches?.let { delta ->
                                val difference = settings.format(Quantity.inches(Rational.of(abs(delta))))
                                val percent = entry.percentError ?: 0.0
                                "Diff ${if (delta < 0) "−" else "+"}$difference (${String.format(Locale.getDefault(), "%+.2f", percent)}%)"
                            }
                            ItemRow(
                                title = "${if (ref.check) "Check" else "Fit"} · ${settings.format(ref.length.toQuantity())}",
                                detail = detail,
                                detailIsError = entry.exceedsTolerance,
                                onDelete = if (editable) ({
                                    onZoneChange(zone.copy(references = zone.references.filterIndexed { i, _ -> i != entry.index }))
                                }) else null,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )

    renamingZone?.let { target ->
        RenameDialog(
            title = "Rename scale zone",
            label = "Zone name",
            initialName = target.name,
            maxLength = 80,
            onDismiss = { renamingZone = null },
            onConfirm = { updated ->
                onZoneChange(target.copy(name = updated))
                renamingZone = null
            },
        )
    }
    deletingZone?.let { target ->
        ConfirmDeleteDialog(
            title = "Delete scale zone?",
            message = "Remove “${target.name}” and its ${target.references.size} reference(s)? Measurements inside it will fall back to the document scale.",
            onDismiss = { deletingZone = null },
            onConfirm = {
                onZoneDelete(target.id)
                deletingZone = null
            },
        )
    }
}

@Composable
internal fun CircuitTagDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var tag by rememberSaveable(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Circuit tag") },
        text = {
            OutlinedTextField(tag, { tag = it.take(80) }, singleLine = true, label = { Text("Circuit") })
        },
        confirmButton = { TextButton(onClick = { onSave(tag.trim().uppercase().take(80)) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun TextMarkupDialog(
    onDismiss: () -> Unit,
    initial: String = "",
    title: String = if (initial.isBlank()) "Place text" else "Edit text",
    label: String = "Text",
    allowEmpty: Boolean = false,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(2_000) },
                label = { Text(label) },
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim()) }, enabled = allowEmpty || value.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun PageItemsDialog(
    zone: PlanZone?,
    measurements: List<Pair<PlanMeasurement, Quantity?>>,
    paths: List<CanvasPath> = emptyList(),
    marks: List<PlanMark>,
    editable: Boolean,
    publish: (PlanMeasurement, Quantity) -> Unit,
    onZoneChange: (PlanZone) -> Unit,
    onZoneDelete: (String) -> Unit,
    onMeasureEdit: (String) -> Unit,
    onMeasureDelete: (String) -> Unit,
    onPathDelete: (String) -> Unit = {},
    onMarkDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val settings = rememberAppSettings()
    var renamingZone by remember { mutableStateOf<PlanZone?>(null) }
    var deletingZone by remember { mutableStateOf<PlanZone?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Page details") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (zone != null) {
                    val zoneSummary = remember(zone) { CalibrationDiagnostics.evaluateZone(zone) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(zone.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${zoneSummary.status.label} · ${zoneSummary.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = statusColor(zoneSummary.status),
                            )
                        }
                        if (editable) {
                            TextButton(onClick = { renamingZone = zone }) { Text("Rename") }
                            TextButton(onClick = { deletingZone = zone }) { Text("Delete") }
                        }
                    }
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for ((mode, label) in scaleModeLabels) {
                            val supported = mode in zoneSummary.supportedModes
                            FilterChip(
                                selected = zone.mode == mode,
                                onClick = { onZoneChange(zone.copy(mode = mode)) },
                                enabled = editable,
                                label = { Text(if (supported) label else "$label ·") },
                            )
                        }
                    }
                    zoneSummary.residuals.forEach { entry ->
                        val ref = entry.reference
                        val detail = entry.deltaInches?.let { delta ->
                            val difference = settings.format(Quantity.inches(Rational.of(abs(delta))))
                            val percent = entry.percentError ?: 0.0
                            "Difference ${if (delta < 0) "−" else "+"}$difference (${String.format(Locale.getDefault(), "%+.2f", percent)}%)"
                        }
                        ItemRow(
                            title = "${if (ref.check) "Check" else "Fit"} · ${settings.format(ref.length.toQuantity())}",
                            detail = detail,
                            detailIsError = entry.exceedsTolerance,
                            onDelete = if (editable) ({
                                onZoneChange(zone.copy(references = zone.references.filterIndexed { i, _ -> i != entry.index }))
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
                            val value = quantity?.let { "≈ ${settings.format(it)} · Use" } ?: "Uncalibrated"
                            Text(if (measurement.label.isBlank()) value else "${measurement.label}: $value")
                        }
                        if (editable) {
                            TextButton(onClick = { onMeasureEdit(measurement.id) }) { Text("Edit") }
                            TextButton(onClick = { onMeasureDelete(measurement.id) }) { Text("Delete") }
                        }
                    }
                }
                if (paths.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Saved paths & areas", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    paths.forEach { item ->
                        ItemRow(
                            title = if (item.path.closed) "Area (${item.path.points.size} pts)" else "Polyline (${item.path.points.size} pts)",
                            detail = item.label,
                            onDelete = if (editable) ({ onPathDelete(item.path.id) }) else null,
                        )
                    }
                }
                HorizontalDivider()
                Text("Markups", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                if (marks.isEmpty()) Text("No markups on this page.")
                marks.forEach { mark ->
                    val name = mark.kind.name.lowercase().replace('_', ' ').replaceFirstChar { it.titlecase() }
                    ItemRow(
                        title = name,
                        detail = mark.text.takeIf { it.isNotBlank() },
                        onDelete = if (editable) ({ onMarkDelete(mark.id) }) else null,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )

    renamingZone?.let { target ->
        RenameDialog(
            title = "Rename scale zone",
            label = "Zone name",
            initialName = target.name,
            maxLength = 80,
            onDismiss = { renamingZone = null },
            onConfirm = { updated ->
                onZoneChange(target.copy(name = updated))
                renamingZone = null
            },
        )
    }
    deletingZone?.let { target ->
        ConfirmDeleteDialog(
            title = "Delete scale zone?",
            message = "Remove “${target.name}” and its ${target.references.size} reference(s)?",
            onDismiss = { deletingZone = null },
            onConfirm = {
                onZoneDelete(target.id)
                deletingZone = null
            },
        )
    }
}

@Composable
internal fun SendRouteToRunDialog(
    project: Project,
    summary: RouteSummary,
    onDismiss: () -> Unit,
    onSend: (runId: String?, newRunName: String?, segments: List<PathSegment>) -> Unit,
) {
    val settings = rememberAppSettings()
    var selectedRunId by rememberSaveable(project.id) { mutableStateOf(project.runs.firstOrNull()?.id) }
    var createNewRun by rememberSaveable(project.id) { mutableStateOf(project.runs.isEmpty()) }
    var newRunName by rememberSaveable { mutableStateOf("Plan route") }
    var startVertical by remember { mutableStateOf<Quantity?>(null) }
    var startUp by rememberSaveable { mutableStateOf(true) }
    var endVertical by remember { mutableStateOf<Quantity?>(null) }
    var endUp by rememberSaveable { mutableStateOf(false) }

    val startInches = startVertical?.value?.toDouble()?.coerceAtLeast(0.0) ?: 0.0
    val endInches = endVertical?.value?.toDouble()?.coerceAtLeast(0.0) ?: 0.0
    val segments = remember(summary, startInches, startUp, endInches, endUp) {
        PlanRoute.toRunPath(
            summary = summary,
            startVerticalInches = startInches,
            startVerticalUp = startUp,
            endVerticalInches = endInches,
            endVerticalUp = endUp,
        )
    }
    val totalLength = remember(summary, startInches, endInches) {
        Quantity.inches(Rational.of(summary.totalHorizontalInches + startInches + endInches))
    }
    val totalDegrees = remember(segments) { RunPath.totalDegrees(segments) }
    val bendCount = remember(segments) { segments.count { it is PathSegment.Bend } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send route to Run") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Plan horizontal: ≈ ${settings.format(summary.totalHorizontalQuantity)} (${summary.legs.size} legs, ${summary.corners.size} corners)",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Total route: ≈ ${settings.format(totalLength)} · $bendCount bend(s) (${totalDegrees.roundToInt()}°)",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (totalDegrees > 360.0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
                if (totalDegrees > 360.0) {
                    Text(
                        "Exceeds 360° between pull points — add a pull point in Wire Pull Planner.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                HorizontalDivider()
                Text("Optional vertical drops / rises", style = MaterialTheme.typography.labelLarge)
                MeasureField(
                    label = "Start vertical (optional)",
                    value = startVertical,
                    onValueChange = { startVertical = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (startInches > 0.0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = startUp, onClick = { startUp = true }, label = { Text("Rise up") })
                        FilterChip(selected = !startUp, onClick = { startUp = false }, label = { Text("Drop down") })
                    }
                }
                MeasureField(
                    label = "End vertical (optional)",
                    value = endVertical,
                    onValueChange = { endVertical = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (endInches > 0.0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = endUp, onClick = { endUp = true }, label = { Text("Rise up") })
                        FilterChip(selected = !endUp, onClick = { endUp = false }, label = { Text("Drop down") })
                    }
                }
                HorizontalDivider()
                Text("Target run in ${project.name}", style = MaterialTheme.typography.labelLarge)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (run in project.runs) {
                        FilterChip(
                            selected = !createNewRun && selectedRunId == run.id,
                            onClick = {
                                createNewRun = false
                                selectedRunId = run.id
                            },
                            label = { Text(run.name) },
                        )
                    }
                    FilterChip(
                        selected = createNewRun,
                        onClick = { createNewRun = true },
                        label = { Text("+ New run") },
                    )
                }
                if (createNewRun) {
                    OutlinedTextField(
                        value = newRunName,
                        onValueChange = { newRunName = it.take(120) },
                        label = { Text("New run name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            val canSend = if (createNewRun) newRunName.isNotBlank() else selectedRunId != null
            TextButton(
                onClick = {
                    if (createNewRun) onSend(null, newRunName.trim(), segments)
                    else onSend(selectedRunId, null, segments)
                },
                enabled = canSend,
            ) { Text("Send to Run") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun ItemRow(
    title: String,
    detail: String?,
    detailIsError: Boolean = false,
    onDelete: (() -> Unit)?,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (detailIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete") }
    }
}

@Composable
internal fun SymbolSettings() {
    val context = LocalContext.current
    val library = remember(context) { PlanSymbols(context.applicationContext) }
    var entries by remember(library) { mutableStateOf(emptyList<PlanSymbol>()) }
    var problem by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(library) {
        entries = withContext(Dispatchers.IO) { library.list() }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true
            scope.launch {
                try {
                    library.import(uri)
                    entries = withContext(Dispatchers.IO) { library.list() }
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
                    scope.launch {
                        withContext(Dispatchers.IO) { library.remove(symbol.id) }
                        entries = withContext(Dispatchers.IO) { library.list() }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SymbolDialog(
    onDismiss: () -> Unit,
    openSettings: () -> Unit,
    onSelect: (PlanSymbol) -> Unit,
) {
    val context = LocalContext.current
    val library = remember(context) { PlanSymbols(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var reloadKey by remember { mutableStateOf(0) }
    val loaded by produceState(starterSymbols to SymbolLibraryMeta(), context, reloadKey) {
        val (imported, meta) = withContext(Dispatchers.IO) { library.list() to library.readMeta() }
        value = (starterSymbols + imported) to meta
    }
    val allSymbols = loaded.first
    var meta by remember(loaded) { mutableStateOf(loaded.second) }
    var filter by rememberSaveable { mutableStateOf("All") }
    var creatingSet by remember { mutableStateOf(false) }
    var assigningSymbol by remember { mutableStateOf<PlanSymbol?>(null) }

    fun saveMeta(updated: SymbolLibraryMeta) {
        meta = updated
        scope.launch(Dispatchers.IO) {
            runCatching { library.writeMeta(updated) }
        }
    }

    val byId = remember(allSymbols) { allSymbols.associateBy { it.id } }
    val visibleSymbols = remember(allSymbols, meta, filter) {
        when (filter) {
            "All" -> allSymbols
            "Favorites" -> allSymbols.filter { it.id in meta.favorites }
            "Recent" -> meta.recents.mapNotNull(byId::get)
            else -> {
                val ids = (builtInSymbolSets[filter].orEmpty() + meta.sets[filter].orEmpty()).toSet()
                allSymbols.filter { it.id in ids }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a count symbol") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val filters = buildList {
                        add("All")
                        add("Favorites")
                        add("Recent")
                        addAll(builtInSymbolSets.keys)
                        meta.sets.keys.sorted().forEach { if (it !in this) add(it) }
                    }
                    for (option in filters) {
                        FilterChip(
                            selected = filter == option,
                            onClick = { filter = option },
                            label = { Text(option) },
                        )
                    }
                    TextButton(onClick = { creatingSet = true }) { Text("+ Set") }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    if (visibleSymbols.isEmpty()) {
                        Text(
                            when (filter) {
                                "Favorites" -> "Star symbols to add them to Favorites."
                                "Recent" -> "Picked symbols will appear here."
                                else -> "No symbols in this set yet."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                    visibleSymbols.forEach { symbol ->
                        val fav = symbol.id in meta.favorites
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    val updatedRecents = (listOf(symbol.id) + meta.recents.filterNot { it == symbol.id }).take(24)
                                    saveMeta(meta.copy(recents = updatedRecents))
                                    onSelect(symbol)
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                SymbolPreview(symbol)
                                Text(symbol.name, Modifier.weight(1f).padding(start = 8.dp))
                            }
                            if (meta.sets.isNotEmpty() || assigningSymbol != null) {
                                TextButton(onClick = { assigningSymbol = symbol }) { Text("Set") }
                            }
                            IconButton(onClick = {
                                val nextFavs = if (fav) meta.favorites - symbol.id else meta.favorites + symbol.id
                                saveMeta(meta.copy(favorites = nextFavs))
                            }) {
                                Icon(
                                    painterResource(if (fav) R.drawable.ic_plan_star else R.drawable.ic_plan_star_outline),
                                    contentDescription = if (fav) "Remove ${symbol.name} from favorites" else "Favorite ${symbol.name}",
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if (meta.sets.isEmpty()) {
                    TextButton(onClick = { creatingSet = true }) { Text("New set") }
                }
                TextButton(onClick = { openSettings(); reloadKey++ }) { Text("Import SVG") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (creatingSet) {
        RenameDialog(
            title = "New symbol set",
            label = "Set name",
            initialName = "",
            maxLength = 80,
            onDismiss = { creatingSet = false },
            onConfirm = { name ->
                val clean = name.trim()
                if (clean.isNotBlank() && clean !in listOf("All", "Favorites", "Recent")) {
                    saveMeta(meta.copy(sets = meta.sets + (clean to meta.sets[clean].orEmpty())))
                    filter = clean
                }
                creatingSet = false
            },
        )
    }

    assigningSymbol?.let { target ->
        AlertDialog(
            onDismissRequest = { assigningSymbol = null },
            title = { Text("Sets for “${target.name}”") },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    if (meta.sets.isEmpty()) {
                        Text("Create a custom set first.")
                    } else {
                        meta.sets.keys.sorted().forEach { setName ->
                            val member = target.id in meta.sets[setName].orEmpty()
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = member,
                                    onCheckedChange = { checked ->
                                        val existing = meta.sets[setName].orEmpty()
                                        val updated = if (checked) (existing + target.id).distinct() else existing.filterNot { it == target.id }
                                        saveMeta(meta.copy(sets = meta.sets + (setName to updated)))
                                    },
                                )
                                Text(setName, Modifier.weight(1f))
                                TextButton(onClick = {
                                    saveMeta(meta.copy(sets = meta.sets - setName))
                                    if (filter == setName) filter = "All"
                                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { assigningSymbol = null }) { Text("Done") } },
        )
    }
}
