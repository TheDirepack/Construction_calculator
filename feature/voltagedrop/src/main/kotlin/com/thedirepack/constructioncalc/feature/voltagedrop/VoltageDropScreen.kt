package com.thedirepack.constructioncalc.feature.voltagedrop

import com.thedirepack.constructioncalc.core.electrical.Table9Conduit
import com.thedirepack.constructioncalc.core.electrical.AcPhases
import com.thedirepack.constructioncalc.core.electrical.ConductorResistance
import com.thedirepack.constructioncalc.core.electrical.Stranding
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.model.ToolInputs
import com.thedirepack.constructioncalc.core.model.ValueKind
import com.thedirepack.constructioncalc.core.model.stored
import com.thedirepack.constructioncalc.core.ui.components.ChoiceField
import com.thedirepack.constructioncalc.core.ui.components.FormSection
import com.thedirepack.constructioncalc.core.ui.components.MeasureField
import com.thedirepack.constructioncalc.core.ui.components.NumberField
import com.thedirepack.constructioncalc.core.ui.components.ResultCard
import com.thedirepack.constructioncalc.core.ui.components.SegmentedChoice
import com.thedirepack.constructioncalc.core.ui.components.ToolScaffold
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.core.ui.tool.ItemDraft
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost
import com.thedirepack.constructioncalc.feature.voltagedrop.domain.Method
import com.thedirepack.constructioncalc.feature.voltagedrop.domain.VoltageDrop

private const val CUSTOM_VOLTAGE = 0

@Composable
fun VoltageDropScreen(host: ToolHost) {
    val opened = host.openedItem
    val vm: VoltageDropViewModel = viewModel(key = "voltage-drop:${opened?.id}")
    LaunchedEffect(opened) {
        opened?.takeIf { it.toolId == VoltageDropTool.ID }?.let(vm::restore)
    }
    val inputs = vm.inputs
    val settings = rememberAppSettings()
    val output = remember(inputs, settings) { voltageDropOutput(inputs, settings) }
    val results = output.results

    LaunchedEffect(results) {
        if (results.isNotEmpty()) host.publish(results, context = output.summary)
    }

    ToolScaffold(
        title = VoltageDropTool.title,
        host = host,
        onSave = if (results.isEmpty()) null else ({
            host.saveToProject(
                ItemDraft(VoltageDropTool.ID, "Voltage drop", ToolInputs.encode(inputs), results, output.summary),
            )
        }),
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FormSection("Circuit") {
                SegmentedChoice(
                    options = AcPhases.entries,
                    selected = inputs.system,
                    onSelect = { s -> vm.update { it.copy(system = s) } },
                    label = "System",
                    optionLabel = { it.label },
                )
                ChoiceField(
                    label = "Voltage",
                    options = VoltageDropInputs.VOLTAGE_PRESETS + CUSTOM_VOLTAGE,
                    selected = inputs.voltagePreset.takeIf { it in VoltageDropInputs.VOLTAGE_PRESETS } ?: CUSTOM_VOLTAGE,
                    onSelect = { v -> vm.update { it.copy(voltagePreset = v) } },
                    optionLabel = { if (it == CUSTOM_VOLTAGE) "Custom…" else "$it V" },
                )
                if (inputs.voltagePreset !in VoltageDropInputs.VOLTAGE_PRESETS) {
                    NumberField(
                        "Custom voltage",
                        inputs.customVoltage,
                        { t -> vm.update { it.copy(customVoltage = t) } },
                        unit = "V",
                        pickKinds = setOf(ValueKind.VOLTAGE),
                    )
                }
                NumberField(
                    "Load current",
                    inputs.amps,
                    { t -> vm.update { it.copy(amps = t) } },
                    unit = "A",
                    pickKinds = setOf(ValueKind.CURRENT),
                )
                MeasureField(
                    "One-way length",
                    inputs.length?.toQuantity(),
                    { q -> vm.update { it.copy(length = q?.stored()) } },
                    supportingText = "Source to load, one direction",
                )
            }

            FormSection("Conductor") {
                SegmentedChoice(
                    options = ConductorMaterial.entries,
                    selected = inputs.conductorMaterial,
                    onSelect = { m -> vm.update { it.copy(material = m.name) } },
                    label = "Material",
                    optionLabel = { it.label },
                )
                ChoiceField(
                    label = "Conductor size",
                    options = VoltageDrop.supportedSizes(inputs.conductorMaterial, inputs.method),
                    selected = inputs.conductorSize,
                    onSelect = { s -> vm.update { it.copy(size = s.label) } },
                    optionLabel = { it.label },
                )
                ChoiceField(
                    label = "Parallel sets per phase",
                    options = (1..VoltageDrop.MAX_SETS).toList(),
                    selected = inputs.parallelSets.coerceIn(1, VoltageDrop.MAX_SETS),
                    onSelect = { n -> vm.update { it.copy(parallelSets = n) } },
                    optionLabel = { if (it == 1) "1 (no parallel)" else "$it" },
                )
            }

            FormSection("Method") {
                ChoiceField(
                    label = "Calculation",
                    options = Method.entries,
                    selected = inputs.method,
                    onSelect = { m -> vm.update { it.copy(method = m) } },
                    optionLabel = { it.label },
                )
                if (inputs.method == Method.AC_TABLE_9) {
                    SegmentedChoice(
                        options = Table9Conduit.entries,
                        selected = inputs.conduit,
                        onSelect = { c -> vm.update { it.copy(conduit = c) } },
                        label = "Conduit",
                        optionLabel = { it.label },
                    )
                    NumberField(
                        "Power factor",
                        inputs.powerFactor,
                        { t -> vm.update { it.copy(powerFactor = t) } },
                    )
                }
                if (inputs.method == Method.DC_TABLE_8 && ConductorResistance.hasSolidOption(inputs.conductorSize)) {
                    SegmentedChoice(
                        options = Stranding.entries,
                        selected = inputs.stranding,
                        onSelect = { st -> vm.update { it.copy(stranding = st) } },
                        label = "Conductor",
                        optionLabel = { it.label },
                    )
                }
                NumberField(
                    "Target maximum drop",
                    inputs.targetPercent,
                    { t -> vm.update { it.copy(targetPercent = t) } },
                    unit = "%",
                    supportingText = "3% is the usual branch-circuit recommendation",
                )
            }

            ResultCard(results, notices = output.notices)
        }
    }
}
