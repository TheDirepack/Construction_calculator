package com.thedirepack.constructioncalc.feature.reellength

import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.ui.tool.toolViewModel
import com.thedirepack.constructioncalc.core.model.ToolInputs
import com.thedirepack.constructioncalc.core.model.stored
import com.thedirepack.constructioncalc.core.ui.components.ChoiceField
import com.thedirepack.constructioncalc.core.ui.components.FormSection
import com.thedirepack.constructioncalc.core.ui.components.MeasureField
import com.thedirepack.constructioncalc.core.ui.components.Notice
import com.thedirepack.constructioncalc.core.ui.components.NumberField
import com.thedirepack.constructioncalc.core.ui.components.ResultCard
import com.thedirepack.constructioncalc.core.ui.components.SegmentedChoice
import com.thedirepack.constructioncalc.core.ui.components.ToolScaffold
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.core.ui.tool.ItemDraft
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost

@Composable
fun ReelLengthScreen(host: ToolHost) {
    val vm: ReelLengthViewModel = toolViewModel(ReelLengthTool.ID, host)
    var pickingWire by remember { mutableStateOf(false) }
    val inputs = vm.inputs
    val settings = rememberAppSettings()
    val output = remember(inputs, settings) { reelLengthOutput(inputs, settings) }
    val results = output.results
    val capacity = inputs.mode == ReelMode.CAPACITY

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
                MeasureField(
                    "Flange diameter",
                    inputs.flange?.toQuantity(),
                    { q -> vm.update { it.copy(flange = q?.stored()) } },
                    supportingText = "Outside diameter of the flange",
                )
                ChoiceField(
                    options = DrumMeasurement.entries,
                    selected = inputs.drumMeasurement,
                    onSelect = { method -> vm.update { it.copy(drumMeasurement = method) } },
                    label = "Measure the core",
                    optionLabel = { it.label },
                )
                when (inputs.drumMeasurement) {
                    DrumMeasurement.DIAMETER -> MeasureField(
                        "Drum (core) diameter",
                        inputs.drum?.toQuantity(),
                        { q -> vm.update { it.copy(drum = q?.stored()) } },
                        supportingText = "Measure across the bare core through its center.",
                    )
                    DrumMeasurement.CIRCUMFERENCE -> MeasureField(
                        "Drum (core) circumference",
                        inputs.drumCircumference?.toQuantity(),
                        { q -> vm.update { it.copy(drumCircumference = q?.stored()) } },
                        supportingText = "Wrap a tape around the bare core. The app converts this to diameter.",
                    )
                    DrumMeasurement.FLANGE_TO_CORE -> MeasureField(
                        "Depth from flange rim to bare core",
                        inputs.flangeToCore?.toQuantity(),
                        { q -> vm.update { it.copy(flangeToCore = q?.stored()) } },
                        supportingText = "Measure straight in from the flange rim to the bare core. Enter the separate distance to the cable surface below.",
                    )
                }
                MeasureField(
                    "Traverse",
                    inputs.traverse?.toQuantity(),
                    { q -> vm.update { it.copy(traverse = q?.stored()) } },
                    supportingText = "Inside width between the flanges",
                )
            }

            FormSection("Cable") {
                MeasureField(
                    "Cable outside diameter",
                    inputs.cableDiameter?.toQuantity(),
                    { q -> vm.update { it.copy(cableDiameter = q?.stored()) } },
                )
                TextButton(onClick = { pickingWire = true }) { Text("From a wire size (NEC Table 5)…") }
                if (pickingWire) {
                    WireDiameterDialog(
                        onPick = { q ->
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
}

/** A hint listing what's still to fill in, when there's no error to show. */
private fun parsedMissing(inputs: ReelLengthInputs): List<Notice> {
    val parsed = inputs.parse()
    if (parsed.missing.isEmpty() || parsed.problems.isNotEmpty()) return emptyList()
    return listOf(
        Notice("Still needed: " + parsed.missing.joinToString(", ") + "."),
    )
}
