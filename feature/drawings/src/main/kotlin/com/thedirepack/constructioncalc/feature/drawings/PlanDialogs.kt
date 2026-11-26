package com.thedirepack.constructioncalc.feature.drawings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.painterResource
import com.thedirepack.constructioncalc.core.data.PlanSymbol
import com.thedirepack.constructioncalc.core.data.PlanSymbols
import com.thedirepack.constructioncalc.core.data.SymbolLibraryMeta
import com.thedirepack.constructioncalc.core.model.PlanDrawing
import com.thedirepack.constructioncalc.core.model.PlanMarkKind
import com.thedirepack.constructioncalc.core.model.PlanPath
import com.thedirepack.constructioncalc.core.model.PlanReference
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
    initialLength: Quantity? = null,
    initialCheck: Boolean = false,
    title: String = "Known dimension",
    confirmLabel: String = "Save dimension",
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onConfirm: (StoredQuantity, Boolean) -> Unit,
) {
    var length by remember(initialLength) { mutableStateOf(initialLength) }
    var check by rememberSaveable(initialCheck) { mutableStateOf(initialCheck) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
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
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("Remove dimension", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { length?.let { onConfirm(it.stored(), check) } },
                enabled = length?.value?.signum?.let { it > 0 } == true,
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CalibrationDialog(
    calibration: PlanCalibrationLayer,
    page: Int,
    pageSize: PageSize?,
    pageZones: List<PlanZone>,
    editable: Boolean,
    scaleLocked: Boolean = false,
    onToggleScaleLock: () -> Unit = {},
    onCalibrationChange: (PlanCalibrationLayer) -> Unit,
    onZoneChange: (PlanZone) -> Unit,
    onZoneDelete: (String) -> Unit,
    onCalibrateFromLine: () -> Unit = {},
    onDrawScaleZone: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val settings = rememberAppSettings()
    val summary = remember(calibration, page) { CalibrationDiagnostics.evaluate(calibration, page) }
    val activePageMode = calibration.modeFor(page)
    val hasPageOverride = calibration.pageModes.containsKey(page)
    val canEditScale = editable && !scaleLocked
    var renamingZone by remember { mutableStateOf<PlanZone?>(null) }
    var deletingZone by remember { mutableStateOf<PlanZone?>(null) }
    var editingDocIndex by remember { mutableStateOf<Int?>(null) }
    var editingZoneRef by remember { mutableStateOf<Pair<PlanZone, Int>?>(null) }

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
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = scaleLocked,
                        onClick = onToggleScaleLock,
                        enabled = editable,
                        leadingIcon = {
                            Icon(
                                painterResource(if (scaleLocked) R.drawable.ic_plan_lock else R.drawable.ic_plan_lock_open),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        label = { Text(if (scaleLocked) "Scale locked" else "Lock scale") },
                    )
                }
                if (scaleLocked) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            EditorActions.SCALE_LOCKED_MESSAGE,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
                Text(
                    summary.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (summary.status == CalibrationStatus.NEEDS_REVIEW) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (editable) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilledTonalButton(onClick = onCalibrateFromLine, enabled = canEditScale) {
                            Icon(painterResource(R.drawable.ic_plan_tool_calibrate), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Measure known dimensions")
                        }
                        OutlinedButton(onClick = onDrawScaleZone, enabled = canEditScale) {
                            Icon(painterResource(R.drawable.ic_plan_tool_zone), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add scale zone")
                        }
                    }
                    Text(
                        "Pick a preset scale below, or draw known dimensions on the sheet, fine-tune their endpoints, and submit.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("Document preset", style = MaterialTheme.typography.labelLarge)
                PresetPicker(
                    selected = calibration.preset?.toQuantity()?.value,
                    enabled = canEditScale,
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
                            enabled = canEditScale,
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
                        enabled = canEditScale,
                        label = { Text("Follow document") },
                    )
                    for ((mode, label) in scaleModeLabels) {
                        FilterChip(
                            selected = hasPageOverride && activePageMode == mode,
                            onClick = { onCalibrationChange(calibration.copy(pageModes = calibration.pageModes + (page to mode))) },
                            enabled = canEditScale,
                            label = { Text(label) },
                        )
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Document dimensions (${calibration.references.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (editable) {
                        TextButton(onClick = onCalibrateFromLine, enabled = canEditScale) { Text("+ Measure") }
                    }
                }
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
                        actionLabel = if (canEditScale) "Edit" else null,
                        onAction = if (canEditScale) ({ editingDocIndex = entry.index }) else null,
                        onDelete = if (canEditScale) ({
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
                            if (canEditScale) {
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
                                    enabled = canEditScale,
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
                                actionLabel = if (canEditScale) "Edit" else null,
                                onAction = if (canEditScale) ({ editingZoneRef = zone to entry.index }) else null,
                                onDelete = if (canEditScale) ({
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

    editingDocIndex?.let { index ->
        val current = calibration.references.getOrNull(index)
        if (current != null) {
            ReferenceDialog(
                targetName = "Document calibration (page ${current.page + 1})",
                hasFit = calibration.references.indices.any { it != index && !calibration.references[it].check } || calibration.preset != null,
                initialLength = current.length.toQuantity(),
                initialCheck = current.check,
                title = "Edit known dimension",
                confirmLabel = "Update",
                onDelete = {
                    onCalibrationChange(calibration.copy(references = calibration.references.filterIndexed { i, _ -> i != index }))
                    editingDocIndex = null
                },
                onDismiss = { editingDocIndex = null },
                onConfirm = { length, check ->
                    val updated = current.copy(length = length, check = check)
                    onCalibrationChange(calibration.copy(references = calibration.references.mapIndexed { i, r -> if (i == index) updated else r }))
                    editingDocIndex = null
                },
            )
        }
    }

    editingZoneRef?.let { (zone, index) ->
        val current = zone.references.getOrNull(index)
        if (current != null) {
            ReferenceDialog(
                targetName = zone.name,
                hasFit = zone.references.indices.any { it != index && !zone.references[it].check } || zone.preset != null,
                initialLength = current.length.toQuantity(),
                initialCheck = current.check,
                title = "Edit zone dimension",
                confirmLabel = "Update",
                onDelete = {
                    onZoneChange(zone.copy(references = zone.references.filterIndexed { i, _ -> i != index }))
                    editingZoneRef = null
                },
                onDismiss = { editingZoneRef = null },
                onConfirm = { length, check ->
                    val updated = current.copy(length = length, check = check)
                    onZoneChange(zone.copy(references = zone.references.mapIndexed { i, r -> if (i == index) updated else r }))
                    editingZoneRef = null
                },
            )
        }
    }

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
internal fun StagedCalibrationListDialog(
    previewDrawing: PlanDrawing,
    draft: List<StagedCalibrationRef>,
    page: Int,
    editable: Boolean,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val settings = rememberAppSettings()
    val docSummary = remember(previewDrawing.calibration, page) {
        CalibrationDiagnostics.evaluate(previewDrawing.calibration, page)
    }
    val docItems = remember(draft) { draft.filter { it.zoneId == null } }
    val zoneSummaries = remember(previewDrawing.zones) {
        previewDrawing.zones.associateWith { CalibrationDiagnostics.evaluateZone(it) }.mapKeys { it.key.id }
    }
    val zoneItemsByZone = remember(draft) {
        draft.filter { it.zoneId != null }.groupBy { it.zoneId }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Staged calibration (${draft.size})") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Preview status", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(docSummary.status.label, style = MaterialTheme.typography.labelLarge, color = statusColor(docSummary.status))
                }
                Text(
                    "Adjust exact endpoint handles directly on the plan, or edit entered distances below before submitting.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (draft.isEmpty()) {
                    Text("No calibration dimensions staged yet.", style = MaterialTheme.typography.bodySmall)
                } else {
                    draft.forEachIndexed { idx, item ->
                        val ref = item.ref
                        val zone = item.zoneId?.let { id -> previewDrawing.zones.firstOrNull { it.id == id } }
                        val residual = if (item.zoneId == null) {
                            val docIdx = docItems.indexOfFirst { it.id == item.id }
                            docSummary.residuals.getOrNull(docIdx)
                        } else {
                            val zList = zoneItemsByZone[item.zoneId].orEmpty()
                            val zIdx = zList.indexOfFirst { it.id == item.id }
                            zoneSummaries[item.zoneId]?.residuals?.getOrNull(zIdx)
                        }
                        val diffDetail = residual?.deltaInches?.let { delta ->
                            val diff = settings.format(Quantity.inches(Rational.of(abs(delta))))
                            val pct = residual.percentError ?: 0.0
                            "Diff ${if (delta < 0) "−" else "+"}$diff (${String.format(Locale.getDefault(), "%+.2f", pct)}%)"
                        }
                        val scope = zone?.name ?: "Doc P${ref.page + 1}"
                        ItemRow(
                            title = "#${idx + 1} · $scope · ${if (ref.check) "Check" else "Fit"} · ${settings.format(ref.length.toQuantity())}",
                            detail = diffDetail,
                            detailIsError = residual?.exceedsTolerance == true,
                            actionLabel = if (editable) "Edit" else null,
                            onAction = if (editable) ({ onEdit(item.id) }) else null,
                            onDelete = if (editable) ({ onDelete(item.id) }) else null,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = editable) { Text("Submit") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Back") }
        },
    )
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
    helperText: String? = null,
    allowEmpty: Boolean = false,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable(initial, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(text = initial, selection = TextRange(initial.length)))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (helperText != null) {
                    Text(
                        helperText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { updated ->
                        value = if (updated.text.length <= 2_000) updated else updated.copy(text = updated.text.take(2_000))
                    },
                    label = { Text(label) },
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text.trim()) }, enabled = allowEmpty || value.text.isNotBlank()) { Text("Save") }
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
    scaleLocked: Boolean = false,
    publish: (PlanMeasurement, Quantity) -> Unit,
    onZoneChange: (PlanZone) -> Unit,
    onZoneDelete: (String) -> Unit,
    onMeasureEdit: (String) -> Unit,
    onMeasureDelete: (String) -> Unit,
    onPathDelete: (String) -> Unit = {},
    onMarkOpen: ((PlanMark) -> Unit)? = null,
    onMarkDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val settings = rememberAppSettings()
    val canEditScale = editable && !scaleLocked
    var renamingZone by remember { mutableStateOf<PlanZone?>(null) }
    var deletingZone by remember { mutableStateOf<PlanZone?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Page details") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (zone != null) {
                    val zoneSummary = remember(zone) { CalibrationDiagnostics.evaluateZone(zone) }
                    if (scaleLocked) {
                        Text(
                            EditorActions.SCALE_LOCKED_MESSAGE,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(zone.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${zoneSummary.status.label} · ${zoneSummary.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = statusColor(zoneSummary.status),
                            )
                        }
                        if (canEditScale) {
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
                                enabled = canEditScale,
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
                            onDelete = if (canEditScale) ({
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
                    val openLabel = when (mark.kind) {
                        PlanMarkKind.PHOTO -> "View"
                        PlanMarkKind.DETAIL_LINK -> "Open"
                        else -> null
                    }
                    ItemRow(
                        title = name,
                        detail = mark.text.takeIf { it.isNotBlank() } ?: mark.photoSha256?.let { "Photo (${it.take(8)}…)" },
                        actionLabel = openLabel,
                        onAction = if (openLabel != null && onMarkOpen != null) ({ onMarkOpen(mark) }) else null,
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
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
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
        if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
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
