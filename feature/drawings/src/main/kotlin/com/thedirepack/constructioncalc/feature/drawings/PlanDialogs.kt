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
import com.thedirepack.constructioncalc.core.model.PlanCalibrationLayer
import com.thedirepack.constructioncalc.core.model.PlanMark
import com.thedirepack.constructioncalc.core.model.PlanMeasurement
import com.thedirepack.constructioncalc.core.model.PlanScaleMode
import com.thedirepack.constructioncalc.core.model.PlanZone
import com.thedirepack.constructioncalc.core.model.SheetSize
import com.thedirepack.constructioncalc.core.model.StoredQuantity
import com.thedirepack.constructioncalc.core.model.stored
import com.thedirepack.constructioncalc.core.ui.components.MeasureField
import com.thedirepack.constructioncalc.core.ui.components.SettingsGroup
import com.thedirepack.constructioncalc.core.ui.format
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

private val scalePresets = listOf(
    "¼″ = 1′" to 48L,
    "⅛″ = 1′" to 96L,
    "½″ = 1′" to 24L,
    "1″ = 10′" to 120L,
    "1:20" to 20L,
    "1:50" to 50L,
    "1:100" to 100L,
    "1:200" to 200L,
)

private val scaleModeLabels = mapOf(
    PlanScaleMode.UNIFORM to "Uniform",
    PlanScaleMode.AXES to "X/Y",
    PlanScaleMode.AFFINE to "Skew",
    PlanScaleMode.DISTORTED to "2D Warp",
)

private fun presetWarning(pageSize: PageSize?): String? {
    val size = pageSize ?: return null
    val sheet = SheetSize.of(size.width.toDouble(), size.height.toDouble())
    return if (sheet == null) {
        "Page size doesn't match a standard drawing sheet — a preset scale may be inaccurate on a scanned or resized page. Measure a known dimension to check."
    } else {
        "Sheet matches ${sheet.label}. Verify with a known dimension if this set was printed at reduced size."
    }
}

@Composable
internal fun ZoneDialog(
    defaultName: String,
    pageSize: PageSize?,
    onDismiss: () -> Unit,
    onConfirm: (String, StoredQuantity?) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(defaultName) }
    var preset by rememberSaveable { mutableStateOf<Long?>(null) }
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
                FilterChip(preset == null, onClick = { preset = null }, label = { Text("Calibrate from known dimensions") })
                for ((label, ratio) in scalePresets) {
                    FilterChip(preset == ratio, onClick = { preset = ratio }, label = { Text(label) })
                }
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
    val fit = remember(calibration, page) { calibration.fit(page) }
    val activePageMode = calibration.modeFor(page)
    val hasPageOverride = calibration.pageModes.containsKey(page)
    val selectedPresetRatio = remember(calibration.preset) {
        calibration.preset?.toQuantity()?.value?.let { v ->
            scalePresets.firstOrNull { (_, ratio) -> v == Rational.of(ratio, 72) }?.second
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Calibration layer") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Document scale (all pages)", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        fit == null -> "Pick a preset or use Calibrate to tap known dimensions on any page."
                        calibration.preset != null && calibration.references.none { !it.check } -> "Preset scale active across all pages."
                        calibration.references.none { it.check } -> "Fitted from ${calibration.references.size} dimension(s). Add more anywhere to refine or check."
                        else -> "Fitted across document · check differences below."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Document preset", style = MaterialTheme.typography.labelLarge)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = calibration.preset == null,
                        onClick = { onCalibrationChange(calibration.copy(preset = null)) },
                        enabled = editable,
                        label = { Text("None") },
                    )
                    for ((label, ratio) in scalePresets) {
                        FilterChip(
                            selected = selectedPresetRatio == ratio,
                            onClick = { onCalibrationChange(calibration.copy(preset = Quantity.inches(Rational.of(ratio, 72)).stored())) },
                            enabled = editable,
                            label = { Text(label) },
                        )
                    }
                }
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
                        FilterChip(
                            selected = calibration.mode == mode,
                            onClick = { onCalibrationChange(calibration.copy(mode = mode)) },
                            enabled = editable,
                            label = { Text(label) },
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
                calibration.references.forEachIndexed { index, ref ->
                    val residual = fit?.residuals?.getOrNull(index)
                    ItemRow(
                        title = "P${ref.page + 1} · ${if (ref.check) "Check" else "Fit"} · ${settings.format(ref.length.toQuantity())}",
                        detail = residual?.let { delta ->
                            val difference = settings.format(Quantity.inches(Rational.of(abs(delta))))
                            val percent = 100 * delta / ref.length.toQuantity().value.toDouble()
                            "Diff ${if (delta < 0) "−" else "+"}$difference (${String.format(Locale.getDefault(), "%+.2f", percent)}%)"
                        },
                        onDelete = if (editable) ({
                            onCalibrationChange(calibration.copy(references = calibration.references.filterIndexed { i, _ -> i != index }))
                        }) else null,
                    )
                }
                if (pageZones.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Override zones on Page ${page + 1}", style = MaterialTheme.typography.titleMedium)
                    for (zone in pageZones) {
                        val zoneFit = remember(zone) { zone.fit() }
                        ItemRow(
                            title = "${zone.name} (${scaleModeLabels[zone.mode]})",
                            detail = "${zone.references.size} dimension(s)" + if (zoneFit == null) " · Uncalibrated" else "",
                            onDelete = if (editable) ({ onZoneDelete(zone.id) }) else null,
                        )
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            for ((mode, label) in scaleModeLabels) {
                                FilterChip(
                                    selected = zone.mode == mode,
                                    onClick = { onZoneChange(zone.copy(mode = mode)) },
                                    enabled = editable,
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
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
                        for ((mode, label) in scaleModeLabels) {
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
                            Text(quantity?.let { "≈ ${settings.format(it)} · Use" } ?: "Uncalibrated")
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

@Composable
internal fun SymbolDialog(
    onDismiss: () -> Unit,
    openSettings: () -> Unit,
    onSelect: (PlanSymbol) -> Unit,
) {
    val context = LocalContext.current
    val entries by produceState<List<PlanSymbol>>(emptyList(), context) {
        value = withContext(Dispatchers.IO) { PlanSymbols(context.applicationContext).list() }
    }
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
