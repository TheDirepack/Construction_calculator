package com.thedirepack.constructioncalc.feature.reellength

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Decimals
import com.thedirepack.constructioncalc.core.measure.Dimension
import com.thedirepack.constructioncalc.core.measure.Measure
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.StoredQuantity
import com.thedirepack.constructioncalc.core.model.ToolInputs
import com.thedirepack.constructioncalc.core.model.ToolTarget
import com.thedirepack.constructioncalc.core.model.stored
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.components.ChoiceField
import com.thedirepack.constructioncalc.core.ui.components.FormSection
import com.thedirepack.constructioncalc.core.ui.components.MeasureField
import com.thedirepack.constructioncalc.core.ui.components.NamedCableActions
import com.thedirepack.constructioncalc.core.ui.components.Notice
import com.thedirepack.constructioncalc.core.ui.components.NumberField
import com.thedirepack.constructioncalc.core.ui.components.ResultCard
import com.thedirepack.constructioncalc.core.ui.components.SegmentedChoice
import com.thedirepack.constructioncalc.core.ui.components.ToolScaffold
import com.thedirepack.constructioncalc.core.ui.components.UnitField
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.core.ui.show
import com.thedirepack.constructioncalc.core.ui.tool.ItemDraft
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost
import com.thedirepack.constructioncalc.core.ui.tool.toolViewModel
import com.thedirepack.constructioncalc.feature.reellength.domain.BuiltInReelPresets
import com.thedirepack.constructioncalc.feature.reellength.domain.ReelCableFavorite
import com.thedirepack.constructioncalc.feature.reellength.domain.ReelPreset
import com.thedirepack.constructioncalc.feature.reellength.domain.ReelPresetsPayload
import java.util.UUID
import kotlin.math.PI

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReelLengthScreen(host: ToolHost) {
    val vm: ReelLengthViewModel = toolViewModel(ReelLengthTool.ID, host)
    var pickingWire by remember { mutableStateOf(false) }
    var pickingReel by remember { mutableStateOf(false) }
    var creatingReel by remember { mutableStateOf(false) }
    var editingReel by remember { mutableStateOf<ReelGeometry?>(null) }
    var pickingSetup by remember { mutableStateOf(false) }
    var savingSetup by remember { mutableStateOf(false) }
    var lastReelLabel by remember { mutableStateOf<String?>(null) }
    var lastCableLabel by remember { mutableStateOf<String?>(null) }

    val inputs = vm.inputs
    val settings = rememberAppSettings()
    val (presetStore, presetsState) = rememberReelPresets()
    val output = remember(inputs, settings) { reelLengthOutput(inputs, settings) }
    val results = output.results
    val capacity = inputs.mode == ReelMode.CAPACITY
    val projects = LocalAppServices.current.projects
    val currentReel = remember(inputs) { extractReelGeometry(inputs) }
    val currentCableOdIn = remember(inputs.cableDiameter) { positiveInches(inputs.cableDiameter) }
    val starredSetups = remember(presetsState.favorites) {
        presetsState.favorites.filter { it.favorite }.take(4)
    }

    fun applyReelPreset(preset: ReelPreset) {
        vm.update {
            it.copy(
                flange = Quantity.inches(Rational.of(preset.flangeIn)).stored(),
                drumMeasurement = DrumMeasurement.DIAMETER,
                drum = Quantity.inches(Rational.of(preset.drumIn)).stored(),
                traverse = Quantity.inches(Rational.of(preset.traverseIn)).stored(),
                clearance = Quantity.inches(Rational.of(preset.clearanceIn)).stored(),
            )
        }
        lastReelLabel = preset.name
    }

    fun applyReelCableSetup(setup: ReelCableFavorite) {
        vm.update {
            it.copy(
                flange = Quantity.inches(Rational.of(setup.flangeIn)).stored(),
                drumMeasurement = DrumMeasurement.DIAMETER,
                drum = Quantity.inches(Rational.of(setup.drumIn)).stored(),
                traverse = Quantity.inches(Rational.of(setup.traverseIn)).stored(),
                clearance = Quantity.inches(Rational.of(setup.clearanceIn)).stored(),
                cableDiameter = Quantity.inches(Rational.of(setup.cableDiameterIn)).stored(),
            )
        }
        lastReelLabel = setup.name
        lastCableLabel = setup.cableName
    }

    // Opened from the Wire Pull Planner's "Check on reel" for a project run: the cable that run needs.
    LaunchedEffect(host.target) {
        (host.target as? ToolTarget.OfRun)?.let { t ->
            vm.prefill(t) { projects.get(t.projectId)?.let { reelInputsForRun(vm.inputs, it, t.runId) } }
        }
    }

    LaunchedEffect(results) {
        if (results.isNotEmpty()) host.publish(results, context = output.summary)
    }

    ToolScaffold(
        title = ReelLengthTool.title,
        host = host,
        onSave = if (results.isEmpty()) null else ({
            host.saveToProject(
                ItemDraft(
                    ReelLengthTool.ID,
                    if (capacity) "Reel capacity" else "Reel length",
                    ToolInputs.encode(inputs),
                    results,
                    output.summary,
                ),
            )
        }),
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SegmentedChoice(
                options = ReelMode.entries,
                selected = inputs.mode,
                onSelect = { m -> vm.update { it.copy(mode = m) } },
                label = "Find",
                optionLabel = { it.label },
            )

            FormSection("Reel") {
                if (presetsState.favoriteReels.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        presetsState.favoriteReels.forEach { preset ->
                            AssistChip(
                                onClick = { applyReelPreset(preset) },
                                label = { Text(preset.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                }
                FlowRow {
                    TextButton(onClick = { pickingReel = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Reel presets…")
                    }
                    if (currentReel != null) {
                        TextButton(onClick = { editingReel = currentReel }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Save reel…")
                        }
                    }
                }
                MeasureField(
                    "Flange diameter",
                    inputs.flange?.toQuantity(),
                    { q ->
                        lastReelLabel = null
                        vm.update { it.copy(flange = q?.stored()) }
                    },
                    supportingText = "Outside diameter of the flange",
                )
                ChoiceField(
                    options = DrumMeasurement.entries,
                    selected = inputs.drumMeasurement,
                    onSelect = { method ->
                        lastReelLabel = null
                        vm.update { it.copy(drumMeasurement = method) }
                    },
                    label = "Measure the core",
                    optionLabel = { it.label },
                )
                when (inputs.drumMeasurement) {
                    DrumMeasurement.DIAMETER -> MeasureField(
                        "Drum (core) diameter",
                        inputs.drum?.toQuantity(),
                        { q ->
                            lastReelLabel = null
                            vm.update { it.copy(drum = q?.stored()) }
                        },
                        supportingText = "Measure across the bare core through its center.",
                    )
                    DrumMeasurement.CIRCUMFERENCE -> MeasureField(
                        "Drum (core) circumference",
                        inputs.drumCircumference?.toQuantity(),
                        { q ->
                            lastReelLabel = null
                            vm.update { it.copy(drumCircumference = q?.stored()) }
                        },
                        supportingText = "Wrap a tape around the bare core. The app converts this to diameter.",
                    )
                    DrumMeasurement.FLANGE_TO_CORE -> MeasureField(
                        "Depth from flange rim to bare core",
                        inputs.flangeToCore?.toQuantity(),
                        { q ->
                            lastReelLabel = null
                            vm.update { it.copy(flangeToCore = q?.stored()) }
                        },
                        supportingText = "Measure straight in from the flange rim to the bare core. Enter the separate distance to the cable surface below.",
                    )
                }
                MeasureField(
                    "Traverse",
                    inputs.traverse?.toQuantity(),
                    { q ->
                        lastReelLabel = null
                        vm.update { it.copy(traverse = q?.stored()) }
                    },
                    supportingText = "Inside width between the flanges",
                )
            }

            FormSection("Cable") {
                MeasureField(
                    "Cable outside diameter",
                    inputs.cableDiameter?.toQuantity(),
                    { q ->
                        lastCableLabel = null
                        vm.update { it.copy(cableDiameter = q?.stored()) }
                    },
                )
                TextButton(onClick = { pickingWire = true }) { Text("From a wire size (NEC ${CodeRule.CONDUCTOR_DIMENSIONS.cite})…") }
                NamedCableActions(
                    onPick = { cable ->
                        lastCableLabel = cable.name
                        vm.update { it.copy(cableDiameter = Quantity.inches(Rational.of(cable.diameterIn)).stored()) }
                    },
                    current = namedCableFor(inputs),
                    showFavoriteChips = true,
                )
                if (pickingWire) {
                    WireDiameterDialog(
                        settings = settings,
                        onPick = { q ->
                            lastCableLabel = null
                            vm.update { it.copy(cableDiameter = q.stored()) }
                            pickingWire = false
                        },
                        onDismiss = { pickingWire = false },
                    )
                }
                if (capacity) {
                    MeasureField(
                        "Clearance below flange edge",
                        inputs.clearance?.toQuantity(),
                        { q -> vm.update { it.copy(clearance = q?.stored()) } },
                        supportingText = "Space to leave between the cable and the flange edge",
                    )
                } else {
                    MeasureField(
                        "Distance from flange edge",
                        inputs.distance?.toQuantity(),
                        { q -> vm.update { it.copy(distance = q?.stored()) } },
                        supportingText = "From the flange edge down to the top of the cable",
                    )
                }
                NumberField(
                    "Packing factor",
                    inputs.packingFactor,
                    { t -> vm.update { it.copy(packingFactor = t) } },
                    supportingText = "1 = tight, even layers. Use less than 1 for loose winding",
                )
            }

            FormSection("Reel + cable favorites") {
                if (starredSetups.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        starredSetups.forEach { setup ->
                            AssistChip(
                                onClick = { applyReelCableSetup(setup) },
                                label = { Text(setup.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                }
                FlowRow {
                    TextButton(onClick = { pickingSetup = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Reel + cable favorites…")
                    }
                    if (currentReel != null && currentCableOdIn != null) {
                        TextButton(onClick = { savingSetup = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Save reel + cable favorite…")
                        }
                    }
                }
            }

            FormSection("Pull") {
                MeasureField(
                    "Cable needed (optional)",
                    inputs.cableNeeded?.toQuantity(),
                    { q -> vm.update { it.copy(cableNeeded = q?.stored()) } },
                    supportingText = "Per conductor. Pick a run's \"cable needed\" from the Wire Pull Planner to check this reel.",
                )
            }

            ResultCard(
                results,
                notices = output.notices + if (results.isEmpty()) parsedMissing(inputs) else emptyList(),
            )
        }
    }

    if (pickingReel) {
        ReelPresetPickerDialog(
            reels = presetsState.allReels,
            favoriteIds = presetsState.payload.favoriteReelIds,
            settings = settings,
            onPick = { preset ->
                applyReelPreset(preset)
                pickingReel = false
            },
            onToggleFavorite = { presetStore.toggleFavoriteReel(it.id) },
            onDelete = { presetStore.deleteCustomReel(it.id) },
            onNew = {
                pickingReel = false
                creatingReel = true
            },
            onDismiss = { pickingReel = false },
        )
    }

    if (creatingReel || editingReel != null) {
        ReelPresetEditorDialog(
            initial = editingReel,
            onSave = { preset ->
                presetStore.saveCustomReel(preset)
                if (creatingReel) applyReelPreset(preset)
                creatingReel = false
                editingReel = null
            },
            onDismiss = {
                creatingReel = false
                editingReel = null
            },
        )
    }

    if (pickingSetup) {
        ReelCableFavoritePickerDialog(
            favorites = presetsState.favorites,
            settings = settings,
            onPick = { setup ->
                applyReelCableSetup(setup)
                pickingSetup = false
            },
            onToggleFavorite = { presetStore.toggleFavoriteSetup(it.id) },
            onDelete = { presetStore.deleteFavoriteSetup(it.id) },
            onSaveCurrent = if (currentReel != null && currentCableOdIn != null) {
                {
                    pickingSetup = false
                    savingSetup = true
                }
            } else null,
            onDismiss = { pickingSetup = false },
        )
    }

    if (savingSetup && currentReel != null && currentCableOdIn != null) {
        ReelCableFavoriteEditorDialog(
            reel = currentReel,
            cableDiameterIn = currentCableOdIn,
            initialReelLabel = lastReelLabel,
            initialCableLabel = lastCableLabel,
            settings = settings,
            onSave = { setup ->
                val existing = presetsState.favorites.firstOrNull {
                    it.name.trim().equals(setup.name.trim(), ignoreCase = true)
                }
                val toSave = if (existing != null && !setup.favorite) {
                    setup.copy(favorite = existing.favorite)
                } else {
                    setup
                }
                presetStore.saveFavoriteSetup(toSave)
                lastReelLabel = toSave.name
                lastCableLabel = toSave.cableName
                savingSetup = false
            },
            onDismiss = { savingSetup = false },
        )
    }
}

private data class ReelGeometry(
    val flangeIn: Double,
    val drumIn: Double,
    val traverseIn: Double,
    val clearanceIn: Double,
)

private fun positiveInches(q: StoredQuantity?): Double? =
    q?.takeIf { it.dimension == Dimension.LENGTH }
        ?.toQuantity()
        ?.value
        ?.toDouble()
        ?.takeIf { it.isFinite() && it > 0.0 }

private fun extractReelGeometry(inputs: ReelLengthInputs): ReelGeometry? {
    val flange = positiveInches(inputs.flange) ?: return null
    val drum = when (inputs.drumMeasurement) {
        DrumMeasurement.DIAMETER -> positiveInches(inputs.drum)
        DrumMeasurement.CIRCUMFERENCE -> positiveInches(inputs.drumCircumference)?.div(PI)
        DrumMeasurement.FLANGE_TO_CORE -> positiveInches(inputs.flangeToCore)?.let { depth ->
            val core = flange - 2.0 * depth
            if (core.isFinite() && core > 0.0) core else null
        }
    } ?: return null
    if (flange <= drum) return null
    val traverse = positiveInches(inputs.traverse) ?: return null
    val rawClearance = inputs.clearance
        ?.takeIf { it.dimension == Dimension.LENGTH }
        ?.toQuantity()
        ?.value
        ?.toDouble()
        ?.takeIf { it.isFinite() && it >= 0.0 }
    val clearance = when {
        rawClearance != null && flange - 2.0 * rawClearance > drum -> rawClearance
        inputs.mode == ReelMode.REMAINING -> 0.0
        else -> rawClearance ?: 2.0
    }
    return ReelGeometry(flangeIn = flange, drumIn = drum, traverseIn = traverse, clearanceIn = clearance)
}

@Composable
private fun ReelPresetPickerDialog(
    reels: List<ReelPreset>,
    favoriteIds: Set<String>,
    settings: AppSettings,
    onPick: (ReelPreset) -> Unit,
    onToggleFavorite: (ReelPreset) -> Unit,
    onDelete: (ReelPreset) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    val hasFavorites = remember(reels, favoriteIds) { reels.any { it.id in favoriteIds } }
    var favoritesOnly by remember { mutableStateOf(false) }
    val shown = remember(reels, favoriteIds, favoritesOnly, hasFavorites) {
        ReelPresetsPayload.sortedReels(
            if (favoritesOnly && hasFavorites) reels.filter { it.id in favoriteIds } else reels,
            favoriteIds,
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reel presets") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    BuiltInReelPresets.NOMINAL_NOTE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (hasFavorites) {
                    FilterChip(
                        selected = favoritesOnly,
                        onClick = { favoritesOnly = !favoritesOnly },
                        label = { Text("Favorites only") },
                    )
                }
                shown.forEach { preset ->
                    val isFavorite = preset.id in favoriteIds
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onPick(preset) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(preset.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    describeReel(preset, settings),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        IconButton(onClick = { onToggleFavorite(preset) }) {
                            Icon(
                                painterResource(if (isFavorite) R.drawable.ic_star else R.drawable.ic_star_border),
                                contentDescription = if (isFavorite) "Remove ${preset.name} from favorites" else "Favorite ${preset.name}",
                                tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!preset.builtIn) {
                            IconButton(onClick = { onDelete(preset) }) {
                                Icon(painterResource(R.drawable.ic_delete), contentDescription = "Delete ${preset.name}")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onNew) { Text("New reel") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun describeReel(preset: ReelPreset, settings: AppSettings): String = buildList {
    add("Flange ${settings.show(Measure.SMALL_LENGTH, preset.flangeIn)}")
    add("Drum ${settings.show(Measure.SMALL_LENGTH, preset.drumIn)}")
    add("Traverse ${settings.show(Measure.SMALL_LENGTH, preset.traverseIn)}")
    add("Clearance ${settings.show(Measure.SMALL_LENGTH, preset.clearanceIn)}")
    if (!preset.builtIn && preset.note.isNotBlank()) add(preset.note)
}.joinToString(" · ")

private fun parsePositiveDecimal(s: String): Double? =
    Decimals.parse(s)?.takeIf { it.isFinite() && it > 0.0 }

private fun parseNonNegativeDecimal(s: String): Double? =
    if (s.isBlank()) 0.0 else Decimals.parse(s)?.takeIf { it.isFinite() && it >= 0.0 }

private fun reelGeometryIssue(flange: Double?, drum: Double?, clearance: Double?): String? = when {
    flange != null && drum != null && flange <= drum -> "Flange diameter must be larger than drum diameter."
    flange != null && drum != null && clearance != null && flange - 2.0 * clearance <= drum ->
        "Clearance leaves no room for cable above the drum."
    else -> null
}

@Composable
private fun ReelGeometryFields(
    flange: String,
    onFlangeChange: (String) -> Unit,
    drum: String,
    onDrumChange: (String) -> Unit,
    traverse: String,
    onTraverseChange: (String) -> Unit,
    clearance: String,
    onClearanceChange: (String) -> Unit,
    geometryIssue: String?,
) {
    UnitField("Flange diameter", Measure.SMALL_LENGTH, flange, onFlangeChange)
    UnitField("Drum diameter", Measure.SMALL_LENGTH, drum, onDrumChange)
    UnitField("Traverse", Measure.SMALL_LENGTH, traverse, onTraverseChange)
    UnitField("Clearance below flange", Measure.SMALL_LENGTH, clearance, onClearanceChange)
    if (geometryIssue != null) {
        Text(
            geometryIssue,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ReelPresetEditorDialog(
    initial: ReelGeometry?,
    onSave: (ReelPreset) -> Unit,
    onDismiss: () -> Unit,
) {
    fun text(v: Double?) = v?.let { Decimals.trim(it, 6) }.orEmpty()
    var name by remember { mutableStateOf("") }
    var flange by remember { mutableStateOf(text(initial?.flangeIn)) }
    var drum by remember { mutableStateOf(text(initial?.drumIn)) }
    var traverse by remember { mutableStateOf(text(initial?.traverseIn)) }
    var clearance by remember { mutableStateOf(text(initial?.clearanceIn ?: 2.0)) }
    var note by remember { mutableStateOf("") }

    val f = parsePositiveDecimal(flange)
    val d = parsePositiveDecimal(drum)
    val w = parsePositiveDecimal(traverse)
    val c = parseNonNegativeDecimal(clearance)
    val built: ReelPreset? = if (name.isNotBlank() && f != null && d != null && w != null && c != null) {
        runCatching {
            ReelPreset(
                id = UUID.randomUUID().toString(),
                name = name.trim(),
                flangeIn = f,
                drumIn = d,
                traverseIn = w,
                clearanceIn = c,
                builtIn = false,
                note = note.trim(),
            )
        }.getOrNull()
    } else null

    val geometryIssue = reelGeometryIssue(f, d, c)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New reel preset" else "Save reel preset") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    label = { Text("Reel name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ReelGeometryFields(
                    flange = flange,
                    onFlangeChange = { flange = it },
                    drum = drum,
                    onDrumChange = { drum = it },
                    traverse = traverse,
                    onTraverseChange = { traverse = it },
                    clearance = clearance,
                    onClearanceChange = { clearance = it },
                    geometryIssue = geometryIssue,
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(120) },
                    label = { Text("Note (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = built != null, onClick = { built?.let(onSave) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ReelCableFavoritePickerDialog(
    favorites: List<ReelCableFavorite>,
    settings: AppSettings,
    onPick: (ReelCableFavorite) -> Unit,
    onToggleFavorite: (ReelCableFavorite) -> Unit,
    onDelete: (ReelCableFavorite) -> Unit,
    onSaveCurrent: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val hasStarred = remember(favorites) { favorites.any { it.favorite } }
    var starredOnly by remember { mutableStateOf(false) }
    val shown = remember(favorites, starredOnly, hasStarred) {
        ReelPresetsPayload.sortedFavorites(if (starredOnly && hasStarred) favorites.filter { it.favorite } else favorites)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reel + cable favorites") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (hasStarred) {
                    FilterChip(
                        selected = starredOnly,
                        onClick = { starredOnly = !starredOnly },
                        label = { Text("Favorites only") },
                    )
                }
                if (favorites.isEmpty()) {
                    Text(
                        "No saved reel + cable setups yet. Enter a reel and cable OD, then save the pair here while keeping your pull's cable needed.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                shown.forEach { setup ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onPick(setup) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(setup.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    describeReelCableFavorite(setup, settings),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        IconButton(onClick = { onToggleFavorite(setup) }) {
                            Icon(
                                painterResource(if (setup.favorite) R.drawable.ic_star else R.drawable.ic_star_border),
                                contentDescription = if (setup.favorite) "Remove ${setup.name} from favorites" else "Favorite ${setup.name}",
                                tint = if (setup.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onDelete(setup) }) {
                            Icon(painterResource(R.drawable.ic_delete), contentDescription = "Delete ${setup.name}")
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (onSaveCurrent != null) {
                TextButton(onClick = onSaveCurrent) { Text("Save current…") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun describeReelCableFavorite(setup: ReelCableFavorite, settings: AppSettings): String =
    "${setup.cableName} (${settings.show(Measure.SMALL_LENGTH, setup.cableDiameterIn)}) · " +
        "${settings.show(Measure.SMALL_LENGTH, setup.flangeIn)} × ${settings.show(Measure.SMALL_LENGTH, setup.traverseIn)} reel"

@Composable
private fun ReelCableFavoriteEditorDialog(
    reel: ReelGeometry,
    cableDiameterIn: Double,
    initialReelLabel: String?,
    initialCableLabel: String?,
    settings: AppSettings,
    onSave: (ReelCableFavorite) -> Unit,
    onDismiss: () -> Unit,
) {
    fun text(v: Double) = Decimals.trim(v, 6)
    val defaultCableName = initialCableLabel?.takeIf { it.isNotBlank() }
        ?: "Cable (${settings.show(Measure.SMALL_LENGTH, cableDiameterIn)})"
    val defaultSetupName = if (!initialReelLabel.isNullOrBlank() && !initialCableLabel.isNullOrBlank()) {
        "$initialReelLabel + $initialCableLabel".take(60)
    } else ""

    var name by remember { mutableStateOf(defaultSetupName) }
    var cableName by remember { mutableStateOf(defaultCableName) }
    var flange by remember { mutableStateOf(text(reel.flangeIn)) }
    var drum by remember { mutableStateOf(text(reel.drumIn)) }
    var traverse by remember { mutableStateOf(text(reel.traverseIn)) }
    var clearance by remember { mutableStateOf(text(reel.clearanceIn)) }
    var cableOd by remember { mutableStateOf(text(cableDiameterIn)) }

    val f = parsePositiveDecimal(flange)
    val d = parsePositiveDecimal(drum)
    val w = parsePositiveDecimal(traverse)
    val c = parseNonNegativeDecimal(clearance)
    val od = parsePositiveDecimal(cableOd)
    val built: ReelCableFavorite? = if (name.isNotBlank() && cableName.isNotBlank() && f != null && d != null && w != null && c != null && od != null) {
        runCatching {
            ReelCableFavorite(
                id = UUID.randomUUID().toString(),
                name = name.trim(),
                flangeIn = f,
                drumIn = d,
                traverseIn = w,
                clearanceIn = c,
                cableName = cableName.trim(),
                cableDiameterIn = od,
            )
        }.getOrNull()
    } else null

    val geometryIssue = reelGeometryIssue(f, d, c)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save reel + cable favorite") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    label = { Text("Setup name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = cableName,
                    onValueChange = { cableName = it.take(60) },
                    label = { Text("Cable name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                UnitField("Cable outside diameter", Measure.SMALL_LENGTH, cableOd, { cableOd = it })
                ReelGeometryFields(
                    flange = flange,
                    onFlangeChange = { flange = it },
                    drum = drum,
                    onDrumChange = { drum = it },
                    traverse = traverse,
                    onTraverseChange = { traverse = it },
                    clearance = clearance,
                    onClearanceChange = { clearance = it },
                    geometryIssue = geometryIssue,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = built != null, onClick = { built?.let(onSave) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A hint listing what's still to fill in, when there's no error to show. */
private fun parsedMissing(inputs: ReelLengthInputs): List<Notice> {
    val parsed = inputs.parse()
    if (parsed.missing.isEmpty() || parsed.problems.isNotEmpty()) return emptyList()
    return listOf(
        Notice("Still needed: " + parsed.missing.joinToString(", ") + "."),
    )
}
